package scripting.idlescript.SeattaScript.AIOQuester.quests;

import models.entities.ItemId;
import models.entities.NpcId;
import orsc.ORSCharacter;
import scripting.idlescript.SeattaScript.AIOQuester.QuestHandler;
import scripting.idlescript.SeattaScript.AIOQuester.QuestWalker;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuitReason;

/**
 * Druidic Ritual. No skill requirement. Jungle Potion's herb identify refuses to work until this
 * quest is complete and Herblaw is at least 3, which the reward provides. The four raw meats are
 * killed for, then dipped in the cauldron of thunder. Nothing is bought from another player.
 *
 * <p>Lines are the local DruidicRitual plugin. Kaqemeex is at 362,459. Sanfew is at 379,487. The
 * dungeon ladder is 376,520 and the cauldron is object 236 at 369,3332.
 */
public final class DruidicRitual extends QuestHandler {
  private static final int CHICKEN_MEAT = ItemId.RAW_CHICKEN.getId();
  private static final int BEEF = ItemId.RAW_BEEF.getId();
  private static final int RAT = ItemId.RAW_RAT_MEAT.getId();
  private static final int BEAR = ItemId.RAW_BEAR_MEAT.getId();
  private static final int ENCH_CHICKEN = ItemId.ENCHANTED_CHICKEN_MEAT.getId();
  private static final int ENCH_BEEF = ItemId.ENCHANTED_BEEF.getId();
  private static final int ENCH_RAT = ItemId.ENCHANTED_RAT_MEAT.getId();
  private static final int ENCH_BEAR = ItemId.ENCHANTED_BEAR_MEAT.getId();
  private static final int KAQEMEEX = NpcId.KAQEMEEX.getId();
  private static final int SANFEW = NpcId.SANFEW.getId();

  @Override
  public void run() {
    hasWalkedToStart = true;
    while (isQuesting()) {
      int stage = QUEST_STAGE;
      if (stage == 0) {
        CURRENT_QUEST_STEP = "Asking Kaqemeex for a quest";
        walker.walkTo(362, 459, 2, 0);
        runDialogue(
            KAQEMEEX,
            new String[] {"I'm in search of a quest", "Ok, I will try and help"},
            true,
            2);
      } else if (stage == 1) {
        CURRENT_QUEST_STEP = "Talking to Sanfew";
        walker.walkTo(379, 487, 2, 0);
        runDialogue(
            SANFEW,
            new String[] {
              "I've been sent to help purify the varrock stone circle", "Ok I'll do that then"
            },
            true,
            2);
      } else if (stage == 2) {
        if (!hasMeats()) {
          CURRENT_QUEST_STEP = needsDip() ? "Dipping the four meats" : "Gathering the four meats";
          gatherOrDip();
        } else {
          CURRENT_QUEST_STEP = "Giving the enchanted meats to Sanfew";
          if (QuestWalker.floor(c.currentY()) > 0) {
            climbOut();
            continue;
          }
          walker.walkTo(379, 487, 2, 0);
          runDialogue(SANFEW, new String[0], true, 2);
        }
      } else if (stage == 3) {
        CURRENT_QUEST_STEP = "Returning to Kaqemeex";
        if (QuestWalker.floor(c.currentY()) > 0) {
          climbOut();
          continue;
        }
        walker.walkTo(362, 459, 2, 0);
        runDialogue(KAQEMEEX, new String[0], true, 2);
      } else {
        quit(QuitReason.QUEST_STAGE_NOT_IN_SWITCH);
        return;
      }
    }
  }

  private boolean hasMeats() {
    return carrying(ENCH_CHICKEN, 1)
        && carrying(ENCH_BEEF, 1)
        && carrying(ENCH_RAT, 1)
        && carrying(ENCH_BEAR, 1);
  }

  private void gatherOrDip() {
    if (!hasAllMeats()) {
      if (QuestWalker.floor(c.currentY()) >= 3) {
        climbOut();
        return;
      }
    } else if (needsDip()) {
      dip();
      return;
    }
    if (!carrying(CHICKEN_MEAT, 1) && !carrying(ENCH_CHICKEN, 1)) {
      killFor(new int[] {NpcId.CHICKEN.getId()}, CHICKEN_MEAT, 118, 605, "a chicken");
      return;
    }
    if (!carrying(BEEF, 1) && !carrying(ENCH_BEEF, 1)) {
      killFor(new int[] {NpcId.COW_ATTACKABLE.getId()}, BEEF, 103, 614, "a cow");
      return;
    }
    if (!carrying(RAT, 1) && !carrying(ENCH_RAT, 1)) {
      killFor(
          new int[] {
            NpcId.RAT_LVL8.getId(), NpcId.RAT_WITCHES_POTION.getId(), NpcId.RAT_LVL13.getId()
          },
          RAT,
          221,
          622,
          "a rat");
      return;
    }
    killFor(
        new int[] {NpcId.BEAR_LVL24.getId(), NpcId.BEAR_LVL26.getId()},
        BEAR,
        165,
        439,
        "a bear");
  }

