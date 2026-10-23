package scripting.idlescript.SeattaScript.AIOQuester.quests;

import models.entities.ItemId;
import models.entities.NpcId;
import scripting.idlescript.SeattaScript.AIOQuester.QuestHandler;
import scripting.idlescript.SeattaScript.AIOQuester.QuestWalker;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuitReason;

/**
 * Murder Mystery. The killer is chosen by the server and the client cannot read that choice.
 * The window thread, the six poison sites and a fingerprint match are all collected, then the
 * guard is told "I know who did it!". That completes the quest without naming the killer.
 *
 * <p>Flour comes from the mansion barrel, which fills an empty pot bought from Arhein's own
 * stock. Each dusting returns the empty pot. Flypaper comes from the sack at 500,385.
 */
public final class MurderMystery extends QuestHandler {
  private static final int POT = ItemId.POT.getId();
  private static final int FLOUR = ItemId.POT_OF_FLOUR.getId();
  private static final int PAPER = ItemId.FLYPAPER.getId();
  private static final int DAGGER = ItemId.A_SILVER_DAGGER.getId();
  private static final int DAGGER_FLOUR = ItemId.A_SILVER_DAGGER_FLOUR.getId();
  private static final int UNKNOWN = ItemId.UNIDENTIFIED_FINGERPRINT.getId();
  private static final int MATCH = ItemId.MURDERERS_FINGERPRINT.getId();
  private static final int GUARD = NpcId.GUARD_SINCLAIR_MANSION.getId();
  private static final int ARHEIN = NpcId.ARHEIN.getId();

  private static final int[] PERSON = {
    NpcId.ANNA_SINCLAIR.getId(),
    NpcId.BOB_SINCLAIR.getId(),
    NpcId.FRANK_SINCLAIR.getId(),
    NpcId.DAVID_SINCLAIR.getId(),
    NpcId.CAROL_SINCLAIR.getId(),
    NpcId.ELIZABETH_SINCLAIR.getId()
  };
  private static final int[] SILVER = {
    ItemId.ANNAS_SILVER_NECKLACE.getId(),
    ItemId.BOBS_SILVER_TEACUP.getId(),
    ItemId.FRANKS_SILVER_POT.getId(),
    ItemId.DAVIDS_SILVER_BOOK.getId(),
    ItemId.CAROLS_SILVER_BOTTLE.getId(),
    ItemId.ELIZABETHS_SILVER_NEEDLE.getId()
  };
  private static final int[] DUSTED = {
    ItemId.ANNAS_SILVER_NECKLACE_FLOUR.getId(),
    ItemId.BOBS_SILVER_TEACUP_FLOUR.getId(),
    ItemId.FRANKS_SILVER_POT_FLOUR.getId(),
    ItemId.DAVIDS_SILVER_BOOK_FLOUR.getId(),
    ItemId.CAROLS_SILVER_BOTTLE_FLOUR.getId(),
    ItemId.ELIZABETHS_SILVER_NEEDLE_FLOUR.getId()
  };
  private static final int[] PRINT = {
    ItemId.ANNAS_FINGERPRINT.getId(),
    ItemId.BOBS_FINGERPRINT.getId(),
    ItemId.FRANKS_FINGERPRINT.getId(),
    ItemId.DAVIDS_FINGERPRINT.getId(),
    ItemId.CAROLS_FINGERPRINT.getId(),
    ItemId.ELIZABETHS_FINGERPRINT.getId()
  };
  private static final int[][] CONTAINER = {
    {498, 390}, {498, 391}, {498, 1334}, {484, 1332}, {484, 1331}, {498, 1331}
  };
  private static final int[][] SITES = {
    {500, 392}, {500, 406}, {493, 394}, {484, 399}, {486, 394}, {491, 1337}
  };

  private boolean askedSalesman;
  private boolean asked;
  private boolean sitesDone;
  private int ask;
  private int site;
  private int suspect;
  private boolean secondClimb;

