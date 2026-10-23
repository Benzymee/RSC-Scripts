package scripting.idlescript.SeattaScript.AIOQuester;

import bot.Main;
import bot.ui.SidePanel;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuestDef;

/**
 * AIOQuester's paint, docked in IdleRSC's side panel directly under the side buttons (Load Script,
 * ... World Map) through SidePanel.setScriptDock, with a "Skip quest" button.
 *
 * <p>Every row is laid out on the exact x and width of the side panel's own buttons (measured from
 * a live button, so it follows the panel width / look and feel): the same 4 px gap on the left,
 * the same width, and therefore the same gap on the right as the buttons above. Falls back to the
 * SidePanel formula (x 4, width max(110, sideWidth - 18)) before the buttons are laid out.
 *
 * <p>While docked and visible the on-canvas paint is not drawn (QuestHandler.drawPaint); when the
 * side panel is hidden the old canvas paint comes back.
 */
final class QuestDock extends JPanel {
  // Dracula palette, as the canvas paint
  private static final Color PURPLE = new Color(0xBD93F9);
  private static final Color DARK = new Color(0x282A36);
  private static final Color DARKER = new Color(0x1D1F27);
  private static final Color WHITE = new Color(0xF8F8F2);
  private static final Color DIM = new Color(0xA8A8B8);
  private static final Color GREEN = new Color(0x50FA7B);
  private static final Color YELLOW = new Color(0xF1FA8C);
  private static final Color RED = new Color(0xFF5555);
  private static final Color CYAN = new Color(0x8BE9FD);

  private static final Font F_TITLE = new Font("SansSerif", Font.BOLD, 12);
  private static final Font F_STEP = new Font("SansSerif", Font.PLAIN, 11);
  private static final Font F_SMALL = new Font("SansSerif", Font.PLAIN, 10);
  private static final Font F_BUTTON = new Font("SansSerif", Font.PLAIN, 12); // = side buttons

  private static final int ROW_TOP = 2; // side button wrappers: 2 px matte top/bottom
  private static final int BUTTON_H = 30; // side button height
  private static final int BUTTON_ROW_H = 35; // side button wrapper height

  private static volatile QuestDock instance;
  /** Read by the client's draw thread: true while the dock is on screen. */
  private static volatile boolean showingNow = false;

  private final PaintView view = new PaintView();
  private final JButton skip = new JButton("Skip quest");
  private final JButton steps = new JButton("Quest steps");
  private final AlignedRow viewRow;
  private final AlignedRow skipRow;
  private final AlignedRow stepsRow;
  private final Timer timer;
  private QuestDef skipPressedFor = null;

  private QuestDock() {
    setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
    setOpaque(true);
    viewRow = new AlignedRow(view, -1);
    skipRow = new AlignedRow(skip, BUTTON_H);
    stepsRow = new AlignedRow(steps, BUTTON_H);
    // Skip and the step list sit with the side buttons. The paint is under them.
    add(skipRow);
    add(stepsRow);
    add(viewRow);
    styleButton(skip);
    styleButton(steps);
    skip.addActionListener(e -> onSkip());
    steps.addActionListener(
        e -> {
          QuestHandler.showQuestSteps = !QuestHandler.showQuestSteps;
          tick();
        });
    timer = new Timer(500, e -> tick());
    timer.setRepeats(true);
    applyTheme();
    tick();
  }

  // ---------------------------------------------------------------- install / remove

  /** Docks the paint under the side buttons (EDT-safe). */
  static void install() {
    Runnable apply =
        () -> {
          try {
            if (instance == null) instance = new QuestDock();
            SidePanel.setScriptDock(instance);
            instance.timer.start();
            Main.log("AIOQuester: paint docked under the side panel buttons");
          } catch (Throwable t) {
            instance = null;
            showingNow = false;
            Main.log("AIOQuester: couldn't dock the paint (" + t + "), drawing it on the game view");
          }
        };
    if (SwingUtilities.isEventDispatchThread()) apply.run();
    else SwingUtilities.invokeLater(apply);
  }

