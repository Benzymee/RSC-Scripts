import bot.Main;
import bot.ui.scriptselector.models.Category;
import bot.ui.scriptselector.models.ScriptInfo;
import controller.Controller;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import orsc.ORSCharacter;
import scripting.idlescript.IdleScript;

/**
 * Bens BattlefieldGnomes - trains melee on the gnome troops on the Khazard battlefield south-east
 * of East Ardougne, banking for food at the East Ardougne south bank.
 *
 * <p>OpenRSC data (Coleslaw): "gnome troop" (npc 409, level 3, attackable, not aggressive) roam
 * the south half of the battlefield, x 640-675 / y 637-675. The north half is full of "Khazard
 * troop" (407, level 28) and a "Khazard commander" (428, level 41), both aggressive to players
 * under combat 57 / 83, so the script only fights gnomes at y 660+ and walks in from the west
 * (bank -> south of the zoo -> past the gnome village maze entrance), never through the troops.
 * Aggressive NPCs on this server only attack from 1 tile away (aggro_range 1).
 *
 * <p>Food: a cooked fish, "Best" (highest-heal cooked fish in the bank, then the next best when it
 * runs out) or None. Never burnt fish. Out of food: stop safely, keep fighting carefully (rest
 * only when HP is critical) or flee and rest at the eat threshold. HP critical with no food always
 * makes it walk away and rest.
 *
 * <p>Script arguments (skip the window, e.g. for the script queue): {@code style=strength
 * food=best amount=5 eatat=50 restto=90 oof=rest bank=on coins=on bones=off bury=off}
 */
public class BensBattlefieldGnomes extends IdleScript {
  public static final ScriptInfo info =
      new ScriptInfo(
          new Category[] {Category.COMBAT, Category.IRONMAN_SUPPORTED},
          "Ben",
          "Trains on the gnome troops on the battlefield south of Ardougne.\n\n"
              + "Fight style, food (a fish or Best = best food in the bank), eat %,\n"
              + "out-of-food behaviour, banking at Ardougne south bank,\n"
              + "loot coins/bones and bury bones.",
          null,
          "Bens BattlefieldGnomes");

  static final int GNOME_TROOP = 409;
  static final int KHAZARD_TROOP = 407;
  static final int KHAZARD_COMMANDER = 428;
  static final int HITS = 3;
  static final int COINS = 10;
  static final int BONES = 20;

  /** Where we fight: gnome troops here are away from the Khazard troops (y <= 657 + 1 tile). */
  static final int FIGHT_X0 = 640, FIGHT_Y0 = 660, FIGHT_X1 = 673, FIGHT_Y1 = 672;
  static final int[] FIGHT_CENTER = {657, 666};
  /** Quiet tile on the route just west of the gnomes (no aggressive NPC within 6 tiles). */
  static final int[] REST_SPOT = {640, 666};
  static final int[] BANK_TILE = {550, 612};

  /**
   * East Ardougne south bank -> gnome troops. Shortest path over AIOQuester's collision map with
   * aggressive NPC boxes costed out: south along the zoo, then east past the gnome village maze
   * entrance (the giant at 624-634,674-684 stays 2+ tiles off) to the south half of the battlefield.
   */
  static final int[][] BANK_TO_GNOMES = {
    {550, 612}, {552, 620}, {562, 620}, {568, 627}, {572, 637}, {582, 646}, {591, 652},
    {601, 657}, {611, 664}, {621, 671}, {630, 666}, {640, 666}, {650, 667}, {657, 666}
  };
  static final int[][] GNOMES_TO_BANK = reversed(BANK_TO_GNOMES);
  /** Bank -> the rest spot (the route up to {640, 666}). */
  static final int[][] BANK_TO_REST = java.util.Arrays.copyOf(BANK_TO_GNOMES, 12);

  /** Cooked fish, best (highest heal on OpenRSC, ItemEdibleHeals.xml) first. Never burnt. */
  static final String[] FOOD_NAMES = {
    "Shark", "Manta ray", "Sea turtle", "Swordfish", "Bass", "Lobster", "Tuna", "Salmon", "Pike",
    "Trout", "Cod", "Mackerel", "Herring", "Sardine", "Shrimp", "Anchovies"
  };
  static final int[] FOOD_IDS = {546, 1191, 1193, 370, 555, 373, 367, 357, 364, 359, 551, 553, 362, 355, 350, 352};
  static final int[] FOOD_HEALS = {20, 20, 20, 14, 13, 12, 10, 9, 8, 7, 7, 6, 5, 4, 3, 1};

  static final String[] STYLES = {"Controlled", "Strength", "Attack", "Defence"};
  /** RSC fight modes: 0 controlled, 1 aggressive (strength), 2 accurate (attack), 3 defensive. */
  static final int[] STYLE_MODES = {0, 1, 2, 3};
  static final String[] OOF = {
    "Stop safely", "Keep fighting carefully (rest when critical)", "Flee and rest at eat %"
  };
  static final int OOF_STOP = 0, OOF_CAREFUL = 1, OOF_REST = 2;
  static final long MIN_RUN_BEFORE_STOP_MS = 15_000;

  private static int[][] reversed(int[][] r) {
    int[][] o = new int[r.length][];
    for (int i = 0; i < r.length; i++) o[i] = r[r.length - 1 - i];
    return o;
  }

  // --- setup window (remembered by SetupMemory, copied by mass start) ---
  int styleIndex = 1;
  /** "Best", "None" or a fish name from FOOD_NAMES. */
  String foodChoice = "Best";
  int foodAmount = 5;
  int eatAtPercent = 50;
  int restToPercent = 90;
  int outOfFood = OOF_REST;
  boolean bankForFood = true;
  boolean lootCoins = true;
  boolean lootBones = false;
  boolean buryBones = false;
  volatile boolean setupDone = false;
  volatile boolean setupCancelled = false;

