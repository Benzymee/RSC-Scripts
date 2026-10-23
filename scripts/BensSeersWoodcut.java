import bot.Main;
import bot.ui.scriptselector.models.Category;
import bot.ui.scriptselector.models.Parameter;
import bot.ui.scriptselector.models.ScriptInfo;
import controller.Controller;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import models.entities.ItemId;
import models.entities.Location;
import models.entities.NpcId;
import models.entities.SceneryId;
import models.entities.SkillId;
import orsc.ORSCharacter;
import scripting.idlescript.IdleScript;

/**
 * Progressive woodcutting around Seers' Village.
 *
 * <p>Picks the best tree the account can cut (normal, oak, willow, maple, yew, magic) and either
 * banks the logs at Seers, drops them, or lights them. Authentic firemaking only lights normal
 * logs (item 14). A tinderbox on oak, willow, maple, yew, or magic logs is "Nothing interesting
 * happens", so Light mode stays on the normal trees west of the village.
 *
 * <p>The best axe in the inventory or the Seers bank is withdrawn. If there is no axe in either, it
 * withdraws coins and buys the best axe it can afford from Bob in Lumbridge (steel, then iron,
 * then bronze). It is wielded when the account's Attack level meets that axe's requirement. A
 * better axe the account cannot wield yet stays in the inventory, which is enough to chop. The
 * unimplemented dragon woodcutting axe is ignored; the best real axe is the rune axe.
 *
 * <p>Args: {@code bank}, {@code drop}, or {@code light}.
 */
public class BensSeersWoodcut extends IdleScript {
  public static final ScriptInfo info =
      new ScriptInfo(
          new Category[] {Category.WOODCUTTING, Category.IRONMAN_SUPPORTED},
          "Ben",
          "Cuts trees around Seers' Village, moving up as Woodcutting rises.\n\n"
              + "Bank the logs, drop them, or light them. Only normal logs can be lit.\n"
              + "Takes the best axe from the bank or inventory. If there is no axe, buys one from Bob in Lumbridge.\n"
              + "Wields it when Attack is high enough.\n\n"
              + "Args: bank, drop, or light.",
          new Parameter[] {
            new Parameter("bank", "Bank logs at Seers' Village."),
            new Parameter("drop", "Drop logs."),
            new Parameter("light", "Light normal logs. Oak and better cannot be lit.")
          },
          "Ben's Seers Woodcut");

  /** Best implemented woodcutting axe first. {item id, Attack level to wield}. */
  private static final int[][] AXES = {
    {ItemId.RUNE_AXE.getId(), 40},
    {ItemId.ADAMANTITE_AXE.getId(), 30},
    {ItemId.MITHRIL_AXE.getId(), 20},
    {ItemId.BLACK_AXE.getId(), 10},
    {ItemId.STEEL_AXE.getId(), 5},
    {ItemId.IRON_AXE.getId(), 1},
    {ItemId.BRONZE_AXE.getId(), 1}
  };

  private static final int COINS = ItemId.COINS.getId();
  private static final int TINDERBOX = ItemId.TINDERBOX.getId();
  /** Bob's normal-stock price for a bronze axe. Iron is 56, steel is 200; low stock costs more. */
  private static final int BRONZE_AXE_PRICE = 16;
  private static final int BOB = NpcId.BOB.getId();
  private static final int[][] BOB_DOORS = {{122, 669}, {124, 664}};
  private static final int CLOSED_DOOR = 2;
  private static final int NORMAL_LOGS = ItemId.LOGS.getId();
  private static final int[] LOGS = {
    ItemId.LOGS.getId(),
    ItemId.OAK_LOGS.getId(),
    ItemId.WILLOW_LOGS.getId(),
    ItemId.MAPLE_LOGS.getId(),
    ItemId.YEW_LOGS.getId(),
    ItemId.MAGIC_LOGS.getId()
  };

