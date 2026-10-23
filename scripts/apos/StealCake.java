package scripting.apos;

import bot.Main;
import bot.ui.scriptselector.models.Category;
import bot.ui.scriptselector.models.ScriptInfo;
import controller.Controller;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * StealCake - steals cakes from the Bakers stall in the East Ardougne market.
 *
 * <p>Originally a small APOS script (power-steal, or "bank"). Rewritten 21 Oct 2026 as an
 * IdleScript so it gets a stop hook (weapon back on), Ben's paint and a remembered setup window.
 *
 * <p><b>Opposite side.</b> The server (OpenRSC Thieving.stallThieving) yells "Hey thats mine" and
 * gives nothing when the baker (npc 325, wanders x 543-546 / y 597-602 around the stall) is within
 * 7 tiles, not busy, and {@code canBeSeen(baker -> player)}: a stepped line from the baker to the
 * player that is blocked only by full-block (type 1) scenery such as the stall itself (2x2 at
 * 544,599). Ardougne guards (321) within 4 tiles that can see you do the same and attack. Before
 * every steal the script reads the baker's (and guards') tiles, replays that exact line-of-sight
 * test against the stall footprint (and other full-block scenery nearby), and stands on the
 * stall-adjacent tile the baker can't see - normally the opposite side - preferring tiles he also
 * can't see from the tiles he could step to next. If the baker comes round to our side, it walks
 * round the stall (client path finding, never through it). A tile that gives "I can't get close
 * enough" or can't be reached is skipped for 60 s.
 *
 * <p>Full inventory: bank at the East Ardougne south bank (default) or drop the loot. Keeps a few
 * cakes to eat. Weapon off while thieving, back on before the script stops (weapon guard file).
 *
 * <p>Args (skip the window): {@code full=bank|drop keep=2 eatat=80 debug=off}. The old APOS args
 * still work: {@code bank} = bank, {@code power}/{@code drop} = drop.
 */
public class StealCake extends IdleScript {
  public static final ScriptInfo info =
      new ScriptInfo(
          new Category[] {Category.THIEVING, Category.IRONMAN_SUPPORTED},
          "Ben",
          "Steals cakes from the Bakers stall in East Ardougne market.\n\n"
              + "Stands on the side of the stall the baker can't see (re-checked every steal),\n"
              + "banks at Ardougne south bank (or drops) when full, eats cakes,\n"
              + "weapon off while thieving and back on at stop.",
          null,
          "StealCake");

  static final String NAME = "StealCake";
  static final int STALL = 322, EMPTY_STALL = 341, BAKER = 325, GUARD = 321, SLEEPING_BAG = 1263;
  /** Server spawn (SceneryLocs): 2x2 at 544,599 -> tiles 544-545 / 599-600. */
  static final int STALL_X = 544, STALL_Y = 599;
  /** Baker wander box (NpcLocs). */
  static final int BAKER_X0 = 543, BAKER_X1 = 546, BAKER_Y0 = 597, BAKER_Y1 = 602;
  static final int CAKE = 330, PARTIAL_CAKE = 333, SLICE = 335, CHOC_SLICE = 336, BREAD = 138;
  /** Eaten / kept in this order (pieces first, like the original). */
  static final int[] FOOD = {SLICE, PARTIAL_CAKE, CHOC_SLICE, BREAD, CAKE};
  /** Stall loot that gets banked / dropped. */
  static final int[] LOOT = {CAKE, PARTIAL_CAKE, SLICE, CHOC_SLICE, BREAD};
  static final int[][] BANK_TILES = {{550, 612}, {550, 610}, {550, 614}, {549, 611}, {549, 613}};
  static final int BANKER_ID = 95;
  static final int FIGHT_MODE = 3; // the original's fmode (defensive)
  static final long MIN_RUN_BEFORE_STOP_MS = 15_000;
  static final long BLACKLIST_MS = 60_000;

  // --- setup (remembered by SetupMemory, copied by mass start) ---
  boolean bankWhenFull = true;
  int keepFood = 2;
  int eatAtPercent = 80;
  boolean debugLog = false;
  volatile boolean setupDone = false;
  volatile boolean setupCancelled = false;

  // --- run state ---
  long runStartedAt = 0;
  String state = "Starting";
  int startXp = 0;
  int steals = 0, caughtBaker = 0, caughtGuard = 0, attacked = 0, sideSwitches = 0, cantReach = 0;
  int banked = 0, bankTrips = 0, dropped = 0, eaten = 0, waitsNoTile = 0;
  int[] stall = {STALL_X, STALL_Y, 2, 2};
  int[] tile = null; // where we steal from now
  String tileSide = "";
  String lastSide = "";
  int[] bakerAt = null;
  final Map<Long, Long> badTiles = new HashMap<>();
  final Map<Integer, Long> badBankers = new HashMap<>();
  Set<Long> blocked = new HashSet<>();
  long blockedReadAt = 0;
  volatile long cantReachAt = 0, caughtAt = 0, stoleAt = 0, attemptAt = 0, fullMsgAt = 0;
  boolean inFight = false;
  long noTileSince = 0;
  volatile int caughtBy = 0;
  // 60 s summary
  long summaryAt = 0;
  int sSteals = 0, sCaught = 0, sSwitch = 0, sReach = 0;
  final Map<String, Integer> sSides = new HashMap<>();

  private Controller c() {
    return Main.getController();
  }

  private void setState(String s) {
    state = s;
    Controller c = c();
    if (c != null) c.setStatus("@cya@" + s);
  }

  private void log(String s) {
    Main.log("[" + NAME + "] " + s);
  }

  private void notStalled(long ms) {
    try {
      bot.probe.Probe.noteIntentionalWait(ms);
    } catch (Throwable ignored) {
      // older clients
    }
  }

  private boolean waitFor(java.util.function.BooleanSupplier cond, long ms) {
    Controller c = c();
    long end = System.currentTimeMillis() + ms;
    while (c.isRunning() && System.currentTimeMillis() < end) {
      if (cond.getAsBoolean()) return true;
      c.sleep(100);
    }
    return cond.getAsBoolean();
  }