  // --- run state ---
  long runStartedAt = 0;
  String state = "Starting";
  int kills = 0;
  int eaten = 0;
  int bankTrips = 0;
  int buried = 0;
  int coinsLooted = 0;
  int rests = 0;
  int deaths = 0;
  int lowestHp = Integer.MAX_VALUE;
  boolean bankHasNoFood = false;
  long bankEmptyCheckedAt = 0;
  volatile long fightSeenAt = 0;
  volatile boolean diedFlag = false;
  int[] startXp = new int[4];
  int lastCombatXp = 0;
  String lastFoodTaken = "";

  private void setState(String s) {
    state = s;
    Controller c = c();
    if (c != null) c.setStatus("@cya@" + s);
  }

  private Controller c() {
    return Main.getController();
  }

  private void log(String s) {
    Main.log("[BensBattlefieldGnomes] " + s);
    Controller c = c();
    if (c != null) c.setStatus("@cya@" + s);
  }

  private void notStalled(long ms) {
    try {
      bot.probe.Probe.noteIntentionalWait(ms);
    } catch (Throwable ignored) {
      // older clients have no stall watch
    }
  }

  private boolean waitFor(java.util.function.BooleanSupplier cond, long ms) {
    Controller controller = c();
    long end = System.currentTimeMillis() + ms;
    while (controller.isRunning() && System.currentTimeMillis() < end) {
      if (cond.getAsBoolean()) return true;
      controller.sleep(100);
    }
    return cond.getAsBoolean();
  }

  // ---------------------------------------------------------------------------------------------
  // Main loop
  // ---------------------------------------------------------------------------------------------

  public int start(String[] param) {
    Controller controller = c();
    if (controller == null) return 1000;
    if (!setup(param)) return 1000;
    runStartedAt = System.currentTimeMillis();
    for (int s = 0; s < 4; s++) startXp[s] = controller.getStatXp(s);
    lastCombatXp = combatXp();
    log("Settings: style " + STYLES[styleIndex] + ", food " + foodChoice
        + (isNoFood() ? "" : " x" + foodAmount) + ", eat at " + eatAtPercent + "% HP, rest to " + restToPercent
        + "%, out of food: " + OOF[outOfFood] + ", bank " + (bankForFood ? "on" : "off") + ", loot coins "
        + (lootCoins ? "on" : "off") + ", bones " + (lootBones ? "on" : "off") + ", bury " + (buryBones ? "on" : "off"));
    paintBuilder.start(4, 125, 250);

    if (!nearSomewhereKnown()) {
      stopWithReason("Not near the Ardougne south bank, the route or the battlefield (at " + controller.currentX()
          + "," + controller.currentY() + ") - walk this account to Ardougne first");
      return 1000;
    }

    while (controller.isRunning()) {
      if (controller.getNeedToMove()) controller.moveCharacter();
      if (controller.getShouldSleep()) controller.sleepHandler(true);
      countKills();

      if (diedFlag || controller.currentY() > 1000 || !nearSomewhereKnown()) {
        diedFlag = false;
        if (!nearSomewhereKnown()) {
          stopWithReason("Died or moved away (at " + controller.currentX() + "," + controller.currentY()
              + ") - stopping. Walk the account back to Ardougne and re-equip, then start again.");
          return 1000;
        }
      }

      ensureFightMode();

      // HP safety first
      if (hpNow() * 100 <= eatAtPercent * hpMax()) {
        if (eatSomething()) continue;
        if (!isNoFood() && canBankForFood()) {
          goBank("out of food");
          continue;
        }
        if (outOfFood == OOF_STOP) {
          setState("Out of food - stopping");
          getToSafety();
          stopWithReason("Out of food at " + hpNow() + "/" + hpMax() + " HP - stopped safely at "
              + controller.currentX() + "," + controller.currentY());
          return 1000;
        }
        if (outOfFood == OOF_REST || hpCritical()) {
          restUntilHealthy();
          continue;
        }
      }
      if (hpCritical() && carriedFoodCount() == 0) {
        restUntilHealthy();
        continue;
      }

      if (needBank()) {
        goBank(controller.getInventoryItemCount() >= 30 ? "inventory full" : "getting food");
        continue;
      }

      if (!inFightZone()) {
        setState("Walking to the gnomes");
        walkRouteFully(BANK_TO_GNOMES, "the gnomes", 180_000);
        continue;
      }

      if (fighting()) {
        setState("Fighting");
        controller.sleep(300);
        continue;
      }

      if (buryBones && controller.getInventoryItemCount(BONES) > 0) {
        buryOne();
        continue;
      }
      if (loot()) continue;

      attackGnome();
    }
    return 1000;
  }

  // ---------------------------------------------------------------------------------------------
  // Fighting
  // ---------------------------------------------------------------------------------------------

  private boolean inFightZone() {
    Controller c = c();
    int x = c.currentX(), y = c.currentY();
    return x >= FIGHT_X0 && x <= FIGHT_X1 && y >= FIGHT_Y0 && y <= FIGHT_Y1;
  }

  private boolean inZone(int x, int y) {
    return x >= FIGHT_X0 && x <= FIGHT_X1 && y >= FIGHT_Y0 && y <= FIGHT_Y1;
  }

  private boolean fighting() {
    return c().isInCombat() || System.currentTimeMillis() - fightSeenAt < 1500;
  }

  private void ensureFightMode() {
    Controller c = c();
    int mode = STYLE_MODES[styleIndex];
    if (c.getFightMode() != mode) {
      c.setFightMode(mode);
      c.sleep(300);
    }
  }

  private int combatXp() {
    Controller c = c();
    return c.getStatXp(0) + c.getStatXp(1) + c.getStatXp(2) + c.getStatXp(3);
  }

  /** RSC hands out combat XP when the NPC dies: every jump in combat XP is a kill. */
  private void countKills() {
    int xp = combatXp();
    if (xp > lastCombatXp) kills++;
    lastCombatXp = xp;
  }

