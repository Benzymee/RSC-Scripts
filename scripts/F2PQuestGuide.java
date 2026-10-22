/*
 * F2PQuestGuide - IdleRSC external script (hot-loaded from the scripts folder).
 *
 * Runs the free-to-play quests in the order of the "RuneScape Classic - Complete F2P Quest Guide
 * (Script-Ready)". Each quest is a small state machine keyed on the server quest stage, so a
 * restart resumes from wherever the account is. Finished quests are detected with
 * Controller.getQuestStage / isQuestComplete and skipped.
 *
 * Start it from the script selector ("F2PQuestGuide [ext]"); a dark start window lets you pick
 * quests and options. Parameters (optional, skips the window): a comma separated list of quest
 * keys, e.g. "cook,sheep,ghost" or "all". Extra flags: gather, skipreq, stopeach, manual=pause|
 * skip|stop, eat=50.
 *
 * Steps that can't be automated safely are "manual steps": they are logged with a clear message
 * and, depending on the option, the script pauses until the stage changes (you do it by hand),
 * skips to the next quest, or stops.
 *
 * Everything is logged with the prefix [F2PQuestGuide] through bot.Main.log (console + probe).
 *
 * Self-contained: default package, Java 8 compatible, helper classes are nested so nothing
 * collides with other scripts compiled in the same batch. Uses only the public Controller API.
 */

import bot.Main;
import controller.Controller;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import models.entities.ItemId;
import models.entities.NpcId;
import models.entities.QuestId;
import orsc.ORSCharacter;
import scripting.idlescript.IdleScript;

public class F2PQuestGuide extends IdleScript {

  // ---------------------------------------------------------------------------------------------
  // Script entry points
  // ---------------------------------------------------------------------------------------------

  private final Engine e = new Engine();
  private volatile boolean guiShown = false;
  private volatile boolean startRequested = false;
  private volatile boolean finished = false;
  private JFrame frame;

  @Override
  public int start(String[] parameters) {
    Controller c = Main.getController();
    if (c == null) return 1000;
    e.c = c;
    if (finished) {
      c.stop();
      return 1000;
    }
    if (!startRequested) {
      if (parameters != null && parameters.length > 0 && joined(parameters).trim().length() > 0) {
        e.parseParameters(joined(parameters));
        startRequested = true;
      } else if (!guiShown) {
        guiShown = true;
        showGui();
        return 500;
      } else {
        return 500;
      }
    }
    try {
      e.runAll();
    } catch (Throwable t) {
      Main.logError("[F2PQuestGuide] unexpected error, stopping", t);
    }
    finished = true;
    c.stop();
    return 1000;
  }

  private static String joined(String[] p) {
    StringBuilder sb = new StringBuilder();
    for (String s : p) {
      if (s == null) continue;
      if (sb.length() > 0) sb.append(' ');
      sb.append(s);
    }
    return sb.toString();
  }

  @Override
  public void serverMessageInterrupt(String message) {
    e.onServerMessage(message);
  }

  @Override
  public void questMessageInterrupt(String message) {
    e.onServerMessage(message);
  }

  @Override
  public void cleanup() {
    final JFrame f = frame;
    if (f != null) SwingUtilities.invokeLater(f::dispose);
    e.log("script stopped");
  }

  // ---------------------------------------------------------------------------------------------
  // Start window (Swing, dark theme)
  // ---------------------------------------------------------------------------------------------

  private static final Color BG = new Color(0x2B2B2B);
  private static final Color BG2 = new Color(0x3C3F41);
  private static final Color FG = new Color(0xDDDDDD);
  private static final Color DIM = new Color(0x9A9A9A);
  private static final Color OK = new Color(0x6A8759);
  private static final Color WARN = new Color(0xCC7832);

  private void showGui() {
    SwingUtilities.invokeLater(
        () -> {
          try {
            buildGui();
          } catch (Throwable t) {
            Main.logError("[F2PQuestGuide] couldn't open the start window; starting with defaults", t);
            e.selectDefaults();
            startRequested = true;
          }
        });
  }

  private static void dark(java.awt.Component comp) {
    comp.setBackground(BG);
    comp.setForeground(FG);
  }

