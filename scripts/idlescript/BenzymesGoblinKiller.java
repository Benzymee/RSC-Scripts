package scripting.idlescript;

import bot.Main;
import bot.tabs.MassStart;
import bot.tabs.MassStartSettings;
import bot.ui.scriptselector.models.Category;
import bot.ui.scriptselector.models.Parameter;
import bot.ui.scriptselector.models.ScriptInfo;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.SwingUtilities;
import models.entities.ItemId;
import models.entities.Location;
import models.entities.NpcId;
import models.entities.SkillId;
import orsc.ORSCharacter;
import scripting.idlescript.goblin.GoblinGui;
import scripting.idlescript.goblin.GoblinPaint;
import scripting.idlescript.goblin.GoblinStats;

/**
 * Kills goblins at the Lumbridge goblin hut on OpenRSC Coleslaw. Walks back after a death, buries
 * the bones from its own kills (optional), eats food when low and docks a small report under the
 * side buttons.
 *
 * <p>In the script selector look for <b>Benzyme's Goblin Killer</b>. Filter text: goblin / benzyme
 */
public class BenzymesGoblinKiller extends IdleScript implements MassStartSettings {
  private static final String DISPLAY_NAME = "Benzyme's Goblin Killer";
  private static final String AUTHOR = "Benzyme";
  private static final String DESCRIPTION =
      "Kills goblins by the Lumbridge goblin hut (OpenRSC Coleslaw). Buries the bones from its own "
          + "kills (or ignores them), eats food under the HP threshold, skips goblins that are "
          + "already fighting, puts its worn gear back on after dying (lost items are shown on "
          + "the report), walks back and counts deaths. The report docks under "
          + "the side buttons (::paint to pop it out). "
          + "Parameters: bury / nobury, style=strength|attack|defence|controlled, eat=50, "
          + "nogui, gui. Any parameter skips the start window unless gui is given.";
  private static final Category[] CATEGORIES =
      new Category[] {Category.COMBAT, Category.MELEE, Category.PRAYER};
  private static final Parameter[] PARAMETERS =
      new Parameter[] {
        new Parameter("bury", "Pick up and bury the bones from your own kills (default)."),
        new Parameter("nobury", "Ignore bones."),
        new Parameter(
            "style=strength",
            "Combat style: strength (aggressive), attack (accurate), defence (defensive) or "
                + "controlled. Default controlled."),
        new Parameter("eat=50", "Eat food at or below this HP percent (default 50)."),
        new Parameter("nogui", "Start straight away with the parameters / defaults."),
        new Parameter("gui", "Show the start window even when other parameters are given.")
      };

  public static final ScriptInfo info =
      new ScriptInfo(CATEGORIES, AUTHOR, DESCRIPTION, PARAMETERS, DISPLAY_NAME);

  // ---- Goblin area: Location.LUMBRIDGE_GOBLIN_HUT (Boundary 115,628..119,632, tile 117,630) ----
  private static final int[] GOBLIN_IDS = {
    NpcId.GOBLIN_LVL7.getId(), // 62
    NpcId.GOBLIN_LVL13.getId(), // 4
    NpcId.GOBLIN_RED_ARMOUR_LVL13.getId(), // 153
    NpcId.GOBLIN_GREEN_ARMOUR_LVL13.getId() // 154
  };
  /**
   * Only hunt goblins this close to the hut (tiles) so the bot does not wander off after strays.
   * Generous on purpose: the goblins roam the field around the hut, not just the 5x5 hut box.
   */
  private static final int HUNT_RADIUS = 15;
  /** How often to remember what is worn while alive (ms). */
  private static final long GEAR_SNAPSHOT_MS = 3000;
  /** Attempts per item when putting gear back on after a death. */
  private static final int REGEAR_TRIES = 3;
  /** Lumbridge respawn tile used by the AIO Trainer's death recovery (World.safety). */
  private static final int SPAWN_X = 122;

  private static final int SPAWN_Y = 648;
  private static final int BONES = ItemId.BONES.getId();
  private static final int[] COMBAT_SKILLS = {
    SkillId.ATTACK.getId(), SkillId.DEFENSE.getId(), SkillId.STRENGTH.getId(), SkillId.HITS.getId()
  };

