package scripting.idlescript.SeattaScript.AIOQuester.quests;

import models.entities.ItemId;
import models.entities.NpcId;
import orsc.ORSCharacter;
import scripting.idlescript.SeattaScript.AIOQuester.QuestHandler;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuitReason;

/**
 * Shield of Arrav. One account finishes its own gang. gang=phoenix (default) kills Jonny the Beard
 * for the intel report and takes the Phoenix half. gang=blackarm talks to the tramp and Katrine,
 * then stops when the Phoenix weapon key or the crossbows are required. The certificate trade
 * needs the other account, so the script stops with {@link QuitReason#COOP_PARTNER_NEEDED}.
 */
public final class ShieldOfArrav extends QuestHandler {
  private static final int BOOK = ItemId.BOOK.getId();
  private static final int SCROLL = ItemId.SCROLL.getId();
  private static final int WEAPON_KEY = ItemId.PHOENIX_GANG_WEAPON_KEY.getId();
  private static final int HALF1 = ItemId.BROKEN_SHIELD_ARRAV_1.getId();
  private static final int HALF2 = ItemId.BROKEN_SHIELD_ARRAV_2.getId();
  private static final int CROSSBOW = ItemId.PHOENIX_CROSSBOW.getId();
  private static final int RELDO = NpcId.RELDO.getId();
  private static final int STRAVEN = NpcId.STRAVEN.getId();

  private boolean phoenix() {
    return SHIELD_GANG == null || !SHIELD_GANG.startsWith("black");
  }

  @Override
  public void run() {
    hasWalkedToStart = true;
    while (isQuesting()) {
      int stage = QUEST_STAGE;
      if (carrying(HALF1, 1) || carrying(HALF2, 1)) {
        CURRENT_QUEST_STEP = "Waiting on the other Shield of Arrav account";
        quit(QuitReason.COOP_PARTNER_NEEDED);
        return;
      }
      if (stage == 0) {
        CURRENT_QUEST_STEP = "Asking Reldo about a quest";
        walker.walkTo(128, 456, 2, 0);
        runDialogue(RELDO, new String[] {"I'm in search of a quest"}, true, 2);
      } else if (stage == 1 && !carrying(BOOK, 1)) {
        CURRENT_QUEST_STEP = "Taking the book from the bookcase";
        walker.walkTo(132, 455, 1, 0);
        atObject(132, 455);
        c.sleep(1500);
      } else if (stage == 1) {
        CURRENT_QUEST_STEP = "Asking Reldo about the Phoenix Gang";
        walker.walkTo(128, 456, 2, 0);
        runDialogue(RELDO, new String[] {"Do you know where I can find the Phoenix Gang?"}, true, 2);
      } else if (stage == 2) {
        if (!phoenix()) blackArm();
        else {
          CURRENT_QUEST_STEP = "Paying Baraek for the Phoenix location";
          if (coins() < 20) gatherCoins(20);
          else {
            walker.walkTo(128, 505, 2, 0);
            runDialogue(
                NpcId.BARAEK.getId(),
                new String[] {"Can you tell me where I can find the phoenix gang?", "Okay have 20 gold coins"},
                true,
                2);
          }
        }
      } else if (!phoenix()) {
        blackArm();
      } else if (stage == 3 && !carrying(SCROLL, 1)) {
        CURRENT_QUEST_STEP = "Joining the Phoenix Gang";
        if (c.currentY() < 3000) {
          walker.walkTo(118, 524, 2, 0);
          atObject(106, 534);
          c.sleep(1500);
        } else {
          walker.walkTo(111, 3369, 2, 0);
          runDialogue(
              STRAVEN,
              new String[] {"I know who you are", "I'd like to offer you my services"},
              true,
              2);
          killJonny();
        }
      } else if (stage == 3 || (stage == 4 && carrying(SCROLL, 1) && !carrying(WEAPON_KEY, 1))) {
        CURRENT_QUEST_STEP = "Handing the intel report to Straven";
        if (c.currentY() < 3000) {
          walker.walkTo(118, 524, 2, 0);
          atObject(106, 534);
          c.sleep(1500);
        } else {
          walker.walkTo(111, 3369, 2, 0);
          runDialogue(STRAVEN, new String[] {"I have the intelligence report"}, true, 2);
        }
      } else if (carrying(WEAPON_KEY, 1) || stage >= 5) {
        CURRENT_QUEST_STEP = "Taking the Phoenix shield half";
        if (c.currentY() < 3000) {
          walker.walkTo(118, 524, 2, 0);
          atObject(106, 534);
          c.sleep(1500);
        } else {
          useItemOnWallSlot(WEAPON_KEY, 110, 3370);
          c.sleep(800);
          atWallObject(110, 3370);
          c.sleep(800);
          walker.walkTo(101, 3380, 1, 0);
          atObject(101, 3380);
          c.sleep(1500);
          int[] half = c.getNearestItemById(HALF1);
          if (half != null) pickupGroundItem(HALF1, 1);
        }
      } else {
        quit(QuitReason.QUEST_STAGE_NOT_IN_SWITCH);
        return;
      }
    }
  }

  private void killJonny() {
    if (c.currentY() > 3000) {
      atObject(106, 3366);
      c.sleep(1200);
      return;
    }
    CURRENT_QUEST_STEP = "Killing Jonny the Beard";
    walker.walkTo(122, 524, 3, 0);
    int[] report = c.getNearestItemById(SCROLL);
    if (report != null) {
      pickupGroundItem(SCROLL, 1);
      return;
    }
    if (c.isInCombat()) {
      c.sleep(640);
      return;
    }
    ORSCharacter jonny = c.getNearestNpcByIds(new int[] {NpcId.JONNY_THE_BEARD.getId()}, false);
    if (jonny == null) waitPolitely("Jonny the Beard");
    else c.attackNpc(jonny.serverIndex);
  }

  private void blackArm() {
    if (c.getInventoryItemCount(CROSSBOW) >= 2) {
      CURRENT_QUEST_STEP = "Giving Katrine the phoenix crossbows";
      walker.walkTo(150, 534, 2, 0);
      atWallObject(148, 533);
      runDialogue(NpcId.KATRINE.getId(), new String[0], true, 1);
      atObject(150, 534);
      c.sleep(1000);
      return;
    }
    CURRENT_QUEST_STEP = "Black Arm Gang, up to the crossbows";
    walker.walkTo(131, 529, 2, 0);
    runDialogue(
        NpcId.TRAMP.getId(),
        new String[] {"Is there anything down this alleyway?", "Do you think they would let me join?"},
        true,
        2);
    walker.walkTo(150, 534, 2, 0);
    atWallObject(148, 533);
    runDialogue(
        NpcId.KATRINE.getId(),
        new String[] {
          "I've heard you're the blackarm gang",
          "I'd rather not reveal my sources",
          "I want to become a member of your gang",
          "Well you can give me a try, can't you?",
          "Ok no problem"
        },
        true,
        2);
    c.log(
        "AIOQuester: Black Arm now needs two phoenix crossbows from the other account's weapon key",
        "yel");
    quit(QuitReason.COOP_PARTNER_NEEDED);
  }
}
