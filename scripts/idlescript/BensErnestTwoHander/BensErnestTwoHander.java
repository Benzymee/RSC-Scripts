package scripting.idlescript.BensErnestTwoHander;

import bot.Main;
import bot.tabs.MassStart;
import bot.tabs.MassStartSettings;
import bot.ui.scriptselector.models.Category;
import bot.ui.scriptselector.models.Parameter;
import bot.ui.scriptselector.models.ScriptInfo;
import controller.Controller;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import models.entities.ItemId;
import models.entities.SkillId;
import orsc.ORSCharacter;
import scripting.idlescript.IdleScript;
import controller.WebWalker.CustomLabelHandlers;
import scripting.idlescript.SeattaScript.AIOQuester.QuestWalker;

/**
 * Ben's Ernest -> 2H Goblins.
 *
 * <ol>
 *   <li>Ernest the Chicken (Ben's AIOQuester logic, started from wherever the account is), keeping
 *       the 300 coins reward.
 *   <li>Gaius' Two-Handed Sword Shop in Taverley: buys one Iron 2-handed Sword and wields it
 *       (skipped when the sword is already owned: worn, carried or banked).
 *   <li>Falador West bank: banks everything except the sword.
 *   <li>Lumbridge goblins: trains with the chosen style (default Controlled). After a death it
 *       re-wields the sword in Lumbridge and walks back; a lost sword is re-bought if the coins
 *       allow, otherwise the script stops with a message.
 * </ol>
 *
 * <p>Server data (OpenRSC): Gaius npc 228 at 379,501 (house 377,500..381,503, members), talk menu
 * "Let's trade"; Iron 2-handed Sword item 77, base price 280, no level requirement; coins 10;
 * Falador West bank 328,549..334,557; goblins npc 62 (level 7, not aggressive) around the
 * Lumbridge goblin hut 117,630; Lumbridge respawn 122,648.
 */
public class BensErnestTwoHander extends IdleScript implements MassStartSettings {
  static final String DISPLAY_NAME = "Ben's Ernest -> 2H Goblins";
  private static final Category[] CATEGORIES =
      new Category[] {Category.QUESTING, Category.COMBAT, Category.MELEE};
  private static final Parameter[] PARAMETERS =
      new Parameter[] {
        new Parameter("style=controlled", "controlled (default), strength, attack or defence."),
        new Parameter("area=hut", "hut (goblin hut, default) or field (south-east field)."),
        new Parameter("skipquest", "Do not do Ernest the Chicken (go straight to the sword)."),
        new Parameter("stop=0", "Stop when Attack, Strength and Defence all reach this level."),
        new Parameter("gui", "Show the setup window even when parameters are given.")
      };
  public static final ScriptInfo info =
      new ScriptInfo(
          CATEGORIES,
          "Benzyme",
          "Does Ernest the Chicken (keeps the 300 coins), buys an Iron 2-handed Sword from Gaius in "
              + "Taverley and wields it, banks everything else at Falador West, then trains on the "
              + "Lumbridge goblins. After a death it re-wields the sword and walks back. Needs "
              + "Ben's AIOQuester in the scripts folder. Parameters skip the setup window.",
          PARAMETERS,
          DISPLAY_NAME);

  // ---- ids / places (verified against the OpenRSC server data) ---------------------------------
  static final int SWORD = ItemId.IRON_2_HANDED_SWORD.getId(); // 77
  static final int COINS = ItemId.COINS.getId(); // 10
  static final int GAIUS = 228;
  static final int[] GAIUS_SPOT = {379, 502};
  static final int[] FALADOR_WEST_BANK = {331, 553};
  /** Lumbridge respawn tile. */
  static final int SPAWN_X = 122, SPAWN_Y = 648;

  static final int SWORD_BASE_PRICE = 280;
  /** Coins taken along for the shop (the price rises a little when Gaius is low on stock). */
  static final int COINS_WANTED = 400;

  static final String[] AREA_KEYS = {"hut", "field"};
  static final String[] AREA_LABELS = {
    "Goblin hut, east of the castle (117,630) - busiest", "Field south-east of the hut (104,646)"
  };
  static final int[][] AREAS = {{117, 630, 15}, {104, 646, 11}};
  static final int[] GOBLIN_IDS = {62, 4, 153, 154};