  @Override
  public void run() {
    hasWalkedToStart = true;
    while (isQuesting()) {
      int stage = QUEST_STAGE;
      if (stage == 0) {
        CURRENT_QUEST_STEP = "Offering to help the guard";
        leaveUpstairs();
        if (QuestWalker.floor(c.currentY()) != 0) continue;
        walker.walkTo(496, 399, 3, 0);
        runDialogue(GUARD, new String[] {"Sure, I'll help"}, true, 2);
      } else if (stage == 1) {
        investigate();
      } else {
        quit(QuitReason.QUEST_STAGE_NOT_IN_SWITCH);
        return;
      }
    }
  }

  private void investigate() {
    if (carrying(MATCH, 1)) {
      CURRENT_QUEST_STEP = "Naming the evidence to the guard";
      leaveUpstairs();
      if (QuestWalker.floor(c.currentY()) != 0) return;
      walker.walkTo(496, 399, 3, 0);
      runDialogue(GUARD, new String[] {"I know who did it!"}, true, 2);
      return;
    }
    if (!carrying(ItemId.THREAD_RED.getId(), 1)
        && !carrying(ItemId.THREAD_GREEN.getId(), 1)
        && !carrying(ItemId.THREAD_BLUE.getId(), 1)) {
      CURRENT_QUEST_STEP = "Taking the thread from the window";
      leaveUpstairs();
      if (QuestWalker.floor(c.currentY()) != 0) return;
      walker.walkTo(484, 389, 1, 0);
      atWallObject(484, 389);
      c.sleep(1000);
      return;
    }
    if (!askedSalesman) {
      CURRENT_QUEST_STEP = "Asking the poison salesman who he sold to";
      walker.walkTo(490, 452, 2, 0);
      if (runDialogue(
          NpcId.POISON_SALESMAN.getId(),
          new String[] {"Who did you sell Poison to at the house?"},
          true,
          2)) askedSalesman = true;
      return;
    }
    if (!asked) {
      CURRENT_QUEST_STEP = "Asking the family about the poison";
      askFamily();
      return;
    }
    if (!sitesDone) {
      CURRENT_QUEST_STEP = "Searching the poison sites";
      searchSites();
      return;
    }
    CURRENT_QUEST_STEP = "Matching the fingerprints";
    fingerprints();
  }

  private void askFamily() {
    if (ask >= PERSON.length) {
      asked = true;
      return;
    }
    int npc = PERSON[ask];
    int y = npc == NpcId.CAROL_SINCLAIR.getId() || npc == NpcId.ELIZABETH_SINCLAIR.getId() ? 1329 : 390;
    if (y > 944 && QuestWalker.floor(c.currentY()) == 0) {
      goUp();
      return;
    }
    if (y < 944 && QuestWalker.floor(c.currentY()) > 0) {
      leaveUpstairs();
      return;
    }
    int[] spawn = npc == NpcId.ANNA_SINCLAIR.getId()
        ? new int[] {496, 389}
        : npc == NpcId.BOB_SINCLAIR.getId()
            ? new int[] {485, 385}
            : npc == NpcId.FRANK_SINCLAIR.getId()
                ? new int[] {495, 385}
                : npc == NpcId.DAVID_SINCLAIR.getId()
                    ? new int[] {485, 403}
                    : npc == NpcId.CAROL_SINCLAIR.getId()
                        ? new int[] {488, 1329}
                        : new int[] {495, 1329};
    walker.walkTo(spawn[0], spawn[1], 2, 0);
    if (runDialogue(npc, new String[] {"Why did you buy poison the other day?"}, true, 2)) ask++;
  }

  private void searchSites() {
    if (site >= SITES.length) {
      sitesDone = true;
      return;
    }
    int x = SITES[site][0];
    int y = SITES[site][1];
    if (y > 944 && QuestWalker.floor(c.currentY()) == 0) {
      goUp();
      return;
    }
    if (y < 944 && QuestWalker.floor(c.currentY()) > 0) {
      leaveUpstairs();
      return;
    }
    walker.walkTo(x, y, 1, 0);
    c.atObject(x, y);
    c.sleep(800);
    site++;
  }

