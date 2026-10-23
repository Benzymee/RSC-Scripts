package scripting.idlescript.SeattaScript.AIOQuester.quests;

import models.entities.ItemId;
import models.entities.NpcId;
import scripting.idlescript.SeattaScript.AIOQuester.QuestHandler;
import scripting.idlescript.SeattaScript.AIOQuester.QuestWalker;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuitReason;

/**
 * Sea Slug. Swamp tar is picked up in the Lumbridge swamp, mixed with flour from the Lumbridge
 * mill, and heated on a fire. The torch is lit on the platform, after the crossing, because the
 * crossing puts a lit torch out. Lighting the dry sticks needs 30 Firemaking on this server.
 *
 * <p>Lines are the local SeaSlug plugin. Holgart on the shore is at 516,612. The platform ladder
 * is 494,618 and the crane is 493,1558.
 */
public final class SeaSlug extends QuestHandler {
  private static final int POT = ItemId.POT.getId();
  private static final int FLOUR_POT = ItemId.POT_OF_FLOUR.getId();
  private static final int GRAIN = ItemId.GRAIN.getId();
  private static final int FLOUR = ItemId.FLOUR.getId();
  private static final int TAR = ItemId.SWAMP_TAR.getId();
  private static final int UNCOOKED = ItemId.UNCOOKED_SWAMP_PASTE.getId();
  private static final int PASTE = ItemId.SWAMP_PASTE.getId();
  private static final int DAMP = ItemId.DAMP_STICKS.getId();
  private static final int DRY = ItemId.DRY_STICKS.getId();
  private static final int GLASS = ItemId.BROKEN_GLASS.getId();
  private static final int UNLIT = ItemId.UNLIT_TORCH.getId();
  private static final int LIT = ItemId.LIT_TORCH.getId();
  private static final int CAROLINE = NpcId.CAROLINE.getId();
  private static final int HOLGART = NpcId.HOLGART_LAND.getId();
  private static final int HOLGART_BOAT = NpcId.HOLGART_PLATFORM.getId();
  private static final int HOLGART_ISLAND = NpcId.HOLGART_ISLAND.getId();
  private static final int KENNITH = NpcId.KENNITH.getId();
  private static final int KENT = NpcId.KENT.getId();
  private static final int BAILEY = NpcId.BAILEY.getId();
  private static final int SHOP = NpcId.SHOPKEEPER_LUMBRIDGE.getId();

  private boolean hopperUsed;
  private boolean panelKicked;
  private boolean secondClimb;
  private boolean warnedFiremaking;

  @Override
  public void run() {
    hasWalkedToStart = true;
    while (isQuesting()) {
      int stage = QUEST_STAGE;
      if (stage == 0) {
        CURRENT_QUEST_STEP = "Asking Caroline about the platform";
        walker.walkTo(521, 619, 2, 0);
        runDialogue(CAROLINE, new String[] {"i suppose so, how do i get there?"}, true, 2);
      } else if (stage == 1) {
        CURRENT_QUEST_STEP = "Asking Holgart for a ride";
        walker.walkTo(516, 612, 2, 0);
        runDialogue(HOLGART, new String[0], true, 2);
      } else if (stage == 2) {
        CURRENT_QUEST_STEP = "Making swamp paste";
        if (!carrying(PASTE, 1)) makePaste();
        else {
          walker.walkTo(516, 612, 2, 0);
          runDialogue(HOLGART, new String[] {"okay, lets do it"}, true, 2);
        }
      } else if (stage == 3) {
        CURRENT_QUEST_STEP = "Finding Kennith";
        if (!onPlatform() && QuestWalker.floor(c.currentY()) == 0) {
          walker.walkTo(516, 612, 2, 0);
          runDialogue(HOLGART, new String[] {"will you take me there?", "okay, lets do it"}, true, 2);
        } else if (QuestWalker.floor(c.currentY()) == 0) {
          walker.walkTo(494, 618, 1, 0);
          climbEither(494, 618);
        } else {
          walker.walkTo(484, 1560, 2, 0);
          runDialogue(KENNITH, new String[0], true, 2);
        }
      } else if (stage == 4) {
        CURRENT_QUEST_STEP = "Finding Kent";
        if (onIsland()) {
          walker.walkTo(510, 636, 2, 0);
          runDialogue(KENT, new String[0], true, 2);
        } else if (QuestWalker.floor(c.currentY()) > 0) {
          climbEither(494, 1562);
        } else if (onPlatform()) {
          walker.walkTo(493, 615, 2, 0);
          runDialogue(HOLGART_BOAT, new String[0], true, 2);
        } else {
          walker.walkTo(516, 612, 2, 0);
          runDialogue(HOLGART, new String[] {"will you take me there?"}, true, 2);
        }
      } else if (stage == 5) {
        CURRENT_QUEST_STEP = "Freeing Kennith";
        freeKennith();
      } else if (stage == 6) {
        CURRENT_QUEST_STEP = "Returning to Caroline";
        if (onPlatform() || QuestWalker.floor(c.currentY()) > 0) {
          if (QuestWalker.floor(c.currentY()) > 0) climbEither(494, 1562);
          else {
            walker.walkTo(493, 615, 2, 0);
            runDialogue(HOLGART_BOAT, new String[0], true, 2);
          }
        } else {
          walker.walkTo(521, 619, 2, 0);
          runDialogue(CAROLINE, new String[0], true, 2);
        }
      } else {
        quit(QuitReason.QUEST_STAGE_NOT_IN_SWITCH);
        return;
      }
    }
  }

