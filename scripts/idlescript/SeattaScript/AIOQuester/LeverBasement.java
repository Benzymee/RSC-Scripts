package scripting.idlescript.SeattaScript.AIOQuester;

import controller.Controller;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Draynor Manor's lever basement (Ernest the Chicken): six levers A-F, seven rooms and nine
 * lever-locked doors. Gets the player from whatever room they're in to any other room, from any
 * lever state, including after a restart mid-puzzle.
 *
 * <p>Data (OpenRSC Core-Framework): levers are scenery 124-129 (LeverA..F, commands Pull/Inspect)
 * at A 225,3386, B 222,3382, C 222,3378, D 223,3375, E 229,3375, F 230,3376 (SceneryLocs.json);
 * doors are boundaries 25-33 (BoundaryLocs.json); the door rules are ErnestTheChicken.onOpBound
 * (quests/free/ErnestTheChicken.java). The lever positions live in the player's server cache, not
 * in the object (the lever object id never changes), so the only way to read one is its Inspect
 * message ("The lever is up/down"), and only for levers in the room we're in. Climbing the
 * basement ladder up clears them all (the cache keys are removed: every door locked = all up).
 *
 * <p>So the solver keeps a belief: the set of the 64 lever states still possible given what it
 * has seen (inspect/pull messages, doors that opened or said "The door is locked"). It plans with
 * a breadth-first search over (room, levers) for every possible state, follows the shortest plan
 * and re-plans whenever an observation rules out the state it was planning for. The old solver
 * assumed every lever it couldn't see was up, which made the C/D room a dead end when A/B were
 * really down ("no lever sequence from the lever C/D room; resetting" forever).
 */
public final class LeverBasement {
  public static final int R_LADDER = 0, R_OIL = 1, R_CD = 2, R_MID_N = 3, R_MID_S = 4, R_EF = 5,
      R_E_S = 6;
  public static final String[] ROOM_NAMES = {
    "ladder room", "oil can room", "lever C/D room", "middle north room", "middle south room",
    "lever E/F room", "south-east room"
  };
  /** Room bounds {minX, minY, maxX, maxY}. */
  static final int[][] ROOMS = {
    {222, 3381, 227, 3386}, // ladder room: levers A, B; ladder 223,3385
    {228, 3381, 233, 3383}, // oil can (spawn 232,3382)
    {222, 3375, 224, 3380}, // levers C, D
    {225, 3375, 227, 3377},
    {225, 3378, 227, 3380},
    {228, 3375, 230, 3377}, // levers E, F
    {228, 3378, 230, 3380},
  };
  /** Doors {boundaryId, x, y, room on the (x,y) tile, other room, otherX, otherY}. */
  static final int[][] DOORS = {
    {25, 225, 3376, R_MID_N, R_CD, 224, 3376},
    {26, 228, 3376, R_EF, R_MID_N, 227, 3376},
    {30, 226, 3378, R_MID_S, R_MID_N, 226, 3377},
    {31, 229, 3378, R_E_S, R_EF, 229, 3377},
    {27, 225, 3379, R_MID_S, R_CD, 224, 3379},
    {28, 228, 3379, R_E_S, R_MID_S, 227, 3379},
    {32, 223, 3381, R_LADDER, R_CD, 223, 3380},
    {33, 226, 3381, R_LADDER, R_MID_S, 226, 3380},
    {29, 228, 3382, R_OIL, R_LADDER, 227, 3382},
  };
  /** Levers A..F {x, y, room}. */
  static final int[][] LEVERS = {
    {225, 3386, R_LADDER}, {222, 3382, R_LADDER}, {222, 3378, R_CD},
    {223, 3375, R_CD}, {229, 3375, R_EF}, {230, 3376, R_EF},
  };
  public static final int[] LADDER_UP = {223, 3385};
  public static final int[] LADDER_STAND = {223, 3384};
  private static final long ALL = -1L; // all 64 lever states possible

  /** Bit s set = lever state s (bit i = lever i down) is still possible. Per tab (script class loader). */
  private static volatile long belief = ALL;

  private static volatile long lastLeverMsgAt = 0, lastLockedAt = 0;
  private static volatile String lastLeverMsg = "";

  private final Controller c;
  private final QuestWalker walker;

  public LeverBasement(Controller c, QuestWalker walker) {
    this.c = c;
    this.walker = walker;
  }

  // ================================================================ messages / resets

  /** Feed every server message here (QuestHandler does for AIOQuester quests). */
  public static void onServerMessage(String message) {
    if (message == null) return;
    String m = message.toLowerCase(Locale.ROOT);
    if (m.startsWith("you pull lever ") || m.startsWith("the lever is ")) {
      lastLeverMsg = m;
      lastLeverMsgAt = System.currentTimeMillis();
    } else if (m.contains("the door is locked")) {
      lastLockedAt = System.currentTimeMillis();
    }
  }