  private void fingerprints() {
    if (!carrying(UNKNOWN, 1)) {
      printDagger();
      return;
    }
    if (suspect >= SILVER.length) {
      suspect = 0;
      return;
    }
    int silver = SILVER[suspect];
    int dusted = DUSTED[suspect];
    int print = PRINT[suspect];
    if (carrying(print, 1)) {
      useItemsTogether(print, UNKNOWN);
      c.sleep(1200);
      if (!carrying(print, 1) || carrying(MATCH, 1)) suspect++;
      return;
    }
    if (carrying(dusted, 1)) {
      if (!carrying(PAPER, 1)) {
        takePaper();
        return;
      }
      useItemsTogether(PAPER, dusted);
      c.sleep(1000);
      return;
    }
    if (carrying(silver, 1)) {
      if (!carrying(FLOUR, 1)) {
        fillPot();
        return;
      }
      useItemsTogether(FLOUR, silver);
      c.sleep(1000);
      return;
    }
    takeSilver(suspect);
  }

  private void printDagger() {
    if (!carrying(DAGGER, 1) && !carrying(DAGGER_FLOUR, 1)) {
      leaveUpstairs();
      if (QuestWalker.floor(c.currentY()) != 0) return;
      walker.walkTo(485, 389, 1, 0);
      pickupGroundItem(DAGGER, 1);
      return;
    }
    if (carrying(DAGGER, 1)) {
      if (!carrying(FLOUR, 1)) {
        fillPot();
        return;
      }
      useItemsTogether(FLOUR, DAGGER);
      c.sleep(1000);
      return;
    }
    if (!carrying(PAPER, 1)) {
      takePaper();
      return;
    }
    useItemsTogether(PAPER, DAGGER_FLOUR);
    c.sleep(1000);
  }

  private void fillPot() {
    leaveUpstairs();
    if (QuestWalker.floor(c.currentY()) != 0) return;
    if (!carrying(POT, 1)) {
      if (coins() < 30) {
        gatherCoins(30);
        return;
      }
      walker.walkTo(440, 502, 3, 0);
      buyBaseStock(new int[] {ARHEIN}, POT, 2, 1);
      return;
    }
    walker.walkTo(496, 385, 1, 0);
    c.atObject(496, 385);
    c.sleep(1000);
  }

  private void takePaper() {
    leaveUpstairs();
    if (QuestWalker.floor(c.currentY()) != 0) return;
    walker.walkTo(500, 385, 1, 0);
    c.atObject(500, 385);
    c.sleep(600);
    if (!answerMenuIfPresent("Yes, it might be useful")) c.atObject2(500, 385);
    c.sleep(800);
  }

  private void takeSilver(int i) {
    int y = CONTAINER[i][1];
    if (y > 944 && QuestWalker.floor(c.currentY()) == 0) {
      goUp();
      return;
    }
    if (y < 944 && QuestWalker.floor(c.currentY()) > 0) {
      leaveUpstairs();
      return;
    }
    walker.walkTo(CONTAINER[i][0], CONTAINER[i][1], 1, 0);
    c.atObject(CONTAINER[i][0], CONTAINER[i][1]);
    c.sleep(1000);
  }

  private void goUp() {
    walker.walkTo(494, 385, 1, 0);
    climbEither(494, 385);
  }

  private void leaveUpstairs() {
    if (QuestWalker.floor(c.currentY()) == 0) return;
    walker.walkTo(494, 1329, 1, 0);
    climbEither(494, 1329);
  }

  private void climbEither(int x, int y) {
    int before = c.currentY();
    walker.climb(x, y, secondClimb, 2);
    if (Math.abs(c.currentY() - before) < 400) secondClimb = !secondClimb;
    else secondClimb = false;
  }
}
