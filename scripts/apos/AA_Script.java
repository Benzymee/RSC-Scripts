package scripting.apos;

import bot.Main;
import compatibility.apos.AntiIdle;
import compatibility.apos.Script;
import compatibility.apos.ServerProfile;
import controller.Controller;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import orsc.ORSCharacter;

/**
 * Base class of the AA_* APOS scripts, ported to IdleRSC.
 *
 * <p>Port notes: the APOS raw client ({@code Extension bot}) is replaced by {@link AA_Extension};
 * raw packets were replaced by Controller calls; APOS event hooks (onNpcSpawned, onNpcDespawned,
 * onNpcDamaged, onPlayerDamaged, onPlayerCoord, onGroundItemDespawned, onDeath) are emulated by
 * polling once per loop: subclasses implement {@link #loop()} instead of main(). Banks and shops
 * are opened with the right-click "Bank"/"Trade" command when the NPC has one (RSC Coleslaw),
 * otherwise by talking and answering the option menu.
 */
public abstract class AA_Script extends Script {
  public static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("#,##0");

  public static final int[] NPC_IDS_BANKER = new int[] {95, 224, 268, 485, 540, 617, 792};
  public static final int[] OBJECT_IDS_BED = new int[] {14, 15, 1035, 1162, 1171};

  public static final long TIMEOUT_ONE_TICK = 650L;
  public static final long TIMEOUT_ONE_SECOND = 1000L;
  public static final long TIMEOUT_TWO_SECONDS = 2000L;
  public static final long TIMEOUT_THREE_SECONDS = 3000L;
  public static final long TIMEOUT_FIVE_SECONDS = 5000L;
  public static final long TIMEOUT_TEN_SECONDS = 10000L;

  public static final int SLEEP_ONE_TICK = 650;
  public static final int SLEEP_ONE_SECOND = 1000;
  public static final int SLEEP_TWO_SECONDS = 2000;
  public static final int SLEEP_THREE_SECONDS = 3000;
  public static final int SLEEP_FIVE_SECONDS = 5000;
  public static final int SLEEP_TEN_SECONDS = 10000;

  public static final int PAINT_OFFSET_X = 312;
  public static final int PAINT_OFFSET_X_LOOT = 140;
  public static final int PAINT_OFFSET_Y = 48;
  public static final int PAINT_OFFSET_Y_INCREMENT = 14;

  public static final int MAX_INVENTORY_SIZE = 30;
  public static final int MAX_TRADE_SIZE = 12;

  public static final int ITEM_ID_SLEEPING_BAG = 1263;

  private static final DateTimeFormatter DATE_TIME_FORMATTER =
      DateTimeFormatter.ofPattern("HH:mm:ss");

  protected final AA_Extension bot;

  protected CombatStyle combatStyle = CombatStyle.STRENGTH;

  private long optionMenuTimeout;

  public AA_Script(final String ex) {
    this.bot = new AA_Extension(this);
  }

  protected static Controller c() {
    return Main.getController();
  }

  public static String toUnitsPerHour(final int processed, final long time) {
    if (processed == 0) {
      return "0";
    }

    final double amount = processed * 60.0 * 60.0;
    final double seconds = (System.currentTimeMillis() - time) / 1000.0;
    return DECIMAL_FORMAT.format(amount / seconds);
  }

  public static String toTimeToCompletion(
      final int processed, final int remaining, final long time) {
    if (processed == 0) {
      return "0:00:00";
    }

    final double seconds = (System.currentTimeMillis() - time) / 1000.0;
    final double secondsPerItem = seconds / processed;
    final long ttl = (long) (secondsPerItem * remaining);
    return String.format("%d:%02d:%02d", ttl / 3600, (ttl % 3600) / 60, (ttl % 60));
  }

  public static String toDuration(final long time) {
    final long seconds = (System.currentTimeMillis() - time) / 1000;
    return String.format("%d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, (seconds % 60));
  }

  public static String getLocalDateTime() {
    return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).format(DATE_TIME_FORMATTER);
  }

  @Override
  public abstract void init(final String parameters);

  private boolean sleepingBagWaived;
  private boolean fatigueWarned;

  /** AA_Script runs anti-idle on its own thread in main(); the central watcher skips it. */
  @Override
  public boolean handlesAntiIdle() {
    return true;
  }

  /** True if a sleeping bag is really in the inventory. */
  protected boolean hasRealSleepingBag() {
    return super.hasInventoryItem(ITEM_ID_SLEEPING_BAG);
  }

