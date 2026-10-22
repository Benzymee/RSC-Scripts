import bot.Main;
import bot.tabs.MassStart;
import bot.tabs.MassStartSettings;
import bot.ui.scriptselector.models.Category;
import bot.ui.scriptselector.models.Parameter;
import bot.ui.scriptselector.models.ScriptInfo;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFormattedTextField;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import models.entities.SkillId;
import orsc.ORSCharacter;
import scripting.idlescript.IdleScript;

/**
 * Ben's Ardy Thiever - progressive pickpocketing in East Ardougne with a Catherby fish-and-cook
 * food resupply loop. Ironman safe: no trading; tools are only bought (optional) from Harry's
 * default stock.
 *
 * <p>NPC ids, levels, xp and spawn boxes come from the OpenRSC server data Coleslaw uses
 * (Thieving.java Pickpocket enum, NpcDefs.json, NpcLocs.json). Stand tiles and routes were checked
 * against AIOQuester's baked collision map (doors closed) and keep away from aggressive NPC boxes.
 * Self-contained: imports nothing from AIOQuester.
 *
 * <p>Weapons: before thieving (and whenever one is seen wielded later, e.g. after a death) the
 * wielded weapon is unequipped straight into the bank. Armour stays worn. This script never
 * equips anything.
 *
 * <p>Parameters (skip the window): target:auto|man|farmer|warrior|guard|knight|paladin|hero
 * cap:N food:best|shark|... eat:P carry:N resupply:N fish:N fishing:on|off buytools:on|off
 * coins:bank|keep stopat:N stall:N castle:on|off logout:on|off
 */
public class BensArdyThiever extends IdleScript implements MassStartSettings {
  public static final ScriptInfo info =
      new ScriptInfo(
          new Category[] {Category.THIEVING, Category.FISHING, Category.COOKING, Category.IRONMAN_SUPPORTED},
          "Ben",
          "Progressive East Ardougne thiever: pickpockets the best NPC your Thieving allows\n"
              + "(Man, Farmer, Warrior, Guard, Knight, Paladin, Hero), eats the best food, banks\n"
              + "in Ardougne, banks any wielded weapon (armour stays on), and when food runs low\n"
              + "fishes + cooks a set amount in Catherby, then walks back and carries on.",
          new Parameter[] {
            new Parameter("target:<auto|man|farmer|warrior|guard|knight|paladin|hero>", "NPC (default auto = best allowed)"),
            new Parameter("cap:<lvl>", "Highest NPC thieving level to use (default 99)"),
            new Parameter("food:<best|shark|swordfish|...>", "Food to eat (default best)"),
            new Parameter("eat:<percent>", "Eat below this % HP (default 50)"),
            new Parameter("carry:<n>", "Food to carry (default 12)"),
            new Parameter("resupply:<n>", "Resupply when bank+inventory food is below n (default 15)"),
            new Parameter("fish:<n>", "Fish to catch and cook per resupply trip (default 200)"),
            new Parameter("fishing:<on|off>", "Catherby food resupply (default on)"),
            new Parameter("buytools:<on|off>", "Buy missing tools at Harry's (default off)"),
            new Parameter("coins:<bank|keep>", "Bank coins or keep them (default bank)"),
            new Parameter("stopat:<lvl>", "Stop at this Thieving level (0 = never)")
          },
          "Ben's Ardy Thiever");

  private static final String P = "[BensArdyThiever] ";

  // =========================================================================================
  // Data
  // =========================================================================================

  private static final int COINS = 10;
  private static final int FLOOR = 944;

  /** Ardougne banks: south (market) and north (bankers, NpcLocs id 95). */
  private static final int[] S_BANK = {551, 612};
  private static final int[] N_BANK = {581, 573};
  private static final int[] CATH_BANK = {441, 495};

  /** Castle courtyard behind gate 57 at 598,603 (SceneryLocs, "Open"). */
  private static final int[] CASTLE_IN = {599, 597, 612, 609};
  private static final int[] GATE = {598, 603};
  private static final int GATE_CLOSED = 57;
  private static final int[] GATE_OUT = {597, 603};
  private static final int[] GATE_IN = {599, 603};

  static final class Area {
    final String name;
    final int[] stand;
    final int[][] boxes; // {minX,minY,maxX,maxY}
    final boolean north; // banks at the north bank (else south)
    final boolean castle;
    final int[][] route; // from its bank to the stand (castle: to the outside of the gate)

    Area(String name, int[] stand, int[][] boxes, boolean north, boolean castle, int[][] route) {
      this.name = name;
      this.stand = stand;
      this.boxes = boxes;
      this.north = north;
      this.castle = castle;
      this.route = route;
    }

    boolean contains(int x, int y, int pad) {
      for (int[] b : boxes)
        if (x >= b[0] - pad && x <= b[2] + pad && y >= b[1] - pad && y <= b[3] + pad) return true;
      return false;
    }
  }

  // Routes: collision-map shortest paths (doors closed), costed away from aggressive NPC boxes.
  static final int[][] R_S_MARKET_MAN = {{551, 612}, {552, 606}, {562, 604}, {572, 600}, {582, 605}};
  static final int[][] R_N_MAN_NW = {{581, 573}, {584, 569}, {586, 579}, {587, 589}, {587, 599}, {596, 601}, {600, 591}, {610, 586}, {606, 584}, {606, 583}};
  static final int[][] R_N_CASTLE = {{581, 573}, {584, 569}, {586, 579}, {587, 589}, {587, 599}, {597, 603}};
  static final int[][] R_S_GUARD_MARKET = {{551, 612}, {543, 602}, {547, 594}};
  static final int[][] R_S_GUARD_EAST = {{551, 612}, {552, 606}, {561, 602}, {563, 599}, {568, 595}};
  static final int[][] R_S_KNIGHT = {{551, 612}, {544, 602}, {549, 597}};
  static final int[][] R_S_HERO = {{551, 612}, {545, 602}, {548, 599}};
  static final int[][] R_N_FARMER_FIELD = {{581, 573}, {571, 568}, {562, 565}, {562, 555}, {561, 551}, {560, 552}};
  static final int[][] R_N_FARMER_NORTH = {{581, 573}, {584, 568}, {587, 567}, {587, 557}, {582, 547}, {574, 537}, {574, 527}, {576, 525}};
  static final int[][] R_N_WARRIOR_NORTH = {{581, 573}, {584, 568}, {587, 567}, {593, 557}, {602, 547}, {611, 537}, {614, 534}};
  static final int[][] R_N_GUARD_NORTH = {{581, 573}, {571, 568}, {564, 568}};
  static final int[][] R_S_TO_N = {{551, 612}, {543, 602}, {543, 592}, {548, 582}, {558, 572}, {568, 568}, {578, 568}, {581, 573}};
  static final int[][] R_N_TO_S = reverse(R_S_TO_N);
  /** Thief2Ardougne's ARDOUGNE_TO_CATHERBY (south of Seers, clear of the bats/renegade knights). */
  static final int[][] R_ARDY_TO_CATH = {
    {551, 612}, {550, 602}, {550, 592}, {550, 582}, {558, 572}, {563, 563}, {563, 553}, {563, 543},
    {570, 533}, {563, 529}, {553, 523}, {543, 523}, {533, 523}, {523, 523}, {513, 523}, {503, 517},
    {493, 507}, {483, 505}, {473, 504}, {463, 500}, {453, 500}, {443, 500}, {441, 497}, {441, 495}
  };
  static final int[][] R_CATH_TO_ARDY = reverse(R_ARDY_TO_CATH);

  static final Area MAN_MARKET = new Area("Men by the market (582,605)", new int[] {582, 605}, new int[][] {{574, 603, 588, 607}}, false, false, R_S_MARKET_MAN);
  static final Area MAN_NW = new Area("Men, north-west East Ardougne", new int[] {606, 583}, new int[][] {{601, 575, 612, 590}}, true, false, R_N_MAN_NW);
  static final Area FARMER_FIELD = new Area("Farmers north of Ardougne", new int[] {560, 552}, new int[][] {{554, 545, 564, 558}}, true, false, R_N_FARMER_FIELD);
  static final Area FARMER_NORTH = new Area("Farmer by the north road", new int[] {576, 525}, new int[][] {{571, 520, 581, 530}}, true, false, R_N_FARMER_NORTH);
  static final Area WARRIOR_NORTH = new Area("Warriors north-west of Ardougne", new int[] {614, 534}, new int[][] {{608, 528, 621, 541}}, true, false, R_N_WARRIOR_NORTH);
  static final Area CASTLE = new Area("Castle courtyard (gate)", new int[] {606, 604}, new int[][] {CASTLE_IN}, true, true, R_N_CASTLE);
  static final Area GUARD_MARKET = new Area("Guards in the market", new int[] {547, 594}, new int[][] {{538, 578, 558, 598}, {544, 558, 552, 603}, {527, 590, 537, 600}, {536, 588, 556, 608}}, false, false, R_S_GUARD_MARKET);
  static final Area GUARD_NORTH = new Area("Guards by the north bank", new int[] {564, 568}, new int[][] {{562, 564, 567, 572}}, true, false, R_N_GUARD_NORTH);
  static final Area GUARD_EAST = new Area("Guards east of the market", new int[] {568, 595}, new int[][] {{564, 592, 572, 599}}, false, false, R_S_GUARD_EAST);
  static final Area KNIGHT_MARKET = new Area("Market (knights/paladins)", new int[] {549, 597}, new int[][] {{544, 582, 554, 608}}, false, false, R_S_KNIGHT);
  static final Area HERO_MARKET = new Area("Market (hero)", new int[] {548, 599}, new int[][] {{543, 594, 553, 604}}, false, false, R_S_HERO);

  static final class Target {
    final String key, name;
    final int npcId, level, maxHit;
    final double xp;
    final Area[] areas;

