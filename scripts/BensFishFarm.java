import bot.Main;
import bot.ui.scriptselector.models.Category;
import bot.ui.scriptselector.models.ScriptInfo;
import com.openrsc.client.entityhandling.instances.Item;
import controller.Controller;
import java.util.concurrent.ThreadLocalRandom;
import models.entities.ItemId;
import models.entities.NpcId;
import models.entities.SkillId;
import orsc.ORSCharacter;
import scripting.idlescript.IdleScript;
import java.awt.GridLayout;
import java.util.HashSet;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * Ben's Fish Farm (was BensCatherbyFishFarm) - external copy of the built-in CatherbyFishFarm (same fishing / cooking
 * / banking loop) that gets its own tools instead of stopping. Whenever the tool it needs (net
 * for shrimp/anchovies, harpoon for tuna/swordfish at Fishing 35+ and Cooking 30+) isn't in the
 * inventory it withdraws it from Catherby bank, otherwise withdraws coins, buys one from Harry's
 * Fishing Shop (OpenRSC HarrysFishingShack: stocks Net x3 and Harpoon x2, base price 5gp each)
 * and banks the change. No harpoon obtainable: falls back to the net (bought the same way). No
 * tool and no coins: stops with a clear message. The script never uses lobster pots. The
 * original CatherbyFishFarm is left untouched.
 *
 * <p>Added (setup window, remembered by SetupMemory and copied by mass start):
 *
 * <ul>
 *   <li>Selling fish: no coins (inventory + bank) for a net/harpoon - withdraws fish from the bank
 *       (noted when the server has bank notes), sells them to Arhein's general store on the docks
 *       and buys the tool. Raw fish are sold before cooked (cooked go to the mule), cheapest
 *       first; burnt fish are worthless. No fish and no coins: falls back to the net / stops.
 *   <li>Sharks when levels allow: Fishing 76 + Cooking 80 (OpenRSC), harpoon on the Catherby
 *       "Net/Harpoon" spots, cooked on the range and banked like the other fish.
 *   <li>Muling: any cooked fish (never raw or burnt) over the threshold (bank + inventory) is
 *       traded to the mule standing in Catherby bank (run BensFishMule on it).
 *   <li>Broke failsafe: no tool, no coins and no fish to sell - walks a safe route (no aggressive
 *       NPCs) to the men in East Ardougne, pickpockets them until it has the gear money (default
 *       gear + 20gp), rests away from the men when HP gets low, walks back and buys the tool.
 *       Started away from Catherby (e.g. after a reload mid-failsafe): resumes from where it is.
 *   <li>Fishing Guild (v2): at Fishing 68 (OpenRSC DoorAction: current Fishing level 68 for door
 *       586,524) with harpoon levels, walks a safe route from Catherby, fishes tuna/swordfish
 *       (or sharks at 76/80) on the guild spots, cooks on the guild range (583,520) and banks at
 *       the guild bank chest (605,505). Muling trips walk to the
 *       mule in Catherby bank; a missing harpoon / selling / thieving go through Catherby.
 *   <li>Cross-client cap (v2): every running copy (any tab, any client process started in this
 *       IdleRSC folder) heartbeats settings/fishfarm-presence/&lt;account&gt;.json; at most N
 *       accounts fish at Catherby and N at the guild (default 25 each). Full: the other spot if
 *       allowed, else wait and recheck every minute. Heartbeats older than 2 min are ignored.
 *   <li>Certing at the guild (v3): swordfish (Orven) and sharks (Padik), raw or cooked, are
 *       certed 5:1 at the east end of the guild instead of walking to the bank; leftovers under 5
 *       wait for the next round, burnt fish are dropped, certs stay in the inventory and are
 *       banked on the next bank trip (tuna / Catherby / mule). Muling counts certs as 5 fish each
 *       and trades the cooked-fish certs to the mule. Broke with only certs: trades one back at
 *       the guild and sells the fish to Arhein.
 *   <li>Weapon (v4): when the thieving failsafe starts, the weapon in hand is taken off (saved per
 *       account in settings/weapon-guard so a reload / crash still puts it back), kept out of
 *       every deposit, and put back on when thieving ends and before the script stops.
 * </ul>
 *
 * <p>Script arguments (skip the window, e.g. for the script queue): {@code sharks=off sell=off
 * muling=on mule=xoxoxo threshold=500 thieve=off thievegp=auto resthp=50 fullhp=90 eat=off
 * guild=off catherbycap=25 guildcap=25 cert=off}
 */
public class BensFishFarm extends IdleScript {
  public static final ScriptInfo info =
      new ScriptInfo(
          new Category[] {Category.FISHING, Category.COOKING, Category.IRONMAN_SUPPORTED},
          "Author Unknown, Paint and Fixed by Seatta, harpoon buying for Ben",
          "Fishes and cooks fish in Catherby and the Fishing Guild (68+).\n\n"
              + "Fishes shrimp and anchovies to 35 fishing and 30 cooking\n"
              + "Then swaps over to catching tuna and swordfish.\n"
              + "If the net or harpoon is missing it withdraws one from the bank, or withdraws\n"
              + "coins and buys one at Harry's Fishing Shop.\n"
              + "No coins: sells fish to Arhein's general store first.\n"
              + "Sharks at Fishing 76 / Cooking 80, muling cooked fish to a mule (optional).\n"
              + "Nothing to sell: thieves men in East Ardougne for the gear money.\n"
              + "Fishing Guild at Fishing 68 (harpoon), banks at the guild bank chest.\n"
              + "Max accounts per spot across every open client (default 25 each).\n"
              + "Certs swordfish and sharks at the guild certers (5 fish = 1 cert).",
          null,
          "Ben's Fish Farm");

  final int[] rawIds = {349, 351, 366, 369, 545};
  final int[] cookedIds = {350, 352, 353, 367, 368, 370, 371, 546, 547};

  static final int COINS = ItemId.COINS.getId();
  static final int NET = ItemId.NET.getId();
  static final int HARPOON = ItemId.HARPOON.getId();
  static final int HARRY = NpcId.HARRY.getId();
  /**
   * Net and harpoon both have a 5gp base price on OpenRSC (ItemDefs.json); the shop price rises a
   * little when Harry's stock (net 3, harpoon 2) is low.
   */
  static final int TOOL_MIN_COINS = 5;
  /** Coins withdrawn for a purchase (safe margin); the change is banked again afterwards. */
  static final int TOOL_COINS_TO_WITHDRAW = 100;
  static final int[] HARRYS_SHOP = {418, 487};
  static final int[] BANK_TILE = {440, 494};

  // --- selling fish to Arhein (Catherby general store, on the docks south of the bank) ---
  static final int ARHEIN = NpcId.ARHEIN.getId();
  static final int[] ARHEIN_TILE = {440, 503};
  /** Coins to raise when broke: a tool (5gp, a bit more when Harry is low) plus a buffer. */
  static final int SELL_TARGET_COINS = 25;
  /** Bank-to-Arhein trips per purchase before giving up (never loops forever). */
  static final int MAX_SELL_TRIPS = 6;
  /** Fish we may sell: raw first, then cooked, cheapest first. Burnt fish sell for 0gp. */
  static final int[] SELL_ORDER = {349, 351, 366, 369, 545, 350, 352, 367, 370, 546};

  // --- sharks: Catherby "Net/Harpoon" spots (object 261), second option = Harpoon ---
  static final int SHARK_SPOT = 261;
  static final int[][] SHARK_SPOTS = {{406, 505}, {402, 507}, {399, 503}, {398, 505}};
  static final int SHARK_FISHING = 76;
  static final int SHARK_COOKING = 80;

  // --- muling: only cooked fish (never raw / burnt) ---
  static final int[] MULE_FISH = {350, 352, 367, 370, 546};
  static final long MULE_WAIT_MS = 120_000;

  // --- broke failsafe: pickpocket men in East Ardougne (no tool, no coins, nothing to sell) ---
  static final int[] MEN = {11, 72, 318}; // "Man" (318 in Ardougne)
  /** OpenRSC NpcLocs: three men roam 601-612,575-590 in north-west East Ardougne. */
  static final int[] MEN_SPOT = {606, 583};
  /** Out of the men's reach, on the route: where we rest and escape to. */
  static final int[] REST_SPOT = {622, 605};
  /** Gear money target when the setup window says auto: tool price + this buffer. */
  static final int GEAR_BUFFER = 20;
  static final long THIEVE_MAX_MS = 90 * 60_000L;
  /** Never pickpocket below this many HP, whatever the rest % says. */
  static final int MIN_THIEVE_HP = 8;
  /**
   * Catherby bank door -> the men. Shortest path over AIOQuester's collision map with every tile
   * within 7 of an aggressive NPC's roaming box costed out (no exposed tiles): along y 499 west of
   * Catherby, past Seers' south side, down the east side of Ardougne (clear of the bears and guard
   * dogs), round the south of the zoo (grey wolves) and up the west side of East Ardougne.
   */
  static final int[][] CATHERBY_TO_MEN = {
    {439, 499}, {449, 499}, {459, 499}, {469, 499}, {479, 499}, {489, 499}, {499, 499}, {509, 500},
    {519, 500}, {529, 500}, {539, 500}, {549, 509}, {559, 519}, {569, 529}, {568, 539}, {564, 549},
    {564, 559}, {562, 569}, {554, 578}, {554, 588}, {557, 598}, {558, 608}, {562, 618}, {568, 625},
    {571, 635}, {580, 643}, {590, 643}, {600, 643}, {610, 636}, {620, 633}, {622, 625}, {622, 615},
    {622, 605}, {616, 595}, {610, 585}, {606, 583}
  };
  static final int[][] MEN_TO_CATHERBY = reversed(CATHERBY_TO_MEN);
  static final int[][] MEN_TO_REST = {{606, 583}, {610, 585}, {616, 595}, {622, 605}};

  // --- Fishing Guild (OpenRSC data: DoorAction DOOR_FISHING_GUILD, SceneryLocs, ObjectFishing.xml) ---
  static final int GUILD_FISHING = 68;
  /** Boundary 112 (direction 0): standing on 586,524 and opening it puts us on 586,523 (inside). */
  static final int[] GUILD_DOOR = {586, 524};
  static final int[] GUILD_RANGE = {583, 520};
  /** Bank chest (id 942) at the east end of the guild, next to the certers. */
  static final int BANK_CHEST = 942;
  static final int[] GUILD_BANK_CHEST = {605, 505};
  /** Net/Harpoon spots (261): option 2 = harpoon = sharks. */
  static final int[][] GUILD_SHARK_SPOTS = {{585, 496}, {588, 496}, {585, 498}, {588, 498}, {593, 501}};
  /** Harpoon/Cage spots (194): option 1 = harpoon = tuna/swordfish. */
  static final int GUILD_HARPOON_SPOT = 194;
  static final int[][] GUILD_HARPOON_SPOTS = {{585, 505}, {588, 505}, {596, 501}};
  /** Cage/Harpoon spots (376): option 2 = harpoon = tuna/swordfish. */
  static final int GUILD_CAGE_SPOT = 376;
  static final int[][] GUILD_CAGE_SPOTS = {{588, 500}, {589, 501}};
  /**
   * Catherby bank door -> outside the guild door. Shortest path over AIOQuester's collision map
   * with every tile within 7 of an aggressive NPC's roaming box costed out: 0 exposed tiles.
   */
  static final int[][] CATHERBY_TO_GUILD = {
    {439, 499}, {446, 500}, {456, 500}, {466, 500}, {476, 505}, {486, 505}, {496, 510}, {506, 519},
    {516, 523}, {526, 523}, {536, 523}, {546, 523}, {556, 523}, {566, 527}, {576, 527}, {586, 526}
  };
  static final int[][] GUILD_TO_CATHERBY = reversed(CATHERBY_TO_GUILD);
  /** Outside the guild door -> East Ardougne north bank (same safe planner, 0 exposed tiles). */
  static final int[][] GUILD_TO_ARDY_BANK = {{586, 526}, {586, 537}, {586, 547}, {587, 556}, {587, 566}, {581, 573}};
  static final int[][] ARDY_BANK_TO_GUILD = reversed(GUILD_TO_ARDY_BANK);
  static final String CATHERBY = "catherby";
  static final String GUILD = "guild";
  static final String WAITING = "waiting";

  private static int[][] reversed(int[][] r) {
    int[][] o = new int[r.length][];
    for (int i = 0; i < r.length; i++) o[i] = r[r.length - 1 - i];
    return o;
  }

  // --- setup window (remembered by SetupMemory, copied by mass start) ---
  boolean sharksEnabled = true;
  boolean sellFishEnabled = true;
  boolean mulingEnabled = false;
  String muleName = "xoxoxo";
  int muleThreshold = 500;
  boolean thieveEnabled = true;
  /** Coins (bank + inventory) to thieve up to; 0 = auto (tool price + GEAR_BUFFER). */
  int thieveTargetSetting = 0;
  int restHpPercent = 50;
  int fullHpPercent = 90;
  boolean eatFood = true;
  boolean guildEnabled = true;
  int catherbyCap = 25;
  int guildCap = 25;
  volatile boolean setupDone = false;
  volatile boolean setupCancelled = false;

  int fishSold = 0;
  int coinsMade = 0;
  int fishMuled = 0;
  long nextMuleTry = 0;
  int muleFails = 0;
  volatile boolean thievingForGear = false;
  int thieveGoal = 0;
  int thieveBanked = 0;
  int coinsThieved = 0;
  int pickpockets = 0;
  int pickFails = 0;
  int hpRests = 0;
  boolean thieveFailed = false;
  long stunnedUntil = 0;
  /** "Nothing interesting happens" while fishing: wait 2-5s instead of clicking again. */
  volatile long fishMissUntil = 0;
  boolean resting = false;
  /** When start() got past the setup window (a script queue / mass start captures it then). */
  long runStartedAt = 0;
  /** Don't stop in the first seconds after setup: the queue's set-up capture needs us running. */
  static final long MIN_RUN_BEFORE_STOP_MS = 15_000;
  /** Last "under attack" / "whilst you are fighting" message: isInCombat() can miss NPC fights. */
  volatile long fightSeenAt = 0;
  int lowestHp = Integer.MAX_VALUE;

  /** Where this account fishes now (CATHERBY / GUILD), or null while waiting for a free slot. */
  volatile String workLoc = null;
  boolean needDecision = true;
  long nextDecisionAt = 0;
  volatile String waitReason = null;
  /** The guild door refused us (Fishing drained below 68?): don't try again until then. */
  long guildRefusedUntil = 0;
  int guildTrips = 0;
  int guildBankTrips = 0;
  final Presence presence = new Presence();

  int startFishingXp;
  int startCookingXp;
  int fished = 0;
  int cooked = 0;
  /** Set when no harpoon could be got; the script then keeps net fishing. */
  boolean harpoonUnavailable = false;
  /** Why the last getTool() failed: true when it was for lack of coins. */
  boolean lastToolFailNoCoins = false;

  private Controller c() {
    return Main.getController();
  }

  private void log(String s) {
    Main.log("[Ben's Fish Farm] " + s);
    Controller c = c();
    if (c != null) c.setStatus("@cya@" + s);
  }

  /** Tells the client's stall watch we're waiting on purpose (busy shop, NPC or mule). */
  private void notStalled(long ms) {
    try {
      bot.probe.Probe.noteIntentionalWait(ms);
    } catch (Throwable ignored) {
      // older clients have no stall watch
    }
  }

  /** True when the script wants to fish with the harpoon (tuna/swordfish). */
  private boolean useHarpoon() {
    Controller c = c();
    return !harpoonUnavailable
        && c.getBaseStat(SkillId.FISHING.getId()) >= 35
        && c.getBaseStat(SkillId.COOKING.getId()) >= 30;
  }