  /**
   * Whether the sleeping bag can be skipped: the server profile says fatigue is off (Coleslaw), or
   * the server is unknown and fatigue has not been seen to rise. See {@link ServerProfile}.
   */
  protected boolean isSleepingBagOptional() {
    return !ServerProfile.isSleepingBagRequired() && ServerProfile.sampleFatigue() == 0;
  }

  /**
   * Central sleeping-bag fix for the AA_* scripts: their init()/banking checks use {@code
   * hasInventoryItem(ITEM_ID_SLEEPING_BAG)}. When the bag is optional for this server and fatigue
   * is 0, a missing bag is reported as present, so the scripts start and don't withdraw one. Their
   * sleep paths only run when fatigue is above 0, where the real inventory is used again.
   */
  @Override
  public boolean hasInventoryItem(final int id) {
    if (id == ITEM_ID_SLEEPING_BAG && !super.hasInventoryItem(id) && isSleepingBagOptional()) {
      if (!sleepingBagWaived) {
        sleepingBagWaived = true;
        System.out.println(
            "No sleeping bag carried; not needed here ("
                + ServerProfile.kind().label
                + ", fatigue off).");
      }
      return true;
    }
    return super.hasInventoryItem(id);
  }

  /**
   * For the scripts that want the sleeping bag in inventory slot 1 (index 0) as a fixed slot: true
   * if it is there, or if the bag is optional on this server and slot 1 holds any item, which then
   * just keeps the script's slot layout (it is never used or banked by the script).
   */
  protected boolean isSleepingBagSlot(final int index) {
    if (getInventoryCount() > index && getInventoryId(index) == ITEM_ID_SLEEPING_BAG) return true;
    if (isSleepingBagOptional() && getInventoryCount() > index) {
      if (!sleepingBagWaived) {
        sleepingBagWaived = true;
        System.out.println(
            "No sleeping bag in slot "
                + (index + 1)
                + "; fatigue is off here, so "
                + getItemName(getInventoryId(index))
                + " in that slot is used as the slot placeholder.");
      }
      return true;
    }
    return false;
  }

  /** The script's main loop body (APOS main()). */
  public abstract int loop();

  @Override
  public final int main() {
    dispatchEvents();

    final Controller c = c();

    // Anti-idle (all OpenRSC servers): step to an adjacent tile and back on the server's
    // "standing here" warning, or after ServerProfile.antiIdleAfterMs() on one tile. IdleRSC only
    // handles this itself when no script is running.
    if (AntiIdle.isMoveDue(c) && (c.getNeedToMove() || AntiIdle.isQuietMoment(c))) {
      AntiIdle.stepAndBack(c);
      return 1000;
    }

    // Sleeping bag waived (fatigue off / unknown server) but fatigue turned out to rise: warn
    // once, and stop at 100% if there is still no bag, since nothing can be trained then.
    if (sleepingBagWaived && !hasRealSleepingBag()) {
      final int fatigue = ServerProfile.sampleFatigue();
      if (fatigue >= 100) {
        return exit("Fatigue is 100% and there is no sleeping bag in the inventory.");
      }
      if (fatigue > 0 && !fatigueWarned) {
        fatigueWarned = true;
        System.err.println(
            "Fatigue is rising on this server and no sleeping bag is carried; bring one.");
      }
    }

    // Batching servers (Coleslaw): clicking again (or walking) while a batch runs stops it.
    // Let the batch finish unless the script may need to react (combat, full inventory, low hits).
    if (ServerProfile.isBatching()
        && c.isBatching()
        && !c.isInCombat()
        && getInventoryCount() < 30
        && getCurrentLevel(3) * 2 > getLevel(3)) {
      return 640;
    }

    return loop();
  }

  // --- APOS event hooks, emulated by polling in dispatchEvents() ---

  public void onDeath() {
    setAutoLogin(false);
    stopScript();
    System.out.println("Oh dear, you are dead.");
  }

  public void onNpcSpawned(final Object npc) {}

  public void onNpcDespawned(final Object npc) {}

  public void onNpcDamaged(final Object npc) {}

  public void onPlayerDamaged(final Object player) {}

  public void onPlayerCoord(final int x, final int y) {}

  public void onGroundItemDespawned(final int groundItemIndex) {}

  private boolean eventsPrimed;
  private boolean wasDead;
  private Map<Integer, ORSCharacter> knownNpcs = new HashMap<>();
  private Map<ORSCharacter, Integer> knownCombatTimeouts = new IdentityHashMap<>();
  private List<int[]> knownGroundItems = new ArrayList<>();

