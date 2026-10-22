import bot.Main;
import controller.Controller;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
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
import models.entities.QuestId;
import models.entities.SkillId;
import orsc.ORSCharacter;
import scripting.idlescript.IdleScript;
import scripting.idlescript.SeattaScript.AIOQuester.DraynorManor;
import scripting.idlescript.SeattaScript.AIOQuester.LeverBasement;
import scripting.idlescript.SeattaScript.AIOQuester.QuestHandler;
import scripting.idlescript.SeattaScript.AIOQuester.QuestQueue;
import scripting.idlescript.SeattaScript.AIOQuester.QuestWalker;
import scripting.idlescript.SeattaScript.AIOQuester.models.Quest;

/**
 * Thief 2 Ardougne - takes a fresh account from Lumbridge to Ardougne (and optionally on to the
 * Seers' Village or Catherby bank) the cheap way.
 *
 * <ol>
 *   <li>Picks cabbages (default 20) in the field just north of Lumbridge castle (ground item 18,
 *       spawns at x 137-153, y 600-612 in OpenRSC GroundItems.json).
 *   <li>Pickpockets the men by the Lumbridge castle spawn until it has the coin target (default
 *       100), eating cabbages when HP drops below the threshold. Runs out of cabbages: goes back to
 *       the field, picks more, carries on. Tops the cabbages up again before leaving.
 *   <li>Port Sarim -> Karamja ship (30 gp), walks to Brimhaven, Customs Official ship to Ardougne
 *       (30 gp). Boat logic (busy NPC handling, menu matching, rum) is copied from GetMeToArdougne.
 *   <li>Walks to the chosen destination bank and stops.
 * </ol>
 *
 * <p>All walking that matters uses fixed waypoint lists. They were produced by a shortest-path
 * search over AIOQuester's baked collision map with a heavy cost on every tile within 7 tiles of an
 * aggressive NPC's roaming box (OpenRSC NpcLocs.json + NpcDefs.json "aggressive"), so they keep
 * clear of the Draynor highwaymen and dark wizards, and the Ardougne -> Seers/Catherby legs touch
 * no aggressive NPC area at all. Karamja (scorpions, muggers) cannot be avoided completely; that leg
 * takes the least exposed line and walks away if attacked.
 *
 * <p>Parameters (all optional; no parameters shows the setup window): {@code dest:ardougne|seers|
 * catherby}, {@code cabbages:N}, {@code coins:N}, {@code eat:P} (eat below P% HP).
 */
public class Thief2Ardougne extends IdleScript implements MassStartSettings {
  public static final ScriptInfo info =
      new ScriptInfo(
          new Category[] {Category.MISCELLANEOUS, Category.THIEVING},
          "Ben",
          "Fresh account: picks cabbages, thieves men in Lumbridge for coins, then takes the Port"
              + " Sarim -> Karamja -> Ardougne ships and walks to Ardougne, Seers' or Catherby bank"
              + " along routes that avoid aggressive NPCs.",
          new Parameter[] {
            new Parameter("dest:<ardougne|seers|catherby>", "Where to finish (default ardougne)."),
            new Parameter(
                "money:<thieve|ernest>",
                "How to get the coins: pickpocket men (default) or do Ernest the Chicken (300 gp),"
                    + " thieving only to top up."),
            new Parameter("cabbages:<n>", "Cabbages to pick and keep as food (default 20)."),
            new Parameter("coins:<n>", "Coins to thieve before leaving (default 100, min 60)."),
            new Parameter("eat:<percent>", "Eat a cabbage below this % HP (default 50).")
          });

  private static final String P = "[Thief2Ardougne] ";
  private static final String PM = "[Thief2Ardougne] [Manor] ";

  // ---- Settings (plain fields so the tab client's mass start can copy them) ----
  static final String DEST_ARDOUGNE = "ardougne";
  static final String DEST_SEERS = "seers";
  static final String DEST_CATHERBY = "catherby";
  private String destination = DEST_ARDOUGNE;
  static final String MONEY_THIEVE = "thieve";
  static final String MONEY_ERNEST = "ernest";
  private String moneyMethod = MONEY_THIEVE;
  private int cabbageTarget = 20;
  private int coinTarget = 100;
  private int eatPercent = 50;
  private static final int MAX_CABBAGES = 25, MIN_COINS = 60, MAX_COINS = 5000;
  private volatile boolean guiSetup = false;
  private volatile boolean scriptStarted = false;
  private JFrame scriptFrame = null;
  private boolean prefilled = false;

  // ---- Ids (OpenRSC defs) ----
  private static final int COINS = 10;
  private static final int CABBAGE = 18; // ground item spawns in the Lumbridge field
  private static final int KARAMJA_RUM = 318;
  private static final int MAN = 11;
  private static final int[] SAILORS = {166, 170, 171}; // Captain Tobias, Seaman Lorris/Thresnor
  private static final int[] CUSTOMS_OFFICIAL = {317};
  private static final int BRIMHAVEN_GATE_ID = 254;
  private static final int BOAT_FEE = 30;
  private static final int FLOOR = 944;
  private static final int[] GENERIC_DOWN = {6, 42, 44};
  private static final int[] GENERIC_UP = {5, 41, 43, 130, 198, 227, 249, 270, 295, 342, 369, 629};

  // ---- Places (RSC world coords; x grows westwards) ----
  private static final int[] THIEVE_PT = {128, 641}; // men by the castle (spawns 118-132,639-658)
  private static final int[] FIELD = {145, 606}; // cabbage field north of the castle
  private static final int[] FIELD_BOX = {137, 600, 153, 612};
  private static final int[] PORT_SARIM_DOCK = {268, 646};
  private static final int[] BRIMHAVEN_GATE = {434, 682}; // gate 254, other side x >= 435
  private static final int[] BRIMHAVEN_DOCK = {467, 653}; // Customs Official 467,649/651
  private static final int[] ARDOUGNE_BANK = {551, 612}; // south bank (bankers 552-554,610-615)
  private static final int[] SEERS_BANK = {501, 452}; // bankers 499-503,447-451
  private static final int[] CATHERBY_BANK = {441, 495}; // bankers 438-441,491-494

  private static final int[][] KARAMJA = {
    {331, 722, 480, 910}, {435, 640, 532, 731}, {318, 682, 367, 726}, {364, 656, 437, 719}
  };

  // ---- Routes (collision-map shortest paths with aggressive-NPC areas costed out) ----
  private static final int[][] LUMB_TO_FIELD = {
    {120, 648}, {128, 641}, {134, 631}, {134, 621}, {139, 612}, {145, 606}
  };
  private static final int[][] FIELD_TO_LUMB = {
    {145, 606}, {136, 616}, {135, 626}, {133, 636}, {128, 641}
  };
  /** North of the highwaymen (188-208,594-614) and away from the Draynor dark wizards. */
  private static final int[][] TO_SARIM = {
    {128, 641}, {134, 631}, {134, 621}, {141, 617}, {151, 615}, {155, 606}, {159, 599}, {168, 595},
    {178, 593}, {184, 586}, {194, 586}, {204, 586}, {214, 586}, {224, 588}, {234, 598}, {244, 608},
    {253, 617}, {258, 627}, {266, 634}, {268, 644}, {268, 646}
  };
  /** Lumbridge -> Draynor bank (AIOQuester's start point for Ernest), along the south road. */
  private static final int[][] LUMB_TO_DRAYNOR_BANK = {
    {128, 641}, {130, 639}, {140, 634}, {150, 634}, {160, 634}, {170, 634}, {180, 634}, {190, 634},
    {200, 634}, {210, 633}, {219, 634}
  };
  private static final int[][] DRAYNOR_BANK_TO_LUMB = reverse(LUMB_TO_DRAYNOR_BANK);
  /** Draynor bank (where AIOQuester banks Ernest's reward) -> Port Sarim, heading north-west away
   * from the dark wizards (226-233,638-645), west of the highwaymen. */
  private static final int[][] DRAYNOR_BANK_TO_SARIM = {
    {219, 634}, {228, 628}, {237, 618}, {247, 610}, {255, 620}, {261, 630}, {268, 637}, {268, 646}
  };
  private static final int[] DRAYNOR_BANK = {219, 634};
  private static final int[] SARIM_FIELD = {255, 602}; // cabbages 248-262,597-606 (fenced, gaps N/S)
  private static final int[] SARIM_FIELD_BOX = {248, 597, 262, 606};
  private static final int[][] DRAYNOR_BANK_TO_SARIM_FIELD = {
    {219, 634}, {228, 628}, {237, 618}, {247, 610}, {255, 608}, {255, 602}
  };
  private static final int[][] SARIM_FIELD_TO_DOCK = {
    {255, 602}, {255, 608}, {258, 612}, {265, 622}, {268, 632}, {268, 642}, {268, 646}
  };

