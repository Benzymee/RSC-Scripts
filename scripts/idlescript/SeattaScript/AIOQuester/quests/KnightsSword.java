package scripting.idlescript.SeattaScript.AIOQuester.quests;

import models.entities.ItemId;
import models.entities.NpcId;
import models.entities.SkillId;
import scripting.idlescript.SeattaScript.AIOQuester.QuestHandler;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuitReason;

/**
 * The Knight's Sword. The redberry pie is cooked from picked berries, milled flour and a pie dish
 * made from mined clay. Iron bars are mined and smelted. Blurite is mined in the ice dungeon.
 * Thurgo smiths the sword. Nothing is traded or taken from player-sold shop stock.
 */
public final class KnightsSword extends QuestHandler {
  private static final int PIE = ItemId.REDBERRY_PIE.getId();
  private static final int RAW_PIE = ItemId.UNCOOKED_REDBERRY_PIE.getId();
  private static final int BERRIES = ItemId.REDBERRIES.getId();
  private static final int DISH = ItemId.PIE_DISH.getId();
  private static final int UNFIRED_DISH = ItemId.UNFIRED_PIE_DISH.getId();
  private static final int FLOUR = ItemId.POT_OF_FLOUR.getId();
  private static final int POT = ItemId.POT.getId();
  private static final int GRAIN = ItemId.GRAIN.getId();
  private static final int CLAY = ItemId.CLAY.getId();
  private static final int SOFT = ItemId.SOFT_CLAY.getId();
  private static final int BUCKET = ItemId.BUCKET.getId();
  private static final int WATER = ItemId.BUCKET_OF_WATER.getId();
  private static final int PICK = ItemId.BRONZE_PICKAXE.getId();
  private static final int IRON_ORE = ItemId.IRON_ORE.getId();
  private static final int IRON_BAR = ItemId.IRON_BAR.getId();
  private static final int COPPER = ItemId.COPPER_ORE.getId();
  private static final int TIN = ItemId.TIN_ORE.getId();
  private static final int BLURITE = ItemId.BLURITE_ORE.getId();
  private static final int PORTRAIT = ItemId.PORTRAIT.getId();
  private static final int SWORD = ItemId.FALADIAN_KNIGHTS_SWORD.getId();
  private static final int SQUIRE = NpcId.SQUIRE.getId();
  private static final int THURGO = NpcId.THURGO.getId();

