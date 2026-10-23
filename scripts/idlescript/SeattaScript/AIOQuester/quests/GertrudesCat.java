package scripting.idlescript.SeattaScript.AIOQuester.quests;

import models.entities.ItemId;
import models.entities.NpcId;
import models.entities.SceneryId;
import models.entities.SkillId;
import orsc.ORSCharacter;
import scripting.idlescript.SeattaScript.AIOQuester.QuestHandler;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuitReason;

/**
 * Gertrude's Cat. Milk comes from a cow. A sardine is bait-fished (rod + bait, Fishing 5) at
 * Gerrant's spot; the net option is never used (it only gives shrimp). Doogle leaves are
 * picked behind Gertrude's house. Shilop is paid 100 coins. Nothing is taken from another player.
 *
 * <p>Stages and lines are the local GertrudesCat plugin. Fluffs is the ground item at 58,2327,
 * reached through the fence at 51,438 and the ladder at 60,439. The kittens are in the crate at
 * 64,445.
 */
public final class GertrudesCat extends QuestHandler {
  private static final int MILK = ItemId.MILK.getId();
  private static final int BUCKET = ItemId.BUCKET.getId();
  private static final int SARDINE = ItemId.RAW_SARDINE.getId();
  private static final int SHRIMP = ItemId.RAW_SHRIMP.getId();
  private static final int DOOGLE = ItemId.DOOGLE_LEAVES.getId();
  private static final int SEASONED = ItemId.SEASONED_SARDINE.getId();
  private static final int KITTENS = ItemId.KITTENS.getId();
  private static final int CAT = ItemId.GERTRUDES_CAT.getId();
  private static final int NET = ItemId.NET.getId();
  private static final int ROD = ItemId.FISHING_ROD.getId();
  private static final int BAIT = ItemId.FISHING_BAIT.getId();
  private static final int ANCHOVY = ItemId.RAW_ANCHOVIES.getId();
  private static final int GERTRUDE = NpcId.GERTRUDE.getId();
  private static final int SHILOP = NpcId.SHILOP.getId();
  private static final int WILOUGH = NpcId.WILOUGH.getId();
  private static final int COW = NpcId.COW_ATTACKABLE.getId();
  private static final int GERRANT = NpcId.GERRANT.getId();
  private static final int NET_SPOT = SceneryId.FISH_NET_BAIT.getId();

  @Override
  public void run() {
    hasWalkedToStart = true;
    while (isQuesting()) {
      int stage = QUEST_STAGE;
      if (stage == 0) {
        CURRENT_QUEST_STEP = "Asking Gertrude about Fluffs";
        walker.walkTo(165, 512, 2, 0);
        runDialogue(
            GERTRUDE,
            new String[] {"well, i suppose i could", "alright then, i'll see what i can do"},
            true,
            2);
      } else if (stage == 1) {
        CURRENT_QUEST_STEP = "Paying Shilop";
        payBoy();
      } else if (stage == 2) {
        CURRENT_QUEST_STEP = "Feeding Fluffs and returning her kittens";
        feedCat();
      } else if (stage == 3) {
        CURRENT_QUEST_STEP = "Telling Gertrude that Fluffs is home";
        if (upstairs()) {
          walker.climb(60, 2328, false, 3);
          continue;
        }
        if (millFloor1()) {
          walker.climb(60, 1383, false, 3);
          continue;
        }
        if (inYard()) {
          atWallObject(51, 438);
          c.sleepUntil(() -> c.currentX() == 50, 4000);
          continue;
        }
        walker.walkTo(165, 512, 2, 0);
        runDialogue(GERTRUDE, new String[0], true, 2);
      } else {
        quit(QuitReason.QUEST_STAGE_NOT_IN_SWITCH);
        return;
      }
    }
  }

  private void payBoy() {
    if (coins() < 100) {
      gatherCoins(100);
      return;
    }
    walker.walkTo(126, 505, 3, 0);
    int[] boys = {SHILOP, WILOUGH};
    ORSCharacter boy = c.getNearestNpcByIds(boys, false);
    if (boy == null) {
      waitPolitely("Shilop or Wilough");
      return;
    }
    runDialogue(
        boy.npcId,
        new String[] {"what will make you tell me?", "ok then, i'll pay"},
        true,
        2);
  }

  /** Set after a crate search that did not give the kittens, so food is gathered before searching again. */
  private boolean crateEmpty = false;

  private void feedCat() {
    if (carrying(KITTENS, 1)) {
      if (!upstairs()) {
        goUpToCat();
        return;
      }
      walker.walkTo(58, 2327, 1, 0);
      c.useItemOnGroundItem(58, 2327, KITTENS, CAT);
      c.sleep(1500);
      return;
    }
    if (upstairs()) {
      walker.walkTo(58, 2327, 1, 0);
      if (carrying(MILK, 1)) {
        c.useItemOnGroundItem(58, 2327, MILK, CAT);
        c.sleep(1200);
        return;
      }
      if (carrying(SEASONED, 1)) {
        c.useItemOnGroundItem(58, 2327, SEASONED, CAT);
        c.sleep(1200);
        return;
      }
      crateEmpty = false;
      walker.climb(60, 2328, false, 3);
      return;
    }
    if (millFloor1()) {
      if (carrying(KITTENS, 1) || carrying(MILK, 1) || carrying(SEASONED, 1)) {
        goUpToCat();
        return;
      }
      walker.climb(60, 1383, false, 3);
      return;
    }
    if (!inYard()) {
      walker.walkTo(50, 438, 0, 0);
      atWallObject(51, 438);
      c.sleepUntil(() -> c.currentX() >= 51, 4000);
      return;
    }
    if (!crateEmpty) {
      walker.walkTo(64, 446, 0, 0);
      int before = c.getInventoryItemCount(KITTENS);
      c.atObject(64, 445);
      noteRepeatedAction("search crate 64,445");
      c.sleepUntil(() -> c.getInventoryItemCount(KITTENS) > before, 5000);
      if (c.getInventoryItemCount(KITTENS) <= before && gameMessageContains("you find nothing")) {
        crateEmpty = true;
      }
      return;
    }
    if (!carrying(SEASONED, 1)) {
      if (!carrying(SARDINE, 1)) {
        fishSardine();
        return;
      }
      dropFishJunk();
      if (!carrying(DOOGLE, 1)) {
        pickDoogle();
        return;
      }
      useItemsTogether(SARDINE, DOOGLE);
      c.sleep(600);
      return;
    }
    if (!carrying(MILK, 1)) {
      milkCow();
      return;
    }
    goUpToCat();
  }