  static final String[] STYLES = {"controlled", "strength", "attack", "defence"};
  static final String[] STYLE_LABELS = {
    "Controlled", "Strength (Aggressive)", "Attack (Accurate)", "Defence (Defensive)"
  };
  static final int[] SKILLS = {
    SkillId.ATTACK.getId(), SkillId.DEFENSE.getId(), SkillId.STRENGTH.getId(), SkillId.HITS.getId()
  };
  static final String[] SKILL_NAMES = {"Attack", "Defence", "Strength", "Hits"};

  // ---- settings: plain fields (mass start copies them to followers) -----------------------------
  private String style = "controlled";
  private String area = "hut";
  private boolean skipQuest = false;
  private int stopLevel = 0;
  private boolean configured = false;

  // ---- runtime ---------------------------------------------------------------------------------
  enum Phase {
    SETUP("Setup"),
    QUEST("Ernest the Chicken"),
    SWORD("Getting the 2-handed sword"),
    BANK("Banking at Falador West"),
    GOBLINS("Training on goblins"),
    DONE("Finished");

    final String label;

    Phase(String label) {
      this.label = label;
    }
  }

  private volatile Phase phase = Phase.SETUP;
  private volatile String status = "Starting";
  private volatile boolean waitingForGui;
  private volatile long startedAt;
  private final int[] xpStart = new int[SKILLS.length];
  private volatile boolean xpCaptured;
  private volatile int kills, deaths, swordsBought;
  private volatile long lastDeathAt;
  private volatile boolean needRegear;
  private volatile long lastBusyAt;
  private ErnestNoBank ernest;
  private QuestWalker walker;
  private int prevX = -1, prevY = -1;
  // goblin target tracking (Benzyme's Goblin Killer logic)
  private int targetIndex = -1, targetNpcId = -1, targetX, targetY;
  private boolean engaged;
  private long attackAt;
  private boolean swordLostWarned;

  @Override
  public int start(String[] parameters) {
    startedAt = System.currentTimeMillis();
    try {
      if (!configure(parameters)) return stopWith(null);
      run();
    } catch (StopScript s) {
      return stopWith(s.getMessage());
    } catch (ErnestNoBank.ErnestEnd e) {
      return stopWith("Unexpected quest stop: " + e.getMessage());
    }
    return stopWith(null);
  }

  private int stopWith(String message) {
    if (message != null) {
      status = message;
      controller.log("Ben's Ernest -> 2H: " + message, "red");
      controller.displayMessage("@red@Ben's Ernest -> 2H: @whi@" + message);
    }
    if (controller.isRunning()) controller.stop();
    return 1000;
  }

  /** Thrown to stop the whole script with a message. */
  static final class StopScript extends RuntimeException {
    StopScript(String message) {
      super(message, null, false, false);
    }
  }

  // =============================================================================================
  // Setup (window / parameters / mass start)
  // =============================================================================================

  private boolean configure(String[] parameters) {
    boolean follower = configured; // plain fields copied from the mass start leader
    boolean gui = false;
    if (!follower) {
      gui = readParameters(parameters);
      if (MassStart.isLeaderSetup()) gui = true; // the leader always shows the window
      if (GraphicsEnvironment.isHeadless()) gui = false;
    }
    if (gui) {
      waitingForGui = true;
      status = "Waiting for the setup window";
      controller.setStatus("Ben's Ernest -> 2H: waiting for the setup window");
      SwingUtilities.invokeLater(
          () ->
              SetupWindow.open(
                  style,
                  area,
                  skipQuest,
                  stopLevel,
                  (s, a, skip, stop) -> {
                    style = s;
                    area = a;
                    skipQuest = skip;
                    stopLevel = stop;
                    configured = true;
                    waitingForGui = false;
                  },
                  () -> waitingForGui = false));
      while (waitingForGui && controller.isRunning()) controller.sleep(250);
      if (!configured || !controller.isRunning()) return false;
    }
    configured = true;
    controller.setStatus("Ben's Ernest -> 2H Goblins");
    controller.log(
        (follower ? "Mass start: " : "")
            + "Ben's Ernest -> 2H: style "
            + style
            + ", area "
            + area
            + (skipQuest ? ", skip quest" : "")
            + (stopLevel > 0 ? ", stop at " + stopLevel : ""),
        "gre");
    return true;
  }