    Target(String key, String name, int npcId, int level, double xp, int maxHit, Area... areas) {
      this.key = key;
      this.name = name;
      this.npcId = npcId;
      this.level = level;
      this.xp = xp;
      this.maxHit = maxHit;
      this.areas = areas;
    }
  }

  /** Pickpocket enum (server xp is in quarter points: 32 = 8 xp). NPC ids from NpcDefs.json. */
  static final Target[] TARGETS = {
    new Target("man", "Man", 318, 1, 8.0, 2, MAN_MARKET, MAN_NW),
    new Target("farmer", "Farmer", 319, 10, 14.5, 3, FARMER_FIELD, FARMER_NORTH),
    new Target("warrior", "Warrior", 320, 25, 26.0, 4, WARRIOR_NORTH, CASTLE),
    new Target("guard", "Guard", 321, 40, 46.75, 4, GUARD_MARKET, GUARD_EAST, GUARD_NORTH),
    new Target("knight", "Knight", 322, 55, 84.25, 7, KNIGHT_MARKET, CASTLE),
    new Target("paladin", "Paladin", 323, 70, 151.75, 10, KNIGHT_MARKET, CASTLE),
    new Target("hero", "Hero", 324, 80, 273.25, 10, HERO_MARKET),
  };

  /** {cooked id, heal} from ItemEdibleHeals.xml; names from ItemDefs.json. */
  static final int[][] FOODS = {
    {546, 20}, {370, 14}, {373, 12}, {367, 10}, {357, 9}, {364, 8}, {359, 7}, {362, 5},
    {355, 4}, {138, 4}, {350, 3}, {132, 3}, {352, 1}
  };
  static final String[] FOOD_NAMES = {
    "Shark", "Swordfish", "Lobster", "Tuna", "Salmon", "Pike", "Trout", "Herring",
    "Sardine", "Bread", "Shrimp", "Cooked meat", "Anchovies"
  };
  static final int[] BURNT = {353, 360, 365, 368, 371, 374, 547, 134};

  // Fishing tools (ItemDefs) and Harry's default stock (HarrysFishingShack.java).
  static final int NET = 376, ROD = 377, HARPOON = 379, POT = 375, BAIT = 380;
  static final int[][] HARRY_STOCK = {{NET, 3}, {ROD, 3}, {HARPOON, 2}, {POT, 2}, {BAIT, 200}};
  static final int HARRY = 250;
  static final int[] HARRY_TILE = {418, 487};
  static final int[] RANGE = {432, 480}; // object 11
  static final int[] COOK_DOOR = {435, 486};
  static final int[] BANK_DOOR = {439, 497}; // object 64 when closed (BensFishFarm)

  static final class Method {
    final String name;
    final int tool, bait, spotId, option, fishLvl, cookLvl, heal;
    final int[][] spots;
    final int[] raw;

    Method(String name, int tool, int bait, int spotId, int option, int fishLvl, int cookLvl, int heal, int[][] spots, int... raw) {
      this.name = name;
      this.tool = tool;
      this.bait = bait;
      this.spotId = spotId;
      this.option = option;
      this.fishLvl = fishLvl;
      this.cookLvl = cookLvl;
      this.heal = heal;
      this.spots = spots;
      this.raw = raw;
    }
  }

  // ObjectFishing.xml: 193 net/bait, 194 harpoon/cage, 261 big net/harpoon. Catherby SceneryLocs.
  static final int[][] SPOTS_193 = {{418, 500}, {414, 502}};
  static final int[][] SPOTS_194 = {{409, 504}};
  static final int[][] SPOTS_261 = {{406, 505}, {402, 507}, {399, 503}, {398, 505}};
  static final Method[] METHODS = {
    new Method("Sharks", HARPOON, -1, 261, 2, 76, 80, 20, SPOTS_261, 545),
    new Method("Swordfish/tuna", HARPOON, -1, 194, 1, 50, 45, 14, SPOTS_194, 369, 366),
    new Method("Lobsters", POT, -1, 194, 2, 40, 40, 12, SPOTS_194, 372),
    new Method("Tuna", HARPOON, -1, 194, 1, 35, 30, 10, SPOTS_194, 366),
    new Method("Herring/sardine", ROD, BAIT, 193, 2, 10, 5, 5, SPOTS_193, 361, 354),
    new Method("Sardine", ROD, BAIT, 193, 2, 5, 1, 4, SPOTS_193, 354),
    new Method("Shrimp/anchovies", NET, -1, 193, 1, 1, 1, 3, SPOTS_193, 349, 351),
  };

  /** ItemCookingDef.xml required levels. */
  static int cookLevel(int raw) {
    switch (raw) {
      case 361: return 5;
      case 366: return 30;
      case 372: return 40;
      case 369: return 45;
      case 545: return 80;
      default: return 1;
    }
  }

  // =========================================================================================
  // Settings
  // =========================================================================================

  String targetMode = "auto";
  int levelCap = 99;
  String foodChoice = "best";
  int eatPercent = 50;
  int foodCarry = 12;
  int resupplyBelow = 15;
  int fishPerTrip = 200;
  boolean fishingEnabled = true;
  boolean buyTools = false;
  boolean bankCoins = true;
  int stopAtLevel = 0;
  int stallMinutes = 5;
  boolean useCastle = true;
  boolean logoutWhenStuck = true;

  private volatile boolean guiShown = false;
  private volatile boolean scriptStarted = false;
  private boolean initialised = false;
  private JFrame frame = null;

  // =========================================================================================
  // State
  // =========================================================================================

  enum Phase { BANK, THIEVE, TO_CATH, CATH_BANK, FISH, COOK, TO_ARDY }

  Phase phase = Phase.BANK;
  String status = "Starting";
  Target target = null;
  Area area = null;
  int areaIdx = 0;
  Method method = null;
  long startTime;
  int startThXp, startThLvl, startFishXp, startCookXp;
  int picks = 0, fails = 0, attempts = 0, busyCount = 0, eaten = 0;
  int coinsGained = 0, fished = 0, cookedCount = 0, burntCount = 0, tripCooked = 0, trips = 0;
  int weaponsBanked = 0, deaths = 0;
  int lastFoodBank = -1;
  boolean firstCathVisit = true;
  long lastXpAt = 0;
  int lastXp = 0;
  int stalls = 0;
  long noNpcSince = 0;
  long lowerTargetUntil = 0;
  final Map<Integer, Long> skipNpc = new HashMap<>();
  final Map<String, Integer> loot = new LinkedHashMap<>();
  int[] lastInv = null;
  volatile int lastNpcIdx = -1;
  volatile long busyAt = 0, failAt = 0, fishMissUntil = 0, diedAt = 0, fightSeenAt = 0;
  long lostSince = 0;
  int lostBestDist = Integer.MAX_VALUE;
  long runStartedAt = 0;

  // =========================================================================================
  // Lifecycle
  // =========================================================================================

  @Override
  public int start(String[] parameters) {
    if (!initialised) init(parameters);
    if (!controller.isRunning()) return 1000;
    if (!scriptStarted) {
      if (!guiShown) {
        guiShown = true;
        status = "Waiting for setup window";
        SwingUtilities.invokeLater(this::setupGUI);
      }
      return 500;
    }
    if (!controller.isLoggedIn()) return 1000;
    if (runStartedAt == 0) begin();
    if (controller.getNeedToMove()) controller.moveCharacter();
    if (controller.getShouldSleep()) controller.sleepHandler(true);
    try {
      return tick();
    } catch (Exception e) {
      log("step failed: " + e);
      return 2000;
    }
  }

  private void init(String[] params) {
    initialised = true;
    boolean any = false;
    if (params != null) {
      for (String raw : params) {
        if (raw == null) continue;
        for (String s : raw.trim().toLowerCase().split("[\\s,]+")) {
          if (s.isEmpty()) continue;
          any = true;
          int sep = Math.max(s.indexOf(':'), s.indexOf('='));
          String k = sep > 0 ? s.substring(0, sep) : s, v = sep > 0 ? s.substring(sep + 1) : "on";
          boolean on = !(v.equals("off") || v.equals("false") || v.equals("no") || v.equals("0"));
          if (k.equals("target")) targetMode = findTarget(v) != null ? v : "auto";
          else if (k.equals("cap")) levelCap = clamp(parse(v, 99), 1, 99);
          else if (k.equals("food")) foodChoice = foodIdByName(v) > 0 ? v.replace("_", " ") : "best";
          else if (k.equals("eat")) eatPercent = clamp(parse(v, 50), 10, 95);
          else if (k.equals("carry")) foodCarry = clamp(parse(v, 12), 1, 28);
          else if (k.equals("resupply")) resupplyBelow = clamp(parse(v, 15), 0, 5000);
          else if (k.equals("fish")) fishPerTrip = clamp(parse(v, 200), 1, 10000);
          else if (k.equals("fishing")) fishingEnabled = on;
          else if (k.equals("buytools")) buyTools = on;
          else if (k.equals("coins")) bankCoins = !v.startsWith("keep");
          else if (k.equals("stopat")) stopAtLevel = clamp(parse(v, 0), 0, 99);
          else if (k.equals("stall")) stallMinutes = clamp(parse(v, 5), 1, 60);
          else if (k.equals("castle")) useCastle = on;
          else if (k.equals("logout")) logoutWhenStuck = on;
          else controller.displayMessage("@red@Ben's Ardy Thiever: @whi@unknown parameter '" + s + "'");
        }
      }
    }
    if (!any) return;
    if (MassStart.isLeaderSetup()) return; // the window opens prefilled for the leader
    scriptStarted = true;
  }

  @Override
  public String[] massStartArguments() {
    return new String[] {
      "target:" + targetMode, "cap:" + levelCap, "food:" + foodChoice.replace(" ", "_"), "eat:" + eatPercent,
      "carry:" + foodCarry, "resupply:" + resupplyBelow, "fish:" + fishPerTrip,
      "fishing:" + (fishingEnabled ? "on" : "off"), "buytools:" + (buyTools ? "on" : "off"),
      "coins:" + (bankCoins ? "bank" : "keep"), "stopat:" + stopAtLevel, "stall:" + stallMinutes,
      "castle:" + (useCastle ? "on" : "off"), "logout:" + (logoutWhenStuck ? "on" : "off")
    };
  }