  private void buildGui() {
    final Controller c = e.c;
    frame = new JFrame("F2P Quest Guide");
    frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    JPanel root = new JPanel(new BorderLayout(8, 8));
    root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
    dark(root);

    JLabel title =
        new JLabel(
            "<html><b>F2P Quest Guide</b> &mdash; quests in the guide's optimal order."
                + "<br><span style='color:#9a9a9a'>Completed quests are unticked and skipped."
                + " QP: "
                + safeQp(c)
                + "</span></html>");
    dark(title);
    root.add(title, BorderLayout.NORTH);

    JPanel list = new JPanel();
    list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
    dark(list);
    final List<JCheckBox> boxes = new ArrayList<>();
    for (Quest q : e.quests) {
      int stage = e.stageOf(q);
      String state =
          stage == -1 ? "done" : stage == 0 ? "not started" : stage == -2 ? "?" : "stage " + stage;
      JCheckBox cb =
          new JCheckBox(
              String.format(
                  "<html>%2d. %s <span style='color:%s'>[%s]</span> <span style='color:#9a9a9a'>%s</span></html>",
                  q.order, q.name, stage == -1 ? "#6a8759" : "#cc7832", state, q.automation));
      cb.setSelected(stage != -1);
      cb.setEnabled(stage != -1);
      cb.setOpaque(false);
      cb.setForeground(FG);
      cb.setFocusPainted(false);
      boxes.add(cb);
      list.add(cb);
    }
    JScrollPane sp = new JScrollPane(list);
    sp.getViewport().setBackground(BG);
    sp.setBorder(BorderFactory.createLineBorder(BG2));
    sp.setPreferredSize(new Dimension(640, 420));
    root.add(sp, BorderLayout.CENTER);

    JPanel opts = new JPanel(new GridLayout(0, 1, 2, 2));
    dark(opts);
    final JCheckBox gather = new JCheckBox("Gather items first (before starting each quest)", true);
    final JCheckBox skipReq = new JCheckBox("Skip quests missing skill / quest point requirements", true);
    final JCheckBox stopEach = new JCheckBox("Stop after each quest", false);
    for (JCheckBox b : new JCheckBox[] {gather, skipReq, stopEach}) {
      b.setOpaque(false);
      b.setForeground(FG);
      b.setFocusPainted(false);
      opts.add(b);
    }
    JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    row.setOpaque(false);
    JLabel ml = new JLabel("On a manual step:");
    ml.setForeground(FG);
    final JComboBox<String> manual =
        new JComboBox<>(
            new String[] {
              "Pause until I do it (resume when the stage changes)",
              "Skip to the next quest",
              "Stop the script"
            });
    manual.setBackground(BG2);
    manual.setForeground(FG);
    JLabel el = new JLabel("   Eat below HP %:");
    el.setForeground(FG);
    final JSpinner eat = new JSpinner(new SpinnerNumberModel(50, 10, 90, 5));
    eat.getEditor().getComponent(0).setBackground(BG2);
    eat.getEditor().getComponent(0).setForeground(FG);
    row.add(ml);
    row.add(manual);
    row.add(el);
    row.add(eat);
    opts.add(row);

    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    buttons.setOpaque(false);
    JButton all = new JButton("All open");
    JButton none = new JButton("None");
    JButton go = new JButton("Start");
    for (JButton b : new JButton[] {all, none, go}) {
      b.setBackground(BG2);
      b.setForeground(FG);
      b.setFocusPainted(false);
      buttons.add(b);
    }
    go.setFont(go.getFont().deriveFont(Font.BOLD));
    all.addActionListener(
        ev -> {
          for (JCheckBox b : boxes) if (b.isEnabled()) b.setSelected(true);
        });
    none.addActionListener(
        ev -> {
          for (JCheckBox b : boxes) b.setSelected(false);
        });
    go.addActionListener(
        ev -> {
          e.selected.clear();
          for (int i = 0; i < boxes.size(); i++)
            if (boxes.get(i).isSelected()) e.selected.add(e.quests.get(i));
          e.gatherFirst = gather.isSelected();
          e.skipMissingReqs = skipReq.isSelected();
          e.stopAfterEach = stopEach.isSelected();
          e.manualMode = manual.getSelectedIndex();
          e.eatPercent = (Integer) eat.getValue();
          startRequested = true;
          frame.dispose();
        });
    frame.addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosed(WindowEvent we) {
            if (!startRequested) {
              e.log("start window closed without starting; stopping");
              finished = true;
              startRequested = true;
            }
          }
        });
    JPanel south = new JPanel(new BorderLayout());
    south.setOpaque(false);
    south.add(opts, BorderLayout.CENTER);
    south.add(buttons, BorderLayout.SOUTH);
    root.add(south, BorderLayout.SOUTH);

    frame.setContentPane(root);
    frame.pack();
    frame.setLocationRelativeTo(null);
    frame.setVisible(true);
  }

  private static String safeQp(Controller c) {
    try {
      return String.valueOf(c.getQuestPoints());
    } catch (Throwable t) {
      return "?";
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Engine: runner, primitives (walk, talk, items, shops, combat), logging
  // ---------------------------------------------------------------------------------------------

  /** Thrown to abandon the current attempt of a step (it is retried). */
  static final class StepFailed extends RuntimeException {
    private static final long serialVersionUID = 1L;

    StepFailed(String m) {
      super(m);
    }
  }

  /** Thrown when a step needs the player. */
  static final class ManualStep extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ManualStep(String m) {
      super(m);
    }
  }

  static final int FLOOR = 944;
  static final int HITS = 3;
  static final int COOKING = 7;
  static final int MINING = 14;
  static final int MAGIC = 6;

  static final int[] FOOD = {
    ItemId.LOBSTER.getId(), ItemId.SWORDFISH.getId(), ItemId.TUNA.getId(), ItemId.SALMON.getId(),
    ItemId.TROUT.getId(), ItemId.HERRING.getId(), ItemId.SARDINE.getId(), ItemId.ANCHOVIES.getId(),
    ItemId.SHRIMP.getId(), ItemId.MEAT_PIE.getId(), ItemId.STEW.getId(), ItemId.CAKE.getId(),
    ItemId.BREAD.getId(), ItemId.COOKEDMEAT.getId()
  };

  static final class Engine {
    Controller c;
    final List<Quest> quests = new ArrayList<>();
    final Set<Quest> selected = new LinkedHashSet<>();
    volatile boolean gatherFirst = true;
    volatile boolean skipMissingReqs = true;
    volatile boolean stopAfterEach = false;
    volatile int manualMode = 0; // 0 pause, 1 skip, 2 stop
    volatile int eatPercent = 50;

    long deadline = Long.MAX_VALUE;
    Quest current;
    final Set<Integer> reservedFood = new java.util.HashSet<>();

    volatile String lastServerMsg = "";
    volatile long lastServerMsgAt = 0;

    Engine() {
      quests.add(new CooksAssistant());
      quests.add(new SheepShearer());
      quests.add(new RestlessGhost());
      quests.add(new WitchsPotion());
      quests.add(new PrinceAliRescue());
      quests.add(new ShieldOfArrav());
      quests.add(new RomeoAndJuliet());
      quests.add(new DemonSlayer());
      quests.add(new VampireSlayer());
      quests.add(new ErnestTheChicken());
      quests.add(new ImpCatcher());
      quests.add(new DoricsQuest());
      quests.add(new GoblinDiplomacy());
      quests.add(new PiratesTreasure());
      quests.add(new BlackKnightsFortress());
      quests.add(new KnightsSword());
      quests.add(new DragonSlayer());
      for (int i = 0; i < quests.size(); i++) quests.get(i).order = i + 1;
    }

    // ----- logging ------------------------------------------------------------------------------

    void log(String s) {
      String q = current != null ? "[" + current.name + "] " : "";
      Main.log("[F2PQuestGuide] " + q + s);
      try {
        if (c != null) c.setStatus("@cya@F2P: @whi@" + (current != null ? current.name + ": " : "") + s);
      } catch (Throwable ignored) {
      }
    }

    void warn(String s) {
      log("WARNING: " + s);
      try {
        if (c != null) c.displayMessage("@yel@[F2PQuestGuide] " + s);
      } catch (Throwable ignored) {
      }
    }

    void onServerMessage(String m) {
      if (m == null) return;
      lastServerMsg = m.toLowerCase(Locale.ROOT);
      lastServerMsgAt = System.currentTimeMillis();
      if (current instanceof ErnestTheChicken) ((ErnestTheChicken) current).onMessage(lastServerMsg);
    }

    // ----- parameters ---------------------------------------------------------------------------

    void selectDefaults() {
      selected.clear();
      for (Quest q : quests) if (stageOf(q) != -1) selected.add(q);
    }

    void parseParameters(String p) {
      selected.clear();
      for (String raw : p.split("[,\\s]+")) {
        String t = raw.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) continue;
        if (t.equals("all")) {
          for (Quest q : quests) selected.add(q);
        } else if (t.equals("gather")) gatherFirst = true;
        else if (t.equals("nogather")) gatherFirst = false;
        else if (t.equals("skipreq")) skipMissingReqs = true;
        else if (t.equals("noskipreq")) skipMissingReqs = false;
        else if (t.equals("stopeach")) stopAfterEach = true;
        else if (t.startsWith("manual=")) {
          String v = t.substring(7);
          manualMode = v.startsWith("skip") ? 1 : v.startsWith("stop") ? 2 : 0;
        } else if (t.startsWith("eat=")) {
          try {
            eatPercent = Math.max(10, Math.min(90, Integer.parseInt(t.substring(4))));
          } catch (NumberFormatException ignored) {
          }
        } else {
          boolean found = false;
          for (Quest q : quests)
            if (q.key.equals(t)) {
              selected.add(q);
              found = true;
            }
          if (!found) warn("unknown parameter '" + raw + "'");
        }
      }
      if (selected.isEmpty()) selectDefaults();
      log("parameters: " + selected.size() + " quests, gather=" + gatherFirst + ", skipreq="
          + skipMissingReqs + ", stopeach=" + stopAfterEach + ", manual=" + manualMode);
    }

    // ----- runner -------------------------------------------------------------------------------

    int stageOf(Quest q) {
      try {
        if (c.isQuestComplete(q.id)) return -1;
        return c.getQuestStage(q.id);
      } catch (Throwable t) {
        return -2;
      }
    }

    boolean running() {
      return c.isRunning();
    }

    /** True while the script runs and the current step's time budget isn't used up. */
    boolean ok() {
      return c.isRunning() && System.currentTimeMillis() < deadline;
    }

    void runAll() {
      List<Quest> order = new ArrayList<>();
      for (Quest q : quests) if (selected.contains(q)) order.add(q);
      log("starting; quests: " + names(order));
      List<String> done = new ArrayList<>(), skipped = new ArrayList<>(), failed = new ArrayList<>();
      for (Quest q : order) {
        if (!running()) break;
        current = q;
        reservedFood.clear();
        String result = runQuest(q);
        current = null;
        log(q.name + ": " + result);
        if (result.startsWith("complete")) done.add(q.name);
        else if (result.startsWith("skipped")) skipped.add(q.name + " (" + result + ")");
        else failed.add(q.name + " (" + result + ")");
        if (result.startsWith("complete") && stopAfterEach && running()) {
          log("'Stop after each quest' is on; stopping. Start the script again to continue.");
          break;
        }
        if (result.startsWith("stop")) break;
      }
      log("finished. Completed: " + done + "; skipped: " + skipped + "; failed/manual: " + failed
          + "; quest points: " + safeQp(c));
      try {
        c.displayMessage("@gre@[F2PQuestGuide] finished, see the log for the summary");
      } catch (Throwable ignored) {
      }
    }

    String names(List<Quest> l) {
      List<String> n = new ArrayList<>();
      for (Quest q : l) n.add(q.name);
      return n.toString();
    }

    /** Runs one quest to completion; returns a short result text. */
    String runQuest(Quest q) {
      int stage = stageOf(q);
      if (stage == -1) return "skipped: already complete";
      log("---- " + q.name + " (stage " + stage + ", " + q.automation + ") ----");
      if (skipMissingReqs) {
        String miss = q.missingRequirements(this);
        if (miss != null) return "skipped: missing requirements: " + miss;
      }
      int lastStage = -99;
      int attempts = 0;
      boolean gathered = false;
      while (running()) {
        stage = stageOf(q);
        if (stage == -1) {
          log("quest complete! QP now " + safeQp(c));
          deadline = Long.MAX_VALUE;
          return "complete";
        }
        if (stage != lastStage) {
          attempts = 0;
          lastStage = stage;
        }
        attempts++;
        if (attempts > q.maxAttempts) {
          deadline = Long.MAX_VALUE;
          warn("stage " + stage + " didn't advance after " + q.maxAttempts + " attempts; giving up on this quest");
          return "failed at stage " + stage;
        }
        deadline = System.currentTimeMillis() + q.stepTimeoutMs;
        try {
          if (gatherFirst && !gathered && stage >= 0) {
            gathered = true;
            log("gathering items first");
            q.gather(this, stage);
            if (stageOf(q) != stage) continue;
          }
          log("stage " + stage + " - attempt " + attempts + "/" + q.maxAttempts);
          q.step(this, stage);
          if (!c.isRunning()) break;
          if (System.currentTimeMillis() >= deadline) {
            warn("stage " + stage + " step timed out (" + (q.stepTimeoutMs / 60000) + " min), retrying");
          } else if (!waitStageChange(q, stage, 8000)) {
            log("stage still " + stage + ", repeating the step");
          }
        } catch (ManualStep m) {
          deadline = Long.MAX_VALUE;
          String r = handleManual(q, stage, m.getMessage());
          if (r != null) return r;
          attempts = 0;
        } catch (StepFailed f) {
          warn("step failed: " + f.getMessage() + " - retrying");
          sleep(2000);
        } catch (RuntimeException ex) {
          Main.logError("[F2PQuestGuide] [" + q.name + "] error in stage " + stage, ex);
          sleep(3000);
        }
      }
      deadline = Long.MAX_VALUE;
      return "stopped (script stopped at stage " + stageOf(q) + ")";
    }

    boolean waitStageChange(Quest q, int stage, long ms) {
      long until = System.currentTimeMillis() + ms;
      while (c.isRunning() && System.currentTimeMillis() < until) {
        if (stageOf(q) != stage) return true;
        c.sleep(320);
      }
      return stageOf(q) != stage;
    }

    /** Returns null to continue the quest, or a result text to leave it. */
    String handleManual(Quest q, int stage, String msg) {
      String full = "MANUAL STEP (" + q.name + ", stage " + stage + "): " + msg;
      log(full);
      try {
        c.displayMessage("@red@[F2PQuestGuide] @whi@" + full);
      } catch (Throwable ignored) {
      }
      if (manualMode == 2) {
        log("stopping the script so you can do this by hand (option 'Stop the script').");
        c.stop();
        return "stop: manual step at stage " + stage;
      }
      if (manualMode == 1) return "skipped: manual step at stage " + stage + ": " + msg;
      log("pausing until the quest stage changes from " + stage + " (do the step by hand; stop the script to quit)");
      long nextNag = System.currentTimeMillis() + 120000;
      int inv = inventorySignature();
      long invChangedAt = 0;
      while (c.isRunning()) {
        int s = stageOf(q);
        if (s != stage) {
          log("stage changed to " + s + ", resuming");
          return null;
        }
        int now = inventorySignature();
        if (now != inv) {
          inv = now;
          invChangedAt = System.currentTimeMillis();
        } else if (invChangedAt > 0 && System.currentTimeMillis() - invChangedAt > 15000) {
          log("inventory changed, retrying the step");
          return null;
        }
        if (System.currentTimeMillis() > nextNag) {
          nextNag = System.currentTimeMillis() + 120000;
          log("still waiting for: " + msg);
        }
        c.sleep(1000);
      }
      return "stopped while waiting for a manual step";
    }

    int inventorySignature() {
      try {
        int[] ids = c.getInventoryItemIds();
        int h = 1;
        if (ids != null)
          for (int i = 0; i < Math.min(ids.length, c.getInventoryItemCount()); i++) h = 31 * h + ids[i];
        return 31 * h + c.getInventoryItemCount(COINS);
      } catch (RuntimeException ex) {
        return 0;
      }
    }

    ManualStep manual(String msg) {
      return new ManualStep(msg);
    }

    // ----- basics -------------------------------------------------------------------------------

    void sleep(int ms) {
      c.sleep(ms);
    }

    boolean waitFor(BooleanSupplier cond, long ms) {
      long until = System.currentTimeMillis() + ms;
      while (c.isRunning()) {
        if (cond.getAsBoolean()) return true;
        if (System.currentTimeMillis() > until) return false;
        c.sleep(250);
      }
      return false;
    }

    int x() {
      return c.currentX();
    }

    int y() {
      return c.currentY();
    }

    static int floorOf(int y) {
      return y / FLOOR;
    }

    int floor() {
      return floorOf(y());
    }

    int dist(int tx, int ty) {
      if (floorOf(ty) != floor()) return 9999;
      return Math.max(Math.abs(tx - x()), Math.abs(ty - y()));
    }

    boolean near(int tx, int ty, int r) {
      return dist(tx, ty) <= r;
    }

    int count(int itemId) {
      return c.getInventoryItemCount(itemId);
    }

    boolean has(int itemId) {
      return c.getInventoryItemCount(itemId) > 0;
    }

    boolean has(int itemId, int n) {
      return c.getInventoryItemCount(itemId) >= n;
    }

    int freeSlots() {
      return 30 - c.getInventoryItemCount();
    }

    int hpPercent() {
      int base = Math.max(1, c.getBaseStat(HITS));
      return c.getCurrentStat(HITS) * 100 / base;
    }

    // ----- eating / combat ------------------------------------------------------------------------

    boolean hasFood() {
      for (int f : FOOD) if (!reservedFood.contains(f) && has(f)) return true;
      return false;
    }

    /** Eats if HP is below the threshold. Returns true if something was eaten. */
    boolean eatIfNeeded() {
      if (hpPercent() >= eatPercent) return false;
      for (int f : FOOD) {
        if (reservedFood.contains(f) || !has(f)) continue;
        log("HP " + hpPercent() + "% - eating " + itemName(f));
        int before = count(f);
        c.itemCommand(f);
        waitFor(() -> count(f) < before, 3000);
        sleep(600);
        return true;
      }
      return false;
    }

    /** Leaves combat by walking a step (used when fighting is not wanted). */
    void leaveCombat() {
      if (!c.isInCombat()) return;
      log("in combat, walking away");
      for (int i = 0; i < 10 && c.isInCombat() && c.isRunning(); i++) {
        c.walkTo(x(), y(), 0, true, true);
        sleep(640);
      }
    }

    /**
     * Fights the nearest npc of the given id(s) until done() is true or the time runs out. Eats
     * while fighting; if out of food and HP gets critical it runs and throws a ManualStep.
     */
    void fightUntil(int[] npcIds, BooleanSupplier done, long ms, int[] pickupIds) {
      long until = System.currentTimeMillis() + ms;
      while (ok() && System.currentTimeMillis() < until) {
        if (done.getAsBoolean()) return;
        eatIfNeeded();
        if (hpPercent() < Math.min(25, eatPercent) && !hasFood()) {
          leaveCombat();
          throw manual("HP is low (" + hpPercent() + "%) and there is no food left. Bring food and restart.");
        }
        if (pickupIds != null) pickupAny(pickupIds, 6);
        if (done.getAsBoolean()) return;
        if (!c.isInCombat()) {
          ORSCharacter n = c.getNearestNpcByIds(npcIds, false);
          if (n != null) {
            c.attackNpc(n.serverIndex);
            sleep(1200);
          } else sleep(640);
        } else sleep(640);
      }
    }

    void pickupAny(int[] ids, int maxDist) {
      for (int id : ids) {
        int[] at = c.getNearestItemById(id, maxDist);
        if (at != null && freeSlots() > 0) {
          int before = count(id);
          c.pickupItem(at[0], at[1], id, true, true);
          waitFor(() -> count(id) > before, 3000);
        }
      }
    }

    // ----- walking --------------------------------------------------------------------------------

    /**
     * Walks to (x,y) within radius. Long distances use the client's webwalker (gates, doors,
     * ladders it knows); short distances on the same floor use walkTo. Bounded by a timeout.
     */
    boolean walk(int tx, int ty, int radius, long timeoutMs) {
      if (near(tx, ty, radius)) return true;
      log("walking to " + tx + "," + ty + " (from " + x() + "," + y() + ")");
      long until = System.currentTimeMillis() + timeoutMs;
      int fails = 0;
      int shortFails = 0;
      int lastX = x(), lastY = y();
      long lastMove = System.currentTimeMillis();
      while (ok() && System.currentTimeMillis() < until) {
        if (near(tx, ty, radius)) return true;
        while (!c.isLoggedIn() && c.isRunning()) sleep(1000);
        if (c.isInCombat()) {
          eatIfNeeded();
          c.walkTo(x(), y(), 0, true, true);
          sleep(640);
          continue;
        }
        int d = dist(tx, ty);
        boolean stepped;
        if (d <= 18 && shortFails < 2) {
          int bx = x(), by = y();
          c.walkTo(tx, ty, Math.min(radius, 1), false, true);
          if (x() == bx && y() == by && !near(tx, ty, radius)) shortFails++;
          stepped = true;
        } else {
          stepped = c.walkTowards(tx, ty);
          if (!stepped && d <= 18) {
            shortFails = 0; // let walkTo try again
          }
        }
        if (!stepped) {
          fails++;
          if (fails >= 6) {
            warn("webwalker can't find a path to " + tx + "," + ty + " from " + x() + "," + y());
            return false;
          }
          sleep(1000);
        } else fails = 0;
        if (x() != lastX || y() != lastY) {
          lastX = x();
          lastY = y();
          lastMove = System.currentTimeMillis();
        } else if (System.currentTimeMillis() - lastMove > 25000) {
          warn("stuck at " + x() + "," + y() + " walking to " + tx + "," + ty);
          return false;
        }
        sleep(200);
      }
      return near(tx, ty, radius);
    }

    boolean walk(int tx, int ty, int radius) {
      return walk(tx, ty, radius, 6 * 60_000L);
    }

    /** Walks or throws StepFailed. */
    void go(int tx, int ty, int radius) {
      if (!walk(tx, ty, radius)) throw new StepFailed("couldn't walk to " + tx + "," + ty);
    }

    void go(int tx, int ty) {
      go(tx, ty, 2);
    }

    void openDoor(int dx, int dy) {
      try {
        if (!c.isDoorOpen(dx, dy)) {
          c.openDoor(dx, dy);
          sleep(900);
        }
      } catch (RuntimeException ex) {
        log("couldn't open door at " + dx + "," + dy + ": " + ex.getMessage());
      }
    }

    /** Uses an object that changes floors (ladder, stairs) and waits for the floor change. */
    boolean climb(int ox, int oy, boolean second) {
      int startFloor = floor();
      int startY = y();
      for (int i = 0; i < 5 && ok(); i++) {
        if (floor() != startFloor || Math.abs(y() - startY) > 100) return true;
        if (second) c.atObject2(ox, oy);
        else c.atObject(ox, oy);
        if (waitFor(() -> floor() != startFloor || Math.abs(y() - startY) > 100, 6000)) {
          sleep(640);
          return true;
        }
      }
      return floor() != startFloor;
    }

    void climbOrFail(int ox, int oy) {
      if (!climb(ox, oy, false)) throw new StepFailed("couldn't climb at " + ox + "," + oy);
    }

    // ----- npcs & dialogue -----------------------------------------------------------------------

    ORSCharacter npc(int id) {
      return c.getNearestNpcById(id, true);
    }

    ORSCharacter findNpc(int id, long ms) {
      long until = System.currentTimeMillis() + ms;
      while (ok()) {
        ORSCharacter n = c.getNearestNpcById(id, true);
        if (n != null) return n;
        if (System.currentTimeMillis() > until) return null;
        sleep(500);
      }
      return null;
    }

    void hideMenu() {
      try {
        c.getMud().setOptionsMenuShow(false);
      } catch (Throwable ignored) {
      }
    }

    static String norm(String s) {
      if (s == null) return "";
      return s.replaceAll("@[a-zA-Z0-9]{3}@", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    String[] menu() {
      String[] all = c.getOptionsMenuText();
      int n = c.getOptionMenuCount();
      if (all == null || n <= 0) return new String[0];
      return Arrays.copyOf(all, Math.min(n, all.length));
    }

    /** Index of the first wanted choice present in the open menu (exact, then contains). */
    int matchMenu(String[] choices) {
      String[] opts = menu();
      for (String ch : choices) {
        String w = norm(ch);
        if (w.isEmpty()) continue;
        for (int i = 0; i < opts.length; i++) if (norm(opts[i]).equals(w)) return i;
      }
      for (String ch : choices) {
        String w = norm(ch);
        if (w.length() < 8) continue;
        for (int i = 0; i < opts.length; i++) {
          String h = norm(opts[i]);
          if (!h.isEmpty() && (h.contains(w) || (h.length() >= 10 && w.contains(h)))) return i;
        }
      }
      return -1;
    }

    /**
     * Talks to the nearest npc with the id and answers every option menu with the first matching
     * choice (choices can be given in any order and are reused). Returns once the conversation has
     * gone quiet. Retries the conversation a few times if it never starts.
     */
    boolean talk(int npcId, String... choices) {
      return dialog(npcId, true, 3, choices);
    }

    /** Answers a dialogue that pops up by itself (after using an item/object). */
    boolean popup(int npcId, String... choices) {
      return dialog(npcId, false, 1, choices);
    }

    boolean dialog(int npcId, boolean startTalk, int attempts, String... choices) {
      if (choices == null) choices = new String[0];
      for (int attempt = 1; attempt <= attempts && ok(); attempt++) {
        if (startTalk) {
          ORSCharacter n = findNpc(npcId, 20000);
          if (n == null) {
            warn("can't find npc " + npcName(npcId) + " near " + x() + "," + y());
            return false;
          }
          int[] at = c.getNpcCoordsByServerIndex(n.serverIndex);
          if (at != null && Math.max(Math.abs(at[0] - x()), Math.abs(at[1] - y())) > 10)
            walk(at[0], at[1], 2, 60000);
          hideMenu();
          log("talking to " + npcName(npcId) + (attempt > 1 ? " (attempt " + attempt + ")" : ""));
          c.talkToNpc(n.serverIndex);
          sleep(800);
        }
        long start = System.currentTimeMillis();
        long last = start;
        boolean saw = false;
        ORSCharacter n0 = c.getNearestNpcById(npcId, true);
        String lastNpcMsg = n0 != null ? n0.message : null;
        ORSCharacter me = c.getPlayer();
        String lastMyMsg = me != null ? me.message : null;
        long lastSrv = lastServerMsgAt;
        String loggedMenu = null;
        while (ok()) {
          long now = System.currentTimeMillis();
          if (c.isInOptionMenu()) {
            int idx = matchMenu(choices);
            if (idx >= 0) {
              String[] opts = menu();
              log("  > " + (idx < opts.length ? opts[idx] : ("option " + idx)));
              c.optionAnswer(idx);
              hideMenu();
              saw = true;
              last = System.currentTimeMillis();
              sleep(1200);
              continue;
            }
            String m = String.join(" | ", menu());
            if (!m.equals(loggedMenu)) {
              log("  no matching option in menu [" + m + "]");
              loggedMenu = m;
            }
          }
          ORSCharacter n = c.getNearestNpcById(npcId, true);
          String nm = n != null ? n.message : null;
          me = c.getPlayer();
          String mm = me != null ? me.message : null;
          if ((nm != null && nm != lastNpcMsg) || (mm != null && mm != lastMyMsg) || lastServerMsgAt != lastSrv) {
            last = now;
            saw = true;
          }
          lastNpcMsg = nm;
          lastMyMsg = mm;
          lastSrv = lastServerMsgAt;
          if (c.isCurrentlyWalking()) last = now;
          long idle = now - last;
          if (saw && idle > 3500 && !c.isInOptionMenu()) return true;
          if (idle > 9000 || now - start > 120000) break;
          sleep(300);
        }
        if (c.isInOptionMenu()) hideMenu();
        if (saw) return true;
        if (!startTalk) break;
      }
      return false;
    }

    /** Answers an option menu that is already open (or opens within ms). */
    boolean answerIfMenu(long ms, String... choices) {
      if (!waitFor(() -> c.isInOptionMenu(), ms)) return false;
      int idx = matchMenu(choices);
      if (idx < 0) return false;
      c.optionAnswer(idx);
      hideMenu();
      sleep(1000);
      return true;
    }

    void useOnNpc(int npcId, int itemId) {
      ORSCharacter n = findNpc(npcId, 15000);
      if (n == null) throw new StepFailed("can't find " + npcName(npcId) + " to use " + itemName(itemId) + " on");
      log("using " + itemName(itemId) + " on " + npcName(npcId));
      c.useItemOnNpc(n.serverIndex, itemId);
      sleep(1500);
    }

    void useOnObject(int ox, int oy, int itemId) {
      log("using " + itemName(itemId) + " on object at " + ox + "," + oy);
      c.useItemIdOnObject(ox, oy, itemId);
      sleep(1500);
    }

    void useItemOnItem(int a, int b) {
      int sa = c.getInventoryItemSlotIndex(a), sb = c.getInventoryItemSlotIndex(b);
      if (sa < 0 || sb < 0) return;
      log("using " + itemName(a) + " on " + itemName(b));
      c.useItemOnItemBySlot(sa, sb);
      sleep(1500);
    }

    // ----- items ------------------------------------------------------------------------------------

    /** Picks up ground items of an id near (x,y) until count reaches n. Waits for respawns. */
    boolean gather(int itemId, int n, int gx, int gy, int range, long ms) {
      if (count(itemId) >= n) return true;
      log("gathering " + itemName(itemId) + " x" + (n - count(itemId)) + " near " + gx + "," + gy);
      if (!walk(gx, gy, 3)) return false;
      long until = System.currentTimeMillis() + ms;
      while (ok() && System.currentTimeMillis() < until && count(itemId) < n) {
        if (freeSlots() <= 0 && !c.isItemStackable(itemId)) {
          warn("inventory full while gathering " + itemName(itemId));
          return false;
        }
        if (c.isInCombat()) leaveCombat();
        int[] at = c.getNearestItemById(itemId, range);
        if (at != null) {
          int before = count(itemId);
          c.pickupItem(at[0], at[1], itemId, true, true);
          waitFor(() -> count(itemId) > before, 5000);
        } else {
          if (!near(gx, gy, 4)) walk(gx, gy, 2, 60000);
          sleep(1000);
        }
      }
      return count(itemId) >= n;
    }

    void needGather(int itemId, int n, int gx, int gy, int range, long ms) {
      if (!gather(itemId, n, gx, gy, range, ms))
        throw new StepFailed("couldn't gather " + itemName(itemId));
    }

    /** Opens the shop of the nearest of the npc ids (bounded wait). */
    boolean openShop(int... npcIds) {
      if (c.isInShop()) return true;
      for (int i = 0; i < 4 && ok(); i++) {
        ORSCharacter n = c.getNearestNpcByIds(npcIds, false);
        if (n == null) {
          sleep(1000);
          continue;
        }
        c.npcCommand1(n.serverIndex);
        if (waitFor(() -> c.isInShop(), 8000)) return true;
      }
      return c.isInShop();
    }

    /** Buys up to n of an item from a shop (opens and closes it). */
    boolean buy(int[] npcIds, int itemId, int n) {
      if (count(itemId) >= n) return true;
      log("buying " + itemName(itemId) + " x" + (n - count(itemId)));
      if (!openShop(npcIds)) {
        warn("couldn't open the shop");
        return false;
      }
      long until = System.currentTimeMillis() + 60000;
      while (ok() && count(itemId) < n && System.currentTimeMillis() < until) {
        if (c.getShopItemCount(itemId) < 1) {
          sleep(1000);
          continue;
        }
        int price = Math.max(1, c.getShopItemPrice(itemId));
        if (count(ItemId.COINS.getId()) < price) {
          warn("not enough coins for " + itemName(itemId));
          break;
        }
        int before = count(itemId);
        c.shopBuy(itemId, n - before);
        waitFor(() -> count(itemId) > before, 3000);
      }
      c.closeShop();
      sleep(640);
      return count(itemId) >= n;
    }

    /** Opens a bank via the nearest banker (bounded; no endless loops). */
    boolean openBank() {
      for (int attempt = 0; attempt < 5 && ok() && !c.isInBank(); attempt++) {
        ORSCharacter b = c.getNearestNpcByIds(c.bankerIds, false);
        if (b == null) {
          sleep(1000);
          continue;
        }
        String cmd = "";
        try {
          cmd = c.getNpcCommand1(b.npcId);
        } catch (RuntimeException ignored) {
        }
        if ("Bank".equalsIgnoreCase(cmd)) c.npcCommand1(b.serverIndex);
        else {
          c.talkToNpc(b.serverIndex);
          if (waitFor(() -> c.isInOptionMenu(), 8000)) {
            int idx = matchMenu(new String[] {"I'd like to access my bank account please", "access my bank"});
            c.optionAnswer(Math.max(0, idx));
          }
        }
        waitFor(() -> c.isInBank(), 10000);
      }
      return c.isInBank();
    }

    /** Withdraws items from the bank at (bx,by) if they are banked. */
    void withdrawFromBank(int bx, int by, int[][] items) {
      boolean need = false;
      for (int[] it : items) if (count(it[0]) < it[1]) need = true;
      if (!need) return;
      if (!walk(bx, by, 4)) return;
      if (!openBank()) {
        warn("couldn't open the bank");
        return;
      }
      for (int[] it : items) {
        int want = it[1] - count(it[0]);
        if (want <= 0) continue;
        int banked = c.getBankItemCount(it[0]);
        if (banked <= 0) continue;
        int amt = Math.min(want, banked);
        if (!c.isItemStackable(it[0])) amt = Math.min(amt, freeSlots());
        if (amt <= 0) continue;
        log("withdrawing " + itemName(it[0]) + " x" + amt);
        c.withdrawItem(it[0], amt);
        sleep(1200);
      }
      c.closeBank();
      sleep(640);
    }

    /** Throws a ManualStep listing the missing items, if any are missing. */
    void requireItems(String why, int[][] items) {
      List<String> miss = new ArrayList<>();
      for (int[] it : items)
        if (count(it[0]) < it[1]) miss.add(itemName(it[0]) + " x" + (it[1] - count(it[0])));
      if (!miss.isEmpty()) throw manual(why + " - missing: " + String.join(", ", miss));
    }

    // ----- names ------------------------------------------------------------------------------------

    static String itemName(int id) {
      ItemId i = ItemId.getById(id);
      return i == null ? ("item " + id) : i.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    String npcName(int id) {
      try {
        String n = c.getNpcName(id);
        if (n != null && !n.isEmpty()) return n;
      } catch (Throwable ignored) {
      }
      return "npc " + id;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Quest base
  // ---------------------------------------------------------------------------------------------

  abstract static class Quest {
    final int id;
    final String key;
    final String name;
    final String automation;
    int order;
    int maxAttempts = 4;
    long stepTimeoutMs = 20 * 60_000L;

    Quest(QuestId q, String key, String name, String automation) {
      this.id = q.getId();
      this.key = key;
      this.name = name;
      this.automation = automation;
    }

    /** Null if requirements are met, otherwise a description. */
    String missingRequirements(Engine e) {
      return null;
    }

    /** Collects items ahead of time (only when "gather items first" is on). Idempotent. */
    void gather(Engine e, int stage) {}

    /** Does the work for one quest stage. Return normally to let the runner check the stage. */
    abstract void step(Engine e, int stage);

    @Override
    public String toString() {
      return name;
    }
  }

  static int id(ItemId i) {
    return i.getId();
  }

  static int id(NpcId n) {
    return n.getId();
  }

  // ---------------------------------------------------------------------------------------------
  // Shared helpers used by several quests
  // ---------------------------------------------------------------------------------------------

  static final int COINS = ItemId.COINS.getId();
  static final int[] LUMBRIDGE_STORE = {id(NpcId.SHOPKEEPER_LUMBRIDGE), id(NpcId.SHOP_ASSISTANT_LUMBRIDGE)};
  static final int[] VARROCK_STORE = {id(NpcId.SHOPKEEPER_VARROCK), id(NpcId.SHOP_ASSISTANT_VARROCK)};
  static final int[] FALADOR_STORE = {id(NpcId.SHOPKEEPER_FALADOR), id(NpcId.SHOP_ASSISTANT_FALADOR)};
  static final int[] LUMBRIDGE_STORE_TILE = {133, 641};
  static final int[] DRAYNOR_BANK = {220, 635};
  static final int[] VARROCK_EAST_BANK = {102, 512};
  static final int[] VARROCK_WEST_BANK = {150, 502};
  static final int[] FALADOR_WEST_BANK = {328, 552};

  /** Buys an item at the Lumbridge general store. */
  static boolean buyLumbridge(Engine e, int itemId, int n) {
    if (e.count(itemId) >= n) return true;
    if (!e.walk(LUMBRIDGE_STORE_TILE[0], LUMBRIDGE_STORE_TILE[1], 3)) return false;
    return e.buy(LUMBRIDGE_STORE, itemId, n);
  }

  /** Picks onions in the field north of Lumbridge (ground spawns). */
  static void getOnions(Engine e, int n) {
    if (e.count(ItemId.ONION.getId()) >= n) return;
    e.needGather(ItemId.ONION.getId(), n, 160, 621, 8, 4 * 60_000L);
  }

  /** Picks redberries at the bushes south-west of Varrock (ground spawns). */
  static void getRedberries(Engine e, int n) {
    int id = ItemId.REDBERRIES.getId();
    if (e.count(id) >= n) return;
    if (!e.gather(id, n, 99, 537, 8, 3 * 60_000L)) e.needGather(id, n, 84, 537, 8, 3 * 60_000L);
  }

  /** Lumbridge castle: ground floor ladder and the spinning wheel on the top floor. */
  static void spinAllWool(Engine e) {
    int wool = ItemId.WOOL.getId();
    if (!e.has(wool)) return;
    if (e.floor() != 2) {
      if (e.floor() == 0) {
        e.go(139, 667, 1);
        e.climbOrFail(139, 666);
      }
      if (e.floor() == 1) {
        e.go(138, 1613, 2);
        e.climbOrFail(138, 1612);
      }
    }
    e.go(138, 2554, 1);
    long until = System.currentTimeMillis() + 4 * 60_000L;
    while (e.ok() && e.has(wool) && System.currentTimeMillis() < until) {
      int before = e.count(wool);
      e.useOnObject(139, 2554, wool);
      e.waitFor(() -> e.count(wool) < before, 6000);
    }
    // back down to the ground floor
    if (e.floor() == 2) {
      e.go(138, 2555, 1);
      e.climb(138, 2556, false);
    }
    if (e.floor() == 1) {
      e.go(139, 1611, 1);
      e.climb(139, 1610, false);
    }
  }

  /** Shears sheep in the pen by Fred's farm until we carry `n` wool + balls of wool. */
  static void shearWool(Engine e, int n) {
    int wool = ItemId.WOOL.getId(), ball = ItemId.BALL_OF_WOOL.getId(), shears = ItemId.SHEARS.getId();
    if (e.count(wool) + e.count(ball) >= n) return;
    if (!e.has(shears) && !buyLumbridge(e, shears, 1)) throw e.manual("Need shears (1gp at the Lumbridge general store)");
    int needed = n - e.count(wool) - e.count(ball);
    if (e.freeSlots() < needed) throw e.manual("Free up " + needed + " inventory slots for wool");
    e.go(144, 630, 4);
    long until = System.currentTimeMillis() + 8 * 60_000L;
    while (e.ok() && e.count(wool) + e.count(ball) < n && System.currentTimeMillis() < until) {
      ORSCharacter s = e.c.getNearestNpcById(id(NpcId.SHEEP), false);
      if (s == null) {
        e.walk(144, 630, 3, 30000);
        e.sleep(1000);
        continue;
      }
      int before = e.count(wool);
      e.c.useItemOnNpc(s.serverIndex, shears);
      e.waitFor(() -> e.count(wool) > before, 5000);
    }
    if (e.count(wool) + e.count(ball) < n) throw new StepFailed("couldn't shear enough wool");
  }

  // ---------------------------------------------------------------------------------------------
  // 1. Cook's Assistant - fully automated
  // ---------------------------------------------------------------------------------------------

  static final class CooksAssistant extends Quest {
    static final int COOK = id(NpcId.COOK);
    static final int COW = id(NpcId.COW_ATTACKABLE);
    static final int POT = id(ItemId.POT), POT_OF_FLOUR = id(ItemId.POT_OF_FLOUR), FLOUR = id(ItemId.FLOUR);
    static final int BUCKET = id(ItemId.BUCKET), MILK = id(ItemId.MILK), EGG = id(ItemId.EGG), GRAIN = id(ItemId.GRAIN);
    static final int WHEAT = 72;

    CooksAssistant() {
      super(QuestId.COOKS_ASSISTANT, "cook", "Cook's Assistant", "fully automated");
    }

    @Override
    void gather(Engine e, int stage) {
      if (!e.has(POT) && !e.has(POT_OF_FLOUR)) {
        if (!e.gather(POT, 1, 134, 660, 3, 30000)) buyLumbridge(e, POT, 1);
      }
      if (!e.has(BUCKET) && !e.has(MILK)) {
        if (!e.gather(BUCKET, 1, 121, 607, 4, 30000)) buyLumbridge(e, BUCKET, 1);
      }
      if (!e.has(EGG)) e.gather(EGG, 1, 120, 603, 8, 3 * 60_000L);
      if (!e.has(MILK) && e.has(BUCKET)) milkCow(e);
      if (!e.has(POT_OF_FLOUR) && e.has(POT)) makeFlour(e);
    }

    void milkCow(Engine e) {
      e.go(103, 614, 5);
      for (int i = 0; i < 8 && e.ok() && !e.has(MILK); i++) {
        ORSCharacter cow = e.c.getNearestNpcById(COW, false);
        if (cow == null) {
          e.sleep(1500);
          continue;
        }
        e.log("milking a cow");
        e.c.useItemOnNpc(cow.serverIndex, BUCKET);
        e.waitFor(() -> e.has(MILK), 6000);
      }
    }

    void makeFlour(Engine e) {
      if (!e.has(GRAIN) && e.floor() == 0) {
        e.go(173, 602, 3);
        for (int i = 0; i < 10 && e.ok() && !e.has(GRAIN); i++) {
          int[] w = e.c.getNearestObjectById(WHEAT);
          if (w == null) {
            e.sleep(1000);
            continue;
          }
          e.log("picking wheat at " + w[0] + "," + w[1]);
          e.c.atObject2(w[0], w[1]);
          e.waitFor(() -> e.has(GRAIN), 6000);
        }
        if (!e.has(GRAIN)) throw new StepFailed("couldn't pick grain");
      }
      if (e.has(GRAIN)) {
        if (e.floor() == 0) {
          e.go(166, 600, 1);
          e.climbOrFail(165, 598);
        }
        if (e.floor() == 1) {
          e.go(166, 1545, 1);
          e.climbOrFail(166, 1546);
        }
        e.go(166, 2488, 1);
        e.useOnObject(166, 2487, GRAIN);
        e.waitFor(() -> !e.has(GRAIN), 4000);
        e.log("operating the hopper");
        e.c.atObject(166, 2487);
        e.sleep(3000);
      }
      if (e.floor() == 2) {
        e.go(166, 2489, 1);
        e.climb(166, 2490, false);
      }
      if (e.floor() == 1) {
        e.go(165, 1543, 1);
        e.climb(165, 1542, false);
      }
      e.go(166, 598, 1);
      for (int i = 0; i < 6 && e.ok() && !e.has(POT_OF_FLOUR); i++) {
        int[] f = e.c.getNearestItemById(FLOUR, 6);
        if (f == null) {
          e.sleep(1500);
          continue;
        }
        e.log("collecting flour from the chute");
        e.c.pickupItem(f[0], f[1], FLOUR, false, true);
        e.waitFor(() -> e.has(POT_OF_FLOUR), 4000);
      }
    }

    @Override
    void step(Engine e, int stage) {
      if (stage == 0) {
        e.go(134, 661, 3);
        e.talk(COOK, "What's wrong?", "Yes, I'll help you");
        return;
      }
      gather(e, stage);
      e.requireItems("The cook needs his ingredients", new int[][] {{EGG, 1}, {MILK, 1}, {POT_OF_FLOUR, 1}});
      e.go(134, 661, 3);
      e.talk(COOK);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // 2. Sheep Shearer - fully automated
  // ---------------------------------------------------------------------------------------------

  static final class SheepShearer extends Quest {
    static final int FRED = id(NpcId.FRED_THE_FARMER);
    static final int BALL = id(ItemId.BALL_OF_WOOL);

    SheepShearer() {
      super(QuestId.SHEEP_SHEARER, "sheep", "Sheep Shearer", "fully automated");
    }

    @Override
    void gather(Engine e, int stage) {
      if (e.count(BALL) >= 20) return;
      shearWool(e, 20);
      spinAllWool(e);
    }

    @Override
    void step(Engine e, int stage) {
      if (stage == 0) {
        e.go(159, 618, 3);
        e.talk(FRED, "I'm looking for a quest", "Yes okay. I can do that");
        return;
      }
      gather(e, stage);
      e.requireItems("Fred wants 20 balls of wool", new int[][] {{BALL, 20}});
      e.go(159, 618, 3);
      e.talk(FRED, "I need to talk to you about shearing these sheep");
    }
  }

  // ---------------------------------------------------------------------------------------------
  // 3. The Restless Ghost - fully automated (the skull step relies on the webwalker reaching the
  //    Wizards' Tower basement)
  // ---------------------------------------------------------------------------------------------

  static final class RestlessGhost extends Quest {
    static final int PRIEST = id(NpcId.PRIEST), URHNEY = id(NpcId.URHNEY), GHOST = id(NpcId.GHOST_RESTLESS);
    static final int AMULET = id(ItemId.AMULET_OF_GHOSTSPEAK), SKULL = id(ItemId.QUEST_SKULL);
    static final int[] COFFIN = {103, 675};

    RestlessGhost() {
      super(QuestId.THE_RESTLESS_GHOST, "ghost", "The Restless Ghost", "fully automated");
    }

    void openCoffin(Engine e) {
      for (int i = 0; i < 5 && e.ok(); i++) {
        if (e.c.getObjectAtCoord(COFFIN[0], COFFIN[1]) == 40) return;
        e.log("opening the coffin");
        e.c.atObject(COFFIN[0], COFFIN[1]);
        e.waitFor(() -> e.c.getObjectAtCoord(COFFIN[0], COFFIN[1]) == 40, 4000);
      }
    }

    void toGraveyard(Engine e) {
      e.go(107, 675, 2);
      e.openDoor(106, 675);
      e.go(104, 675, 1);
    }

    @Override
    void step(Engine e, int stage) {
      switch (stage) {
        case 0:
          e.go(112, 664, 3);
          e.openDoor(115, 664);
          e.talk(PRIEST, "I'm looking for a quest", "Ok, let me help then");
          break;
        case 1:
          if (e.freeSlots() < 1) throw e.manual("Free one inventory slot for the amulet of ghostspeak");
          e.go(116, 710, 3);
          e.openDoor(116, 709);
          e.talk(URHNEY, "Father Aereck sent me to talk to you", "He's got a ghost haunting his graveyard");
          break;
        case 2:
          if (!e.c.isItemIdEquipped(AMULET) && !e.has(AMULET)) {
            e.go(116, 710, 3);
            e.openDoor(116, 709);
            e.talk(URHNEY, "I've lost the amulet");
            e.waitFor(() -> e.has(AMULET), 8000);
          }
          if (!e.c.isItemIdEquipped(AMULET) && e.has(AMULET)) {
            e.log("wearing the amulet of ghostspeak");
            e.c.equipItemById(AMULET);
            e.waitFor(() -> e.c.isItemIdEquipped(AMULET), 5000);
          }
          toGraveyard(e);
          openCoffin(e);
          if (e.findNpc(GHOST, 8000) == null) throw new StepFailed("the ghost didn't appear");
          e.talk(GHOST, "Yep, now tell me what the problem is");
          break;
        case 3:
          if (!e.has(SKULL)) {
            e.log("fetching the skull from the Wizards' Tower basement (a skeleton will chase)");
            if (!e.walk(220, 3519, 3, 8 * 60_000L))
              throw e.manual("Couldn't path to the Wizards' Tower basement; pick up the skull at 218,3521 by hand");
            for (int i = 0; i < 10 && e.ok() && !e.has(SKULL); i++) {
              e.c.pickupItem(218, 3521, SKULL, true, true);
              e.waitFor(() -> e.has(SKULL), 4000);
            }
            if (!e.has(SKULL)) throw new StepFailed("couldn't pick up the skull");
            long until = System.currentTimeMillis() + 60000;
            while (e.ok() && e.floor() == 3 && System.currentTimeMillis() < until) {
              if (e.c.isInCombat()) {
                e.c.walkTo(e.x(), e.y(), 0, true, true);
                e.sleep(640);
                continue;
              }
              e.c.atObject(218, 3526);
              e.sleep(1500);
            }
          }
          if (!e.c.isItemIdEquipped(AMULET) && e.has(AMULET)) e.c.equipItemById(AMULET);
          toGraveyard(e);
          for (int i = 0; i < 6 && e.ok() && e.has(SKULL); i++) {
            openCoffin(e);
            e.useOnObject(COFFIN[0], COFFIN[1], SKULL);
            e.waitFor(() -> !e.has(SKULL), 6000);
          }
          break;
        default:
          throw new StepFailed("unknown stage " + stage);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // 4. Witch's Potion - fully automated (rat fight is trivial)
  // ---------------------------------------------------------------------------------------------

  static final class WitchsPotion extends Quest {
    static final int HETTY = id(NpcId.HETTY), BETTY = id(NpcId.BETTY), RAT = id(NpcId.RAT_WITCHES_POTION);
    static final int COOKED = id(ItemId.COOKEDMEAT), BURNT = id(ItemId.BURNTMEAT), NEWT = id(ItemId.EYE_OF_NEWT);
    static final int ONION = id(ItemId.ONION), TAIL = id(ItemId.RATS_TAIL);
    static final int[][] DOORS = {{319, 667}, {324, 667}, {329, 661}, {320, 662}};

    WitchsPotion() {
      super(QuestId.WITCHS_POTION, "witch", "Witch's Potion", "fully automated");
    }

    void doors(Engine e) {
      for (int[] d : DOORS) if (e.near(d[0], d[1], 8)) e.openDoor(d[0], d[1]);
    }

    @Override
    void gather(Engine e, int stage) {
      e.reservedFood.add(COOKED);
      if (!e.has(BURNT)) {
        if (!e.has(COOKED)) {
          e.go(234, 501, 2);
          e.needGather(COOKED, 1, 234, 501, 6, 3 * 60_000L);
        }
        e.go(235, 495, 1);
        for (int i = 0; i < 6 && e.ok() && !e.has(BURNT) && e.has(COOKED); i++) {
          int before = e.count(COOKED);
          e.useOnObject(234, 495, COOKED);
          e.waitFor(() -> e.count(COOKED) < before, 5000);
        }
        // cooking cooked meat again burns it; retry with another piece if we still have none
        if (!e.has(BURNT)) throw new StepFailed("no burnt meat yet");
      }
      getOnions(e, 1);
      if (!e.has(NEWT)) {
        if (e.count(COINS) < 3) throw e.manual("Need 3 coins for an eye of newt");
        e.go(272, 632, 3);
        if (!e.buy(new int[] {BETTY}, NEWT, 1)) throw new StepFailed("couldn't buy an eye of newt");
      }
      if (!e.has(TAIL)) {
        e.go(323, 664, 4);
        doors(e);
        e.fightUntil(new int[] {RAT}, () -> e.has(TAIL), 5 * 60_000L, new int[] {TAIL});
        if (!e.has(TAIL)) throw new StepFailed("no rat's tail yet");
      }
    }

    @Override
    void step(Engine e, int stage) {
      e.reservedFood.add(COOKED);
      switch (stage) {
        case 0:
          e.go(316, 667, 3);
          doors(e);
          e.talk(HETTY, "Witch's Potion", "I am in search of a quest", "Yes help me become one with my darker side");
          break;
        case 1:
          gather(e, stage);
          e.go(316, 667, 3);
          doors(e);
          e.talk(HETTY);
          break;
        case 2:
          e.go(317, 666, 1);
          doors(e);
          e.log("drinking from the cauldron");
          e.c.atObject2(316, 666);
          e.sleep(3000);
          break;
        default:
          throw new StepFailed("unknown stage " + stage);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // 5. Prince Ali Rescue - partly automated (soft clay, bronze bar and ashes must be brought)
  // ---------------------------------------------------------------------------------------------

  static final class PrinceAliRescue extends Quest {
    static final int HASSAN = id(NpcId.HASSAN), OSMAN = id(NpcId.OSMAN), NED = id(NpcId.NED), AGGIE = id(NpcId.AGGIE);
    static final int LEELA = id(NpcId.LEELA), JOE = id(NpcId.JOE), KELI = id(NpcId.LADY_KELI), ALI = id(NpcId.PRINCE_ALI);
    static final int THESSALIA = id(NpcId.THESSALIA), BARTENDER = id(NpcId.BARTENDER_VARROCK);
    static final int BALL = id(ItemId.BALL_OF_WOOL), ROPE = id(ItemId.ROPE), WIG = id(ItemId.WOOL_WIG), BLONDE = id(ItemId.BLONDE_WIG);
    static final int YELLOW = id(ItemId.YELLOWDYE), PASTE = id(ItemId.PASTE), SKIRT = id(ItemId.PINK_SKIRT), BEER = id(ItemId.BEER);
    static final int KEY = id(ItemId.BRONZE_KEY), PRINT = id(ItemId.KEYPRINT), CLAY = id(ItemId.SOFT_CLAY), BAR = id(ItemId.BRONZE_BAR);
    static final int ASHES = id(ItemId.ASHES), FLOUR = id(ItemId.FLOUR), POT_FLOUR = id(ItemId.POT_OF_FLOUR);
    static final int WATER = id(ItemId.BUCKET_OF_WATER), BUCKET = id(ItemId.BUCKET), BERRIES = id(ItemId.REDBERRIES), ONION = id(ItemId.ONION);
    static final int[][] PALACE_DOORS = {{71, 694}, {67, 688}, {77, 688}};

    PrinceAliRescue() {
      super(QuestId.PRINCE_ALI_RESCUE, "ali", "Prince Ali Rescue",
          "partly automated: bring soft clay, a bronze bar and ashes");
      stepTimeoutMs = 40 * 60_000L;
    }

    void toPalace(Engine e) {
      if (e.count(COINS) < 10 && e.x() > 92) e.warn("the Al-Kharid toll gate costs 10 coins");
      e.go(72, 690, 3);
      for (int[] d : PALACE_DOORS) if (e.near(d[0], d[1], 8)) e.openDoor(d[0], d[1]);
    }

    boolean disguiseReady(Engine e) {
      return e.has(BLONDE) && e.has(PASTE) && e.has(SKIRT);
    }

    @Override
    void gather(Engine e, int stage) {
      if (stage != 2) return;
      // wool for the wig (3) and the rope (4)
      int ballsNeeded = (e.has(WIG) || e.has(BLONDE) ? 0 : 3) + (e.has(ROPE) ? 0 : 4);
      if (e.count(BALL) < ballsNeeded) {
        shearWool(e, ballsNeeded);
        spinAllWool(e);
      }
      if (!e.has(YELLOW) && !e.has(BLONDE)) getOnions(e, 2);
      if (!e.has(PASTE)) {
        getRedberries(e, 1);
        if (!e.has(POT_FLOUR) && !e.has(FLOUR)) {
          if (!e.has(id(ItemId.POT))) buyLumbridge(e, id(ItemId.POT), 1);
          if (e.has(id(ItemId.POT))) new CooksAssistant().makeFlour(e);
        }
        if (!e.has(WATER)) {
          if (!e.has(BUCKET)) buyLumbridge(e, BUCKET, 1);
          if (e.has(BUCKET)) {
            e.go(118, 649, 1);
            e.useOnObject(117, 650, BUCKET);
            e.waitFor(() -> e.has(WATER), 4000);
          }
        }
      }
      if (!e.has(SKIRT)) {
        e.go(137, 516, 3);
        e.buy(new int[] {THESSALIA}, SKIRT, 1);
      }
      if (e.count(BEER) < 3) {
        e.go(122, 523, 3);
        for (int i = 0; i < 6 && e.ok() && e.count(BEER) < 3; i++) {
          if (e.count(COINS) < 2) throw e.manual("Need coins for 3 beers (2gp each)");
          e.talk(BARTENDER, "A glass of your finest ale please");
        }
      }
    }

    void nedAndAggie(Engine e) {
      if ((!e.has(ROPE) && e.count(BALL) >= 4) || (!e.has(WIG) && !e.has(BLONDE) && e.count(BALL) >= 3)) {
        e.go(215, 625, 3);
        if (!e.has(ROPE) && e.count(BALL) >= 4)
          e.talk(NED, "Yes, I would like some Rope", "I have some balls of wool. could you make me some Rope?");
        if (!e.has(WIG) && !e.has(BLONDE) && e.count(BALL) >= 3)
          e.talk(NED, "Ned, could you make other things from wool?", "How about some sort of a wig?",
              "I have that now. Please, make me a wig");
      }
      boolean wantDye = !e.has(YELLOW) && !e.has(BLONDE) && e.count(ONION) >= 2;
      boolean wantPaste = !e.has(PASTE) && e.has(ASHES) && e.has(BERRIES) && e.has(WATER)
          && (e.has(FLOUR) || e.has(POT_FLOUR));
      if (wantDye || wantPaste) {
        e.go(223, 625, 3);
        if (wantDye)
          e.talk(AGGIE, "Can you make dyes for me please", "What do you need to make some yellow dye please",
              "Okay, make me some yellow dye please");
        if (wantPaste)
          e.talk(AGGIE, "Could you think of a way to make pink skin paste", "Yes please, mix me some skin paste");
      }
      if (e.has(YELLOW) && e.has(WIG) && !e.has(BLONDE)) e.useItemOnItem(YELLOW, WIG);
    }

    void getKey(Engine e) {
      if (e.has(KEY)) return;
      // Leela hands over the key once Osman has made it
      e.go(215, 625, 6);
      if (e.findNpc(LEELA, 8000) != null) e.talk(LEELA, "I hoped to get him drunk");
      if (e.has(KEY)) return;
      if (!e.has(PRINT)) {
        if (!e.has(CLAY)) throw e.manual("Bring 1 soft clay (clay + bucket of water) for the key imprint");
        e.go(195, 639, 4);
        e.talk(KELI, "Heard of you? you are famous in Runescape!", "What is your latest plan then?",
            "Can you be sure they will not try to get him out?", "Could I see the key please",
            "Could I touch the key for a moment please");
        e.waitFor(() -> e.has(PRINT), 4000);
      }
      if (e.has(PRINT)) {
        if (!e.has(BAR)) throw e.manual("Bring 1 bronze bar for Osman to make the key");
        toPalace(e);
        e.go(74, 683, 3);
        e.talk(OSMAN, "Thank you, I will try to find the other items");
        e.go(215, 625, 6);
        e.talk(LEELA, "I hoped to get him drunk");
      }
    }

    @Override
    void step(Engine e, int stage) {
      switch (stage) {
        case 0:
          toPalace(e);
          e.talk(HASSAN, "Can I help you? You must need some help here in the desert.");
          break;
        case 1:
          toPalace(e);
          e.go(74, 683, 3);
          e.talk(OSMAN, "What is first thing I must do?", "What is needed second?", "Okay, I better go find some things");
          break;
        case 2:
          gather(e, stage);
          nedAndAggie(e);
          getKey(e);
          e.requireItems("Prince Ali jail break", new int[][] {
            {BLONDE, 1}, {PASTE, 1}, {SKIRT, 1}, {BEER, 3}, {ROPE, 1}, {KEY, 1}});
          e.go(197, 637, 4);
          e.talk(JOE, "I have some beer here, fancy one?");
          for (int i = 0; i < 4 && e.ok() && e.has(ROPE); i++) {
            ORSCharacter k = e.c.getNearestNpcById(KELI, false);
            if (k == null) break;
            e.c.useItemOnNpc(k.serverIndex, ROPE);
            e.sleep(2600);
          }
          e.walk(198, 640, 0, 60000);
          for (int i = 0; i < 4 && e.ok() && e.x() < 199; i++) {
            int slot = e.c.getInventoryItemSlotIndex(KEY);
            if (slot < 0) break;
            e.c.useItemOnWall(199, 640, slot);
            e.waitFor(() -> e.x() >= 199, 4000);
          }
          e.talk(ALI);
          break;
        case 3:
          for (int i = 0; i < 6 && e.ok() && e.x() >= 199 && e.near(200, 640, 6); i++) {
            e.walk(199, 640, 0, 20000);
            e.c.atWallObject(199, 640);
            e.sleep(1500);
          }
          toPalace(e);
          e.talk(HASSAN);
          break;
        default:
          throw new StepFailed("unknown stage " + stage);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // 6. Shield of Arrav - manual (needs two players: one per gang)
  // ---------------------------------------------------------------------------------------------

  static final class ShieldOfArrav extends Quest {
    ShieldOfArrav() {
      super(QuestId.SHIELD_OF_ARRAV, "arrav", "Shield of Arrav", "manual (needs two players)");
    }

    @Override
    void step(Engine e, int stage) {
      throw e.manual("Shield of Arrav needs two players (one in each gang) to exchange shield halves."
          + " Do it by hand with a partner: Reldo's book -> Baraek -> Phoenix/Black Arm gang -> King Roald.");
    }
  }

  // ---------------------------------------------------------------------------------------------
  // 7. Romeo & Juliet - fully automated
  // ---------------------------------------------------------------------------------------------

  static final class RomeoAndJuliet extends Quest {
    static final int ROMEO = id(NpcId.ROMEO), JULIET = id(NpcId.JULIET), LAWRENCE = id(NpcId.FATHER_LAWRENCE);
    static final int APOTHECARY = id(NpcId.APOTHECARY), BERRIES = id(ItemId.CADAVABERRIES);
    static final int[][] JULIET_DOORS = {{166, 495}, {170, 498}, {170, 496}, {168, 499}};
    static final int[][] CHURCH_DOORS = {{107, 474}, {107, 480}, {104, 478}, {103, 474}};

    RomeoAndJuliet() {
      super(QuestId.ROMEO_AND_JULIET, "romeo", "Romeo & Juliet", "fully automated");
    }

    @Override
    void gather(Engine e, int stage) {
      if (stage <= 5 && !e.has(BERRIES)) {
        if (e.freeSlots() < 2) throw e.manual("Free 2 inventory slots");
        e.needGather(BERRIES, 1, 73, 545, 10, 5 * 60_000L);
      }
    }

    void juliet(Engine e) {
      e.go(168, 496, 3);
      for (int[] d : JULIET_DOORS) e.openDoor(d[0], d[1]);
      if (e.findNpc(JULIET, 10000) == null)
        throw e.manual("Juliet isn't in sight at her house (168,496); talk to her by hand");
      e.talk(JULIET, "Certainly, I will do so straight away", "Certinly, I will do so straight away", "Yes I have met him");
    }

    void romeo(Engine e, String... ch) {
      e.go(134, 506, 6);
      e.talk(ROMEO, ch);
    }

    @Override
    void step(Engine e, int stage) {
      switch (stage) {
        case 0:
          romeo(e, "Can I help find her for you?", "Yes, I will tell her how you feel", "Yes, I will tell her");
          break;
        case 1:
        case 6:
          juliet(e);
          break;
        case 2:
        case 7:
          romeo(e);
          break;
        case 3:
          e.go(100, 475, 4);
          for (int[] d : CHURCH_DOORS) e.openDoor(d[0], d[1]);
          e.talk(LAWRENCE);
          break;
        case 4:
        case 5:
          gather(e, stage);
          e.go(143, 520, 2);
          e.openDoor(143, 522);
          e.talk(APOTHECARY);
          break;
        default:
          throw new StepFailed("unknown stage " + stage);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // 8. Demon Slayer - partly automated (Rovin's tower and the sewer depend on the webwalker; the
  //    Delrith fight needs food). The incantation in OpenRSC is always the same.
  // ---------------------------------------------------------------------------------------------

  static final class DemonSlayer extends Quest {
    static final int GYPSY = id(NpcId.GYPSY), PRYSIN = id(NpcId.SIR_PRYSIN), ROVIN = id(NpcId.CAPTAIN_ROVIN);
    static final int TRAIBORN = id(NpcId.TRAIBORN_THE_WIZARD), DELRITH = id(NpcId.DELRITH);
    static final int KEY1 = id(ItemId.SILVERLIGHT_KEY_1), KEY2 = id(ItemId.SILVERLIGHT_KEY_2), KEY3 = id(ItemId.SILVERLIGHT_KEY_3);
    static final int SILVERLIGHT = id(ItemId.SILVERLIGHT), BONES = id(ItemId.BONES);
    static final int BUCKET = id(ItemId.BUCKET), WATER = id(ItemId.BUCKET_OF_WATER);
    static final int DRAIN = 77;
    static final String INCANTATION = "Carlem Aber Camerinthum Purchai Gabindo";

    DemonSlayer() {
      super(QuestId.DEMON_SLAYER, "demon", "Demon Slayer",
          "partly automated: keys need webwalker reach; bring food for Delrith");
      stepTimeoutMs = 45 * 60_000L;
    }

    void prysin(Engine e) {
      e.go(137, 474, 3);
      e.talk(PRYSIN, "Gypsy Aris said I should come and talk to you", "I need to find Silverlight",
          "He's back and unfortunatly I've got to deal with him", "And why is this a problem?",
          "Well I'd better go key hunting", "Where can I find Captain Rovin?");
    }

    void traiborn(Engine e, int stage) {
      e.go(217, 688, 3);
      if (e.floor() == 0) {
        e.go(218, 693, 1);
        e.climbOrFail(218, 692);
      }
      if (e.findNpc(TRAIBORN, 10000) == null) throw e.manual("Couldn't find Wizard Traiborn on the tower's first floor");
      e.talk(TRAIBORN, "I need to get a key given to you by Sir Prysin", "Well, have you got any keys knocking around?",
          "I'll get the bones for you");
      if (e.floor() == 1) {
        e.go(216, 1637, 2);
        e.climb(218, 1636, false);
      }
    }

    void collectBones(Engine e) {
      int want = Math.min(25, e.count(BONES) + e.freeSlots() - 2);
      if (e.count(BONES) >= want) return;
      e.log("collecting bones from Lumbridge chickens (" + e.count(BONES) + "/" + want + ")");
      e.go(117, 609, 4);
      e.fightUntil(new int[] {id(NpcId.CHICKEN)}, () -> e.count(BONES) >= want || e.freeSlots() <= 2,
          15 * 60_000L, new int[] {BONES});
    }

    void rovinKey(Engine e) {
      if (e.has(KEY2)) return;
      e.log("looking for Captain Rovin (top of the palace guards' quarters)");
      if (!e.walk(140, 1398, 3, 5 * 60_000L))
        throw e.manual("Couldn't reach the Varrock palace guards' quarters; get Captain Rovin's key by hand"
            + " (\"Yes I know but this important\" -> \"There's a demon who wants to invade this city\")");
      if (e.findNpc(ROVIN, 4000) == null) {
        int[] ladder = e.c.getNearestObjectById(5);
        if (ladder != null) {
          e.walk(ladder[0], ladder[1] + 1, 1, 60000);
          e.climb(ladder[0], ladder[1], false);
        }
      }
      if (e.findNpc(ROVIN, 8000) == null)
        throw e.manual("Captain Rovin not found; get his key by hand"
            + " (\"Yes I know but this important\" -> \"There's a demon who wants to invade this city\")");
      e.talk(ROVIN, "Yes I know but this important", "There's a demon who wants to invade this city",
          "Well I am going to use the powerful sword silverlight");
    }

    void drainKey(Engine e) {
      if (e.has(KEY3)) return;
      if (!e.has(WATER)) {
        if (!e.has(BUCKET)) throw e.manual("Bring a bucket (or bucket of water) for the palace drain key");
        int[] f = e.c.getNearestObjectById(26);
        if (f == null) {
          e.walk(130, 493, 4, 3 * 60_000L);
          f = e.c.getNearestObjectById(26);
        }
        if (f != null) {
          e.walk(f[0], f[1] + 1, 1, 60000);
          e.useOnObject(f[0], f[1], BUCKET);
          e.waitFor(() -> e.has(WATER), 4000);
        }
        if (!e.has(WATER)) throw e.manual("Fill a bucket with water (fountain/sink) for the drain key");
      }
      e.go(119, 463, 4);
      int[] drain = e.c.getNearestObjectById(DRAIN);
      if (drain == null) throw e.manual("Couldn't see the drain outside the palace kitchen; use the bucket of water on it by hand");
      e.walk(drain[0], drain[1] + 1, 1, 60000);
      e.useOnObject(drain[0], drain[1], WATER);
      e.log("walking into the sewers for the key at 117,3294");
      if (!e.walk(117, 3294, 2, 6 * 60_000L))
        throw e.manual("Couldn't path into the Varrock sewer; pick up the key at 117,3294 by hand (it's only visible to you for a while)");
      e.gather(KEY3, 1, 117, 3294, 6, 30000);
    }

    @Override
    void step(Engine e, int stage) {
      switch (stage) {
        case 0:
          if (e.count(COINS) < 1) throw e.manual("Need 1 coin for the gypsy");
          e.go(133, 508, 6);
          e.talk(GYPSY, "Ok, here you go", "Yes please", "Very interesting what does the Aaargh bit mean?",
              "Who's Delrith?", "Ok thanks. I'll do my best to stop the Demon", "Ok here you go");
          break;
        case 1:
          prysin(e);
          break;
        case 2:
          traiborn(e, stage);
          break;
        case 3:
          if (!e.has(SILVERLIGHT)) {
            if (!e.has(KEY1)) {
              if (e.gatherFirst) collectBones(e);
              if (!e.has(BONES)) throw e.manual("Bring 25 bones for Wizard Traiborn");
              traiborn(e, stage);
            }
            rovinKey(e);
            drainKey(e);
            if (e.has(KEY1) && e.has(KEY2) && e.has(KEY3)) prysin(e);
            else throw new StepFailed("keys still missing");
          }
          break;
        case 4:
          fightDelrith(e);
          break;
        default:
          throw new StepFailed("unknown stage " + stage);
      }
    }

    void fightDelrith(Engine e) {
      if (!e.has(SILVERLIGHT) && !e.c.isItemIdEquipped(SILVERLIGHT)) {
        prysin(e);
        if (!e.has(SILVERLIGHT)) throw new StepFailed("no Silverlight");
      }
      if (!e.hasFood()) throw e.manual("Bring food before fighting Delrith (level 27) at the stone circle");
      if (!e.c.isItemIdEquipped(SILVERLIGHT)) {
        e.c.equipItemById(SILVERLIGHT);
        e.waitFor(() -> e.c.isItemIdEquipped(SILVERLIGHT), 5000);
      }
      e.go(111, 553, 3);
      long until = System.currentTimeMillis() + 10 * 60_000L;
      while (e.ok() && System.currentTimeMillis() < until && e.stageOf(this) == 4) {
        if (e.c.isInOptionMenu()) {
          int idx = e.matchMenu(new String[] {INCANTATION});
          if (idx >= 0) {
            e.log("chanting: " + INCANTATION);
            e.c.optionAnswer(idx);
            e.hideMenu();
            e.sleep(3000);
            continue;
          }
        }
        e.eatIfNeeded();
        if (e.hpPercent() < 25 && !e.hasFood()) {
          e.leaveCombat();
          throw e.manual("Out of food fighting Delrith; bring more food");
        }
        if (!e.c.isInCombat()) {
          ORSCharacter d = e.c.getNearestNpcById(DELRITH, false);
          if (d != null) e.c.attackNpc(d.serverIndex);
        }
        e.sleep(700);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // 9. Vampire Slayer - fully automated (needs food; combat vs Count Draynor lvl 34)
  // ---------------------------------------------------------------------------------------------

  static final class VampireSlayer extends Quest {
    static final int MORGAN = id(NpcId.MORGAN), HARLOW = id(NpcId.DR_HARLOW), COUNT = id(NpcId.COUNT_DRAYNOR);
    static final int BARTENDER = id(NpcId.BARTENDER_JOLLY_BOAR);
    static final int GARLIC = id(ItemId.GARLIC), STAKE = id(ItemId.STAKE), BEER = id(ItemId.BEER), HAMMER = id(ItemId.HAMMER);

    VampireSlayer() {
      super(QuestId.VAMPIRE_SLAYER, "vampire", "Vampire Slayer", "fully automated (bring food)");
      stepTimeoutMs = 30 * 60_000L;
    }

    @Override
    void gather(Engine e, int stage) {
      e.withdrawFromBank(DRAYNOR_BANK[0], DRAYNOR_BANK[1], new int[][] {{HAMMER, 1}, {GARLIC, 1}, {BEER, 1}});
      if (!e.has(HAMMER)) {
        e.go(126, 515, 3);
        e.openDoor(130, 515);
        e.buy(VARROCK_STORE, HAMMER, 1);
      }
    }

    @Override
    void step(Engine e, int stage) {
      switch (stage) {
        case 0:
          e.go(216, 619, 3);
          e.talk(MORGAN, "Ok I'm up for an adventure");
          break;
        case 1:
          if (!e.has(BEER)) {
            if (e.count(COINS) < 2) throw e.manual("Need 2 coins for a beer for Dr Harlow");
            e.go(82, 438, 3);
            e.openDoor(81, 441);
            e.talk(BARTENDER, "I'll have a beer please", "A glass of your finest ale please");
          }
          e.go(82, 438, 4);
          e.talk(HARLOW, "Morgan needs your help", "Ok mate");
          break;
        case 2:
          if (!e.has(HAMMER)) gather(e, stage);
          if (!e.has(STAKE)) {
            e.go(82, 438, 4);
            e.talk(HARLOW, "Morgan needs your help", "Ok mate");
            if (!e.has(STAKE)) throw new StepFailed("Dr Harlow didn't give a stake");
          }
          if (!e.has(HAMMER)) throw e.manual("Need a hammer to finish the Count");
          if (!e.hasFood()) throw e.manual("Bring food before fighting Count Draynor (level 34)");
          if (!e.has(GARLIC)) {
            e.go(216, 619, 2);
            if (e.climb(216, 620, false)) {
              e.c.atObject(140);
              e.sleep(1500);
              e.c.atObject(141);
              e.sleep(1500);
              e.climb(216, 1564, false);
            }
          }
          if (e.floor() != 3) {
            e.go(209, 561, 2);
            e.walk(210, 553, 0, 60000);
            for (int i = 0; i < 4 && e.ok() && e.y() >= 553; i++) {
              e.c.atWallObject(210, 553);
              e.sleep(2000);
            }
            e.walk(205, 554, 0, 60000);
            e.climb(204, 551, false);
            if (e.floor() != 3) throw new StepFailed("didn't reach the manor basement");
          }
          ORSCharacter count = e.c.getNearestNpcById(COUNT, true);
          for (int i = 0; i < 10 && count == null && e.ok(); i++) {
            e.c.atObject(204, 3380);
            e.sleep(1900);
            count = e.c.getNearestNpcById(COUNT, true);
          }
          final int[] missing = {0};
          long until = System.currentTimeMillis() + 6 * 60_000L;
          while (e.ok() && System.currentTimeMillis() < until && e.stageOf(this) == 2 && missing[0] < 8) {
            e.eatIfNeeded();
            if (e.hpPercent() < 25 && !e.hasFood()) {
              e.leaveCombat();
              throw e.manual("Out of food fighting Count Draynor");
            }
            count = e.c.getNearestNpcById(COUNT, true);
            if (count == null) missing[0]++;
            else {
              missing[0] = 0;
              if (!e.c.isInCombat()) e.c.attackNpc(count.serverIndex);
            }
            e.sleep(1200);
          }
          break;
        default:
          throw new StepFailed("unknown stage " + stage);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // 10. Ernest the Chicken - fully automated (lever puzzle solved by search over lever states)
  // ---------------------------------------------------------------------------------------------

  static final class ErnestTheChicken extends Quest {
    static final int VERONICA = id(NpcId.VERONICA), ODDENSTEIN = id(NpcId.PROFESSOR_ODDENSTEIN);
    static final int FISH_FOOD = id(ItemId.FISH_FOOD), GAUGE = id(ItemId.PRESSURE_GAUGE), TUBE = id(ItemId.RUBBER_TUBE);
    static final int SPADE = id(ItemId.SPADE), CLOSET_KEY = id(ItemId.CLOSET_KEY), OIL = id(ItemId.OIL_CAN);
    static final int POISON = id(ItemId.POISON), POISON_FOOD = id(ItemId.POISONED_FISH_FOOD);

    static final int[] FRONT_DOOR = {210, 553}, BACK_DOOR = {199, 551}, CLOSET_DOOR = {212, 545};
    static final int[] STAIRS_UP = {210, 547}, STAIRS_DOWN = {210, 1491};
    static final int[] LADDER_UP_F1 = {215, 1492}, LADDER_DOWN_F2 = {215, 2436};
    static final int[] LADDER_DOWN_BASEMENT = {223, 553}, LADDER_UP_BASEMENT = {223, 3385};
    static final int[] COMPOST = {230, 552}, FOUNTAIN = {226, 565}, ODDENSTEIN_ROOM = {211, 2441};
    static final int[] FISH_FOOD_SPAWN = {212, 1497}, POISON_SPAWN = {221, 546}, SPADE_SPAWN = {197, 554}, OIL_SPAWN = {232, 3382};

    // basement rooms {x1,y1,x2,y2}; levers {x,y,room}; doors {id,x,y,roomA,roomB,sideBx,sideBy}
    static final int R_LADDER = 0, R_OIL = 1, R_CD = 2, R_MID_N = 3, R_MID_S = 4, R_EF = 5, R_E_S = 6;
    static final int[][] ROOMS = {
      {222, 3381, 227, 3386}, {228, 3381, 233, 3383}, {222, 3375, 224, 3380}, {225, 3375, 227, 3377},
      {225, 3378, 227, 3380}, {228, 3375, 230, 3377}, {228, 3378, 230, 3380}
    };
    static final int[][] LEVERS = {
      {225, 3386, R_LADDER}, {222, 3382, R_LADDER}, {222, 3378, R_CD},
      {223, 3375, R_CD}, {229, 3375, R_EF}, {230, 3376, R_EF}
    };
    static final int[][] DOORS = {
      {25, 225, 3376, R_MID_N, R_CD, 224, 3376}, {26, 228, 3376, R_EF, R_MID_N, 227, 3376},
      {30, 226, 3378, R_MID_S, R_MID_N, 226, 3377}, {31, 229, 3378, R_E_S, R_EF, 229, 3377},
      {27, 225, 3379, R_MID_S, R_CD, 224, 3379}, {28, 228, 3379, R_E_S, R_MID_S, 227, 3379},
      {32, 223, 3381, R_LADDER, R_CD, 223, 3380}, {33, 226, 3381, R_LADDER, R_MID_S, 226, 3380},
      {29, 228, 3382, R_OIL, R_LADDER, 227, 3382}
    };

    int leverState = 0, leverKnown = 0;
    volatile long leverMsgAt = 0, lockedAt = 0;
    volatile String leverMsg = "";

    ErnestTheChicken() {
      super(QuestId.ERNEST_THE_CHICKEN, "ernest", "Ernest the Chicken", "fully automated");
      stepTimeoutMs = 40 * 60_000L;
    }

    void onMessage(String m) {
      if (m.startsWith("you pull lever ") || m.startsWith("the lever is ")) {
        leverMsg = m;
        leverMsgAt = System.currentTimeMillis();
      } else if (m.contains("the door is locked")) lockedAt = System.currentTimeMillis();
    }

    static boolean down(int s, char l) {
      return ((s >> (l - 'A')) & 1) != 0;
    }

    static boolean doorOpens(int id, int s) {
      boolean a = down(s, 'A'), b = down(s, 'B'), cc = down(s, 'C'), d = down(s, 'D'), e = down(s, 'E'), f = down(s, 'F');
      switch (id) {
        case 29: return !a && !b && cc && d && !e && f;
        case 28: return d;
        case 25: return !a && !b && d && e && f;
        case 26: return f && d && !b;
        case 27: return a && b && d;
        case 31: return d && !b && !f;
        case 32: return a && b && !cc && !d && !e && !f;
        case 33: return (!cc && d) || (!a && !b && cc && d && !e && f);
        case 30: return f && !e;
        default: return false;
      }
    }

    static int roomAt(int x, int y) {
      for (int i = 0; i < ROOMS.length; i++)
        if (x >= ROOMS[i][0] && x <= ROOMS[i][2] && y >= ROOMS[i][1] && y <= ROOMS[i][3]) return i;
      return -1;
    }

    /** Breadth-first search over (room, lever state); returns steps "L<i>" / "D<i>". */
    static List<String> solve(int from, int state, int to) {
      Map<Long, String> how = new HashMap<>();
      Map<Long, Long> parent = new HashMap<>();
      ArrayDeque<int[]> q = new ArrayDeque<>();
      long start = ((long) from << 8) | state;
      how.put(start, null);
      q.add(new int[] {from, state});
      while (!q.isEmpty()) {
        int[] p = q.poll();
        long key = ((long) p[0] << 8) | p[1];
        if (p[0] == to) {
          List<String> out = new ArrayList<>();
          Long k = key;
          while (k != null && how.get(k) != null) {
            out.add(0, how.get(k));
            k = parent.get(k);
          }
          return out;
        }
        for (int l = 0; l < LEVERS.length; l++) {
          if (LEVERS[l][2] != p[0]) continue;
          int ns = p[1] ^ (1 << l);
          long nk = ((long) p[0] << 8) | ns;
          if (how.containsKey(nk)) continue;
          how.put(nk, "L" + l);
          parent.put(nk, key);
          q.add(new int[] {p[0], ns});
        }
        for (int d = 0; d < DOORS.length; d++) {
          int other = DOORS[d][3] == p[0] ? DOORS[d][4] : DOORS[d][4] == p[0] ? DOORS[d][3] : -1;
          if (other < 0 || !doorOpens(DOORS[d][0], p[1])) continue;
          long nk = ((long) other << 8) | p[1];
          if (how.containsKey(nk)) continue;
          how.put(nk, "D" + d);
          parent.put(nk, key);
          q.add(new int[] {other, p[1]});
        }
      }
      return null;
    }

    int room(Engine e) {
      return roomAt(e.x(), e.y());
    }

    void setLever(int i, boolean isDown) {
      if (isDown) leverState |= 1 << i;
      else leverState &= ~(1 << i);
      leverKnown |= 1 << i;
    }

    boolean pullLever(Engine e, int i) {
      char name = (char) ('a' + i);
      for (int a = 0; a < 3 && e.ok(); a++) {
        e.walk(LEVERS[i][0], LEVERS[i][1], 1, 30000);
        long t = System.currentTimeMillis();
        e.log("pulling lever " + Character.toUpperCase(name));
        e.c.atObject(LEVERS[i][0], LEVERS[i][1]);
        if (e.waitFor(() -> leverMsgAt > t && leverMsg.startsWith("you pull lever " + name), 6000)) {
          setLever(i, leverMsg.contains(" down"));
          e.sleep(640);
          return true;
        }
      }
      return false;
    }

    boolean inspectLever(Engine e, int i) {
      e.walk(LEVERS[i][0], LEVERS[i][1], 1, 30000);
      long t = System.currentTimeMillis();
      e.c.atObject2(LEVERS[i][0], LEVERS[i][1]);
      if (e.waitFor(() -> leverMsgAt > t && leverMsg.startsWith("the lever is"), 6000)) {
        setLever(i, leverMsg.contains("down"));
        return true;
      }
      return false;
    }

    boolean useDoor(Engine e, int d) {
      int[] door = DOORS[d];
      int here = room(e);
      int target = door[3] == here ? door[4] : door[3];
      int sx = door[3] == here ? door[1] : door[5];
      int sy = door[3] == here ? door[2] : door[6];
      for (int a = 0; a < 3 && e.ok(); a++) {
        e.walk(sx, sy, 0, 30000);
        long t = System.currentTimeMillis();
        e.c.atWallObject(door[1], door[2]);
        boolean through = e.waitFor(() -> room(e) == target || lockedAt > t, 6000) && room(e) == target;
        if (through) {
          e.sleep(640);
          return true;
        }
        if (lockedAt > t) return false;
      }
      return false;
    }

    boolean basementGoTo(Engine e, int target) {
      int failures = 0;
      while (e.ok() && failures < 6) {
        int here = room(e);
        if (here < 0) {
          e.walk(LADDER_UP_BASEMENT[0], LADDER_UP_BASEMENT[1] - 1, 2, 30000);
          failures++;
          continue;
        }
        if (here == target) return true;
        for (int l = 0; l < LEVERS.length; l++)
          if (LEVERS[l][2] == here && (leverKnown & (1 << l)) == 0) inspectLever(e, l);
        List<String> plan = solve(here, leverState, target);
        if (plan == null) {
          e.log("no lever sequence found from room " + here + "; re-inspecting");
          leverKnown = 0;
          failures++;
          continue;
        }
        e.log("lever plan: " + plan);
        boolean ok = true;
        for (String s : plan) {
          if (!e.ok()) return false;
          int i = Integer.parseInt(s.substring(1));
          ok = s.charAt(0) == 'L' ? pullLever(e, i) : useDoor(e, i);
          if (!ok) {
            leverKnown = 0;
            failures++;
            break;
          }
        }
        if (ok && room(e) == target) return true;
      }
      return room(e) == target;
    }

    boolean inCloset(Engine e) {
      return e.floor() == 0 && e.x() >= 208 && e.x() <= 211 && e.y() >= 545 && e.y() <= 548;
    }

    /** Inside the manor's ground floor: decided by reachability of a tile inside vs. the garden. */
    boolean insideGround(Engine e) {
      if (e.floor() != 0) return false;
      if (e.x() < 190 || e.x() > 235 || e.y() < 535 || e.y() > 570) return false;
      if (inCloset(e)) return true;
      try {
        return e.c.isReachable(210, 550, false) && !e.c.isReachable(209, 561, false);
      } catch (RuntimeException ex) {
        return e.x() >= 200 && e.x() <= 224 && e.y() >= 543 && e.y() <= 552;
      }
    }

    void enterManor(Engine e) {
      if (insideGround(e)) return;
      e.go(FRONT_DOOR[0], FRONT_DOOR[1], 0);
      for (int i = 0; i < 4 && e.ok() && !insideGround(e); i++) {
        e.c.atWallObject(FRONT_DOOR[0], FRONT_DOOR[1]);
        e.waitFor(() -> insideGround(e), 6000);
      }
      if (!insideGround(e)) throw new StepFailed("couldn't enter Draynor Manor");
    }

    void toGround(Engine e) {
      for (int i = 0; i < 10 && e.ok(); i++) {
        int f = e.floor();
        if (f == 2) {
          e.walk(LADDER_DOWN_F2[0], LADDER_DOWN_F2[1] + 1, 1, 60000);
          e.climb(LADDER_DOWN_F2[0], LADDER_DOWN_F2[1], false);
        } else if (f == 1) {
          e.walk(STAIRS_DOWN[0], STAIRS_DOWN[1] - 1, 1, 60000);
          e.climb(STAIRS_DOWN[0], STAIRS_DOWN[1], false);
        } else if (f == 3) {
          if (room(e) != R_LADDER && !basementGoTo(e, R_LADDER)) throw new StepFailed("lost in the basement");
          e.climb(LADDER_UP_BASEMENT[0], LADDER_UP_BASEMENT[1], false);
          leverState = 0;
          leverKnown = (1 << LEVERS.length) - 1; // levers reset when leaving
        } else return;
      }
    }

    void exitManor(Engine e) {
      toGround(e);
      if (!insideGround(e)) return;
      e.walk(BACK_DOOR[0], BACK_DOOR[1], 0, 60000);
      for (int i = 0; i < 4 && e.ok() && insideGround(e); i++) {
        e.c.atWallObject(BACK_DOOR[0], BACK_DOOR[1]);
        e.waitFor(() -> !insideGround(e), 6000);
      }
      e.walk(199, 548, 1, 30000);
    }

    /** Moves anywhere in/around the manor: floor 0 inside/outside, 1, 2, or the basement (3). */
    void manorGo(Engine e, int x, int y, int tol, boolean targetInside) {
      int tf = Engine.floorOf(y);
      for (int i = 0; i < 12 && e.ok(); i++) {
        int f = e.floor();
        if (f == tf) {
          if (tf == 0) {
            if (targetInside && !insideGround(e)) enterManor(e);
            else if (!targetInside && insideGround(e)) exitManor(e);
          }
          e.go(x, y, tol);
          return;
        }
        if (f == 3 || f > tf) {
          toGround(e);
          continue;
        }
        if (f == 0) {
          enterManor(e);
          if (tf == 3) {
            e.go(LADDER_DOWN_BASEMENT[0], LADDER_DOWN_BASEMENT[1] + 1, 1);
            e.climbOrFail(LADDER_DOWN_BASEMENT[0], LADDER_DOWN_BASEMENT[1]);
            leverKnown = 0;
          } else {
            e.go(STAIRS_UP[0], STAIRS_UP[1] + 3, 1);
            e.climbOrFail(STAIRS_UP[0], STAIRS_UP[1]);
          }
        } else if (f == 1 && tf == 2) {
          e.go(LADDER_UP_F1[0], LADDER_UP_F1[1] + 1, 1);
          e.climbOrFail(LADDER_UP_F1[0], LADDER_UP_F1[1]);
        }
      }
      if (e.floor() != tf) throw new StepFailed("couldn't reach floor " + tf + " in the manor");
    }

    boolean take(Engine e, int item, int[] spawn, boolean inside) {
      if (e.has(item)) return true;
      if (e.freeSlots() < 1) throw e.manual("Inventory full; free some slots");
      manorGo(e, spawn[0], spawn[1], 1, inside);
      return e.gather(item, 1, spawn[0], spawn[1], 6, 3 * 60_000L);
    }

    void oddenstein(Engine e, String... ch) {
      manorGo(e, ODDENSTEIN_ROOM[0], ODDENSTEIN_ROOM[1], 2, true);
      e.talk(ODDENSTEIN, ch);
    }

    void gauge(Engine e) {
      if (e.has(GAUGE)) return;
      if (!e.has(POISON_FOOD)) {
        if (!take(e, FISH_FOOD, FISH_FOOD_SPAWN, true)) return;
        if (!take(e, POISON, POISON_SPAWN, true)) return;
        for (int i = 0; i < 3 && e.ok() && !e.has(POISON_FOOD); i++) {
          e.useItemOnItem(POISON, FISH_FOOD);
          e.waitFor(() -> e.has(POISON_FOOD), 4000);
        }
      }
      manorGo(e, 225, 564, 1, false);
      if (e.has(POISON_FOOD)) {
        e.c.useUnnotedItemIdOnObject(FOUNTAIN[0], FOUNTAIN[1], POISON_FOOD);
        e.waitFor(() -> !e.has(POISON_FOOD), 10000);
        e.sleep(4000);
      }
      for (int i = 0; i < 6 && e.ok() && !e.has(GAUGE); i++) {
        e.log("searching the fountain");
        e.c.atObject2(FOUNTAIN[0], FOUNTAIN[1]);
        e.waitFor(() -> e.has(GAUGE), 5000);
      }
    }

    void tube(Engine e) {
      if (e.has(TUBE)) return;
      if (!e.has(CLOSET_KEY)) {
        if (!take(e, SPADE, SPADE_SPAWN, true)) return;
        manorGo(e, 229, 552, 1, false);
        for (int i = 0; i < 4 && e.ok() && !e.has(CLOSET_KEY); i++) {
          e.log("digging in the compost heap");
          e.c.useUnnotedItemIdOnObject(COMPOST[0], COMPOST[1], SPADE);
          e.waitFor(() -> e.has(CLOSET_KEY), 8000);
        }
        if (!e.has(CLOSET_KEY)) return;
      }
      manorGo(e, CLOSET_DOOR[0], CLOSET_DOOR[1], 0, true);
      for (int i = 0; i < 4 && e.ok() && e.x() >= CLOSET_DOOR[0]; i++) {
        e.c.useItemOnWall(CLOSET_DOOR[0], CLOSET_DOOR[1], e.c.getInventoryItemSlotIndex(CLOSET_KEY));
        e.waitFor(() -> e.x() < CLOSET_DOOR[0], 6000);
      }
      if (e.x() >= CLOSET_DOOR[0]) return;
      e.gather(TUBE, 1, e.x(), e.y(), 4, 3 * 60_000L);
      leaveCloset(e);
    }

    void leaveCloset(Engine e) {
      if (!inCloset(e)) return;
      e.walk(CLOSET_DOOR[0] - 1, CLOSET_DOOR[1], 0, 20000);
      for (int i = 0; i < 4 && e.ok() && e.x() < CLOSET_DOOR[0]; i++) {
        int slot = e.c.getInventoryItemSlotIndex(CLOSET_KEY);
        if (slot >= 0) e.c.useItemOnWall(CLOSET_DOOR[0], CLOSET_DOOR[1], slot);
        else e.c.atWallObject(CLOSET_DOOR[0], CLOSET_DOOR[1]);
        e.waitFor(() -> e.x() >= CLOSET_DOOR[0], 6000);
      }
    }

    void oilCan(Engine e) {
      if (e.has(OIL)) return;
      if (e.floor() != 3) manorGo(e, LADDER_UP_BASEMENT[0], LADDER_UP_BASEMENT[1] - 1, 1, true);
      if (e.floor() != 3) return;
      if (!basementGoTo(e, R_OIL)) throw new StepFailed("couldn't solve the lever puzzle");
      e.gather(OIL, 1, OIL_SPAWN[0], OIL_SPAWN[1], 4, 3 * 60_000L);
      if (e.has(OIL) && basementGoTo(e, R_LADDER)) {
        e.climb(LADDER_UP_BASEMENT[0], LADDER_UP_BASEMENT[1], false);
        leverState = 0;
        leverKnown = (1 << LEVERS.length) - 1;
      }
    }

    @Override
    void step(Engine e, int stage) {
      switch (stage) {
        case 0:
          if (e.floor() != 0 || insideGround(e)) exitManor(e);
          e.go(209, 561, 3);
          e.talk(VERONICA, "Aha, sounds like a quest. I'll help");
          break;
        case 1:
          oddenstein(e, "I'm looking for a guy called Ernest", "Change him back this instant");
          break;
        case 2:
          if (e.freeSlots() < 5) throw e.manual("Free at least 5 inventory slots for Ernest's items");
          leaveCloset(e);
          gauge(e);
          tube(e);
          oilCan(e);
          if (e.has(GAUGE) && e.has(TUBE) && e.has(OIL)) oddenstein(e);
          else throw new StepFailed("still missing: " + (e.has(GAUGE) ? "" : "pressure gauge ")
              + (e.has(TUBE) ? "" : "rubber tube ") + (e.has(OIL) ? "" : "oil can"));
          break;
        default:
          throw new StepFailed("unknown stage " + stage);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // 11. Imp Catcher - fully automated (imps near Falador; beads are taken from the bank first)
  // ---------------------------------------------------------------------------------------------

  static final class ImpCatcher extends Quest {
    static final int MIZGOG = id(NpcId.WIZARD_MIZGOG), IMP = id(NpcId.IMP);
    static final int[] BEADS = {id(ItemId.RED_BEAD), id(ItemId.YELLOW_BEAD), id(ItemId.BLACK_BEAD), id(ItemId.WHITE_BEAD)};

    ImpCatcher() {
      super(QuestId.IMP_CATCHER, "imp", "Imp Catcher", "fully automated (imp farming can take a while)");
      stepTimeoutMs = 60 * 60_000L;
    }

    boolean beads(Engine e) {
      for (int b : BEADS) if (!e.has(b)) return false;
      return true;
    }

    @Override
    void gather(Engine e, int stage) {
      if (beads(e)) return;
      int[][] want = new int[BEADS.length][];
      for (int i = 0; i < BEADS.length; i++) want[i] = new int[] {BEADS[i], 1};
      e.withdrawFromBank(FALADOR_WEST_BANK[0], FALADOR_WEST_BANK[1], want);
      if (beads(e)) return;
      if (e.freeSlots() < 4) throw e.manual("Free 4 inventory slots for the beads");
      int[][] spots = {{314, 558}, {314, 550}};
      int spot = 0;
      long until = System.currentTimeMillis() + 50 * 60_000L;
      e.go(spots[0][0], spots[0][1], 4);
      while (e.ok() && !beads(e) && System.currentTimeMillis() < until) {
        List<Integer> missing = new ArrayList<>();
        for (int b : BEADS) if (!e.has(b)) missing.add(b);
        int[] pick = new int[missing.size()];
        for (int i = 0; i < pick.length; i++) pick[i] = missing.get(i);
        e.fightUntil(new int[] {IMP}, () -> beads(e), 90_000L, pick);
        if (!beads(e) && !e.c.isInCombat() && e.c.getNearestNpcById(IMP, false) == null) {
          spot ^= 1;
          e.walk(spots[spot][0], spots[spot][1], 3, 60000);
        }
      }
      if (!beads(e)) throw new StepFailed("beads still missing");
    }

    void toMizgog(Engine e) {
      if (e.floor() != 2) {
        e.go(217, 688, 3);
        if (e.floor() == 0) {
          e.go(218, 693, 1);
          e.climbOrFail(218, 692);
        }
        if (e.floor() == 1) {
          e.go(216, 1637, 1);
          e.climbOrFail(216, 1636);
        }
      }
      e.openDoor(216, 2579);
      e.openDoor(216, 2581);
      e.openDoor(217, 2582);
    }

    void leave(Engine e) {
      if (e.floor() == 2) {
        e.walk(216, 2581, 1, 30000);
        e.climb(216, 2580, false);
      }
      if (e.floor() == 1) {
        e.walk(218, 1637, 1, 30000);
        e.climb(218, 1636, false);
      }
    }

    @Override
    void step(Engine e, int stage) {
      if (stage == 0) {
        toMizgog(e);
        e.talk(MIZGOG, "Give me a quest!", "Give me a quest please");
        return;
      }
      gather(e, stage);
      toMizgog(e);
      e.talk(MIZGOG);
      if (e.stageOf(this) == -1) leave(e);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // 12. Doric's Quest - fully automated (mines the ores if needed: iron needs Mining 15)
  // ---------------------------------------------------------------------------------------------

  static final class DoricsQuest extends Quest {
    static final int DORIC = id(NpcId.DORIC);
    static final int CLAY = id(ItemId.CLAY), COPPER = id(ItemId.COPPER_ORE), IRON = id(ItemId.IRON_ORE);
    static final int[] PICKS = {
      id(ItemId.BRONZE_PICKAXE), id(ItemId.IRON_PICKAXE), id(ItemId.STEEL_PICKAXE),
      id(ItemId.MITHRIL_PICKAXE), id(ItemId.ADAMANTITE_PICKAXE), id(ItemId.RUNE_PICKAXE)
    };

    DoricsQuest() {
      super(QuestId.DORICS_QUEST, "doric", "Doric's Quest", "fully automated (mines ores if not carried)");
      stepTimeoutMs = 40 * 60_000L;
    }

    boolean hasOres(Engine e) {
      return e.has(CLAY, 6) && e.has(COPPER, 4) && e.has(IRON, 2);
    }

    boolean hasPick(Engine e) {
      for (int p : PICKS) if (e.has(p) || e.c.isItemIdEquipped(p)) return true;
      return false;
    }

    @Override
    String missingRequirements(Engine e) {
      if (!e.has(IRON, 2) && e.c.getBaseStat(MINING) < 15) return "Mining 15 for iron ore (or carry 2 iron ore)";
      return null;
    }

    void mine(Engine e, int rx, int ry, int ore, int n) {
      long until = System.currentTimeMillis() + 10 * 60_000L;
      while (e.ok() && e.count(ore) < n && System.currentTimeMillis() < until) {
        if (e.freeSlots() < 1) throw e.manual("Inventory full while mining");
        int before = e.count(ore);
        e.c.atObject(rx, ry);
        e.waitFor(() -> e.count(ore) > before, 5000);
      }
    }

    @Override
    void gather(Engine e, int stage) {
      if (hasOres(e)) return;
      e.withdrawFromBank(FALADOR_WEST_BANK[0], FALADOR_WEST_BANK[1], new int[][] {{CLAY, 6}, {COPPER, 4}, {IRON, 2}, {PICKS[0], 1}});
      if (hasOres(e)) return;
      if (!hasPick(e)) throw e.manual("Need a pickaxe to mine Doric's ores (or bring 6 clay, 4 copper, 2 iron)");
      if (!e.has(IRON, 2) && e.c.getCurrentStat(MINING) < 15) throw e.manual("Iron ore needs Mining 15; bring 2 iron ore");
      int need = Math.max(0, 6 - e.count(CLAY)) + Math.max(0, 4 - e.count(COPPER)) + Math.max(0, 2 - e.count(IRON));
      if (e.freeSlots() < need) throw e.manual("Free " + need + " inventory slots for ores");
      if (e.floor() != 3) {
        e.go(280, 493, 1);
        e.go(279, 495, 1);
        if (!e.climb(279, 494, false)) throw new StepFailed("couldn't climb down into the Dwarven Mine");
      }
      e.go(277, 3347, 1);
      mine(e, 277, 3348, CLAY, 6);
      e.go(276, 3342, 1);
      mine(e, 276, 3341, COPPER, 4);
      e.go(274, 3341, 1);
      mine(e, 274, 3340, IRON, 2);
      e.go(280, 3327, 1);
      e.climb(279, 3326, false);
    }

    @Override
    void step(Engine e, int stage) {
      if (stage == 0 && !e.gatherFirst) {
        e.go(325, 492, 2);
        e.talk(DORIC, "I wanted to use your anvils", "Yes I will get you materials");
        return;
      }
      gather(e, stage);
      if (e.floor() == 3) e.climb(279, 3326, false);
      e.go(325, 493, 1);
      e.openDoor(325, 492);
      e.talk(DORIC, "I wanted to use your anvils", "Yes I will get you materials");
    }
  }

  // ---------------------------------------------------------------------------------------------
  // 13. Goblin Diplomacy - fully automated (goblin armour farming can take a while)
  // ---------------------------------------------------------------------------------------------

  static final class GoblinDiplomacy extends Quest {
    static final int BARTENDER = id(NpcId.BARTENDER_PORTSARIM), WARTFACE = id(NpcId.GENERAL_WARTFACE), WYSON = id(NpcId.WYSON_THE_GARDENER);
    static final int AGGIE = id(NpcId.AGGIE);
    static final int ARMOUR = id(ItemId.GOBLIN_ARMOUR), ORANGE_ARMOUR = id(ItemId.ORANGE_GOBLIN_ARMOUR), BLUE_ARMOUR = id(ItemId.BLUE_GOBLIN_ARMOUR);
    static final int RED = id(ItemId.REDDYE), YELLOW = id(ItemId.YELLOWDYE), ORANGE = id(ItemId.ORANGEDYE), BLUE = id(ItemId.BLUEDYE);
    static final int WOAD = id(ItemId.WOAD_LEAF), BERRIES = id(ItemId.REDBERRIES), ONION = id(ItemId.ONION);
    static final int[] GOBLINS = {id(NpcId.GOBLIN_RED_ARMOUR_LVL13), id(NpcId.GOBLIN_GREEN_ARMOUR_LVL13), id(NpcId.GOBLIN_LVL13), id(NpcId.GOBLIN_LVL7)};
    static final int[] VILLAGE = {322, 449};

    GoblinDiplomacy() {
      super(QuestId.GOBLIN_DIPLOMACY, "goblin", "Goblin Diplomacy", "fully automated (needs ~35 coins)");
      stepTimeoutMs = 60 * 60_000L;
    }

    int armourNeeded(Engine e, int stage) {
      int n = 0;
      if (stage <= 2 && !e.has(ORANGE_ARMOUR)) n++;
      if (stage <= 3 && !e.has(BLUE_ARMOUR)) n++;
      return n + 1; // plain one for the last hand-in
    }

    void aggie(Engine e, String dye) {
      e.go(223, 625, 3);
      e.talk(AGGIE, "Can you make dyes for me please", "What do you need to make some " + dye + " dye please",
          "Okay, make me some " + dye + " dye please");
    }

    @Override
    void gather(Engine e, int stage) {
      boolean needOrange = stage <= 2 && !e.has(ORANGE_ARMOUR) && !e.has(ORANGE);
      boolean needBlue = stage <= 3 && !e.has(BLUE_ARMOUR) && !e.has(BLUE);
      if (needOrange) {
        if (!e.has(RED)) getRedberries(e, 3);
        if (!e.has(YELLOW)) getOnions(e, 2);
      }
      if (needBlue && !e.has(WOAD, 2)) {
        if (e.count(COINS) < 20) throw e.manual("Need 20 coins for Wyson's woad leaves");
        e.go(292, 545, 6);
        e.talk(WYSON, "I'm looking for woad leaves", "How about 20 coins?");
        if (!e.has(WOAD, 2)) throw new StepFailed("no woad leaves");
      }
      if ((needOrange && (!e.has(RED) || !e.has(YELLOW))) || (needBlue && !e.has(BLUE))) {
        int coinsNeeded = 5 * ((needOrange && !e.has(RED) ? 1 : 0) + (needOrange && !e.has(YELLOW) ? 1 : 0) + (needBlue ? 1 : 0));
        if (e.count(COINS) < coinsNeeded) throw e.manual("Need " + coinsNeeded + " coins for Aggie's dyes");
        if (needOrange && !e.has(RED) && e.has(BERRIES, 3)) aggie(e, "red");
        if (needOrange && !e.has(YELLOW) && e.has(ONION, 2)) aggie(e, "yellow");
        if (needBlue && !e.has(BLUE) && e.has(WOAD, 2)) aggie(e, "blue");
      }
      if (needOrange && e.has(RED) && e.has(YELLOW)) e.useItemOnItem(RED, YELLOW);
      int need = armourNeeded(e, stage);
      if (e.count(ARMOUR) < need) {
        if (e.freeSlots() < need - e.count(ARMOUR)) throw e.manual("Free inventory slots for goblin armour");
        e.log("farming goblin armour (" + e.count(ARMOUR) + "/" + need + ")");
        e.go(VILLAGE[0], VILLAGE[1], 6);
        e.fightUntil(GOBLINS, () -> e.count(ARMOUR) >= need, 40 * 60_000L, new int[] {ARMOUR});
        if (e.count(ARMOUR) < need) throw new StepFailed("not enough goblin armour yet");
      }
      if (stage <= 2 && !e.has(ORANGE_ARMOUR) && e.has(ORANGE) && e.has(ARMOUR)) e.useItemOnItem(ORANGE, ARMOUR);
      if (stage <= 3 && !e.has(BLUE_ARMOUR) && e.has(BLUE) && e.has(ARMOUR)) e.useItemOnItem(BLUE, ARMOUR);
    }

    void generals(Engine e, String... ch) {
      e.go(VILLAGE[0], VILLAGE[1], 5);
      e.talk(WARTFACE, ch);
    }

    @Override
    void step(Engine e, int stage) {
      switch (stage) {
        case 0:
          e.go(253, 625, 3);
          e.talk(BARTENDER, "Not very busy in here today is it?");
          break;
        case 1:
          generals(e, "Do you want me to pick an armour colour for you?");
          break;
        case 2:
        case 3:
        case 4:
          gather(e, stage);
          int item = stage == 2 ? ORANGE_ARMOUR : stage == 3 ? BLUE_ARMOUR : ARMOUR;
          e.requireItems("Goblin armour hand-in", new int[][] {{item, 1}});
          generals(e);
          break;
        default:
          throw new StepFailed("unknown stage " + stage);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // 14. Pirate's Treasure - partly automated (the Karamja rum smuggling is a manual step)
  // ---------------------------------------------------------------------------------------------

  static final class PiratesTreasure extends Quest {
    static final int FRANK = id(NpcId.REDBEARD_FRANK);
    static final int RUM = id(ItemId.KARAMJA_RUM), CHEST_KEY = id(ItemId.CHEST_KEY), SPADE = id(ItemId.SPADE);
    static final int CHEST = 187, FLOWERS = 188;

    PiratesTreasure() {
      super(QuestId.PIRATES_TREASURE, "pirate", "Pirate's Treasure",
          "partly automated: rum smuggling and Hector's chest are manual");
    }

    @Override
    void step(Engine e, int stage) {
      switch (stage) {
        case 0:
          e.go(269, 650, 6);
          e.talk(FRANK, "I'm in search of treasure");
          break;
        case 1:
          if (!e.has(RUM))
            throw e.manual("Get Karamja rum: sail to Karamja (30gp), buy rum from Zambo, work for Luthas, stash the rum"
                + " in the banana crate, sail back, get a white apron job at Wydin's and search the back-room crate."
                + " The script continues when the rum is in your inventory and you restart/resume.");
          e.go(269, 650, 6);
          e.talk(FRANK);
          break;
        case 2:
          if (!e.has(CHEST_KEY)) {
            e.go(269, 650, 6);
            e.talk(FRANK, "Arrrh");
          }
          int[] chest = e.c.getNearestObjectById(CHEST);
          if (chest == null)
            throw e.manual("Open Hector's chest upstairs in the Blue Moon Inn (Varrock) with the chest key");
          e.walk(chest[0], chest[1] + 1, 1, 60000);
          e.useOnObject(chest[0], chest[1], CHEST_KEY);
          break;
        case 3:
          if (!e.has(SPADE))
            e.withdrawFromBank(FALADOR_WEST_BANK[0], FALADOR_WEST_BANK[1], new int[][] {{SPADE, 1}});
          if (!e.has(SPADE)) throw e.manual("Bring a spade (one spawns in Draynor Manor) to dig in Falador park");
          e.go(292, 548, 6);
          int[] f = e.c.getNearestObjectById(FLOWERS);
          if (f == null) throw e.manual("Dig with the spade just behind the south bench in Falador park");
          e.walk(f[0], f[1] + 1, 1, 60000);
          e.c.useUnnotedItemIdOnObject(f[0], f[1], SPADE);
          e.sleep(5000);
          break;
        default:
          throw new StepFailed("unknown stage " + stage);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // 15. Black Knights' Fortress - manual (disguise, secret doors and the grill can't be done safely)
  // ---------------------------------------------------------------------------------------------

  static final class BlackKnightsFortress extends Quest {
    BlackKnightsFortress() {
      super(QuestId.BLACK_KNIGHTS_FORTRESS, "bkf", "Black Knights' Fortress", "manual (12 QP; disguise + fortress)");
    }

    @Override
    String missingRequirements(Engine e) {
      return e.c.getQuestPoints() < 12 ? "12 quest points (have " + e.c.getQuestPoints() + ")" : null;
    }

    @Override
    void step(Engine e, int stage) {
      throw e.manual("Black Knights' Fortress: wear an iron chainmail body + bronze med helm, talk to Sir Amik Varze"
          + " (\"I seek a quest\", \"I laugh in the face of danger\"), listen at the grill in the fortress and use a"
          + " normal cabbage on the hole. Not automated.");
    }
  }

  // ---------------------------------------------------------------------------------------------
  // 16. The Knight's Sword - partly automated (portrait and blurite are manual)
  // ---------------------------------------------------------------------------------------------

  static final class KnightsSword extends Quest {
    static final int SQUIRE = id(NpcId.SQUIRE), RELDO = id(NpcId.RELDO), THURGO = id(NpcId.THURGO);
    static final int PIE = id(ItemId.REDBERRY_PIE), PORTRAIT = id(ItemId.PORTRAIT), BLURITE = id(ItemId.BLURITE_ORE);
    static final int IRON_BAR = id(ItemId.IRON_BAR), SWORD = id(ItemId.FALADIAN_KNIGHTS_SWORD);

    KnightsSword() {
      super(QuestId.THE_KNIGHTS_SWORD, "sword", "The Knight's Sword",
          "partly automated: portrait + blurite manual; bring redberry pie and 2 iron bars");
    }

    @Override
    String missingRequirements(Engine e) {
      if (e.c.getBaseStat(MINING) < 10) return "Mining 10 for blurite";
      return null;
    }

    void squire(Engine e, String... ch) {
      e.go(313, 564, 6);
      e.talk(SQUIRE, ch);
    }

    void thurgo(Engine e, String... ch) {
      e.go(293, 712, 4);
      e.talk(THURGO, ch);
    }

    @Override
    void step(Engine e, int stage) {
      switch (stage) {
        case 0:
          squire(e, "And how is life as a squire?", "I can make a new sword if you like",
              "So would these dwarves make another one?", "Ok I'll give it a go");
          break;
        case 1:
          e.go(128, 457, 3);
          e.talk(RELDO, "What do you know about the Imcando dwarves?");
          break;
        case 2:
          if (!e.has(PIE)) throw e.manual("Bring a redberry pie for Thurgo (south of Port Sarim)");
          thurgo(e, "Would you like some redberry pie?");
          break;
        case 3:
          thurgo(e);
          break;
        case 4:
          if (!e.has(PORTRAIT))
            throw e.manual("Get the portrait: ask the squire, then search Sir Vyvin's cupboard (top floor of Falador"
                + " castle) while he is not watching. Give it to Thurgo.");
          thurgo(e);
          break;
        case 5:
          if (!e.has(BLURITE) || !e.has(IRON_BAR, 2))
            throw e.manual("Bring 1 blurite ore (Ice dungeon under Thurgo's cliff, ice warriors/giants) and 2 iron bars to Thurgo");
          thurgo(e);
          break;
        case 6:
          if (!e.has(SWORD)) throw e.manual("You need the Faladian knight's sword from Thurgo");
          squire(e);
          break;
        default:
          throw new StepFailed("unknown stage " + stage);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // 17. Dragon Slayer - manual
  // ---------------------------------------------------------------------------------------------

  static final class DragonSlayer extends Quest {
    DragonSlayer() {
      super(QuestId.DRAGON_SLAYER, "dragon", "Dragon Slayer", "manual (32 QP, Elvarg)");
    }

    @Override
    String missingRequirements(Engine e) {
      return e.c.getQuestPoints() < 32 ? "32 quest points (have " + e.c.getQuestPoints() + ")" : null;
    }

    @Override
    void step(Engine e, int stage) {
      throw e.manual("Dragon Slayer is not automated (map pieces from Melzar's maze, Wormbrain and the Oracle"
          + " door, ship + Ned, anti-dragon shield from the Duke, then Elvarg). Follow the guide by hand.");
    }
  }
}