  private static long key(int x, int y) {
    return ((long) x << 20) | (y & 0xFFFFF);
  }

  // ---------------------------------------------------------------------------------------------
  // Main loop
  // ---------------------------------------------------------------------------------------------

  public int start(String[] param) {
    Controller c = c();
    if (c == null) return 1000;
    if (!setup(param)) return 1000;
    runStartedAt = System.currentTimeMillis();
    summaryAt = runStartedAt;
    startXp = c.getStatXp(17);
    paintBuilder.start(4, 18, 214);
    log("Settings: full inventory " + (bankWhenFull ? "bank (Ardougne south)" : "drop") + ", keep " + keepFood
        + " food, eat at " + eatAtPercent + "% HP" + (debugLog ? ", debug log on" : ""));

    if (c.currentY() > 1000 || c.distanceTo(STALL_X, STALL_Y) > 60) {
      stopWithReason("Not near the East Ardougne market (at " + c.currentX() + "," + c.currentY()
          + ") - walk this account to the Bakers stall first");
      return 1000;
    }
    weapon.load();

    while (c.isRunning()) {
      try {
        loopOnce();
      } catch (Controller.ClientShutdownException e) {
        throw e;
      } catch (RuntimeException e) {
        log("Error (continuing): " + e);
        c.sleep(1000);
      }
      summary(false);
    }
    return 1000;
  }

  private void loopOnce() {
    Controller c = c();
    if (c.getNeedToMove()) c.moveCharacter();
    if (c.getShouldSleep()) c.sleepHandler(true);
    if (c.getFatigue() > 90 && c.getInventoryItemCount(SLEEPING_BAG) > 0) {
      setState("Sleeping");
      c.sleepHandler(false);
      return;
    }
    if (c.getFightMode() != FIGHT_MODE) c.setFightMode(FIGHT_MODE);

    if (c.isInCombat()) {
      escapeFight();
      return;
    }
    inFight = false;

    if (hpNow() * 100 < eatAtPercent * hpMax()) {
      if (eat()) return;
      // like the original: out of food and hurt -> stop safely
      stopWithReason("HP " + hpNow() + "/" + hpMax() + " is below " + eatAtPercent + "% and there is no food - stopping");
      return;
    }

    if (c.isInBank()) c.closeBank();
    if (c.isInOptionMenu()) {
      c.optionAnswer(0);
      c.sleep(600);
      return;
    }

    if (c.getInventoryItemCount() >= 30) {
      if (bankWhenFull) bank();
      else dropLoot();
      return;
    }

    if (weapon.wielded() >= 0) {
      weapon.takeOff(null);
      return;
    }

    if (c.distanceTo(STALL_X, STALL_Y) > 12) {
      walkNear(STALL_X - 1, STALL_Y + 1, "the Bakers stall", 120_000);
      return;
    }

    stealOnce();
  }

  // ---------------------------------------------------------------------------------------------
  // Opposite-side stealing
  // ---------------------------------------------------------------------------------------------

  private void stealOnce() {
    Controller c = c();
    findStall();
    readBlocked(false);
    int[] baker = npcTile(BAKER);
    bakerAt = baker;
    List<int[]> guards = npcTiles(GUARD, 12);

    int[] best = chooseTile(baker, guards, false);
    if (best == null && noTileSince > 0 && System.currentTimeMillis() - noTileSince > 8000)
      best = chooseTile(baker, guards, true); // he's been watching every side for 8 s: furthest tile
    if (best == null) {
      if (noTileSince == 0) noTileSince = System.currentTimeMillis();
      // every tile visible right now: wait a moment for the baker to move (not a stall)
      waitsNoTile++;
      setState("Waiting - the baker or a guard can see every side");
      notStalled(5000);
      c.sleep(600);
      return;
    }
    noTileSince = 0;
    String side = sideOf(best);
    if (tile == null || tile[0] != best[0] || tile[1] != best[1]) {
      if (!lastSide.isEmpty() && !side.equals(lastSide)) {
        sideSwitches++;
        sSwitch++;
      }
      tile = best;
      tileSide = side;
    }
    lastSide = side;

    if (c.currentX() != tile[0] || c.currentY() != tile[1]) {
      setState("Walking round to the " + side + " side");
      if (!walkToTile(tile[0], tile[1], 8000)) {
        badTiles.put(key(tile[0], tile[1]), System.currentTimeMillis() + BLACKLIST_MS);
        cantReach++;
        sReach++;
        tile = null;
      }
      return; // re-check the baker before stealing
    }

    int id = c.getObjectAtCoord(stall[0], stall[1]);
    if (id != STALL) {
      setState("Stall empty - waiting");
      notStalled(5000);
      waitFor(() -> c.getObjectAtCoord(stall[0], stall[1]) == STALL || c.isInCombat(), 1200);
      return;
    }

    // last look right before the click
    int[] b2 = npcTile(BAKER);
    guards = npcTiles(GUARD, 12);
    if ((bakerSees(b2, tile[0], tile[1]) || guardSees(guards, tile[0], tile[1], 0)) && chooseTile(b2, guards, false) != null) {
      tile = null;
      return;
    }

    setState("Stealing (" + side + " side)");
    long t0 = System.currentTimeMillis();
    attemptAt = t0;
    c.atObject2(stall[0], stall[1]);
    boolean done =
        waitFor(() -> stoleAt >= t0 || caughtAt >= t0 || cantReachAt >= t0 || fullMsgAt >= t0 || c.isInCombat(), 4500);
    String result;
    if (stoleAt >= t0) {
      steals++;
      sSteals++;
      result = "stole";
    } else if (caughtAt >= t0) {
      sCaught++;
      result = caughtBy == GUARD ? "CAUGHT by a guard" : "CAUGHT by the baker";
    } else if (cantReachAt >= t0) {
      badTiles.put(key(tile[0], tile[1]), System.currentTimeMillis() + BLACKLIST_MS);
      cantReach++;
      sReach++;
      result = "can't reach";
      tile = null;
    } else {
      result = c.isInCombat() ? "attacked" : done ? "other" : "no answer";
    }
    sSides.merge(side, 1, Integer::sum);
    if (debugLog)
      log("tile " + (tile == null ? "-" : tile[0] + "," + tile[1]) + " " + side + " | baker "
          + (b2 == null ? "none" : b2[2] + "," + b2[3] + " " + bakerSide(new int[] {b2[2], b2[3]})) + " | " + result);
    c.sleep(300);
  }