  private void begin() {
    runStartedAt = startTime = System.currentTimeMillis();
    startThXp = lastXp = controller.getStatXp(SkillId.THIEVING.getId());
    startThLvl = controller.getBaseStat(SkillId.THIEVING.getId());
    startFishXp = controller.getStatXp(SkillId.FISHING.getId());
    startCookXp = controller.getStatXp(SkillId.COOKING.getId());
    lastXpAt = System.currentTimeMillis();
    String s = String.join(" ", massStartArguments());
    log("Started: " + s);
    controller.displayMessage("@gre@Ben's Ardy Thiever started: @whi@" + s);
    if (nearCatherby()) {
      phase = Phase.CATH_BANK; // started in Catherby: do a fishing trip first
      firstCathVisit = true;
      tripCooked = 0;
      trips++;
      log("Started in Catherby: fishing/cooking a food trip first (" + fishPerTrip + " fish)");
    } else phase = Phase.BANK; // weapon check + food before thieving
  }

  @Override
  public void cleanup() {
    JFrame f = frame;
    if (f != null && f.isDisplayable()) SwingUtilities.invokeLater(f::dispose);
  }

  // =========================================================================================
  // Main step
  // =========================================================================================

  private int tick() {
    int x = controller.currentX(), y = controller.currentY();
    if (x <= 0 || y <= 0) return 1000;
    trackLoot();
    if (y >= FLOOR) {
      setPhaseStatus("Upstairs: climbing down");
      int[] ladder = controller.getNearestObjectByIds(new int[] {6, 42, 44});
      if (ladder != null) {
        controller.atObject(ladder[0], ladder[1]);
        controller.sleepUntil(() -> controller.currentY() < FLOOR, 8000);
      }
      return 600;
    }
    if (!inKnownRegion(x, y)) return lost();
    lostSince = 0;
    if (fighting()) return escapeCombat();

    switch (phase) {
      case BANK: return ardyBank();
      case THIEVE: return thieve();
      case TO_CATH: return toCatherby();
      case CATH_BANK: return cathBank();
      case FISH: return fish();
      case COOK: return cook();
      case TO_ARDY: return toArdougne();
      default: phase = Phase.BANK; return 100;
    }
  }

  // ---- Target selection ----

  private Target findTarget(String key) {
    for (Target t : TARGETS) if (t.key.equalsIgnoreCase(key)) return t;
    return null;
  }

  private int thLvl() {
    return controller.getCurrentStat(SkillId.THIEVING.getId());
  }

  /** Highest NPC the current Thieving level (and the cap / HP safety) allows, or the forced one. */
  private Target chooseTarget() {
    Target forced = "auto".equals(targetMode) ? null : findTarget(targetMode);
    int lvl = thLvl();
    int maxHp = maxHp();
    Target best = TARGETS[0];
    for (Target t : TARGETS) {
      if (t.level > lvl || t.level > levelCap) continue;
      if (maxHp < 3 * t.maxHit + 5) continue; // a failed pickpocket costs up to ~3 hits
      if (!useCastle && onlyCastle(t)) continue;
      if (forced != null && t.level > forced.level) continue;
      best = t;
    }
    if (System.currentTimeMillis() < lowerTargetUntil) {
      for (int i = TARGETS.length - 1; i > 0; i--)
        if (TARGETS[i] == best) {
          best = TARGETS[i - 1];
          break;
        }
    }
    return best;
  }

  private boolean onlyCastle(Target t) {
    for (Area a : t.areas) if (!a.castle) return false;
    return true;
  }

  private void setTarget(Target t) {
    if (t == target) return;
    log("Target: " + t.name + " (level " + t.level + ", " + t.xp + " xp) at Thieving " + thLvl()
        + (target == null ? "" : " (was " + target.name + ")"));
    target = t;
    area = pickArea(t, 0);
    stalls = 0;
    noNpcSince = 0;
  }

  private Area pickArea(Target t, int idx) {
    for (int i = 0; i < t.areas.length; i++) {
      int k = (idx + i) % t.areas.length;
      if (t.areas[k].castle && !useCastle) continue;
      areaIdx = k;
      return t.areas[k];
    }
    areaIdx = 0;
    return t.areas[0];
  }

  private void nextArea(String why) {
    Area before = area;
    area = pickArea(target, areaIdx + 1);
    if (area != before) log(why + ": moving to " + area.name);
    noNpcSince = 0;
  }

  // ---- Thieving ----

  private int thieve() {
    if (stopAtLevel > 0 && controller.getBaseStat(SkillId.THIEVING.getId()) >= stopAtLevel) {
      log("Reached Thieving " + stopAtLevel + ": banking and stopping");
      phase = Phase.BANK;
      return 100;
    }
    if (weaponSlot() >= 0) {
      log("A weapon is wielded: banking it before thieving");
      phase = Phase.BANK;
      return 100;
    }
    if (lowHp() && eat()) return 1200;
    int food = invFood();
    setTarget(chooseTarget());
    if (food == 0 && (lowHp() || hp() < minHp(target))) {
      log("Out of food in the inventory (HP " + hp() + "/" + maxHp() + "): banking");
      phase = Phase.BANK;
      return 100;
    }
    if (controller.getInventoryItemCount() >= 29) {
      log("Inventory nearly full: banking");
      phase = Phase.BANK;
      return 100;
    }
    if (!atArea(area)) {
      setPhaseStatus("Walking to " + target.name + ": " + area.name);
      goToArea(area);
      lastXpAt = System.currentTimeMillis(); // walking isn't a stall
      return 300;
    }
    if (hp() < minHp(target)) {
      if (food > 0 && eat()) return 1200;
      setPhaseStatus("HP " + hp() + " too low for " + target.name + ", resting");
      notStalled(5000);
      lastXpAt = System.currentTimeMillis(); // resting isn't a stall
      return 2000;
    }
    int xp = controller.getStatXp(SkillId.THIEVING.getId());
    long now = System.currentTimeMillis();
    if (xp != lastXp) {
      lastXp = xp;
      lastXpAt = now;
      stalls = 0;
    } else if (now - lastXpAt > stallMinutes * 60_000L) {
      stalls++;
      lastXpAt = now;
      log("No Thieving xp for " + stallMinutes + " min (stall " + stalls + ")");
      if (stalls == 1) nextArea("Stall");
      else if (stalls == 2) {
        phase = Phase.BANK;
        return 100;
      } else {
        lowerTargetUntil = now + 15 * 60_000L;
        stalls = 0;
        log("Using the next lower NPC for 15 minutes");
      }
      return 300;
    }
    if (controller.isBatching()) {
      setPhaseStatus("Pickpocketing " + target.name);
      return 400;
    }
    ORSCharacter npc = findNpc();
    if (npc == null) {
      if (noNpcSince == 0) noNpcSince = now;
      setPhaseStatus("Waiting for a " + target.name + " (" + area.name + ")");
      if (now - noNpcSince > 25_000 && target.areas.length > 1) nextArea("No free " + target.name);
      else if (controller.distanceTo(area.stand[0], area.stand[1]) > 4)
        controller.walkToAsync(area.stand[0], area.stand[1], 1);
      notStalled(5000);
      return 1200;
    }
    noNpcSince = 0;
    setPhaseStatus("Pickpocketing " + target.name);
    attempts++;
    lastNpcIdx = npc.serverIndex;
    final int xp0 = xp;
    final long sent = System.currentTimeMillis();
    controller.thieveNpc(npc.serverIndex);
    controller.sleepUntil(
        () -> controller.getStatXp(SkillId.THIEVING.getId()) > xp0 || fighting() || failAt > sent || busyAt > sent,
        4000);
    long until = System.currentTimeMillis() + 30_000;
    while (controller.isRunning() && controller.isBatching() && !fighting() && !lowHp()
        && System.currentTimeMillis() < until) controller.sleep(300);
    if (busyAt > sent) busyCount++;
    return 300;
  }

  private boolean atArea(Area a) {
    int x = controller.currentX(), y = controller.currentY();
    if (a.castle) return inCastle(x, y);
    return !inCastle(x, y) && (a.contains(x, y, 1) || controller.distanceTo(a.stand[0], a.stand[1]) <= 3);
  }

  private ORSCharacter findNpc() {
    final long now = System.currentTimeMillis();
    final Area a = area;
    final int id = target.npcId;
    return controller.getNearestNPCByLambda(
        n -> {
          if (n.npcId != id) return false;
          Long until = skipNpc.get(n.serverIndex);
          if (until != null && until > now) return false;
          if (controller.isNpcInCombat(n.serverIndex)) return false;
          int[] c = controller.getNpcCoordsByServerIndex(n.serverIndex);
          if (c == null || c[0] == -1) return false;
          if (a.castle != inCastle(c[0], c[1])) return false;
          if (!a.contains(c[0], c[1], 3)) return false;
          return controller.isReachable(c[0], c[1], true);
        });
  }

  private int minHp(Target t) {
    return Math.max(3 * t.maxHit + 1, (eatPercent * maxHp() + 99) / 100);
  }

  /** A failed pickpocket makes the NPC attack: walk away until out of combat (RSC lets you retreat). */
  private int escapeCombat() {
    setPhaseStatus("Caught: walking away (HP " + hp() + "/" + maxHp() + ")");
    int[] base = area != null && phase == Phase.THIEVE ? area.stand : null;
    long until = System.currentTimeMillis() + 25_000;
    int n = 0;
    while (controller.isRunning() && fighting() && System.currentTimeMillis() < until) {
      ThreadLocalRandom r = ThreadLocalRandom.current();
      int tx = controller.currentX() + r.nextInt(-5, 6), ty = controller.currentY() + r.nextInt(-5, 6);
      if (base != null && n++ % 3 == 0) {
        tx = base[0] + r.nextInt(-3, 4);
        ty = base[1] + r.nextInt(-3, 4);
      }
      if (controller.isReachable(tx, ty, false)) controller.walkToAsync(tx, ty, 0);
      notStalled(3000);
      controller.sleep(650);
    }
    return 300;
  }

