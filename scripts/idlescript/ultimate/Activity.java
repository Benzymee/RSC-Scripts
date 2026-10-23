package scripting.idlescript.ultimate;

import models.entities.SkillId;

/**
 * One rotatable thing Benzyme's AIO Trainer can spend a timer on.
 *
 * <p>When {@link #canTrain(World)} is false, {@link #prepare(World)} runs for the rest of that
 * activity's timer: buy or withdraw tools, gather the missing resource, or make coins. Add a new
 * class, implement this interface, and register it in {@link Activities#all()} to extend the
 * rotation.
 */
public interface Activity {
  String id();

  String displayName();

  /** One line shown in the settings window and the README. */
  String summary();

  /** Skill highlighted on the paint. Hits is reported alongside combat. */
  SkillId skill();

  /** False on a server that does not have this content. The scheduler skips it. */
  default boolean supported(World world) {
    return true;
  }

  boolean canTrain(World world);

  int train(World world);

  int prepare(World world);

  default int tick(World world) {
    if (!supported(world)) {
      world.preparing = true;
      world.status = displayName() + " is not on this server";
      world.prepDetail = world.status;
      return 1000;
    }
    if (canTrain(world)) {
      world.preparing = false;
      world.prepDetail = "";
      return train(world);
    }
    world.preparing = true;
    return prepare(world);
  }
}
