package scripting.idlescript.SeattaScript.AIOQuester.quests;

import models.entities.ItemId;
import models.entities.NpcId;
import orsc.ORSCharacter;
import scripting.idlescript.SeattaScript.AIOQuester.QuestHandler;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuitReason;

/**
 * Plague City. No skill requirement and no combat. Dwellberries, the picture and the spade are
 * picked up. Rope is bought from Ned for 15 coins. Chocolate dust is bought from Hudo's own
 * stock. Milk is from a cow and snape grass is picked. The hangover cure is mixed by the account.
 *
 * <p>Stages, doors and lines are the local PlagueCity plugin and DoorAction. Four buckets of
 * water soften the mud at 620,581. The grill is 636,3422. Elena's key is in the barrel at
 * 639,3440 and the gate is 637,3447.
 */
public final class PlagueCity extends QuestHandler {
  private static final int BUCKET = ItemId.BUCKET.getId();
  private static final int WATER = ItemId.BUCKET_OF_WATER.getId();
  private static final int SPADE = ItemId.SPADE.getId();
  private static final int ROPE = ItemId.ROPE.getId();
  private static final int BERRIES = ItemId.DWELLBERRIES.getId();
  private static final int MASK = ItemId.GASMASK.getId();
  private static final int PICTURE = ItemId.PICTURE.getId();
  private static final int BOOK = ItemId.PLAGUE_CITY_BOOK.getId();
  private static final int MILK = ItemId.MILK.getId();
  private static final int DUST = ItemId.CHOCOLATE_DUST.getId();
  private static final int CHOC_MILK = ItemId.CHOCOLATY_MILK.getId();
  private static final int SNAPE = ItemId.SNAPE_GRASS.getId();
  private static final int CURE = ItemId.HANGOVER_CURE.getId();
  private static final int WARRANT = ItemId.WARRANT.getId();
  private static final int KEY = ItemId.LITTLE_KEY.getId();
  private static final int EDMOND = NpcId.EDMOND.getId();
  private static final int ALRENA = NpcId.ALRENA.getId();
  private static final int JETHICK = NpcId.JETHICK.getId();
  private static final int MILLI = NpcId.MILLI_REHNISON.getId();
  private static final int BRAVEK = NpcId.BRAVEK.getId();
  private static final int ELENA = NpcId.ELENA.getId();
  private static final int MOURNER = NpcId.MOURNER_WESTARDOUGNE.getId();
  private static final int HUDO = NpcId.HUDO_GLENFAD.getId();
  private static final int NED = NpcId.NED.getId();
  private static final int COW = NpcId.COW_ATTACKABLE.getId();
  private static final int[][] BERRY_TILES = {{564, 443}, {544, 453}, {566, 457}, {544, 460}};

  /** How many buckets have been poured since the soil was last found still hard. */
  private int pours = 0;
  private boolean soilHard = false;

