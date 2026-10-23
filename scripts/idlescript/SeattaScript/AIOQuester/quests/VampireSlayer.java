package scripting.idlescript.SeattaScript.AIOQuester.quests;

import models.entities.ItemId;
import models.entities.Location;
import models.entities.NpcId;
import orsc.ORSCharacter;
import scripting.idlescript.SeattaScript.AIOQuester.DraynorManor;
import scripting.idlescript.SeattaScript.AIOQuester.QuestHandler;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuitReason;

public final class VampireSlayer extends QuestHandler {
  // COORDINATES FOR walkPath() PATHS AND pickupUnreachableItem()

  // OBJECT COORDINATES

  private final int[] MORGAN_DOOR = {136, 514};
  private final int[] JOLLY_BOAR_DOOR = {81, 441};
  private final int[] DRAYNOR_MANOR_DOOR_ONE = {210, 556};
  private final int[] DRAYNOR_MANOR_DOOR_TWO = {210, 553}; // wall object
  private final int[] DRAYNOR_MANOR_STAIRS = {204, 551};
  private final int[] COUNT_COFFIN = {204, 3380};
  private final int[] VARROCK_HOUSE_DOOR = {108, 530};
  private final int[] CLOTHES_SHOP_DOOR = {136, 514};
  private final int[] GENSTORE_SHOP_DOOR = {130, 515};
  private final int[] LADDER_UP = {216, 620};
  private final int[] LADDER_DOWN = {216, 1564};
  private final int CUPBOARD_CLOSED_ID = 140;
  private final int CUPBOARD_OPEN_ID = 141;

  // ITEM IDS
  private final int GARLIC_ID = ItemId.GARLIC.getId();
  private final int STAKE_ID = ItemId.STAKE.getId();
  private final int BEER_ID = ItemId.BEER.getId();
  private final int HAMMER_ID = ItemId.HAMMER.getId();
  private final int COIN_ID = ItemId.COINS.getId();
  private final int LEATHER_ARMOUR_ID = ItemId.LEATHER_ARMOUR.getId();

  // NPC IDS
  private final int MORGAN_ID = NpcId.MORGAN.getId();
  private final int HARLOW_ID = NpcId.DR_HARLOW.getId();
  private final int COUNT_DRAYNOR_ID = NpcId.COUNT_DRAYNOR.getId();
  private final int BARTENDER_ID =
      NpcId.BARTENDER_JOLLY_BOAR.getId(); // 44 = server BARTENDER_OUTSIDE_VARROCK
  private final int[] THESSALIA_ARRAY_ID = {NpcId.THESSALIA.getId()};
  private final int[] GENSTORE_ARRAY_ID = {NpcId.SHOPKEEPER_VARROCK.getId()};

  // NPC DIALOGS
  private final String[] BUY_BEER = {"I'll have a beer please"};
  private final String[] STEP_ONE_START_QUEST_DIALOG = {"Ok I'm up for an adventure"};
  private final String[] STEP_TWO_QUEST_DIALOG = {"Morgan needs your help", "Ok mate"};

  private void RaiseGP() {
    walkTowards(Location.VARROCK_RUNE_SHOP);
    c.openDoor(VARROCK_HOUSE_DOOR[0], VARROCK_HOUSE_DOOR[1]);
    c.sleep(640);
    while (running()
        && !(c.getUnnotedInventoryItemCount(LEATHER_ARMOUR_ID) >= 1)
        && running()) { // we gonna be a gatherer
      int[] LEATHER_ARMOUR_LOCATION = c.getNearestItemById(LEATHER_ARMOUR_ID);
      if (LEATHER_ARMOUR_LOCATION != null
          && c.getUnnotedInventoryItemCount(LEATHER_ARMOUR_ID) == 0) {
        c.pickupItem(
            LEATHER_ARMOUR_LOCATION[0], LEATHER_ARMOUR_LOCATION[1], LEATHER_ARMOUR_ID, true, true);
        c.sleep(640);
      }
    }
    walkTowards(Location.VARROCK_CLOTHES_SHOP);
    c.openDoor(CLOTHES_SHOP_DOOR[0], CLOTHES_SHOP_DOOR[1]);
    c.sleep(640);
    if (openNpcShop(THESSALIA_ARRAY_ID)) {
      c.shopSell(LEATHER_ARMOUR_ID);
      c.sleep(640);
      c.closeShop();
    }
  }

  private void BuyHammer() {
    walkTowards(Location.VARROCK_GENERAL_STORE);
    c.openDoor(GENSTORE_SHOP_DOOR[0], GENSTORE_SHOP_DOOR[1]);
    c.sleep(640);
    buyBaseStock(GENSTORE_ARRAY_ID, HAMMER_ID, 5, 1);
  }

  private boolean inCrypt() {
    return DraynorManor.areaOf(c.currentX(), c.currentY()) == DraynorManor.Area.CRYPT;
  }