  private void dispatchEvents() {
    final Controller c = c();
    if (c == null || !c.isLoggedIn()) {
      eventsPrimed = false;
      return;
    }

    final boolean dead = bot.isDeathScreen();
    if (dead && !wasDead) {
      wasDead = true;
      onDeath();
    }
    wasDead = dead;

    onPlayerCoord(getX(), getY());

    final Map<Integer, ORSCharacter> npcs = new HashMap<>();
    for (final ORSCharacter npc : c.getNpcs()) {
      if (npc != null) npcs.put(npc.serverIndex, npc);
    }

    final List<ORSCharacter> players = new ArrayList<>();
    final ORSCharacter local = c.getPlayer();
    if (local != null) players.add(local);
    for (final ORSCharacter player : c.getPlayers()) {
      if (player != null && player != local) players.add(player);
    }

    final List<int[]> groundItems = new ArrayList<>();
    for (int i = 0; i < getGroundItemCount(); i++) {
      groundItems.add(new int[] {getGroundItemId(i), getItemX(i), getItemY(i)});
    }

    final Map<ORSCharacter, Integer> combatTimeouts = new IdentityHashMap<>();
    for (final ORSCharacter npc : npcs.values()) combatTimeouts.put(npc, npc.combatTimeout);
    for (final ORSCharacter player : players) combatTimeouts.put(player, player.combatTimeout);

    if (eventsPrimed) {
      for (final Map.Entry<Integer, ORSCharacter> entry : knownNpcs.entrySet()) {
        if (!npcs.containsKey(entry.getKey())) onNpcDespawned(entry.getValue());
      }
      for (final Map.Entry<Integer, ORSCharacter> entry : npcs.entrySet()) {
        if (!knownNpcs.containsKey(entry.getKey())) onNpcSpawned(entry.getValue());
      }
      // a hit sets combatTimeout back up (it only counts down otherwise)
      for (final ORSCharacter npc : npcs.values()) {
        final Integer before = knownCombatTimeouts.get(npc);
        if (before != null && npc.combatTimeout > before) onNpcDamaged(npc);
      }
      for (final ORSCharacter player : players) {
        final Integer before = knownCombatTimeouts.get(player);
        if (before != null && player.combatTimeout > before) onPlayerDamaged(player);
      }
      final List<int[]> remaining = new ArrayList<>(groundItems);
      for (final int[] item : knownGroundItems) {
        boolean found = false;
        for (int i = 0; i < remaining.size(); i++) {
          final int[] other = remaining.get(i);
          if (other[0] == item[0] && other[1] == item[1] && other[2] == item[2]) {
            remaining.remove(i);
            found = true;
            break;
          }
        }
        if (!found) {
          bot.setDespawnedGroundItem(item);
          try {
            onGroundItemDespawned(AA_Extension.DESPAWNED_GROUND_ITEM_INDEX);
          } finally {
            bot.setDespawnedGroundItem(null);
          }
        }
      }
    }

    knownNpcs = npcs;
    knownCombatTimeouts = combatTimeouts;
    knownGroundItems = groundItems;
    eventsPrimed = true;
  }

  @Override
  public void onServerMessage(final String message) {
    if (message.endsWith("moment")) {
      optionMenuTimeout = 0L;
    }
  }

  /**
   * Overrides walkTo and walks to the given tile.
   *
   * @param x the x coordinate to walk to
   * @param y the y coordinate to walk to
   */
  @Override
  public void walkTo(final int x, final int y) {
    bot.walkDirectly(x - bot.getAreaX(), y - bot.getAreaY(), false);
    bot.setActionInd(24);
  }

  @Override
  public void useItemOnObject(final int inventoryIndex, final int x, final int y) {
    c().useItemSlotOnObject(x, y, inventoryIndex);
  }

  @Override
  public void atObject(final int x, final int y) {
    c().atObject(x, y);
  }

  @Override
  public void atObject2(final int x, final int y) {
    c().atObject2(x, y);
  }

  @Override
  public void atWallObject(final int x, final int y) {
    c().atWallObject(x, y);
  }

  @Override
  public void atWallObject2(final int x, final int y) {
    c().atWallObject2(x, y);
  }

  /** Closes the bank client- and server-side, like APOS (IdleRSC's closeBank only hides it). */
  @Override
  public void closeBank() {
    bot.closeBank();
  }

  @Override
  public boolean hasBankItem(final int itemId) {
    for (int index = 0; index < bot.getBankSize(); index++) {
      if (bot.getBankId(index) == itemId && bot.getBankStack(index) > 0) {
        return true;
      }
    }

    return false;
  }

  @Override
  public String getItemName(final int itemId) {
    return getItemNameId(itemId);
  }

