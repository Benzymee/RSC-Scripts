package scripting.idlescript.SeattaScript.AIOQuester;

import controller.Controller;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import scripting.idlescript.SeattaScript.AIOQuester.pathfinding.CollisionMap;

/**
 * Draynor Manor: knows which part of the manor (and which floor) the player is in, and moves
 * between those parts over explicit routes (doors, stairs, ladders), because neither IdleRSC's
 * WebWalker graph nor AIOQuester's single-floor A* knows the manor's floors.
 *
 * <p>Floors are encoded in y: ground 0, first floor y+944, top floor y+1888, basement y+2832.
 *
 * <p>Ids and coordinates are from the OpenRSC server data (Core-Framework
 * server/conf/server/defs/locs/SceneryLocs.json and BoundaryLocs.json, names from
 * GameObjectDef.xml / DoorDef.xml) and were checked against AIOQuester's baked collision map:
 *
 * <ul>
 *   <li>Ground: front door (boundary 36) 210,553, opens only from outside (stand on 210,553, you
 *       come out on 210,552); outer "doors" (scenery 64) 210,556 between the garden and the porch;
 *       back door (boundary 37) 199,551, inside tile 199,551, outside 199,550; closet door
 *       (boundary 35) 212,545 (needs the closet key, never routed through).
 *   <li>Ground -> first floor: stairs (scenery 43, "Go up") 210,547, stand 210,550.
 *   <li>First floor -> ground: stairs (scenery 44, "Go down") 210,1491, stand 210,1490.
 *   <li>First floor -> top floor: ladder (scenery 5, "Climb-Up") 215,1492, stand 215,1493.
 *   <li>Top floor -> first floor: ladder (scenery 6, "Climb-Down") 215,2436, stand 215,2437.
 *       Professor Oddenstein's room is around 211,2441.
 *   <li>Ground -> lever basement (Ernest the Chicken): ladder (scenery 6, "Climb-Down") 223,553,
 *       stand 223,554; back up: ladder (scenery 130, "Climb-Up") 223,3385, stand 223,3384, only
 *       in the ladder room (the other rooms are behind the lever doors; ErnestTheChicken solves
 *       those).
 *   <li>Ground -> crypt (Vampire Slayer, Count Draynor's coffin 204,3380): stairs (scenery 42,
 *       "Go down") 204,551, stand 205,554; back up: stairs (scenery 41, "Go up") 204,3383, stand
 *       204,3382.
 * </ul>
 *
 * Every area change and route step is logged (console + probe events) so it shows up in
 * logs/probe/&lt;account&gt;/current.
 */
public final class DraynorManor {
  public static final int FLOOR = QuestWalker.FLOOR_HEIGHT;

  /** Where in (or outside) the manor a tile is. */
  public enum Area {
    OUTSIDE("outside Draynor Manor", -1),
    GROUND("Draynor Manor ground floor", 0),
    FIRST_FLOOR("Draynor Manor first floor (upstairs)", 1),
    TOP_FLOOR("Draynor Manor top floor (Oddenstein)", 2),
    LEVER_BASEMENT("Draynor Manor basement (lever puzzle)", 3),
    CRYPT("Draynor Manor crypt (Count Draynor)", 3);

    public final String label;
    public final int floor;

    Area(String label, int floor) {
      this.label = label;
      this.floor = floor;
    }

    public boolean inManor() {
      return this != OUTSIDE;
    }

    /** Areas you can't reach by walking from outside: upstairs and the basements. */
    public boolean offGround() {
      return this != OUTSIDE && this != GROUND;
    }
  }