  public void run() {
    if (getQuestStage() == 2 && DraynorManor.inManor(c.currentX(), c.currentY())) {
      c.log(
          "Vampire Slayer: resuming inside the manor at "
              + DraynorManor.describe(c.currentX(), c.currentY()),
          "cya");
      hasWalkedToStart = true;
    }

    while (isQuesting()) {
      int stage = QUEST_STAGE;
      if (stage == 0) {
        CURRENT_QUEST_STEP = "Starting quest with Morgan";
        walker.walkTo(216, 619, 2, 0);
        runDialogue(MORGAN_ID, STEP_ONE_START_QUEST_DIALOG, true, 2);
      } else if (stage == 1) {
        CURRENT_QUEST_STEP = "Getting a stake from Dr Harlow";
        getStakeFromHarlow();
      } else if (stage == 2) {
        if (!haveStake()) {
          CURRENT_QUEST_STEP = "Getting a stake from Dr Harlow";
          getStakeFromHarlow();
        } else if (c.getInventoryItemCount(HAMMER_ID) < 1) {
          CURRENT_QUEST_STEP = "Buying a hammer";
          if (coins() < 5) gatherCoins(5);
          else BuyHammer();
        } else if (c.getInventoryItemCount(GARLIC_ID) < 1
            && !DraynorManor.inManor(c.currentX(), c.currentY())) {
          CURRENT_QUEST_STEP = "Taking garlic from Morgan's cupboard";
          takeGarlic();
        } else {
          CURRENT_QUEST_STEP = "Fighting Count Draynor";
          fightCount();
        }
      } else {
        quit(QuitReason.QUEST_STAGE_NOT_IN_SWITCH);
        return;
      }
    }
  }

  private boolean haveStake() {
    return c.getInventoryItemCount(STAKE_ID) > 0 || c.isItemIdEquipped(STAKE_ID);
  }

  private void getStakeFromHarlow() {
    if (c.getInventoryItemCount(BEER_ID) < 1) {
      if (coins() < 2) {
        gatherCoins(2);
        return;
      }
      walker.walkTo(82, 438, 2, 0);
      c.openDoor(JOLLY_BOAR_DOOR[0], JOLLY_BOAR_DOOR[1]);
      c.sleep(640);
      runDialogue(BARTENDER_ID, BUY_BEER, true, 2);
      return;
    }
    walker.walkTo(82, 438, 2, 0);
    c.openDoor(JOLLY_BOAR_DOOR[0], JOLLY_BOAR_DOOR[1]);
    c.sleep(640);
    runDialogue(HARLOW_ID, STEP_TWO_QUEST_DIALOG, true, 2);
  }

  private void takeGarlic() {
    if (c.currentY() < 944) {
      walker.walkTo(216, 619, 2, 0);
      walker.climb(LADDER_UP[0], LADDER_UP[1], false, 3);
      return;
    }
    int[] cup = c.getNearestObjectByIds(new int[] {CUPBOARD_CLOSED_ID, CUPBOARD_OPEN_ID});
    if (cup != null) {
      if (c.getObjectAtCoord(cup[0], cup[1]) == CUPBOARD_CLOSED_ID) {
        c.atObject(cup[0], cup[1]);
        c.sleep(1000);
      }
      c.atObject(cup[0], cup[1]);
      c.sleep(1200);
    }
    walker.climb(LADDER_DOWN[0], LADDER_DOWN[1], false, 3);
  }

  private void fightCount() {
    if (!inCrypt()) {
      if (!DraynorManor.inManor(c.currentX(), c.currentY())) {
        walker.walkTo(209, 561, 2, 0);
      }
      if (!walker.manorGoTo(DraynorManor.Area.CRYPT) || !inCrypt()) {
        c.log(
            "Vampire Slayer: didn't reach the manor crypt (at "
                + DraynorManor.describe(c.currentX(), c.currentY())
                + "), retrying",
            "red");
        return;
      }
    }
    if (c.getInventoryItemCount(HAMMER_ID) < 1) {
      c.log("Vampire Slayer: no hammer, the stake can't finish the Count", "red");
      return;
    }
    if (c.getInventoryItemCount(STAKE_ID) > 0 && !c.isItemIdEquipped(STAKE_ID)) {
      c.equipItemById(STAKE_ID);
      waitFor(() -> c.isItemIdEquipped(STAKE_ID), 5000);
    }
    if (!c.isItemIdEquipped(STAKE_ID)) {
      c.log("Vampire Slayer: stake is not equipped, the Count will regenerate", "red");
      return;
    }
    ORSCharacter count = c.getNearestNpcById(COUNT_DRAYNOR_ID, true);
    if (count == null) {
      CURRENT_QUEST_STEP = "Opening the coffin";
      c.atObject(COUNT_COFFIN[0], COUNT_COFFIN[1]);
      c.sleep(1920);
      return;
    }
    if (!readyToFight()) return;
    if (!c.isInCombat()) {
      c.attackNpc(count.serverIndex);
      c.sleep(1280);
    } else {
      c.sleep(640);
    }
  }
}