  protected int getWallObjectIndex(final int x, final int y) {
    for (int index = 0; index < bot.getBoundCount(); index++) {
      if (bot.getBoundLocalX(index) == (x - bot.getAreaX())
          && bot.getBoundLocalY(index) == (y - bot.getAreaY())) {
        return index;
      }
    }

    return -1;
  }

  protected int getObjectIndex(final int x, final int y) {
    for (int index = 0; index < bot.getObjectCount(); index++) {
      if (bot.getObjectLocalX(index) == (x - bot.getAreaX())
          && bot.getObjectLocalY(index) == (y - bot.getAreaY())) {
        return index;
      }
    }

    return -1;
  }

  protected boolean isOnFriendList(final String playerName) {
    for (int index = 0; index < getFriendCount(); ++index) {
      if (getFriendName(index).equalsIgnoreCase(playerName)) return true;
    }
    return false;
  }

  protected void addToFriendList(final String playerName) {
    c().addFriend(playerName);
  }

  protected void offerTradeItem(final int inventoryIndex, final int amount) {
    bot.offerItemTrade(inventoryIndex, amount);
  }

  protected boolean isTradeOpen() {
    return bot.isInTradeOffer() || bot.isInTradeConfirm();
  }

  protected boolean isTradeConfirmOpen() {
    return bot.isInTradeConfirm();
  }

  protected boolean isTradeAcceptOpen() {
    return bot.isInTradeOffer();
  }

  protected boolean isTradeRecipientAccepted() {
    return bot.hasRemoteAcceptedTrade();
  }

  protected boolean isTradeAccepted() {
    return bot.hasLocalAcceptedTrade();
  }

  public void setCombatStyle(final int combatStyle) {
    bot.setCombatStyle(combatStyle);
  }

  protected void printInstructions() {
    throw new IllegalArgumentException(
        String.format("Please see the %s.java file for instructions!%n", this));
  }

  @Override
  public final String toString() {
    return getClass().getSimpleName();
  }

  protected boolean isAtCoordinate(final Coordinate coordinate) {
    return getPlayerX() == coordinate.getX() && getPlayerY() == coordinate.getY();
  }

  protected int getPlayerX() {
    return bot.getLocalX() + bot.getAreaX();
  }

  protected int getPlayerY() {
    return bot.getLocalY() + bot.getAreaY();
  }

  protected int getServerIndex(final java.lang.Object mob) {
    return bot.getMobServerIndex(mob);
  }

  protected boolean isDead() {
    return bot.isDeathScreen();
  }

  protected double getTotalCombatXp() {
    int total = 0;

    for (int i = 0; i < 4; i++) {
      total += bot.getExperience(i);
    }

    return total;
  }

  protected boolean isInventoryFull() {
    return bot.getInventorySize() == MAX_INV_SIZE;
  }

  protected boolean isInventoryEmpty() {
    return bot.getInventorySize() == 0;
  }

  protected int getInventoryEmptyCount() {
    return MAX_INV_SIZE - bot.getInventorySize();
  }

  protected boolean hasInventoryItem(final int[] itemIds) {
    for (final int itemId : itemIds) {
      for (int index = 0; index < bot.getInventorySize(); index++) {
        if (bot.getInventoryId(index) == itemId) {
          return true;
        }
      }
    }

    return false;
  }

  protected void equipItem(final int inventoryIndex) {
    c().equipItem(inventoryIndex);
  }

  protected int openBank() {
    return openInterface(0, NPC_IDS_BANKER);
  }

  private int openInterface(final int optionIndex, final int[] npcs) {
    if (bot.isDialogVisible()) {
      c().optionAnswer(optionIndex);
      optionMenuTimeout = System.currentTimeMillis() + TIMEOUT_FIVE_SECONDS;
      return 0;
    }

    if (System.currentTimeMillis() <= optionMenuTimeout) {
      return 0;
    }

    final Object npc = getNearestNpcNotTalking(npcs);

    if (npc == null) {
      return 0;
    }

    if (distanceTo(npc) > 2) {
      walkTo(npc);
      return SLEEP_ONE_TICK;
    }

    // RSC Coleslaw: bankers/shopkeepers have a right-click "Bank"/"Trade" command which opens the
    // interface directly; talking + dialogue keeps the player busy and the server resets shops.
    final String command = c().getNpcCommand1(bot.getNpcId(npc));
    if ("Bank".equalsIgnoreCase(command) || "Trade".equalsIgnoreCase(command)) {
      c().npcCommand1(bot.getMobServerIndex(npc));
    } else {
      talkToNpc(npc);
    }
    optionMenuTimeout = System.currentTimeMillis() + TIMEOUT_FIVE_SECONDS;
    return 0;
  }