  private void goUpToCat() {
    if (upstairs()) return;
    if (millFloor1()) {
      c.log("AIOQuester: climbing the lumber mill ladder at 60,1384 to Fluffs", "yel");
      walker.climb(60, 1384, false, 3);
      return;
    }
    if (!inYard()) {
      walker.walkTo(50, 438, 0, 0);
      atWallObject(51, 438);
      return;
    }
    climb(60, 439);
  }

  /** Ground floor of the lumber mill, on the inside of the broken fence. */
  private boolean inYard() {
    int x = c.currentX();
    int y = c.currentY();
    return y < 944 && x >= 51 && x <= 75 && y >= 430 && y <= 455;
  }

  private boolean upstairs() {
    int y = c.currentY();
    return y > 2200 && y < 2500 && c.currentX() >= 50 && c.currentX() <= 75;
  }

  /** Lumber mill floor 1 (ladder 5 at 60,1384 goes up to Fluffs). */
  private boolean millFloor1() {
    int x = c.currentX();
    int y = c.currentY();
    return y >= 1370 && y <= 1400 && x >= 50 && x <= 75;
  }

  private void dropFishJunk() {
    int[] junk = {SHRIMP, ANCHOVY, NET};
    for (int id : junk) {
      while (running() && c.getInventoryItemCount(id) > 0) {
        int slot = c.getInventoryItemSlotIndex(id);
        if (slot < 0) break;
        c.dropItem(slot);
        c.sleep(400);
      }
    }
  }

  private void dropIfFull() {
    if (c.getInventoryItemCount() < 28) return;
    for (int id : new int[] {SHRIMP, ANCHOVY, NET}) {
      int slot = c.getInventoryItemSlotIndex(id);
      if (slot >= 0) {
        c.dropItem(slot);
        c.sleep(400);
        return;
      }
    }
  }

  /**
   * Raw sardine is the bait option at Gerrant's spot (rod + bait, Fishing 5). The net option only
   * ever gives shrimp and anchovies.
   */
  private void fishSardine() {
    dropFishJunk();
    dropIfFull();
    if (c.getBaseStat(SkillId.FISHING.getId()) < 5) {
      c.log(
          "AIOQuester: a sardine needs Fishing 5, a fishing rod and bait. Not netting at 224,659.",
          "red");
      quit(QuitReason.REQUIREMENTS_NOT_MET);
      return;
    }
    if (!carrying(ROD, 1) || c.getInventoryItemCount(BAIT) < 1) {
      buyRodAndBait();
      return;
    }
    int[] spot = c.getNearestReachableObjectById(NET_SPOT, true);
    if (spot == null || Math.max(Math.abs(spot[0] - 224), Math.abs(spot[1] - 659)) > 20) {
      walker.walkTo(224, 660, 2, 0);
      return;
    }
    CURRENT_QUEST_STEP = "Baiting a sardine";
    c.atObject2(spot[0], spot[1]);
    c.sleep(2000);
  }

  private void buyRodAndBait() {
    if (coins() < 80) {
      gatherCoins(80);
      return;
    }
    walker.walkTo(279, 647, 2, 0);
    if (!carrying(ROD, 1)) buyBaseStock(new int[] {GERRANT}, ROD, 5, 1);
    if (c.getInventoryItemCount(BAIT) < 5)
      buyBaseStock(new int[] {GERRANT}, BAIT, 200, 10 - c.getInventoryItemCount(BAIT));
  }

  private void pickDoogle() {
    walker.walkTo(165, 521, 1, 0);
    int[] leaf = c.getNearestItemById(DOOGLE, 8);
    if (leaf == null) {
      waitPolitely("doogle leaves behind Gertrude's house");
      return;
    }
    c.pickupItem(leaf[0], leaf[1], DOOGLE, true, true);
    c.sleep(600);
  }

  private void milkCow() {
    if (!carrying(BUCKET, 1) && !carrying(MILK, 1)) {
      if (coins() < 20) {
        gatherCoins(20);
        return;
      }
      int[] spawn = c.getNearestItemById(BUCKET, 12);
      if (c.currentX() < 140 && c.currentY() > 580 && c.currentY() < 700 && spawn != null) {
        c.pickupItem(spawn[0], spawn[1], BUCKET, true, true);
        c.sleep(600);
        return;
      }
      walker.walkTo(121, 606, 0, 0);
      spawn = c.getNearestItemById(BUCKET, 6);
      if (spawn != null) {
        c.pickupItem(spawn[0], spawn[1], BUCKET, true, true);
        c.sleep(600);
        return;
      }
      buyBaseStock(
          new int[] {NpcId.SHOPKEEPER_LUMBRIDGE.getId(), NpcId.SHOP_ASSISTANT_LUMBRIDGE.getId()},
          BUCKET,
          2,
          1);
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
}
