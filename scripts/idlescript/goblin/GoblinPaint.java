package scripting.idlescript.goblin;

import bot.Main;
import bot.ui.SidePanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.NumberFormat;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * Small report for Benzyme's Goblin Killer, docked under the side buttons through the same
 * SidePanel dock API as the AIO Trainer's AnalyticsPanel, and painted in the same stone / gold
 * style. Supports pop out / dock (button or ::paint).
 */
public final class GoblinPaint extends JPanel {
  private static final int DOCK_WIDTH = SidePanel.EXPANDED_DOCK_WIDTH;
  private static final String TITLE = "Benzyme's Goblin Killer";

  // Same palette as scripting.idlescript.ultimate.ui.AnalyticsPanel.
  private static final Color FALLBACK_BG = new Color(40, 40, 40);
  private static final Color FALLBACK_PANEL = new Color(52, 52, 52);
  private static final Color BORDER_OUTER = new Color(20, 20, 20);
  private static final Color BORDER_ACCENT = new Color(180, 180, 180);
  private static final Color BORDER_INNER = new Color(90, 90, 90);
  private static final Color TEXT_GOLD = new Color(0xFFE066);
  private static final Color TEXT_YELLOW = new Color(0xFFFF00);
  private static final Color TEXT_WHITE = new Color(0xE8E8E8);
  private static final Color TEXT_DIM = new Color(0xB0B0B0);
  private static final Color GOOD = new Color(0x6BCF6B);
  private static final Color ACCENT_RED = new Color(0xCC4444);
  private static final Font FONT_TITLE = new Font(Font.DIALOG, Font.BOLD, 12);
  private static final Font FONT_SECTION = new Font(Font.DIALOG, Font.BOLD, 11);
  private static final Font FONT_BODY = new Font(Font.DIALOG, Font.PLAIN, 11);
  private static final Font FONT_SMALL = new Font(Font.DIALOG, Font.PLAIN, 10);
  private static final int CONTENT_HEIGHT = 276;

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

  /** Immutable values shown by the paint; built on the script thread. */
  public static final class Snapshot {
    public String runtime = "00:00:00";
    public String status = "Starting";
    public String style = "Controlled";
    public boolean bury = true;
    public int kills;
    public int killsHour;
    public int deaths;
    /** Deaths after which every remembered worn item was put back on. */
    public int regeared;
    /** Worn items that were not found after a death (empty when none). */
    public String lost = "";
    public int buried;
    public int combatXp;
    public int combatXpHour;
    public int prayerXp;
    public int prayerXpHour;
  }

  private final Canvas canvas = new Canvas();
  private final PopButton pop = new PopButton("Pop out");
  private volatile Snapshot snapshot = new Snapshot();
  private JFrame frame;
  private boolean popped;
  private boolean expanded;
  private boolean installed;

