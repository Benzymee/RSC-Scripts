package scripting.idlescript.ultimate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Switches activities when each one's timer elapses. Sequential order follows the catalog.
 * Weighted mode picks at random using the weight set in the GUI.
 */
public final class Scheduler {
  private final TrainerConfig config;
  private final List<Activity> catalog;
  private Activity current;
  private long endsAt;
  private int sequentialIndex = -1;

  public Scheduler(TrainerConfig config, List<Activity> catalog) {
    this.config = config;
    this.catalog = catalog;
  }

  public Activity current() {
    return current;
  }

  public long millisLeft() {
    return Math.max(0, endsAt - System.currentTimeMillis());
  }

  public int tick(World world) {
    long now = System.currentTimeMillis();
    if (world.switchRequested) {
      world.switchRequested = false;
      endsAt = now;
    }
    if (current == null || now >= endsAt) {
      Activity next = pick(world);
      if (next == null) {
        world.activityName = "Idle";
        world.status = "Enable at least one activity";
        world.millisLeft = 0;
        return 1000;
      }
      current = next;
      TrainerConfig.ActivitySetting setting = config.setting(next.id());
      int minutes = setting == null ? 15 : setting.minutes;
      int variance = Math.max(0, config.variance);
      if (variance > 0) {
        int delta = ThreadLocalRandom.current().nextInt(-variance, variance + 1);
        minutes = Math.max(1, minutes + delta);
      }
      endsAt = now + minutes * 60000L;
      world.onNewActivity(next);
      String varianceNote = variance > 0 ? (" (+/-" + variance + "m)") : "";
      world.c.log(
          "Benzyme's AIO Trainer: "
              + next.displayName()
              + " for "
              + minutes
              + " minutes"
              + varianceNote);
    }
    if (!current.supported(world) || world.isBlocked(current.id())) {
      world.status = current.displayName() + " is not available here. Switching.";
      world.prepDetail = world.status;
      endsAt = now;
      return 600;
    }
    world.activityName = current.displayName();
    world.millisLeft = millisLeft();
    world.highlight = current.skill();
    return current.tick(world);
  }

  private Activity pick(World world) {
    List<Activity> enabled = new ArrayList<Activity>();
    for (Activity activity : catalog) {
      TrainerConfig.ActivitySetting setting = config.setting(activity.id());
      if (setting == null || !setting.enabled) continue;
      if (!activity.supported(world) || world.isBlocked(activity.id())) continue;
      enabled.add(activity);
    }
    if (enabled.isEmpty()) return null;
    if (!config.weighted) {
      sequentialIndex = (sequentialIndex + 1) % enabled.size();
      return enabled.get(sequentialIndex);
    }
    int total = 0;
    for (Activity activity : enabled) {
      TrainerConfig.ActivitySetting setting = config.setting(activity.id());
      total += setting == null ? 1 : setting.weight;
    }
    int roll = ThreadLocalRandom.current().nextInt(Math.max(1, total));
    int cursor = 0;
    Activity choice = enabled.get(0);
    for (Activity activity : enabled) {
      TrainerConfig.ActivitySetting setting = config.setting(activity.id());
      cursor += setting == null ? 1 : setting.weight;
      choice = activity;
      if (roll < cursor) break;
    }
    if (enabled.size() > 1 && choice == current) {
      int index = enabled.indexOf(choice);
      choice = enabled.get((index + 1) % enabled.size());
    }
    return choice;
  }
}