  /** @return true when the setup window should be shown (no parameters, or "gui"). */
  private boolean readParameters(String[] parameters) {
    boolean any = false, gui = false;
    if (parameters != null) {
      for (String raw : parameters) {
        if (raw == null) continue;
        for (String t : raw.trim().toLowerCase(Locale.ROOT).split("[\\s,]+")) {
          if (t.isEmpty()) continue;
          any = true;
          if (t.equals("gui")) gui = true;
          else if (t.equals("nogui")) gui = false;
          else if (t.equals("skipquest") || t.equals("noquest")) skipQuest = true;
          else if (t.equals("quest")) skipQuest = false;
          else if (t.startsWith("style=")) style = normaliseStyle(t.substring(6));
          else if (t.startsWith("area=")) area = t.substring(5).startsWith("f") ? "field" : "hut";
          else if (t.startsWith("stop=")) {
            try {
              stopLevel = Math.max(0, Math.min(99, Integer.parseInt(t.substring(5))));
            } catch (NumberFormatException ignored) {
              // keep
            }
          }
        }
      }
    }
    return gui || !any;
  }

  static String normaliseStyle(String v) {
    v = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
    if (v.startsWith("str") || v.startsWith("agg")) return "strength";
    if (v.startsWith("att") || v.startsWith("acc")) return "attack";
    if (v.startsWith("def")) return "defence";
    return "controlled";
  }

  @Override
  public String[] massStartArguments() {
    List<String> out = new ArrayList<>();
    out.add("style=" + style);
    out.add("area=" + area);
    if (skipQuest) out.add("skipquest");
    out.add("stop=" + stopLevel);
    return out.toArray(new String[0]);
  }

  // =============================================================================================
  // Phases
  // =============================================================================================

  private void run() {
    controller.setAutoLogin(true);
    controller.setBatchBars(true);
    while (controller.isRunning() && !controller.isLoggedIn()) controller.sleep(1000);
    controller.hideWelcomeScreen();
    controller.sleep(1500);
    ernest = new ErnestNoBank();
    ernest.prepare();
    walker = new QuestWalker(controller);
    captureXp();
    applyStyle();

    phase = decideStartPhase();
    while (controller.isRunning() && phase != Phase.DONE) {
      switch (phase) {
        case QUEST:
          doQuest();
          break;
        case SWORD:
          doSword();
          break;
        case BANK:
          doBank();
          break;
        case GOBLINS:
          doGoblins();
          break;
        default:
          phase = Phase.DONE;
      }
    }
    if (phase == Phase.DONE) status = "Finished";
  }

  private Phase decideStartPhase() {
    int stage = questStage();
    if (skipQuest || stage == -1) {
      controller.log(
          "Ben's Ernest -> 2H: " + (stage == -1 ? "Ernest already complete" : "quest skipped"),
          "cya");
      return swordWorn() || swordCarried() ? (onlySwordLeft() ? Phase.GOBLINS : Phase.BANK)
          : Phase.SWORD;
    }
    return Phase.QUEST;
  }

  private int questStage() {
    return controller.getQuestStage(
        scripting.idlescript.SeattaScript.AIOQuester.models.Quest.ERNEST_THE_CHICKEN
            .getQuestDef()
            .getId());
  }

  // ---- 1. Ernest the Chicken --------------------------------------------------------------------

  private int questTries = 0;

  private void doQuest() {
    status = "Ernest the Chicken";
    if (questStage() == -1) {
      finishQuest();
      return;
    }
    ernest.prepare();
    try {
      ernest.run();
    } catch (ErnestNoBank.ErnestEnd e) {
      if (!controller.isRunning()) return;
      if (questStage() == -1) {
        finishQuest();
        return;
      }
      questTries++;
      controller.log(
          "Ben's Ernest -> 2H: quest step stopped (" + e.getMessage() + "), retry " + questTries,
          "yel");
      if (questTries > 6) throw new StopScript("Ernest the Chicken kept failing: " + e.getMessage());
      controller.sleep(2000 + (int) (Math.random() * 3000));
      return;
    }
    if (controller.isRunning() && questStage() == -1) finishQuest();
  }

  private void finishQuest() {
    status = "Quest done - leaving the manor";
    for (int i = 0; i < 3 && controller.isRunning(); i++) {
      try {
        ernest.prepare();
        ernest.leaveManor();
        break;
      } catch (ErnestNoBank.ErnestEnd ignored) {
        controller.sleep(1500);
      }
    }
    controller.log(
        "Ben's Ernest -> 2H: Ernest the Chicken complete, coins carried: "
            + controller.getInventoryItemCount(COINS),
        "gre");
    phase = Phase.SWORD;
  }

  // ---- 2. The sword ------------------------------------------------------------------------------

  private int swordTries = 0;

