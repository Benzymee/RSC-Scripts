package scripting.idlescript.SeattaScript.AIOQuester.quests;

import models.entities.ItemId;
import models.entities.NpcId;
import models.entities.SkillId;
import orsc.ORSCharacter;
import scripting.idlescript.SeattaScript.AIOQuester.QuestHandler;
import scripting.idlescript.SeattaScript.AIOQuester.QuestWalker;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuitReason;

/**
 * The Hazeel Cult, good side. Clivet's offer is refused, so the script never poisons a
 * Carnillean. The sewer valves are turned to the Mark of Hazeel combination and the raft is
 * boarded. Alomone (combat 42) is killed for the family armour, which is returned to Ceril.
 * The script quits TOO_WEAK if combat or Hitpoints are under 30 before that fight. The butler's
 * cupboard is then searched while Ceril is upstairs.
 *
 * <p>Valve commands are the local SewerValve plugin: 1 right, 2 left, 3 right, 4 right, 5 left.
 * Turn left is the first object command and turn right is the second.
 */
public final class HazeelCult extends QuestHandler {
  private static final int ARMOUR = ItemId.CARNILLEAN_ARMOUR.getId();
  private static final int CERIL = NpcId.CERIL.getId();
  private static final int CLIVET = NpcId.CLIVET.getId();
  private static final int ALOMONE = NpcId.ALOMONE.getId();

  private int valve;

  @Override
  public void run() {
    hasWalkedToStart = true;
    while (isQuesting()) {
      int stage = QUEST_STAGE;
      if (stage == 0) {
        CURRENT_QUEST_STEP = "Asking Ceril about the thieves";
        if (underground()) {
          leaveSewer();
          continue;
        }
        walker.walkTo(619, 620, 2, 0);
        runDialogue(
            CERIL,
            new String[] {"What's wrong?", "yes, off course,i'd be happy to help"},
            true,
            2);
      } else if (stage == 1 || stage == 2) {
        CURRENT_QUEST_STEP = "Refusing to join the cult";
        if (underground() && c.currentY() >= 3483) {
          leaveSewer();
          continue;
        }
        enterSewer();
        if (!underground()) continue;
        walker.walkTo(619, 3477, 2, 0);
        runDialogue(
            CLIVET,
            new String[] {"What do you mean?", "You're crazy, i'd never help you"},
            true,
            2);
      } else if (stage == 3) {
        CURRENT_QUEST_STEP = "Reaching Alomone";
        if (combatLevel() < 30 || c.getBaseStat(SkillId.HITS.getId()) < 30 || foodCount() < 1) {
          c.log(
              "AIOQuester: Alomone is combat 42; need combat 30, 30 Hitpoints and food. Quitting TOO_WEAK.",
              "red");
          quit(QuitReason.TOO_WEAK);
          return;
        }
        if (!carrying(ARMOUR, 1)) fightAlomone();
        else {
          // The kill should have moved the stage. Talk to Ceril if it already has.
          returnArmour();
        }
      } else if (stage == 4) {
        CURRENT_QUEST_STEP = "Returning the armour to Ceril";
        returnArmour();
      } else if (stage == 5) {
        CURRENT_QUEST_STEP = "Searching the butler's cupboard";
        searchCupboard();
      } else {
        quit(QuitReason.QUEST_STAGE_NOT_IN_SWITCH);
        return;
      }
    }
  }

  private void fightAlomone() {
    if (hazeelSewerWest()) valve = 5;
    if (valve < 5) {
      if (underground()) {
        leaveSewer();
        return;
      }
      turnOneValve();
      return;
    }
    if (!underground()) {
      enterSewer();
      return;
    }
    if (c.currentX() > 600) {
      walker.walkTo(621, 3477, 1, 0);
      c.atObject(621, 3477);
      c.sleep(2000);
      return;
    }
    if (c.isInCombat()) {
      c.sleep(640);
      return;
    }
    walker.walkTo(581, 3419, 3, 0);
    ORSCharacter alomone = c.getNearestNpcByIds(new int[] {ALOMONE}, false);
    if (alomone == null) {
      waitPolitely("Alomone");
      return;
    }
    if (!readyToFight()) return;
    // Alomone is not attackable until talked to (server setChasing on the good side).
    c.log("AIOQuester: talking to Alomone to start the fight", "yel");
    runDialogue(ALOMONE, new String[0], true, 2);
    c.sleep(1500);
    if (!c.isInCombat()) {
      alomone = c.getNearestNpcByIds(new int[] {ALOMONE}, false);
      if (alomone != null) {
        c.attackNpc(alomone.serverIndex);
        c.sleep(1280);
      }
    }
  }

  private void turnOneValve() {
    int x;
    int y;
    boolean right;
    if (valve == 0) {
      x = 623;
      y = 634;
      right = true;
    } else if (valve == 1) {
      x = 622;
      y = 603;
      right = false;
    } else if (valve == 2) {
      x = 622;
      y = 593;
      right = true;
    } else if (valve == 3) {
      x = 622;
      y = 582;
      right = true;
    } else {
      x = 622;
      y = 578;
      right = false;
    }
    walker.walkTo(x, y, 1, 0);
    if (Math.max(Math.abs(c.currentX() - x), Math.abs(c.currentY() - y)) > 3) return;
    if (right) c.atObject2(x, y);
    else c.atObject(x, y);
    c.sleep(1200);
    valve++;
  }

  private void returnArmour() {
    if (underground()) {
      leaveSewer();
      return;
    }
    if (QuestWalker.floor(c.currentY()) > 0) {
      climbEither(613, 1562);
      return;
    }
    walker.walkTo(619, 620, 2, 0);
    runDialogue(CERIL, new String[0], true, 2);
  }

  private void searchCupboard() {
    if (underground()) {
      leaveSewer();
      return;
    }
    if (QuestWalker.floor(c.currentY()) == 0) {
      walker.walkTo(613, 618, 1, 0);
      climbEither(613, 618);
      return;
    }
    walker.walkTo(614, 1562, 1, 0);
    c.atObject(614, 1562);
    c.sleep(1200);
  }

  private boolean hazeelSewerWest() {
    return underground() && c.currentX() < 600;
  }

  private boolean underground() {
    int x = c.currentX();
    int y = c.currentY();
    if (x >= 610 && x <= 630 && y >= 3475 && y <= 3490) return true; // Clivet
    if (x >= 575 && x < 600 && y >= 3400 && y <= 3435) return true; // raft / Alomone
    return false;
  }

  private void enterSewer() {
    if (underground()) return;
    if (QuestWalker.floor(c.currentY()) > 0) {
      climbEither(613, 1562);
      return;
    }
    // Cave entrance 417 at 619,649 teleports to 617,3479 beside Clivet. The ladder at 617,657
    // drops into a sealed sewer pocket (617,3488) that cannot reach him.
    walker.walkTo(619, 649, 1, 0);
    c.log("AIOQuester: entering the Hazeel cave at 619,649", "yel");
    c.atObject(619, 649);
    c.sleep(2000);
  }

  private void leaveSewer() {
    if (!underground()) return;
    if (c.currentX() < 600) {
      walker.walkTo(589, 3409, 1, 0);
      c.atObject(589, 3409);
      c.sleep(2000);
      return;
    }
    if (c.currentY() >= 3483) {
      walker.walkTo(617, 3489, 2, 0);
      climbEither(617, 3489);
      return;
    }
    walker.walkTo(616, 3479, 2, 0);
    climbEither(616, 3479);
  }

  private void climbEither(int x, int y) {
    walker.climb(x, y, false, 3);
  }
}
