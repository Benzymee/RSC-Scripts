package scripting.idlescript.SeattaScript.AIOQuester.quests;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import models.entities.ItemId;
import models.entities.NpcId;
import scripting.idlescript.SeattaScript.AIOQuester.QuestHandler;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuitReason;

/**
 * Pirate's Treasure. Karamja rum is bought from Zambo's own stock (base 3), hidden in Luthas's
 * banana crate, and collected from Wydin's crate in the white apron. The spade is the Falador
 * ground spawn. Bananas are picked from the trees.
 *
 * <p>The crate only runs while the rum is still in the inventory, so a stash flag (also written
 * under settings/aioquester) keeps a restart from buying a second bottle and handing the first
 * one to Frank.
 */
public final class PiratesTreasure extends QuestHandler {
  private static final int RUM = ItemId.KARAMJA_RUM.getId();
  private static final int BANANA = ItemId.BANANA.getId();
  private static final int APRON = ItemId.WHITE_APRON.getId();
  private static final int SPADE = ItemId.SPADE.getId();
  private static final int KEY = ItemId.CHEST_KEY.getId();
  private static final int FRANK = NpcId.REDBEARD_FRANK.getId();

  private boolean rumStashed;
  private boolean luthasHired;
  private boolean luthasPaid;
  private boolean loadedStash;
  private boolean tookKaramjaCoins;
  private int wydinMisses;
  private int lastBananaX = -1;
  private int lastBananaY = -1;
  private int bareTreeX = -1;
  private int bareTreeY = -1;
  private long bareTreeUntil;

  /** Ripe banana tree. An empty one changes to scenery 184. */
  private static final int RIPE_BANANA_TREE = 183;

  /** Every banana tree on the Karamja plantation. */
  private static final int[][] BANANA_TREES = {
    {363, 695},
    {357, 697},
    {360, 697},
    {363, 697},
    {351, 699},
    {354, 699},
    {357, 699},
    {360, 699},
    {363, 699},
    {348, 701},
    {351, 701},
    {354, 701},
    {357, 701},
    {360, 701},
    {363, 701},
    {344, 703},
    {348, 703},
    {351, 703},
    {354, 703},
    {357, 703},
    {360, 703},
    {363, 703},
    {344, 705},
    {348, 705},
    {351, 705},
    {354, 705},
    {357, 705},
    {360, 705},
    {363, 705},
    {344, 707},
    {348, 707},
    {351, 707},
    {354, 707},
    {357, 707},
    {360, 707}
  };

  @Override
  public void run() {
    hasWalkedToStart = true;
    loadStash();
    while (isQuesting()) {
      if (QUEST_STAGE != 1) clearStash();
      if (answerMenuIfPresent(
          "Ok thanks, I'll go and get it",
          "Thankyou, I'll be on my way",
          "Well can I get a job here?",
          "Search away I have nothing to hide",
          "Can I board this ship?",
          "Yes please",
          "No")) continue;
      int stage = QUEST_STAGE;
      if (!carrying(APRON, 1) && !c.isItemIdEquipped(APRON)) {
        CURRENT_QUEST_STEP = "Buying a white apron from Thessalia";
        if (coins() < 20) gatherCoins(20);
        else {
          walker.walkTo(137, 515, 2, 0);
          buyBaseStock(new int[] {NpcId.THESSALIA.getId()}, APRON, 3, 1);
        }
      } else if (!carrying(SPADE, 1)) {
        CURRENT_QUEST_STEP = "Picking up the Falador spade";
        walker.walkTo(291, 524, 2, 0);
        withTurn(
            "spade-291-524",
            () -> {
              int[] spade = c.getNearestItemById(SPADE);
              if (spade == null) {
                waitPolitely("the Falador spade");
                return false;
              }
              pickupGroundItem(SPADE, 1);
              return true;
            });
      } else if (stage == 0) {
        CURRENT_QUEST_STEP = "Talking to Redbeard Frank";
        walker.walkTo(252, 632, 2, 0);
        runDialogue(FRANK, new String[] {"I'm in search of treasure"}, true, 2);
      } else if (stage == 1 && !(rumStashed && carrying(RUM, 1))) {
        CURRENT_QUEST_STEP = "Smuggling Karamja rum";
        smuggleRum();
      } else if (stage == 1) {
        CURRENT_QUEST_STEP = "Giving the rum to Frank";
        walker.walkTo(252, 632, 2, 0);
        runDialogue(FRANK, new String[] {"Ok thanks, I'll go and get it"}, true, 1);
      } else if (stage == 2) {
        CURRENT_QUEST_STEP = "Opening Hector's chest";
        if (!carrying(KEY, 1)) {
          walker.walkTo(252, 632, 2, 0);
          runDialogue(FRANK, new String[] {"Ok thanks, I'll go and get it"}, true, 1);
        } else {
          approachChest();
        }
      } else if (stage == 3) {
        CURRENT_QUEST_STEP = "Digging behind the bench";
        if (c.isInCombat()) {
          c.walkTo(c.currentX(), c.currentY());
          c.sleep(600);
          continue;
        }
        walker.walkTo(290, 548, 1, 0);
        c.useItemIdOnObject(289, 548, SPADE);
        c.sleep(1500);
      } else {
        quit(QuitReason.QUEST_STAGE_NOT_IN_SWITCH);
        return;
      }
    }
  }