  /** Reads the stall position (and its size from the object definition). */
  private void findStall() {
    Controller c = c();
    if (c.getObjectAtCoord(STALL_X, STALL_Y) == STALL || c.getObjectAtCoord(STALL_X, STALL_Y) == EMPTY_STALL) {
      stall[0] = STALL_X;
      stall[1] = STALL_Y;
    } else {
      int[][] found = c.getObjectsById(STALL);
      if (found != null && found.length > 0) {
        int[] best = found[0];
        for (int[] f : found) if (c.distanceTo(f[0], f[1]) < c.distanceTo(best[0], best[1])) best = f;
        stall[0] = best[0];
        stall[1] = best[1];
      }
    }
    try {
      com.openrsc.client.entityhandling.defs.GameObjectDef d =
          com.openrsc.client.entityhandling.EntityHandler.getObjectDef(STALL);
      if (d != null && d.getWidth() > 0 && d.getHeight() > 0) {
        stall[2] = d.getWidth();
        stall[3] = d.getHeight();
      }
    } catch (Throwable ignored) {
      // keep 2x2
    }
  }

  /** Full-block (type 1) scenery tiles around the stall, as the server's traversal mask 64. */
  private void readBlocked(boolean force) {
    long now = System.currentTimeMillis();
    if (!force && now - blockedReadAt < 30_000 && !blocked.isEmpty()) return;
    blockedReadAt = now;
    Set<Long> b = new HashSet<>();
    Controller c = c();
    for (int x = stall[0] - 10; x <= stall[0] + 10; x++) {
      for (int y = stall[1] - 10; y <= stall[1] + 10; y++) {
        int id = c.getObjectAtCoord(x, y);
        if (id < 0) continue;
        int w = 1, h = 1, type = 0;
        try {
          com.openrsc.client.entityhandling.defs.GameObjectDef d =
              com.openrsc.client.entityhandling.EntityHandler.getObjectDef(id);
          type = d.getType();
          w = Math.max(1, d.getWidth());
          h = Math.max(1, d.getHeight());
        } catch (Throwable ignored) {
          continue;
        }
        if (type != 1) continue;
        for (int dx = 0; dx < w; dx++) for (int dy = 0; dy < h; dy++) b.add(key(x + dx, y + dy));
      }
    }
    // the stall always blocks, even if the object list is mid-reload
    for (int dx = 0; dx < stall[2]; dx++) for (int dy = 0; dy < stall[3]; dy++) b.add(key(stall[0] + dx, stall[1] + dy));
    blocked = b;
  }

  /** Exact replay of the server's Thieving.canBeSeen(from -> target). */
  private boolean seenFrom(int fromX, int fromY, int targetX, int targetY) {
    int count = 0;
    while (true) {
      if (count++ > 10) return true;
      if (fromY < targetY) fromY++;
      if (fromY > targetY) fromY--;
      if (fromX >= targetX) fromX--;
      if (fromX <= targetX) fromX++;
      if (blocked.contains(key(fromX, fromY))) return false;
      if (fromX == targetX && fromY == targetY) return true;
    }
  }

  private boolean inStall(int x, int y) {
    return x >= stall[0] && x < stall[0] + stall[2] && y >= stall[1] && y < stall[1] + stall[3];
  }

  /** The tiles next to the stall's edges (no corners). */
  private List<int[]> candidateTiles() {
    List<int[]> t = new ArrayList<>();
    for (int dx = 0; dx < stall[2]; dx++) {
      t.add(new int[] {stall[0] + dx, stall[1] - 1});
      t.add(new int[] {stall[0] + dx, stall[1] + stall[3]});
    }
    for (int dy = 0; dy < stall[3]; dy++) {
      t.add(new int[] {stall[0] - 1, stall[1] + dy});
      t.add(new int[] {stall[0] + stall[2], stall[1] + dy});
    }
    return t;
  }

  /**
   * Best stall-side tile: hidden from the baker now (required), then hidden from where he can step
   * next, hidden from guards, on the far side, free of players, close. Null when none is hidden.
   */
  private int[] chooseTile(int[] baker, List<int[]> guards, boolean allowSeen) {
    Controller c = c();
    long now = System.currentTimeMillis();
    badTiles.values().removeIf(t -> t < now);
    double cx = stall[0] + (stall[2] - 1) / 2.0, cy = stall[1] + (stall[3] - 1) / 2.0;
    Set<Long> players = playerTiles();
    int[] best = null;
    double bestScore = Double.MAX_VALUE;
    for (int[] t : candidateTiles()) {
      if (badTiles.containsKey(key(t[0], t[1])) || blocked.contains(key(t[0], t[1]))) continue;
      double score = 0;
      if (baker != null) {
        if ((t[0] == baker[0] && t[1] == baker[1]) || (t[0] == baker[2] && t[1] == baker[3])) score += 30;
        if (bakerSees(baker, t[0], t[1])) {
          if (!allowSeen) continue; // he would see us now
          // fallback: as far from him as possible
          score += 100 - 5 * Math.max(Math.abs(t[0] - baker[2]), Math.abs(t[1] - baker[3]));
        }
        // where he can be by the time the steal resolves (3 ticks): 1-2 steps from his server tile
        for (int dx = -2; dx <= 2; dx++)
          for (int dy = -2; dy <= 2; dy++) {
            int nx = baker[2] + dx, ny = baker[3] + dy;
            if ((dx == 0 && dy == 0) || inStall(nx, ny) || blocked.contains(key(nx, ny))) continue;
            if (nx < BAKER_X0 || nx > BAKER_X1 || ny < BAKER_Y0 || ny > BAKER_Y1) continue;
            if (seenFrom(nx, ny, t[0], t[1])) score += Math.max(Math.abs(dx), Math.abs(dy)) == 1 ? 10 : 3;
          }
        // the far side from him
        double dot = (t[0] - cx) * (cx - baker[2]) + (t[1] - cy) * (cy - baker[3]);
        if (dot <= 0) score += 5;
      }
      if (guardSees(guards, t[0], t[1], 0)) {
        if (!allowSeen) continue; // a guard would catch us (and attack)
        score += 60;
      } else if (guardSees(guards, t[0], t[1], 1)) score += 8; // one step from catching us
      // another account on the tile: two thieves clicking in the same tick both lose the steal
      if (players.contains(key(t[0], t[1])) && !(t[0] == c.currentX() && t[1] == c.currentY())) score += 12;
      score += 0.5 * Math.max(Math.abs(t[0] - c.currentX()), Math.abs(t[1] - c.currentY()));
      if (tile != null && t[0] == tile[0] && t[1] == tile[1]) score -= 1; // don't dither
      if (score < bestScore) {
        bestScore = score;
        best = t;
      }
    }
    return best;
  }

