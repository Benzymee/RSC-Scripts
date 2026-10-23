package scripting.idlescript.SeattaScript.AIOQuester.quests;

import models.entities.ItemId;
import models.entities.NpcId;
import orsc.ORSCharacter;
import scripting.idlescript.SeattaScript.AIOQuester.DraynorManor;
import scripting.idlescript.SeattaScript.AIOQuester.QuestHandler;
import scripting.idlescript.SeattaScript.AIOQuester.QuestWalker;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuitReason;

/**
 * Cook's Assistant, checked against the OpenRSC server (plugin CooksAssistant, Hopper, PotFlour,
 * GeneralStore and the NpcLocs / GroundItems / SceneryLocs data):
 *
 * <ul>
 *   <li>Cook = npc 7, spawns 135,661 and wanders 131-137 x 659-665 (castle kitchen, double door
 *       at 128,658). Stage 0: "What's wrong?" then "Yes, I'll help you" -> stage 1. Stage 1 with
 *       egg (19), milk (22) and pot of flour (136) carried: plain talk hands them in -> stage -1.
 *   <li>Pot (135): ground spawn on the kitchen table at 134,660 (respawn 25). Taken from an
 *       adjacent tile. Fallback: Lumbridge general store (shopkeeper 55 / assistant 83), 3 in
 *       stock.
 *   <li>Bucket (21): ground spawn 121,607 (farm house by the chickens). Fallback: general store.
 *   <li>Egg (19): ground spawns 119,603 and 122,603 (chicken pen).
 *   <li>Milk: use bucket on a cow (npc 6), field ~89-115 x 601-633.
 *   <li>Flour: pick wheat (object 72, "pick" = 2nd option) in the field behind the gate at
 *       172,607; climb the mill ladders 165,598 -> 166,1546; use grain on the hopper 166,2487
 *       ("There is already grain in the hopper" if someone else filled it) and "operate" it;
 *       flour (ground item 23) appears on the chute at 166,599 on the ground floor and is taken
 *       with a pot -> pot of flour (136).
 * </ul>
 *
 * Every loop re-decides the step from the quest stage, the inventory and where we are ({@link
 * #decide}), so it resumes from any stage, inventory, floor or half-finished conversation. Waiting
 * for the busy Cook or for a shared spawn other accounts took is never a stall. Genuine
 * no-progress for 60 s in a step re-paths to the step's anchor tile (doors reopened) and retries.
 */
public final class CooksAssistant extends QuestHandler {
  // ---- ids (OpenRSC)
  static final int COOK_ID = NpcId.COOK.getId(); // 7
  static final int COW_ID = NpcId.COW_ATTACKABLE.getId(); // 6
  static final int SHOPKEEPER_ID = NpcId.SHOPKEEPER_LUMBRIDGE.getId(); // 55
  static final int SHOP_ASSISTANT_ID = NpcId.SHOP_ASSISTANT_LUMBRIDGE.getId(); // 83
  static final int POT_ID = ItemId.POT.getId(); // 135
  static final int POT_OF_FLOUR_ID = ItemId.POT_OF_FLOUR.getId(); // 136
  static final int FLOUR_ID = ItemId.FLOUR.getId(); // 23 (ground flour on the chute)
  static final int BUCKET_ID = ItemId.BUCKET.getId(); // 21
  static final int MILK_ID = ItemId.MILK.getId(); // 22
  static final int EGG_ID = ItemId.EGG.getId(); // 19
  static final int GRAIN_ID = ItemId.GRAIN.getId(); // 29
  static final int COINS_ID = ItemId.COINS.getId();

  // ---- places
  static final int[] POT_TABLE = {134, 660};
  // Tiles next to the table (all reachable through the kitchen door); a random one is used so
  // many accounts don't stack on one tile
  static final int[][] POT_STAND = {{133, 660}, {135, 660}, {134, 659}, {134, 661}};
  static final int[] KITCHEN = {134, 662}; // kitchen anchor (inside the door at 128,658)
  static final int[] COOK_SPAWN = {135, 661};
  static final int[] BUCKET_SPAWN = {121, 607};
  static final int[][] EGG_SPAWNS = {{119, 603}, {122, 603}};
  static final int[] COW_FIELD = {103, 614};
  static final int[][] COW_SPOTS = {{103, 614}, {108, 608}, {98, 608}, {99, 625}};
  static final int[] WHEAT_FIELD = {173, 602};
  static final int[] MILL_DOOR_SIDE = {166, 600}; // inside the mill, ground floor
  static final int[] MILL_LADDER_UP_0 = {165, 598};
  static final int[] MILL_LADDER_UP_1 = {166, 1546};
  static final int[] MILL_LADDER_DOWN_1 = {165, 1542};
  static final int[] MILL_LADDER_DOWN_2 = {166, 2490};
  static final int[] HOPPER = {166, 2487};
  static final int[] CHUTE_FLOUR = {166, 599};
  static final int[] CHUTE_STAND = {166, 598};
  static final int[] GENERAL_STORE = {133, 641};