  @Override
  public void run() {
    hasWalkedToStart = true;
    while (isQuesting()) {
      int stage = QUEST_STAGE;
      if (stage == 0) {
        CURRENT_QUEST_STEP = "Taking the quest from the squire";
        walker.walkTo(311, 568, 2, 0);
        runDialogue(
            SQUIRE,
            new String[] {"So would these dwarves make another one?", "Ok I'll give it a go"},
            true,
            2);
      } else if (stage == 1) {
        CURRENT_QUEST_STEP = "Asking Reldo about the Imcando";
        walker.walkTo(128, 456, 2, 0);
        runDialogue(NpcId.RELDO.getId(), new String[] {"What do you know about the Imcando dwarves?"}, true, 2);
      } else if (stage == 2) {
        CURRENT_QUEST_STEP = "Taking a redberry pie to Thurgo";
        if (!carrying(PIE, 1)) {
          makePie();
          continue;
        }
        walker.walkTo(291, 713, 2, 0);
        runDialogue(
            THURGO,
            new String[] {"Hello are you are an Imcando Dwarf?", "Would you like some redberry pie?"},
            true,
            2);
      } else if (stage == 3 || (stage == 4 && !carrying(PORTRAIT, 1))) {
        if (stage == 3) {
          CURRENT_QUEST_STEP = "Asking Thurgo to make the sword";
          walker.walkTo(291, 713, 2, 0);
          runDialogue(THURGO, new String[] {"Can you make a sword for me?"}, true, 2);
        } else {
          CURRENT_QUEST_STEP = "Taking Sir Vyvin's portrait";
          if (c.currentY() < 944) {
            walker.walkTo(316, 568, 1, 0);
            walker.climb(317, 568, false, 4);
          } else if (c.currentY() < 2000) {
            walker.walkTo(318, 1512, 1, 0);
            walker.climb(319, 1513, false, 4);
          } else {
            walker.walkTo(318, 2454, 1, 0);
            atObject(318, 2454);
            c.sleep(800);
            if (!carrying(PORTRAIT, 1)) {
              atObject(318, 2454);
              c.sleep(1200);
            }
            if (!carrying(PORTRAIT, 1)) quit(QuitReason.VYVIN_DISTRACTION_NEEDED);
          }
        }
      } else if (stage == 4) {
        CURRENT_QUEST_STEP = "Giving Thurgo the portrait";
        if (c.currentY() > 1888) {
          walker.walkTo(319, 2456, 1, 0);
          walker.climb(319, 2457, false, 3);
          continue;
        }
        if (c.currentY() > 944 && c.currentX() >= 310 && c.currentX() <= 325) {
          walker.walkTo(318, 1512, 1, 0);
          walker.climb(317, 1512, false, 3);
          continue;
        }
        walker.walkTo(291, 713, 2, 0);
        runDialogue(THURGO, new String[0], true, 1);
      } else if (stage == 5 && !carrying(SWORD, 1)) {
        CURRENT_QUEST_STEP = "Mining blurite and smelting two iron bars";
        if (c.getInventoryItemCount(IRON_BAR) < 2) smeltIron();
        else if (!carrying(BLURITE, 1)) mineBlurite();
        else {
          walker.walkTo(291, 713, 2, 0);
          runDialogue(THURGO, new String[0], true, 1);
        }
      } else if (stage >= 5 && carrying(SWORD, 1)) {
        CURRENT_QUEST_STEP = "Returning the sword to the squire";
        walker.walkTo(311, 568, 2, 0);
        runDialogue(SQUIRE, new String[0], true, 1);
      } else {
        quit(QuitReason.QUEST_STAGE_NOT_IN_SWITCH);
        return;
      }
    }
  }

  private void makePie() {
    if (!carrying(BERRIES, 1)) {
      walker.walkTo(90, 548, 3, 0);
      atObject2(90, 548);
      c.sleep(1000);
      return;
    }
    if (!carrying(FLOUR, 1)) {
      millFlour();
      return;
    }
    if (!carrying(DISH, 1) && !carrying(RAW_PIE, 1)) {
      makePieDish();
      return;
    }
    if (!carrying(RAW_PIE, 1)) {
      useItemsTogether(DISH, FLOUR);
      c.sleep(800);
      useItemsTogether(DISH, BERRIES);
      c.sleep(800);
      return;
    }
    walker.walkTo(131, 659, 2, 0);
    c.useItemIdOnObject(131, 659, RAW_PIE);
    c.sleep(2000);
  }

  private void millFlour() {
    if (!carrying(POT, 1) && !carrying(FLOUR, 1)) {
      walker.walkTo(134, 660, 1, 0);
      int[] pot = c.getNearestItemById(POT);
      if (pot != null && pot[0] >= 133 && pot[0] <= 135) pickupGroundItem(POT, 1);
      else {
        if (coins() < 5) gatherCoins(5);
        else {
          walker.walkTo(133, 642, 3, 0);
          buyBaseStock(new int[] {NpcId.SHOPKEEPER_LUMBRIDGE.getId()}, POT, 3, 1);
        }
      }
      return;
    }
    if (!carrying(GRAIN, 1)) {
      walker.walkTo(173, 602, 2, 0);
      atObject2(173, 602);
      c.sleep(1000);
      return;
    }
    if (c.currentY() < 2000) {
      walker.walkTo(165, 598, 1, 0);
      atObject(165, 598);
      c.sleep(1000);
      if (c.currentY() > 944 && c.currentY() < 2000) atObject(166, 1546);
      return;
    }
    c.useItemIdOnObject(166, 2487, GRAIN);
    c.sleep(800);
    atObject(166, 2487);
    c.sleep(1200);
    atObject(166, 2490);
    c.sleep(1000);
    walker.walkTo(166, 599, 1, 0);
    c.useItemIdOnObject(166, 599, POT);
    c.sleep(1000);
  }