  private boolean fighting() {
    return controller.isInCombat() || System.currentTimeMillis() - fightSeenAt < 2500;
  }

  // ---- Food ----

  private static int heal(int id) {
    for (int[] f : FOODS) if (f[0] == id) return f[1];
    return 0;
  }

  private static int foodIdByName(String n) {
    String s = n.toLowerCase().replace("_", " ").trim();
    for (int i = 0; i < FOODS.length; i++) if (FOOD_NAMES[i].toLowerCase().equals(s)) return FOODS[i][0];
    return -1;
  }

  private int invFood() {
    int n = 0;
    for (int[] f : FOODS) n += controller.getInventoryItemCount(f[0]);
    return n;
  }

  private int bankFood() {
    int n = 0;
    for (int[] f : FOODS) n += controller.getBankItemCount(f[0]);
    return n;
  }

  /** The food to eat: the chosen one if carried, else the best-healing food carried. */
  private int foodToEat() {
    int chosen = foodIdByName(foodChoice);
    if (chosen > 0 && controller.getInventoryItemCount(chosen) > 0) return chosen;
    for (int[] f : FOODS) if (controller.getInventoryItemCount(f[0]) > 0) return f[0];
    return -1;
  }

  /** Eats one food (never in combat: RSC can't). @return true if it tried */
  private boolean eat() {
    if (controller.isInCombat()) return false;
    int id = foodToEat();
    if (id < 0) return false;
    if (controller.isBatching()) controller.stopBatching();
    int before = controller.getInventoryItemCount(id), h = hp();
    controller.itemCommand(id);
    controller.sleepUntil(() -> controller.getInventoryItemCount(id) < before, 2500);
    if (controller.getInventoryItemCount(id) < before) {
      eaten++;
      log("Ate " + controller.getItemName(id) + " at " + h + " HP, now " + hp() + "/" + maxHp());
    }
    return true;
  }

  private int hp() {
    return controller.getCurrentStat(SkillId.HITS.getId());
  }

  private int maxHp() {
    return Math.max(1, controller.getBaseStat(SkillId.HITS.getId()));
  }

  private boolean lowHp() {
    return hp() * 100 < eatPercent * maxHp();
  }

  // ---- Weapons: unequip into the bank (armour stays worn; never equip anything) ----

  /** Equipment index (equipment tab) or inventory slot (authentic) of a wielded weapon, else -1. */
  private int weaponSlot() {
    try {
      if (orsc.Config.S_WANT_EQUIPMENT_TAB) {
        com.openrsc.client.entityhandling.defs.ItemDef[] eq = controller.getMud().equippedItems;
        if (eq == null) return -1;
        for (int i = 0; i < eq.length; i++)
          if (eq[i] != null && (eq[i].wearableID & 16) != 0) return i; // weapons, incl. bows / 2h
        return -1;
      }
      com.openrsc.client.entityhandling.instances.Item[] inv = controller.getMud().getInventory();
      for (int i = 0; i < inv.length; i++) {
        com.openrsc.client.entityhandling.instances.Item it = inv[i];
        if (it == null || it.getItemDef() == null) continue;
        if (it.getEquipped() && it.getItemDef().wieldable && (it.getItemDef().wearableID & 16) != 0) return i;
      }
    } catch (Throwable ignored) {
      // client not ready
    }
    return -1;
  }

  /** Bank open: puts any wielded weapon straight into the bank. */
  private void bankWeapon() {
    for (int tries = 0; tries < 3 && controller.isRunning(); tries++) {
      int slot = weaponSlot();
      if (slot < 0) return;
      String name = "weapon";
      try {
        if (orsc.Config.S_WANT_EQUIPMENT_TAB) {
          name = controller.getMud().equippedItems[slot].getName();
          controller.unequipSlotIndexToBank(slot);
        } else {
          int id = controller.getMud().getInventory()[slot].getItemDef().id;
          name = controller.getItemName(id);
          controller.unequipItem(slot);
          controller.sleepUntil(() -> weaponSlot() < 0, 3000);
          controller.depositItem(id, controller.getInventoryItemCount(id));
        }
      } catch (Throwable t) {
        log("Couldn't unequip the weapon: " + t);
      }
      controller.sleepUntil(() -> weaponSlot() < 0, 3000);
      if (weaponSlot() < 0) {
        weaponsBanked++;
        log("Unequipped " + name + " into the bank (armour stays worn)");
        return;
      }
    }
    if (weaponSlot() >= 0) log("Couldn't bank the wielded weapon - will try again next bank visit");
  }

  // ---- Ardougne bank ----

  private int[] bankFor(Area a) {
    return a != null && a.north ? N_BANK : S_BANK;
  }

  private int ardyBank() {
    setTarget(chooseTarget());
    int[] bank = bankFor(area);
    if (controller.distanceTo(bank[0], bank[1]) > 6 && !controller.isInBank()) {
      setPhaseStatus("Walking to the " + (bank == N_BANK ? "north" : "south") + " bank");
      goToHub(bank);
      return 300;
    }
    setPhaseStatus("Banking");
    if (!openBank(bank)) return 1000;
    bankWeapon();
    depositAllExcept(-1, -1);
    int total = invFood() + bankFood();
    lastFoodBank = bankFood();
    if (stopAtLevel > 0 && controller.getBaseStat(SkillId.THIEVING.getId()) >= stopAtLevel) {
      controller.closeBank();
      log("Thieving " + stopAtLevel + " reached. Stopping. " + summary());
      stopScript();
      return 1000;
    }
    if (fishingEnabled && total < resupplyBelow) {
      Method m = chooseMethod(true);
      if (m != null) {
        startResupply(m, total);
        return 300;
      }
      log("Food low (" + total + ") but no fishing method is possible (levels/tools"
          + (buyTools ? "/coins" : ", buying off") + ")");
    }
    withdrawFood(foodCarry);
    lastFoodBank = bankFood();
    if (invFood() == 0) {
      controller.closeBank();
      String why = "No food left in the bank or inventory" + (fishingEnabled ? " and no resupply possible" : " (resupply off)");
      if (logoutWhenStuck) {
        log(why + ": logging out safely. " + summary());
        controller.logout();
        stopScript();
        return 1000;
      }
      log(why + ": thieving on without food (HP rests only)");
    }
    controller.closeBank();
    controller.sleep(640);
    phase = Phase.THIEVE;
    lastXpAt = System.currentTimeMillis();
    return 300;
  }

  /** Bank open: deposits everything except food, the two kept items, and coins when keeping. */
  private void depositAllExcept(int keepA, int keepB) {
    for (int id : distinctInv()) {
      if (id == keepA || id == keepB || heal(id) > 0) continue;
      if (id == COINS && !bankCoins) continue;
      controller.depositItem(id, controller.getInventoryItemCount(id));
      controller.sleep(200);
    }
  }

  /** Bank open: tops the inventory up to {@code want} food, the chosen food (or best) first. */
  private void withdrawFood(int want) {
    int chosen = foodIdByName(foodChoice);
    java.util.List<Integer> order = new java.util.ArrayList<>();
    if (chosen > 0) order.add(chosen);
    for (int[] f : FOODS) if (f[0] != chosen) order.add(f[0]);
    for (int id : order) {
      int need = Math.min(want - invFood(), 30 - controller.getInventoryItemCount());
      if (need <= 0) break;
      int inBank = controller.getBankItemCount(id);
      if (inBank <= 0) continue;
      final int before = controller.getInventoryItemCount(id);
      controller.withdrawItem(id, Math.min(need, inBank));
      controller.sleepUntil(() -> controller.getInventoryItemCount(id) > before, 4000);
    }
  }

  /** Opens the bank with the nearest banker, bounded (Controller.openBank can wait forever). */
  private boolean openBank(int[] tile) {
    for (int attempt = 0; attempt < 6 && controller.isRunning() && !controller.isInBank(); attempt++) {
      ORSCharacter banker = controller.getNearestNpcByIds(controller.bankerIds, false);
      if (banker == null) {
        controller.walkToAsync(tile[0], tile[1], 1);
        controller.sleep(1280);
        continue;
      }
      if ("Bank".equalsIgnoreCase(controller.getNpcCommand1(banker.npcId))) controller.npcCommand1(banker.serverIndex);
      else {
        controller.talkToNpc(banker.serverIndex);
        if (controller.sleepUntil(() -> controller.isInOptionMenu(), 8000)) {
          int idx = 0;
          for (int i = 0; i < controller.getOptionMenuCount(); i++) {
            String s = controller.getOptionsMenuText(i);
            if (s != null && s.toLowerCase().contains("access my bank")) idx = i;
          }
          controller.optionAnswer(idx);
        }
      }
      controller.sleepUntil(() -> controller.isInBank(), 10000);
    }
    return controller.isInBank();
  }

  // ---- Resupply: Catherby fishing + cooking ----

  private int owned(int id, boolean bankOpen) {
    return controller.getInventoryItemCount(id) + (bankOpen ? controller.getBankItemCount(id) : 0);
  }

  /** Best-healing method allowed by Fishing/Cooking levels and the tools we own (or may buy). */
  private Method chooseMethod(boolean bankOpen) {
    int fishLvl = controller.getBaseStat(SkillId.FISHING.getId());
    int cookLvl = controller.getBaseStat(SkillId.COOKING.getId());
    int coins = owned(COINS, bankOpen);
    for (Method m : METHODS) {
      if (fishLvl < m.fishLvl || cookLvl < m.cookLvl) continue;
      if (owned(m.tool, bankOpen) == 0 && !(buyTools && coins >= 30)) continue;
      if (m.bait > 0 && owned(m.bait, bankOpen) < 30 && !(buyTools && coins >= 150)) continue;
      return m;
    }
    return null;
  }