  /** West of Seers, the leafy and pointy trees. */
  private static final Spot NORMAL =
      new Spot("normal trees", 1, new int[] {SceneryId.POINTY_TREE.getId(), SceneryId.LEAFY_TREE.getId()}, 470, 472, null);
  /** Oaks just south of the Seers bank. */
  private static final Spot OAK =
      new Spot("oak trees", 15, new int[] {SceneryId.TREE_OAK.getId()}, 508, 446, null);
  private static final Spot WILLOW =
      new Spot(
          "willow trees",
          30,
          new int[] {SceneryId.TREE_WILLOW.getId()},
          505,
          440,
          Location.SEERS_VILLAGE_WILLOW_TREES);
  /** Maples between the bank and the church. */
  private static final Spot MAPLE =
      new Spot("maple trees", 45, new int[] {SceneryId.TREE_MAPLE.getId()}, 510, 458, null);
  private static final Spot YEW =
      new Spot(
          "yew trees",
          60,
          new int[] {SceneryId.TREE_YEW.getId()},
          517,
          475,
          Location.SEERS_VILLAGE_YEW_TREES);
  private static final Spot MAGIC =
      new Spot(
          "magic trees",
          75,
          new int[] {SceneryId.TREE_MAGIC.getId()},
          524,
          491,
          Location.SEERS_VILLAGE_MAGIC_TREES);
  /** Highest requirement first. */
  private static final Spot[] SPOTS = {MAGIC, YEW, MAPLE, WILLOW, OAK, NORMAL};

  private enum Mode {
    BANK,
    DROP,
    LIGHT
  }

  private Mode mode = Mode.BANK;
  private boolean guiShown;
  private boolean started;
  private boolean geared;
  private boolean buyingAxe;
  private int axeBuyTries;
  private boolean stopped;
  private volatile boolean fireBlocked;
  private String status = "Starting";
  private int chopped;
  private int banked;
  private int dropped;
  private int lit;

  @Override
  public int start(String[] parameters) {
    Controller c = Main.getController();
    if (!guiShown) {
      Mode fromArgs = modeFrom(parameters);
      if (fromArgs != null) {
        mode = fromArgs;
        started = true;
      } else {
        showGui();
      }
      guiShown = true;
      return 400;
    }
    if (!started || stopped) return 1000;
    if (c == null || !c.isLoggedIn()) return 1000;
    if (c.getNeedToMove()) c.moveCharacter();
    if (c.getShouldSleep()) {
      status = "Sleeping";
      c.sleepHandler(true);
      return 600;
    }
    if (c.isSleeping()) return 600;

    if (!geared) return gearUp(c);

    int axe = bestCarried(c);
    if (axe < 0) {
      geared = false;
      status = "Axe missing, checking the bank";
      return 600;
    }
    wieldIfAllowed(c, axe);

    if (mode == Mode.LIGHT && c.getInventoryItemCount(TINDERBOX) < 1) {
      geared = false;
      status = "Tinderbox missing, checking the bank";
      return 600;
    }

    if (needsLogsHandled(c)) return handleLogs(c);
    return chop(c);
  }

  @Override
  public void serverMessageInterrupt(String message) {
    if (message == null) return;
    String m = message.toLowerCase();
    if (m.contains("you get some wood")) chopped++;
    if (m.contains("the fire catches")) lit++;
    if (m.contains("can't light a fire")) fireBlocked = true;
  }

  @Override
  public void paintInterrupt() {
    Controller c = Main.getController();
    if (c == null) return;
    int y = 36;
    c.drawString("Ben's Seers Woodcut", 6, y, 0xD4AF37, 1);
    y += 14;
    c.drawString(status, 6, y, 0xFFFFFF, 1);
    y += 14;
    c.drawString("Cut " + chopped + "  banked " + banked + "  dropped " + dropped + "  lit " + lit, 6, y, 0xFFE066, 1);
  }