  private void freeKennith() {
    if (onIsland()) {
      walker.walkTo(512, 638, 2, 0);
      runDialogue(HOLGART_ISLAND, new String[] {"will you take me there?"}, true, 2);
      return;
    }
    if (!onPlatform() && QuestWalker.floor(c.currentY()) == 0) {
      walker.walkTo(516, 612, 2, 0);
      runDialogue(HOLGART, new String[] {"will you take me there?"}, true, 2);
      return;
    }
    if (!carrying(LIT, 1)) {
      lightTorch();
      return;
    }
    if (QuestWalker.floor(c.currentY()) == 0) {
      walker.walkTo(494, 618, 1, 0);
      climbEither(494, 618);
      return;
    }
    if (!panelKicked) {
      walker.walkTo(487, 1558, 1, 0);
      atWallObject(487, 1558);
      c.sleep(1200);
      if (gameMessageContains("rotten") || gameMessageContains("opening")) panelKicked = true;
      return;
    }
    walker.walkTo(493, 1558, 1, 0);
    c.atObject(493, 1558);
    c.sleep(3000);
  }

  private void lightTorch() {
    if (QuestWalker.floor(c.currentY()) > 0) {
      climbEither(494, 1562);
      return;
    }
    if (!carrying(UNLIT, 1)) {
      walker.walkTo(483, 614, 2, 0);
      runDialogue(BAILEY, new String[0], true, 2);
      return;
    }
    if (!carrying(DRY, 1)) {
      if (!carrying(DAMP, 1)) {
        walker.walkTo(488, 609, 2, 0);
        pickupGroundItem(DAMP, 1);
        return;
      }
      if (!carrying(GLASS, 1)) {
        walker.walkTo(485, 614, 2, 0);
        pickupGroundItem(GLASS, 1);
        return;
      }
      useItemsTogether(GLASS, DAMP);
      c.sleep(1000);
      return;
    }
    c.itemCommand(DRY);
    c.sleep(1500);
    if (!warnedFiremaking && gameMessageContains("firemaking")) {
      warnedFiremaking = true;
      c.log(
          "AIOQuester: Sea Slug torch needs 30 Firemaking. The sticks stay in the inventory.",
          "red");
    }
  }

  private void makePaste() {
    if (carrying(UNCOOKED, 1)) {
      walker.walkTo(178, 668, 1, 0);
      c.useItemIdOnObject(178, 668, UNCOOKED);
      c.sleep(1500);
      return;
    }
    if (carrying(FLOUR_POT, 1) && carrying(TAR, 1)) {
      useItemsTogether(FLOUR_POT, TAR);
      c.sleep(1000);
      return;
    }
    if (carrying(FLOUR_POT, 1)) {
      walker.walkTo(166, 674, 3, 0);
      pickupGroundItem(TAR, 1);
      return;
    }
    if (QuestWalker.floor(c.currentY()) == 0 && !hopperUsed) {
      if (!carrying(GRAIN, 1)) {
        if (!carrying(POT, 1)) {
          buyPot();
          return;
        }
        walker.walkTo(173, 602, 2, 0);
        c.atObject2(173, 602);
        c.sleep(1200);
        return;
      }
    }
    mill();
  }

  private void buyPot() {
    if (coins() < 20) {
      gatherCoins(20);
      return;
    }
    walker.walkTo(134, 643, 3, 0);
    buyBaseStock(new int[] {SHOP}, POT, 3, 1);
  }

  private void mill() {
    int floor = QuestWalker.floor(c.currentY());
    if (floor == 2) {
      if (gameMessageContains("already grain")) {
        c.log("AIOQuester: hopper already has grain; operating it", "yel");
        c.atObject(166, 2487);
        c.sleep(2000);
        hopperUsed = true;
        return;
      }
      if (carrying(GRAIN, 1) && !hopperUsed) {
        c.useItemIdOnObject(166, 2487, GRAIN);
        c.sleep(1200);
        if (gameMessageContains("already grain")) {
          c.log("AIOQuester: hopper already has grain; operating it", "yel");
          c.atObject(166, 2487);
          c.sleep(2000);
          hopperUsed = true;
        }
        return;
      }
      if (!hopperUsed) {
        c.atObject(166, 2487);
        c.sleep(2000);
        hopperUsed = true;
        return;
      }
      climbEither(166, 2490);
      return;
    }
    if (floor == 1) {
      if (!hopperUsed) climbEither(166, 1546);
      else climbEither(165, 1542);
      return;
    }
    if (!hopperUsed) {
      walker.walkTo(166, 600, 1, 0);
      climbEither(165, 598);
      return;
    }
    walker.walkTo(166, 598, 1, 0);
    int[] flour = c.getNearestItemById(FLOUR);
    if (flour != null) {
      c.pickupItem(flour[0], flour[1], FLOUR, false, true);
      c.sleep(800);
    } else waitPolitely("flour on the chute");
  }

  private boolean onPlatform() {
    int x = c.currentX();
    int y = c.currentY();
    return x >= 475 && x <= 500 && y >= 600 && y <= 625;
  }

  private boolean onIsland() {
    int x = c.currentX();
    int y = c.currentY();
    return x >= 505 && x <= 520 && y >= 630 && y <= 648;
  }

  private void climbEither(int x, int y) {
    int before = c.currentY();
    walker.climb(x, y, secondClimb, 2);
    if (Math.abs(c.currentY() - before) < 400) secondClimb = !secondClimb;
    else secondClimb = false;
  }
}
