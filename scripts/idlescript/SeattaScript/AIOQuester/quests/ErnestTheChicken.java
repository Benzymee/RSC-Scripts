package scripting.idlescript.SeattaScript.AIOQuester.quests;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import models.entities.ItemId;
import models.entities.Location;
import models.entities.NpcId;
import orsc.ORSCharacter;
import scripting.idlescript.SeattaScript.AIOQuester.DraynorManor;
import scripting.idlescript.SeattaScript.AIOQuester.LeverBasement;
import scripting.idlescript.SeattaScript.AIOQuester.QuestHandler;
import scripting.idlescript.SeattaScript.AIOQuester.QuestWalker;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuitReason;
import scripting.idlescript.SeattaScript.AIOQuester.pathfinding.CollisionMap;

/**
 * Ernest the Chicken (1 star, free). Rewritten to decide every step from the quest stage, the
 * inventory and where the player stands, so it can start (or restart) anywhere in the quest.
 *
 * <p>Object/NPC ids, coordinates and door rules are from the OpenRSC server data
 * (SceneryLocs/BoundaryLocs/GroundItems, ErnestTheChicken.java and DoorAction.java):
 *
 * <ul>
 *   <li>Front door (boundary 36 at 210,553) only opens from outside (y >= 553). The way out is the
 *       back door (boundary 37 at 199,551, opens both ways) on the west side; QuestWalker never
 *       plans through the front door from inside.
 *   <li>Floors: ground 0, first floor +944, top floor +1888, basement +2832. Stairs 210,547 (up) /
 *       210,1491 (down), ladders 215,1492 (up) / 215,2436 (down), basement ladder 223,553 (down) /
 *       223,3385 (up, resets the levers).
 *   <li>Basement: six levers (A-F) and nine one-way locked doors; solved at runtime with a search
 *       over (room, lever positions), each pull read back from the server message.
 * </ul>
 *
 * After the quest completes it walks out of the manor and keeps the 300 coins (not banked).
 */
public final class ErnestTheChicken extends QuestHandler {
  private static final int FLOOR = QuestWalker.FLOOR_HEIGHT;

  // ---- manor objects
  private static final int[] FRONT_DOOR = {210, 553}; // outside tile is 210,553, inside 210,552
  private static final int[] BACK_DOOR = {199, 551}; // inside tile 199,551, outside 199,550
  private static final int[] BACK_DOOR_OUTSIDE = {199, 548};
  private static final int[] OUTSIDE_FRONT = {211, 560}; // garden in front of the manor
  private static final int[] CLOSET_DOOR = {212, 545}; // corridor side 212,545, closet 211,545
  private static final int[] STAIRS_UP = {210, 547};
  private static final int[] STAIRS_UP_APPROACH = {210, 550};
  private static final int[] STAIRS_DOWN = {210, 1491};
  private static final int[] STAIRS_DOWN_APPROACH = {210, 1490};
  private static final int[] LADDER_UP_F1 = {215, 1492};
  private static final int[] LADDER_UP_F1_APPROACH = {215, 1493};
  private static final int[] LADDER_DOWN_F2 = {215, 2436};
  private static final int[] LADDER_DOWN_F2_APPROACH = {215, 2437};
  private static final int[] LADDER_DOWN_BASEMENT = {223, 553};
  private static final int[] LADDER_DOWN_BASEMENT_APPROACH = {223, 554};
  private static final int[] LADDER_UP_BASEMENT = {223, 3385};
  private static final int[] COMPOST_HEAP = {230, 552};
  private static final int[] COMPOST_APPROACH = {229, 552};
  private static final int[] FOUNTAIN = {226, 565};
  private static final int[] FOUNTAIN_APPROACH = {225, 564};
  private static final int[] ODDENSTEIN_ROOM = {211, 2441};
  private static final int[] VERONICA_SPOT = {209, 561};

  // ---- item spawns (GroundItems.json)
  private static final int[] FISH_FOOD_SPAWN = {212, 1497};
  private static final int[] POISON_SPAWN = {221, 546};
  private static final int[] SPADE_SPAWN = {197, 554};
  private static final int[] RUBBER_TUBE_SPAWN = {208, 546};
  private static final int[] OIL_CAN_SPAWN = {232, 3382};