  private int toolId() {
    return useHarpoon() ? HARPOON : NET;
  }

  /** True when sharks are on and the levels allow (Fishing 76, Cooking 80). */
  private boolean useSharks() {
    Controller c = c();
    return sharksEnabled
        && useHarpoon()
        && c.getBaseStat(SkillId.FISHING.getId()) >= SHARK_FISHING
        && c.getBaseStat(SkillId.COOKING.getId()) >= SHARK_COOKING;
  }

  public int start(String[] param) {
    Controller controller = c();
    if (controller == null) return 1000;

    migrateSavedSettings();
    if (!setup(param)) return 1000;
    runStartedAt = System.currentTimeMillis();
    presence.start(controller);
    weapon.load();
    if (weapon.remembered >= 0) weapon.putBack(false);
    log("Settings: sharks " + (sharksEnabled ? "on" : "off") + ", sell fish " + (sellFishEnabled ? "on" : "off")
        + ", muling " + (mulingEnabled ? "on (mule '" + muleName + "', over " + muleThreshold + ")" : "off")
        + ", thieving failsafe " + (thieveEnabled ? "on (until " + thieveTarget() + "gp, rest at " + restHpPercent
            + "% HP until " + fullHpPercent + "%, eat " + (eatFood ? "on" : "off") + ")" : "off")
        + ", guild " + (guildEnabled ? "on" : "off") + ", max " + catherbyCap + " at Catherby / " + guildCap + " at the guild"
        + ", cert at the guild " + (certEnabled ? "on" : "off"));
    startFishingXp = controller.getStatXp(SkillId.FISHING.getId());
    startCookingXp = controller.getStatXp(SkillId.COOKING.getId());
    paintBuilder.start(4, 18, 220);
    resumeIfAway();
    while (controller.isRunning()) {
      if (controller.getNeedToMove()) controller.moveCharacter();
      if (controller.getShouldSleep()) controller.sleepHandler(true);

      if (needDecision || System.currentTimeMillis() >= nextDecisionAt) {
        decideLocation();
        needDecision = false;
        nextDecisionAt = System.currentTimeMillis() + 15 * 60_000L;
      }
      if (workLoc == null) {
        waitForSlot();
        needDecision = true;
        continue;
      }
      if (GUILD.equals(workLoc)) {
        guildStep();
        continue;
      }
      if (!nearCatherby()) {
        travelToCatherby();
        continue;
      }

      int toolId = toolId();
      if (controller.getInventoryItemCount(toolId) < 1) {
        controller.setStatus(String.format("Withdrawing %s", controller.getItemName(toolId)));
        bank();
        continue; // re-evaluate the tool (a harpoon may have been bought, or we fell back to the net)
      }

      if (controller.getInventoryItemCount() < 30) {
        if (!useHarpoon()) {
          if (controller.currentX() != 418 || controller.currentY() != 499)
            controller.walkTo(418, 499);
          controller.atObject(418, 500);
        } else if (useSharks()) {
          fishSharks();
        } else {
          if (System.currentTimeMillis() < fishMissUntil) {
            controller.sleep(400);
            continue;
          }
          if (controller.currentX() != 409 || controller.currentY() != 503)
            controller.walkTo(409, 503);
          if (controller.getObjectAtCoord(409, 504) > 0) controller.atObject(409, 504);
          else controller.sleep(600);
        }

        controller.sleep(500);
        while (controller.isBatching() && controller.getInventoryItemCount() < 30)
          controller.sleep(10);
      } else {
        cook();

        bank();
        needDecision = true;
        if (GUILD.equals(workLoc) || needDecisionNow()) continue; // re-decide before walking back

        walkBack();
      }
    }

    return 1000; // start() must return a int value now.
  }

  public void cook() {
    Controller controller = c();
    controller.walkTo(419, 499);
    controller.walkTo(428, 498);
    controller.walkTo(436, 493);
    controller.walkTo(435, 486);

    openCookDoor();

    cookAllOn(432, 480);

    controller.walkTo(435, 485);
    openCookDoor();
  }

  public void openDoor() {
    Controller controller = c();
    while (controller.getObjectAtCoord(439, 497) == 64) {
      controller.atObject(439, 497);
      controller.sleep(100);
    }
  }

  public void openCookDoor() {
    Controller controller = c();
    while (!controller.isDoorOpen(435, 486)) {
      controller.openDoor(435, 486);
    }
  }

  public void bank() {
    Controller controller = c();
    controller.walkTo(439, 497);
    openDoor();

    openNearbyBank();

    int toolId = toolId();

    for (Item item : controller.getInventoryItems()) {
      int id = item.getItemDef().id;
      if (weapon.isProtected(id)) continue; // the weapon taken off for thieving
      if (id == toolId) continue;

      controller.depositItem(id, controller.getInventoryItemCount(id));
      controller.sleep(180);
    }

    if (controller.getInventoryItemCount(toolId) < 1) ensureTool(toolId);

    if (!controller.isRunning()) return;
    rememberBank();
    muleIfNeeded();
    if (!controller.isInBank()) {
      walkNear(BANK_TILE[0], BANK_TILE[1], 2, 120_000);
      openDoor();
    }
    if (controller.isInBank()) {
      controller.closeBank();
      controller.sleep(640);
    }
    controller.walkTo(439, 496);
    openDoor();
  }

  public void walkBack() {
    Controller controller = c();
    controller.walkTo(436, 497);
    controller.walkTo(430, 497);
    controller.walkTo(423, 496);
    controller.walkTo(419, 499);
  }

  // ---------------------------------------------------------------------------------------------
  // Tools (net / harpoon): bank first, else buy at Harry's Fishing Shop with banked coins
  // ---------------------------------------------------------------------------------------------

  private String name(int id) {
    return id == HARPOON ? "harpoon" : id == NET ? "net" : c().getItemName(id);
  }

  /**
   * Makes sure the tool for the current phase is in the inventory (bank open on entry). A harpoon
   * that can't be got falls back to the net; no tool at all stops the script with the reason.
   */
  private void ensureTool(int toolId) {
    Controller controller = c();
    if (getTool(toolId)) return;
    if (toolId == HARPOON) {
      harpoonUnavailable = true;
      log("No harpoon available - falling back to net fishing (restart the script to try buying again)");
      if (getTool(NET)) return;
    }
    String why =
        lastToolFailNoCoins
            ? "no net/harpoon in the inventory or bank and not enough coins to buy one (need ~"
                + TOOL_MIN_COINS + "gp)"
            : "no net/harpoon in the inventory or bank and couldn't buy one at Harry's";
    controller.log("Missing fishing tool: " + why, "red");
    controller.log("Stopping script", "red");
    log("Stopping: " + why);
    stopScript();
  }

  /** Gets one of the tool: from the bank if it's there, else buys it. Bank open on entry. */
  private boolean getTool(int toolId) {
    Controller controller = c();
    lastToolFailNoCoins = false;
    if (controller.getInventoryItemCount(toolId) > 0) return true;
    if (!controller.isInBank()) {
      walkNear(BANK_TILE[0], BANK_TILE[1], 2, 120_000);
      openDoor();
      openNearbyBank();
      waitFor(controller::isInBank, 8000);
    }
    if (controller.isInBank() && controller.getBankItemCount(toolId) >= 1) {
      log("Withdrawing the " + name(toolId) + " from the bank");
      controller.withdrawItem(toolId, 1);
      waitFor(() -> controller.getInventoryItemCount(toolId) > 0, 5000);
      if (controller.getInventoryItemCount(toolId) > 0) return true;
    }
    return buyTool(toolId);
  }

  private boolean buyTool(int toolId) {
    Controller controller = c();
    String tool = name(toolId);
    log("No " + tool + " in the inventory or bank - going to buy one at Harry's");
    if (sellFishEnabled && controller.isInBank()
        && controller.getBankItemCount(COINS) + controller.getInventoryItemCount(COINS) < TOOL_MIN_COINS) {
      log("Not enough coins for a " + tool + " - selling fish to Arhein first");
      sellFishForCoins(SELL_TARGET_COINS);
    }
    if (thieveEnabled && !thieveFailed && controller.isInBank()
        && controller.getBankItemCount(COINS) + controller.getInventoryItemCount(COINS) < TOOL_MIN_COINS) {
      log("No coins and no fish to sell for a " + tool + " - thieving men in East Ardougne for it");
      if (!thieveForGear()) thieveFailed = true;
    }
    int banked = controller.isInBank() ? controller.getBankItemCount(COINS) : 0;
    int carried = controller.getInventoryItemCount(COINS);
    if (banked + carried < TOOL_MIN_COINS) {
      log("Not enough coins for a " + tool + " (bank " + banked + ", inventory " + carried
          + ", need ~" + TOOL_MIN_COINS + "gp)");
      lastToolFailNoCoins = true;
      return false;
    }
    int want = Math.min(TOOL_COINS_TO_WITHDRAW - carried, banked);
    if (want > 0) {
      log("Withdrawing " + want + " coins for the " + tool);
      controller.withdrawItem(COINS, want);
      waitFor(() -> controller.getInventoryItemCount(COINS) >= carried + want, 5000);
    }
    controller.closeBank();
    controller.sleep(640);

    log("Walking to Harry's Fishing Shop");
    boolean bought = false;
    if (!walkNear(HARRYS_SHOP[0], HARRYS_SHOP[1], 3, 120_000)) {
      log("Couldn't reach Harry's Fishing Shop at " + HARRYS_SHOP[0] + "," + HARRYS_SHOP[1]);
    } else {
      bought = buyFromHarry(toolId);
    }
    returnCoinsToBank();
    log(bought ? "Bought a " + tool + " - resuming fishing" : "Couldn't buy a " + tool);
    return bought;
  }

  private boolean buyFromHarry(int toolId) {
    Controller controller = c();
    String tool = name(toolId);
    if (!openHarrysShop()) {
      log("Couldn't open Harry's shop");
      return false;
    }
    long until = System.currentTimeMillis() + 30_000;
    while (controller.isRunning()
        && controller.getInventoryItemCount(toolId) < 1
        && System.currentTimeMillis() < until) {
      if (controller.getShopItemCount(toolId) < 1) {
        log("Harry is out of " + tool + "s, waiting for restock");
        notStalled(5000);
        controller.sleep(2000);
        continue;
      }
      int price = controller.getShopItemPrice(toolId);
      int coins = controller.getInventoryItemCount(COINS);
      log(tool + " price at Harry's: " + price + "gp (carrying " + coins + ")");
      if (coins < price) {
        log("Not enough coins for the " + tool + " (" + coins + " < " + price + ")");
        lastToolFailNoCoins = true;
        break;
      }
      log("Buying 1 " + tool);
      controller.shopBuy(toolId, 1);
      waitFor(() -> controller.getInventoryItemCount(toolId) > 0, 4000);
    }
    controller.closeShop();
    controller.sleep(640);
    return controller.getInventoryItemCount(toolId) > 0;
  }

  private boolean openHarrysShop() {
    return openShop(HARRY, "Harry");
  }

  /**
   * Opens an NPC's shop. Coleslaw shopkeepers have a right-click Trade option. Uranium does not
   * (same as the bankers): talk, then choose the shop line once that menu is actually on screen.
   * Harry: "Let's see what you've got then". Arhein: "Yes ok".
   */
  private boolean openShop(int npcId, String who) {
    Controller controller = c();
    boolean talkOnly = !"Bank".equalsIgnoreCase(controller.getNpcCommand1(95));
    for (int attempt = 0; attempt < 6 && controller.isRunning(); attempt++) {
      if (controller.isInShop()) return true;
      if (talkOnly && controller.isInOptionMenu()) {
        if (chooseShopOption(controller) && waitFor(controller::isInShop, 8000)) return true;
        controller.sleep(300);
        continue;
      }
      ORSCharacter keeper = controller.getNearestNpcById(npcId, false);
      if (keeper == null) {
        log(who + " not in sight, waiting (attempt " + (attempt + 1) + "/6)");
        notStalled(5000);
        controller.sleep(1500);
        continue;
      }
      String cmd = "";
      try {
        cmd = controller.getNpcCommand1(npcId);
      } catch (RuntimeException ignored) {
      }
      log("Opening " + who + "'s shop" + (attempt > 0 ? " (attempt " + (attempt + 1) + ")" : ""));
      if (!talkOnly && "Trade".equalsIgnoreCase(cmd)) {
        controller.npcCommand1(keeper.serverIndex);
        notStalled(15_000);
        if (waitFor(controller::isInShop, 10000)) return true;
      } else {
        controller.talkToNpc(keeper.serverIndex);
        if (waitFor(controller::isInOptionMenu, 12000) && chooseShopOption(controller)) {
          notStalled(15_000);
          if (waitFor(controller::isInShop, 8000)) return true;
        }
      }
    }
    return controller.isInShop();
  }

  /** Picks the shop line. Does nothing if the menu text is not loaded yet. */
  private boolean chooseShopOption(Controller controller) {
    String[] opts = controller.getOptionsMenuText();
    int n = Math.min(controller.getOptionMenuCount(), opts == null ? 0 : opts.length);
    for (int i = 0; i < n; i++) {
      String o = opts[i] == null ? "" : opts[i].toLowerCase();
      if (o.contains("see what") || o.contains("yes ok") || o.contains("trade") || o.contains("buy")) {
        controller.optionAnswer(i);
        return true;
      }
    }
    return false;
  }

  /** Banks the change after shopping so it doesn't use a fishing slot. */
  private void returnCoinsToBank() {
    Controller controller = c();
    if (controller.getInventoryItemCount(COINS) < 1) return;
    log("Walking back to Catherby bank to deposit the leftover coins");
    walkNear(BANK_TILE[0], BANK_TILE[1], 2, 120_000);
    openNearbyBank();
    if (controller.isInBank()) {
      controller.depositItem(COINS, controller.getInventoryItemCount(COINS));
      controller.sleep(640);
    }
  }

  private boolean walkNear(int x, int y, int radius, long timeoutMs) {
    Controller controller = c();
    long until = System.currentTimeMillis() + timeoutMs;
    int fails = 0;
    while (controller.isRunning() && System.currentTimeMillis() < until) {
      if (Math.max(Math.abs(controller.currentX() - x), Math.abs(controller.currentY() - y)) <= radius)
        return true;
      if (controller.walkTowards(x, y)) fails = 0;
      else if (++fails >= 5) return false;
      controller.sleep(300);
    }
    return Math.max(Math.abs(controller.currentX() - x), Math.abs(controller.currentY() - y)) <= radius;
  }

  private boolean waitFor(java.util.function.BooleanSupplier cond, long ms) {
    Controller controller = c();
    long until = System.currentTimeMillis() + ms;
    while (controller.isRunning() && System.currentTimeMillis() < until) {
      if (cond.getAsBoolean()) return true;
      controller.sleep(250);
    }
    return cond.getAsBoolean();
  }

  // ---------------------------------------------------------------------------------------------
  // Sharks: harpoon on the Catherby Net/Harpoon spots (Fishing 76, Cooking 80)
  // ---------------------------------------------------------------------------------------------

  private void fishSharks() {
    Controller controller = c();
    int[] spot = null;
    for (int[] s : SHARK_SPOTS) {
      if (controller.getObjectAtCoord(s[0], s[1]) == SHARK_SPOT) {
        spot = s;
        break;
      }
    }
    if (spot == null) {
      int[] near = controller.getNearestObjectById(SHARK_SPOT);
      if (near != null) spot = near;
    }
    if (spot == null) {
      log("No shark spot in sight - walking to the Catherby harpoon spots");
      controller.walkTo(406, 504);
      controller.sleep(1000);
      return;
    }
    if (Math.max(Math.abs(controller.currentX() - spot[0]), Math.abs(controller.currentY() - spot[1])) > 2)
      controller.walkTo(spot[0], spot[1] - 1);
    controller.setStatus("@cya@Harpooning sharks");
    controller.atObject2(spot[0], spot[1]);
  }