  private int gearUp(Controller c) {
    if (buyingAxe) return buyAxe(c);
    if (!Location.SEERS_VILLAGE_BANK.isAtLocation()) {
      status = "Walking to Seers bank for an axe";
      Location.SEERS_VILLAGE_BANK.walkTowards();
      return 600;
    }
    if (!c.isInBank()) {
      status = "Opening Seers bank";
      c.openBank();
      return 400;
    }

    int want = bestAvailable(c);
    if (want < 0) return leaveBankToBuyAxe(c);
    if (c.getInventoryItemCount(want) < 1 && !c.isItemIdEquipped(want)) {
      if (c.getInventoryItemCount() >= 30) depositLogs(c);
      if (c.getInventoryItemCount() >= 30) {
        fail(c, "Inventory is full, so the " + c.getItemName(want) + " cannot be withdrawn");
        return 1000;
      }
      status = "Withdrawing " + c.getItemName(want);
      c.withdrawItem(want);
      c.sleep(600);
    }

    if (mode == Mode.LIGHT && c.getInventoryItemCount(TINDERBOX) < 1) {
      if (c.getBankItemCount(TINDERBOX) < 1) {
        fail(c, "Light mode needs a tinderbox. There is not one in the inventory or the bank");
        return 1000;
      }
      if (c.getInventoryItemCount() >= 30) depositLogs(c);
      status = "Withdrawing a tinderbox";
      c.withdrawItem(TINDERBOX);
      c.sleep(400);
    }

    c.closeBank();
    wieldIfAllowed(c, want);
    geared = c.getInventoryItemCount(want) > 0 || c.isItemIdEquipped(want);
    if (!geared) {
      fail(c, "Could not take a " + c.getItemName(want) + " from the bank");
      return 1000;
    }
    status = "Using " + c.getItemName(want);
    return 600;
  }

  /**
   * The Seers bank has no axe. Take the coins and buy steel, iron, or bronze from Bob, whichever
   * the shop has in stock and the account can pay for.
   */
  private int leaveBankToBuyAxe(Controller c) {
    int coins = c.getInventoryItemCount(COINS) + c.getBankItemCount(COINS);
    if (coins < BRONZE_AXE_PRICE) {
      fail(
          c,
          "No axe in the bank or inventory, and only "
              + coins
              + " coins. Bob sells a bronze axe for "
              + BRONZE_AXE_PRICE
              + " coins");
      return 1000;
    }
    int inBank = c.getBankItemCount(COINS);
    if (inBank > 0) {
      if (c.getInventoryItemCount(COINS) < 1 && c.getInventoryItemCount() >= 30) depositLogs(c);
      if (c.getInventoryItemCount(COINS) < 1 && c.getInventoryItemCount() >= 30) {
        fail(c, "Inventory is full, so the coins for an axe cannot be withdrawn");
        return 1000;
      }
      status = "Withdrawing " + inBank + " coins to buy an axe";
      c.withdrawItem(COINS, inBank);
      c.sleep(500);
    }
    if (c.isInBank()) c.closeBank();
    buyingAxe = true;
    axeBuyTries = 0;
    status = "No axe in the bank. Going to Bob's Axes";
    return 600;
  }

  private int buyAxe(Controller c) {
    int carried = bestCarried(c);
    if (carried >= 0) {
      if (c.isInShop()) c.closeShop();
      buyingAxe = false;
      wieldIfAllowed(c, carried);
      geared = true;
      status = "Got " + c.getItemName(carried);
      return 600;
    }
    if (c.isInBank()) {
      c.closeBank();
      return 400;
    }
    if (c.isInShop()) return purchaseFromBob(c);
    if (c.isInOptionMenu()) {
      c.optionAnswer(shopOption(c));
      c.sleepUntil(() -> c.isInShop(), 5000);
      return 600;
    }
    if (!Location.LUMBRIDGE_BOBS_AXES.isAtLocation()) {
      if (openBobDoor(c)) return 900;
      status = "Walking to Bob's Axes in Lumbridge";
      Location.LUMBRIDGE_BOBS_AXES.walkTowards();
      return 600;
    }
    ORSCharacter bob = c.getNearestNpcById(BOB, false);
    if (bob == null) {
      status = "Waiting for Bob";
      return 1000;
    }
    String command = c.getNpcCommand1(BOB);
    status = "Trading with Bob";
    if (command != null && command.equalsIgnoreCase("Trade")) c.npcCommand1(bob.serverIndex);
    else c.talkToNpc(bob.serverIndex);
    c.sleepUntil(() -> c.isInShop() || c.isInOptionMenu(), 5000);
    return 600;
  }

