package scripting.idlescript.SeattaScript.AIOQuester.quests;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import models.entities.ItemId;
import models.entities.NpcId;
import models.entities.SkillId;
import orsc.ORSCharacter;
import scripting.idlescript.SeattaScript.AIOQuester.QuestHandler;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuitReason;

/**
 * Dragon Slayer. Map pieces come from Wormbrain's drop, Melzar's maze and the dwarf mines chest.
 * The chest door takes items the account makes or buys from NPC base stock: silk (Thessalia, base
 * 5), a lobster pot (Gerrant, base 2), an unfired bowl (mined clay) and a mind bomb (Emily, 2
 * coins). The ship is bought from Klarense. Planks are wilderness ground spawns. This Coleslaw
 * tree has no free-to-play NPC that saws logs into planks. Steel nails are smelted and smithed.
 * Wormbrain is killed, not paid. Oziach gives the maze key. It is not a drop.
 */
public final class DragonSlayer extends QuestHandler {
  private static final int PIECE1 = ItemId.MAP_PIECE_1.getId();
  private static final int PIECE2 = ItemId.MAP_PIECE_2.getId();
  private static final int PIECE3 = ItemId.MAP_PIECE_3.getId();
  private static final int MAP = ItemId.MAP.getId();
  private static final int SHIELD = ItemId.ANTI_DRAGON_BREATH_SHIELD.getId();
  private static final int SILK = ItemId.SILK.getId();
  private static final int POT = ItemId.LOBSTER_POT.getId();
  private static final int BOWL = ItemId.UNFIRED_BOWL.getId();
  private static final int BOMB = ItemId.WIZARDS_MIND_BOMB.getId();
  private static final int CLAY = ItemId.CLAY.getId();
  private static final int SOFT = ItemId.SOFT_CLAY.getId();
  private static final int BUCKET = ItemId.BUCKET.getId();
  private static final int WATER = ItemId.BUCKET_OF_WATER.getId();
  private static final int PLANK = ItemId.PLANK.getId();
  private static final int NAILS = ItemId.NAILS.getId();
  private static final int HAMMER = ItemId.HAMMER.getId();
  private static final int IRON = ItemId.IRON_ORE.getId();
  /** Wilderness level ~19-23. Three spawns close enough to walk as one trip. */
  private static final int[][] WILD_PLANKS = {{303, 297}, {303, 299}, {304, 302}};
  private static final int COAL = ItemId.COAL.getId();
  private static final int STEEL = ItemId.STEEL_BAR.getId();
  private static final int PICK = ItemId.BRONZE_PICKAXE.getId();
  private static final int RED = ItemId.RED_KEY.getId();
  private static final int ORANGE = ItemId.ORANGE_KEY.getId();
  private static final int YELLOW = ItemId.YELLOW_KEY.getId();
  private static final int BLUE = ItemId.BLUE_KEY.getId();
  private static final int MAGENTA = ItemId.MAGENTA_KEY.getId();
  private static final int BLACK = ItemId.BLACK_KEY.getId();
  private static final int MAZE = ItemId.MAZE_KEY.getId();
  private static final int[] MAZE_NPCS = {177, 178, 179, 180, 181, 182};
  private boolean shipFixed;
  private boolean nedHired;
  private boolean loadedShip;
  private boolean probedShip;
  private boolean oracleSpoken;
  private long mixedAt;
  private int skippedNpc = -1;
  private long skipUntil;
  private int nedMisses;

