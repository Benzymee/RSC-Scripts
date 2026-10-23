package scripting.idlescript.SeattaScript.AIOQuester.quests;

import models.entities.ItemId;
import models.entities.NpcId;
import orsc.ORSCharacter;
import scripting.idlescript.SeattaScript.AIOQuester.QuestHandler;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuitReason;

/**
 * Black Knights' Fortress. Stages from the OpenRSC plugin: 0 Sir Amik, 1 listen at the grill, 2
 * cabbage down the hole, 3 back to Sir Amik. The cabbage is the Falador field spawn (item 18).
 * The Draynor Manor cabbage (item 228) does not count. Iron chain and bronze med come from Wayne's
 * and Peksa's own stock.
 *
 * <p>Ladders were walked on a local Coleslaw server. The castle ladder is 303,563, reached from
 * 304,564. The fortress grill is behind the odd wall at 273,435 and ladder 271,433. The hole is
 * through doors 275,439 and 278,443, stairs 273,444 and ladder 281,1388, then the odd wall
 * 281,2325. The way out reverses that: wall 281,2325, ladder 281,2332, stairs 273,1388, the doors,
 * then the entrance.
 */
public final class BlackKnightsFortress extends QuestHandler {
  private static final int CHAIN = ItemId.IRON_CHAIN_MAIL_BODY.getId();
  private static final int HELM = ItemId.MEDIUM_BRONZE_HELMET.getId();
  private static final int CABBAGE = ItemId.CABBAGE.getId();
  private static final int AMIK = NpcId.SIR_AMIK_VARZE.getId();

  private boolean grillQuiet;
  /** Failed pickpocket. Men do not attack, but the stun has to finish before the next try. */
  private long stunnedUntil;
  /** The server refused pickpocketing, so goblins are used for the rest of the coins. */
  private boolean thieveBlocked;

  @Override
  public void run() {
    hasWalkedToStart = true;
    while (isQuesting()) {
      if (answerMenuIfPresent("I don't care I'm going in anyway", "Go up", "Go down")) continue;
      int stage = QUEST_STAGE;
      if (!disguised()) {
        CURRENT_QUEST_STEP = "Getting the black knight disguise";
        getDisguise();
      } else if (stage == 2 && !carrying(CABBAGE, 1)) {
        CURRENT_QUEST_STEP = "Picking a cabbage in the Falador field";
        getCabbage();
      } else if (stage == 0 || stage == 3) {
        CURRENT_QUEST_STEP = stage == 0 ? "Starting the quest with Sir Amik" : "Reporting back to Sir Amik";
        if (!readyForAmik()) continue;
        runDialogue(
            AMIK,
            stage == 0 ? new String[] {"I seek a quest", "I laugh in the face of danger"} : new String[0],
            true,
            2);
      } else if (stage == 1) {
        CURRENT_QUEST_STEP = "Listening at the grill";
        if (!inGrillRoom()) {
          approachGrill();
          continue;
        }
        walker.walkTo(274, 1377, 1, 0);
        atObject(275, 1377);
        long until = System.currentTimeMillis() + 30_000;
        while (running() && getQuestStage() == 1 && System.currentTimeMillis() < until) {
          if (grillQuiet) break;
          c.sleep(600);
        }
        if (getQuestStage() == 1 && grillQuiet) {
          grillQuiet = false;
          waitPolitely("the black knights by the grill");
        }
      } else if (stage == 2) {
        CURRENT_QUEST_STEP = "Pushing the cabbage through the hole";
        if (!inHoleRoom()) {
          approachHole();
          continue;
        }
        walker.walkTo(279, 2323, 1, 0);
        c.useItemIdOnObject(278, 2323, CABBAGE);
        c.sleep(2000);
      } else {
        quit(QuitReason.QUEST_STAGE_NOT_IN_SWITCH);
        return;
      }
    }
  }

  @Override
  public void serverMessageInterrupt(String message) {
    if (message == null) return;
    String lower = message.toLowerCase();
    if (lower.contains("can't hear")) grillQuiet = true;
    if (lower.contains("stun")) stunnedUntil = System.currentTimeMillis() + 5000;
    if (lower.contains("thieving level") || lower.contains("can't pickpocket")) thieveBlocked = true;
  }

  private boolean disguised() {
    return (c.isItemIdEquipped(CHAIN) || carrying(CHAIN, 1))
        && (c.isItemIdEquipped(HELM) || carrying(HELM, 1));
  }

