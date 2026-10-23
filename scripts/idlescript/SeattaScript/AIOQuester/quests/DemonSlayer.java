package scripting.idlescript.SeattaScript.AIOQuester.quests;

import models.entities.ItemId;
import models.entities.NpcId;
import orsc.ORSCharacter;
import scripting.idlescript.SeattaScript.AIOQuester.QuestHandler;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuitReason;

/**
 * Demon Slayer. The coin for the gypsy is gathered (chicken feathers sold to the general store)
 * if the account has none. Bones are killed for. The bucket is picked up or bought from the
 * general store's own stock (base 2). The drain key is the sewer ground item. Delrith's
 * incantation is the fourth line, the only one that kills him.
 */
public final class DemonSlayer extends QuestHandler {
  private static final int BONES = ItemId.BONES.getId();
  private static final int BUCKET = ItemId.BUCKET.getId();
  private static final int WATER = ItemId.BUCKET_OF_WATER.getId();
  private static final int KEY1 = ItemId.SILVERLIGHT_KEY_1.getId();
  private static final int KEY2 = ItemId.SILVERLIGHT_KEY_2.getId();
  private static final int KEY3 = ItemId.SILVERLIGHT_KEY_3.getId();
  private static final int SWORD = ItemId.SILVERLIGHT.getId();
  private static final int GYPSY = NpcId.GYPSY.getId();
  private static final int PRYSIN = NpcId.SIR_PRYSIN.getId();
  private static final int ROVIN = NpcId.CAPTAIN_ROVIN.getId();
  private static final int TRAIBORN = NpcId.TRAIBORN_THE_WIZARD.getId();
  private static final int DELRITH = NpcId.DELRITH.getId();
  private static final String INCANTATION = "Carlem Aber Camerinthum Purchai Gabindo";
  /** The Lumbridge bucket spawn at 121,607 cannot be stood on, so one take is enough. */
  private boolean bucketSpawnTried;
  /** Looks at the sewer key tile with nothing there. After a few, the key has despawned. */
  private int sewerKeyMisses;
  /** One bank trip for the best food before Delrith is attacked. */
  private boolean delrithFoodReady;

  @Override
  public void run() {
    hasWalkedToStart = true;
    while (isQuesting()) {
      int stage = QUEST_STAGE;
      if (stage == 0) {
        CURRENT_QUEST_STEP = "Paying the gypsy";
        if (coins() < 1) {
          gatherCoins(1);
          continue;
        }
        walker.walkTo(136, 511, 2, 0);
        runDialogue(
            GYPSY,
            new String[] {
              "Ok, here you go",
              "Yes please",
              "Very interesting what does the Aaargh bit mean?",
              "Who's Delrith?",
              "Ok where is he? I'll kill him for you",
              "Ok thanks. I'll do my best to stop the Demon",
              "What is the magical incantation?",
              "Where can I find Silverlight?",
              "Ok here you go"
            },
            true,
            2);
      } else if (stage == 1) {
        CURRENT_QUEST_STEP = "Talking to Sir Prysin";
        walker.walkTo(136, 472, 2, 0);
        runDialogue(
            PRYSIN,
            new String[] {
              "Gypsy Aris said I should come and talk to you",
              "I need to find Silverlight",
              "He's back and unfortunatly I've got to deal with him",
              "He's back and unfortunately I've got to deal with him",
              "And why is this a problem?",
              "So give me the keys",
              "Well I'd better go key hunting",
              "Where can I find Captain Rovin?",
              "Can you give me your key?",
              "Where does the wizard live?",
              "I'm still looking"
            },
            true,
            2);
      } else if (stage == 2 || stage == 3) {
        if (!carrying(KEY2, 1)) {
          CURRENT_QUEST_STEP = "Taking Captain Rovin's key";
          rovin();
        } else if (!carrying(KEY1, 1)) {
          CURRENT_QUEST_STEP = "Bringing Traiborn 25 bones";
          traiborn();
        } else if (!carrying(KEY3, 1)) {
          CURRENT_QUEST_STEP = "Washing Sir Prysin's key down the drain";
          drain();
        } else {
          CURRENT_QUEST_STEP = "Giving the three keys to Sir Prysin";
          if (inVarrockSewer()) {
            leaveVarrockSewer();
            continue;
          }
          walker.walkTo(136, 472, 2, 0);
          runDialogue(PRYSIN, new String[0], true, 2);
        }
      } else if (stage == 4) {
        CURRENT_QUEST_STEP = "Killing Delrith";
        fightDelrith();
      } else {
        quit(QuitReason.QUEST_STAGE_NOT_IN_SWITCH);
        return;
      }
    }
  }