  // ---- Settings (plain fields: MassStart "gui" mode copies these to followers) ----
  private boolean buryBones = true;
  private String style = "controlled";
  private int eatPercent = 50;
  /** True once settings are final. A follower that already has it set never opens the GUI. */
  private boolean configured;

  // ---- Runtime state (non-plain objects so MassStart does not copy them) ----
  private Run rt;
  private GoblinPaint paint;

  /** Everything that belongs to one run. */
  private static final class Run {
    final GoblinStats stats = new GoblinStats();
    volatile boolean waitingForGui;
    int targetIndex = -1;
    int targetNpcId = -1;
    int targetX;
    int targetY;
    boolean engaged;
    long attackAt;
    // bones from our last kill
    int boneX = -1;
    int boneY = -1;
    long boneUntil;
    int buryBefore = -1;
    // walking / death tracking
    int lastX = -1;
    int lastY = -1;
    int still;
    boolean returning;
    // worn gear (id -> name), remembered while alive, put back on after a death
    final Map<Integer, String> wornGear = new LinkedHashMap<>();
    long gearSnapshotAt;
    boolean needRegear;
    final Map<Integer, Integer> regearTries = new LinkedHashMap<>();
    final Set<Integer> lostIds = new LinkedHashSet<>();
    // rate-limited debug logging of rejected goblin candidates
    long lastRejectLogAt;
    String lastRejectLog = "";
  }

  @Override
  public int start(String[] parameters) {
    if (rt == null) {
      rt = new Run();
      boolean follower = configured; // copied from a mass-start leader
      boolean gui = false;
      if (!follower) {
        gui = readParameters(parameters);
        // Mass start: the first tab always shows the start window (pre-filled from the arguments)
        if (MassStart.isLeaderSetup()) gui = true;
        if (GraphicsEnvironment.isHeadless()) gui = false;
      }
      if (gui) {
        rt.waitingForGui = true;
        controller.setStatus("Goblin Killer: waiting for the start window");
        final Run run = rt;
        SwingUtilities.invokeLater(
            () ->
                GoblinGui.open(
                    buryBones,
                    style,
                    eatPercent,
                    (bury, chosenStyle, eat) -> {
                      buryBones = bury;
                      style = chosenStyle;
                      eatPercent = eat;
                      configured = true;
                      run.waitingForGui = false;
                    }));
      } else {
        configured = true;
        controller.setStatus(
            (follower ? "Mass-start: " : "")
                + "Goblin Killer: "
                + (buryBones ? "bury" : "nobury")
                + ", "
                + style);
      }
      return 300;
    }
    if (rt.waitingForGui) return 250;
    if (!controller.isRunning() || !controller.isLoggedIn()) return 1000;
    controller.setAutoLogin(true);
    controller.setBatchBars(true);
    controller.hideWelcomeScreen();
    rt.stats.capture(controller, COMBAT_SKILLS, SkillId.PRAYER.getId());

    // Defer the dock one tick (same as the AIO Trainer) so the side panel resize does not race.
    if (paint == null) {
      paint = new GoblinPaint();
      return 200;
    }
    if (!paint.isInstalled()) {
      paint.install();
      return 200;
    }
    int delay = tick();
    paint.refresh(rt.stats.snapshot(controller, buryBones, styleLabel()));
    return Math.max(50, delay);
  }

  /** @return true when the start window should be shown. */
  private boolean readParameters(String[] parameters) {
    boolean any = false;
    boolean gui = false;
    if (parameters != null) {
      for (String raw : parameters) {
        if (raw == null) continue;
        for (String token : raw.trim().toLowerCase().split("[\\s,]+")) {
          if (token.isEmpty()) continue;
          any = true;
          if (token.equals("bury")) buryBones = true;
          else if (token.equals("nobury") || token.equals("no-bury")) buryBones = false;
          else if (token.equals("gui")) gui = true;
          else if (token.equals("nogui")) gui = false;
          else if (token.startsWith("style=")) style = normaliseStyle(token.substring(6));
          else if (token.startsWith("eat=")) {
            try {
              eatPercent = Math.max(1, Math.min(99, Integer.parseInt(token.substring(4))));
            } catch (NumberFormatException ignored) {
              // keep default
            }
          } else {
            String s = normaliseStyle(token);
            if (!s.equals("controlled") || token.startsWith("control")) style = s;
          }
        }
      }
    }
    // No parameters at all: show the window. Any parameter: start immediately unless "gui".
    return gui || !any;
  }