  // ---- ground floor doors
  public static final int[] FRONT_DOOR = {210, 553}; // boundary 36, stand on it (outside)
  public static final int[] FRONT_DOOR_INSIDE = {210, 552};
  public static final int[] BACK_DOOR = {199, 551}; // boundary 37, this tile is inside
  public static final int[] BACK_DOOR_OUTSIDE = {199, 548};
  public static final int[] CLOSET_DOOR = {212, 545}; // boundary 35, locked (closet key)
  // ---- stairs / ladders {objX, objY, standX, standY}
  public static final int[] STAIRS_UP_GROUND = {210, 547, 210, 550}; // scenery 43
  public static final int[] STAIRS_DOWN_F1 = {210, 1491, 210, 1490}; // scenery 44
  public static final int[] LADDER_UP_F1 = {215, 1492, 215, 1493}; // scenery 5
  public static final int[] LADDER_DOWN_F2 = {215, 2436, 215, 2437}; // scenery 6
  public static final int[] LADDER_DOWN_GROUND = {223, 553, 223, 554}; // scenery 6
  public static final int[] LADDER_UP_BASEMENT = {223, 3385, 223, 3384}; // scenery 130
  public static final int[] CRYPT_STAIRS_DOWN = {204, 551, 205, 554}; // scenery 42
  public static final int[] CRYPT_STAIRS_UP = {204, 3383, 204, 3382}; // scenery 41
  /** Lever basement ladder room {minX, minY, maxX, maxY} (levers A and B). */
  private static final int[] LADDER_ROOM = {222, 3381, 227, 3386};

  private static final int MAX_STEPS = 10;
  private static final int ATTEMPTS = 3;

  private static Set<Long> groundTiles;

  private final Controller c;
  private final QuestWalker walker;
  private Area lastLogged;

  DraynorManor(Controller c, QuestWalker walker) {
    this.c = c;
    this.walker = walker;
  }

  // ================================================================ area detection

  public static int floorOf(int y) {
    return y / FLOOR;
  }

  /** The manor area of a tile; OUTSIDE for anything that isn't in the manor. */
  public static Area areaOf(int x, int y) {
    int f = floorOf(y);
    int ly = y - f * FLOOR;
    switch (f) {
      case 0:
        return groundTiles().contains(key(x, y)) ? Area.GROUND : Area.OUTSIDE;
      case 1:
        return x >= 198 && x <= 227 && ly >= 538 && ly <= 560 ? Area.FIRST_FLOOR : Area.OUTSIDE;
      case 2:
        return x >= 198 && x <= 227 && ly >= 538 && ly <= 560 ? Area.TOP_FLOOR : Area.OUTSIDE;
      case 3:
        if (x >= 220 && x <= 236 && y >= 3372 && y <= 3389) return Area.LEVER_BASEMENT;
        if (x >= 198 && x <= 210 && y >= 3376 && y <= 3389) return Area.CRYPT;
        return Area.OUTSIDE;
      default:
        return Area.OUTSIDE;
    }
  }

  public static boolean inManor(int x, int y) {
    return areaOf(x, y).inManor();
  }

  /** e.g. "220,1497 floor 1, Draynor Manor first floor (upstairs)". */
  public static String describe(int x, int y) {
    Area a = areaOf(x, y);
    return x + "," + y + " floor " + floorOf(y) + (a.inManor() ? ", " + a.label : "");
  }

  public Area here() {
    return areaOf(c.currentX(), c.currentY());
  }

  private static long key(int x, int y) {
    return ((long) x << 32) | (y & 0xffffffffL);
  }

