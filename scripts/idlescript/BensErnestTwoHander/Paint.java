package scripting.idlescript.BensErnestTwoHander;

import controller.Controller;

/** Large dark grey / gold paint in the client's style. */
final class Paint {
  private static final int BG = 0x1D1F27, BG2 = 0x282A36, GOLD = 0xD4AF37, GOLD_DIM = 0x8C7325,
      WHITE = 0xF2F2F2, GREY = 0xB0B0B0, GREEN = 0x50FA7B, RED = 0xFF5555;
  private static final int Y = 34, W = 262;
  private static int X = 6;

  private Paint() {}

  static void draw(BensErnestTwoHander s) {
    Controller c = s.ctl();
    if (c == null || s.waitingForGui()) return;
    // right-hand side, under the HUD icons: the client's combat style menu sits top-left
    try {
      X = Math.max(6, c.getGameWidth() - W - 6);
    } catch (Throwable t) {
      X = 6;
    }
    int rows = 11;
    int h = 40 + rows * 15 + 6;
    c.drawBoxAlpha(X, Y, W, h, BG, 245);
    c.drawBoxAlpha(X, Y, W, 30, BG2, 235);
    c.drawBoxBorder(X, Y, W, h, GOLD);
    c.drawBoxBorder(X + 2, Y + 2, W - 4, h - 4, GOLD_DIM);
    c.drawLineHoriz(X + 2, Y + 30, W - 4, GOLD);
    c.drawCenteredString(BensErnestTwoHander.DISPLAY_NAME, X + W / 2, Y + 15, GOLD, 4);

    int y = Y + 46;
    int stage = s.questStageForPaint();
    row(c, y, "Phase", s.phase().label, GOLD);
    y += 15;
    String st = s.status();
    if (st != null && st.length() > 34) st = st.substring(0, 33) + "~";
    row(c, y, "Doing", st == null ? "" : st, WHITE);
    y += 15;
    row(
        c,
        y,
        "Ernest",
        stage == -1 ? "Complete" : stage == 0 ? "Not started" : "Stage " + stage,
        stage == -1 ? GREEN : WHITE);
    y += 15;
    row(c, y, "Style", s.styleLabel(), WHITE);
    y += 15;
    long ms = s.runtime();
    double hours = Math.max(1e-6, ms / 3_600_000.0);
    for (int i = 0; i < BensErnestTwoHander.SKILLS.length; i++) {
      int gained = s.xpGained(i);
      int lvl = c.getBaseStat(BensErnestTwoHander.SKILLS[i]);
      String v = "Lv " + lvl + "   +" + gained + " xp  (" + (int) (gained / hours) + "/h)";
      row(c, y, BensErnestTwoHander.SKILL_NAMES[i], v, gained > 0 ? GREEN : GREY);
      y += 15;
    }
    row(c, y, "Kills", s.kills() + "  (" + (int) (s.kills() / hours) + "/h)", WHITE);
    y += 15;
    row(c, y, "Deaths", String.valueOf(s.deaths()), s.deaths() > 0 ? RED : WHITE);
    y += 15;
    row(c, y, "Runtime", time(ms), WHITE);
  }

  private static void row(Controller c, int y, String label, String value, int color) {
    c.drawString(label, X + 10, y, GOLD, 2);
    c.drawString(value, X + 82, y, color, 2);
  }

  private static String time(long ms) {
    long s = ms / 1000;
    return String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
  }
}
