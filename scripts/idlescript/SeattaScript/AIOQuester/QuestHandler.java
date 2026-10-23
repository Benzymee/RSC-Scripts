package scripting.idlescript.SeattaScript.AIOQuester;

import static scripting.idlescript.SeattaScript.AIOQuester.AIOQuester.TESTING_QUESTS;
import static scripting.idlescript.SeattaScript.AIOQuester.AIOQuester.TESTING_STAGE;

import bot.Main;
import controller.BotController;
import controller.Controller;
import controller.PaintBuilder.PaintBuilder;
import controller.PaintBuilder.RowBuilder;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import models.entities.*;
import orsc.ORSCharacter;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuestDef;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuitReason;

// TODO: Add a method for atObjectUntilItemAmount(int[][] objectCoords, int itemId, int amount)
// TODO: Add a method for atObjectUntilItemAmount(int objectX, int objectY, int itemId, int amount)
// TODO: Add support for eating and handling combat in quest scripts

/* USE THESE FOR HELP WITH QUEST STAGES/DIALOGUES/ETC...
Authentic Quests:
https://gitlab.com/openrsc/openrsc/-/tree/develop/server/plugins/com/openrsc/server/plugins/authentic/quests
Custom Quests:
https://gitlab.com/openrsc/openrsc/-/tree/develop/server/plugins/com/openrsc/server/plugins/custom/quests
*/

/**
 * The superclass that AIOQuester quest scripts extend. Contains useful methods for quest script
 * writing. Quest scripts MUST extend this class, or they will not be found by AIOQuester!
 */
public class QuestHandler {
  protected static final Controller c = Main.getController();
  protected static final BotController bc = new BotController(c);
  /** The one walker every quest walk goes through (see QuestWalker). */
  protected static final QuestWalker walker = new QuestWalker(c);
  public static PaintBuilder paintBuilder;
  public static RowBuilder rowBuilder;

  protected static boolean timeToDrinkAntidote = false;
  protected static boolean hasWalkedToStart = false;
  protected static boolean hasStoppedScript = false;

  public QuestHandler script = null;
  public static Map<Integer, Integer> bankItems = null;
  public static QuestDef QUEST;
  protected static int QUEST_STAGE;
  /** Shield of Arrav gang. "phoenix" (default) or "blackarm". Set from gang=. */
  public static String SHIELD_GANG = "phoenix";
  protected static String CURRENT_QUEST_STEP;
  /** Side-panel toggle: full stage list, with finished stages marked off. */
  public static volatile boolean showQuestSteps = true;
  protected static int[][] STEP_ITEMS;
  protected static Location START_LOCATION;

  // ---- Quest queue support -------------------------------------------------------------------
  /** When true, quit() ends only the current quest (via QuestAbortException) instead of c.stop(). */
  static volatile boolean queueMode = false;
  /** Thread currently running a queued quest; abort exceptions are only thrown on this thread. */
  static volatile Thread questThread = null;
  /** System time (ms) at which the current queued quest times out; 0 = no timeout. */
  static volatile long questDeadline = 0;
  /** A quit() requested from a non-quest thread, consumed by the quest thread. */
  static volatile QuitReason pendingQuit = null;
  /** Optional extra paint line showing queue progress. */
  public static volatile String QUEUE_STATUS = null;
  /** When the current quest started (paint run time). */
  static volatile long questStartedAt = System.currentTimeMillis();
  /** Quest the user asked to skip (Skip quest button / ::skip); consumed by QuestQueue. */
  static volatile QuestDef skipRequested = null;

  /** True while a queued quest script is running on the quest thread. */
  static boolean isQuestRunning() {
    return queueMode && questThread != null && QUEST != null && c.isRunning();
  }

