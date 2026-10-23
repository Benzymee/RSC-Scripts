package scripting.idlescript.SeattaScript.AIOQuester.quests;

import models.entities.ItemId;
import models.entities.Location;
import models.entities.NpcId;
import orsc.ORSCharacter;
import scripting.idlescript.SeattaScript.AIOQuester.QuestHandler;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuitReason;

public final class PrinceAliRescue extends QuestHandler {
  // COORDINATES FOR walkPath() PATHS AND pickupUnreachableItem()
  private final int GAME_TICK = 640;
  // OBJECT COORDINATES

  private final int[] AL_KHARID_COURTYARD_INNER = {71, 690};
  private final int[] AL_KHARID_COURTYARD_OUTER = {74, 683};
  private final int[][] AL_KHARID_COURTYARD_DOORS = {
    {71, 694}, // door one
    {67, 688}, // door two
    {77, 688} // door three
  };

  // ITEM IDS
  private final int COIN_ID = ItemId.COINS.getId();
  private final int PINK_SKIRT_ID = ItemId.PINK_SKIRT.getId(); // 2gp at The Clothes Shop
  private final int SHEARS_ID = ItemId.SHEARS.getId(); // 1 gp Lumbridge General Store
  private final int WOOL_ID = ItemId.WOOL.getId(); // free
  private final int ONION_ID = ItemId.ONION.getId(); // free
  private final int REDBERRIES_ID = ItemId.REDBERRIES.getId(); // free, varrock
  private final int POT_ID = ItemId.POT.getId();
  private final int FLOUR_ID = ItemId.FLOUR.getId();
  private final int POT_OF_FLOUR_ID = ItemId.POT_OF_FLOUR.getId();
  private int potStand;
  private final int GRAIN_ID = ItemId.GRAIN.getId();
  private final int BUCKET_ID = ItemId.BUCKET.getId(); // free, lumbridge house
  private final int ASHES_ID = ItemId.ASHES.getId(); // free, draynor village
  private final int BRONZE_PICKAXE_ID = ItemId.BRONZE_PICKAXE.getId(); // free, draynor village
  private final int CLAY_ID = ItemId.CLAY.getId(); // free
  private final int TIN_ID = ItemId.TIN_ORE.getId();
  private final int COPPER_ID = ItemId.COPPER_ORE.getId(); // make
  private final int BEER_ID = ItemId.BEER.getId(); // 6gp total
  private final int YELLOWDYE_ID = ItemId.YELLOWDYE.getId(); // 9gp for all quest items
  private final int WIG_ID = ItemId.WOOL_WIG.getId(); //
  private final int WATER_ID = ItemId.BUCKET_OF_WATER.getId(); //

  // NPC IDS
  private final int HASSAN_ID = NpcId.HASSAN.getId();
  private final int OSMAN_ID = NpcId.OSMAN.getId();
  // NPC DIALOGS
  private final String[] STEP_ONE_START_QUEST_DIALOG = {
    "Can I help you? You must need some help here in the desert."
  };
  private final String[] STEP_TWO_QUEST_DIALOG = {
    "Okay, I better go find some things",
    "And the final thing you need?",
    "What is needed second?",
    "What is first thing I must do?"
  };
  private final String[] NED_DIALOG_ROPE = {
    "Yes, I would like some Rope", "I have some balls of wool. could you make me some Rope?"
  };
  private final String[] NED_DIALOG_WIG = {
    "Ned, could you make other things from wool?",
    "How about some sort of a wig?",
    "I have that now. Please, make me a wig"
  };
  private final String[] AGGIE_DIALOG_DYE = {
    "Can you make dyes for me please",
    "What do you need to make some yellow dye please",
    "Okay, make me some yellow dye please"
  };
  private final String[] AGGIE_DIALOG_PASTE = {
    "Could you think of a way to make pink skin paste", "Yes please, mix me some skin paste"
  };
  private final String[] LADY_KELI_DIALOG_ONE = {
    "Heard of you? you are famous in Runescape!",
    "What is your latest plan then?",
    "Can you be sure they will not try to get him out?",
    "Could I see the key please",
    "Could I touch the key for a moment please"
  };
  private final String[] OSMAN_DIALOG_TWO = {"Thank you, I will try to find the other items"};
  private final String[] LEELA_DIALOG = {"I hoped to get him drunk"};
  private final String[] JOE_DIALOG = {"I have some beer here, fancy one?"};

