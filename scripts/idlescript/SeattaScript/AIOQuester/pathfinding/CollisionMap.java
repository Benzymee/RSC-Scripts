/*
 * AIOQuester collision map.
 *
 * The obstacle model (2x2 sub-cells per tile, wall/door/object placement and the
 * isValidGameStep rules) is ported from rsc-path-finder by the 2003Scape Team
 * (https://github.com/2003scape/rsc-path-finder, mirrored at Open-RSC/rsc-path-finder),
 * licensed under the GNU Affero General Public License v3.0 or later. See NOTICE.txt in this
 * package. The baked data in CollisionData was generated with rsc-path-finder's PathFinder from
 * its land63/maps63 archives, overlaid with Coleslaw's Custom_Landscape.orsc and Coleslaw's
 * scenery/boundary locations and definitions (Core-Framework).
 */
package scripting.idlescript.SeattaScript.AIOQuester.pathfinding;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.InflaterInputStream;

/** Full-world collision for all four floors (944 tiles per floor), loaded lazily and cached. */
public final class CollisionMap {
  public static final int FLOOR_HEIGHT = 944;

  /** A closed door or gate that has an "Open" command in the server definitions. */
  public static final class Door {
    public final int x, y, id, command, direction, width, height;
    public final boolean wall; // boundary (wall object) vs scenery (gate)

    Door(int x, int y, boolean wall, int id, int command, int direction, int width, int height) {
      this.x = x;
      this.y = y;
      this.wall = wall;
      this.id = id;
      this.command = command;
      this.direction = direction;
      this.width = width;
      this.height = height;
    }

    /** Chebyshev distance from (px,py) to the door's footprint. */
    public int distanceTo(int px, int py) {
      int w = width, h = height;
      if (!wall && (direction == 2 || direction == 6)) {
        int t = w;
        w = h;
        h = t;
      }
      int dx = px < x ? x - px : (px > x + w - 1 ? px - (x + w - 1) : 0);
      int dy = py < y ? y - py : (py > y + h - 1 ? py - (y + h - 1) : 0);
      return Math.max(dx, dy);
    }

    @Override
    public String toString() {
      return (wall ? "door " : "gate ") + id + " at " + x + "," + y;
    }
  }

  private static CollisionMap instance;
  private static boolean loadFailed;
  private static String loadError;

  public final int width, height; // game tiles
  private final byte[] grid; // low nibble: as placed; high nibble: openable doors/gates open
  private final List<Door> doors;
  private final Map<Long, List<Door>> doorIndex = new HashMap<>();
  public final long loadMillis;
  public final long bytes;

  private CollisionMap(int width, int height, byte[] grid, List<Door> doors, long loadMillis) {
    this.width = width;
    this.height = height;
    this.grid = grid;
    this.doors = doors;
    this.loadMillis = loadMillis;
    this.bytes = grid.length + doors.size() * 48L;
    for (Door d : doors) {
      long k = bucket(d.x, d.y);
      List<Door> l = doorIndex.get(k);
      if (l == null) doorIndex.put(k, l = new ArrayList<>());
      l.add(d);
    }
  }

  /** Lazily loads the baked map (once). Returns null if it can't be loaded. */
  public static synchronized CollisionMap get() {
    if (instance != null || loadFailed) return instance;
    try {
      StringBuilder sb = new StringBuilder();
      for (String s : CollisionData.parts()) sb.append(s);
      instance = load(new ByteArrayInputStream(Base64.getDecoder().decode(sb.toString())));
    } catch (Throwable t) {
      loadFailed = true;
      loadError = String.valueOf(t);
    }
    return instance;
  }

  public static String loadError() {
    return loadError;
  }

  /** Reads the deflated "AQCM" blob produced by the bake script. */
  public static CollisionMap load(InputStream deflated) throws Exception {
    long t0 = System.nanoTime();
    try (DataInputStream in = new DataInputStream(new InflaterInputStream(deflated))) {
      byte[] magic = new byte[4];
      in.readFully(magic);
      if (magic[0] != 'A' || magic[1] != 'Q' || magic[2] != 'C' || magic[3] != 'M')
        throw new IllegalStateException("bad collision data");
      in.readInt(); // version
      int w = in.readInt(), h = in.readInt();
      byte[] g = new byte[w * h];
      in.readFully(g);
      int n = in.readInt();
      List<Door> doors = new ArrayList<>(n);
      for (int i = 0; i < n; i++) {
        int x = in.readUnsignedShort(), y = in.readUnsignedShort();
        boolean scenery = in.readUnsignedByte() == 1;
        int id = in.readUnsignedShort(), cmd = in.readUnsignedByte(), dir = in.readUnsignedByte();
        int wh = in.readUnsignedByte();
        doors.add(new Door(x, y, !scenery, id, cmd, dir, wh >> 4, wh & 15));
      }
      return new CollisionMap(w, h, g, doors, (System.nanoTime() - t0) / 1_000_000L);
    }
  }