  // ---------------------------------------------------------------------------------------------
  // Selling fish to Arhein (Catherby general store) when we can't afford a tool
  // ---------------------------------------------------------------------------------------------

  private int baseValue(int id) {
    switch (id) {
      case 349: case 350: return 5; // shrimp
      case 351: case 352: return 15; // anchovies
      case 366: case 367: return 100; // tuna
      case 369: case 370: return 200; // swordfish
      case 545: case 546: return 300; // shark
      default: return 1;
    }
  }

  private int totalCoins() {
    Controller controller = c();
    return controller.getInventoryItemCount(COINS)
        + (controller.isInBank() ? controller.getBankItemCount(COINS) : 0);
  }

  /** The fish to sell next: raw before cooked, cheapest first, skipping ones that sold for 0. */
  private int pickFishToSell(Set<Integer> worthless) {
    Controller controller = c();
    for (int id : SELL_ORDER) {
      if (worthless.contains(id)) continue;
      if (controller.getBankItemCount(id) > 0 || controller.getInventoryItemCount(id) > 0) return id;
    }
    return -1;
  }

  private void openBankHere() {
    Controller controller = c();
    if (controller.isInBank()) return;
    walkNear(BANK_TILE[0], BANK_TILE[1], 2, 120_000);
    openDoor();
    openNearbyBank();
    waitFor(controller::isInBank, 8000);
  }

  /**
   * Coleslaw bankers have a right-click Bank option, so the client opener is used there. Uranium
   * does not: the banker must be talked to, and the bank option has to be chosen after the menu
   * is actually on screen. Choosing it earlier is ignored and the banker just says hello again.
   */
  private void openNearbyBank() {
    Controller c = c();
    if (c.isInBank()) return;
    if ("Bank".equalsIgnoreCase(c.getNpcCommand1(95))) {
      c.openBank();
      return;
    }
    while (!c.isInBank() && c.isRunning()) {
      if (c.isInOptionMenu()) {
        chooseBankOption(c);
        c.sleepUntil(c::isInBank, 8000);
        continue;
      }
      ORSCharacter banker = c.getNearestNpcByIds(c.bankerIds, false);
      if (banker == null) {
        c.sleep(600);
        continue;
      }
      c.talkToNpc(banker.serverIndex);
      c.sleepUntil(c::isInOptionMenu, 8000);
    }
  }

  private void chooseBankOption(Controller c) {
    int idx = 0;
    int n = c.getOptionMenuCount();
    for (int i = 0; i < n; i++) {
      String s = c.getOptionsMenuText(i);
      if (s != null && s.toLowerCase().contains("access my bank")) {
        idx = i;
        break;
      }
    }
    c.optionAnswer(idx);
  }

  /**
   * Withdraws fish, sells them to Arhein and comes back to the bank until there are {@code target}
   * coins (inventory + bank) or no sellable fish are left. Bank open on entry and on return (coins
   * are kept in the inventory). @return true when the target was reached
   */
  private boolean sellFishForCoins(int target) {
    Controller controller = c();
    Set<Integer> worthless = new HashSet<>();
    for (int trip = 1; trip <= MAX_SELL_TRIPS && controller.isRunning(); trip++) {
      openBankHere();
      if (!controller.isInBank()) {
        log("Couldn't open the bank to get fish to sell");
        return false;
      }
      int have = totalCoins();
      if (have >= target) return true;
      int fish = pickFishToSell(worthless);
      if (fish < 0 && certsUncerted < 3 && uncertForSelling()) {
        openBankHere();
        fish = pickFishToSell(worthless);
      }
      if (fish < 0) {
        log("No fish in the bank to sell to Arhein (coins: " + have + ")");
        return false;
      }
      int need = target - have;
      int each = Math.max(1, baseValue(fish) * 40 / 100 / 2); // general store pays ~40%, less as stock grows
      int carried = controller.getInventoryItemCount(fish);
      int amount = Math.min(controller.getBankItemCount(fish), need / each + 3);
      boolean notes = orsc.Config.S_WANT_BANK_NOTES;
      if (!notes) amount = Math.min(amount, 30 - controller.getInventoryItemCount());
      if (amount > 0) {
        log("Sell trip " + trip + ": withdrawing " + amount + (notes ? " noted " : " ")
            + controller.getItemName(fish) + " (need " + need + " more coins)");
        if (notes) controller.withdrawItemAsNote(fish, amount);
        else controller.withdrawItem(fish, amount);
        final int want = carried + amount;
        final int sf = fish;
        waitFor(() -> controller.getInventoryItemCount(sf) >= want, 5000);
      }
      if (controller.getInventoryItemCount(fish) < 1) {
        worthless.add(fish);
        continue;
      }
      controller.closeBank();
      controller.sleep(640);
      controller.walkTo(439, 497);
      openDoor();
      log("Walking to Arhein's general store");
      int gained = 0;
      if (walkNear(ARHEIN_TILE[0], ARHEIN_TILE[1], 3, 120_000)) {
        gained = sellToArhein(fish, controller.getInventoryItemCount(COINS) + need);
      } else {
        log("Couldn't reach Arhein at " + ARHEIN_TILE[0] + "," + ARHEIN_TILE[1]);
      }
      if (gained <= 0) worthless.add(fish);
      walkNear(BANK_TILE[0], BANK_TILE[1], 2, 120_000);
      openDoor();
      openBankHere();
      for (Item item : controller.getInventoryItems()) { // unsold fish back in the bank
        int id = item.getItemDef().id;
        if (weapon.isProtected(id)) continue; // the weapon taken off for thieving
        if (id == COINS || id == NET || id == HARPOON) continue;
        controller.depositItem(id, controller.getInventoryItemCount(id));
        controller.sleep(180);
      }
    }
    boolean ok = totalCoins() >= target;
    if (!ok) log("Still short of coins after selling (" + totalCoins() + "/" + target + ")");
    return ok;
  }

  /** Sells a fish to Arhein until we carry {@code coinGoal} coins. @return coins gained */
  private int sellToArhein(int fish, int coinGoal) {
    Controller controller = c();
    if (!openShop(ARHEIN, "Arhein")) {
      log("Couldn't open Arhein's shop");
      return 0;
    }
    String name = controller.getItemName(fish);
    int startCoins = controller.getInventoryItemCount(COINS);
    int misses = 0;
    while (controller.isRunning()
        && controller.isInShop()
        && controller.getInventoryItemCount(fish) > 0
        && controller.getInventoryItemCount(COINS) < coinGoal) {
      int before = controller.getInventoryItemCount(fish);
      int coinsBefore = controller.getInventoryItemCount(COINS);
      int each = Math.max(1, baseValue(fish) * 30 / 100); // a bit under what Arhein pays at first
      int n = Math.max(1, Math.min(Math.min(before, 5), (coinGoal - coinsBefore + each - 1) / each));
      controller.shopSell(fish, n);
      waitFor(() -> controller.getInventoryItemCount(fish) < before, 4000);
      int sold = before - controller.getInventoryItemCount(fish);
      if (sold <= 0) {
        if (++misses >= 3) {
          log("Arhein isn't taking the " + name);
          break;
        }
        continue;
      }
      misses = 0;
      int got = controller.getInventoryItemCount(COINS) - coinsBefore;
      fishSold += sold;
      coinsMade += Math.max(0, got);
      log("Sold " + sold + " " + name + " for " + got + "gp (carrying " + controller.getInventoryItemCount(COINS) + "/" + coinGoal + ")");
      if (got <= 0) {
        log(name + " now sells for 0gp at Arhein (his stock is high) - trying another fish");
        break;
      }
    }
    controller.closeShop();
    controller.sleep(640);
    return controller.getInventoryItemCount(COINS) - startCoins;
  }

  // ---------------------------------------------------------------------------------------------
  // Muling: trade cooked fish (never raw / burnt) over the threshold to the mule in Catherby bank
  // ---------------------------------------------------------------------------------------------

  private static String norm(String n) {
    return n == null ? "" : n.replace('\u00a0', ' ').replace('_', ' ').trim().toLowerCase();
  }

  private ORSCharacter findMule() {
    Controller controller = c();
    String want = norm(muleName);
    if (want.isEmpty() || want.equals(norm(controller.getPlayerName()))) return null;
    for (ORSCharacter p : controller.getPlayers()) {
      if (p != null && want.equals(norm(p.displayName))) return p;
    }
    return null;
  }

  /** Bank open on entry; leaves it open. Trades cooked fish over the threshold to the mule. */
  private void muleIfNeeded() {
    Controller controller = c();
    if (!mulingEnabled || System.currentTimeMillis() < nextMuleTry || !controller.isInBank()) return;
    int fish = -1;
    int count = 0;
    for (int id : MULE_FISH) {
      int n = ownedWithCerts(id, true); // a certificate counts as 5 fish
      if (n > muleThreshold && n > count) {
        fish = id;
        count = n;
      }
    }
    if (fish < 0) return;
    String name = controller.getItemName(fish);
    log(count + " " + name + " (over " + muleThreshold + ") - muling them to " + muleName);
    long until = System.currentTimeMillis() + MULE_WAIT_MS;
    while (controller.isRunning() && findMule() == null && System.currentTimeMillis() < until) {
      controller.setStatus("@cya@Waiting for mule " + muleName + " in the bank ("
          + (until - System.currentTimeMillis()) / 1000 + "s)");
      notStalled(5000);
      controller.sleep(1000);
    }
    if (findMule() == null) {
      muleFails++;
      long backoff = Math.min(30, 5L << Math.min(3, muleFails - 1)) * 60_000L;
      nextMuleTry = System.currentTimeMillis() + backoff;
      log("Mule " + muleName + " isn't in Catherby bank - fishing on, trying again in " + backoff / 60_000 + " min");
      return;
    }
    boolean notes = orsc.Config.S_WANT_BANK_NOTES;
    int moved = 0;
    int cert = certOf(fish);
    if (cert > 0 && controller.getBankItemCount(cert) + controller.getInventoryItemCount(cert) > 0) {
      // certificates first: one stack, one trade
      openBankHere();
      if (controller.isInBank()) {
        for (Item item : controller.getInventoryItems()) {
          int id = item.getItemDef().id;
          if (weapon.isProtected(id)) continue; // the weapon taken off for thieving
          if (id == cert || id == NET || id == HARPOON) continue;
          controller.depositItem(id, controller.getInventoryItemCount(id));
          controller.sleep(180);
        }
        int inBank = controller.getBankItemCount(cert);
        int carried = controller.getInventoryItemCount(cert);
        if (inBank > 0) {
          controller.withdrawItem(cert, inBank);
          final int want = carried + inBank;
          waitFor(() -> controller.getInventoryItemCount(cert) >= want, 5000);
        }
        int offer = controller.getInventoryItemCount(cert);
        if (offer > 0) {
          controller.closeBank();
          controller.sleep(640);
          stepNextToMule();
          int done = tradeToMule(cert, offer, false);
          if (done > 0) {
            moved += done * 5;
            fishMuled += done * 5;
            certsMuled += done;
            log("Muled " + done + " " + controller.getItemName(cert) + " (= " + (done * 5) + " " + name + ") to " + muleName);
          }
        }
      }
    }
    for (int round = 0; round < 60 && controller.isRunning(); round++) {
      openBankHere();
      if (!controller.isInBank()) break;
      for (Item item : controller.getInventoryItems()) { // only this cooked fish goes in the trade
        int id = item.getItemDef().id;
        if (weapon.isProtected(id)) continue; // the weapon taken off for thieving
        if (id == fish || id == NET || id == HARPOON) continue;
        controller.depositItem(id, controller.getInventoryItemCount(id));
        controller.sleep(180);
      }
      int inBank = controller.getBankItemCount(fish);
      int carried = controller.getInventoryItemCount(fish);
      if (inBank + carried <= 0) break; // (all certs, no loose fish)
      int amount = notes ? inBank : Math.min(Math.min(12 - carried, inBank), 30 - controller.getInventoryItemCount());
      if (amount > 0) {
        if (notes) controller.withdrawItemAsNote(fish, amount);
        else controller.withdrawItem(fish, amount);
        final int want = carried + amount;
        final int f = fish;
        waitFor(() -> controller.getInventoryItemCount(f) >= want, 5000);
      }
      int offer = Math.min(controller.getInventoryItemCount(fish), notes ? Integer.MAX_VALUE : 12);
      if (offer <= 0) break;
      controller.closeBank();
      controller.sleep(640);
      stepNextToMule(); // also ends the server-side bank session (a trade can't start in the bank screen)
      int done = tradeToMule(fish, offer, notes && controller.getNotedInventoryItemCount(fish) > 0);
      if (done <= 0) break;
      moved += done;
      fishMuled += done;
      log("Muled " + done + " " + name + " to " + muleName + " (" + moved + " this trip)");
      if (notes) break;
    }
    openBankHere();
    if (controller.isInBank()) { // anything not traded goes back
      for (Item item : controller.getInventoryItems()) {
        int id = item.getItemDef().id;
        if (weapon.isProtected(id)) continue; // the weapon taken off for thieving
        if (id == NET || id == HARPOON || id == toolId()) continue;
        controller.depositItem(id, controller.getInventoryItemCount(id));
        controller.sleep(180);
      }
    }
    rememberBank();
    if (moved > 0) {
      muleFails = 0;
      nextMuleTry = 0;
    } else {
      muleFails++;
      long backoff = Math.min(10, 2L * muleFails) * 60_000L; // the mule was there: retry fairly soon
      nextMuleTry = System.currentTimeMillis() + backoff;
      log("Muling " + name + " didn't complete - trying again in " + backoff / 60_000 + " min");
    }
  }

  /** Walks next to the mule (a step also closes the bank on the server). */
  private void stepNextToMule() {
    Controller controller = c();
    ORSCharacter mule = findMule();
    int[] at = mule == null ? null : controller.getPlayerCoordsByServerIndex(mule.serverIndex);
    if (at != null && at[0] < 0) at = null;
    int tx = at != null ? at[0] : BANK_TILE[0];
    int ty = at != null ? at[1] + 1 : BANK_TILE[1];
    if (controller.currentX() == tx && controller.currentY() == ty) tx += (tx < 442 ? 1 : -1);
    controller.walkTo(tx, ty);
    controller.sleep(640);
  }