  /** Climbing the basement ladder up removes all lever keys on the server: all up. */
  public static void onLadderUp() {
    belief = 1L; // only state 0
  }

  /** Forget everything (e.g. levers changed while we weren't watching). */
  public static void forget() {
    belief = ALL;
  }

  // ================================================================ model

  public static int roomAt(int x, int y) {
    for (int i = 0; i < ROOMS.length; i++)
      if (x >= ROOMS[i][0] && x <= ROOMS[i][2] && y >= ROOMS[i][1] && y <= ROOMS[i][3]) return i;
    return -1;
  }

  public int room() {
    return roomAt(c.currentX(), c.currentY());
  }

  private static boolean down(int s, int lever) {
    return ((s >> lever) & 1) != 0;
  }

  /** The server's door rules (ErnestTheChicken.onOpBound); bit set = lever down. */
  static boolean doorOpens(int id, int s) {
    boolean a = down(s, 0), b = down(s, 1), cc = down(s, 2), d = down(s, 3), e = down(s, 4),
        f = down(s, 5);
    switch (id) {
      case 29:
        return !a && !b && cc && d && !e && f;
      case 28:
        return d;
      case 25:
        return !a && !b && d && e && f; // C either way
      case 26:
        return f && d && !b;
      case 27:
        return a && b && d;
      case 31:
        return d && !b && !f;
      case 32:
        return a && b && !cc && !d && !e && !f;
      case 33:
        return (!cc && d) || (!a && !b && cc && d && !e && f);
      case 30:
        return f && !e;
      default:
        return false;
    }
  }

  private static long filter(long set, java.util.function.IntPredicate keep) {
    long out = 0;
    for (int s = 0; s < 64; s++) if ((set >>> s & 1L) != 0 && keep.test(s)) out |= 1L << s;
    return out;
  }

  private static long flip(long set, int lever) {
    long out = 0;
    for (int s = 0; s < 64; s++) if ((set >>> s & 1L) != 0) out |= 1L << (s ^ (1 << lever));
    return out;
  }

  /** Narrows the belief; if an observation contradicts everything, starts over from it alone. */
  private void observe(long narrowed, String what) {
    if (narrowed == 0) {
      log("observation '" + what + "' contradicts what I thought the levers were; re-learning them");
      belief = ALL;
    } else belief = narrowed;
  }

  /** "A=down B=? C=up ..." from the current belief. */
  public static String describeLevers() {
    long b = belief;
    StringBuilder sb = new StringBuilder();
    for (int l = 0; l < 6; l++) {
      final int lv = l;
      boolean anyDown = filter(b, s -> down(s, lv)) != 0, anyUp = filter(b, s -> !down(s, lv)) != 0;
      sb.append((char) ('A' + l)).append('=').append(anyDown && anyUp ? "?" : anyDown ? "down" : "up");
      if (l < 5) sb.append(' ');
    }
    return sb.append(" (").append(Long.bitCount(b)).append(" possible)").toString();
  }

  private static String stateText(int s) {
    StringBuilder sb = new StringBuilder();
    for (int l = 0; l < 6; l++) sb.append((char) ('A' + l)).append(down(s, l) ? "v" : "^");
    return sb.toString();
  }

  /** Breadth-first search over (room, levers). Actions "L<idx>" / "D<doorIdx>", or null. */
  static List<String> solve(int fromRoom, int state, int toRoom) {
    Map<Long, String> prev = new HashMap<>();
    Map<Long, Long> parent = new HashMap<>();
    ArrayDeque<int[]> q = new ArrayDeque<>();
    long start = ((long) fromRoom << 8) | state;
    prev.put(start, null);
    q.add(new int[] {fromRoom, state});
    while (!q.isEmpty()) {
      int[] p = q.poll();
      long key = ((long) p[0] << 8) | p[1];
      if (p[0] == toRoom) {
        List<String> out = new ArrayList<>();
        Long k = key;
        while (k != null && prev.get(k) != null) {
          out.add(0, prev.get(k));
          k = parent.get(k);
        }
        return out;
      }
      for (int l = 0; l < LEVERS.length; l++) {
        if (LEVERS[l][2] != p[0]) continue;
        int ns = p[1] ^ (1 << l);
        long nk = ((long) p[0] << 8) | ns;
        if (prev.containsKey(nk)) continue;
        prev.put(nk, "L" + l);
        parent.put(nk, key);
        q.add(new int[] {p[0], ns});
      }
      for (int d = 0; d < DOORS.length; d++) {
        int other = DOORS[d][3] == p[0] ? DOORS[d][4] : DOORS[d][4] == p[0] ? DOORS[d][3] : -1;
        if (other < 0 || !doorOpens(DOORS[d][0], p[1])) continue;
        long nk = ((long) other << 8) | p[1];
        if (prev.containsKey(nk)) continue;
        prev.put(nk, "D" + d);
        parent.put(nk, key);
        q.add(new int[] {other, p[1]});
      }
    }
    return null;
  }