  // --- reach guard: "I can't get close enough" = the gnome was already fighting someone when we
  // got there (AttackHandler: mob.inCombat()). With many accounts on 30 gnome spawns they race for
  // the same gnome, so: claim targets across tabs, spread accounts over fight spots, switch target
  // the moment ours gets taken, blacklist a gnome for 60 s after the message, and after 3 misses
  // in a row walk back to our own fight spot instead of spamming attacks.
  /** Fight spots inside the zone (open ground, from OpenRSC SceneryLocs / NpcLocs). */
  static final int[][] FIGHT_SPOTS = {{646, 664}, {652, 662}, {657, 666}, {662, 663}, {667, 665}};
  /** Gnome claims shared by every tab in this client: server index -> "account|until ms". */
  static final java.util.concurrent.ConcurrentHashMap<Integer, String> CLAIMS = new java.util.concurrent.ConcurrentHashMap<>();
  static final long BLACKLIST_MS = 60_000, CLAIM_MS = 6_000;
  final java.util.HashMap<Integer, Long> blacklist = new java.util.HashMap<>();
  int cantReach = 0, cantReachInRow = 0, targetSwitches = 0;
  volatile long cantReachAt = 0;
  int targetIndex = -1;
  int[] homeSpot = null;

  long reachLoggedAt = 0;
  int reachLoggedCount = 0;

  /** One log line a minute at most: how many gnomes were skipped (unreachable / taken first). */
  private void reachSummary() {
    int total = cantReach + targetSwitches;
    if (total == reachLoggedCount || System.currentTimeMillis() - reachLoggedAt < 60_000) return;
    log("Skipped " + (total - reachLoggedCount) + " gnomes someone else got to first since the last report ("
        + cantReach + " unreachable, " + targetSwitches + " switched before arriving, so far)");
    reachLoggedCount = total;
    reachLoggedAt = System.currentTimeMillis();
  }

  private String me() {
    String n = c().getPlayerName();
    return n == null ? "?" : n;
  }

  private int[] homeSpot() {
    if (homeSpot == null) homeSpot = FIGHT_SPOTS[Math.floorMod(me().toLowerCase().hashCode(), FIGHT_SPOTS.length)];
    return homeSpot;
  }

  private boolean claimedByOther(int idx) {
    String v = CLAIMS.get(idx);
    if (v == null) return false;
    int bar = v.lastIndexOf('|');
    long until = Long.parseLong(v.substring(bar + 1));
    if (until < System.currentTimeMillis()) {
      CLAIMS.remove(idx, v);
      return false;
    }
    return !v.substring(0, bar).equalsIgnoreCase(me());
  }

  private void claim(int idx) {
    CLAIMS.put(idx, me() + "|" + (System.currentTimeMillis() + CLAIM_MS));
  }

  private boolean blacklisted(int idx) {
    Long until = blacklist.get(idx);
    if (until == null) return false;
    if (until < System.currentTimeMillis()) {
      blacklist.remove(idx);
      return false;
    }
    return true;
  }

  /** Another player standing on / next to the gnome is about to fight it. */
  private boolean playerNextTo(int x, int y) {
    Controller c = c();
    int mx = c.currentX(), my = c.currentY();
    for (ORSCharacter p : c.getPlayers()) {
      if (p == null) continue;
      int[] xy = c.getPlayerCoordsByServerIndex(p.serverIndex);
      if (xy == null || (xy[0] == mx && xy[1] == my)) continue;
      if (Math.abs(xy[0] - x) <= 1 && Math.abs(xy[1] - y) <= 1) return true;
    }
    return false;
  }

  private void attackGnome() {
    attackGnome(0);
  }

  private void attackGnome(int depth) {
    Controller c = c();
    int[] home = homeSpot();
    if (cantReachInRow >= 3) {
      log("Couldn't reach " + cantReachInRow + " gnomes in a row - walking back to my fight spot " + home[0] + "," + home[1]);
      cantReachInRow = 0;
      setState("Back to my fight spot");
      c.walkToAsync(home[0], home[1], 1);
      waitFor(() -> c.distanceTo(home[0], home[1]) <= 2 || c.isInCombat(), 10_000);
      return;
    }
    ORSCharacter best = null;
    int bestScore = Integer.MAX_VALUE;
    for (ORSCharacter n : c.getNpcs()) {
      if (n == null || n.npcId != GNOME_TROOP) continue;
      int[] xy = c.getNpcCoordsByServerIndex(n.serverIndex);
      if (!inZone(xy[0], xy[1])) continue;
      if (c.isNpcInCombat(n.serverIndex) || blacklisted(n.serverIndex) || claimedByOther(n.serverIndex)) continue;
      int d = c.distanceTo(xy[0], xy[1]);
      if (d > 1 && playerNextTo(xy[0], xy[1])) continue;
      if (d > 1 && !c.isReachable(xy[0], xy[1], true)) continue;
      // nearest first, nudged towards our own fight spot so the accounts spread out
      int score = d * 2 + Math.max(Math.abs(xy[0] - home[0]), Math.abs(xy[1] - home[1]));
      if (score < bestScore) {
        bestScore = score;
        best = n;
      }
    }
    if (best == null && depth > 0) {
      int[] h = homeSpot();
      if (c.distanceTo(h[0], h[1]) > 1) c.walkToAsync(h[0], h[1], 0);
      else c.walkToAsync(h[0] + 1, h[1], 0);
      return;
    }
    if (best == null) {
      setState("Waiting for a gnome to respawn");
      notStalled(5000);
      if (c.distanceTo(home[0], home[1]) > 4) c.walkToAsync(home[0], home[1], 1);
      c.sleep(1000);
      return;
    }
    final int idx = best.serverIndex;
    targetIndex = idx;
    claim(idx);
    setState("Attacking a gnome troop");
    long sentAt = System.currentTimeMillis();
    c.attackNpc(idx);
    // wait for the fight; if someone else gets our gnome first, give up on it at once
    boolean taken = false;
    long end = sentAt + 3500;
    while (c.isRunning() && System.currentTimeMillis() < end) {
      if (c.isInCombat()) break;
      if (cantReachAt >= sentAt) break;
      if (c.isNpcInCombat(idx)) {
        // our own fight often shows on the gnome before it shows on us: only call it taken if we
        // still aren't fighting 1.5 s later and the gnome isn't right next to us
        waitFor(() -> c.isInCombat(), 1500);
        int[] at = c.getNpcCoordsByServerIndex(idx);
        boolean nextToUs = at != null && at[0] >= 0 && Math.max(Math.abs(at[0] - c.currentX()), Math.abs(at[1] - c.currentY())) <= 1;
        if (!c.isInCombat() && !nextToUs) taken = true;
        break;
      }
      c.sleep(100);
    }
    if (c.isInCombat()) {
      cantReachInRow = 0;
      claim(idx);
      return;
    }
    if (cantReachAt >= sentAt) {
      blacklist.put(idx, System.currentTimeMillis() + BLACKLIST_MS);
      cantReach++;
      cantReachInRow++;
      reachSummary();
    } else if (taken) {
      targetSwitches++;
      reachSummary();
      blacklist.put(idx, System.currentTimeMillis() + 10_000);
      CLAIMS.remove(idx);
      // replace the server-side walk-to-attack straight away (it would still end in "I can't get
      // close enough"): attack the next free gnome now, or step towards our fight spot
      if (depth < 3 && c.isRunning()) {
        attackGnome(depth + 1);
        return;
      }
      int[] h = homeSpot();
      if (c.distanceTo(h[0], h[1]) > 1) c.walkToAsync(h[0], h[1], 0);
      else c.walkToAsync(h[0] + 1, h[1], 0);
      return;
    }
    CLAIMS.remove(idx);
  }