  private void RaiseGP_VARROCK() {
    CURRENT_QUEST_STEP = "Getting paid";
    int[] VARROCK_HOUSE_DOOR = {108, 530};
    int LEATHER_ARMOUR_ID = ItemId.LEATHER_ARMOUR.getId();
    int[] CLOTHES_SHOP_DOOR = {136, 514};
    int[] THESSALIA_ARRAY_ID = {NpcId.THESSALIA.getId()};
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
    c.sleep(640); // should now have enough GP
  }

  private void pickOnions() {
    CURRENT_QUEST_STEP = "Picking onion";
    if (c.getUnnotedInventoryItemCount(ONION_ID) < 2) {
      walkTowards(Location.LUMBRIDGE_ONION_FIELD);
      c.sleep(GAME_TICK * 2);
      while (running() && c.getUnnotedInventoryItemCount(ONION_ID) < 2) {
        c.sleep(320);
        CURRENT_QUEST_STEP = "Retrieving Onion";
        int[] OIL_CAN_LOCATION = c.getNearestItemById(ONION_ID);
        if (OIL_CAN_LOCATION != null && c.getUnnotedInventoryItemCount(ONION_ID) < 2) {
          c.pickupItem(OIL_CAN_LOCATION[0], OIL_CAN_LOCATION[1], ONION_ID, true, true);
          c.sleep(640);
        }
      }
    }
  }

  private void RaiseGP_Lumbridge() {
    CURRENT_QUEST_STEP = "Getting monies";
    int IRON_DAGGER_ID = ItemId.IRON_DAGGER.getId();
    int[] SHOPKEEPERS = {55, 83};
    int[][] SELL_ARRAY = {{IRON_DAGGER_ID, 1}};
    walkTowards(Location.LUMBRIDGE_GOBLIN_HUT);
    c.sleep(GAME_TICK * 2);
    while (running() && c.getUnnotedInventoryItemCount(IRON_DAGGER_ID) < 1) {
      c.pickupItem(IRON_DAGGER_ID);
      c.sleep(GAME_TICK * 2);
    }
    walkTowards(Location.LUMBRIDGE_GENERAL_STORE);
    openShopThenSell(SHOPKEEPERS, SELL_ARRAY);
  }

  private boolean getUsablePickaxe() {
    boolean usablePickaxeFound = false;
    int[][] all_pickaxes = {
      {41, 1262}, {31, 1261}, {21, 1260}, {6, 1259}, {1, 1258}, {1, 156}
    }; // {levelReq, itemId}
    int[] worn_equipment = c.getEquippedItemIds();
    int mining_level = c.getCurrentStat(14);
    for (int[] pickaxe : all_pickaxes) {

      c.log("Checking for item:" + " " + c.getItemName(pickaxe[1]));

      if (mining_level >= pickaxe[0] && c.getBankItemCount(pickaxe[1]) >= 1 && c.isInBank()) {
        c.log("Found usable Pickaxe in bank:");
        c.log(c.getItemName(pickaxe[1]));
        usablePickaxeFound = true;
        c.withdrawItem(pickaxe[1]);
        c.sleep(GAME_TICK * 4);
        c.equipItemById(pickaxe[1]);
        break;
      }
      if (mining_level >= pickaxe[0] && c.getUnnotedInventoryItemCount(pickaxe[1]) >= 1) {
        c.log("Found usable Pickaxe:");
        c.log(c.getItemName(pickaxe[1]));
        usablePickaxeFound = true;
        c.equipItemById(pickaxe[1]);
        break;
      }
      for (int equipment : worn_equipment) {
        if (mining_level >= pickaxe[0] && equipment == pickaxe[1]) {
          c.log("Found usable Pickaxe already worn:");
          c.log(c.getItemName(equipment));
          usablePickaxeFound = true;
          break;
        }
      }
      if (usablePickaxeFound) {
        return true;
      }
    }
    if (!usablePickaxeFound) {
      c.log("No usable pickaxe found");
      return false;
    }
    return false;
  }

