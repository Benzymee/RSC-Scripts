package scripting.idlescript.ultimate.ui;

import bot.Main;
import bot.ui.SidePanel;
import controller.Controller;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.plaf.basic.BasicScrollBarUI;
import models.entities.SkillId;
import scripting.idlescript.ultimate.SessionStats;
import scripting.idlescript.ultimate.World;
import scripting.idlescript.ultimate.XpTable;

/**
 * Script paint docked under the IdleRSC side buttons. Custom-painted in the client's dark-grey
 * look (not plain Swing tables). Supports pop-out / dock and widens the sidebar while docked.
 */
public final class AnalyticsPanel extends JPanel {
  private static final int DOCK_WIDTH = SidePanel.EXPANDED_DOCK_WIDTH;

  // Dark grey matching IdleRSC / RuneDark side panel (falls back if theme colors missing).
  private static final Color FALLBACK_BG = new Color(40, 40, 40);
  private static final Color FALLBACK_PANEL = new Color(52, 52, 52);
  private static final Color FALLBACK_LIGHT = new Color(70, 70, 70);
  private static final Color BORDER_OUTER = new Color(20, 20, 20);
  private static final Color BORDER_ACCENT = new Color(180, 180, 180);
  private static final Color BORDER_INNER = new Color(90, 90, 90);
  private static final Color TEXT_GOLD = new Color(0xFFE066);
  private static final Color TEXT_YELLOW = new Color(0xFFFF00);
  private static final Color TEXT_WHITE = new Color(0xE8E8E8);
  private static final Color TEXT_DIM = new Color(0xB0B0B0);
  private static final Color BAR_BG = new Color(24, 24, 24);
  private static final Color BAR_FILL = new Color(0x3D8B3D);
  private static final Color BAR_FILL_HI = new Color(0x6BCF6B);
  private static final Color ACCENT_RED = new Color(0xC44);
  private static final Font FONT_TITLE = new Font(Font.DIALOG, Font.BOLD, 12);
  private static final Font FONT_SECTION = new Font(Font.DIALOG, Font.BOLD, 11);
  private static final Font FONT_BODY = new Font(Font.DIALOG, Font.PLAIN, 11);
  private static final Font FONT_SMALL = new Font(Font.DIALOG, Font.PLAIN, 10);

  private static Color bgDark() {
    return Main.primaryBG != null ? Main.primaryBG : FALLBACK_BG;
  }

  private static Color bgMid() {
    return Main.secondaryBG != null ? Main.secondaryBG : FALLBACK_PANEL;
  }

  private static Color bgLight() {
    Color mid = bgMid();
    return new Color(
        Math.min(255, mid.getRed() + 22),
        Math.min(255, mid.getGreen() + 22),
        Math.min(255, mid.getBlue() + 22));
  }

  private final PaintCanvas canvas = new PaintCanvas();
  private final JScrollPane scroll;
  private final PopButton pop = new PopButton("Pop out");
  private JFrame frame;
  private boolean popped;
  private boolean expanded;
  private boolean installed;
  private volatile Snapshot snapshot = Snapshot.empty();

  public boolean isInstalled() {
    return installed;
  }