  private boolean hasAllMeats() {
    return (carrying(CHICKEN_MEAT, 1) || carrying(ENCH_CHICKEN, 1))
        && (carrying(BEEF, 1) || carrying(ENCH_BEEF, 1))
        && (carrying(RAT, 1) || carrying(ENCH_RAT, 1))
        && (carrying(BEAR, 1) || carrying(ENCH_BEAR, 1));
  }

  private boolean needsDip() {
    return (carrying(CHICKEN_MEAT, 1) && !carrying(ENCH_CHICKEN, 1))
        || (carrying(BEEF, 1) && !carrying(ENCH_BEEF, 1))
        || (carrying(RAT, 1) && !carrying(ENCH_RAT, 1))
        || (carrying(BEAR, 1) && !carrying(ENCH_BEAR, 1));
  }

  private void dip() {
    if (QuestWalker.floor(c.currentY()) < 3) {
      walker.walkTo(376, 520, 2, 0);
      climbEither(376, 520);
      return;
    }
    if (Math.max(Math.abs(c.currentX() - 369), Math.abs(c.currentY() - 3332)) > 5) {
      atWallObject(367, 3332);
      walker.walkTo(369, 3332, 1, 0);
      return;
    }
    int raw = -1;
    if (carrying(CHICKEN_MEAT, 1) && !carrying(ENCH_CHICKEN, 1)) raw = CHICKEN_MEAT;
    else if (carrying(BEAR, 1) && !carrying(ENCH_BEAR, 1)) raw = BEAR;
    else if (carrying(RAT, 1) && !carrying(ENCH_RAT, 1)) raw = RAT;
    else if (carrying(BEEF, 1) && !carrying(ENCH_BEEF, 1)) raw = BEEF;
    if (raw < 0) return;
    useCarriedItemOnObject(369, 3332, raw);
    c.sleep(1200);
  }

  private void climbOut() {
    if (QuestWalker.floor(c.currentY()) >= 3) {
      walker.walkTo(376, 3352, 2, 0);
      climbEither(376, 3352);
    }
  }

  private int cowMisses;

  /** Never walk to a wilderness kill tile (y <= 427 and 48 < x < 335). */
  private static boolean wildernessKillTile(int x, int y) {
    return y <= 427 && x > 48 && x < 335;
  }

  private void killFor(int[] npcIds, int dropId, int x, int y, String name) {
    if (wildernessKillTile(c.currentX(), c.currentY())) {
      walker.walkTo(120, 505, 4, 0);
      return;
    }
    int walkX = x;
    int walkY = y;
    if (wildernessKillTile(walkX, walkY)) {
      walkX = 221;
      walkY = 622;
    }
    int[] drop = c.getNearestItemById(dropId);
    if (drop != null && Math.max(Math.abs(drop[0] - c.currentX()), Math.abs(drop[1] - c.currentY())) < 15) {
      pickupGroundItem(dropId, 1);
      return;
    }
    if (c.isInCombat()) {
      cowMisses = 0;
      c.sleep(640);
      return;
    }
    boolean cow = npcIds.length == 1 && npcIds[0] == NpcId.COW_ATTACKABLE.getId();
    if (cow) {
      if (!inLumbridgeCowPen()) {
        enterLumbridgeCowPen();
        return;
      }
    } else if (!targetBeside(npcIds)
        && Math.max(Math.abs(c.currentX() - walkX), Math.abs(c.currentY() - walkY)) > 8) {
      walker.walkTo(walkX, walkY, 2, 0);
      return;
    }
    ORSCharacter npc = c.getNearestNpcByIds(npcIds, false);
    if (npc == null) {
      waitPolitely(name);
      return;
    }
    if (cow && !inLumbridgeCowPen()) {
      enterLumbridgeCowPen();
      return;
    }
    if (!readyToFight()) return;
    c.attackNpc(npc.serverIndex);
    c.sleep(1280);
    if (cow && !c.isInCombat()) {
      cowMisses++;
      if (cowMisses >= 3) {
        c.log("AIOQuester: cow attacks are not landing; opening the pen gate again", "yel");
        cowMisses = 0;
        enterLumbridgeCowPen();
      }
    }
  }

  private boolean targetBeside(int[] npcIds) {
    ORSCharacter npc = c.getNearestNpcByIds(npcIds, false);
    if (npc == null) return false;
    int[] at = c.getNpcCoordsByServerIndex(npc.serverIndex);
    return at != null
        && Math.max(Math.abs(at[0] - c.currentX()), Math.abs(at[1] - c.currentY())) <= 6;
  }

  private void climbEither(int x, int y) {
    walker.climb(x, y, false, 3);
  }
}