  /**
   * Ground-floor tiles inside the manor: flood fill from the hall over the collision map with all
   * doors shut (so the porch between the outer doors and the front door counts as outside), plus
   * the locked closet. Falls back to the interior's bounding box without collision data.
   */
  private static synchronized Set<Long> groundTiles() {
    if (groundTiles != null) return groundTiles;
    Set<Long> seen = new HashSet<>();
    CollisionMap m = CollisionMap.get();
    if (m != null) {
      ArrayDeque<int[]> q = new ArrayDeque<>();
      q.add(new int[] {210, 550});
      seen.add(key(210, 550));
      while (!q.isEmpty()) {
        int[] p = q.poll();
        for (int dx = -1; dx <= 1; dx++)
          for (int dy = -1; dy <= 1; dy++) {
            if (dx == 0 && dy == 0) continue;
            int x = p[0] + dx, y = p[1] + dy;
            if (x < 190 || x > 235 || y < 535 || y > 560) continue;
            if (seen.contains(key(x, y)) || !m.isValidStep(p[0], p[1], dx, dy, false)) continue;
            seen.add(key(x, y));
            q.add(new int[] {x, y});
          }
      }
    }
    if (seen.size() < 50 || seen.size() > 600) { // no/odd collision data: the interior's box
      seen.clear();
      for (int x = 197; x <= 220; x++)
        for (int y = 542; y <= 555; y++)
          if (!(x >= 209 && x <= 212 && y >= 553)) seen.add(key(x, y));
      for (int x = 221; x <= 224; x++) for (int y = 550; y <= 555; y++) seen.add(key(x, y));
    }
    // the closet is behind a locked door; the stair/ladder landings are inside too
    for (int x = 208; x <= 211; x++) for (int y = 545; y <= 548; y++) seen.add(key(x, y));
    seen.add(key(204, 554));
    seen.add(key(205, 554));
    seen.add(key(223, 554));
    seen.add(key(210, 550));
    seen.add(key(199, 551));
    groundTiles = seen;
    return seen;
  }

  // ================================================================ routing

  /**
   * Whether a walk from (px,py) to (tx,ty) needs the manor routes first: one end is upstairs or
   * in a basement and the other isn't in that same area, or the walk goes in/out of the house on
   * the ground floor (the front door only opens from outside).
   */
  public static boolean needsRoute(int px, int py, int tx, int ty) {
    Area from = areaOf(px, py), to = areaOf(tx, ty);
    if (from == to) return false;
    return from.inManor() || to.inManor();
  }

  /**
   * Moves the player to the target area over the manor routes. Returns true once there (for
   * OUTSIDE: out of the house on the ground floor, by the back door).
   */
  public boolean goTo(Area target) {
    Area start = here();
    if (start == target) return true;
    log(
        String.format(
            "route %s -> %s (at %s)",
            start.label, target.label, describe(c.currentX(), c.currentY())));
    for (int step = 0; step < MAX_STEPS && c.isRunning(); step++) {
      QuestHandler.running();
      Area now = here();
      noteArea(now);
      if (now == target) {
        log("arrived: " + now.label + " at " + c.currentX() + "," + c.currentY());
        return true;
      }
      boolean ok;
      switch (now) {
        case OUTSIDE:
          if (floorOf(c.currentY()) != 0) {
            log(
                "not in the manor and not on the ground floor ("
                    + describe(c.currentX(), c.currentY())
                    + "); can't route from here");
            return false;
          }
          ok = enterByFrontDoor();
          break;
        case GROUND:
          switch (target) {
            case OUTSIDE:
              ok = exitByBackDoor();
              break;
            case FIRST_FLOOR:
            case TOP_FLOOR:
              ok = use(STAIRS_UP_GROUND, Area.FIRST_FLOOR, "stairs up to the first floor");
              break;
            case LEVER_BASEMENT:
              ok = use(LADDER_DOWN_GROUND, Area.LEVER_BASEMENT, "ladder down to the lever basement");
              break;
            case CRYPT:
              ok = use(CRYPT_STAIRS_DOWN, Area.CRYPT, "stairs down to the crypt");
              break;
            default:
              ok = false;
          }
          break;
        case FIRST_FLOOR:
          ok =
              target == Area.TOP_FLOOR
                  ? use(LADDER_UP_F1, Area.TOP_FLOOR, "ladder up to the top floor")
                  : use(STAIRS_DOWN_F1, Area.GROUND, "stairs down to the ground floor");
          break;
        case TOP_FLOOR:
          ok = use(LADDER_DOWN_F2, Area.FIRST_FLOOR, "ladder down to the first floor");
          break;
        case LEVER_BASEMENT:
          // Any room, any lever state: the lever solver walks/pulls its way back to the ladder.
          log("in the lever basement at " + c.currentX() + "," + c.currentY()
              + (inLadderRoom() ? " (ladder room)" : "; solving the levers back to the ladder"));
          ok = new LeverBasement(c, walker).climbOut();
          break;
        case CRYPT:
          ok = use(CRYPT_STAIRS_UP, Area.GROUND, "stairs up from the crypt");
          break;
        default:
          ok = false;
      }
      if (!ok) {
        log("FAILED route step from " + now.label + " towards " + target.label);
        return false;
      }
    }
    boolean there = here() == target;
    if (!there) log("FAILED to reach " + target.label + " (at " + describe(c.currentX(), c.currentY()) + ")");
    return there;
  }

