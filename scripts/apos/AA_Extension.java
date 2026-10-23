package scripting.apos;

import bot.Main;
import com.openrsc.client.entityhandling.instances.Item;
import compatibility.apos.Script;
import controller.Controller;
import java.util.List;
import orsc.ORSCharacter;
import orsc.enumerations.ORSCharacterDirection;

/**
 * Stand-in for the raw APOS client object ({@code Extension}) that the AA_* scripts access through
 * the {@code bot} field of {@link AA_Script}. Only the read-only accessors and simple actions used
 * by the AA scripts are provided, each implemented through IdleRSC's {@link Controller}. Raw packet
 * building (createPacket/put*) is intentionally not provided: callers were rewritten to use the
 * Controller/compatibility API, which knows the OpenRSC packet layouts.
 *
 * <p>Coordinates: APOS scripts work with region-local coordinates plus getAreaX()/getAreaY(). Here
 * every "local" coordinate is already a world tile coordinate and getAreaX()/getAreaY() return 0,
 * so the usual {@code local + area} and {@code world - area} arithmetic in the scripts still works.
 *
 * <p>Mob arguments ({@code Object}) are {@link ORSCharacter} instances.
 */
public final class AA_Extension {
  /**
   * Index passed to onGroundItemDespawned(); the accessors below then report the despawned item.
   */
  static final int DESPAWNED_GROUND_ITEM_INDEX = -2;

  private final Script script;
  private int[] despawnedGroundItem; // {id, x, y} while dispatching onGroundItemDespawned

  AA_Extension(final Script script) {
    this.script = script;
  }

  private static Controller c() {
    return Main.getController();
  }

  private static ORSCharacter mob(final Object o) {
    return (ORSCharacter) o;
  }

  void setDespawnedGroundItem(final int[] item) {
    despawnedGroundItem = item;
  }

  // --- region / position ---

  public int getAreaX() {
    return 0;
  }

  public int getAreaY() {
    return 0;
  }

  public int getLocalX() {
    return c().currentX();
  }

  public int getLocalY() {
    return c().currentY();
  }

  /**
   * Tile the local player is on (IdleRSC exposes no walk destination; used for "same tile" checks).
   */
  public int getPlayerWaypointX() {
    return c().currentX();
  }

  public int getPlayerWaypointY() {
    return c().currentY();
  }

  public boolean isLoggedIn() {
    return c().isLoggedIn();
  }

  public boolean isDeathScreen() {
    final Object timeout = c().getMudClientValue("deathScreenTimeout");
    return timeout instanceof Integer && (Integer) timeout != 0;
  }

  // --- walking ---

  /** Walks towards the given tile without blocking (APOS walkDirectly). */
  public void walkDirectly(final int x, final int y, final boolean action) {
    c().walkToAsync(x, y, 0);
  }

  public void setActionInd(final int i) {}

  // --- stats ---

  public int getBaseLevel(final int skill) {
    return c().getBaseStat(skill);
  }

  public int getCurrentLevel(final int skill) {
    return c().getCurrentStat(skill);
  }

  public double getExperience(final int skill) {
    return c().getPlayerExperience(skill);
  }

  public double getAccurateFatigue() {
    return c().getFatigue();
  }

  public int getCombatStyle() {
    return c().getFightMode();
  }

  /** Sets and sends the combat style. */
  public void setCombatStyle(final int style) {
    c().setFightMode(style);
  }

  public boolean isPrayerEnabled(final int id) {
    return c().isPrayerOn(id);
  }

  // --- option menu ---

  public boolean isDialogVisible() {
    return c().isInOptionMenu();
  }

  public int getDialogOptionCount() {
    return c().getOptionMenuCount();
  }

  // --- inventory ---

  public int getInventorySize() {
    return c().getInventoryItemCount();
  }

  public int getInventoryId(final int slot) {
    return c().getInventorySlotItemId(slot);
  }