  private boolean checkUsablePickaxe() {
    boolean usablePickaxeFound = false;
    int[][] all_pickaxes = {
      {41, 1262}, {31, 1261}, {21, 1260}, {6, 1259}, {1, 1258}, {1, 156}
    }; // {levelReq, itemId}
    int[] worn_equipment = c.getEquippedItemIds();
    int mining_level = c.getCurrentStat(14);
    for (int[] pickaxe : all_pickaxes) {

      c.log("Checking for item:" + " " + c.getItemName(pickaxe[1]));

      if (mining_level >= pickaxe[0] && c.getUnnotedInventoryItemCount(pickaxe[1]) >= 1) {
        c.log("Found usable Pickaxe:");
        c.log(c.getItemName(pickaxe[1]));
        usablePickaxeFound = true;
        c.equipItemById(pickaxe[1]);
        break;
      }
      for (int equipment : worn_equipment) {
        if (mining_level >= pickaxe[0] && equipment == pickaxe[1]) {
          c.log("Found usable Pickaxe already worn:");
          c.log(c.getItemName(equipment));
          usablePickaxeFound = true;
          break;
        }
      }
      if (usablePickaxeFound) {
        return true;
      }
    }
    if (!usablePickaxeFound) {
      c.log("No usable pickaxe found");
      return false;
    }
    return false;
  }
  // int[] equipped_items = c.getEquippedItemIds();
  // for (int equippedItem : equipped_items) {
  // c.log(Integer.toString(equippedItem));
  // }
  // c.log(Integer.toString(c.getCurrentStat(14)));

  private void buyShears() {
    CURRENT_QUEST_STEP = "Buying snippers";
    int[] SHOPKEEPERS = {55, 83};
    int[][] BUY_ARRAY = {{SHEARS_ID, 1, 2}};
    if (c.getInventoryItemCount(COIN_ID) < 1) {
      RaiseGP_Lumbridge();
    }
    walkTowards(Location.LUMBRIDGE_GENERAL_STORE);
    openShopThenBuy(SHOPKEEPERS, BUY_ARRAY);
  }

  private void buySkirt() {
    CURRENT_QUEST_STEP = "Buying hot fashion";
    int[] clothes_shop = new int[] {NpcId.THESSALIA.getId()};
    int[][] skirt_buy = {{PINK_SKIRT_ID, 1, 5}};
    walkTowards(Location.VARROCK_CLOTHES_SHOP);
    openShopThenBuy(clothes_shop, skirt_buy);
  }

  private void buyBeer() {
    CURRENT_QUEST_STEP = "Buying three beer";
    String[] BUY_BEER = {"A glass of your finest ale please"};
    int BARTENDER_ID = NpcId.BARTENDER_VARROCK.getId();
    if (c.getUnnotedInventoryItemCount(BEER_ID) < 3) {
      walkTowards(Location.VARROCK_BLUE_MOON_INN);
      while (running() && c.getUnnotedInventoryItemCount(BEER_ID) < 3) {
        if (c.getInventoryItemCount(COIN_ID) < 2) {
          RaiseGP_VARROCK();
        }
        followNPCDialog(BARTENDER_ID, BUY_BEER);
        c.sleep(GAME_TICK * 4);
      }
    }
  }

  private void getYarn() {
    CURRENT_QUEST_STEP = "Getting 7 yarn";
    int SHEEP_ID = NpcId.SHEEP.getId();
    if (c.getUnnotedInventoryItemCount(SHEARS_ID) == 0) {
      buyShears();
    }
    walkTowards(Location.LUMBRIDGE_SHEEP_PEN);

    while (running() && c.getUnnotedInventoryItemCount(WOOL_ID) < 7) {
      useItemOnNearestNpcId(SHEEP_ID, SHEARS_ID);
      c.sleep(GAME_TICK);
      if (c.isBatching()) {
        c.stopBatching();
        c.sleep(GAME_TICK);
      }
    }

    while (running() && c.getUnnotedInventoryItemCount(WOOL_ID) > 7) {
      c.dropItem(c.getInventoryItemSlotIndex(WOOL_ID), 1);
      c.sleep(GAME_TICK * 2);
      if (c.getUnnotedInventoryItemCount(SHEARS_ID) > 0) {
        c.dropItem(c.getInventoryItemSlotIndex(SHEARS_ID), 1);
        c.sleep(GAME_TICK * 2);
      }
    }
    walkTowards(Location.LUMBRIDGE_CASTLE_KITCHEN);

    while (running() && c.currentY() < 700) {
      c.atObject(139, 666);
      c.sleep(GAME_TICK * 4);
    }

    while (running() && c.currentY() < 2000) {
      c.atObject(138, 1612);
      c.sleep(GAME_TICK * 4);
    }

    while (running() && c.currentY() > 2000 && c.getUnnotedInventoryItemCount(WOOL_ID) > 0) {
      c.useItemIdOnObject(139, 2554, WOOL_ID);
      c.sleep(GAME_TICK * 4);
    }

    while (running() && c.currentY() > 2000) {
      c.atObject(138, 2556);
      c.sleep(GAME_TICK * 4);
    }

    while (running() && c.currentY() > 1600) {
      c.atObject(139, 1610);
      c.sleep(GAME_TICK * 4);
    }
  }

