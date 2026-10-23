package scripting.idlescript;

import bot.ui.scriptselector.models.Category;
import bot.ui.scriptselector.models.ScriptInfo;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import orsc.ORSCharacter;

/**
 * Gets to Ardougne from (almost) anywhere. Works out where it is and what step it is up to on
 * start and again after every step, so it can be started anywhere and survives deaths, teleports
 * and other surprises. Mainland: gets 60 gp (bank or thieving men), Port Sarim ship to Karamja,
 * walks to Brimhaven, Customs Official ship to Ardougne. Waits its turn when a boat NPC is busy.
 *
 * @author Dvorak (start-anywhere / busy-NPC rework: Benzyme)
 */
public class GetMeToArdougne extends IdleScript {
  public static final ScriptInfo info =
      new ScriptInfo(
          new Category[] {
            Category.MISCELLANEOUS, Category.IRONMAN_SUPPORTED, Category.ULTIMATE_IRONMAN_SUPPORTED
          },
          "Dvorak",
          "Gets to Ardougne from anywhere: detects where it is, gets 60 gp (bank or thieving men)"
              + " and uses the ships via Karamja. Waits its turn if a boat NPC is busy.");

  // ---- Original fixed paths (used as fallbacks when the web walker can't find a way) ----
  final int[] lumbToShip = {
    120, 648, 130, 647, 142, 644, 154, 642, 171, 643, 179, 640, 190, 638, 202, 633, 213, 632, 227,
    631, 237, 620, 254, 619, 259, 631, 267, 641
  };
  final int[] shipToGate = {
    334, 713, 355, 709, 370, 706, 386, 699, 402, 691, 416, 686, 426, 682, 434, 682
  };
  final int[] gateToShip = {439, 675, 446, 665, 459, 661, 467, 654};

  // ---- Coordinates (RSC world coords; x grows westwards) ----
  private static final int COINS = 10;
  private static final int MAN = 11;
  private static final int[] SAILORS = {166, 170, 171}; // Captain Tobias, Seaman Lorris/Thresnor
  private static final int[] CUSTOMS_OFFICIAL = {317};
  private static final int KARAMJA_RUM = 318;
  private static final int[] PORT_SARIM_DOCK = {268, 646}; // webwalk graph boat node, by sailors
  private static final int[] KARAMJA_LANDING = {324, 713};
  private static final int[] BRIMHAVEN_GATE = {434, 682}; // gate object 254, other side x>=435
  private static final int[] BRIMHAVEN_DOCK = {467, 654}; // by the Customs Official
  private static final int[] ARDOUGNE = {552, 613}; // south bank, near where the ship lands
  private static final int[] LUMBRIDGE_MEN = {122, 648}; // start of the original route
  private static final int BOAT_FEE = 30;
  private static final int FLOOR = 944; // y offset per floor; >= 3 floors = underground

  /** Karamja island, same rectangles as models.entities.Location (x1, y1, x2, y2). */
  private static final int[][] KARAMJA = {
    {331, 722, 480, 910}, {435, 640, 532, 731}, {318, 682, 367, 726}, {364, 656, 437, 719}
  };
  /** East + West Ardougne, the monastery and the docks. Checked after Karamja. */
  private static final int[] ARDOUGNE_AREA = {495, 520, 710, 680};
  /** Islands with no way to Ardougne we handle (fallback: unknown -> unstuck/wait). */
  private static final int[][] ISLANDS = {{395, 525, 442, 574} /* Entrana */};

  private static final int[] GENERIC_DOWN = {6, 42, 44}; // ladder / wooden / stone stairs down
  private static final int[] GENERIC_UP = {5, 41, 43, 130, 198, 227, 249, 270, 295, 342, 369, 629};

  private static final long TALK_REPLY_MS = 8_000L; // no menu / reply by then: treat as busy
  private static final long SAIL_MS = 15_000L; // menus done: time for the trip to finish

  enum Step {
    LOADING("waiting for the game (loading / on a boat)"),
    ARDOUGNE("in Ardougne, finishing"),
    UPSTAIRS("upstairs, climbing down"),
    UNDERGROUND("underground, climbing up"),
    KANDARIN("in Kandarin, walking to Ardougne by land"),
    MAINLAND_COINS("on the mainland, getting 60 coins"),
    MAINLAND_TO_SARIM("on the mainland, heading to Port Sarim"),
    SARIM_BOAT("at Port Sarim, taking the ship to Karamja"),
    KARAMJA("on Karamja, heading to Brimhaven"),
    BRIMHAVEN("in Brimhaven, heading to the dock"),
    BRIMHAVEN_BOAT("at Brimhaven dock, taking the ship to Ardougne"),
    KARAMJA_NO_COINS("on Karamja without 30 coins"),
    UNKNOWN("unsure where I am, heading to a known waypoint");

