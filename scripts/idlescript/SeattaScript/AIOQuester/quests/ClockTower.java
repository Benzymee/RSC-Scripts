package scripting.idlescript.SeattaScript.AIOQuester.quests;

import models.entities.ItemId;
import models.entities.NpcId;
import orsc.ORSCharacter;
import scripting.idlescript.SeattaScript.AIOQuester.QuestHandler;
import scripting.idlescript.SeattaScript.AIOQuester.QuestWalker;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuitReason;

/**
 * Clock Tower. No skill requirement. The four cogs are picked up one at a time and placed on the
 * pole that accepts that colour. The black cog is cooled with a bucket of water. The purple cog
 * is behind the rat cage: poison goes in the trough, then the east lever is pulled once while
 * both gates are still open.
 *
 * <p>Poles, from the local ClockTower plugin: blue 362 at 580,3470, red 363 at 582,1582, purple
 * 364 at 581,2525, black 365 at 581,639. Brother Kojo is at 580,637.
 */
public final class ClockTower extends QuestHandler {
  private static final int BLUE = ItemId.LARGE_COG_BLUE.getId();
  private static final int BLACK = ItemId.LARGE_COG_BLACK.getId();
  private static final int RED = ItemId.LARGE_COG_RED.getId();
  private static final int PURPLE = ItemId.LARGE_COG_PURPLE.getId();
  private static final int POISON = ItemId.RAT_POISON.getId();
  private static final int BUCKET = ItemId.BUCKET.getId();
  private static final int WATER = ItemId.BUCKET_OF_WATER.getId();
  private static final int KOJO = NpcId.BROTHER_KOJO.getId();

  private boolean blueDone;
  private boolean redDone;
  private boolean purpleDone;
  private boolean blackDone;
  private boolean poisoned;
  private boolean leverPulled;

  @Override
  public void run() {
    hasWalkedToStart = true;
    while (isQuesting()) {
      int stage = QUEST_STAGE;
      if (stage == 0) {
        CURRENT_QUEST_STEP = "Asking Brother Kojo for the cogs";
        goFloor(0);
        if (floor() != 0) continue;
        walker.walkTo(580, 637, 2, 0);
        runDialogue(KOJO, new String[] {"Ok old monk what can I do?"}, true, 2);
      } else if (stage == 1) {
        placeNext();
      } else {
        quit(QuitReason.QUEST_STAGE_NOT_IN_SWITCH);
        return;
      }
    }
  }

  private void placeNext() {
    if (carrying(BLUE, 1)) {
      CURRENT_QUEST_STEP = "Placing the blue cog";
      goFloor(3);
      if (floor() == 3 && useCog(BLUE, 580, 3470)) blueDone = true;
      return;
    }
    if (carrying(RED, 1)) {
      CURRENT_QUEST_STEP = "Placing the red cog";
      goFloor(1);
      if (floor() == 1 && useCog(RED, 582, 1582)) redDone = true;
      return;
    }
    if (carrying(PURPLE, 1)) {
      CURRENT_QUEST_STEP = "Placing the purple cog";
      goFloor(2);
      if (floor() == 2 && useCog(PURPLE, 581, 2525)) purpleDone = true;
      return;
    }
    if (carrying(BLACK, 1)) {
      CURRENT_QUEST_STEP = "Placing the black cog";
      goFloor(0);
      if (floor() == 0 && useCog(BLACK, 581, 639)) blackDone = true;
      return;
    }
    if (blueDone && redDone && purpleDone && blackDone) {
      CURRENT_QUEST_STEP = "Telling Brother Kojo the clock is fixed";
      goFloor(0);
      if (floor() != 0) return;
      walker.walkTo(580, 637, 2, 0);
      runDialogue(KOJO, new String[] {"I have replaced all the cogs"}, true, 2);
      return;
    }
    goFloor(3);
    if (floor() != 3) return;
    if (!blueDone) {
      CURRENT_QUEST_STEP = "Fetching the blue cog";
      takeBlueCog();
      return;
    }
    if (!redDone) {
      CURRENT_QUEST_STEP = "Fetching the red cog";
      takeCog(RED, 616, 3484);
      return;
    }
    if (!purpleDone) {
      CURRENT_QUEST_STEP = "Fetching the purple cog";
      if (!poisoned) {
        if (!carrying(POISON, 1)) {
          takeCog(POISON, 592, 3483);
          return;
        }
        walker.walkTo(591, 3474, 1, 0);
        c.useItemIdOnObject(591, 3474, POISON);
        c.sleep(1000);
        if (!carrying(POISON, 1)) poisoned = true;
        return;
      }
      if (!leverPulled) {
        walker.walkTo(594, 3478, 1, 0);
        c.atObject(594, 3478);
        c.sleep(2500);
        leverPulled = true;
        return;
      }
      atWallObject(583, 3476);
      takeCog(PURPLE, 579, 3474);
      return;
    }
    CURRENT_QUEST_STEP = "Cooling the black cog";
    if (!carrying(WATER, 1)) {
      fillBucket();
      return;
    }
    walker.walkTo(609, 3465, 1, 0);
    c.useItemOnGroundItem(609, 3465, WATER, BLACK);
    c.sleep(1200);
  }

