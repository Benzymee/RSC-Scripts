/*
 * A* over the rsc-path-finder collision model (see CollisionMap and NOTICE.txt; rsc-path-finder is
 * AGPL-3.0+, 2003Scape Team). Same floor only; 8-directional steps with rsc-path-finder's wall
 * and corner rules. Openable doors/gates are edges with an extra cost.
 */
package scripting.idlescript.SeattaScript.AIOQuester.pathfinding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class GridPlanner {
  /** Extra cost for crossing a closed door/gate between two tiles, or -1 if it can't be used. */
  public interface DoorPolicy {
    int cost(int fromX, int fromY, int toX, int toY);
  }

  public static final class Path {
    public final int[] xs, ys;
    /** door[i]: the step from tile i-1 to tile i crosses a closed door/gate. */
    public final boolean[] door;

    public final int expanded;
    public final long micros;

    Path(int[] xs, int[] ys, boolean[] door, int expanded, long micros) {
      this.xs = xs;
      this.ys = ys;
      this.door = door;
      this.expanded = expanded;
      this.micros = micros;
    }

    public int length() {
      return xs.length;
    }

    public int doorCount() {
      int n = 0;
      for (boolean b : door) if (b) n++;
      return n;
    }
  }

  private static final int[] DX = {0, 0, 1, -1, 1, 1, -1, -1};
  private static final int[] DY = {1, -1, 0, 0, 1, -1, 1, -1};
  public static final int DEFAULT_DOOR_COST = 60; // 10 = one straight step

  public final CollisionMap map;
  public final Set<Long> blockedTiles = new HashSet<>();
  public final Set<Long> blockedSteps = new HashSet<>();
  public int maxNodes = 900_000;

  public GridPlanner(CollisionMap map) {
    this.map = map;
  }

  public static long tileKey(int x, int y) {
    return ((long) x << 32) | (y & 0xffffffffL);
  }

  public static long stepKey(int ax, int ay, int bx, int by) {
    long a = tileKey(ax, ay), b = tileKey(bx, by);
    return a < b ? a * 1_000_003L + b : b * 1_000_003L + a;
  }

  /** Plans from (sx,sy) to any tile within tol (Chebyshev) of (tx,ty); tries a wider box once. */
  public Path find(int sx, int sy, int tx, int ty, int tol, DoorPolicy doors) {
    Path p = find(sx, sy, tx, ty, tol, doors, 48);
    if (p == null) p = find(sx, sy, tx, ty, tol, doors, 220);
    return p;
  }

  public Path find(int sx, int sy, int tx, int ty, int tol, DoorPolicy doors, int margin) {
    long t0 = System.nanoTime();
    if (CollisionMap.floor(sy) != CollisionMap.floor(ty)) return null;
    if (!map.inBounds(sx, sy) || !map.inBounds(tx, ty)) return null;
    int f = CollisionMap.floor(sy);
    int minX = Math.max(0, Math.min(sx, tx) - margin);
    int maxX = Math.min(map.width - 1, Math.max(sx, tx) + margin);
    int minY = Math.max(f * CollisionMap.FLOOR_HEIGHT, Math.min(sy, ty) - margin);
    int maxY =
        Math.min(
            Math.min(map.height - 1, (f + 1) * CollisionMap.FLOOR_HEIGHT - 1),
            Math.max(sy, ty) + margin);
    int bw = maxX - minX + 1, bh = maxY - minY + 1;
    int n = bw * bh;
    int[] g = new int[n];
    Arrays.fill(g, Integer.MAX_VALUE);
    int[] parent = new int[n];
    byte[] state = new byte[n]; // 0 new, 1 open, 2 closed; bit 4 = reached through a door
    Heap open = new Heap();
    int start = (sy - minY) * bw + (sx - minX);
    g[start] = 0;
    parent[start] = -1;
    open.push(start, h(sx, sy, tx, ty, tol));
    int expanded = 0, goal = -1;
    while (open.size > 0 && expanded < maxNodes) {
      int cur = open.pop();
      if ((state[cur] & 3) == 2) continue;
      state[cur] = (byte) ((state[cur] & ~3) | 2);
      expanded++;
      int cx = cur % bw + minX, cy = cur / bw + minY;
      if (Math.max(Math.abs(cx - tx), Math.abs(cy - ty)) <= tol) {
        goal = cur;
        break;
      }
      for (int d = 0; d < 8; d++) {
        int nx = cx + DX[d], ny = cy + DY[d];
        if (nx < minX || nx > maxX || ny < minY || ny > maxY) continue;
        int ni = (ny - minY) * bw + (nx - minX);
        if ((state[ni] & 3) == 2) continue;
        if (!map.isValidStep(cx, cy, DX[d], DY[d], true)) continue;
        if (!blockedTiles.isEmpty() && blockedTiles.contains(tileKey(nx, ny))) continue;
        if (!blockedSteps.isEmpty() && blockedSteps.contains(stepKey(cx, cy, nx, ny))) continue;
        int cost = d < 4 ? 10 : 14;
        boolean door = !map.isValidStep(cx, cy, DX[d], DY[d], false);
        if (door) {
          if (d >= 4) continue; // never cut a corner through a door
          int extra = doors == null ? DEFAULT_DOOR_COST : doors.cost(cx, cy, nx, ny);
          if (extra < 0) continue;
          cost += extra;
        }
        int ng = g[cur] + cost;
        if (ng < g[ni]) {
          g[ni] = ng;
          parent[ni] = cur;
          state[ni] = (byte) (1 | (door ? 4 : 0));
          open.push(ni, ng + h(nx, ny, tx, ty, tol));
        }
      }
    }
    if (goal < 0) return null;
    List<Integer> rev = new ArrayList<>();
    for (int i = goal; i >= 0; i = parent[i]) {
      rev.add(i);
      if (i == start) break;
    }
    int len = rev.size();
    int[] xs = new int[len], ys = new int[len];
    boolean[] door = new boolean[len];
    for (int i = 0; i < len; i++) {
      int idx = rev.get(len - 1 - i);
      xs[i] = idx % bw + minX;
      ys[i] = idx / bw + minY;
      door[i] = i > 0 && (state[idx] & 4) != 0;
    }
    return new Path(xs, ys, door, expanded, (System.nanoTime() - t0) / 1000);
  }

  private static int h(int x, int y, int tx, int ty, int tol) {
    int dx = Math.max(0, Math.abs(x - tx) - tol), dy = Math.max(0, Math.abs(y - ty) - tol);
    int mn = Math.min(dx, dy), mx = Math.max(dx, dy);
    return 14 * mn + 10 * (mx - mn);
  }

  /** Min-heap of (node, priority) with lazy deletion. */
  private static final class Heap {
    int[] node = new int[1024];
    int[] pri = new int[1024];
    int size;

    void push(int n, int p) {
      if (size == node.length) {
        node = Arrays.copyOf(node, size * 2);
        pri = Arrays.copyOf(pri, size * 2);
      }
      int i = size++;
      while (i > 0) {
        int par = (i - 1) >> 1;
        if (pri[par] <= p) break;
        node[i] = node[par];
        pri[i] = pri[par];
        i = par;
      }
      node[i] = n;
      pri[i] = p;
    }

    int pop() {
      int top = node[0];
      int ln = node[--size], lp = pri[size];
      int i = 0;
      while (true) {
        int c = 2 * i + 1;
        if (c >= size) break;
        if (c + 1 < size && pri[c + 1] < pri[c]) c++;
        if (pri[c] >= lp) break;
        node[i] = node[c];
        pri[i] = pri[c];
        i = c;
      }
      node[i] = ln;
      pri[i] = lp;
      return top;
    }
  }
}
