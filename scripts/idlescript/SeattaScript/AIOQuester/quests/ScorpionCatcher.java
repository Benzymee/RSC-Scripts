package scripting.idlescript.SeattaScript.AIOQuester.quests;

import models.entities.*;
import models.entities.Location;
import scripting.idlescript.K_kailaScript;
import scripting.idlescript.SeattaScript.AIOQuester.QuestHandler;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuitReason;

public final class ScorpionCatcher extends QuestHandler {

  // MAP POINTS377,520
  private final MapPoint TOWER_ENTRANCE = new MapPoint(511, 507);
  private final MapPoint SEER_HOUSE = new MapPoint(521, 465);
  private final MapPoint BARB_OUTPOST_POINT = new MapPoint(500, 547);
  private final MapPoint MONESTARY = new MapPoint(256, 469);
  private final MapPoint MONESTARY_LADDER = new MapPoint(251, 467);
  private final MapPoint PRAYER_ALTER = new MapPoint(262, 460);
  private final MapPoint TAV_LADDER_ABOVE = new MapPoint(377, 520);
  // OBJECT COORDINATES
  private final int[] TOWER_LADDER_UP = {510, 507};
  private final int[] TOWER_LADDER_DOWN = {510, 1451};
  private final int[] BARB_OUTPOST_DOOR = {494, 543};
  private final int[] PRAY_ALTER = {261, 459};
  private final int[] MONESTARY_LADDER_UP = {251, 468};
  private final int[] MONESTARY_LADDER_DOWN = {251, 1412};
  private final int[] TAV_LADDER_UP = {376, 3352};
  private final int[] TAV_LADDER_DOWN = {376, 520};
  // ITEM IDS
  private final int SCORP_CAGE_STAGE_1 = ItemId.SCORPION_CAGE_NONE.getId();
  private final int SCORP_CAGE_STAGE_2 = ItemId.SCORPION_CAGE_TWO.getId();
  private final int SCORP_CAGE_STAGE_3 = ItemId.SCORPION_CAGE_TWO_THREE.getId();
  private final int SCORP_CAGE_STAGE_4 = ItemId.SCORPION_CAGE_ONE_TWO_THREE.getId();
  // NPC IDS
  private final int THORMAC_ID = NpcId.THORMAC_THE_SORCEROR.getId();
  private final int SEER_ID = NpcId.SEER.getId();
  private final int MONK_ID = NpcId.ABBOT_LANGLEY.getId();
  // NPC DIALOGS
  private final String[] THORMAC_START_QUEST_DIALOG = {
    "What do you need assistance with?",
    "So how would I go about catching them then?",
    "Ok I will do it then"
  };
  private final String[] BARB_GUARD = {
    "I want to come through this gate", "Looks can be deceiving, I am in fact a barbarian"
  };
  private final String[] SEER_QUEST_DIALOG = {
    "I need to locate some scorpions",
  };
  private final String[] MONK_DIALOG = {"Well can i join your order?"};

  private void drinkAnti() {
    timeToDrinkAntidote = false;
    if (K_kailaScript.drinkAnti(true)) {
      c.stop();
      c.logout();
    }
  }