  @Override
  public void run() {
    hasWalkedToStart = true;
    while (isQuesting()) {
      int stage = QUEST_STAGE;
      if (inWest() && !c.isItemIdEquipped(MASK) && (carrying(MASK, 1) || c.isItemIdEquipped(MASK))) {
        if (carrying(MASK, 1)) c.equipItemById(MASK);
      }
      if (stage == 0) {
        CURRENT_QUEST_STEP = "Offering to find Elena";
        walker.walkTo(620, 581, 2, 0);
        runDialogue(
            EDMOND,
            new String[] {"What's happened to her?", "Can i help find her?"},
            true,
            2);
      } else if (stage == 1) {
        CURRENT_QUEST_STEP = "Taking dwellberries to Alrena";
        if (!carrying(BERRIES, 1)) pickBerries();
        else {
          walker.walkTo(615, 581, 2, 0);
          runDialogue(ALRENA, new String[0], true, 2);
        }
      } else if (stage == 2) {
        CURRENT_QUEST_STEP = "Digging into the Ardougne sewer";
        softenAndDig();
      } else if (stage == 3) {
        CURRENT_QUEST_STEP = "Tying rope to the sewer grill";
        tieRope();
      } else if (stage == 4) {
        CURRENT_QUEST_STEP = "Pulling the grill with Edmond";
        if (!inSewer()) digDown();
        else runDialogue(EDMOND, new String[0], true, 2);
      } else if (stage == 5) {
        CURRENT_QUEST_STEP = "Entering West Ardougne";
        enterWest();
      } else if (stage == 6) {
        CURRENT_QUEST_STEP = "Asking Milli what she saw";
        talkMilli();
      } else if (stage == 7) {
        CURRENT_QUEST_STEP = "Asking how to enter the plague house";
        askMourner();
      } else if (stage == 8) {
        CURRENT_QUEST_STEP = "Asking Bravek for the cure recipe";
        talkBravek(new String[] {"This is really important though", "Do you know what is in the cure?"});
      } else if (stage == 9) {
        CURRENT_QUEST_STEP = "Making a hangover cure";
        if (!carrying(CURE, 1)) makeCure();
        else talkBravek(new String[] {"They won't listen to me"});
      } else if (stage == 10) {
        CURRENT_QUEST_STEP = "Freeing Elena";
        freeElena();
      } else if (stage == 11) {
        CURRENT_QUEST_STEP = "Returning to Edmond";
        returnToEdmond();
      } else {
        quit(QuitReason.QUEST_STAGE_NOT_IN_SWITCH);
        return;
      }
    }
  }

  private void pickBerries() {
    // The spawns are inside the fence. A walk onto 544,460 from the west side never arrives:
    // the only way in is the loose panel at 540,445, and a short hop does not use it.
    if (!inMcGruborsWood()) {
      squeezeIntoMcGrubors();
      return;
    }
    int[] berry = c.getNearestItemById(BERRIES, 25);
    if (berry != null) {
      if (Math.max(Math.abs(c.currentX() - berry[0]), Math.abs(c.currentY() - berry[1])) > 1)
        walker.walkTo(berry[0], berry[1], 1, 0);
      if (Math.max(Math.abs(c.currentX() - berry[0]), Math.abs(c.currentY() - berry[1])) <= 1) {
        c.pickupItem(berry[0], berry[1], BERRIES, false, true);
        c.sleep(600);
      }
      return;
    }
    int[] tile = BERRY_TILES[(int) (System.currentTimeMillis() / 4000 % BERRY_TILES.length)];
    if (Math.max(Math.abs(c.currentX() - tile[0]), Math.abs(c.currentY() - tile[1])) > 8) {
      walker.walkTo(tile[0], tile[1], 2, 0);
      return;
    }
    if (coins() < 80) {
      gatherCoins(80);
      return;
    }
    if (!nearHudo()) {
      climbToHudo();
      return;
    }
    buyBaseStock(new int[] {HUDO}, BERRIES, 3, 1);
  }

  private void softenAndDig() {
    if (!carrying(PICTURE, 1)) {
      pickupAt(615, 577, PICTURE, "Elena's picture");
      return;
    }
    if (!carrying(SPADE, 1)) {
      pickupAt(614, 577, SPADE, "a spade");
      return;
    }
    if (!soilHard) {
      digDown();
      if (inSewer()) return;
      if (gameMessageContains("hard")) soilHard = true;
      return;
    }
    if (pours >= 4) {
      digDown();
      if (gameMessageContains("hard")) {
        pours = 0;
        soilHard = true;
      }
      return;
    }
    if (!carrying(WATER, 1)) {
      fillBucket();
      return;
    }
    walker.walkTo(615, 576, 2, 0);
    walker.walkTo(619, 580, 1, 0);
    c.useItemIdOnObject(620, 581, WATER);
    c.sleep(1500);
    if (gameMessageContains("soft enough")) pours = 4;
    else if (!carrying(WATER, 1)) pours++;
  }