  private void rovin() {
    // Ladder 5 at 142,454 (stand 141,454) comes up at 142,1398, inside Rovin's room.
    // Ladder 5 at 123,463 comes up in the other wing, and the wall at 136,1406 blocks the room.
    int x = c.currentX(), y = c.currentY();
    boolean inRoom = y >= 1350 && y < 1550 && x >= 130;
    if (!inRoom) {
      if (y >= 944) {
        climbDownAnywhere();
        return;
      }
      walker.walkTo(141, 454, 1, 0);
      walker.climb(142, 454, false, 3);
      return;
    }
    walker.walkTo(140, 1403, 0, 0);
    runDialogue(
        ROVIN,
        new String[] {"Yes I know but this important", "There's a demon who wants to invade this city"},
        true,
        2);
  }

  private boolean onWizardsTower() {
    return c.currentY() >= 1600 && c.currentY() <= 1800;
  }

  private void climbDownAnywhere() {
    int[] down = c.getNearestObjectById(6);
    if (down == null) down = c.getNearestObjectById(5);
    if (down == null) down = c.getNearestObjectByIds(new int[] {41, 42, 43});
    if (down == null) {
      c.log("AIOQuester: no ladder or stairs to leave this floor", "yel");
      c.sleep(640);
      return;
    }
    walker.climb(down[0], down[1], false, 3);
    c.sleep(640);
  }

  private void traiborn() {
    int stage = c.getQuestStage(QUEST.getId());
    if (stage >= 3 && c.getInventoryItemCount(BONES) < 25 && !carrying(KEY1, 1)) {
      CURRENT_QUEST_STEP = "Killing for bones (" + c.getInventoryItemCount(BONES) + "/25)";
      if (onWizardsTower()) {
        walker.walkTo(218, 1636, 2, 0);
        walker.climb(218, 1636, false, 3);
        c.sleep(640);
        return;
      }
      if (c.currentY() >= 944) {
        climbDownAnywhere();
        return;
      }
      killForBones();
      return;
    }
    if (!onWizardsTower()) {
      if (c.currentY() >= 944) {
        climbDownAnywhere();
        return;
      }
      walker.walkTo(218, 693, 2, 0);
      walker.climb(218, 692, false, 3);
      c.sleep(640);
      return;
    }
    walker.walkTo(214, 1640, 2, 0);
    if (stage < 3) {
      runDialogue(
          TRAIBORN,
          new String[] {
            "I need to get a key given to you by Sir Prysin",
            "Well, have you got any keys knocking around?",
            "I'll get the bones for you"
          },
          true,
          2);
      return;
    }
    runDialogue(TRAIBORN, new String[0], true, 1);
  }

  private void killForBones() {
    if (30 - c.getInventoryItemCount() < 1) {
      c.log("AIOQuester: inventory full while collecting bones", "yel");
      c.sleep(640);
      return;
    }
    walker.walkTo(118, 605, 4, 0);
    int[] drop = c.getNearestItemById(BONES);
    if (drop != null) {
      pickupGroundItem(BONES, 1);
      return;
    }
    if (c.isInCombat()) {
      c.sleep(640);
      return;
    }
    ORSCharacter chicken = c.getNearestNpcByIds(new int[] {NpcId.CHICKEN.getId()}, false);
    if (chicken == null) {
      waitPolitely("something to kill for bones");
      return;
    }
    if (!readyToFight()) return;
    c.attackNpc(chicken.serverIndex);
    c.sleep(1000);
  }

  private void drain() {
    // Pouring the bucket empties it, then the manhole drops into the sewer. The next loop must
    // pick up the key down here. Walking back to the fountain from floor 3 never finds a path.
    if (inVarrockSewer()) {
      takeSewerKey();
      return;
    }
    if (!carrying(WATER, 1)) {
      if (!carrying(BUCKET, 1)) {
        if (!bucketSpawnTried) {
          walker.walkTo(121, 606, 0, 0);
          int[] bucket = c.getNearestItemById(BUCKET);
          if (bucket != null
              && Math.max(Math.abs(c.currentX() - bucket[0]), Math.abs(c.currentY() - bucket[1]))
                  <= 1) {
            int before = c.getInventoryItemCount(BUCKET);
            c.pickupItem(bucket[0], bucket[1], BUCKET, false, true);
            c.sleep(900);
            if (c.getInventoryItemCount(BUCKET) > before) return;
          }
          bucketSpawnTried = true;
          c.log(
              "AIOQuester: the bucket at 121,607 cannot be stood on; buying one from the Lumbridge general store",
              "yel");
          return;
        }
        if (coins() < 10) {
          gatherCoins(10);
          return;
        }
        walker.walkTo(133, 642, 3, 0);
        buyBaseStock(new int[] {NpcId.SHOPKEEPER_LUMBRIDGE.getId()}, BUCKET, 2, 1);
        return;
      }
      walker.walkTo(124, 652, 1, 0);
      c.useItemIdOnObject(124, 652, BUCKET);
      c.sleep(1200);
      return;
    }
    walker.walkTo(117, 461, 1, 0);
    useCarriedItemOnObject(117, 461, WATER);
    c.sleep(1500);
    if (c.currentY() < 3000) {
      enterVarrockSewer();
      return;
    }
    takeSewerKey();
  }