  private void doSword() {
    if (swordWorn()) {
      phase = onlySwordLeft() ? Phase.GOBLINS : Phase.BANK;
      return;
    }
    if (swordCarried()) {
      wieldSword();
      return;
    }
    if (++swordTries > 8) throw new StopScript("Could not get the Iron 2-handed Sword (8 tries)");

    // Falador West bank is on the way from Draynor/Lumbridge to Taverley: check the bank for a
    // sword and take coins along if needed.
    int coins = controller.getInventoryItemCount(COINS);
    status = "Checking the bank for a sword / coins";
    if (!walkTo(FALADOR_WEST_BANK[0], FALADOR_WEST_BANK[1], 2, "Falador West bank")) return;
    if (!openBank()) return;
    int bankSwords = controller.getBankItemCount(SWORD);
    if (bankSwords > 0) {
      controller.log("Ben's Ernest -> 2H: sword found in the bank, taking it", "gre");
      controller.withdrawItem(SWORD, 1);
      waitFor(() -> controller.getInventoryItemCount(SWORD) > 0, 5000);
      controller.closeBank();
      controller.sleep(800);
      wieldSword();
      return;
    }
    int bankCoins = Math.max(0, controller.getBankItemCount(COINS));
    if (coins < COINS_WANTED && bankCoins > 0) {
      int take = Math.min(bankCoins, COINS_WANTED - coins);
      status = "Withdrawing " + take + " coins";
      controller.withdrawItem(COINS, coins + take);
      final int before = coins;
      waitFor(() -> controller.getInventoryItemCount(COINS) > before, 5000);
      coins = controller.getInventoryItemCount(COINS);
    }
    controller.closeBank();
    controller.sleep(800);
    if (coins < SWORD_BASE_PRICE) {
      throw new StopScript(
          "No Iron 2-handed Sword and only "
              + coins
              + " coins (bank included); it costs about "
              + SWORD_BASE_PRICE
              + ". Stopping.");
    }
    buySword();
  }

  private void buySword() {
    status = "Walking to Gaius in Taverley";
    if (!crossTaverleyGate(true)) return;
    if (!walkTo(GAIUS_SPOT[0], GAIUS_SPOT[1], 1, "Gaius' sword shop")) return;
    for (int attempt = 0; attempt < 30 && controller.isRunning(); attempt++) {
      if (!controller.isInShop()) {
        status = "Talking to Gaius";
        try {
          ernest.prepare();
          ernest.runDialogue(GAIUS, new String[] {"Let's trade"}, true, 4);
        } catch (ErnestNoBank.ErnestEnd e) {
          controller.log("Ben's Ernest -> 2H: Gaius: " + e.getMessage(), "yel");
          walkTo(GAIUS_SPOT[0], GAIUS_SPOT[1], 1, "Gaius' sword shop");
          continue;
        }
        waitFor(() -> controller.isInShop(), 5000);
        if (!controller.isInShop()) {
          politeWait("Gaius");
          continue;
        }
      }
      int stock = controller.getShopItemCount(SWORD);
      int coins = controller.getInventoryItemCount(COINS);
      if (stock < 1 || coins < SWORD_BASE_PRICE) {
        status =
            stock < 1
                ? "Gaius is out of iron 2-handers - waiting for stock"
                : "Only " + coins + " coins - waiting";
        controller.sleep(3000 + (int) (Math.random() * 3000));
        if (attempt > 25) {
          controller.closeShop();
          throw new StopScript(status + " for too long. Stopping.");
        }
        continue;
      }
      status = "Buying an Iron 2-handed Sword";
      int before = controller.getInventoryItemCount(SWORD);
      controller.shopBuy(SWORD);
      if (waitFor(() -> controller.getInventoryItemCount(SWORD) > before, 5000)) {
        swordsBought++;
        controller.sleep(600);
        int paid = coins - controller.getInventoryItemCount(COINS);
        controller.log("Ben's Ernest -> 2H: bought an Iron 2-handed Sword for " + paid + " coins", "gre");
        break;
      }
      // not enough coins for the current price, or someone else bought the last one
      controller.sleep(2500 + (int) (Math.random() * 2500));
    }
    if (controller.isInShop()) {
      controller.closeShop();
      controller.sleep(800);
    }
    if (swordCarried()) wieldSword();
  }

  private void wieldSword() {
    for (int i = 0; i < 4 && controller.isRunning() && !swordWorn(); i++) {
      status = "Wielding the Iron 2-handed Sword";
      if (!swordCarried()) return;
      if (controller.isInBank()) controller.closeBank();
      if (controller.isInShop()) controller.closeShop();
      controller.equipItemById(SWORD);
      waitFor(this::swordWorn, 3000);
    }
    if (swordWorn()) {
      swordLostWarned = false;
      if (phase == Phase.SWORD) phase = onlySwordLeft() ? Phase.GOBLINS : Phase.BANK;
    }
  }