  private void startResupply(Method m, int food) {
    method = m;
    trips++;
    tripCooked = 0;
    firstCathVisit = true;
    log("Food low (" + food + " in bank+inventory, threshold " + resupplyBelow + "): resupply trip " + trips
        + " to Catherby - " + m.name + ", " + fishPerTrip + " fish");
    withdrawFood(Math.min(5, foodCarry)); // a little food for the walk
    takeTools(m);
    controller.closeBank();
    controller.sleep(640);
    phase = Phase.TO_CATH;
  }

  /** Bank open: withdraw the method's tool and bait. */
  private void takeTools(Method m) {
    if (controller.getInventoryItemCount(m.tool) == 0 && controller.getBankItemCount(m.tool) > 0) {
      controller.withdrawItem(m.tool, 1);
      controller.sleepUntil(() -> controller.getInventoryItemCount(m.tool) > 0, 4000);
    }
    if (m.bait > 0 && controller.getInventoryItemCount(m.bait) == 0) {
      int want = Math.min(fishPerTrip + 20, controller.getBankItemCount(m.bait));
      if (want > 0) {
        controller.withdrawItem(m.bait, want);
        controller.sleepUntil(() -> controller.getInventoryItemCount(m.bait) > 0, 4000);
      }
    }
  }

  private int toCatherby() {
    if (controller.distanceTo(CATH_BANK[0], CATH_BANK[1]) <= 3) {
      phase = Phase.CATH_BANK;
      return 100;
    }
    setPhaseStatus("Walking to Catherby (" + (method == null ? "?" : method.name) + ")");
    if (nearestIndex(R_ARDY_TO_CATH, 12) < 0 && controller.currentX() > 530) {
      goToHub(S_BANK);
      return 300;
    }
    walkRoute(R_ARDY_TO_CATH, "Catherby bank");
    return 300;
  }

  private int toArdougne() {
    if (controller.distanceTo(S_BANK[0], S_BANK[1]) <= 3) {
      log("Back in Ardougne after the resupply trip (" + tripCooked + " cooked)");
      phase = Phase.BANK;
      return 100;
    }
    setPhaseStatus("Walking back to Ardougne");
    if (nearCatherby() && nearestIndex(R_CATH_TO_ARDY, 12) < 0) stepTo(441, 497, 1, 30_000);
    walkRoute(R_CATH_TO_ARDY, "Ardougne south bank");
    return 300;
  }

  private int cathBank() {
    setPhaseStatus("Catherby bank (" + tripCooked + "/" + fishPerTrip + " cooked)");
    if (!controller.isInBank()) {
      if (controller.distanceTo(CATH_BANK[0], CATH_BANK[1]) > 3) stepTo(BANK_DOOR[0], BANK_DOOR[1] + 1, 1, 60_000);
      openBankDoor();
      if (!openBank(CATH_BANK)) return 1000;
    }
    bankWeapon();
    if (method == null) method = chooseMethod(true);
    // Count what we cooked (the food carried here is deposited uncounted on the first visit).
    if (!firstCathVisit && method != null)
      for (int r : method.raw) tripCooked += controller.getInventoryItemCount(cookedOf(r));
    int keepTool = method == null ? -1 : method.tool, keepBait = method == null ? -1 : method.bait;
    for (int id : distinctInv()) {
      if (id == keepTool || id == keepBait) continue;
      if (id == COINS && !bankCoins) continue;
      controller.depositItem(id, controller.getInventoryItemCount(id));
      controller.sleep(200);
    }
    firstCathVisit = false;
    if (tripCooked >= fishPerTrip || method == null) {
      if (method == null) log("No fishing method possible - heading back with what we have");
      finishTrip();
      return 300;
    }
    Method m = chooseMethod(true); // levels may have gone up
    if (m != null && m != method) {
      log("Switching fishing method: " + method.name + " -> " + m.name);
      if (method.tool != m.tool && controller.getInventoryItemCount(method.tool) > 0) controller.depositItem(method.tool, 1);
      if (method.bait > 0 && method.bait != m.bait) controller.depositItem(method.bait, controller.getInventoryItemCount(method.bait));
      method = m;
    }
    takeTools(method);
    boolean needTool = controller.getInventoryItemCount(method.tool) == 0;
    boolean needBait = method.bait > 0 && controller.getInventoryItemCount(method.bait) == 0;
    if (needTool || needBait) {
      if (!buyTools || !buyFromHarry(method, needTool, needBait)) {
        log("Missing " + (needTool ? controller.getItemName(method.tool) : "bait") + " for " + method.name
            + (buyTools ? " and couldn't buy it" : " (buying is off)"));
        method = fallbackMethod(method);
      }
      return 600; // next visit: carry on, the new method, or finish
    }
    controller.closeBank();
    controller.sleep(640);
    phase = Phase.FISH;
    return 300;
  }

  /** Bank open: the next lower-healing method we have the levels, tool and bait for, or null. */
  private Method fallbackMethod(Method from) {
    int fishLvl = controller.getBaseStat(SkillId.FISHING.getId());
    int cookLvl = controller.getBaseStat(SkillId.COOKING.getId());
    for (Method mm : METHODS) {
      if (mm.heal >= from.heal || fishLvl < mm.fishLvl || cookLvl < mm.cookLvl) continue;
      if (owned(mm.tool, true) == 0 || (mm.bait > 0 && owned(mm.bait, true) == 0)) continue;
      return mm;
    }
    return null;
  }

  private void finishTrip() {
    if (method != null) {
      if (controller.getInventoryItemCount(method.tool) > 0) controller.depositItem(method.tool, 1);
      if (method.bait > 0 && controller.getInventoryItemCount(method.bait) > 0)
        controller.depositItem(method.bait, controller.getInventoryItemCount(method.bait));
      controller.sleep(400);
    }
    withdrawFood(foodCarry);
    lastFoodBank = bankFood();
    log("Resupply trip " + trips + " done: " + tripCooked + " cooked (bank food now " + lastFoodBank + "). Walking back to Ardougne.");
    controller.closeBank();
    controller.sleep(640);
    method = null;
    phase = Phase.TO_ARDY;
  }

  private static int cookedOf(int raw) {
    switch (raw) {
      case 349: return 350;
      case 351: return 352;
      case 354: return 355;
      case 361: return 362;
      case 366: return 367;
      case 369: return 370;
      case 372: return 373;
      case 545: return 546;
      default: return -1;
    }
  }

  private int fish() {
    Method m = method;
    if (m == null) {
      phase = Phase.CATH_BANK;
      return 100;
    }
    if (controller.getInventoryItemCount() >= 30 || (rawCount(m) > 0 && tripCooked + rawCount(m) >= fishPerTrip)) {
      if (controller.isBatching()) controller.stopBatching();
      phase = Phase.COOK; // full, or enough raw fish for this trip's target
      return 100;
    }
    if (controller.getInventoryItemCount(m.tool) == 0 || (m.bait > 0 && controller.getInventoryItemCount(m.bait) == 0)) {
      int raw = rawCount(m);
      phase = raw > 0 ? Phase.COOK : Phase.CATH_BANK;
      return 100;
    }
    if (System.currentTimeMillis() < fishMissUntil) return 400;
    setPhaseStatus("Fishing " + m.name + " (" + tripCooked + "/" + fishPerTrip + " cooked)");
    int[] spot = null;
    for (int[] s : m.spots)
      if (controller.getObjectAtCoord(s[0], s[1]) == m.spotId) {
        spot = s;
        break;
      }
    if (spot == null) {
      int[] near = controller.getNearestObjectById(m.spotId);
      if (near != null && near[0] < 425 && near[1] > 495) spot = near;
    }
    if (spot == null) {
      int[] s0 = m.spots[0];
      stepTo(s0[0], s0[1] - 1, 2, 30_000);
      notStalled(5000);
      controller.sleep(1500);
      return 300;
    }
    if (controller.distanceTo(spot[0], spot[1]) > 2) stepTo(spot[0], spot[1] - 1, 1, 30_000);
    if (m.option == 1) controller.atObject(spot[0], spot[1]);
    else controller.atObject2(spot[0], spot[1]);
    controller.sleep(800);
    long until = System.currentTimeMillis() + 90_000;
    while (controller.isRunning() && controller.isBatching() && controller.getInventoryItemCount() < 30
        && tripCooked + rawCount(m) < fishPerTrip && System.currentTimeMillis() < until) controller.sleep(200);
    return 300;
  }

  private int rawCount(Method m) {
    int n = 0;
    for (int r : m.raw) n += controller.getInventoryItemCount(r);
    return n;
  }

  private int cook() {
    setPhaseStatus("Cooking on the Catherby range");
    if (controller.distanceTo(RANGE[0], RANGE[1]) > 5) {
      stepTo(436, 493, 1, 40_000);
      stepTo(COOK_DOOR[0], COOK_DOOR[1] + 1, 0, 20_000);
      openCookDoor();
      stepTo(433, 482, 1, 15_000);
    }
    int cooking = controller.getCurrentStat(SkillId.COOKING.getId());
    for (int raw : method == null ? new int[0] : method.raw) {
      if (controller.getInventoryItemCount(raw) == 0 || cooking < cookLevel(raw)) continue;
      int last = controller.getInventoryItemCount(raw);
      long progressAt = System.currentTimeMillis();
      while (controller.getInventoryItemCount(raw) > 0 && controller.isRunning()) {
        if (controller.getShouldSleep()) controller.sleepHandler(true);
        if (!controller.isBatching()) controller.useItemIdOnObject(RANGE[0], RANGE[1], raw);
        controller.sleep(300);
        int now = controller.getInventoryItemCount(raw);
        if (now < last) {
          last = now;
          progressAt = System.currentTimeMillis();
        } else if (System.currentTimeMillis() - progressAt > 20_000) {
          log("Couldn't cook " + controller.getItemName(raw) + " - banking them raw");
          break;
        }
      }
    }
    dropBurnt();
    stepTo(COOK_DOOR[0], COOK_DOOR[1] - 1, 0, 15_000);
    openCookDoor();
    stepTo(436, 493, 1, 20_000);
    phase = Phase.CATH_BANK;
    return 300;
  }