  /** Removes the dock (EDT-safe). */
  static void uninstall() {
    final QuestDock d = instance;
    instance = null;
    showingNow = false;
    if (d == null) return;
    Runnable apply =
        () -> {
          d.timer.stop();
          try {
            SidePanel.clearScriptDock();
          } catch (Throwable ignored) {
            // older client
          }
        };
    if (SwingUtilities.isEventDispatchThread()) apply.run();
    else SwingUtilities.invokeLater(apply);
  }

  /** True while the docked paint is visible (then the canvas paint is skipped). */
  static boolean isShowingDocked() {
    return showingNow && instance != null;
  }

  // ---------------------------------------------------------------- behaviour

  private void onSkip() {
    QuestDef q = QuestHandler.QUEST;
    if (q == null) return;
    skipPressedFor = q;
    skip.setText("Skipping...");
    skip.setEnabled(false);
    // The quest thread picks this up at its next check, unwinds and the queue moves on
    new Thread(() -> QuestHandler.requestSkip("Skip quest button"), "AIOQuester skip").start();
  }

  private void tick() {
    showingNow = isShowing();
    QuestDef q = QuestHandler.QUEST;
    boolean questRunning = QuestHandler.isQuestRunning();
    if (skipPressedFor != null && skipPressedFor != q) skipPressedFor = null; // next quest
    boolean canSkip = questRunning && skipPressedFor == null;
    if (skip.isEnabled() != canSkip) {
      skip.setEnabled(canSkip);
      skip.setBackground(bg2());
    }
    String text = skipPressedFor != null ? "Skipping..." : "Skip quest";
    if (!text.equals(skip.getText())) skip.setText(text);
    String stepsText = QuestHandler.showQuestSteps ? "Hide quest steps" : "Quest steps";
    if (!stepsText.equals(steps.getText())) steps.setText(stepsText);
    steps.setToolTipText("Show every stage, with completed stages marked off");
    skip.setToolTipText(
        q == null
            ? "No quest running"
            : "Abandon " + q.getName() + " now and go on with the next selected quest (also ::skip)");
    int h = view.wantedHeight(viewRow.childWidth());
    if (h != view.lastHeight) {
      view.lastHeight = h;
      viewRow.revalidate();
      revalidate();
    }
    view.repaint();
  }

  // ---------------------------------------------------------------- theme

  private static Color bg1() {
    return Main.primaryBG != null ? Main.primaryBG : new Color(40, 40, 40);
  }

  private static Color bg2() {
    return Main.secondaryBG != null ? Main.secondaryBG : new Color(60, 60, 60);
  }

  private static Color fg1() {
    return Main.primaryFG != null ? Main.primaryFG : Color.LIGHT_GRAY;
  }

  private static Color fg2() {
    return Main.secondaryFG != null ? Main.secondaryFG : Color.WHITE;
  }

  private void applyTheme() {
    setBackground(bg1());
    viewRow.setBackground(bg1());
    skipRow.setBackground(bg1());
    stepsRow.setBackground(bg1());
  }