  public static String normaliseStyle(String value) {
    String v = value == null ? "" : value.trim().toLowerCase();
    if (v.startsWith("str") || v.startsWith("agg")) return "strength";
    if (v.startsWith("att") || v.startsWith("acc")) return "attack";
    if (v.startsWith("def")) return "defence";
    return "controlled";
  }

  private String styleLabel() {
    switch (style) {
      case "strength":
        return "Strength (Aggressive)";
      case "attack":
        return "Attack (Accurate)";
      case "defence":
        return "Defence (Defensive)";
      default:
        return "Controlled";
    }
  }

  // =============================================================================================
  // Main loop
  // =============================================================================================

  private int tick() {
    GoblinStats st = rt.stats;
    if (controller.getNeedToMove()) {
      controller.moveCharacter();
      return 400;
    }
    if (controller.isAuthentic() && controller.getShouldSleep()) {
      st.status = "Sleeping";
      controller.sleepHandler(true);
      return 600;
    }
    noteDeathByPosition();
    if (rt.needRegear) {
      int d = regear();
      if (d > 0) return d;
    } else {
      rememberGear(false);
    }
    applyStyle();
    noteBury();

    // 1. Eat when low.
    if (hitsPercent() > 0 && hitsPercent() <= eatPercent) {
      int food = findFood();
      if (food > 0) {
        st.status = "Eating " + controller.getItemName(food);
        controller.itemCommand(food);
        return 700;
      }
    }

    // 2. Fighting: wait, but track the target so we know when it dies.
    if (controller.isInCombat()) {
      if (rt.targetIndex >= 0) {
        rt.engaged = true;
        ORSCharacter npc = findTarget();
        if (npc != null) {
          rt.targetX = tileX(npc);
          rt.targetY = tileY(npc);
        }
      }
      st.status = "Fighting a goblin";
      return 300;
    }

    // 3. Out of combat: did our target die?
    if (rt.targetIndex >= 0) {
      ORSCharacter npc = findTarget();
      if (npc == null) {
        if (rt.engaged) {
          st.kills++;
          rt.boneX = rt.targetX;
          rt.boneY = rt.targetY;
          rt.boneUntil = System.currentTimeMillis() + 5000;
        }
        clearTarget();
      } else {
        rt.targetX = tileX(npc);
        rt.targetY = tileY(npc);
        long since = System.currentTimeMillis() - rt.attackAt;
        if (!rt.engaged && since < 4000 && !controller.isNpcInCombat(npc.serverIndex)) {
          st.status = "Attacking a goblin";
          return 250; // still walking up to it
        }
        if (rt.engaged || controller.isNpcInCombat(npc.serverIndex) || since >= 4000) {
          // Fight ended without a death (it ran / someone else took it) or attack never landed.
          clearTarget();
        }
      }
    }

    // 4. Bones from our kill.
    if (buryBones) {
      int bury = buryOrLoot();
      if (bury > 0) return bury;
    }

    // 5. Get back to the goblins.
    if (controller.distanceTo(Location.LUMBRIDGE_GOBLIN_HUT.getX(), Location.LUMBRIDGE_GOBLIN_HUT.getY())
        > HUNT_RADIUS) {
      return walkBack();
    }
    rt.returning = false;
    rt.still = 0;

    // 6. Attack the nearest free goblin near the hut.
    ORSCharacter goblin = nearestFreeGoblin();
    if (goblin == null) {
      st.status = "Waiting for a free goblin";
      if (controller.distanceTo(Location.LUMBRIDGE_GOBLIN_HUT.getX(), Location.LUMBRIDGE_GOBLIN_HUT.getY()) > 4
          && !controller.isCurrentlyWalking()) {
        controller.walkToAsync(
            Location.LUMBRIDGE_GOBLIN_HUT.getX(), Location.LUMBRIDGE_GOBLIN_HUT.getY(), 2);
      }
      return 600;
    }
    rt.targetIndex = goblin.serverIndex;
    rt.targetNpcId = goblin.npcId;
    rt.targetX = tileX(goblin);
    rt.targetY = tileY(goblin);
    rt.engaged = false;
    rt.attackAt = System.currentTimeMillis();
    st.status = "Attacking a goblin";
    controller.attackNpc(goblin.serverIndex);
    return 600;
  }