  // ---- 3. Bank everything except the sword -------------------------------------------------------

  private int bankTries = 0;

  private void doBank() {
    if (!swordWorn()) {
      if (swordCarried()) wieldSword();
      else {
        phase = Phase.SWORD;
        return;
      }
    }
    if (onlySwordLeft()) {
      phase = Phase.GOBLINS;
      return;
    }
    if (++bankTries > 8) throw new StopScript("Could not bank at Falador West (8 tries)");
    status = "Walking to Falador West bank";
    if (!crossTaverleyGate(false)) return;
    if (!walkTo(FALADOR_WEST_BANK[0], FALADOR_WEST_BANK[1], 2, "Falador West bank")) return;
    if (!openBank()) return;
    status = "Banking everything except the sword";
    for (int pass = 0; pass < 4 && controller.isInBank() && !onlySwordLeft(); pass++) {
      int[] ids = controller.getInventoryItemIds();
      for (int id : ids) {
        if (id <= 0 || id == SWORD || !controller.isInBank()) continue;
        int n = controller.getInventoryItemCount(id);
        if (n > 0) {
          controller.depositItem(id, n);
          controller.sleep(350);
        }
      }
      controller.sleep(900);
    }
    controller.closeBank();
    controller.sleep(800);
    if (onlySwordLeft()) {
      controller.log("Ben's Ernest -> 2H: banked everything except the sword", "gre");
      phase = Phase.GOBLINS;
    }
  }

  // ---- 4. Goblins --------------------------------------------------------------------------------

  private void doGoblins() {
    int[] a = AREAS[areaIndex()];
    while (controller.isRunning() && phase == Phase.GOBLINS) {
      int delay = goblinTick(a);
      controller.sleep(Math.max(80, delay));
    }
  }

  private int goblinTick(int[] a) {
    if (controller.getNeedToMove()) {
      controller.moveCharacter();
      return 400;
    }
    if (controller.isAuthentic() && controller.getShouldSleep()) {
      status = "Sleeping";
      controller.sleepHandler(true);
      return 600;
    }
    noteDeathByPosition();
    if (stopLevel > 0
        && controller.getBaseStat(SkillId.ATTACK.getId()) >= stopLevel
        && controller.getBaseStat(SkillId.STRENGTH.getId()) >= stopLevel
        && controller.getBaseStat(SkillId.DEFENSE.getId()) >= stopLevel) {
      controller.log("Ben's Ernest -> 2H: reached level " + stopLevel + ", stopping", "gre");
      phase = Phase.DONE;
      return 0;
    }
    applyStyle();

    if (controller.isInCombat()) {
      if (targetIndex < 0) adoptOpponent();
      if (targetIndex >= 0) {
        engaged = true;
        ORSCharacter npc = findTarget();
        if (npc != null) {
          targetX = tileX(npc);
          targetY = tileY(npc);
        }
      }
      status = "Fighting a goblin";
      return 300;
    }

    // ALWAYS have the sword on before a new fight.
    if (!swordWorn()) {
      clearTarget();
      if (swordCarried()) {
        status = "Re-wielding the Iron 2-handed Sword";
        controller.equipItemById(SWORD);
        waitFor(this::swordWorn, 3000);
        return 300;
      }
      if (!swordLostWarned) {
        controller.log("Ben's Ernest -> 2H: the sword is gone - getting a new one", "red");
        swordLostWarned = true;
      }
      swordTries = 0;
      phase = Phase.SWORD;
      return 0;
    }
    needRegear = false;

    // Did our goblin die?
    if (targetIndex >= 0) {
      ORSCharacter npc = findTarget();
      if (npc == null) {
        if (engaged) kills++;
        clearTarget();
      } else {
        targetX = tileX(npc);
        targetY = tileY(npc);
        long since = System.currentTimeMillis() - attackAt;
        if (!engaged && since < 4000 && !controller.isNpcInCombat(npc.serverIndex)) {
          status = "Attacking a goblin";
          return 250;
        }
        // The goblin entered combat a tick before we did: that is our fight starting.
        if (!engaged
            && since < 6000
            && controller.isNpcInCombat(npc.serverIndex)
            && controller.distanceTo(targetX, targetY) <= 1) {
          status = "Attacking a goblin";
          return 250;
        }
        clearTarget();
      }
    }

    // Back to the goblins (also the walk back after a death).
    if (controller.distanceTo(a[0], a[1]) > a[2]) {
      status =
          System.currentTimeMillis() - lastDeathAt < 180_000
              ? "Died - walking back to the goblins"
              : "Walking to the Lumbridge goblins";
      walkTo(a[0], a[1], 3, "the Lumbridge goblins");
      return 300;
    }

    ORSCharacter goblin = nearestFreeGoblin(a);
    if (goblin == null) {
      status = "Waiting for a free goblin";
      if (controller.distanceTo(a[0], a[1]) > 4 && !controller.isCurrentlyWalking())
        controller.walkToAsync(a[0], a[1], 2);
      return 600 + (int) (Math.random() * 400);
    }
    targetIndex = goblin.serverIndex;
    targetNpcId = goblin.npcId;
    targetX = tileX(goblin);
    targetY = tileY(goblin);
    engaged = false;
    attackAt = System.currentTimeMillis();
    status = "Attacking a goblin";
    controller.attackNpc(goblin.serverIndex);
    return 600;
  }