  private boolean inVarrockSewer() {
    int y = c.currentY();
    return y >= 3200 && y < 3600 && c.currentX() < 250;
  }

  /** Picks the key up, or climbs out and washes it down again when the spawn has gone. */
  private void takeSewerKey() {
    CURRENT_QUEST_STEP = "Picking up Sir Prysin's key in the Varrock sewer";
    walker.walkTo(117, 3294, 2, 0);
    int[] key = c.getNearestItemById(KEY3);
    if (key != null) {
      sewerKeyMisses = 0;
      pickupGroundItem(KEY3, 1);
      return;
    }
    sewerKeyMisses++;
    if (sewerKeyMisses < 3) {
      c.sleep(1200);
      return;
    }
    sewerKeyMisses = 0;
    c.log(
        "AIOQuester: Sir Prysin's key is not in the sewer, so it will be washed down the drain again",
        "yel");
    leaveVarrockSewer();
  }

  /** Ladder 5 at 111,3306 is the manhole exit. A ground walk from this floor never starts. */
  private void leaveVarrockSewer() {
    CURRENT_QUEST_STEP = "Climbing out of the Varrock sewer";
    walker.walkTo(111, 3305, 1, 0);
    walker.climb(111, 3306, false, 3);
    c.sleep(800);
  }

  /** Manhole 78/79 east of the palace, not the kitchen drain at 117,461. */
  private void enterVarrockSewer() {
    int[] hole = c.getNearestObjectByIds(new int[] {78, 79});
    if (hole == null || Math.max(Math.abs(hole[0] - 110), Math.abs(hole[1] - 454)) > 20) {
      walker.walkTo(110, 454, 2, 0);
      hole = c.getNearestObjectByIds(new int[] {78, 79});
    }
    if (hole == null) {
      waitPolitely("the Varrock sewer manhole");
      return;
    }
    if (c.getObjectAtCoord(hole[0], hole[1]) == 78) {
      c.atObject(hole[0], hole[1]);
      c.sleep(1200);
    }
    walker.climb(hole[0], hole[1], false, 3);
  }

  private void fightDelrith() {
    if (inVarrockSewer()) {
      leaveVarrockSewer();
      return;
    }
    if (!delrithFoodReady && !c.isInCombat() && !c.isInOptionMenu()) {
      CURRENT_QUEST_STEP = "Withdrawing the best food for Delrith";
      withdrawBestFood(SWORD);
      delrithFoodReady = true;
      return;
    }
    if (carrying(SWORD, 1) && !c.isItemIdEquipped(SWORD)) {
      c.equipItemById(SWORD);
      c.sleepUntil(() -> c.isItemIdEquipped(SWORD), 4000);
    }
    walker.walkTo(111, 550, 2, 0);
    if (c.isInOptionMenu()) {
      answerMenuIfPresent(INCANTATION, "Carlem Aber Camerinthum Purchai Gabindo");
      c.sleep(640);
      return;
    }
    if (c.isInCombat()) {
      if (c.isInOptionMenu()) answerMenuIfPresent(INCANTATION);
      c.sleep(640);
      return;
    }
    if (!readyToFight()) return;
    ORSCharacter demon = c.getNearestNpcByIds(new int[] {DELRITH}, false);
    if (demon == null) {
      ORSCharacter busy = c.getNearestNpcByIds(new int[] {DELRITH}, true);
      waitPolitely(busy != null ? "Delrith (in combat)" : "Delrith to respawn");
      return;
    }
    if (!c.isItemIdEquipped(SWORD)) {
      c.log("AIOQuester: Silverlight must be equipped before attacking Delrith", "yel");
      return;
    }
    c.attackNpc(demon.serverIndex);
    c.sleep(1280);
  }
}