  /** One trade of {@code amount} of a cooked fish to the mule. @return how many left our inventory */
  private int tradeToMule(int fish, int amount, boolean noted) {
    Controller controller = c();
    ORSCharacter mule = findMule();
    if (mule == null) {
      log("Mule " + muleName + " not in sight");
      return 0;
    }
    int before = controller.getInventoryItemCount(fish);
    log("Trading " + muleName + ": " + amount + " " + controller.getItemName(fish));
    // The mule trades one farmer at a time: ask again every few seconds while it's busy
    long askUntil = System.currentTimeMillis() + 90_000;
    while (controller.isRunning() && !controller.isInTrade() && System.currentTimeMillis() < askUntil) {
      ORSCharacter m = findMule();
      if (m == null) break;
      controller.tradePlayer(m.serverIndex);
      long next = System.currentTimeMillis() + 5000;
      while (controller.isRunning() && !controller.isInTrade() && System.currentTimeMillis() < next) {
        controller.setStatus("@cya@Waiting for " + muleName + " to take the trade ("
            + (askUntil - System.currentTimeMillis()) / 1000 + "s)");
        notStalled(5000);
        controller.sleep(250);
      }
    }
    if (!controller.isInTrade()) {
      log(muleName + " didn't accept the trade request (busy, or BensFishMule isn't running?)");
      return 0;
    }
    if (!norm(controller.getTradeRecipientName()).equals(norm(muleName))) {
      log("In a trade with " + controller.getTradeRecipientName() + ", not the mule - declining");
      controller.declineTrade();
      return 0;
    }
    controller.setTradeItems(new int[] {fish}, new int[] {amount}, noted);
    waitFor(() -> controller.getLocalTradeItemsCount() > 0, 5000);
    long until = System.currentTimeMillis() + 60_000;
    long lastAccept = 0;
    while (controller.isRunning() && controller.isInTrade() && System.currentTimeMillis() < until) {
      for (Item it : controller.getLocalTradeItems()) { // only that cooked fish, never anything else
        if (it.getCatalogID() != fish) {
          log("Unexpected item in our trade offer - declining");
          controller.declineTrade();
          return 0;
        }
      }
      if (controller.getRecipientTradeItemsCount() > 0) {
        log("The mule offered items - declining (it should offer nothing)");
        controller.declineTrade();
        return 0;
      }
      if (System.currentTimeMillis() - lastAccept > 2000) {
        if (controller.isInTradeConfirmation()) controller.acceptTradeConfirmation();
        else if (controller.getLocalTradeItemsCount() > 0) controller.acceptTrade();
        lastAccept = System.currentTimeMillis();
      }
      controller.setStatus("@cya@Trading " + muleName + (controller.isInTradeConfirmation() ? " (confirming)" : ""));
      notStalled(5000);
      controller.sleep(250);
    }
    if (controller.isInTrade()) {
      log("Trade with " + muleName + " timed out - declining");
      controller.declineTrade();
      controller.sleep(1000);
    }
    controller.sleep(1200);
    int gone = before - controller.getInventoryItemCount(fish);
    if (gone <= 0) log("Trade with " + muleName + " didn't complete");
    return Math.max(0, gone);
  }

  // ---------------------------------------------------------------------------------------------
  // Broke failsafe: no tool, no coins, nothing to sell - pickpocket men in East Ardougne
  // ---------------------------------------------------------------------------------------------

  /** Coins (bank + inventory) to have before walking back: the setup value or tool + buffer. */
  private int thieveTarget() {
    return thieveTargetSetting > 0 ? thieveTargetSetting : TOOL_MIN_COINS + GEAR_BUFFER;
  }

  private int hpNow() {
    int h = c().getCurrentStat(SkillId.HITS.getId());
    if (thievingForGear && h > 0 && h < lowestHp) lowestHp = h;
    return h;
  }

  private int hpMax() {
    return Math.max(1, c().getBaseStat(SkillId.HITS.getId()));
  }

  /**
   * At or below the rest threshold, or under MIN_THIEVE_HP: a failed pickpocket costs up to 3 rounds
   * of the man's hits before RSC lets you retreat (we saw 3 HP lost per fail), so a fail never
   * takes us below ~5 HP.
   */
  private boolean hpLow() {
    int h = hpNow();
    return h * 100 <= restHpPercent * hpMax() || h < MIN_THIEVE_HP;
  }

  private boolean fighting() {
    return c().isInCombat() || System.currentTimeMillis() - fightSeenAt < 5000;
  }

  private boolean awayFromCatherby() {
    Controller controller = c();
    return controller.currentX() >= 455 || controller.currentY() >= 520;
  }

  /** Started away from Catherby (reload / restart mid-failsafe): finish the job from here. */
  private void resumeIfAway() {
    Controller controller = c();
    if (!controller.isLoggedIn() || !awayFromCatherby() || controller.currentY() > 1000) return;
    boolean anyTool = controller.getInventoryItemCount(NET) > 0 || controller.getInventoryItemCount(HARPOON) > 0;
    if (insideGuild() && !anyTool) leaveGuild();
    if (anyTool && onGuildSide()) {
      log("Started at / on the way to the Fishing Guild (" + controller.currentX() + "," + controller.currentY() + ")");
      return;
    }
    log("Started away from Catherby (at " + controller.currentX() + "," + controller.currentY() + ") - resuming the broke failsafe");
    boolean haveTool = controller.getInventoryItemCount(NET) > 0 || controller.getInventoryItemCount(HARPOON) > 0;
    thievingForGear = true;
    try {
      thieveGoal = thieveTarget();
      thieveBanked = 0; // can't see the bank from here: thieve the whole target to be safe
      if (thieveEnabled && !haveTool && controller.getInventoryItemCount(COINS) < thieveGoal)
        thieveMen(thieveGoal);
      if (!controller.isRunning()) return;
      leaveMen();
      weapon.putBack(false); // thieving over: the weapon goes back on for the walk
      log("Walking back to Catherby with " + controller.getInventoryItemCount(COINS) + " coins");
      if (walkRouteFully(MEN_TO_CATHERBY, "Catherby", 30 * 60_000L)) {
        walkNear(BANK_TILE[0], BANK_TILE[1], 2, 120_000);
        openDoor();
        openBankHere(); // bank the coins now so the normal tool check sees them in the bank
        if (controller.isInBank() && controller.getInventoryItemCount(COINS) > 0) {
          int coins = controller.getInventoryItemCount(COINS);
          int banked = controller.getBankItemCount(COINS);
          controller.depositItem(COINS, coins);
          waitFor(() -> controller.getInventoryItemCount(COINS) == 0 && controller.getBankItemCount(COINS) >= banked + coins, 5000);
          log("Back in Catherby bank: banked " + coins + " coins (" + coinsThieved + "gp thieved, " + hpRests
              + " HP rests, lowest HP " + lowestHp + ")");
        }
      }
    } finally {
      thievingForGear = false;
      weapon.putBack(false); // thieving over: the weapon goes back on
    }
  }

  /**
   * Bank open on entry and (if we made it back) on return. Walks to the Ardougne men, thieves
   * the gear money, walks back. @return true when we're back with enough coins for the tool
   */
  private boolean thieveForGear() {
    Controller controller = c();
    thieveBanked = controller.isInBank() ? controller.getBankItemCount(COINS) : 0;
    thieveGoal = thieveTarget();
    int carryGoal = Math.max(1, thieveGoal - thieveBanked);
    for (Item item : controller.getInventoryItems()) { // free slots; coins and food stay
      int id = item.getItemDef().id;
      if (weapon.isProtected(id)) continue; // the weapon taken off for thieving
      if (id == COINS || isFood(id)) continue;
      controller.depositItem(id, controller.getInventoryItemCount(id));
      controller.sleep(180);
    }
    log("Thieving for gear: " + thieveGoal + "gp wanted (bank " + thieveBanked + ") - walking to the men in East Ardougne");
    controller.closeBank();
    controller.sleep(640);
    thievingForGear = true;
    long started = System.currentTimeMillis();
    try {
      controller.walkTo(439, 497);
      openDoor();
      boolean ok = thieveMen(carryGoal);
      if (!controller.isRunning()) return false;
      leaveMen();
      weapon.putBack(false); // thieving over: the weapon goes back on for the walk
      log((ok ? "Got " : "Stopped thieving with ") + controller.getInventoryItemCount(COINS) + " coins after "
          + (System.currentTimeMillis() - started) / 60_000 + " min - walking back to Catherby");
      if (!walkRouteFully(MEN_TO_CATHERBY, "Catherby", 30 * 60_000L)) {
        log("Couldn't walk back to Catherby");
        return false;
      }
      walkNear(BANK_TILE[0], BANK_TILE[1], 2, 120_000);
      openDoor();
      openBankHere();
      log("Back in Catherby bank after " + (System.currentTimeMillis() - started) / 60_000 + " min ("
          + coinsThieved + "gp thieved, " + hpRests + " HP rests, lowest HP " + lowestHp + ")");
      return ok;
    } finally {
      thievingForGear = false;
      weapon.putBack(false); // thieving over: the weapon goes back on
    }
  }

  /** Pickpockets men until we carry {@code carryGoal} coins. Walks there first if needed. */
  private boolean thieveMen(int carryGoal) {
    Controller controller = c();
    long until = System.currentTimeMillis() + THIEVE_MAX_MS;
    long lastMan = System.currentTimeMillis();
    int seen = controller.getInventoryItemCount(COINS);
    weapon.takeOff(this::dropJunk); // thieving failsafe only: no weapon in hand on the men
    while (controller.isRunning() && System.currentTimeMillis() < until) {
      int coins = controller.getInventoryItemCount(COINS);
      if (coins > seen) coinsThieved += coins - seen; // the server repeats a pickpocket until it fails
      seen = coins;
      if (coins >= carryGoal) return true;
      if (fighting()) {
        escapeCombat(REST_SPOT);
        continue;
      }
      if (hpLow()) {
        restUntilHealthy();
        continue;
      }
      if (controller.distanceTo(MEN_SPOT[0], MEN_SPOT[1]) > 14) {
        if (!walkRouteFully(CATHERBY_TO_MEN, "the Ardougne men", 30 * 60_000L)) return false;
        lastMan = System.currentTimeMillis();
        continue;
      }
      if (System.currentTimeMillis() < stunnedUntil || controller.isBatching()) {
        controller.setStatus("@cya@Stunned / busy - waiting");
        notStalled(5000);
        controller.sleep(600);
        continue;
      }
      if (controller.getInventoryItemCount() >= 30 && coins == 0) dropJunk();
      ORSCharacter man =
          controller.getNearestNPCByLambda(
              n -> {
                boolean isMan = false;
                for (int id : MEN) isMan |= n.npcId == id;
                if (!isMan || controller.isNpcInCombat(n.serverIndex)) return false;
                int[] at = controller.getNpcCoordsByServerIndex(n.serverIndex);
                return at[0] != -1
                    && controller.distance(at[0], at[1], MEN_SPOT[0], MEN_SPOT[1]) <= 12
                    && controller.isReachable(at[0], at[1], true);
              });
      if (man == null) {
        controller.setStatus("@cya@Waiting for a man to thieve (" + coins + "/" + carryGoal + "gp)");
        notStalled(5000);
        if (System.currentTimeMillis() - lastMan > 10 * 60_000L) {
          log("No men to thieve for 10 minutes - giving up on thieving");
          return false;
        }
        controller.sleep(1500);
        continue;
      }
      lastMan = System.currentTimeMillis();
      controller.setStatus("@cya@Pickpocketing a man (" + (thieveBanked + coins) + "/" + thieveGoal + "gp)");
      pickpockets++;
      if (weapon.wielded() >= 0) weapon.takeOff(this::dropJunk);
      controller.thieveNpc(man.serverIndex);
      final int before = coins;
      waitFor(() -> controller.getInventoryItemCount(COINS) > before || fighting(), 3000);
      int got = controller.getInventoryItemCount(COINS) - before;
      if (got > 0)
        log("Pickpocketed a man: " + (thieveBanked + before + got) + "/" + thieveGoal + "gp (HP " + hpNow() + "/" + hpMax() + ")");
      controller.sleep(600);
    }
    return controller.getInventoryItemCount(COINS) >= carryGoal;
  }

  /** The server repeats a pickpocket until it fails: let that settle, and get out of any fight. */
  private void leaveMen() {
    Controller controller = c();
    int before = controller.getInventoryItemCount(COINS);
    controller.sleep(1500);
    coinsThieved += Math.max(0, controller.getInventoryItemCount(COINS) - before);
    if (fighting()) escapeCombat(REST_SPOT);
  }

  /** A failed pickpocket makes the man attack: walk off until out of combat (RSC lets you retreat). */
  private void escapeCombat(int[] to) {
    Controller controller = c();
    log("Attacked at " + controller.currentX() + "," + controller.currentY() + " (HP " + hpNow() + "/" + hpMax() + ") - walking away");
    long until = System.currentTimeMillis() + 30_000;
    int n = 0;
    while (controller.isRunning() && fighting() && System.currentTimeMillis() < until) {
      int[] t = to;
      if (controller.distanceTo(to[0], to[1]) <= 1 || ++n % 4 == 0) {
        int r = ThreadLocalRandom.current().nextInt(-4, 5);
        t = new int[] {controller.currentX() + r, controller.currentY() + 4 - Math.abs(r)};
      }
      controller.walkToAsync(t[0], t[1], 0);
      notStalled(3000);
      controller.sleep(650);
    }
    if (controller.distanceTo(MEN_SPOT[0], MEN_SPOT[1]) <= 14 && hpLow()) walkRouteFully(MEN_TO_REST, "the rest spot", 60_000);
  }

  /** Steps away from the men (or stays put on the route) and waits for HP to come back. */
  private void restUntilHealthy() {
    Controller controller = c();
    if (resting) return;
    resting = true;
    try {
      rest();
    } finally {
      resting = false;
    }
  }

  private void rest() {
    Controller controller = c();
    hpRests++;
    int from = hpNow();
    long started = System.currentTimeMillis();
    log("HP " + from + "/" + hpMax() + " - resting until " + fullHpPercent + "% (rest " + hpRests + ")");
    if (controller.distanceTo(MEN_SPOT[0], MEN_SPOT[1]) <= 14) walkRouteFully(MEN_TO_REST, "the rest spot", 60_000);
    long until = System.currentTimeMillis() + 30 * 60_000L;
    while (controller.isRunning()
        && (hpNow() * 100 < fullHpPercent * hpMax() || hpLow())
        && System.currentTimeMillis() < until) {
      if (fighting()) {
        escapeCombat(REST_SPOT);
        continue;
      }
      if (eatFood && eatSomething()) continue;
      controller.setStatus("@cya@Resting: HP " + hpNow() + "/" + hpMax() + " (until " + fullHpPercent + "%)");
      notStalled(5000);
      controller.sleep(1000);
    }
    log("Rested: HP " + from + " -> " + hpNow() + "/" + hpMax() + " in " + (System.currentTimeMillis() - started) / 1000 + "s");
  }