  @Override
  public void run() {
    hasWalkedToStart = true;
    loadShipFixed();
    while (isQuesting()) {
      int stage = QUEST_STAGE;
      if (stage == 0) {
        CURRENT_QUEST_STEP = "Asking the Guildmaster about rune plate";
        walker.walkTo(149, 557, 2, 0);
        runDialogue(
            NpcId.GUILDMASTER.getId(),
            new String[] {"Do you know know where I could get a rune plate mail body?"},
            true,
            2);
      } else if (stage == 1) {
        CURRENT_QUEST_STEP = "Talking to Oziach";
        walker.walkTo(244, 443, 2, 0);
        runDialogue(
            NpcId.OZIACH.getId(),
            new String[] {
              "Can you sell me some rune plate mail?",
              "The guildmaster of the champion guild told me",
              "So how am I meant to prove that?",
              "A dragon, that sounds like fun"
            },
            true,
            2);
      } else if (stage == 2) {
        prepare();
      } else if (stage == 3) {
        CURRENT_QUEST_STEP = "Fighting Elvarg";
        fightElvarg();
      } else {
        quit(QuitReason.QUEST_STAGE_NOT_IN_SWITCH);
        return;
      }
    }
  }

  private void prepare() {
    if (!carrying(SHIELD, 1) && !c.isItemIdEquipped(SHIELD)) {
      CURRENT_QUEST_STEP = "Getting the anti-dragon shield from the Duke";
      if (c.currentY() < 944) {
        walker.walkTo(134, 661, 2, 0);
        atObject(134, 661);
        c.sleep(1000);
        return;
      }
      walker.walkTo(130, 1601, 2, 0);
      runDialogue(
          NpcId.DUKE_OF_LUMBRIDGE.getId(),
          new String[] {"I seek a shield that will protect me from dragon breath"},
          true,
          2);
      if (carrying(SHIELD, 1)) c.equipItemById(SHIELD);
      return;
    }
    if (!c.isItemIdEquipped(SHIELD) && carrying(SHIELD, 1)) c.equipItemById(SHIELD);
    if (!carrying(PIECE1, 1) && !carrying(MAP, 1)) {
      killWormbrain();
      return;
    }
    if (inMelzarMaze() || (!carrying(PIECE2, 1) && !carrying(MAP, 1))) {
      maze();
      return;
    }
    if (!carrying(PIECE3, 1) && !carrying(MAP, 1)) {
      thalzar();
      return;
    }
    if (!carrying(MAP, 1)) {
      CURRENT_QUEST_STEP = "Joining the map pieces";
      if (carrying(PIECE1, 1) && carrying(PIECE2, 1)) useItemsTogether(PIECE1, PIECE2);
      else if (carrying(PIECE1, 1) && carrying(PIECE3, 1)) useItemsTogether(PIECE1, PIECE3);
      else useItemsTogether(PIECE2, PIECE3);
      c.sleep(800);
      return;
    }
    if (c.currentY() > 3000 && !inShip() && c.currentX() < 400) {
      walker.walkTo(258, 3334, 1, 0);
      atWallObject(259, 3334);
      return;
    }
    if (!shipFixed && !inShip() && needsShipSupplies()) {
      if (!probedShip && nearPortSarim()) {
        repairShip();
        return;
      }
      shipSupplies();
      return;
    }
    repairShip();
  }

  /** On land, before the hole is boarded up: 3 planks and 4 nails for each of those planks. */
  private boolean needsShipSupplies() {
    int planks = c.getInventoryItemCount(PLANK);
    int nailsNeeded = 4 * Math.max(planks, 3);
    return planks < 3 || c.getInventoryItemCount(NAILS) < nailsNeeded || !carrying(HAMMER, 1);
  }

  /** x 338-352 and the local floor band the maze rooms sit in, including the basement. */
  private boolean inMelzarMaze() {
    int x = c.currentX();
    int y = c.currentY();
    int local = Math.floorMod(y, 944);
    return x >= 338 && x <= 352 && local >= 620 && local <= 645;
  }

  @Override
  public void serverMessageInterrupt(String message) {
    if (message == null) return;
    String lower = message.toLowerCase();
    if (lower.contains("board up the hole")) rememberShipFixed();
    if (lower.contains("check her out") || lower.contains("go right over")) rememberNed();
  }