    final String text;

    Step(String text) {
      this.text = text;
    }
  }

  private volatile boolean npcBusy = false; // "... is busy at the moment"
  private volatile String status = "Starting";
  private volatile Step step = null;
  private int boatWaits = 0;
  private int walkFails = 0;
  private boolean bankChecked = false;
  private long lastPosChange = System.currentTimeMillis();
  private int lastX = -1, lastY = -1;

  public int start(String[] parameters) {
    controller.displayMessage("@red@GetMeToArdougne by Dvorak. @yel@Start anywhere!");
    controller.displayMessage("@yel@WARNING: @red@Script is not death safe (it re-routes after).");

    while (controller.isRunning()) {
      if (controller.getNeedToMove()) controller.moveCharacter();
      if (controller.getShouldSleep()) controller.sleepHandler(true);

      Step now = detect();
      if (now != step) {
        step = now;
        String msg = "Detected: " + now.text;
        controller.log("[GetMeToArdougne] " + msg);
        controller.displayMessage("@yel@" + msg);
      }
      try {
        if (!runStep(now)) break;
      } catch (Exception e) { // never die on a hiccup: log, wait, re-detect
        controller.log("[GetMeToArdougne] step " + now + " failed: " + e);
        controller.sleep(2000);
      }
      controller.sleep(200);
    }
    return 1000; // start() must return a int value now.
  }

  // ---------------------------------------------------------------- detection

  private Step detect() {
    int x = controller.currentX(), y = controller.currentY();
    if (x <= 0 || y <= 0) return Step.LOADING;
    if (y >= FLOOR * 3) return Step.UNDERGROUND;
    if (y >= FLOOR) return Step.UPSTAIRS;
    if (inKaramja(x, y)) {
      if (controller.getInventoryItemCount(COINS) < BOAT_FEE) return Step.KARAMJA_NO_COINS;
      if (x < BRIMHAVEN_GATE[0] + 1) return Step.KARAMJA;
      return controller.distanceTo(BRIMHAVEN_DOCK[0], BRIMHAVEN_DOCK[1]) <= 6
          ? Step.BRIMHAVEN_BOAT
          : Step.BRIMHAVEN;
    }
    if (in(ARDOUGNE_AREA, x, y)) return Step.ARDOUGNE;
    for (int[] r : ISLANDS) if (in(r, x, y)) return Step.UNKNOWN;
    if (x >= 430) return Step.KANDARIN; // west of White Wolf Mountain: Ardougne is walkable
    if (controller.getInventoryItemCount(COINS) < BOAT_FEE * 2) return Step.MAINLAND_COINS;
    return controller.distanceTo(PORT_SARIM_DOCK[0], PORT_SARIM_DOCK[1]) <= 6
        ? Step.SARIM_BOAT
        : Step.MAINLAND_TO_SARIM;
  }