  private String sideOf(int[] t) {
    // RSC: x grows to the west, y grows to the south
    if (t[1] < stall[1]) return "north";
    if (t[1] >= stall[1] + stall[3]) return "south";
    if (t[0] < stall[0]) return "east";
    return "west";
  }

  private String bakerSide(int[] b) {
    String ns = b[1] < stall[1] ? "N" : b[1] >= stall[1] + stall[3] ? "S" : "";
    String ew = b[0] < stall[0] ? "E" : b[0] >= stall[0] + stall[2] ? "W" : "";
    return ns + ew;
  }

  /**
   * The NPC's tile as {drawn x, drawn y, latest server x, latest server y}: the client draws a
   * walking NPC between tiles, the newest waypoint is where the server already has him.
   */
  private int[] npcTile(int id) {
    Controller c = c();
    ORSCharacter n = c.getNearestNpcById(id, true);
    if (n == null) return null;
    int[] p = c.getNpcCoordsByServerIndex(n.serverIndex);
    if (p == null || p[0] < 0) return null;
    int lx = p[0], ly = p[1];
    try {
      int i = n.waypointIndexCurrent;
      if (i >= 0 && i < n.waypointsX.length) {
        lx = c.convertX(n.waypointsX[i]);
        ly = c.convertZ(n.waypointsZ[i]);
        if (Math.abs(lx - p[0]) > 3 || Math.abs(ly - p[1]) > 3) {
          lx = p[0];
          ly = p[1];
        }
      }
    } catch (Throwable ignored) {
      // fall back to the drawn tile
    }
    return new int[] {p[0], p[1], lx, ly};
  }

  private boolean bakerSees(int[] b, int x, int y) {
    if (b == null) return false;
    boolean near0 = Math.max(Math.abs(b[0] - x), Math.abs(b[1] - y)) <= 7;
    boolean near1 = Math.max(Math.abs(b[2] - x), Math.abs(b[3] - y)) <= 7;
    return (near0 && seenFrom(b[0], b[1], x, y)) || (near1 && seenFrom(b[2], b[3], x, y));
  }

  /** All NPCs of this id within range of the stall, as {drawn x, y, latest server x, y}. */
  private List<int[]> npcTiles(int id, int range) {
    Controller c = c();
    List<int[]> out = new ArrayList<>();
    try {
      for (ORSCharacter n : c.getNpcs()) {
        if (n == null || n.npcId != id) continue;
        int[] p = c.getNpcCoordsByServerIndex(n.serverIndex);
        if (p == null || p[0] < 0 || Math.max(Math.abs(p[0] - stall[0]), Math.abs(p[1] - stall[1])) > range) continue;
        int lx = p[0], ly = p[1];
        int i = n.waypointIndexCurrent;
        if (i >= 0 && i < n.waypointsX.length) {
          lx = c.convertX(n.waypointsX[i]);
          ly = c.convertZ(n.waypointsZ[i]);
          if (Math.abs(lx - p[0]) > 3 || Math.abs(ly - p[1]) > 3) {
            lx = p[0];
            ly = p[1];
          }
        }
        out.add(new int[] {p[0], p[1], lx, ly});
      }
    } catch (Throwable ignored) {
      // list changing
    }
    return out;
  }

  /** A guard catches you from up to 4 tiles away when he can see you ("Get your hands off there!"). */
  private boolean guardSees(List<int[]> guards, int x, int y, int extra) {
    for (int[] g : guards) {
      for (int k = 0; k <= 2; k += 2) {
        if (Math.max(Math.abs(g[k] - x), Math.abs(g[k + 1] - y)) <= 4 + extra && seenFrom(g[k], g[k + 1], x, y)) return true;
      }
    }
    return false;
  }

  private Set<Long> playerTiles() {
    Controller c = c();
    Set<Long> s = new HashSet<>();
    try {
      for (ORSCharacter p : c.getPlayers()) {
        if (p == null) continue;
        s.add(key(c.convertX(p.currentX), c.convertZ(p.currentZ)));
      }
    } catch (Throwable ignored) {
      // list changing
    }
    return s;
  }

  /** Walks onto an exact tile (around the stall by the client's path finding). */
  private boolean walkToTile(int x, int y, long ms) {
    Controller c = c();
    long end = System.currentTimeMillis() + ms;
    long lastClick = 0;
    while (c.isRunning() && System.currentTimeMillis() < end && (c.currentX() != x || c.currentY() != y)) {
      if (c.isInCombat()) return true; // handled by the loop
      if (System.currentTimeMillis() - lastClick > 1800 || !c.isCurrentlyWalking()) {
        if (lastClick != 0 && !c.isCurrentlyWalking() && !c.isReachable(x, y, false)) return false;
        c.walkToAsync(x, y, 0);
        lastClick = System.currentTimeMillis();
      }
      c.sleep(150);
      // the baker may have walked round to this side meanwhile: re-pick
      int[] b = npcTile(BAKER);
      if (bakerSees(b, x, y) && c.distanceTo(x, y) > 1) return true;
    }
    return c.currentX() == x && c.currentY() == y || c.isInCombat() || System.currentTimeMillis() < end;
  }