  private void getPickaxe() {
    walkTowards(Location.BARBARIAN_VILLAGE_COAL_MINE);
    while (running() && c.getUnnotedInventoryItemCount(BRONZE_PICKAXE_ID) < 1) {
      CURRENT_QUEST_STEP = "Getting pickaxe";
      walkTo(231, 509);
      c.sleep(GAME_TICK * 2);
      int[] OIL_CAN_LOCATION = c.getNearestItemById(BRONZE_PICKAXE_ID);
      if (OIL_CAN_LOCATION != null && c.getUnnotedInventoryItemCount(BRONZE_PICKAXE_ID) < 2) {
        c.pickupItem(OIL_CAN_LOCATION[0], OIL_CAN_LOCATION[1], BRONZE_PICKAXE_ID, true, true);
        c.sleep(640);
      }
    }
  }

  private void mineOres() {
    CURRENT_QUEST_STEP = "Getting ores";
    walkTowards(Location.VARROCK_WEST_MINE);
    while (running() && c.getUnnotedInventoryItemCount(CLAY_ID) < 1) {
      if (c.isInCombat()) {
        c.walkTo(c.currentX(), c.currentY());
      }
      c.atObject(161, 534);
      c.stopBatching();
      c.sleep(GAME_TICK * 2);
      if (c.getUnnotedInventoryItemCount(CLAY_ID) > 1) {
        c.dropItem(c.getInventoryItemSlotIndex(CLAY_ID), 1);
        c.sleep(GAME_TICK * 2);
      }
    }
    while (running() && c.getUnnotedInventoryItemCount(TIN_ID) < 1) {
      if (c.isInCombat()) {
        c.walkTo(c.currentX(), c.currentY());
      }
      c.atObject(161, 535);
      c.stopBatching();
      c.sleep(GAME_TICK * 2);
      if (c.getUnnotedInventoryItemCount(TIN_ID) > 1) {
        c.dropItem(c.getInventoryItemSlotIndex(TIN_ID), 1);
        c.sleep(GAME_TICK * 2);
      }
    }
    walkTowards(Location.VARROCK_EAST_MINE);
    while (running() && c.getUnnotedInventoryItemCount(COPPER_ID) < 1) {
      if (c.isInCombat()) {
        c.walkTo(c.currentX(), c.currentY());
      }
      c.atObject(76, 547);
      c.stopBatching();
      c.sleep(GAME_TICK * 2);
      if (c.getUnnotedInventoryItemCount(COPPER_ID) > 1) {
        c.dropItem(c.getInventoryItemSlotIndex(COPPER_ID), 1);
        c.sleep(GAME_TICK * 2);
      }
    }
    while (running() && c.getUnnotedInventoryItemCount(REDBERRIES_ID) < 1) {
      c.pickupItem(REDBERRIES_ID);
      c.sleep(GAME_TICK);
      if (c.getUnnotedInventoryItemCount(REDBERRIES_ID) > 1) {
        c.dropItem(c.getInventoryItemSlotIndex(REDBERRIES_ID), 1);
      }
    }
  }

  private void smelt() {
    walkTowards(Location.LUMBRIDGE_FURNACE);
    c.useItemIdOnObject(130, 626, COPPER_ID);
    c.sleep(GAME_TICK * 4);
  }

