package scripting.idlescript.SeattaScript.AIOQuester.quests;

import models.entities.ItemId;
import models.entities.NpcId;
import scripting.idlescript.SeattaScript.AIOQuester.QuestHandler;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuitReason;

public final class FishingContest extends QuestHandler {
  private final int[] OUTSIDE_HUT_WAYPOINT = {433, 460};
  private final int[] CUPBOARD_COORDS = {216, 1562};
  private final int GIANT_CARP = ItemId.RAW_GIANT_CARP.getId();
  private final int FISHING_ROD = ItemId.FISHING_ROD.getId();
  private final int BAIT = ItemId.FISHING_BAIT.getId();
  private final int GARLIC = ItemId.GARLIC.getId();
  private final int SPADE = ItemId.SPADE.getId();
  private final int PASS = ItemId.FISHING_COMPETITION_PASS.getId();
  private final int RED_VINE_WORMS = ItemId.RED_VINE_WORMS.getId();
  private final int MOUNTAIN_DWARF = NpcId.MOUNTAIN_DWARF.getId();
  private final int HARRY = NpcId.HARRY.getId();
  private final int BONZO = NpcId.BONZO.getId();
  private final String[] MOUNTAIN_DWARF_START_QUEST_DIALOG = {
    "I was wondering what was down those stairs?",
    "Why not?",
    "If you were my friend I wouldn't mind it",
    "Well lets be friends",
    "And how am I meant to do that?",
    "Fortunately I'm alright at fishing"
  };
  private final String[] BONZO_START_DIALOG = {"I'll give that a go then"};
  private final String[] BONZO_END_DIALOG = {"I have this big fish,is it enough to win?"};

  /** Rod, 50 bait, spade (red vine worms) and garlic (pipe), fetched if missing. */
  private void gatherSupplies() {
    CURRENT_QUEST_STEP = "Getting supplies";
    // GET FISHING ROD AND BAIT
    if (running()
        && (c.getInventoryItemCount(FISHING_ROD) == 0
            || c.getInventoryItemCount(BAIT) < 50)) {
      walkPath(new int[][] {OUTSIDE_HUT_WAYPOINT});
      CURRENT_QUEST_STEP = "Walking to Fish Shop";
      pathWalker(418, 486);
      CURRENT_QUEST_STEP = "Buying rod and bait";
      openShopThenBuy(
          new int[] {HARRY},
          new int[][] {
            {FISHING_ROD, 1 - c.getInventoryItemCount(FISHING_ROD), 3},
            {BAIT, 50 - c.getInventoryItemCount(BAIT), 200}
          });
      CURRENT_QUEST_STEP = "Walking Outside";
      walkTo(418, 492);
    }
    // GET SPADE
    if (running() && c.getInventoryItemCount(SPADE) == 0) {
      CURRENT_QUEST_STEP = "Walking to Falador Spade";
      pathWalker(291, 524); // 295,525
      CURRENT_QUEST_STEP = "Getting Spade";
      // walkTo(291,524);
      pickupGroundItem(SPADE, 1);
      CURRENT_QUEST_STEP = "Walking Outside";
      walkTo(295, 524);
    }
    // GET GARLIC
    if (running() && c.getInventoryItemCount(GARLIC) == 0) {
      CURRENT_QUEST_STEP = "Walking to Garlic";
      pathWalker(216, 619);
      climb(216, 620);
      for (int i = 0; i < 6 && running() && c.getInventoryItemCount(GARLIC) == 0; i++) {
        if (c.getObjectAtCoord(CUPBOARD_COORDS[0], CUPBOARD_COORDS[1]) == 140) {
          CURRENT_QUEST_STEP = "Opening Cupboard";
          c.atObject(CUPBOARD_COORDS[0], CUPBOARD_COORDS[1]);
          c.sleep(2000);
        }
        CURRENT_QUEST_STEP = "Grabbing Garlic";
        c.atObject(CUPBOARD_COORDS[0], CUPBOARD_COORDS[1]);
        c.sleep(2000);
      }
      CURRENT_QUEST_STEP = "Walking Outside";
      climb(216, 1564);
      walkTo(212, 605);
    }
    if (c.getInventoryItemCount(ItemId.COINS.getId()) < 5) {
      c.log("Fishing Contest: Bonzo's entry fee is 5 coins and you have fewer", "red");
    }
  }