  private boolean walkNear(int x, int y, String name, long ms) {
    Controller c = c();
    setState("Walking to " + name);
    long end = System.currentTimeMillis() + ms;
    long lastClick = 0;
    while (c.isRunning() && System.currentTimeMillis() < end && c.distanceTo(x, y) > 1) {
      if (c.isInCombat()) {
        escapeFight();
        continue;
      }
      if (System.currentTimeMillis() - lastClick > 2400 || !c.isCurrentlyWalking()) {
        if (c.distanceTo(x, y) > 15) {
          if (!c.walkTowards(x, y)) c.walkToAsync(x, y, 1);
        } else c.walkToAsync(x, y, 0);
        lastClick = System.currentTimeMillis();
      }
      notStalled(5000);
      c.sleep(300);
    }
    return c.distanceTo(x, y) <= 1;
  }

  /**
   * Attacked (a guard that caught us chases and fights): walk away from the stall until the fight
   * drops, then wait until no guard can see the stall sides before going back.
   */
  private void escapeFight() {
    Controller c = c();
    if (!inFight) {
      inFight = true;
      attacked++;
    }
    setState("Attacked - walking away");
    // two spots ~6 tiles off on the bank side of the market; alternate so we keep moving
    int[][] spots = {{stall[0] + 3, stall[1] + 7}, {stall[0] - 4, stall[1] + 7}};
    int k = 0;
    long end = System.currentTimeMillis() + 30_000;
    while (c.isRunning() && c.isInCombat() && System.currentTimeMillis() < end) {
      int[] s = spots[k];
      c.walkToAsync(s[0], s[1], 0);
      c.sleep(700);
      if (c.distanceTo(s[0], s[1]) <= 1) k = 1 - k;
      notStalled(5000);
    }
    if (hpNow() * 100 < eatAtPercent * hpMax()) eat();
    // let the guard wander off before stealing again (the loop also refuses tiles a guard can see)
    c.sleep(1500);
    tile = null;
  }

  // ---------------------------------------------------------------------------------------------
  // Food, banking, dropping
  // ---------------------------------------------------------------------------------------------

  private int hpNow() {
    return c().getCurrentStat(3);
  }

  private int hpMax() {
    return Math.max(1, c().getBaseStat(3));
  }

  private int foodCount() {
    int n = 0;
    for (int id : FOOD) n += c().getInventoryItemCount(id);
    return n;
  }

  private boolean eat() {
    Controller c = c();
    for (int id : FOOD) {
      if (c.getInventoryItemCount(id) > 0) {
        int before = c.getInventoryItemCount();
        int hp = hpNow();
        setState("Eating");
        c.itemCommand(id);
        waitFor(() -> hpNow() > hp || c.getInventoryItemCount() != before, 3000);
        eaten++;
        c.sleep(300);
        return true;
      }
    }
    return false;
  }

  private boolean isLoot(int id) {
    for (int l : LOOT) if (l == id) return true;
    return false;
  }

  /** How many of this item to keep as food: the first {@code keepFood} food items, best first. */
  private int keepOf(int id) {
    Controller c = c();
    int left = keepFood;
    for (int f : new int[] {CAKE, PARTIAL_CAKE, SLICE, CHOC_SLICE, BREAD}) {
      int have = c.getInventoryItemCount(f);
      int k = Math.min(have, left);
      if (f == id) return k;
      left -= k;
    }
    return 0;
  }

  private void dropLoot() {
    Controller c = c();
    setState("Dropping loot");
    int n = 0;
    for (int id : LOOT) {
      int drop = c.getInventoryItemCount(id) - keepOf(id);
      for (int i = 0; i < drop && c.isRunning(); i++) {
        int slot = c.getInventoryItemSlotIndex(id);
        if (slot < 0) break;
        int before = c.getInventoryItemCount(id);
        c.dropItem(slot);
        waitFor(() -> c.getInventoryItemCount(id) < before, 2000);
        n++;
      }
    }
    dropped += n;
    if (n == 0) stopWithReason("Inventory is full of things that aren't stall loot - stopping (nothing to drop)");
  }

  private void bank() {
    Controller c = c();
    setState("Banking (inventory full)");
    if (!walkNear(BANK_TILES[0][0], BANK_TILES[0][1], "the bank", 180_000)) return;
    if (!openBank()) return;
    int n = 0;
    for (int id : LOOT) {
      int dep = c.getInventoryItemCount(id) - keepOf(id);
      if (dep <= 0) continue;
      int before = c.getInventoryItemCount(id);
      c.depositItem(id, dep);
      waitFor(() -> c.getInventoryItemCount(id) <= before - dep, 4000);
      n += before - c.getInventoryItemCount(id);
    }
    banked += n;
    bankTrips++;
    c.closeBank();
    c.sleep(600);
    log("Banked " + n + " (" + banked + " this run, trip " + bankTrips + ") - back to the stall");
    if (c.getInventoryItemCount() >= 30) {
      stopWithReason("Still full after banking the loot (other items) - stopping");
      return;
    }
    tile = null;
    walkNear(STALL_X - 1, STALL_Y + 1, "the Bakers stall", 120_000);
  }