  protected Object getNearestNpcNotTalking(final int[] npcIds) {
    return getNearestNpc(npcIds, false, true);
  }

  protected int distanceTo(final Object character) {
    return distanceTo(getX(character), getY(character), getPlayerX(), getPlayerY());
  }

  protected void walkTo(final Object character) {
    bot.walkDirectly(bot.getMobLocalX(character), bot.getMobLocalY(character), true);
    bot.setActionInd(24);
  }

  protected void talkToNpc(final Object npc) {
    c().talkToNpc(bot.getMobServerIndex(npc));
  }

  protected Object getNearestNpc(
      final int[] npcIds, final boolean notInCombat, final boolean notTalking) {
    Object nearestNpc = null;

    int currentDistance = Integer.MAX_VALUE;

    final int playerX = getPlayerX();
    final int playerY = getPlayerY();

    for (int index = 0; index < bot.getNpcCount(); index++) {
      final Object npc = bot.getNpc(index);

      if (!inArray(npcIds, bot.getNpcId(npc))
          || (notInCombat && isInCombat(npc))
          || (notTalking && isTalking(npc))) {
        continue;
      }

      final int npcX = bot.getMobLocalX(npc) + bot.getAreaX();
      final int npcY = bot.getMobLocalY(npc) + bot.getAreaY();

      final int distance = distanceTo(npcX, npcY, playerX, playerY);

      if (distance < currentDistance) {
        nearestNpc = npc;
        currentDistance = distance;
      }
    }

    return nearestNpc;
  }

  protected int getX(final Object character) {
    return bot.getMobLocalX(character) + bot.getAreaX();
  }

  protected int getY(final Object character) {
    return bot.getMobLocalY(character) + bot.getAreaY();
  }

  protected boolean isInCombat(final Object character) {
    return bot.isMobInCombat(character);
  }

  protected boolean isTalking(final Object character) {
    return bot.isMobTalking(character);
  }

  protected int openShop(final int... shopkeepers) {
    return openInterface(0, shopkeepers);
  }

  protected int openShop(final int optionIndex, final int[] shopkeepers) {
    return openInterface(optionIndex, shopkeepers);
  }

  protected int sleep() {
    final int index = getInventoryItemIndex(ITEM_ID_SLEEPING_BAG);

    if (index == -1) {
      return exit("Sleeping bag missing from inventory.");
    }

    useItem(index);
    return SLEEP_ONE_SECOND;
  }

  protected int getInventoryItemIndex(final int itemId) {
    for (int index = 0; index < bot.getInventorySize(); index++) {
      if (bot.getInventoryId(index) == itemId) {
        return index;
      }
    }

    return -1;
  }

  protected int exit(final String reason) {
    bot.setAutoLogin(false);
    bot.stopScript();
    System.err.println(reason);
    return 0;
  }

  protected int getInventoryItemIndex(final int[] itemIds) {
    for (final int itemId : itemIds) {
      for (int index = 0; index < bot.getInventorySize(); index++) {
        if (bot.getInventoryId(index) == itemId) {
          return index;
        }
      }
    }

    return -1;
  }

  protected int getBaseHits() {
    return bot.getBaseLevel(3);
  }

  protected int getCurrentHits() {
    return bot.getCurrentLevel(3);
  }

  protected int getCurrentHits(final java.lang.Object character) {
    return bot.getMobCurrentHits(character);
  }

  protected Object getPlayerObjByName(final String playerName) {
    Object player;

    for (int index = 0; index < bot.getPlayerCount(); index++) {
      player = bot.getPlayer(index);
      if (playerName.equalsIgnoreCase(getName(player))) return player;
    }

    return null;
  }

  protected String getName(final java.lang.Object character) {
    final String name = bot.getPlayerName(character);

    if (name == null) {
      return null;
    }

    return name.replace((char) 160, ' ');
  }

  protected int getWaypointX(final java.lang.Object character) {
    return bot.getMobLocalX(character);
  }

  protected int getWaypointY(final java.lang.Object character) {
    return bot.getMobLocalY(character);
  }

  protected void useObject1(final int x, final int y) {
    c().atObject(x, y);
  }

  protected void useObject2(final int x, final int y) {
    c().atObject2(x, y);
  }

  protected void useWithObject(final int inventoryIndex, final int x, final int y) {
    c().useItemSlotOnObject(x, y, inventoryIndex);
  }

  protected void takeGroundItem(final int itemId, final int x, final int y) {
    c().pickupItem(x, y, itemId, false, true);
  }