  private static int[][] reverse(int[][] r) {
    int[][] o = new int[r.length][];
    for (int i = 0; i < r.length; i++) o[i] = r[r.length - 1 - i];
    return o;
  }

  /** Along the south coast, entering the scorpion area (390-434,665-710) as late as possible. */
  private static final int[][] KARAMJA_TO_GATE = {
    {324, 713}, {334, 713}, {344, 716}, {354, 716}, {364, 717}, {374, 717}, {384, 718}, {394, 713},
    {404, 711}, {414, 701}, {424, 691}, {433, 682}
  };
  private static final int[][] GATE_TO_DOCK = {
    {435, 682}, {441, 672}, {451, 662}, {461, 657}, {467, 653}
  };
  private static final int[][] LANDING_TO_ARDOUGNE_BANK = {{538, 614}, {547, 615}, {551, 612}};
  /** Up the east side of Ardougne, clear of the bears (515-529,563-580) and guard dogs. */
  private static final int[][] ARDOUGNE_TO_SEERS = {
    {551, 612}, {550, 602}, {550, 592}, {550, 582}, {558, 572}, {563, 563}, {563, 553}, {563, 543},
    {570, 533}, {563, 526}, {553, 516}, {543, 506}, {533, 496}, {523, 487}, {513, 479}, {512, 469},
    {505, 459}, {501, 454}, {501, 452}
  };
  /** Along y 523/500, south of Seers, north of the renegade knights and giant bats. */
  private static final int[][] ARDOUGNE_TO_CATHERBY = {
    {551, 612}, {550, 602}, {550, 592}, {550, 582}, {558, 572}, {563, 563}, {563, 553}, {563, 543},
    {570, 533}, {563, 529}, {553, 523}, {543, 523}, {533, 523}, {523, 523}, {513, 523}, {503, 517},
    {493, 507}, {483, 505}, {473, 504}, {463, 500}, {453, 500}, {443, 500}, {441, 497}, {441, 495}
  };

  // The ship lands at 538,617 (BoatFromBrimhaven.java); every destination starts from there.
  private static final int[][] LANDING_TO_SEERS = concat(LANDING_TO_ARDOUGNE_BANK, ARDOUGNE_TO_SEERS);
  private static final int[][] LANDING_TO_CATHERBY =
      concat(LANDING_TO_ARDOUGNE_BANK, ARDOUGNE_TO_CATHERBY);

  private static int[][] concat(int[][] a, int[][] b) {
    int skip = a[a.length - 1][0] == b[0][0] && a[a.length - 1][1] == b[0][1] ? 1 : 0;
    int[][] o = new int[a.length + b.length - skip][];
    System.arraycopy(a, 0, o, 0, a.length);
    System.arraycopy(b, skip, o, a.length, b.length - skip);
    return o;
  }

  private static final long TALK_REPLY_MS = 8_000L;
  private static final long SAIL_MS = 15_000L;
  private static final long WAYPOINT_MS = 25_000L;

  // ---- State ----
  private boolean initialised = false;
  private boolean prepDone = false; // coins + cabbages ready, travelling
  private boolean restocking = false; // walking to / picking in the cabbage field
  private boolean firstPickDone = false;
  private volatile boolean npcBusy = false;
  private String status = "Starting";
  private String phase = "-";
  private long startTime = 0;
  private int picked = 0, eaten = 0, thieveTries = 0, boatWaits = 0;
  private boolean ernestChecked = false, ernestDone = false, ernestFailed = false;
  private boolean toppingUpLogged = false;
  private String ernestFailReason = "";
  private QuestHandler aioQh = null; // AIOQuester's handler while Ernest runs
  private QuestWalker aioWalker = null;
  private volatile String ernestStatus = "not started";

  // =========================================================================================
  // Lifecycle
  // =========================================================================================

  @Override
  public int start(String[] parameters) {
    if (!initialised) init(parameters);
    if (!controller.isRunning()) return 1000;
    if (!scriptStarted) {
      if (!guiSetup) {
        guiSetup = true;
        status = "Waiting for setup window";
        SwingUtilities.invokeLater(this::setupGUI);
      }
      return 500;
    }
    if (!controller.isLoggedIn()) return 1000;
    if (controller.getNeedToMove()) controller.moveCharacter();
    if (controller.getShouldSleep()) controller.sleepHandler(true);
    try {
      return tick();
    } catch (Exception e) {
      log("step failed: " + e);
      return 2000;
    }
  }

  private void init(String[] parameters) {
    initialised = true;
    startTime = System.currentTimeMillis();
    if (scriptStarted) { // settings copied in by mass start
      announce();
      return;
    }
    boolean any = false;
    if (parameters != null) {
      for (String raw : parameters) {
        if (raw == null || raw.trim().isEmpty()) continue;
        any = true;
        String s = raw.trim().toLowerCase();
        String key = s, val = "";
        int sep = Math.max(s.indexOf(':'), s.indexOf('='));
        if (sep > 0) {
          key = s.substring(0, sep);
          val = s.substring(sep + 1);
        }
        if (s.startsWith("ard") || s.startsWith("stay")) destination = DEST_ARDOUGNE;
        else if (s.startsWith("seer")) destination = DEST_SEERS;
        else if (s.startsWith("cath")) destination = DEST_CATHERBY;
        else if (key.startsWith("dest")) {
          if (val.startsWith("seer")) destination = DEST_SEERS;
          else if (val.startsWith("cath")) destination = DEST_CATHERBY;
          else if (val.startsWith("ard") || val.startsWith("stay")) destination = DEST_ARDOUGNE;
          else bad(raw);
        } else if (key.startsWith("cab")) cabbageTarget = clamp(parse(val, cabbageTarget), 0, MAX_CABBAGES);
        else if (key.startsWith("coin")) coinTarget = clamp(parse(val, coinTarget), MIN_COINS, MAX_COINS);
        else if (key.startsWith("money") || key.startsWith("method")) {
          if (val.startsWith("ern") || val.startsWith("quest")) moneyMethod = MONEY_ERNEST;
          else if (val.startsWith("thi") || val.startsWith("pick")) moneyMethod = MONEY_THIEVE;
          else bad(raw);
        } else if (s.equals("ernest")) moneyMethod = MONEY_ERNEST;
        else if (key.startsWith("eat") || key.startsWith("hp"))
          eatPercent = clamp(parse(val, eatPercent), 1, 99);
        else bad(raw);
      }
    }
    if (!any) return; // the setup window chooses
    if (MassStart.isLeaderSetup()) {
      prefilled = true;
      return;
    }
    scriptStarted = true;
    announce();
  }