  /** In combat without a tracked target: the goblin fighting next to us is the one. */
  private void adoptOpponent() {
    ORSCharacter best = null;
    for (ORSCharacter npc : controller.getNpcs()) {
      if (npc == null || !isGoblin(npc.npcId) || !controller.isNpcInCombat(npc.serverIndex)) continue;
      if (controller.distanceTo(tileX(npc), tileY(npc)) <= 1) {
        best = npc;
        break;
      }
    }
    if (best != null) {
      targetIndex = best.serverIndex;
      targetNpcId = best.npcId;
      targetX = tileX(best);
      targetY = tileY(best);
      attackAt = System.currentTimeMillis();
    }
  }

  private ORSCharacter findTarget() {
    for (ORSCharacter npc : controller.getNpcs())
      if (npc != null && npc.serverIndex == targetIndex && npc.npcId == targetNpcId) return npc;
    return null;
  }

  private void clearTarget() {
    targetIndex = -1;
    targetNpcId = -1;
    engaged = false;
  }

  /** Nearest goblin inside the area that nobody is fighting (fixed world-tile comparisons). */
  private ORSCharacter nearestFreeGoblin(int[] a) {
    ORSCharacter best = null;
    int bestDist = Integer.MAX_VALUE;
    for (ORSCharacter npc : controller.getNpcs()) {
      if (npc == null || !isGoblin(npc.npcId)) continue;
      int x = tileX(npc), y = tileY(npc);
      if (controller.distance(x, y, a[0], a[1]) > a[2]) continue;
      if (controller.isNpcInCombat(npc.serverIndex)) continue;
      int d = controller.distanceTo(x, y);
      if (d < bestDist) {
        bestDist = d;
        best = npc;
      }
    }
    return best;
  }

  private boolean isGoblin(int id) {
    for (int g : GOBLIN_IDS) if (g == id) return true;
    try {
      String n = controller.getNpcName(id);
      return n != null && n.trim().equalsIgnoreCase("goblin");
    } catch (Throwable ignored) {
      return false;
    }
  }

  private int tileX(ORSCharacter npc) {
    return controller.convertX(npc.currentX);
  }

  private int tileY(ORSCharacter npc) {
    return controller.convertZ(npc.currentZ);
  }

  // ---- deaths ------------------------------------------------------------------------------------

  private void noteDeathByPosition() {
    int x = controller.currentX(), y = controller.currentY();
    if (prevX >= 0
        && controller.distance(prevX, prevY, x, y) >= 12
        && controller.distance(x, y, SPAWN_X, SPAWN_Y) <= 6
        && System.currentTimeMillis() - lastDeathAt > 15000) onDeath();
    prevX = x;
    prevY = y;
  }

  private void onDeath() {
    deaths++;
    lastDeathAt = System.currentTimeMillis();
    needRegear = true;
    clearTarget();
    status = "Died - re-wielding the sword in Lumbridge";
    controller.log("Ben's Ernest -> 2H: died (" + deaths + "), re-wielding and walking back", "yel");
  }

  // =============================================================================================
  // Helpers
  // =============================================================================================

  private int areaIndex() {
    for (int i = 0; i < AREA_KEYS.length; i++) if (AREA_KEYS[i].equals(area)) return i;
    return 0;
  }