  private boolean isFood(int id) {
    try {
      return "eat".equalsIgnoreCase(c().getItemCommand(id));
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** Eats one carried food item (cooked fish etc.). @return true if it ate */
  private boolean eatSomething() {
    Controller controller = c();
    for (Item item : controller.getInventoryItems()) {
      int id = item.getItemDef().id;
      if (weapon.isProtected(id)) continue; // the weapon taken off for thieving
      if (!isFood(id)) continue;
      int before = controller.getInventoryItemCount(id);
      int h = hpNow();
      controller.itemCommand(id);
      waitFor(() -> controller.getInventoryItemCount(id) < before, 2000);
      log("Ate a " + controller.getItemName(id) + " at " + h + " HP, now " + hpNow() + "/" + hpMax());
      return true;
    }
    return false;
  }

  /** Full inventory with no coin stack yet: drop one junk item (never coins, tools or food). */
  private void dropJunk() {
    Controller controller = c();
    for (Item item : controller.getInventoryItems()) {
      int id = item.getItemDef().id;
      if (weapon.isProtected(id)) continue; // the weapon taken off for thieving
      if (id == COINS || id == NET || id == HARPOON || isFood(id) || certRowForCert(id) != null) continue; // never certs
      int slot = controller.getInventoryItemSlotIndex(id);
      if (slot < 0) continue;
      log("Inventory full - dropping a " + controller.getItemName(id));
      controller.dropItem(slot);
      controller.sleep(1200);
      return;
    }
  }

  /**
   * Stops the script, but never within MIN_RUN_BEFORE_STOP_MS of leaving the setup window: the v15.6
   * script queue (and mass start) capture the setup right after its Start is pressed and treat a
   * script that already stopped as a cancelled setup ("did not keep running").
   */
  private void stopScript() {
    Controller controller = c();
    weapon.putBack(false); // before stopping (and its 15 s grace)
    long wait = runStartedAt + MIN_RUN_BEFORE_STOP_MS - System.currentTimeMillis();
    while (controller.isRunning() && wait > 0) {
      controller.setStatus("@red@Stopping in " + (wait / 1000 + 1) + "s (see the log)");
      notStalled(5000);
      try {
        Thread.sleep(Math.min(1000, wait));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt(); // stopped (e.g. by the queue's set-up capture)
        return;
      }
      wait = runStartedAt + MIN_RUN_BEFORE_STOP_MS - System.currentTimeMillis();
    }
    if (controller.isRunning()) controller.stop();
  }

  private int nearestWaypoint(int[][] route, int maxDist) {
    Controller controller = c();
    int best = -1, bestD = Integer.MAX_VALUE;
    for (int i = 0; i < route.length; i++) {
      int d = controller.distanceTo(route[i][0], route[i][1]);
      if (d <= maxDist && d < bestD) {
        bestD = d;
        best = i;
      }
    }
    return best;
  }

  private boolean walkRouteFully(int[][] route, String name, long ms) {
    Controller controller = c();
    long until = System.currentTimeMillis() + ms;
    while (controller.isRunning() && System.currentTimeMillis() < until) {
      if (walkRoute(route, name)) return true;
    }
    return false;
  }

  /**
   * Walks a route from its nearest waypoint to its end. Attacked: walks on (away). Low HP out of
   * combat: rests. Far from the route (e.g. respawned in Lumbridge after dying): stops cleanly.
   * @return true at the end of the route
   */
  private boolean walkRoute(int[][] route, String name) {
    Controller controller = c();
    int start = nearestWaypoint(route, 25);
    if (start < 0) {
      int far = nearestWaypoint(route, 90);
      if (far < 0 || controller.currentY() > 1000) {
        String why = "Not near Catherby or the Ardougne route (at " + controller.currentX() + "," + controller.currentY()
            + ", died?) - stopping. Thief2Ardougne (dest catherby) can walk this account back.";
        controller.log(why, "red");
        log(why);
        stopScript();
        return false;
      }
      log("Off the route to " + name + " - heading for waypoint " + route[far][0] + "," + route[far][1]);
      if (!controller.walkTowards(route[far][0], route[far][1])) controller.sleep(1200);
      controller.sleep(600);
      return false;
    }
    int[] a = route[start];
    if (start + 1 < route.length) {
      int[] b = route[start + 1];
      if (Math.max(Math.abs(a[0] - b[0]), Math.abs(a[1] - b[1])) >= controller.distanceTo(b[0], b[1])) start++;
    }
    for (int i = start; i < route.length && controller.isRunning(); i++) {
      int wx = route[i][0], wy = route[i][1];
      controller.setStatus("@cya@Walking to " + name + " (" + (i + 1) + "/" + route.length + ")");
      long end = System.currentTimeMillis() + 25_000;
      long lastClick = 0;
      while (controller.isRunning() && controller.distanceTo(wx, wy) > 1) {
        if (System.currentTimeMillis() > end) {
          log("Waypoint " + wx + "," + wy + " not reached (at " + controller.currentX() + "," + controller.currentY() + ") - retrying");
          if (!controller.walkTowards(wx, wy)) controller.sleep(1200);
          return false;
        }
        if (fighting()) {
          int[] away = route[Math.min(i + 1, route.length - 1)];
          if (System.currentTimeMillis() - lastClick > 600) {
            controller.walkToAsync(away[0], away[1], 0);
            lastClick = System.currentTimeMillis();
          }
          end = Math.max(end, System.currentTimeMillis() + 5000);
          controller.sleep(300);
          continue;
        }
        if (thievingForGear && !resting && (hpNow() * 100 <= restHpPercent * hpMax() || hpNow() <= 4)) {
          restUntilHealthy();
          return false;
        }
        if (System.currentTimeMillis() - lastClick > 2400 || !controller.isCurrentlyWalking()) {
          controller.walkToAsync(wx, wy, 0);
          lastClick = System.currentTimeMillis();
        }
        controller.sleep(300);
      }
    }
    return controller.isRunning();
  }

  // ---------------------------------------------------------------------------------------------
  // Setup window
  // ---------------------------------------------------------------------------------------------

  /** Arguments win (script queue / command line); else the setup window. @return false = cancel */
  private boolean setup(String[] param) {
    String joined = param == null ? "" : String.join(" ", param).trim();
    if (!joined.isEmpty()) {
      for (String a : joined.split("[\\s,]+")) {
        String[] kv = a.split("=", 2);
        String k = kv[0].toLowerCase();
        String v = kv.length > 1 ? kv[1] : "on";
        boolean on = !(v.equalsIgnoreCase("off") || v.equalsIgnoreCase("false") || v.equals("0") || v.equalsIgnoreCase("no"));
        if (k.equals("sharks")) sharksEnabled = on;
        else if (k.equals("sell")) sellFishEnabled = on;
        else if (k.equals("muling")) mulingEnabled = on;
        else if (k.equals("mule") && kv.length > 1) {
          muleName = kv[1].replace('_', ' ');
          mulingEnabled = true;
        } else if (k.equals("threshold")) {
          try {
            muleThreshold = Math.max(1, Integer.parseInt(v));
          } catch (NumberFormatException ignored) {
          }
        } else if (k.equals("thieve")) thieveEnabled = on;
        else if (k.equals("thievegp")) thieveTargetSetting = parseTarget(v);
        else if (k.equals("resthp")) restHpPercent = parsePercent(v, 50, 20, 90);
        else if (k.equals("fullhp")) fullHpPercent = parsePercent(v, 90, 30, 100);
        else if (k.equals("eat")) eatFood = on;
        else if (k.equals("guild")) guildEnabled = on;
        else if (k.equals("catherbycap")) catherbyCap = parseCap(v, catherbyCap);
        else if (k.equals("guildcap")) guildCap = parseCap(v, guildCap);
        else if (k.equals("cert")) certEnabled = on;
      }
      fullHpPercent = Math.max(fullHpPercent, restHpPercent + 10);
      return true;
    }
    if (java.awt.GraphicsEnvironment.isHeadless()) return true;
    SwingUtilities.invokeLater(this::showSetup);
    Controller controller = c();
    while (controller.isRunning() && !setupDone && !setupCancelled) controller.sleep(200);
    if (!setupDone) {
      log("Setup window closed - not starting");
      controller.stop();
      return false;
    }
    return true;
  }

  /** "auto" / blank / 0 = tool price + buffer; else the coin target (at least the tool price). */
  private static int parseTarget(String v) {
    try {
      int n = Integer.parseInt(v.trim());
      return n <= 0 ? 0 : Math.max(TOOL_MIN_COINS + 1, Math.min(5000, n));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static int parseCap(String v, int def) {
    try {
      return Math.max(0, Math.min(500, Integer.parseInt(v.trim())));
    } catch (NumberFormatException e) {
      return def;
    }
  }

  private static int parsePercent(String v, int def, int lo, int hi) {
    try {
      return Math.max(lo, Math.min(hi, Integer.parseInt(v.trim().replace("%", ""))));
    } catch (NumberFormatException e) {
      return def;
    }
  }

  private void showSetup() {
    JFrame f = new JFrame("Ben's Fish Farm");
    JPanel p = new JPanel(new GridLayout(0, 1, 4, 4));
    p.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
    JCheckBox sharks = new JCheckBox("Sharks when levels allow (Fishing 76, Cooking 80)", sharksEnabled);
    JCheckBox sell = new JCheckBox("Sell fish to Arhein when we can't afford a net/harpoon", sellFishEnabled);
    JCheckBox mule = new JCheckBox("Muling: trade cooked fish to the mule in Catherby bank", mulingEnabled);
    JTextField muleField = new JTextField(muleName, 12);
    JTextField thresholdField = new JTextField(String.valueOf(muleThreshold), 6);
    JPanel m1 = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
    m1.add(new JLabel("Mule name:"));
    m1.add(muleField);
    JPanel m2 = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
    m2.add(new JLabel("Trade a cooked fish when over (a cert = 5):"));
    m2.add(thresholdField);
    JCheckBox thieve = new JCheckBox("Broke failsafe: thieve men in East Ardougne for gear money", thieveEnabled);
    JTextField thieveField = new JTextField(thieveTargetSetting > 0 ? String.valueOf(thieveTargetSetting) : "auto", 6);
    JTextField restField = new JTextField(String.valueOf(restHpPercent), 3);
    JTextField fullField = new JTextField(String.valueOf(fullHpPercent), 3);
    JCheckBox eat = new JCheckBox("Eat food while thieving if any is carried", eatFood);
    JPanel t1 = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
    t1.add(new JLabel("Thieve until coins (auto = gear + " + GEAR_BUFFER + "gp):"));
    t1.add(thieveField);
    JPanel t2 = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
    t2.add(new JLabel("Rest at HP %:"));
    t2.add(restField);
    t2.add(new JLabel("until HP %:"));
    t2.add(fullField);
    JCheckBox guild = new JCheckBox("Fishing Guild at Fishing 68+ (harpoon; banks at the guild chest)", guildEnabled);
    JTextField catherbyCapField = new JTextField(String.valueOf(catherbyCap), 3);
    JTextField guildCapField = new JTextField(String.valueOf(guildCap), 3);
    JPanel g1 = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
    g1.add(new JLabel("Max accounts (all clients): Catherby"));
    g1.add(catherbyCapField);
    g1.add(new JLabel("Guild"));
    g1.add(guildCapField);
    JCheckBox certBox = new JCheckBox("Cert at the Fishing Guild (swordfish / sharks, 5 fish = 1 cert)", certEnabled);
    JButton start = new JButton("Start");
    start.addActionListener(
        e -> {
          sharksEnabled = sharks.isSelected();
          sellFishEnabled = sell.isSelected();
          mulingEnabled = mule.isSelected();
          String n = muleField.getText().trim();
          muleName = n.isEmpty() ? "xoxoxo" : n;
          try {
            muleThreshold = Math.max(1, Integer.parseInt(thresholdField.getText().trim()));
          } catch (NumberFormatException ex) {
            muleThreshold = 500;
          }
          thieveEnabled = thieve.isSelected();
          thieveTargetSetting = parseTarget(thieveField.getText());
          restHpPercent = parsePercent(restField.getText(), 50, 20, 90);
          fullHpPercent = Math.max(parsePercent(fullField.getText(), 90, 30, 100), restHpPercent + 10);
          eatFood = eat.isSelected();
          guildEnabled = guild.isSelected();
          catherbyCap = parseCap(catherbyCapField.getText(), 25);
          guildCap = parseCap(guildCapField.getText(), 25);
          certEnabled = certBox.isSelected();
          setupDone = true;
          f.dispose();
        });
    f.addWindowListener(
        new java.awt.event.WindowAdapter() {
          @Override
          public void windowClosed(java.awt.event.WindowEvent e) {
            if (!setupDone) setupCancelled = true;
          }
        });
    p.add(sharks);
    p.add(sell);
    p.add(mule);
    p.add(m1);
    p.add(m2);
    p.add(thieve);
    p.add(t1);
    p.add(t2);
    p.add(eat);
    p.add(guild);
    p.add(g1);
    p.add(certBox);
    p.add(start);
    f.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    f.setContentPane(p);
    f.pack();
    f.setLocationRelativeTo(null);
    f.setVisible(true);
  }

  // ---------------------------------------------------------------------------------------------
  // Locations: Catherby / Fishing Guild, chosen against the cross-client cap
  // ---------------------------------------------------------------------------------------------

  /** Shared by every copy of the script in this JVM (string literals are JVM-wide). */
  private static final Object PRESENCE_LOCK = "BensFishFarm.presence.lock";
  /** A heartbeat older than this is a crashed / closed client: not counted. */
  static final long PRESENCE_STALE_MS = 120_000;
  static final long PRESENCE_BEAT_MS = 20_000;
  static final long WAIT_RECHECK_MS = 60_000;
  volatile long guildRefusedMsgAt = 0;

  private boolean nearCatherby() {
    Controller c = c();
    return c.currentX() < 455 && c.currentY() < 520;
  }

  /** Inside the guild walls (flood fill of the collision map from the door: 583-605, 496-523). */
  private boolean insideGuild() {
    Controller c = c();
    int x = c.currentX(), y = c.currentY();
    return x >= 583 && x <= 605 && y >= 496 && y <= 523;
  }

  private boolean nearGuildDoor() {
    return c().distanceTo(GUILD_DOOR[0], GUILD_DOOR[1]) <= 6 && c().currentY() >= 524;
  }

  /** Between the guild and East Ardougne north bank (south of the door). */
  private boolean onArdyBankSide() {
    Controller c = c();
    return c.currentY() >= 529 && c.currentY() < 600 && nearestWaypoint(GUILD_TO_ARDY_BANK, 12) >= 0;
  }

  /** At the guild, at Ardougne north bank, or on the routes there (resume check). */
  private boolean onGuildSide() {
    return insideGuild()
        || nearGuildDoor()
        || onArdyBankSide()
        || (c().currentX() >= 455 && nearestWaypoint(CATHERBY_TO_GUILD, 8) >= 0);
  }

  private boolean inCatherbyBank() {
    Controller c = c();
    return c.currentX() >= 437 && c.currentX() <= 443 && c.currentY() >= 488 && c.currentY() <= 496;
  }

  /** Guild level (base Fishing 68) and harpoon levels (the guild has no small-net spot). */
  private boolean guildEligible() {
    return c().getBaseStat(SkillId.FISHING.getId()) >= GUILD_FISHING && useHarpoon();
  }

  private int capOf(String loc) {
    return GUILD.equals(loc) ? guildCap : catherbyCap;
  }

  private static String placeName(String loc) {
    return GUILD.equals(loc) ? "Guild" : "Catherby";
  }

  /** Picks where to fish now and claims the slot in the shared registry (atomic across clients). */
  private void decideLocation() {
    boolean guildOk = guildEnabled && guildEligible() && System.currentTimeMillis() >= guildRefusedUntil;
    final String pref = guildOk ? GUILD : CATHERBY;
    final String alt = guildOk ? CATHERBY : null;
    final String[] chosen = new String[1];
    final int[] seen = new int[2];
    presence.locked(
        () -> {
          java.util.Map<String, String> others = presence.othersFresh();
          int oc = 0, og = 0;
          for (String l : others.values()) {
            if (CATHERBY.equals(l)) oc++;
            else if (GUILD.equals(l)) og++;
          }
          int prefOthers = GUILD.equals(pref) ? og : oc;
          if (prefOthers < capOf(pref)) chosen[0] = pref;
          else if (alt != null && (GUILD.equals(alt) ? og : oc) < capOf(alt)) chosen[0] = alt;
          presence.writeOwn(chosen[0] == null ? WAITING : chosen[0]);
          seen[0] = oc + (CATHERBY.equals(chosen[0]) ? 1 : 0);
          seen[1] = og + (GUILD.equals(chosen[0]) ? 1 : 0);
          presence.countCatherby = seen[0];
          presence.countGuild = seen[1];
        });
    String before = workLoc;
    workLoc = chosen[0];
    if (workLoc == null) {
      StringBuilder why = new StringBuilder();
      why.append(placeName(pref)).append(" full (").append(GUILD.equals(pref) ? seen[1] : seen[0]).append('/').append(capOf(pref)).append(')');
      if (alt != null)
        why.append(", ").append(placeName(alt)).append(" full (").append(GUILD.equals(alt) ? seen[1] : seen[0]).append('/').append(capOf(alt)).append(')');
      if (!java.util.Objects.equals(why.toString(), waitReason) || before != null)
        log("Waiting: " + why + " - checking again every minute");
      waitReason = why.toString();
      return;
    }
    waitReason = null;
    if (!workLoc.equals(before)) {
      String counts = "(Catherby " + seen[0] + "/" + catherbyCap + ", Guild " + seen[1] + "/" + guildCap + ")";
      if (!workLoc.equals(pref)) log(placeName(pref) + " is full - fishing at " + placeName(workLoc) + " instead " + counts);
      else log((before == null ? "Fishing at " : "Moving to ") + (GUILD.equals(workLoc) ? "the Fishing Guild " : "Catherby ") + counts);
    }
  }

  /** Re-decides right after a Catherby bank trip. @return true when we're no longer fishing in Catherby */
  private boolean needDecisionNow() {
    decideLocation();
    needDecision = false;
    nextDecisionAt = System.currentTimeMillis() + 15 * 60_000L;
    return !CATHERBY.equals(workLoc);
  }

  /** Both spots full (or Catherby full and no guild): wait a minute somewhere safe. */
  private void waitForSlot() {
    Controller c = c();
    if (nearCatherby() && !inCatherbyBank() && !controllerBusy()) {
      walkNear(BANK_TILE[0], BANK_TILE[1], 2, 60_000); // the bank is a safe place to wait
    }
    long until = System.currentTimeMillis() + WAIT_RECHECK_MS;
    while (c.isRunning() && System.currentTimeMillis() < until) {
      c.setStatus("@yel@Waiting: " + waitReason + " (" + (until - System.currentTimeMillis()) / 1000 + "s)");
      notStalled(10_000);
      if (fighting()) c.walkToAsync(c.currentX() + 3, c.currentY() + 1, 1);
      c.sleep(1000);
    }
  }

  private boolean controllerBusy() {
    return c().isBatching() || fighting();
  }

  // ---------------------------------------------------------------------------------------------
  // Fishing Guild loop: fish (harpoon / sharks), cook on the guild range, bank at the guild chest
  // ---------------------------------------------------------------------------------------------

  private void guildStep() {
    Controller c = c();
    if (c.getInventoryItemCount(HARPOON) < 1) {
      log("No harpoon for the guild - getting one in Catherby");
      travelToCatherby();
      if (!c.isRunning() || !nearCatherby()) return;
      bank(); // withdraws / buys / sells / thieves exactly like the Catherby loop
      needDecision = true;
      return;
    }
    if (!insideGuild()) {
      if (c.getInventoryItemCount() >= 30) { // full inventory outside (reload / mule trip): bank it first
        if (nearCatherby()) bank();
        else bankAtGuildChest();
        needDecision = true;
        return;
      }
      travelToGuild();
      if (!c.isRunning()) return;
      if (!enterGuild()) {
        guildRefusedUntil = System.currentTimeMillis() + 15 * 60_000L;
        log("Couldn't get into the Fishing Guild (Fishing " + c.getCurrentStat(SkillId.FISHING.getId())
            + ") - Catherby for 15 min");
        needDecision = true;
      }
      return;
    }
    if (c.getInventoryItemCount() < 30) {
      guildFish();
      return;
    }
    guildCook();
    if (certEnabled) {
      dropBurnt();
      certAll();
      if (!c.isRunning()) return;
      if (guildMuleDue()) {
        muleTripToCatherby(); // the mule takes the certs; the loop walks back to the guild
        return;
      }
      int free = 30 - c.getInventoryItemCount();
      int certs = carriedCerts();
      if (free >= GUILD_MIN_FREE && certs < CERT_BANK_AT) {
        c.setStatus("@cya@Certed - " + free + " slots free, back to fishing");
        return;
      }
      log(certs >= CERT_BANK_AT ? "Carrying " + certs + " certificates - banking them at the guild chest"
          : "Only " + free + " slots free after certing (tuna etc.) - banking at the guild chest");
    }
    bankAtGuildChest();
    needDecision = true;
  }

  private void travelToGuild() {
    Controller c = c();
    if (insideGuild() || nearGuildDoor()) {
      if (!insideGuild()) c.walkTo(GUILD_DOOR[0], GUILD_DOOR[1]);
      return;
    }
    guildTrips++;
    if (onArdyBankSide()) {
      walkRouteFully(ARDY_BANK_TO_GUILD, "the Fishing Guild", 10 * 60_000L);
    } else {
      if (inCatherbyBank()) leaveCatherbyBank();
      log("Walking to the Fishing Guild");
      walkRouteFully(CATHERBY_TO_GUILD, "the Fishing Guild", 20 * 60_000L);
    }
    if (c.isRunning() && nearGuildDoor()) c.walkTo(GUILD_DOOR[0], GUILD_DOOR[1]);
  }

  private void travelToCatherby() {
    Controller c = c();
    if (nearCatherby()) return;
    log("Walking back to Catherby (at " + c.currentX() + "," + c.currentY() + ")");
    if (insideGuild()) leaveGuild();
    if (onArdyBankSide()) walkRouteFully(ARDY_BANK_TO_GUILD, "the Fishing Guild", 10 * 60_000L);
    walkRouteFully(GUILD_TO_CATHERBY, "Catherby", 20 * 60_000L);
  }

  private void leaveCatherbyBank() {
    Controller c = c();
    c.walkTo(439, 496);
    openDoor();
    c.walkTo(439, 499);
  }

  /** Opens the guild door from 586,524 (the server moves us to 586,523; Fishing 68 needed). */
  private boolean enterGuild() {
    Controller c = c();
    long refusedBefore = guildRefusedMsgAt;
    for (int i = 0; i < 6 && c.isRunning(); i++) {
      if (insideGuild()) return true;
      if (c.currentX() != GUILD_DOOR[0] || c.currentY() != GUILD_DOOR[1]) c.walkTo(GUILD_DOOR[0], GUILD_DOOR[1]);
      c.atWallObject(GUILD_DOOR[0], GUILD_DOOR[1]);
      waitFor(() -> insideGuild() || guildRefusedMsgAt != refusedBefore, 5000);
      if (guildRefusedMsgAt != refusedBefore) return false;
    }
    if (insideGuild()) log("Into the Fishing Guild");
    return insideGuild();
  }

  /** Opens the door from 586,523 (the server moves us to 586,524). */
  private boolean leaveGuild() {
    Controller c = c();
    for (int i = 0; i < 6 && c.isRunning(); i++) {
      if (!insideGuild()) return true;
      if (c.currentX() != GUILD_DOOR[0] || c.currentY() != GUILD_DOOR[1] - 1) c.walkTo(GUILD_DOOR[0], GUILD_DOOR[1] - 1);
      c.atWallObject(GUILD_DOOR[0], GUILD_DOOR[1]);
      waitFor(() -> !insideGuild(), 5000);
    }
    return !insideGuild();
  }

  private int[] firstSpot(int[][] spots, int id) {
    Controller c = c();
    int[] best = null;
    int bestD = Integer.MAX_VALUE;
    for (int[] s : spots) {
      if (c.getObjectAtCoord(s[0], s[1]) != id) continue;
      int d = c.distanceTo(s[0], s[1]);
      if (d < bestD) {
        bestD = d;
        best = s;
      }
    }
    return best;
  }

  /** Sharks on the 261 spots (option 2) at 76/80, else tuna/swordfish on 194 (option 1) / 376 (option 2). */
  private void guildFish() {
    Controller c = c();
    int[] spot;
    boolean second;
    String what;
    if (useSharks() && (spot = firstSpot(GUILD_SHARK_SPOTS, SHARK_SPOT)) != null) {
      second = true;
      what = "sharks";
    } else if ((spot = firstSpot(GUILD_HARPOON_SPOTS, GUILD_HARPOON_SPOT)) != null) {
      second = false;
      what = "tuna/swordfish";
    } else if ((spot = firstSpot(GUILD_CAGE_SPOTS, GUILD_CAGE_SPOT)) != null) {
      second = true;
      what = "tuna/swordfish";
    } else {
      c.setStatus("@cya@No guild spot in sight - walking to the pier");
      c.walkTo(586, 506);
      c.sleep(1000);
      return;
    }
    // A spot can be in the object list from the door (586,523) while still ~25 tiles away.
    // Clicking it from there never starts a walk, so the account stands on the door.
    if (Math.max(Math.abs(c.currentX() - spot[0]), Math.abs(c.currentY() - spot[1])) > 2) {
      c.setStatus("@cya@Walking to the guild " + what + " spot");
      notStalled(70_000);
      c.walkTo(spot[0], spot[1] + 1); // dock is south of the water
      if (Math.max(Math.abs(c.currentX() - spot[0]), Math.abs(c.currentY() - spot[1])) > 2) return;
    }
    c.setStatus("@cya@Guild: harpooning " + what);
    if (second) c.atObject2(spot[0], spot[1]);
    else c.atObject(spot[0], spot[1]);
    c.sleep(800);
    long until = System.currentTimeMillis() + 6000;
    while (c.isRunning() && !c.isBatching() && c.isCurrentlyWalking() && System.currentTimeMillis() < until) {
      notStalled(8000);
      c.sleep(200);
    }
    long fishUntil = System.currentTimeMillis() + 120_000;
    while (c.isRunning() && c.isBatching() && c.getInventoryItemCount() < 30
        && System.currentTimeMillis() < fishUntil) {
      notStalled(15_000);
      c.sleep(50);
    }
  }

  private void guildCook() {
    Controller c = c();
    c.setStatus("@cya@Cooking on the guild range");
    if (c.distanceTo(GUILD_RANGE[0], GUILD_RANGE[1]) > 2) c.walkTo(GUILD_RANGE[0] + 1, GUILD_RANGE[1] + 1);
    cookAllOn(GUILD_RANGE[0], GUILD_RANGE[1]);
  }

  /** Cooking level each raw fish needs (OpenRSC ItemCookingDef). */
  private static int cookLevel(int rawId) {
    switch (rawId) {
      case 349: return 1; // shrimp
      case 351: return 1; // anchovies
      case 366: return 30; // tuna
      case 369: return 45; // swordfish
      case 545: return 80; // shark
      default: return 1;
    }
  }

  /**
   * Cooks every raw fish we have the level for on the range at x,y. Fish we can't cook yet (e.g.
   * swordfish under Cooking 45) are banked raw instead of retried forever; no progress for 20 s
   * moves on to the next fish.
   */
  private void cookAllOn(int x, int y) {
    Controller c = c();
    int cooking = c.getCurrentStat(SkillId.COOKING.getId());
    for (int rawId : rawIds) {
      if (c.getInventoryItemCount(rawId) == 0) continue;
      if (cooking < cookLevel(rawId)) {
        log("Cooking " + cooking + " is too low for " + c.getItemName(rawId) + " (" + cookLevel(rawId) + ") - banking them raw");
        continue;
      }
      int last = c.getInventoryItemCount(rawId);
      long progressAt = System.currentTimeMillis();
      while (c.getInventoryItemCount(rawId) > 0 && c.isRunning()) {
        if (c.getShouldSleep()) c.sleepHandler(true);
        if (!c.isBatching()) c.useItemIdOnObject(x, y, rawId);
        c.sleep(250);
        int now = c.getInventoryItemCount(rawId);
        if (now < last) {
          last = now;
          progressAt = System.currentTimeMillis();
        } else if (System.currentTimeMillis() - progressAt > 20_000) {
          log("Couldn't cook " + c.getItemName(rawId) + " on the range at " + x + "," + y + " - banking them raw");
          break;
        }
      }
    }
  }

  /** Banks everything but the harpoon at the fishing guild chest, then mules if due. */
  private void bankAtGuildChest() {
    Controller c = c();
    if (!insideGuild()) {
      travelToGuild();
      if (!c.isRunning() || !enterGuild()) {
        log("Couldn't get into the Fishing Guild to use the bank chest");
        return;
      }
    }
    if (c.distanceTo(GUILD_BANK_CHEST[0], GUILD_BANK_CHEST[1]) > 1)
      c.walkTo(GUILD_BANK_CHEST[0] - 1, GUILD_BANK_CHEST[1]);
    if (!c.isRunning()) return;
    for (int i = 0; i < 3 && !c.isInBank() && c.isRunning(); i++) {
      if (c.getObjectAtCoord(GUILD_BANK_CHEST[0], GUILD_BANK_CHEST[1]) != BANK_CHEST)
        c.walkTo(GUILD_BANK_CHEST[0] - 1, GUILD_BANK_CHEST[1]);
      c.atObject(GUILD_BANK_CHEST[0], GUILD_BANK_CHEST[1]);
      waitFor(c::isInBank, 8000);
    }
    if (!c.isInBank()) {
      log("Couldn't open the fishing guild bank chest - trying again");
      return;
    }
    guildBankTrips++;
    for (Item item : c.getInventoryItems()) {
      int id = item.getItemDef().id;
      if (weapon.isProtected(id)) continue; // the weapon taken off for thieving
      if (id == HARPOON) continue;
      c.depositItem(id, c.getInventoryItemCount(id));
      c.sleep(180);
    }
    if (c.getInventoryItemCount(HARPOON) < 1 && c.getBankItemCount(HARPOON) > 0) {
      c.withdrawItem(HARPOON, 1);
      waitFor(() -> c.getInventoryItemCount(HARPOON) > 0, 5000);
    }
    rememberBank();
    boolean mule = muleDue();
    c.closeBank();
    c.sleep(640);
    if (mule) muleTripToCatherby();
  }

  /** Bank open: a cooked fish over the threshold and the mule back-off has passed. */
  private boolean muleDue() {
    Controller c = c();
    if (!mulingEnabled || System.currentTimeMillis() < nextMuleTry || !c.isInBank()) return false;
    for (int id : MULE_FISH) if (ownedWithCerts(id, true) > muleThreshold) return true;
    return false;
  }

  /** The mule stands in Catherby bank: walk there, trade, and let the loop walk back to the guild. */
  private void muleTripToCatherby() {
    Controller c = c();
    log("Muling is due - walking to the mule in Catherby bank");
    travelToCatherby();
    if (!c.isRunning() || !nearCatherby()) return;
    walkNear(BANK_TILE[0], BANK_TILE[1], 2, 120_000);
    openDoor();
    openBankHere();
    muleIfNeeded();
    if (c.isInBank()) {
      c.closeBank();
      c.sleep(640);
    }
    needDecision = true;
  }

  // ---------------------------------------------------------------------------------------------
  // Certing at the Fishing Guild (OpenRSC NpcCerters.xml + NpcLocs.json): Orven (369) certs
  // lobster / swordfish (raw and cooked), Padik (370) certs shark / bass (raw and cooked); both
  // stand at the east end of the guild (601-606, 499-504). 5 fish = 1 certificate. Tuna has no
  // certer. Certs stay in the inventory (they stack) and go in the bank on the next bank trip.
  // ---------------------------------------------------------------------------------------------

  static final int ORVEN = 369;
  static final int PADIK = 370;
  /** Where the two certers roam (inside the guild, east end). */
  static final int[] CERTER_SPOT = {602, 502};
  /** {fish, certificate, certer, menu text to cert ("I have some fish"), menu text to uncert}. */
  static final Object[][] CERTS = {
    {370, 535, ORVEN, "swordfish", "Swordfish"},
    {369, 536, ORVEN, "Raw swordfish", "Raw swordfish"},
    {546, 630, PADIK, "shark", "shark"},
    {545, 631, PADIK, "Raw shark", "Raw shark"},
  };
  static final int[] BURNT_IDS = {353, 368, 371, 547};
  /** After certing, keep fishing in the guild while at least this many slots are free. */
  static final int GUILD_MIN_FREE = 10;
  /** Carrying this many certs (500 fish): put them in the guild chest on the next chance. */
  static final int CERT_BANK_AT = 100;
  static final long CERT_GIVE_UP_MS = 10 * 60_000L;

  boolean certEnabled = true;
  int certsMade = 0;
  int certsMuled = 0;
  int certsUncerted = 0;
  volatile long certerBusyAt = 0;
  volatile long certerNoFishAt = 0;
  /** Bank counts seen on the last bank visit (fish and certs), for the mule check in the guild. */
  final java.util.Map<Integer, Integer> bankSeen = new java.util.HashMap<>();

  private static Object[] certRowForFish(int fish) {
    for (Object[] r : CERTS) if ((Integer) r[0] == fish) return r;
    return null;
  }

  private static Object[] certRowForCert(int cert) {
    for (Object[] r : CERTS) if ((Integer) r[1] == cert) return r;
    return null;
  }

  /** The certificate for a cooked fish (muling counts / trades these), or -1. */
  static int certOf(int fish) {
    Object[] r = certRowForFish(fish);
    return r == null ? -1 : (Integer) r[1];
  }

  private int carriedCerts() {
    Controller c = c();
    int n = 0;
    for (Object[] r : CERTS) n += c.getInventoryItemCount((Integer) r[1]);
    return n;
  }

  /** Bank open: remembers fish / cert counts so the guild can tell when muling is due. */
  private void rememberBank() {
    Controller c = c();
    if (!c.isInBank()) return;
    for (int id : MULE_FISH) bankSeen.put(id, c.getBankItemCount(id));
    for (Object[] r : CERTS) bankSeen.put((Integer) r[1], c.getBankItemCount((Integer) r[1]));
  }

  /** Fish of one kind we own, counting each certificate as 5 (bank part from the bank or last visit). */
  private int ownedWithCerts(int fish, boolean bankOpen) {
    Controller c = c();
    int cert = certOf(fish);
    int bankFish = bankOpen ? c.getBankItemCount(fish) : bankSeen.getOrDefault(fish, 0);
    int bankCert = cert < 0 ? 0 : bankOpen ? c.getBankItemCount(cert) : bankSeen.getOrDefault(cert, 0);
    int invCert = cert < 0 ? 0 : c.getInventoryItemCount(cert);
    return bankFish + c.getInventoryItemCount(fish) + 5 * (bankCert + invCert);
  }

  /** In the guild (bank closed): muling is due by the last bank counts plus what we carry. */
  private boolean guildMuleDue() {
    if (!mulingEnabled || System.currentTimeMillis() < nextMuleTry) return false;
    for (int id : MULE_FISH) if (ownedWithCerts(id, false) > muleThreshold) return true;
    return false;
  }

  /** Burnt fish are worthless: drop them in the guild instead of carrying them to a bank. */
  private void dropBurnt() {
    Controller c = c();
    for (int id : BURNT_IDS) {
      for (int tries = 0; tries < 30 && c.isRunning() && c.getInventoryItemCount(id) > 0; tries++) {
        int slot = c.getInventoryItemSlotIndex(id);
        if (slot < 0) break;
        int before = c.getInventoryItemCount(id);
        c.dropItem(slot, before);
        waitFor(() -> c.getInventoryItemCount(id) < before, 3000);
      }
    }
  }

  /** Waits for an option menu with an option matching {@code want}; @return its index or -1. */
  private int waitForOption(java.util.function.Predicate<String> want, long ms) {
    Controller c = c();
    long until = System.currentTimeMillis() + ms;
    while (c.isRunning() && System.currentTimeMillis() < until) {
      if (c.isInOptionMenu()) {
        int n = c.getOptionMenuCount();
        String[] opts = c.getOptionsMenuText();
        for (int i = 0; i < n && opts != null && i < opts.length; i++) {
          if (opts[i] != null && want.test(opts[i].trim())) return i;
        }
      }
      c.sleep(150);
    }
    return -1;
  }

  /**
   * Starts a conversation with a certer. Busy talking to someone else ("X is busy at the moment")
   * just waits and asks again - never counted as a stall. @return true once the first menu is up
   */
  private boolean talkToCerter(int npcId, String firstOption) {
    Controller c = c();
    String who = npcId == ORVEN ? "Orven" : "Padik";
    long giveUp = System.currentTimeMillis() + CERT_GIVE_UP_MS;
    int misses = 0;
    while (c.isRunning() && System.currentTimeMillis() < giveUp) {
      orsc.ORSCharacter npc = c.getNearestNpcById(npcId, false);
      if (npc == null) {
        c.setStatus("@cya@Looking for " + who + " (the certer)");
        notStalled(5000);
        c.walkTo(CERTER_SPOT[0], CERTER_SPOT[1]);
        c.sleep(1500);
        if (++misses > 20) break;
        continue;
      }
      long busyBefore = certerBusyAt;
      c.talkToNpc(npc.serverIndex);
      long until = System.currentTimeMillis() + 12_000;
      while (c.isRunning() && System.currentTimeMillis() < until && !c.isInOptionMenu() && certerBusyAt == busyBefore) {
        notStalled(5000);
        c.sleep(150);
      }
      if (certerBusyAt != busyBefore) {
        c.setStatus("@cya@" + who + " is busy with someone else - waiting");
        notStalled(8000);
        c.sleep(3000 + ThreadLocalRandom.current().nextInt(2000));
        continue;
      }
      if (waitForOption(o -> o.toLowerCase().contains(firstOption), 2000) >= 0) return true;
      if (++misses > 8) break;
      c.sleep(1000);
    }
    log("Couldn't talk to " + who + " - skipping certing this round");
    return false;
  }

  /** Certs every certable fish we hold in multiples of 5 (up to 25 per conversation). */
  private void certAll() {
    Controller c = c();
    for (Object[] r : CERTS) {
      int fish = (Integer) r[0];
      int cert = (Integer) r[1];
      int npcId = (Integer) r[2];
      String opt = (String) r[3];
      int guard = 0;
      while (c.isRunning() && c.getInventoryItemCount(fish) >= 5 && guard++ < 8) {
        int have = c.getInventoryItemCount(fish);
        int certsBefore = c.getInventoryItemCount(cert);
        int n = Math.min(5, have / 5); // "five" .. "Twentyfive" = 1 .. 5 certificates
        if (!talkToCerter(npcId, "fish to trade in")) return;
        int i1 = waitForOption(o -> o.toLowerCase().contains("fish to trade in"), 3000);
        if (i1 < 0) continue;
        c.optionAnswer(i1);
        int i2 = waitForOption(o -> o.equalsIgnoreCase(opt), 10_000);
        if (i2 < 0) {
          log("The certer didn't offer " + opt + " - skipping");
          break;
        }
        c.optionAnswer(i2);
        final String[] amounts = {"five", "ten", "fifteen", "twenty", "twentyfive"};
        final String amt = amounts[n - 1];
        int i3 = waitForOption(o -> o.equalsIgnoreCase(amt), 10_000);
        if (i3 < 0) continue;
        c.optionAnswer(i3);
        waitFor(() -> c.getInventoryItemCount(cert) > certsBefore, 6000);
        int made = c.getInventoryItemCount(cert) - certsBefore;
        if (made > 0) {
          certsMade += made;
          log("Certed " + (made * 5) + " " + c.getItemName(fish) + " -> " + made + " certificate" + (made > 1 ? "s" : "")
              + " (" + c.getInventoryItemCount(fish) + " left over, carrying " + c.getInventoryItemCount(cert) + ")");
        }
        c.sleep(600);
      }
    }
  }

  /**
   * Broke with only certificates in the bank (Catherby bank open): takes one certificate (5 fish),
   * walks to the guild, trades it back at Orven / Padik and banks the fish so they can be sold to
   * Arhein. Raw fish certificates first (raw fish are sold before cooked). @return true if fish came back
   */
  private boolean uncertForSelling() {
    Controller c = c();
    if (!c.isInBank()) return false;
    int[] order = {536, 631, 535, 630};
    int cert = -1;
    for (int id : order) if (c.getBankItemCount(id) > 0) { cert = id; break; }
    if (cert < 0) return false;
    if (c.getBaseStat(SkillId.FISHING.getId()) < GUILD_FISHING) {
      log("Only certificates to sell, but the certers are in the Fishing Guild (Fishing 68) - not uncerting");
      return false;
    }
    Object[] r = certRowForCert(cert);
    int fish = (Integer) r[0];
    log("No loose fish to sell - taking 1 " + c.getItemName(cert) + " to the guild to trade back for 5 fish");
    c.withdrawItem(cert, 1);
    final int cid = cert;
    waitFor(() -> c.getInventoryItemCount(cid) > 0, 5000);
    if (c.getInventoryItemCount(cert) < 1) return false;
    c.closeBank();
    c.sleep(640);
    int fishBefore = c.getInventoryItemCount(fish);
    travelToGuild();
    if (c.isRunning() && enterGuild()) {
      String opt = (String) r[4];
      if (talkToCerter((Integer) r[2], "certificates to trade in")) {
        int i1 = waitForOption(o -> o.toLowerCase().contains("certificates to trade in"), 3000);
        if (i1 >= 0) {
          c.optionAnswer(i1);
          int i2 = waitForOption(o -> o.equalsIgnoreCase(opt), 10_000);
          if (i2 >= 0) {
            c.optionAnswer(i2);
            int i3 = waitForOption(o -> o.equalsIgnoreCase("one"), 10_000);
            if (i3 >= 0) {
              c.optionAnswer(i3);
              waitFor(() -> c.getInventoryItemCount(fish) >= fishBefore + 5, 6000);
            }
          }
        }
      }
    }
    int got = c.getInventoryItemCount(fish) - fishBefore;
    if (got > 0) {
      certsUncerted++;
      log("Traded the certificate back for " + got + " " + c.getItemName(fish));
    }
    travelToCatherby();
    openBankHere();
    if (c.isInBank()) {
      for (Item item : c.getInventoryItems()) {
        int id = item.getItemDef().id;
        if (weapon.isProtected(id)) continue; // the weapon taken off for thieving
        if (id == COINS || id == NET || id == HARPOON) continue;
        c.depositItem(id, c.getInventoryItemCount(id));
        c.sleep(180);
      }
    }
    return got > 0;
  }

  // ---------------------------------------------------------------------------------------------
  // Rename: carry the old BensCatherbyFishFarm saved setup over once
  // ---------------------------------------------------------------------------------------------

  private void migrateSavedSettings() {
    try {
      String me = getClass().getSimpleName();
      if (!"BensFishFarm".equals(me)) return; // the old-name shim keeps using its own file
      java.io.File dir = new java.io.File("settings", "script-settings");
      java.io.File neu = new java.io.File(dir, "BensFishFarm.properties");
      java.io.File old = new java.io.File(dir, "BensCatherbyFishFarm.properties");
      if (!neu.exists() && old.exists()) {
        java.nio.file.Files.copy(old.toPath(), neu.toPath());
        log("Copied your saved BensCatherbyFishFarm setup to Ben's Fish Farm");
      }
    } catch (Exception e) {
      Main.log("[Ben's Fish Farm] couldn't copy the old saved setup: " + e);
    }
  }

  /** Tab status hook (v15.6 AccountStatus). */
  public String getStatusSuffix() {
    if (workLoc == null)
      return waitReason == null ? null : "Waiting: " + waitReason;
    return (GUILD.equals(workLoc) ? "Guild" : "Catherby") + " | Catherby " + presence.countCatherby + "/" + catherbyCap
        + ", Guild " + presence.countGuild + "/" + guildCap;
  }

  /**
   * Cross-client presence registry: one small JSON file per account in
   * settings/fishfarm-presence (relative to the IdleRSC folder every client runs from). Written
   * atomically (temp file + rename) under a JVM lock plus an OS file lock on .lock, so tabs and
   * separate client processes never claim the same last slot. A heartbeat thread refreshes the
   * file every 20 s while this script runs and deletes it when the script stops; a crashed client
   * leaves a file that simply goes stale after 2 minutes (and is cleaned up after 10).
   */
  final class Presence {
    final java.io.File dir = new java.io.File("settings", "fishfarm-presence");
    final String id = java.util.UUID.randomUUID().toString();
    volatile String loc = null;
    volatile int countCatherby = 0;
    volatile int countGuild = 0;
    volatile int countWaiting = 0;
    String account = "";
    java.io.File own;
    volatile boolean active = false;

    void start(Controller ctl) {
      String a = "";
      try {
        a = Main.getTabTitle();
      } catch (Throwable ignored) {
      }
      if (a == null || a.isEmpty()) a = ctl.getPlayerName();
      if (a == null || a.isEmpty()) a = "account-" + id.substring(0, 8);
      account = a;
      own = new java.io.File(dir, a.toLowerCase().replaceAll("[^a-z0-9_-]", "_") + ".json");
      active = true;
      final Object script = BensFishFarm.this;
      Thread t =
          new Thread(
              () -> {
                try {
                  while (true) {
                    Object cur = null;
                    try {
                      cur = Main.getCurrentRunningScript();
                    } catch (Throwable ignored) {
                    }
                    if (!Main.isRunning() || (cur != null && cur != script)) break;
                    locked(() -> {
                      if (loc != null) writeOwn(loc);
                      refreshCounts();
                    });
                    long next = System.currentTimeMillis() + PRESENCE_BEAT_MS;
                    while (System.currentTimeMillis() < next) {
                      Thread.sleep(500);
                      Object now = null;
                      try {
                        now = Main.getCurrentRunningScript();
                      } catch (Throwable ignored) {
                      }
                      if (!Main.isRunning() || (now != null && now != script)) break;
                    }
                  }
                } catch (InterruptedException ignored) {
                  // client closing
                } finally {
                  active = false;
                  locked(this::removeOwn);
                }
              },
              "BensFishFarm-presence-" + a);
      t.setDaemon(true);
      t.start();
    }

    /** Runs r holding the JVM lock and the OS lock on settings/fishfarm-presence/.lock. */
    void locked(Runnable r) {
      synchronized (PRESENCE_LOCK) {
        dir.mkdirs();
        java.nio.channels.FileLock fl = null;
        try (java.nio.channels.FileChannel ch =
            java.nio.channels.FileChannel.open(
                new java.io.File(dir, ".lock").toPath(),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE)) {
          long until = System.currentTimeMillis() + 5000;
          while (fl == null && System.currentTimeMillis() < until) {
            try {
              fl = ch.tryLock();
            } catch (java.nio.channels.OverlappingFileLockException e) {
              fl = null;
            }
            if (fl == null) {
              try {
                Thread.sleep(25);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
              }
            }
          }
          try {
            r.run();
          } finally {
            if (fl != null) fl.release();
          }
        } catch (java.io.IOException e) {
          r.run(); // no lock file possible: still better to work than to stop
        }
      }
    }

    /** Lock held. */
    void writeOwn(String location) {
      loc = location;
      if (own == null || !active) return;
      String json =
          "{\"account\":\"" + account.replace("\\", "\\\\").replace("\"", "\\\"") + "\",\"location\":\"" + location
              + "\",\"updated\":" + System.currentTimeMillis() + ",\"id\":\"" + id + "\",\"pid\":" + pid()
              + ",\"script\":\"" + BensFishFarm.this.getClass().getSimpleName() + "\"}\n";
      java.io.File tmp = new java.io.File(dir, ".tmp-" + id);
      try {
        java.nio.file.Files.write(tmp.toPath(), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (int i = 0; i < 5; i++) {
          try {
            try {
              java.nio.file.Files.move(tmp.toPath(), own.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                  java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
              java.nio.file.Files.move(tmp.toPath(), own.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return;
          } catch (java.io.IOException e) {
            Thread.sleep(40); // Windows: a reader outside our lock may hold it for a moment
          }
        }
      } catch (Exception e) {
        // next heartbeat tries again
      } finally {
        tmp.delete();
      }
    }

    /** Lock held: delete our file only if it's still ours (a reloaded copy may have taken over). */
    void removeOwn() {
      if (own == null) return;
      String[] f = read(own);
      if (f != null && id.equals(f[3])) own.delete();
    }

    private long pid() {
      try {
        return ProcessHandle.current().pid();
      } catch (Throwable t) {
        return -1;
      }
    }

    /** {account, location, updated, id} or null. */
    private String[] read(java.io.File f) {
      try {
        String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        return new String[] {field(s, "account"), field(s, "location"), field(s, "updated"), field(s, "id")};
      } catch (Exception e) {
        return null;
      }
    }

    private String field(String json, String key) {
      java.util.regex.Matcher m =
          java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|(-?\\d+))").matcher(json);
      if (!m.find()) return "";
      return m.group(2) != null ? m.group(2) : m.group(3);
    }

    /** Lock held: other accounts' fresh locations (account file name -> location). */
    java.util.Map<String, String> othersFresh() {
      java.util.Map<String, String> out = new java.util.HashMap<>();
      java.io.File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
      if (files == null) return out;
      long now = System.currentTimeMillis();
      for (java.io.File f : files) {
        String[] v = read(f);
        long upd;
        try {
          upd = v == null ? 0 : Long.parseLong(v[2]);
        } catch (NumberFormatException e) {
          upd = 0;
        }
        if (now - upd > 10 * 60_000L && f.lastModified() < now - 10 * 60_000L) {
          f.delete(); // long dead (crashed client): tidy up
          continue;
        }
        if (v == null || now - upd > PRESENCE_STALE_MS) continue;
        if (f.equals(own)) continue;
        out.put(f.getName(), v[1]);
      }
      return out;
    }

    /** Lock held: counts for the paint (us included). */
    void refreshCounts() {
      int cc = 0, gg = 0, ww = 0;
      for (String l : othersFresh().values()) {
        if (CATHERBY.equals(l)) cc++;
        else if (GUILD.equals(l)) gg++;
        else ww++;
      }
      if (CATHERBY.equals(loc)) cc++;
      else if (GUILD.equals(loc)) gg++;
      else if (loc != null) ww++;
      countCatherby = cc;
      countGuild = gg;
      countWaiting = ww;
    }
  }


  @Override
  public void serverMessageInterrupt(String message) {
    if (message.contains("nicely cooked")) cooked++;
    String m = message.toLowerCase();
    if (m.contains("stunned")) stunnedUntil = System.currentTimeMillis() + 5000;
    if (m.contains("nothing interesting happens"))
      fishMissUntil = System.currentTimeMillis() + 2000 + (long) (Math.random() * 3000);
    if (m.contains("fishing level of 68")) guildRefusedMsgAt = System.currentTimeMillis();
    if (m.contains("is busy at the moment") && (m.contains("orven") || m.contains("padik")))
      certerBusyAt = System.currentTimeMillis();
    if (m.contains("under attack") || m.contains("whilst you are fighting") || m.contains("can't retreat"))
      fightSeenAt = System.currentTimeMillis();
  }

  @Override
  public void questMessageInterrupt(String message) {
    if (message.contains("You catch")) fished++;
    if (message.contains("fail to pick")) {
      pickFails++;
      fightSeenAt = System.currentTimeMillis(); // the man attacks straight after
    }
    if (message.toLowerCase().contains("stunned")) stunnedUntil = System.currentTimeMillis() + 5000;
    if (message.toLowerCase().contains("fishing level of 68")) guildRefusedMsgAt = System.currentTimeMillis();
    String q = message.toLowerCase();
    if (q.contains("is busy at the moment") && (q.contains("orven") || q.contains("padik")))
      certerBusyAt = System.currentTimeMillis();
  }

  @Override
  public void paintInterrupt() {
    Controller controller = c();
    if (controller != null) {
      int currentFishingXp = controller.getStatXp(SkillId.FISHING.getId());
      int currentCookingXp = controller.getStatXp(SkillId.COOKING.getId());

      int fishingGains = currentFishingXp - startFishingXp;
      int cookingGains = currentCookingXp - startCookingXp;

      paintBuilder.setTitleCenteredSingleColor("Ben's Fish Farm", 0xD4AF37, 4);
      paintBuilder.addSpacerRow(4);
      paintBuilder.setBackgroundColor(0x282828, 200);
      paintBuilder.setBorderColor(0xD4AF37);

      paintBuilder.addRow(
          rowBuilder.multipleStringRow(
              new String[] {
                "Caught:",
                paintBuilder.stringFormatInt(fished),
                paintBuilder.stringAmountPerHour(fished)
              },
              new int[] {0xD4AF37, 0xE0E0E0, 0xFFE066},
              new int[] {4, 82, 58},
              1));

      paintBuilder.addRow(
          rowBuilder.multipleStringRow(
              new String[] {
                "Fishing XP:",
                paintBuilder.stringFormatInt(fishingGains),
                paintBuilder.stringAmountPerHour(fishingGains)
              },
              new int[] {0xD4AF37, 0xE0E0E0, 0xFFE066},
              new int[] {4, 82, 58},
              1));

      paintBuilder.addSpacerRow(4);

      paintBuilder.addRow(
          rowBuilder.multipleStringRow(
              new String[] {
                "Cooked:",
                paintBuilder.stringFormatInt(cooked),
                paintBuilder.stringAmountPerHour(cooked)
              },
              new int[] {0xD4AF37, 0xE0E0E0, 0xFFE066},
              new int[] {4, 82, 58},
              1));

      paintBuilder.addRow(
          rowBuilder.multipleStringRow(
              new String[] {
                "Cooking XP:",
                paintBuilder.stringFormatInt(cookingGains),
                paintBuilder.stringAmountPerHour(cookingGains)
              },
              new int[] {0xD4AF37, 0xE0E0E0, 0xFFE066},
              new int[] {4, 82, 58},
              1));

      if (fishSold > 0 || coinsMade > 0 || fishMuled > 0 || mulingEnabled) {
        paintBuilder.addSpacerRow(4);
        paintBuilder.addRow(
            rowBuilder.multipleStringRow(
                new String[] {
                  "Fish sold:", paintBuilder.stringFormatInt(fishSold), coinsMade + "gp made"
                },
                new int[] {0xD4AF37, 0xE0E0E0, 0xFFE066},
                new int[] {4, 82, 58},
                1));
        paintBuilder.addRow(
            rowBuilder.multipleStringRow(
                new String[] {
                  "Fish muled:",
                  paintBuilder.stringFormatInt(fishMuled),
                  mulingEnabled ? "to " + muleName : "off"
                },
                new int[] {0xD4AF37, 0xE0E0E0, 0xFFE066},
                new int[] {4, 82, 58},
                1));
      }
      if (useSharks())
        paintBuilder.addRow(rowBuilder.centeredSingleStringRow("Sharks", 0xFFE066, 1));
      int heldCerts = carriedCerts();
      if (certsMade > 0 || heldCerts > 0 || certsMuled > 0) {
        paintBuilder.addRow(
            rowBuilder.multipleStringRow(
                new String[] {
                  "Certs held:",
                  paintBuilder.stringFormatInt(heldCerts) + " (" + (heldCerts * 5) + " fish)",
                  certsMade + " made" + (certsMuled > 0 ? ", " + certsMuled + " muled" : "")
                },
                new int[] {0xD4AF37, 0xE0E0E0, 0xFFE066},
                new int[] {4, 82, 58},
                1));
      }
      paintBuilder.addSpacerRow(4);
      paintBuilder.addRow(
          rowBuilder.multipleStringRow(
              new String[] {
                "Spot:",
                workLoc == null ? "waiting" : GUILD.equals(workLoc) ? "Fishing Guild" : "Catherby",
                guildTrips > 0 ? guildBankTrips + " guild banks" : ""
              },
              new int[] {0xD4AF37, 0xE0E0E0, 0xFFE066},
              new int[] {4, 82, 58},
              1));
      paintBuilder.addRow(
          rowBuilder.centeredSingleStringRow(
              "Catherby " + presence.countCatherby + "/" + catherbyCap + ", Guild " + presence.countGuild + "/" + guildCap,
              0xE0E0E0, 1));
      if (workLoc == null && waitReason != null)
        paintBuilder.addRow(rowBuilder.centeredSingleStringRow("Waiting: " + waitReason, 0xFF8C66, 1));
      if (thievingForGear || coinsThieved > 0 || !weapon.paintText().isEmpty()) {
        paintBuilder.addSpacerRow(4);
        if (!weapon.paintText().isEmpty())
          paintBuilder.addRow(rowBuilder.centeredSingleStringRow("Weapon: " + weapon.paintText(), 0xFFB366, 1));
        if (thievingForGear)
          paintBuilder.addRow(rowBuilder.centeredSingleStringRow("Thieving for gear", 0xFFE066, 1));
        paintBuilder.addRow(
            rowBuilder.multipleStringRow(
                new String[] {
                  "Gear money:",
                  (thieveBanked + controller.getInventoryItemCount(COINS)) + "/" + thieveGoal + "gp",
                  "HP " + hpNow() + "/" + hpMax()
                },
                new int[] {0xD4AF37, 0xE0E0E0, 0xFFE066},
                new int[] {4, 82, 58},
                1));
        paintBuilder.addRow(
            rowBuilder.multipleStringRow(
                new String[] {"Thieved:", coinsThieved + "gp", hpRests + " rests"},
                new int[] {0xD4AF37, 0xE0E0E0, 0xFFE066},
                new int[] {4, 82, 58},
                1));
      }
    }
    paintBuilder.draw();
  }

  // ---------------------------------------------------------------------------------------------
  // Weapon guard: nothing in the weapon hand while pickpocketing. The weapon's item id is saved
  // per account in settings/weapon-guard/<script>_<account>.txt (survives a reload / crash), kept
  // out of every deposit / drop, and put back on before the script stops. Shields and armour
  // stay as they are (a two-hander sits in the weapon slot, so it counts as the weapon).
  // ---------------------------------------------------------------------------------------------

  final WeaponGuard weapon = new WeaponGuard("Ben's Fish Farm");

  @Override
  public void cleanup() {
    try {
      weapon.putBack(true); // stop button / queue stop: put the weapon back on (no long waits here)
    } catch (Throwable ignored) {
      // never block a stop
    }
    super.cleanup();
  }

  final class WeaponGuard {
    final String script;
    int remembered = -1;
    String rememberedName = "";
    boolean loaded = false;
    int takenOff = 0;
    int putBackCount = 0;

    WeaponGuard(String script) {
      this.script = script;
    }

    private Controller ctl() {
      return Main.getController();
    }

    private void say(String s) {
      Main.log("[" + script + "] " + s);
      Controller c = ctl();
      if (c != null) c.setStatus("@cya@" + s);
    }

    private java.io.File file() {
      Controller c = ctl();
      String acct = c == null ? null : c.getPlayerName();
      if (acct == null || acct.trim().isEmpty()) return null;
      acct = acct.trim().toLowerCase().replaceAll("[^a-z0-9_-]", "_");
      return new java.io.File(new java.io.File("settings", "weapon-guard"), script + "_" + acct + ".txt");
    }

    /** Reads a weapon remembered by an earlier run (reload / crash mid-thieving). */
    void load() {
      if (loaded) return;
      java.io.File f = file();
      if (f == null) return;
      loaded = true;
      try {
        if (!f.exists()) return;
        String[] p = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8)
            .trim().split("\\|", 2);
        remembered = Integer.parseInt(p[0].trim());
        rememberedName = p.length > 1 ? p[1].trim() : "item " + remembered;
        say("Remembered weapon from the last run: " + rememberedName + " (taken off for thieving)");
      } catch (Exception e) {
        remembered = -1;
      }
    }

    private void save() {
      java.io.File f = file();
      if (f == null) return;
      try {
        f.getParentFile().mkdirs();
        java.nio.file.Files.write(f.toPath(), (remembered + "|" + rememberedName + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      } catch (Exception e) {
        Main.log("[" + script + "] couldn't save the weapon file: " + e);
      }
    }

    private void forget() {
      remembered = -1;
      rememberedName = "";
      java.io.File f = file();
      if (f != null) f.delete();
    }

    /** Item id in the weapon hand, or -1 (equipment tab on Coleslaw, the inventory elsewhere). */
    int wielded() {
      Controller c = ctl();
      if (c == null) return -1;
      try {
        if (orsc.Config.S_WANT_EQUIPMENT_TAB) {
          com.openrsc.client.entityhandling.defs.ItemDef[] eq = c.getMud().equippedItems;
          return eq != null && eq.length > 4 && eq[4] != null ? eq[4].id : -1;
        }
        for (com.openrsc.client.entityhandling.instances.Item it : c.getInventoryItems()) {
          com.openrsc.client.entityhandling.defs.ItemDef d = it.getItemDef();
          if (d != null && it.getEquipped() && d.wieldable && (d.wearableID & 16) != 0) return d.id;
        }
      } catch (Throwable ignored) {
        // client not ready
      }
      return -1;
    }

    /** True for the remembered weapon: never deposit, drop or sell it. */
    boolean isProtected(int id) {
      return remembered >= 0 && id == remembered;
    }

    private boolean waitUntil(java.util.function.BooleanSupplier ok, long ms) {
      long until = System.currentTimeMillis() + ms;
      while (System.currentTimeMillis() < until) {
        if (ok.getAsBoolean()) return true;
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return ok.getAsBoolean();
        }
      }
      return ok.getAsBoolean();
    }

    /**
     * Takes the weapon off before thieving. Full inventory (equipment tab needs a free slot):
     * {@code makeRoom} is asked to free one. @return true when the weapon hand is empty
     */
    boolean takeOff(Runnable makeRoom) {
      Controller c = ctl();
      if (c == null || !c.isLoggedIn()) return false;
      load();
      int w = wielded();
      if (w < 0) return true;
      boolean tab = orsc.Config.S_WANT_EQUIPMENT_TAB;
      if (tab && c.getInventoryItemCount() >= 30 && makeRoom != null) makeRoom.run();
      if (tab && c.getInventoryItemCount() >= 30) {
        say("Inventory full - can't take the " + c.getItemName(w) + " off for thieving");
        return false;
      }
      if (c.isInBank()) {
        c.closeBank();
        c.sleep(640);
      }
      remembered = w;
      rememberedName = c.getItemName(w);
      save(); // first, so a crash right now still puts it back next time
      for (int i = 0; i < 3 && wielded() == w; i++) {
        if (tab) {
          orsc.mudclient m = c.getMud();
          while (m.packetHandler.getClientStream().hasFinishedPackets()) c.sleep(1);
          m.packetHandler.getClientStream().newPacket(168); // unequip from the equipment tab
          m.packetHandler.getClientStream().bufferBits.putByte(4); // weapon slot
          m.packetHandler.getClientStream().finishPacket();
        } else {
          c.unequipItem(c.getInventoryItemSlotIndex(w));
        }
        waitUntil(() -> wielded() != w, 3000);
      }
      if (wielded() == w) {
        say("Couldn't take the " + rememberedName + " off - thieving with it");
        return false;
      }
      takenOff++;
      say("Took the " + rememberedName + " off for thieving (it goes back on before the script stops)");
      return true;
    }

    /** Puts the remembered weapon back on. @return true when nothing is left to do */
    boolean putBack(boolean quick) {
      Controller c = ctl();
      if (c == null) return remembered < 0;
      load();
      if (remembered < 0) return true;
      if (!c.isLoggedIn()) return false;
      int w = wielded();
      if (w == remembered) {
        forget();
        return true;
      }
      if (w >= 0) {
        say("Holding a " + c.getItemName(w) + " now - leaving it (the " + rememberedName + " stays in the inventory)");
        forget();
        return true;
      }
      if (c.getInventoryItemCount(remembered) < 1) {
        say("The " + rememberedName + " isn't in the inventory - can't put it back on");
        return false;
      }
      if (c.isInBank()) c.closeBank();
      final int id = remembered;
      String name = rememberedName;
      for (int i = 0; i < (quick ? 1 : 3) && wielded() != id; i++) {
        c.equipItemById(id);
        waitUntil(() -> wielded() == id, quick ? 1500 : 3000);
      }
      if (wielded() == id) {
        putBackCount++;
        say("Put the " + name + " back on");
        forget();
        return true;
      }
      say("Couldn't put the " + name + " back on yet - will try again next start");
      return false;
    }

    String paintText() {
      return remembered >= 0 ? rememberedName + " off" : putBackCount > 0 ? "back on" : "";
    }
  }
}
