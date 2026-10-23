package scripting.idlescript.ultimate;

import bot.Main;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Per-activity timers plus the shared food, loot and coin settings. Script parameters override
 * values loaded from the saved properties file.
 */
public final class TrainerConfig {
  public final List<ActivitySetting> activities = new ArrayList<ActivitySetting>();
  public boolean weighted;
  public int foodId = Ids.CABBAGE;
  public int eatPercent = 50;
  public int minLootValue = 10;
  public boolean lootBones = true;
  public boolean lootHides = true;
  public int minCoins = 100;
  public String meleeStyle = "Rotate";
  public boolean noGui;
  /** Force the settings window even when a saved config exists (mass-start normally skips it). */
  public boolean forceGui;
  /** Random +/- minutes applied when an activity starts. 0 disables. */
  public int variance;

  public static final class ActivitySetting {
    public final String id;
    public final String name;
    public final String summary;
    public boolean enabled;
    public int minutes;
    public int weight;

    public ActivitySetting(String id, String name, String summary, boolean enabled, int minutes) {
      this.id = id;
      this.name = name;
      this.summary = summary;
      this.enabled = enabled;
      this.minutes = minutes;
      this.weight = "money".equals(id) ? 2 : 1;
    }
  }

  public static TrainerConfig create(List<Activity> catalog) {
    TrainerConfig config = new TrainerConfig();
    for (Activity activity : catalog) {
      config.activities.add(
          new ActivitySetting(activity.id(), activity.displayName(), activity.summary(), true, 15));
    }
    return config;
  }

  /**
   * Applies IdleRSC script-parameter tokens. Call after {@link #load()} so parameters override the
   * saved config (and therefore agree with the GUI when it opens).
   *
   * <p>Supported tokens (case-insensitive):
   *
   * <ul>
   *   <li>{@code nogui} — skip the settings window
   *   <li>{@code weighted} / {@code sequential}
   *   <li>{@code swap=20} — set every activity timer to 20 minutes
   *   <li>{@code woodcutting=30} — override one activity (any activity id)
   *   <li>{@code woodcutting=30,fishing=10} — several overrides in one token
   *   <li>{@code variance=5} — random +/- 5 minutes when each activity starts
   * </ul>
   */
  public void readParameters(String[] parameters) {
    if (parameters == null) return;
    for (String parameter : parameters) {
      if (parameter == null) continue;
      String trimmed = parameter.trim();
      if (trimmed.length() == 0) continue;
      // Allow comma-chained key=value pairs in a single selector argument.
      String[] parts = trimmed.split(",");
      for (String part : parts) {
        applyToken(part.trim());
      }
    }
  }

  private void applyToken(String token) {
    if (token.length() == 0) return;
    String lower = token.toLowerCase(Locale.ROOT);
    if (lower.equals("nogui")) {
      noGui = true;
      forceGui = false;
      return;
    }
    if (lower.equals("gui")) {
      forceGui = true;
      noGui = false;
      return;
    }
    if (lower.equals("weighted")) {
      weighted = true;
      return;
    }
    if (lower.equals("sequential")) {
      weighted = false;
      return;
    }
    int eq = lower.indexOf('=');
    if (eq <= 0 || eq == lower.length() - 1) return;
    String key = lower.substring(0, eq).trim();
    String raw = token.substring(eq + 1).trim();
    int value = parseInt(raw, Integer.MIN_VALUE);
    if (value == Integer.MIN_VALUE) return;
    if (key.equals("swap") || key.equals("minutes") || key.equals("timer")) {
      if (value < 1) value = 1;
      for (ActivitySetting setting : activities) {
        setting.minutes = value;
      }
      return;
    }
    if (key.equals("variance") || key.equals("var")) {
      variance = Math.max(0, value);
      return;
    }
    ActivitySetting setting = setting(key);
    if (setting != null) {
      setting.minutes = Math.max(1, value);
      setting.enabled = true;
    }
  }

