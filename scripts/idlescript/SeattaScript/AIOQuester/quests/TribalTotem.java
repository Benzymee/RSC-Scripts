package scripting.idlescript.SeattaScript.AIOQuester.quests;

import models.entities.ItemId;
import models.entities.Location;
import models.entities.NpcId;
import scripting.idlescript.SeattaScript.AIOQuester.QuestHandler;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuitReason;

public final class TribalTotem extends QuestHandler {
  private final int[][] BOAT_TO_HORACIO = {{546, 609}, {548, 594}, {554, 579}, {555, 579}};
  private final int CPT_BARNABY = NpcId.CAPTAIN_BARNABY.getId();
  private final int CST_OFFICIAL = NpcId.CUSTOMS_OFFICIAL.getId();
  private final int KANGAI_MAU = NpcId.KANGAI_MAU.getId();
  private final int HORACIO = NpcId.HORACIO.getId();
  private final int RPDT = NpcId.RPDT_EMPLOYEE.getId();
  private final int CROMPERTY = NpcId.WIZARD_CROMPERTY.getId();
  private final int ADDRESS_LABEL = ItemId.ADDRESS_LABEL.getId();
  private final int TRIBAL_TOTEM = ItemId.TRIBAL_TOTEM.getId();
  private final String[] HORACIO_DIALOG = {
    "So who are you?", "So do you garden round the back too?"
  };
  private final String[] RPDT_DIALOG = {"So when are you going to deliver this crate?"};
  private final String[] CROMPERTY_DIALOG = {
    "So what have you invented?", "Can I be teleported please?", "Yes, that sounds good teleport me"
  };
  private final String[] BOAT_TO_BRIM_DIALOG = {"Yes please"};
  private final String[] BOAT_TO_ARDY_DIALOG = {
    "Can I board this ship?", "Search away I have nothing to hide", "Ok"
  };
  private final String[] KANGAI_MAU_START_DIALOG = {
    "I'm in search of adventure", "Ok I will get it back"
  };

  // Combination lock on the BRAD door (boundary 98 at 561,586). The server shows four menus
  // (no npc); the code is B, R, A, D. "postition A" is spelled that way on the server.
  private final String[] BRAD_DIALS = {"position B", "position R", "postition A", "Position D"};

  private void openBradDoor() {
    int x = c.currentX();
    int y = c.currentY();
    for (int attempt = 0; attempt < 5 && running(); attempt++) {
      if (c.currentX() != x || c.currentY() != y) return;
      hideOptionMenu();
      c.atWallObject(561, 586);
      for (String dial : BRAD_DIALS) {
        if (!waitFor(() -> c.isInOptionMenu(), 6000)) break;
        int idx = findOption(dial);
        if (idx < 0) {
          c.log("Tribal Totem: lock menu without '" + dial + "': " + String.join(" | ", currentOptions()), "red");
          hideOptionMenu();
          break;
        }
        answerOption(idx);
        c.sleep(1280);
      }
      waitFor(() -> c.currentX() != x || c.currentY() != y, 4000);
    }
  }