  /** Same font, colours, border and hover as SidePanel's buttons. */
  private void styleButton(final JButton button) {
    button.setFont(F_BUTTON);
    button.setFocusable(false);
    button.setBackground(bg2());
    button.setForeground(fg2());
    Border outerShadow = new MatteBorder(0, 0, 2, 2, bg1().darker().darker());
    Border innerBorder = new LineBorder(fg1().darker(), 2);
    button.setBorder(new CompoundBorder(outerShadow, innerBorder));
    button.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseEntered(MouseEvent e) {
            if (button.isEnabled()) button.setBackground(bg2().brighter());
          }

          @Override
          public void mouseExited(MouseEvent e) {
            button.setBackground(bg2());
          }
        });
  }

  // ---------------------------------------------------------------- alignment

  /** The side panel's first button (Load Script), to copy its x and width. */
  private static JButton referenceButton() {
    Container side = Main.sidePanel;
    if (side == null) return null;
    return findButton(side);
  }

  private static JButton findButton(Container parent) {
    for (Component child : parent.getComponents()) {
      if (child instanceof QuestDock) continue;
      if (child instanceof JButton && child.isVisible() && child.getWidth() > 0) return (JButton) child;
      if (child instanceof Container) {
        JButton b = findButton((Container) child);
        if (b != null) return b;
      }
    }
    return null;
  }

  /** {x, width} of the side buttons in the given component's coordinates. */
  private static int[] buttonColumn(Component in) {
    JButton ref = referenceButton();
    if (ref != null && ref.getParent() != null && in.isShowing() && ref.isShowing()) {
      Rectangle r = SwingUtilities.convertRectangle(ref.getParent(), ref.getBounds(), in);
      if (r.width > 0) return new int[] {r.x, r.width};
    }
    int side;
    try {
      side = SidePanel.getSideWidth();
    } catch (Throwable t) {
      side = 140;
    }
    return new int[] {4, Math.max(110, side - 18)}; // SidePanel: 4 px wrapper matte, width - 18
  }

  /** One row: its child sits exactly in the side buttons' column. */
  private static final class AlignedRow extends JPanel {
    private final JComponent child;
    private final int fixedHeight; // -1 = the paint view's wanted height

    AlignedRow(JComponent child, int fixedHeight) {
      super(null);
      this.child = child;
      this.fixedHeight = fixedHeight;
      setOpaque(true);
      setAlignmentX(Component.LEFT_ALIGNMENT);
      add(child);
    }

    int childWidth() {
      return buttonColumn(this)[1];
    }

    private int childHeight() {
      if (fixedHeight > 0) return fixedHeight;
      PaintView v = (PaintView) child;
      return v.lastHeight > 0 ? v.lastHeight : v.wantedHeight(childWidth());
    }

    @Override
    public void doLayout() {
      int[] col = buttonColumn(this);
      child.setBounds(col[0], ROW_TOP, col[1], childHeight());
    }

    @Override
    public Dimension getPreferredSize() {
      int h = fixedHeight > 0 ? BUTTON_ROW_H : childHeight() + ROW_TOP * 2 + 1;
      int[] col = buttonColumn(this);
      return new Dimension(col[0] + col[1], h);
    }

    @Override
    public Dimension getMaximumSize() {
      return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override
    public Dimension getMinimumSize() {
      return new Dimension(0, getPreferredSize().height);
    }
  }

  // ---------------------------------------------------------------- the paint

  private static final class Line {
    final String text;
    final Font font;
    final Color color;
    final boolean centered;
    final String right; // right-aligned second column (step items)
    final Color rightColor;

    Line(String text, Font font, Color color, boolean centered) {
      this(text, font, color, centered, null, null);
    }

    Line(String text, Font font, Color color, boolean centered, String right, Color rightColor) {
      this.text = text;
      this.font = font;
      this.color = color;
      this.centered = centered;
      this.right = right;
      this.rightColor = rightColor;
    }
  }

  /** Marker line for the stage progress bar. */
  private static final Line BAR = new Line("", F_SMALL, WHITE, true);

  private static final int PAD = 4;
  private static final int BAR_H = 14;

  private static final class PaintView extends JPanel {
    int lastHeight = 0;

    PaintView() {
      setOpaque(true);
    }

    int wantedHeight(int width) {
      int h = PAD;
      for (Line l : lines(width)) h += l == BAR ? BAR_H + 3 : getFontMetrics(l.font).getHeight();
      return Math.max(40, h + PAD);
    }

    private List<Line> lines(int width) {
      List<Line> out = new ArrayList<>();
      int w = Math.max(40, width - PAD * 2);
      QuestDef q = QuestHandler.QUEST;
      if (q == null) {
        wrap(out, "AIOQuester", F_TITLE, PURPLE, w, 2);
        String status = QuestHandler.QUEUE_STATUS;
        wrap(out, status != null ? status : "Choose quests in the setup window", F_SMALL, WHITE, w, 4);
        return out;
      }
      wrap(out, q.getName(), F_TITLE, PURPLE, w, 3);
      addSteps(out, q, w);
      String step = QuestHandler.CURRENT_QUEST_STEP;
      if (step != null) wrap(out, "Now: " + step, F_STEP, YELLOW, w, 6);
      addPrepItems(out, q, w);
      String status = QuestHandler.QUEUE_STATUS;
      if (status != null) wrap(out, status, F_SMALL, DIM, w, 3);
      try {
        int x = QuestHandler.c.currentX(), y = QuestHandler.c.currentY();
        DraynorManor.Area a = DraynorManor.areaOf(x, y);
        String where = a.inManor() ? a.label : x + "," + y + " floor " + DraynorManor.floorOf(y);
        wrap(out, where, F_SMALL, a.inManor() ? CYAN : DIM, w, 2);
      } catch (RuntimeException ignored) {
        // not logged in
      }
      if (q.getStagesTotal() > 0) out.add(BAR);
      out.add(new Line("Run time " + QuestHandler.questRunTime(), F_SMALL, WHITE, true));
      int[][] items = QuestHandler.STEP_ITEMS;
      if (items != null) {
        for (int[] it : items) {
          if (it == null || it.length < 2) continue;
          String name;
          int have;
          try {
            name = QuestHandler.c.getItemName(it[0]);
            have = QuestHandler.c.getInventoryItemCount(it[0]);
          } catch (RuntimeException e) {
            continue;
          }
          if (name == null) name = "item " + it[0];
          if (!name.isEmpty()) name = name.substring(0, 1).toUpperCase() + name.substring(1);
          String amount = have + "/" + it[1];
          Color col = have >= it[1] ? GREEN : have == 0 ? RED : YELLOW;
          FontMetrics fm = getFontMetrics(F_SMALL);
          name = ellipsize(name, fm, w - fm.stringWidth(amount) - 6);
          out.add(new Line(name, F_SMALL, col, false, amount, col));
        }
      }
      return out;
    }

    private void addSteps(List<Line> out, QuestDef q, int w) {
      String[] outline = QuestSteps.of(q.getName());
      if (outline == null || outline.length == 0) return;
      int current = QuestSteps.currentIndex(q.getName(), QuestHandler.QUEST_STAGE);
      boolean finished = current >= outline.length;
      if (!QuestHandler.showQuestSteps) {
        wrap(out, outline[Math.min(current, outline.length - 1)], F_STEP, YELLOW, w, 4);
        return;
      }
      for (int i = 0; i < outline.length; i++) {
        boolean done = finished || i < current;
        Color col = done ? GREEN : i == current ? YELLOW : DIM;
        String mark = done ? "\u2714 " : i == current ? "\u25B6 " : "\u00B7 ";
        wrap(out, mark + outline[i], F_SMALL, col, w, 3);
      }
    }

    private void addPrepItems(List<Line> out, QuestDef q, int w) {
      java.util.List<scripting.idlescript.SeattaScript.AIOQuester.models.QuestRequirement> items =
          QuestSupplies.beforeStart(q);
      if (items.isEmpty()) return;
      wrap(out, "Before starting", F_SMALL, CYAN, w, 1);
      for (scripting.idlescript.SeattaScript.AIOQuester.models.QuestRequirement req : items) {
        int have;
        try {
          have = QuestSupplies.carried(req);
        } catch (RuntimeException e) {
          continue;
        }
        String amount = have + "/" + req.amount;
        Color col = have >= req.amount ? GREEN : have == 0 ? RED : YELLOW;
        FontMetrics fm = getFontMetrics(F_SMALL);
        String name = ellipsize(req.label, fm, w - fm.stringWidth(amount) - 6);
        out.add(new Line(name, F_SMALL, col, false, amount, col));
      }
    }

    private void wrap(List<Line> out, String text, Font f, Color col, int w, int maxLines) {
      FontMetrics fm = getFontMetrics(f);
      String[] words = text.trim().split("\\s+");
      StringBuilder cur = new StringBuilder();
      int n = 0;
      for (int i = 0; i < words.length; i++) {
        String word = words[i];
        String tryLine = cur.length() == 0 ? word : cur + " " + word;
        if (fm.stringWidth(tryLine) <= w || cur.length() == 0) {
          cur.setLength(0);
          cur.append(tryLine);
          continue;
        }
        if (++n >= maxLines) {
          StringBuilder rest = new StringBuilder(cur);
          for (int j = i; j < words.length; j++) rest.append(' ').append(words[j]);
          out.add(new Line(ellipsize(rest.toString(), fm, w), f, col, true));
          return;
        }
        out.add(new Line(ellipsize(cur.toString(), fm, w), f, col, true));
        cur.setLength(0);
        cur.append(word);
      }
      if (cur.length() > 0) out.add(new Line(ellipsize(cur.toString(), fm, w), f, col, true));
    }

    private static String ellipsize(String s, FontMetrics fm, int w) {
      if (fm.stringWidth(s) <= w) return s;
      String dots = "...";
      int end = s.length();
      while (end > 0 && fm.stringWidth(s.substring(0, end) + dots) > w) end--;
      return s.substring(0, end) + dots;
    }

    @Override
    protected void paintComponent(Graphics g0) {
      Graphics2D g = (Graphics2D) g0.create();
      try {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        g.setColor(DARK);
        g.fillRect(0, 0, w, h);
        g.setColor(PURPLE);
        g.drawRect(0, 0, w - 1, h - 1);
        int y = PAD;
        QuestDef q = QuestHandler.QUEST;
        for (Line l : lines(w)) {
          if (l == BAR) {
            drawBar(g, q, PAD + 2, y + 1, w - PAD * 2 - 4, BAR_H);
            y += BAR_H + 3;
            continue;
          }
          g.setFont(l.font);
          FontMetrics fm = g.getFontMetrics();
          int base = y + fm.getAscent();
          g.setColor(l.color);
          int x = l.centered ? (w - fm.stringWidth(l.text)) / 2 : PAD;
          g.drawString(l.text, x, base);
          if (l.right != null) {
            g.setColor(l.rightColor);
            g.drawString(l.right, w - PAD - fm.stringWidth(l.right), base);
          }
          y += fm.getHeight();
        }
      } finally {
        g.dispose();
      }
    }

    private void drawBar(Graphics2D g, QuestDef q, int x, int y, int w, int h) {
      if (q == null) return;
      int total = q.getStagesTotal() + 1;
      int stage = QuestHandler.QUEST_STAGE == -1 ? total : Math.max(0, QuestHandler.QUEST_STAGE);
      g.setColor(DARKER);
      g.fillRect(x, y, w, h);
      g.setColor(GREEN);
      g.fillRect(x, y, (int) (w * Math.min(1.0, stage / (double) total)), h);
      g.setColor(DARK);
      g.drawRect(x, y, w - 1, h - 1);
      g.setFont(F_SMALL);
      FontMetrics fm = g.getFontMetrics();
      String s = "Stage " + (QuestHandler.QUEST_STAGE == -1 ? "done" : stage + "/" + (total - 1));
      g.setColor(WHITE);
      g.drawString(s, x + (w - fm.stringWidth(s)) / 2, y + (h + fm.getAscent() - fm.getDescent()) / 2);
    }
  }
}
