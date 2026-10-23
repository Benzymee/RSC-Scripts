package scripting.idlescript.SeattaScript.AIOQuester;

import bot.Main;
import controller.Controller;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.*;
import java.util.function.BooleanSupplier;
import models.entities.ItemId;
import models.entities.NpcId;
import models.entities.QuestId;
import models.entities.SkillId;
import orsc.ORSCharacter;
import scripting.idlescript.SeattaScript.AIOQuester.pathfinding.CollisionMap;
import scripting.idlescript.SeattaScript.AIOQuester.pathfinding.GridPlanner;
import scripting.idlescript.SeattaScript.AIOQuester.pathfinding.RouteGraph;
import utils.Extractor;

/**
 * Central walker for AIOQuester. Every quest walk goes through {@link #walkTo}.
 *
 * <p><b>Primary planner: rsc-path-finder.</b> Same-floor walks are planned with A* over the full
 * RSC collision map (walls, objects, openable doors/gates as costed edges; see the pathfinding
 * package, ported from rsc-path-finder, AGPL-3.0+). The route is cut into waypoints within the
 * client's local walk range and walked with the client's walkToAsync. A closed door/gate on the
 * route is opened with its own command from the server definitions and checked open before
 * stepping through; doors that belong to a labelled WebWalker edge (toll gate, guild doors, ...)
 * are crossed by IdleRSC's WebWalker handler instead. When the client disagrees with the map
 * (dynamic objects, a door a player closed, Coleslaw-only objects) the tile/step is blocked for
 * this walk and A* re-plans.
 *
 * <p><b>Fallback: IdleRSC WebWalker graph.</b> Floor changes, boats, areas outside the collision
 * map, and walks A* can't finish use AIOQuester's own Dijkstra over IdleRSC's graph.txt, driven
 * one edge at a time through Controller.walkTowards so IdleRSC's label handlers climb ladders,
 * take boats and open labelled gates. Unlabelled stretches of such a route are walked with A*
 * again. This works around WebWalker problems without touching core files: labels without a
 * handler (draynorManorSouthDoor, championsGuild, brassKeyDoor, ...) are never used, and
 * unwalkable targets are snapped to a walkable tile so WebWalker doesn't "yeet" back and forth.
 *
 * <p>Upper floors of ordinary buildings (floor 1 and 2) are left by a climb-down ladder or stairs
 * that this floor can actually path to, before a walk to another floor or another building. A walk
 * that stays on that same upper floor is planned there. Basements (floor 3, y + 2832) are left by
 * a climb-up ladder or stairs the same way. A ladder on the other side of a wall (Varrock Palace:
 * Rovin's room at 142,1398 is not a way out of 137,1400) is skipped, so the walk never asks
 * WebWalker to yeet toward it. Draynor Manor keeps its own routes.
 *
 * <p>Progress is watched everywhere; no progress for ~15 s (covers walking back and forth)
 * escalates: block the failing step and re-plan, open nearby doors, side waypoint, re-plan the
 * graph without the failing edge. All bounded; on failure walkTo returns false.
 */
public final class QuestWalker {
  public static final int FLOOR_HEIGHT = 944;
  private static final long STUCK_MS = 15_000;
  private static final int LOCAL_RANGE = 18;
  private static final int MAX_ESCALATIONS = 6;
  private static final int WAYPOINT_RANGE = 12; // client local walk range kept well inside a region
  private static final int MAX_REPLANS = 8;
  private static final long DOOR_BLOCK_MS = 5 * 60_000L;
  private static final int GRID_ARRIVED = 0, GRID_NO_PATH = 1, GRID_STUCK = 2;
  private static final Set<String> UNHANDLED_LABELS =
      new HashSet<>(
          Arrays.asList(
              "draynorManorSouthDoor",
              "championsGuild",
              "brassKeyDoor",
              "shantayPass",
              "entranaBoat",
              "kbdGate",
              "kbdLadder",
              "shiloVillageEntrance",
              "wildyAgilityGate",
              "wildTunnelSkip"));

  /**
   * Openable doors/gates A* must never plan through: locked/key doors whose far side is dangerous
   * or that only a quest step opens (Taverley Dusty Key door into the blue dragons, the
   * suits-of-armour door, McGrubor's locked main gate). x,y of the door/gate.
   */
  private static final Set<Long> AVOID_DOORS =
      new HashSet<>(
          Arrays.asList(
              GridPlanner.tileKey(355, 3353), // Dusty Key door (blue dragon lair)
              GridPlanner.tileKey(374, 3332), // Taverley suits of armour door
              GridPlanner.tileKey(560, 472), // McGrubor's wood main gate (locked)
              // Draynor Manor: closet door (needs the closet key, used by Ernest the Chicken itself)
              GridPlanner.tileKey(212, 545),
              // Draynor Manor basement lever-puzzle doors (only open for set lever positions)
              GridPlanner.tileKey(225, 3376),
              GridPlanner.tileKey(228, 3376),
              GridPlanner.tileKey(226, 3378),
              GridPlanner.tileKey(229, 3378),
              GridPlanner.tileKey(225, 3379),
              GridPlanner.tileKey(228, 3379),
              GridPlanner.tileKey(223, 3381),
              GridPlanner.tileKey(226, 3381),
              GridPlanner.tileKey(228, 3382),
              // Draynor Manor top floor magic whistle door (Holy Grail)
              GridPlanner.tileKey(202, 2438),
              // Clock Tower cellar: Odd looking wall 22. One push per pass; never plan through it.
              GridPlanner.tileKey(584, 3457)));

  /**
   * Draynor Manor front door (210,553): the server only opens it from outside (player y >= 553,
   * "The door won't open" from inside). From inside you leave through the back door (199,551).
   */
  private static final int MANOR_FRONT_DOOR_X = 210, MANOR_FRONT_DOOR_Y = 553;

  /** True when a door/gate at (dx,dy) must not be used from the player's tile (px,py). */
  static boolean doorForbidden(int dx, int dy, int px, int py) {
    if (AVOID_DOORS.contains(GridPlanner.tileKey(dx, dy))) return true;
    return dx == MANOR_FRONT_DOOR_X && dy == MANOR_FRONT_DOOR_Y && py < MANOR_FRONT_DOOR_Y;
  }

  /** Odd looking wall 22 between 583,3457 (pocket) and 584,3457 (corridor). */
  private static boolean secretClockWallStep(int fx, int fy, int tx, int ty) {
    return (fx == 583 && fy == 3457 && tx == 584 && ty == 3457)
        || (fx == 584 && fy == 3457 && tx == 583 && ty == 3457);
  }

  private static byte[][] walkable; // [900][4050], != 0 = walkable (IdleRSC map data)
  private static RouteGraph graph;
  private static boolean graphFailed;
  /** Door steps that failed to open recently: stepKey -> expiry time. */
  private static final Map<Long, Long> failedDoors = new HashMap<>();
  private static boolean mapLogged;

  private final Controller c;
  /** Draynor Manor floors/routes (the WebWalker graph and A* don't know its floors). */
  private final DraynorManor manor;
  /** True while DraynorManor drives the walk (its own same-area walks must not re-route). */
  private boolean manorRouting = false;
  /** True while walking to the stairs that leave an upper floor, so that walk does not climb again. */
  private boolean descending = false;
  /** True while walking to the ladder that leaves a basement, so that walk does not climb again. */
  private boolean ascending = false;
  private boolean crossingGertrudeFence = false;
  private final Set<Long> blockedEdges = new HashSet<>();
  private int localDoorTries = 0;
  private int pathSerial = 0;

  public QuestWalker(Controller c) {
    this.c = c;
    this.manor = new DraynorManor(c, this);
  }

  /** Draynor Manor helper (area/floor detection and explicit routes). */
  public DraynorManor manor() {
    return manor;
  }

