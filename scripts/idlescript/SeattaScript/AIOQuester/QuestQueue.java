package scripting.idlescript.SeattaScript.AIOQuester;

import bot.Main;
import controller.Controller;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import models.entities.ItemId;
import models.entities.Location;
import models.entities.QuestId;
import models.entities.SkillId;
import scripting.idlescript.SeattaScript.AIOQuester.models.Quest;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuestDef;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuitReason;

/**
 * Runs a list of AIOQuester quests one after another.
 *
 * <p>Each quest is checked before it starts and skipped (with a log line) if it is already
 * completed, has no quest script, or its requirements aren't met. Missing inventory items, equipped
 * items or free inventory slots are fixed from the bank first when banking is enabled. A quest that
 * fails (quit reason, error, or timeout) is logged and the queue continues with the next quest. A
 * summary is logged at the end.
 */
public final class QuestQueue {
  private static final Controller c = Main.getController();

  public static final int DEFAULT_TIMEOUT_MINUTES = 60;

  /** Queue settings, from the GUI or the script parameters. */
  public static final class Options {
    public final List<QuestDef> quests = new ArrayList<>();
    public boolean doAllAvailable = false;
    public boolean bankForRequirements = true;
    /** Collect the before-start items, then move on without talking to the quest giver. */
    public boolean gatherOnly = false;
    /** Buy missing before-start items from the Varrock centre auctioneers or a bank clerk. */
    public boolean useAuctionHouse = false;
    /** Queued quests whose row box is ticked. Empty with useAuctionHouse means every quest. */
    public final Set<QuestDef> auctionQuests = new LinkedHashSet<>();
    public int timeoutMinutes = DEFAULT_TIMEOUT_MINUTES;

    /** True when this quest should buy its before-start items at the Auction House. */
    public boolean auctionFor(QuestDef q) {
      if (q == null) return false;
      if (doAllAvailable) return useAuctionHouse;
      if (auctionQuests.contains(q)) return true;
      return useAuctionHouse && auctionQuests.isEmpty();
    }

    public boolean hasSelection() {
      return doAllAvailable || !quests.isEmpty();
    }
  }

  /** Result of parsing the script parameters. */
  public static final class ParsedArgs {
    public final Options options = new Options();
    public boolean debug = false;
    public boolean test = false;
    public final List<String> unknown = new ArrayList<>();
  }

  private enum Result {
    COMPLETED,
    FAILED,
    SKIPPED,
    STOPPED
  }

  private final QuestHandler qh;
  private final Options options;
  private final List<String> completed = new ArrayList<>();
  private final List<String> skipped = new ArrayList<>();
  private final List<String> failed = new ArrayList<>();
  private final List<String> notAttempted = new ArrayList<>();
  private String lastFailure = null;
  private static final String SKIPPED_BY_USER = "skipped by the user";

  public QuestQueue(QuestHandler qh, Options options) {
    this.qh = qh;
    this.options = options;
  }

  // ---------------------------------------------------------------------------------------------
  // Parameter parsing
  // ---------------------------------------------------------------------------------------------

