package scripting.idlescript.SeattaScript.AIOQuester.quests;

import models.entities.ItemId;
import models.entities.Location;
import models.entities.NpcId;
import models.entities.QuestId;
import scripting.idlescript.SeattaScript.AIOQuester.QuestHandler;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuitReason;

public final class RuneMysteries extends QuestHandler {

  private final int DUKE_ID = NpcId.DUKE_OF_LUMBRIDGE.getId();
  private final int SEDRIDOR_ID = NpcId.SEDRIDOR.getId();
  private final int AUBURY_ID = NpcId.AUBURY.getId();

  private final int AIR_TALISMAN = ItemId.AIR_TALISMAN.getId();
  private final int RESEARCH_PACKAGE = ItemId.RESEARCH_PACKAGE.getId();
  private final int RESEARCH_NOTES = ItemId.RESEARCH_NOTES.getId();

  private final String[] DUKE_DIALOG = {
    "Have you any quests for me?", "Sure, I have some spare time"
  };
  private final String[] SEDRIDOR_DIALOG_1 = {"I'm looking for the head wizard", "Yes, certainly"};
  private final String[] AUBURY_DIALOG = {
    "I've been sent here with a package for you", "Yes, please."
  };
  private final String[] SEDRIDOR_DIALOG_2 = {"Rune Mysteries"};
  // Re-issues (OpenRSC RuneMysteries.java): at stage 2 Sedridor's "Rune Mysteries" option packs
  // another research package if you don't carry one; at stage 3 Aubury's "Rune mysteries" option
  // hands over another copy of the notes. Nobody re-issues the air talisman at stage 1 (the Duke
  // only says "Take the air talisman to the head wizard").
  private final String[] SEDRIDOR_REISSUE = {"Rune Mysteries"};
  private final String[] AUBURY_REISSUE = {"Rune mysteries"};
  private boolean breakFinalDialog = false;
  private boolean deferStep = false;
  private boolean bankAlreadyChecked = false;
  private long bankCheckedAt = 0;
  private int talismanLostRounds = 0;