  private void bad(String p) {
    controller.displayMessage("@red@Thief 2 Ardougne: @whi@unknown parameter '" + p.trim() + "'");
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

  @Override
  public String[] massStartArguments() {
    return new String[] {
      "dest:" + destination,
      "money:" + moneyMethod,
      "cabbages:" + cabbageTarget,
      "coins:" + coinTarget,
      "eat:" + eatPercent
    };
  }

  private void announce() {
    String msg =
        "destination="
            + destName()
            + ", money="
            + (ernestMode() ? "Ernest the Chicken (300 gp), thieving tops up" : "thieving men")
            + ", cabbages="
            + cabbageTarget
            + ", coins="
            + coinTarget
            + ", eat below "
            + eatPercent
            + "% HP";
    controller.displayMessage("@gre@Thief 2 Ardougne started: @whi@" + msg);
    log("Settings: " + msg);
  }

  private boolean ernestMode() {
    return MONEY_ERNEST.equals(moneyMethod);
  }

  private String destName() {
    switch (destination) {
      case DEST_SEERS:
        return "Seers' Village bank";
      case DEST_CATHERBY:
        return "Catherby bank";
      default:
        return "Ardougne (south bank)";
    }
  }

  // =========================================================================================
  // Main step
  // =========================================================================================

  private boolean weaponChecked = false;

  private int tick() {
    if (!weaponChecked) { // thieving money method: no weapon in hand on the men (back on at the end)
      weaponChecked = true;
      weapon.load();
      if (!ernestMode() && controller.getInventoryItemCount(COINS) < coinTarget) weapon.takeOff(this::dropOneCabbage);
      else if (weapon.remembered >= 0) weapon.putBack(false); // thieving already done (restart after it)
    }
    int x = controller.currentX(), y = controller.currentY();
    if (x <= 0 || y <= 0) {
      setPhase("Loading / sailing");
      return 1500;
    }
    if (inManor()) return manorTick();
    if (y >= FLOOR) {
      setPhase(y >= FLOOR * 3 ? "Underground" : "Upstairs");
      changeFloor(y >= FLOOR * 3 ? GENERIC_UP : GENERIC_DOWN, y >= FLOOR * 3 ? "up" : "down");
      return 600;
    }
    if (inKaramja(x, y)) return karamja();
    if (x >= 430) return kandarin();
    return prepDone ? toSarim() : prepare();
  }

  // ---- Lumbridge: cabbages + coins ----

  private int prepare() {
    int coins = controller.getInventoryItemCount(COINS);
    int cab = controller.getInventoryItemCount(CABBAGE);
    if (controller.isInCombat()) return escapeCombat(THIEVE_PT);
    if (eatIfNeeded()) return 1300;

    if (ernestMode() && !ernestChecked) {
      ernestChecked = true;
      int st = questStage();
      if (st == -1) {
        ernestDone = true;
        log("Ernest the Chicken is already complete on this account: getting coins by thieving.");
      } else if (st > 0) {
        firstPickDone = true; // resume the quest; cabbages get topped up before the trip
        log("Ernest the Chicken already started (stage " + st + "): resuming it first.");
      }
    }
    if (!firstPickDone && !restocking && cab < cabbageTarget) {
      restocking = true;
      log("Picking " + (cabbageTarget - cab) + " cabbages first (have " + cab + ").");
    }
    if (!restocking && ernestMode() && !ernestDone && !ernestFailed) return runErnest();
    if (ernestMode() && !toppingUpLogged && coins < coinTarget && !restocking) {
      toppingUpLogged = true;
      log((ernestDone ? "Ernest the Chicken done" : "Ernest the Chicken abandoned (" + ernestFailReason + ")")
          + ": " + coins + "/" + coinTarget + " coins, topping up by thieving men in Lumbridge.");
    }
    if (coins >= coinTarget) {
      if (weapon.remembered >= 0) weapon.putBack(false); // thieving done: the weapon goes straight back on
      if (cab < cabbageTarget && !restocking) {
        restocking = true;
        log("Have " + coins + " coins. Topping cabbages up to " + cabbageTarget
            + " for the journey (have " + cab + ").");
      }
      if (!restocking) {
        prepDone = true;
        log("Ready: " + coins + " coins and " + cab + " cabbages. Heading to Port Sarim.");
        return 100;
      }
    } else if (!restocking && cab == 0 && cabbageTarget > 0) {
      restocking = true;
      log("Out of cabbages at " + coins + "/" + coinTarget + " coins: back to the field for "
          + cabbageTarget + ".");
    }
    if (restocking) return pickCabbages();
    return thieve();
  }

  private int pickCabbages() {
    int cab = controller.getInventoryItemCount(CABBAGE);
    if (cab >= cabbageTarget || controller.getInventoryItemCount() >= 30) {
      restocking = false;
      firstPickDone = true;
      log("Cabbages: " + cab + " (target " + cabbageTarget + "), done picking.");
      return 100;
    }
    setPhase("Picking cabbages " + cab + "/" + cabbageTarget);
    boolean sarim =
        controller.distanceTo(SARIM_FIELD[0], SARIM_FIELD[1])
            < controller.distanceTo(FIELD[0], FIELD[1]);
    int[] box = sarim ? SARIM_FIELD_BOX : FIELD_BOX;
    if (!inBox(box, controller.currentX(), controller.currentY(), 4)) {
      if (sarim) {
        if (inManorGrounds()) leaveManorGrounds();
        walkRoute(DRAYNOR_BANK_TO_SARIM_FIELD, "Port Sarim cabbage field");
      } else walkRoute(LUMB_TO_FIELD, "cabbage field");
      return 300;
    }
    int[] item = controller.getNearestItemById(CABBAGE, 16);
    if (item == null) {
      status = "Waiting for cabbages to respawn";
      ThreadLocalRandom r = ThreadLocalRandom.current();
      int tx = box[0] + r.nextInt(box[2] - box[0] + 1);
      int ty = box[1] + r.nextInt(box[3] - box[1] + 1);
      if (controller.isReachable(tx, ty, false)) controller.walkToAsync(tx, ty, 0);
      return 2500;
    }
    status = "Picking cabbage at " + item[0] + "," + item[1];
    controller.pickupItem(item[0], item[1], CABBAGE, true, false);
    final int before = cab;
    if (controller.sleepUntil(() -> controller.getInventoryItemCount(CABBAGE) > before, 4000)) {
      picked++;
      log("Picked a cabbage at " + item[0] + "," + item[1] + " (" + (before + 1) + "/"
          + cabbageTarget + ")");
    }
    return 200;
  }

  private int thieve() {
    int coins = controller.getInventoryItemCount(COINS);
    setPhase("Thieving " + coins + "/" + coinTarget + " coins");
    if (controller.isBatching()) return 640;
    if (controller.distanceTo(THIEVE_PT[0], THIEVE_PT[1]) > 10) {
      if (inManorGrounds()) leaveManorGrounds();
      walkRoute(
          nearestIndex(FIELD_TO_LUMB, 25) >= 0 || nearestIndex(DRAYNOR_BANK_TO_LUMB, 25) < 0
              ? FIELD_TO_LUMB
              : DRAYNOR_BANK_TO_LUMB,
          "Lumbridge men");
      return 300;
    }
    ORSCharacter man =
        controller.getNearestNPCByLambda(
            n -> {
              if (n.npcId != MAN) return false;
              if (controller.isNpcInCombat(n.serverIndex)) return false;
              int[] c = controller.getNpcCoordsByServerIndex(n.serverIndex);
              return c[0] != -1
                  && controller.distance(c[0], c[1], THIEVE_PT[0], THIEVE_PT[1]) <= 12
                  && controller.isReachable(c[0], c[1], true);
            });
    if (man == null) {
      status = "Looking for a man to thieve";
      return 1500;
    }
    if (weapon.wielded() >= 0) weapon.takeOff(this::dropOneCabbage);
    status = "Pickpocketing a man";
    thieveTries++;
    controller.thieveNpc(man.serverIndex);
    final int before = coins;
    if (controller.sleepUntil(
        () -> controller.getInventoryItemCount(COINS) > before || controller.isInCombat(), 2500)
        && controller.getInventoryItemCount(COINS) > before) {
      logEvery("thieved", 30_000, "Thieved: " + controller.getInventoryItemCount(COINS) + "/" + coinTarget + " coins");
    }
    return 400;
  }

  // ---- Port Sarim ----

  private int toSarim() {
    if (controller.getInventoryItemCount(COINS) < BOAT_FEE * 2) {
      prepDone = false;
      log("Only " + controller.getInventoryItemCount(COINS) + " coins, need " + BOAT_FEE * 2
          + " for both ships: back to thieving.");
      return 100;
    }
    if (controller.isInCombat()) return escapeCombat(PORT_SARIM_DOCK);
    if (eatIfNeeded()) return 1300;
    if (controller.distanceTo(PORT_SARIM_DOCK[0], PORT_SARIM_DOCK[1]) <= 6) {
      setPhase("Port Sarim ship to Karamja");
      takeBoat(SAILORS, "Sailor", PORT_SARIM_DOCK, new String[][] {{"yes"}}, new int[] {1},
          this::onKaramja, BOAT_FEE);
      if (onKaramja()) log("Arrived on Karamja at " + pos());
      return 600;
    }
    setPhase("Walking to Port Sarim");
    if (inBox(SARIM_FIELD_BOX, controller.currentX(), controller.currentY(), 6))
      walkRoute(SARIM_FIELD_TO_DOCK, "Port Sarim dock");
    else if (inManorGrounds() || controller.distanceTo(DRAYNOR_BANK[0], DRAYNOR_BANK[1]) <= 25
        || nearestIndex(DRAYNOR_BANK_TO_SARIM, 6) >= 0) {
      if (inManorGrounds()) leaveManorGrounds();
      walkRoute(DRAYNOR_BANK_TO_SARIM, "Port Sarim dock");
    } else walkRoute(TO_SARIM, "Port Sarim dock");
    return 300;
  }

  // ---- Karamja ----

  private int karamja() {
    int x = controller.currentX();
    if (controller.getInventoryItemCount(COINS) < BOAT_FEE) {
      setPhase("On Karamja without " + BOAT_FEE + " coins");
      log("On Karamja with " + controller.getInventoryItemCount(COINS)
          + " coins; the Brimhaven ship needs " + BOAT_FEE + ". Waiting.");
      return 15000;
    }
    if (controller.isInCombat()) {
      return escapeCombat(x < BRIMHAVEN_GATE[0] + 1 ? BRIMHAVEN_GATE : BRIMHAVEN_DOCK);
    }
    if (eatIfNeeded()) return 1300;
    if (x < BRIMHAVEN_GATE[0] + 1) {
      if (controller.distanceTo(BRIMHAVEN_GATE[0], BRIMHAVEN_GATE[1]) <= 3) {
        setPhase("Opening the Brimhaven gate");
        if (controller.getObjectAtCoord(BRIMHAVEN_GATE[0], BRIMHAVEN_GATE[1]) == BRIMHAVEN_GATE_ID)
          controller.atObject(BRIMHAVEN_GATE[0], BRIMHAVEN_GATE[1]);
        if (controller.sleepUntil(() -> controller.currentX() >= BRIMHAVEN_GATE[0] + 1, 5000))
          log("Through the Brimhaven gate at " + pos());
        return 300;
      }
      setPhase("Karamja: walking to the Brimhaven gate");
      walkRoute(KARAMJA_TO_GATE, "Brimhaven gate");
      return 300;
    }
    if (controller.distanceTo(BRIMHAVEN_DOCK[0], BRIMHAVEN_DOCK[1]) <= 6) {
      setPhase("Brimhaven ship to Ardougne");
      dropRum();
      takeBoat(CUSTOMS_OFFICIAL, "Customs Official", BRIMHAVEN_DOCK,
          new String[][] {{"board this ship"}, {"search away", "nothing to hide"}, {"ok"}},
          new int[] {0, 1, 0}, () -> !onKaramja() && controller.currentX() > 0, BOAT_FEE);
      if (!onKaramja()) log("Arrived in Ardougne at " + pos());
      return 600;
    }
    setPhase("Brimhaven: walking to the dock");
    walkRoute(GATE_TO_DOCK, "Brimhaven dock");
    return 300;
  }

  private void dropRum() {
    while (controller.isRunning() && controller.getInventoryItemCount(KARAMJA_RUM) > 0) {
      log("Dropping Karamja rum (customs won't allow it).");
      int slot = controller.getInventoryItemSlotIndex(KARAMJA_RUM);
      if (slot < 0) break;
      controller.dropItem(slot);
      controller.sleep(1200);
    }
  }

  // ---- Ardougne and on ----

  private int kandarin() {
    if (controller.isInCombat()) return escapeCombat(destTile());
    if (eatIfNeeded()) return 1300;
    int[] dest = destTile();
    if (controller.distanceTo(dest[0], dest[1]) <= 1) {
      setPhase("Arrived: " + destName());
      log("Arrived at " + destName() + " (" + pos() + "). Coins " + controller.getInventoryItemCount(COINS)
          + ", cabbages " + controller.getInventoryItemCount(CABBAGE) + ". Stopping.");
      controller.displayMessage("@gre@Thief 2 Ardougne: @whi@arrived at " + destName() + "!");
      weapon.putBack(false); // before stopping
      controller.stop();
      return 1000;
    }
    setPhase("Walking to " + destName());
    walkRoute(route(), destName());
    return 300;
  }

  private int[] destTile() {
    switch (destination) {
      case DEST_SEERS:
        return SEERS_BANK;
      case DEST_CATHERBY:
        return CATHERBY_BANK;
      default:
        return ARDOUGNE_BANK;
    }
  }

  private int[][] route() {
    switch (destination) {
      case DEST_SEERS:
        return LANDING_TO_SEERS;
      case DEST_CATHERBY:
        return LANDING_TO_CATHERBY;
      default:
        return LANDING_TO_ARDOUGNE_BANK;
    }
  }

  // =========================================================================================
  // Walking
  // =========================================================================================

  /**
   * Walks the route from its nearest waypoint to its end, one waypoint at a time, watching HP and
   * combat on every tick. Attacked: keeps walking (the next waypoint, away from the attacker) and
   * eats once out of combat. Far from the route: one web walker step towards its nearest point.
   */
  private boolean walkRoute(int[][] route, String name) {
    int start = nearestIndex(route, 25);
    if (start < 0) {
      int[] first = route[nearestIndex(route, Integer.MAX_VALUE)];
      status = "Web walking towards the " + name + " route";
      log("Not near the " + name + " route (at " + pos() + "); web walking towards " + first[0]
          + "," + first[1]);
      boolean ok;
      try {
        ok = controller.walkTowards(first[0], first[1]);
      } catch (Exception e) {
        ok = false;
      }
      if (!ok) randomStep(5);
      return false;
    }
    // Skip the nearest waypoint if we're already past it towards the next one.
    if (start + 1 < route.length
        && dist(route[start], route[start + 1]) >= controller.distanceTo(route[start + 1][0], route[start + 1][1]))
      start++;
    for (int i = start; i < route.length && controller.isRunning(); i++) {
      int wx = route[i][0], wy = route[i][1];
      status = "Walking to " + name + " (" + (i + 1) + "/" + route.length + ")";
      if (i == start || i == route.length - 1) // first and last waypoint always, the rest every 30 s
        log("Route to " + name + ": waypoint " + (i + 1) + "/" + route.length + " (" + wx + "," + wy + ") from " + pos());
      else
        logEvery("route", 30_000, "Route to " + name + ": waypoint " + (i + 1) + "/" + route.length + " (" + wx + ","
            + wy + ") from " + pos());
      long end = System.currentTimeMillis() + WAYPOINT_MS;
      long lastClick = 0;
      while (controller.isRunning() && controller.distanceTo(wx, wy) > 1) {
        if (System.currentTimeMillis() > end) {
          log("Waypoint " + wx + "," + wy + " not reached in " + WAYPOINT_MS / 1000
              + "s (at " + pos() + "); trying the web walker step.");
          try {
            controller.walkTowards(wx, wy);
          } catch (Exception e) {
            randomStep(3);
          }
          return false;
        }
        if (controller.currentY() >= FLOOR || controller.currentX() <= 0) return false;
        if (controller.isInCombat()) {
          // Run on: next waypoint (or this one at the end), clicked every tick to break combat.
          int[] away = route[Math.min(i + 1, route.length - 1)];
          if (System.currentTimeMillis() - lastClick > 600) {
            log("Attacked at " + pos() + " (HP " + hp() + "/" + maxHp() + "), walking on to "
                + away[0] + "," + away[1]);
            controller.walkToAsync(away[0], away[1], 0);
            lastClick = System.currentTimeMillis();
          }
          end = Math.max(end, System.currentTimeMillis() + 5000);
          controller.sleep(300);
          continue;
        }
        if (lowHp() && controller.getInventoryItemCount(CABBAGE) > 0) {
          eatIfNeeded();
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

  /** Index of the waypoint nearest to us within maxDist, or -1. */
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

  /** Climbs the nearest suitable ladder/stairs (from GetMeToArdougne). */
  private void changeFloor(int[] ids, String dir) {
    int[] obj = controller.getNearestObjectByIds(ids);
    if (obj != null) {
      status = "Climbing " + dir + " at " + obj[0] + "," + obj[1];
      log("Not on the ground floor (" + pos() + "): climbing " + dir + " at " + obj[0] + ","
          + obj[1]);
      int startY = controller.currentY();
      controller.atObject(obj[0], obj[1]);
      controller.sleepUntil(() -> Math.abs(controller.currentY() - startY) > 500, 8000);
      return;
    }
    try {
      controller.walkTowards(THIEVE_PT[0], THIEVE_PT[1]);
    } catch (Exception e) {
      controller.sleep(500);
    }
  }

  // =========================================================================================
  // HP / combat
  // =========================================================================================

  private int hp() {
    return controller.getCurrentStat(SkillId.HITS.getId());
  }

  private int maxHp() {
    return Math.max(1, controller.getBaseStat(SkillId.HITS.getId()));
  }

  private boolean lowHp() {
    return hp() * 100 < eatPercent * maxHp();
  }

  /** Eats one cabbage if below the HP threshold and not in combat. Returns true if it tried. */
  private boolean eatIfNeeded() {
    if (!lowHp() || controller.isInCombat()) return false;
    int before = controller.getInventoryItemCount(CABBAGE);
    if (before == 0) return false;
    if (controller.isBatching()) controller.stopBatching();
    int h = hp();
    status = "Eating a cabbage (" + h + "/" + maxHp() + " HP)";
    controller.itemCommand(CABBAGE);
    controller.sleepUntil(() -> controller.getInventoryItemCount(CABBAGE) < before, 2000);
    if (controller.getInventoryItemCount(CABBAGE) < before) {
      eaten++;
      log("Ate a cabbage at " + h + " HP, now " + hp() + "/" + maxHp() + " ("
          + (before - 1) + " left)");
    }
    return true;
  }

  /** RSC can't eat in combat: walk off (towards where we're going) and eat afterwards. */
  private int escapeCombat(int[] towards) {
    status = "Escaping combat";
    log("In combat at " + pos() + " (HP " + hp() + "/" + maxHp() + "), walking away");
    if (towards != null && controller.distanceTo(towards[0], towards[1]) <= 15)
      controller.walkToAsync(towards[0], towards[1], 0);
    else controller.walkTo(controller.currentX(), controller.currentY(), 0, true, false);
    return 650;
  }

  // =========================================================================================
  // Ernest the Chicken money method
  // =========================================================================================

  private static final int ERNEST_TIMEOUT_MINUTES = 35;

  private int questStage() {
    return controller.getQuestStage(QuestId.ERNEST_THE_CHICKEN.getId());
  }

  /** In Draynor Manor on any floor (AIOQuester's DraynorManor area detection). */
  private boolean inManor() {
    return DraynorManor.inManor(controller.currentX(), controller.currentY());
  }

  /** The manor's garden/grounds (inside the outer gate at 210,570) but not in the house. */
  private boolean inManorGrounds() {
    int x = controller.currentX(), y = controller.currentY();
    return y < FLOOR && x >= 188 && x <= 240 && y >= 535 && y <= 569 && !inManor();
  }

  private QuestWalker aioWalker() {
    if (aioWalker == null) aioWalker = new QuestWalker(controller);
    return aioWalker;
  }

  /** Out through the manor's outer gate with AIOQuester's door-aware walker. */
  private boolean leaveManorGrounds() {
    mlog("Leaving the manor grounds through the gate from " + where());
    return aioWalker().walkTo(210, 572, 2, 60_000L);
  }

  /** Inside Draynor Manor (any floor): carry on with Ernest, or leave the house. */
  private int manorTick() {
    if (ernestMode() && !ernestDone && !ernestFailed && questStage() != -1) {
      firstPickDone = true; // resuming inside the manor: don't walk off to the cabbage field first
      ernestChecked = true;
      return runErnest();
    }
    setPhase("Leaving Draynor Manor");
    mlog("In the manor at " + where() + ": leaving with AIOQuester's DraynorManor routes");
    if (!aioWalker().manorGoTo(DraynorManor.Area.OUTSIDE)) {
      mlog("Couldn't get out of the manor from " + where() + ", retrying");
      return 1500;
    }
    return 300;
  }

  /**
   * Runs AIOQuester's own Ernest the Chicken as a one-quest AIOQuester queue (the same call
   * AIOQuester makes for a single selected quest). AIOQuester never banks coins and is told to keep
   * the cabbages, so the 300gp reward stays carried for the boat fares. Anything but a completed
   * quest (failed, timed out, skipped) falls back to thieving.
   */
  private int runErnest() {
    if (questStage() == -1) {
      ernestDone = true;
      ernestStatus = "already complete";
      log("Ernest the Chicken is already complete: getting coins by thieving.");
      return 100;
    }
    // AIOQuester starts Ernest from Draynor bank: get there along a safe road first, unless
    // we're already at the manor (AIOQuester then carries on from where we stand).
    if (!inManor() && !inManorGrounds() && controller.distanceTo(DRAYNOR_BANK[0], DRAYNOR_BANK[1]) > 8) {
      setPhase("Walking to Draynor bank for Ernest the Chicken");
      walkRoute(LUMB_TO_DRAYNOR_BANK, "Draynor bank");
      return 300;
    }
    int stage = questStage();
    int coinsBefore = controller.getInventoryItemCount(COINS);
    setPhase("Ernest the Chicken (AIOQuester)");
    ernestStatus = "running (AIOQuester)";
    log("Handing over to AIOQuester's Ernest the Chicken: stage " + stage + ", at " + where()
        + ", timeout " + ERNEST_TIMEOUT_MINUTES + " min. Type ::skip to abandon it.");
    QuestQueue.Options options = new QuestQueue.Options();
    options.quests.add(Quest.ERNEST_THE_CHICKEN.getQuestDef());
    options.timeoutMinutes = ERNEST_TIMEOUT_MINUTES;
    QuestHandler qh = new QuestHandler();
    try {
      qh.updateBankItems();
    } catch (RuntimeException ignored) {
      // bank not loaded yet; AIOQuester refreshes it itself
    }
    long t0 = System.currentTimeMillis();
    aioQh = qh;
    QuestHandler.EXTRA_KEEP_ITEMS.add(CABBAGE); // food for the trip: AIOQuester mustn't bank it
    try {
      new QuestQueue(qh, options).run();
    } catch (RuntimeException e) {
      log("AIOQuester's Ernest the Chicken threw " + e);
    } finally {
      aioQh = null;
      QuestHandler.EXTRA_KEEP_ITEMS.remove(CABBAGE);
    }
    if (!controller.isRunning()) return 1000;
    long mins = (System.currentTimeMillis() - t0) / 60_000L;
    if (questStage() == -1) {
      ernestDone = true;
      ernestStatus = "done";
      log("AIOQuester finished Ernest the Chicken in " + mins + " min (now at " + where() + ").");
      int carried = controller.getInventoryItemCount(COINS);
      log("Carrying " + carried + " coins after Ernest (had " + coinsBefore + "; reward not banked).");
      // Fallback only: an older AIOQuester banked the reward. Fetch it if we're short.
      if (carried < coinTarget && carried < coinsBefore + 300) withdrawReward(coinsBefore);
      controller.displayMessage("@gre@Thief 2 Ardougne: @whi@Ernest the Chicken done, "
          + controller.getInventoryItemCount(COINS) + " coins");
    } else {
      ernestFailed = true;
      ernestStatus = "abandoned";
      ernestFailReason = "AIOQuester didn't complete it after " + mins + " min (stage "
          + questStage() + "; failed, timed out or skipped - see the AIOQuester log lines)";
      log("Abandoning Ernest the Chicken: " + ernestFailReason + ". Falling back to thieving.");
      controller.displayMessage("@red@Thief 2 Ardougne: @whi@Ernest not completed, thieving instead");
      if (inManor()) aioWalker().manorGoTo(DraynorManor.Area.OUTSIDE);
    }
    return 300;
  }

  /** Fallback: if the reward ended up banked at Draynor, take the coins back out for the ships. */
  private void withdrawReward(int coinsBefore) {
    if (controller.getInventoryItemCount(COINS) >= coinsBefore + 300) return; // still carrying it
    if (controller.distanceTo(DRAYNOR_BANK[0], DRAYNOR_BANK[1]) > 10) {
      if (!walkRoute(LUMB_TO_DRAYNOR_BANK, "Draynor bank") && controller.distanceTo(DRAYNOR_BANK[0], DRAYNOR_BANK[1]) > 10) {
        log("Not near Draynor bank (" + where() + "): reward not withdrawn, "
            + controller.getInventoryItemCount(COINS) + " coins carried.");
        return;
      }
    }
    for (int attempt = 0; attempt < 3 && controller.isRunning(); attempt++) {
      if (!openBankBounded()) continue;
      int inBank = controller.getBankItemCount(COINS);
      if (inBank > 0) {
        int before = controller.getInventoryItemCount(COINS);
        controller.withdrawItem(COINS, inBank);
        controller.sleepUntil(() -> controller.getInventoryItemCount(COINS) > before, 5000);
      }
      log("Withdrew Ernest's reward at Draynor bank: " + controller.getInventoryItemCount(COINS)
          + " coins carried (bank had " + inBank + ").");
      controller.closeBank();
      controller.sleep(640);
      return;
    }
    log("Couldn't open Draynor bank to withdraw the reward.");
  }

  /** Opens the bank with the nearest banker, bounded (Controller.openBank can wait forever). */
  private boolean openBankBounded() {
    for (int attempt = 0; attempt < 6 && controller.isRunning() && !controller.isInBank(); attempt++) {
      ORSCharacter banker = controller.getNearestNpcByIds(controller.bankerIds, false);
      if (banker == null) {
        controller.walkToAsync(DRAYNOR_BANK[0], DRAYNOR_BANK[1], 1);
        controller.sleep(1280);
        continue;
      }
      if ("Bank".equalsIgnoreCase(controller.getNpcCommand1(banker.npcId)))
        controller.npcCommand1(banker.serverIndex);
      else {
        controller.talkToNpc(banker.serverIndex);
        if (controller.sleepUntil(() -> controller.isInOptionMenu(), 8000)) {
          int idx = 0;
          for (int i = 0; i < controller.getOptionMenuCount(); i++) {
            String t = controller.getOptionsMenuText(i);
            if (t != null && t.toLowerCase().contains("access my bank")) idx = i;
          }
          controller.optionAnswer(idx);
        }
      }
      controller.sleepUntil(() -> controller.isInBank(), 10000);
    }
    return controller.isInBank();
  }

  // =========================================================================================
  // Boats (copied from GetMeToArdougne: busy NPC handling, menu matching, rum)
  // =========================================================================================

  private boolean takeBoat(int[] npcIds, String name, int[] dock, String[][] choices,
      int[] fallback, BooleanSupplier arrived, int fee) {
    int attempt = 0;
    int talkedNoSail = 0;
    while (controller.isRunning()) {
      if (arrived.getAsBoolean()) return true;
      if (controller.getInventoryItemCount(COINS) < fee) return false;
      if (controller.distanceTo(dock[0], dock[1]) > 25) return false;
      attempt++;
      if (controller.isInCombat()) {
        controller.walkTo(controller.currentX(), controller.currentY());
        controller.sleep(300);
        continue;
      }
      ORSCharacter npc = controller.getNearestNpcByIds(npcIds, false);
      if (npc == null) {
        waitTurn(name + " not in sight", attempt);
        if (controller.distanceTo(dock[0], dock[1]) > 3) controller.walkToAsync(dock[0], dock[1], 2);
        continue;
      }
      npcBusy = false;
      status = "Talking to " + name + (attempt > 1 ? " (attempt " + attempt + ")" : "");
      log(status);
      controller.talkToNpc(npc.serverIndex);
      int menus = 0;
      long deadline = System.currentTimeMillis() + TALK_REPLY_MS;
      while (controller.isRunning() && System.currentTimeMillis() < deadline && !npcBusy
          && !arrived.getAsBoolean()) {
        if (controller.isInOptionMenu()) {
          int opt = pickOption(choices, fallback, menus);
          log(name + " menu " + (menus + 1) + ": answering '" + controller.getOptionsMenuText(opt) + "'");
          controller.optionAnswer(opt);
          menus++;
          controller.sleep(1200);
          deadline = System.currentTimeMillis() + TALK_REPLY_MS;
          continue;
        }
        controller.sleep(250);
      }
      if (menus > 0 && !npcBusy) {
        long end = System.currentTimeMillis() + SAIL_MS;
        while (controller.isRunning() && System.currentTimeMillis() < end && !arrived.getAsBoolean()) {
          if (controller.isInOptionMenu()) break;
          controller.sleep(300);
        }
        if (controller.isInOptionMenu()) continue;
      }
      if (arrived.getAsBoolean()) return true;
      if (!controller.isRunning()) return false;
      if (menus >= choices.length && !npcBusy) {
        talkedNoSail++;
        if (talkedNoSail >= 2 && controller.getInventoryItemCount(KARAMJA_RUM) > 0) dropRum();
      }
      String why = npcBusy ? name + " busy"
          : menus == 0 ? "No reply from " + name : "Lost the conversation with " + name;
      waitTurn(why, attempt);
    }
    return false;
  }

  private int pickOption(String[][] choices, int[] fallback, int menuNo) {
    int count = Math.max(0, controller.getOptionMenuCount());
    if (menuNo < choices.length) {
      for (int i = 0; i < count; i++) {
        String text = controller.getOptionsMenuText(i);
        if (text == null) continue;
        String t = text.toLowerCase();
        for (String word : choices[menuNo]) if (t.contains(word)) return i;
      }
    }
    int idx = menuNo < fallback.length ? fallback[menuNo] : 0;
    return count > 0 ? Math.min(idx, count - 1) : idx;
  }

  private void waitTurn(String why, int attempt) {
    boatWaits++;
    status = why + ", waiting turn (attempt " + attempt + ")";
    log(status);
    controller.sleep(2000 + ThreadLocalRandom.current().nextInt(3001));
    if (attempt % 3 == 0 && controller.isRunning() && !controller.isInCombat()) {
      int x = controller.currentX() + ThreadLocalRandom.current().nextInt(-1, 2);
      int y = controller.currentY() + ThreadLocalRandom.current().nextInt(-1, 2);
      if (controller.isReachable(x, y, false)) controller.walkToAsync(x, y, 0);
      controller.sleep(600);
    }
  }

  // =========================================================================================
  // Helpers
  // =========================================================================================

  private static boolean in(int[] r, int x, int y) {
    return x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3];
  }

  private static boolean inBox(int[] r, int x, int y, int pad) {
    return x >= r[0] - pad && x <= r[2] + pad && y >= r[1] - pad && y <= r[3] + pad;
  }

  private static boolean inKaramja(int x, int y) {
    for (int[] r : KARAMJA) if (in(r, x, y)) return true;
    return false;
  }

  private boolean onKaramja() {
    return inKaramja(controller.currentX(), controller.currentY());
  }

  private String pos() {
    return controller.currentX() + "," + controller.currentY();
  }

  private void setPhase(String p) {
    if (!p.equals(phase)) {
      // "Thieving 21/60 coins" -> "Thieving #/# coins": a new step is logged at once, a counter
      // ticking up inside the same step at most every 30 s
      boolean sameStep = phase != null && p.replaceAll("[0-9]+", "#").equals(phase.replaceAll("[0-9]+", "#"));
      phase = p;
      if (sameStep) logEvery("step", 30_000, "Step: " + p + " (at " + pos() + ")");
      else {
        loggedAt.put("step", System.currentTimeMillis());
        log("Step: " + p + " (at " + pos() + ")");
      }
    }
    status = p;
    controller.setStatus("@yel@" + p);
  }

  // Log trim: script log only (no copy into the game chat, which the probe records a second time
  // and which used to come back through serverMessageInterrupt); chatty progress lines go through
  // logEvery so they show up at most once per interval.
  private void log(String msg) {
    bot.Main.logScript(P + msg);
  }

  private void mlog(String msg) {
    bot.Main.logScript(PM + msg);
  }

  private final java.util.HashMap<String, Long> loggedAt = new java.util.HashMap<>();

  /** Logs msg unless a line with the same key was logged less than ms ago. */
  private void logEvery(String key, long ms, String msg) {
    long now = System.currentTimeMillis();
    Long last = loggedAt.get(key);
    if (last != null && now - last < ms) return;
    loggedAt.put(key, now);
    log(msg);
  }

  private String where() {
    return DraynorManor.describe(controller.currentX(), controller.currentY());
  }

  @Override
  public void serverMessageInterrupt(String message) {
    if (message == null) return;
    String m = message.toLowerCase();
    if (m.contains("busy")) npcBusy = true;
    LeverBasement.onServerMessage(message); // lever solver when leaving the basement ourselves
    QuestHandler qh = aioQh;
    if (qh != null) qh._serverMessageInterrupt(message);
    // Our own log lines come back through chat ("[Thief2Ardougne] Server: ..."): never re-log those,
    // or every failed pickpocket snowballs into more and more copies.
    if (message.startsWith("[") || message.contains(P.trim())) return;
    if (m.contains("fail to pick") || m.contains("stunned")) log("Server: " + message);
  }

  @Override
  public void questMessageInterrupt(String message) {
    QuestHandler qh = aioQh;
    if (qh != null) {
      qh._questMessageInterrupt(message);
      return;
    }
    serverMessageInterrupt(message);
  }

  @Override
  public void chatMessageInterrupt(String message) {
    QuestHandler qh = aioQh;
    if (qh != null) qh._chatMessageInterrupt(message);
  }

  /** ::skip while Ernest runs = AIOQuester's Skip quest (thieving then takes over). */
  @Override
  public void chatCommandInterrupt(String commandText) {
    String cmd = commandText == null ? "" : commandText.trim().toLowerCase();
    if ((cmd.equals("skip") || cmd.equals("skipquest")) && aioQh != null) {
      log("::" + cmd + " typed: asking AIOQuester to skip Ernest the Chicken");
      QuestHandler.requestSkip("::" + cmd);
    }
  }

  @Override
  public void paintInterrupt() {
    if (controller == null) return;
    int y = 21;
    String wt = weapon.paintText();
    controller.drawBoxAlpha(7, 7, 250, 14 * (wt.isEmpty() ? 8 : 9) + 8, 0x000000, 140);
    controller.drawString("@gre@Thief 2 Ardougne", 10, y, 0xFFFFFF, 1);
    y += 14;
    controller.drawString("@whi@Status: @yel@" + status, 10, y, 0xFFFFFF, 1);
    y += 14;
    controller.drawString("@whi@Destination: @yel@" + destName(), 10, y, 0xFFFFFF, 1);
    y += 14;
    controller.drawString(
        "@whi@Money: @yel@"
            + (!ernestMode()
                ? "thieving"
                : "Ernest " + (ernestDone ? "done" : ernestFailed ? "abandoned" : ernestStatus)),
        10, y, 0xFFFFFF, 1);
    y += 14;
    controller.drawString(
        "@whi@Coins: @yel@" + controller.getInventoryItemCount(COINS) + "/" + coinTarget
            + " @whi@Thieve tries: @yel@" + thieveTries, 10, y, 0xFFFFFF, 1);
    y += 14;
    controller.drawString(
        "@whi@Cabbages: @yel@" + controller.getInventoryItemCount(CABBAGE) + "/" + cabbageTarget
            + " @whi@picked @yel@" + picked + " @whi@eaten @yel@" + eaten, 10, y, 0xFFFFFF, 1);
    y += 14;
    controller.drawString(
        "@whi@HP: @yel@" + hp() + "/" + maxHp() + " @whi@(eat < " + eatPercent + "%)", 10, y,
        0xFFFFFF, 1);
    y += 14;
    controller.drawString(
        "@whi@Runtime: @yel@" + controller.msToString(System.currentTimeMillis() - startTime)
            + " @whi@Boat waits: @yel@" + boatWaits, 10, y, 0xFFFFFF, 1);
    if (!wt.isEmpty()) {
      y += 14;
      controller.drawString("@whi@Weapon: @or1@" + wt, 10, y, 0xFFFFFF, 1);
    }
  }

  // =========================================================================================
  // Start GUI (dark)
  // =========================================================================================

  private void setupGUI() {
    Color bg = Main.primaryBG != null ? Main.primaryBG : new Color(40, 40, 40);
    Color fg = Main.primaryFG != null ? Main.primaryFG : new Color(230, 230, 230);
    Color field = bg.brighter();

    JFrame frame = new JFrame("Thief 2 Ardougne - Setup");
    scriptFrame = frame;
    frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

    JPanel content = new JPanel(new BorderLayout(0, 10));
    content.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

    JPanel header = new JPanel(new GridBagLayout());
    JLabel title = new JLabel("Thief 2 Ardougne");
    title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 4f));
    JLabel blurb = new JLabel("<html>Fresh account: picks cabbages, thieves men in Lumbridge,"
        + " (or does Ernest the Chicken),"
        + "<br>then Port Sarim &rarr; Karamja &rarr; Ardougne by ship (60 gp),"
        + "<br>walking routes that keep away from aggressive NPCs.</html>");
    GridBagConstraints hc = new GridBagConstraints();
    hc.gridx = 0;
    hc.anchor = GridBagConstraints.WEST;
    hc.gridy = 0;
    header.add(title, hc);
    hc.gridy = 1;
    hc.insets = new Insets(4, 0, 0, 0);
    header.add(blurb, hc);
    content.add(header, BorderLayout.NORTH);

    JPanel options = new JPanel(new GridBagLayout());
    GridBagConstraints c = new GridBagConstraints();
    c.gridx = 0;
    c.gridy = 0;
    c.anchor = GridBagConstraints.WEST;
    c.insets = new Insets(2, 0, 2, 0);

    JLabel destLabel = new JLabel("When in Ardougne:");
    destLabel.setFont(destLabel.getFont().deriveFont(Font.BOLD));
    options.add(destLabel, c);
    JRadioButton stay = new JRadioButton("Stay at Ardougne (south bank)");
    JRadioButton seers = new JRadioButton("Walk to Seers' Village bank");
    JRadioButton cath = new JRadioButton("Walk to Catherby bank");
    ButtonGroup g = new ButtonGroup();
    g.add(stay);
    g.add(seers);
    g.add(cath);
    if (DEST_SEERS.equals(destination)) seers.setSelected(true);
    else if (DEST_CATHERBY.equals(destination)) cath.setSelected(true);
    else stay.setSelected(true);
    c.insets = new Insets(2, 12, 2, 0);
    for (JRadioButton b : new JRadioButton[] {stay, seers, cath}) {
      c.gridy++;
      options.add(b, c);
    }

    JLabel moneyLabel = new JLabel("Money for the ships:");
    moneyLabel.setFont(moneyLabel.getFont().deriveFont(Font.BOLD));
    c.insets = new Insets(10, 0, 2, 0);
    c.gridy++;
    options.add(moneyLabel, c);
    JRadioButton moneyThieve = new JRadioButton("Thieve men in Lumbridge");
    JRadioButton moneyErnest =
        new JRadioButton("Do Ernest the Chicken (300 gp), thieve only to top up");
    ButtonGroup mg = new ButtonGroup();
    mg.add(moneyThieve);
    mg.add(moneyErnest);
    if (ernestMode()) moneyErnest.setSelected(true);
    else moneyThieve.setSelected(true);
    c.insets = new Insets(2, 12, 2, 0);
    c.gridy++;
    options.add(moneyThieve, c);
    c.gridy++;
    options.add(moneyErnest, c);

    JSpinner cabSpin = spinner(cabbageTarget, 0, MAX_CABBAGES, field, fg);
    JSpinner coinSpin = spinner(coinTarget, MIN_COINS, MAX_COINS, field, fg);
    JSpinner eatSpin = spinner(eatPercent, 1, 99, field, fg);
    c.insets = new Insets(10, 0, 2, 0);
    c.gridy++;
    options.add(row("Cabbages to pick:", cabSpin, "(0-" + MAX_CABBAGES + ", kept as food)"), c);
    c.insets = new Insets(2, 0, 2, 0);
    c.gridy++;
    options.add(row("Coins to thieve:", coinSpin, "(min " + MIN_COINS + ": two 30 gp ships)"), c);
    c.gridy++;
    options.add(row("Eat a cabbage below:", eatSpin, "% HP"), c);
    content.add(options, BorderLayout.CENTER);

    JButton startButton = darkButton("Start", field, fg);
    JButton cancelButton = darkButton("Cancel", field, fg);
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    buttons.add(cancelButton);
    buttons.add(startButton);
    content.add(buttons, BorderLayout.SOUTH);

    final boolean[] confirmed = {false};
    frame.addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosed(WindowEvent e) {
            if (!confirmed[0] && !scriptStarted) {
              controller.displayMessage("@red@Thief 2 Ardougne cancelled.");
              log("Stopping: setup window closed without pressing Start.");
              controller.stop();
            }
          }
        });
    startButton.addActionListener(
        e -> {
          try {
            cabSpin.commitEdit();
            coinSpin.commitEdit();
            eatSpin.commitEdit();
          } catch (java.text.ParseException ignored) {
            // keep the last valid values
          }
          destination = seers.isSelected() ? DEST_SEERS : cath.isSelected() ? DEST_CATHERBY : DEST_ARDOUGNE;
          moneyMethod = moneyErnest.isSelected() ? MONEY_ERNEST : MONEY_THIEVE;
          cabbageTarget = ((Number) cabSpin.getValue()).intValue();
          coinTarget = ((Number) coinSpin.getValue()).intValue();
          eatPercent = ((Number) eatSpin.getValue()).intValue();
          confirmed[0] = true;
          scriptStarted = true;
          announce();
          frame.dispose();
        });
    cancelButton.addActionListener(e -> frame.dispose());

    frame.setContentPane(content);
    applyTheme(content, bg, fg);
    frame.getContentPane().setBackground(bg);
    frame.getRootPane().setDefaultButton(startButton);
    frame.pack();
    frame.setResizable(false);
    frame.setLocationRelativeTo(null);
    frame.setVisible(true);
    frame.toFront();
    frame.requestFocus();
  }