  public ActivitySetting setting(String id) {
    if (id == null) return null;
    String needle = id.toLowerCase(Locale.ROOT);
    for (ActivitySetting setting : activities) {
      if (setting.id.equals(needle)) return setting;
    }
    return null;
  }

  /** True when this account already has a saved trainer properties file. */
  public static boolean hasSavedConfig() {
    return new File(path()).isFile();
  }

  /**
   * Whether to open the blocking settings window. Default is off so mass-start / tabbed-out
   * clients begin immediately with the saved config (or defaults). Pass {@code gui} to configure.
   */
  public boolean shouldShowGui() {
    if (noGui) return false;
    return forceGui;
  }

  public void load() {
    Properties properties = new Properties();
    File file = new File(path());
    if (!file.exists()) return;
    try {
      FileInputStream input = new FileInputStream(file);
      try {
        properties.load(input);
      } finally {
        input.close();
      }
    } catch (IOException exception) {
      return;
    }
    weighted = Boolean.parseBoolean(properties.getProperty("weighted", "false"));
    foodId = parseInt(properties.getProperty("foodId"), foodId);
    eatPercent = parseInt(properties.getProperty("eatPercent"), eatPercent);
    minLootValue = parseInt(properties.getProperty("minLootValue"), minLootValue);
    lootBones = Boolean.parseBoolean(properties.getProperty("lootBones", "true"));
    lootHides = Boolean.parseBoolean(properties.getProperty("lootHides", "true"));
    minCoins = parseInt(properties.getProperty("minCoins"), minCoins);
    meleeStyle = properties.getProperty("meleeStyle", meleeStyle);
    variance = Math.max(0, parseInt(properties.getProperty("variance"), variance));
    for (ActivitySetting setting : activities) {
      setting.enabled =
          Boolean.parseBoolean(
              properties.getProperty(setting.id + ".enabled", Boolean.toString(setting.enabled)));
      setting.minutes = parseInt(properties.getProperty(setting.id + ".minutes"), setting.minutes);
      setting.weight = parseInt(properties.getProperty(setting.id + ".weight"), setting.weight);
      if (setting.minutes < 1) setting.minutes = 1;
      if (setting.weight < 1) setting.weight = 1;
    }
  }

  public void save() {
    Properties properties = new Properties();
    properties.setProperty("weighted", Boolean.toString(weighted));
    properties.setProperty("foodId", Integer.toString(foodId));
    properties.setProperty("eatPercent", Integer.toString(eatPercent));
    properties.setProperty("minLootValue", Integer.toString(minLootValue));
    properties.setProperty("lootBones", Boolean.toString(lootBones));
    properties.setProperty("lootHides", Boolean.toString(lootHides));
    properties.setProperty("minCoins", Integer.toString(minCoins));
    properties.setProperty("meleeStyle", meleeStyle);
    properties.setProperty("variance", Integer.toString(variance));
    for (ActivitySetting setting : activities) {
      properties.setProperty(setting.id + ".enabled", Boolean.toString(setting.enabled));
      properties.setProperty(setting.id + ".minutes", Integer.toString(setting.minutes));
      properties.setProperty(setting.id + ".weight", Integer.toString(setting.weight));
    }
    try {
      File file = new File(path());
      File parent = file.getParentFile();
      if (parent != null && !parent.exists()) parent.mkdirs();
      FileOutputStream output = new FileOutputStream(file);
      try {
        properties.store(output, "Benzyme's AIO Trainer");
      } finally {
        output.close();
      }
    } catch (IOException ignored) {
      // The script still runs with the values currently on screen.
    }
  }

  private static int parseInt(String value, int fallback) {
    if (value == null) return fallback;
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException exception) {
      return fallback;
    }
  }

  private static String path() {
    String user = "default";
    try {
      if (Main.config != null && Main.config.getUsername() != null) {
        String name = Main.config.getUsername().trim();
        if (name.length() > 0) user = name;
      }
    } catch (Exception ignored) {
      user = "default";
    }
    return "Cache/botconfigs/" + user + "/benzyme-aio.properties";
  }
}