  // ---- ids
  private static final int FISH_FOOD_ID = ItemId.FISH_FOOD.getId();
  private static final int PRESSURE_GAUGE_ID = ItemId.PRESSURE_GAUGE.getId();
  private static final int RUBBER_TUBE_ID = ItemId.RUBBER_TUBE.getId();
  private static final int SPADE_ID = ItemId.SPADE.getId();
  private static final int CLOSET_KEY_ID = ItemId.CLOSET_KEY.getId();
  private static final int OIL_CAN_ID = ItemId.OIL_CAN.getId();
  private static final int POISON_ID = ItemId.POISON.getId();
  private static final int POISON_FOOD_ID = ItemId.POISONED_FISH_FOOD.getId();
  private static final int COINS_ID = ItemId.COINS.getId();
  private static final int VERONICA_ID = NpcId.VERONICA.getId();
  private static final int ODDENSTEIN_ID = NpcId.PROFESSOR_ODDENSTEIN.getId();

  // ---- dialogue (server ErnestTheChicken.java multi() texts)
  private static final String[] VERONICA_START = {"Aha, sounds like a quest. I'll help"};
  private static final String[] ODDENSTEIN_STAGE_ONE = {
    "I'm looking for a guy called Ernest", "Change him back this instant"
  };

  // =============================================================================================
  // Basement lever puzzle: LeverBasement (belief over the six levers, works from any room/state)
  // =============================================================================================

  private static final int R_LADDER = LeverBasement.R_LADDER, R_OIL = LeverBasement.R_OIL;
  private static final int MAX_MISSING_ROUNDS = 6;
  private int missingRounds = 0;
  private LeverBasement levers;

  private LeverBasement levers() {
    if (levers == null) levers = new LeverBasement(c, walker);
    return levers;
  }

  @Override
  public void serverMessageInterrupt(String message) {
    LeverBasement.onServerMessage(message); // also fed by QuestHandler; idempotent
  }

  private int currentRoom() {
    return LeverBasement.roomAt(c.currentX(), c.currentY());
  }

  private boolean basementGoTo(int targetRoom) {
    CURRENT_QUEST_STEP = "Lever puzzle: to the " + LeverBasement.ROOM_NAMES[targetRoom];
    return levers().goToRoom(targetRoom);
  }

  // =============================================================================================
  // Manor navigation
  // =============================================================================================

  private static int floorOf(int y) {
    return y / FLOOR;
  }

  private static Set<Long> manorInside;

  private static long key(int x, int y) {
    return ((long) x << 32) | (y & 0xffffffffL);
  }

  /** Ground-floor tiles inside the manor (flood fill from the hall with all doors shut). */
  private static synchronized Set<Long> manorTiles() {
    if (manorInside != null) return manorInside;
    Set<Long> seen = new HashSet<>();
    CollisionMap m = CollisionMap.get();
    if (m != null) {
      ArrayDeque<int[]> q = new ArrayDeque<>();
      q.add(new int[] {210, 550});
      seen.add(key(210, 550));
      while (!q.isEmpty()) {
        int[] p = q.poll();
        for (int dx = -1; dx <= 1; dx++)
          for (int dy = -1; dy <= 1; dy++) {
            if (dx == 0 && dy == 0) continue;
            int x = p[0] + dx, y = p[1] + dy;
            if (x < 190 || x > 235 || y < 535 || y > 560) continue;
            if (seen.contains(key(x, y)) || !m.isValidStep(p[0], p[1], dx, dy, false)) continue;
            seen.add(key(x, y));
            q.add(new int[] {x, y});
          }
      }
      // the closet is behind a locked door: inside too
      for (int x = 208; x <= 211; x++) for (int y = 545; y <= 548; y++) seen.add(key(x, y));
    }
    if (seen.size() < 50) { // no collision data: the interior's bounding box
      seen.clear();
      for (int x = 197; x <= 224; x++) for (int y = 542; y <= 555; y++) seen.add(key(x, y));
    }
    manorInside = seen;
    return seen;
  }

  private boolean insideManorGround() {
    return floorOf(c.currentY()) == 0 && manorTiles().contains(key(c.currentX(), c.currentY()));
  }