  private ORSCharacter findTarget() {
    for (ORSCharacter npc : controller.getNpcs()) {
      if (npc != null && npc.serverIndex == rt.targetIndex && npc.npcId == rt.targetNpcId) {
        return npc;
      }
    }
    return null;
  }

  private void clearTarget() {
    rt.targetIndex = -1;
    rt.targetNpcId = -1;
    rt.engaged = false;
  }

  /**
   * Nearest goblin near the hut that nobody is fighting.
   *
   * <p>NPC positions in {@link ORSCharacter} are local scene units (tile * tile size + 64, relative
   * to the loaded region), not world tiles; {@link controller.Controller#convertX(int)} / {@link
   * controller.Controller#convertZ(int)} turn them into the world tiles used by the hut location
   * and by {@link controller.Controller#distanceTo(int, int)}.
   */
  private ORSCharacter nearestFreeGoblin() {
    int hutX = Location.LUMBRIDGE_GOBLIN_HUT.getX();
    int hutY = Location.LUMBRIDGE_GOBLIN_HUT.getY();
    ORSCharacter best = null;
    int bestDist = Integer.MAX_VALUE;
    int seen = 0;
    List<String> rejected = new ArrayList<>();
    for (ORSCharacter npc : controller.getNpcs()) {
      if (npc == null || !isGoblin(npc.npcId)) continue;
      seen++;
      int x = tileX(npc);
      int y = tileY(npc);
      int fromHut = controller.distance(x, y, hutX, hutY);
      if (fromHut > HUNT_RADIUS) {
        rejected.add("#" + npc.serverIndex + " id " + npc.npcId + " at " + x + "," + y
            + ": " + fromHut + " tiles from the hut (max " + HUNT_RADIUS + ")");
        continue;
      }
      // Never steal a goblin that is already fighting someone.
      if (controller.isNpcInCombat(npc.serverIndex)) {
        rejected.add("#" + npc.serverIndex + " id " + npc.npcId + " at " + x + "," + y
            + ": already in combat");
        continue;
      }
      int d = controller.distanceTo(x, y);
      if (d < bestDist) {
        bestDist = d;
        best = npc;
      }
    }
    if (best == null) {
      logRejects(
          "no free goblin: "
              + seen
              + " goblin(s) in view"
              + (rejected.isEmpty() ? "" : "; rejected " + String.join(" | ", rejected)));
    }
    return best;
  }

  private int tileX(ORSCharacter npc) {
    return controller.convertX(npc.currentX);
  }

  private int tileY(ORSCharacter npc) {
    return controller.convertZ(npc.currentZ);
  }

  /** Any goblin variant: the known ids, or an NPC simply named "Goblin" (not Hobgoblin). */
  private boolean isGoblin(int id) {
    for (int g : GOBLIN_IDS) if (g == id) return true;
    try {
      String name = controller.getNpcName(id);
      return name != null && name.trim().equalsIgnoreCase("goblin");
    } catch (Throwable ignored) {
      return false;
    }
  }

  /** Debug log, at most every 5 s unless the reason changes. */
  private void logRejects(String text) {
    long now = System.currentTimeMillis();
    if (text.equals(rt.lastRejectLog) && now - rt.lastRejectLogAt < 5000) return;
    if (now - rt.lastRejectLogAt < 1000) return;
    rt.lastRejectLog = text;
    rt.lastRejectLogAt = now;
    try {
      Main.logDebug("[Goblin Killer] " + text);
    } catch (Throwable ignored) {
      // logging only
    }
  }

  // ---- Gear: remember what is worn, put it back on after a death --------------------------------

  /** Worn item ids right now (Coleslaw keeps them outside the inventory; authentic marks slots). */
  private Set<Integer> wornNow() {
    Set<Integer> out = new LinkedHashSet<>();
    try {
      int[] ids = controller.getEquippedItemIds();
      if (ids != null) for (int id : ids) if (id > 0) out.add(id);
    } catch (Throwable ignored) {
      // no equipment array on this client
    }
    try {
      int[] inv = controller.getInventoryItemIds();
      for (int slot = 0; inv != null && slot < inv.length; slot++) {
        if (inv[slot] > 0 && controller.isEquipped(slot)) out.add(inv[slot]);
      }
    } catch (Throwable ignored) {
      // slot flags not available
    }
    return out;
  }