  /**
   * Parses script parameters. Accepts (case-insensitive, comma and/or space separated):
   *
   * <ul>
   *   <li>quests=Cook's Assistant,Sheep Shearer (the "quests=" prefix is optional; names may be
   *       written with or without spaces/punctuation, e.g. cooksassistant)
   *   <li>all - do all available quests
   *   <li>timeout=MINUTES - per-quest time limit (0 = none)
   *   <li>nobank - don't bank to meet item/inventory-space requirements
   *   <li>gather / gatheronly - collect before-start items and do not start the quests
   *   <li>auction / ah - buy missing before-start items at the Auction House
   *   <li>debug, test
   * </ul>
   */
  public static ParsedArgs parseArgs(String[] parameters) {
    ParsedArgs args = new ParsedArgs();
    if (parameters == null || parameters.length == 0) return args;

    // Join with spaces so commas inside tokens stay as hard separators while words split by the
    // script-selector text box (which splits on spaces) can be re-joined into quest names.
    String joined = String.join(" ", parameters);
    Map<String, QuestDef> keys = questKeys();
    Set<QuestDef> selected = new LinkedHashSet<>();

    for (String group : joined.split("[,;]+")) {
      String[] raw = group.trim().split("\\s+");
      List<String> words = new ArrayList<>();
      for (String w : raw) {
        String lw = w.trim().toLowerCase(Locale.ROOT);
        for (String prefix : new String[] {"quests=", "quest=", "q="}) {
          if (lw.startsWith(prefix)) {
            lw = lw.substring(prefix.length());
            break;
          }
        }
        if (!lw.isEmpty()) words.add(lw);
      }

      int i = 0;
      while (i < words.size()) {
        String word = words.get(i);
        String norm = normalize(word);
        if (handleFlag(word, norm, args)) {
          i++;
          continue;
        }
        // Greedy: longest run of words that forms a quest name
        int matchedEnd = -1;
        QuestDef match = null;
        StringBuilder sb = new StringBuilder();
        for (int j = i; j < words.size(); j++) {
          sb.append(normalize(words.get(j)));
          QuestDef q = keys.get(sb.toString());
          if (q != null) {
            match = q;
            matchedEnd = j;
          }
        }
        if (match != null) {
          selected.add(match);
          i = matchedEnd + 1;
        } else {
          args.unknown.add(word);
          i++;
        }
      }
    }
    args.options.quests.addAll(selected);
    return args;
  }

  private static boolean handleFlag(String word, String norm, ParsedArgs args) {
    switch (norm) {
      case "debug":
        args.debug = true;
        return true;
      case "test":
        args.test = true;
        return true;
      case "all":
      case "doall":
      case "allavailable":
      case "doallavailable":
        args.options.doAllAvailable = true;
        return true;
      case "nobank":
        args.options.bankForRequirements = false;
        return true;
      case "bank":
        args.options.bankForRequirements = true;
        return true;
      case "gather":
      case "gatheronly":
      case "itemsonly":
        args.options.gatherOnly = true;
        return true;
      case "auction":
      case "ah":
      case "auctionhouse":
        args.options.useAuctionHouse = true;
        return true;
      default:
        break;
    }
    if (word.startsWith("gang=") || word.startsWith("gang:")) {
      String gang = word.substring(5).trim().toLowerCase(Locale.ROOT);
      QuestHandler.SHIELD_GANG = gang.startsWith("black") ? "blackarm" : "phoenix";
      return true;
    }
    if (word.startsWith("timeout=") || word.startsWith("timeout:")) {
      try {
        args.options.timeoutMinutes = Math.max(0, Integer.parseInt(word.substring(8).trim()));
      } catch (NumberFormatException e) {
        args.unknown.add(word);
      }
      return true;
    }
    return false;
  }

  private static String normalize(String s) {
    return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
  }

  /** Map of normalized aliases to quests (display name, enum name, without "the"/"quest"). */
  private static Map<String, QuestDef> questKeys() {
    Map<String, QuestDef> keys = new LinkedHashMap<>();
    for (Quest quest : Quest.values()) {
      QuestDef def = quest.getQuestDef();
      if (def.getName() == null) continue;
      for (String base : new String[] {normalize(def.getName()), normalize(quest.name())}) {
        keys.putIfAbsent(base, def);
        if (base.startsWith("the") && base.length() > 3) keys.putIfAbsent(base.substring(3), def);
        if (base.endsWith("quest") && base.length() > 5)
          keys.putIfAbsent(base.substring(0, base.length() - 5), def);
      }
    }
    return keys;
  }

  /** All quests that have a quest script, in the Quest enum order. */
  public static List<QuestDef> allScriptedQuests() {
    List<QuestDef> list = new ArrayList<>();
    for (Quest quest : Quest.values()) {
      QuestDef def = quest.getQuestDef();
      if (def.getScript() != null && def.isQuestDefined()) list.add(def);
    }
    return list;
  }

  // ---------------------------------------------------------------------------------------------
  // Requirement checks
  // ---------------------------------------------------------------------------------------------