  /** On any manor floor above/below ground, or inside on the ground floor. */
  private boolean inManor() {
    int f = floorOf(c.currentY());
    int x = c.currentX(), y = c.currentY() % FLOOR;
    if (f == 1 || f == 2) return x >= 195 && x <= 226 && y >= 535 && y <= 560;
    if (f == 3) return DraynorManor.inManor(x, c.currentY());
    return insideManorGround();
  }

  private boolean inCrypt() {
    return DraynorManor.areaOf(c.currentX(), c.currentY()) == DraynorManor.Area.CRYPT;
  }

  private void walkOrRetry(int x, int y, int tol) {
    if (!walkTo(x, y, tol)) {
      c.sleep(640);
      if (!walkTo(x, y, tol)) walkFailed(x, y);
    }
  }

  /** Gets onto the manor's ground floor, inside. */
  private void toGroundInside() {
    for (int i = 0; i < 12 && running(); i++) {
      int f = floorOf(c.currentY());
      if (f == 2) {
        CURRENT_QUEST_STEP = "Climbing down from the top floor";
        walkOrRetry(LADDER_DOWN_F2_APPROACH[0], LADDER_DOWN_F2_APPROACH[1], 1);
        climb(LADDER_DOWN_F2);
      } else if (f == 1) {
        CURRENT_QUEST_STEP = "Going down the stairs";
        walkOrRetry(STAIRS_DOWN_APPROACH[0], STAIRS_DOWN_APPROACH[1], 1);
        climb(STAIRS_DOWN);
      } else if (f == 3) {
        CURRENT_QUEST_STEP = "Leaving the basement";
        if (inCrypt()) walker.manorGoTo(DraynorManor.Area.GROUND);
        else if (!levers().climbOut()) walkFailed(LADDER_UP_BASEMENT[0], LADDER_UP_BASEMENT[1]);
      } else if (!insideManorGround()) {
        enterManor();
      } else return;
      c.sleep(640);
    }
  }

  /** From outside: through the grounds and the front door (which opens from outside only). */
  private void enterManor() {
    CURRENT_QUEST_STEP = "Entering Draynor Manor";
    if (insideManorGround()) return;
    walkOrRetry(FRONT_DOOR[0], FRONT_DOOR[1], 0); // in front of the door, outside
    for (int i = 0; i < 4 && running() && !insideManorGround(); i++) {
      c.atWallObject(FRONT_DOOR[0], FRONT_DOOR[1]);
      waitFor(this::insideManorGround, 6000);
    }
    if (!insideManorGround()) walkFailed(FRONT_DOOR[0], FRONT_DOOR[1] - 1);
  }

  /** Leaves the manor from any floor through the back door; ends outside on the ground floor. */
  private void exitManor() {
    if (!inManor()) return;
    toGroundInside();
    if (!insideManorGround()) return;
    CURRENT_QUEST_STEP = "Leaving the manor by the back door";
    // QuestWalker plans out through the back door (it never uses the front door from inside)
    if (!walkTo(BACK_DOOR_OUTSIDE[0], BACK_DOOR_OUTSIDE[1], 1) || insideManorGround()) {
      c.log("Ernest: walking out failed, using the back door directly", "yel");
      walkOrRetry(BACK_DOOR[0], BACK_DOOR[1], 0);
      for (int i = 0; i < 4 && running() && insideManorGround(); i++) {
        c.atWallObject(BACK_DOOR[0], BACK_DOOR[1]);
        waitFor(() -> !insideManorGround(), 6000);
      }
      if (insideManorGround()) walkFailed(BACK_DOOR_OUTSIDE[0], BACK_DOOR_OUTSIDE[1]);
      walkTo(BACK_DOOR_OUTSIDE[0], BACK_DOOR_OUTSIDE[1], 1);
    }
  }