  private void bucketFilling() {
    if (c.getUnnotedInventoryItemCount(ItemId.SOFT_CLAY.getId()) > 0
        || c.getUnnotedInventoryItemCount(ItemId.KEYPRINT.getId()) > 0) return;
    CURRENT_QUEST_STEP = "Making soft clay";
    walkTowards(Location.LUMBRIDGE_GENERAL_STORE);
    walkTo(118, 649);
    c.sleep(GAME_TICK);
    c.useItemIdOnObject(117, 650, BUCKET_ID);
    c.sleep(GAME_TICK * 4);
    c.useItemOnItemBySlot(
        c.getInventoryItemSlotIndex(WATER_ID), c.getInventoryItemSlotIndex(CLAY_ID));
    c.sleep(GAME_TICK * 4);
    c.useItemIdOnObject(117, 650, BUCKET_ID);
    c.sleep(GAME_TICK * 4);
  }

  private void getAsh() {
    CURRENT_QUEST_STEP = "Stealing ash from a scientist";
    int[] DRAYNOR_MANOR_DOOR_ONE = {210, 556};
    int[] DRAYNOR_MANOR_DOOR_TWO = {210, 553}; // wall object door
    int[] DRAYNOR_MANOR_DOOR_THREE = {213, 545};
    int[] DRAYNOR_MANOR_DOOR_FOUR = {199, 551};
    int[] DRAYNOR_MANOR_F2_DOOR_ONE = {207, 1495};
    int[] DRAYNOR_MANOR_F2_DOOR_TWO = {209, 1497};
    int[] DRAYNOR_MANOR_F3_DOOR = {211, 2438};
    int[] DRAYNOR_MANOR_STAIRS_UP = {210, 547};
    int[] DRAYNOR_MANOR_STAIRS_DOWN = {210, 1491};
    int[] DRAYNOR_MANOR_LADDER_UP = {215, 1492};
    int[] DRAYNOR_MANOR_LADDER_DOWN = {215, 2436};

    walkTowards(Location.DRAYNOR_MANOR_ENTRANCE);
    c.openDoor(DRAYNOR_MANOR_DOOR_ONE[0], DRAYNOR_MANOR_DOOR_ONE[1]);
    walkTo(210, 553); // directly infront of Manor entrance closed door
    c.sleep(2000);
    c.atWallObject(DRAYNOR_MANOR_DOOR_TWO[0], DRAYNOR_MANOR_DOOR_TWO[1]);
    c.sleep(3000);
    walkTo(210, 550); // directly infront of stairs up
    c.atObject(DRAYNOR_MANOR_STAIRS_UP[0], DRAYNOR_MANOR_STAIRS_UP[1]);
    c.sleep(1280);
    c.openDoor(DRAYNOR_MANOR_F2_DOOR_ONE[0], DRAYNOR_MANOR_F2_DOOR_ONE[1]);
    c.sleep(640);
    c.openDoor(DRAYNOR_MANOR_F2_DOOR_TWO[0], DRAYNOR_MANOR_F2_DOOR_TWO[1]);
    c.sleep(640);
    c.sleep(640);
    walkTo(215, 1491);
    c.sleep(640); // directly infront of ladder up
    c.atObject(DRAYNOR_MANOR_LADDER_UP[0], DRAYNOR_MANOR_LADDER_UP[1]);
    c.sleep(1280);
    c.openDoor(DRAYNOR_MANOR_F3_DOOR[0], DRAYNOR_MANOR_F3_DOOR[1]);
    c.sleep(640);

    while (running() && c.getUnnotedInventoryItemCount(ASHES_ID) == 0) {
      c.pickupItem(ASHES_ID);
      c.sleep(GAME_TICK);
      if (c.getUnnotedInventoryItemCount(ASHES_ID) > 1) {
        c.dropItem(c.getInventoryItemSlotIndex(ASHES_ID), 1);
      }
    }

    walkTo(215, 2435); // directly beside ladder
    c.atObject(DRAYNOR_MANOR_LADDER_DOWN[0], DRAYNOR_MANOR_LADDER_DOWN[1]);
    c.sleep(1280);
    walkTo(210, 1490); // directly beside staircase entrance
    c.atObject(DRAYNOR_MANOR_STAIRS_DOWN[0], DRAYNOR_MANOR_STAIRS_DOWN[1]);
    c.sleep(1280);
    c.openDoor(DRAYNOR_MANOR_DOOR_THREE[0], DRAYNOR_MANOR_DOOR_THREE[1]);
    c.sleep(640);
    c.sleep(640);
    walkTo(208, 544); // spade spawn
    walkTo(197, 554); // spade spawn
    walkTo(DRAYNOR_MANOR_DOOR_FOUR[0], DRAYNOR_MANOR_DOOR_FOUR[1]); // wall object door
    c.sleep(1280);
    c.atWallObject(DRAYNOR_MANOR_DOOR_FOUR[0], DRAYNOR_MANOR_DOOR_FOUR[1]); // should be outside now
    c.sleep(2400);
    walkTo(204, 539); // around back 1
    c.sleep(640);
    walkTo(215, 538); // around back 2
    c.sleep(640);
    walkTo(226, 546); // around back 3
    c.sleep(640);
    walkTo(229, 552); // beside compost
    c.sleep(640);
    c.sleep(640);
    walkTo(227, 564); // beside fountain
    c.sleep(640);
    walkTo(222, 562);
    c.sleep(640);
    walkTo(216, 562);
  }

