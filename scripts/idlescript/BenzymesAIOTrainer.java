package scripting.idlescript;

import bot.tabs.MassStart;
import bot.tabs.MassStartSettings;
import bot.ui.scriptselector.models.Category;
import bot.ui.scriptselector.models.Parameter;
import bot.ui.scriptselector.models.ScriptInfo;
import java.lang.reflect.Constructor;
import java.util.List;
import javax.swing.SwingUtilities;
import scripting.idlescript.ultimate.Activities;
import scripting.idlescript.ultimate.Activity;
import scripting.idlescript.ultimate.Scheduler;
import scripting.idlescript.ultimate.SessionStats;
import scripting.idlescript.ultimate.TrainerConfig;
import scripting.idlescript.ultimate.World;
import scripting.idlescript.ultimate.ui.AnalyticsPanel;
import scripting.idlescript.ultimate.ui.TrainerGui;

/**
 * Rotates skilling and money-making on Coleslaw. Each activity buys or withdraws what it is missing
 * before it trains, and the report sits under the side buttons.
 *
 * <p>In the script selector look for <b>Benzyme's AIO Trainer</b> (or <b>BenzymesAIOTrainer</b> on
 * an unpatched client). Category: All, Combat, or Miscellaneous. Filter text: benzyme
 */
public class BenzymesAIOTrainer extends IdleScript implements MassStartSettings {
  private static final String DISPLAY_NAME = "Benzyme's AIO Trainer";
  private static final String AUTHOR = "Benzyme";
  private static final String DESCRIPTION =
      "Rotates combat and skilling on OpenRSC Coleslaw. Each activity has its own timer. "
          + "Missing tools are withdrawn or bought, food is eaten under the hits threshold, "
          + "and bones are looted and buried. The report is docked under the side buttons "
          + "and can be popped out into its own window. "
          + "Parameters: swap=20 (minutes for every activity), woodcutting=30 or "
          + "woodcutting=30,fishing=10 (per-activity), variance=5 (+/- minutes), "
          + "nogui (skip settings), gui (open settings — otherwise mass-start begins "
          + "immediately so tabbed-out clients do not stall), weighted, sequential. "
          + "Script parameters override the saved config.";
  private static final Category[] CATEGORIES =
      new Category[] {
        Category.COMBAT,
        Category.MELEE,
        Category.RANGED,
        Category.MAGIC,
        Category.PRAYER,
        Category.WOODCUTTING,
        Category.FISHING,
        Category.MINING,
        Category.SMITHING,
        Category.COOKING,
        Category.FIREMAKING,
        Category.THIEVING,
        Category.CRAFTING,
        Category.FLETCHING,
        Category.AGILITY,
        Category.RUNECRAFTING,
        Category.HARVESTING,
        Category.MISCELLANEOUS
      };
  private static final Parameter[] PARAMETERS =
      new Parameter[] {
        new Parameter(
            "swap=20",
            "Set every activity swap timer to N minutes (overrides saved config / GUI defaults)."),
        new Parameter(
            "woodcutting=30",
            "Override one activity's minutes. Any activity id works (melee, fishing, mining, …)."),
        new Parameter(
            "woodcutting=30,fishing=10",
            "Several per-activity minute overrides in one parameter (comma-separated)."),
        new Parameter(
            "variance=5",
            "Random +/- N minutes applied when each activity starts (still at least 1 minute)."),
        new Parameter("nogui", "Skip the settings window (default). Use saved config + parameters."),
        new Parameter(
            "gui",
            "Open the settings window before starting. Omit for mass-start so tabbed clients do not stall."),
        new Parameter("weighted", "Pick the next activity at random using the weights."),
        new Parameter("sequential", "Run enabled activities in list order.")
      };

  public static final ScriptInfo info = buildInfo();

  /**
   * Uses the display-name constructor when the client has it; otherwise falls back so the script
   * still appears as BenzymesAIOTrainer on a stock IdleRSC rebuild.
   */
  private static ScriptInfo buildInfo() {
    try {
      Constructor<ScriptInfo> withDisplay =
          ScriptInfo.class.getConstructor(
              Category[].class, String.class, String.class, Parameter[].class, String.class);
      return withDisplay.newInstance(CATEGORIES, AUTHOR, DESCRIPTION, PARAMETERS, DISPLAY_NAME);
    } catch (Throwable ignored) {
      return new ScriptInfo(CATEGORIES, AUTHOR, DESCRIPTION, PARAMETERS);
    }
  }

  private boolean ready;
  private boolean waiting;
  private World world;
  private Scheduler scheduler;
  private AnalyticsPanel panel;

  @Override
  public int start(String[] parameters) {
    // MassStart may copy ready=true / waiting=false from the leader onto a fresh instance
    // before the script thread runs. Re-init whenever world is missing so followers do not NPE
    // and sit in Probe's "no state change" stall.
    if (world == null || scheduler == null) {
      // If MassStart already marked setup done (ready was true before we got here), never open GUI.
      boolean massStartFollower = ready;
      List<Activity> catalog = Activities.all();
      TrainerConfig config = TrainerConfig.create(catalog);
      // Load saved settings first; script parameters override so the GUI and runtime agree.
      config.load();
      config.readParameters(parameters);
      SessionStats stats = new SessionStats();
      world = new World(controller, config, stats);
      scheduler = new Scheduler(config, catalog);
      ready = true;
      // Never block on the settings window for mass-start followers, or unless gui was requested.
      // Mass start: the first tab always shows the settings window (it loads the saved settings
      // and the arguments); the window saves the config, which the other tabs then load.
      if (!massStartFollower && (config.shouldShowGui() || MassStart.isLeaderSetup())) {
        waiting = true;
        controller.setStatus("Waiting for settings — click Start");
        SwingUtilities.invokeLater(
            new Runnable() {
              public void run() {
                TrainerGui.open(
                    config,
                    new Runnable() {
                      public void run() {
                        waiting = false;
                      }
                    });
              }
            });
      } else {
        waiting = false;
        config.save();
        controller.setStatus(
            massStartFollower
                ? "Mass-start: using saved config + arguments"
                : "Starting (saved config / defaults)");
      }
      return 300;
    }
    if (waiting) {
      controller.setStatus("Waiting for settings — Start, or close the window");
      return 200;
    }
    if (!controller.isRunning() || !controller.isLoggedIn()) return 1000;
    controller.setAutoLogin(true);
    controller.setBatchBars(true);
    controller.hideWelcomeScreen();
    world.stats.capture(controller);
    // Defer paint install one tick so SidePanel expand does not race mass-start / graphics-off.
    if (panel == null) {
      panel = new AnalyticsPanel();
      return 200;
    }
    if (!panel.isInstalled()) {
      panel.install();
      return 200;
    }
    int delay = world.safety();
    if (delay <= 0) delay = scheduler.tick(world);
    panel.refresh(world);
    return Math.max(50, delay);
  }

  @Override
  public void serverMessageInterrupt(String message) {
    if (world != null) world.noteMessage(message);
  }

  @Override
  public void paintInterrupt() {
    // The report is the side-panel dock, not a box drawn over the game.
  }

  @Override
  public void chatCommandInterrupt(String commandText) {
    if (commandText == null || panel == null) return;
    String command = commandText.trim().toLowerCase();
    if (command.equals("paint") || command.equals("popout") || command.equals("dock")) {
      panel.toggle();
    }
  }

  @Override
  public void cleanup() {
    if (panel != null) panel.dispose();
    panel = null;
    world = null;
    scheduler = null;
    ready = false;
    waiting = false;
    super.cleanup();
  }
}
