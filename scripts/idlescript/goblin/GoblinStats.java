package scripting.idlescript.goblin;

import controller.Controller;

/** Session counters for Benzyme's Goblin Killer. Not a plain value so MassStart never copies it. */
public final class GoblinStats {
  public final long startedAt = System.currentTimeMillis();
  public volatile String status = "Starting";
  public int kills;
  public int deaths;
  public int regeared;
  /** Names of worn items missing after a death, for the paint. */
  public volatile String lost = "";
  public int buried;
  public long lastDeathAt;
  public int prevX = -1;
  public int prevY = -1;

  private boolean captured;
  private int[] combatSkills = new int[0];
  private int prayerSkill = 5;
  private int[] startCombatXp = new int[0];
  private int startPrayerXp;

  /** Records the starting XP once, the first tick the player is logged in. */
  public void capture(Controller c, int[] combatSkills, int prayerSkill) {
    if (captured) return;
    this.combatSkills = combatSkills.clone();
    this.prayerSkill = prayerSkill;
    startCombatXp = new int[combatSkills.length];
    for (int i = 0; i < combatSkills.length; i++) {
      startCombatXp[i] = c.getPlayerExperience(combatSkills[i]);
    }
    startPrayerXp = c.getPlayerExperience(prayerSkill);
    captured = true;
  }

  public int combatXpGained(Controller c) {
    if (!captured) return 0;
    int total = 0;
    for (int i = 0; i < combatSkills.length; i++) {
      total += Math.max(0, c.getPlayerExperience(combatSkills[i]) - startCombatXp[i]);
    }
    return total;
  }

  public int prayerXpGained(Controller c) {
    if (!captured) return 0;
    return Math.max(0, c.getPlayerExperience(prayerSkill) - startPrayerXp);
  }

  public GoblinPaint.Snapshot snapshot(Controller c, boolean bury, String style) {
    long ms = System.currentTimeMillis() - startedAt;
    double hours = Math.max(1.0 / 60.0, ms / 3600000.0);
    int combatXp = combatXpGained(c);
    int prayerXp = prayerXpGained(c);
    GoblinPaint.Snapshot s = new GoblinPaint.Snapshot();
    s.runtime = formatTime(ms);
    s.status = status;
    s.kills = kills;
    s.killsHour = (int) Math.round(kills / hours);
    s.deaths = deaths;
    s.regeared = regeared;
    s.lost = lost;
    s.buried = buried;
    s.bury = bury;
    s.style = style;
    s.combatXp = combatXp;
    s.combatXpHour = (int) Math.round(combatXp / hours);
    s.prayerXp = prayerXp;
    s.prayerXpHour = (int) Math.round(prayerXp / hours);
    return s;
  }

  public static String formatTime(long ms) {
    long sec = Math.max(0, ms / 1000);
    return String.format("%02d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60);
  }
}
