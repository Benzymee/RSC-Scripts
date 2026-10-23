package scripting.idlescript.SeattaScript.AIOQuester.quests;

import models.entities.ItemId;
import models.entities.NpcId;
import scripting.idlescript.SeattaScript.AIOQuester.QuestHandler;
import scripting.idlescript.SeattaScript.AIOQuester.QuestWalker;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuitReason;

/**
 * Jungle Potion. Each herb is searched for, identified, and handed to Trufitus. Identify refuses
 * until Druidic Ritual is complete and Herblaw is at least 3. Queue Druidic Ritual first.
 *
 * <p>The herbs are the local Jungle_Potion plugin: snake vine 564, ardrigal palm 553, scorched
 * earth 554, volencia rocks 555, and the rogues purse wall in the cavern under 426,740.
 */
public final class JunglePotion extends QuestHandler {
  private static final int TRUFITUS = NpcId.TRUFITUS.getId();
  private static final int[] UNID = {
    ItemId.UNIDENTIFIED_SNAKE_WEED.getId(),
    ItemId.UNIDENTIFIED_ARDRIGAL.getId(),
    ItemId.UNIDENTIFIED_SITO_FOIL.getId(),
    ItemId.UNIDENTIFIED_VOLENCIA_MOSS.getId(),
    ItemId.UNIDENTIFIED_ROGUES_PURSE.getId()
  };
  private static final int[] HERB = {
    ItemId.SNAKE_WEED.getId(),
    ItemId.ARDRIGAL.getId(),
    ItemId.SITO_FOIL.getId(),
    ItemId.VOLENCIA_MOSS.getId(),
    ItemId.ROGUES_PURSE.getId()
  };
  private static final int[] SEARCH = {564, 553, 554, 555, 0};
  private static final int[][] AT = {
    {470, 794}, {397, 756}, {447, 778}, {412, 794}, {411, 3575}
  };

  private boolean secondClimb;
  private int wall;

  @Override
  public void run() {
    hasWalkedToStart = true;
    while (isQuesting()) {
      int stage = QUEST_STAGE;
      if (stage == 0) {
        CURRENT_QUEST_STEP = "Asking Trufitus for the potion";
        if (!goTrufitus()) continue;
        runDialogue(
            TRUFITUS,
            new String[] {
              "It's a nice village, where is everyone?",
              "Me! How can I help?",
              "It sounds like just the challenge for me!"
            },
            true,
            2);
      } else if (stage >= 1 && stage <= 5) {
        CURRENT_QUEST_STEP = "Collecting herb " + stage + " of 5";
        handleHerb(stage - 1);
      } else {
        quit(QuitReason.QUEST_STAGE_NOT_IN_SWITCH);
        return;
      }
    }
  }

  private void handleHerb(int i) {
    if (carrying(HERB[i], 1)) {
      if (QuestWalker.floor(c.currentY()) > 0 && i == 4) {
        climbEither(426, 3572);
        return;
      }
      if (!goTrufitus()) return;
      String turnIn = i == 4 ? "Yes Sir, indeedy I do!" : "Of course!";
      runDialogue(TRUFITUS, new String[] {turnIn}, true, 2);
      return;
    }
    if (carrying(UNID[i], 1)) {
      c.itemCommand(UNID[i]);
      c.sleep(1200);
      if (gameMessageContains("druidic")
          || gameMessageContains("herblaw")
          || gameMessageContains("cannot identify")) {
        c.log(
            "AIOQuester: Jungle Potion identify needs Druidic Ritual complete and 3 Herblaw.",
            "red");
        quit(QuitReason.REQUIREMENTS_NOT_MET);
      }
      return;
    }
    int[] drop = c.getNearestItemById(UNID[i]);
    if (drop != null) {
      pickupGroundItem(UNID[i], 1);
      return;
    }
    if (i == 4) {
      searchRogues();
      return;
    }
    walker.walkTo(AT[i][0], AT[i][1], 2, 0);
    int[] obj = c.getNearestObjectById(SEARCH[i]);
    if (obj == null) {
      waitPolitely("the herb patch");
      return;
    }
    c.atObject(obj[0], obj[1]);
    c.sleep(1200);
  }

  private void searchRogues() {
    if (c.currentY() < 3000) {
      walker.walkTo(426, 740, 1, 0);
      climbEither(426, 740);
      return;
    }
    int[][] walls = {{411, 3575}, {410, 3576}, {409, 3577}};
    int[] spot = walls[wall % walls.length];
    walker.walkTo(spot[0], spot[1], 1, 0);
    atWallObject(spot[0], spot[1]);
    c.sleep(1000);
    if (c.getNearestItemById(UNID[4]) == null) wall++;
  }

  private boolean goTrufitus() {
    if (QuestWalker.floor(c.currentY()) >= 3 && c.currentY() < 3500) {
      c.log("AIOQuester: climbing out of Taverley dungeon before the Karamja boat.", "yel");
      walker.walkTo(376, 3352, 2, 0);
      climbEither(376, 3352);
      return false;
    }
    if (coins() < 30
        && Math.max(Math.abs(c.currentX() - 436), Math.abs(c.currentY() - 751)) > 40) {
      c.log("AIOQuester: need 30 coins for the Karamja boat.", "yel");
      gatherCoins(30);
      return false;
    }
    walker.walkTo(436, 751, 2, 0);
    return true;
  }

  private void climbEither(int x, int y) {
    int before = c.currentY();
    walker.climb(x, y, secondClimb, 2);
    if (Math.abs(c.currentY() - before) < 400) secondClimb = !secondClimb;
    else secondClimb = false;
  }
}