  /**
   * Moves between Draynor Manor areas (ground, first floor, top floor, lever basement, crypt,
   * outside) over the manor's explicit routes. Logged as [Manor] lines.
   */
  public boolean manorGoTo(DraynorManor.Area target) {
    if (manorRouting) return manor.here() == target;
    manorRouting = true;
    try {
      return manor.goTo(target);
    } finally {
      manorRouting = false;
    }
  }

  // ---------------------------------------------------------------- public API

  public boolean walkTo(int x, int y, int tolerance, long timeoutMs) {
    return walkTo(x, y, tolerance, timeoutMs, null);
  }

  /**
   * Walks to (x,y) until within tolerance (Chebyshev) or arrived.getAsBoolean().
   *
   * @return true when arrived, false after the timeout or the escalation limit.
   */
  public boolean walkTo(int x, int y, int tolerance, long timeoutMs, BooleanSupplier arrived) {
    if (x <= 0 || y <= 0 || !c.isRunning()) return false;
    if (isThere(x, y, tolerance, arrived) && arrivedForReal(x, y, tolerance, arrived)) return true;
    blockedEdges.clear();
    localDoorTries = 0;
    CollisionMap map = collisionMap();
    int[] goal = snap(x, y);
    if (goal[0] != x || goal[1] != y)
      log(String.format("target %d,%d is not walkable, using %d,%d", x, y, goal[0], goal[1]));
    int tol = Math.max(tolerance, Math.max(Math.abs(goal[0] - x), Math.abs(goal[1] - y)));
    if (timeoutMs <= 0) timeoutMs = defaultTimeout(x, y);
    log(
        String.format(
            "walk %d,%d -> %d,%d (tol %d, %ds)",
            c.currentX(), c.currentY(), x, y, tol, timeoutMs / 1000));
    QuestHandler.noteRepeatedAction("walk to " + x + "," + y);
    if (!crossingGertrudeFence && floor(c.currentY()) == 0 && floor(y) == 0) {
      if (inGertrudeYard(c.currentX(), c.currentY()) && !inGertrudeYard(x, y)) {
        if (!leaveGertrudeYard()) return false;
      } else if (!inGertrudeYard(c.currentX(), c.currentY()) && inGertrudeYard(x, y)) {
        if (!enterGertrudeYard()) return false;
      }
    }
    // Draynor Manor: upstairs / basements / inside the house aren't on the WebWalker graph (it
    // would route via Lumbridge castle's ladders and hang), so get to the target's manor area
    // (or out of the manor) over the explicit manor routes first.
    if (!manorRouting && DraynorManor.needsRoute(c.currentX(), c.currentY(), goal[0], goal[1])) {
      DraynorManor.Area to = DraynorManor.areaOf(goal[0], goal[1]);
      log(
          String.format(
              "Draynor Manor: at %s, target %s -> manor route first",
              DraynorManor.describe(c.currentX(), c.currentY()),
              DraynorManor.describe(goal[0], goal[1])));
      if (!manorGoTo(to)) {
        log("Draynor Manor route to " + to.label + " failed (at " + c.currentX() + "," + c.currentY() + ")");
        return false;
      }
      if (isThere(x, y, tol, arrived) && arrivedForReal(x, y, tol, arrived)) {
        log("arrived at " + c.currentX() + "," + c.currentY());
        return true;
      }
    }
    if (!descending && !ascending && floor(c.currentY()) != floor(y)) {
      if (upstairs() && !sameUpperFloor(x, y)) {
        int stop = buildingFootprint(x, y) && floor(y) < floor(c.currentY()) ? floor(y) : 0;
        log(
            "upstairs at "
                + c.currentX()
                + ","
                + c.currentY()
                + " (floor "
                + floor(c.currentY())
                + "), climbing down before the walk to "
                + x
                + ","
                + y);
        if (!leaveByLadder(false, stop)) return false;
      } else if (floor(c.currentY()) >= 3) {
        log(
            "off the ground map at "
                + c.currentX()
                + ","
                + c.currentY()
                + " (floor "
                + floor(c.currentY())
                + "), climbing up before the walk to "
                + x
                + ","
                + y);
        if (!leaveByLadder(true, 0)) return false;
      }
      if (isThere(x, y, tol, arrived) && arrivedForReal(x, y, tol, arrived)) {
        log("arrived at " + c.currentX() + "," + c.currentY());
        return true;
      }
    }
    if (floor(c.currentY()) != floor(y) && (upstairs() || floor(c.currentY()) >= 3)) {
      log(
          "on floor "
              + floor(c.currentY())
              + " (y "
              + c.currentY()
              + "), not planning a ground path to "
              + x
              + ","
              + y);
      return false;
    }
    long deadline = System.currentTimeMillis() + timeoutMs;

    List<long[]> route = null; // {nodeKey, labelIndex of edge INTO this node}
    int routeIdx = 0;
    int escalations = 0;
    Progress progress = new Progress();
    String mode = "";
    boolean forceRoute = false;
    boolean gridGoalFailed = false;
    Set<Long> gridFailedLegs = new HashSet<>();

    while (c.isRunning() && System.currentTimeMillis() < deadline) {
      QuestHandler.running(); // lets the queue abort a timed-out quest
      if (isThere(x, y, tol, arrived) && arrivedForReal(x, y, tol, arrived)) {
        log("arrived at " + c.currentX() + "," + c.currentY());
        return true;
      }
      int px = c.currentX(), py = c.currentY();
      boolean sameFloor = floor(py) == floor(goal[1]);

      // ---- 1. rsc-path-finder A* over real collision (same floor, inside the map)
      if (map != null && sameFloor && !gridGoalFailed && covered(map, px, py, goal[0], goal[1])) {
        int r = gridWalk(map, goal[0], goal[1], tol, deadline, x, y, arrived);
        if (r == GRID_ARRIVED && arrivedForReal(x, y, tol, arrived)) {
          log("arrived at " + c.currentX() + "," + c.currentY());
          return true;
        }
        gridGoalFailed = true;
        if (r == GRID_NO_PATH) {
          log("rsc-path-finder has no same-floor path; using the WebWalker graph (boat/ladder/unmapped)");
        } else {
          log("rsc-path-finder walk didn't finish; using the WebWalker graph");
          forceRoute = true;
        }
        route = null;
        mode = "";
        progress.reset();
        continue;
      }

      int cheb = Math.max(Math.abs(px - goal[0]), Math.abs(py - goal[1]));
      int[] sub; // current short-term goal

      if (sameFloor && cheb <= LOCAL_RANGE && !forceRoute) {
        if (!mode.equals("local")) {
          mode = "local";
          log("short hop, using client pathing");
          progress.reset();
        }
        sub = goal;
        if (localStep(goal[0], goal[1], tol)) {
          if (arrivedForReal(x, y, tol, arrived)) {
            log("standing on the closest reachable tile " + c.currentX() + "," + c.currentY());
            return true;
          }
          log(
              "client path reported arrival at "
                  + c.currentX()
                  + ","
                  + c.currentY()
                  + " but the server position is not at "
                  + x
                  + ","
                  + y
                  + "; using the graph");
          forceRoute = true;
          progress.reset();
        }
      } else {
        if (route == null) {
          route = planRoute(px, py, goal[0], goal[1]);
          routeIdx = 0;
          if (route != null && !route.isEmpty()) {
            long first = route.get(0)[0];
            int fx = RouteGraph.nx(first), fy = RouteGraph.ny(first);
            if (floor(fy) != floor(py) || cheb(px, py, fx, fy) > 40) {
              // WebWalker can't reach its own first node from here: Controller.walkDamnit would
              // wait forever for that region ("Can't find the closest walkable node"), so don't.
              log(
                  String.format(
                      "nearest WebWalker node %d,%d (floor %d) is out of reach from %d,%d (floor %d);"
                          + " not using the graph here",
                      fx, fy, floor(fy), px, py, floor(py)));
              route = null;
            }
          }
          if (route == null) {
            if (!sameFloor) {
              log("no graph route between floors; the quest has to use a ladder/stairs here");
              return false;
            }
            log("no graph route, falling back to IdleRSC path walker");
            route = Collections.emptyList();
          } else {
            log("graph route over " + route.size() + " nodes" + graph.describe(route));
          }
          mode = "route";
          progress.reset();
        }
        // advance past nodes we're already at
        while (routeIdx < route.size() && near(px, py, route.get(routeIdx)[0], 2)) routeIdx++;
        if (routeIdx >= route.size()) {
          // graph exhausted but not yet there: walk directly towards the goal
          sub = goal;
          if (!sameFloor) {
            log("graph route ended on another floor; the quest has to use a ladder/stairs here");
            return false;
          }
          if (map != null && covered(map, px, py, goal[0], goal[1]) && gridFailedLegs.add(-1L)) {
            if (gridWalk(map, goal[0], goal[1], tol, deadline, x, y, arrived) == GRID_ARRIVED)
              return true;
          } else pathWalkerStep(goal[0], goal[1]);
        } else {
          // walk the unlabelled stretch up to the next labelled edge with rsc-path-finder
          int legEnd = route.size() - 1;
          for (int i = routeIdx; i < route.size(); i++)
            if (route.get(i)[1] >= 0) {
              legEnd = i - 1;
              break;
            }
          if (legEnd > routeIdx && map != null) {
            long leg = route.get(legEnd)[0];
            int lx = RouteGraph.nx(leg), ly = RouteGraph.ny(leg);
            if (floor(ly) == floor(py)
                && covered(map, px, py, lx, ly)
                && cheb(px, py, lx, ly) > 3
                && gridFailedLegs.add(leg)) {
              log(String.format("graph leg to %d,%d with rsc-path-finder", lx, ly));
              if (gridWalk(map, lx, ly, 2, deadline, lx, ly, null) == GRID_ARRIVED) {
                routeIdx = legEnd;
                progress.reset();
                continue;
              }
            }
          }
          long node = route.get(routeIdx)[0];
          sub = new int[] {RouteGraph.nx(node), RouteGraph.ny(node)};
          boolean ok = c.walkTowards(sub[0], sub[1]);
          if (!ok) progress.failures++;
          c.sleep(200);
        }
      }

      // ---- progress / oscillation check
      int d = Math.max(Math.abs(c.currentX() - sub[0]), Math.abs(c.currentY() - sub[1]));
      if (floor(c.currentY()) != floor(py)) {
        QuestHandler.noteFloorChange(floor(py), floor(c.currentY()));
        progress.reset(); // changed floor = progress
      }
      else progress.update(sub, d);
      if (progress.isStuck()) {
        escalations++;
        log(
            String.format(
                "no progress towards %d,%d for %ds at %d,%d (escalation %d/%d)",
                sub[0], sub[1], STUCK_MS / 1000, c.currentX(), c.currentY(), escalations,
                MAX_ESCALATIONS));
        if (escalations > MAX_ESCALATIONS) break;
        if (mode.equals("local") && escalations == 3) {
          log("short hop keeps failing, planning over the graph instead");
          forceRoute = true;
          route = null;
          progress.reset();
          continue;
        }
        switch (escalations % 4) {
          case 1:
            openDoorTowards(sub[0], sub[1], mode.equals("local") ? 15 : 8);
            break;
          case 2:
            log("trying IdleRSC tile path walker");
            pathWalkerStep(sub[0], sub[1]);
            break;
          case 3:
            sideStep(sub[0], sub[1]);
            break;
          default:
            if (route != null && routeIdx > 0 && routeIdx < route.size()) {
              blockedEdges.add(RouteGraph.edgeKey(route.get(routeIdx - 1)[0], route.get(routeIdx)[0]));
            } else if (route != null && routeIdx < route.size() && !route.isEmpty() && graph != null) {
              long here = graph.closestNode(c.currentX(), c.currentY(), WALKABLE);
              blockedEdges.add(RouteGraph.edgeKey(here, route.get(routeIdx)[0]));
            }
            log("re-planning the route without the failing edge");
            route = null;
            break;
        }
        progress.reset();
      }
    }
    log(
        String.format(
            "FAILED to reach %d,%d (at %d,%d after %d escalations)",
            x, y, c.currentX(), c.currentY(), escalations));
    QuestHandler.noteRepeatedAction("walk to " + x + "," + y);
    return false;
  }