  public void run() {
    hasWalkedToStart = true;
    while (isQuesting()) {
      if (breakFinalDialog) break;

      // Server stages: 0 not started, 1 carrying the talisman, 2 carrying the package, 3 carrying
      // the notes. Sedridor's hand-over saves stage 2 without telling the client, so a resume
      // still reads stage 1 while the research package is already held or in the bank. The item
      // decides the step: notes go back to Sedridor, the package goes to Aubury.
      int stage = c.getQuestStage(QuestId.RUNE_MYSTERIES.getId());
      if (stage == -1) break;
      if (stage >= 1) stage = takeBankedQuestItem(stage);
      QUEST_STAGE = stage;
      if (deferStep) continue;

      switch (stage) {
        case 0:
          CURRENT_QUEST_STEP = "Talking to Duke Horacio";
          STEP_ITEMS = new int[][] {{AIR_TALISMAN, 1}};
          if (c.getInventoryItemCount() >= 30) recoverQuestItem(AIR_TALISMAN, "air talisman (inventory full, freeing a slot)");
          if (c.getNearestNpcById(DUKE_ID, false) != null) {
            talkToNpcAndSelectOptions(DUKE_ID, DUKE_DIALOG);
            waitForItem(AIR_TALISMAN);
          } else if (!Location.LUMBRIDGE_CASTLE_DUKES_ROOM.isAtLocation()) {
            walkTowards(Location.LUMBRIDGE_CASTLE_DUKES_ROOM);
          } else {
            c.sleep(640);
          }
          break;

        case 1:
          CURRENT_QUEST_STEP = "Taking Talisman to Sedridor";
          STEP_ITEMS = new int[][] {{AIR_TALISMAN, 1}};
          if (c.getInventoryItemCount(AIR_TALISMAN) == 0
              && (bankAlreadyChecked || !recoverQuestItem(AIR_TALISMAN, "air talisman"))) {
            // No npc gives another one at this stage; the bank/ground were the only places.
            talismanLostRounds++;
            c.log(
                "AIOQuester: Rune Mysteries: the air talisman is lost (not carried, banked or nearby)"
                    + " and nobody re-issues it at stage 1",
                "red");
            if (talismanLostRounds >= 2) {
              quit(QuitReason.REQUIREMENTS_NOT_MET);
              return;
            }
            c.sleep(2000);
            break;
          }
          toWizardsTowerBasement(SEDRIDOR_ID);
          if (c.getNearestNpcById(SEDRIDOR_ID, false) != null) {
            talkToNpcAndSelectOptions(SEDRIDOR_ID, SEDRIDOR_DIALOG_1);
            waitForItem(RESEARCH_PACKAGE);
          }
          break;

        case 2:
          STEP_ITEMS = new int[][] {{RESEARCH_PACKAGE, 1}};
          if (c.getInventoryItemCount(RESEARCH_PACKAGE) == 0
              && (bankAlreadyChecked
                  || !recoverQuestItem(RESEARCH_PACKAGE, "research package"))) {
            CURRENT_QUEST_STEP = "Getting another research package from Sedridor";
            c.log("AIOQuester: Rune Mysteries: research package lost; asking Sedridor for another", "yel");
            toWizardsTowerBasement(SEDRIDOR_ID);
            if (c.getNearestNpcById(SEDRIDOR_ID, false) != null) {
              talkToNpcAndSelectOptions(SEDRIDOR_ID, SEDRIDOR_REISSUE);
              waitForItem(RESEARCH_PACKAGE);
            }
            break;
          }
          CURRENT_QUEST_STEP = "Taking Package to Aubury";
          goToAuburyAndTalk(AUBURY_DIALOG, RESEARCH_NOTES);
          break;

        case 3:
          STEP_ITEMS = new int[][] {{RESEARCH_NOTES, 1}};
          if (c.getInventoryItemCount(RESEARCH_NOTES) == 0
              && (bankAlreadyChecked || !recoverQuestItem(RESEARCH_NOTES, "research notes"))) {
            CURRENT_QUEST_STEP = "Getting another copy of the notes from Aubury";
            c.log("AIOQuester: Rune Mysteries: research notes lost; asking Aubury for another copy", "yel");
            goToAuburyAndTalk(AUBURY_REISSUE, RESEARCH_NOTES);
            break;
          }
          CURRENT_QUEST_STEP = "Returning Notes to Sedridor";
          toWizardsTowerBasement(SEDRIDOR_ID);
          if (c.getNearestNpcById(SEDRIDOR_ID, false) != null) {
            talkToNpcAndSelectOptions(SEDRIDOR_ID, SEDRIDOR_DIALOG_2);
            waitForStageChange();
          }
          break;

        default:
          quit(QuitReason.QUEST_STAGE_NOT_IN_SWITCH);
      }
    }
  }

  /**
   * The step follows the quest item, not only the stage number. Notes and the research package
   * are withdrawn before the air talisman, so a banked package resumes the Aubury delivery.
   */
  private int takeBankedQuestItem(int stage) {
    deferStep = false;
    bankAlreadyChecked = false;
    if (c.getInventoryItemCount(RESEARCH_NOTES) > 0) return Math.max(stage, 3);
    if (c.getInventoryItemCount(RESEARCH_PACKAGE) > 0) return Math.max(stage, 2);
    if (stage <= 1 && c.getInventoryItemCount(AIR_TALISMAN) > 0) return stage;

    if (Location.WIZARDS_TOWER_BASEMENT.isAtLocation()) {
      CURRENT_QUEST_STEP = "Leaving the tower to check the bank";
      int[] ladder = c.getNearestObjectById(5);
      if (ladder != null) {
        c.atObject(ladder[0], ladder[1]);
        c.sleep(2000);
      } else {
        walkTowards(Location.WIZARDS_TOWER_ENTRANCE);
      }
      deferStep = true;
      return stage;
    }

    bankAlreadyChecked = true;
    if (System.currentTimeMillis() - bankCheckedAt < 10 * 60_000L) return stage;

    CURRENT_QUEST_STEP = "Checking the bank for Rune Mysteries items";
    c.log("AIOQuester: a Rune Mysteries item is not in the inventory; checking the bank", "yel");
    if (!openNearestBank()) {
      bankAlreadyChecked = false;
      return stage;
    }
    int next = stage;
    boolean present = false;
    try {
      if (bankHas(RESEARCH_NOTES)) {
        present = true;
        if (withdrawQuestItem(RESEARCH_NOTES)) next = Math.max(stage, 3);
      } else if (bankHas(RESEARCH_PACKAGE)) {
        present = true;
        if (withdrawQuestItem(RESEARCH_PACKAGE)) next = Math.max(stage, 2);
      } else if (stage <= 1 && bankHas(AIR_TALISMAN)) {
        present = true;
        withdrawQuestItem(AIR_TALISMAN);
      }
    } finally {
      if (c.isInBank()) c.closeBank();
      c.sleep(400);
    }
    if (!present) bankCheckedAt = System.currentTimeMillis();
    return next;
  }