  public void run() { // warning does not handle food conditions
    if (c.getInventoryItemCount(SCORP_CAGE_STAGE_1) == 1) {
      START_LOCATION = Location.BARBARIAN_OUTPOST_ENTRANCE;
    } else if (c.getInventoryItemCount(SCORP_CAGE_STAGE_2) == 1) {
      START_LOCATION = Location.EDGEVILLE_MONASTERY;
    } else if (c.getInventoryItemCount(SCORP_CAGE_STAGE_3) == 1) {
      START_LOCATION = Location.TAVERLEY_DUNGEON_ENTRANCE;
    } else if (c.currentY() > 1200) {
      START_LOCATION = Location.SORCERERS_TOWER_F2;
    } else {
      START_LOCATION = Location.SORCERERS_TOWER_F1;
    }
    c.log("~ by Kaila", "mag");
    while (isQuesting()) {
      switch (QUEST_STAGE) {
        case 0: // not started
          // get anti
          if (running()
              && c.getInventoryItemCount(ItemId.CURE_POISON_POTION_1DOSE.getId()) == 0) {
            CURRENT_QUEST_STEP = "Getting cure poison potion";
            if (isAtLocation(Location.SORCERERS_TOWER_F2)) climb(510, 1451);
            walkTo(511, 513); // outside tower
            pathWalker(new MapPoint(631, 634)); // battlefield
            pathWalker(new MapPoint(691, 678)); // goblins
            walkTo(698, 687); // at anti-poison
            pickupGroundItem(ItemId.CURE_POISON_POTION_1DOSE.getId(), 1);
            walkTo(691, 678); // outside gobs
            pathWalker(new MapPoint(631, 634)); // battlefield
          }
          if (running() && !isAtLocation(Location.SORCERERS_TOWER_F2)) {
            CURRENT_QUEST_STEP = "Walking to Thormac";
            pathWalker(TOWER_ENTRANCE);
            climb(TOWER_LADDER_UP);
          }
          if (running()) {
            CURRENT_QUEST_STEP = "Talking to Thormac";
            followNPCDialog(THORMAC_ID, THORMAC_START_QUEST_DIALOG);
            sleepUntilQuestStageChanges();
          }
          break;
        case 1: // talking to seer
          if (c.getInventoryItemCount(SCORP_CAGE_STAGE_1) == 1) {
            if (running()) {
              CURRENT_QUEST_STEP = "Walking to Seer";
              climb(TOWER_LADDER_DOWN);
              walkTo(511, 513); // prevent pathwalker breaking inside houses?
              pathWalker(SEER_HOUSE);
            }
            if (running()) {
              CURRENT_QUEST_STEP = "Talking to Seer";
              followNPCDialog(SEER_ID, SEER_QUEST_DIALOG);
              sleepUntilQuestStageChanges();
              walkTo(521, 458);
            }
          }
          break;
        case 2: // scorpion time
          if (c.getInventoryItemCount(SCORP_CAGE_STAGE_1) == 1) {
            if (running()) {
              CURRENT_QUEST_STEP = "Walking to Barb Outpost Scorpion";
              pathWalker(BARB_OUTPOST_POINT);
              //              walkTo(494, 543); //?
              walkTo(494, 544);
              CURRENT_QUEST_STEP = "Entering Barb Gate";
              c.atObject(BARB_OUTPOST_DOOR[0], BARB_OUTPOST_DOOR[1]);
              c.sleep(3000);
              if (c.currentX() > 493) {
                CURRENT_QUEST_STEP = "Doing Barcrawl";
                followNPCDialogPopUps(305, BARB_GUARD); // Barbarian guard
                c.sleep(3000);
              }
            }
            BarCrawl bar = new BarCrawl();
            bar.doBarCrawl();

            if (running()) {
              CURRENT_QUEST_STEP = "Getting Barb Outpost Scorpion";
              walkTo(489, 547);
              c.sleep(640);
              useItemOnNearestNpcId(303, 678);
            }
            //   DO IN THIS ORDER barb outpost, monestary, tavelry
          } else if (c.getInventoryItemCount(SCORP_CAGE_STAGE_2) == 1) {
            if (running()) {
              if (isAtLocation(Location.BARBARIAN_OUTPOST_INNER)) {
                atObject(BARB_OUTPOST_DOOR);
              }
              CURRENT_QUEST_STEP = "Walking to Monestery Scorpion";
              pathWalker(MONESTARY);
            }
            if (running() && c.getCurrentStat(SkillId.PRAYER.getId()) < 31) {
              CURRENT_QUEST_STEP = "Refilling Prayer Points";
              pathWalker(PRAYER_ALTER);
              while (c.isCurrentlyWalking() && running()) c.sleep(640);
              c.atObject(PRAY_ALTER[0], PRAY_ALTER[1]);
              c.sleep(1280);
            }
            if (running() && c.getCurrentStat(SkillId.PRAYER.getId()) >= 31) {
              CURRENT_QUEST_STEP = "Going up ladder";
              pathWalker(MONESTARY_LADDER);
              hideOptionMenu(); // so a stale menu can't be mistaken for the abbot's
              c.atObject(MONESTARY_LADDER_UP[0], MONESTARY_LADDER_UP[1]);
              waitFor(() -> c.currentY() > 1000 || c.isInOptionMenu(), 8000);
              if (c.currentY() < 1000) {
                CURRENT_QUEST_STEP = "Joining guild";
                followNPCDialogPopUps(MONK_ID, MONK_DIALOG);
                //              } else {
                //                //walkTo(251, 1411);
                //                climb(MONESTARY_LADDER_UP);
              }
            }
            if (running()
                && c.currentY() > 1000
                && c.getCurrentStat(SkillId.PRAYER.getId()) >= 31) {
              CURRENT_QUEST_STEP = "Catching second scorpion";
              useItemOnNearestNpcId(304, SCORP_CAGE_STAGE_2);
              climb(MONESTARY_LADDER_DOWN);
            }
          } else if (c.getInventoryItemCount(SCORP_CAGE_STAGE_3) == 1) {
            if (running()) {
              CURRENT_QUEST_STEP = "Going Down Ladder";
              pathWalker(TAV_LADDER_ABOVE);
              climb(TAV_LADDER_DOWN);
            }
            if (running() && c.currentY() > 1000) { // tav dungeon
              // The ladder pocket (x 374-378) only connects to the rest of the dungeon through the
              // pipe (Agility 70). The other way round is the Dusty Key door through the blue dragons.
              CURRENT_QUEST_STEP = "Going through Pipe, walking to Scorpion";
              if (c.currentX() >= 374 && c.currentX() <= 378 && c.currentY() < 3356) {
                if (c.getCurrentStat(SkillId.AGILITY.getId()) < 70) {
                  c.log("Scorpion Catcher: the Taverley pipe needs 70 Agility, can't reach the third scorpion", "red");
                  climb(TAV_LADDER_UP);
                  quit(QuitReason.REQUIREMENTS_NOT_MET);
                  return;
                }
                if (!walker.walkTo(375, 3352, 1, 20000)) walkFailed(375, 3352);
                atObject(374, 3352); // pipe -> 372,3352
                c.sleepUntil(() -> c.currentX() < 374, 5000);
                if (c.currentX() >= 374) walkFailed(372, 3352); // pipe didn't take us through
              }
              walkTo(376, 3368, 1);
              walkTowards(383, 3352);
              atWallObject(383, 3353); // secret door, opens at this quest stage
              useItemOnNearestNpcId(302, SCORP_CAGE_STAGE_3);
            }
            if (running() && c.currentY() > 1000) { // tav dungeon
              CURRENT_QUEST_STEP = "Returning to Surface";
              if (c.currentY() >= 3353 && c.currentX() >= 379) { // still on the scorpion side of the secret door
                walkTo(383, 3353);
                atWallObject(383, 3353);
              }
              walkTo(375, 3365, 1);
              walkTowards(372, 3352);
              atObject(373, 3352); // pipe back -> 375,3352
              c.sleepUntil(() -> c.currentX() >= 374, 5000);
              climb(TAV_LADDER_UP);
              walkTo(374, 513);
            }
          } else if (c.getInventoryItemCount(SCORP_CAGE_STAGE_4) == 1) {
            if (running() && !isAtLocation(Location.SORCERERS_TOWER_F2)) {
              CURRENT_QUEST_STEP = "Walking to Tower";
              if (timeToDrinkAntidote) drinkAnti();
              pathWalker(TOWER_ENTRANCE);
              if (timeToDrinkAntidote) drinkAnti();
              climb(TOWER_LADDER_UP);
            }
            if (running()) {
              CURRENT_QUEST_STEP = "Talking to Thormac, Finishing Quest";
              if (timeToDrinkAntidote) drinkAnti();
              talkToNpcId(THORMAC_ID);
              sleepUntilQuestStageChanges();
            }
          }
          break;
        default:
          quit(QuitReason.QUEST_STAGE_NOT_IN_SWITCH);
          break;
      }
    }
  }
}