  /** Walks to (x,y) on the manor's floors, using the right stairs/ladders. */
  private void manorGo(int x, int y, int tol) {
    int tf = floorOf(y);
    for (int i = 0; i < 12 && running(); i++) {
      int f = floorOf(c.currentY());
      if (f == tf) {
        if (tf == 0) {
          boolean targetInside = manorTiles().contains(key(x, y));
          if (targetInside && !insideManorGround()) enterManor();
          else if (!targetInside && insideManorGround()) exitManor();
        }
        walkOrRetry(x, y, tol);
        return;
      }
      if (f == 3 || (f > tf && f != 3) || (tf == 3 && f != 0)) {
        // go down from upstairs / up from the basement towards the ground floor
        toGroundInside();
        if (tf == 0) continue;
      }
      f = floorOf(c.currentY());
      if (f == 0 && tf != 0) {
        if (!insideManorGround()) enterManor();
        if (tf == 3) {
          CURRENT_QUEST_STEP = "Climbing down into the basement";
          walkOrRetry(LADDER_DOWN_BASEMENT_APPROACH[0], LADDER_DOWN_BASEMENT_APPROACH[1], 1);
          climb(LADDER_DOWN_BASEMENT);
          // levers are as the server last had them (all up if we climbed out last time)
        } else {
          CURRENT_QUEST_STEP = "Going up the stairs";
          walkOrRetry(STAIRS_UP_APPROACH[0], STAIRS_UP_APPROACH[1], 1);
          climb(STAIRS_UP);
        }
      } else if (f == 1 && tf == 2) {
        CURRENT_QUEST_STEP = "Climbing up to the top floor";
        walkOrRetry(LADDER_UP_F1_APPROACH[0], LADDER_UP_F1_APPROACH[1], 1);
        climb(LADDER_UP_F1);
      }
      c.sleep(640);
    }
    if (floorOf(c.currentY()) != tf) walkFailed(x, y);
  }

  /** Picks up an item at its spawn (waits for the respawn), bounded. */
  private boolean takeItem(int itemId, int[] spawn, String what) {
    if (c.getInventoryItemCount(itemId) > 0) return true;
    CURRENT_QUEST_STEP = "Getting the " + what;
    if (c.getInventoryItemCount() >= 30) {
      c.log("Ernest: inventory full, can't pick up the " + what, "red");
      quit(QuitReason.INVENTORY_FULL);
      return false;
    }
    manorGo(spawn[0], spawn[1], 1);
    long until = System.currentTimeMillis() + 180_000;
    while (running() && c.getInventoryItemCount(itemId) == 0 && System.currentTimeMillis() < until) {
      int[] at = c.getNearestItemById(itemId);
      if (at != null) {
        c.pickupItem(at[0], at[1], itemId, true, true);
        waitFor(() -> c.getInventoryItemCount(itemId) > 0, 5000);
      } else {
        CURRENT_QUEST_STEP = "Waiting for the " + what + " to respawn";
        c.sleep(1280);
      }
    }
    return c.getInventoryItemCount(itemId) > 0;
  }

  // =============================================================================================
  // Quest steps
  // =============================================================================================

  private void talkToOddenstein(String[] choices) {
    manorGo(ODDENSTEIN_ROOM[0], ODDENSTEIN_ROOM[1], 2);
    CURRENT_QUEST_STEP = "Talking to Professor Oddenstein";
    if (choices == null) talkToNpcId(ODDENSTEIN_ID);
    else followNPCDialog(ODDENSTEIN_ID, choices);
  }