  /** Whether the quest is known to be completed already. */
  public static boolean isCompleted(QuestDef q) {
    if (!q.isMiniquest()) return q.getId() > -1 && c.getQuestStage(q.getId()) == -1;
    // Barcrawl can only be detected as done once Scorpion Catcher is completed
    return q.getName().equals(Quest.BARCRAWL.getQuestDef().getName())
        && c.getQuestStage(QuestId.SCORPION_CATCHER.getId()) == -1;
  }

  /**
   * Empty slots the next quest needs. Druidic Ritual is 4 minus meat types already carried
   * (raw or enchanted).
   */
  static int emptySlotsNeeded(QuestDef q) {
    int need = Math.max(0, q.getRequiredEmptyInventorySlots());
    if (q.getId() == QuestId.DRUIDIC_RITUAL.getId()) {
      int meats = 0;
      if (c.getInventoryItemCount(ItemId.RAW_CHICKEN.getId()) > 0
          || c.getInventoryItemCount(ItemId.ENCHANTED_CHICKEN_MEAT.getId()) > 0) meats++;
      if (c.getInventoryItemCount(ItemId.RAW_BEEF.getId()) > 0
          || c.getInventoryItemCount(ItemId.ENCHANTED_BEEF.getId()) > 0) meats++;
      if (c.getInventoryItemCount(ItemId.RAW_RAT_MEAT.getId()) > 0
          || c.getInventoryItemCount(ItemId.ENCHANTED_RAT_MEAT.getId()) > 0) meats++;
      if (c.getInventoryItemCount(ItemId.RAW_BEAR_MEAT.getId()) > 0
          || c.getInventoryItemCount(ItemId.ENCHANTED_BEAR_MEAT.getId()) > 0) meats++;
      need = Math.max(0, 4 - meats);
    }
    return need;
  }

  /**
   * Returns why a quest can't be run right now, or null if it can be started.
   *
   * @param q QuestDef
   * @return String or null
   */
  public static String getSkipReason(QuestDef q) {
    if (q.getScript() == null || !q.isQuestDefined()) return "no AIOQuester script for this quest";
    if (isCompleted(q)) return "already completed";
    List<String> missing = new ArrayList<>();

    if (q.getRequiredQuests() != null) {
      for (Quest rq : q.getRequiredQuests()) {
        QuestDef d = rq.getQuestDef();
        if (d.getId() != -1 && c.getQuestStage(d.getId()) != -1)
          missing.add("quest " + d.getName());
      }
    }
    if (q.getRequiredSkills() != null) {
      for (int[] s : q.getRequiredSkills()) {
        if (c.getBaseStat(s[0]) < s[1])
          missing.add(s[1] + " " + c.getSkillNamesLong()[s[0]] + " (have " + c.getBaseStat(s[0]) + ")");
      }
    }
    // The third scorpion is behind the Taverley dungeon pipe (70 Agility); AIOQuester doesn't do the
    // Dusty Key route through the blue dragons.
    if (q.getId() == QuestId.SCORPION_CATCHER.getId()
        && c.getInventoryItemCount(ItemId.SCORPION_CAGE_ONE_TWO_THREE.getId()) == 0
        && c.getBaseStat(SkillId.AGILITY.getId()) < 70)
      missing.add("70 Agility for the Taverley dungeon pipe (have "
          + c.getBaseStat(SkillId.AGILITY.getId()) + ")");
    if (c.getQuestPoints() < q.getRequiredQuestPoints())
      missing.add(q.getRequiredQuestPoints() + " quest points (have " + c.getQuestPoints() + ")");
    if (q.getRequiredInventoryItems() != null) {
      for (int[] item : q.getRequiredInventoryItems()) {
        int have = c.getInventoryItemCount(item[0]);
        if (have < item[1])
          missing.add(item[1] + "x " + c.getItemName(item[0]) + " in inventory (have " + have + ")");
      }
    }
    if (q.getRequiredEquippedItems() != null) {
      for (int[] item : q.getRequiredEquippedItems()) {
        if (!c.isItemIdEquipped(item[0])) missing.add(c.getItemName(item[0]) + " equipped");
      }
    }
    if (q.getRequiredBankItems() != null) {
      if (QuestHandler.bankItems == null) {
        missing.add("bank contents unknown (open a bank, then restart)");
      } else if (c.getPlayerMode() != 2) {
        for (int[] item : q.getRequiredBankItems()) {
          Integer have = QuestHandler.bankItems.get(item[0]);
          if (have == null || have < item[1])
            missing.add(item[1] + "x " + c.getItemName(item[0]) + " in bank");
        }
      }
    }
    int free = 30 - c.getInventoryItemCount();
    int needSlots = emptySlotsNeeded(q);
    if (needSlots > free)
      missing.add(needSlots + " empty inventory slots (have " + free + ")");

    if (!missing.isEmpty()) return "requirements not met: " + String.join(", ", missing);
    if (!q.isStartable()) return "requirements not met";
    return null;
  }