  /** Remembers the worn gear every few seconds while alive (not right after a death). */
  private void rememberGear(boolean force) {
    long now = System.currentTimeMillis();
    if (!force && now - rt.gearSnapshotAt < GEAR_SNAPSHOT_MS) return;
    if (now - rt.stats.lastDeathAt < 10000) return; // just respawned: items are unequipped
    rt.gearSnapshotAt = now;
    Set<Integer> worn = wornNow();
    if (worn.isEmpty()) return; // naked or not loaded yet: keep the last known set
    rt.wornGear.clear();
    for (int id : worn) rt.wornGear.put(id, itemName(id));
  }

  /**
   * After a death: wield every remembered item that is in the inventory but not worn, a few tries
   * each. Items that are gone are logged and shown on the paint; the script carries on either way.
   *
   * @return delay to wait after an equip attempt, or 0 when finished
   */
  private int regear() {
    GoblinStats st = rt.stats;
    if (rt.wornGear.isEmpty()) {
      rt.needRegear = false;
      return 0;
    }
    Set<Integer> worn = wornNow();
    for (Map.Entry<Integer, String> e : rt.wornGear.entrySet()) {
      int id = e.getKey();
      if (worn.contains(id) || controller.isItemIdEquipped(id) || rt.lostIds.contains(id)) continue;
      int tries = rt.regearTries.getOrDefault(id, 0);
      boolean inInventory = controller.getUnnotedInventoryItemCount(id) > 0;
      if (!inInventory || tries >= REGEAR_TRIES) {
        rt.lostIds.add(id);
        String why = inInventory ? "could not wield" : "lost on death";
        controller.log("Goblin Killer: " + e.getValue() + " " + why + ".", "red");
        Main.logDebug("[Goblin Killer] regear: " + e.getValue() + " (" + id + ") " + why);
        continue;
      }
      rt.regearTries.put(id, tries + 1);
      st.status = "Re-equipping " + e.getValue();
      controller.equipItemById(id);
      return 900;
    }
    // finished: every item worn again or given up on
    boolean allBack = rt.lostIds.isEmpty();
    if (allBack) st.regeared++;
    List<String> lostNames = new ArrayList<>();
    for (int id : rt.lostIds) lostNames.add(rt.wornGear.getOrDefault(id, "item " + id));
    if (!lostNames.isEmpty()) {
      String add = String.join(", ", lostNames);
      st.lost = st.lost.isEmpty() ? add : st.lost + ", " + add;
      for (int id : rt.lostIds) rt.wornGear.remove(id);
    }
    rt.lostIds.clear();
    rt.regearTries.clear();
    rt.needRegear = false;
    rt.gearSnapshotAt = 0;
    st.status = allBack ? "Gear back on - walking back" : "Some gear lost - walking back";
    return 0;
  }

  private String itemName(int id) {
    try {
      String n = controller.getItemName(id);
      if (n != null && !n.isEmpty()) return n;
    } catch (Throwable ignored) {
      // fall through
    }
    return "item " + id;
  }

  // ---- Bones ------------------------------------------------------------------------------------

  /** Picks up bones dropped by our own kill and buries any bones carried. */
  private int buryOrLoot() {
    GoblinStats st = rt.stats;
    if (controller.getInventoryItemCount(BONES) > 0) {
      st.status = "Burying bones";
      rt.buryBefore = controller.getInventoryItemCount(BONES);
      controller.itemCommand(BONES);
      return 700;
    }
    if (rt.boneX < 0) return 0;
    if (System.currentTimeMillis() > rt.boneUntil || controller.getInventoryItemCount() >= 30) {
      rt.boneX = -1;
      return 0;
    }
    int[] spot = bonesNear(rt.boneX, rt.boneY);
    if (spot == null) return 250; // the drop can appear a tick after the goblin vanishes
    st.status = "Picking up bones";
    controller.pickupItem(spot[0], spot[1], BONES, false, false);
    rt.boneX = -1; // one pickup per kill
    return 700;
  }