  /** Gets the pressure gauge: poisoned fish food into the fountain, then search it. */
  private void getPressureGauge() {
    if (c.getInventoryItemCount(PRESSURE_GAUGE_ID) > 0) return;
    if (c.getInventoryItemCount(POISON_FOOD_ID) == 0) {
      if (!takeItem(FISH_FOOD_ID, FISH_FOOD_SPAWN, "fish food")) return;
      if (!takeItem(POISON_ID, POISON_SPAWN, "poison")) return;
      CURRENT_QUEST_STEP = "Poisoning the fish food";
      for (int i = 0; i < 3 && running() && c.getInventoryItemCount(POISON_FOOD_ID) == 0; i++) {
        c.useItemOnItemBySlot(
            c.getInventoryItemSlotIndex(POISON_ID), c.getInventoryItemSlotIndex(FISH_FOOD_ID));
        waitFor(() -> c.getInventoryItemCount(POISON_FOOD_ID) > 0, 4000);
      }
    }
    CURRENT_QUEST_STEP = "Poisoning the fountain's piranhas";
    manorGo(FOUNTAIN_APPROACH[0], FOUNTAIN_APPROACH[1], 1);
    if (c.getInventoryItemCount(POISON_FOOD_ID) > 0) {
      c.useUnnotedItemIdOnObject(FOUNTAIN[0], FOUNTAIN[1], POISON_FOOD_ID);
      waitFor(() -> c.getInventoryItemCount(POISON_FOOD_ID) == 0, 10000);
      c.sleep(4000); // "The pirhanas drop dead and float to the surface"
    }
    CURRENT_QUEST_STEP = "Searching the fountain";
    for (int i = 0; i < 6 && running() && c.getInventoryItemCount(PRESSURE_GAUGE_ID) == 0; i++) {
      c.atObject2(FOUNTAIN[0], FOUNTAIN[1]); // "Search"
      waitFor(() -> c.getInventoryItemCount(PRESSURE_GAUGE_ID) > 0, 5000);
    }
  }

  /** Gets the rubber tube from the closet (key from the compost heap, dug up with a spade). */
  private void getRubberTube() {
    if (c.getInventoryItemCount(RUBBER_TUBE_ID) > 0) return;
    if (c.getInventoryItemCount(CLOSET_KEY_ID) == 0) {
      if (!takeItem(SPADE_ID, SPADE_SPAWN, "spade")) return;
      CURRENT_QUEST_STEP = "Digging the compost heap for the key";
      manorGo(COMPOST_APPROACH[0], COMPOST_APPROACH[1], 1);
      for (int i = 0; i < 4 && running() && c.getInventoryItemCount(CLOSET_KEY_ID) == 0; i++) {
        c.useUnnotedItemIdOnObject(COMPOST_HEAP[0], COMPOST_HEAP[1], SPADE_ID);
        waitFor(() -> c.getInventoryItemCount(CLOSET_KEY_ID) > 0, 8000);
      }
      if (c.getInventoryItemCount(CLOSET_KEY_ID) == 0) return;
      if (c.getInventoryItemCount(SPADE_ID) > 0) {
        c.dropItem(c.getInventoryItemSlotIndex(SPADE_ID));
        c.sleep(1280);
      }
    }
    CURRENT_QUEST_STEP = "Unlocking the closet";
    manorGo(CLOSET_DOOR[0], CLOSET_DOOR[1], 0);
    for (int i = 0; i < 4 && running() && c.currentX() >= CLOSET_DOOR[0]; i++) {
      c.useItemOnWall(CLOSET_DOOR[0], CLOSET_DOOR[1], c.getInventoryItemSlotIndex(CLOSET_KEY_ID));
      waitFor(() -> c.currentX() < CLOSET_DOOR[0], 6000);
    }
    if (c.currentX() >= CLOSET_DOOR[0]) return;
    long until = System.currentTimeMillis() + 180_000;
    while (running() && c.getInventoryItemCount(RUBBER_TUBE_ID) == 0 && System.currentTimeMillis() < until) {
      CURRENT_QUEST_STEP = "Getting the rubber tube";
      int[] at = c.getNearestItemById(RUBBER_TUBE_ID);
      if (at != null) {
        c.pickupItem(at[0], at[1], RUBBER_TUBE_ID, true, true);
        waitFor(() -> c.getInventoryItemCount(RUBBER_TUBE_ID) > 0, 5000);
      } else c.sleep(1280);
    }
    leaveCloset();
  }

  /** Out of the closet (with the key; the server also lets you out from 211,545 without it). */
  private void leaveCloset() {
    if (!(c.currentX() >= 208 && c.currentX() <= 211 && c.currentY() >= 545 && c.currentY() <= 548))
      return;
    walkTo(CLOSET_DOOR[0] - 1, CLOSET_DOOR[1], 0);
    for (int i = 0; i < 4 && running() && c.currentX() < CLOSET_DOOR[0]; i++) {
      int slot = c.getInventoryItemSlotIndex(CLOSET_KEY_ID);
      if (slot >= 0) c.useItemOnWall(CLOSET_DOOR[0], CLOSET_DOOR[1], slot);
      else c.atWallObject(CLOSET_DOOR[0], CLOSET_DOOR[1]);
      waitFor(() -> c.currentX() >= CLOSET_DOOR[0], 6000);
    }
    if (c.getInventoryItemCount(CLOSET_KEY_ID) > 0) {
      c.dropItem(c.getInventoryItemSlotIndex(CLOSET_KEY_ID));
      c.sleep(1280);
    }
  }