  private boolean inLadderRoom() {
    int x = c.currentX(), y = c.currentY();
    return x >= LADDER_ROOM[0] && x <= LADDER_ROOM[2] && y >= LADDER_ROOM[1] && y <= LADDER_ROOM[3];
  }

  /**
   * Walks next to a staircase/ladder {objX, objY, standX, standY}, uses it ("Go up/down",
   * "Climb-Up/Down" are all the first command) and checks we arrived in the expected area.
   */
  private boolean use(int[] obj, Area expect, String what) {
    Area from = here();
    for (int attempt = 1; attempt <= ATTEMPTS && c.isRunning(); attempt++) {
      QuestHandler.running();
      log(
          String.format(
              "step: %s (%d,%d), standing at %d,%d first (attempt %d/%d)",
              what, obj[0], obj[1], obj[2], obj[3], attempt, ATTEMPTS));
      int standTol = (obj[0] == 204 && obj[1] == 551) ? 0 : 1;
      if (obj[0] == 204 && obj[1] == 551 && cheb(c.currentX(), c.currentY(), 205, 554) > 0) {
        walker.walkTo(205, 555, 0, 30_000);
      }
      if (cheb(c.currentX(), c.currentY(), obj[2], obj[3]) > standTol
          && !walker.walkTo(obj[2], obj[3], standTol, 60_000)) {
        log("couldn't get next to the " + what + " (at " + c.currentX() + "," + c.currentY() + ")");
        c.sleep(640);
        continue;
      }
      while (c.isCurrentlyWalking() && c.isRunning()) c.sleep(200);
      int startY = c.currentY();
      c.atObject(obj[0], obj[1]);
      boolean moved =
          c.sleepUntil(() -> here() == expect || floorOf(c.currentY()) != floorOf(startY), 8000);
      Area now = here();
      if (moved && now == expect) {
        log(
            String.format(
                "floor change: %s -> %s, now at %s",
                from.label, now.label, describe(c.currentX(), c.currentY())));
        noteArea(now);
        c.sleep(640);
        return true;
      }
      if (moved) {
        log(
            String.format(
                "floor changed but landed somewhere unexpected: %s (wanted %s)",
                describe(c.currentX(), c.currentY()), expect.label));
        noteArea(now);
        return now.inManor() || floorOf(c.currentY()) == 0;
      }
      log("the " + what + " didn't move us (attempt " + attempt + "), retrying");
      if (c.isInOptionMenu()) c.sleep(640);
    }
    return false;
  }