  private void fillBucket() {
    if (carrying(WATER, 1)) return;
    if (floor() != 0 && floor() != 3) {
      goFloor(0);
      return;
    }
    if (!carrying(BUCKET, 1)) {
      if (floor() != 0) {
        goFloor(0);
        return;
      }
      if (coins() < 20) {
        gatherCoins(20);
        return;
      }
      walker.walkTo(577, 586, 2, 0);
      ORSCharacter shop =
          c.getNearestNPCByLambda(
              n -> {
                String name = c.getNpcName(n.npcId);
                return name != null
                    && (name.equalsIgnoreCase("Shopkeeper")
                        || name.equalsIgnoreCase("Shop assistant"));
              });
      if (shop != null) {
        buyBaseStock(new int[] {shop.npcId}, BUCKET, 2, 1);
        return;
      }
      waitPolitely("an East Ardougne shopkeeper");
      return;
    }
    if (floor() != 0) {
      goFloor(0);
      return;
    }
    walker.walkTo(579, 640, 1, 0);
    useCarriedItemOnObject(579, 640, BUCKET);
    c.sleepUntil(() -> carrying(WATER, 1), 3000);
  }

  private void takeCog(int id, int x, int y) {
    walker.walkTo(x, y, 1, 0);
    int[] item = c.getNearestItemById(id);
    if (item == null) {
      waitPolitely("the cog");
      return;
    }
    c.pickupItem(item[0], item[1], id, false, true);
    c.sleep(800);
  }

  /**
   * Dir-1 (vertical) Odd looking wall 22 at 584,3457. Push from the corridor tile 584,3457, take
   * cog 727 at 583,3456, then push once more and confirm 584,3457. QuestWalker treats the wall as
   * solid so it never plans through it.
   */
  private void takeBlueCog() {
    if (carrying(BLUE, 1)) {
      leaveBluePocket();
      return;
    }
    int[] item = c.getNearestItemById(BLUE);
    if (item != null
        && Math.max(Math.abs(item[0] - c.currentX()), Math.abs(item[1] - c.currentY())) <= 1) {
      c.pickupItem(item[0], item[1], BLUE, false, true);
      c.sleep(800);
      leaveBluePocket();
      return;
    }
    if (inBluePocket()) {
      item = c.getNearestItemById(BLUE);
      if (item == null) {
        blueDone = true;
        leaveBluePocket();
        return;
      }
      takeCog(BLUE, 583, 3456);
      leaveBluePocket();
      return;
    }
    if (Math.max(Math.abs(c.currentX() - 584), Math.abs(c.currentY() - 3457)) > 8) {
      walker.walkTo(583, 3458, 2, 0);
    }
    c.log(
        "AIOQuester: pushing the Odd looking wall at 584,3457 from "
            + c.currentX()
            + ","
            + c.currentY(),
        "yel");
    noteRepeatedAction("push wall 584,3457 in");
    c.atWallObject(584, 3457);
    c.sleepUntil(() -> c.currentX() == 583 && c.currentY() == 3457, 3000);
    if (!inBluePocket()) return;
    takeCog(BLUE, 583, 3456);
    leaveBluePocket();
  }

  private boolean inBluePocket() {
    return c.currentX() == 583 && (c.currentY() == 3456 || c.currentY() == 3457);
  }

  private void leaveBluePocket() {
    if (c.currentX() == 584 && c.currentY() == 3457) return;
    if (c.currentX() == 583 && c.currentY() == 3456) {
      c.walkToAsync(583, 3457, 0);
      c.sleepUntil(() -> c.currentX() == 583 && c.currentY() == 3457, 3000);
    }
    if (c.currentX() == 583 && c.currentY() == 3457) {
      c.log("AIOQuester: pushing the Odd looking wall to leave the blue cog pocket", "yel");
      noteRepeatedAction("push wall 584,3457 out");
      c.atWallObject(584, 3457);
      c.sleepUntil(() -> c.currentX() == 584 && c.currentY() == 3457, 3000);
    }
  }

  private boolean useCog(int id, int x, int y) {
    walker.walkTo(x, y, 1, 0);
    useCarriedItemOnObject(x, y, id);
    c.sleep(1000);
    boolean done =
        !carrying(id, 1)
            || gameMessageContains("fits perfectly")
            || gameMessageContains("already placed");
    if (done) markCogOnThisFloor();
    return done;
  }

  private int floor() {
    return QuestWalker.floor(c.currentY());
  }

  /** Floor 3 is the cellar (y ~3300), below ground. Map it as level -1 so we climb down to it. */
  private static int level(int f) {
    return f == 3 ? -1 : f;
  }

  private void goFloor(int want) {
    int f = floor();
    if (f == want) return;
    if (level(want) < level(f)) {
      if (f == 2) climbTry(578, 2525);
      else if (f == 1) climbTry(578, 1580);
      else if (f == 0) climbTry(578, 637);
      else climbTry(578, 3469);
    } else {
      if (f == 3) climbTry(578, 3469);
      else if (f == 0) climbTry(578, 636);
      else if (f == 1) climbTry(578, 1581);
      else climbTry(578, 2525);
    }
  }

  private void climbTry(int x, int y) {
    walker.climb(x, y, false, 3);
  }

  private void markCogOnThisFloor() {
    int f = floor();
    if (f == 3) blueDone = true;
    else if (f == 1) redDone = true;
    else if (f == 2) purpleDone = true;
    else if (f == 0) blackDone = true;
  }

  private void noteCogMessage(String message) {
    if (message == null) return;
    String lower = message.toLowerCase();
    if (lower.contains("fits perfectly") || lower.contains("already placed a cog")) {
      markCogOnThisFloor();
    }
  }

  @Override
  public void serverMessageInterrupt(String message) {
    noteCogMessage(message);
  }

  @Override
  public void questMessageInterrupt(String message) {
    noteCogMessage(message);
  }
}