  public int getInventoryStack(final int slot) {
    final List<Item> items = c().getInventoryItems();
    if (slot < 0 || slot >= items.size() || items.get(slot) == null) return 0;
    return items.get(slot).getAmount();
  }

  public boolean isEquipped(final int slot) {
    return c().isEquipped(slot);
  }

  // --- bank ---

  public boolean isBankVisible() {
    return c().isInBank();
  }

  /** Only hides the bank client-side, like APOS. Use closeBank() to also tell the server. */
  public void setBankVisible(final boolean visible) {
    if (!visible) c().closeBank();
  }

  /**
   * Closes the bank the way the client's close button does: hides it and tells the server (opcode
   * 212, no payload), so the server no longer considers the bank open.
   */
  public void closeBank() {
    final Controller c = c();
    c.getMud().setShowDialogBank(false);
    while (c.getMud().packetHandler.getClientStream().hasFinishedPackets()) c.sleep(1);
    c.getMud().packetHandler.getClientStream().newPacket(212);
    c.getMud().packetHandler.getClientStream().finishPacket();
  }

  public int getBankSize() {
    return script.getBankSize();
  }

  public int getBankId(final int i) {
    return script.getBankId(i);
  }

  public int getBankStack(final int i) {
    return script.getBankStack(i);
  }

  // --- shop ---

  public boolean isShopVisible() {
    return c().isInShop();
  }

  public int getShopSize() {
    return c().getShopItemsCount();
  }

  public int getShopId(final int i) {
    final List<Item> items = c().getShopItems();
    if (i < 0 || i >= items.size()) return -1;
    return items.get(i).getCatalogID();
  }

  public int getShopStack(final int i) {
    final List<Item> items = c().getShopItems();
    if (i < 0 || i >= items.size()) return 0;
    return items.get(i).getAmount();
  }

  // --- trade ---

  public boolean isInTradeOffer() {
    return c().isInTrade() && !c().isInTradeConfirmation();
  }

  public boolean isInTradeConfirm() {
    return c().isInTradeConfirmation();
  }

  /** Client-side only. */
  public void setInTradeOffer(final boolean b) {
    if (!b) c().getMud().setShowDialogTrade(false);
  }

  /** Client-side only. */
  public void setInTradeConfirm(final boolean b) {
    c().getMud().setShowDialogTradeConfirm(b);
  }

  public boolean hasLocalAcceptedTrade() {
    return Boolean.TRUE.equals(c().getMudClientValue("tradeAccepted"));
  }

  public boolean hasLocalConfirmedTrade() {
    return Boolean.TRUE.equals(c().getMudClientValue("tradeConfirmAccepted"));
  }

  public boolean hasRemoteAcceptedTrade() {
    return c().isTradeRecipientAccepting();
  }

  /** Client-side only (APOS field Mi). */
  public void setLocalAcceptedTrade(final boolean b) {
    c().getMud().setTradeAccepted(b);
  }

  /** Client-side only (APOS field Vi). */
  public void setLocalConfirmedTrade(final boolean b) {
    c().getMud().setTradeConfirmAccepted(b);
  }

  public int getLocalTradeItemCount() {
    return c().getLocalTradeItemsCount();
  }

  public int getLocalTradeItemId(final int i) {
    return script.getLocalTradeItemId(i);
  }

  public int getLocalTradeItemStack(final int i) {
    return script.getLocalTradeItemStack(i);
  }

  public void offerItemTrade(final int slot, final int amount) {
    script.offerItemTrade(slot, amount);
  }

  // --- ground items ---

  public int getGroundItemCount() {
    return c().getGroundItemsCount();
  }

  public int getGroundItemId(final int i) {
    if (i == DESPAWNED_GROUND_ITEM_INDEX && despawnedGroundItem != null) {
      return despawnedGroundItem[0];
    }
    return script.getGroundItemId(i);
  }