  private static boolean in(int[] r, int x, int y) {
    return x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3];
  }

  private static boolean inKaramja(int x, int y) {
    for (int[] r : KARAMJA) if (in(r, x, y)) return true;
    return false;
  }

  private boolean onKaramja() {
    return inKaramja(controller.currentX(), controller.currentY());
  }

  // ---------------------------------------------------------------- steps

  /** Runs one bounded chunk of the given step. Returns false only once finished. */
  private boolean runStep(Step s) {
    if (controller.isInCombat() && s != Step.MAINLAND_COINS) {
      setStatus("@red@In combat, stepping away");
      controller.walkTo(controller.currentX(), controller.currentY());
      controller.sleep(400);
      return true;
    }
    switch (s) {
      case LOADING:
        setStatus("@yel@Waiting (loading / sailing)..");
        controller.sleep(1500);
        return true;
      case ARDOUGNE:
        setStatus("@gre@Arrived in Ardougne");
        controller.log("[GetMeToArdougne] Arrived in Ardougne, done.");
        controller.displayMessage("@red@GetMeToArdougne finished!");
        controller.stop();
        return false;
      case UPSTAIRS:
        changeFloor(GENERIC_DOWN, "down");
        return true;
      case UNDERGROUND:
        changeFloor(GENERIC_UP, "up");
        return true;
      case KANDARIN:
        webwalk(ARDOUGNE, "Ardougne", null);
        return true;
      case MAINLAND_COINS:
        getCoins();
        return true;
      case MAINLAND_TO_SARIM:
        webwalk(PORT_SARIM_DOCK, "Port Sarim dock", lumbToShip);
        return true;
      case SARIM_BOAT:
        takeBoat(
            SAILORS,
            "Sailor",
            PORT_SARIM_DOCK,
            new String[][] {{"yes"}},
            new int[] {1}, // as before: no "Yes" option means Dragon Slayer isn't done
            this::onKaramja,
            BOAT_FEE);
        return true;
      case KARAMJA:
        if (!webwalk(BRIMHAVEN_DOCK, "Brimhaven", null)) karamjaFallback();
        return true;
      case BRIMHAVEN:
        webwalk(BRIMHAVEN_DOCK, "Brimhaven dock", gateToShip);
        return true;
      case BRIMHAVEN_BOAT:
        takeBoat(
            CUSTOMS_OFFICIAL,
            "Customs Official",
            BRIMHAVEN_DOCK,
            new String[][] {{"board this ship"}, {"search away", "nothing to hide"}, {"ok"}},
            new int[] {0, 1, 0},
            () -> !onKaramja() && controller.currentX() > 0,
            BOAT_FEE);
        return true;
      case KARAMJA_NO_COINS:
        setStatus("@red@Need " + BOAT_FEE + " coins for the Brimhaven ship, waiting");
        controller.log(
            "[GetMeToArdougne] On Karamja with "
                + controller.getInventoryItemCount(COINS)
                + " coins; the Brimhaven ship needs "
                + BOAT_FEE
                + ". Waiting (give/pick up coins).");
        controller.sleep(15000);
        return true;
      case UNKNOWN:
      default:
        unstuck("Unsure where I am");
        return true;
    }
  }

  /**
   * One web walker step towards a target. On failure, uses the given fixed path if near it, and
   * after a few failures goes to the nearest bank / takes a random step.
   */
  private boolean webwalk(int[] target, String name, int[] fallbackPath) {
    setStatus("@yel@Walking to " + name + "..");
    boolean ok;
    try {
      ok = controller.walkTowards(target[0], target[1]);
    } catch (Exception e) {
      ok = false;
    }
    trackMovement();
    if (ok && !stuckFor(60_000)) {
      walkFails = 0;
      return true;
    }
    walkFails++;
    controller.log("[GetMeToArdougne] Web walker couldn't get to " + name + " (" + walkFails + ")");
    if (fallbackPath != null && nearPath(fallbackPath, 20)) {
      setStatus("@yel@Walking to " + name + " (fixed path)..");
      walkPathFromNearest(fallbackPath);
      return true;
    }
    if (walkFails >= 3) unstuck("Can't path to " + name);
    else controller.sleep(1000);
    return false;
  }

  /** Original Karamja route + gate logic, used if the web walker fails on Karamja. */
  private void karamjaFallback() {
    if (controller.distanceTo(BRIMHAVEN_GATE[0], BRIMHAVEN_GATE[1]) <= 3) {
      setStatus("@yel@Opening Brimhaven gate..");
      if (controller.getObjectAtCoord(BRIMHAVEN_GATE[0], BRIMHAVEN_GATE[1]) == 254)
        controller.atObject(BRIMHAVEN_GATE[0], BRIMHAVEN_GATE[1]);
      controller.sleepUntil(() -> controller.currentX() >= 435, 5000);
    } else if (nearPath(shipToGate, 20)) {
      setStatus("@yel@Walking to Brimhaven gate (fixed path)..");
      walkPathFromNearest(shipToGate);
    }
  }

  /**
   * Walks a fixed path starting from its nearest waypoint (not the first), giving up on a waypoint
   * after 20 s so a blocked tile can't hang the script; the main loop re-detects afterwards.
   */
  private void walkPathFromNearest(int[] path) {
    int start = 0;
    for (int i = 0; i + 1 < path.length; i += 2)
      if (controller.distanceTo(path[i], path[i + 1])
          < controller.distanceTo(path[start], path[start + 1])) start = i;
    for (int i = start; i + 1 < path.length && controller.isRunning(); i += 2) {
      long end = System.currentTimeMillis() + 20_000L;
      while (controller.isRunning()
          && controller.distanceTo(path[i], path[i + 1]) > 1
          && System.currentTimeMillis() < end) {
        controller.walkTo(path[i], path[i + 1]);
        controller.sleep(618);
      }
    }
  }

  private boolean nearPath(int[] path, int dist) {
    for (int i = 0; i + 1 < path.length; i += 2)
      if (controller.distanceTo(path[i], path[i + 1]) <= dist) return true;
    return false;
  }

  /** Climbs the nearest suitable ladder/stairs; falls back to the web walker / unstuck. */
  private void changeFloor(int[] ids, String dir) {
    int[] obj = controller.getNearestObjectByIds(ids);
    if (obj != null) {
      setStatus("@yel@Climbing " + dir + " at " + obj[0] + "," + obj[1] + "..");
      int startY = controller.currentY();
      controller.atObject(obj[0], obj[1]);
      controller.sleepUntil(() -> Math.abs(controller.currentY() - startY) > 500, 8000);
      return;
    }
    // No ladder in sight: the web walker knows some floors/dungeons, else find our way out
    if (!webwalk(LUMBRIDGE_MEN, "a way " + dir, null)) controller.sleep(500);
  }

  /** Bank (if nearer than Lumbridge and it has coins) or thieve men until 60 coins. */
  private void getCoins() {
    int need = BOAT_FEE * 2 - controller.getInventoryItemCount(COINS);
    if (controller.isInCombat()) {
      controller.walkTo(controller.currentX(), controller.currentY());
      controller.sleep(250);
      return;
    }
    ORSCharacter man = controller.getNearestNpcById(MAN, false);
    int[] manPos = man == null ? null : controller.getNpcCoordsByServerIndex(man.serverIndex);
    if (man != null && (manPos == null || manPos[0] < 0 || controller.distanceTo(manPos[0], manPos[1]) <= 15)) {
      setStatus("@yel@Stealing GP.. (need " + need + " more)");
      controller.thieveNpc(man.serverIndex);
      controller.sleep(700);
      return;
    }
    int[] bank = nearestBank();
    int bankDist = controller.distanceTo(bank[0], bank[1]);
    int lumbDist = controller.distanceTo(LUMBRIDGE_MEN[0], LUMBRIDGE_MEN[1]);
    if (!bankChecked && bankDist < lumbDist) {
      if (bankDist > 5) {
        webwalk(bank, "the nearest bank for coins", null);
        return;
      }
      setStatus("@yel@Checking bank for " + need + " coins..");
      controller.openBank();
      if (controller.isInBank()) {
        int inBank = controller.getBankItemCount(COINS);
        if (inBank >= need) {
          controller.withdrawItem(COINS, need);
          controller.sleep(1200);
        }
        controller.log("[GetMeToArdougne] Bank had " + inBank + " coins (needed " + need + ").");
        controller.closeBank();
      }
      bankChecked = true; // don't keep walking back to an empty bank
      return;
    }
    if (lumbDist > 10) {
      webwalk(LUMBRIDGE_MEN, "Lumbridge (men to thieve)", null);
      return;
    }
    setStatus("@yel@Looking for men to thieve..");
    randomStep(4);
    controller.sleep(1500);
  }

  private int[] nearestBank() {
    int[][] banks = {
      {220, 635}, {150, 504}, {103, 511}, {220, 365}, {216, 450}, {283, 569},
      {503, 452}, {582, 576}, {566, 605}, {588, 754}, {440, 495}, {327, 552}, {89, 694}
    };
    int[] best = banks[0];
    for (int[] b : banks)
      if (controller.distanceTo(b[0], b[1]) < controller.distanceTo(best[0], best[1])) best = b;
    return best;
  }

  /** Not sure where we are / can't path: nearest bank via web walker, else a random step. */
  private void unstuck(String why) {
    setStatus("@yel@" + why + ", heading to a known spot..");
    controller.log("[GetMeToArdougne] " + why + " at " + controller.currentX() + ","
        + controller.currentY() + ", trying the nearest bank / a random step.");
    boolean ok;
    try {
      ok = controller.walkTowardsBank();
    } catch (Exception e) {
      ok = false;
    }
    if (!ok) randomStep(6);
    walkFails = 0;
    controller.sleep(2000);
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

  private void trackMovement() {
    int x = controller.currentX(), y = controller.currentY();
    if (x != lastX || y != lastY) {
      lastX = x;
      lastY = y;
      lastPosChange = System.currentTimeMillis();
    }
  }

  private boolean stuckFor(long ms) {
    return System.currentTimeMillis() - lastPosChange > ms;
  }

  // ---------------------------------------------------------------- boats (v11 busy handling)

  /**
   * Talks to a boat NPC and answers its menus until the trip has happened. If the NPC is busy with
   * another player, doesn't answer, can't be found for a moment, or the conversation is lost half
   * way, it waits 2-5 s (stepping a tile now and then) and tries again, re-finding the NPC each
   * time. Returns when arrived, or when something changed that needs re-detecting (moved away from
   * the dock, e.g. died/teleported, or not enough coins).
   */
  private boolean takeBoat(
      int[] npcIds,
      String name,
      int[] dock,
      String[][] choices,
      int[] fallback,
      BooleanSupplier arrived,
      int fee) {
    int attempt = 0;
    int talkedNoSail = 0;
    while (controller.isRunning()) {
      if (arrived.getAsBoolean()) return true;
      if (controller.getInventoryItemCount(COINS) < fee) return false; // re-detect
      if (controller.distanceTo(dock[0], dock[1]) > 25) return false; // died / teleported
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
      setStatus("@yel@Talking to " + name + (attempt > 1 ? " (attempt " + attempt + ")" : "") + "..");
      controller.talkToNpc(npc.serverIndex);

      int menus = 0;
      long deadline = System.currentTimeMillis() + TALK_REPLY_MS;
      while (controller.isRunning()
          && System.currentTimeMillis() < deadline
          && !npcBusy
          && !arrived.getAsBoolean()) {
        if (controller.isInOptionMenu()) {
          controller.optionAnswer(pickOption(choices, fallback, menus));
          menus++;
          controller.sleep(1200);
          deadline = System.currentTimeMillis() + TALK_REPLY_MS; // the NPC is answering us
          continue;
        }
        controller.sleep(250);
      }
      if (menus > 0 && !npcBusy) {
        long end = System.currentTimeMillis() + SAIL_MS;
        while (controller.isRunning()
            && System.currentTimeMillis() < end
            && !arrived.getAsBoolean()) {
          if (controller.isInOptionMenu()) break;
          controller.sleep(300);
        }
        if (controller.isInOptionMenu()) continue; // carry on with this conversation
      }
      if (arrived.getAsBoolean()) return true;
      if (!controller.isRunning()) return false;
      if (menus >= choices.length && !npcBusy) {
        talkedNoSail++;
        // Customs won't let alcohol through: after two full conversations without sailing, drop rum
        if (talkedNoSail >= 2 && controller.getInventoryItemCount(KARAMJA_RUM) > 0) {
          controller.log("[GetMeToArdougne] Not allowed on board with Karamja rum: dropping it.");
          int slot = controller.getInventoryItemSlotIndex(KARAMJA_RUM);
          if (slot >= 0) controller.dropItem(slot);
          controller.sleep(1500);
        }
      }
      String why =
          npcBusy
              ? name + " busy"
              : menus == 0 ? "No reply from " + name : "Lost the conversation with " + name;
      waitTurn(why, attempt);
    }
    return false;
  }

  /** Picks the menu option matching this step's words, else the fallback index. */
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

  /** Waits 2-5 s for our turn, logging it; steps one tile every third try. */
  private void waitTurn(String why, int attempt) {
    boatWaits++;
    setStatus("@yel@" + why + ", waiting turn (attempt " + attempt + ")");
    controller.log("[GetMeToArdougne] " + why + ", waiting turn (attempt " + attempt + ")");
    controller.sleep(2000 + ThreadLocalRandom.current().nextInt(3001));
    if (attempt % 3 == 0 && controller.isRunning() && !controller.isInCombat()) {
      int x = controller.currentX() + ThreadLocalRandom.current().nextInt(-1, 2);
      int y = controller.currentY() + ThreadLocalRandom.current().nextInt(-1, 2);
      if (controller.isReachable(x, y, false)) controller.walkToAsync(x, y, 0);
      controller.sleep(600);
    }
  }

  // ---------------------------------------------------------------- misc

  private void setStatus(String s) {
    status = s;
    controller.setStatus(s);
  }

  @Override
  public void serverMessageInterrupt(String message) {
    if (message != null && message.toLowerCase().contains("busy")) npcBusy = true;
  }

  @Override
  public void questMessageInterrupt(String message) {
    serverMessageInterrupt(message);
  }

  @Override
  public void cleanup() {
    super.cleanup();
    npcBusy = false;
    boatWaits = 0;
    walkFails = 0;
    bankChecked = false;
    step = null;
    status = "Starting";
  }

  @Override
  public void paintInterrupt() {
    if (controller != null) {
      Step s = step;
      controller.drawBoxAlpha(7, 7, 300, 63, 0x228B22, 128);
      controller.drawString("@yel@GetMeToArdougne @whi@by @yel@Dvorak", 10, 21, 0xFFFFFF, 1);
      controller.drawString(
          "@whi@Detected: @cya@" + (s == null ? "-" : s.text), 10, 35, 0xFFFFFF, 1);
      controller.drawString("@whi@" + status, 10, 49, 0xFFFFFF, 1);
      controller.drawString("@whi@Boat waits: @yel@" + boatWaits, 10, 63, 0xFFFFFF, 1);
    }
  }
}