  private static boolean inGertrudeYard(int x, int y) {
    return floor(y) == 0 && x >= 51 && x <= 75 && y >= 430 && y <= 455;
  }

  private boolean leaveGertrudeYard() {
    if (c.currentX() <= 50) return true;
    crossingGertrudeFence = true;
    try {
      if (c.currentX() != 51 || c.currentY() != 438) {
        c.walkToAsync(51, 438, 0);
        c.sleepUntil(() -> c.currentX() == 51 && c.currentY() == 438, 12000);
      }
      if (c.currentX() <= 50) return true;
      log("crossing Gertrude's broken fence 199 at 51,438 (leave)");
      QuestHandler.noteRepeatedAction("fence 51,438");
      c.atWallObject(51, 438);
      c.sleepUntil(() -> c.currentX() == 50, 4000);
      return c.currentX() == 50;
    } finally {
      crossingGertrudeFence = false;
    }
  }

  private boolean enterGertrudeYard() {
    if (inGertrudeYard(c.currentX(), c.currentY())) return true;
    crossingGertrudeFence = true;
    try {
      if (c.currentX() != 50 || c.currentY() != 438) {
        crossingGertrudeFence = false;
        walkTo(50, 438, 0, 0);
        crossingGertrudeFence = true;
      }
      if (inGertrudeYard(c.currentX(), c.currentY())) return true;
      log("crossing Gertrude's broken fence 199 at 51,438 (enter)");
      QuestHandler.noteRepeatedAction("fence 51,438");
      c.atWallObject(51, 438);
      c.sleepUntil(() -> c.currentX() >= 51, 4000);
      return c.currentX() >= 51;
    } finally {
      crossingGertrudeFence = false;
    }
  }

  // ---------------------------------------------------------------- rsc-path-finder walking

  private static boolean covered(CollisionMap map, int ax, int ay, int bx, int by) {
    return map.inBounds(ax, ay) && map.inBounds(bx, by) && floor(ay) == floor(by);
  }

  /** Door cost for A*: WebWalker-labelled doors follow canUse/penalty; recently failed = closed. */
  private GridPlanner.DoorPolicy doorPolicy() {
    return (fx, fy, tx, ty) -> {
      Long until = failedDoors.get(GridPlanner.stepKey(fx, fy, tx, ty));
      if (until != null && until > System.currentTimeMillis()) return -1;
      if (secretClockWallStep(fx, fy, tx, ty)) return -1;
      CollisionMap m = CollisionMap.get();
      CollisionMap.Door door = m == null ? null : m.doorBetween(fx, fy, tx, ty, 1);
      if (door != null && doorForbidden(door.x, door.y, fx, fy)) return -1;
      RouteGraph g = routeGraph();
      RouteGraph.LabelEdge le = g == null ? null : g.labelAcross(fx, fy, tx, ty);
      if (le != null && !UNHANDLED_LABELS.contains(le.label)) {
        if (!canUse(le.label)) return -1;
        return GridPlanner.DEFAULT_DOOR_COST + (int) penalty(le.label);
      }
      return GridPlanner.DEFAULT_DOOR_COST;
    };
  }