  @Override
  public void serverMessageInterrupt(String message) {
    if (message == null) return;
    String text = message.toLowerCase();
    if (text.contains("stash the rum")) rememberStash();
    if (text.contains("hands you 30")) luthasPaid = true;
    if (text.contains("no bananas left")) {
      bareTreeX = lastBananaX;
      bareTreeY = lastBananaY;
      bareTreeUntil = System.currentTimeMillis() + 20_000;
    }
  }

  /**
   * The ladder at 129,518 comes up in a room that does not connect to Hector's chest. The chest
   * room is the ladder at 119,526. Tiles 124–129, 1457–1462 are that disconnected landing.
   */
  private void approachChest() {
    int x = c.currentX();
    int y = c.currentY();
    if (x >= 124 && x <= 129 && y >= 1457 && y <= 1462) {
      CURRENT_QUEST_STEP = "Climbing down from the wrong Blue Moon room";
      atObject(129, 1462);
      c.sleep(800);
      return;
    }
    if (y < 1000) {
      walker.walkTo(119, 526, 1, 0);
      atObject(119, 526);
      c.sleep(1200);
      return;
    }
    walker.walkTo(125, 1466, 1, 0);
    c.useItemIdOnObject(126, 1467, KEY);
    c.sleep(1500);
  }

  private void smuggleRum() {
    boolean onKaramja = c.currentX() > 300 && c.currentY() > 680 && c.currentY() < 800;
    if (!rumStashed) {
      if (!carrying(RUM, 1)) {
        if (!onKaramja) {
          if (coins() < 500 && !tookKaramjaCoins) {
            CURRENT_QUEST_STEP = "Withdrawing 500 coins for Karamja";
            int before = coins();
            withdrawCoins(500);
            tookKaramjaCoins = true;
            if (coins() > before) return;
          }
          if (coins() < 30) {
            gatherCoins(30);
            return;
          }
          walker.walkTo(269, 650, 3, 0);
          runDialogue(NpcId.CAPTAIN_TOBIAS.getId(), new String[] {"Yes please"}, true, 2);
          return;
        }
        tookKaramjaCoins = false;
        if (coins() < 40) {
          if (coins() < 30) {
            earnLuthasWages();
            return;
          }
          boardShipHome();
          return;
        }
        walker.walkTo(347, 711, 2, 0);
        buyBaseStock(new int[] {NpcId.ZAMBO.getId()}, RUM, 3, 1);
        return;
      }
      if (!onKaramja) {
        CURRENT_QUEST_STEP = "Rum is in the inventory on the mainland. It has to be stashed on Karamja";
        waitPolitely("a way back to Karamja without the customs search");
        return;
      }
      if (c.getInventoryItemCount(BANANA) < 10) {
        pickBanana();
        return;
      }
      if (!luthasHired) {
        walker.walkTo(339, 706, 3, 0);
        runDialogue(
            NpcId.LUTHAS.getId(),
            new String[] {"Could you offer me employment on your plantation?"},
            true,
            1);
        luthasHired = true;
        return;
      }
      fillCrate();
      return;
    }
    if (carrying(RUM, 1)) return;
    if (onKaramja && !luthasPaid) {
      fillCrate();
      return;
    }
    if (onKaramja) {
      if (coins() < 30) {
        earnLuthasWages();
        return;
      }
      boardShipHome();
      return;
    }
    if (!c.isItemIdEquipped(APRON)) {
      c.equipItemById(APRON);
      c.sleep(640);
      return;
    }
    if (c.currentX() < 277 || c.currentY() < 1000 && c.currentX() == 276) {
      walker.walkTo(276, 658, 0, 0);
      atWallObject(277, 658);
      c.sleep(800);
      return;
    }
    int before = c.getInventoryItemCount(RUM);
    walker.walkTo(279, 658, 1, 0);
    atObject2(279, 658);
    c.sleep(1200);
    answerMenuIfPresent("No");
    if (c.getInventoryItemCount(RUM) > before) {
      wydinMisses = 0;
      return;
    }
    if (++wydinMisses >= 4) {
      c.log("AIOQuester: Wydin's crate had no rum. Clearing the stash flag and starting again.", "yel");
      wydinMisses = 0;
      forgetStash();
    }
  }

  private void boardShipHome() {
    CURRENT_QUEST_STEP = "Sailing back to bank for Karamja";
    walker.walkTo(326, 713, 3, 0);
    runDialogue(
        NpcId.CUSTOMS_OFFICER.getId(),
        new String[] {"Can I board this ship?", "Search away I have nothing to hide", "Ok"},
        true,
        2);
  }

