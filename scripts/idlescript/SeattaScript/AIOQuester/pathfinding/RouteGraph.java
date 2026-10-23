package scripting.idlescript.SeattaScript.AIOQuester.pathfinding;

import java.io.InputStream;
import java.util.*;

/**
 * IdleRSC's WebWalker graph (assets/map/graph.txt): nodes, distances and labelled edges (doors,
 * gates, ladders, boats with a WebWalker handler). Pure planning code, shared by QuestWalker and
 * the offline reachability check.
 */
public final class RouteGraph {
  public interface LabelPolicy {
    boolean canUse(String label);

    double penalty(String label);
  }

  public interface Walkable {
    boolean isWalkable(int x, int y);
  }

  /** A labelled edge (a <-> b). */
  public static final class LabelEdge {
    public final int ax, ay, bx, by;
    public final String label;

    LabelEdge(int ax, int ay, int bx, int by, String label) {
      this.ax = ax;
      this.ay = ay;
      this.bx = bx;
      this.by = by;
      this.label = label;
    }
  }

  private final Map<Long, List<long[]>> graph = new HashMap<>(); // node -> {to, dist, label}
  public final List<String> labels = new ArrayList<>();
  private final List<LabelEdge> labelEdges = new ArrayList<>();

  public RouteGraph(InputStream in) {
    try (Scanner s = new Scanner(in)) {
      while (s.hasNextLine()) {
        String[] p = s.nextLine().trim().split(",");
        if (p.length < 5) continue;
        int ax = Integer.parseInt(p[0]), ay = Integer.parseInt(p[1]);
        int bx = Integer.parseInt(p[2]), by = Integer.parseInt(p[3]);
        long a = key(ax, ay), b = key(bx, by);
        long dist = Long.parseLong(p[4]);
        long label = -1;
        if (p.length > 5 && !p[5].isEmpty()) {
          int i = labels.indexOf(p[5]);
          if (i < 0) {
            labels.add(p[5]);
            i = labels.size() - 1;
          }
          label = i;
          labelEdges.add(new LabelEdge(ax, ay, bx, by, p[5]));
        }
        add(a, new long[] {b, dist, label});
        add(b, new long[] {a, dist, label});
      }
    }
  }

  private void add(long a, long[] e) {
    List<long[]> l = graph.get(a);
    if (l == null) graph.put(a, l = new ArrayList<>());
    l.add(e);
  }

  public boolean isEmpty() {
    return graph.isEmpty();
  }

  public String label(long idx) {
    return idx < 0 ? null : labels.get((int) idx);
  }

  /**
   * The labelled same-floor edge (door/gate with a WebWalker handler) spanning a door step from
   * (fx,fy) to (tx,ty), or null.
   */
  public LabelEdge labelAcross(int fx, int fy, int tx, int ty) {
    for (LabelEdge e : labelEdges) {
      if (CollisionMap.floor(e.ay) != CollisionMap.floor(e.by)) continue;
      if (CollisionMap.floor(e.ay) != CollisionMap.floor(fy)) continue;
      if (Math.max(Math.abs(e.ax - e.bx), Math.abs(e.ay - e.by)) > 20) continue;
      int minX = Math.min(e.ax, e.bx) - 2, maxX = Math.max(e.ax, e.bx) + 2;
      int minY = Math.min(e.ay, e.by) - 2, maxY = Math.max(e.ay, e.by) + 2;
      if (fx >= minX && fx <= maxX && tx >= minX && tx <= maxX
          && fy >= minY && fy <= maxY && ty >= minY && ty <= maxY) return e;
    }
    return null;
  }