  /**
   * Walks to within tol of (tx,ty) on this floor along an rsc-path-finder A* route.
   *
   * @return GRID_ARRIVED, GRID_NO_PATH (A* found nothing) or GRID_STUCK (bounded retries spent)
   */
  private int gridWalk(
      CollisionMap map, int tx, int ty, int tol, long deadline, int ox, int oy, BooleanSupplier arrived) {
    GridPlanner gp = new GridPlanner(map);
    GridPlanner.DoorPolicy policy = doorPolicy();
    GridPlanner.Path path = null;
    int idx = 0, replans = 0, escalations = 0, liveDoorTries = 0, crossings = 0;
    Progress progress = new Progress();
    while (c.isRunning() && System.currentTimeMillis() < deadline) {
      QuestHandler.running();
      if (isThere(ox, oy, tol, arrived) || isThere(tx, ty, tol, null)) return GRID_ARRIVED;
      int px = c.currentX(), py = c.currentY();
      if (floor(py) != floor(ty)) return GRID_STUCK;
      if (path == null) {
        if (replans > MAX_REPLANS) {
          log("rsc-path-finder: giving up after " + MAX_REPLANS + " re-plans");
          return GRID_STUCK;
        }
        path = gp.find(px, py, tx, ty, tol, policy);
        if (path == null) {
          log(String.format("rsc-path-finder: no path %d,%d -> %d,%d", px, py, tx, ty));
          return replans == 0 ? GRID_NO_PATH : GRID_STUCK;
        }
        log(
            String.format(
                "rsc-path-finder: %d tiles, %d door(s), %d nodes in %dms",
                path.length(), path.doorCount(), path.expanded, path.micros / 1000));
        idx = 0;
        pathSerial++;
        progress.reset();
      }
      // The client walks a straight line to the next waypoint, so on the Lumbridge
      // bridge it finishes a few tiles beside the planned line. Re-planning from
      // there picks the other side of the bridge and the account crosses it again.
      // Stay on this path while any tile at or ahead of the cursor is still close.
      int lastTile = path.length() - 1;
      int k = -1, best = Integer.MAX_VALUE;
      int behind = -1, behindD = Integer.MAX_VALUE;
      for (int i = 0; i <= lastTile; i++) {
        int dd = cheb(px, py, path.xs[i], path.ys[i]);
        if (i >= idx - 2) {
          if (dd < best || (dd == best && i > k)) {
            best = dd;
            k = i;
          }
        } else if (dd < behindD || (dd == behindD && i > behind)) {
          behindD = dd;
          behind = i;
        }
      }
      if (best > 5) {
        if (behindD <= 2) {
          k = behind;
          best = behindD;
        } else {
          log(String.format("off the planned path at %d,%d, re-planning", px, py));
          path = null;
          replans++;
          continue;
        }
      }
      idx = k;
      int last = path.length() - 1;
      if (k == last && best == 0) return GRID_ARRIVED;
      int door = -1;
      for (int i = k + 1; i <= last; i++)
        if (path.door[i]) {
          door = i;
          break;
        }
      if (door == k + 1 && best == 0) {
        int fx = path.xs[k], fy = path.ys[k], dx = path.xs[door], dy = path.ys[door];
        if (crossDoor(map, fx, fy, dx, dy)) {
          if (++crossings > 20) {
            log("rsc-path-finder: too many door crossings on one walk, giving up");
            return GRID_STUCK;
          }
          path = null; // re-plan from the far side
          continue;
        }
        long sk = GridPlanner.stepKey(fx, fy, dx, dy);
        gp.blockedSteps.add(sk);
        failedDoors.put(sk, System.currentTimeMillis() + DOOR_BLOCK_MS);
        log(String.format("couldn't get through at %d,%d -> %d,%d; avoiding it and re-planning", fx, fy, dx, dy));
        path = null;
        replans++;
        continue;
      }
      int end = door < 0 ? last : door - 1;
      int j = Math.min(k + 1, end);
      for (int i = k + 1; i <= end; i++) {
        if (cheb(px, py, path.xs[i], path.ys[i]) > WAYPOINT_RANGE) break;
        j = i;
      }
      int wx = path.xs[j], wy = path.ys[j];
      if (!safeReachable(wx, wy, false)) {
        // the client's collision disagrees: a door someone closed, a dynamic or custom object
        if (liveDoorTries++ < 3 && openDoorTowards(wx, wy, 8)) continue;
        int bad = j;
        for (int i = k + 1; i <= j; i++)
          if (!safeReachable(path.xs[i], path.ys[i], false)) {
            bad = i;
            break;
          }
        log(
            String.format(
                "client can't reach %d,%d (object/NPC not on the map?), blocking it and re-planning",
                path.xs[bad], path.ys[bad]));
        if (bad == j && bad == last) gp.blockedSteps.add(GridPlanner.stepKey(path.xs[bad - 1], path.ys[bad - 1], path.xs[bad], path.ys[bad]));
        else gp.blockedTiles.add(GridPlanner.tileKey(path.xs[bad], path.ys[bad]));
        path = null;
        replans++;
        continue;
      }
      if (wx != px || wy != py) {
        c.walkToAsync(wx, wy, 0);
        long until = System.currentTimeMillis() + 2500L + 700L * Math.max(1, cheb(px, py, wx, wy));
        c.sleep(500);
        while (System.currentTimeMillis() < until && c.isRunning()) {
          if (c.currentX() == wx && c.currentY() == wy) break;
          if (!c.isCurrentlyWalking()) break;
          c.sleep(250);
        }
      }
      // progress = how far along this path we are (catches back-and-forth between waypoints)
      progress.update(new int[] {pathSerial, 0}, last - k);
      if (progress.isStuck()) {
        escalations++;
        log(
            String.format(
                "no progress along the path for %ds at %d,%d (escalation %d/%d)",
                STUCK_MS / 1000, c.currentX(), c.currentY(), escalations, MAX_ESCALATIONS));
        if (escalations > MAX_ESCALATIONS) return GRID_STUCK;
        switch (escalations % 3) {
          case 1:
            if (k + 1 <= last) {
              gp.blockedSteps.add(GridPlanner.stepKey(path.xs[k], path.ys[k], path.xs[k + 1], path.ys[k + 1]));
              log(String.format("blocking step %d,%d -> %d,%d and re-planning", path.xs[k], path.ys[k], path.xs[k + 1], path.ys[k + 1]));
            }
            path = null;
            replans++;
            break;
          case 2:
            openDoorTowards(wx, wy, 10);
            break;
          default:
            sideStep(wx, wy);
            path = null;
            replans++;
            break;
        }
        progress.reset();
      }
    }
    return GRID_STUCK;
  }