  /**
   * Opens the bank by talking to a free banker. A banker that gives "I can't get close enough" is
   * skipped for 60 s and we step to another counter tile. Busy bankers: wait and retry (not a stall).
   */
  private boolean openBank() {
    Controller c = c();
    long end = System.currentTimeMillis() + 90_000;
    int tileIdx = 0;
    while (c.isRunning() && !c.isInBank() && System.currentTimeMillis() < end) {
      if (c.isInCombat()) {
        escapeFight();
        return false;
      }
      long now = System.currentTimeMillis();
      badBankers.values().removeIf(t -> t < now);
      ORSCharacter banker = null;
      int bestD = Integer.MAX_VALUE;
      for (ORSCharacter n : c.getNpcs()) {
        if (n == null || n.npcId != BANKER_ID || badBankers.containsKey(n.serverIndex)) continue;
        int[] p = c.getNpcCoordsByServerIndex(n.serverIndex);
        if (p == null || p[0] < 0) continue;
        int d = c.distanceTo(p[0], p[1]);
        if (d < bestD) {
          bestD = d;
          banker = n;
        }
      }
      if (banker == null) {
        setState("Bank busy - waiting for a banker");
        notStalled(10_000);
        c.sleep(3000);
        continue;
      }
      if (c.isInOptionMenu()) {
        c.optionAnswer(0);
        waitFor(c::isInBank, 5000);
        continue;
      }
      long t0 = System.currentTimeMillis();
      if ("Bank".equalsIgnoreCase(String.valueOf(c.getNpcCommand1(BANKER_ID)))) c.npcCommand1(banker.serverIndex);
      else c.talkToNpc(banker.serverIndex);
      waitFor(() -> c.isInBank() || c.isInOptionMenu() || cantReachAt >= t0, 6000);
      if (c.isInOptionMenu()) {
        c.optionAnswer(0);
        waitFor(c::isInBank, 6000);
      }
      if (!c.isInBank()) {
        if (cantReachAt >= t0) badBankers.put(banker.serverIndex, System.currentTimeMillis() + BLACKLIST_MS);
        else badBankers.put(banker.serverIndex, System.currentTimeMillis() + 10_000); // busy: try another
        tileIdx = (tileIdx + 1) % BANK_TILES.length;
        c.walkToAsync(BANK_TILES[tileIdx][0], BANK_TILES[tileIdx][1], 0);
        notStalled(10_000);
        c.sleep(1200);
      }
    }
    if (!c.isInBank()) log("Couldn't open the bank for 90 s - will try again");
    return c.isInBank();
  }

  // ---------------------------------------------------------------------------------------------
  // Logging, stop
  // ---------------------------------------------------------------------------------------------

  private void summary(boolean force) {
    long now = System.currentTimeMillis();
    if (!force && now - summaryAt < 60_000) return;
    if (sSteals + sCaught + sSwitch + sReach > 0 || force) {
      StringBuilder sides = new StringBuilder();
      for (Map.Entry<String, Integer> e : sSides.entrySet()) {
        if (sides.length() > 0) sides.append(" ");
        sides.append(e.getKey()).append(" ").append(e.getValue());
      }
      log("Last " + ((now - summaryAt + 500) / 1000) + "s: stole " + sSteals + ", caught " + sCaught
          + ", side switches " + sSwitch + (sReach > 0 ? ", bad tiles " + sReach : "") + (sides.length() > 0 ? " (" + sides + ")" : "")
          + " | total " + steals + " stolen, " + banked + (bankWhenFull ? " banked" : " dropped " + dropped));
    }
    summaryAt = now;
    sSteals = sCaught = sSwitch = sReach = 0;
    sSides.clear();
  }

  /** Never stops within 15 s of the setup window closing (v15.6 queue rule). Weapon back on first. */
  private void stopWithReason(String why) {
    Controller c = c();
    setState("Stopped");
    c.log(why, "red");
    log(why);
    weapon.putBack(false);
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
    summary(true);
    if (c.isRunning()) c.stop();
  }

  public String getStatusSuffix() {
    Controller c = c();
    if (c == null) return null;
    return "Thv " + c.getBaseStat(17) + " - " + steals + " cakes - " + state;
  }

  // ---------------------------------------------------------------------------------------------
  // Setup
  // ---------------------------------------------------------------------------------------------

  private boolean setup(String[] param) {
    String joined = param == null ? "" : String.join(" ", param).trim();
    if (!joined.isEmpty()) {
      for (String a : joined.split("[\\s,]+")) {
        String[] kv = a.split("[=:]", 2);
        String k = kv[0].toLowerCase();
        String v = kv.length > 1 ? kv[1].toLowerCase() : "on";
        boolean on = !(v.equals("off") || v.equals("false") || v.equals("0") || v.equals("no"));
        switch (k) {
          case "bank":
            bankWhenFull = on;
            break;
          case "power":
          case "drop":
            bankWhenFull = !on;
            break;
          case "full":
            bankWhenFull = !v.startsWith("drop");
            break;
          case "keep":
            keepFood = parseInt(v, 2, 0, 28);
            break;
          case "eatat":
            eatAtPercent = parseInt(v.replace("%", ""), 80, 10, 99);
            break;
          case "debug":
            debugLog = on;
            break;
          default:
        }
      }
      return true;
    }
    if (java.awt.GraphicsEnvironment.isHeadless()) return true;
    SwingUtilities.invokeLater(this::showSetup);
    Controller c = c();
    while (c.isRunning() && !setupDone && !setupCancelled) c.sleep(200);
    if (!setupDone) {
      log("Setup window closed - not starting");
      c.stop();
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
    JFrame f = new JFrame(NAME);
    JPanel outer = new JPanel(new BorderLayout(0, 6));
    outer.setBackground(BG);
    outer.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(2, 2, 2, 2, GOLD), BorderFactory.createEmptyBorder(8, 12, 10, 12)));
    JLabel title = new JLabel("\u2726 StealCake \u2726", SwingConstants.CENTER);
    title.setForeground(GOLD);
    title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
    JLabel sub = new JLabel("Bakers stall, East Ardougne market - steals from the side the baker can't see", SwingConstants.CENTER);
    sub.setForeground(new Color(0xA89060));
    JPanel head = new JPanel(new GridLayout(0, 1));
    head.setOpaque(false);
    head.add(title);
    head.add(sub);

    JComboBox<String> fullBox = new JComboBox<>(new String[] {"Bank (Ardougne south bank)", "Drop the loot"});
    fullBox.setSelectedIndex(bankWhenFull ? 0 : 1);
    JTextField keepField = new JTextField(String.valueOf(keepFood), 3);
    JTextField eatField = new JTextField(String.valueOf(eatAtPercent), 3);
    JCheckBox debug = style(new JCheckBox("Log every steal (tile / baker position) - for testing", debugLog));