  /** Closest graph node by walking distance (BFS over walkable), else straight-line closest. */
  public long closestNode(int x, int y, Walkable w) {
    if (w != null) {
      ArrayDeque<int[]> q = new ArrayDeque<>();
      Set<Long> seen = new HashSet<>();
      q.add(new int[] {x, y});
      seen.add(key(x, y));
      while (!q.isEmpty() && seen.size() < 40_000) {
        int[] cur = q.poll();
        long k = key(cur[0], cur[1]);
        if (graph.containsKey(k)) return k;
        int[][] dirs = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
        for (int[] d : dirs) {
          int nx = cur[0] + d[0], ny = cur[1] + d[1];
          if (!w.isWalkable(nx, ny) || !seen.add(key(nx, ny))) continue;
          q.add(new int[] {nx, ny});
        }
      }
    }
    long best = -1;
    double bd = Double.MAX_VALUE;
    for (long k : graph.keySet()) {
      double d = Math.hypot(nx(k) - x, ny(k) - y);
      if (d < bd) {
        bd = d;
        best = k;
      }
    }
    return best;
  }

  /**
   * Dijkstra from the node closest to (sx,sy) to the node closest to (tx,ty). Returns a list of
   * {nodeKey, labelIndex of the edge into this node}, or null. If the end node is only reachable
   * through an unusable edge, ends at the reached node nearest the target on foot.
   */
  public List<long[]> plan(
      int sx, int sy, int tx, int ty, LabelPolicy policy, Set<Long> blockedEdges, Walkable w) {
    long start = closestNode(sx, sy, w), end = closestNode(tx, ty, w);
    if (start < 0 || end < 0) return null;
    Map<Long, Double> cost = new HashMap<>();
    Map<Long, long[]> prev = new HashMap<>();
    PriorityQueue<double[]> open = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
    cost.put(start, 0d);
    open.add(new double[] {0, start});
    Set<Long> done = new HashSet<>();
    while (!open.isEmpty()) {
      double[] cur = open.poll();
      long n = (long) cur[1];
      if (!done.add(n)) continue;
      if (n == end) break;
      List<long[]> es = graph.get(n);
      if (es == null) continue;
      for (long[] e : es) {
        long to = e[0];
        if (done.contains(to) || (blockedEdges != null && blockedEdges.contains(edgeKey(n, to))))
          continue;
        String label = label(e[2]);
        if (label != null && policy != null && !policy.canUse(label)) continue;
        double nc =
            cur[0] + Math.max(1, e[1]) + (label != null && policy != null ? policy.penalty(label) : 0);
        Double old = cost.get(to);
        if (old == null || nc < old) {
          cost.put(to, nc);
          prev.put(to, new long[] {n, e[2]});
          open.add(new double[] {nc, to});
        }
      }
    }
    if (!cost.containsKey(end)) {
      long alt = nearestReached(tx, ty, cost.keySet(), w);
      if (alt < 0) return null;
      end = alt;
    }
    LinkedList<long[]> path = new LinkedList<>();
    for (long n = end; n != start; ) {
      long[] p = prev.get(n);
      path.addFirst(new long[] {n, p[1]});
      n = p[0];
    }
    path.addFirst(new long[] {start, -1});
    return path;
  }

  private long nearestReached(int tx, int ty, Set<Long> reached, Walkable w) {
    if (w == null) return -1;
    ArrayDeque<Long> q = new ArrayDeque<>();
    Set<Long> seen = new HashSet<>();
    q.add(key(tx, ty));
    seen.add(key(tx, ty));
    int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    while (!q.isEmpty() && seen.size() < 40000) {
      long k = q.poll();
      if (reached.contains(k)) return k;
      for (int[] d : dirs) {
        int ax = nx(k) + d[0], ay = ny(k) + d[1];
        long nk = key(ax, ay);
        if (seen.contains(nk) || !w.isWalkable(ax, ay)) continue;
        seen.add(nk);
        q.add(nk);
      }
    }
    return -1;
  }

  public String describe(List<long[]> route) {
    StringBuilder sb = new StringBuilder();
    for (long[] r : route)
      if (r[1] >= 0) sb.append(sb.length() == 0 ? " via " : ", ").append(label(r[1]));
    return sb.toString();
  }

  public static long key(int x, int y) {
    return ((long) x << 32) | (y & 0xffffffffL);
  }

  public static int nx(long k) {
    return (int) (k >> 32);
  }

  public static int ny(long k) {
    return (int) k;
  }

  public static long edgeKey(long a, long b) {
    return a < b ? a * 31 + b : b * 31 + a;
  }
}