  /** Gets through the closed door/gate between two adjacent path tiles. */
  private boolean crossDoor(CollisionMap map, int fx, int fy, int tx, int ty) {
    if (safeReachable(tx, ty, false) && stepTo(tx, ty)) return true; // already open
    RouteGraph g = routeGraph();
    RouteGraph.LabelEdge le = g == null ? null : g.labelAcross(fx, fy, tx, ty);
    if (le != null && !UNHANDLED_LABELS.contains(le.label)) {
      // The endpoint further along the step. "Farther from the from-tile" picks the Taverley
      // node when leaving Taverley: tile 342 is closer to Falador's node (340) than Taverley's (345).
      int dirX = Integer.signum(tx - fx), dirY = Integer.signum(ty - fy);
      int scoreA = (le.ax - fx) * dirX + (le.ay - fy) * dirY;
      int scoreB = (le.bx - fx) * dirX + (le.by - fy) * dirY;
      int farX = scoreA >= scoreB ? le.ax : le.bx, farY = scoreA >= scoreB ? le.ay : le.by;
      log(String.format("door at %d,%d is WebWalker's '%s' edge; letting its handler cross", tx, ty, le.label));
      // walkTowards snaps to the closest graph node, and it skips a gate when the far tile
      // already looks reachable. From Falador that leaves the Taverley gate shut on the way
      // to Catherby. From Taverley the closest node is already in Falador, so the way home
      // does the same. Open this gate directly instead.
      if ("northFallyTavGate".equals(le.label)) {
        boolean toTaverley = farX > 341;
        boolean onFaladorSide = c.currentX() <= 341;
        if (toTaverley != onFaladorSide) return true;
        log(
            toTaverley
                ? "opening the Taverley gate toward Catherby"
                : "opening the Taverley gate toward Falador");
        for (int attempt = 0; attempt < 3 && c.isRunning(); attempt++) {
          controller.WebWalker.CustomLabelHandlers.northFallyTavGate();
          if (toTaverley ? c.currentX() > 341 : c.currentX() <= 341) return true;
          c.sleep(600);
        }
        return false;
      }
      // The south wall gate (343,581) is scenery, not a door the pathfinder can swing. walkTowards
      // the far node leaves it shut, which is what stuck the Falador to Al Kharid walks.
      if ("southFallyTavGate".equals(le.label)) {
        boolean goingSouth = farY >= 582;
        boolean onNorthSide = c.currentY() < 581;
        if (goingSouth != onNorthSide) return true;
        log(goingSouth ? "opening the Falador south gate" : "opening the Falador south gate toward the city");
        for (int attempt = 0; attempt < 3 && c.isRunning(); attempt++) {
          if (controller.WebWalker.CustomLabelHandlers.southFallyTavGate()) return true;
          c.sleep(600);
        }
        return false;
      }
      // walkTowards the far side never talks to the border guard. It brute-forces a path
      // and the account walks back over the Lumbridge bridge.
      if ("alkharidGate".equals(le.label)) return payAlKharidGate(fx, fy);
      long until = System.currentTimeMillis() + 25_000;
      while (System.currentTimeMillis() < until && c.isRunning()) {
        QuestHandler.running();
        if (cheb(c.currentX(), c.currentY(), farX, farY) <= 2
            || (c.currentX() == tx && c.currentY() == ty)) return true;
        c.walkTowards(farX, farY);
        c.sleep(300);
      }
      return false;
    }
    CollisionMap.Door door = map.doorBetween(fx, fy, tx, ty, 1);
    if (door == null) return openDoorTowards(tx, ty, 3) && stepTo(tx, ty);
    String cmd = door.command == 2 ? "second" : "first";
    for (int attempt = 0; attempt < 3 && c.isRunning(); attempt++) {
      log(String.format("opening %s with its %s command (attempt %d)", door, cmd, attempt + 1));
      if (door.wall) {
        if (door.command == 2) c.atWallObject2(door.x, door.y);
        else c.atWallObject(door.x, door.y);
      } else {
        if (door.command == 2) c.atObject2(door.x, door.y);
        else c.atObject(door.x, door.y);
      }
      boolean open =
          c.sleepUntil(
              () ->
                  (c.currentX() == tx && c.currentY() == ty) || safeReachable(tx, ty, false),
              6000);
      if (open && stepTo(tx, ty)) {
        log(String.format("%s is open, through to %d,%d", door, tx, ty));
        return true;
      }
      if (c.isInOptionMenu()) {
        log(door + " wants a dialogue; not handled by the walker");
        return false;
      }
    }
    return false;
  }

  /**
   * The Al Kharid gate only opens after the border guard is spoken to. Before Prince Ali Rescue
   * is finished that costs 10 coins each way, so both crossings are withdrawn from the bank on
   * this side of the gate first. The Al Kharid bank is not used from the Lumbridge side, because
   * reaching it is what this gate is for.
   */
  private boolean payAlKharidGate(int standX, int standY) {
    int stage = c.getQuestStage(QuestId.PRINCE_ALI_RESCUE.getId());
    boolean free = stage < 0 || stage >= 3;
    if (!free && c.getInventoryItemCount(ItemId.COINS.getId()) < 20) {
      log("getting coins for the Al Kharid toll");
      boolean east = c.currentX() >= 92;
      if (!walkTo(east ? 220 : 89, east ? 635 : 694, 3, 0)) return false;
      QuestHandler.withdrawCoins(20);
      if (c.getInventoryItemCount(ItemId.COINS.getId()) < 10) {
        log("not enough coins to pay the Al Kharid toll");
        return false;
      }
      if (!walkTo(standX, standY, 1, 0)) return false;
    }
    for (int attempt = 0; attempt < 3 && c.isRunning(); attempt++) {
      boolean entering = c.currentX() >= 92;
      ORSCharacter guard = borderGuard(entering);
      if (guard == null) {
        walkTo(standX, standY, 1, 0);
        guard = borderGuard(entering);
      }
      if (guard == null) {
        log("no border guard in view");
        c.sleep(600);
        continue;
      }
      int xBefore = c.currentX();
      log(free ? "asking the border guard to open the gate" : "paying the Al Kharid border guard");
      c.talkToNpc(guard.serverIndex);
      c.sleepUntil(() -> c.isInOptionMenu() || crossedAlKharidGate(xBefore), 8000);
      if (c.isInOptionMenu()) {
        String[] opts = c.getOptionsMenuText();
        int pick = -1;
        if (opts != null) {
          for (int i = 0; i < opts.length; i++) {
            if (opts[i] != null && opts[i].toLowerCase().contains("yes")) {
              pick = i;
              break;
            }
          }
        }
        if (pick >= 0) c.optionAnswer(pick);
      }
      if (c.sleepUntil(() -> crossedAlKharidGate(xBefore), 12000)) return true;
      c.sleep(600);
    }
    return false;
  }

  private ORSCharacter borderGuard(boolean entering) {
    int want =
        entering ? NpcId.BORDER_GUARD_LUMBRIDGE.getId() : NpcId.BORDER_GUARD_ALKHARID.getId();
    ORSCharacter guard = c.getNearestNpcById(want, false);
    if (guard != null) return guard;
    int other =
        entering ? NpcId.BORDER_GUARD_ALKHARID.getId() : NpcId.BORDER_GUARD_LUMBRIDGE.getId();
    return c.getNearestNpcById(other, false);
  }

  private boolean crossedAlKharidGate(int xBefore) {
    return xBefore >= 92 ? c.currentX() < 92 : c.currentX() >= 92;
  }

  /** Walks onto (tx,ty) (adjacent/near tile). */
  private boolean stepTo(int tx, int ty) {
    if (c.currentX() == tx && c.currentY() == ty) return true;
    c.walkToAsync(tx, ty, 0);
    c.sleepUntil(() -> c.currentX() == tx && c.currentY() == ty, 4000);
    return c.currentX() == tx && c.currentY() == ty;
  }

  // ---------------------------------------------------------------- shared data

  /** The baked rsc-path-finder collision map (lazy, cached for the whole client). */
  private CollisionMap collisionMap() {
    CollisionMap m = CollisionMap.get();
    if (!mapLogged) {
      mapLogged = true;
      if (m == null) log("collision map unavailable (" + CollisionMap.loadError() + "); WebWalker only");
      else
        log(
            String.format(
                "collision map loaded: %dx%d tiles, %d doors, %d KB, %d ms",
                m.width, m.height, m.doorCount(), m.bytes / 1024, m.loadMillis));
    }
    return m;
  }

  private static synchronized RouteGraph routeGraph() {
    if (graph != null || graphFailed) return graph;
    try {
      graph = new RouteGraph(Extractor.extractResourceAsStream("/assets/map/graph.txt"));
      if (graph.isEmpty()) {
        graph = null;
        graphFailed = true;
      }
    } catch (Throwable e) {
      // A failed script reload can leave RouteGraph missing. That is an Error, not an Exception,
      // and it used to kill the quest on the next door-aware walk (Romeo, church).
      graph = null;
      graphFailed = true;
      Main.logError("AIOQuester walker: couldn't read graph.txt", e);
    }
    return graph;
  }

  private static final RouteGraph.Walkable WALKABLE =
      (x, y) -> {
        loadWalkable();
        return isWalkable(x, y);
      };