    JPanel p = new JPanel(new GridLayout(0, 1, 4, 4));
    p.setOpaque(false);
    p.add(row("When the inventory is full:", fullBox));
    JPanel food = row("Cakes to keep for food:", keepField);
    food.add(style(new JLabel("Eat at HP %:")));
    food.add(style(eatField));
    p.add(food);
    p.add(debug);

    JButton start = new JButton("Start");
    start.setBackground(GOLD);
    start.setForeground(Color.BLACK);
    start.setFont(start.getFont().deriveFont(Font.BOLD));
    start.addActionListener(
        e -> {
          bankWhenFull = fullBox.getSelectedIndex() == 0;
          keepFood = parseInt(keepField.getText(), 2, 0, 28);
          eatAtPercent = parseInt(eatField.getText().replace("%", ""), 80, 10, 99);
          debugLog = debug.isSelected();
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
  // Messages and paint
  // ---------------------------------------------------------------------------------------------

  private void onMessage(String message) {
    if (message == null) return;
    String m = message.toLowerCase();
    long now = System.currentTimeMillis();
    if (m.contains("you steal a")) stoleAt = now;
    else if (m.contains("hey thats mine") || m.contains("hey that's mine")) {
      if (!(caughtBy == BAKER && now - caughtAt < 1500)) caughtBaker++;
      caughtBy = BAKER;
      caughtAt = now;
    } else if (m.contains("get your hands off")) {
      if (!(caughtBy == GUARD && now - caughtAt < 1500)) caughtGuard++;
      caughtBy = GUARD;
      caughtAt = now;
    } else if (m.contains("can't get close enough") || m.contains("cannot get close enough")) cantReachAt = now;
    else if (m.contains("inventory is full") || m.contains("don't have room")) fullMsgAt = now;
  }

  @Override
  public void serverMessageInterrupt(String message) {
    onMessage(message);
  }

  @Override
  public void questMessageInterrupt(String message) {
    onMessage(message);
  }

  @Override
  public void paintInterrupt() {
    Controller c = c();
    if (c != null) {
      int xp = c.getStatXp(17) - startXp;
      int[] gold = {0xD4AF37, 0xE0E0E0, 0xFFE066};
      int[] cols = {4, 82, 58};
      paintBuilder.setTitleCenteredSingleColor("StealCake", 0xD4AF37, 4);
      paintBuilder.addSpacerRow(4);
      paintBuilder.setBackgroundColor(0x282828, 200);
      paintBuilder.setBorderColor(0xD4AF37);
      paintBuilder.addRow(rowBuilder.centeredSingleStringRow(state, 0xFFE066, 1));
      paintBuilder.addSpacerRow(4);
      paintBuilder.addRow(rowBuilder.multipleStringRow(
          new String[] {"Cakes:", paintBuilder.stringFormatInt(steals), paintBuilder.stringAmountPerHour(steals)}, gold, cols, 1));
      paintBuilder.addRow(rowBuilder.multipleStringRow(
          new String[] {"Thieving " + c.getBaseStat(17) + ":", paintBuilder.stringFormatInt(xp), paintBuilder.stringAmountPerHour(xp)}, gold, cols, 1));
      paintBuilder.addRow(rowBuilder.multipleStringRow(
          new String[] {"Caught:", caughtBaker + " baker", caughtGuard + " guard"}, gold, cols, 1));
      paintBuilder.addRow(rowBuilder.multipleStringRow(
          new String[] {"Side:", tile == null ? "-" : tileSide, sideSwitches + " switches"}, gold, cols, 1));
      paintBuilder.addRow(rowBuilder.multipleStringRow(
          new String[] {"Baker:", bakerAt == null ? "not seen" : bakerSide(new int[] {bakerAt[2], bakerAt[3]}) + " " + bakerAt[2] + "," + bakerAt[3],
              cantReach > 0 ? cantReach + " bad tiles" : ""}, gold, cols, 1));
      if (bankWhenFull)
        paintBuilder.addRow(rowBuilder.multipleStringRow(new String[] {"Banked:", paintBuilder.stringFormatInt(banked), bankTrips + " trips"}, gold, cols, 1));
      else
        paintBuilder.addRow(rowBuilder.multipleStringRow(new String[] {"Dropped:", paintBuilder.stringFormatInt(dropped), ""}, gold, cols, 1));
      paintBuilder.addRow(rowBuilder.multipleStringRow(
          new String[] {"HP:", hpNow() + "/" + hpMax(), foodCount() + " food, " + eaten + " eaten"}, gold, cols, 1));
      if (attacked > 0) paintBuilder.addRow(rowBuilder.centeredSingleStringRow("Attacked: " + attacked, 0xFF6060, 1));
      String wt = weapon.paintText();
      if (!wt.isEmpty()) paintBuilder.addRow(rowBuilder.centeredSingleStringRow("Weapon: " + wt, 0xFFB366, 1));
    }
    paintBuilder.draw();
  }

  // ---------------------------------------------------------------------------------------------
  // Weapon guard (same as Ben's other thieving scripts): nothing in the weapon hand while thieving.
  // The weapon id is saved per account in settings/weapon-guard/StealCake_<account>.txt (survives a
  // reload or crash) and put back on before the script stops. Shields and armour stay on.
  // ---------------------------------------------------------------------------------------------

  final WeaponGuard weapon = new WeaponGuard(NAME);

  @Override
  public void cleanup() {
    try {
      weapon.putBack(true); // stop button / queue stop
    } catch (Throwable ignored) {
      // never block a stop
    }
    super.cleanup();
  }

  final class WeaponGuard {
    final String script;
    int remembered = -1;
    String rememberedName = "";
    boolean loaded = false;
    int takenOff = 0;
    int putBackCount = 0;

    WeaponGuard(String script) {
      this.script = script;
    }

    private Controller ctl() {
      return Main.getController();
    }

    private void say(String s) {
      Main.log("[" + script + "] " + s);
      Controller c = ctl();
      if (c != null) c.setStatus("@cya@" + s);
    }

    private java.io.File file() {
      Controller c = ctl();
      String acct = c == null ? null : c.getPlayerName();
      if (acct == null || acct.trim().isEmpty()) return null;
      acct = acct.trim().toLowerCase().replaceAll("[^a-z0-9_-]", "_");
      return new java.io.File(new java.io.File("settings", "weapon-guard"), script + "_" + acct + ".txt");
    }

    /** Reads a weapon remembered by an earlier run (reload / crash mid-thieving). */
    void load() {
      if (loaded) return;
      java.io.File f = file();
      if (f == null) return;
      loaded = true;
      try {
        if (!f.exists()) return;
        String[] p = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8)
            .trim().split("\\|", 2);
        remembered = Integer.parseInt(p[0].trim());
        rememberedName = p.length > 1 ? p[1].trim() : "item " + remembered;
        say("Remembered weapon from the last run: " + rememberedName + " (taken off for thieving)");
      } catch (Exception e) {
        remembered = -1;
      }
    }

    private void save() {
      java.io.File f = file();
      if (f == null) return;
      try {
        f.getParentFile().mkdirs();
        java.nio.file.Files.write(f.toPath(), (remembered + "|" + rememberedName + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      } catch (Exception e) {
        Main.log("[" + script + "] couldn't save the weapon file: " + e);
      }
    }

    private void forget() {
      remembered = -1;
      rememberedName = "";
      java.io.File f = file();
      if (f != null) f.delete();
    }

    /** Item id in the weapon hand, or -1 (equipment tab on Coleslaw, the inventory elsewhere). */
    int wielded() {
      Controller c = ctl();
      if (c == null) return -1;
      try {
        if (orsc.Config.S_WANT_EQUIPMENT_TAB) {
          com.openrsc.client.entityhandling.defs.ItemDef[] eq = c.getMud().equippedItems;
          return eq != null && eq.length > 4 && eq[4] != null ? eq[4].id : -1;
        }
        for (com.openrsc.client.entityhandling.instances.Item it : c.getInventoryItems()) {
          com.openrsc.client.entityhandling.defs.ItemDef d = it.getItemDef();
          if (d != null && it.getEquipped() && d.wieldable && (d.wearableID & 16) != 0) return d.id;
        }
      } catch (Throwable ignored) {
        // client not ready
      }
      return -1;
    }

    /** True for the remembered weapon: never deposit, drop or sell it. */
    boolean isProtected(int id) {
      return remembered >= 0 && id == remembered;
    }

    private boolean waitUntil(java.util.function.BooleanSupplier ok, long ms) {
      long until = System.currentTimeMillis() + ms;
      while (System.currentTimeMillis() < until) {
        if (ok.getAsBoolean()) return true;
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return ok.getAsBoolean();
        }
      }
      return ok.getAsBoolean();
    }

    /**
     * Takes the weapon off before thieving. Full inventory (equipment tab needs a free slot):
     * {@code makeRoom} is asked to free one. @return true when the weapon hand is empty
     */
    boolean takeOff(Runnable makeRoom) {
      Controller c = ctl();
      if (c == null || !c.isLoggedIn()) return false;
      load();
      int w = wielded();
      if (w < 0) return true;
      boolean tab = orsc.Config.S_WANT_EQUIPMENT_TAB;
      if (tab && c.getInventoryItemCount() >= 30 && makeRoom != null) makeRoom.run();
      if (tab && c.getInventoryItemCount() >= 30) {
        say("Inventory full - can't take the " + c.getItemName(w) + " off for thieving");
        return false;
      }
      if (c.isInBank()) {
        c.closeBank();
        c.sleep(640);
      }
      remembered = w;
      rememberedName = c.getItemName(w);
      save(); // first, so a crash right now still puts it back next time
      for (int i = 0; i < 3 && wielded() == w; i++) {
        if (tab) {
          orsc.mudclient m = c.getMud();
          while (m.packetHandler.getClientStream().hasFinishedPackets()) c.sleep(1);
          m.packetHandler.getClientStream().newPacket(168); // unequip from the equipment tab
          m.packetHandler.getClientStream().bufferBits.putByte(4); // weapon slot
          m.packetHandler.getClientStream().finishPacket();
        } else {
          c.unequipItem(c.getInventoryItemSlotIndex(w));
        }
        waitUntil(() -> wielded() != w, 3000);
      }
      if (wielded() == w) {
        say("Couldn't take the " + rememberedName + " off - thieving with it");
        return false;
      }
      takenOff++;
      say("Took the " + rememberedName + " off for thieving (it goes back on before the script stops)");
      return true;
    }

    /** Puts the remembered weapon back on. @return true when nothing is left to do */
    boolean putBack(boolean quick) {
      Controller c = ctl();
      if (c == null) return remembered < 0;
      load();
      if (remembered < 0) return true;
      if (!c.isLoggedIn()) return false;
      int w = wielded();
      if (w == remembered) {
        forget();
        return true;
      }
      if (w >= 0) {
        say("Holding a " + c.getItemName(w) + " now - leaving it (the " + rememberedName + " stays in the inventory)");
        forget();
        return true;
      }
      if (c.getInventoryItemCount(remembered) < 1) {
        say("The " + rememberedName + " isn't in the inventory - can't put it back on");
        return false;
      }
      if (c.isInBank()) c.closeBank();
      final int id = remembered;
      String name = rememberedName;
      for (int i = 0; i < (quick ? 1 : 3) && wielded() != id; i++) {
        c.equipItemById(id);
        waitUntil(() -> wielded() == id, quick ? 1500 : 3000);
      }
      if (wielded() == id) {
        putBackCount++;
        say("Put the " + name + " back on");
        forget();
        return true;
      }
      say("Couldn't put the " + name + " back on yet - will try again next start");
      return false;
    }

    String paintText() {
      return remembered >= 0 ? rememberedName + " off" : putBackCount > 0 ? "back on" : "";
    }
  }
}