  private static String planText(List<String> plan) {
    StringBuilder sb = new StringBuilder();
    for (String s : plan) {
      if (sb.length() > 0) sb.append(", ");
      int i = Integer.parseInt(s.substring(1));
      if (s.charAt(0) == 'L') sb.append("pull ").append((char) ('A' + i));
      else sb.append("door ").append(DOORS[i][0]).append(" (").append(DOORS[i][1]).append(',').append(DOORS[i][2]).append(')');
    }
    return sb.toString();
  }

  // ================================================================ actions

  private boolean stepTo(int x, int y) {
    for (int i = 0; i < 3 && c.isRunning(); i++) {
      if (c.currentX() == x && c.currentY() == y) return true;
      c.walkToAsync(x, y, 0);
      c.sleepUntil(() -> c.currentX() == x && c.currentY() == y, 4000);
    }
    if (c.currentX() == x && c.currentY() == y) return true;
    return walker != null && walker.walkTo(x, y, 0, 15_000);
  }

  /** Inspects a lever in this room; narrows the belief. */
  private boolean inspect(int l) {
    stepNear(LEVERS[l][0], LEVERS[l][1]);
    long t = System.currentTimeMillis();
    c.atObject2(LEVERS[l][0], LEVERS[l][1]); // "Inspect"
    if (c.sleepUntil(() -> lastLeverMsgAt > t && lastLeverMsg.startsWith("the lever is"), 6000)) {
      boolean isDown = lastLeverMsg.contains("down");
      observe(filter(belief, s -> down(s, l) == isDown), "lever " + (char) ('A' + l) + " is " + (isDown ? "down" : "up"));
      return true;
    }
    log("no answer inspecting lever " + (char) ('A' + l));
    return false;
  }

  private void stepNear(int x, int y) {
    if (Math.max(Math.abs(c.currentX() - x), Math.abs(c.currentY() - y)) <= 1) return;
    int r = room();
    // any free tile next to the lever inside this room
    for (int dx = -1; dx <= 1; dx++)
      for (int dy = -1; dy <= 1; dy++) {
        int tx = x + dx, ty = y + dy;
        if ((dx == 0 && dy == 0) || roomAt(tx, ty) != r) continue;
        if (c.isReachable(tx, ty, false) && stepTo(tx, ty)) return;
      }
  }

  /** Pulls a lever; the message says where it ended up. */
  private boolean pull(int l) {
    char name = (char) ('a' + l);
    for (int attempt = 0; attempt < 3 && c.isRunning(); attempt++) {
      stepNear(LEVERS[l][0], LEVERS[l][1]);
      long t = System.currentTimeMillis();
      c.atObject(LEVERS[l][0], LEVERS[l][1]); // "Pull"
      if (c.sleepUntil(() -> lastLeverMsgAt > t && lastLeverMsg.startsWith("you pull lever " + name), 6000)) {
        boolean isDown = lastLeverMsg.contains(" down");
        observe(filter(flip(belief, l), s -> down(s, l) == isDown), "pulled " + Character.toUpperCase(name) + " " + (isDown ? "down" : "up"));
        log("pulled lever " + Character.toUpperCase(name) + " " + (isDown ? "down" : "up") + " -> " + describeLevers());
        c.sleep(640);
        return true;
      }
    }
    log("pulling lever " + Character.toUpperCase(name) + " gave no message");
    return false;
  }

  /** Tries door d from this room. 1 = through, 0 = locked (belief updated), -1 = couldn't try. */
  private int door(int d) {
    int[] door = DOORS[d];
    int here = room();
    if (door[3] != here && door[4] != here) return -1;
    int target = door[3] == here ? door[4] : door[3];
    int sx = door[3] == here ? door[1] : door[5];
    int sy = door[3] == here ? door[2] : door[6];
    for (int attempt = 0; attempt < 3 && c.isRunning(); attempt++) {
      stepTo(sx, sy);
      long t = System.currentTimeMillis();
      c.atWallObject(door[1], door[2]);
      c.sleepUntil(() -> room() == target || lastLockedAt > t, 6000);
      if (room() == target) {
        observe(filter(belief, s -> doorOpens(door[0], s)), "door " + door[0] + " opened");
        log("through door " + door[0] + " into the " + ROOM_NAMES[target]);
        c.sleep(640);
        return 1;
      }
      if (lastLockedAt > t) {
        observe(filter(belief, s -> !doorOpens(door[0], s)), "door " + door[0] + " locked");
        log("door " + door[0] + " is locked -> " + describeLevers());
        return 0;
      }
    }
    return -1;
  }