  private List<long[]> planRoute(int sx, int sy, int tx, int ty) {
    RouteGraph g = routeGraph();
    if (g == null) return null;
    RouteGraph.LabelPolicy policy =
        new RouteGraph.LabelPolicy() {
          public boolean canUse(String label) {
            return QuestWalker.this.canUse(label);
          }

          public double penalty(String label) {
            return QuestWalker.this.penalty(label);
          }
        };
    return g.plan(sx, sy, tx, ty, policy, blockedEdges, WALKABLE);
  }


  /**
   * Walks to a ladder/staircase, uses it and verifies the floor changed (944 tiles per floor).
   *
   * @param secondOption use the object's second command (e.g. "climb-down" on a two-way ladder)
   */
  public boolean climb(int objX, int objY, boolean secondOption, int attempts) {
    int startX = c.currentX(), startY = c.currentY();
    int objId = c.getObjectAtCoord(objX, objY);
    String name = objId >= 0 ? safeName(objId) : "";
    boolean useSecond = secondOption;
    if (useSecond && objId >= 0) {
      String cmd2 = "";
      try {
        cmd2 = c.getObjectCommand2(objId);
      } catch (Exception ignored) {
      }
      if (cmd2 == null || cmd2.isEmpty() || "examine".equalsIgnoreCase(cmd2)) {
        log("object " + objId + " command2 is Examine or empty; using the first option");
        useSecond = false;
      }
    }
    if (name.contains("door") || name.contains("gate")) {
      log("refusing to climb " + name + " (" + objId + ") at " + objX + "," + objY);
      return false;
    }
    boolean levelChange =
        name.contains("ladder") || name.contains("stair") || name.contains("trapdoor")
            || name.contains("manhole") || name.contains("rope");
    log(
        String.format(
            "using %s (%d) at %d,%d from %d,%d%s",
            name.isEmpty() ? "object" : name, objId, objX, objY, startX, startY,
            levelChange ? ", expecting a floor change" : ""));
    if (Math.max(Math.abs(startX - objX), Math.abs(startY - objY)) > 6)
      walkTo(objX, objY, 2, 0, null);
    for (int i = 0; i < attempts && c.isRunning(); i++) {
      QuestHandler.running();
      while (c.isCurrentlyWalking() && c.isRunning()) c.sleep(300);
      QuestHandler.noteRepeatedAction("climb " + objX + "," + objY);
      if (useSecond) c.atObject2(objX, objY);
      else c.atObject(objX, objY);
      final boolean lc = levelChange;
      boolean moved =
          c.sleepUntil(
              () ->
                  lc
                      ? Math.abs(c.currentY() - startY) > 500
                      : (c.currentX() != startX || c.currentY() != startY),
              7000);
      if (moved) {
        log(
            String.format(
                "floor change: %d -> %d, now at %s",
                floor(startY), floor(c.currentY()), DraynorManor.describe(c.currentX(), c.currentY())));
        QuestHandler.noteFloorChange(floor(startY), floor(c.currentY()));
        manor.noteArea(manor.here());
        return true;
      }
      if (c.isInOptionMenu()) return false; // object asked something; caller handles it
      if (i == 1) openDoorTowards(objX, objY, 6);
    }
    log(String.format("FAILED to use the object at %d,%d", objX, objY));
    return false;
  }

  public static int floor(int y) {
    return y / FLOOR_HEIGHT;
  }

  /** Floor 1 or 2 of a building. Floor 3 and above is a basement (y + 2832), which climbs up to leave. */
  private boolean upstairs() {
    int f = floor(c.currentY());
    return f == 1 || f == 2;
  }

  /** Same floor, and the ground-floor footprint is one building (about 80 tiles). */
  private boolean sameUpperFloor(int x, int y) {
    return upstairs() && floor(c.currentY()) == floor(y) && buildingFootprint(x, y);
  }

  private boolean buildingFootprint(int x, int y) {
    int dx = Math.abs(c.currentX() - x);
    int dy = Math.abs((c.currentY() % FLOOR_HEIGHT) - (y % FLOOR_HEIGHT));
    return Math.max(dx, dy) <= 80;
  }

  /**
   * Climbs down ladders and stairs until the player is on {@code stopFloor} (0 is the ground).
   * Used when a walk starts upstairs in a building. A walk to somewhere on this same upper floor
   * does not come through here.
   */
  public boolean descendToGround() {
    return leaveByLadder(false, 0);
  }

  /**
   * Ladders the floor change has to use even when that object is not loaded yet. {x, y, 1 if the
   * second command}. Varrock Palace's east wing (137,1400) cannot path to Rovin's ladder at
   * 142,1398; the way down is 123,1407. The Phoenix Gang basement (110,3368 / 111,3369) climbs up
   * at 106,3366, back to 106,534.
   */
  private static final int[][] KNOWN_DOWN = {{123, 1407, 0}, {142, 1398, 0}};

  private static final int[][] KNOWN_UP = {{106, 3366, 0}};

  /**
   * Climbs until {@code floor(currentY) <= stopFloor}. {@code up} is a basement (climb-up command);
   * otherwise a building's upper floor (climb-down). Only a ladder this floor can path to is used,
   * so a ladder behind a wall is not walked at (that walk has no WebWalker node and yeets).
   */
  private boolean leaveByLadder(boolean up, int stopFloor) {
    for (int i = 0; i < 6 && c.isRunning() && floor(c.currentY()) > stopFloor; i++) {
      int[] ladder = findClimb(up);
      if (ladder == null) {
        log(
            (up ? "basement" : "upstairs")
                + " at "
                + c.currentX()
                + ","
                + c.currentY()
                + " and no "
                + (up ? "climb-up" : "climb-down")
                + " ladder or stairs can be pathed to");
        return false;
      }
      log(
          "leaving floor "
              + floor(c.currentY())
              + (up ? " up" : " down")
              + " the "
              + (ladder[2] == 1 ? "second option on " : "")
              + "ladder/stairs at "
              + ladder[0]
              + ","
              + ladder[1]);
      int y0 = c.currentY();
      if (Math.max(Math.abs(c.currentX() - ladder[0]), Math.abs(c.currentY() - ladder[1])) > 1) {
        if (up) ascending = true;
        else descending = true;
        try {
          int[] stand = standBeside(ladder[0], ladder[1]);
          walkTo(stand[0], stand[1], 0, 30_000);
        } finally {
          ascending = false;
          descending = false;
        }
      }
      if (Math.max(Math.abs(c.currentX() - ladder[0]), Math.abs(c.currentY() - ladder[1])) > 2) {
        log("could not reach the ladder at " + ladder[0] + "," + ladder[1]);
        return false;
      }
      climb(ladder[0], ladder[1], ladder[2] == 1, 3);
      // Ground is y, upstairs is y+944, a basement is y+2832. Leaving either drops y by hundreds.
      if (c.currentY() > y0 - 400) {
        log("climb at " + ladder[0] + "," + ladder[1] + " did not change floor");
        return false;
      }
    }
    return floor(c.currentY()) <= stopFloor;
  }

  /** Nearest ladder/stairs with the right command that this floor can path to. {x, y, second}. */
  private int[] findClimb(boolean up) {
    int here = floor(c.currentY());
    List<int[]> options = new ArrayList<>();
    addLoadedClimbs(options, up, here);
    for (int[] known : up ? KNOWN_UP : KNOWN_DOWN) {
      if (floor(known[1]) != here) continue;
      if (!listed(options, known[0], known[1])) options.add(new int[] {known[0], known[1], known[2]});
    }
    int[] best = null;
    int bestD = Integer.MAX_VALUE;
    for (int[] ladder : options) {
      int d = Math.max(Math.abs(ladder[0] - c.currentX()), Math.abs(ladder[1] - c.currentY()));
      if (d > 80 || d >= bestD) continue;
      if (!canReachTile(ladder[0], ladder[1])) continue;
      bestD = d;
      best = ladder;
    }
    return best;
  }