  public void run() { // warning does not handle food conditions
    c.log("WARNING: Entering dangerous areas, low combat not recommended", "red");
    c.log("~ by Kaila", "mag");
    while (isQuesting()) {
      switch (QUEST_STAGE) { // 6 stages in total
        case 0: // not started
          CURRENT_QUEST_STEP = "Getting ingredients";
          gatherSupplies();
          if (running()
              && c.getInventoryItemCount(FISHING_ROD) > 0
              && c.getInventoryItemCount(BAIT) >= 40
              && c.getInventoryItemCount(SPADE) > 0
              && c.getInventoryItemCount(GARLIC) > 0) {

            if (running() && !isAtLocation(START_LOCATION)) {
              CURRENT_QUEST_STEP = "Walking back to Dwarf";
              pathWalker(OUTSIDE_HUT_WAYPOINT);
            }
            if (running()) {
              CURRENT_QUEST_STEP = "Starting Quest";
              followNPCDialog(MOUNTAIN_DWARF, MOUNTAIN_DWARF_START_QUEST_DIALOG);
              sleepUntilQuestStageChanges();
              CURRENT_QUEST_STEP = "Walking Outside";
              walkPath(new int[][] {OUTSIDE_HUT_WAYPOINT});
            }
          }
          break;
        case 1:
        case 2: // stage 2 only means Grandpa Jack was asked for tips
          if (c.getInventoryItemCount(PASS) == 0) {
            CURRENT_QUEST_STEP = "Getting another competition pass";
            if (!isAtLocation(START_LOCATION)) pathWalker(OUTSIDE_HUT_WAYPOINT);
            followNPCDialog(MOUNTAIN_DWARF, new String[] {"No I need another competition pass"});
            waitFor(() -> c.getInventoryItemCount(PASS) > 0, 5000);
          }
          if (c.getInventoryItemCount(FISHING_ROD) == 0
              || c.getInventoryItemCount(BAIT) < 40
              || (c.getInventoryItemCount(RED_VINE_WORMS) == 0 && c.getInventoryItemCount(SPADE) == 0)) {
            gatherSupplies();
          }
          // Get red vine worms
          if (c.getInventoryItemCount(PASS) > 0 && c.getInventoryItemCount(RED_VINE_WORMS) == 0) {
            if (running()) {
              CURRENT_QUEST_STEP = "Walking to McGrubor";
              pathWalker(539, 445); // walk to mc grubbor
            }
            if (running()) {
              CURRENT_QUEST_STEP = "Entering McGrubor";
              atWallObject(540, 445);
              CURRENT_QUEST_STEP = "Walking to Vines";
              walkTo(550, 453);
              walkTo(568, 453);
              CURRENT_QUEST_STEP = "Grabbing Red Vine Worms";
              for (int i = 0; i < 10 && running() && c.getInventoryItemCount(RED_VINE_WORMS) == 0; i++) {
                c.useItemIdOnObject(568, 453, SPADE); // red vine (355)
                c.sleep(4000);
              }
              CURRENT_QUEST_STEP = "Leaving McGrubor";
              walkTo(550, 453);
              walkTo(540, 445);
              atWallObject(540, 445);
              CURRENT_QUEST_STEP = "Walking Out";
              walkTo(533, 451);
            }
          }
          // Start Contest
          if (c.getInventoryItemCount(PASS) > 0
              && c.getInventoryItemCount(RED_VINE_WORMS) > 0
              && c.getInventoryItemCount(FISHING_ROD) > 0
              && c.getInventoryItemCount(BAIT) >= 40) {

            if (running()) {
              CURRENT_QUEST_STEP = "Walking to Contest";
              pathWalker(564, 492);
            }
            if (running()) {
              CURRENT_QUEST_STEP = "Entering gate";
              atObject(564, 492);
            }
            if (running() && c.getInventoryItemCount(GARLIC) > 0) {
              CURRENT_QUEST_STEP = "Hiding Garlic";
              walkTo(568, 488);
              c.useItemIdOnObject(569, 488, GARLIC);
              c.sleep(2000);
            }
            if (running()) {
              CURRENT_QUEST_STEP = "Starting Contest";
              followNPCDialog(BONZO, BONZO_START_DIALOG);
            }
            if (running()) {
              CURRENT_QUEST_STEP = "Gone Fishing...";
              for (int i = 0;
                  i < 40 && running() && c.getInventoryItemCount(GIANT_CARP) == 0
                      && c.getInventoryItemCount(RED_VINE_WORMS) > 0;
                  i++) {
                c.atObject(570, 489); // carp spot (352) by the pipes, needs the garlic trick
                c.sleep(2000);
              }
            }
            if (running() && c.getInventoryItemCount(GIANT_CARP) > 0) {
              CURRENT_QUEST_STEP = "Ending Contest";
              followNPCDialog(BONZO, BONZO_END_DIALOG);
              sleepUntilQuestStageChanges();
              CURRENT_QUEST_STEP = "Leaving Contest Area";
              walkTo(565, 492);
              atObject(564, 492);
              walkTo(560, 494);
            }
          }
          break;
        case 3: // has trophy
          int FISHING_TROPHY = ItemId.HEMENSTER_FISHING_TROPHY.getId();
          if (c.getInventoryItemCount(FISHING_TROPHY) > 0) {
            if (running() && !isAtLocation(START_LOCATION)) {
              CURRENT_QUEST_STEP = "Walking to Dwarf";
              pathWalker(OUTSIDE_HUT_WAYPOINT);
            }
            if (running()) {
              CURRENT_QUEST_STEP = "Ending Quest";
              talkToNpcId(MOUNTAIN_DWARF);
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
