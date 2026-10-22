import bot.Main;
import bot.ui.scriptselector.models.Category;
import bot.ui.scriptselector.models.ScriptInfo;
import com.openrsc.client.entityhandling.instances.Item;
import controller.Controller;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.concurrent.ThreadLocalRandom;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import orsc.ORSCharacter;
import scripting.idlescript.IdleScript;

/**
 * Bens ArdyToSarim - gets an account from East Ardougne (or Catherby) to Port Sarim the Karamja
 * way and ends there, so it works as a script-queue step.
 *
 * <p>Route (OpenRSC Coleslaw data): Ardougne docks, Captain Barnaby (npc 316, "Yes please", 30gp)
 * sails to Brimhaven (467,651) -> walk east across Karamja through the members gate (254 at
 * 434,682) to Musa Point -> Customs officer (npc 163: "Can I board this ship?", "Search away I have
 * nothing to hide", "Ok", 30gp; Karamja rum is confiscated) sails to Port Sarim (269,648).
 *
 * <p>Coins: fares 60gp + a buffer. Short: pickpockets men in East Ardougne (same route, HP floor
 * and resting as BensCatherbyFishFarm's broke failsafe). Food: none carried (and none in the bank):
 * walks to Catherby, fishes and cooks the best fish it can with a net or harpoon only (shrimp /
 * anchovies, tuna / swordfish, sharks at 76/80), buying the net or harpoon at Harry's if it has
 * neither (thieving that money too), never keeps burnt fish, then walks back to the Ardougne docks.
 *
 * <p>Script arguments (skip the window): {@code buffer=10 food=10 eatat=50 resthp=50 fullhp=90 bank=on}
 */
public class BensArdyToSarim extends IdleScript {
  public static final ScriptInfo info =
      new ScriptInfo(
          new Category[] {Category.MISCELLANEOUS, Category.THIEVING, Category.FISHING, Category.IRONMAN_SUPPORTED},
          "Ben",
          "Travels East Ardougne -> Brimhaven -> Musa Point -> Port Sarim and stops.\n\n"
              + "Short of the 60gp fares: pickpockets men in East Ardougne (rests when HP is low).\n"
              + "No food: fishes and cooks the best fish it can with a net or harpoon in Catherby.",
          null,
          "Bens ArdyToSarim");

  static final int COINS = 10;
  static final int NET = 376;
  static final int HARPOON = 379;
  static final int HARRY = 250;
  static final int BARNABY = 316;
  static final int CUSTOMS = 163;
  static final int HITS = 3;
  static final int FISHING = 10;
  static final int COOKING = 7;
  static final int FARES = 60;
  /** Net or harpoon cost 5gp at Harry's (a bit more when he's low on stock). */
  static final int GEAR_COINS = 10;

  static final int[] MEN = {11, 72, 318};
  static final int[] MEN_SPOT = {606, 583};
  static final int[] REST_SPOT = {622, 605};
  static final int MIN_THIEVE_HP = 8;
  static final long THIEVE_MAX_MS = 120 * 60_000L;
  static final long MIN_RUN_BEFORE_STOP_MS = 15_000;

  static final int[] CATHERBY_BANK = {440, 494};
  static final int[] HARRYS_SHOP = {418, 487};
  static final int[] ARDY_BANK_DOOR = {552, 620};
  static final int[] BRIMHAVEN = {467, 651};
  static final int[] OFFICER_SPOT = {329, 712};
  static final int[] PORT_SARIM = {269, 648};
  static final int[] GATE = {434, 682};

  /** Catherby bank door -> East Ardougne men (BensCatherbyFishFarm's safe route, no exposed tiles). */
  static final int[][] CATHERBY_TO_MEN = {
    {439, 499}, {449, 499}, {459, 499}, {469, 499}, {479, 499}, {489, 499}, {499, 499}, {509, 500},
    {519, 500}, {529, 500}, {539, 500}, {549, 509}, {559, 519}, {569, 529}, {568, 539}, {564, 549},
    {564, 559}, {562, 569}, {554, 578}, {554, 588}, {557, 598}, {558, 608}, {562, 618}, {568, 625},
    {571, 635}, {580, 643}, {590, 643}, {600, 643}, {610, 636}, {620, 633}, {622, 625}, {622, 615},
    {622, 605}, {616, 595}, {610, 585}, {606, 583}
  };
  static final int[][] MEN_TO_CATHERBY = reversed(CATHERBY_TO_MEN);
  static final int[][] MEN_TO_REST = {{606, 583}, {610, 585}, {616, 595}, {622, 605}};
  /** Men -> Ardougne docks (collision map, aggressive boxes costed out: 0 exposed tiles). */
  static final int[][] MEN_TO_DOCKS = {
    {606, 583}, {611, 589}, {614, 599}, {620, 609}, {623, 619}, {623, 628}, {615, 632}, {605, 635},
    {595, 635}, {585, 642}, {578, 640}, {574, 630}, {565, 621}, {555, 620}, {546, 617}, {537, 615}
  };
  static final int[][] DOCKS_TO_MEN = reversed(MEN_TO_DOCKS);
  /** Catherby -> docks: the men route as far as East Ardougne's south bank, then the docks. */
  static final int[][] CATHERBY_TO_DOCKS = concat(java.util.Arrays.copyOf(CATHERBY_TO_MEN, 23),
      new int[][] {{555, 620}, {546, 617}, {537, 615}});
  static final int[][] DOCKS_TO_CATHERBY = reversed(CATHERBY_TO_DOCKS);
  /**
   * Brimhaven -> members gate (collision map; muggers lvl 10 can't be avoided on Brimhaven's
   * streets, they only attack from 1 tile away on this server).
   */
  static final int[][] BRIM_TO_GATE = {{467, 651}, {462, 657}, {452, 661}, {442, 671}, {436, 681}, {435, 682}};
  /** Members gate -> Musa Point (scorpions roam the jungle: we walk on and eat if attacked). */
  static final int[][] GATE_TO_MUSA = {
    {433, 682}, {424, 691}, {414, 701}, {404, 711}, {394, 713}, {384, 718}, {374, 717}, {364, 717},
    {354, 715}, {344, 711}, {340, 709}
  };

  /** Cooked fish, best heal first (OpenRSC ItemEdibleHeals.xml). Never burnt. */
  static final int[] FOOD_IDS = {546, 1191, 1193, 370, 555, 373, 367, 357, 364, 359, 551, 553, 362, 355, 350, 352};
  static final int[] FOOD_HEALS = {20, 20, 20, 14, 13, 12, 10, 9, 8, 7, 7, 6, 5, 4, 3, 1};
  static final int[] BURNT = {353, 360, 365, 368, 371, 374, 547, 1247, 1248};
  /** Raw fish we can catch with net / harpoon, and the cooking level each needs. */
  static final int[] RAW = {349, 351, 366, 369, 545};
  static final int[] RAW_COOK_LVL = {1, 1, 30, 45, 80};

  private static int[][] reversed(int[][] r) {
    int[][] o = new int[r.length][];
    for (int i = 0; i < r.length; i++) o[i] = r[r.length - 1 - i];
    return o;
  }