  /** Gets the oil can: basement lever puzzle there and back. */
  private void getOilCan() {
    if (c.getInventoryItemCount(OIL_CAN_ID) > 0) return;
    if (floorOf(c.currentY()) != 3) manorGo(LADDER_UP_BASEMENT[0], LADDER_UP_BASEMENT[1] - 1, 1);
    if (floorOf(c.currentY()) != 3) return;
    if (!basementGoTo(R_OIL)) {
      c.log("Ernest: couldn't get to the oil can room (" + LeverBasement.describeLevers() + ")", "red");
      return;
    }
    long until = System.currentTimeMillis() + 180_000;
    while (running() && c.getInventoryItemCount(OIL_CAN_ID) == 0 && System.currentTimeMillis() < until) {
      CURRENT_QUEST_STEP = "Getting the oil can";
      int[] at = c.getNearestItemById(OIL_CAN_ID);
      if (at != null) {
        c.pickupItem(at[0], at[1], OIL_CAN_ID, true, true);
        waitFor(() -> c.getInventoryItemCount(OIL_CAN_ID) > 0, 5000);
      } else {
        walkTo(OIL_CAN_SPAWN[0], OIL_CAN_SPAWN[1], 1);
        c.sleep(1280);
      }
    }
    if (c.getInventoryItemCount(OIL_CAN_ID) > 0) levers().climbOut();
  }

  /** Makes room for the quest items (5 slots) at Draynor bank. */
  private void makeInventoryRoom() {
    if (c.getInventoryItemCount() <= 25) return;
    CURRENT_QUEST_STEP = "Banking to make inventory room";
    exitManor();
    walkTowards(Location.DRAYNOR_BANK);
    if (!openBankBounded()) return;
    for (int i = 0; i < 10 && running() && c.isInBank() && c.getInventoryItemCount() > 25; i++) {
      int[] ids = c.getInventoryItemIds();
      int id = -1;
      // last stack that isn't coins / a quest item / something the caller keeps (cabbages)
      for (int k = Math.min(ids.length, c.getInventoryItemCount()) - 1; k >= 0 && id < 0; k--)
        if (ids[k] >= 0 && !isKeptQuestItem(ids[k])) id = ids[k];
      if (id < 0) {
        c.log("Ernest: inventory full of items I mustn't bank; carrying on", "yel");
        break;
      }
      c.depositItem(id, c.getInventoryItemCount(id));
      c.sleep(1000);
    }
    c.closeBank();
    c.sleep(640);
  }

  /** Opens the bank with the nearest banker, bounded (Controller.openBank can wait forever). */
  private boolean openBankBounded() {
    for (int attempt = 0; attempt < 6 && running() && !c.isInBank(); attempt++) {
      ORSCharacter banker = c.getNearestNpcByIds(c.bankerIds, false);
      if (banker == null) {
        walkTowards(Location.DRAYNOR_BANK);
        c.sleep(1280);
        continue;
      }
      if ("Bank".equalsIgnoreCase(c.getNpcCommand1(banker.npcId))) c.npcCommand1(banker.serverIndex);
      else {
        c.talkToNpc(banker.serverIndex);
        if (waitFor(() -> c.isInOptionMenu(), 8000)) {
          int idx = 0;
          String[] opts = c.getOptionsMenuText();
          for (int i = 0; opts != null && i < Math.min(c.getOptionMenuCount(), opts.length); i++)
            if (opts[i] != null && opts[i].toLowerCase(Locale.ROOT).contains("access my bank")) idx = i;
          c.optionAnswer(idx);
        }
      }
      waitFor(() -> c.isInBank(), 10000);
    }
    return c.isInBank();
  }