  private void equipDisguise() {
    if (carrying(CHAIN, 1) && !c.isItemIdEquipped(CHAIN)) c.equipItemById(CHAIN);
    if (carrying(HELM, 1) && !c.isItemIdEquipped(HELM)) c.equipItemById(HELM);
    c.sleep(640);
  }

  private void getDisguise() {
    if (!carrying(CHAIN, 1) && !c.isItemIdEquipped(CHAIN)) {
      if (coins() < 500) {
        ensureCoins(500);
        return;
      }
      walker.walkTo(305, 578, 3, 0);
      buyBaseStock(new int[] {NpcId.WAYNE.getId()}, CHAIN, 2, 1);
      return;
    }
    if (!carrying(HELM, 1) && !c.isItemIdEquipped(HELM)) {
      if (coins() < 200) {
        ensureCoins(200);
        return;
      }
      walker.walkTo(236, 509, 3, 0);
      buyBaseStock(new int[] {NpcId.PEKSA.getId()}, HELM, 5, 1);
      return;
    }
    equipDisguise();
  }

  /** Bank first. Lumbridge men are the coin source. Goblins only if pickpocketing is refused. */
  private void ensureCoins(int needed) {
    if (coins() >= needed) return;
    withdrawCoins(needed);
    if (coins() >= needed || !running()) return;
    if (!thieveBlocked) thieveMen(needed);
    if (coins() < needed && running()) gatherCoins(needed);
  }

  /** Men wander the Lumbridge castle yard. A successful pickpocket puts 3 coins straight in the inventory. */
  private void thieveMen(int needed) {
    int manId = NpcId.MAN.getId();
    while (running() && !thieveBlocked && coins() < needed) {
      CURRENT_QUEST_STEP = "Pickpocketing men in Lumbridge (" + coins() + "/" + needed + ")";
      if (System.currentTimeMillis() < stunnedUntil) {
        c.sleep(600);
        continue;
      }
      if (c.isInCombat() || c.isInOptionMenu()) {
        c.sleep(640);
        continue;
      }
      if (Math.max(Math.abs(c.currentX() - 128), Math.abs(c.currentY() - 641)) > 10) {
        walker.walkTo(128, 641, 6, 0);
        continue;
      }
      if (c.getInventoryItemCount() >= 30 && coins() == 0) {
        freeInventorySlots(1);
        if (c.getInventoryItemCount() >= 30) {
          c.log("AIOQuester: no inventory space for coins from pickpocketing", "yel");
          thieveBlocked = true;
          return;
        }
      }
      ORSCharacter man = c.getNearestNpcByIds(new int[] {manId}, false);
      if (man == null) {
        waitPolitely("a man in Lumbridge");
        continue;
      }
      c.thieveNpc(man.serverIndex);
      c.sleep(900);
    }
  }

  /** Falador cabbage field only. Item 228 at Draynor Manor is refused by the quest. */
  private void getCabbage() {
    if (inFortress()) {
      leaveOneStep();
      return;
    }
    walker.walkTo(254, 606, 2, 0);
    withTurn(
        "cabbage-254-606",
        () -> {
          int[] item = c.getNearestItemById(CABBAGE);
          if (item == null || item[0] < 248 || item[0] > 262 || item[1] < 597 || item[1] > 606) {
            waitPolitely("a Falador cabbage");
            return false;
          }
          c.pickupItem(item[0], item[1], CABBAGE, true, true);
          c.sleep(640);
          markProgress();
          return true;
        });
  }

  private boolean readyForAmik() {
    equipDisguise();
    if (inFortress()) {
      leaveOneStep();
      return false;
    }
    if (onCastleFloor()) return true;
    walker.walkTo(304, 564, 1, 0);
    climbUp(303, 563);
    return false;
  }

  private void approachGrill() {
    equipDisguise();
    if (onCastleFloor()) {
      walker.walkTo(304, 1508, 1, 0);
      climbDown(303, 1507);
      return;
    }
    if (c.currentY() > 1382) {
      leaveOneStep();
      return;
    }
    if (insideGround()) {
      if (c.currentY() >= 436) {
        walker.walkTo(273, 436, 0, 0);
        atWallObject(273, 435);
      } else {
        walker.walkTo(271, 434, 1, 0);
        climbUp(271, 433);
      }
      return;
    }
    if (c.currentY() < 944) {
      walker.walkTo(270, 441, 0, 0);
      atWallObject(271, 441);
      return;
    }
    walker.walkTo(270, 441, 2, 0);
  }