  private void digDown() {
    walker.walkTo(620, 581, 1, 0);
    c.useItemIdOnObject(620, 581, SPADE);
    c.sleep(2000);
  }

  private void getBucket() {
    if (carrying(BUCKET, 1)) return;
    if (carrying(MILK, 1)) {
      c.log("AIOQuester: emptying a bucket of milk", "yel");
      c.itemCommand(MILK);
      c.sleep(800);
      if (carrying(BUCKET, 1)) return;
    }
    if (coins() < 20) {
      gatherCoins(20);
      return;
    }
    walker.walkTo(577, 586, 2, 0);
    ORSCharacter shop =
        c.getNearestNPCByLambda(
            n -> {
              String name = c.getNpcName(n.npcId);
              return name != null
                  && (name.equalsIgnoreCase("Shopkeeper")
                      || name.equalsIgnoreCase("Shop assistant"));
            });
    if (shop != null) {
      buyBaseStock(new int[] {shop.npcId}, BUCKET, 2, 1);
      return;
    }
    waitPolitely("an East Ardougne shopkeeper");
  }

  /** 581,581 is behind locked door 94. 605,575 is behind BioHazard door 152. */
  private static final int[][] WATER_SOURCES = {
    {579, 584}, // open sink
    {568, 589}, // fountain
    {560, 545} // well
  };
  private int fillFails;

  private void fillBucket() {
    if (!carrying(BUCKET, 1)) {
      getBucket();
      return;
    }
    if (carrying(WATER, 1)) return;
    int[] src = WATER_SOURCES[Math.min(fillFails, WATER_SOURCES.length - 1)];
    walker.walkTo(src[0], src[1], 1, 0);
    useCarriedItemOnObject(src[0], src[1], BUCKET);
    c.sleepUntil(() -> carrying(WATER, 1), 3000);
    if (carrying(WATER, 1)) {
      fillFails = 0;
      return;
    }
    fillFails++;
    c.log(
        "AIOQuester: failed to fill the bucket at "
            + src[0]
            + ","
            + src[1]
            + " ("
            + fillFails
            + "/3)",
        "yel");
    if (fillFails >= 3) quit(QuitReason.OBJECT_NOT_FOUND);
  }

  private void tieRope() {
    if (!inSewer()) {
      digDown();
      return;
    }
    if (!carrying(ROPE, 1)) {
      buyRope();
      return;
    }
    walker.walkTo(636, 3422, 1, 0);
    c.useItemIdOnObject(636, 3422, ROPE);
    c.sleep(1200);
  }

  private void buyRope() {
    if (coins() < 15) {
      gatherCoins(15);
      return;
    }
    walker.walkTo(214, 627, 2, 0);
    runDialogue(
        NED, new String[] {"Yes, I would like some Rope", "Okay, please sell me some Rope"}, true, 2);
  }

  /** Picture, gas mask, sewer and pipe. Stops once the account is in West Ardougne. */
  private void reachWest() {
    if (!carrying(PICTURE, 1)) {
      if (inWest()) {
        leaveWest();
        return;
      }
      if (inSewer()) {
        climb(620, 3414);
        return;
      }
      pickupAt(615, 577, PICTURE, "Elena's picture");
      return;
    }
    if (carrying(MASK, 1) && !c.isItemIdEquipped(MASK)) {
      c.equipItemById(MASK);
      return;
    }
    if (!c.isItemIdEquipped(MASK) && !carrying(MASK, 1)) {
      walker.walkTo(614, 579, 1, 0);
      c.atObject(614, 579);
      c.sleep(800);
      return;
    }
    if (inSewer()) {
      walker.walkTo(636, 3422, 1, 0);
      c.atObject(636, 3422);
      c.sleep(1500);
      return;
    }
    digDown();
  }

  private void enterWest() {
    if (!inWest()) {
      reachWest();
      return;
    }
    if (!carrying(BOOK, 1)) {
      walker.walkTo(634, 589, 2, 0);
      runDialogue(JETHICK, new String[] {"Hi I'm looking for a woman from east Ardougne"}, true, 2);
      return;
    }
    walker.walkTo(645, 570, 0, 0);
    atWallObject(645, 569);
  }