  /** Run time of the current quest, hh:mm:ss. */
  static String questRunTime() {
    long s = Math.max(0, (System.currentTimeMillis() - questStartedAt) / 1000);
    return String.format("%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
  }

  /**
   * Abandons the current quest: the quest thread unwinds at its next running() check (every walk
   * step, wait and dialogue checks it), the queue logs it as skipped and starts the next quest.
   * Safe from any thread (paint button, chat command).
   */
  public static boolean requestSkip(String source) {
    QuestDef q = QUEST;
    if (q == null || !isQuestRunning()) {
      c.log("AIOQuester: Skip quest (" + source + "): no quest is running right now", "yel");
      return false;
    }
    if (skipRequested == q) return true;
    skipRequested = q;
    pendingQuit = QuitReason.SKIPPED_BY_USER;
    String msg =
        String.format(
            "Skip quest (%s): abandoning %s at stage %d, step '%s', at %s",
            source,
            q.getName(),
            QUEST_STAGE,
            CURRENT_QUEST_STEP,
            DraynorManor.describe(c.currentX(), c.currentY()));
    c.log("AIOQuester: " + msg, "yel");
    try {
      bot.probe.Probe.recordEvent("aioquester_skip", msg);
    } catch (Throwable ignored) {
      // older client without the probe
    }
    CURRENT_QUEST_STEP = "Skipping " + q.getName() + "...";
    return true;
  }

  /** Thrown on the quest thread to unwind the current quest script in queue mode. */
  /**
   * Thrown on the quest thread when a step didn't advance the quest stage in time. QuestQueue
   * catches it and runs the quest script again from the current stage (bounded number of times).
   */
  public static final class StageRetryException extends RuntimeException {
    StageRetryException(String message) {
      super(message, null, false, false);
    }
  }

  public static final class QuestAbortException extends RuntimeException {
    private final QuitReason reason;

    QuestAbortException(QuitReason reason) {
      super(reason.getMessage(), null, false, false);
      this.reason = reason;
    }

    public QuitReason getReason() {
      return reason;
    }
  }

  /**
   * Use this instead of c.isRunning() in quest scripts. Returns whether the script is still
   * running. In queue mode, on the quest thread, this also ends the current quest (by throwing
   * QuestAbortException) when it timed out or a quit was requested from another thread.
   *
   * @return boolean
   */
  protected static boolean running() {
    if (!c.isRunning()) return false;
    if (!queueMode) {
      prepareMovement();
      return true;
    }
    boolean timedOut = questDeadline > 0 && System.currentTimeMillis() > questDeadline;
    if (Thread.currentThread() == questThread) {
      QuitReason pending = pendingQuit;
      if (pending != null) {
        // Leave pendingQuit set until QuestQueue catches the abort. Clearing it here lost the
        // quit when a catch(Exception) swallowed QuestAbortException.
        throw new QuestAbortException(pending);
      }
      if (timedOut) {
        questDeadline = 0;
        throw new QuestAbortException(QuitReason.TIMED_OUT);
      }
      prepareMovement();
      return true;
    }
    return pendingQuit == null && !timedOut;
  }

  /** Rethrow abort/retry so catch(Exception) blocks cannot swallow a queued quit. */
  protected static void rethrowAbort(Throwable t) {
    if (t instanceof Error) throw (Error) t;
    if (t instanceof QuestAbortException) throw (QuestAbortException) t;
    if (t instanceof StageRetryException) throw (StageRetryException) t;
  }

  // ============================================================== quest items: keep / recover

  /**
   * Quest items that can't be bought back. Banking helpers (QuestQueue's pre-quest banking,
   * depositAllExceptQuestItems) never deposit these: a Witch's Potion depositAll used to bank a
   * half-done Rune Mysteries' research package/talisman, and the quest then looped forever.
   */
  private static final java.util.Set<Integer> KEEP_QUEST_ITEMS =
      new java.util.HashSet<>(
          Arrays.asList(
              ItemId.AIR_TALISMAN.getId(), // Rune Mysteries
              ItemId.RESEARCH_PACKAGE.getId(),
              ItemId.RESEARCH_NOTES.getId(),
              ItemId.PRESSURE_GAUGE.getId(), // Ernest the Chicken
              ItemId.RUBBER_TUBE.getId(),
              ItemId.OIL_CAN.getId(),
              ItemId.CLOSET_KEY.getId(),
              ItemId.QUEST_SKULL.getId(), // The Restless Ghost
              ItemId.MESSAGE.getId(), // Romeo and Juliet
              ItemId.BRONZE_KEY.getId(), // Prince Ali Rescue
              ItemId.KEYPRINT.getId(),
              ItemId.SOFT_CLAY.getId(),
              ItemId.PASTE.getId(),
              ItemId.BLONDE_WIG.getId(),
              ItemId.WOOL_WIG.getId(),
              ItemId.PINK_SKIRT.getId(),
              ItemId.CABBAGE.getId(),
              ItemId.GOBLIN_ARMOUR.getId(),
              ItemId.ORANGE_GOBLIN_ARMOUR.getId(),
              ItemId.BLUE_GOBLIN_ARMOUR.getId(),
              ItemId.REDDYE.getId(),
              ItemId.YELLOWDYE.getId(),
              ItemId.BLUEDYE.getId(),
              ItemId.ORANGEDYE.getId(),
              ItemId.SILVERLIGHT.getId(),
              ItemId.SILVERLIGHT_KEY_1.getId(),
              ItemId.SILVERLIGHT_KEY_2.getId(),
              ItemId.SILVERLIGHT_KEY_3.getId(),
              ItemId.MAP_PIECE_1.getId(),
              ItemId.MAP_PIECE_2.getId(),
              ItemId.MAP_PIECE_3.getId(),
              ItemId.MAP.getId(),
              ItemId.RED_KEY.getId(),
              ItemId.ORANGE_KEY.getId(),
              ItemId.YELLOW_KEY.getId(),
              ItemId.BLUE_KEY.getId(),
              ItemId.MAGENTA_KEY.getId(),
              ItemId.BLACK_KEY.getId(),
              ItemId.MAZE_KEY.getId(),
              ItemId.PORTRAIT.getId(),
              ItemId.BLURITE_ORE.getId(),
              ItemId.FALADIAN_KNIGHTS_SWORD.getId(),
              ItemId.KARAMJA_RUM.getId(),
              ItemId.CHEST_KEY.getId(),
              ItemId.BOOK.getId(),
              ItemId.SCROLL.getId(),
              ItemId.BROKEN_SHIELD_ARRAV_1.getId(),
              ItemId.BROKEN_SHIELD_ARRAV_2.getId(),
              ItemId.PHOENIX_GANG_KEY.getId(),
              ItemId.PHOENIX_GANG_WEAPON_KEY.getId(),
              ItemId.PHOENIX_CROSSBOW.getId(),
              ItemId.CERTIFICATE.getId(),
              ItemId.ANTI_DRAGON_BREATH_SHIELD.getId(),
              ItemId.PLANK.getId(),
              ItemId.NAILS.getId(),
              ItemId.DOOGLE_LEAVES.getId(),
              ItemId.SEASONED_SARDINE.getId(),
              ItemId.KITTENS.getId(),
              ItemId.DWELLBERRIES.getId(),
              ItemId.GASMASK.getId(),
              ItemId.PICTURE.getId(),
              ItemId.PLAGUE_CITY_BOOK.getId(),
              ItemId.SCRUFFY_NOTE.getId(),
              ItemId.HANGOVER_CURE.getId(),
              ItemId.CHOCOLATY_MILK.getId(),
              ItemId.CHOCOLATE_DUST.getId(),
              ItemId.WARRANT.getId(),
              ItemId.LITTLE_KEY.getId(),
              ItemId.RAW_CHICKEN.getId(),
              ItemId.RAW_BEEF.getId(),
              ItemId.RAW_RAT_MEAT.getId(),
              ItemId.RAW_BEAR_MEAT.getId(),
              ItemId.ENCHANTED_CHICKEN_MEAT.getId(),
              ItemId.ENCHANTED_BEEF.getId(),
              ItemId.ENCHANTED_RAT_MEAT.getId(),
              ItemId.ENCHANTED_BEAR_MEAT.getId(),
              ItemId.LARGE_COG_BLUE.getId(),
              ItemId.LARGE_COG_BLACK.getId(),
              ItemId.LARGE_COG_RED.getId(),
              ItemId.LARGE_COG_PURPLE.getId(),
              ItemId.RAT_POISON.getId(),
              ItemId.CARNILLEAN_ARMOUR.getId(),
              ItemId.SWAMP_TAR.getId(),
              ItemId.UNCOOKED_SWAMP_PASTE.getId(),
              ItemId.SWAMP_PASTE.getId(),
              ItemId.DAMP_STICKS.getId(),
              ItemId.DRY_STICKS.getId(),
              ItemId.BROKEN_GLASS.getId(),
              ItemId.UNLIT_TORCH.getId(),
              ItemId.LIT_TORCH.getId(),
              ItemId.POT_OF_FLOUR.getId(),
              ItemId.GRAIN.getId(),
              ItemId.UNIDENTIFIED_SNAKE_WEED.getId(),
              ItemId.SNAKE_WEED.getId(),
              ItemId.UNIDENTIFIED_ARDRIGAL.getId(),
              ItemId.ARDRIGAL.getId(),
              ItemId.UNIDENTIFIED_SITO_FOIL.getId(),
              ItemId.SITO_FOIL.getId(),
              ItemId.UNIDENTIFIED_VOLENCIA_MOSS.getId(),
              ItemId.VOLENCIA_MOSS.getId(),
              ItemId.UNIDENTIFIED_ROGUES_PURSE.getId(),
              ItemId.ROGUES_PURSE.getId(),
              ItemId.THREAD_RED.getId(),
              ItemId.THREAD_GREEN.getId(),
              ItemId.THREAD_BLUE.getId(),
              ItemId.FLYPAPER.getId(),
              ItemId.A_SILVER_DAGGER.getId(),
              ItemId.A_SILVER_DAGGER_FLOUR.getId(),
              ItemId.UNIDENTIFIED_FINGERPRINT.getId(),
              ItemId.MURDERERS_FINGERPRINT.getId(),
              ItemId.ANNAS_SILVER_NECKLACE.getId(),
              ItemId.BOBS_SILVER_TEACUP.getId(),
              ItemId.CAROLS_SILVER_BOTTLE.getId(),
              ItemId.DAVIDS_SILVER_BOOK.getId(),
              ItemId.ELIZABETHS_SILVER_NEEDLE.getId(),
              ItemId.FRANKS_SILVER_POT.getId(),
              ItemId.ANNAS_SILVER_NECKLACE_FLOUR.getId(),
              ItemId.BOBS_SILVER_TEACUP_FLOUR.getId(),
              ItemId.CAROLS_SILVER_BOTTLE_FLOUR.getId(),
              ItemId.DAVIDS_SILVER_BOOK_FLOUR.getId(),
              ItemId.ELIZABETHS_SILVER_NEEDLE_FLOUR.getId(),
              ItemId.FRANKS_SILVER_POT_FLOUR.getId(),
              ItemId.ANNAS_FINGERPRINT.getId(),
              ItemId.BOBS_FINGERPRINT.getId(),
              ItemId.CAROLS_FINGERPRINT.getId(),
              ItemId.DAVIDS_FINGERPRINT.getId(),
              ItemId.ELIZABETHS_FINGERPRINT.getId(),
              ItemId.FRANKS_FINGERPRINT.getId(),
              ItemId.POT.getId(),
              ItemId.BUCKET.getId(),
              ItemId.BUCKET_OF_WATER.getId()));

  /**
   * Extra items a calling script wants kept through AIOQuester's banking (e.g. Thief2Ardougne
   * keeps its cabbages). Add before running a queue, remove afterwards.
   */
  public static final java.util.Set<Integer> EXTRA_KEEP_ITEMS =
      java.util.Collections.synchronizedSet(new java.util.HashSet<>());

  /**
   * Never deposited by AIOQuester's generic banking: unfinished quest items, coins (no quest needs
   * them banked; quests that need an exact amount withdraw it on top, and boat fares etc. need
   * them carried) and anything in EXTRA_KEEP_ITEMS.
   */
  public static boolean isKeptQuestItem(int id) {
    return KEEP_QUEST_ITEMS.contains(id) || id == ItemId.COINS.getId() || EXTRA_KEEP_ITEMS.contains(id);
  }

  /** Opens the nearest ground-floor bank and withdraws coins up to {@code needed} carried. */
  protected static void withdrawCoins(int needed) {
    QuestSupplies.withdrawCoins(needed);
  }

  /** Opens the nearest ground-floor bank and fills the inventory with the best healing food. */
  protected static void withdrawBestFood(int keepId) {
    QuestSupplies.withdrawBestFood(keepId);
  }

  /** Bank must be open. Deposits everything except the quest items above. */
  protected static void depositAllExceptQuestItems() {
    int[] ids = c.getInventoryItemIds();
    if (ids == null) return;
    java.util.Set<Integer> seen = new java.util.LinkedHashSet<>();
    for (int id : ids) if (id >= 0) seen.add(id);
    for (int id : seen) {
      if (isKeptQuestItem(id)) continue;
      int n = c.getInventoryItemCount(id);
      if (n <= 0) continue;
      c.depositItem(id, n);
      long until = System.currentTimeMillis() + 3000;
      while (c.getInventoryItemCount(id) > 0 && System.currentTimeMillis() < until && c.isRunning())
        c.sleep(100);
    }
  }

  private static final Map<Integer, Long> bankCheckedAt = new java.util.HashMap<>();

  /**
   * A quest item we should be carrying is missing: pick it up if it's on the ground nearby, else
   * withdraw it if it's in the bank (checked at most every 10 minutes per item; also frees a slot
   * there when the inventory is full). Returns whether we now carry it. The caller re-obtains it
   * from the npc when this returns false.
   */
  protected boolean recoverQuestItem(int id, String name) {
    if (c.getInventoryItemCount(id) > 0) return true;
    int[] ground = c.getNearestItemById(id);
    if (ground != null && c.distance(c.currentX(), c.currentY(), ground[0], ground[1]) <= 12) {
      c.log("AIOQuester: " + name + " is on the ground at " + ground[0] + "," + ground[1] + ", picking it up", "yel");
      c.pickupItem(ground[0], ground[1], id, true, true);
      long until = System.currentTimeMillis() + 6000;
      while (running() && c.getInventoryItemCount(id) == 0 && System.currentTimeMillis() < until) c.sleep(200);
      if (c.getInventoryItemCount(id) > 0) return true;
    }
    Long last = bankCheckedAt.get(id);
    // (the cached bankItems can be stale: a quest's own depositAll doesn't refresh it)
    boolean recentlyChecked = last != null && System.currentTimeMillis() - last < 10 * 60_000L;
    if (recentlyChecked) return false;
    c.log("AIOQuester: missing the " + name + "; checking the bank first", "yel");
    bankCheckedAt.put(id, System.currentTimeMillis());
    if (!openNearestBank()) return false;
    updateBankItems();
    if (c.getBankItemCount(id) > 0) {
      if (c.getInventoryItemCount() >= 30) freeOneSlotInBank();
      c.withdrawItem(id, 1);
      long until = System.currentTimeMillis() + 5000;
      while (running() && c.getInventoryItemCount(id) == 0 && System.currentTimeMillis() < until) c.sleep(200);
      c.log("AIOQuester: " + (c.getInventoryItemCount(id) > 0 ? "withdrew the " : "couldn't withdraw the ") + name, "yel");
    } else {
      c.log("AIOQuester: the " + name + " isn't in the bank either", "yel");
      if (c.getInventoryItemCount() >= 30) freeOneSlotInBank(); // npcs won't re-issue into a full pack
    }
    updateBankItems();
    c.closeBank();
    c.sleep(640);
    return c.getInventoryItemCount(id) > 0;
  }

  /** Bank open: deposits one stack that no quest step needs. */
  private static void freeOneSlotInBank() {
    int[] ids = c.getInventoryItemIds();
    if (ids == null) return;
    for (int i = ids.length - 1; i >= 0; i--) {
      int id = ids[i];
      if (id < 0 || isKeptQuestItem(id) || questNeedsItem(id)) continue;
      c.depositItem(id, c.getInventoryItemCount(id));
      c.sleep(1280);
      return;
    }
  }

  protected boolean openNearestBank() {
    return QuestSupplies.openNearestBank();
  }

  // This server refuses eating in melee. Eat to 70% out of combat before walking or attacking.
  // In combat below 50% HP, retreat after round 3 (~2.5s), then eat, then re-engage.
  private static final int AUTO_EAT_PERCENT = 70;
  private static final int RETREAT_PERCENT = 50;
  private static final long COMBAT_RETREAT_MS = 2500;
  private static long lastAutoEat = 0;
  private static boolean inAutoEat = false;
  private static long combatSince = 0;
  private static volatile long lastEatRefusedAt = 0;
  private static boolean wasInCombat = false;
  private static int fightStartHp = 0;
  private static int lastFightNpcId = -1;
  private static final java.util.Set<Integer> tooDangerousNpcs = new java.util.HashSet<>();

  /** Eat, and flee combat if HP is low. Called from running() and from walkTo. */
  protected static void prepareMovement() {
    noteCombat();
    int max = c.getBaseStat(SkillId.HITS.getId()), hp = c.getCurrentStat(SkillId.HITS.getId());
    if (max <= 0) return;
    if (c.isInCombat()) {
      if (hp * 100 < RETREAT_PERCENT * max) retreatThenEat();
      return;
    }
    eatToPercent(AUTO_EAT_PERCENT);
  }

  protected static boolean readyToFight() {
    noteCombat();
    if (lastFightNpcId >= 0 && tooDangerousNpcs.contains(lastFightNpcId)) {
      c.log(
          "AIOQuester: last fight vs npc "
              + lastFightNpcId
              + " cost over 60% Hitpoints; not re-engaging",
          "red");
      pendingQuit = QuitReason.TOO_WEAK;
      if (queueMode && Thread.currentThread() == questThread) {
        throw new QuestAbortException(QuitReason.TOO_WEAK);
      }
      return false;
    }
    if (c.isInCombat()) return true;
    eatToPercent(AUTO_EAT_PERCENT);
    int max = c.getBaseStat(SkillId.HITS.getId()), hp = c.getCurrentStat(SkillId.HITS.getId());
    return max <= 0 || hp * 100 >= AUTO_EAT_PERCENT * max;
  }

  /** RSC melee combat: (attack + strength + defense + hits) / 4, or the client's player.level. */
  protected static int combatLevel() {
    try {
      ORSCharacter p = c.getPlayer();
      if (p != null && p.level > 0) return p.level;
    } catch (RuntimeException ignored) {
    }
    int att = c.getBaseStat(SkillId.ATTACK.getId());
    int str = c.getBaseStat(SkillId.STRENGTH.getId());
    int def = c.getBaseStat(SkillId.DEFENSE.getId());
    int hits = c.getBaseStat(SkillId.HITS.getId());
    return (att + str + def + hits) / 4;
  }

  protected static int foodCount() {
    int[] foods = c.getFoodIds();
    if (foods == null) return 0;
    int n = 0;
    for (int id : foods) n += c.getInventoryItemCount(id);
    return n;
  }

  private static void noteCombat() {
    boolean now = c.isInCombat();
    if (now && !wasInCombat) {
      combatSince = System.currentTimeMillis();
      fightStartHp = c.getCurrentStat(SkillId.HITS.getId());
      ORSCharacter npc = null;
      try {
        npc = c.getTargetedNpc();
        if (npc == null) npc = c.getNpcTargetingLocalPlayer();
      } catch (RuntimeException ignored) {
      }
      lastFightNpcId = npc != null ? npc.npcId : -1;
    } else if (!now && wasInCombat) {
      int max = c.getBaseStat(SkillId.HITS.getId());
      int hp = c.getCurrentStat(SkillId.HITS.getId());
      int lost = Math.max(0, fightStartHp - hp);
      if (lastFightNpcId >= 0 && max > 0 && lost * 100 > 60 * max) {
        tooDangerousNpcs.add(lastFightNpcId);
        c.log(
            "AIOQuester: fight vs npc "
                + lastFightNpcId
                + " lost "
                + lost
                + "/"
                + max
                + " HP; will not re-engage",
            "yel");
      }
      combatSince = 0;
    }
    wasInCombat = now;
  }

  private static boolean canRetreat() {
    return combatSince > 0 && System.currentTimeMillis() - combatSince >= COMBAT_RETREAT_MS;
  }

  private static void retreatThenEat() {
    if (!c.isInCombat()) {
      eatToPercent(AUTO_EAT_PERCENT);
      return;
    }
    if (!canRetreat()) return;
    int x = c.currentX(), y = c.currentY();
    c.log("AIOQuester: retreating to eat (HP low in combat)", "yel");
    c.walkToAsync(x + 3, y, 0);
    c.sleepUntil(() -> !c.isInCombat(), 4000);
    if (c.isInCombat()) {
      c.walkToAsync(x, y + 3, 0);
      c.sleepUntil(() -> !c.isInCombat(), 3000);
    }
    if (!c.isInCombat()) eatToPercent(AUTO_EAT_PERCENT);
  }

  /** Eat only while out of combat. Never spams refused in-melee eats. */
  protected static void eatToPercent(int percent) {
    if (inAutoEat || c.isInCombat()) return;
    long now = System.currentTimeMillis();
    if (now - lastEatRefusedAt < 1500) return;
    if (now - lastAutoEat < 1200) return;
    try {
      int max = c.getBaseStat(SkillId.HITS.getId()), hp = c.getCurrentStat(SkillId.HITS.getId());
      if (max <= 0 || hp * 100 >= percent * max) return;
      int[] foods = c.getFoodIds();
      if (foods == null) return;
      for (int id : foods) {
        if (c.getInventoryItemCount(id) <= 0 || questNeedsItem(id)) continue;
        inAutoEat = true;
        lastAutoEat = now;
        int before = c.getInventoryItemCount(id);
        c.log(String.format("Low HP (%d/%d): eating item %d", hp, max, id), "yel");
        try {
          bot.probe.Probe.recordEvent("eat", "hp " + hp + "/" + max + " item " + id);
        } catch (Throwable t) {
          rethrowAbort(t);
        }
        c.itemCommand(id);
        c.sleepUntil(() -> c.getInventoryItemCount(id) < before, 2000);
        return;
      }
    } catch (Exception e) {
      rethrowAbort(e);
    } finally {
      inAutoEat = false;
    }
  }

  private static boolean questNeedsItem(int id) {
    int[][][] lists = {STEP_ITEMS, QUEST == null ? null : QUEST.getRequiredInventoryItems()};
    for (int[][] list : lists) {
      if (list == null) continue;
      for (int[] item : list) if (item != null && item.length > 0 && item[0] == id) return true;
    }
    return false;
  }

  /**
   * Drops leftover fishing junk and extra food so the next quest has enough empty slots. Used even
   * with nobank. Never drops items the current quest still needs.
   */
  public static void freeInventorySlots(int need) {
    if (need <= 0 || !c.isRunning()) return;
    if (c.isInCombat()) {
      c.log("AIOQuester: leaving combat before dropping for inventory space", "yel");
      long start = System.currentTimeMillis();
      while (c.isInCombat() && c.isRunning() && System.currentTimeMillis() - start < 8000) {
        if (combatSince == 0) combatSince = start;
        if (System.currentTimeMillis() - combatSince >= COMBAT_RETREAT_MS) {
          c.walkToAsync(c.currentX() + 3, c.currentY(), 0);
        }
        c.sleep(400);
      }
      if (c.isInCombat()) return;
    }
    int free = 30 - c.getInventoryItemCount();
    if (free >= need) return;
    int[] junk = {
      ItemId.RAW_SHRIMP.getId(),
      ItemId.RAW_ANCHOVIES.getId(),
      ItemId.NET.getId(),
      ItemId.BONES.getId(),
      ItemId.FEATHER.getId()
    };
    for (int id : junk) {
      while (c.isRunning() && 30 - c.getInventoryItemCount() < need && c.getInventoryItemCount(id) > 0) {
        if (c.isInCombat() || questNeedsItem(id)) break;
        int slot = c.getInventoryItemSlotIndex(id);
        if (slot < 0) break;
        int before = c.getInventoryItemCount();
        c.log("AIOQuester: dropping " + c.getItemName(id) + " for inventory space", "yel");
        c.dropItem(slot, 1);
        c.sleep(400);
        if (c.getInventoryItemCount() >= before) break;
      }
    }
    int[] foods = c.getFoodIds();
    if (foods == null || 30 - c.getInventoryItemCount() >= need) return;
    int foodLeft = 0;
    for (int id : foods) foodLeft += c.getInventoryItemCount(id);
    for (int id : foods) {
      while (c.isRunning()
          && 30 - c.getInventoryItemCount() < need
          && foodLeft > 4
          && c.getInventoryItemCount(id) > 0) {
        if (c.isInCombat() || questNeedsItem(id)) break;
        int slot = c.getInventoryItemSlotIndex(id);
        if (slot < 0) break;
        int before = c.getInventoryItemCount();
        c.log("AIOQuester: dropping extra food for inventory space", "yel");
        c.dropItem(slot, 1);
        c.sleep(400);
        if (c.getInventoryItemCount() >= before) break;
        foodLeft--;
      }
    }
  }

  /** Inside the fenced Lumbridge cow field (not the road along 105,619). */
  protected static boolean inLumbridgeCowPen() {
    int x = c.currentX();
    int y = c.currentY();
    return x >= 96 && x <= 110 && y >= 606 && y <= 616;
  }

  /** Opens gate 60 at 105,619 if it is closed, then walks to 103,612 inside the pen. */
  protected static void enterLumbridgeCowPen() {
    if (inLumbridgeCowPen()) return;
    int[][] gates = {{105, 619}, {112, 616}, {114, 608}};
    walker.walkTo(105, 618, 0, 0);
    for (int[] g : gates) {
      if (Math.max(Math.abs(c.currentX() - g[0]), Math.abs(c.currentY() - g[1])) > 3) continue;
      if (c.getObjectAtCoord(g[0], g[1]) != 60) continue;
      c.log("AIOQuester: opening the Lumbridge cow gate at " + g[0] + "," + g[1], "yel");
      c.atObject(g[0], g[1]);
      c.sleep(1200);
    }
    if (!inLumbridgeCowPen() && c.getObjectAtCoord(105, 619) == 60) {
      c.log("AIOQuester: opening the Lumbridge cow gate at 105,619", "yel");
      c.atObject(105, 619);
      c.sleep(1200);
    }
    walker.walkTo(103, 612, 1, 0);
  }

  /** Resets the shared per-quest state before a queued quest starts. */
  static void resetQuestState(QuestDef quest) {
    QUEST = quest;
    hasWalkedToStart = false;
    timeToDrinkAntidote = false;
    hasStoppedScript = false;
    STEP_ITEMS = null;
    START_LOCATION = quest == null ? null : quest.getStartLocation();
    CURRENT_QUEST_STEP = quest == null ? null : "Starting " + quest.getName();
    QUEST_STAGE = quest != null && !quest.isMiniquest() ? c.getQuestStage(quest.getId()) : 0;
    pendingQuit = null;
    questStartedAt = System.currentTimeMillis();
    lastServerText = "";
    lastQuestText = "";
    lastServerAt = 0;
    lastQuestAt = 0;
    repeatText = "";
    repeatCount = 0;
    repeatAction = "";
    repeatActionCount = 0;
    combatSince = 0;
    lastEatRefusedAt = 0;
    wasInCombat = false;
    fightStartHp = 0;
    lastFightNpcId = -1;
    tooDangerousNpcs.clear();
    floorHopCount = 0;
    floorHopFrom = -1;
    floorHopWindow = 0;
  }

  /**
   * Checks for and runs the given class' run method.
   *
   * @param cl Class - Class of the quest script that will be run.
   */
  void start(Class<? extends QuestHandler> cl) {
    paintBuilder.start(4, 18, 182);
    CURRENT_QUEST_STEP = "Starting " + QUEST.getName();
    START_LOCATION = QUEST.getStartLocation();
    hasWalkedToStart = false;
    if (!QUEST.isMiniquest()) QUEST_STAGE = c.getQuestStage(QUEST.getId());

    if (running()) {
      c.setBatchBars(true);
      try {
        script = cl.getDeclaredConstructor().newInstance();
        script.run();
        script.cleanupScript();
        quit(QuitReason.SCRIPT_STOPPED);
      } catch (ReflectiveOperationException e) {
        Main.logError("AIOQuester: Failed to instantiate the quest class: " + cl.getName(), e);
      }
    }
  }

  /** Cleanup method ran by IdleScript to reset QuestHandler's values */
  public void cleanup() {
    paintBuilder = null;
    rowBuilder = null;

    hasWalkedToStart = false;
    timeToDrinkAntidote = false;
    hasStoppedScript = false;
    queueMode = false;
    questThread = null;
    questDeadline = 0;
    pendingQuit = null;
    QUEUE_STATUS = null;
    skipRequested = null;
    QuestDock.uninstall();

    QUEST = null;
    QUEST_STAGE = 0;
    CURRENT_QUEST_STEP = null;
    STEP_ITEMS = null;
    START_LOCATION = null;
  }

  /** Cleanup method overridden and ran by quest scripts to reset values */
  public void cleanupScript() {
    Class<?> script = this.getClass();

    boolean hasFields =
        Arrays.stream(script.getDeclaredFields())
            .anyMatch(f -> Modifier.isStatic(f.getModifiers()));
    if (hasFields)
      System.out.printf(
          "AIOQuester: Script '%s' has  fields but no cleanupScript override.  field states will persist across instances.%n",
          script.getSimpleName());
  }

  /** This method is overridden by Quest scripts to provide quest logic */
  public void run() {
    System.out.println("Override the 'run()' method class in quest scripts");
    c.sleep(1280);
  }

  /** Used for testing only */
  private void doTestLoop() {
    // * TRY NOT TO COMMIT TESTING CODE
    // * DO TEST STUFF HERE!!
  }

  /** Used for testing only */
  public void startTestingLoop() {
    CURRENT_QUEST_STEP = "This is only ran for testing";
    paintBuilder.start(4, 18, 182);
    int loops = 0;

    while (running()) {
      loops++;
      doTestLoop();
      c.sleep(640);
    }
  }

  /** Uses WebWalker to walk to the start location. */
  public void walkToStartLocation() {
    if (!isAtLocation(START_LOCATION)) {
      CURRENT_QUEST_STEP = "Walking to start location";
      walkTowards(START_LOCATION);
    }
    hasWalkedToStart = true;
  }

  /**
   * Uses WebWalker to walk to a location's standable tile or if null, to the first corner in its
   * bounds.
   *
   * @param location - Location to walk to
   */
  public void walkTowards(Location location) {
    if (!running() || location.isAtLocation()) return;
    CURRENT_QUEST_STEP_WALK = location.getDescription();
    if (!walker.walkTo(location.getX(), location.getY(), 1, 0, location::isAtLocation))
      walkFailed(location.getX(), location.getY());
  }

  /** Last walk destination, for logs. */
  protected static String CURRENT_QUEST_STEP_WALK = "";

  /**
   * A walk failed after QuestWalker's bounded retries. In the queue this re-runs the quest from
   * its current stage (QuestQueue retries a few times, then fails the quest); otherwise quits.
   */
  protected void walkFailed(int x, int y) {
    String msg = String.format("Couldn't walk to %d,%d from %d,%d", x, y, c.currentX(), c.currentY());
    c.log(msg, "red");
    if (Thread.currentThread() == questThread) throw new StageRetryException(msg);
    quit(QuitReason.UNABLE_TO_WALK_TO_LOCATION);
  }

  /**
   * Walks to an exact tile through QuestWalker (short or long distance). Replaces c.walkTo in the
   * quest scripts. Soft: logs and returns false on failure, the script's own checks follow.
   */
  public boolean walkTo(int x, int y) {
    if (!running()) return false;
    return walker.walkTo(x, y, 0, 0);
  }

  /** Walks to within tolerance tiles of (x,y); soft like walkTo(x,y). */
  public boolean walkTo(int x, int y, int tolerance) {
    if (!running()) return false;
    return walker.walkTo(x, y, tolerance, 0);
  }

  /**
   * Uses WebWalker to walk to a set of coordinates.
   *
   * @param x - X coordinate to walk to
   * @param y - y coordinate to walk to
   */
  public void walkTowards(int x, int y) {
    if (running()) {
      if (!isAtCoords(x, y)) {
        c.displayMessage(
            "@yel@Attempting to walk to: @cya@" + Location.getDescriptionFromStandableTile(x, y));
        System.out.println(
            "\nAttempting to walk to: " + Location.getDescriptionFromStandableTile(x, y));
        System.out.println(
            "If this fails, WebWalker might need to be updated to include the area.");
        if (!walker.walkTo(x, y, 1, 0)) walkFailed(x, y);
      }
    }
  }

  /**
   * Checks if the player has at least a certain amount of an item id.
   *
   * @param id int -- The item id to check
   * @param amount int -- The amount to check for
   * @return boolean
   */
  public boolean hasAtLeastItemAmount(int id, int amount) {
    return (c.getInventoryItemCount(id) >= amount);
  }

  /**
   * Checks if the player has at least a certain amount of an ItemId.
   *
   * @param item ItemId -- The ItemId to check
   * @param amount int -- The amount to check for
   * @return boolean
   */
  public boolean hasAtLeastItemAmount(ItemId item, int amount) {
    return (c.getInventoryItemCount(item.getId()) >= amount);
  }

  /**
   * Checks if the player has the required level of a specified skill.
   *
   * @param skill SkillId -- SkillId enum value
   * @param requiredLevel int -- Level required for the skill
   * @return boolean -- Does the player have the specified skill level
   */
  public boolean hasSkillLevel(SkillId skill, int requiredLevel) {
    return c.getBaseStat(skill.getId()) >= requiredLevel;
  }

  /**
   * Checks if the player is within the boundary of a Location
   *
   * @param location Location to check
   * @return boolean
   */
  public boolean isAtLocation(Location location) {
    return Location.isAtLocation(location);
  }

  /**
   * Returns whether the player is at a specific coordinate.
   *
   * @param x int - X coordinate to check
   * @param y int - Y coordinate to check
   * @return boolean
   */
  public boolean isAtCoords(int x, int y) {
    return c.currentX() == x && c.currentY() == y;
  }

  /**
   * THIS IS THE MAIN QUEST LOOP -- Returns whether the controller is still running; while handling
   * sleep, afk anti-logout movement, setting QUEST_STAGE, and doing the initial walk to
   * START_LOCATION. Use this for looping the stage switch in scripts.
   *
   * @return boolean
   */
  public boolean isQuesting() {
    if (!running()) return false;
    if (c.getNeedToMove()) c.moveCharacter();
    if (c.getShouldSleep()) c.sleepHandler(true);
    if (!hasWalkedToStart) walkToStartLocation();
    if (!QUEST.isMiniquest()) {
      QUEST_STAGE = getQuestStage();
      // -1 is the usual complete stage. Shield of Arrav's Black Arm reward writes -2 after that.
      if (QUEST_STAGE < 0) {
        quit(QuitReason.QUEST_COMPLETED);
        return false;
      }
    }
    logDetected();
    return true;
  }

  /** Set by quests that log their own "Detected:" line (reset for every quest). */
  protected static boolean ownDetectionLog = false;

  private static int detectedStage = Integer.MIN_VALUE;
  private static String detectedStep = null;
  private static QuestDef detectedQuest = null;

  /**
   * "Detected: &lt;quest&gt; stage N -> step &lt;name&gt;" whenever the stage or the step changes,
   * with where we are and what we carry, so a resumed run shows what it decided. The step is the
   * one the quest script is on (it sets CURRENT_QUEST_STEP), logged as soon as it changes.
   */
  private static void logDetected() {
    if (QUEST == null) return;
    if (detectedQuest != QUEST) {
      detectedQuest = QUEST;
      ownDetectionLog = false;
      detectedStage = Integer.MIN_VALUE;
      detectedStep = null;
    }
    String step = CURRENT_QUEST_STEP == null ? "(deciding)" : CURRENT_QUEST_STEP;
    if (QUEST_STAGE != detectedStage) {
      detectedStage = QUEST_STAGE;
      detectedStep = null;
      return;
    }
    boolean firstForStage = detectedStep == null;
    if (step.equals(detectedStep)) return;
    detectedStep = step;
    if (ownDetectionLog) return; // the quest logs its own, more detailed detection
    StringBuilder inv = new StringBuilder();
    if (firstForStage) {
      try {
        int n = 0;
        for (int id : c.getInventoryItemIds()) {
          if (id < 0) continue;
          if (n++ > 0) inv.append(", ");
          if (n > 12) {
            inv.append("...");
            break;
          }
          inv.append(c.getItemName(id));
        }
      } catch (RuntimeException e) {
        rethrowAbort(e);
      }
    }
    c.log(
        String.format(
            "AIOQuester: Detected: %s stage %d -> step %s (at %d,%d floor %d%s)",
            QUEST.getName(),
            QUEST_STAGE,
            step,
            c.currentX(),
            c.currentY(),
            QuestWalker.floor(c.currentY()),
            firstForStage ? "; carrying: " + (inv.length() == 0 ? "nothing" : inv) : ""),
        "cya");
  }

  /**
   * Drops all of a given item id except for a given amount.
   *
   * @param itemId int -- Id of the item to drop
   * @param amountToKeep int -- Amount of item to keep
   */
  public void dropAllButAmount(int itemId, int amountToKeep) {
    while (c.isCurrentlyWalking() && running()) c.sleep(640);
    c.sleep(1280);
    if (c.getInventoryItemCount(itemId) > amountToKeep && running()) {
      c.dropItem(
          c.getInventoryItemSlotIndex(itemId), c.getInventoryItemCount(itemId) - amountToKeep);
      c.sleep(1280);
      while (c.getInventoryItemCount(itemId) > amountToKeep && running()) {
        c.sleep(640);
      }
    }
  }

  /**
   * Opens the shop for the closest NPC with a given id and sells items to them.
   *
   * @param npcIds int[] -- Ids of npcs to look for
   * @param sellItems int[][] -- ItemId/amount pairs. Example: {{SILK_ID, 1}, {EGG_ID, 12}}
   */
  public void openShopThenSell(int[] npcIds, int[][] sellItems) {
    if (running()) {
      if (!openNpcShop(npcIds)) return;

      for (int[] item : sellItems) {
        int itemId = item[0];
        int sellAmount = item[1];
        int startAmount = c.getInventoryItemCount(itemId);

        String itemName = String.valueOf(ItemId.getById(itemId)).replaceAll("_", " ").toLowerCase();
        itemName = itemName.substring(0, 1).toUpperCase() + itemName.substring(1);

        if (startAmount < sellAmount) {
          c.log(String.format("Only had %sx %s to sell", startAmount, itemName));
        }
        c.shopSell(itemId, sellAmount);
        c.sleep(1280);
      }
      c.closeShop();
      while (c.isInShop() && running()) c.sleep(640);
    }
  }

  /**
   * Opens the shop for the closest NPC with a given id and buys items from them.
   *
   * @param npcIds int[] -- Ids of npcs to look for
   * @param buyItems int[][] -- Item id, amount, and the NPC's own base stock. Example: {{SHEARS, 1,
   *     2}}
   */
  public void openShopThenBuy(int[] npcIds, int[][] buyItems) {
    if (running()) {
      if (!openNpcShop(npcIds)) return;

      for (int[] item : buyItems) {
        if (item.length < 3) {
          c.log("AIOQuester: refusing a shop buy with no NPC base stock", "yel");
          continue;
        }
        if (item[1] > 0) buyFromOpenShop(item[0], item[1], item[2]);
      }
      c.closeShop();
      while (c.isInShop() && running()) c.sleep(640);
    }
  }

  /**
   * Opens the shop for the closest NPC with a given id and sells then buys items.
   *
   * @param npcIds int[] -- Ids of npcs to look for
   * @param sellItems int[][] -- ItemId/amount pairs. Example: {{SILK_ID, 1}, {EGG_ID, 12}}
   * @param buyItems int[][] -- Item id, amount, and the NPC's own base stock. Example: {{SHEARS, 1,
   *     2}}
   */
  public void openShopThenSellAndBuy(int[] npcIds, int[][] sellItems, int[][] buyItems) {
    if (running()) {
      if (!openNpcShop(npcIds)) return;

      // Sell Items
      for (int[] item : sellItems) {
        int itemId = item[0];
        int sellAmount = item[1];
        int startAmount = c.getInventoryItemCount(itemId);

        String itemName = String.valueOf(ItemId.getById(itemId)).replaceAll("_", " ").toLowerCase();
        itemName = itemName.substring(0, 1).toUpperCase() + itemName.substring(1);

        if (startAmount < sellAmount) {
          c.log(String.format("Only had %sx %s to sell", startAmount, itemName));
        }
        c.shopSell(itemId, sellAmount);
        c.sleep(1280);
      }

      for (int[] item : buyItems) {
        if (item.length < 3) {
          c.log("AIOQuester: refusing a shop buy with no NPC base stock", "yel");
          continue;
        }
        if (item[1] > 0) buyFromOpenShop(item[0], item[1], item[2]);
      }
      c.closeShop();
      while (c.isInShop() && running()) c.sleep(640);
    }
  }

  /**
   * Walks to specified tile and picks up an item id from an unreachable tile. Keeps retrying ever
   * two ticks until the specified amount is picked up.
   *
   * @param itemId int -- Id of the item to pick up
   * @param standTile int[] -- Tile to stand at to pick up item
   * @param amount int -- The amount to pick up
   */
  public void pickupUnreachableItem(int itemId, int[] standTile, int amount) {
    int newId = changingIdCheck(itemId);
    if (c.getInventoryItemCount() == 30) {
      if (!c.isItemStackable(itemId)
          || ((c.isItemStackable(itemId) && c.getInventoryItemCount(itemId) < 1))) {
        quit(QuitReason.INVENTORY_FULL);
      }
    } else {
      int itemStartCount = c.getInventoryItemCount(newId);
      while (c.getInventoryItemCount(newId) < itemStartCount + amount && running()) {
        int[] item = c.getNearestItemById(itemId);
        if (item != null) {
          if (distanceFromTile(standTile) > 1) {
            int[][] path = {standTile};
            walkPath(path);
          }
          c.pickupItem(item[0], item[1], itemId, false, true);
        }
        c.sleep(1280);
      }
    }
  }

  /**
   * Picks up the nearest item matching the given item id. Keeps retrying every two ticks until the
   * specified amount is picked up.
   *
   * @param itemId int -- Id of the item to pick up
   * @param amount int -- The amount to pick up
   */
  public void pickupGroundItem(int itemId, int amount) {
    int newId = changingIdCheck(itemId);
    if (c.getInventoryItemCount() == 30) {
      if (!c.isItemStackable(itemId)
          || ((c.isItemStackable(itemId) && c.getInventoryItemCount(itemId) < 1))) {
        if (!c.isInCombat()) freeInventorySlots(1);
        if (c.getInventoryItemCount() == 30) {
          quit(QuitReason.INVENTORY_FULL);
          return;
        }
      }
    }
    if (c.getInventoryItemCount() < 30
        || (c.isItemStackable(itemId) && c.getInventoryItemCount(itemId) > 0)) {
      int itemStartCount = c.getInventoryItemCount(newId);
      while (c.getInventoryItemCount(newId) < itemStartCount + amount && running()) {
        int[] item = c.getNearestItemById(itemId);
        if (item != null) {
          if (distanceFromTile(item) > 1) {
            int[][] path = {item};
            walkPath(path);
          }
          c.pickupItem(item[0], item[1], itemId, true, true);
          c.sleep(640);
        } else {
          // Another account took the spawn. Wait for it; this is not a stall and not a quit.
          waitPolitely("ground item " + itemId + " to respawn");
        }
      }
    }
  }

  /**
   * Checks if the given itemId matches any conditions to change it and if not return the original
   *
   * @param itemId Int -- Id of item to check
   * @return Int -- Item id after being checked
   */
  private int changingIdCheck(int itemId) {
    System.out.println(
        "If you get stuck in the while loop here you may have to add a line similar to the line below to changingIdCheck()");
    System.out.println(
        "This happens because the item you are trying to pick up changes ids when picked up");
    System.out.println(
        "\"if (newId == ItemId.ITEM_NAME.getId()) newId = ItemId.NEW_ITEM_NAME.getId();\"");
    // If statements to switch ids for items that change to another id on pickup
    if (itemId == ItemId.FLOUR.getId()) return ItemId.POT_OF_FLOUR.getId();

    return itemId;
  }

  /**
   * Uses an item id on the nearest npc with matching npc id.
   *
   * @param npcId int -- Id of npc, not server index
   * @param itemId int -- Id of the item to use on the npc
   */
  public void useItemOnNearestNpcId(int npcId, int itemId) {
    if (running()) {
      int[] npc_id = {npcId};
      final ORSCharacter npc = c.getNearestNpcByIds(npc_id, false);
      if (npc != null) {
        c.useItemOnNpc(npc.serverIndex, itemId);
        do c.sleep(640);
        while (c.isCurrentlyWalking() && running());
      } else {
        c.log(String.format("Could not find NPC: %s", npcId));
        quit(QuitReason.NPC_NOT_FOUND);
      }
    }
  }

  // ---- Dialogue handling -----------------------------------------------------------------------
  // Controller.optionAnswer() only sends the answer packet. Unlike a real mouse click it leaves the
  // client's option menu flagged as shown (with the old option texts) until the server sends a new
  // menu. Waiting for "a menu" right after answering therefore saw the stale menu, didn't find the
  // next option in it and skipped it, so the server's real next menu was never answered. All
  // dialogue below hides the local menu after answering (like the client does on click), matches
  // options by text, and is bounded in time.

  // ---- Waiting vs. jams -------------------------------------------------------------------------
  // Waiting for a busy npc or a shared ground spawn (other accounts doing the same quest) is
  // normal: it never counts as a stall or a jam, has no retry cap and is logged only now and then.
  // A jam is genuine no-progress: same tile, same inventory, same stage, no dialogue for a while.

  private static String waitWhat = null;
  private static long waitStartedAt = 0, waitLoggedAt = 0, waitLastCall = 0;
  private static final java.util.Random JITTER = new java.util.Random();

  /** @return int -- a random number in [0, bound) (per-account jitter for retries) */
  protected static int jitter(int bound) {
    return bound <= 0 ? 0 : JITTER.nextInt(bound);
  }

  /**
   * One polite wait (2-6 s, randomized) for something other players are using. Logs "Waiting for
   * &lt;what&gt;" at most once a minute with how long it has been. Never counts as a stall/jam.
   */
  protected static void waitPolitely(String what) {
    long now = System.currentTimeMillis();
    if (!what.equals(waitWhat) || now - waitLastCall > 30_000) {
      waitWhat = what;
      waitStartedAt = now;
      waitLoggedAt = 0;
    }
    waitLastCall = now;
    if (now - waitLoggedAt >= 60_000) {
      long mins = (now - waitStartedAt) / 60_000;
      c.log("AIOQuester: Waiting for " + what + (mins > 0 ? " " + mins + "m" : ""), "yel");
      waitLoggedAt = now;
    }
    int ms = 2000 + jitter(4000);
    noteWaiting(ms + 1000);
    c.sleep(ms);
    markProgress(); // waiting is not "no progress"
  }

  private static final ConcurrentHashMap<String, ReentrantLock> TURNS = new ConcurrentHashMap<>();

  /**
   * Same-JVM turn for a shared spawn or npc. The account that gets the lock runs action. The
   * others wait politely and return false so the quest loop retries. Separate client processes
   * do not share the lock; the jitter in waitPolitely still spreads them out.
   */
  protected boolean withTurn(String key, java.util.function.BooleanSupplier action) {
    ReentrantLock lock = TURNS.computeIfAbsent(key, k -> new ReentrantLock());
    boolean held;
    try {
      held = lock.tryLock(80, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      held = false;
    }
    if (!held) {
      waitPolitely(key);
      return false;
    }
    try {
      return action.getAsBoolean();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Buys from an NPC's own shelf. Accounts are treated as ironmen even when the server lets them
   * trade or buy player-sold stock. Stock above the NPC's base amount is left, and nothing is
   * bought from another player. Returns how many were actually added to the inventory.
   */
  public int buyBaseStock(int[] npcIds, int itemId, int baseStock, int amount) {
    if (!running() || amount <= 0 || baseStock < 0) return 0;
    ORSCharacter npc = c.getNearestNpcByIds(npcIds, false);
    if (npc == null) {
      waitPolitely("shop npc " + npcIds[0]);
      return 0;
    }
    if (!openNpcShop(npc)) return 0;
    int bought = 0;
    try {
      bought = buyFromOpenShop(itemId, amount, baseStock);
    } finally {
      c.closeShop();
      while (c.isInShop() && running()) c.sleep(320);
    }
    return bought;
  }

  /**
   * Opens a shop. Uses the NPC's Trade command when it has one. Otherwise talks and picks the
   * shop line. Bob, general stores, Thessalia, Gerrant, Harry, Wayne, Zambo, Peksa, Nurmof
   * and Hudo are covered.
   */
  protected boolean openNpcShop(int[] npcIds) {
    ORSCharacter npc = c.getNearestNpcByIds(npcIds, false);
    if (npc == null) {
      waitPolitely("shop npc " + npcIds[0]);
      return false;
    }
    return openNpcShop(npc);
  }

  protected boolean openNpcShop(ORSCharacter npc) {
    if (npc == null || !running()) return false;
    if (c.isInShop()) return true;
    String cmd = c.getNpcCommand1(npc.npcId);
    if ("Trade".equalsIgnoreCase(cmd)) {
      c.npcCommand1(npc.serverIndex);
      c.sleepUntil(() -> c.isInShop() || c.isInOptionMenu(), 3000);
    }
    if (!c.isInShop()) {
      if (!c.isInOptionMenu()) {
        c.talkToNpc(npc.serverIndex);
        c.sleepUntil(() -> c.isInOptionMenu() || c.isInShop(), 8000);
      }
      if (c.isInOptionMenu()) {
        answerMenuIfPresent(
            "Have you anything to sell?",
            "Yes please, what are you selling?",
            "What have you got?",
            "Let's see what you've got then",
            "I could be, yes",
            "i'll have a look",
            "Yes please");
      }
      c.sleepUntil(c::isInShop, 8000);
    }
    return c.isInShop();
  }

  /** Buys from the shop that is already open. Refuses stock above the NPC's own base amount. */
  private int buyFromOpenShop(int itemId, int amount, int baseStock) {
    if (!c.isInShop() || amount <= 0 || baseStock < 0) return 0;
    int stock = c.getShopItemCount(itemId);
    String name = c.getItemName(itemId);
    if (stock <= 0) {
      waitPolitely(name + " from the NPC's own stock");
      return 0;
    }
    if (stock > baseStock) {
      c.log(
          "AIOQuester: not buying "
              + name
              + " (stock "
              + stock
              + " is above the NPC base "
              + baseStock
              + ")",
          "yel");
      waitPolitely(name + " to return to the NPC's own stock");
      return 0;
    }
    int price = c.getShopItemPrice(itemId);
    int can = Math.min(amount, stock);
    int gold = c.getInventoryItemCount(ItemId.COINS.getId());
    if (price > 0) can = Math.min(can, gold / price);
    int space = 30 - c.getInventoryItemCount();
    if (!c.isItemStackable(itemId)) can = Math.min(can, space);
    if (can <= 0) return 0;
    int before = c.getInventoryItemCount(itemId);
    c.shopBuy(itemId, can);
    c.sleep(1280);
    int bought = Math.max(0, c.getInventoryItemCount(itemId) - before);
    if (bought > 0) markProgress();
    return bought;
  }

  protected int coins() {
    return c.getInventoryItemCount(ItemId.COINS.getId());
  }

  protected boolean carrying(int itemId, int amount) {
    return c.getInventoryItemCount(itemId) >= amount;
  }

  protected void useItemsTogether(int firstId, int secondId) {
    int a = c.getInventoryItemSlotIndex(firstId);
    int b = c.getInventoryItemSlotIndex(secondId);
    if (a >= 0 && b >= 0) c.useItemOnItemBySlot(a, b);
  }

  protected void useItemOnWallSlot(int itemId, int x, int y) {
    int slot = c.getInventoryItemSlotIndex(itemId);
    if (slot < 0) return;
    int startX = c.currentX(), startY = c.currentY();
    long startMsg = lastMessageTime;
    c.useItemOnWall(x, y, slot);
    // Another use before this one is processed cancels it. Wait for the door or a message.
    c.sleepUntil(
        () ->
            c.currentX() != startX
                || c.currentY() != startY
                || lastMessageTime > startMsg
                || c.isInOptionMenu(),
        3000);
    c.sleepUntil(() -> !c.isCurrentlyWalking() || c.isInOptionMenu(), 2000);
  }

  /**
   * Coins from Lumbridge level-7 goblins. A general store pays 0 for feathers, bones, raw chicken
   * and cow hide (40 * basePrice / 100, and those base prices are 1 or 2). Goblins drop coins
   * directly, so a fresh account can pay a boat fare or a shop without selling anything.
   */
  protected void gatherCoins(int needed) {
    if (coins() >= needed) return;
    if (c.currentX() > 300 && c.currentY() > 680 && c.currentY() < 800) {
      c.log("AIOQuester: the bank and the goblins are off Karamja", "yel");
      c.sleep(600);
      return;
    }
    withdrawCoins(needed);
    if (coins() >= needed || !running()) return;
    int[] goblins = {NpcId.GOBLIN_LVL7.getId()};
    int coin = ItemId.COINS.getId();
    while (running() && coins() < needed) {
      CURRENT_QUEST_STEP = "Gathering coins from goblins (" + coins() + "/" + needed + ")";
      if (c.isInCombat()) {
        c.sleep(640);
        continue;
      }
      int[] onGround = c.getNearestItemById(coin);
      if (onGround != null
          && Math.max(Math.abs(onGround[0] - c.currentX()), Math.abs(onGround[1] - c.currentY()))
              < 12) {
        pickupGroundItem(coin, 1);
        continue;
      }
      ORSCharacter goblin = c.getNearestNpcByIds(goblins, false);
      if (goblin == null) {
        walker.walkTo(116, 637, 6, 0);
        waitPolitely("a goblin");
        continue;
      }
      if (!readyToFight()) continue;
      c.attackNpc(goblin.serverIndex);
      c.sleep(1280);
    }
  }

  /** Tells the probe and the quest timeout that the next ms are a deliberate wait. */
  protected static void noteWaiting(long ms) {
    bot.probe.Probe.noteIntentionalWait(ms);
    if (questDeadline > 0) questDeadline += ms;
  }

  private static String progressKey = "";
  private static long progressAt = System.currentTimeMillis();

  /** Resets the jam timer (call after something meaningful happened). */
  protected static void markProgress() {
    progressAt = System.currentTimeMillis();
  }

  /**
   * @return boolean -- true when nothing changed (position, inventory, quest stage, option menu,
   *     chat) for ms. Deliberate waits ({@link #waitPolitely}) reset the timer.
   */
  protected static boolean noProgressFor(long ms) {
    StringBuilder k = new StringBuilder();
    k.append(c.currentX()).append(',').append(c.currentY()).append('|');
    k.append(c.getInventoryItemCount()).append('|');
    try {
      for (int id : c.getInventoryItemIds()) k.append(id).append(' ');
    } catch (RuntimeException e) {
      rethrowAbort(e);
    }
    k.append('|').append(QUEST != null && !QUEST.isMiniquest() ? c.getQuestStage(QUEST.getId()) : 0);
    k.append('|').append(c.isInOptionMenu()).append('|').append(lastMessageTime);
    String key = k.toString();
    long now = System.currentTimeMillis();
    if (!key.equals(progressKey)) {
      progressKey = key;
      progressAt = now;
    }
    return now - progressAt >= ms;
  }

  private static String npcLabel(int npcId) {
    try {
      String n = c.getNpcName(npcId);
      if (n != null && !n.isEmpty()) return n;
    } catch (RuntimeException e) {
      rethrowAbort(e);
    }
    return "npc " + npcId;
  }

  /** Last time (ms) a game/quest message arrived; used to detect ongoing dialogue. */
  protected static volatile long lastMessageTime = 0;
  /** Time of the last "<npc> is busy at the moment" / "I can't reach that" server message. */
  protected static volatile long lastBusyMessage = 0, lastCantReachMessage = 0;
  /** Latest server-type and quest-type (mes) lines. Many plugins send progress as quest messages. */
  protected static volatile String lastServerText = "";
  protected static volatile String lastQuestText = "";
  protected static volatile long lastServerAt = 0;
  protected static volatile long lastQuestAt = 0;
  private static volatile String repeatText = "";
  private static volatile int repeatCount = 0;
  private static final int REPEAT_QUIT_AFTER = 15;
  private static final int ACTION_QUIT_AFTER = 30;
  private static final int WALK_QUIT_AFTER = 5;
  private static volatile String repeatAction = "";
  private static volatile int repeatActionCount = 0;
  private static volatile int repeatActionX = -1;
  private static volatile int repeatActionY = -1;
  private static int floorHopFrom = -1;
  private static int floorHopCount = 0;
  private static long floorHopWindow = 0;

  /** Most recent server or quest message, whichever arrived last. */
  protected static String lastGameMessage() {
    String text = lastQuestAt >= lastServerAt ? lastQuestText : lastServerText;
    return text == null ? "" : text;
  }

  /** True if the latest server or quest message contains the needle (case-insensitive). */
  protected static boolean gameMessageContains(String needle) {
    if (needle == null || needle.isEmpty()) return false;
    return lastGameMessage().toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
  }

  /**
   * Spawn tiles (OpenRSC NpcLocs) of the npcs the free quests talk to. When the npc isn't in view,
   * runDialogue walks here (same floor only) instead of giving up. Quests can add their own with
   * {@link #registerNpcSpawn}.
   */
  private static final Map<Integer, int[]> NPC_SPAWNS = new java.util.HashMap<>();

  static {
    registerNpcSpawn(NpcId.COOK.getId(), 135, 661); // Lumbridge castle kitchen
    registerNpcSpawn(NpcId.DORIC.getId(), 325, 490);
    registerNpcSpawn(NpcId.VERONICA.getId(), 208, 561);
    registerNpcSpawn(NpcId.PROFESSOR_ODDENSTEIN.getId(), 213, 2441);
    registerNpcSpawn(NpcId.WIZARD_MIZGOG.getId(), 218, 2580);
    registerNpcSpawn(NpcId.PRIEST.getId(), 113, 667); // Father Aereck
    registerNpcSpawn(NpcId.URHNEY.getId(), 117, 710);
    registerNpcSpawn(NpcId.GHOST_RESTLESS.getId(), 103, 674);
    registerNpcSpawn(NpcId.ROMEO.getId(), 133, 507);
    registerNpcSpawn(NpcId.JULIET.getId(), 168, 500);
    registerNpcSpawn(NpcId.FATHER_LAWRENCE.getId(), 100, 477);
    registerNpcSpawn(NpcId.APOTHECARY.getId(), 143, 519);
    registerNpcSpawn(NpcId.FRED_THE_FARMER.getId(), 159, 618);
    registerNpcSpawn(NpcId.HETTY.getId(), 317, 667);
    registerNpcSpawn(NpcId.SIR_AMIK_VARZE.getId(), 304, 1507);
    registerNpcSpawn(NpcId.WAYNE.getId(), 305, 578);
    registerNpcSpawn(NpcId.PEKSA.getId(), 236, 509);
    registerNpcSpawn(NpcId.BARTENDER_PORTSARIM.getId(), 255, 625);
    registerNpcSpawn(NpcId.GENERAL_WARTFACE.getId(), 324, 447);
    registerNpcSpawn(NpcId.GENERAL_BENTNOZE.getId(), 323, 447);
    registerNpcSpawn(NpcId.AGGIE.getId(), 223, 626);
    registerNpcSpawn(NpcId.WYSON_THE_GARDENER.getId(), 288, 543);
    registerNpcSpawn(NpcId.GYPSY.getId(), 136, 511);
    registerNpcSpawn(NpcId.SIR_PRYSIN.getId(), 139, 475);
    registerNpcSpawn(NpcId.CAPTAIN_ROVIN.getId(), 140, 1403);
    registerNpcSpawn(NpcId.TRAIBORN_THE_WIZARD.getId(), 214, 1640);
    registerNpcSpawn(NpcId.MORGAN.getId(), 216, 619);
    registerNpcSpawn(NpcId.DR_HARLOW.getId(), 83, 445);
    registerNpcSpawn(NpcId.BARTENDER_JOLLY_BOAR.getId(), 80, 440);
    registerNpcSpawn(NpcId.DELRITH.getId(), 111, 550);
    registerNpcSpawn(NpcId.GUILDMASTER.getId(), 149, 557);
    registerNpcSpawn(NpcId.OZIACH.getId(), 244, 443);
    registerNpcSpawn(NpcId.WORMBRAIN.getId(), 285, 663);
    registerNpcSpawn(NpcId.ORACLE.getId(), 287, 457);
    registerNpcSpawn(NpcId.DUKE_OF_LUMBRIDGE.getId(), 130, 1601);
    registerNpcSpawn(NpcId.NED.getId(), 214, 627);
    registerNpcSpawn(NpcId.KLARENSE.getId(), 260, 641);
    registerNpcSpawn(NpcId.NED_BOAT.getId(), 281, 3473);
    registerNpcSpawn(NpcId.SQUIRE.getId(), 311, 568);
    registerNpcSpawn(NpcId.THURGO.getId(), 291, 713);
    registerNpcSpawn(NpcId.SIR_VYVIN.getId(), 319, 2456);
    registerNpcSpawn(NpcId.RELDO.getId(), 128, 456);
    registerNpcSpawn(NpcId.REDBEARD_FRANK.getId(), 252, 632);
    registerNpcSpawn(NpcId.LUTHAS.getId(), 339, 706);
    registerNpcSpawn(NpcId.WYDIN.getId(), 275, 658);
    registerNpcSpawn(NpcId.ZAMBO.getId(), 347, 711);
    registerNpcSpawn(NpcId.STRAVEN.getId(), 111, 3369);
    registerNpcSpawn(NpcId.KATRINE.getId(), 150, 534);
    registerNpcSpawn(NpcId.BARAEK.getId(), 128, 505);
    registerNpcSpawn(NpcId.JONNY_THE_BEARD.getId(), 122, 524);
    registerNpcSpawn(NpcId.BOB.getId(), 124, 669);
    registerNpcSpawn(NpcId.THESSALIA.getId(), 137, 515);
    registerNpcSpawn(NpcId.GERRANT.getId(), 279, 647);
    registerNpcSpawn(NpcId.BARMAID.getId(), 321, 549);
    registerNpcSpawn(NpcId.GERTRUDE.getId(), 165, 512);
    registerNpcSpawn(NpcId.SHILOP.getId(), 126, 504);
    registerNpcSpawn(NpcId.WILOUGH.getId(), 123, 506);
    registerNpcSpawn(NpcId.EDMOND.getId(), 620, 581);
    registerNpcSpawn(NpcId.ALRENA.getId(), 615, 581);
    registerNpcSpawn(NpcId.JETHICK.getId(), 634, 589);
    registerNpcSpawn(NpcId.MILLI_REHNISON.getId(), 645, 1511);
    registerNpcSpawn(NpcId.BRAVEK.getId(), 646, 586);
    registerNpcSpawn(NpcId.ELENA.getId(), 637, 3449);
    registerNpcSpawn(NpcId.HUDO_GLENFAD.getId(), 716, 1380);
    registerNpcSpawn(NpcId.KAQEMEEX.getId(), 362, 459);
    registerNpcSpawn(NpcId.SANFEW.getId(), 379, 487);
    registerNpcSpawn(NpcId.BROTHER_KOJO.getId(), 580, 637);
    registerNpcSpawn(NpcId.CERIL.getId(), 619, 620);
    registerNpcSpawn(NpcId.CLIVET.getId(), 619, 3477);
    registerNpcSpawn(NpcId.ALOMONE.getId(), 581, 3419);
    registerNpcSpawn(NpcId.CAROLINE.getId(), 521, 619);
    registerNpcSpawn(NpcId.HOLGART_LAND.getId(), 516, 612);
    registerNpcSpawn(NpcId.HOLGART_PLATFORM.getId(), 493, 615);
    registerNpcSpawn(NpcId.HOLGART_ISLAND.getId(), 512, 638);
    registerNpcSpawn(NpcId.KENT.getId(), 510, 636);
    registerNpcSpawn(NpcId.BAILEY.getId(), 483, 614);
    registerNpcSpawn(NpcId.KENNITH.getId(), 484, 1560);
    registerNpcSpawn(NpcId.TRUFITUS.getId(), 436, 751);
    registerNpcSpawn(NpcId.GUARD_SINCLAIR_MANSION.getId(), 496, 399);
    registerNpcSpawn(NpcId.ANNA_SINCLAIR.getId(), 496, 389);
    registerNpcSpawn(NpcId.BOB_SINCLAIR.getId(), 485, 385);
    registerNpcSpawn(NpcId.FRANK_SINCLAIR.getId(), 495, 385);
    registerNpcSpawn(NpcId.DAVID_SINCLAIR.getId(), 485, 403);
    registerNpcSpawn(NpcId.CAROL_SINCLAIR.getId(), 488, 1329);
    registerNpcSpawn(NpcId.ELIZABETH_SINCLAIR.getId(), 495, 1329);
    registerNpcSpawn(NpcId.ARHEIN.getId(), 440, 502);
    registerNpcSpawn(NpcId.POISON_SALESMAN.getId(), 490, 452);
    registerNpcSpawn(NpcId.SHOPKEEPER_LUMBRIDGE.getId(), 134, 643);
  }

  /** Adds/overrides the tile runDialogue walks to when the npc can't be seen. */
  protected static void registerNpcSpawn(int npcId, int x, int y) {
    NPC_SPAWNS.put(npcId, new int[] {x, y});
  }

  private static final long DIALOGUE_IDLE_DONE_MS = 6000;
  private static final long DIALOGUE_IDLE_ABORT_MS = 15000;
  private static final long DIALOGUE_ATTEMPT_MAX_MS = 150000;

  /** Hides the client's local option menu, exactly like the client does when an option is clicked. */
  /**
   * Bounded wait. Sleeps in 320ms steps until the condition is true, the timeout expires or the
   * quest is aborted.
   *
   * @return true if the condition became true
   */
  public boolean waitFor(java.util.function.BooleanSupplier condition, long timeoutMs) {
    long end = System.currentTimeMillis() + timeoutMs;
    while (running() && System.currentTimeMillis() < end) {
      if (condition.getAsBoolean()) return true;
      c.sleep(320);
    }
    return condition.getAsBoolean();
  }

  protected static void hideOptionMenu() {
    try {
      c.getMud().setOptionsMenuShow(false);
    } catch (RuntimeException e) {
      rethrowAbort(e);
      Main.logError("AIOQuester: couldn't hide the option menu", e);
    }
  }

  private static String normalizeDialogue(String s) {
    if (s == null) return "";
    return s.replaceAll("@[a-zA-Z0-9]{3}@", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
  }

  /** Current option menu texts (only the shown ones). */
  protected static String[] currentOptions() {
    String[] all = c.getOptionsMenuText();
    int count = c.getOptionMenuCount();
    if (all == null || count <= 0) return new String[0];
    return Arrays.copyOf(all, Math.min(count, all.length));
  }

  /**
   * Finds the menu option matching a choice: exact match ignoring case/punctuation/colour codes
   * first, then a contains match.
   *
   * @param choice String -- wanted option text
   * @return int -- option index or -1
   */
  protected static int findOption(String choice) {
    String want = normalizeDialogue(choice);
    if (want.isEmpty()) return -1;
    String[] opts = currentOptions();
    for (int i = 0; i < opts.length; i++) if (normalizeDialogue(opts[i]).equals(want)) return i;
    for (int i = 0; i < opts.length; i++) {
      String have = normalizeDialogue(opts[i]);
      if (have.isEmpty()) continue;
      // partial matches only for reasonably specific text, so "Ok" can't match "Look around"
      if ((want.length() >= 6 && have.contains(want)) || (have.length() >= 8 && want.contains(have)))
        return i;
    }
    return -1;
  }

  /** Answers an option by index and hides the local menu so the next menu can be detected. */
  protected static void answerOption(int index) {
    QuestFailureProbe.noteOption(index); // failure probe (tester, keep)
    c.optionAnswer(index);
    hideOptionMenu();
  }

  /**
   * If an option menu is showing, answers the first of the given choices that is present.
   *
   * @return boolean -- whether an option was answered
   */
  protected static boolean answerMenuIfPresent(String... choices) {
    if (!c.isInOptionMenu()) return false;
    for (String choice : choices) {
      int idx = findOption(choice);
      if (idx >= 0) {
        answerOption(idx);
        return true;
      }
    }
    return false;
  }

  /**
   * Waits until this npc is nearby and out of combat. A busy, fighting or dead npc is waited for
   * with a randomized backoff. This does not quit. timeoutMs is unused so old callers still compile.
   */
  private ORSCharacter waitForNpc(int npcId, long timeoutMs) {
    long walkAfter = System.currentTimeMillis() + 5000;
    boolean walked = false;
    int[] ids = {npcId};
    while (running()) {
      ORSCharacter npc = c.getNearestNpcByIds(ids, false);
      if (npc != null) return npc;
      int[] spawn = NPC_SPAWNS.get(npcId);
      if (spawn != null && QuestWalker.floor(spawn[1]) != QuestWalker.floor(c.currentY())) {
        c.log(
            String.format(
                "AIOQuester: npc %s is on another floor (%d,%d, we're at %d,%d). Not waiting.",
                npcId, spawn[0], spawn[1], c.currentX(), c.currentY()),
            "yel");
        return null;
      }
      long now = System.currentTimeMillis();
      if (!walked && now > walkAfter) {
        // Not in view: walk to where it spawns (same floor) instead of giving up
        walked = true;
        if (spawn != null
            && Math.max(Math.abs(spawn[0] - c.currentX()), Math.abs(spawn[1] - c.currentY())) > 3) {
          c.log(
              String.format(
                  "AIOQuester: npc %s not in view, walking to its spawn %d,%d",
                  npcId, spawn[0], spawn[1]),
              "yel");
          walker.walkTo(spawn[0], spawn[1], 3, 0);
        }
      }
      ORSCharacter busy = c.getNearestNpcByIds(ids, true);
      if (busy != null) waitPolitely(npcLabel(npcId) + " (in combat)");
      else waitPolitely(npcLabel(npcId) + " to respawn");
    }
    return null;
  }

  /** Walks next to an npc that is in view but out of reach (behind a door, far away). */
  private void approachNpc(ORSCharacter npc) {
    if (npc == null) return;
    int[] at = c.getNpcCoordsByServerIndex(npc.serverIndex);
    if (at == null || at.length < 2) return;
    c.log(String.format("AIOQuester: walking up to npc at %d,%d", at[0], at[1]), "yel");
    walker.walkTo(at[0], at[1], 1, 45_000);
  }

  /**
   * Runs a dialogue: talks to the npc (if startTalk), answers each option menu with the matching
   * choice (by text, in order, skipping choices whose menu doesn't appear), and returns once the
   * conversation has gone quiet. If the conversation closes or never starts before all choices
   * were used, it talks again (up to attempts times). Never waits forever.
   *
   * @param npcId int -- npc id
   * @param choices String[] -- option texts to pick, in order (may be empty)
   * @param startTalk boolean -- talk to the npc first (false for pop-up dialogue started by an
   *     object or item)
   * @param attempts int -- maximum number of talk attempts
   * @return boolean -- true if every choice was answered and the dialogue finished
   */
  public boolean runDialogue(int npcId, String[] choices, boolean startTalk, int attempts) {
    if (choices == null) choices = new String[0];
    int[] ids = {npcId};
    int next = 0;
    int busyRetries = 0, reachRetries = 0;
    for (int attempt = 1; attempt <= Math.max(1, attempts) && running(); attempt++) {
      if (startTalk) {
        ORSCharacter npc = waitForNpc(npcId, 60000);
        if (!running()) return false;
        if (npc == null) {
          int[] spawn = NPC_SPAWNS.get(npcId);
          if (spawn != null && QuestWalker.floor(spawn[1]) != QuestWalker.floor(c.currentY())) {
            c.log(
                String.format("AIOQuester: %s is on another floor. Climb before talking.", npcId),
                "yel");
            waitPolitely("a ladder towards " + npcLabel(npcId));
            return false;
          }
          c.log(String.format("Could not find NPC: %s", npcId));
          quit(QuitReason.NPC_NOT_FOUND);
          return false;
        }
        hideOptionMenu(); // drop any stale menu from an earlier conversation
        int[] npcAt = c.getNpcCoordsByServerIndex(npc.serverIndex);
        if (npcAt != null
            && Math.max(Math.abs(npcAt[0] - c.currentX()), Math.abs(npcAt[1] - c.currentY())) > 12)
          approachNpc(npc);
        logForMethodIfDebugging(
            "runDialogue", "Talking to " + c.getNpcName(npc.npcId) + " (attempt " + attempt + ")");
        long talkAt = System.currentTimeMillis();
        QuestFailureProbe.noteTalk(npc.npcId, attempt); // failure probe (tester, keep)
        c.talkToNpc(npc.serverIndex);
        c.sleep(640);
        // "<npc> is busy at the moment" (talking to someone else) / "I can't reach that"
        c.sleepUntil(
            () -> c.isInOptionMenu() || lastBusyMessage > talkAt || lastCantReachMessage > talkAt,
            1600);
        if (lastBusyMessage > talkAt) {
          // Busy with another player (many accounts run the same quest): wait as long as it
          // takes. Not a stall and no retry cap; jittered so accounts don't retry in lockstep.
          busyRetries++;
          waitPolitely(npcLabel(npcId) + " (busy)");
          attempt--;
          continue;
        }
        if (lastCantReachMessage > talkAt && reachRetries++ < 4) {
          approachNpc(npc);
          attempt--;
          continue;
        }
      }

      long attemptStart = System.currentTimeMillis();
      long lastActivity = attemptStart;
      long lastMsgSeen = lastMessageTime;
      boolean sawDialogue = false;
      ORSCharacter startNpc = c.getNearestNpcByIds(ids, false);
      String lastNpcMsg = startNpc != null ? startNpc.message : null;
      ORSCharacter player = c.getPlayer();
      String lastPlayerMsg = player != null ? player.message : null;
      String loggedMenu = null;

      while (running()) {
        long now = System.currentTimeMillis();
        if (c.isInOptionMenu()) {
          int choiceIdx = -1;
          int optIdx = -1;
          // Lowest list index that is on this menu wins, so an exit line listed first is
          // picked even when an info option is also showing.
          for (int j = 0; j < choices.length; j++) {
            int idx = findOption(choices[j]);
            if (idx >= 0) {
              choiceIdx = j;
              optIdx = idx;
              break;
            }
          }
          if (optIdx >= 0) {
            logForMethodIfDebugging("runDialogue", "Choosing: " + currentOptions()[optIdx]);
            answerOption(optIdx);
            next = choiceIdx + 1;
            sawDialogue = true;
            lastActivity = System.currentTimeMillis();
            c.sleep(1280);
            continue;
          }
          String menu = String.join(" | ", currentOptions());
          if (!menu.equals(loggedMenu)) {
            c.log("AIOQuester: no matching option in menu: [" + menu + "]", "red");
            c.log("AIOQuester: wanted one of: " + String.join(" | ", choices), "red");
            loggedMenu = menu;
          }
          // Leave the unknown menu alone; if nothing else happens the attempt ends below.
        }

        ORSCharacter npc = c.getNearestNpcByIds(ids, false);
        String npcMsg = npc != null ? npc.message : null;
        player = c.getPlayer();
        String playerMsg = player != null ? player.message : null;
        // Each new chat line is a new String object, so compare references
        boolean changed =
            (npcMsg != null && npcMsg != lastNpcMsg)
                || (playerMsg != null && playerMsg != lastPlayerMsg)
                || lastMessageTime != lastMsgSeen;
        if (changed) {
          lastActivity = now;
          sawDialogue = true;
        }
        lastNpcMsg = npcMsg;
        lastPlayerMsg = playerMsg;
        lastMsgSeen = lastMessageTime;
        if (c.isCurrentlyWalking()) lastActivity = now; // still walking to the npc

        long idle = now - lastActivity;
        if (sawDialogue
            && idle > DIALOGUE_IDLE_DONE_MS
            && !c.isInOptionMenu()
            && (next > 0 || choices.length == 0)) return true;
        if (idle > DIALOGUE_IDLE_ABORT_MS || now - attemptStart > DIALOGUE_ATTEMPT_MAX_MS) break;
        c.sleep(320);
      }
      if (!running()) return false;
      if (c.isInOptionMenu()) hideOptionMenu();
      if (sawDialogue && !c.isInOptionMenu() && (next > 0 || choices.length == 0)) return true;
      if (!startTalk) break; // can't restart a pop-up dialogue from here
      c.log(
          String.format(
              "AIOQuester: dialogue with npc %s ended early (%d/%d options used)%s",
              npcId,
              Math.min(next, choices.length),
              choices.length,
              attempt < attempts ? ", talking again" : ""),
          "yel");
    }
    return false;
  }

  /**
   * Talks to the nearest npc with the given id and waits for the dialogue to finish. Option menus
   * that appear are not answered (use followNPCDialog for that).
   *
   * @param npcId int -- Id of the npc to talk to
   */
  public boolean talkToNpcId(int npcId) {
    return runDialogue(npcId, new String[0], true, 2);
  }

  /**
   * Goes through a dialogue with the nearest instance of a npc id with dialogChoices[] being the
   * responses from the character.
   *
   * <p>This method is for pop up quest dialog after interacting with an object
   *
   * @param npcId int -- Id of the npc to talk to
   * @param dialogChoices String[] -- Responses to dialogs
   */
  public boolean followNPCDialogPopUps(int npcId, String[] dialogChoices) {
    while (c.isCurrentlyWalking() && running()) c.sleep(640);
    return runDialogue(npcId, dialogChoices, false, 1);
  }

  /**
   * Talks to the nearest npc with the id and answers its option menus with dialogChoices (matched
   * by text, in order), retrying the conversation if it closes early.
   *
   * @param npcId int -- Id of the npc to talk to
   * @param dialogChoices String[] -- Responses to dialogs
   */
  public boolean followNPCDialog(int npcId, String[] dialogChoices) {
    return runDialogue(npcId, dialogChoices, true, 3);
  }

  /**
   * Waits for the quest stage to change, for up to 30 seconds. If it doesn't change, the quest
   * loop simply runs the current stage again.
   */
  public void sleepUntilQuestStageChanges() {
    int oldStage = QUEST_STAGE;
    long until = System.currentTimeMillis() + 30000;
    while (c.getQuestStage(QUEST.getId()) == oldStage && running()) {
      if (System.currentTimeMillis() > until) {
        c.log("AIOQuester: quest stage didn't change from " + oldStage + ", retrying the step", "yel");
        if (Thread.currentThread() == questThread)
          throw new StageRetryException("stage " + oldStage + " didn't advance");
        return;
      }
      c.sleep(640);
    }
  }

  /**
   * Ideally, this will eventually be removed in place of walkToward(). Uses PathWalkerApi to walk
   * to a tile.
   *
   * @param mapPoint MapPoint -- Coordinates of the tile to walk to
   */
  public void pathWalker(MapPoint mapPoint) {
    // Was: loop until exactly on the tile, re-planning the IdleRSC path walker each time; an
    // unwalkable tile or a null path made it spin forever.
    if (!running()) return;
    if (!walker.walkTo(mapPoint.getX(), mapPoint.getY(), 1, 0))
      walkFailed(mapPoint.getX(), mapPoint.getY());
  }

  /**
   * Ideally, this will eventually be removed in place of walkToward(). Uses PathWalkerApi to walk
   * to a tile.
   *
   * @param coords int[] -- Coordinates of the tile to walk to
   */
  public void pathWalker(int[] coords) {
    pathWalker(new MapPoint(coords[0], coords[1]));
  }

  /**
   * Ideally, this will eventually be removed in place of walkToward(). Uses PathWalkerApi to walk
   * to a tile.
   *
   * @param x int -- X coordinate of the tile to walk to
   * @param y int -- Y coordinate of the tile to walk to
   */
  public void pathWalker(int x, int y) {
    pathWalker(new MapPoint(x, y));
  }

  /**
   * Ideally, this will eventually be removed in place of walkToward(). Follows a given path while
   * checking for and opening closed doors next to given tiles.
   *
   * @param path - Int[][] -- Each index is a different tile. Either {{X, Y}, {X2, Y2}} or
   *     {TILE1[],TILE2[]}
   */
  public void walkPath(int[][] path) {
    for (int[] tile : path) walkPathTile(tile);
  }

  private void walkPathTile(int[] tile) {
    if (!running()) return;
    if (!walker.walkTo(tile[0], tile[1], 1, 0)) walkFailed(tile[0], tile[1]);
  }

  /**
   * Ideally, this will eventually be removed in place of walkToward(). Follows the reverse of a
   * given path while checking for and opening closed doors next to given tiles.
   *
   * @param path - Int[][] -- Each index is a different tile. Either {{X, Y}, {X2, Y2}} or {TILE1[],
   *     TILE2[]}
   */
  public void walkPathReverse(int[][] path) {
    for (int i = path.length - 1; i >= 0; i--) walkPathTile(path[i]);
  }

  /**
   * Returns the distance the player is from the specified tile
   *
   * @param coords int[] -- Coordinates to specified tile
   * @return boolean -- Returns the distance from the tile
   */
  public int distanceFromTile(int[] coords) {
    return c.distance(c.currentX(), c.currentY(), coords[0], coords[1]);
  }

  /**
   * Returns the distance the player is from the specified Map Point
   *
   * @param mapPoint MapPoint -- MapPoint to check
   * @return int -- Returns the distance from the MapPoint
   */
  public int distanceFromTile(MapPoint mapPoint) {
    MapPoint current = new MapPoint(c.currentX(), c.currentY());
    return MapPoint.distance(current, mapPoint);
  }

  /**
   * Does c.atObject command and sleeps until you are moved to the destination, Climbs a ladder or
   * stairs and sleeps until you are moved to the destination Also Supports going through fixed
   * (does not stay open) doors
   *
   * <p>*Requires the object moving your coordinates*
   *
   * @param coords int[] -- Coordinates of an object
   */
  public void atObject(int[] coords) {
    useObject(coords, false);
  }


  /**
   * Does c.atObject2 command and sleeps until you are moved to the destination, Climbs a ladder or
   * stairs and sleeps until you are moved to the destination Also Supports going through a fixed
   * (does not stay open) doors
   *
   * <p>*Requires the object moving your coordinates*
   *
   * @param coords int[] -- Coordinates of an object
   */
  public void atObject2(int[] coords) {
    useObject(coords, true);
  }

  /**
   * A one-tile step toward an object is the walk up to it, not a successful use. Success is a
   * floor change, a door taken while already standing next to it, an option menu, a quest-stage
   * change, or a server message (listen, search, pick).
   */
  private void useObject(int[] coords, boolean second) {
    while (running() && c.isCurrentlyWalking()) c.sleep(640);
    c.log(
        String.format(
            "Using object%s at (%s,%s)", second ? " (2nd option)" : "", coords[0], coords[1]));
    for (int i = 0; i < 8 && running(); i++) {
      final int startX = c.currentX(), startY = c.currentY();
      final long startMsg = lastMessageTime;
      final int startStage = QUEST_STAGE;
      noteRepeatedAction("atObject " + coords[0] + "," + coords[1]);
      if (second) c.atObject2(coords[0], coords[1]);
      else c.atObject(coords[0], coords[1]);
      if (c.sleepUntil(
          () -> objectSettled(startX, startY, coords[0], coords[1], startMsg, startStage), 8000))
        return;
    }
    c.log("Position didn't change after using it", "red");
    walkFailed(coords[0], coords[1]);
  }

  private boolean objectSettled(int sx, int sy, int ox, int oy, long startMsg, int startStage) {
    if (c.isInOptionMenu()) return true;
    if (Math.abs(c.currentY() - sy) > 900) return true;
    if (lastMessageTime > startMsg || QUEST_STAGE != startStage) return true;
    if (c.isCurrentlyWalking()) return false;
    int dx = Math.abs(c.currentX() - sx);
    int dy = Math.abs(c.currentY() - sy);
    if (dx == 0 && dy == 0) return false;
    int away = Math.max(Math.abs(sx - ox), Math.abs(sy - oy));
    return away <= 1;
  }


  /**
   * Does c.atObject command and sleeps until you are moved to the destination. Climbs a ladder or
   * stairs and sleeps until you are moved to the destination. Also Supports going through a fixed
   * (does not stay open) doors
   *
   * <p>*Requires the object moving your coordinates*
   *
   * @param x int x coordinate of the object
   * @param y int y coordinate of the object
   */
  public void atObject(int x, int y) {
    atObject(new int[] {x, y});
  }

  /** useItemIdOnObject that counts toward the 30-action repeat guard. */
  protected static void useCarriedItemOnObject(int x, int y, int itemId) {
    noteRepeatedAction("use item " + itemId + " on " + x + "," + y);
    c.useItemIdOnObject(x, y, itemId);
  }

  /**
   * Does c.atObject2 command and sleeps until you are moved to the destination, Climbs a ladder or
   * stairs and sleeps until you are moved to the destination Also Supports going through fixed
   * (does not stay open) doors
   *
   * <p>*Requires the object moving your coordinates*
   *
   * @param x int x coordinate of the object
   * @param y int y coordinate of the object
   */
  public void atObject2(int x, int y) {
    atObject2(new int[] {x, y});
  }

  /**
   * Opens a fixed (does not stay open) wall object (c.atWallObject()) and sleeps until you are
   * moved to the destination
   *
   * <p>*Requires the object moving your coordinates*
   *
   * @param coords int[] -- Coordinates of a wall object
   */
  public void atWallObject(int[] coords) {
    // Bounded: was an endless loop until the player's position changed.
    while (running() && c.isCurrentlyWalking()) c.sleep(640);
    final int startX = c.currentX(), startY = c.currentY();
    c.log(String.format("Using wall object at (%s,%s)", coords[0], coords[1]));
    for (int i = 0; i < 8 && running(); i++) {
      noteRepeatedAction("atWallObject " + coords[0] + "," + coords[1]);
      c.atWallObject(coords[0], coords[1]);
      if (c.sleepUntil(
          () -> c.isInOptionMenu() || c.currentX() != startX || c.currentY() != startY, 4000)) {
        // A secret wall keeps moving after the first tile. Wait until that walk finishes.
        if (!c.isInOptionMenu()) c.sleepUntil(() -> !c.isCurrentlyWalking(), 3000);
        return;
      }
      if (c.isInOptionMenu()) return;
    }
    c.log("Position didn't change after using it", "red");
    walkFailed(coords[0], coords[1]);
  }


  /**
   * Opens a fixed (does not stay open) wall object (c.atWallObject2())and sleeps until you are
   * moved to the destination
   *
   * <p>*Requires the object moving your coordinates*
   *
   * @param coords int[] -- Coordinates of a wall object
   */
  public void atWallObject2(int[] coords) {
    // Bounded: was an endless loop until the player's position changed.
    while (running() && c.isCurrentlyWalking()) c.sleep(640);
    final int startX = c.currentX(), startY = c.currentY();
    c.log(String.format("Using wall object (2nd option) at (%s,%s)", coords[0], coords[1]));
    for (int i = 0; i < 8 && running(); i++) {
      noteRepeatedAction("atWallObject2 " + coords[0] + "," + coords[1]);
      c.atWallObject2(coords[0], coords[1]);
      if (c.sleepUntil(() -> c.currentX() != startX || c.currentY() != startY, 4000)) return;
      if (c.isInOptionMenu()) return;
    }
    c.log("Position didn't change after using it", "red");
    walkFailed(coords[0], coords[1]);
  }


  /**
   * Opens a fixed (does not stay open) wall object (c.atWallObject()) and sleeps until you are
   * moved to the destination
   *
   * <p>*Requires the object moving your coordinates*
   *
   * @param x int x coordinate of the object
   * @param y int y coordinate of the object
   */
  public void atWallObject(int x, int y) {
    atWallObject(new int[] {x, y});
  }

  /**
   * Opens a fixed (does not stay open) wall object (c.atWallObject2())and sleeps until you are
   * moved to the destination
   *
   * <p>*Requires the object moving your coordinates*
   *
   * @param x int x coordinate of the object
   * @param y int y coordinate of the object
   */
  public void atWallObject2(int x, int y) {
    atWallObject2(new int[] {x, y});
  }

  /**
   * Climbs a ladder or stairs and sleeps until you are moved to the destination Also Supports going
   * through fixed (does not stay open) doors
   *
   * @param coords int[] -- Coordinates of a climbable object
   */
  public void climb(int[] coords) {
    // Bounded and verified: walks to the ladder/stairs, uses it and checks the floor changed.
    if (!running()) return;
    if (!walker.climb(coords[0], coords[1], false, 6)) {
      if (c.isInOptionMenu()) return;
      walkFailed(coords[0], coords[1]);
    }
  }


  /**
   * Climbs a ladder or stairs and sleeps until you are moved to the destination
   *
   * @param x int -- X coordinate of a climbable object
   * @param y int -- Y coordinate of a climbable object
   */
  public void climb(int x, int y) {
    climb(new int[] {x, y});
  }

  /**
   * Interacts with objects in a given order based on coords. This has no pathing between objects,
   * so they must be clickable.
   *
   * @param coords - Int[][] -- Each index is a different Object's tile coordinates. Either {{X, Y},
   *     {X2, Y2}} or {TILE1[], TILE2[]}
   */
  public void atObjectSequence(int[][] coords) {
    int maxDistance = 30;
    for (int i = 0; i < coords.length; i++) {
      int[] coord = coords[i];
      // Quit if the item is not found nearby
      if (distanceFromTile(coord) > maxDistance) quit(QuitReason.OBJECT_NOT_FOUND);
      while (c.isCurrentlyWalking() && running()) c.sleep(640);
      // Walks to and performs atObject at the current coords
      if (running()) {
        if (!c.atObject(coord[0], coord[1])) quit(QuitReason.OBJECT_NOT_FOUND);
        c.sleep(2560);
        while (running() && c.isCurrentlyWalking()) c.sleep(640);
        if (i + 1 < coords.length) {
          // If the player is not within maxDistance of the next object sleep for up to 20 ticks
          for (int j = 0; j < 5; j++) {
            if (distanceFromTile(coords[i + 1]) > maxDistance && running()) {
              c.atObject(coord[0], coord[1]);
              c.sleep(2560);
            } else {
              break;
            }
          }
        }
      }
    }
    c.sleep(1280);
  }

  /**
   * If TESTING_QUESTS is false, returns the quest's stage; if not, then it returns TESTING_STAGE
   *
   * @return int -- Quest stage
   */
  public int getQuestStage() {
    if (TESTING_QUESTS) {
      @SuppressWarnings("ConstantValue")
      int stage = Math.max(TESTING_STAGE, 0);
      c.log("Testing '" + QUEST.getName() + "' at stage " + stage);
      return stage;
    }
    return c.getQuestStage(QUEST.getId());
  }

  /**
   * Update the bankItems Map from controller.getDebuggerBank(). This should always be run after
   * opening a bank interface.
   */
  public void updateBankItems() {
    bankItems = c.getDebuggerBank();
  }

  /**
   * Logs a message if AIOQuester.DEBUGGING is true Use this to log extra information to console for
   * testing and debugging methods
   *
   * @param message String -- Message to log
   */
  public void logIfDebugging(String message) {
    if (AIOQuester.DEBUGGING) Main.logDebug(message);
  }

  /**
   * Logs a message if AIOQuester.DEBUGGING is true Use this to log extra information to console for
   * testing and debugging methods
   *
   * @param message String -- Message to log
   */
  public void logForMethodIfDebugging(String methodName, String message) {
    if (message == null || methodName == null) return;

    if (AIOQuester.DEBUGGING)
      Main.logDebug(String.format("AIOQuester.%s(): %s", methodName, message));
  }

  /**
   * Quits the script and prints out a corresponding message.
   *
   * @param reason QuestReason - Reason to quit
   */
  public void quit(QuitReason reason) {
    String message = reason.getMessage();
    if (queueMode && c.isRunning()) {
      // Queue mode: end only the current quest instead of stopping the whole script.
      if (Thread.currentThread() == questThread) throw new QuestAbortException(reason);
      // Called from another thread (e.g. a message interrupt); the quest thread picks it up in
      // running()/isQuesting().
      pendingQuit = reason;
      return;
    }
    if (c.isRunning()
        || !hasStoppedScript && message.equals(QuitReason.SCRIPT_STOPPED.getMessage())) {
      hasStoppedScript = true;
      c.log(message, "red");
      c.stop();
    }
  }

  /**
   * Check if the client receives a server message and do something with it. This method is called
   * in AIOQuester where it overrides IdleScript's serverMessageInterrupt()<br>
   * <br>
   * This then calls a script-specific method that can be overridden for message handling per script
   *
   * @param message String - Message to check for
   */
  public final void _serverMessageInterrupt(String message) {
    if (message == null) return;
    logIfDebugging(String.format("Server: [%s]", message));
    QuestFailureProbe.noteMessage("server", message); // failure probe (tester, keep)
    lastMessageTime = System.currentTimeMillis();
    lastServerText = message;
    lastServerAt = lastMessageTime;
    noteRepeatedGameMessage(message);
    noteDeath(message);
    if (message.contains("is busy at the moment")) lastBusyMessage = lastMessageTime;
    if (message.contains("can't reach") || message.contains("cannot reach"))
      lastCantReachMessage = lastMessageTime;
    if (message.toLowerCase(Locale.ROOT).contains("whilst you are fighting")
        || message.toLowerCase(Locale.ROOT).contains("while you are fighting")) {
      lastEatRefusedAt = lastMessageTime;
    }
    // don't change spelling
    if (message.contains("@gr3@You @gr2@are @gr1@poisioned")) timeToDrinkAntidote = true;
    LeverBasement.onServerMessage(message); // Draynor Manor lever puzzle (any quest that passes)
    if (script != null) script.serverMessageInterrupt(message);
  }

  /**
   * Check if the client receives a quest message and do something with it. This method is called in
   * AIOQuester where it overrides IdleScript's serverMessageInterrupt()<br>
   * <br>
   * This then calls a script-specific method that can be overridden for message handling per script
   *
   * @param message String - Message to check for
   */
  public final void _questMessageInterrupt(String message) {
    if (message == null) return;
    logIfDebugging(String.format("Quest: [%s]", message));
    QuestFailureProbe.noteMessage("quest", message); // failure probe (tester, keep)
    lastMessageTime = System.currentTimeMillis();
    lastQuestText = message;
    lastQuestAt = lastMessageTime;
    noteRepeatedGameMessage(message);
    noteDeath(message);
    if (script != null) script.questMessageInterrupt(message);
  }

  /**
   * Quits the current quest when the same stripped server/quest line arrives many times in a row.
   * Combat, eating, catch/drop spam and the script's own debug echoes are ignored.
   */
  private void noteRepeatedGameMessage(String message) {
    String stripped = message.replaceAll("@[a-zA-Z0-9]{2,3}@", "").trim();
    if (stripped.length() < 10) {
      repeatText = "";
      repeatCount = 0;
      return;
    }
    String lower = stripped.toLowerCase(Locale.ROOT);
    if (stripped.startsWith("AIOQuester:")
        || stripped.startsWith("[QuestWalker]")
        || stripped.startsWith("[Manor]")
        || lower.startsWith("server:")
        || lower.startsWith("quest: [")
        || lower.contains("under attack")
        || lower.contains("eat")
        || lower.contains(" hit")
        || lower.contains("hits ")
        || lower.contains("you catch")
        || lower.contains("dropping")
        || lower.contains("you drop")
        || lower.contains("you give traiborn a set of bones")
        || lower.contains("you give fred a ball of wool")
        || lower.contains("no bananas left")
        || lower.contains("pick the man")
        || lower.contains("you are stunned")
        || lower.contains("you're stunned")
        || lower.contains("already busy fighting")
        || lower.contains("is busy at the moment")
        || lower.contains("you bury")
        || lower.contains("whilst you are fighting")
        || lower.contains("while you are fighting")) {
      return;
    }
    if (stripped.equalsIgnoreCase(repeatText)) {
      repeatCount++;
      if (repeatCount == REPEAT_QUIT_AFTER) {
        c.log(
            "AIOQuester: same game message "
                + REPEAT_QUIT_AFTER
                + " times: \""
                + stripped
                + "\"",
            "red");
        quit(QuitReason.REPEATED_GAME_MESSAGE);
      }
    } else {
      repeatText = stripped;
      repeatCount = 1;
    }
  }

  /**
   * Counts identical client actions (walks, pushes) that do not move the player. 30 in a row
   * quits. Call from the quest thread.
   */
  public static void noteRepeatedAction(String action) {
    if (action == null || action.isEmpty()) return;
    QuestFailureProbe.noteAction(action); // failure probe (tester, keep)
    boolean walk = action.startsWith("walk ");
    String key = walk ? action : action + "@" + c.currentX() + "," + c.currentY();
    int limit = walk ? WALK_QUIT_AFTER : ACTION_QUIT_AFTER;
    if (walk && key.equals(repeatAction)) {
      int moved =
          Math.max(Math.abs(c.currentX() - repeatActionX), Math.abs(c.currentY() - repeatActionY));
      if (moved > 20) repeatActionCount = 0;
    }
    if (walk) {
      repeatActionX = c.currentX();
      repeatActionY = c.currentY();
    }
    if (key.equals(repeatAction)) {
      repeatActionCount++;
      if (repeatActionCount == limit) {
        c.log(
            "AIOQuester: same client action "
                + limit
                + " times with no position change: \""
                + action
                + "\"",
            "red");
        pendingQuit = QuitReason.REPEATED_GAME_MESSAGE;
        if (queueMode && Thread.currentThread() == questThread) {
          throw new QuestAbortException(QuitReason.REPEATED_GAME_MESSAGE);
        }
      }
    } else {
      repeatAction = key;
      repeatActionCount = 1;
    }
  }

  /** Failure probe (tester, keep): read-only snapshot of the repeat guards for reports. */
  static Map<String, Object> probeGuardState() {
    Map<String, Object> m = new java.util.LinkedHashMap<>();
    m.put("repeatMessage", repeatText);
    m.put("repeatMessageCount", repeatCount);
    m.put("repeatMessageLimit", REPEAT_QUIT_AFTER);
    m.put("repeatAction", repeatAction);
    m.put("repeatActionCount", repeatActionCount);
    m.put("repeatActionLimit", ACTION_QUIT_AFTER + " (walks " + WALK_QUIT_AFTER + ")");
    m.put("floorFlipCount", floorHopCount);
    m.put("floorFlipFrom", floorHopFrom);
    m.put("lastServerText", lastServerText);
    m.put("lastQuestText", lastQuestText);
    m.put("pendingQuit", pendingQuit == null ? null : pendingQuit.name());
    return m;
  }

  /** Alternating floor changes (A→B→A) more than 10 times in 60s quit the quest. */
  public static void noteFloorChange(int from, int to) {
    if (from == to) return;
    long now = System.currentTimeMillis();
    if (floorHopWindow == 0 || now - floorHopWindow > 60_000) {
      floorHopWindow = now;
      floorHopCount = 0;
      floorHopFrom = from;
    }
    floorHopCount++;
    if (floorHopCount > 10) {
      c.log(
          "AIOQuester: alternating floor changes "
              + floorHopCount
              + " times in 60s ("
              + from
              + " -> "
              + to
              + ")",
          "red");
      pendingQuit = QuitReason.REPEATED_GAME_MESSAGE;
      if (queueMode && Thread.currentThread() == questThread) {
        throw new QuestAbortException(QuitReason.REPEATED_GAME_MESSAGE);
      }
    }
  }

  private static void noteDeath(String message) {
    if (message == null) return;
    String lower = message.replaceAll("@[a-zA-Z0-9]{2,3}@", "").toLowerCase(Locale.ROOT);
    if (!lower.contains("oh dear! you are dead") && !lower.contains("granted another life")) {
      return;
    }
    c.log("AIOQuester: death detected, quitting this quest", "red");
    pendingQuit = QuitReason.DIED;
    if (queueMode && Thread.currentThread() == questThread) {
      throw new QuestAbortException(QuitReason.DIED);
    }
  }

  /** Climb nearby ladders/stairs until y < 944 so the next queued quest starts on the ground. */
  public static void returnToGroundFloor() {
    if (!c.isRunning() || c.currentY() <= QuestWalker.FLOOR_HEIGHT) return;
    c.log(
        "AIOQuester: returning to floor 0 before the next quest (at "
            + c.currentX()
            + ","
            + c.currentY()
            + ")",
        "yel");
    int f = QuestWalker.floor(c.currentY());
    if (f == 1 || f == 2) {
      if (!walker.descendToGround()) {
        c.log("AIOQuester: no nearby ladder or stairs to climb down", "yel");
      }
      return;
    }
    for (int i = 0; i < 8 && c.isRunning() && c.currentY() > QuestWalker.FLOOR_HEIGHT; i++) {
      int[] ladder = nearestClimbDown();
      if (ladder == null) {
        c.log("AIOQuester: no nearby ladder or stairs to climb down", "yel");
        return;
      }
      walker.climb(ladder[0], ladder[1], false, 3);
    }
  }

  private static int[] nearestClimbDown() {
    try {
      int[] ids = c.getObjectsIds();
      int[] xs = c.getObjectsX();
      int[] zs = c.getObjectsZ();
      int n = c.getObjectsCount();
      int bestD = Integer.MAX_VALUE;
      int[] best = null;
      for (int i = 0; i < n; i++) {
        String name = c.getObjectName(ids[i]).toLowerCase(Locale.ROOT);
        String cmd = c.getObjectCommand1(ids[i]).toLowerCase(Locale.ROOT);
        if (!name.contains("ladder")
            && !name.contains("stair")
            && !name.contains("trapdoor")) {
          continue;
        }
        if (cmd.contains("up") && !cmd.contains("down")) continue;
        int wx = c.offsetX(xs[i]);
        int wy = c.offsetZ(zs[i]);
        int d = Math.max(Math.abs(wx - c.currentX()), Math.abs(wy - c.currentY()));
        if (d < bestD && d <= 24) {
          bestD = d;
          best = new int[] {wx, wy};
        }
      }
      return best;
    } catch (RuntimeException e) {
      rethrowAbort(e);
      return null;
    }
  }

  /**
   * Check if the client receives a private message and do something with it. This method is called
   * in AIOQuester where it overrides IdleScript's serverMessageInterrupt()<br>
   * <br>
   * This then calls a script-specific method that can be overridden for message handling per script
   *
   * @param sender String - Message to check for
   * @param message String - Message to check for
   */
  public final void _privateMessageInterrupt(String sender, String message) {
    if (message == null || sender == null) return;
    logIfDebugging(String.format("Private: %s: [%s]", sender, message));
    if (script != null) script.privateMessageInterrupt(sender, message);
  }

  /**
   * Check if the client receives a private message and do something with it. This method is called
   * in AIOQuester where it overrides IdleScript's serverMessageInterrupt()<br>
   * <br>
   * This then calls a script-specific method that can be overridden for message handling per script
   *
   * @param message String - Message to check for
   */
  public final void _chatMessageInterrupt(String message) {
    if (message == null) return;
    logIfDebugging(String.format("Chat: [%s]", message));
    QuestFailureProbe.noteMessage("chat", message); // failure probe (tester, keep)
    if (script != null) script.chatMessageInterrupt(message);
  }

  /**
   * Check if the client receives a private message and do something with it. This method is called
   * in AIOQuester where it overrides IdleScript's serverMessageInterrupt()<br>
   * <br>
   * This then calls a script-specific method that can be overridden for message handling per script
   *
   * @param message String - Message to check for
   */
  public final void _tradeMessageInterrupt(String message) {
    if (message == null) return;
    logIfDebugging(String.format("Trade: [%s]", message));
    if (script != null) script.tradeMessageInterrupt(message);
  }

  /**
   * A method that is to be overridden in quest scripts for passing along server messages
   *
   * @param message String -- Message content
   */
  public void serverMessageInterrupt(String message) {}

  /**
   * A method that is to be overridden in quest scripts for passing along quest messages
   *
   * @param message String -- Message content
   */
  public void questMessageInterrupt(String message) {}

  /**
   * A method that is to be overridden in quest scripts for passing along private messages
   *
   * @param sender String -- Sender's username
   * @param message String -- Message content
   */
  public void privateMessageInterrupt(String sender, String message) {}

  /**
   * A method that is to be overridden in quest scripts for passing along chat messages
   *
   * @param message String -- Message content
   */
  public void chatMessageInterrupt(String message) {}

  /**
   * A method that is to be overridden in quest scripts for passing along trade messages
   *
   * @param message String -- Message content
   */
  public void tradeMessageInterrupt(String message) {}

  /** Draws the quest paint using PaintBuilder. */
  public void drawPaint() {
    // Docked under the side panel buttons (QuestDock): don't also draw on the game view
    if (QuestDock.isShowingDocked()) return;
    if (QUEST != null && paintBuilder != null && rowBuilder != null) {
      // Colors are based on https://spec.draculatheme.com/#sec-Standard
      int purple = 0xBD93F9;
      int darkGray = 0x282A36;
      int darkerGray = 0x1d1f27;
      int white = 0xF8F8F2;
      int green = 0x50FA7B;
      int yellow = 0xF1FA8C;
      int red = 0xFF5555;

      paintBuilder.setBackgroundColor(darkGray, 255);
      paintBuilder.setBorderColor(purple);

      paintBuilder.addRow(rowBuilder.centeredSingleStringRow(QUEST.getName(), purple, 4));
      String[] outline = QuestSteps.of(QUEST.getName());
      if (outline != null && outline.length > 0) {
        int current = QuestSteps.currentIndex(QUEST.getName(), QUEST_STAGE);
        boolean finished = current >= outline.length;
        if (showQuestSteps) {
          for (int i = 0; i < outline.length; i++) {
            boolean done = finished || i < current;
            int col = done ? green : i == current ? yellow : white;
            String mark = done ? "[done] " : i == current ? "[now] " : "[ ] ";
            paintBuilder.addRow(rowBuilder.centeredSingleStringRow(mark + outline[i], col, 1));
          }
        } else {
          paintBuilder.addRow(
              rowBuilder.centeredSingleStringRow(outline[Math.min(current, outline.length - 1)], yellow, 2));
        }
      }
      paintBuilder.addRow(rowBuilder.centeredSingleStringRow("Now: " + CURRENT_QUEST_STEP, yellow, 2));
      paintBuilder.addRow(
          rowBuilder.centeredSingleStringRow("Type ::skip to skip this quest", white, 1));
      for (scripting.idlescript.SeattaScript.AIOQuester.models.QuestRequirement req :
          QuestSupplies.beforeStart(QUEST)) {
        int have = QuestSupplies.carried(req);
        int col = have >= req.amount ? green : have == 0 ? red : yellow;
        paintBuilder.addRow(
            rowBuilder.centeredSingleStringRow(
                req.label + " " + have + "/" + req.amount, col, 1));
      }
      String queueStatus = QUEUE_STATUS;
      if (queueStatus != null)
        paintBuilder.addRow(rowBuilder.centeredSingleStringRow(queueStatus, white, 1));
      if (QUEST.getStagesTotal() > 0) {
        paintBuilder.addSpacerRow(4);
        paintBuilder.addRow(
            rowBuilder.progressBarRow(
                QUEST_STAGE == -1 ? QUEST.getStagesTotal() + 1 : QUEST_STAGE,
                QUEST.getStagesTotal() + 1,
                darkerGray,
                green,
                darkGray,
                20,
                paintBuilder.getWidth() - 40,
                18,
                true,
                false,
                "Run Time: " + paintBuilder.stringRunTime,
                white));
        if (STEP_ITEMS != null && STEP_ITEMS.length > 0) {
          paintBuilder.addSpacerRow(8);
        }
      } else {
        paintBuilder.addSpacerRow(4);
        paintBuilder.addRow(
            rowBuilder.centeredSingleStringRow(
                "Run Time: " + paintBuilder.stringRunTime, white, 1));
      }

      if (STEP_ITEMS != null && STEP_ITEMS.length > 0) {
        paintBuilder.addSpacerRow(4);

        for (int[] stepItem : STEP_ITEMS) {

          String itemName = c.getItemName(stepItem[0]);
          itemName = itemName.substring(0, 1).toUpperCase() + itemName.substring(1);
          String amount = c.getInventoryItemCount(stepItem[0]) + "/" + stepItem[1];

          String[] strings = {itemName, amount};
          int stringColor =
              c.getInventoryItemCount(stepItem[0]) >= stepItem[1]
                  ? green
                  : c.getInventoryItemCount(stepItem[0]) == 0 ? red : yellow;
          int[] colors = {stringColor, stringColor};
          int[] spacing = {
            4,
            paintBuilder.getWidth()
                - c.getStringWidth(amount, 1)
                - (amount.charAt(amount.length() - 1) == '1' ? 3 : 4)
                - 4
          };
          paintBuilder.addRow(rowBuilder.multipleStringRow(strings, colors, spacing, 1));
        }
      }
      paintBuilder.draw();
    }
  }
}