  private void buryOne() {
    Controller c = c();
    int before = c.getInventoryItemCount(BONES);
    setState("Burying bones");
    c.itemCommand(BONES);
    if (waitFor(() -> c.getInventoryItemCount(BONES) < before, 2500)) buried++;
    c.sleep(300);
  }

  /** Picks up coins / bones lying in the fight zone close by. @return true if it tried */
  private boolean loot() {
    if (!lootCoins && !lootBones) return false;
    Controller c = c();
    int n = c.getGroundItemsCount();
    int[] ids = c.getGroundItems();
    int[] xs = c.getGroundItemsX();
    int[] ys = c.getGroundItemsY();
    boolean full = c.getInventoryItemCount() >= 30;
    for (int i = 0; i < n; i++) {
      int id = ids[i];
      boolean want = (id == COINS && lootCoins && (!full || c.getInventoryItemCount(COINS) > 0))
          || (id == BONES && lootBones && !full);
      if (!want || !inZone(xs[i], ys[i]) || c.distanceTo(xs[i], ys[i]) > 6) continue;
      final int before = c.getInventoryItemCount(id);
      setState("Picking up " + (id == COINS ? "coins" : "bones"));
      c.pickupItem(xs[i], ys[i], id, false, true);
      if (waitFor(() -> c.getInventoryItemCount(id) > before || c.isInCombat(), 4000) && id == COINS)
        coinsLooted += Math.max(0, c.getInventoryItemCount(COINS) - before);
      return true;
    }
    return false;
  }

  // ---------------------------------------------------------------------------------------------
  // HP, food, resting
  // ---------------------------------------------------------------------------------------------

  private int hpNow() {
    int h = c().getCurrentStat(HITS);
    if (h < lowestHp && h > 0) lowestHp = h;
    return h;
  }

  private int hpMax() {
    return Math.max(1, c().getBaseStat(HITS));
  }

  /** Critical: 30% of max HP or 3 HP, whichever is more (and never above the eat threshold). */
  private boolean hpCritical() {
    int crit = Math.max(3, hpMax() * 30 / 100);
    crit = Math.min(crit, Math.max(1, hpMax() * eatAtPercent / 100));
    return hpNow() <= crit;
  }

  private boolean isNoFood() {
    return foodChoice.equalsIgnoreCase("None");
  }

  private boolean isBest() {
    return foodChoice.equalsIgnoreCase("Best");
  }

  private static int foodIndex(int id) {
    for (int i = 0; i < FOOD_IDS.length; i++) if (FOOD_IDS[i] == id) return i;
    return -1;
  }

  private static int foodIdByName(String name) {
    for (int i = 0; i < FOOD_NAMES.length; i++) if (FOOD_NAMES[i].equalsIgnoreCase(name.trim())) return FOOD_IDS[i];
    return -1;
  }

  /** Food we bring: Best = all cooked fish (best first); a fish = just that one; None = nothing. */
  private int[] wantedFood() {
    if (isNoFood()) return new int[0];
    if (isBest()) return FOOD_IDS;
    int id = foodIdByName(foodChoice);
    return id < 0 ? new int[0] : new int[] {id};
  }

  /** Cooked fish of any kind we carry (we eat any cooked fish we have, never burnt). */
  private int carriedFoodCount() {
    Controller c = c();
    int n = 0;
    for (int id : FOOD_IDS) n += c.getUnnotedInventoryItemCount(id);
    return n;
  }