  private int purchaseFromBob(Controller c) {
    int axe = affordableShopAxe(c);
    if (axe < 0) {
      int coins = c.getInventoryItemCount(COINS);
      c.closeShop();
      fail(
          c,
          "Bob has no axe in stock that "
              + coins
              + " coins will buy. A bronze axe is "
              + BRONZE_AXE_PRICE
              + " coins when his stock is full");
      return 1000;
    }
    int before = c.getInventoryItemCount(axe);
    status = "Buying " + c.getItemName(axe) + " for " + c.getShopItemPrice(axe) + " coins";
    c.shopBuy(axe);
    c.sleep(800);
    if (c.getInventoryItemCount(axe) > before) {
      c.closeShop();
      buyingAxe = false;
      wieldIfAllowed(c, axe);
      geared = true;
      status = "Bought " + c.getItemName(axe);
      return 600;
    }
    axeBuyTries++;
    if (axeBuyTries > 4) {
      c.closeShop();
      fail(c, "Bob did not sell a " + c.getItemName(axe));
      return 1000;
    }
    return 800;
  }

  /** Steel, then iron, then bronze: the first one Bob has in stock that the coins will cover. */
  private int affordableShopAxe(Controller c) {
    int coins = c.getInventoryItemCount(COINS);
    int[] sell = {
      ItemId.STEEL_AXE.getId(), ItemId.IRON_AXE.getId(), ItemId.BRONZE_AXE.getId()
    };
    for (int id : sell) {
      if (c.getShopItemCount(id) < 1) continue;
      int price = c.getShopItemPrice(id);
      if (price > 0 && coins >= price) return id;
    }
    return -1;
  }

  private int shopOption(Controller c) {
    int count = c.getOptionMenuCount();
    for (int i = 0; i < count; i++) {
      String text = c.getOptionsMenuText(i);
      if (text != null && text.toLowerCase().contains("sell")) return i;
    }
    return 1;
  }

  private boolean openBobDoor(Controller c) {
    if (Location.LUMBRIDGE_BOBS_AXES.isAtLocation()) return false;
    if (c.distanceTo(122, 668) > 12) return false;
    for (int[] door : BOB_DOORS) {
      if (c.getObjectAtCoord(door[0], door[1]) != CLOSED_DOOR) continue;
      status = "Opening the door to Bob's Axes";
      c.openDoor(door[0], door[1]);
      return true;
    }
    return false;
  }

  private int chop(Controller c) {
    if (c.isBatching()) return 800;
    Spot spot = mode == Mode.LIGHT ? NORMAL : bestSpot(c);
    if (!atSpot(c, spot)) {
      status = "Walking to " + spot.name;
      if (spot.place != null) spot.place.walkTowards();
      else Location.walkTowards(spot.x, spot.y);
      return 600;
    }
    int[] tree = c.getNearestReachableObjectByIds(spot.trees, true);
    if (tree == null) tree = c.getNearestObjectByIds(spot.trees);
    if (tree == null || c.distanceTo(tree[0], tree[1]) > 18) {
      status = "Waiting for " + spot.name;
      return 1200;
    }
    status = "Chopping " + spot.name;
    c.atObject(tree[0], tree[1]);
    return 1200;
  }

  private boolean needsLogsHandled(Controller c) {
    if (mode == Mode.LIGHT) {
      if (otherLogCount(c) > 0) return true;
      if (c.getInventoryItemCount(NORMAL_LOGS) > 0) return true;
      int[] ground = c.getNearestItemById(NORMAL_LOGS, 1);
      return ground != null && ground[0] == c.currentX() && ground[1] == c.currentY();
    }
    return logCount(c) > 0 && c.getInventoryItemCount() >= 29;
  }