  public int getGroundItemLocalX(final int i) {
    if (i == DESPAWNED_GROUND_ITEM_INDEX && despawnedGroundItem != null) {
      return despawnedGroundItem[1];
    }
    return script.getItemX(i);
  }

  public int getGroundItemLocalY(final int i) {
    if (i == DESPAWNED_GROUND_ITEM_INDEX && despawnedGroundItem != null) {
      return despawnedGroundItem[2];
    }
    return script.getItemY(i);
  }

  // --- objects / boundaries ---

  public int getObjectCount() {
    return script.getObjectCount();
  }

  public int getObjectId(final int i) {
    return script.getObjectId(i);
  }

  public int getObjectLocalX(final int i) {
    return script.getObjectX(i);
  }

  public int getObjectLocalY(final int i) {
    return script.getObjectY(i);
  }

  public int getBoundCount() {
    return script.getWallObjectCount();
  }

  public int getBoundId(final int i) {
    return script.getWallObjectId(i);
  }

  public int getBoundLocalX(final int i) {
    return script.getWallObjectX(i);
  }

  public int getBoundLocalY(final int i) {
    return script.getWallObjectY(i);
  }

  public int getBoundDir(final int i) {
    if (i < 0 || i >= c().getWallObjectsCount()) return -1;
    return c().getWallObjectsDirections()[i];
  }

  // --- characters ---

  public Object getPlayer() {
    return c().getPlayer();
  }

  public int getPlayerCount() {
    return c().getPlayerCount();
  }

  public Object getPlayer(final int index) {
    final List<ORSCharacter> players = c().getPlayers();
    if (index < 0 || index >= players.size()) return null;
    return players.get(index);
  }

  public String getPlayerName(final Object player) {
    final ORSCharacter p = mob(player);
    if (p == null) return null;
    return p.displayName != null ? p.displayName : p.accountName;
  }

  public int getPlayerCombatLevel(final Object player) {
    return mob(player).level;
  }

  public int getNpcCount() {
    return c().getNpcCount();
  }

  public Object getNpc(final int index) {
    final List<ORSCharacter> npcs = c().getNpcs();
    if (index < 0 || index >= npcs.size()) return null;
    return npcs.get(index);
  }

  public int getNpcId(final Object npc) {
    return mob(npc).npcId;
  }

  public int getMobServerIndex(final Object mob) {
    return mob(mob).serverIndex;
  }

  public int getMobLocalX(final Object mob) {
    return c().convertX(mob(mob).currentX);
  }

  public int getMobLocalY(final Object mob) {
    return c().convertZ(mob(mob).currentZ);
  }

  public int getMobDirection(final Object mob) {
    final ORSCharacterDirection dir = c().getCharacterDirection(mob(mob));
    return dir == null ? -1 : dir.rsDir;
  }

  public boolean isMobInCombat(final Object mob) {
    final ORSCharacterDirection dir = c().getCharacterDirection(mob(mob));
    return dir == ORSCharacterDirection.COMBAT_A || dir == ORSCharacterDirection.COMBAT_B;
  }

  public boolean isMobTalking(final Object mob) {
    return mob(mob).messageTimeout > 0;
  }

  public boolean isHeadIconVisible(final Object mob) {
    return mob(mob).bubbleTimeout > 0;
  }

  public int getMobCurrentHits(final Object mob) {
    return mob(mob).healthCurrent;
  }

  // --- misc ---

  public void drawString(
      final String str, final int x, final int y, final int font, final int colour) {
    c().drawString(str, x, y, colour, font);
  }

  public void displayMessage(final String str) {
    c().displayMessage(str);
  }

  public void sendPrivateMessage(final String msg, final String name) {
    c().sendPrivateMessage(name, msg);
  }

  public void addFriend(final String name) {
    c().addFriend(name);
  }

  public void setAutoLogin(final boolean b) {
    c().setAutoLogin(b);
  }

  public void stopScript() {
    script.stopScript();
  }
}