  /**
   * Eats one carried cooked fish: the biggest one that doesn't overheal much, else the smallest.
   * @return true if it ate
   */
  private boolean eatSomething() {
    Controller c = c();
    int missing = hpMax() - hpNow();
    int pick = -1;
    for (int i = 0; i < FOOD_IDS.length; i++) { // best first
      if (c.getUnnotedInventoryItemCount(FOOD_IDS[i]) < 1) continue;
      if (FOOD_HEALS[i] <= missing + 2) {
        pick = i;
        break;
      }
      pick = i; // keeps the smallest one seen so far as the fallback
    }
    if (pick < 0) return false;
    int id = FOOD_IDS[pick];
    int before = c.getUnnotedInventoryItemCount(id);
    int h = hpNow();
    setState("Eating");
    c.itemCommand(id);
    if (waitFor(() -> c.getUnnotedInventoryItemCount(id) < before, 2500)) {
      eaten++;
      waitFor(() -> c.getCurrentStat(HITS) > h, 1500); // the stat update comes a tick later
      log("Ate a " + FOOD_NAMES[pick] + " at " + h + "/" + hpMax() + " HP, now " + hpNow() + " ("
          + carriedFoodCount() + " food left)");
      c.sleep(600);
      return true;
    }
    return false;
  }

  /** Walks out of the fight to the rest spot and waits for HP (no stall: we wait on purpose). */
  private void restUntilHealthy() {
    Controller c = c();
    rests++;
    log("Resting: " + hpNow() + "/" + hpMax() + " HP and nothing to eat - resting until " + restToPercent + "%");
    getToSafety();
    long lastLog = System.currentTimeMillis();
    while (c.isRunning() && hpNow() * 100 < restToPercent * hpMax()) {
      setState("Resting " + hpNow() + "/" + hpMax() + " HP");
      notStalled(15_000);
      if (fighting() || c.distanceTo(REST_SPOT[0], REST_SPOT[1]) > 3) getToSafety();
      if (carriedFoodCount() > 0 && eatSomething()) continue;
      if (System.currentTimeMillis() - lastLog > 120_000) {
        log("Still resting: " + hpNow() + "/" + hpMax() + " HP");
        lastLog = System.currentTimeMillis();
      }
      c.sleep(1000);
    }
    if (c.isRunning()) log("Rested to " + hpNow() + "/" + hpMax() + " HP - back to the gnomes");
  }