  private void makePieDish() {
    if (!ensurePick()) return;
    if (!carrying(CLAY, 1) && !carrying(SOFT, 1) && !carrying(UNFIRED_DISH, 1)) {
      walker.walkTo(308, 639, 2, 0);
      atObject(308, 639);
      c.sleep(2000);
      return;
    }
    if (carrying(CLAY, 1)) {
      if (!carrying(WATER, 1)) {
        if (!carrying(BUCKET, 1)) {
          walker.walkTo(121, 607, 2, 0);
          pickupGroundItem(BUCKET, 1);
        } else {
          walker.walkTo(137, 641, 1, 0);
          c.useItemIdOnObject(137, 641, BUCKET);
        }
        c.sleep(1000);
        return;
      }
      useItemsTogether(WATER, CLAY);
      c.sleep(800);
      return;
    }
    walker.walkTo(227, 524, 1, 0);
    if (carrying(SOFT, 1)) {
      c.useItemIdOnObject(227, 524, SOFT);
      c.sleep(1500);
      answerMenuIfPresent("Pie dish");
      return;
    }
    c.useItemIdOnObject(229, 523, UNFIRED_DISH);
    c.sleep(2000);
  }

  private boolean ensurePick() {
    if (carrying(PICK, 1) || c.isItemIdEquipped(PICK)) return true;
    if (coins() < 50) {
      gatherCoins(50);
      return false;
    }
    walker.walkTo(124, 669, 2, 0);
    buyBaseStock(new int[] {NpcId.BOB.getId()}, PICK, 5, 1);
    if (carrying(PICK, 1)) c.equipItemById(PICK);
    return carrying(PICK, 1) || c.isItemIdEquipped(PICK);
  }

  private void smeltIron() {
    if (!ensurePick()) return;
    int smithing = c.getBaseStat(SkillId.SMITHING.getId());
    int mining = c.getBaseStat(SkillId.MINING.getId());
    if (mining < 15) {
      CURRENT_QUEST_STEP = "Mining copper and tin until Mining is 15";
      walker.walkTo(308, 639, 3, 0);
      atObject(308, 640);
      c.sleep(2000);
      return;
    }
    if (c.getInventoryItemCount(IRON_ORE) < 2 - c.getInventoryItemCount(IRON_BAR) && smithing >= 15) {
      walker.walkTo(315, 640, 2, 0);
      atObject(315, 640);
      c.sleep(2000);
      return;
    }
    if (smithing < 15) {
      CURRENT_QUEST_STEP = "Smelting bronze until Smithing is 15";
      if (!carrying(COPPER, 1) || !carrying(TIN, 1)) {
        walker.walkTo(308, 639, 3, 0);
        atObject(carrying(COPPER, 1) ? 308 : 309, 639);
        c.sleep(2000);
        return;
      }
      walker.walkTo(309, 544, 2, 0);
      c.useItemIdOnObject(309, 544, COPPER);
      c.sleep(2000);
      return;
    }
    walker.walkTo(309, 544, 2, 0);
    c.useItemIdOnObject(309, 544, IRON_ORE);
    c.sleep(2500);
  }

  private void mineBlurite() {
    if (!ensurePick()) return;
    if (c.currentY() < 3000) {
      walker.walkTo(286, 711, 1, 0);
      atObject(286, 711);
      c.sleep(1500);
      return;
    }
    walker.walkTo(312, 3517, 1, 0);
    atObject(312, 3517);
    c.sleep(2500);
  }
}