  private void dropBurnt() {
    for (int id : BURNT) {
      for (int tries = 0; tries < 30 && controller.isRunning() && controller.getInventoryItemCount(id) > 0; tries++) {
        int slot = controller.getInventoryItemSlotIndex(id);
        if (slot < 0) break;
        int before = controller.getInventoryItemCount(id);
        controller.dropItem(slot, before);
        controller.sleepUntil(() -> controller.getInventoryItemCount(id) < before, 3000);
      }
    }
  }

  private void openBankDoor() {
    for (int i = 0; i < 5 && controller.isRunning() && controller.getObjectAtCoord(BANK_DOOR[0], BANK_DOOR[1]) == 64; i++) {
      controller.atObject(BANK_DOOR[0], BANK_DOOR[1]);
      controller.sleep(900);
    }
  }

  private void openCookDoor() {
    for (int i = 0; i < 5 && controller.isRunning() && !controller.isDoorOpen(COOK_DOOR[0], COOK_DOOR[1]); i++) {
      controller.openDoor(COOK_DOOR[0], COOK_DOOR[1]);
      controller.sleep(900);
    }
  }

  /**
   * Bank open in Catherby: withdraws coins, buys the missing tool / bait from Harry's default stock
   * only (net, rod, harpoon, lobster pot, bait - never fish or anything else), banks the change.
   */
  private boolean buyFromHarry(Method m, boolean tool, boolean bait) {
    int want = Math.min(controller.getBankItemCount(COINS), (tool ? 60 : 0) + (bait ? 4 * Math.min(200, fishPerTrip + 20) : 0));
    if (want > 0) {
      final int before = controller.getInventoryItemCount(COINS);
      controller.withdrawItem(COINS, want);
      controller.sleepUntil(() -> controller.getInventoryItemCount(COINS) > before, 4000);
    }
    if (controller.getInventoryItemCount(COINS) < 5) return false;
    controller.closeBank();
    controller.sleep(640);
    setPhaseStatus("Buying at Harry's");
    boolean ok = false;
    if (stepTo(HARRY_TILE[0], HARRY_TILE[1], 3, 90_000) && openShop()) {
      ok = !tool || buy(m.tool, 1);
      if (ok && bait) ok = buy(m.bait, Math.min(fishPerTrip + 20, 200));
      controller.closeShop();
      controller.sleep(640);
    }
    stepTo(BANK_DOOR[0], BANK_DOOR[1] + 1, 1, 90_000);
    openBankDoor();
    if (openBank(CATH_BANK) && bankCoins && controller.getInventoryItemCount(COINS) > 0)
      controller.depositItem(COINS, controller.getInventoryItemCount(COINS));
    return ok;
  }

  private boolean buy(int id, int amount) {
    int stock = 0;
    for (int[] s : HARRY_STOCK) if (s[0] == id) stock = s[1];
    if (stock == 0) return false; // only Harry's own default stock
    amount = Math.min(amount, stock);
    long until = System.currentTimeMillis() + 30_000;
    int start = controller.getInventoryItemCount(id);
    while (controller.isRunning() && controller.getInventoryItemCount(id) - start < amount && System.currentTimeMillis() < until) {
      int inShop = controller.getShopItemCount(id);
      int price = controller.getShopItemPrice(id);
      int coins = controller.getInventoryItemCount(COINS);
      if (inShop < 1) {
        notStalled(5000);
        controller.sleep(2000);
        continue;
      }
      if (price <= 0 || coins < price) break;
      int n = Math.min(Math.min(amount - (controller.getInventoryItemCount(id) - start), inShop), Math.max(1, coins / price));
      final int before = controller.getInventoryItemCount(id);
      controller.shopBuy(id, n);
      controller.sleepUntil(() -> controller.getInventoryItemCount(id) > before, 4000);
    }
    int got = controller.getInventoryItemCount(id) - start;
    if (got > 0) log("Bought " + got + " " + controller.getItemName(id) + " at Harry's");
    return got > 0;
  }

  private boolean openShop() {
    for (int attempt = 0; attempt < 4 && controller.isRunning(); attempt++) {
      if (controller.isInShop()) return true;
      ORSCharacter harry = controller.getNearestNpcById(HARRY, false);
      if (harry == null) {
        controller.sleep(1500);
        continue;
      }
      if ("Trade".equalsIgnoreCase(controller.getNpcCommand1(HARRY))) controller.npcCommand1(harry.serverIndex);
      else {
        controller.talkToNpc(harry.serverIndex);
        if (controller.sleepUntil(() -> controller.isInOptionMenu(), 8000)) {
          int pick = 0;
          for (int i = 0; i < controller.getOptionMenuCount(); i++) {
            String o = controller.getOptionsMenuText(i);
            String l = o == null ? "" : o.toLowerCase();
            if (l.contains("trade") || l.contains("see what") || l.contains("buy")) {
              pick = i;
              break;
            }
          }
          controller.optionAnswer(pick);
        }
      }
      if (controller.sleepUntil(() -> controller.isInShop(), 10000)) return true;
    }
    return controller.isInShop();
  }

  // =========================================================================================
  // Navigation
  // =========================================================================================

  private static int[][] reverse(int[][] r) {
    int[][] o = new int[r.length][];
    for (int i = 0; i < r.length; i++) o[i] = r[r.length - 1 - i];
    return o;
  }

  private boolean nearCatherby() {
    return controller.currentX() < 455 && controller.currentY() < 520;
  }

  private static boolean inCastle(int x, int y) {
    return x >= CASTLE_IN[0] && x <= CASTLE_IN[2] && y >= CASTLE_IN[1] && y <= CASTLE_IN[3];
  }

  private static boolean inKnownRegion(int x, int y) {
    return x >= 395 && x <= 640 && y >= 470 && y <= 660;
  }

  /** Out of the courtyard through the gate (opening it when closed). */
  private void leaveCastle() {
    stepTo(GATE_IN[0], GATE_IN[1], 0, 20_000);
    openGate();
    stepTo(GATE_OUT[0], GATE_OUT[1], 0, 10_000);
  }

  private void openGate() {
    for (int i = 0; i < 5 && controller.isRunning() && controller.getObjectAtCoord(GATE[0], GATE[1]) == GATE_CLOSED; i++) {
      controller.atObject(GATE[0], GATE[1]);
      controller.sleepUntil(() -> controller.getObjectAtCoord(GATE[0], GATE[1]) != GATE_CLOSED, 3000);
    }
  }

  /** One navigation step towards an area (via its bank's route). */
  private void goToArea(Area a) {
    int x = controller.currentX(), y = controller.currentY();
    if (inCastle(x, y)) {
      if (a.castle) stepTo(a.stand[0], a.stand[1], 2, 20_000);
      else leaveCastle();
      return;
    }
    if (nearestIndex(a.route, 10) >= 0) {
      if (walkRoute(a.route, a.name) && a.castle) {
        openGate();
        stepTo(GATE_IN[0], GATE_IN[1], 0, 10_000);
        stepTo(a.stand[0], a.stand[1], 2, 15_000);
      }
      return;
    }
    goToHub(bankFor(a));
  }

  /** One navigation step towards an Ardougne bank from anywhere we know. */
  private void goToHub(int[] hub) {
    int x = controller.currentX(), y = controller.currentY();
    if (controller.distanceTo(hub[0], hub[1]) <= 3) return;
    if (inCastle(x, y)) {
      leaveCastle();
      return;
    }
    int[] other = hub == S_BANK ? N_BANK : S_BANK;
    String hubName = hub == S_BANK ? "south bank" : "north bank";
    if (controller.distanceTo(other[0], other[1]) <= 6) {
      walkRoute(hub == S_BANK ? R_N_TO_S : R_S_TO_N, hubName);
      return;
    }
    if (x < 535 && nearestIndex(R_CATH_TO_ARDY, 15) >= 0) {
      walkRoute(R_CATH_TO_ARDY, "Ardougne south bank");
      return;
    }
    // Leaving an area: walk its route back to its bank (closest route first).
    Area best = null;
    int bestD = Integer.MAX_VALUE;
    for (Target t : TARGETS)
      for (Area a : t.areas) {
        int i = nearestIndex(a.route, 10);
        if (i < 0) continue;
        int d = controller.distanceTo(a.route[i][0], a.route[i][1]);
        if (d < bestD) {
          bestD = d;
          best = a;
        }
      }
    if (best != null) {
      walkRoute(reverse(best.route), bankFor(best) == N_BANK ? "north bank" : "south bank");
      return;
    }
    if (nearestIndex(R_S_TO_N, 12) >= 0) {
      walkRoute(hub == S_BANK ? R_N_TO_S : R_S_TO_N, hubName);
      return;
    }
    status = "Web walking to the " + hubName;
    try {
      if (!controller.walkTowards(hub[0], hub[1])) randomStep(4);
    } catch (Exception e) {
      randomStep(4);
    }
  }

  /** Outside Ardougne/Catherby (died, or started elsewhere): web walk back, stop if no progress. */
  private int lost() {
    long now = System.currentTimeMillis();
    int d = controller.distanceTo(S_BANK[0], S_BANK[1]);
    if (lostSince == 0) {
      lostSince = now;
      lostBestDist = d;
      log("Away from Ardougne/Catherby at " + pos() + (now - diedAt < 180_000 ? " (died)" : "")
          + ": web walking back to the south bank, then regearing");
    }
    if (d < lostBestDist - 5) {
      lostBestDist = d;
      lostSince = now;
    } else if (now - lostSince > 5 * 60_000L) {
      log("No progress walking back for 5 minutes - stopping. Thief2Ardougne (dest ardougne) can walk this account back.");
      stopScript();
      return 1000;
    }
    phase = Phase.BANK; // regear (food, weapon into the bank) once back
    setPhaseStatus("Walking back to Ardougne (" + d + " tiles)");
    if (fighting()) return escapeCombat();
    try {
      if (!controller.walkTowards(S_BANK[0], S_BANK[1])) randomStep(4);
    } catch (Exception e) {
      randomStep(4);
    }
    return 600;
  }