  private static boolean listed(List<int[]> options, int x, int y) {
    for (int[] o : options) if (o[0] == x && o[1] == y) return true;
    return false;
  }

  /** Loaded ladders, stairs, trapdoors and manholes whose command matches. */
  private void addLoadedClimbs(List<int[]> options, boolean up, int here) {
    try {
      int[] ids = c.getObjectsIds();
      int[] xs = c.getObjectsX();
      int[] zs = c.getObjectsZ();
      int n = c.getObjectsCount();
      String want = up ? "up" : "down";
      for (int i = 0; i < n; i++) {
        String name = c.getObjectName(ids[i]);
        if (name == null) continue;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("door") || lower.contains("gate")) continue;
        if (!lower.contains("ladder")
            && !lower.contains("stair")
            && !lower.contains("trapdoor")
            && !lower.contains("manhole")) continue;
        String cmd1 = c.getObjectCommand1(ids[i]);
        String cmd2 = c.getObjectCommand2(ids[i]);
        String a = cmd1 == null ? "" : cmd1.toLowerCase(Locale.ROOT);
        String b = cmd2 == null ? "" : cmd2.toLowerCase(Locale.ROOT);
        int useSecond = a.contains(want) ? 0 : b.contains(want) ? 1 : -1;
        if (useSecond < 0) continue;
        int wx = c.offsetX(xs[i]);
        int wy = c.offsetZ(zs[i]);
        if (floor(wy) != here) continue;
        if (!listed(options, wx, wy)) options.add(new int[] {wx, wy, useSecond});
      }
    } catch (RuntimeException e) {
      QuestHandler.rethrowAbort(e);
    }
  }

  /** A tile next to (or on) the ladder that the collision map can stand on. */
  private int[] standBeside(int x, int y) {
    CollisionMap map = collisionMap();
    if (map == null) return new int[] {x, y};
    int[] stand = map.snap(x, y, 2, true);
    return stand == null ? new int[] {x, y} : stand;
  }

  /** True when A* on this floor can reach a tile beside (x,y), doors included. */
  private boolean canReachTile(int x, int y) {
    int px = c.currentX(), py = c.currentY();
    if (floor(py) != floor(y)) return false;
    if (Math.max(Math.abs(px - x), Math.abs(py - y)) <= 1) return true;
    CollisionMap map = collisionMap();
    if (map == null) return false;
    int[] stand = map.snap(x, y, 2, true);
    if (stand == null) return false;
    if (Math.max(Math.abs(px - stand[0]), Math.abs(py - stand[1])) <= 1) return true;
    try {
      return new GridPlanner(map).find(px, py, stand[0], stand[1], 1, doorPolicy()) != null;
    } catch (RuntimeException e) {
      QuestHandler.rethrowAbort(e);
      return false;
    }
  }

  // ---------------------------------------------------------------- local movement

  /** @return true if we already stand on the closest reachable tile to an unreachable target */
  private boolean localStep(int tx, int ty, int tol) {
    int[] t = reachableNear(tx, ty, tol);
    if (t == null) {
      // not reachable as the map is now: a closed door or gate is in the way
      if (!openDoorTowards(tx, ty, 10)) pathWalkerStep(tx, ty);
      return false;
    }
    if (t[0] == c.currentX() && t[1] == c.currentY()) {
      if (cheb(t[0], t[1], tx, ty) <= tol) return true;
      // Closest reachable tile is outside the tolerance: a door may still be shut in between.
      if (localDoorTries++ < 3 && openDoorTowards(tx, ty, 10)) return false;
      return true;
    }
    c.walkToAsync(t[0], t[1], 0);
    long until = System.currentTimeMillis() + 2500L + 700L * Math.max(1, dist(t[0], t[1]));
    c.sleep(600);
    while (System.currentTimeMillis() < until && c.isRunning()) {
      if (Math.max(Math.abs(c.currentX() - t[0]), Math.abs(c.currentY() - t[1])) <= tol) break;
      if (!c.isCurrentlyWalking()) break;
      c.sleep(300);
    }
    return false;
  }

  /** Nearest tile within tol (then up to 3 more) of the target that the client can path to. */
  private int[] reachableNear(int tx, int ty, int tol) {
    if (safeReachable(tx, ty, false)) return new int[] {tx, ty};
    int maxR = Math.max(tol, 1) + 3;
    for (int r = 1; r <= maxR; r++) {
      int[] best = null;
      int bestD = Integer.MAX_VALUE;
      for (int dx = -r; dx <= r; dx++)
        for (int dy = -r; dy <= r; dy++) {
          if (Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
          int x = tx + dx, y = ty + dy;
          if (!safeReachable(x, y, false)) continue;
          int d = dist(x, y);
          if (d < bestD) {
            bestD = d;
            best = new int[] {x, y};
          }
        }
      if (best != null) return best;
    }
    return null;
  }

  private boolean safeReachable(int x, int y, boolean edges) {
    if (Math.max(Math.abs(c.currentX() - x), Math.abs(c.currentY() - y)) > 40) return false;
    if (floor(c.currentY()) != floor(y)) return false;
    try {
      return c.isReachable(x, y, edges);
    } catch (Exception e) {
      return false;
    }
  }

  /** Opens the closed door/gate within radius that lies best between us and the target. */
  private boolean openDoorTowards(int tx, int ty, int radius) {
    int[][] doors;
    try {
      doors = c.getNearbyClosedDoors(radius);
    } catch (Exception e) {
      return false;
    }
    if (doors == null || doors.length == 0) return false;
    int px = c.currentX(), py = c.currentY();
    int direct = cheb(px, py, tx, ty);
    int[] best = null;
    int bestScore = Integer.MAX_VALUE;
    for (int[] d : doors) {
      if (doorForbidden(d[0], d[1], px, py)) continue; // locked / one-way (Draynor Manor)
      int score = cheb(px, py, d[0], d[1]) + cheb(d[0], d[1], tx, ty);
      if (score > direct + 6) continue; // not on the way
      if (score < bestScore) {
        bestScore = score;
        best = d;
      }
    }
    if (best == null) return false;
    boolean wall = c.getWallObjectIdAtCoord(best[0], best[1]) != -1;
    if (best[0] == 51 && best[1] == 438) {
      log("crossing Gertrude's broken fence 199 at 51,438");
      QuestHandler.noteRepeatedAction("fence 51,438");
      c.atWallObject(51, 438);
      c.sleep(1800);
      while (c.isCurrentlyWalking() && c.isRunning()) c.sleep(300);
      return true;
    }
    log(String.format("opening %s at %d,%d on the way", wall ? "door" : "gate", best[0], best[1]));
    if (wall) c.openDoor(best[0], best[1]);
    else c.atObject(best[0], best[1]);
    c.sleep(1800);
    while (c.isCurrentlyWalking() && c.isRunning()) c.sleep(300);
    return true;
  }

  /** One attempt with IdleRSC's tile A* path walker (opens some doors/gates itself). */
  private void pathWalkerStep(int tx, int ty) {
    if (floor(c.currentY()) != floor(ty)) return;
    try {
      int[] t = snap(tx, ty);
      QuestHandler.bc.pathWalkerApi.walkTo(new models.entities.MapPoint(t[0], t[1]));
    } catch (Throwable e) {
      QuestHandler.rethrowAbort(e);
      c.sleep(600);
    }
  }

  /** Walks to a walkable tile beside the straight line, to get around whatever blocks us. */
  private void sideStep(int tx, int ty) {
    int px = c.currentX(), py = c.currentY();
    int dx = Integer.signum(tx - px), dy = Integer.signum(ty - py);
    int[][] cands = {
      {px - dy * 6 + dx * 3, py + dx * 6 + dy * 3}, {px + dy * 6 + dx * 3, py - dx * 6 + dy * 3},
      {px - dx * 5, py - dy * 5}
    };
    for (int[] cand : cands) {
      int[] s = snap(cand[0], cand[1]);
      if (safeReachable(s[0], s[1], false)) {
        log(String.format("side waypoint %d,%d", s[0], s[1]));
        c.walkToAsync(s[0], s[1], 0);
        c.sleep(600);
        c.sleepUntilNotMoving(8000);
        return;
      }
    }
  }

  // ---------------------------------------------------------------- graph rules

  /** Extra route cost so a walk doesn't pay a toll or sail when walking round is reasonable. */
  private double penalty(String label) {
    if (label == null) return 0;
    switch (label) {
      case "alkharidGate":
        return c.isQuestComplete(QuestId.PRINCE_ALI_RESCUE.getId()) ? 0 : 150;
      case "portSarimKaramjaBoat":
      case "brimhavenArdyBoat":
        return 100;
      default:
        return 0;
    }
  }

  /** Mirrors WebWalker.canWalkEdge, minus the labels WebWalker has no handler for. */
  private boolean canUse(String label) {
    if (UNHANDLED_LABELS.contains(label)) return false;
    try {
      switch (label) {
        case "alkharidGate":
          return c.isQuestComplete(QuestId.PRINCE_ALI_RESCUE.getId())
              || c.getInventoryItemCount(ItemId.COINS.getId()) >= 10;
        case "miningGuildDoor":
        case "miningGuildLadder":
          return c.getCurrentStat(SkillId.MINING.getId()) >= 60;
        case "taverleySteppingStones":
          return c.getCurrentStat(SkillId.AGILITY.getId()) >= 50
              && c.getCurrentStat(SkillId.HITS.getId()) > 20;
        case "dwarfTunnel":
          return c.isQuestComplete(QuestId.FISHING_CONTEST.getId());
        case "witchsHouseDoor":
          return c.isQuestComplete(QuestId.WITCHS_HOUSE.getId())
              || c.getInventoryItemCount(ItemId.FRONT_DOOR_KEY.getId()) > 0;
        case "craftingGuild":
          return c.getCurrentStat(SkillId.CRAFTING.getId()) >= 40
              || c.isItemIdEquipped(ItemId.BROWN_APRON.getId());
        case "zanarisShed":
          return c.isItemIdEquipped(ItemId.DRAMEN_STAFF.getId())
              || c.getInventoryItemCount(ItemId.DRAMEN_STAFF.getId()) > 0;
        case "portSarimKaramjaBoat":
        case "brimhavenArdyBoat":
          return c.getInventoryItemCount(ItemId.COINS.getId()) >= 30; // fare per crossing
        case "fishingGuildEntrance":
          return c.getCurrentStat(SkillId.FISHING.getId()) >= 68;
        case "brimMossGiantSwing":
          return c.getCurrentStat(SkillId.AGILITY.getId()) >= 10;
        case "edgeRowBoat":
          return false; // one-way and conditional; never plan through it
        default:
          return !label.startsWith("wildy") && !label.startsWith("deepWild");
      }
    } catch (Throwable t) {
      return false;
    }
  }

  // ---------------------------------------------------------------- map data

  /** Nearest walkable tile to (x,y) per IdleRSC's map data (the tile itself if walkable). */
  public int[] snap(int x, int y) {
    CollisionMap m = CollisionMap.get();
    if (m != null && m.inBounds(x, y)) {
      int[] s = m.snap(x, y, 4, true);
      if (s != null) return s;
    }
    loadWalkable();
    if (walkable == null || isWalkable(x, y)) return new int[] {x, y};
    for (int r = 1; r <= 4; r++)
      for (int dx = -r; dx <= r; dx++)
        for (int dy = -r; dy <= r; dy++)
          if (Math.max(Math.abs(dx), Math.abs(dy)) == r && isWalkable(x + dx, y + dy))
            return new int[] {x + dx, y + dy};
    return new int[] {x, y};
  }

  private static boolean isWalkable(int x, int y) {
    return walkable != null && x >= 0 && y >= 0 && x < 900 && y < 4050 && walkable[x][y] != 0;
  }

  private static synchronized void loadWalkable() {
    if (walkable != null) return;
    byte[][] w = new byte[900][4050];
    try (InputStream in =
        new BufferedInputStream(Extractor.extractResourceAsStream("/assets/map/data"))) {
      for (int i = 0; i < 900; i++) {
        int read = 0;
        while (read < 4050) {
          int r = in.read(w[i], read, 4050 - read);
          if (r < 0) throw new java.io.EOFException();
          read += r;
        }
      }
      walkable = w;
    } catch (Exception e) {
      Main.logError("AIOQuester walker: couldn't read map data", e);
    }
  }

  // ---------------------------------------------------------------- helpers

  private boolean isThere(int x, int y, int tol, BooleanSupplier arrived) {
    if (arrived != null) {
      try {
        if (arrived.getAsBoolean()) return true;
      } catch (Exception e) {
        QuestHandler.rethrowAbort(e);
      }
    }
    int px = c.currentX(), py = c.currentY();
    return floor(py) == floor(y) && Math.abs(px - x) <= tol && Math.abs(py - y) <= tol;
  }

  /**
   * Client pathing can report arrival before the server snaps the player back (e.g. through a
   * wall). Wait a couple of ticks and re-read the position before accepting it.
   */
  private boolean arrivedForReal(int x, int y, int tol, BooleanSupplier arrived) {
    c.sleep(400);
    while (c.isCurrentlyWalking() && c.isRunning()) c.sleep(200);
    c.sleep(320);
    return isThere(x, y, tol, arrived);
  }

  private long defaultTimeout(int x, int y) {
    int d = cheb(c.currentX(), c.currentY() % FLOOR_HEIGHT, x, y % FLOOR_HEIGHT);
    return Math.min(8 * 60_000L, Math.max(60_000L, 30_000L + 1500L * d));
  }

  private boolean near(int px, int py, long node, int r) {
    return floor(py) == floor(ny(node)) && cheb(px, py, nx(node), ny(node)) <= r;
  }

  private int dist(int x, int y) {
    return cheb(c.currentX(), c.currentY(), x, y);
  }

  private static int cheb(int x1, int y1, int x2, int y2) {
    return Math.max(Math.abs(x1 - x2), Math.abs(y1 - y2));
  }

  private String safeName(int objId) {
    try {
      String n = c.getObjectName(objId);
      return n == null ? "" : n.toLowerCase(Locale.ROOT);
    } catch (Exception e) {
      return "";
    }
  }

  private static long key(int x, int y) {
    return ((long) x << 32) | (y & 0xffffffffL);
  }

  private static int nx(long k) {
    return (int) (k >> 32);
  }

  private static int ny(long k) {
    return (int) k;
  }

  private static long edgeKey(long a, long b) {
    return a < b ? a * 31 + b : b * 31 + a;
  }

  private void log(String msg) {
    String line = "[QuestWalker] " + msg;
    System.out.println(line);
    QuestFailureProbe.noteWalker(msg); // failure probe (tester, keep)
    try {
      c.log(line, "cya");
    } catch (Exception e) {
      QuestHandler.rethrowAbort(e);
    }
  }

  /** Tracks the best distance to the current short-term goal; stuck = no improvement in 15 s. */
  private static final class Progress {
    int[] goal;
    int best = Integer.MAX_VALUE;
    long bestAt = System.currentTimeMillis();
    int failures;

    void reset() {
      goal = null;
      best = Integer.MAX_VALUE;
      bestAt = System.currentTimeMillis();
      failures = 0;
    }

    void update(int[] g, int d) {
      if (goal == null || goal[0] != g[0] || goal[1] != g[1]) {
        goal = g.clone();
        best = d;
        bestAt = System.currentTimeMillis();
        return;
      }
      if (d < best) {
        best = d;
        bestAt = System.currentTimeMillis();
      }
    }

    boolean isStuck() {
      return failures >= 3 || System.currentTimeMillis() - bestAt > STUCK_MS;
    }
  }
}