  private void talkMilli() {
    if (c.currentY() < 1000) {
      if (c.currentY() >= 569 || c.currentX() < 638) {
        walker.walkTo(645, 570, 0, 0);
        atWallObject(645, 569);
        return;
      }
      climb(647, 565);
      return;
    }
    walker.walkTo(645, 1511, 2, 0);
    runDialogue(MILLI, new String[0], true, 2);
  }

  private void askMourner() {
    if (!inWest()) {
      reachWest();
      return;
    }
    walker.walkTo(637, 605, 0, 0);
    atWallObject(637, 606);
    runDialogue(
        MOURNER,
        new String[] {"but I think a kidnap victim is in here", "I want to check anyway"},
        false,
        2);
  }

  private void talkBravek(String[] choices) {
    if (c.currentX() < 648 || c.currentY() < 580 || c.currentY() > 590) {
      walker.walkTo(647, 585, 1, 0);
      atWallObject(648, 585);
      return;
    }
    runDialogue(BRAVEK, choices, true, 2);
  }

  private void makeCure() {
    if (!carrying(DUST, 1) && !carrying(CHOC_MILK, 1) && !carrying(CURE, 1)) {
      if (coins() < 100) {
        gatherCoins(100);
        return;
      }
      if (!nearHudo()) {
        climbToHudo();
        return;
      }
      buyBaseStock(new int[] {HUDO}, DUST, 5, 1);
      return;
    }
    if (!carrying(MILK, 1) && !carrying(CHOC_MILK, 1) && !carrying(CURE, 1)) {
      milkCow();
      return;
    }
    if (carrying(MILK, 1) && carrying(DUST, 1)) {
      useItemsTogether(MILK, DUST);
      c.sleep(800);
      return;
    }
    if (!carrying(SNAPE, 1) && carrying(CHOC_MILK, 1)) {
      pickSnape();
      return;
    }
    if (carrying(CHOC_MILK, 1) && carrying(SNAPE, 1)) {
      useItemsTogether(CHOC_MILK, SNAPE);
      c.sleep(800);
    }
  }

  private void freeElena() {
    if (!carrying(WARRANT, 1) && !inCellar() && c.currentY() < 3000) {
      talkBravek(new String[] {"They won't listen to me"});
      return;
    }
    if (!inCellar() && !inWest() && c.currentY() < 3000) {
      reachWest();
      return;
    }
    if (!inCellar()) {
      if (inWest() && c.currentY() > 608) {
        walker.walkTo(637, 605, 0, 0);
        atWallObject(637, 606);
        return;
      }
      if (c.currentY() < 1000) {
        walker.walkTo(637, 608, 1, 0);
        if (!walker.climb(637, 608, true, 3)) walker.climb(637, 608, false, 3);
        return;
      }
    }
    if (!carrying(KEY, 1)) {
      walker.walkTo(639, 3440, 1, 0);
      c.atObject(639, 3440);
      c.sleep(1200);
      return;
    }
    if (c.currentY() >= 3448) {
      walker.walkTo(637, 3446, 1, 0);
      c.useItemIdOnObject(637, 3447, KEY);
      c.sleep(1200);
      return;
    }
    runDialogue(ELENA, new String[0], true, 2);
  }

  private void leaveWest() {
    walker.walkTo(632, 590, 1, 0);
    int cover = c.getObjectAtCoord(632, 590);
    c.atObject(632, 590);
    c.sleep(1200);
    if (cover == 78) c.atObject2(632, 590);
  }

  private void returnToEdmond() {
    if (inCellar()) {
      climb(637, 3440);
      return;
    }
    if (inWest()) {
      leaveWest();
      return;
    }
    if (inSewer()) {
      climb(620, 3414);
      return;
    }
    walker.walkTo(620, 581, 2, 0);
    runDialogue(EDMOND, new String[0], true, 2);
  }