  // ---------------------------------------------------------------------------------------------
  // Running
  // ---------------------------------------------------------------------------------------------

  /** Runs the queue, then logs a summary. */
  public void run() {
    List<QuestDef> queue = new ArrayList<>();
    if (options.doAllAvailable) {
      queue.addAll(allScriptedQuests());
    } else {
      for (QuestDef q : options.quests) if (q != null && !queue.contains(q)) queue.add(q);
    }
    if (queue.isEmpty()) {
      c.log("AIOQuester: no quests selected, nothing to do.", "red");
      return;
    }

    List<String> names = new ArrayList<>();
    for (QuestDef q : queue) names.add(q.getName());
    c.log(
        String.format(
            "AIOQuester queue (%d): %s%s",
            queue.size(),
            String.join(", ", names),
            options.timeoutMinutes > 0
                ? " | timeout " + options.timeoutMinutes + " min/quest"
                : " | no timeout"),
        "cya");

    long startTime = System.currentTimeMillis();
    QuestHandler.queueMode = true;
    try {
      for (int i = 0; i < queue.size(); i++) {
        QuestDef q = queue.get(i);
        if (!c.isRunning()) {
          for (int j = i; j < queue.size(); j++) notAttempted.add(queue.get(j).getName());
          break;
        }
        setStatus(i + 1, queue.size());

        if (options.gatherOnly) {
          if (isCompleted(q)) {
            skipped.add(q.getName() + " (already completed)");
            c.log(String.format("[%d/%d] Skipping %s: already completed", i + 1, queue.size(), q.getName()), "yel");
            continue;
          }
          if (QuestSupplies.beforeStart(q).isEmpty()) {
            skipped.add(q.getName() + " (no items to gather before it starts)");
            c.log(
                String.format(
                    "[%d/%d] %s: no items to gather before it starts, quest not started",
                    i + 1, queue.size(), q.getName()),
                "yel");
            continue;
          }
          QuestHandler.resetQuestState(q);
          QuestHandler.questThread = Thread.currentThread();
          List<String> still =
              QuestSupplies.gather(q, options.bankForRequirements, options.auctionFor(q));
          if (still.isEmpty()) completed.add(q.getName() + " (items gathered)");
          else skipped.add(q.getName() + " (items still missing: " + String.join(", ", still) + ")");
          c.log(
              String.format(
                  "[%d/%d] %s: gathered items only, quest not started",
                  i + 1, queue.size(), q.getName()),
              "cya");
          continue;
        }

        int needSlots = emptySlotsNeeded(q);
        if (needSlots > 0) QuestHandler.freeInventorySlots(needSlots);
        String skip = getSkipReason(q);
        if (skip != null && canFixFromBank(q)) {
          prepareFromBank(q);
          if (!c.isRunning()) {
            for (int j = i; j < queue.size(); j++) notAttempted.add(queue.get(j).getName());
            break;
          }
          needSlots = emptySlotsNeeded(q);
          if (needSlots > 0) QuestHandler.freeInventorySlots(needSlots);
          skip = getSkipReason(q);
        }
        if (skip != null) {
          skipped.add(q.getName() + " (" + skip + ")");
          c.log(String.format("[%d/%d] Skipping %s: %s", i + 1, queue.size(), q.getName(), skip), "yel");
          continue;
        }

        if (QuestHandler.skipRequested == q) { // skip pressed while banking for it
          QuestHandler.skipRequested = null;
          skipped.add(q.getName() + " (" + SKIPPED_BY_USER + ")");
          c.log(String.format("[%d/%d] Skipping %s: %s", i + 1, queue.size(), q.getName(), SKIPPED_BY_USER), "yel");
          continue;
        }
        if (options.auctionFor(q)) {
          QuestHandler.resetQuestState(q);
          QuestHandler.questThread = Thread.currentThread();
          QuestSupplies.gather(q, options.bankForRequirements, true);
        }
        c.log(String.format("[%d/%d] Starting %s", i + 1, queue.size(), q.getName()), "cya");
        long questStart = System.currentTimeMillis();
        Result result = runQuest(q);
        String took = formatDuration(System.currentTimeMillis() - questStart);
        switch (result) {
          case SKIPPED:
            skipped.add(q.getName() + " (" + SKIPPED_BY_USER + " after " + took + ")");
            c.log(
                String.format(
                    "[%d/%d] Skipped %s after %s (Skip quest) - continuing with the next quest",
                    i + 1, queue.size(), q.getName(), took),
                "yel");
            break;
          case COMPLETED:
            completed.add(q.getName());
            c.log(String.format("[%d/%d] Completed %s in %s", i + 1, queue.size(), q.getName(), took), "gre");
            if (q.getId() == QuestId.DEMON_SLAYER.getId()) QuestSupplies.depositCarriedFood();
            break;
          case FAILED:
            failed.add(q.getName() + " (" + lastFailure + ")");
            c.log(
                String.format(
                    "[%d/%d] Failed %s after %s: %s - continuing with the next quest",
                    i + 1, queue.size(), q.getName(), took, lastFailure),
                "red");
            break;
          case STOPPED:
          default:
            notAttempted.add(q.getName() + " (stopped mid-quest)");
            for (int j = i + 1; j < queue.size(); j++) notAttempted.add(queue.get(j).getName());
            i = queue.size();
            continue;
        }
        if (i + 1 < queue.size()) betweenQuests();
      }
    } finally {
      QuestHandler.queueMode = false;
      QuestHandler.questThread = null;
      QuestHandler.questDeadline = 0;
      QuestHandler.pendingQuit = null;
    }

    logSummary(System.currentTimeMillis() - startTime);
  }

