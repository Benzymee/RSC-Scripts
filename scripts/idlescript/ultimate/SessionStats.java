package scripting.idlescript.ultimate;

import controller.Controller;
import java.util.LinkedHashMap;
import java.util.Map;
import models.entities.SkillId;

/** Runtime totals painted under the side buttons and in the pop-out window. */
public final class SessionStats {
  public final long startedAt = System.currentTimeMillis();
  public final int[] startXp = new int[20];
  public final int[] startLevel = new int[20];
  public boolean captured;
  public long coinsEarned;
  public long coinsSpent;
  public long lootValue;
  public int lootCount;
  public int deaths;
  public final Map<String, Integer> loot = new LinkedHashMap<String, Integer>();

  public void capture(Controller c) {
    if (captured) return;
    for (SkillId skill : SkillId.values()) {
      int id = skill.getId();
      if (id < 0 || id >= startXp.length) continue;
      startXp[id] = c.getPlayerExperience(id);
      startLevel[id] = c.getBaseStat(id);
    }
    captured = true;
  }

  public void addLoot(String name, int amount, long value) {
    if (amount <= 0) return;
    lootCount += amount;
    lootValue += Math.max(0, value);
    Integer prev = loot.get(name);
    loot.put(name, (prev == null ? 0 : prev) + amount);
  }

  public long runtimeMs() {
    return Math.max(0, System.currentTimeMillis() - startedAt);
  }

  public String report(World world) {
    StringBuilder out = new StringBuilder();
    long ms = runtimeMs();
    double hours = Math.max(ms / 3600000.0, 1.0 / 3600.0);
    long profit = coinsEarned - coinsSpent;
    out.append("Benzyme's AIO Trainer\n");
    out.append("Runtime: ").append(formatDuration(ms)).append('\n');
    out.append("Activity: ").append(world.activityName).append('\n');
    out.append("Time left: ").append(formatDuration(world.millisLeft)).append('\n');
    out.append("Mode: ").append(world.preparing ? "Preparing" : "Training").append('\n');
    if (world.prepDetail != null && world.prepDetail.length() > 0) {
      out.append("Prep: ").append(world.prepDetail).append('\n');
    }
    out.append("Status: ").append(world.status).append('\n');
    out.append("Style: ").append(world.styleName).append('\n');
    out.append("Server: ").append(world.coleslaw() ? "Coleslaw" : "Uranium").append('\n');
    out.append('\n');
    out.append("Coins: ").append(world.invCoins()).append('\n');
    out.append("Profit: ").append(profit).append(" gp\n");
    out.append("GP/hr: ").append(perHour(profit, hours)).append('\n');
    out.append("Earned: ").append(coinsEarned).append("  spent: ").append(coinsSpent).append('\n');
    out.append("Loot: ").append(lootCount).append(" items, ");
    out.append(lootValue).append(" gp base value\n");
    out.append("Deaths: ").append(deaths).append('\n');
    if (!loot.isEmpty()) {
      out.append("Recent loot:\n");
      int shown = 0;
      for (Map.Entry<String, Integer> entry : loot.entrySet()) {
        out.append("  ").append(entry.getValue()).append(" ").append(entry.getKey()).append('\n');
        if (++shown >= 8) break;
      }
    }
    out.append('\n');
    out.append(String.format("%-14s %5s %6s %8s %8s%n", "Skill", "Lvl", "Gain", "XP", "XP/hr"));
    Controller c = world.c;
    for (SkillId skill : SkillId.values()) {
      int id = skill.getId();
      if (id < 0 || id >= startXp.length) continue;
      int level = c.getBaseStat(id);
      int gainedLevels = level - startLevel[id];
      int gainedXp = c.getPlayerExperience(id) - startXp[id];
      if (gainedXp == 0 && gainedLevels == 0 && id > 7 && skill != world.highlight) continue;
      String label = skill.name();
      if (label.length() > 14) label = label.substring(0, 14);
      out.append(
          String.format(
              "%-14s %5d %+6d %8d %8s%n",
              label, level, gainedLevels, gainedXp, perHour(gainedXp, hours)));
    }
    return out.toString();
  }

  private static String perHour(long amount, double hours) {
    long value = (long) (amount / hours);
    if (Math.abs(value) >= 1000000) return (value / 1000000) + "m";
    if (Math.abs(value) >= 1000) return (value / 1000) + "k";
    return Long.toString(value);
  }

  public static String formatDuration(long ms) {
    if (ms < 0) ms = 0;
    long seconds = ms / 1000;
    long minutes = seconds / 60;
    long hours = minutes / 60;
    seconds %= 60;
    minutes %= 60;
    if (hours > 0) return hours + "h " + minutes + "m " + seconds + "s";
    if (minutes > 0) return minutes + "m " + seconds + "s";
    return seconds + "s";
  }
}