  boolean swordWorn() {
    try {
      if (controller.isItemIdEquipped(SWORD)) return true;
    } catch (Throwable ignored) {
      // fall through
    }
    try {
      for (int id : controller.getEquippedItemIds()) if (id == SWORD) return true;
    } catch (Throwable ignored) {
      // no equipment array
    }
    return false;
  }

  boolean swordCarried() {
    return controller.getUnnotedInventoryItemCount(SWORD) > 0;
  }

  /** True when the inventory holds nothing but (at most) the sword. */
  private boolean onlySwordLeft() {
    int[] ids = controller.getInventoryItemIds();
    if (ids == null) return true;
    for (int id : ids) if (id > 0 && id != SWORD) return false;
    return true;
  }

  private void applyStyle() {
    int mode = 0;
    if ("strength".equals(style)) mode = 1;
    else if ("attack".equals(style)) mode = 2;
    else if ("defence".equals(style)) mode = 3;
    if (controller.getFightMode() != mode) controller.setFightMode(mode);
  }

  /**
   * Crosses the Falador - Taverley north gate (341,487 / 342,487) with the client's own WebWalker
   * gate handler. QuestWalker's crossing of this gate from the Taverley side kept retrying on the
   * test server, so the script walks up to the gate and crosses it itself in both directions.
   *
   * @param toTaverley true when heading into Taverley (west, x >= 342)
   */
  private boolean crossTaverleyGate(boolean toTaverley) {
    int x = controller.currentX(), y = controller.currentY();
    boolean inTaverley = x >= 342 && x <= 400 && y >= 440 && y <= 530;
    if (toTaverley == inTaverley) return true; // already on the right side
    if (!toTaverley && !inTaverley) return true;
    if (toTaverley && (y > 600 || x < 200)) {
      // far away (e.g. Lumbridge): get to Falador first so the gate is the way in
      if (!walkTo(FALADOR_WEST_BANK[0], FALADOR_WEST_BANK[1], 3, "Falador")) return false;
    }
    status = "Walking to the Taverley gate";
    int sx = toTaverley ? 340 : 343;
    if (!walkTo(sx, 487, 1, "the Taverley gate")) return false;
    for (int i = 0; i < 4 && controller.isRunning(); i++) {
      status = "Passing the Taverley gate";
      try {
        CustomLabelHandlers.northFallyTavGate();
      } catch (RuntimeException e) {
        controller.log("Ben's Ernest -> 2H: gate error " + e, "red");
      }
      controller.sleep(1200);
      boolean through = toTaverley ? controller.currentX() >= 342 : controller.currentX() <= 341;
      if (through) return true;
      controller.sleep(1000 + (int) (Math.random() * 1500));
    }
    controller.log("Ben's Ernest -> 2H: could not pass the Taverley gate, trying the walker", "yel");
    return true; // let QuestWalker try its own way
  }

  /** QuestWalker (A* over the RSC map, opens doors and gates), a few tries. */
  private boolean walkTo(int x, int y, int tol, String what) {
    for (int i = 0; i < 4 && controller.isRunning(); i++) {
      if (Math.max(Math.abs(controller.currentX() - x), Math.abs(controller.currentY() - y)) <= tol)
        return true;
      if (i == 0) status = status.startsWith("Walk") || status.startsWith("Died") ? status : "Walking to " + what;
      try {
        if (walker.walkTo(x, y, tol, 15 * 60_000L)) return true;
      } catch (RuntimeException e) {
        controller.log("Ben's Ernest -> 2H: walk error " + e, "red");
      }
      controller.log("Ben's Ernest -> 2H: walk to " + what + " failed, retrying", "yel");
      controller.sleep(1500 + (int) (Math.random() * 2000));
    }
    return Math.max(Math.abs(controller.currentX() - x), Math.abs(controller.currentY() - y)) <= tol;
  }

  /** Opens the bank with the nearest banker; bounded, waits politely when bankers are busy. */
  private boolean openBank() {
    for (int attempt = 0; attempt < 8 && controller.isRunning() && !controller.isInBank(); attempt++) {
      ORSCharacter banker = controller.getNearestNpcByIds(controller.bankerIds, false);
      if (banker == null) {
        controller.sleep(1500);
        continue;
      }
      status = "Opening the bank";
      long t = System.currentTimeMillis();
      if ("Bank".equalsIgnoreCase(controller.getNpcCommand1(banker.npcId)))
        controller.npcCommand1(banker.serverIndex);
      else {
        controller.talkToNpc(banker.serverIndex);
        if (waitFor(() -> controller.isInOptionMenu(), 8000)) {
          int idx = 0;
          String[] opts = controller.getOptionsMenuText();
          for (int i = 0; opts != null && i < Math.min(controller.getOptionMenuCount(), opts.length); i++)
            if (opts[i] != null && opts[i].toLowerCase(Locale.ROOT).contains("access my bank")) idx = i;
          controller.optionAnswer(idx);
        }
      }
      if (waitFor(() -> controller.isInBank() || lastBusyAt > t, 10000) && lastBusyAt > t) {
        politeWait("a banker");
      }
    }
    return controller.isInBank();
  }