  public AnalyticsPanel() {
    super(new BorderLayout(0, 0));
    setOpaque(true);
    setBackground(bgDark());
    setPreferredSize(new Dimension(DOCK_WIDTH - 12, 420));
    setMaximumSize(new Dimension(DOCK_WIDTH, Integer.MAX_VALUE));
    setMinimumSize(new Dimension(120, 120));

    scroll = new JScrollPane(canvas);
    scroll.setBorder(null);
    scroll.setOpaque(false);
    scroll.getViewport().setOpaque(false);
    scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
    styleScrollBar(scroll.getVerticalScrollBar());

    JPanel header = new JPanel(new BorderLayout());
    header.setOpaque(true);
    header.setBackground(bgMid());
    header.setBorder(
        javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_ACCENT),
            javax.swing.BorderFactory.createEmptyBorder(4, 4, 4, 4)));
    header.add(pop, BorderLayout.CENTER);
    pop.addActionListener(event -> toggle());

    add(header, BorderLayout.NORTH);
    add(scroll, BorderLayout.CENTER);
  }

  public void install() {
    if (installed) return;
    installed = true; // claim immediately so the script tick does not re-queue expand
    final AnalyticsPanel self = this;
    Runnable apply =
        new Runnable() {
          public void run() {
            try {
              // Expand first so the dock host is wide enough before we attach — otherwise the
              // paint paints past the window edge and looks "stuck" on the right.
              SidePanel.expandForScriptDock(DOCK_WIDTH);
              expanded = true;
              SidePanel.setScriptDock(self);
            } catch (NoSuchMethodError | NoClassDefFoundError error) {
              expanded = false;
              popOut();
            }
          }
        };
    if (SwingUtilities.isEventDispatchThread()) apply.run();
    else SwingUtilities.invokeLater(apply);
  }

  public void refresh(World world) {
    final Snapshot next = Snapshot.from(world);
    SwingUtilities.invokeLater(
        new Runnable() {
          public void run() {
            snapshot = next;
            canvas.recompute();
            canvas.repaint();
          }
        });
  }

  public void toggle() {
    if (popped) dock();
    else popOut();
  }

  private void popOut() {
    if (frame == null) {
      frame = new JFrame("Benzyme's AIO Trainer");
      frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
      frame.setSize(360, 640);
      frame.setMinimumSize(new Dimension(280, 360));
      frame.getContentPane().setBackground(bgDark());
      frame.addWindowListener(
          new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
              if (popped) dock();
            }
          });
    }
    frame.getContentPane().removeAll();
    frame.getContentPane().add(this, BorderLayout.CENTER);
    pop.setCaption("Dock");
    popped = true;
    frame.setVisible(true);
    frame.revalidate();
    SidePanel.clearScriptDock();
    restoreWidth();
  }

  private void dock() {
    popped = false;
    pop.setCaption("Pop out");
    if (frame != null) {
      frame.setVisible(false);
      frame.getContentPane().removeAll();
    }
    try {
      SidePanel.expandForScriptDock(DOCK_WIDTH);
      expanded = true;
      SidePanel.setScriptDock(this);
    } catch (NoSuchMethodError | NoClassDefFoundError error) {
      expanded = false;
      if (frame != null) {
        frame.getContentPane().add(this, BorderLayout.CENTER);
        frame.setVisible(true);
        popped = true;
        pop.setCaption("Dock");
      }
    }
  }

  public void dispose() {
    popped = false;
    installed = false;
    SidePanel.clearScriptDock();
    restoreWidth();
    if (frame != null) {
      frame.dispose();
      frame = null;
    }
  }

  private void restoreWidth() {
    if (!expanded) return;
    try {
      SidePanel.restoreScriptDockWidth();
    } catch (NoSuchMethodError | NoClassDefFoundError ignored) {
      // Stock client without the expand API.
    }
    expanded = false;
  }

  private static void styleScrollBar(JScrollBar bar) {
    bar.setUnitIncrement(16);
    bar.setPreferredSize(new Dimension(12, 0));
    bar.setUI(
        new BasicScrollBarUI() {
          @Override
          protected void configureScrollBarColors() {
            this.thumbColor = bgLight();
            this.trackColor = bgDark();
          }

          @Override
          protected JButton createDecreaseButton(int orientation) {
            return zeroButton();
          }

          @Override
          protected JButton createIncreaseButton(int orientation) {
            return zeroButton();
          }

          private JButton zeroButton() {
            JButton b = new JButton();
            b.setPreferredSize(new Dimension(0, 0));
            b.setMinimumSize(new Dimension(0, 0));
            b.setMaximumSize(new Dimension(0, 0));
            return b;
          }

          @Override
          protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
            g.setColor(bgDark());
            g.fillRect(trackBounds.x, trackBounds.y, trackBounds.width, trackBounds.height);
            g.setColor(BORDER_OUTER);
            g.drawRect(trackBounds.x, trackBounds.y, trackBounds.width - 1, trackBounds.height - 1);
          }

          @Override
          protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds) {
            if (thumbBounds.isEmpty() || !scrollbar.isEnabled()) return;
            g.setColor(bgLight());
            g.fillRect(thumbBounds.x + 1, thumbBounds.y + 1, thumbBounds.width - 2, thumbBounds.height - 2);
            g.setColor(BORDER_ACCENT);
            g.drawRect(thumbBounds.x + 1, thumbBounds.y + 1, thumbBounds.width - 3, thumbBounds.height - 3);
          }
        });
  }

  /** Offscreen RSC paint mockup for screenshots / artifacts. */
  public static void writeMockupPng(File file) throws Exception {
    AnalyticsPanel panel = new AnalyticsPanel();
    panel.snapshot = Snapshot.mock();
    panel.canvas.recompute();
    int width = 248;
    int height = 560;
    panel.setSize(width, height);
    panel.setPreferredSize(new Dimension(width, height));
    // Force a real layout pass so the header + scroll viewport get non-zero bounds.
    panel.addNotify();
    panel.validate();
    panel.doLayout();
    if (panel.scroll != null) {
      panel.scroll.setSize(width, height - 30);
      panel.scroll.doLayout();
      panel.scroll.getViewport().setViewSize(panel.canvas.getPreferredSize());
    }
    panel.canvas.setSize(panel.canvas.getPreferredSize());
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    g.setColor(bgDark());
    g.fillRect(0, 0, width, height);
    // Paint header strip + button, then the canvas content directly (reliable headless).
    g.setColor(bgMid());
    g.fillRect(0, 0, width, 30);
    g.setColor(BORDER_ACCENT);
    g.drawLine(0, 29, width, 29);
    g.setFont(FONT_SECTION);
    g.setColor(TEXT_GOLD);
    g.drawString("Pop out", 12, 20);
    Graphics2D cg = (Graphics2D) g.create(0, 30, width, height - 30);
    panel.canvas.paint(cg);
    cg.dispose();
    g.dispose();
    File parent = file.getParentFile();
    if (parent != null && !parent.exists()) parent.mkdirs();
    ImageIO.write(image, "png", file);
  }

  // ---------------------------------------------------------------------------
  // Snapshot / canvas
  // ---------------------------------------------------------------------------

  private static final class SkillRow {
    final String name;
    final String abbrev;
    final int level;
    final int gained;
    final int xpGained;
    final int xpInto;
    final int xpSpan;
    final int xpLeft;
    final boolean highlight;
    final Color badge;

    SkillRow(
        String name,
        String abbrev,
        int level,
        int gained,
        int xpGained,
        int xpInto,
        int xpSpan,
        int xpLeft,
        boolean highlight,
        Color badge) {
      this.name = name;
      this.abbrev = abbrev;
      this.level = level;
      this.gained = gained;
      this.xpGained = xpGained;
      this.xpInto = xpInto;
      this.xpSpan = xpSpan;
      this.xpLeft = xpLeft;
      this.highlight = highlight;
      this.badge = badge;
    }
  }

  private static final class Snapshot {
    final String activity;
    final String status;
    final String mode;
    final String style;
    final String server;
    final String runtime;
    final String timeLeft;
    final long profit;
    final String gpHour;
    final long earned;
    final long spent;
    final int lootCount;
    final long lootValue;
    final int deaths;
    final int coins;
    final List<SkillRow> skills;
    final List<String> lootLines;

    Snapshot(
        String activity,
        String status,
        String mode,
        String style,
        String server,
        String runtime,
        String timeLeft,
        long profit,
        String gpHour,
        long earned,
        long spent,
        int lootCount,
        long lootValue,
        int deaths,
        int coins,
        List<SkillRow> skills,
        List<String> lootLines) {
      this.activity = activity;
      this.status = status;
      this.mode = mode;
      this.style = style;
      this.server = server;
      this.runtime = runtime;
      this.timeLeft = timeLeft;
      this.profit = profit;
      this.gpHour = gpHour;
      this.earned = earned;
      this.spent = spent;
      this.lootCount = lootCount;
      this.lootValue = lootValue;
      this.deaths = deaths;
      this.coins = coins;
      this.skills = skills;
      this.lootLines = lootLines;
    }

    static Snapshot empty() {
      return new Snapshot(
          "Waiting",
          "Waiting for the first tick.",
          "—",
          "—",
          "—",
          "0s",
          "—",
          0,
          "0",
          0,
          0,
          0,
          0,
          0,
          0,
          new ArrayList<SkillRow>(),
          new ArrayList<String>());
    }

    static Snapshot mock() {
      List<SkillRow> skills = new ArrayList<SkillRow>();
      skills.add(row("Woodcutting", "WC", 47, 2, 18420, 3200, 8000, 4800, true));
      skills.add(row("Fishing", "FI", 38, 1, 4200, 1100, 5000, 3900, false));
      skills.add(row("Attack", "AT", 55, 0, 900, 2000, 12000, 10000, false));
      skills.add(row("Hits", "HT", 54, 0, 600, 1500, 11000, 9500, false));
      List<String> loot = new ArrayList<String>();
      loot.add("12 Logs");
      loot.add("4 Oak logs");
      loot.add("2 Coins");
      return new Snapshot(
          "Woodcutting",
          "Chopping oak",
          "Training",
          "Controlled",
          "Coleslaw",
          "42m 18s",
          "12m 04s",
          18450,
          "26k",
          21000,
          2550,
          18,
          420,
          0,
          3250,
          skills,
          loot);
    }

    private static SkillRow row(
        String name,
        String abbrev,
        int level,
        int gained,
        int xpGained,
        int into,
        int span,
        int left,
        boolean hi) {
      return new SkillRow(
          name, abbrev, level, gained, xpGained, into, span, left, hi, badgeColor(name));
    }

    static Snapshot from(World world) {
      SessionStats stats = world.stats;
      long ms = stats.runtimeMs();
      double hours = Math.max(ms / 3600000.0, 1.0 / 3600.0);
      long profit = stats.coinsEarned - stats.coinsSpent;
      List<SkillRow> skills = new ArrayList<SkillRow>();
      Controller c = world.c;
      for (SkillId skill : SkillId.values()) {
        int id = skill.getId();
        if (id < 0 || id >= stats.startXp.length) continue;
        int level = c.getBaseStat(id);
        int gainedLevels = level - stats.startLevel[id];
        int gainedXp = c.getPlayerExperience(id) - stats.startXp[id];
        boolean hi = skill == world.highlight;
        if (gainedXp == 0 && gainedLevels == 0 && id > 7 && !hi) continue;
        int[] prog = XpTable.progress(level, c.getPlayerExperience(id));
        String label = pretty(skill.name());
        skills.add(
            new SkillRow(
                label,
                abbrev(label),
                level,
                gainedLevels,
                gainedXp,
                prog[0],
                prog[1],
                prog[2],
                hi,
                badgeColor(label)));
      }
      List<String> lootLines = new ArrayList<String>();
      int shown = 0;
      for (Map.Entry<String, Integer> entry : stats.loot.entrySet()) {
        lootLines.add(entry.getValue() + " " + entry.getKey());
        if (++shown >= 10) break;
      }
      return new Snapshot(
          world.activityName == null ? "—" : world.activityName,
          world.status == null ? "" : world.status,
          world.preparing ? "Preparing" : "Training",
          world.styleName == null ? "—" : world.styleName,
          world.coleslaw() ? "Coleslaw" : "Uranium",
          SessionStats.formatDuration(ms),
          SessionStats.formatDuration(world.millisLeft),
          profit,
          perHour(profit, hours),
          stats.coinsEarned,
          stats.coinsSpent,
          stats.lootCount,
          stats.lootValue,
          stats.deaths,
          world.invCoins(),
          skills,
          lootLines);
    }

    private static String pretty(String name) {
      String lower = name.toLowerCase();
      if (lower.equals("hits")) return "Hits";
      if (lower.equals("runecraft")) return "Runecraft";
      StringBuilder out = new StringBuilder();
      boolean cap = true;
      for (int i = 0; i < name.length(); i++) {
        char ch = name.charAt(i);
        if (ch == '_') {
          out.append(' ');
          cap = true;
          continue;
        }
        out.append(cap ? Character.toUpperCase(ch) : Character.toLowerCase(ch));
        cap = false;
      }
      return out.toString();
    }

    private static String abbrev(String name) {
      String clean = name.replace(" ", "");
      if (clean.length() <= 2) return clean.toUpperCase();
      return clean.substring(0, 2).toUpperCase();
    }

    private static Color badgeColor(String name) {
      String n = name.toLowerCase();
      if (n.contains("attack")) return new Color(0xC04040);
      if (n.contains("defense") || n.contains("defence")) return new Color(0x4080C0);
      if (n.contains("strength")) return new Color(0x40A040);
      if (n.contains("hits") || n.contains("hitpoint")) return new Color(0xC07070);
      if (n.contains("ranged")) return new Color(0x70A040);
      if (n.contains("prayer")) return new Color(0xC0C0C0);
      if (n.contains("magic")) return new Color(0x6060C0);
      if (n.contains("cook")) return new Color(0xC08040);
      if (n.contains("wood")) return new Color(0x6B8E23);
      if (n.contains("fletch")) return new Color(0x8B7355);
      if (n.contains("fish")) return new Color(0x4080A0);
      if (n.contains("fire")) return new Color(0xC06020);
      if (n.contains("craft")) return new Color(0xA07040);
      if (n.contains("smith")) return new Color(0x808080);
      if (n.contains("min")) return new Color(0x708090);
      if (n.contains("herb")) return new Color(0x50A050);
      if (n.contains("agil")) return new Color(0x40A0A0);
      if (n.contains("thiev")) return new Color(0x705090);
      if (n.contains("rune")) return new Color(0xA09040);
      if (n.contains("harvest")) return new Color(0x90A040);
      return bgLight();
    }

    private static String perHour(long amount, double hours) {
      long value = (long) (amount / hours);
      if (Math.abs(value) >= 1000000) return (value / 1000000) + "m";
      if (Math.abs(value) >= 1000) return (value / 1000) + "k";
      return Long.toString(value);
    }
  }

  private final class PaintCanvas extends JPanel {
    private int contentHeight = 400;

    PaintCanvas() {
      setOpaque(true);
      setBackground(bgDark());
      setPreferredSize(new Dimension(DOCK_WIDTH - 24, 400));
    }

    void recompute() {
      Snapshot s = snapshot;
      int h = 16; // top pad
      h += 28; // title
      h += 78; // activity box
      h += 72; // gp box
      h += 18; // skills header
      h += Math.max(1, s.skills.size()) * 40;
      h += 18; // loot header
      h += Math.max(1, s.lootLines.size()) * 14 + 8;
      h += 36; // footer
      contentHeight = h;
      setPreferredSize(new Dimension(DOCK_WIDTH - 24, contentHeight));
      revalidate();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      super.paintComponent(graphics);
      Graphics2D g = (Graphics2D) graphics.create();
      g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

      int w = getWidth();
      int h = Math.max(getHeight(), contentHeight);
      // stone gradient fill
      Color dark = bgDark();
      Color mid = bgMid();
      g.setColor(dark);
      g.fillRect(0, 0, w, h);
      // subtle vertical shade toward mid grey
      for (int y = 0; y < h; y += 3) {
        float t = y / (float) Math.max(1, h);
        int r = (int) (dark.getRed() + (mid.getRed() - dark.getRed()) * t * 0.45f);
        int gg = (int) (dark.getGreen() + (mid.getGreen() - dark.getGreen()) * t * 0.45f);
        int b = (int) (dark.getBlue() + (mid.getBlue() - dark.getBlue()) * t * 0.45f);
        g.setColor(new Color(r, gg, b, 90));
        g.drawLine(0, y, w, y);
      }

      Snapshot s = snapshot;
      int x = 8;
      int y = 10;
      int inner = w - 16;

      // Title banner
      drawPanel(g, x, y, inner, 24);
      g.setFont(FONT_TITLE);
      g.setColor(TEXT_GOLD);
      centerText(g, "Benzyme's AIO Trainer", x, y, inner, 24);
      y += 30;

      // Activity section
      drawPanel(g, x, y, inner, 72);
      int cy = y + 14;
      g.setFont(FONT_SECTION);
      g.setColor(TEXT_YELLOW);
      g.drawString("Activity", x + 8, cy);
      g.setFont(FONT_BODY);
      g.setColor(TEXT_WHITE);
      g.drawString(clip(g, s.activity, inner - 16), x + 8, cy + 14);
      g.setColor(TEXT_DIM);
      g.drawString("Swap in  " + s.timeLeft, x + 8, cy + 28);
      g.drawString(s.mode + "  ·  " + s.server, x + 8, cy + 42);
      g.setColor(TEXT_WHITE);
      String status = s.status == null ? "" : s.status;
      g.drawString(clip(g, status, inner - 16), x + 8, cy + 56);
      y += 78;

      // GP / session
      drawPanel(g, x, y, inner, 66);
      cy = y + 14;
      g.setFont(FONT_SECTION);
      g.setColor(TEXT_YELLOW);
      g.drawString("Session", x + 8, cy);
      g.setFont(FONT_BODY);
      g.setColor(TEXT_WHITE);
      g.drawString("Runtime  " + s.runtime, x + 8, cy + 14);
      g.drawString("Coins  " + s.coins, x + 8, cy + 28);
      Color profitColor = s.profit >= 0 ? BAR_FILL_HI : ACCENT_RED;
      g.setColor(profitColor);
      g.drawString("Profit  " + s.profit + " gp  (" + s.gpHour + "/hr)", x + 8, cy + 42);
      g.setColor(TEXT_DIM);
      g.setFont(FONT_SMALL);
      g.drawString(
          "Earned " + s.earned + "  Spent " + s.spent + "  Deaths " + s.deaths, x + 8, cy + 56);
      y += 72;

      // Skills
      g.setFont(FONT_SECTION);
      g.setColor(TEXT_GOLD);
      g.drawString("Skills  —  XP to next level", x + 2, y + 12);
      y += 18;
      if (s.skills.isEmpty()) {
        drawPanel(g, x, y, inner, 28);
        g.setFont(FONT_BODY);
        g.setColor(TEXT_DIM);
        g.drawString("No skill gains yet.", x + 8, y + 18);
        y += 34;
      } else {
        for (SkillRow skill : s.skills) {
          drawPanel(g, x, y, inner, 36);
          drawBadge(g, x + 6, y + 8, skill.abbrev, skill.badge);
          g.setFont(FONT_BODY);
          g.setColor(skill.highlight ? TEXT_YELLOW : TEXT_WHITE);
          g.drawString(skill.name, x + 32, y + 14);
          g.setColor(TEXT_DIM);
          g.setFont(FONT_SMALL);
          String meta =
              "lvl "
                  + skill.level
                  + (skill.gained != 0 ? " (" + signed(skill.gained) + ")" : "")
                  + "  +"
                  + skill.xpGained
                  + " xp";
          g.drawString(meta, x + 32, y + 26);
          float pct = skill.xpSpan <= 0 ? 1f : skill.xpInto / (float) skill.xpSpan;
          int barW = Math.min(100, Math.max(60, inner / 3));
          int barX = x + inner - barW - 8;
          int barY = y + 8;
          if (barX < x + 120) {
            barX = x + 8;
            barY = y + 28;
            barW = inner - 16;
            drawBar(g, barX, barY, barW, 4, pct);
          } else {
            drawBar(g, barX, barY, barW, 10, pct);
            g.setFont(FONT_SMALL);
            g.setColor(TEXT_DIM);
            g.drawString(skill.xpLeft + " to lvl", barX, barY + 22);
          }
          y += 40;
        }
      }

      // Loot
      g.setFont(FONT_SECTION);
      g.setColor(TEXT_GOLD);
      g.drawString("Loot  (" + s.lootCount + " / " + s.lootValue + " gp)", x + 2, y + 12);
      y += 18;
      int lootH = Math.max(22, s.lootLines.size() * 14 + 10);
      drawPanel(g, x, y, inner, lootH);
      g.setFont(FONT_BODY);
      if (s.lootLines.isEmpty()) {
        g.setColor(TEXT_DIM);
        g.drawString("No loot yet.", x + 8, y + 16);
      } else {
        int ly = y + 14;
        for (String line : s.lootLines) {
          g.setColor(TEXT_WHITE);
          g.drawString(clip(g, line, inner - 16), x + 8, ly);
          ly += 14;
        }
      }
      y += lootH + 8;

      g.setFont(FONT_SMALL);
      g.setColor(TEXT_DIM);
      g.drawString("::paint  ::popout  ::dock", x + 2, y + 12);
      g.dispose();
    }

    private void drawPanel(Graphics2D g, int x, int y, int w, int h) {
      g.setColor(bgMid());
      g.fillRect(x, y, w, h);
      g.setColor(BORDER_OUTER);
      g.drawRect(x, y, w - 1, h - 1);
      g.setColor(BORDER_ACCENT);
      g.drawRect(x + 1, y + 1, w - 3, h - 3);
      g.setColor(BORDER_INNER);
      g.drawRect(x + 2, y + 2, w - 5, h - 5);
      // subtle top highlight
      g.setColor(new Color(255, 255, 255, 18));
      g.drawLine(x + 3, y + 3, x + w - 4, y + 3);
    }

    private void drawBadge(Graphics2D g, int x, int y, String text, Color fill) {
      g.setColor(BORDER_OUTER);
      g.fillRoundRect(x, y, 22, 20, 4, 4);
      g.setColor(fill);
      g.fillRoundRect(x + 1, y + 1, 20, 18, 4, 4);
      g.setColor(BORDER_ACCENT);
      g.setStroke(new BasicStroke(1f));
      g.drawRoundRect(x + 1, y + 1, 20, 18, 4, 4);
      g.setFont(FONT_SMALL);
      g.setColor(TEXT_WHITE);
      FontMetrics fm = g.getFontMetrics();
      int tx = x + 1 + (20 - fm.stringWidth(text)) / 2;
      int ty = y + 1 + (18 + fm.getAscent() - fm.getDescent()) / 2;
      g.drawString(text, tx, ty);
    }

    private void drawBar(Graphics2D g, int x, int y, int w, int h, float pct) {
      if (pct < 0) pct = 0;
      if (pct > 1) pct = 1;
      g.setColor(BAR_BG);
      g.fillRect(x, y, w, h);
      g.setColor(BORDER_OUTER);
      g.drawRect(x, y, w - 1, h - 1);
      int fill = Math.max(0, (int) ((w - 2) * pct));
      if (fill > 0) {
        g.setColor(BAR_FILL);
        g.fillRect(x + 1, y + 1, fill, h - 2);
        g.setColor(BAR_FILL_HI);
        g.drawLine(x + 1, y + 1, x + fill, y + 1);
      }
      g.setColor(BORDER_ACCENT);
      g.drawRect(x, y, w - 1, h - 1);
    }

    private void centerText(Graphics2D g, String text, int x, int y, int w, int h) {
      FontMetrics fm = g.getFontMetrics();
      int tx = x + (w - fm.stringWidth(text)) / 2;
      int ty = y + (h + fm.getAscent() - fm.getDescent()) / 2 - 1;
      g.drawString(text, tx, ty);
    }

    private String clip(Graphics2D g, String text, int maxW) {
      if (text == null) return "";
      FontMetrics fm = g.getFontMetrics();
      if (fm.stringWidth(text) <= maxW) return text;
      String ellipsis = "...";
      int ew = fm.stringWidth(ellipsis);
      for (int i = text.length() - 1; i > 0; i--) {
        if (fm.stringWidth(text.substring(0, i)) + ew <= maxW) {
          return text.substring(0, i) + ellipsis;
        }
      }
      return ellipsis;
    }

    private String signed(int value) {
      return value > 0 ? "+" + value : Integer.toString(value);
    }
  }

  /** Gold-bordered stone button matching the paint. */
  private static final class PopButton extends JButton {
    private String caption;

    PopButton(String caption) {
      this.caption = caption;
      setOpaque(false);
      setContentAreaFilled(false);
      setBorderPainted(false);
      setFocusPainted(false);
      setPreferredSize(new Dimension(100, 22));
      setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
      addMouseListener(
          new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
              repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
              repaint();
            }
          });
    }

    public void setCaption(String caption) {
      this.caption = caption;
      repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      Graphics2D g = (Graphics2D) graphics.create();
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      boolean hover = getModel().isRollover();
      int w = getWidth();
      int h = getHeight();
      g.setColor(hover ? bgLight() : bgMid());
      g.fillRect(0, 0, w, h);
      g.setColor(BORDER_OUTER);
      g.drawRect(0, 0, w - 1, h - 1);
      g.setColor(BORDER_ACCENT);
      g.drawRect(1, 1, w - 3, h - 3);
      g.setFont(FONT_SECTION);
      g.setColor(TEXT_GOLD);
      FontMetrics fm = g.getFontMetrics();
      int tx = (w - fm.stringWidth(caption)) / 2;
      int ty = (h + fm.getAscent() - fm.getDescent()) / 2 - 1;
      g.drawString(caption, tx, ty);
      g.dispose();
    }

    @Override
    public Insets getInsets() {
      return new Insets(2, 6, 2, 6);
    }
  }
}