  public static int floor(int y) {
    return y / FLOOR_HEIGHT;
  }

  public boolean inBounds(int x, int y) {
    return x >= 0 && y >= 0 && x < width && y < height;
  }

  private boolean obstacle(int x, int y, int ox, int oy, boolean open) {
    if (!inBounds(x, y)) return true;
    int b = grid[y * width + x];
    int nib = open ? (b >> 4) & 15 : b & 15;
    return ((nib >> (ox + 2 * oy)) & 1) != 0;
  }

  /** True when the whole tile is blocked (object, water, unmapped). */
  public boolean tileBlocked(int x, int y, boolean open) {
    return obstacle(x, y, 0, 0, open)
        && obstacle(x, y, 1, 0, open)
        && obstacle(x, y, 0, 1, open)
        && obstacle(x, y, 1, 1, open);
  }

  /**
   * rsc-path-finder's isValidGameStep. deltaX = +1 is west (game x grows westwards).
   *
   * @param open true to treat openable doors/gates as open
   */
  public boolean isValidStep(int sx, int sy, int dx, int dy, boolean open) {
    int ex = sx + dx, ey = sy + dy;
    if (!inBounds(ex, ey) || !inBounds(sx, sy)) return false;
    if (tileBlocked(ex, ey, open)) return false;
    if (dx == 0 && dy == -1) return !obstacle(sx, sy, 0, 0, open);
    if (dx == 1 && dy == -1)
      return !(obstacle(sx, sy, 0, 0, open)
          || obstacle(ex, sy, 1, 0, open)
          || obstacle(ex, ey, 1, 1, open));
    if (dx == 1 && dy == 0) return !obstacle(ex, sy, 1, 1, open);
    if (dx == 1 && dy == 1)
      return !(obstacle(sx, ey, 0, 0, open)
          || obstacle(ex, sy, 1, 1, open)
          || obstacle(ex, ey, 1, 0, open));
    if (dx == 0 && dy == 1) return !obstacle(ex, ey, 0, 0, open);
    if (dx == -1 && dy == 1)
      return !(obstacle(sx, sy, 1, 1, open) || obstacle(sx, ey, 1, 0, open));
    if (dx == -1 && dy == 0) return !obstacle(sx, sy, 1, 1, open);
    if (dx == -1 && dy == -1)
      return !(obstacle(sx, sy, 0, 0, open)
          || obstacle(sx, ey, 1, 1, open)
          || obstacle(ex, sy, 0, 0, open));
    return true;
  }

  /** A tile you can stand on and leave (not fully blocked, at least one valid step out). */
  public boolean standable(int x, int y, boolean open) {
    if (!inBounds(x, y) || tileBlocked(x, y, open)) return false;
    for (int dx = -1; dx <= 1; dx++)
      for (int dy = -1; dy <= 1; dy++)
        if ((dx != 0 || dy != 0) && isValidStep(x, y, dx, dy, open)) return true;
    return false;
  }

  /** Nearest standable tile within r of (x,y), or null. */
  public int[] snap(int x, int y, int r, boolean open) {
    if (standable(x, y, open)) return new int[] {x, y};
    for (int d = 1; d <= r; d++)
      for (int dx = -d; dx <= d; dx++)
        for (int dy = -d; dy <= d; dy++)
          if (Math.max(Math.abs(dx), Math.abs(dy)) == d && standable(x + dx, y + dy, open))
            return new int[] {x + dx, y + dy};
    return null;
  }

  /** Openable door/gate whose footprint is within r of either tile, closest first; or null. */
  public Door doorBetween(int ax, int ay, int bx, int by, int r) {
    Door best = null;
    int bestD = Integer.MAX_VALUE;
    for (int qx = -1; qx <= 1; qx++)
      for (int qy = -1; qy <= 1; qy++) {
        List<Door> l = doorIndex.get(bucket(ax, ay) + ((long) qx << 32) + qy);
        if (l == null) continue;
        for (Door d : l) {
          int dd = Math.min(d.distanceTo(ax, ay), d.distanceTo(bx, by));
          if (dd <= r && dd < bestD) {
            bestD = dd;
            best = d;
          }
        }
      }
    return best;
  }

  public int doorCount() {
    return doors.size();
  }

  private static long bucket(int x, int y) {
    return ((long) (x >> 3) << 32) + (y >> 3);
  }
}
