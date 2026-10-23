package scripting.idlescript.ultimate;

/**
 * OpenRSC / classic XP curve (same formula as {@code mudclient.setExperienceArray}). Used for
 * "XP to next level" progress bars on the paint without reflecting into mudclient.
 */
public final class XpTable {
  private static final int LIMIT = 120;
  private static final int[] XP = build();

  private XpTable() {}

  private static int[] build() {
    int[] table = new int[LIMIT];
    long experience = 0;
    for (int i = 0; i < LIMIT; ++i) {
      int experienceFactor = 1 + i;
      long experienceIncrease =
          (long) (300D * Math.pow(2.0D, experienceFactor / 7D) + experienceFactor);
      experience += experienceIncrease;
      table[i] = (int) ((experience & 0xffffffffL) / 4);
    }
    return table;
  }

  /** Total XP required to reach {@code level} (level 1 = 0). */
  public static int forLevel(int level) {
    if (level <= 1) return 0;
    int index = Math.min(level - 1, XP.length) - 1;
    if (index < 0) return 0;
    if (index >= XP.length) return XP[XP.length - 1];
    return XP[index];
  }

  /**
   * Progress within the current level as 0.0–1.0, plus XP remaining to the next level.
   *
   * @return int[]{ xpIntoLevel, xpNeededForLevel, xpRemaining } — xpNeededForLevel is 0 at max
   */
  public static int[] progress(int level, int totalXp) {
    if (level < 1) level = 1;
    if (level >= LIMIT) {
      return new int[] {0, 0, 0};
    }
    int start = forLevel(level);
    int end = forLevel(level + 1);
    int span = Math.max(1, end - start);
    int into = Math.max(0, totalXp - start);
    if (into > span) into = span;
    int remaining = Math.max(0, end - totalXp);
    return new int[] {into, span, remaining};
  }
}