  private static int[][] concat(int[][] a, int[][] b) {
    int[][] o = java.util.Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, o, a.length, b.length);
    return o;
  }

  // --- setup window (remembered by SetupMemory, copied by mass start) ---
  int fareBuffer = 10;
  int foodCount = 10;
  int eatAtPercent = 50;
  int restHpPercent = 50;
  int fullHpPercent = 90;
  boolean useBank = true;
  volatile boolean setupDone = false;
  volatile boolean setupCancelled = false;

  // --- run state ---
  long runStartedAt = 0;
  String state = "Starting";
  String leg = "";
  boolean bankChecked = false;
  boolean bankHasTool = false;
  int coinGoal = 0;
  int coinsThieved = 0;
  int pickpockets = 0;
  int pickFails = 0;
  int hpRests = 0;
  int lowestHp = Integer.MAX_VALUE;
  int fished = 0;
  int cookedCount = 0;
  int burntCount = 0;
  int eaten = 0;
  int faresPaid = 0;
  long stunnedUntil = 0;
  volatile long fightSeenAt = 0;
  boolean resting = false;
  boolean thieving = false;
  boolean done = false;
  long legStartedAt = System.currentTimeMillis();

  private Controller c() {
    return Main.getController();
  }

  private void log(String s) {
    Main.log("[BensArdyToSarim] " + s);
    Controller c = c();
    if (c != null) c.setStatus("@cya@" + s);
  }

  private void setState(String s) {
    state = s;
    Controller c = c();
    if (c != null) c.setStatus("@cya@" + s);
  }

  private void setLeg(String s) {
    if (!s.equals(leg)) {
      leg = s;
      legStartedAt = System.currentTimeMillis();
      log("== " + s);
    }
  }

  private void notStalled(long ms) {
    try {
      bot.probe.Probe.noteIntentionalWait(ms);
    } catch (Throwable ignored) {
      // older clients have no stall watch
    }
  }

  private boolean waitFor(java.util.function.BooleanSupplier cond, long ms) {
    Controller c = c();
    long end = System.currentTimeMillis() + ms;
    while (c.isRunning() && System.currentTimeMillis() < end) {
      if (cond.getAsBoolean()) return true;
      c.sleep(150);
    }
    return cond.getAsBoolean();
  }

  // ---------------------------------------------------------------------------------------------
  // Where are we
  // ---------------------------------------------------------------------------------------------

  private boolean atPortSarim() {
    Controller c = c();
    return c.distanceTo(PORT_SARIM[0], PORT_SARIM[1]) <= 20 && c.currentY() < 1000;
  }

  private boolean onKaramja() {
    Controller c = c();
    if (c.currentY() > 1000) return false;
    return nearestWaypoint(BRIM_TO_GATE, 25) >= 0 || nearestWaypoint(GATE_TO_MUSA, 25) >= 0
        || c.distanceTo(OFFICER_SPOT[0], OFFICER_SPOT[1]) <= 15;
  }

  private boolean nearCatherby() {
    Controller c = c();
    return c.currentX() < 455 && c.currentX() > 395 && c.currentY() < 520 && c.currentY() > 470;
  }

  private boolean onMainland() {
    Controller c = c();
    if (c.currentY() > 1000) return false;
    return nearCatherby() || nearestWaypoint(CATHERBY_TO_MEN, 40) >= 0 || nearestWaypoint(MEN_TO_DOCKS, 40) >= 0;
  }

  // ---------------------------------------------------------------------------------------------
  // Main loop
  // ---------------------------------------------------------------------------------------------

  public int start(String[] param) {
    Controller c = c();
    if (c == null) return 1000;
    if (!setup(param)) return 1000;
    runStartedAt = System.currentTimeMillis();
    log("Settings: fares " + FARES + "gp + " + fareBuffer + "gp buffer, food " + foodCount + ", eat at " + eatAtPercent
        + "% HP, rest at " + restHpPercent + "% until " + fullHpPercent + "%, bank " + (useBank ? "on" : "off"));
    paintBuilder.start(4, 125, 250);
    weapon.load();
    if (c.isLoggedIn()) weapon.takeOff(this::dropJunk); // no weapon in hand while pickpocketing

    while (c.isRunning() && !done) {
      if (c.getNeedToMove()) c.moveCharacter();
      if (c.getShouldSleep()) c.sleepHandler(true);
      if (!c.isLoggedIn()) {
        c.sleep(1000);
        continue;
      }

      if (atPortSarim()) {
        setLeg("Arrived at Port Sarim");
        done = true;
        stopWithReason("Arrived at Port Sarim (" + c.currentX() + "," + c.currentY() + ") - " + faresPaid + " fares paid, "
            + coinsThieved + "gp thieved, " + hpRests + " HP rests, " + (cookedCount > 0 ? cookedCount + " fish cooked, " : "")
            + carriedFood() + " food left. Done.");
        break;
      }
      if (onKaramja()) {
        crossKaramja();
        continue;
      }
      if (!onMainland()) {
        stopWithReason("Not near Ardougne, Catherby, Karamja or Port Sarim (at " + c.currentX() + "," + c.currentY()
            + ", died?) - walk the account to East Ardougne and start again");
        break;
      }

      // --- mainland: bank, coins, food, then the docks ---
      if (useBank && !bankChecked) {
        visitBank();
        bankChecked = true;
        continue;
      }
      boolean needFood = needFood();
      boolean needGear = needFood && !hasTool();
      coinGoal = FARES + fareBuffer + (needGear ? GEAR_COINS : 0);
      if (c.getInventoryItemCount(COINS) < coinGoal) {
        setLeg("Thieving in East Ardougne");
        thieving = true;
        try {
          thieveMen(coinGoal);
          leaveMen();
        } finally {
          thieving = false;
        }
        if (c.getInventoryItemCount(COINS) < coinGoal && c.isRunning()) {
          stopWithReason("Couldn't thieve " + coinGoal + "gp (have " + c.getInventoryItemCount(COINS) + ") - stopping");
          break;
        }
        continue;
      }
      if (needFood) {
        setLeg("Getting food in Catherby");
        if (!getFood()) {
          if (!c.isRunning()) break;
          stopWithReason("Couldn't get food in Catherby - stopping");
          break;
        }
        continue;
      }
      setLeg("Sailing from Ardougne to Brimhaven");
      sailToBrimhaven();
    }
    weapon.putBack(false);
    return 1000;
  }

  // ---------------------------------------------------------------------------------------------
  // Bank (once, at the start: Catherby or East Ardougne south bank, whichever we're near)
  // ---------------------------------------------------------------------------------------------

  private void visitBank() {
    Controller c = c();
    boolean catherby = nearCatherby() || nearestWaypoint(CATHERBY_TO_MEN, 40) >= 0
        && nearestWaypoint(CATHERBY_TO_MEN, 40) < 10;
    setLeg("Checking the bank (" + (catherby ? "Catherby" : "East Ardougne south") + ")");
    if (catherby) {
      walkNear(439, 497, 2, 120_000);
      openCatherbyBankDoor();
    } else {
      if (c.distanceTo(ARDY_BANK_DOOR[0], ARDY_BANK_DOOR[1]) > 8) {
        int[][] route = nearestWaypoint(CATHERBY_TO_DOCKS, 40) >= 0
            && distToRoute(CATHERBY_TO_DOCKS) <= distToRoute(MEN_TO_DOCKS) ? CATHERBY_TO_DOCKS : MEN_TO_DOCKS;
        walkRouteTo(route, ARDY_BANK_DOOR, "East Ardougne bank");
      }
    }
    long end = System.currentTimeMillis() + 40_000;
    while (c.isRunning() && !c.isInBank() && System.currentTimeMillis() < end) {
      c.openBank();
      c.sleep(600);
    }
    if (!c.isInBank()) {
      log("Couldn't open the bank - going on without it");
      return;
    }
    // make room: keep coins, food, net/harpoon and anything worn
    if (c.getInventoryItemCount() > 30 - foodCount - 3) {
      for (int slot = 0; slot < 30; slot++) {
        int id = c.getInventorySlotItemId(slot);
        if (id <= 0 || id == COINS || id == NET || id == HARPOON || foodIndex(id) >= 0 || c.isEquipped(slot)
            || weapon.isProtected(id)) continue;
        int n = c.getInventoryItemCount(id);
        if (n > 0) {
          c.depositItem(id, n);
          c.sleep(250);
        }
      }
    }
    // food: none carried -> best cooked fish from the bank
    StringBuilder took = new StringBuilder();
    if (carriedFood() == 0) {
      int need = Math.min(foodCount, 30 - c.getInventoryItemCount() - 2);
      for (int i = 0; i < FOOD_IDS.length && need > 0; i++) {
        int id = FOOD_IDS[i];
        int have = c.getBankItemCount(id);
        if (have <= 0) continue;
        int take = Math.min(need, have);
        int before = c.getInventoryItemCount(id);
        c.withdrawItem(id, take);
        waitFor(() -> c.getInventoryItemCount(id) >= before + take, 4000);
        int got = c.getInventoryItemCount(id) - before;
        need -= got;
        if (got > 0) took.append(took.length() > 0 ? ", " : "").append(got).append(" ").append(c.getItemName(id));
      }
    }
    // tool: still no food -> net/harpoon from the bank
    bankHasTool = c.getBankItemCount(NET) > 0 || c.getBankItemCount(HARPOON) > 0;
    if (carriedFood() == 0 && !hasTool()) {
      int tool = harpoonFishBetter() && c.getBankItemCount(HARPOON) > 0 ? HARPOON
          : c.getBankItemCount(NET) > 0 ? NET : c.getBankItemCount(HARPOON) > 0 && harpoonLevels() ? HARPOON : -1;
      if (tool > 0) {
        c.withdrawItem(tool, 1);
        waitFor(() -> c.getInventoryItemCount(tool) > 0, 4000);
        took.append(took.length() > 0 ? ", " : "").append(tool == NET ? "net" : "harpoon");
      }
    }
    // coins for the fares (+ gear money if we still need gear)
    int want = FARES + fareBuffer + (carriedFood() == 0 && !hasTool() ? GEAR_COINS : 0) - c.getInventoryItemCount(COINS);
    int bankCoins = c.getBankItemCount(COINS);
    if (want > 0 && bankCoins > 0) {
      int take = Math.min(want, bankCoins);
      int before = c.getInventoryItemCount(COINS);
      c.withdrawItem(COINS, take);
      waitFor(() -> c.getInventoryItemCount(COINS) >= before + take, 4000);
      took.append(took.length() > 0 ? ", " : "").append(c.getInventoryItemCount(COINS) - before).append(" coins");
    }
    log("Bank: took " + (took.length() == 0 ? "nothing" : took.toString()) + " - carrying " + c.getInventoryItemCount(COINS)
        + " coins, " + carriedFood() + " food" + (hasTool() ? ", " + (c.getInventoryItemCount(HARPOON) > 0 ? "harpoon" : "net") : ""));
    c.closeBank();
    c.sleep(600);
  }

  private void openCatherbyBankDoor() {
    Controller c = c();
    for (int i = 0; i < 20 && c.getObjectAtCoord(439, 497) == 64; i++) {
      c.atObject(439, 497);
      c.sleep(300);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Food: Catherby, net or harpoon only
  // ---------------------------------------------------------------------------------------------

  private int carriedFood() {
    Controller c = c();
    int n = 0;
    for (int id : FOOD_IDS) n += c.getUnnotedInventoryItemCount(id);
    return n;
  }

  private static int foodIndex(int id) {
    for (int i = 0; i < FOOD_IDS.length; i++) if (FOOD_IDS[i] == id) return i;
    return -1;
  }

  private boolean hasTool() {
    Controller c = c();
    return c.getInventoryItemCount(NET) > 0 || c.getInventoryItemCount(HARPOON) > 0;
  }

  /** No food at all, or we're already fishing in Catherby with a tool and short of the count. */
  private boolean needFood() {
    int f = carriedFood();
    return f == 0 || (nearCatherby() && hasTool() && f < foodCount && hasRawOrFishing());
  }

  private boolean hasRawOrFishing() {
    Controller c = c();
    for (int id : RAW) if (c.getInventoryItemCount(id) > 0) return true;
    return fished > 0;
  }

  private boolean harpoonLevels() {
    Controller c = c();
    return c.getBaseStat(FISHING) >= 35 && c.getBaseStat(COOKING) >= 30;
  }

  /** Harpoon fish (tuna 10, swordfish 14, shark 20) beat net fish (shrimp 3) once we can cook tuna. */
  private boolean harpoonFishBetter() {
    return harpoonLevels();
  }

  private boolean sharkLevels() {
    Controller c = c();
    return c.getBaseStat(FISHING) >= 76 && c.getBaseStat(COOKING) >= 80;
  }

  private boolean getFood() {
    Controller c = c();
    if (!nearCatherby()) {
      setState("Walking to Catherby");
      int[][] route = distToRoute(DOCKS_TO_CATHERBY) < distToRoute(MEN_TO_CATHERBY) ? DOCKS_TO_CATHERBY : MEN_TO_CATHERBY;
      if (!walkRouteFully(route, "Catherby", 30 * 60_000L)) return false;
    }
    // the tool: harpoon if our levels make harpoon fish better, else a net
    int tool = c.getInventoryItemCount(HARPOON) > 0 && harpoonLevels() ? HARPOON
        : c.getInventoryItemCount(NET) > 0 ? NET
        : c.getInventoryItemCount(HARPOON) > 0 ? -2 : -1;
    if (tool == -2) { // a harpoon but too low to use it: need a net
      tool = -1;
    }
    if (tool < 0) {
      int want = harpoonFishBetter() ? HARPOON : NET;
      if (!buyFromHarry(want) && !(want == HARPOON && buyFromHarry(NET))) {
        log("Couldn't buy a " + (want == NET ? "net" : "harpoon") + " at Harry's");
        return false;
      }
      tool = c.getInventoryItemCount(HARPOON) > 0 && harpoonLevels() ? HARPOON : NET;
    }
    final int useTool = tool;
    String what = useTool == NET ? "shrimp/anchovies (net)" : sharkLevels() ? "sharks (harpoon)"
        : "tuna" + (c.getBaseStat(FISHING) >= 50 ? "/swordfish" : "") + " (harpoon)";
    log("Fishing " + what + " until we have " + foodCount + " cooked fish (have " + carriedFood() + ")");
    long until = System.currentTimeMillis() + 90 * 60_000L;
    while (c.isRunning() && carriedFood() < foodCount && System.currentTimeMillis() < until) {
      if (c.getShouldSleep()) c.sleepHandler(true);
      int raw = rawCount();
      int full = c.getInventoryItemCount();
      if ((raw > 0 && carriedFood() + raw >= foodCount + 3) || full >= 30) {
        cookAll();
        continue;
      }
      setState("Fishing " + what + " (" + carriedFood() + "+" + raw + " raw / " + foodCount + ")");
      int before = raw;
      if (useTool == NET) {
        if (c.currentX() != 418 || c.currentY() != 499) c.walkTo(418, 499);
        c.atObject(418, 500);
      } else if (sharkLevels()) {
        fishSharks();
      } else {
        if (c.currentX() != 409 || c.currentY() != 503) c.walkTo(409, 503);
        c.atObject(409, 504);
      }
      c.sleep(800);
      long end = System.currentTimeMillis() + 60_000;
      while (c.isRunning() && c.isBatching() && c.getInventoryItemCount() < 30 && System.currentTimeMillis() < end) c.sleep(100);
      fished += Math.max(0, rawCount() - before);
    }
    if (rawCount() > 0) cookAll();
    log("Food done: " + carriedFood() + " cooked fish (" + fished + " caught, " + cookedCount + " cooked, " + burntCount + " burnt)");
    return carriedFood() > 0;
  }

  private int rawCount() {
    Controller c = c();
    int n = 0;
    for (int id : RAW) n += c.getInventoryItemCount(id);
    return n;
  }

  private void fishSharks() {
    Controller c = c();
    int[] spot = c.getNearestObjectById(261);
    if (spot == null) {
      c.walkTo(406, 504);
      c.sleep(1000);
      return;
    }
    if (c.distanceTo(spot[0], spot[1]) > 2) c.walkTo(spot[0], spot[1] - 1);
    c.atObject2(spot[0], spot[1]);
  }

  /** Cooks every raw fish we can on the Catherby range, drops burnt and uncookable fish. */
  private void cookAll() {
    Controller c = c();
    setState("Cooking on the Catherby range");
    c.walkTo(419, 499);
    c.walkTo(428, 498);
    c.walkTo(436, 493);
    c.walkTo(435, 486);
    openCookDoor();
    int cookLvl = c.getBaseStat(COOKING);
    for (int i = 0; i < RAW.length; i++) {
      int rawId = RAW[i];
      if (RAW_COOK_LVL[i] > cookLvl) continue;
      long end = System.currentTimeMillis() + 120_000;
      while (c.getInventoryItemCount(rawId) > 0 && c.isRunning() && System.currentTimeMillis() < end) {
        if (c.getShouldSleep()) c.sleepHandler(true);
        if (!c.isBatching()) {
          int before = c.getInventoryItemCount(rawId);
          c.useItemIdOnObject(432, 480, rawId);
          waitFor(() -> c.getInventoryItemCount(rawId) < before || c.isBatching(), 3000);
        }
        c.sleep(250);
      }
    }
    dropJunkFish();
    c.walkTo(435, 485);
    openCookDoor();
    c.walkTo(436, 493);
    c.walkTo(428, 498);
    log("Cooked: " + carriedFood() + " food (" + cookedCount + " cooked, " + burntCount + " burnt so far)");
  }

  /** Drops burnt fish and raw fish our cooking level can't cook. */
  private void dropJunkFish() {
    Controller c = c();
    int cookLvl = c.getBaseStat(COOKING);
    for (int pass = 0; pass < 40 && c.isRunning(); pass++) {
      int drop = -1;
      for (int id : BURNT) if (c.getInventoryItemCount(id) > 0) drop = id;
      for (int i = 0; i < RAW.length && drop < 0; i++)
        if (RAW_COOK_LVL[i] > cookLvl && c.getInventoryItemCount(RAW[i]) > 0) drop = RAW[i];
      if (drop < 0) return;
      int slot = c.getInventoryItemSlotIndex(drop);
      if (slot < 0) return;
      int before = c.getInventoryItemCount(drop);
      final int d = drop;
      c.dropItem(slot);
      waitFor(() -> c.getInventoryItemCount(d) < before, 2500);
      c.sleep(300);
    }
  }

  private void openCookDoor() {
    Controller c = c();
    for (int i = 0; i < 20 && !c.isDoorOpen(435, 486) && c.isRunning(); i++) {
      c.openDoor(435, 486);
      c.sleep(300);
    }
  }

  private boolean buyFromHarry(int toolId) {
    Controller c = c();
    String tool = toolId == NET ? "net" : "harpoon";
    setState("Buying a " + tool + " at Harry's");
    if (!walkNear(HARRYS_SHOP[0], HARRYS_SHOP[1], 3, 120_000)) return false;
    for (int attempt = 0; attempt < 4 && c.isRunning() && !c.isInShop(); attempt++) {
      ORSCharacter harry = c.getNearestNpcById(HARRY, false);
      if (harry == null) {
        notStalled(5000);
        c.sleep(1500);
        continue;
      }
      c.talkToNpc(harry.serverIndex);
      if (waitFor(c::isInOptionMenu, 8000)) answerOption("trade", "buy", "see what", "yes");
      notStalled(15_000);
      waitFor(c::isInShop, 10_000);
    }
    if (!c.isInShop()) return false;
    long until = System.currentTimeMillis() + 60_000;
    while (c.isRunning() && c.getInventoryItemCount(toolId) < 1 && System.currentTimeMillis() < until) {
      if (c.getShopItemCount(toolId) < 1) {
        setState("Harry is out of " + tool + "s - waiting for restock");
        notStalled(5000);
        c.sleep(2000);
        continue;
      }
      int price = c.getShopItemPrice(toolId);
      if (c.getInventoryItemCount(COINS) < price) {
        log("Not enough coins for a " + tool + " (" + price + "gp)");
        break;
      }
      int before = c.getInventoryItemCount(COINS);
      c.shopBuy(toolId, 1);
      waitFor(() -> c.getInventoryItemCount(toolId) > 0, 4000);
      c.sleep(600);
      if (c.getInventoryItemCount(toolId) > 0)
        log("Bought a " + tool + " for " + (before - c.getInventoryItemCount(COINS)) + "gp");
    }
    c.closeShop();
    c.sleep(640);
    return c.getInventoryItemCount(toolId) > 0;
  }

  // ---------------------------------------------------------------------------------------------
  // Travel
  // ---------------------------------------------------------------------------------------------

  private void sailToBrimhaven() {
    Controller c = c();
    if (c.distanceTo(537, 615) > 6) {
      int[][] route = distToRoute(CATHERBY_TO_DOCKS) < distToRoute(MEN_TO_DOCKS) ? CATHERBY_TO_DOCKS : MEN_TO_DOCKS;
      setState("Walking to the Ardougne docks");
      if (!walkRouteFully(route, "the Ardougne docks", 30 * 60_000L)) return;
    }
    if (c.getInventoryItemCount(COINS) < FARES) return; // main loop thieves
    ORSCharacter captain = c.getNearestNpcById(BARNABY, false);
    if (captain == null) {
      setState("Waiting for Captain Barnaby");
      notStalled(5000);
      c.walkTo(537, 615);
      c.sleep(1500);
      return;
    }
    setState("Talking to Captain Barnaby (30gp to Brimhaven)");
    int coins = c.getInventoryItemCount(COINS);
    c.talkToNpc(captain.serverIndex);
    if (waitFor(c::isInOptionMenu, 12_000) && answerOption("yes please")) {
      notStalled(20_000);
      if (waitFor(() -> c.distanceTo(BRIMHAVEN[0], BRIMHAVEN[1]) <= 6, 20_000)) {
        faresPaid++;
        log("Sailed to Brimhaven (paid " + (coins - c.getInventoryItemCount(COINS)) + "gp)");
        c.sleep(1200);
        return;
      }
    }
    log("Captain Barnaby didn't take us - retrying");
    c.sleep(1500);
  }

  private int musaStuck = 0;

  private int npcDist(ORSCharacter n) {
    int[] xy = c().getNpcCoordsByServerIndex(n.serverIndex);
    if (xy == null || xy[0] < 0) return 99;
    return c().distanceTo(xy[0], xy[1]);
  }

  private void crossKaramja() {
    Controller c = c();
    if (c.distanceTo(OFFICER_SPOT[0], OFFICER_SPOT[1]) > 12) {
      boolean brimSide = c.currentX() >= 435 && nearestWaypoint(BRIM_TO_GATE, 25) >= 0;
      if (brimSide) {
        setLeg("Walking across Karamja");
        if (!walkRouteFully(BRIM_TO_GATE, "the Karamja gate", 10 * 60_000L)) return;
        crossGate();
        return;
      }
      setLeg("Walking across Karamja");
      if (!walkRouteFully(GATE_TO_MUSA, "Musa Point", 15 * 60_000L)) return;
    }
    // last bit (west of x 336) on the client's own path finder
    setLeg("Boarding at Musa Point");
    ORSCharacter seen = c.getNearestNpcById(CUSTOMS, false);
    if (c.distanceTo(OFFICER_SPOT[0], OFFICER_SPOT[1]) > 4
        && (seen == null || npcDist(seen) > 10)) {
      // the web walker has no data out here, so click-walk on the client's local map
      setState("Walking to the customs officer");
      c.walkToAsync(OFFICER_SPOT[0], OFFICER_SPOT[1], 0);
      int x0 = c.currentX(), y0 = c.currentY();
      waitFor(() -> c.distanceTo(OFFICER_SPOT[0], OFFICER_SPOT[1]) <= 4, 6000);
      if (c.currentX() == x0 && c.currentY() == y0) {
        if (++musaStuck >= 4) { c.walkToAsync(c.currentX() - 3, c.currentY() + 3, 1); musaStuck = 0; }
      } else musaStuck = 0;
      return;
    }
    if (c.getInventoryItemCount(COINS) < 30) {
      stopWithReason("At Musa Point with only " + c.getInventoryItemCount(COINS) + " coins (need 30) - stopping");
      done = true;
      return;
    }
    ORSCharacter officer = c.getNearestNpcById(CUSTOMS, false);
    if (officer == null) {
      setState("Waiting for the customs officer");
      notStalled(5000);
      c.sleep(1500);
      return;
    }
    setState("Talking to the customs officer (30gp to Port Sarim)");
    int coins = c.getInventoryItemCount(COINS);
    c.talkToNpc(officer.serverIndex);
    if (waitFor(c::isInOptionMenu, 10_000) && answerOption("can i board")
        && waitFor(c::isInOptionMenu, 12_000) && answerOption("search away")
        && waitFor(c::isInOptionMenu, 12_000) && answerOption("ok")) {
      notStalled(20_000);
      if (waitFor(() -> c.distanceTo(PORT_SARIM[0], PORT_SARIM[1]) <= 6, 20_000)) {
        faresPaid++;
        log("Sailed to Port Sarim (paid " + (coins - c.getInventoryItemCount(COINS)) + "gp)");
        return;
      }
    }
    log("The customs officer didn't take us - retrying");
    c.sleep(1500);
  }

  /** The members gate east of Brimhaven: open it from the Brimhaven side to get through. */
  private void crossGate() {
    Controller c = c();
    setState("Opening the Karamja gate");
    for (int i = 0; i < 8 && c.isRunning() && c.currentX() > GATE[0]; i++) {
      if (c.distanceTo(435, 682) > 1) c.walkTo(435, 682);
      c.atObject(GATE[0], GATE[1]);
      // the gate (direction 4) moves us from x 435 to x 434 - clicking again would send us back
      waitFor(() -> c.currentX() <= GATE[0], 4000);
    }
    if (c.currentX() <= GATE[0]) log("Through the Karamja gate");
  }

  private boolean answerOption(String... wanted) {
    Controller c = c();
    String[] opts = c.getOptionsMenuText();
    int n = Math.min(c.getOptionMenuCount(), opts == null ? 0 : opts.length);
    for (String w : wanted) {
      for (int i = 0; i < n; i++) {
        String o = opts[i] == null ? "" : opts[i].toLowerCase();
        if (o.contains(w)) {
          c.optionAnswer(i);
          c.sleep(600);
          return true;
        }
      }
    }
    return false;
  }

  // ---------------------------------------------------------------------------------------------
  // Thieving (BensCatherbyFishFarm's broke failsafe)
  // ---------------------------------------------------------------------------------------------

  private int hpNow() {
    int h = c().getCurrentStat(HITS);
    if (h > 0 && h < lowestHp) lowestHp = h;
    return h;
  }

  private int hpMax() {
    return Math.max(1, c().getBaseStat(HITS));
  }

  private boolean hpLow() {
    int h = hpNow();
    return h * 100 <= restHpPercent * hpMax() || h < Math.min(MIN_THIEVE_HP, hpMax());
  }

  private boolean fighting() {
    return c().isInCombat() || System.currentTimeMillis() - fightSeenAt < 5000;
  }

  private boolean thieveMen(int carryGoal) {
    Controller c = c();
    long until = System.currentTimeMillis() + THIEVE_MAX_MS;
    long lastMan = System.currentTimeMillis();
    int seen = c.getInventoryItemCount(COINS);
    log("Thieving men in East Ardougne until we carry " + carryGoal + " coins (have " + seen + ")");
    weapon.takeOff(this::dropJunk);
    while (c.isRunning() && System.currentTimeMillis() < until) {
      int coins = c.getInventoryItemCount(COINS);
      if (coins > seen) coinsThieved += coins - seen;
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
      if (c.distanceTo(MEN_SPOT[0], MEN_SPOT[1]) > 14) {
        int[][] route = distToRoute(DOCKS_TO_MEN) < distToRoute(CATHERBY_TO_MEN) ? DOCKS_TO_MEN : CATHERBY_TO_MEN;
        if (!walkRouteFully(route, "the Ardougne men", 30 * 60_000L)) return false;
        lastMan = System.currentTimeMillis();
        continue;
      }
      if (System.currentTimeMillis() < stunnedUntil || c.isBatching()) {
        setState("Stunned / busy - waiting");
        notStalled(5000);
        c.sleep(600);
        continue;
      }
      if (c.getInventoryItemCount() >= 30 && coins == 0) dropJunk();
      ORSCharacter man =
          c.getNearestNPCByLambda(
              n -> {
                boolean isMan = false;
                for (int id : MEN) isMan |= n.npcId == id;
                if (!isMan || c.isNpcInCombat(n.serverIndex)) return false;
                int[] at = c.getNpcCoordsByServerIndex(n.serverIndex);
                return at[0] != -1
                    && c.distance(at[0], at[1], MEN_SPOT[0], MEN_SPOT[1]) <= 12
                    && c.isReachable(at[0], at[1], true);
              });
      if (man == null) {
        setState("Waiting for a man to thieve (" + coins + "/" + carryGoal + "gp)");
        notStalled(5000);
        if (System.currentTimeMillis() - lastMan > 10 * 60_000L) {
          log("No men to thieve for 10 minutes - giving up");
          return false;
        }
        c.sleep(1500);
        continue;
      }
      lastMan = System.currentTimeMillis();
      setState("Pickpocketing men (" + coins + "/" + carryGoal + "gp)");
      pickpockets++;
      if (weapon.wielded() >= 0) weapon.takeOff(this::dropJunk); // put back on by hand?
      c.thieveNpc(man.serverIndex);
      final int before = coins;
      waitFor(() -> c.getInventoryItemCount(COINS) > before || fighting(), 3000);
      c.sleep(600);
    }
    return c.getInventoryItemCount(COINS) >= carryGoal;
  }

  private void leaveMen() {
    Controller c = c();
    int before = c.getInventoryItemCount(COINS);
    c.sleep(1500);
    coinsThieved += Math.max(0, c.getInventoryItemCount(COINS) - before);
    if (fighting()) escapeCombat(REST_SPOT);
    log("Thieving done: carrying " + c.getInventoryItemCount(COINS) + " coins (" + coinsThieved + "gp thieved, "
        + pickpockets + " tries, " + pickFails + " fails, " + hpRests + " rests, lowest HP " + lowestHp + ")");
  }

  private void escapeCombat(int[] to) {
    Controller c = c();
    log("Attacked at " + c.currentX() + "," + c.currentY() + " (HP " + hpNow() + "/" + hpMax() + ") - walking away");
    long until = System.currentTimeMillis() + 30_000;
    int n = 0;
    while (c.isRunning() && fighting() && System.currentTimeMillis() < until) {
      int[] t = to;
      if (c.distanceTo(to[0], to[1]) <= 1 || ++n % 4 == 0) {
        int r = ThreadLocalRandom.current().nextInt(-4, 5);
        t = new int[] {c.currentX() + r, c.currentY() + 4 - Math.abs(r)};
      }
      c.walkToAsync(t[0], t[1], 0);
      notStalled(3000);
      c.sleep(650);
    }
    if (c.distanceTo(MEN_SPOT[0], MEN_SPOT[1]) <= 14 && hpLow()) walkRouteFully(MEN_TO_REST, "the rest spot", 60_000);
  }

  private void restUntilHealthy() {
    if (resting) return;
    resting = true;
    try {
      Controller c = c();
      hpRests++;
      int from = hpNow();
      long started = System.currentTimeMillis();
      log("HP " + from + "/" + hpMax() + " - resting until " + fullHpPercent + "% (rest " + hpRests + ")");
      if (c.distanceTo(MEN_SPOT[0], MEN_SPOT[1]) <= 14) walkRouteFully(MEN_TO_REST, "the rest spot", 60_000);
      long until = System.currentTimeMillis() + 30 * 60_000L;
      while (c.isRunning() && (hpNow() * 100 < fullHpPercent * hpMax() || hpLow()) && System.currentTimeMillis() < until) {
        if (fighting()) {
          escapeCombat(REST_SPOT);
          continue;
        }
        setState("Resting: HP " + hpNow() + "/" + hpMax() + " (until " + fullHpPercent + "%)");
        notStalled(5000);
        c.sleep(1000);
      }
      log("Rested: HP " + from + " -> " + hpNow() + "/" + hpMax() + " in " + (System.currentTimeMillis() - started) / 1000 + "s");
    } finally {
      resting = false;
    }
  }

  private void dropJunk() {
    Controller c = c();
    for (Item item : c.getInventoryItems()) {
      int id = item.getItemDef().id;
      if (id == COINS || id == NET || id == HARPOON || foodIndex(id) >= 0 || weapon.isProtected(id)) continue;
      int slot = c.getInventoryItemSlotIndex(id);
      if (slot < 0 || c.isEquipped(slot)) continue;
      log("Inventory full - dropping a " + c.getItemName(id));
      c.dropItem(slot);
      c.sleep(1200);
      return;
    }
  }

  /** Eats one carried cooked fish (the biggest that doesn't overheal much). @return true if it ate */
  private boolean eatSomething() {
    Controller c = c();
    int missing = hpMax() - hpNow();
    int pick = -1;
    for (int i = 0; i < FOOD_IDS.length; i++) {
      if (c.getUnnotedInventoryItemCount(FOOD_IDS[i]) < 1) continue;
      pick = i;
      if (FOOD_HEALS[i] <= missing + 2) break;
    }
    if (pick < 0) return false;
    int id = FOOD_IDS[pick];
    int before = c.getUnnotedInventoryItemCount(id);
    int h = hpNow();
    c.itemCommand(id);
    if (waitFor(() -> c.getUnnotedInventoryItemCount(id) < before, 2500)) {
      eaten++;
      waitFor(() -> c.getCurrentStat(HITS) > h, 1500);
      log("Ate a " + c.getItemName(id) + " at " + h + "/" + hpMax() + " HP, now " + hpNow() + " (" + carriedFood() + " left)");
      return true;
    }
    return false;
  }

  // ---------------------------------------------------------------------------------------------
  // Walking
  // ---------------------------------------------------------------------------------------------

  private int nearestWaypoint(int[][] route, int maxDist) {
    Controller c = c();
    int best = -1, bestD = Integer.MAX_VALUE;
    for (int i = 0; i < route.length; i++) {
      int d = c.distanceTo(route[i][0], route[i][1]);
      if (d <= maxDist && d < bestD) {
        bestD = d;
        best = i;
      }
    }
    return best;
  }

  private int distToRoute(int[][] route) {
    Controller c = c();
    int best = Integer.MAX_VALUE;
    for (int[] p : route) best = Math.min(best, c.distanceTo(p[0], p[1]));
    return best;
  }

  /** Walks a route until the waypoint closest to {@code target}, then to the target itself. */
  private boolean walkRouteTo(int[][] route, int[] target, String name) {
    int cut = 0, bestD = Integer.MAX_VALUE;
    for (int i = 0; i < route.length; i++) {
      int d = Math.max(Math.abs(route[i][0] - target[0]), Math.abs(route[i][1] - target[1]));
      if (d < bestD) {
        bestD = d;
        cut = i;
      }
    }
    int[][] part = concat(java.util.Arrays.copyOf(route, cut + 1), new int[][] {target});
    return walkRouteFully(part, name, 20 * 60_000L);
  }

  private boolean walkRouteFully(int[][] route, String name, long ms) {
    Controller c = c();
    long until = System.currentTimeMillis() + ms;
    while (c.isRunning() && System.currentTimeMillis() < until) {
      if (walkRoute(route, name)) return true;
      if (nearestWaypoint(route, 90) < 0) return false;
    }
    return false;
  }

  /**
   * Walks a route from its nearest waypoint to its end. Attacked: walks on (away). Low HP: eats on
   * the journey, rests while thieving.
   */
  private boolean walkRoute(int[][] route, String name) {
    Controller c = c();
    int start = nearestWaypoint(route, 25);
    if (start < 0) {
      int far = nearestWaypoint(route, 90);
      if (far < 0) return false;
      if (!c.walkTowards(route[far][0], route[far][1])) c.sleep(1200);
      c.sleep(600);
      return false;
    }
    int[] a = route[start];
    if (start + 1 < route.length) {
      int[] b = route[start + 1];
      if (Math.max(Math.abs(a[0] - b[0]), Math.abs(a[1] - b[1])) >= c.distanceTo(b[0], b[1])) start++;
    }
    for (int i = start; i < route.length && c.isRunning(); i++) {
      int wx = route[i][0], wy = route[i][1];
      c.setStatus("@cya@Walking to " + name + " (" + (i + 1) + "/" + route.length + ")");
      state = "Walking to " + name;
      long end = System.currentTimeMillis() + 25_000;
      long lastClick = 0;
      while (c.isRunning() && c.distanceTo(wx, wy) > 1) {
        if (System.currentTimeMillis() > end) {
          if (!c.walkTowards(wx, wy)) c.sleep(1200);
          return false;
        }
        if (hpNow() * 100 <= eatAtPercent * hpMax() && carriedFood() > 0 && !thieving && eatSomething()) continue;
        if (fighting()) {
          int[] away = route[Math.min(i + 1, route.length - 1)];
          if (System.currentTimeMillis() - lastClick > 600) {
            c.walkToAsync(away[0], away[1], 0);
            lastClick = System.currentTimeMillis();
          }
          end = Math.max(end, System.currentTimeMillis() + 5000);
          c.sleep(300);
          continue;
        }
        if (thieving && !resting && hpLow()) {
          restUntilHealthy();
          return false;
        }
        if (System.currentTimeMillis() - lastClick > 2400 || !c.isCurrentlyWalking()) {
          c.walkToAsync(wx, wy, 0);
          lastClick = System.currentTimeMillis();
        }
        c.sleep(300);
      }
    }
    return c.isRunning();
  }

  private boolean walkNear(int x, int y, int radius, long timeoutMs) {
    Controller c = c();
    long until = System.currentTimeMillis() + timeoutMs;
    int fails = 0;
    while (c.isRunning() && System.currentTimeMillis() < until) {
      if (Math.max(Math.abs(c.currentX() - x), Math.abs(c.currentY() - y)) <= radius) return true;
      if (hpNow() * 100 <= eatAtPercent * hpMax() && carriedFood() > 0) eatSomething();
      if (c.walkTowards(x, y)) fails = 0;
      else if (++fails >= 5) return false;
      c.sleep(300);
    }
    return Math.max(Math.abs(c.currentX() - x), Math.abs(c.currentY() - y)) <= radius;
  }

  /** Stops, but never within 15 s of the setup window closing (the v15.6 queue captures it then). */
  private void stopWithReason(String why) {
    Controller c = c();
    weapon.putBack(false); // before the stop (and its 15 s grace)
    c.log(why, why.contains("Done") ? "green" : "red");
    log(why);
    state = why.contains("Done") ? "Arrived at Port Sarim" : "Stopped";
    long wait = runStartedAt + MIN_RUN_BEFORE_STOP_MS - System.currentTimeMillis();
    while (c.isRunning() && wait > 0) {
      c.setStatus("@red@Stopping in " + (wait / 1000 + 1) + "s");
      notStalled(5000);
      try {
        Thread.sleep(Math.min(1000, wait));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      wait = runStartedAt + MIN_RUN_BEFORE_STOP_MS - System.currentTimeMillis();
    }
    if (c.isRunning()) c.stop();
  }

  public String getStatusSuffix() {
    Controller c = c();
    if (c == null) return null;
    String l = leg.isEmpty() ? state : leg;
    if (thieving) l = "Thieving " + c.getInventoryItemCount(COINS) + "/" + coinGoal + "gp";
    return l;
  }

  // ---------------------------------------------------------------------------------------------
  // Setup
  // ---------------------------------------------------------------------------------------------

  private boolean setup(String[] param) {
    String joined = param == null ? "" : String.join(" ", param).trim();
    if (!joined.isEmpty()) {
      for (String a : joined.split("[\\s,]+")) {
        String[] kv = a.split("=", 2);
        String k = kv[0].toLowerCase();
        String v = kv.length > 1 ? kv[1] : "on";
        boolean on = !(v.equalsIgnoreCase("off") || v.equalsIgnoreCase("false") || v.equals("0") || v.equalsIgnoreCase("no"));
        if (k.equals("buffer")) fareBuffer = parseInt(v, 10, 0, 500);
        else if (k.equals("food")) foodCount = parseInt(v, 10, 1, 25);
        else if (k.equals("eatat")) eatAtPercent = parseInt(v.replace("%", ""), 50, 10, 90);
        else if (k.equals("resthp")) restHpPercent = parseInt(v.replace("%", ""), 50, 20, 90);
        else if (k.equals("fullhp")) fullHpPercent = parseInt(v.replace("%", ""), 90, 30, 100);
        else if (k.equals("bank")) useBank = on;
      }
      fullHpPercent = Math.max(fullHpPercent, restHpPercent + 10);
      return true;
    }
    if (java.awt.GraphicsEnvironment.isHeadless()) return true;
    SwingUtilities.invokeLater(this::showSetup);
    Controller c = c();
    while (c.isRunning() && !setupDone && !setupCancelled) c.sleep(200);
    if (!setupDone) {
      log("Setup window closed - not starting");
      c.stop();
      return false;
    }
    return true;
  }

  private static int parseInt(String v, int def, int lo, int hi) {
    try {
      return Math.max(lo, Math.min(hi, Integer.parseInt(v.trim())));
    } catch (NumberFormatException e) {
      return def;
    }
  }

  static final Color BG = new Color(0x282828);
  static final Color BG2 = new Color(0x333333);
  static final Color GOLD = new Color(0xD4AF37);
  static final Color TEXT = new Color(0xE0E0E0);

  private <T extends JComponent> T style(T comp) {
    comp.setForeground(TEXT);
    comp.setBackground(BG2);
    if (comp instanceof JCheckBox || comp instanceof JLabel) comp.setOpaque(false);
    return comp;
  }

  private JPanel row(String label, JComponent comp) {
    JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    r.setOpaque(false);
    r.add(style(new JLabel(label)));
    r.add(style(comp));
    return r;
  }

  private void showSetup() {
    JFrame f = new JFrame("Bens ArdyToSarim");
    JPanel outer = new JPanel(new BorderLayout(0, 6));
    outer.setBackground(BG);
    outer.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(2, 2, 2, 2, GOLD),
            BorderFactory.createEmptyBorder(8, 12, 10, 12)));
    JLabel title = new JLabel("\u2726 Bens ArdyToSarim \u2726", SwingConstants.CENTER);
    title.setForeground(GOLD);
    title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
    JLabel sub = new JLabel("Ardougne \u2192 Brimhaven \u2192 Musa Point \u2192 Port Sarim (60gp fares)", SwingConstants.CENTER);
    sub.setForeground(new Color(0xA89060));
    JPanel head = new JPanel(new GridLayout(0, 1));
    head.setOpaque(false);
    head.add(title);
    head.add(sub);

    JTextField bufferField = new JTextField(String.valueOf(fareBuffer), 4);
    JTextField foodField = new JTextField(String.valueOf(foodCount), 3);
    JTextField eatField = new JTextField(String.valueOf(eatAtPercent), 3);
    JTextField restField = new JTextField(String.valueOf(restHpPercent), 3);
    JTextField fullField = new JTextField(String.valueOf(fullHpPercent), 3);
    JCheckBox bank = style(new JCheckBox("Check the bank first for coins, food and a net/harpoon", useBank));

    JPanel p = new JPanel(new GridLayout(0, 1, 4, 4));
    p.setOpaque(false);
    p.add(row("Coins over the 60gp fares (buffer):", bufferField));
    p.add(row("Fish to catch & cook when there's no food:", foodField));
    p.add(row("Eat on the journey at HP %:", eatField));
    JPanel r = row("Thieving: rest at HP %:", restField);
    r.add(style(new JLabel("until HP %:")));
    r.add(style(fullField));
    p.add(r);
    p.add(bank);
    JLabel note = style(new JLabel("Food fishing uses only a net or harpoon (bought at Harry's if needed)."));
    note.setForeground(new Color(0xA89060));
    p.add(note);

    JButton start = new JButton("Start");
    start.setBackground(GOLD);
    start.setForeground(Color.BLACK);
    start.setFont(start.getFont().deriveFont(Font.BOLD));
    start.addActionListener(
        e -> {
          fareBuffer = parseInt(bufferField.getText(), 10, 0, 500);
          foodCount = parseInt(foodField.getText(), 10, 1, 25);
          eatAtPercent = parseInt(eatField.getText().replace("%", ""), 50, 10, 90);
          restHpPercent = parseInt(restField.getText().replace("%", ""), 50, 20, 90);
          fullHpPercent = Math.max(parseInt(fullField.getText().replace("%", ""), 90, 30, 100), restHpPercent + 10);
          useBank = bank.isSelected();
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
    outer.add(head, BorderLayout.NORTH);
    outer.add(p, BorderLayout.CENTER);
    outer.add(start, BorderLayout.SOUTH);
    f.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    f.setContentPane(outer);
    f.pack();
    f.setLocationRelativeTo(null);
    f.setVisible(true);
  }

  // ---------------------------------------------------------------------------------------------
  // Interrupts and paint
  // ---------------------------------------------------------------------------------------------

  @Override
  public void serverMessageInterrupt(String message) {
    String m = message.toLowerCase();
    if (m.contains("stunned")) stunnedUntil = System.currentTimeMillis() + 5000;
    if (m.contains("under attack") || m.contains("whilst you are fighting") || m.contains("can't retreat"))
      fightSeenAt = System.currentTimeMillis();
    if (m.contains("nicely cooked")) cookedCount++;
    if (m.contains("accidentally burn")) burntCount++;
  }

  @Override
  public void questMessageInterrupt(String message) {
    String m = message.toLowerCase();
    if (m.contains("fail to pick")) {
      pickFails++;
      fightSeenAt = System.currentTimeMillis();
    }
    if (m.contains("stunned")) stunnedUntil = System.currentTimeMillis() + 5000;
  }

  @Override
  public void paintInterrupt() {
    Controller c = c();
    if (c != null) {
      paintBuilder.setTitleCenteredSingleColor("Bens ArdyToSarim", 0xD4AF37, 4);
      paintBuilder.addSpacerRow(4);
      paintBuilder.setBackgroundColor(0x282828, 200);
      paintBuilder.setBorderColor(0xD4AF37);
      paintBuilder.addRow(rowBuilder.centeredSingleStringRow(leg.isEmpty() ? "Starting" : leg, 0xFFE066, 1));
      paintBuilder.addRow(rowBuilder.centeredSingleStringRow(state, 0xE0E0E0, 1));
      paintBuilder.addSpacerRow(4);
      int coins = c.getInventoryItemCount(COINS);
      paintBuilder.addRow(
          rowBuilder.multipleStringRow(
              new String[] {"Coins:", coins + "/" + Math.max(coinGoal, FARES) + "gp", faresPaid + "/2 fares"},
              new int[] {0xD4AF37, 0xE0E0E0, 0xFFE066},
              new int[] {4, 82, 70},
              1));
      paintBuilder.addRow(
          rowBuilder.multipleStringRow(
              new String[] {"HP:", hpNow() + "/" + hpMax(), "food " + carriedFood()},
              new int[] {0xD4AF37, 0xE0E0E0, 0xFFE066},
              new int[] {4, 82, 70},
              1));
      if (pickpockets > 0 || thieving)
        paintBuilder.addRow(
            rowBuilder.multipleStringRow(
                new String[] {"Thieved:", coinsThieved + "gp", hpRests + " rests"},
                new int[] {0xD4AF37, 0xE0E0E0, 0xFFE066},
                new int[] {4, 82, 70},
                1));
      if (fished > 0 || cookedCount > 0)
        paintBuilder.addRow(
            rowBuilder.multipleStringRow(
                new String[] {"Fish:", fished + " caught", cookedCount + " cooked, " + burntCount + " burnt"},
                new int[] {0xD4AF37, 0xE0E0E0, 0xFFE066},
                new int[] {4, 82, 70},
                1));
      long min = (System.currentTimeMillis() - (runStartedAt == 0 ? System.currentTimeMillis() : runStartedAt)) / 60_000;
      String wt = weapon.paintText();
      if (!wt.isEmpty()) paintBuilder.addRow(rowBuilder.centeredSingleStringRow("Weapon: " + wt, 0xFFB366, 1));
      paintBuilder.addRow(rowBuilder.centeredSingleStringRow("Running " + min + " min", 0xA89060, 1));
    }
    paintBuilder.draw();
  }

  // ---------------------------------------------------------------------------------------------
  // Weapon guard: nothing in the weapon hand while pickpocketing. The weapon's item id is saved
  // per account in settings/weapon-guard/<script>_<account>.txt (survives a reload / crash), kept
  // out of every deposit / drop, and put back on before the script stops. Shields and armour
  // stay as they are (a two-hander sits in the weapon slot, so it counts as the weapon).
  // ---------------------------------------------------------------------------------------------

  final WeaponGuard weapon = new WeaponGuard("BensArdyToSarim");

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