  /** Leaves any fight and walks to the rest spot. */
  private void getToSafety() {
    Controller c = c();
    long end = System.currentTimeMillis() + 120_000;
    long lastClick = 0;
    if (c.distanceTo(REST_SPOT[0], REST_SPOT[1]) > 1) setState("Walking to the rest spot");
    while (c.isRunning() && System.currentTimeMillis() < end
        && (c.distanceTo(REST_SPOT[0], REST_SPOT[1]) > 1 || c.isInCombat())) {
      if (System.currentTimeMillis() - lastClick > 1200 || !c.isCurrentlyWalking()) {
        if (c.distanceTo(REST_SPOT[0], REST_SPOT[1]) > 12) {
          walkRouteFully(BANK_TO_REST, "the rest spot", 60_000);
        }
        c.walkToAsync(REST_SPOT[0], REST_SPOT[1], 0);
        lastClick = System.currentTimeMillis();
      }
      notStalled(5000);
      c.sleep(300);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Banking
  // ---------------------------------------------------------------------------------------------

  private boolean nearBank() {
    return c().distanceTo(BANK_TILE[0], BANK_TILE[1]) <= 12;
  }

  private boolean canBankForFood() {
    if (!bankForFood || isNoFood()) return false;
    if (bankHasNoFood && System.currentTimeMillis() - bankEmptyCheckedAt < 15 * 60_000L) return false;
    return true;
  }

  private boolean needBank() {
    Controller c = c();
    if (!bankForFood) return false;
    if (c.getInventoryItemCount() >= 30 && (lootBones || lootCoins) && !buryBones) return true;
    if (!canBankForFood()) return false;
    if (carriedFoodCount() == 0) return true;
    // Starting next to the bank: top up first
    return nearBank() && carriedFoodCount() < foodAmount && bankTrips == 0;
  }

  private void goBank(String why) {
    Controller c = c();
    setState("Banking (" + why + ")");
    log("Going to the bank: " + why);
    if (!nearBank()) {
      if (fighting()) getToSafety();
      if (!walkRouteFully(GNOMES_TO_BANK, "the bank", 240_000)) return;
    }
    long end = System.currentTimeMillis() + 30_000;
    while (c.isRunning() && !c.isInBank() && System.currentTimeMillis() < end) {
      c.openBank();
      c.sleep(600);
    }
    if (!c.isInBank()) {
      log("Couldn't open the bank - retrying");
      return;
    }
    bankTrips++;
    // deposit everything that isn't worn and isn't food we want to keep
    for (int slot = 0; slot < 30; slot++) {
      int id = c.getInventorySlotItemId(slot);
      if (id <= 0 || c.isEquipped(slot) || foodIndex(id) >= 0) continue;
      int n = c.getInventoryItemCount(id);
      if (n > 0) {
        c.depositItem(id, n);
        c.sleep(300);
      }
    }
    if (!isNoFood()) {
      int need = foodAmount - carriedFoodCount();
      int free = 30 - c.getInventoryItemCount();
      need = Math.min(need, free);
      StringBuilder took = new StringBuilder();
      for (int id : wantedFood()) {
        if (need <= 0) break;
        int have = c.getBankItemCount(id);
        if (have <= 0) continue;
        int take = Math.min(need, have);
        int before = c.getInventoryItemCount(id);
        c.withdrawItem(id, take);
        waitFor(() -> c.getInventoryItemCount(id) >= before + take, 4000);
        int got = c.getInventoryItemCount(id) - before;
        if (got > 0) {
          need -= got;
          if (took.length() > 0) took.append(", ");
          took.append(got).append(" ").append(FOOD_NAMES[foodIndex(id)]);
        }
      }
      if (took.length() > 0) {
        lastFoodTaken = took.toString();
        log("Took " + took + " (bank: " + bankFoodSummary() + ")");
        bankHasNoFood = false;
      } else if (carriedFoodCount() == 0) {
        bankHasNoFood = true;
        bankEmptyCheckedAt = System.currentTimeMillis();
        log("No " + (isBest() ? "cooked fish" : foodChoice) + " in the bank - out of food (" + OOF[outOfFood] + ")");
      }
    }
    c.closeBank();
    c.sleep(600);
  }

  private String bankFoodSummary() {
    Controller c = c();
    StringBuilder s = new StringBuilder();
    for (int i = 0; i < FOOD_IDS.length; i++) {
      int n = c.getBankItemCount(FOOD_IDS[i]);
      if (n <= 0) continue;
      if (s.length() > 0) s.append(", ");
      s.append(n).append(" ").append(FOOD_NAMES[i]);
    }
    return s.length() == 0 ? "no cooked fish left" : s.toString();
  }

  // ---------------------------------------------------------------------------------------------
  // Walking
  // ---------------------------------------------------------------------------------------------

  private boolean nearSomewhereKnown() {
    Controller c = c();
    if (c.currentY() > 1000) return false;
    return nearestWaypoint(BANK_TO_GNOMES, 40) >= 0 || c.distanceTo(FIGHT_CENTER[0], FIGHT_CENTER[1]) <= 25;
  }

  private int nearestWaypoint(int[][] route, int maxDist) {
    Controller c = c();
    int best = -1, bestD = Integer.MAX_VALUE;
    for (int i = 0; i < route.length; i++) {
      int d = c.distanceTo(route[i][0], route[i][1]);
      if (d <= maxDist && d < bestD) {
        bestD = d;
        best = i;
      }
    }
    return best;
  }

  private boolean walkRouteFully(int[][] route, String name, long ms) {
    Controller c = c();
    long until = System.currentTimeMillis() + ms;
    while (c.isRunning() && System.currentTimeMillis() < until) {
      if (walkRoute(route, name)) return true;
      if (!nearSomewhereKnown()) return false;
    }
    return false;
  }

  /** Walks a route from its nearest waypoint to its end; attacked on the way: keeps walking. */
  private boolean walkRoute(int[][] route, String name) {
    Controller c = c();
    int start = nearestWaypoint(route, 40);
    if (start < 0) {
      int far = nearestWaypoint(route, 90);
      if (far < 0) return false;
      if (!c.walkTowards(route[far][0], route[far][1])) c.sleep(1200);
      c.sleep(600);
      return false;
    }
    int[] a = route[start];
    if (start + 1 < route.length) {
      int[] b = route[start + 1];
      if (Math.max(Math.abs(a[0] - b[0]), Math.abs(a[1] - b[1])) >= c.distanceTo(b[0], b[1])) start++;
    }
    for (int i = start; i < route.length && c.isRunning(); i++) {
      int wx = route[i][0], wy = route[i][1];
      c.setStatus("@cya@Walking to " + name + " (" + (i + 1) + "/" + route.length + ")");
      long end = System.currentTimeMillis() + 25_000;
      long lastClick = 0;
      while (c.isRunning() && c.distanceTo(wx, wy) > 1) {
        if (System.currentTimeMillis() > end) {
          if (!c.walkTowards(wx, wy)) c.sleep(1200);
          return false;
        }
        if (hpNow() * 100 <= eatAtPercent * hpMax() && carriedFoodCount() > 0 && eatSomething()) continue;
        if (fighting()) { // walk on: gets us out of a fight with anything that jumped us
          int[] away = route[Math.min(i + 1, route.length - 1)];
          if (System.currentTimeMillis() - lastClick > 600) {
            c.walkToAsync(away[0], away[1], 0);
            lastClick = System.currentTimeMillis();
          }
          end = Math.max(end, System.currentTimeMillis() + 5000);
          c.sleep(300);
          continue;
        }
        if (System.currentTimeMillis() - lastClick > 2400 || !c.isCurrentlyWalking()) {
          c.walkToAsync(wx, wy, 0);
          lastClick = System.currentTimeMillis();
        }
        c.sleep(300);
      }
    }
    return c.isRunning();
  }

  /**
   * Stops the script, but never within 15 s of leaving the setup window: the v15.6 script queue
   * (and mass start) capture the setup right after Start and treat a script that already stopped
   * as a cancelled setup.
   */
  private void stopWithReason(String why) {
    Controller c = c();
    setState("Stopped");
    c.log(why, "red");
    log(why);
    long wait = runStartedAt + MIN_RUN_BEFORE_STOP_MS - System.currentTimeMillis();
    while (c.isRunning() && wait > 0) {
      c.setStatus("@red@Stopping in " + (wait / 1000 + 1) + "s (see the log)");
      notStalled(5000);
      try {
        Thread.sleep(Math.min(1000, wait));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      wait = runStartedAt + MIN_RUN_BEFORE_STOP_MS - System.currentTimeMillis();
    }
    if (c.isRunning()) c.stop();
  }

  // ---------------------------------------------------------------------------------------------
  // Tab status (v15.6 shows it after the script name)
  // ---------------------------------------------------------------------------------------------

  public String getStatusSuffix() {
    Controller c = c();
    if (c == null) return null;
    String skill;
    switch (styleIndex) {
      case 2:
        skill = "Att " + c.getBaseStat(0);
        break;
      case 3:
        skill = "Def " + c.getBaseStat(1);
        break;
      case 1:
        skill = "Str " + c.getBaseStat(2);
        break;
      default:
        skill = "A" + c.getBaseStat(0) + " S" + c.getBaseStat(2) + " D" + c.getBaseStat(1);
    }
    return skill + " - " + state;
  }

  // ---------------------------------------------------------------------------------------------
  // Setup
  // ---------------------------------------------------------------------------------------------

  private boolean setup(String[] param) {
    String joined = param == null ? "" : String.join(" ", param).trim();
    if (!joined.isEmpty()) {
      for (String a : joined.split("[\\s,]+")) {
        String[] kv = a.split("=", 2);
        String k = kv[0].toLowerCase();
        String v = kv.length > 1 ? kv[1] : "on";
        boolean on = !(v.equalsIgnoreCase("off") || v.equalsIgnoreCase("false") || v.equals("0") || v.equalsIgnoreCase("no"));
        switch (k) {
          case "style":
            for (int i = 0; i < STYLES.length; i++) if (STYLES[i].toLowerCase().startsWith(v.toLowerCase())) styleIndex = i;
            break;
          case "food":
            String f = v.replace('_', ' ');
            if (f.equalsIgnoreCase("best") || f.equalsIgnoreCase("none")) foodChoice = f.equalsIgnoreCase("best") ? "Best" : "None";
            else if (foodIdByName(f) >= 0) foodChoice = FOOD_NAMES[foodIndex(foodIdByName(f))];
            break;
          case "amount":
            foodAmount = parseInt(v, 5, 1, 28);
            break;
          case "eatat":
            eatAtPercent = parseInt(v.replace("%", ""), 50, 10, 90);
            break;
          case "restto":
            restToPercent = parseInt(v.replace("%", ""), 90, 30, 100);
            break;
          case "oof":
            String o = v.toLowerCase();
            outOfFood = o.startsWith("stop") ? OOF_STOP : o.startsWith("care") || o.startsWith("keep") ? OOF_CAREFUL : OOF_REST;
            break;
          case "bank":
            bankForFood = on;
            break;
          case "coins":
            lootCoins = on;
            break;
          case "bones":
            lootBones = on;
            break;
          case "bury":
            buryBones = on;
            if (on) lootBones = true;
            break;
          default:
        }
      }
      restToPercent = Math.max(restToPercent, eatAtPercent + 10);
      return true;
    }
    if (java.awt.GraphicsEnvironment.isHeadless()) return true;
    SwingUtilities.invokeLater(this::showSetup);
    Controller controller = c();
    while (controller.isRunning() && !setupDone && !setupCancelled) controller.sleep(200);
    if (!setupDone) {
      log("Setup window closed - not starting");
      controller.stop();
      return false;
    }
    return true;
  }

  private static int parseInt(String v, int def, int lo, int hi) {
    try {
      return Math.max(lo, Math.min(hi, Integer.parseInt(v.trim())));
    } catch (NumberFormatException e) {
      return def;
    }
  }

  static final Color BG = new Color(0x282828);
  static final Color BG2 = new Color(0x333333);
  static final Color GOLD = new Color(0xD4AF37);
  static final Color TEXT = new Color(0xE0E0E0);

  private <T extends JComponent> T style(T comp) {
    comp.setForeground(TEXT);
    comp.setBackground(BG2);
    if (comp instanceof JCheckBox || comp instanceof JLabel) comp.setOpaque(false);
    return comp;
  }

  private JPanel row(String label, JComponent comp) {
    JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    r.setOpaque(false);
    r.add(style(new JLabel(label)));
    r.add(style(comp));
    return r;
  }

  private void showSetup() {
    JFrame f = new JFrame("Bens BattlefieldGnomes");
    JPanel outer = new JPanel(new BorderLayout(0, 6));
    outer.setBackground(BG);
    outer.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(2, 2, 2, 2, GOLD),
            BorderFactory.createEmptyBorder(8, 12, 10, 12)));
    JLabel title = new JLabel("\u2726 Bens BattlefieldGnomes \u2726", SwingConstants.CENTER);
    title.setForeground(GOLD);
    title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
    JLabel sub = new JLabel("gnome troops, battlefield south of East Ardougne", SwingConstants.CENTER);
    sub.setForeground(new Color(0xA89060));
    JPanel head = new JPanel(new GridLayout(0, 1));
    head.setOpaque(false);
    head.add(title);
    head.add(sub);

    String[] foods = new String[FOOD_NAMES.length + 2];
    foods[0] = "Best";
    for (int i = 0; i < FOOD_NAMES.length; i++) foods[i + 1] = FOOD_NAMES[i];
    foods[foods.length - 1] = "None";

    JComboBox<String> styleBox = new JComboBox<>(STYLES);
    styleBox.setSelectedIndex(styleIndex);
    JComboBox<String> foodBox = new JComboBox<>(foods);
    foodBox.setSelectedItem(foodChoice);
    JTextField amountField = new JTextField(String.valueOf(foodAmount), 3);
    JTextField eatField = new JTextField(String.valueOf(eatAtPercent), 3);
    JTextField restField = new JTextField(String.valueOf(restToPercent), 3);
    JComboBox<String> oofBox = new JComboBox<>(OOF);
    oofBox.setSelectedIndex(outOfFood);
    JCheckBox bank = style(new JCheckBox("Bank for food at Ardougne south bank", bankForFood));
    JCheckBox coins = style(new JCheckBox("Loot coins", lootCoins));
    JCheckBox bones = style(new JCheckBox("Loot bones", lootBones));
    JCheckBox bury = style(new JCheckBox("Bury bones", buryBones));

    JPanel p = new JPanel(new GridLayout(0, 1, 4, 4));
    p.setOpaque(false);
    p.add(row("Fight style:", styleBox));
    p.add(row("Food (Best = best cooked fish in the bank):", foodBox));
    JPanel amounts = row("Food to bring:", amountField);
    amounts.add(style(new JLabel("Eat at HP %:")));
    amounts.add(style(eatField));
    p.add(amounts);
    p.add(row("Out of food:", oofBox));
    p.add(row("Rest until HP %:", restField));
    p.add(bank);
    JPanel loot = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    loot.setOpaque(false);
    loot.add(coins);
    loot.add(bones);
    loot.add(bury);
    p.add(loot);

    JButton start = new JButton("Start");
    start.setBackground(GOLD);
    start.setForeground(Color.BLACK);
    start.setFont(start.getFont().deriveFont(Font.BOLD));
    start.addActionListener(
        e -> {
          styleIndex = Math.max(0, styleBox.getSelectedIndex());
          foodChoice = String.valueOf(foodBox.getSelectedItem());
          foodAmount = parseInt(amountField.getText(), 5, 1, 28);
          eatAtPercent = parseInt(eatField.getText().replace("%", ""), 50, 10, 90);
          restToPercent = Math.max(parseInt(restField.getText().replace("%", ""), 90, 30, 100), eatAtPercent + 10);
          outOfFood = Math.max(0, oofBox.getSelectedIndex());
          bankForFood = bank.isSelected();
          lootCoins = coins.isSelected();
          buryBones = bury.isSelected();
          lootBones = bones.isSelected() || buryBones;
          setupDone = true;
          f.dispose();
        });
    f.addWindowListener(
        new java.awt.event.WindowAdapter() {
          @Override
          public void windowClosed(java.awt.event.WindowEvent e) {
            if (!setupDone) setupCancelled = true;
          }
        });
    outer.add(head, BorderLayout.NORTH);
    outer.add(p, BorderLayout.CENTER);
    outer.add(start, BorderLayout.SOUTH);
    f.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    f.setContentPane(outer);
    f.pack();
    f.setLocationRelativeTo(null);
    f.setVisible(true);
  }

  // ---------------------------------------------------------------------------------------------
  // Interrupts and paint
  // ---------------------------------------------------------------------------------------------

  @Override
  public void serverMessageInterrupt(String message) {
    String m = message.toLowerCase();
    if (m.contains("under attack") || m.contains("whilst you are fighting") || m.contains("can't retreat"))
      fightSeenAt = System.currentTimeMillis();
    if (m.contains("you are dead") || m.contains("oh dear")) {
      deaths++;
      diedFlag = true;
    }
    if (m.contains("can't get close enough") || m.contains("cannot get close enough")) cantReachAt = System.currentTimeMillis();
  }

  @Override
  public void questMessageInterrupt(String message) {
    String m = message.toLowerCase();
    if (m.contains("you are dead") || m.contains("oh dear you")) {
      deaths++;
      diedFlag = true;
    }
  }

  @Override
  public void paintInterrupt() {
    Controller c = c();
    if (c != null) {
      int[] gain = new int[4];
      for (int s = 0; s < 4; s++) gain[s] = c.getStatXp(s) - startXp[s];
      paintBuilder.setTitleCenteredSingleColor("Bens BattlefieldGnomes", 0xD4AF37, 4);
      paintBuilder.addSpacerRow(4);
      paintBuilder.setBackgroundColor(0x282828, 200);
      paintBuilder.setBorderColor(0xD4AF37);
      paintBuilder.addRow(rowBuilder.centeredSingleStringRow(state, 0xFFE066, 1));
      paintBuilder.addSpacerRow(4);
      paintBuilder.addRow(
          rowBuilder.multipleStringRow(
              new String[] {"Kills:", paintBuilder.stringFormatInt(kills), paintBuilder.stringAmountPerHour(kills)},
              new int[] {0xD4AF37, 0xE0E0E0, 0xFFE066},
              new int[] {4, 82, 58},
              1));
      String[] names = {"Attack", "Defence", "Strength", "Hits"};
      for (int s = 0; s < 4; s++) {
        if (gain[s] <= 0 && !(s == trainedSkill())) continue;
        paintBuilder.addRow(
            rowBuilder.multipleStringRow(
                new String[] {
                  names[s] + " " + c.getBaseStat(s) + ":",
                  paintBuilder.stringFormatInt(gain[s]),
                  paintBuilder.stringAmountPerHour(gain[s])
                },
                new int[] {0xD4AF37, 0xE0E0E0, 0xFFE066},
                new int[] {4, 82, 58},
                1));
      }
      paintBuilder.addSpacerRow(4);
      paintBuilder.addRow(
          rowBuilder.multipleStringRow(
              new String[] {"HP:", hpNow() + "/" + hpMax(), "eat at " + eatAtPercent + "%"},
              new int[] {0xD4AF37, 0xE0E0E0, 0xFFE066},
              new int[] {4, 82, 58},
              1));
      paintBuilder.addRow(
          rowBuilder.multipleStringRow(
              new String[] {"Food:", foodChoice + " (" + carriedFoodCount() + ")", eaten + " eaten"},
              new int[] {0xD4AF37, 0xE0E0E0, 0xFFE066},
              new int[] {4, 82, 58},
              1));
      paintBuilder.addRow(
          rowBuilder.multipleStringRow(
              new String[] {"Banked:", bankTrips + " trips", rests + " rests"},
              new int[] {0xD4AF37, 0xE0E0E0, 0xFFE066},
              new int[] {4, 82, 58},
              1));
      if (lootCoins || lootBones || buryBones)
        paintBuilder.addRow(
            rowBuilder.multipleStringRow(
                new String[] {"Loot:", coinsLooted + "gp", buried + " buried"},
                new int[] {0xD4AF37, 0xE0E0E0, 0xFFE066},
                new int[] {4, 82, 58},
                1));
      if (deaths > 0) paintBuilder.addRow(rowBuilder.centeredSingleStringRow("Deaths: " + deaths, 0xFF6060, 1));
      if (cantReach > 0 || targetSwitches > 0)
        paintBuilder.addRow(rowBuilder.centeredSingleStringRow("Can't reach: " + cantReach + ", lost races: " + targetSwitches,
            0xFFB366, 1));
    }
    paintBuilder.draw();
  }

  /** Stat id the fight style trains (controlled: -1, shows all that gained). */
  private int trainedSkill() {
    switch (styleIndex) {
      case 1:
        return 2;
      case 2:
        return 0;
      case 3:
        return 1;
      default:
        return -1;
    }
  }
}