  /**
   * The Lumbridge pot sits on the kitchen table. Walking onto that tile never arrives, and a take
   * from the doorway is out of range, so stand on a neighbouring tile and pick it up from there.
   */
  private void takeKitchenPot() {
    CURRENT_QUEST_STEP = "Retrieving Pot";
    int[] pot = c.getNearestItemById(POT_ID);
    if (pot == null) {
      if (!Location.LUMBRIDGE_CASTLE_KITCHEN.isAtLocation())
        walkTowards(Location.LUMBRIDGE_CASTLE_KITCHEN);
      else c.sleep(600);
      return;
    }
    int[][] beside = {
      {pot[0] + 1, pot[1]},
      {pot[0] - 1, pot[1]},
      {pot[0], pot[1] + 1},
      {pot[0], pot[1] - 1}
    };
    int[] stand = beside[Math.floorMod(potStand, beside.length)];
    if (c.currentX() != stand[0] || c.currentY() != stand[1]) {
      if (!walkTo(stand[0], stand[1])) potStand++;
      return;
    }
    int before = c.getUnnotedInventoryItemCount(POT_ID);
    c.pickupItem(pot[0], pot[1], POT_ID, false, true);
    c.sleep(900);
    if (c.getUnnotedInventoryItemCount(POT_ID) <= before) potStand++;
  }

  private void makeFlour() {

    if (c.getUnnotedInventoryItemCount(POT_ID) == 0
        && c.getUnnotedInventoryItemCount(POT_OF_FLOUR_ID) == 0) {
      while (running() && c.getUnnotedInventoryItemCount(POT_ID) < 1) takeKitchenPot();
    }
    while (running() && c.getUnnotedInventoryItemCount(POT_OF_FLOUR_ID) == 0) {
      walkTowards(Location.LUMBRIDGE_WHEAT_FIELD);
      if (c.atObject2(172, 605)) {
        c.sleep(GAME_TICK);
        c.stopBatching();
      }
      c.sleep(GAME_TICK * 2);
      while (running() && c.getUnnotedInventoryItemCount(GRAIN_ID) > 1) {
        c.dropItem(c.getInventoryItemSlotIndex(GRAIN_ID), 1);
        c.sleep(GAME_TICK);
      }
      c.openDoor(166, 604);
      // Each ladder click has to finish before the next walk. walkTo onto the floor below
      // makes the walker climb down by itself, so the account rides the ladder and never
      // reaches the hopper.
      while (running() && c.currentY() < 1500) {
        walkTo(164, 598);
        c.atObject(165, 598);
        c.sleepUntil(() -> c.currentY() >= 1500, 5000);
      }
      while (running() && c.currentY() >= 1500 && c.currentY() < 2400) {
        walkTo(165, 1546);
        c.atObject(166, 1546);
        c.sleepUntil(() -> c.currentY() >= 2400, 5000);
      }
      while (running() && c.getInventoryItemCount(GRAIN_ID) != 0 && c.currentY() >= 2400) {
        walkTo(166, 2486);
        c.sleep(GAME_TICK * 4);
        c.useItemSlotOnObject(166, 2487, c.getUnnotedInventoryItemSlotIndex(GRAIN_ID));
        c.sleep(GAME_TICK * 4);
        c.atObject(166, 2487);
        c.sleep(GAME_TICK * 4);
      }
      while (running() && c.currentY() >= 2400) {
        walkTo(165, 2490);
        c.atObject(166, 2490);
        c.sleepUntil(() -> c.currentY() < 2400, 5000);
      }
      while (running() && c.currentY() >= 1500 && c.currentY() < 2400) {
        walkTo(164, 1542);
        c.atObject(165, 1542);
        c.sleepUntil(() -> c.currentY() < 1500, 5000);
      }
      while (running()
          && c.currentY() < 1500
          && c.getUnnotedInventoryItemCount(POT_OF_FLOUR_ID) < 1) {
        walkTo(166, 598);
        c.useItemOnGroundItem(166, 599, POT_ID, FLOUR_ID);
        c.sleep(GAME_TICK * 4);
      }
    }
  }