  /**
   * Final step: out of the manor. The 300 coins reward stays in the inventory (it used to be banked
   * at Draynor, but callers like Thief2Ardougne need it carried for boat fares).
   */
  private void bankReward() {
    exitManor();
    CURRENT_QUEST_STEP = "Done: keeping the 300 coins reward";
    int coins = c.getInventoryItemCount(COINS_ID);
    c.log("Ernest the Chicken: done, carrying " + coins + " coins (reward not banked)", "gre");
    c.displayMessage("@gre@AIOQuester: @whi@Ernest the Chicken done, " + coins + " coins carried");
  }

  @Override
  public void run() {
    if (inManor()) {
      // Restarted somewhere in the manor (often upstairs): every stage works out its own way
      // from here, so don't walk out to the start location (Draynor bank) first.
      c.log("Ernest: starting inside the manor at " + DraynorManor.describe(c.currentX(), c.currentY()), "cya");
      hasWalkedToStart = true;
    }
    if (getQuestStage() == -1) {
      // Finished earlier (e.g. stopped before banking): make sure we're out of the manor
      if (inManor()) bankReward();
      quit(QuitReason.QUEST_COMPLETED);
      return;
    }
    while (isQuesting()) {
      switch (QUEST_STAGE) {
        case 0:
          makeInventoryRoom();
          CURRENT_QUEST_STEP = "Talking to Veronica";
          if (inManor()) exitManor();
          if (c.getNearestNpcById(VERONICA_ID, false) == null)
            walkOrRetry(VERONICA_SPOT[0], VERONICA_SPOT[1], 3);
          followNPCDialog(VERONICA_ID, VERONICA_START);
          sleepUntilQuestStageChanges();
          break;
        case 1:
          makeInventoryRoom();
          talkToOddenstein(ODDENSTEIN_STAGE_ONE);
          sleepUntilQuestStageChanges();
          break;
        case 2:
          STEP_ITEMS =
              new int[][] {
                {PRESSURE_GAUGE_ID, 1}, {OIL_CAN_ID, 1}, {RUBBER_TUBE_ID, 1},
              };
          leaveCloset(); // restarted inside the closet
          // Order keeps the walking short from the top floor: fish food (1st floor), poison and
          // spade (ground floor), out the back door to the compost heap and fountain, back in
          // through the front door for the closet, then the basement.
          getPressureGauge();
          getRubberTube();
          getOilCan();
          if (c.getInventoryItemCount(PRESSURE_GAUGE_ID) > 0
              && c.getInventoryItemCount(RUBBER_TUBE_ID) > 0
              && c.getInventoryItemCount(OIL_CAN_ID) > 0) {
            talkToOddenstein(null);
            if (waitFor(() -> getQuestStage() == -1, 30000)) {
              c.sleep(3000); // Ernest hands over the 300 coins at the end of the dialogue
              bankReward();
              quit(QuitReason.QUEST_COMPLETED);
              return;
            }
            sleepUntilQuestStageChanges();
          } else {
            missingRounds++;
            c.log(
                String.format(
                    "Ernest: still missing %s%s%s(round %d/%d, at %s)",
                    c.getInventoryItemCount(PRESSURE_GAUGE_ID) > 0 ? "" : "pressure gauge ",
                    c.getInventoryItemCount(RUBBER_TUBE_ID) > 0 ? "" : "rubber tube ",
                    c.getInventoryItemCount(OIL_CAN_ID) > 0 ? "" : "oil can ",
                    missingRounds, MAX_MISSING_ROUNDS,
                    DraynorManor.describe(c.currentX(), c.currentY())),
                "yel");
            if (missingRounds >= MAX_MISSING_ROUNDS) {
              // Don't spin forever (the old lever bug looped here for 20+ minutes): end the quest
              // so the queue/caller can fall back, leaving the manor first.
              c.log("Ernest: giving up after " + missingRounds + " rounds without the parts", "red");
              exitManor();
              quit(QuitReason.OBJECT_NOT_FOUND);
              return;
            }
            c.sleep(1280);
          }
          break;
        default:
          quit(QuitReason.QUEST_STAGE_NOT_IN_SWITCH);
          break;
      }
    }
  }

  @Override
  public void cleanupScript() {
    // no static state besides the cached manor tile set
  }
}