  public void run() { // warning does not handle food conditions
    if (c.currentX() < 500 && c.currentX() > 400 && c.currentY() > 645) {
      START_LOCATION = Location.BRIMHAVEN_SHRIMP_AND_PARROT;
    } else {
      START_LOCATION = Location.ARDOUGNE_SOUTH_BANK;
    }

    // c.log("WARNING: Entering dangerous areas, low combat not recommended", "red");
    c.log("~ by Kaila", "mag");
    while (isQuesting()) {
      switch (QUEST_STAGE) { // 6 stages in total
        case 0: // not started
          if (running() && !isAtLocation(START_LOCATION)) {
            CURRENT_QUEST_STEP = "Walking back to Start";
            pathWalker(546, 615);
          }
          if (running()) {
            walkPath(new int[][] {{546, 615}});
          }
          if (running()) {
            CURRENT_QUEST_STEP = "Boat to Brimhaven";
            followNPCDialog(CPT_BARNABY, BOAT_TO_BRIM_DIALOG);
            CURRENT_QUEST_STEP = "Walking to Bar";
            while (!c.isCloseToCoord(467, 659) && running()) {
              walkTo(467, 659);
              c.sleep(640);
            }
            pathWalker(451, 688);
            CURRENT_QUEST_STEP = "Starting Quest";
            followNPCDialog(KANGAI_MAU, KANGAI_MAU_START_DIALOG);
            sleepUntilQuestStageChanges();
            walkTo(447, 690);
          }
          break;
        case 1: // setting up break-in
          if (running()) {
            CURRENT_QUEST_STEP = "Walking to Boats";
            pathWalker(467, 652);
          }
          if (running()) {
            CURRENT_QUEST_STEP = "Going to Ardy";
            followNPCDialog(CST_OFFICIAL, BOAT_TO_ARDY_DIALOG);
            CURRENT_QUEST_STEP = "Walking to Horacio";
            walkPath(BOAT_TO_HORACIO); // gate checks
          }
          if (running()) {
            CURRENT_QUEST_STEP = "Talking to Horacio";
            followNPCDialog(HORACIO, HORACIO_DIALOG);
          }
          if (running()) {
            CURRENT_QUEST_STEP = "Talking to Cromperty";
            walkPath(
                new int[][] { // gate checks
                  {555, 579},
                  {554, 579},
                  {545, 581},
                  {545, 580}
                });
            followNPCDialog(CROMPERTY, CROMPERTY_DIALOG);
          }
          // If the label was already stuck on the other crate earlier, the crate won't give
          // another one; then just go straight to the RPDT employee.
          for (int i = 0; i < 5 && running() && c.getInventoryItemCount(ADDRESS_LABEL) == 0; i++) {
            CURRENT_QUEST_STEP = "Grabbing Address Label";
            c.atObject(559, 617);
            c.sleep(3000);
          }
          if (running()) {
            CURRENT_QUEST_STEP = "Putting Label on other";
            if (c.getInventoryItemCount(ADDRESS_LABEL) > 0) {
              c.useItemIdOnObject(558, 617, ADDRESS_LABEL);
              c.sleep(3000);
            }
            CURRENT_QUEST_STEP = "Mailing it";
            followNPCDialog(RPDT, RPDT_DIALOG);
            sleepUntilQuestStageChanges();
            walkTo(559, 609);
          }
          break;
        case 2: // breaking into house
          if (running() && c.getInventoryItemCount(706) == 0) {
            CURRENT_QUEST_STEP = "Grabbing Guide Book";
            walkPath(new int[][] {{565, 604}, {565, 603}, {564, 600}});
            pickupUnreachableItem(706, new int[] {564, 600}, 1);
            walkTo(561, 606);
          }
          if (c.getInventoryItemCount(706) > 0) {
            if (running()) {
              CURRENT_QUEST_STEP = "Walking to Cromperty";
              pathWalker(545, 578);
              CURRENT_QUEST_STEP = "Talking to Cromperty";
              followNPCDialog(CROMPERTY, CROMPERTY_DIALOG);
              c.sleep(2000);
            }
            if (running()) {
              walkTo(561, 586);
              CURRENT_QUEST_STEP = "Picking BRAD Lock"; // brad door is wall object
              openBradDoor();
              walkTo(565, 585);
              CURRENT_QUEST_STEP = "Picking Door Lock";
              atWallObject2(565, 586);
              walkTo(564, 586);
            }
            if (running()) {
              CURRENT_QUEST_STEP = "Checking Stair Trap";
              c.atObject2(563, 587);
              c.sleep(6000);
              CURRENT_QUEST_STEP = "Climbing Stair";
              climb(563, 587);
              CURRENT_QUEST_STEP = "Searching Chest";
              walkTo(560, 1532);
              for (int i = 0; i < 10 && running() && c.getInventoryItemCount(TRIBAL_TOTEM) == 0; i++) {
                c.atObject(560, 1531);
                c.sleep(2000);
              }
            }
            if (running() && c.getInventoryItemCount(TRIBAL_TOTEM) > 0) {
              CURRENT_QUEST_STEP = "Escaping Mansion";
              walkTo(563, 1534);
              c.atObject(563, 1531);
              c.sleep(2000);
            }
            if (running() && c.getInventoryItemCount(TRIBAL_TOTEM) > 0) {
              CURRENT_QUEST_STEP = "Escaping through Sewers";
              climb(563, 587);
              c.sleep(3000);
              walkTo(568, 3437);
              climb(569, 3437);
              CURRENT_QUEST_STEP = "Returning the Totem";
              pathWalker(543, 615);
            }
            if (running()) {
              CURRENT_QUEST_STEP = "Boat to Brimhaven";
              followNPCDialog(CPT_BARNABY, BOAT_TO_BRIM_DIALOG);
            }
            if (running()) {
              CURRENT_QUEST_STEP = "Walking to Bar";
              while (!c.isCloseToCoord(467, 659) && running()) {
                walkTo(467, 659);
                c.sleep(640);
              }
              pathWalker(451, 688);
              CURRENT_QUEST_STEP = "Ending Quest";
              talkToNpcId(KANGAI_MAU);
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