  private void politeWait(String what) {
    status = "Waiting for " + what + " (busy)";
    controller.sleep(2000 + (int) (Math.random() * 4000));
  }

  private boolean waitFor(BooleanSupplier cond, long ms) {
    long end = System.currentTimeMillis() + ms;
    while (System.currentTimeMillis() < end && controller.isRunning()) {
      if (cond.getAsBoolean()) return true;
      controller.sleep(250);
    }
    return cond.getAsBoolean();
  }

  private void captureXp() {
    for (int i = 0; i < SKILLS.length; i++) xpStart[i] = controller.getPlayerExperience(SKILLS[i]);
    xpCaptured = true;
  }

  // =============================================================================================
  // Interrupts and paint
  // =============================================================================================

  @Override
  public void serverMessageInterrupt(String message) {
    if (message == null) return;
    String m = message.toLowerCase(Locale.ROOT);
    if (m.contains("is busy at the moment")) lastBusyAt = System.currentTimeMillis();
    if ((m.contains("oh dear") || m.contains("you are dead"))
        && System.currentTimeMillis() - lastDeathAt > 15000
        && startedAt > 0) onDeath();
    if (ernest != null) ernest._serverMessageInterrupt(message);
  }

  @Override
  public void questMessageInterrupt(String message) {
    if (ernest != null) ernest._questMessageInterrupt(message);
  }

  @Override
  public void chatMessageInterrupt(String message) {
    if (ernest != null) ernest._chatMessageInterrupt(message);
  }

  @Override
  public void tradeMessageInterrupt(String message) {
    if (ernest != null) ernest._tradeMessageInterrupt(message);
  }

  @Override
  public void privateMessageReceivedInterrupt(String sender, String message) {
    if (ernest != null) ernest._privateMessageInterrupt(sender, message);
  }

  @Override
  public void paintInterrupt() {
    try {
      Paint.draw(this);
    } catch (Throwable ignored) {
      // never break the client paint
    }
  }

  /**
   * Status hook read by IdleRSC v15.4.1+ (Accounts Summary, Groups window, headless console, side
   * status panel): shown in brackets after the script name.
   */
  public String getStatusSuffix() {
    switch (phase) {
      case QUEST:
        {
          int st = questStageForPaint();
          int pct = st == -1 ? 100 : Math.max(0, Math.min(99, st * 100 / 3));
          return "Ernest the Chicken " + pct + "%";
        }
      case SWORD:
        return "getting the 2h sword";
      case BANK:
        return "banking";
      case GOBLINS:
        return "goblins: " + kills + " kills, " + deaths + " deaths";
      case DONE:
        return "done";
      default:
        return null;
    }
  }

  // ---- read by Paint ----
  Controller ctl() {
    return controller;
  }

  Phase phase() {
    return phase;
  }

  String status() {
    String s = status;
    if (phase == Phase.QUEST) {
      String q = ErnestNoBank.step();
      if (q != null && !q.isEmpty()) s = q;
    }
    return s;
  }

  int questStageForPaint() {
    try {
      return questStage();
    } catch (Throwable t) {
      return 0;
    }
  }

  int xpGained(int i) {
    return xpCaptured ? Math.max(0, controller.getPlayerExperience(SKILLS[i]) - xpStart[i]) : 0;
  }

  int kills() {
    return kills;
  }

  int deaths() {
    return deaths;
  }

  long runtime() {
    return startedAt == 0 ? 0 : System.currentTimeMillis() - startedAt;
  }

  String styleLabel() {
    for (int i = 0; i < STYLES.length; i++) if (STYLES[i].equals(style)) return STYLE_LABELS[i];
    return "Controlled";
  }

  boolean waitingForGui() {
    return waitingForGui;
  }

  @Override
  public void cleanup() {
    try {
      if (ernest != null) ernest.cleanup();
    } catch (Throwable ignored) {
      // shared AIOQuester state reset
    }
    ernest = null;
    configured = false;
    super.cleanup();
  }
}