  protected int getGroundItemX(final int groundItemIndex) {
    return bot.getGroundItemLocalX(groundItemIndex) + bot.getAreaX();
  }

  protected int getGroundItemY(final int groundItemIndex) {
    return bot.getGroundItemLocalY(groundItemIndex) + bot.getAreaY();
  }

  protected boolean isTradeConfirmed() {
    return bot.hasLocalConfirmedTrade();
  }

  protected boolean hasTradeItem(final int[] itemIds) {
    for (final int itemId : itemIds) {
      for (int index = 0; index < bot.getLocalTradeItemCount(); index++) {
        if (bot.getLocalTradeItemId(index) == itemId) {
          return true;
        }
      }
    }

    return false;
  }

  protected int getTradeItemCount() {
    return bot.getLocalTradeItemCount();
  }

  protected int getTradeItemIdCount(final int itemId) {
    int count = 0;

    for (int index = 0; index < bot.getLocalTradeItemCount(); index++) {
      if (bot.getLocalTradeItemId(index) == itemId) {
        count += bot.getLocalTradeItemStack(index);
      }
    }

    return count;
  }

  protected void offerTradeItemId(final int itemId, final int amount) {
    final int inventoryIndex = getInventoryIndex(itemId);

    if (inventoryIndex == -1) {
      return;
    }

    bot.offerItemTrade(inventoryIndex, amount);
  }

  protected void removeTradeItemId(final int itemId, final int amount) {
    final int tradeIndex = getTradeItemIndex(itemId);

    if (tradeIndex == -1) {
      return;
    }

    removeTradeItem(tradeIndex, amount);
  }

  protected int getTradeItemIndex(final int itemId) {
    for (int index = 0; index < bot.getLocalTradeItemCount(); index++) {
      if (bot.getLocalTradeItemId(index) == itemId) {
        return index;
      }
    }

    return -1;
  }

