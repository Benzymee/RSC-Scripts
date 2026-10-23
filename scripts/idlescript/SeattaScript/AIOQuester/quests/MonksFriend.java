package scripting.idlescript.SeattaScript.AIOQuester.quests;

import models.entities.ItemId;
import models.entities.NpcId;
import scripting.idlescript.SeattaScript.AIOQuester.QuestHandler;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuitReason;

public final class MonksFriend extends QuestHandler {
  private final int[] OUTSIDE_WAYPOINT = {589, 647};
  private final int[] INSIDE_MONESTARY = {589, 664};
  private final int BUCKET_OF_WATER = ItemId.BUCKET_OF_WATER.getId();
  private final int BLANKET = ItemId.BLANKET.getId();
  private final int LOGS = ItemId.LOGS.getId();
  private final int BUCKET = ItemId.BUCKET.getId();
  private final int BROTHER_OMAD = NpcId.BROTHER_OMAD.getId();
  private final int BROTHER_CEDRIC = NpcId.BROTHER_CEDRIC.getId();
  private final String[] BROTHER_OMAD_START_QUEST_DIALOG = {
    "Why can't you sleep, what's wrong?", "Can I help at all?"
  };
  private final String[] BROTHER_OMAD_PART_2 = {"who's brother Cedric?", "where should I look?"};
  private final String[] BROTHER_CEDRIC_PART_2 = {
    "Yes i'd be happy to",
  };