  private void Ned() {
    CURRENT_QUEST_STEP = "Visiting a sailor";
    walkTowards(Location.DRAYNOR_NEDS_HOUSE);
    followNPCDialog(NpcId.NED.getId(), NED_DIALOG_ROPE);
    c.sleep(GAME_TICK * 4);
    followNPCDialog(NpcId.NED.getId(), NED_DIALOG_WIG);
    c.sleep(GAME_TICK * 10);
  }

  private void Aggie() {
    CURRENT_QUEST_STEP = "Visiting a witch";
    walkTowards(Location.DRAYNOR_AGGIES_HOUSE);
    c.sleep(GAME_TICK * 4);
    followNPCDialog(NpcId.AGGIE.getId(), AGGIE_DIALOG_DYE);
    c.sleep(GAME_TICK * 10);
    followNPCDialog(NpcId.AGGIE.getId(), AGGIE_DIALOG_PASTE);
    c.sleep(GAME_TICK * 15);
    c.useItemOnItemBySlot(
        c.getUnnotedInventoryItemSlotIndex(YELLOWDYE_ID),
        c.getUnnotedInventoryItemSlotIndex(WIG_ID));
    c.sleep(GAME_TICK * 5);
  }

  private void pickupBucket() {
    CURRENT_QUEST_STEP = "Grabbing a bucket";
    walkTowards(Location.LUMBRIDGE_CHICKEN_PEN);
    while (running() && c.getUnnotedInventoryItemCount(BUCKET_ID) == 0) {
      c.pickupItem(BUCKET_ID);
      c.sleep(GAME_TICK);
    }
  }

  private void bankPrep() {
    CURRENT_QUEST_STEP = "Prepping inventory";
    walkTowards(83, 684);
    walkTo(87, 693);
    c.openBank();
    depositAllExceptQuestItems(); // was depositAll(): banked other quests' items
    c.withdrawItem(COIN_ID, 400);
    getUsablePickaxe();
  }

  /**
   * Stage 2 stays at 2 until the rescue. The imprint is a separate item: soft clay used on Keli's
   * key becomes the key print. Benno can already be carrying the clay while another account on
   * the same stage has already made the print, so this runs as soon as the clay is in hand.
   */
  private void imprintKey() {
    int print = ItemId.KEYPRINT.getId();
    int clay = ItemId.SOFT_CLAY.getId();
    if (c.getUnnotedInventoryItemCount(print) > 0
        || c.getUnnotedInventoryItemCount(ItemId.BRONZE_KEY.getId()) > 0) return;
    if (c.getUnnotedInventoryItemCount(clay) == 0 && !recoverQuestItem(clay, "soft clay")) return;
    CURRENT_QUEST_STEP = "Imprinting the key";
    walkTowards(Location.DRAYNOR_JAIL);
    c.sleep(GAME_TICK * 2);
    followNPCDialog(NpcId.LADY_KELI.getId(), LADY_KELI_DIALOG_ONE);
    c.sleep(GAME_TICK * 4);
  }