  /**
   * Picks the nearest plantation tree that still has fruit. A tree that just came up empty is left
   * alone so the next one is used.
   */
  private void pickBanana() {
    int[] tree = nearestBananaTree();
    if (tree == null) {
      CURRENT_QUEST_STEP = "Waiting for a banana tree to fruit";
      c.sleep(2000);
      return;
    }
    int dist = Math.max(Math.abs(c.currentX() - tree[0]), Math.abs(c.currentY() - tree[1]));
    if (dist > 1) {
      walker.walkTo(tree[0], tree[1], 1, 0);
      return;
    }
    lastBananaX = tree[0];
    lastBananaY = tree[1];
    int before = c.getInventoryItemCount(BANANA);
    atObject2(tree[0], tree[1]);
    c.sleep(400);
    if (c.getInventoryItemCount(BANANA) == before) {
      bareTreeX = tree[0];
      bareTreeY = tree[1];
      bareTreeUntil = System.currentTimeMillis() + 20_000;
    }
  }

  private int[] nearestBananaTree() {
    long now = System.currentTimeMillis();
    if (now >= bareTreeUntil) {
      bareTreeX = -1;
      bareTreeY = -1;
    }
    int px = c.currentX();
    int py = c.currentY();
    int[] ripe = null;
    int ripeDist = Integer.MAX_VALUE;
    int[] unseen = null;
    int unseenDist = Integer.MAX_VALUE;
    for (int[] tree : BANANA_TREES) {
      if (tree[0] == bareTreeX && tree[1] == bareTreeY) continue;
      int dist = Math.max(Math.abs(px - tree[0]), Math.abs(py - tree[1]));
      int id = c.getObjectAtCoord(tree[0], tree[1]);
      if (id == RIPE_BANANA_TREE && dist < ripeDist) {
        ripeDist = dist;
        ripe = tree;
      } else if (id < 0 && dist < unseenDist) {
        unseenDist = dist;
        unseen = tree;
      }
    }
    return ripe != null ? ripe : unseen;
  }

  /**
   * One crate of bananas pays 30 coins. On Karamja that is the way to afford the rum or the boat
   * back. A payment before the rum is in the crate is wages, so the quest shipment can still be
   * paid for afterwards.
   */
  private void earnLuthasWages() {
    CURRENT_QUEST_STEP = "Earning coins from Luthas (" + coins() + ")";
    if (c.getInventoryItemCount(BANANA) < 10) {
      pickBanana();
      return;
    }
    if (!luthasHired) {
      walker.walkTo(339, 706, 3, 0);
      runDialogue(
          NpcId.LUTHAS.getId(),
          new String[] {"Could you offer me employment on your plantation?"},
          true,
          1);
      luthasHired = true;
      return;
    }
    boolean shipped = rumStashed;
    fillCrate();
    if (!shipped) luthasPaid = false;
  }

  /** Rum goes in first. Luthas pays when the crate already holds 10 bananas, then the thank-you menu. */
  private void fillCrate() {
    walker.walkTo(337, 711, 1, 0);
    if (carrying(RUM, 1)) {
      c.useItemIdOnObject(337, 711, RUM);
      c.sleep(800);
    }
    if (carrying(RUM, 1)) return;
    int guard = 0;
    while (running() && carrying(BANANA, 1) && guard++ < 12) {
      c.useItemIdOnObject(337, 711, BANANA);
      c.sleep(500);
    }
    if (carrying(BANANA, 1)) return;
    walker.walkTo(339, 706, 3, 0);
    if (runDialogue(NpcId.LUTHAS.getId(), new String[] {"Thankyou, I'll be on my way"}, true, 1))
      luthasPaid = true;
  }

  private void loadStash() {
    if (loadedStash) return;
    loadedStash = true;
    try {
      if (Files.exists(stashFile())) rumStashed = true;
    } catch (RuntimeException ignored) {
      // The in-memory flag still covers this run.
    }
  }

  private void rememberStash() {
    rumStashed = true;
    try {
      Path file = stashFile();
      Files.createDirectories(file.getParent());
      Files.write(file, "stashed".getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      c.log("AIOQuester: could not save the rum stash flag", "yel");
    }
  }

  private void clearStash() {
    if (!rumStashed && loadedStash) {
      try {
        if (!Files.exists(stashFile())) return;
      } catch (RuntimeException ignored) {
        return;
      }
    }
    forgetStash();
  }

  private void forgetStash() {
    rumStashed = false;
    luthasPaid = false;
    try {
      Files.deleteIfExists(stashFile());
    } catch (IOException ignored) {
      // The next stage-1 run will see the flag and collect from Wydin instead of buying again.
    }
  }

  private Path stashFile() {
    String name = c.getPlayerName();
    if (name == null || name.isEmpty()) name = "unknown";
    name = name.replaceAll("[^A-Za-z0-9@.\\-_]", "_");
    return Paths.get("settings", "aioquester", "pirate-rum-" + name + ".flag");
  }
}