  /** Walks one route from its nearest waypoint to its end. @return true at the end */
  private boolean walkRoute(int[][] route, String name) {
    int start = nearestIndex(route, 25);
    if (start < 0) {
      int[] first = route[nearestIndex(route, Integer.MAX_VALUE)];
      status = "Web walking towards the " + name + " route";
      try {
        if (!controller.walkTowards(first[0], first[1])) randomStep(5);
      } catch (Exception e) {
        randomStep(5);
      }
      return false;
    }
    if (start + 1 < route.length
        && dist(route[start], route[start + 1]) >= controller.distanceTo(route[start + 1][0], route[start + 1][1]))
      start++;
    for (int i = start; i < route.length && controller.isRunning(); i++) {
      int wx = route[i][0], wy = route[i][1];
      status = "Walking to " + name + " (" + (i + 1) + "/" + route.length + ")";
      long end = System.currentTimeMillis() + 25_000;
      long lastClick = 0;
      while (controller.isRunning() && controller.distanceTo(wx, wy) > 1) {
        if (System.currentTimeMillis() > end) {
          log("Waypoint " + wx + "," + wy + " not reached (at " + pos() + "), web walker step");
          try {
            controller.walkTowards(wx, wy);
          } catch (Exception e) {
            randomStep(3);
          }
          return false;
        }
        if (controller.currentY() >= FLOOR || controller.currentX() <= 0) return false;
        if (controller.isInCombat()) {
          int[] away = route[Math.min(i + 1, route.length - 1)];
          if (System.currentTimeMillis() - lastClick > 600) {
            controller.walkToAsync(away[0], away[1], 0);
            lastClick = System.currentTimeMillis();
          }
          end = Math.max(end, System.currentTimeMillis() + 5000);
          controller.sleep(300);
          continue;
        }
        if (lowHp() && invFood() > 0) {
          eat();
          continue;
        }
        if (System.currentTimeMillis() - lastClick > 2400 || !controller.isCurrentlyWalking()) {
          controller.walkToAsync(wx, wy, 0);
          lastClick = System.currentTimeMillis();
        }
        controller.sleep(300);
      }
    }
    return true;
  }

  /** Walks to within r of a tile (bounded); falls back to one web walker step. */
  private boolean stepTo(int x, int y, int r, long ms) {
    long until = System.currentTimeMillis() + ms;
    long lastClick = 0;
    while (controller.isRunning() && System.currentTimeMillis() < until) {
      if (controller.distanceTo(x, y) <= r) return true;
      if (controller.isInCombat()) return false;
      if (System.currentTimeMillis() - lastClick > 2400 || !controller.isCurrentlyWalking()) {
        controller.walkToAsync(x, y, 0);
        lastClick = System.currentTimeMillis();
      }
      controller.sleep(300);
    }
    if (controller.distanceTo(x, y) <= r) return true;
    try {
      controller.walkTowards(x, y);
    } catch (Exception ignored) {
      // give up this step
    }
    return controller.distanceTo(x, y) <= r;
  }