  private static final int MAX_STAGE_RETRIES = 5;

  /** Runs one quest script and works out how it ended. */
  private Result runQuest(QuestDef q) {
    lastFailure = null;
    QuestHandler.resetQuestState(q);
    if (QuestHandler.paintBuilder != null) QuestHandler.paintBuilder.start(4, 18, 182);
    QuestHandler.questThread = Thread.currentThread();
    QuestHandler.questDeadline =
        options.timeoutMinutes > 0
            ? System.currentTimeMillis() + options.timeoutMinutes * 60_000L
            : 0;

    QuitReason reason = null;
    String error = null;
    QuestHandler script = null;
    // failure probe (tester, keep): stage/time at start and the exception, for the report
    int probeStageBefore = QuestHandler.QUEST_STAGE;
    long probeStartedAt = System.currentTimeMillis();
    Throwable probeThrown = null;
    try {
      c.setBatchBars(true);
      script = q.getScript().getDeclaredConstructor().newInstance();
      qh.script = script;
      int stageRetries = 0;
      int lastRetryStage = Integer.MIN_VALUE;
      while (true) {
        try {
          script.run();
          break;
        } catch (QuestHandler.StageRetryException e) {
          // Count retries per stage: a long quest that advanced since the last retry starts over
          int stageNow = q.isMiniquest() ? 0 : c.getQuestStage(q.getId());
          if (stageNow != lastRetryStage) {
            lastRetryStage = stageNow;
            stageRetries = 0;
          }
          QuestFailureProbe.noteAction("stage retry: " + e.getMessage()); // failure probe (tester, keep)
          if (++stageRetries > MAX_STAGE_RETRIES) {
            error = "quest stage didn't advance after " + MAX_STAGE_RETRIES + " retries";
            break;
          }
          c.log(
              "AIOQuester: " + e.getMessage() + ", retrying (" + stageRetries + "/"
                  + MAX_STAGE_RETRIES + ")",
              "yel");
          if (c.isInOptionMenu()) QuestHandler.hideOptionMenu();
          c.sleep(1280);
        }
      }
    } catch (QuestHandler.QuestAbortException e) {
      reason = e.getReason();
    } catch (ReflectiveOperationException e) {
      error = "could not create quest class " + q.getScript().getSimpleName();
      probeThrown = e;
      Main.logError("AIOQuester: " + error, e);
    } catch (RuntimeException e) {
      error = "error " + e;
      probeThrown = e;
      Main.logError("AIOQuester: quest '" + q.getName() + "' threw an exception", e);
    } finally {
      QuestHandler.questThread = null;
      QuestHandler.questDeadline = 0;
      QuestHandler.pendingQuit = null;
      qh.script = null;
      if (script != null) {
        try {
          script.cleanupScript();
        } catch (RuntimeException e) {
          Main.logError("AIOQuester: cleanupScript failed for " + q.getName(), e);
        }
      }
    }

    boolean skippedByUser = reason == QuitReason.SKIPPED_BY_USER || QuestHandler.skipRequested == q;
    QuestHandler.skipRequested = null;
    boolean done =
        q.isMiniquest()
            ? reason == QuitReason.QUEST_COMPLETED
            : q.getId() > -1 && c.getQuestStage(q.getId()) == -1;
    if (done) {
      QuestHandler.QUEST_STAGE = -1;
      return Result.COMPLETED;
    }
    if (!c.isRunning()) return Result.STOPPED;
    if (skippedByUser) {
      try {
        bot.probe.Probe.recordEvent(
            "aioquester_skip",
            "skipped " + q.getName() + " at " + DraynorManor.describe(c.currentX(), c.currentY()));
      } catch (Throwable ignored) {
        // older client without the probe
      }
      if (c.isInOptionMenu()) QuestHandler.hideOptionMenu();
      return Result.SKIPPED;
    }
    if (reason == QuitReason.TIMED_OUT) {
      lastFailure = "timed out after " + options.timeoutMinutes + " min";
    } else if (reason != null) {
      lastFailure = reason.getMessage();
    } else if (error != null) {
      lastFailure = error;
    } else {
      lastFailure = "quest script ended before the quest was completed";
    }
    QuestFailureProbe.report(
        q, reason, error, probeThrown, lastFailure, probeStageBefore, probeStartedAt); // tester, keep
    return Result.FAILED;
  }