  private int standTry;

  private void pickupAt(int x, int y, int itemId, String name) {
    int[] item = c.getNearestItemById(itemId, 8);
    if (item == null) {
      walker.walkTo(x, y, 1, 0);
      waitPolitely(name);
      return;
    }
    // The picture sits on a table. Standing on the north side only reaches it once the walk has
    // finished, and pickupItem sends a fresh walk first, so that take is dropped. West, east and
    // south reach it while standing still.
    int[][] beside = {{item[0] - 1, item[1]}, {item[0] + 1, item[1]}, {item[0], item[1] + 1}};
    int[] stand = beside[Math.floorMod(standTry, beside.length)];
    if (c.currentX() != stand[0] || c.currentY() != stand[1]) {
      c.log(
          "AIOQuester: " + name + " is at " + item[0] + "," + item[1] + "; taking it from beside "
              + stand[0] + "," + stand[1],
          "yel");
      walker.walkTo(stand[0], stand[1], 0, 0);
      if (c.currentX() != stand[0] || c.currentY() != stand[1]) standTry++;
      return;
    }
    int before = c.getInventoryItemCount(itemId);
    c.pickupItem(item[0], item[1], itemId, false, true);
    c.sleep(900);
    if (c.getInventoryItemCount(itemId) <= before) {
      c.log(
          "AIOQuester: " + name + " is visible at " + item[0] + "," + item[1]
              + " but the take from " + stand[0] + "," + stand[1] + " did not land",
          "yel");
      standTry++;
    }
  }

  private void pickSnape() {
    walker.walkTo(362, 602, 2, 0);
    int[] grass = c.getNearestItemById(SNAPE, 12);
    if (grass == null) {
      waitPolitely("snape grass");
      return;
    }
    c.pickupItem(grass[0], grass[1], SNAPE, true, true);
    c.sleep(600);
  }

  private void milkCow() {
    if (!carrying(BUCKET, 1)) {
      getBucket();
      return;
    }
    if (!inLumbridgeCowPen()) {
      enterLumbridgeCowPen();
      return;
    }
    ORSCharacter cow = c.getNearestNpcByIds(new int[] {COW}, false);
    if (cow == null) {
      waitPolitely("a free cow");
      return;
    }
    c.useItemOnNpc(cow.serverIndex, BUCKET);
    c.sleep(1200);
  }

  /** North fence y=436, loose west panel 540,445, locked south gate 560,472. */
  private boolean inMcGruborsWood() {
    int x = c.currentX();
    int y = c.currentY();
    return x >= 541 && x <= 576 && y >= 437 && y <= 471;
  }

  /** Stand west of the loose panel, then push through. The main gate stays locked. */
  private void squeezeIntoMcGrubors() {
    CURRENT_QUEST_STEP = "Squeezing through the loose fence";
    if (Math.max(Math.abs(c.currentX() - 539), Math.abs(c.currentY() - 445)) > 1) {
      walker.walkTo(539, 445, 1, 0);
      return;
    }
    atWallObject(540, 445);
  }

  private boolean nearHudo() {
    return c.currentY() > 1300 && c.currentY() < 1500 && c.currentX() > 680 && c.currentX() < 750;
  }

  private void climbToHudo() {
    if (c.currentY() < 1000) {
      walker.walkTo(740, 438, 1, 0);
      climb(740, 438);
      return;
    }
    walker.walkTo(716, 1380, 2, 0);
  }

  private boolean inWest() {
    int x = c.currentX();
    int y = c.currentY();
    return y < 944 && x >= 620 && x <= 670 && y >= 545 && y <= 640;
  }

  private boolean inSewer() {
    int y = c.currentY();
    return y >= 3400 && y < 3436 && c.currentX() >= 600 && c.currentX() <= 660;
  }

  private boolean inCellar() {
    int y = c.currentY();
    return y >= 3436 && y < 3600 && c.currentX() >= 620 && c.currentX() <= 660;
  }
}