  private int nearestIndex(int[][] route, int maxDist) {
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

  private static int dist(int[] a, int[] b) {
    return Math.max(Math.abs(a[0] - b[0]), Math.abs(a[1] - b[1]));
  }

  private void randomStep(int range) {
    ThreadLocalRandom r = ThreadLocalRandom.current();
    for (int i = 0; i < 8; i++) {
      int x = controller.currentX() + r.nextInt(-range, range + 1);
      int y = controller.currentY() + r.nextInt(-range, range + 1);
      if (controller.isReachable(x, y, false)) {
        controller.walkToAsync(x, y, 0);
        controller.sleep(1200);
        return;
      }
    }
  }

  // =========================================================================================
  // Helpers
  // =========================================================================================

  private java.util.Set<Integer> distinctInv() {
    java.util.LinkedHashSet<Integer> s = new java.util.LinkedHashSet<>();
    int[] ids = controller.getInventoryItemIds();
    if (ids != null) for (int id : ids) if (id >= 0) s.add(id);
    return s;
  }

  /** Loot summary: item gains while thieving (coins counted separately; food excluded). */
  private void trackLoot() {
    Map<Integer, Integer> inv = new HashMap<>();
    for (int id : distinctInv()) inv.put(id, controller.getInventoryItemCount(id));
    if (lastInv != null && phase == Phase.THIEVE && !controller.isInBank()) {
      for (Map.Entry<Integer, Integer> e : inv.entrySet()) {
        int id = e.getKey();
        int before = id < lastInv.length ? lastInv[id] : 0;
        int d = e.getValue() - before;
        if (d <= 0 || heal(id) > 0) continue;
        if (id == COINS) coinsGained += d;
        else loot.merge(controller.getItemName(id), d, Integer::sum);
      }
    }
    int[] snap = new int[2048];
    for (Map.Entry<Integer, Integer> e : inv.entrySet()) if (e.getKey() < snap.length) snap[e.getKey()] = e.getValue();
    lastInv = snap;
  }

  private String summary() {
    StringBuilder sb = new StringBuilder();
    sb.append("Thieving xp +").append(controller.getStatXp(SkillId.THIEVING.getId()) - startThXp)
        .append(", levels +").append(controller.getBaseStat(SkillId.THIEVING.getId()) - startThLvl)
        .append(", picks ").append(picks).append(", fails ").append(fails).append(", coins +").append(coinsGained);
    for (Map.Entry<String, Integer> e : loot.entrySet()) sb.append(", ").append(e.getKey()).append(" x").append(e.getValue());
    return sb.toString();
  }

  private void notStalled(long ms) {
    try {
      bot.probe.Probe.noteIntentionalWait(ms);
    } catch (Throwable ignored) {
      // older clients have no stall watch
    }
  }

  /** Never stop within 15 s of Start: the script queue / mass start capture needs us running. */
  private void stopScript() {
    long wait = runStartedAt + 15_000 - System.currentTimeMillis();
    if (wait > 0) controller.sleep((int) wait);
    if (controller.isRunning()) controller.stop();
  }

  private static int parse(String s, int def) {
    try {
      return Integer.parseInt(s.trim());
    } catch (Exception e) {
      return def;
    }
  }

  private static int clamp(int v, int lo, int hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  private String pos() {
    return controller.currentX() + "," + controller.currentY();
  }

  private void setPhaseStatus(String s) {
    if (!s.equals(status)) {
      String a = s.replaceAll("[0-9]+", "#"), b = status == null ? "" : status.replaceAll("[0-9]+", "#");
      if (!a.equals(b)) log("Step: " + s + " (at " + pos() + ")");
    }
    status = s;
    controller.setStatus("@yel@" + s);
  }

  private void log(String msg) {
    Main.logScript(P + msg);
  }

  // =========================================================================================
  // Messages
  // =========================================================================================

  @Override
  public void serverMessageInterrupt(String message) {
    onMessage(message);
  }

  @Override
  public void questMessageInterrupt(String message) {
    onMessage(message);
  }

  private void onMessage(String message) {
    if (message == null || message.startsWith("[") || message.contains(P.trim())) return;
    String m = message.toLowerCase();
    long now = System.currentTimeMillis();
    if (m.contains("you pick the")) picks++;
    else if (m.contains("you fail to pick")) {
      fails++;
      failAt = now;
      fightSeenAt = now; // the NPC attacks straight after (no stun on OpenRSC)
    } else if (m.contains("can't get close enough") || m.contains("is busy") || m.contains("has moved")) {
      busyAt = now;
      int idx = lastNpcIdx;
      if (idx >= 0) skipNpc.put(idx, now + 10_000);
    } else if (m.contains("you need to be a level")) {
      target = null; // Thieving drained: re-pick next tick
    } else if (m.contains("oh dear") && m.contains("dead")) {
      deaths++;
      diedAt = now;
      phase = Phase.BANK;
      target = null;
    } else if (m.contains("you catch")) fished++;
    else if (m.contains("nicely cooked") || m.contains("you cook")) cookedCount++;
    else if (m.contains("accidentally burn") || m.contains("you burn")) burntCount++;
    else if (m.contains("nothing interesting happens")) fishMissUntil = now + 2000 + ThreadLocalRandom.current().nextInt(3000);
    if (m.contains("under attack") || m.contains("whilst you are fighting") || m.contains("can't retreat")) fightSeenAt = now;
  }

  // =========================================================================================
  // Paint
  // =========================================================================================

  @Override
  public void paintInterrupt() {
    if (controller == null || startTime == 0) return;
    long run = Math.max(1, System.currentTimeMillis() - startTime);
    int xp = controller.getStatXp(SkillId.THIEVING.getId()) - startThXp;
    int lvl = controller.getBaseStat(SkillId.THIEVING.getId());
    int fx = controller.getStatXp(SkillId.FISHING.getId()) - startFishXp;
    int cx = controller.getStatXp(SkillId.COOKING.getId()) - startCookXp;
    int lootN = 0;
    for (int v : loot.values()) lootN += v;
    String[] lines = {
      "@gre@Ben's Ardy Thiever",
      "@whi@Phase: @yel@" + phase + " @whi@- " + status,
      "@whi@Target: @yel@" + (target == null ? "-" : target.name + " (lvl " + target.level + ")") + (area == null ? "" : " @whi@at @yel@" + area.name),
      "@whi@Thieving: @yel@" + lvl + " (+" + (lvl - startThLvl) + ") @whi@xp @yel@" + xp + " (" + (xp * 3_600_000L / run) + "/h)",
      "@whi@Picks: @gre@" + picks + " @whi@caught: @red@" + fails + " @whi@busy: @yel@" + busyCount + " @whi@tries: @yel@" + attempts,
      "@whi@Food: @yel@" + invFood() + " carried" + (lastFoodBank >= 0 ? ", " + lastFoodBank + " banked" : "") + " @whi@eaten @yel@" + eaten,
      "@whi@HP: @yel@" + hp() + "/" + maxHp() + " @whi@(eat < " + eatPercent + "%)",
      "@whi@Coins: @yel@+" + coinsGained + " @whi@other loot: @yel@" + lootN,
      "@whi@Resupply: @yel@" + trips + " trips, " + tripCooked + "/" + fishPerTrip + (method == null ? "" : " " + method.name)
          + " @whi@fish/cook xp @yel@" + fx + "/" + cx,
      "@whi@Weapons banked: @yel@" + weaponsBanked + " @whi@deaths @yel@" + deaths + " @whi@Runtime: @yel@" + controller.msToString(run)
    };
    int y = 21;
    controller.drawBoxAlpha(7, 7, 310, 14 * lines.length + 8, 0x000000, 140);
    for (String s : lines) {
      controller.drawString(s, 10, y, 0xFFFFFF, 1);
      y += 14;
    }
  }

  // =========================================================================================
  // Setup window (dark, like Thief2Ardougne)
  // =========================================================================================

  private void setupGUI() {
    Color bg = Main.primaryBG != null ? Main.primaryBG : new Color(40, 40, 40);
    Color fg = Main.primaryFG != null ? Main.primaryFG : new Color(230, 230, 230);
    Color field = bg.brighter();
    JFrame f = new JFrame("Ben's Ardy Thiever - Setup");
    frame = f;
    f.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    JPanel content = new JPanel(new BorderLayout(0, 10));
    content.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
    JLabel title = new JLabel("<html><b>Ben's Ardy Thiever</b><br>Pickpockets the best NPC in East Ardougne, eats and banks;"
        + "<br>any wielded weapon goes into the bank (armour stays on);"
        + "<br>food runs low: fishes + cooks in Catherby, then carries on.</html>");
    title.setFont(title.getFont().deriveFont(title.getFont().getSize2D() + 1f));
    content.add(title, BorderLayout.NORTH);

    String[] targetItems = new String[TARGETS.length + 1];
    targetItems[0] = "Auto (best allowed)";
    for (int i = 0; i < TARGETS.length; i++) targetItems[i + 1] = TARGETS[i].name + " (Thieving " + TARGETS[i].level + ")";
    JComboBox<String> targetBox = new JComboBox<>(targetItems);
    Target ft = findTarget(targetMode);
    for (int i = 0; i < TARGETS.length; i++) if (TARGETS[i] == ft) targetBox.setSelectedIndex(i + 1);
    String[] foodItems = new String[FOODS.length + 1];
    foodItems[0] = "Best carried";
    for (int i = 0; i < FOODS.length; i++) foodItems[i + 1] = FOOD_NAMES[i] + " (heals " + FOODS[i][1] + ")";
    JComboBox<String> foodBox = new JComboBox<>(foodItems);
    int fid = foodIdByName(foodChoice);
    for (int i = 0; i < FOODS.length; i++) if (FOODS[i][0] == fid) foodBox.setSelectedIndex(i + 1);

    JSpinner capSpin = spinner(levelCap, 1, 99, field, fg);
    JSpinner eatSpin = spinner(eatPercent, 10, 95, field, fg);
    JSpinner carrySpin = spinner(foodCarry, 1, 28, field, fg);
    JSpinner resupplySpin = spinner(resupplyBelow, 0, 5000, field, fg);
    JSpinner fishSpin = spinner(fishPerTrip, 1, 10000, field, fg);
    JSpinner stopSpin = spinner(stopAtLevel, 0, 99, field, fg);
    JSpinner stallSpin = spinner(stallMinutes, 1, 60, field, fg);
    JCheckBox fishingBox = new JCheckBox("Food resupply: fish + cook in Catherby when food runs low", fishingEnabled);
    JCheckBox buyBox = new JCheckBox("Buy missing tools/bait from Harry's default stock (never player stock)", buyTools);
    JCheckBox castleBox = new JCheckBox("Use the castle courtyard (opens the gate)", useCastle);
    JCheckBox logoutBox = new JCheckBox("Log out safely when out of food and no resupply is possible", logoutWhenStuck);
    JRadioButton bankRb = new JRadioButton("Bank coins");
    JRadioButton keepRb = new JRadioButton("Keep coins in the inventory");
    ButtonGroup cg = new ButtonGroup();
    cg.add(bankRb);
    cg.add(keepRb);
    if (bankCoins) bankRb.setSelected(true);
    else keepRb.setSelected(true);

    JPanel opts = new JPanel(new GridBagLayout());
    GridBagConstraints c = new GridBagConstraints();
    c.gridx = 0;
    c.gridy = 0;
    c.anchor = GridBagConstraints.WEST;
    c.insets = new Insets(2, 0, 2, 0);
    JComponent[] rows = {
      row("Target:", targetBox, ""),
      row("Max NPC level:", capSpin, "(99 = no cap)"),
      row("Food:", foodBox, ""),
      row("Eat below:", eatSpin, "% HP"),
      row("Food to carry:", carrySpin, ""),
      row("Resupply when bank + inventory food below:", resupplySpin, ""),
      row("Fish to catch + cook per trip:", fishSpin, ""),
      fishingBox,
      buyBox,
      castleBox,
      logoutBox,
      row("Coins:", bankRb, ""),
      row("", keepRb, ""),
      row("Stop at Thieving level:", stopSpin, "(0 = never)"),
      row("Stall: no xp for", stallSpin, "minutes")
    };
    for (JComponent r : rows) {
      opts.add(r, c);
      c.gridy++;
    }
    content.add(opts, BorderLayout.CENTER);

    JButton start = darkButton("Start", field, fg);
    JButton cancel = darkButton("Cancel", field, fg);
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    buttons.add(cancel);
    buttons.add(start);
    content.add(buttons, BorderLayout.SOUTH);

    final boolean[] confirmed = {false};
    f.addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosed(WindowEvent e) {
            if (!confirmed[0] && !scriptStarted) {
              controller.displayMessage("@red@Ben's Ardy Thiever cancelled.");
              log("Setup window closed without Start: stopping");
              controller.stop();
            }
          }
        });
    start.addActionListener(
        e -> {
          for (JSpinner s : new JSpinner[] {capSpin, eatSpin, carrySpin, resupplySpin, fishSpin, stopSpin, stallSpin}) {
            try {
              s.commitEdit();
            } catch (java.text.ParseException ignored) {
              // keep the last valid value
            }
          }
          int ti = targetBox.getSelectedIndex();
          targetMode = ti <= 0 ? "auto" : TARGETS[ti - 1].key;
          int fi = foodBox.getSelectedIndex();
          foodChoice = fi <= 0 ? "best" : FOOD_NAMES[fi - 1].toLowerCase();
          levelCap = ((Number) capSpin.getValue()).intValue();
          eatPercent = ((Number) eatSpin.getValue()).intValue();
          foodCarry = ((Number) carrySpin.getValue()).intValue();
          resupplyBelow = ((Number) resupplySpin.getValue()).intValue();
          fishPerTrip = ((Number) fishSpin.getValue()).intValue();
          stopAtLevel = ((Number) stopSpin.getValue()).intValue();
          stallMinutes = ((Number) stallSpin.getValue()).intValue();
          fishingEnabled = fishingBox.isSelected();
          buyTools = buyBox.isSelected();
          useCastle = castleBox.isSelected();
          logoutWhenStuck = logoutBox.isSelected();
          bankCoins = bankRb.isSelected();
          confirmed[0] = true;
          scriptStarted = true;
          f.dispose();
        });
    cancel.addActionListener(e -> f.dispose());
    f.setContentPane(content);
    applyTheme(content, bg, fg);
    f.getRootPane().setDefaultButton(start);
    f.pack();
    f.setResizable(false);
    f.setLocationRelativeTo(null);
    f.setVisible(true);
    f.toFront();
  }

  private static JPanel row(String label, JComponent comp, String hint) {
    JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    if (!label.isEmpty()) p.add(new JLabel(label));
    p.add(comp);
    if (!hint.isEmpty()) {
      JLabel h = new JLabel(hint);
      h.setFont(h.getFont().deriveFont(Font.ITALIC));
      p.add(h);
    }
    return p;
  }

  private static JSpinner spinner(int value, int min, int max, Color bg, Color fg) {
    JSpinner s = new JSpinner(new SpinnerNumberModel(clamp(value, min, max), min, max, 1));
    JSpinner.NumberEditor ed = new JSpinner.NumberEditor(s, "#");
    JFormattedTextField tf = ed.getTextField();
    tf.setColumns(5);
    tf.setBackground(bg);
    tf.setForeground(fg);
    tf.setCaretColor(fg);
    s.setEditor(ed);
    s.setBorder(BorderFactory.createLineBorder(bg.brighter()));
    return s;
  }

  private static JButton darkButton(String text, Color bg, Color fg) {
    JButton b = new JButton(text);
    b.setBackground(bg);
    b.setForeground(fg);
    b.setFocusPainted(false);
    b.setBorder(BorderFactory.createCompoundBorder(
        BorderFactory.createLineBorder(bg.brighter()), BorderFactory.createEmptyBorder(4, 14, 4, 14)));
    return b;
  }

  private static void applyTheme(Component comp, Color bg, Color fg) {
    if (comp instanceof JPanel || comp instanceof JLabel || comp instanceof JRadioButton || comp instanceof JCheckBox) {
      comp.setBackground(bg);
      comp.setForeground(fg);
      if (comp instanceof JComponent && !(comp instanceof JPanel)) ((JComponent) comp).setOpaque(false);
    }
    if (comp instanceof Container && !(comp instanceof JSpinner) && !(comp instanceof JButton) && !(comp instanceof JComboBox)) {
      for (Component child : ((Container) comp).getComponents()) applyTheme(child, bg, fg);
    }
  }
}