  private void getQuestKey() {
    CURRENT_QUEST_STEP = "Getting the door key";
    imprintKey();
    walkTowards(Location.AL_KHARID_BORDER_GATE);
    walkTowards(AL_KHARID_COURTYARD_OUTER[0], AL_KHARID_COURTYARD_OUTER[1]);
    followNPCDialog(OSMAN_ID, OSMAN_DIALOG_TWO);
    walkTowards(Location.DRAYNOR_NEDS_HOUSE);
    // Leela hands over the key Osman made (key_sent). The "get him drunk" menu is optional
    // flavour, so it is answered only if it comes up.
    followNPCDialog(NpcId.LEELA.getId(), LEELA_DIALOG);
    c.sleep(GAME_TICK * 2);
  }

  private void jailBreak() {
    walkTowards(Location.DRAYNOR_JAIL);
    followNPCDialog(NpcId.JOE.getId(), JOE_DIALOG);
    c.sleep(GAME_TICK * 5);
    while (running() && c.getUnnotedInventoryItemCount(ItemId.ROPE.getId()) > 0) {
      ORSCharacter LADY_KELI = c.getNearestNpcById(NpcId.LADY_KELI.getId(), false);
      if (LADY_KELI == null) break;
      c.useItemOnNpc(LADY_KELI.serverIndex, ItemId.ROPE.getId());
      c.sleep(GAME_TICK * 4);
    }

    c.sleep(GAME_TICK * 5);
    walkTo(198, 640);
    c.useItemOnWall(199, 640, c.getUnnotedInventoryItemSlotIndex(ItemId.BRONZE_KEY.getId()));
    c.sleep(GAME_TICK * 5);
    talkToNpcId(NpcId.PRINCE_ALI.getId());
    sleepUntilQuestStageChanges();
    c.sleep(GAME_TICK * 20);
  }

  private void completeQuest() {

    while (c.currentX() >= 199 && running()) {
      walkTo(199, 640);
      c.atWallObject(199, 640);
      c.sleep(GAME_TICK);
    }
    c.sleep(GAME_TICK);
    walkTowards(Location.AL_KHARID_BORDER_GATE);
    walkTowards(AL_KHARID_COURTYARD_INNER[0], AL_KHARID_COURTYARD_INNER[1]);
    talkToNpcId(HASSAN_ID);
    sleepUntilQuestStageChanges();
  }

  public void run() {

    while (isQuesting()) {

      switch (QUEST_STAGE) {
        case 0:
          walkTowards(AL_KHARID_COURTYARD_INNER[0], AL_KHARID_COURTYARD_INNER[1]);

          for (int[] door : AL_KHARID_COURTYARD_DOORS) {
            c.openDoor(door[0], door[1]);
            c.sleep(GAME_TICK * 2);
          }

          followNPCDialog(HASSAN_ID, STEP_ONE_START_QUEST_DIALOG);
          sleepUntilQuestStageChanges();
          break;

        case 1: //
          walkTowards(AL_KHARID_COURTYARD_OUTER[0], AL_KHARID_COURTYARD_OUTER[1]);
          followNPCDialog(OSMAN_ID, STEP_TWO_QUEST_DIALOG);
          sleepUntilQuestStageChanges();
          break;

        case 2: //
          STEP_ITEMS =
              new int[][] {
                {ItemId.WOOL_WIG.getId(), 1},
                {ItemId.BEER.getId(), 3},
                {ItemId.PINK_SKIRT.getId(), 1},
                {ItemId.SOFT_CLAY.getId(), 1},
                {ItemId.BRONZE_BAR.getId(), 1},
                {ItemId.PASTE.getId(), 1}
              };
          CURRENT_QUEST_STEP = "Finding quest items";
          // Imprint before the bank trip. The clay is already made on some accounts, and banking
          // used to deposit it, so the Keli step at the end of this list never saw it.
          imprintKey();
          bankPrep();
          pickupBucket();
          if (c.getInventoryItemCount(COIN_ID) < 300) {
            RaiseGP_VARROCK();
          }
          buySkirt();
          buyBeer();
          if (!checkUsablePickaxe()) getPickaxe();
          mineOres();
          smelt();
          if (c.getInventoryItemCount(COIN_ID) < 5) {
            RaiseGP_Lumbridge();
          }
          bucketFilling();
          getYarn();
          makeFlour();
          pickOnions();
          getAsh();
          Ned();
          Aggie();
          getQuestKey();
          jailBreak();
          break;

        case 3:
          completeQuest();
          break;
        default:
          quit(QuitReason.QUEST_STAGE_NOT_IN_SWITCH);
          break;
      }
    }
  }
}