  private int handleLogs(Controller c) {
    if (c.isBatching()) c.stopBatching();
    if (mode == Mode.DROP) return dropLogs(c);
    if (mode == Mode.LIGHT) {
      if (otherLogCount(c) > 0) return bankLogs(c);
      return lightOne(c);
    }
    return bankLogs(c);
  }

  private int bankLogs(Controller c) {
    if (!Location.SEERS_VILLAGE_BANK.isAtLocation()) {
      status = "Walking to Seers bank";
      Location.SEERS_VILLAGE_BANK.walkTowards();
      return 600;
    }
    if (!c.isInBank()) {
      status = "Opening Seers bank";
      c.openBank();
      return 400;
    }
    int deposited = depositLogs(c);
    banked += deposited;
    int better = bestAvailable(c);
    int carried = bestCarried(c);
    if (better >= 0 && better != carried && c.getInventoryItemCount() < 30) {
      status = "Withdrawing " + c.getItemName(better);
      c.withdrawItem(better);
      c.sleep(400);
      carried = better;
    }
    c.closeBank();
    if (carried >= 0) wieldIfAllowed(c, carried);
    status = "Banked logs";
    return 600;
  }

  private int depositLogs(Controller c) {
    int n = 0;
    for (int id : LOGS) {
      int count = c.getInventoryItemCount(id);
      if (count < 1) continue;
      if (c.depositItem(id, count)) n += count;
    }
    return n;
  }

  private int dropLogs(Controller c) {
    for (int id : LOGS) {
      int count = c.getInventoryItemCount(id);
      if (count < 1) continue;
      status = "Dropping " + c.getItemName(id);
      c.dropItem(c.getInventoryItemSlotIndex(id), count);
      dropped += count;
      return 700;
    }
    return 600;
  }

  /** Drops one normal log on a clear tile and lights it. Other logs are never lit. */
  private int lightOne(Controller c) {
    if (c.getInventoryItemCount(NORMAL_LOGS) < 1 && groundLogHere(c) == null) return 600;
    int x = c.currentX();
    int y = c.currentY();
    if (fireBlocked || c.getObjectAtCoord(x, y) != -1) {
      fireBlocked = false;
      int[] step = clearNeighbour(c);
      if (step == null) {
        status = "No empty tile to light a fire";
        return 1000;
      }
      status = "Moving off the fire";
      c.walkTo(step[0], step[1]);
      return 800;
    }
    int[] ground = groundLogHere(c);
    if (ground == null) {
      int slot = c.getInventoryItemSlotIndex(NORMAL_LOGS);
      if (slot < 0) return 600;
      status = "Dropping a log to light";
      c.dropItem(slot);
      c.sleep(500);
      ground = groundLogHere(c);
      if (ground == null) return 600;
    }
    status = "Lighting a normal log";
    fireBlocked = false;
    c.useItemOnGroundItem(ground[0], ground[1], TINDERBOX, NORMAL_LOGS);
    c.sleep(1400);
    return 400;
  }

  private int[] groundLogHere(Controller c) {
    int[] ground = c.getNearestItemById(NORMAL_LOGS, 1);
    if (ground == null) return null;
    if (ground[0] == c.currentX() && ground[1] == c.currentY()) return ground;
    return null;
  }

  private int[] clearNeighbour(Controller c) {
    int x = c.currentX();
    int y = c.currentY();
    int[][] steps = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    for (int[] step : steps) {
      int nx = x + step[0];
      int ny = y + step[1];
      if (c.getObjectAtCoord(nx, ny) == -1 && c.isReachable(nx, ny, false)) return new int[] {nx, ny};
    }
    return null;
  }

  private void wieldIfAllowed(Controller c, int axeId) {
    if (c.isItemIdEquipped(axeId)) return;
    if (c.getInventoryItemCount(axeId) < 1) return;
    int attack = c.getCurrentStat(SkillId.ATTACK.getId());
    int need = attackNeed(axeId);
    if (attack < need) {
      status = c.getItemName(axeId) + " stays in the inventory (Attack " + attack + "/" + need + ")";
      return;
    }
    status = "Wielding " + c.getItemName(axeId);
    c.equipItemById(axeId);
    c.sleep(400);
  }