  protected void removeTradeItem(final int tradeIndex, final int amount) {
    // IdleRSC can only set the whole offer: re-send it without `amount` of that entry.
    final int count = bot.getLocalTradeItemCount();
    if (tradeIndex < 0 || tradeIndex >= count) {
      return;
    }
    final List<Integer> ids = new ArrayList<>();
    final List<Integer> amounts = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      int stack = bot.getLocalTradeItemStack(index);
      if (index == tradeIndex) {
        stack -= amount;
      }
      if (stack > 0) {
        ids.add(bot.getLocalTradeItemId(index));
        amounts.add(stack);
      }
    }
    c().setTradeItems(
            ids.stream().mapToInt(Integer::intValue).toArray(),
            amounts.stream().mapToInt(Integer::intValue).toArray(),
            false);
  }

  protected double getSkillExperience(final int skillId) {
    return bot.getExperience(skillId);
  }

  protected int getCurrentSkillLevel(final int skillId) {
    return bot.getCurrentLevel(skillId);
  }

  protected int getFatiguePercent() {
    return (int) bot.getAccurateFatigue();
  }

  protected boolean isBankOpen() {
    return bot.isBankVisible();
  }

  protected boolean isInCombat() {
    return c().isInCombat();
  }

  protected int getBankItemIdCount(final int itemId) {
    int count = 0;

    for (int index = 0; index < bot.getBankSize(); index++) {
      if (bot.getBankId(index) == itemId) {
        count += bot.getBankStack(index);
        break;
      }
    }

    return count;
  }

  protected int getBankItemIdCount(final int[] itemIds) {
    int count = 0;

    for (final int itemId : itemIds) {
      for (int index = 0; index < bot.getBankSize(); index++) {
        if (bot.getBankId(index) == itemId) {
          count += bot.getBankStack(index);
          break;
        }
      }
    }

    return count;
  }

  protected int getBaseSkillLevel(final int skillId) {
    return bot.getBaseLevel(skillId);
  }

  protected int getObjectId(final int x, final int y) {
    for (int index = 0; index < bot.getObjectCount(); index++) {
      if (bot.getObjectLocalX(index) == (x - bot.getAreaX())
          && bot.getObjectLocalY(index) == (y - bot.getAreaY())) {
        return bot.getObjectId(index);
      }
    }

    return -1;
  }

  protected int getWallObjectId(final int x, final int y) {
    for (int index = 0; index < bot.getBoundCount(); index++) {
      if (bot.getBoundLocalX(index) == (x - bot.getAreaX())
          && bot.getBoundLocalY(index) == (y - bot.getAreaY())) {
        return bot.getBoundId(index);
      }
    }

    return -1;
  }

  protected void answerOptionMenu(final int menuIndex) {
    c().optionAnswer(menuIndex);
  }

  protected Object getNearestNpcNotInCombat(final int npcId) {
    return getNearestNpc(npcId, true, false);
  }

  protected Object getNearestNpc(
      final int npcId, final boolean notInCombat, final boolean notTalking) {
    Object nearestNpc = null;

    int currentDistance = Integer.MAX_VALUE;

    final int playerX = getPlayerX();
    final int playerY = getPlayerY();

    for (int index = 0; index < bot.getNpcCount(); index++) {
      final Object npc = bot.getNpc(index);

      if (bot.getNpcId(npc) != npcId
          || (notInCombat && isInCombat(npc))
          || (notTalking && isTalking(npc))) {
        continue;
      }

      final int npcX = bot.getMobLocalX(npc) + bot.getAreaX();
      final int npcY = bot.getMobLocalY(npc) + bot.getAreaY();

      final int distance = distanceTo(npcX, npcY, playerX, playerY);

      if (distance < currentDistance) {
        nearestNpc = npc;
        currentDistance = distance;
      }
    }

    return nearestNpc;
  }

  protected Object getNearestNpcNotInCombat(final int[] npcIds) {
    return getNearestNpc(npcIds, true, false);
  }

  protected Object getNearestNpcNotTalking(final int npcId) {
    return getNearestNpc(npcId, false, true);
  }

  protected boolean isOptionMenuOpen() {
    return bot.isDialogVisible();
  }

  protected void castOnNpc(final int spellId, final Object npc) {
    c().castSpellOnNpc(bot.getMobServerIndex(npc), spellId);
  }

  protected void attackNpc(final Object npc) {
    c().attackNpc(bot.getMobServerIndex(npc));
  }

  protected int getInventoryItemIdCount(final int itemId) {
    int count = 0;

    for (int index = 0; index < bot.getInventorySize(); index++) {
      if (bot.getInventoryId(index) == itemId) {
        if (isItemStackableId(itemId)) {
          count += bot.getInventoryStack(index);
        } else {
          count++;
        }
      }
    }

    return count;
  }

  protected int getInventoryItemIdCount(final int[] itemIds) {
    int count = 0;

    for (final int itemId : itemIds) {
      for (int index = 0; index < getInventoryItemCount(); index++) {
        if (getInventoryItemId(index) == itemId) count += getInventoryItemCount(index);
      }
    }

    return count;
  }

  protected int getInventoryItemCount() {
    return bot.getInventorySize();
  }

  protected int getInventoryItemId(final int inventoryIndex) {
    return bot.getInventoryId(inventoryIndex);
  }

  protected int getInventoryItemCount(final int inventoryIndex) {
    return bot.getInventoryStack(inventoryIndex);
  }

  protected int getShopItemCount(final int shopIndex) {
    return bot.getShopStack(shopIndex);
  }

  protected int getOptionMenuCount() {
    return bot.getDialogOptionCount();
  }

  protected void useItemOnNpc(final int inventoryIndex, final Object npc) {
    c().useItemOnNpc(bot.getMobServerIndex(npc), bot.getInventoryId(inventoryIndex));
  }

  protected int getShopItemIndex(final int itemId) {
    for (int index = 0; index < bot.getShopSize(); index++) {
      if (bot.getShopId(index) == itemId) {
        return index;
      }
    }

    return -1;
  }

  protected Coordinate getWalkableCoordinate() {
    int x;
    int y;

    for (int i = -1; i <= 1; i++) {
      x = getX() + i;

      for (int j = -1; j <= 1; j++) {
        y = getY() + j;

        if (i == 0 && j == 0) {
          continue;
        }

        if (isReachable(x, y) && !isObjectAt(x, y)) {
          return new Coordinate(x, y);
        }
      }
    }

    return null;
  }

  protected enum CombatStyle {
    CONTROLLED(0),
    STRENGTH(1),
    ATTACK(2),
    DEFENSE(3);

    private final int index;

    CombatStyle(final int index) {
      this.index = index;
    }

    @Override
    public String toString() {
      return name().charAt(0) + name().substring(1).toLowerCase();
    }

    public int getIndex() {
      return index;
    }
  }

  protected enum Skill {
    ATTACK(0),
    DEFENSE(1),
    STRENGTH(2),
    HITS(3),
    RANGED(4),
    PRAYER(5),
    MAGIC(6),
    COOKING(7),
    WOODCUT(8),
    FLETCHING(9),
    FISHING(10),
    FIREMAKING(11),
    CRAFTING(12),
    SMITHING(13),
    MINING(14),
    HERBLAW(15),
    AGILITY(16),
    THIEVING(17);

    private final int index;

    Skill(final int index) {
      this.index = index;
    }

    @Override
    public String toString() {
      return name().charAt(0) + name().substring(1).toLowerCase();
    }

    public int getIndex() {
      return index;
    }
  }

  protected enum Food {
    NONE(-1, 0, "None"),
    SHRIMP(350, 3, "Shrimp"),
    ANCHOVIES(352, 1, "Anchovies"),
    SARDINE(355, 4, "Sardine"),
    HERRING(362, 5, "Herring"),
    GIANT_CARP(718, 6, "Giant Carp"),
    MACKEREL(553, 6, "Mackerel"),
    TROUT(359, 7, "Trout"),
    COD(551, 7, "Cod"),
    PIKE(364, 8, "Pike"),
    SALMON(357, 9, "Salmon"),
    TUNA(367, 10, "Tuna"),
    LOBSTER(373, 12, "Lobster"),
    BASS(555, 13, "Bass"),
    SWORDFISH(370, 14, "Swordfish"),
    SHARK(546, 20, "Shark"),
    SEA_TURTLE(1193, 20, "Sea Turtle"),
    MANTA_RAY(1191, 20, "Manta Ray");

    private final int id;
    private final int healAmount;
    private final String name;

    Food(final int id, final int healAmount, final String name) {
      this.id = id;
      this.healAmount = healAmount;
      this.name = name;
    }

    @Override
    public String toString() {
      return name;
    }

    public int getId() {
      return id;
    }

    public int getHealAmount() {
      return healAmount;
    }
  }

  protected interface RSObject {
    int getId();

    Coordinate getCoordinate();
  }

  protected interface RSArea {
    default boolean contains(final int x, final int y) {
      return x >= getLowerBoundingCoordinate().getX()
          && x <= getUpperBoundingCoordinate().getX()
          && y >= getLowerBoundingCoordinate().getY()
          && y <= getUpperBoundingCoordinate().getY();
    }

    Coordinate getLowerBoundingCoordinate();

    Coordinate getUpperBoundingCoordinate();
  }

  public static final class Coordinate {
    private int x;

    private int y;

    public Coordinate(final int x, final int y) {
      this.x = x;
      this.y = y;
    }

    public boolean equals(final Object o) {
      if (o == this) {
        return true;
      }
      if (!(o instanceof Coordinate)) {
        return false;
      }
      final Coordinate other = (Coordinate) o;
      if (getX() != other.getX()) {
        return false;
      }
      return getY() == other.getY();
    }

    public int hashCode() {
      final int PRIME = 59;
      int result = 1;
      result = result * PRIME + getX();
      result = result * PRIME + getY();
      return result;
    }

    public String toString() {
      return "AA_Script.Coordinate(x=" + getX() + ", y=" + getY() + ")";
    }

    public int getX() {
      return x;
    }

    public void setX(final int x) {
      this.x = x;
    }

    public int getY() {
      return y;
    }

    public void setY(final int y) {
      this.y = y;
    }

    public void set(final int x, final int y) {
      this.x = x;
      this.y = y;
    }
  }

  public static final class Spawn implements Comparable<Spawn> {
    private final Coordinate coordinate;

    private long timestamp;

    public Spawn(final Coordinate coordinate, final long timestamp) {
      this.coordinate = coordinate;
      this.timestamp = timestamp;
    }

    @Override
    public int compareTo(final Spawn spawn) {
      return Long.compare(timestamp, spawn.timestamp);
    }

    public boolean equals(final Object o) {
      if (o == this) {
        return true;
      }
      if (!(o instanceof Spawn)) {
        return false;
      }
      final Spawn other = (Spawn) o;
      final Object this$coordinate = getCoordinate();
      final Object other$coordinate = other.getCoordinate();
      if (!Objects.equals(this$coordinate, other$coordinate)) {
        return false;
      }
      return getTimestamp() == other.getTimestamp();
    }

    public int hashCode() {
      final int PRIME = 59;
      int result = 1;
      final Object $coordinate = getCoordinate();
      result = result * PRIME + ($coordinate == null ? 43 : $coordinate.hashCode());
      final long $timestamp = getTimestamp();
      result = result * PRIME + (int) ($timestamp >>> 32 ^ $timestamp);
      return result;
    }

    public String toString() {
      return "AA_Script.Spawn(coordinate="
          + getCoordinate()
          + ", timestamp="
          + getTimestamp()
          + ")";
    }

    public Coordinate getCoordinate() {
      return coordinate;
    }

    public long getTimestamp() {
      return timestamp;
    }

    public void setTimestamp(final long timestamp) {
      this.timestamp = timestamp;
    }
  }
}