  static final long JAM_MS = 60_000;
  static final long SPAWN_FALLBACK_MS = 180_000; // then buy the pot/bucket if coins allow

  /** What to do next. */
  enum Step {
    COMPLETE("quest complete"),
    LEAVE_UPSTAIRS("going back down to the ground floor"),
    START_QUEST("starting the quest with the Cook"),
    GET_POT("getting a pot from the kitchen table"),
    GET_BUCKET("getting a bucket"),
    GET_EGG("getting an egg"),
    GET_MILK("milking a cow"),
    GET_GRAIN("picking wheat"),
    USE_HOPPER("milling the grain (hopper upstairs)"),
    COLLECT_FLOUR("collecting flour from the chute with the pot"),
    HAND_IN("handing in milk, egg and flour"),
    INVENTORY_FULL("inventory full");

    final String label;

    Step(String label) {
      this.label = label;
    }
  }

  /** Snapshot of everything the step decision uses (also built by the offline test). */
  static final class State {
    int stage;
    boolean pot, potOfFlour, bucket, milk, egg, grain;
    int freeSlots = 30;
    int x, y; // y includes the floor (floor = y / 944)
    boolean flourPending; // we operated the hopper and our flour should be on the chute
    boolean flourOnChute; // ground flour visible at the chute

    int floor() {
      return y / QuestWalker.FLOOR_HEIGHT;
    }

    boolean atMill() {
      int fx = x, fy = y % QuestWalker.FLOOR_HEIGHT;
      return fx >= 160 && fx <= 171 && fy >= 594 && fy <= 606;
    }
  }

  /**
   * The step for a state. Pure function: depends only on the state (unit tested offline).
   *
   * <p>Order when several things are missing: pot (kitchen) -> bucket -> egg -> milk (farm and cow
   * field west of the castle) -> wheat/mill (north-east) -> back to the Cook. Grain already held
   * or flour already on the chute is used first.
   */
  static Step decide(State s) {
    if (s.stage < 0) return Step.COMPLETE;
    // Upper floors: only the mill's top floor (hopper) is useful, and only with grain
    if (s.floor() > 0) {
      if (s.stage == 1 && !s.potOfFlour && s.grain && s.atMill()) return Step.USE_HOPPER;
      return Step.LEAVE_UPSTAIRS;
    }
    if (s.stage == 0) return Step.START_QUEST;
    if (s.egg && s.milk && s.potOfFlour) return Step.HAND_IN;
    if (!s.potOfFlour) {
      if (s.pot && (s.flourOnChute || s.flourPending)) return Step.COLLECT_FLOUR;
      if (s.grain && s.atMill()) return Step.USE_HOPPER;
    }
    // Things still to pick up need a free slot each
    int needSlots = 0;
    if (!s.potOfFlour && !s.pot) needSlots++;
    if (!s.milk && !s.bucket) needSlots++;
    if (!s.egg) needSlots++;
    if (!s.potOfFlour && !s.grain) needSlots++;
    if (needSlots > 0 && s.freeSlots <= 0) return Step.INVENTORY_FULL;
    if (!s.potOfFlour && !s.pot) return Step.GET_POT;
    if (!s.milk && !s.bucket) return Step.GET_BUCKET;
    if (!s.egg) return Step.GET_EGG;
    if (!s.milk) return Step.GET_MILK;
    // pot held, no pot of flour yet
    if (s.grain) return Step.USE_HOPPER;
    return Step.GET_GRAIN;
  }

  private boolean flourPending = false;
  private Step lastStep = null;
  private int lastStage = Integer.MIN_VALUE;
  private long potMissingSince = 0, bucketMissingSince = 0;

  private boolean has(int id) {
    return c.getInventoryItemCount(id) > 0;
  }