  private void loadShipFixed() {
    if (loadedShip) return;
    loadedShip = true;
    try {
      if (Files.exists(shipFile())) shipFixed = true;
      if (Files.exists(nedFile())) nedHired = true;
    } catch (RuntimeException ignored) {
      // This run still hears the boarded-up and hired messages.
    }
  }

  private void rememberShipFixed() {
    shipFixed = true;
    try {
      Path file = shipFile();
      Files.createDirectories(file.getParent());
      Files.write(file, "fixed".getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      c.log("AIOQuester: could not save the ship-fixed flag", "yel");
    }
  }

  private void rememberNed() {
    nedHired = true;
    try {
      Path file = nedFile();
      Files.createDirectories(file.getParent());
      Files.write(file, "hired".getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      c.log("AIOQuester: could not save the Ned-hired flag", "yel");
    }
  }

  private Path shipFile() {
    return flagFile("dragon-ship-");
  }

  private Path nedFile() {
    return flagFile("dragon-ned-");
  }

  private Path flagFile(String prefix) {
    String name = c.getPlayerName();
    if (name == null || name.isEmpty()) name = "unknown";
    name = name.replaceAll("[^A-Za-z0-9@.\\-_]", "_");
    return Paths.get("settings", "aioquester", prefix + name + ".flag");
  }

  /** The Lady Lumbridge cabins. Not the Dwarven Mine and not Crandor's caves. */
  private boolean inShip() {
    int x = c.currentX();
    int y = c.currentY();
    return x >= 255 && x <= 290 && y >= 3465 && y <= 3510;
  }

  private boolean nearPortSarim() {
    int x = c.currentX();
    int y = c.currentY();
    return y < 944 && x >= 240 && x <= 290 && y >= 620 && y <= 670;
  }

  /** A repaired ship boards to y 3493. An unrepaired one boards to y 3472. */
  private void noteShipPosition() {
    if (!inShip()) return;
    if (c.currentY() >= 3485) rememberShipFixed();
    if (c.currentX() >= 270) rememberNed();
  }

  /**
   * Buy the ship, board scenery 225 at 259,643 into the hold, and hammer planks onto hole 226 at
   * 258,3472. The third plank moves the account to 281,3493. The ladder at 281,3494 leaves the
   * ship so Ned can be hired on the dock.
   */
  private void repairShip() {
    CURRENT_QUEST_STEP = "Buying and repairing the Lady Lumbridge";
    noteShipPosition();
    if (inShip()) {
      if (!shipFixed && c.getInventoryItemCount(PLANK) > 0 && c.currentX() < 270 && c.currentY() < 3485) {
        walker.walkTo(258, 3472, 1, 0);
        c.useItemIdOnObject(258, 3472, PLANK);
        c.sleep(1500);
        return;
      }
      if (nedHired) {
        runDialogue(
            NpcId.NED_BOAT.getId(),
            new String[] {"So are you going to take me to Crandor Island now then?"},
            true,
            2);
        return;
      }
      int lx = c.currentX() < 270 ? 259 : 281;
      int ly = c.currentY() < 3485 ? 3473 : 3494;
      walker.walkTo(lx, ly, 1, 0);
      walker.climb(lx, ly, false, 3);
      return;
    }
    if (!shipFixed && !probedShip && nearPortSarim()) {
      probedShip = true;
      walker.walkTo(259, 643, 2, 0);
      atObject(259, 643);
      c.sleep(1200);
      return;
    }
    if (!shipFixed && c.getInventoryItemCount(PLANK) >= 3) {
      if (coins() < 2000) {
        gatherCoins(2000);
        return;
      }
      walker.walkTo(260, 641, 2, 0);
      runDialogue(
          NpcId.KLARENSE.getId(),
          new String[] {"I don't suppose I could buy it", "Yep sounds good"},
          true,
          2);
      walker.walkTo(259, 643, 2, 0);
      atObject(259, 643);
      c.sleep(1200);
      return;
    }
    if (!nedHired) {
      walker.walkTo(214, 627, 2, 0);
      boolean asked =
          runDialogue(
              NpcId.NED.getId(),
              new String[] {"You're a sailor? Could you take me to the Isle of Crandor"},
              true,
              2);
      if (asked || (++nedMisses >= 2 && c.distanceTo(214, 627) < 8)) rememberNed();
      return;
    }
    walker.walkTo(259, 643, 2, 0);
    atObject(259, 643);
    c.sleep(1200);
  }

  private void killWormbrain() {
    CURRENT_QUEST_STEP = "Killing Wormbrain for a map piece";
    walker.walkTo(285, 663, 2, 0);
    int[] piece = c.getNearestItemById(PIECE1);
    if (piece != null) {
      pickupGroundItem(PIECE1, 1);
      return;
    }
    if (c.isInCombat()) {
      c.sleep(640);
      return;
    }
    ORSCharacter worm = c.getNearestNpcByIds(new int[] {NpcId.WORMBRAIN.getId()}, false);
    if (worm == null) waitPolitely("Wormbrain");
    else c.attackNpc(worm.serverIndex);
  }

  private void thalzar() {
    if (!doorItems()) return;
    CURRENT_QUEST_STEP = "Opening the dwarf mine map door";
    if (!oracleSpoken && c.currentY() < 1000) {
      walker.walkTo(287, 457, 2, 0);
      runDialogue(
          NpcId.ORACLE.getId(),
          new String[] {"I seek a piece of the map of the isle of Crondor"},
          true,
          1);
      oracleSpoken = true;
      return;
    }
    if (c.currentY() < 1000) {
      walker.walkTo(279, 494, 2, 0);
      atObject(279, 494);
      c.sleep(1200);
      return;
    }
    walker.walkTo(259, 3334, 1, 0);
    atWallObject(259, 3334);
    c.sleep(1200);
    walker.walkTo(255, 3331, 1, 0);
    atObject(255, 3331);
    c.sleep(1200);
    int[] piece = c.getNearestItemById(PIECE3);
    if (piece != null) pickupGroundItem(PIECE3, 1);
  }

  private boolean doorItems() {
    if (!carrying(SILK, 1)) {
      CURRENT_QUEST_STEP = "Buying silk from Thessalia's own stock";
      if (coins() < 50) gatherCoins(50);
      else {
        walker.walkTo(137, 515, 2, 0);
        buyBaseStock(new int[] {NpcId.THESSALIA.getId()}, SILK, 5, 1);
      }
      return false;
    }
    if (!carrying(POT, 1)) {
      CURRENT_QUEST_STEP = "Buying a lobster pot from Gerrant's own stock";
      if (coins() < 50) gatherCoins(50);
      else {
        walker.walkTo(279, 647, 2, 0);
        buyBaseStock(new int[] {NpcId.GERRANT.getId()}, POT, 2, 1);
      }
      return false;
    }
    if (!carrying(BOMB, 1)) {
      CURRENT_QUEST_STEP = "Buying a wizard's mind bomb from Emily";
      if (coins() < 2) gatherCoins(2);
      else {
        walker.walkTo(321, 549, 2, 0);
        runDialogue(NpcId.BARMAID.getId(), new String[] {"I'll try the mind bomb"}, true, 2);
      }
      return false;
    }
    if (!carrying(BOWL, 1)) {
      CURRENT_QUEST_STEP = "Making an unfired bowl";
      makeBowl();
      return false;
    }
    return true;
  }

  private void makeBowl() {
    if (!ensurePick()) return;
    if (c.getInventoryItemCount(CLAY) > 1) {
      dropAllButAmount(CLAY, 1);
      return;
    }
    if (carrying(SOFT, 1)) {
      walker.walkTo(227, 524, 1, 0);
      c.useItemIdOnObject(227, 524, SOFT);
      c.sleep(1200);
      answerMenuIfPresent("bowl");
      return;
    }
    if (System.currentTimeMillis() - mixedAt < 3000) return;
    if (!carrying(CLAY, 1)) {
      walker.walkTo(306, 638, 0, 0);
      atObject(306, 639);
      c.sleep(2000);
      return;
    }
    if (!carrying(WATER, 1)) {
      if (!carrying(BUCKET, 1)) {
        walker.walkTo(121, 606, 0, 0);
        pickupGroundItem(BUCKET, 1);
      } else {
        walker.walkTo(124, 652, 1, 0);
        c.useItemIdOnObject(124, 652, BUCKET);
        c.sleep(1200);
      }
      return;
    }
    useItemsTogether(WATER, CLAY);
    mixedAt = System.currentTimeMillis();
    c.sleep(1500);
  }

  private void shipSupplies() {
    if (!carrying(HAMMER, 1)) {
      CURRENT_QUEST_STEP = "Buying a hammer from the general store's own stock";
      if (coins() < 20) gatherCoins(20);
      else {
        walker.walkTo(133, 642, 3, 0);
        buyBaseStock(new int[] {NpcId.SHOPKEEPER_LUMBRIDGE.getId()}, HAMMER, 5, 1);
      }
      return;
    }
    int planks = c.getInventoryItemCount(PLANK);
    if (planks < 3) {
      takePlank();
      return;
    }
    if (c.getInventoryItemCount(NAILS) < 4 * planks) smithNails();
  }

  /** Item 410 spawns in the wilderness. Other players and aggressive monsters are there. */
  private void takePlank() {
    CURRENT_QUEST_STEP = "Picking a wilderness plank. Watch for players and aggressive monsters";
    if (c.isInCombat()) {
      c.walkTo(c.currentX(), c.currentY() + 8);
      c.sleep(800);
      return;
    }
    int before = c.getInventoryItemCount(PLANK);
    for (int[] tile : WILD_PLANKS) {
      walker.walkTo(tile[0], tile[1], 2, 0);
      boolean picked =
          withTurn(
              "plank-" + tile[0] + "-" + tile[1],
              () -> {
                int[] item = c.getNearestItemById(PLANK);
                if (item == null
                    || Math.abs(item[0] - tile[0]) > 3
                    || Math.abs(item[1] - tile[1]) > 3) return false;
                c.pickupItem(item[0], item[1], PLANK, true, true);
                c.sleep(640);
                return c.getInventoryItemCount(PLANK) > before;
              });
      if (picked || c.getInventoryItemCount(PLANK) > before) return;
    }
    waitPolitely("a wilderness plank to respawn");
  }

  private void smithNails() {
    if (!ensurePick()) return;
    int smith = c.getBaseStat(SkillId.SMITHING.getId());
    int mining = c.getBaseStat(SkillId.MINING.getId());
    if (mining < 15 || (smith >= 30 && mining < 30)) {
      CURRENT_QUEST_STEP = "Mining until the ore for steel nails is available";
      walker.walkTo(315, 640, 2, 0);
      atObject(315, 640);
      c.sleep(2000);
      return;
    }
    if (smith < 34 || c.getInventoryItemCount(STEEL) < 2) {
      CURRENT_QUEST_STEP = "Smelting bars for steel nails";
      if (smith < 30) {
        if (!carrying(IRON, 1)) {
          walker.walkTo(315, 640, 2, 0);
          atObject(315, 640);
        } else {
          walker.walkTo(309, 544, 1, 0);
          c.useItemIdOnObject(309, 544, IRON);
        }
        c.sleep(2000);
        return;
      }
      if (c.getInventoryItemCount(IRON) < 1 || c.getInventoryItemCount(COAL) < 2) {
        walker.walkTo(315, 640, 2, 0);
        atObject(c.getInventoryItemCount(IRON) < 1 ? 315 : 313, 640);
        c.sleep(2000);
        return;
      }
      walker.walkTo(309, 544, 1, 0);
      c.useItemIdOnObject(309, 544, IRON);
      c.sleep(2000);
      return;
    }
    CURRENT_QUEST_STEP = "Smithing steel nails";
    walker.walkTo(327, 487, 1, 0);
    c.useItemIdOnObject(327, 487, STEEL);
    c.sleep(1200);
    answerMenuIfPresent("Nails");
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

  /**
   * Melzar's maze. The entrance is Oziach's maze key on wall 338,632, not the one-way exit at
   * 340,630. Coloured keys are drops. The map piece is in the chest at 344,3458.
   */
  private void maze() {
    CURRENT_QUEST_STEP = "Melzar's maze";
    if (c.isInCombat()) {
      c.sleep(600);
      return;
    }
    int x = c.currentX();
    int y = c.currentY();
    if (y < 1000) {
      mazeGround(x, y);
      return;
    }
    if (y < 2000) {
      if (c.isReachable(339, 1582, false)) {
        walker.walkTo(339, 1582, 1, 0);
        climbFloor(340, 1582);
        return;
      }
      if (c.isReachable(347, 1574, false)) {
        if (carrying(ORANGE, 1)) useItemOnWallSlot(ORANGE, 345, 1573);
        else fightOrLoot(ORANGE);
        return;
      }
      walker.walkTo(341, 1572, 1, 0);
      climbFloor(341, 1572);
      return;
    }
    if (y < 3000) {
      if (c.isReachable(338, 2526, false)) {
        walker.walkTo(338, 2526, 1, 0);
        climbFloor(338, 2526);
        return;
      }
      if (carrying(YELLOW, 1)) useItemOnWallSlot(YELLOW, 349, 2520);
      else fightOrLoot(YELLOW);
      return;
    }
    if (carrying(PIECE2, 1)) {
      walker.walkTo(338, 3458, 1, 0);
      climbFloor(338, 3458);
      return;
    }
    if (x <= 344 && y <= 3460) {
      walker.walkTo(343, 3458, 1, 0);
      atObject(344, 3458);
      return;
    }
    if (c.isReachable(344, 3469, false)) {
      if (carrying(BLUE, 1)) useItemOnWallSlot(BLUE, 346, 3467);
      else fightOrLoot(BLUE);
      return;
    }
    if (c.isReachable(346, 3464, false)) {
      if (carrying(MAGENTA, 1)) useItemOnWallSlot(MAGENTA, 346, 3462);
      else fightOrLoot(MAGENTA);
      return;
    }
    if (c.isReachable(342, 3463, false)) {
      if (carrying(BLACK, 1)) useItemOnWallSlot(BLACK, 342, 3460);
      else fightOrLoot(BLACK);
      return;
    }
    walker.walkTo(343, 3458, 1, 0);
    atObject(344, 3458);
  }

  private void mazeGround(int x, int y) {
    if (carrying(PIECE2, 1)) {
      if (x < 338) return;
      walker.walkTo(339, 630, 0, 0);
      atWallObject(340, 630);
      if (c.currentX() >= 338) useItemOnWallSlot(MAZE, 338, 632);
      return;
    }
    if (c.isReachable(348, 627, false)) {
      walker.walkTo(348, 627, 0, 0);
      climbFloor(347, 627);
      return;
    }
    if (c.isReachable(339, 638, false)) {
      walker.walkTo(344, 638, 1, 0);
      climbFloor(344, 638);
      return;
    }
    if (x >= 338) {
      if (carrying(RED, 1)) {
        walker.walkTo(348, 629, 0, 0);
        useItemOnWallSlot(RED, 349, 629);
      } else fightOrLoot(RED);
      return;
    }
    if (!carrying(MAZE, 1)) {
      walker.walkTo(244, 443, 2, 0);
      runDialogue(
          NpcId.OZIACH.getId(),
          new String[] {
            "So where can I find this dragon?",
            "Where is the first piece of map?",
            "Ok I'll try and get everything together"
          },
          true,
          2);
      return;
    }
    walker.walkTo(337, 632, 0, 0);
    useItemOnWallSlot(MAZE, 338, 632);
  }

  /** Climb. A maze ladder is one-way, so any floor change is the result. Try the other command if it stays put. */
  private void climbFloor(int x, int y) {
    int y0 = c.currentY();
    walker.climb(x, y, false, 2);
    if (Math.abs(c.currentY() - y0) > 500) return;
    if (c.isInOptionMenu()) {
      answerMenuIfPresent("Go up", "Go down", "Climb-up", "Climb-down");
      return;
    }
    walker.climb(x, y, true, 2);
  }

  private int npcForKey(int keyId) {
    if (keyId == RED) return 177;
    if (keyId == ORANGE) return 178;
    if (keyId == YELLOW) return 179;
    if (keyId == BLUE) return 180;
    if (keyId == BLACK) return 181;
    if (keyId == MAGENTA) return 182;
    return -1;
  }

  private void fightOrLoot(int keyId) {
    int[] key = c.getNearestItemById(keyId);
    if (key != null && c.isReachable(key[0], key[1], false)) {
      pickupGroundItem(keyId, 1);
      return;
    }
    if (c.isInCombat()) {
      c.sleep(600);
      return;
    }
    int want = npcForKey(keyId);
    ORSCharacter npc = null;
    long now = System.currentTimeMillis();
    for (ORSCharacter n : c.getNpcsAsArray()) {
      if (n == null || n.npcId != want) continue;
      if (n.serverIndex == skippedNpc && now < skipUntil) continue;
      int[] at = c.getNpcCoordsByServerIndex(n.serverIndex);
      if (at == null || at[0] < 0 || !c.isReachable(at[0], at[1], false)) continue;
      npc = n;
      break;
    }
    if (npc == null) {
      waitPolitely("maze npc " + want);
      return;
    }
    c.attackNpc(npc.serverIndex);
    c.sleep(2000);
    if (!c.isInCombat()) {
      skippedNpc = npc.serverIndex;
      skipUntil = System.currentTimeMillis() + 8000;
    }
  }

  private void fightElvarg() {
    if (carrying(SHIELD, 1) && !c.isItemIdEquipped(SHIELD)) c.equipItemById(SHIELD);
    if (c.isInCombat()) {
      c.sleep(640);
      return;
    }
    int x = c.currentX();
    int y = c.currentY();
    if (inShip()) {
      walker.walkTo(281, 3473, 1, 0);
      walker.climb(281, 3473, false, 3);
      return;
    }
    if (x >= 400 && y < 944) {
      walker.walkTo(419, 628, 1, 0);
      walker.climb(419, 628, false, 3);
      return;
    }
    if (x >= 400 && y > 3000) {
      ORSCharacter dragon = null;
      for (ORSCharacter n : c.getNpcsAsArray()) {
        if (n == null || n.npcId != 196) continue;
        if (n.serverIndex == skippedNpc && System.currentTimeMillis() < skipUntil) continue;
        int[] at = c.getNpcCoordsByServerIndex(n.serverIndex);
        if (at == null || at[0] < 0 || !c.isReachable(at[0], at[1], false)) continue;
        dragon = n;
        break;
      }
      if (dragon == null) {
        if (x >= 414) {
          waitPolitely("Elvarg");
          return;
        }
        walker.walkTo(413, 3480, 0, 0);
        atWallObject(414, 3480);
        return;
      }
      c.attackNpc(dragon.serverIndex);
      c.sleep(2000);
      if (!c.isInCombat()) {
        skippedNpc = dragon.serverIndex;
        skipUntil = System.currentTimeMillis() + 8000;
      }
      return;
    }
    walker.walkTo(259, 643, 2, 0);
    atObject(259, 643);
    c.sleep(1200);
  }
}