  /** Resets interfaces/state between quests so the next quest starts clean. */
  private void betweenQuests() {
    if (!c.isRunning()) return;
    QuestHandler.CURRENT_QUEST_STEP = "Preparing next quest";
    QuestHandler.STEP_ITEMS = null;
    try {
      QuestHandler.returnToGroundFloor();
    } catch (RuntimeException e) {
      QuestHandler.rethrowAbort(e);
      Main.logError("AIOQuester: error returning to floor 0 between quests", e);
    }
    try {
      if (c.isBatching()) c.stopBatching();
      if (c.isInShop()) c.closeShop();
      if (c.isInBank()) {
        refreshBankItems();
        c.closeBank();
      }
      if (c.isInOptionMenu()) c.sleep(1280);
      long until = System.currentTimeMillis() + 15_000;
      while (c.isRunning() && c.isCurrentlyWalking() && System.currentTimeMillis() < until)
        c.sleep(640);
      if (c.isRunning() && c.getNeedToMove()) c.moveCharacter();
      if (c.isRunning() && c.getShouldSleep()) c.sleepHandler(true);
      refreshBankItems();
      c.sleep(1920);
    } catch (RuntimeException e) {
      Main.logError("AIOQuester: error while resetting between quests", e);
    }
  }

  private static void refreshBankItems() {
    Map<Integer, Integer> bank = c.getDebuggerBank();
    if (bank != null) QuestHandler.bankItems = bank;
  }