  /** Best axe this account is carrying, or -1. */
  private int bestCarried(Controller c) {
    for (int[] axe : AXES) {
      if (c.getInventoryItemCount(axe[0]) > 0 || c.isItemIdEquipped(axe[0])) return axe[0];
    }
    return -1;
  }

  /** Best axe in the inventory, equipment, or the open bank. Bank counts are only valid while it is open. */
  private int bestAvailable(Controller c) {
    for (int[] axe : AXES) {
      if (c.getInventoryItemCount(axe[0]) > 0 || c.isItemIdEquipped(axe[0])) return axe[0];
      if (c.isInBank() && c.getBankItemCount(axe[0]) > 0) return axe[0];
    }
    return -1;
  }

  private int attackNeed(int axeId) {
    for (int[] axe : AXES) if (axe[0] == axeId) return axe[1];
    return 1;
  }

  private Spot bestSpot(Controller c) {
    int level = c.getCurrentStat(SkillId.WOODCUTTING.getId());
    for (Spot spot : SPOTS) if (level >= spot.level) return spot;
    return NORMAL;
  }

  private boolean atSpot(Controller c, Spot spot) {
    if (spot.place != null && spot.place.isAtLocation()) return true;
    return c.distanceTo(spot.x, spot.y) <= 14;
  }

  private int logCount(Controller c) {
    int n = 0;
    for (int id : LOGS) n += c.getInventoryItemCount(id);
    return n;
  }

  private int otherLogCount(Controller c) {
    return logCount(c) - c.getInventoryItemCount(NORMAL_LOGS);
  }

  private void fail(Controller c, String reason) {
    stopped = true;
    status = reason;
    Main.log("[Ben's Seers Woodcut] " + reason);
    c.setStatus("@red@" + reason);
    c.log(reason, "red");
    if (c.isInBank()) c.closeBank();
    c.stop();
  }

  private Mode modeFrom(String[] parameters) {
    if (parameters == null) return null;
    for (String parameter : parameters) {
      if (parameter == null) continue;
      String value = parameter.trim().toLowerCase();
      if (value.equals("bank")) return Mode.BANK;
      if (value.equals("drop")) return Mode.DROP;
      if (value.equals("light") || value.equals("burn")) return Mode.LIGHT;
    }
    return null;
  }

  private void showGui() {
    JFrame frame = new JFrame("Ben's Seers Woodcut");
    frame.setLayout(new GridLayout(0, 1, 6, 6));
    frame.add(
        new JLabel(
            "<html><body style='width:280px'>Cuts around Seers and moves up with your Woodcutting level."
                + " Light mode only burns normal logs. Oak, willow, maple, yew and magic logs cannot be lit."
                + " The best axe in your bank or inventory is used. If you have no axe, one is bought from Bob in Lumbridge."
                + " It is wielded when your Attack level allows it.</body></html>"));
    JButton bank = new JButton("Bank logs");
    JButton drop = new JButton("Drop logs");
    JButton light = new JButton("Light normal logs");
    bank.addActionListener(e -> choose(frame, Mode.BANK));
    drop.addActionListener(e -> choose(frame, Mode.DROP));
    light.addActionListener(e -> choose(frame, Mode.LIGHT));
    frame.add(bank);
    frame.add(drop);
    frame.add(light);
    frame.addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(WindowEvent e) {
            if (!started) {
              Controller c = Main.getController();
              if (c != null) c.stop();
            }
          }
        });
    frame.pack();
    frame.setLocationRelativeTo(null);
    frame.setVisible(true);
  }

  private void choose(JFrame frame, Mode chosen) {
    mode = chosen;
    started = true;
    frame.dispose();
  }

  private static final class Spot {
    final String name;
    final int level;
    final int[] trees;
    final int x;
    final int y;
    final Location place;

    Spot(String name, int level, int[] trees, int x, int y, Location place) {
      this.name = name;
      this.level = level;
      this.trees = trees;
      this.x = x;
      this.y = y;
      this.place = place;
    }
  }
}