  private State readState() {
    State s = new State();
    s.stage = c.getQuestStage(QUEST.getId());
    s.pot = has(POT_ID);
    s.potOfFlour = has(POT_OF_FLOUR_ID);
    s.bucket = has(BUCKET_ID);
    s.milk = has(MILK_ID);
    s.egg = has(EGG_ID);
    s.grain = has(GRAIN_ID);
    s.freeSlots = 30 - c.getInventoryItemCount();
    s.x = c.currentX();
    s.y = c.currentY();
    s.flourPending = flourPending;
    int[] f = c.getNearestItemById(FLOUR_ID, 12);
    s.flourOnChute = f != null && f[0] == CHUTE_FLOUR[0] && f[1] == CHUTE_FLOUR[1];
    return s;
  }

  @Override
  public void run() {
    // Decide from where we are instead of walking to the start location first
    hasWalkedToStart = true;
    while (isQuesting()) {
      ownDetectionLog = true;
      closeStrayDialogue();
      State s = readState();
      Step step = decide(s);
      if (step != lastStep || s.stage != lastStage) {
        c.log(
            String.format(
                "AIOQuester: Detected: Cook's Assistant stage %d -> step %s (%s) at %d,%d floor %d",
                s.stage, step.name(), step.label, s.x, s.y, s.floor()),
            "cya");
        lastStep = step;
        lastStage = s.stage;
        markProgress();
      }
      CURRENT_QUEST_STEP = step.label;
      switch (step) {
        case COMPLETE:
          return; // isQuesting() ends the quest on stage -1
        case INVENTORY_FULL:
          c.log("AIOQuester: Cook's Assistant needs a free inventory slot", "red");
          quit(QuitReason.INVENTORY_FULL);
          return;
        case LEAVE_UPSTAIRS:
          leaveUpstairs(s);
          break;
        case START_QUEST:
          talkToCook(new String[] {"What's wrong?", "Yes, I'll help you"}, 0);
          break;
        case HAND_IN:
          talkToCook(new String[0], 1);
          break;
        case GET_POT:
          getPot();
          break;
        case GET_BUCKET:
          getBucket();
          break;
        case GET_EGG:
          getEgg();
          break;
        case GET_MILK:
          getMilk();
          break;
        case GET_GRAIN:
          getGrain();
          break;
        case USE_HOPPER:
          useHopper();
          break;
        case COLLECT_FLOUR:
          collectFlour();
          break;
        default:
          quit(QuitReason.QUEST_STAGE_NOT_IN_SWITCH);
          return;
      }
      checkJam(step);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Jam handling
  // ---------------------------------------------------------------------------------------------

  private int[] anchorFor(Step step) {
    switch (step) {
      case START_QUEST:
      case HAND_IN:
      case GET_POT:
        return KITCHEN;
      case GET_BUCKET:
        return BUCKET_SPAWN;
      case GET_EGG:
        return EGG_SPAWNS[0];
      case GET_MILK:
        return COW_FIELD;
      case GET_GRAIN:
        return WHEAT_FIELD;
      case USE_HOPPER:
      case COLLECT_FLOUR:
        return MILL_DOOR_SIDE;
      default:
        return null;
    }
  }

  /** Genuine no-progress for 60 s in a step: re-path to its anchor (reopening doors), retry. */
  private void checkJam(Step step) {
    if (!noProgressFor(JAM_MS)) return;
    int[] anchor = anchorFor(step);
    c.log(
        String.format(
            "AIOQuester: JAM in Cook's Assistant step %s: no progress for %ds at %d,%d; re-pathing to %s and retrying",
            step.name(),
            JAM_MS / 1000,
            c.currentX(),
            c.currentY(),
            anchor == null ? "the ground floor" : anchor[0] + "," + anchor[1]),
        "red");
    closeStrayDialogue();
    if (anchor != null && QuestWalker.floor(c.currentY()) == 0) {
      // Step off first (a tile can be blocked by a door that closed behind us), then re-plan
      c.walkTo(c.currentX() + jitter(3) - 1, c.currentY() + jitter(3) - 1);
      c.sleep(1200);
      walker.walkTo(anchor[0], anchor[1], 2, 90_000);
    }
    markProgress();
  }

  /** Leftover option menu from an interrupted conversation: answer nothing, just close it. */
  private void closeStrayDialogue() {
    if (c.isInOptionMenu()) {
      String[] opts = currentOptions();
      c.log("AIOQuester: closing a leftover dialogue menu [" + String.join(" | ", opts) + "]", "yel");
      hideOptionMenu();
      c.sleep(640);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Steps
  // ---------------------------------------------------------------------------------------------

  private boolean inKitchen() {
    int x = c.currentX(), y = c.currentY();
    return x >= 129 && x <= 137 && y >= 659 && y <= 665;
  }

  private void goKitchen() {
    if (inKitchen()) return;
    int[] t = POT_STAND[jitter(POT_STAND.length)];
    walker.walkTo(t[0], t[1], 1, 0);
  }

  /**
   * Talks to the Cook until the quest stage leaves fromStage. A busy Cook is waited for politely
   * (no cap, not a stall); a conversation that goes nowhere is retried with a pause.
   */
  private void talkToCook(String[] choices, int fromStage) {
    goKitchen();
    if (!inKitchen()) return; // walking failed; the jam check / next loop retries
    int[] ids = {COOK_ID};
    ORSCharacter cook = c.getNearestNpcByIds(ids, false);
    if (cook == null) {
      // He wanders 131-137 x 659-665; step towards his spawn and look again
      walker.walkTo(COOK_SPAWN[0], COOK_SPAWN[1], 2, 20_000);
      c.sleep(1000);
      cook = c.getNearestNpcByIds(ids, false);
      if (cook == null) {
        waitPolitely("the Cook to show up");
        return;
      }
    }
    // runDialogue handles "is busy at the moment" (waits, uncapped) and "can't reach"
    runDialogue(COOK_ID, choices, true, 1);
    if (waitFor(() -> c.getQuestStage(QUEST.getId()) != fromStage, 6000)) {
      markProgress();
      return;
    }
    if (fromStage == 1 && !(has(EGG_ID) && has(MILK_ID) && has(POT_OF_FLOUR_ID))) return;
    // No change: someone else's conversation, or he walked off mid-talk. Jittered pause, retry.
    c.sleep(1500 + jitter(2500));
  }

  /** Pot from the kitchen table; waits for the respawn if taken, buys one after 3 minutes. */
  private void getPot() {
    goKitchen();
    int[] pot = c.getNearestItemById(POT_ID, 8);
    if (pot != null) {
      potMissingSince = 0;
      boolean onTable = pot[0] == POT_TABLE[0] && pot[1] == POT_TABLE[1];
      if (onTable) {
        int[] stand = POT_STAND[jitter(POT_STAND.length)];
        if (Math.max(Math.abs(c.currentX() - POT_TABLE[0]), Math.abs(c.currentY() - POT_TABLE[1])) > 1)
          walker.walkTo(stand[0], stand[1], 0, 20_000);
      }
      // The take packet: the server walks next to the table and picks it up
      c.pickupItem(pot[0], pot[1], POT_ID, false, true);
      if (waitFor(() -> has(POT_ID), 3500)) {
        c.log("AIOQuester: got a pot", "gre");
        markProgress();
      }
      return;
    }
    if (potMissingSince == 0) potMissingSince = System.currentTimeMillis();
    if (System.currentTimeMillis() - potMissingSince > SPAWN_FALLBACK_MS && buyFromGeneralStore(POT_ID)) {
      potMissingSince = 0;
      return;
    }
    waitPolitely("the pot to respawn on the kitchen table");
  }

  private void getBucket() {
    if (dist(BUCKET_SPAWN) > 6) walker.walkTo(BUCKET_SPAWN[0], BUCKET_SPAWN[1], 2, 0);
    int[] b = c.getNearestItemById(BUCKET_ID, 10);
    if (b != null) {
      bucketMissingSince = 0;
      c.pickupItem(b[0], b[1], BUCKET_ID, true, true);
      if (waitFor(() -> has(BUCKET_ID), 6000)) markProgress();
      return;
    }
    if (bucketMissingSince == 0) bucketMissingSince = System.currentTimeMillis();
    if (System.currentTimeMillis() - bucketMissingSince > SPAWN_FALLBACK_MS && buyFromGeneralStore(BUCKET_ID)) {
      bucketMissingSince = 0;
      return;
    }
    waitPolitely("the bucket to respawn at 121,607");
  }

  private void getEgg() {
    int[] near = EGG_SPAWNS[dist(EGG_SPAWNS[0]) <= dist(EGG_SPAWNS[1]) ? 0 : 1];
    if (dist(near) > 6) walker.walkTo(near[0], near[1], 2, 0);
    int[] e = c.getNearestItemById(EGG_ID, 10);
    if (e != null) {
      c.pickupItem(e[0], e[1], EGG_ID, true, true);
      if (waitFor(() -> has(EGG_ID), 6000)) markProgress();
      return;
    }
    waitPolitely("an egg to respawn in the chicken pen");
  }

  private void getMilk() {
    // Tolerance 1: with a looser one the walker can stop outside the pen fence (near 106,616)
    // and every cow is then out of reach
    if (dist(COW_FIELD) > 14) walker.walkTo(COW_FIELD[0], COW_FIELD[1], 1, 0);
    ORSCharacter cow = c.getNearestNpcByIds(new int[] {COW_ID}, false); // not in combat
    if (cow == null) {
      int[] spot = COW_SPOTS[jitter(COW_SPOTS.length)];
      walker.walkTo(spot[0], spot[1], 3, 30_000);
      cow = c.getNearestNpcByIds(new int[] {COW_ID}, false);
      if (cow == null) {
        waitPolitely("a free cow");
        return;
      }
    }
    c.useItemOnNpc(cow.serverIndex, BUCKET_ID);
    if (waitFor(() -> has(MILK_ID), 8000)) {
      c.log("AIOQuester: got milk", "gre");
      markProgress();
    } else if (dist(COW_FIELD) > 2) {
      // Probably outside the fence (cow out of reach): go into the pen through the gate
      walker.walkTo(COW_FIELD[0], COW_FIELD[1], 1, 30_000);
    }
  }

  private void getGrain() {
    if (QuestWalker.floor(c.currentY()) == 0 && !inWheatField())
      walker.walkTo(WHEAT_FIELD[0], WHEAT_FIELD[1], 2, 0);
    int[] wheat = nearestWheat();
    if (wheat == null) return;
    c.atObject2(wheat[0], wheat[1]); // "pick"
    if (waitFor(() -> has(GRAIN_ID), 7000)) {
      c.log("AIOQuester: got grain", "gre");
      markProgress();
    }
  }

  private boolean inWheatField() {
    int x = c.currentX(), y = c.currentY();
    return x >= 171 && x <= 176 && y >= 596 && y <= 606;
  }

  private int[] nearestWheat() {
    int[] best = null;
    int bestD = Integer.MAX_VALUE;
    for (int x = 171; x <= 175; x++)
      for (int y = 597; y <= 605; y++) {
        if ((y == 604 || y == 605) && x < 172) continue; // no wheat there
        if (y == 605 && x > 173) continue;
        int d = Math.max(Math.abs(c.currentX() - x), Math.abs(c.currentY() - y));
        if (d < bestD) {
          bestD = d;
          best = new int[] {x, y};
        }
      }
    return best;
  }

  /** Up the mill, grain into the hopper, operate it, back down. Flour lands on the chute. */
  private void useHopper() {
    int floor = QuestWalker.floor(c.currentY());
    if (floor == 0) {
      if (dist(MILL_LADDER_UP_0) > 3) walker.walkTo(MILL_DOOR_SIDE[0], MILL_DOOR_SIDE[1], 1, 0);
      walker.climb(MILL_LADDER_UP_0[0], MILL_LADDER_UP_0[1], false, 3);
      return;
    }
    if (floor == 1) {
      walker.climb(MILL_LADDER_UP_1[0], MILL_LADDER_UP_1[1], false, 3);
      return;
    }
    if (floor == 2) {
      if (has(GRAIN_ID)) {
        long before = lastMessageTime;
        c.useItemIdOnObject(HOPPER[0], HOPPER[1], GRAIN_ID);
        waitFor(() -> !has(GRAIN_ID) || lastMessageTime != before, 4000);
        c.sleep(640);
      }
      c.atObject(HOPPER[0], HOPPER[1]); // "operate"
      c.sleep(2500);
      flourPending = true; // our (or someone's) flour is now on the chute downstairs
      markProgress();
      walker.climb(MILL_LADDER_DOWN_2[0], MILL_LADDER_DOWN_2[1], false, 3);
      if (QuestWalker.floor(c.currentY()) == 1)
        walker.climb(MILL_LADDER_DOWN_1[0], MILL_LADDER_DOWN_1[1], false, 3);
    }
  }

  private long flourWaitStart = 0;

  private void collectFlour() {
    if (QuestWalker.floor(c.currentY()) != 0) return; // LEAVE_UPSTAIRS first
    if (dist(CHUTE_STAND) > 2) walker.walkTo(CHUTE_STAND[0], CHUTE_STAND[1], 1, 0);
    int[] f = c.getNearestItemById(FLOUR_ID, 6);
    if (f != null) {
      flourWaitStart = 0;
      // The flour lies on the chute (an object): the server only hands it over from a tile next
      // to it, and the client can't path onto the chute itself, so step beside it first.
      if (!besideTile(f[0], f[1])) return; // the jam check re-paths if this keeps failing
      // Taking ground flour with a pot in the inventory gives a pot of flour
      c.pickupItem(f[0], f[1], FLOUR_ID, false, true);
      if (waitFor(() -> has(POT_OF_FLOUR_ID), 4000)) {
        c.log("AIOQuester: got a pot of flour", "gre");
        flourPending = false;
        markProgress();
      }
      return;
    }
    if (flourWaitStart == 0) flourWaitStart = System.currentTimeMillis();
    if (System.currentTimeMillis() - flourWaitStart > 20_000) {
      // Another account took it: mill another grain
      c.log("AIOQuester: no flour on the chute (taken by someone else?), milling again", "yel");
      flourPending = false;
      flourWaitStart = 0;
      return;
    }
    waitPolitely("flour on the mill chute");
  }

  /**
   * Walks onto a reachable tile next to (x,y) (straight sides first). True when already there.
   */
  private boolean besideTile(int x, int y) {
    int mx = c.currentX(), my = c.currentY();
    if (Math.max(Math.abs(mx - x), Math.abs(my - y)) <= 1 && !(mx == x && my == y)) return true;
    int[][] around = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}, {-1, -1}, {1, -1}, {-1, 1}, {1, 1}};
    int[] best = null;
    int bestD = Integer.MAX_VALUE;
    for (int[] d : around) {
      int tx = x + d[0], ty = y + d[1];
      if (!c.isReachable(tx, ty, false)) continue;
      int dd = Math.max(Math.abs(mx - tx), Math.abs(my - ty));
      if (dd < bestD) {
        bestD = dd;
        best = new int[] {tx, ty};
      }
    }
    if (best == null) best = CHUTE_STAND;
    final int bx = best[0], by = best[1];
    c.walkToAsync(bx, by, 0);
    waitFor(() -> c.currentX() == bx && c.currentY() == by, 5000);
    mx = c.currentX();
    my = c.currentY();
    return Math.max(Math.abs(mx - x), Math.abs(my - y)) <= 1 && !(mx == x && my == y);
  }

  private void leaveUpstairs(State s) {
    int floor = s.floor();
    if (s.atMill()) {
      if (floor == 2) walker.climb(MILL_LADDER_DOWN_2[0], MILL_LADDER_DOWN_2[1], false, 3);
      else if (floor == 1) walker.climb(MILL_LADDER_DOWN_1[0], MILL_LADDER_DOWN_1[1], false, 3);
      return;
    }
    if (DraynorManor.inManor(s.x, s.y)) {
      // e.g. left upstairs in Draynor Manor by Ernest the Chicken: the manor routes take us
      // down and out by the back door (the WebWalker graph would try Lumbridge's ladders)
      c.log("AIOQuester: in " + DraynorManor.describe(s.x, s.y) + ", leaving the manor", "yel");
      walker.manorGoTo(DraynorManor.Area.OUTSIDE);
      return;
    }
    // Elsewhere (castle upstairs, a basement...): the walker's graph knows the ladders/stairs
    c.log("AIOQuester: on floor " + floor + " at " + s.x + "," + s.y + ", walking back to the castle", "yel");
    walker.walkTo(KITCHEN[0], KITCHEN[1], 3, 120_000);
  }

  /** Buys one item from the Lumbridge general store if we have the coins (bounded waits). */
  private boolean buyFromGeneralStore(int itemId) {
    if (c.getInventoryItemCount(COINS_ID) < 5) {
      c.log("AIOQuester: not enough coins for the general store fallback; waiting for the spawn", "yel");
      return false;
    }
    if (c.getInventoryItemCount() >= 30) return false;
    c.log("AIOQuester: spawn gone for a while, buying item " + itemId + " at the Lumbridge general store", "yel");
    walker.walkTo(GENERAL_STORE[0], GENERAL_STORE[1], 3, 0);
    int base = itemId == POT_ID ? 3 : itemId == BUCKET_ID ? 2 : -1;
    if (base < 0) return false;
    return buyBaseStock(new int[] {SHOPKEEPER_ID, SHOP_ASSISTANT_ID}, itemId, base, 1) > 0;
  }

  private int dist(int[] t) {
    return Math.max(Math.abs(c.currentX() - t[0]), Math.abs(c.currentY() - t[1]));
  }
}