  /**
   * Whether the only unmet requirements are items/equipment/inventory space that a bank trip could
   * fix.
   */
  private boolean canFixFromBank(QuestDef q) {
    if (!options.bankForRequirements || c.getPlayerMode() == 2) return false; // not for UIM
    if (q.getScript() == null || !q.isQuestDefined() || isCompleted(q)) return false;
    if (!q.isDisplayedStartable()) return false; // quests/skills/QP missing
    if (q.getRequiredBankItems() != null && QuestHandler.bankItems == null) return false;
    // If we know the bank contents, make sure the missing items are actually available
    if (QuestHandler.bankItems != null) {
      if (q.getRequiredInventoryItems() != null) {
        for (int[] item : q.getRequiredInventoryItems()) {
          int have = c.getInventoryItemCount(item[0]);
          Integer banked = QuestHandler.bankItems.get(item[0]);
          if (have + (banked == null ? 0 : banked) < item[1]) return false;
        }
      }
      if (q.getRequiredEquippedItems() != null) {
        for (int[] item : q.getRequiredEquippedItems()) {
          if (c.isItemIdEquipped(item[0]) || c.getInventoryItemCount(item[0]) > 0) continue;
          Integer banked = QuestHandler.bankItems.get(item[0]);
          if (banked == null || banked < 1) return false;
        }
      }
    }
    return true;
  }

  /**
   * Walks to the nearest bank, deposits items the quest doesn't need until there's enough room,
   * withdraws required inventory items and equips required equipment. Bounded to five minutes.
   */
  private void prepareFromBank(QuestDef q) {
    c.log("Banking to meet the item/inventory requirements for " + q.getName(), "cya");
    QuestHandler.resetQuestState(q);
    QuestHandler.CURRENT_QUEST_STEP = "Banking for " + q.getName();
    QuestHandler.questThread = Thread.currentThread();
    QuestHandler.questDeadline = System.currentTimeMillis() + 5 * 60_000L;
    try {
      if (c.isBatching()) c.stopBatching();
      if (c.isInShop()) c.closeShop();
      Location bank = Location.getNearestBank();
      if (bank == null) {
        c.log("No reachable bank found", "red");
        return;
      }
      if (!bank.isAtLocation()) Location.walkTowardsNearestBank();
      bank = Location.getNearestBank();
      if (!QuestHandler.running()) return;
      if (bank == null || !bank.isAtLocation()) {
        c.log("Couldn't reach a bank", "red");
        return;
      }
      if (!c.isInBank()) c.openBank();
      waitFor(() -> c.isInBank(), 10_000);
      if (!c.isInBank()) {
        c.log("Couldn't open the bank", "red");
        return;
      }
      refreshBankItems();

      Set<Integer> keep = new LinkedHashSet<>();
      int slotsForWithdrawals = 0;
      if (q.getRequiredInventoryItems() != null) {
        for (int[] item : q.getRequiredInventoryItems()) {
          keep.add(item[0]);
          if (c.getInventoryItemCount(item[0]) < item[1])
            slotsForWithdrawals += c.isItemStackable(item[0]) ? 1 : item[1];
        }
      }
      if (q.getRequiredEquippedItems() != null) {
        for (int[] item : q.getRequiredEquippedItems()) {
          keep.add(item[0]);
          if (!c.isItemIdEquipped(item[0]) && c.getInventoryItemCount(item[0]) == 0)
            slotsForWithdrawals++;
        }
      }
      int needFree = q.getRequiredEmptyInventorySlots() + slotsForWithdrawals;

      // Deposit things the quest doesn't need until there's enough room
      Set<Integer> invIds = new LinkedHashSet<>();
      int[] ids = c.getInventoryItemIds();
      if (ids != null) for (int id : ids) if (id >= 0) invIds.add(id);
      for (int id : invIds) {
        if (30 - c.getInventoryItemCount() >= needFree) break;
        if (keep.contains(id)) continue;
        if (QuestHandler.isKeptQuestItem(id)) continue; // coins, unfinished quest items, caller's keeps
        int count = c.getInventoryItemCount(id);
        if (count <= 0) continue;
        c.depositItem(id, count);
        waitFor(() -> c.getInventoryItemCount(id) == 0, 5_000);
      }

      // Withdraw required inventory items. withdrawItem() requests the full amount and returns
      // early if we already hold that many, so deposit a partial stack first.
      if (q.getRequiredInventoryItems() != null) {
        for (int[] item : q.getRequiredInventoryItems()) {
          int id = item[0];
          int need = item[1];
          int have = c.getInventoryItemCount(id);
          if (have >= need) continue;
          if (have > 0) {
            c.depositItem(id, have);
            waitFor(() -> c.getInventoryItemCount(id) == 0, 5_000);
          }
          c.withdrawItem(id, need);
          waitFor(() -> c.getInventoryItemCount(id) >= need, 5_000);
        }
      }
      List<Integer> toEquip = new ArrayList<>();
      if (q.getRequiredEquippedItems() != null) {
        for (int[] item : q.getRequiredEquippedItems()) {
          int id = item[0];
          if (c.isItemIdEquipped(id)) continue;
          if (c.getInventoryItemCount(id) == 0) {
            c.withdrawItem(id, 1);
            waitFor(() -> c.getInventoryItemCount(id) > 0, 5_000);
          }
          toEquip.add(id);
        }
      }
      refreshBankItems();
      c.closeBank();
      c.sleep(640);
      for (int id : toEquip) {
        int slot = c.getInventoryItemSlotIndex(id);
        if (slot < 0) continue;
        c.equipItem(slot);
        waitFor(() -> c.isItemIdEquipped(id), 5_000);
      }
    } catch (QuestHandler.QuestAbortException e) {
      c.log("Banking for " + q.getName() + " stopped: " + e.getReason().getMessage(), "red");
    } catch (RuntimeException e) {
      Main.logError("AIOQuester: error while banking for " + q.getName(), e);
    } finally {
      QuestHandler.questThread = null;
      QuestHandler.questDeadline = 0;
      QuestHandler.pendingQuit = null;
      if (c.isInBank()) c.closeBank();
    }
  }