  private int[] bonesNear(int x, int y) {
    int count = controller.getGroundItemsCount();
    int[] ids = controller.getGroundItems();
    int[] xs = controller.getGroundItemsX();
    int[] ys = controller.getGroundItemsY();
    if (ids == null || xs == null || ys == null) return null;
    int limit = Math.min(count, Math.min(ids.length, Math.min(xs.length, ys.length)));
    for (int i = 0; i < limit; i++) {
      if (ids[i] == BONES && Math.abs(xs[i] - x) <= 1 && Math.abs(ys[i] - y) <= 1) {
        return new int[] {xs[i], ys[i]};
      }
    }
    return null;
  }

  private void noteBury() {
    if (rt.buryBefore < 0) return;
    int now = controller.getInventoryItemCount(BONES);
    if (now < rt.buryBefore) rt.stats.buried += rt.buryBefore - now;
    rt.buryBefore = -1;
  }

  // ---- Walking / deaths --------------------------------------------------------------------------

  private int walkBack() {
    GoblinStats st = rt.stats;
    int x = Location.LUMBRIDGE_GOBLIN_HUT.getX();
    int y = Location.LUMBRIDGE_GOBLIN_HUT.getY();
    rt.returning = true;
    st.status = "Walking to the goblin hut";
    // Stuck in front of a door or gate? Open it.
    if (controller.currentX() == rt.lastX && controller.currentY() == rt.lastY) rt.still++;
    else rt.still = 0;
    rt.lastX = controller.currentX();
    rt.lastY = controller.currentY();
    if (rt.still >= 3 && controller.isNearbyDoorClosed(2)) {
      st.status = "Opening a door";
      controller.openNearbyDoor(2);
      rt.still = 0;
      return 900;
    }
    if (controller.isCurrentlyWalking() && rt.still == 0) return 400;
    boolean ok = controller.walkTowards(x, y);
    if (!ok) controller.walkToAsync(x, y, 2);
    return 700;
  }

  /** Backup for the death message: we were away from Lumbridge and suddenly stand at the spawn. */
  private void noteDeathByPosition() {
    int x = controller.currentX();
    int y = controller.currentY();
    GoblinStats st = rt.stats;
    if (st.prevX >= 0
        && controller.distance(st.prevX, st.prevY, x, y) >= 12
        && controller.distance(x, y, SPAWN_X, SPAWN_Y) <= 6
        && System.currentTimeMillis() - st.lastDeathAt > 15000) {
      onDeath();
    }
    st.prevX = x;
    st.prevY = y;
  }

  private void onDeath() {
    GoblinStats st = rt.stats;
    st.deaths++;
    st.lastDeathAt = System.currentTimeMillis();
    rt.needRegear = !rt.wornGear.isEmpty();
    rt.regearTries.clear();
    rt.lostIds.clear();
    clearTarget();
    rt.boneX = -1;
    st.status = "Died - walking back from Lumbridge";
  }

  private int hitsPercent() {
    int max = Math.max(1, controller.getBaseStat(SkillId.HITS.getId()));
    return controller.getCurrentStat(SkillId.HITS.getId()) * 100 / max;
  }

  private int findFood() {
    int[] foods = controller.getFoodIds();
    if (foods == null) return -1;
    for (int id : foods) if (id > 0 && controller.getInventoryItemCount(id) > 0) return id;
    return -1;
  }

  private void applyStyle() {
    int mode = 0; // controlled
    if ("strength".equals(style)) mode = 1;
    else if ("attack".equals(style)) mode = 2;
    else if ("defence".equals(style)) mode = 3;
    if (controller.getFightMode() != mode) controller.setFightMode(mode);
  }

  // =============================================================================================
  // Interrupts
  // =============================================================================================

  @Override
  public void serverMessageInterrupt(String message) {
    if (message == null || rt == null) return;
    String lower = message.toLowerCase();
    if (lower.contains("oh dear") || lower.contains("you are dead")) {
      if (System.currentTimeMillis() - rt.stats.lastDeathAt > 15000) onDeath();
    }
  }

  @Override
  public void paintInterrupt() {
    // The report is the side-panel dock, not a box drawn over the game.
  }

  @Override
  public void chatCommandInterrupt(String commandText) {
    if (commandText == null || paint == null) return;
    String command = commandText.trim().toLowerCase();
    if (command.equals("paint") || command.equals("popout") || command.equals("dock")) {
      paint.toggle();
    }
  }

  @Override
  public void cleanup() {
    if (paint != null) paint.dispose();
    paint = null;
    rt = null;
    configured = false;
    super.cleanup();
  }
}