  private void approachHole() {
    equipDisguise();
    if (fighting()) return;
    if (c.currentY() > 2200) {
      walker.walkTo(281, 2326, 0, 0);
      atWallObject(281, 2325);
      return;
    }
    if (c.currentY() > 1382 && c.currentY() < 2000 && c.currentX() < 296) {
      walker.walkTo(280, 1388, 1, 0);
      climbUp(281, 1388);
      return;
    }
    if (onCastleFloor() || inGrillRoom()) {
      leaveOneStep();
      return;
    }
    if (!insideGround()) {
      walker.walkTo(270, 441, 0, 0);
      atWallObject(271, 441);
      return;
    }
    if (c.currentY() <= 434) {
      walker.walkTo(273, 434, 0, 0);
      atWallObject(273, 435);
      return;
    }
    if (c.currentX() < 275) {
      walker.walkTo(274, 439, 0, 0);
      atWallObject(275, 439);
      return;
    }
    if (c.currentY() <= 442) {
      walker.walkTo(278, 442, 0, 0);
      atWallObject(278, 443);
      return;
    }
    walker.walkTo(276, 444, 0, 0);
    climbUp(273, 444);
  }

  /** One step from the cabbage room back to the Falador ground outside the entrance. */
  private void leaveOneStep() {
    if (fighting()) return;
    int y = c.currentY();
    if (inHoleRoom()) {
      walker.walkTo(281, 2324, 0, 0);
      atWallObject(281, 2325);
      return;
    }
    if (y > 2200) {
      walker.walkTo(280, 2332, 1, 0);
      climbDown(281, 2332);
      return;
    }
    if (y > 1300 && y < 1383 && c.currentX() < 296) {
      walker.walkTo(272, 1377, 1, 0);
      climbDown(271, 1377);
      return;
    }
    if (y > 944 && c.currentX() < 296) {
      if (y < 1500) {
        walker.walkTo(272, 1388, 1, 0);
        climbDown(273, 1388);
      } else {
        walker.walkTo(280, 1388, 1, 0);
        climbUp(281, 1388);
      }
      return;
    }
    if (insideGround()) {
      if (y <= 434 && c.currentX() <= 275) {
        walker.walkTo(273, 434, 0, 0);
        atWallObject(273, 435);
        return;
      }
      if (y >= 443) {
        walker.walkTo(278, 443, 1, 0);
        atWallObject(278, 443);
      } else if (c.currentX() >= 275) {
        walker.walkTo(274, 439, 0, 0);
        atWallObject(275, 439);
      } else {
        walker.walkTo(271, 441, 0, 0);
        atWallObject(271, 441);
      }
      return;
    }
  }

  private void climbUp(int x, int y) {
    int y0 = c.currentY();
    walker.climb(x, y, false, 2);
    if (c.currentY() > y0 + 500) return;
    if (c.isInOptionMenu()) {
      answerMenuIfPresent("Go up", "Climb-up");
      return;
    }
    walker.climb(x, y, true, 2);
  }

  private void climbDown(int x, int y) {
    int y0 = c.currentY();
    walker.climb(x, y, false, 2);
    if (c.currentY() < y0 - 500) return;
    if (c.isInOptionMenu()) {
      answerMenuIfPresent("Go down", "Climb-down");
      return;
    }
    walker.climb(x, y, true, 2);
  }

  private boolean fighting() {
    if (!c.isInCombat()) return false;
    c.sleep(800);
    return true;
  }

  private boolean onCastleFloor() {
    return c.currentY() > 944 && c.currentY() < 2000 && c.currentX() >= 296;
  }

  private boolean inGrillRoom() {
    return c.currentY() > 1300 && c.currentY() < 1383 && c.currentX() >= 268 && c.currentX() <= 278;
  }

  private boolean inHoleRoom() {
    return c.currentY() > 2200 && c.currentY() <= 2324 && c.currentX() >= 276;
  }

  private boolean insideGround() {
    return c.currentY() < 944
        && c.currentX() >= 271
        && c.currentX() <= 290
        && c.currentY() >= 420
        && c.currentY() <= 460;
  }

  private boolean inFortress() {
    if (c.currentY() > 2000 && c.currentX() < 300) return true;
    if (c.currentY() > 944 && c.currentY() < 2000 && c.currentX() < 296 && c.currentX() > 250)
      return true;
    return insideGround();
  }
}