  private static JPanel row(String label, JSpinner spin, String hint) {
    JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    p.add(new JLabel(label));
    p.add(spin);
    JLabel h = new JLabel(hint);
    h.setFont(h.getFont().deriveFont(Font.ITALIC));
    p.add(h);
    return p;
  }

  private static JSpinner spinner(int value, int min, int max, Color bg, Color fg) {
    JSpinner s = new JSpinner(new SpinnerNumberModel(value, min, max, 1));
    JSpinner.NumberEditor ed = new JSpinner.NumberEditor(s, "#");
    JFormattedTextField tf = ed.getTextField();
    tf.setColumns(4);
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
    if (comp instanceof JPanel || comp instanceof JLabel || comp instanceof JRadioButton
        || comp instanceof JCheckBox) {
      comp.setBackground(bg);
      comp.setForeground(fg);
      if (comp instanceof JComponent && !(comp instanceof JPanel)) ((JComponent) comp).setOpaque(false);
    }
    if (comp instanceof Container && !(comp instanceof JSpinner) && !(comp instanceof JButton)) {
      for (Component child : ((Container) comp).getComponents()) applyTheme(child, bg, fg);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Weapon guard: nothing in the weapon hand while pickpocketing. The weapon's item id is saved
  // per account in settings/weapon-guard/<script>_<account>.txt (survives a reload / crash), kept
  // out of every deposit / drop, and put back on before the script stops. Shields and armour
  // stay as they are (a two-hander sits in the weapon slot, so it counts as the weapon).
  // ---------------------------------------------------------------------------------------------

  final WeaponGuard weapon = new WeaponGuard("Thief2Ardougne");

  @Override
  public void cleanup() {
    try {
      weapon.putBack(true); // stop button / queue stop: put the weapon back on (no long waits here)
    } catch (Throwable ignored) {
      // never block a stop
    }
    QuestHandler qh = aioQh;
    if (qh != null) qh.cleanup();
    JFrame frame = scriptFrame;
    if (frame != null && frame.isDisplayable()) SwingUtilities.invokeLater(frame::dispose);
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

  /** Full inventory when taking the weapon off: drop one cabbage (picked again later if needed). */
  private void dropOneCabbage() {
    int slot = controller.getInventoryItemSlotIndex(CABBAGE);
    if (slot < 0) return;
    controller.dropItem(slot, 1);
    controller.sleep(1200);
  }
}