  private boolean bankHas(int id) {
    return c.isInBank() && c.getBankItemCount(id) > 0;
  }

  private boolean withdrawQuestItem(int id) {
    if (c.getInventoryItemCount() >= 30) {
      int[] ids = c.getInventoryItemIds();
      if (ids != null) {
        for (int i = ids.length - 1; i >= 0; i--) {
          int held = ids[i];
          if (held < 0 || isKeptQuestItem(held)) continue;
          if (held == AIR_TALISMAN || held == RESEARCH_PACKAGE || held == RESEARCH_NOTES) continue;
          c.depositItem(held, c.getInventoryItemCount(held));
          c.sleep(600);
          break;
        }
      }
    }
    int before = c.getInventoryItemCount(id);
    c.withdrawItem_apos(id, 1);
    long until = System.currentTimeMillis() + 4000;
    while (running() && c.getInventoryItemCount(id) <= before && System.currentTimeMillis() < until)
      c.sleep(200);
    return c.getInventoryItemCount(id) > before;
  }

  /** Walks to the Wizards' Tower basement unless the npc is already in view. */
  private void toWizardsTowerBasement(int npcId) {
    if (c.getNearestNpcById(npcId, false) != null) return;
    if (!Location.WIZARDS_TOWER_BASEMENT.isAtLocation()) walkTowards(Location.WIZARDS_TOWER_BASEMENT);
    else c.sleep(640);
  }

  /** To Aubury's shop (leaving the tower basement first), then talks with the given options. */
  private void goToAuburyAndTalk(String[] options, int expectItem) {
    if (c.getNearestNpcById(AUBURY_ID, false) != null) {
      talkToNpcAndSelectOptions(AUBURY_ID, options);
      waitForItem(expectItem);
    } else if (Location.WIZARDS_TOWER_BASEMENT.isAtLocation()) {
      int[] ladder = c.getNearestObjectById(5);
      if (ladder != null) {
        c.atObject(ladder[0], ladder[1]);
        c.sleep(2000);
      } else {
        walkTowards(Location.WIZARDS_TOWER_ENTRANCE);
      }
    } else {
      walkToAubury();
    }
  }

  private void waitForItem(int itemId) {
    long timeout = System.currentTimeMillis() + 15000;
    while (running()
        && c.getInventoryItemCount(itemId) < 1
        && System.currentTimeMillis() < timeout) {
      c.sleep(100);
    }
  }

  private void waitForStageChange() {
    int currentStage = c.getQuestStage(QuestId.RUNE_MYSTERIES.getId());
    long timeout = System.currentTimeMillis() + 15000;
    while (c.getQuestStage(QuestId.RUNE_MYSTERIES.getId()) == currentStage
        && running()
        && System.currentTimeMillis() < timeout) {
      c.sleep(100);
    }
  }

  /** Uses the shared dialogue handler: waits for each menu, matches option text, retries. */
  private void talkToNpcAndSelectOptions(int npcId, String[] options) {
    if (c.getNearestNpcById(npcId, false) == null) return;
    followNPCDialog(npcId, options);
  }

  /**
   * The webwalker has no node at the rune shop tile (101,525), so it logged "Can't find the
   * closest walkable node" and never arrived. Walk to the street outside instead, then step in.
   */
  private void walkToAubury() {
    // One web-walked route straight into Aubury's shop (door handled by QuestWalker).
    walkTowards(102, 525);
    c.sleep(640);
  }

  @Override
  public void serverMessageInterrupt(String message) {
    if (message.contains("Well done you have completed the rune mysteries quest"))
      breakFinalDialog = true;
  }
}
