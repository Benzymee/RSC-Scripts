package scripting.idlescript.SeattaScript.AIOQuester.quests;

import models.entities.ItemId;
import models.entities.NpcId;
import orsc.ORSCharacter;
import scripting.idlescript.SeattaScript.AIOQuester.QuestHandler;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuitReason;

/**
 * Goblin Diplomacy. Mail is dropped by goblins. Dyes are made by Aggie from redberries, onions
 * and woad. Woad is bought from Wyson (his own leaves, 20 coins). Nothing is bought from a
 * player-stocked shop.
 */
public final class GoblinDiplomacy extends QuestHandler {
  private static final int MAIL = ItemId.GOBLIN_ARMOUR.getId();
  private static final int ORANGE = ItemId.ORANGE_GOBLIN_ARMOUR.getId();
  private static final int BLUE = ItemId.BLUE_GOBLIN_ARMOUR.getId();
  private static final int RED = ItemId.REDDYE.getId();
  private static final int YELLOW = ItemId.YELLOWDYE.getId();
  private static final int BLUEDYE = ItemId.BLUEDYE.getId();
  private static final int ORANGEDYE = ItemId.ORANGEDYE.getId();
  private static final int BERRIES = ItemId.REDBERRIES.getId();
  private static final int ONION = ItemId.ONION.getId();
  private static final int WOAD = ItemId.WOAD_LEAF.getId();
  private static final int WARTFACE = NpcId.GENERAL_WARTFACE.getId();
  private static final int[] GOBLINS = {
    NpcId.GOBLIN_LVL7.getId(),
    NpcId.GOBLIN_LVL13.getId(),
    NpcId.GOBLIN_RED_ARMOUR_LVL13.getId(),
    NpcId.GOBLIN_GREEN_ARMOUR_LVL13.getId()
  };

  @Override
  public void run() {
    hasWalkedToStart = true;
    while (isQuesting()) {
      int stage = QUEST_STAGE;
      if (stage == 0) {
        CURRENT_QUEST_STEP = "Starting the quest with the Port Sarim bartender";
        walker.walkTo(255, 625, 2, 0);
        runDialogue(
            NpcId.BARTENDER_PORTSARIM.getId(),
            new String[] {"Not very busy in here today is it?"},
            true,
            2);
      } else if (stage == 1) {
        CURRENT_QUEST_STEP = "Offering to pick an armour colour";
        walker.walkTo(322, 449, 2, 0);
        runDialogue(WARTFACE, new String[] {"Do you want me to pick an armour colour for you?"}, true, 2);
      } else if (stage == 2) {
        CURRENT_QUEST_STEP = "Making orange goblin armour";
        if (!carrying(ORANGE, 1)) makeArmour(ORANGE);
        else handArmour();
      } else if (stage == 3) {
        CURRENT_QUEST_STEP = "Making blue goblin armour";
        if (!carrying(BLUE, 1)) makeArmour(BLUE);
        else handArmour();
      } else if (stage == 4) {
        CURRENT_QUEST_STEP = "Handing over plain goblin armour";
        if (!carrying(MAIL, 1)) killForMail(1);
        else handArmour();
      } else {
        quit(QuitReason.QUEST_STAGE_NOT_IN_SWITCH);
        return;
      }
    }
  }

  private void handArmour() {
    walker.walkTo(322, 449, 2, 0);
    runDialogue(WARTFACE, new String[0], true, 1);
  }

  private void makeArmour(int coloured) {
    int mailNeeded = coloured == ORANGE ? 2 : 1;
    int have = c.getInventoryItemCount(MAIL) + (coloured == ORANGE && carrying(ORANGE, 1) ? 1 : 0);
    if (c.getInventoryItemCount(MAIL) < mailNeeded && !carrying(coloured, 1)) {
      killForMail(mailNeeded);
      return;
    }
    if (coloured == ORANGE) {
      if (!carrying(ORANGEDYE, 1)) {
        if (!carrying(RED, 1)) {
          if (!carrying(BERRIES, 3)) pickBerries();
          else dye("red");
          return;
        }
        if (!carrying(YELLOW, 1)) {
          if (!carrying(ONION, 2)) pickOnions();
          else dye("yellow");
          return;
        }
        useItemsTogether(RED, YELLOW);
        c.sleep(1280);
        return;
      }
      useItemsTogether(ORANGEDYE, MAIL);
      c.sleep(1280);
      return;
    }
    if (!carrying(BLUEDYE, 1)) {
      if (!carrying(WOAD, 2)) buyWoad();
      else dye("blue");
      return;
    }
    useItemsTogether(BLUEDYE, MAIL);
    c.sleep(1280);
  }

  private void killForMail(int want) {
    CURRENT_QUEST_STEP = "Killing goblins for armour";
    if (c.isInCombat()) {
      c.sleep(640);
      return;
    }
    int dx = Math.abs(c.currentX() - 322);
    int dy = Math.abs(c.currentY() - 449);
    if ((dx > 8 || dy > 8) && c.getNearestNpcByIds(GOBLINS, false) == null) {
      walker.walkTo(322, 449, 8, 0);
      return;
    }
    while (running() && c.getInventoryItemCount(MAIL) < want) {
      int[] drop = c.getNearestItemById(MAIL);
      if (drop != null) {
        withTurn("goblin-mail", () -> {
          pickupGroundItem(MAIL, 1);
          return true;
        });
        return;
      }
      if (c.isInCombat()) {
        c.sleep(640);
        return;
      }
      ORSCharacter goblin = c.getNearestNpcByIds(GOBLINS, false);
      if (goblin == null) {
        waitPolitely("a goblin");
        return;
      }
      if (!readyToFight()) return;
      c.attackNpc(goblin.serverIndex);
      c.sleep(1280);
      return;
    }
  }

  private void pickBerries() {
    walker.walkTo(90, 548, 3, 0);
    atObject2(90, 548);
    c.sleep(1200);
    if (!carrying(BERRIES, 1)) {
      int[] ground = c.getNearestItemById(BERRIES);
      if (ground != null) pickupGroundItem(BERRIES, 1);
      else waitPolitely("redberries");
    }
  }

  private void pickOnions() {
    walker.walkTo(327, 633, 3, 0);
    int[] ground = c.getNearestItemById(ONION);
    if (ground != null) pickupGroundItem(ONION, 1);
    else {
      atObject2(327, 633);
      c.sleep(1200);
    }
  }

  private void buyWoad() {
    if (coins() < 20) {
      gatherCoins(20);
      return;
    }
    walker.walkTo(292, 545, 3, 0);
    runDialogue(
        NpcId.WYSON_THE_GARDENER.getId(),
        new String[] {"I'm looking for woad leaves", "How about 20 coins?"},
        true,
        2);
  }

  private void dye(String colour) {
    if (coins() < 5) {
      gatherCoins(5);
      return;
    }
    walker.walkTo(223, 626, 2, 0);
    runDialogue(
        NpcId.AGGIE.getId(),
        new String[] {
          "Can you make dyes for me please",
          "What do you need to make some " + colour + " dye please",
          "Okay, make me some " + colour + " dye please"
        },
        true,
        2);
  }
}