  public GoblinPaint() {
    super(new BorderLayout(0, 0));
    setOpaque(true);
    setBackground(bgDark());
    setPreferredSize(new Dimension(DOCK_WIDTH - 12, CONTENT_HEIGHT + 32));
    setMaximumSize(new Dimension(DOCK_WIDTH, Integer.MAX_VALUE));
    setMinimumSize(new Dimension(120, 120));
    JPanel header = new JPanel(new BorderLayout());
    header.setOpaque(true);
    header.setBackground(bgMid());
    header.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_ACCENT),
            BorderFactory.createEmptyBorder(4, 4, 4, 4)));
    header.add(pop, BorderLayout.CENTER);
    pop.addActionListener(event -> toggle());
    add(header, BorderLayout.NORTH);
    add(canvas, BorderLayout.CENTER);
  }

  public boolean isInstalled() {
    return installed;
  }

  public void install() {
    if (installed) return;
    installed = true;
    final GoblinPaint self = this;
    Runnable apply =
        () -> {
          try {
            SidePanel.expandForScriptDock(DOCK_WIDTH);
            expanded = true;
            SidePanel.setScriptDock(self);
          } catch (NoSuchMethodError | NoClassDefFoundError error) {
            expanded = false;
            popOut();
          }
        };
    if (SwingUtilities.isEventDispatchThread()) apply.run();
    else SwingUtilities.invokeLater(apply);
  }

  public void refresh(Snapshot next) {
    if (next == null) return;
    SwingUtilities.invokeLater(
        () -> {
          snapshot = next;
          canvas.repaint();
        });
  }

  public void toggle() {
    SwingUtilities.invokeLater(
        () -> {
          if (popped) dock();
          else popOut();
        });
  }

  private void popOut() {
    if (frame == null) {
      frame = new JFrame(TITLE);
      frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
      frame.setSize(280, CONTENT_HEIGHT + 80);
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
    }
  }

  public void dispose() {
    installed = false;
    popped = false;
    Runnable apply =
        () -> {
          SidePanel.clearScriptDock();
          restoreWidth();
          if (frame != null) {
            frame.dispose();
            frame = null;
          }
        };
    if (SwingUtilities.isEventDispatchThread()) apply.run();
    else SwingUtilities.invokeLater(apply);
  }

  private void restoreWidth() {
    if (!expanded) return;
    try {
      SidePanel.restoreScriptDockWidth();
    } catch (NoSuchMethodError | NoClassDefFoundError ignored) {
      // stock client without the expand API
    }
    expanded = false;
  }

  private static String num(int value) {
    return NumberFormat.getIntegerInstance().format(value);
  }

  private final class Canvas extends JPanel {
    Canvas() {
      setOpaque(true);
      setBackground(bgDark());
      setPreferredSize(new Dimension(DOCK_WIDTH - 24, CONTENT_HEIGHT));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      super.paintComponent(graphics);
      Graphics2D g = (Graphics2D) graphics.create();
      g.setRenderingHint(
          RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      int w = getWidth();
      int h = Math.max(getHeight(), CONTENT_HEIGHT);
      Color dark = bgDark();
      Color mid = bgMid();
      g.setColor(dark);
      g.fillRect(0, 0, w, h);
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

      drawPanel(g, x, y, inner, 24);
      g.setFont(FONT_TITLE);
      g.setColor(TEXT_GOLD);
      centerText(g, TITLE, x, y, inner, 24);
      y += 30;

      // Status
      drawPanel(g, x, y, inner, 58);
      int cy = y + 14;
      g.setFont(FONT_SECTION);
      g.setColor(TEXT_YELLOW);
      g.drawString("Lumbridge goblins", x + 8, cy);
      g.setFont(FONT_BODY);
      g.setColor(TEXT_WHITE);
      g.drawString(clip(g, s.status, inner - 16), x + 8, cy + 14);
      g.setColor(TEXT_DIM);
      g.drawString(
          clip(g, s.style + "  ·  " + (s.bury ? "Bury bones" : "Ignore bones"), inner - 16),
          x + 8,
          cy + 28);
      y += 64;

      // Session
      boolean hasLost = s.lost != null && !s.lost.isEmpty();
      int sessionH = hasLost ? 90 : 76;
      drawPanel(g, x, y, inner, sessionH);
      cy = y + 14;
      g.setFont(FONT_SECTION);
      g.setColor(TEXT_YELLOW);
      g.drawString("Session", x + 8, cy);
      g.setFont(FONT_BODY);
      g.setColor(TEXT_WHITE);
      g.drawString("Runtime  " + s.runtime, x + 8, cy + 14);
      g.setColor(GOOD);
      g.drawString("Kills  " + num(s.kills) + "  (" + num(s.killsHour) + "/hr)", x + 8, cy + 28);
      g.setColor(TEXT_WHITE);
      g.drawString("Bones buried  " + num(s.buried), x + 8, cy + 42);
      g.setColor(s.deaths > 0 ? ACCENT_RED : TEXT_DIM);
      g.drawString(
          clip(g, "Deaths / regeared  " + num(s.deaths) + " / " + num(s.regeared), inner - 16),
          x + 8,
          cy + 56);
      if (hasLost) {
        g.setColor(ACCENT_RED);
        g.drawString(clip(g, "Lost: " + s.lost, inner - 16), x + 8, cy + 70);
      }
      y += sessionH + 6;

      // XP
      drawPanel(g, x, y, inner, 48);
      cy = y + 14;
      g.setFont(FONT_SECTION);
      g.setColor(TEXT_YELLOW);
      g.drawString("XP gained", x + 8, cy);
      g.setFont(FONT_BODY);
      g.setColor(TEXT_WHITE);
      g.drawString(
          clip(g, "Combat  " + num(s.combatXp) + "  (" + num(s.combatXpHour) + "/hr)", inner - 16),
          x + 8,
          cy + 14);
      g.drawString(
          clip(g, "Prayer  " + num(s.prayerXp) + "  (" + num(s.prayerXpHour) + "/hr)", inner - 16),
          x + 8,
          cy + 28);
      y += 54;

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
      g.setColor(new Color(255, 255, 255, 18));
      g.drawLine(x + 3, y + 3, x + w - 4, y + 3);
    }

    private void centerText(Graphics2D g, String text, int x, int y, int w, int h) {
      FontMetrics fm = g.getFontMetrics();
      g.drawString(
          text, x + (w - fm.stringWidth(text)) / 2, y + (h + fm.getAscent() - fm.getDescent()) / 2 - 1);
    }

    private String clip(Graphics2D g, String text, int maxW) {
      if (text == null) return "";
      FontMetrics fm = g.getFontMetrics();
      if (fm.stringWidth(text) <= maxW) return text;
      for (int i = text.length() - 1; i > 0; i--) {
        if (fm.stringWidth(text.substring(0, i)) + fm.stringWidth("...") <= maxW) {
          return text.substring(0, i) + "...";
        }
      }
      return "...";
    }
  }

  /** Gold-captioned stone button, same as the trainer's pop-out button. */
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

    void setCaption(String caption) {
      this.caption = caption;
      repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      Graphics2D g = (Graphics2D) graphics.create();
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      int w = getWidth();
      int h = getHeight();
      g.setColor(getModel().isRollover() ? bgLight() : bgMid());
      g.fillRect(0, 0, w, h);
      g.setColor(BORDER_OUTER);
      g.drawRect(0, 0, w - 1, h - 1);
      g.setColor(BORDER_ACCENT);
      g.drawRect(1, 1, w - 3, h - 3);
      g.setFont(FONT_SECTION);
      g.setColor(TEXT_GOLD);
      FontMetrics fm = g.getFontMetrics();
      g.drawString(
          caption, (w - fm.stringWidth(caption)) / 2, (h + fm.getAscent() - fm.getDescent()) / 2 - 1);
      g.dispose();
    }

    @Override
    public Insets getInsets() {
      return new Insets(2, 6, 2, 6);
    }
  }
}