  public void run() {
    c.log("~ by Kaila", "mag");
    while (isQuesting()) {
      switch (QUEST_STAGE) { // 6 stages in total
        case 0: // not started
          if (running() && c.getInventoryItemCount(LOGS) == 0) {
            CURRENT_QUEST_STEP = "Getting logs";
            for (int i = 0; i < 40 && running() && c.getInventoryItemCount(LOGS) == 0; i++) {
              if (c.getObjectAtCoord(596, 655) == 1) { // chop tree
                walkTo(597, 655);
                c.atObject(596, 655);
              } else if (c.getObjectAtCoord(599, 655) == 1) { // chop tree2
                walkTo(598, 655);
                c.atObject(599, 655);
              }
              c.sleep(3000);
            }
          }
          if (running()) {
            CURRENT_QUEST_STEP = "Walking to Brother Omad";
            pathWalker(OUTSIDE_WAYPOINT);
            walkPath(new int[][] {INSIDE_MONESTARY}); // walk inside
          }
          if (running()) {
            CURRENT_QUEST_STEP = "Talking to Brother Omad";
            followNPCDialog(BROTHER_OMAD, BROTHER_OMAD_START_QUEST_DIALOG);
            sleepUntilQuestStageChanges();
            CURRENT_QUEST_STEP = "Walking Outside";
            walkPath(new int[][] {OUTSIDE_WAYPOINT});
          }
          break;
        case 1: // get the blanket and return it
          if (running() && c.getInventoryItemCount(BLANKET) == 0) {
            CURRENT_QUEST_STEP = "Walking to Kandarin Dungeon";
            if (isAtLocation(START_LOCATION))
              walkPath(new int[][] {OUTSIDE_WAYPOINT}); // walk outside
            pathWalker(618, 657); // walk to kandarin dungeon
          }
          // underground section
          if (running() && c.getInventoryItemCount(BLANKET) == 0) {
            CURRENT_QUEST_STEP = "Grabbing Blanket";
            climb(617, 657); // enter dungeon ladder
            walkPath(new int[][] {{619, 3500}});
            pickupGroundItem(716, 1);
            walkPath(new int[][] {{618, 3489}});
            climb(617, 3489); // enter dungeon ladder
          }
          if (running() && c.getInventoryItemCount(BLANKET) >= 1) {
            CURRENT_QUEST_STEP = "Walking to Brother Omad";
            pathWalker(OUTSIDE_WAYPOINT); // walk back to omad
            walkPath(new int[][] {INSIDE_MONESTARY}); // walk inside
          }
          if (running() && c.getInventoryItemCount(BLANKET) >= 1) {
            CURRENT_QUEST_STEP = "Giving the Blanket";
            talkToNpcId(BROTHER_OMAD);
            sleepUntilQuestStageChanges();
          }
          break;
        case 2: // start part 3
          CURRENT_QUEST_STEP = "Talking to Omad again";
          if (running()) {
            followNPCDialog(BROTHER_OMAD, BROTHER_OMAD_PART_2);
            sleepUntilQuestStageChanges();
            walkPath(new int[][] {OUTSIDE_WAYPOINT}); // walk outside
          }
          break;
        case 3: // Talk to cedric
          if (running() && c.currentX() < 608) {
            CURRENT_QUEST_STEP = "Walking to Cedric";
            if (isAtLocation(START_LOCATION))
              walkPath(new int[][] {OUTSIDE_WAYPOINT}); // walk outside
            pathWalker(616, 640);
            // walkPath(new int[][]{{589,653},{602,645},{615,636}}); //walk outside
          }
          if (running() // get bucket
              && c.getInventoryItemCount(BUCKET) == 0
              && c.getInventoryItemCount(ItemId.BUCKET_OF_WATER.getId()) == 0) {
            CURRENT_QUEST_STEP = "Getting Bucket";
            pickupGroundItem(BUCKET, 1);
          }
          if (running() // get water
              && c.getInventoryItemCount(BUCKET) >= 1
              && c.getInventoryItemCount(BUCKET_OF_WATER) == 0) {
            CURRENT_QUEST_STEP = "Filling Bucket";
            walkPath(new int[][] {{602, 633}});
            c.useItemIdOnObject(600, 633, BUCKET);
            CURRENT_QUEST_STEP = "Walking Back";
            walkPath(new int[][] {{612, 637}});
          }
          if (running() // walk to cedric
              && c.getInventoryItemCount(BUCKET_OF_WATER) >= 1) {
            CURRENT_QUEST_STEP = "Talking to Cedric";
            talkToNpcId(BROTHER_CEDRIC);
            sleepUntilQuestStageChanges();
          }
          break;
        case 4: // Sober them up
          if (running() && c.currentX() < 608) {
            CURRENT_QUEST_STEP = "Walking to Cedric";
            if (isAtLocation(START_LOCATION))
              walkPath(new int[][] {OUTSIDE_WAYPOINT}); // walk outside
            pathWalker(616, 640);
          }
          if (running() && c.getInventoryItemCount(BUCKET_OF_WATER) == 0) {
            CURRENT_QUEST_STEP = "Getting water for Cedric";
            if (c.getInventoryItemCount(BUCKET) == 0) pickupGroundItem(BUCKET, 1); // spawn 616,640
            if (c.getInventoryItemCount(BUCKET) > 0) {
              walkPath(new int[][] {{602, 633}});
              c.useItemIdOnObject(600, 633, BUCKET); // well
              waitFor(() -> c.getInventoryItemCount(BUCKET_OF_WATER) > 0, 5000);
              walkPath(new int[][] {{612, 637}});
            }
          }
          if (running() && c.getInventoryItemCount(BUCKET_OF_WATER) > 0) {
            CURRENT_QUEST_STEP = "Sobering up Cedric";
            useItemOnNearestNpcId(BROTHER_CEDRIC, BUCKET_OF_WATER);
            sleepUntilQuestStageChanges();
          }
          break;
        case 5: // talk to them
          if (running() && c.getInventoryItemCount(LOGS) == 0) {
            CURRENT_QUEST_STEP = "Getting logs";
            for (int i = 0; i < 40 && running() && c.getInventoryItemCount(LOGS) == 0; i++) {
              if (c.getObjectAtCoord(611, 639) == 1) { // tree by Cedric's cart
                walkTo(611, 638);
                c.atObject(611, 639);
              }
              c.sleep(3000); // also waits out the tree respawn
            }
          }
          if (running() && c.getInventoryItemCount(LOGS) > 0) {
            CURRENT_QUEST_STEP = "Giving Cedric Logs";
            followNPCDialog(BROTHER_CEDRIC, BROTHER_CEDRIC_PART_2);
            sleepUntilQuestStageChanges();
            walkTo(606, 636);
          }
          break;
        case 6: // returning to omad
          if (running()) {
            CURRENT_QUEST_STEP = "Walking to Brother Omad";
            pathWalker(OUTSIDE_WAYPOINT);
            walkPath(new int[][] {INSIDE_MONESTARY}); // walk inside
          }
          if (running()) {
            CURRENT_QUEST_STEP = "Talking to Brother Omad";
            talkToNpcId(BROTHER_OMAD);
            sleepUntilQuestStageChanges();
          }
          break;
        default:
          quit(QuitReason.QUEST_STAGE_NOT_IN_SWITCH);
          break;
      }
    }
  }
}