  private interface Condition {
    boolean met();
  }

  private static void waitFor(Condition condition, long timeoutMs) {
    long until = System.currentTimeMillis() + timeoutMs;
    c.sleep(640);
    while (QuestHandler.running() && !condition.met() && System.currentTimeMillis() < until)
      c.sleep(320);
  }

  private void setStatus(int index, int total) {
    QuestHandler.QUEUE_STATUS =
        String.format(
            "Queue %d/%d - done %d, skipped %d, failed %d",
            index, total, completed.size(), skipped.size(), failed.size());
  }

  private void logSummary(long elapsedMs) {
    QuestHandler.QUEUE_STATUS =
        String.format(
            "Queue finished - done %d, skipped %d, failed %d",
            completed.size(), skipped.size(), failed.size());
    c.log(
        String.format(
            "AIOQuester queue finished in %s: %d completed, %d skipped, %d failed%s",
            formatDuration(elapsedMs),
            completed.size(),
            skipped.size(),
            failed.size(),
            notAttempted.isEmpty() ? "" : ", " + notAttempted.size() + " not run (script stopped)"),
        "cya");
    c.log("Completed: " + (completed.isEmpty() ? "none" : String.join(", ", completed)), "gre");
    c.log("Skipped: " + (skipped.isEmpty() ? "none" : String.join("; ", skipped)), "yel");
    c.log("Failed: " + (failed.isEmpty() ? "none" : String.join("; ", failed)), "red");
    if (!notAttempted.isEmpty()) c.log("Not run: " + String.join(", ", notAttempted), "red");
  }

  private static String formatDuration(long ms) {
    long s = ms / 1000;
    return s >= 3600
        ? String.format("%dh %02dm", s / 3600, (s % 3600) / 60)
        : String.format("%dm %02ds", s / 60, s % 60);
  }
}