  /**
   * Gets to the target room from wherever we are in the basement, pulling levers as needed.
   * Bounded; returns whether we're there.
   */
  public boolean goToRoom(int target) {
    int failures = 0;
    while (c.isRunning() && failures < 10) {
      QuestHandler.running();
      int here = room();
      if (here < 0) {
        log("not in a known basement room at " + c.currentX() + "," + c.currentY() + "; stepping into one");
        failures++;
        int best = -1;
        for (int i = 0; i < ROOMS.length && best < 0; i++) {
          int cx = (ROOMS[i][0] + ROOMS[i][2]) / 2, cy = (ROOMS[i][1] + ROOMS[i][3]) / 2;
          if (c.isReachable(cx, cy, false)) best = i;
        }
        if (best >= 0) stepTo((ROOMS[best][0] + ROOMS[best][2]) / 2, (ROOMS[best][1] + ROOMS[best][3]) / 2);
        else c.sleep(1280);
        continue;
      }
      if (here == target) return true;
      // Read the levers we can see from here when we aren't sure of them.
      for (int l = 0; l < LEVERS.length; l++) {
        final int lv = l;
        if (LEVERS[l][2] != here) continue;
        boolean sure = filter(belief, s -> down(s, lv)) == 0 || filter(belief, s -> !down(s, lv)) == 0;
        if (!sure) inspect(l);
      }
      // Shortest plan over every lever state that's still possible.
      List<String> plan = null;
      int planState = -1;
      long b = belief;
      for (int s = 0; s < 64; s++) {
        if ((b >>> s & 1L) == 0) continue;
        List<String> p = solve(here, s, target);
        if (p != null && (plan == null || p.size() < plan.size())) {
          plan = p;
          planState = s;
        }
      }
      if (plan == null) {
        failures++;
        log("no lever sequence from the " + ROOM_NAMES[here] + " to the " + ROOM_NAMES[target]
            + " for any possible lever state (" + describeLevers() + "); re-learning the levers");
        belief = ALL;
        c.sleep(640);
        continue;
      }
      log(String.format(
          "%s -> %s: levers %s; planning for %s: %s",
          ROOM_NAMES[here], ROOM_NAMES[target], describeLevers(), stateText(planState),
          plan.isEmpty() ? "(nothing to do)" : planText(plan)));
      for (String step : plan) {
        if (!c.isRunning()) return false;
        QuestHandler.running();
        int idx = Integer.parseInt(step.substring(1));
        boolean ok;
        if (step.charAt(0) == 'L') ok = pull(idx);
        else ok = door(idx) == 1;
        if (!ok) failures++;
        // re-plan as soon as the state we planned for is ruled out (or a step failed)
        int expect = planState;
        if (!ok || room() < 0) break;
        if (step.charAt(0) == 'L') {
          expect = planState ^ (1 << idx);
          planState = expect;
        }
        if ((belief >>> expect & 1L) == 0) {
          log("levers aren't as planned (" + describeLevers() + "), re-planning");
          break;
        }
      }
      if (room() == target) return true;
    }
    log("FAILED to reach the " + ROOM_NAMES[target] + " (at " + c.currentX() + "," + c.currentY()
        + ", " + describeLevers() + ")");
    return room() == target;
  }

  /** From anywhere in the basement: to the ladder room and up the ladder. */
  public boolean climbOut() {
    if (!goToRoom(R_LADDER)) return false;
    for (int attempt = 0; attempt < 3 && c.isRunning(); attempt++) {
      stepTo(LADDER_STAND[0], LADDER_STAND[1]);
      int startY = c.currentY();
      log("climbing the basement ladder up (attempt " + (attempt + 1) + ")");
      c.atObject(LADDER_UP[0], LADDER_UP[1]);
      if (c.sleepUntil(() -> Math.abs(c.currentY() - startY) > 500, 8000)) {
        onLadderUp();
        log("out of the basement at " + c.currentX() + "," + c.currentY() + " (levers reset to all up)");
        c.sleep(640);
        return true;
      }
    }
    return false;
  }

  static void log(String msg) {
    String line = "[Manor] [Levers] " + msg;
    System.out.println(line);
    try {
      bot.Main.getController().log(line, "cya");
    } catch (Exception ignored) {
    }
    try {
      bot.probe.Probe.recordEvent("levers", msg);
    } catch (Throwable ignored) {
      // older client without the probe
    }
  }
}