  /** From outside on the ground floor: to the porch, then the front door (outside-only). */
  private boolean enterByFrontDoor() {
    log("step: walking to the front door " + FRONT_DOOR[0] + "," + FRONT_DOOR[1] + " (from outside)");
    for (int attempt = 1; attempt <= ATTEMPTS && c.isRunning(); attempt++) {
      QuestHandler.running();
      if (here() == Area.GROUND) return true;
      if (!(c.currentX() == FRONT_DOOR[0] && c.currentY() == FRONT_DOOR[1])
          && !walker.walkTo(FRONT_DOOR[0], FRONT_DOOR[1], 0, 90_000)) {
        log("couldn't reach the front door (at " + c.currentX() + "," + c.currentY() + ")");
        c.sleep(640);
        continue;
      }
      while (c.isCurrentlyWalking() && c.isRunning()) c.sleep(200);
      log("step: opening the front door (attempt " + attempt + "/" + ATTEMPTS + ")");
      c.atWallObject(FRONT_DOOR[0], FRONT_DOOR[1]);
      if (c.sleepUntil(() -> here() == Area.GROUND, 6000)) {
        log("entered the manor, now at " + describe(c.currentX(), c.currentY()));
        noteArea(Area.GROUND);
        return true;
      }
    }
    return here() == Area.GROUND;
  }

  /**
   * From inside on the ground floor: out through the back door (the front door won't open from
   * inside: "The door won't open").
   */
  private boolean exitByBackDoor() {
    log("step: leaving by the back door " + BACK_DOOR[0] + "," + BACK_DOOR[1] + " (front door is locked from inside)");
    for (int attempt = 1; attempt <= ATTEMPTS && c.isRunning(); attempt++) {
      QuestHandler.running();
      if (here() == Area.OUTSIDE) break;
      if (isInCloset()) {
        log("in the closet; using the closet door first");
        c.atWallObject(CLOSET_DOOR[0], CLOSET_DOOR[1]);
        c.sleepUntil(() -> !isInCloset(), 6000);
        continue;
      }
      if (!(c.currentX() == BACK_DOOR[0] && c.currentY() == BACK_DOOR[1])
          && !walker.walkTo(BACK_DOOR[0], BACK_DOOR[1], 0, 60_000)) {
        log("couldn't reach the back door (at " + c.currentX() + "," + c.currentY() + ")");
        c.sleep(640);
        continue;
      }
      while (c.isCurrentlyWalking() && c.isRunning()) c.sleep(200);
      log("step: opening the back door (attempt " + attempt + "/" + ATTEMPTS + ")");
      c.atWallObject(BACK_DOOR[0], BACK_DOOR[1]);
      c.sleepUntil(() -> here() == Area.OUTSIDE, 6000);
    }
    if (here() != Area.OUTSIDE) {
      // last resort: the A* walker plans through the back door itself (never the front door)
      log("back door didn't let us out; letting the walker plan the way out");
      walker.walkTo(BACK_DOOR_OUTSIDE[0], BACK_DOOR_OUTSIDE[1], 1, 60_000);
    }
    if (here() == Area.OUTSIDE) {
      log("left the manor, now at " + describe(c.currentX(), c.currentY()));
      noteArea(Area.OUTSIDE);
      walker.walkTo(BACK_DOOR_OUTSIDE[0], BACK_DOOR_OUTSIDE[1], 1, 30_000);
      return true;
    }
    return false;
  }

  private boolean isInCloset() {
    int x = c.currentX(), y = c.currentY();
    return x >= 208 && x <= 211 && y >= 545 && y <= 548;
  }

  // ================================================================ logging

  /** Logs when the player's manor area changed since the last call (also from other code). */
  public void noteArea(Area now) {
    if (now == lastLogged) return;
    Area before = lastLogged;
    lastLogged = now;
    if (before == null && now == Area.OUTSIDE) return;
    log(
        "area: "
            + (before == null ? "?" : before.label)
            + " -> "
            + now.label
            + " at "
            + describe(c.currentX(), c.currentY()));
  }

  private static int cheb(int x1, int y1, int x2, int y2) {
    return Math.max(Math.abs(x1 - x2), Math.abs(y1 - y2));
  }

  void log(String msg) {
    String line = "[Manor] " + msg;
    System.out.println(line);
    try {
      c.log(line, "cya");
    } catch (Exception ignored) {
    }
    try {
      bot.probe.Probe.recordEvent("manor", msg);
    } catch (Throwable ignored) {
      // older client without the probe
    }
  }
}
