package scripting.idlescript.SeattaScript.AIOQuester;

import bot.Main;
import bot.tabs.MassStart;
import bot.tabs.MassStartSettings;
import bot.ui.scriptselector.models.Category;
import bot.ui.scriptselector.models.Parameter;
import bot.ui.scriptselector.models.ScriptInfo;
import controller.Controller;
import scripting.idlescript.IdleScript;
import scripting.idlescript.SeattaScript.AIOQuester.models.*;

/**
 * This is the main entry point of AIOQuester. Quests are picked either from the setup ui (an
 * ordered queue, or "do all available") or from the script parameters, then QuestQueue runs them
 * one after another.
 */
public class AIOQuester extends IdleScript implements MassStartSettings {
  protected static final Controller c = Main.getController();
  public static final ScriptInfo info =
      new ScriptInfo(
          new Category[] {
            Category.QUESTING, Category.IRONMAN_SUPPORTED, Category.ULTIMATE_IRONMAN_SUPPORTED
          },
          "Seatta",
          "An all-in-one questing script."
              + "\n\nNOT ALL QUESTS ARE AVAILABLE YET!"
              + "\n\nQueue several quests in the setup window, or pass them as parameters:"
              + "\n  quests=Cook's Assistant,Sheep Shearer,Imp Catcher"
              + "\n  all  (do all available quests)"
              + "\nCompleted quests and quests with unmet requirements are skipped and logged."
              + "\n\nSpecial thanks to:"
              // Feel free to add your name here if you've helped write quest scripts.
              + "\n - Kaila, Xatain, and Acry",
          new Parameter[] {
            new Parameter(
                "quests=",
                "Comma-separated list of quests to run in order, e.g.\n"
                    + "quests=cooksassistant,sheepshearer,impcatcher\n"
                    + "Names are matched ignoring case, spaces and punctuation. Skips the setup window."),
            new Parameter("all", "Do all available quests (skips the setup window)"),
            new Parameter(
                "timeout=",
                "Per-quest time limit in minutes (default "
                    + QuestQueue.DEFAULT_TIMEOUT_MINUTES
                    + ", 0 = none). A quest that runs over is logged as failed and the queue moves on."),
            new Parameter(
                "nobank",
                "Don't walk to a bank before a quest to deposit unneeded items / withdraw required items"),
            new Parameter(
                "debug",
                "Enables logging of various debug messages\n"
                    + "Useful for getting exact strings for overriding messageInterrupt methods during quest script development\n"),
            new Parameter(
                "test",
                "Runs the internal testing loop\n"
                    + "Useful for testing and developing various methods for QuestHandler\n"
                    + "Not useful for AIOQuester users")
          });

  /* TODO: Don't forget to set these to false before committing
  These are for testing quest stages and methods. There's obviously no way to reinitialize a
  certain dialog state with an npc apart from having server permissions to reset stages, so stage
  testing is only used for pathing/interacting with objects(unless they're stage-locked/etc...*/

  private QuestHandler qh;
  protected boolean TESTING_LOOP = false;
  public static boolean DEBUGGING = false;
  public static boolean SKIP_QUEST_DEFINED_CHECK = false;
  public static final boolean TESTING_QUESTS = false;
  public static final int TESTING_STAGE = 0;

  public int start(String[] parameters) {
    QuestQueue.ParsedArgs args = QuestQueue.parseArgs(parameters);
    if (args.debug) {
      DEBUGGING = true;
      Main.log("AIOQuester debugging enabled");
    }
    if (args.test) {
      TESTING_LOOP = true;
      Main.log("AIOQuester testing loop started");
    }
    for (String unknown : args.unknown)
      c.log("AIOQuester: ignoring unknown parameter or quest name '" + unknown + "'", "red");

    qh = new QuestHandler();
    qh.updateBankItems();
    setupAndRun(args);
    c.stop();
    return 1000;
  }

  /**
   * Gets the quest queue from the parameters or the setup ui, then runs it. A single selected
   * quest is just a queue of one.
   */
  private void setupAndRun(QuestQueue.ParsedArgs args) {
    QuestHandler.paintBuilder = null;
    QuestHandler.rowBuilder = null;

    if (TESTING_LOOP) {
      QuestHandler.paintBuilder = paintBuilder;
      QuestHandler.rowBuilder = rowBuilder;
      QuestHandler.QUEST = QuestDef.loopTesting;
      qh.startTestingLoop();
      return;
    }

    // Mass start: the first tab always shows the setup window, pre-filled from the parameters
    QuestQueue.Options options =
        args.options.hasSelection() && !MassStart.isLeaderSetup()
            ? args.options
            : QuestUI.showFrame(args.options);
    if (options == null || !c.isRunning()) return;

    QuestHandler.paintBuilder = paintBuilder;
    QuestHandler.rowBuilder = rowBuilder;
    QuestDock.install(); // paint + Skip quest button under the side panel buttons
    try {
      new QuestQueue(qh, options).run();
    } finally {
      QuestDock.uninstall();
    }
  }

  @Override
  public void cleanup() {
    QuestDock.uninstall();
    if (qh != null) qh.cleanup();
  }

  /** ::skip / ::skipquest = the paint's Skip quest button. */
  @Override
  public void chatCommandInterrupt(String commandText) {
    String cmd = commandText == null ? "" : commandText.trim().toLowerCase(java.util.Locale.ROOT);
    if (cmd.equals("skip") || cmd.equals("skipquest")) QuestHandler.requestSkip("::" + cmd);
  }

  /**
   * Mass start: the quest queue chosen in the leader's setup window as parameters (flags first,
   * then quests=...), so the other tabs run the same queue without a window.
   */
  @Override
  public String[] massStartArguments() {
    QuestQueue.Options o = QuestUI.getLastResult();
    if (o == null) return null;
    java.util.List<String> out = new java.util.ArrayList<>();
    if (o.doAllAvailable) out.add("all");
    out.add(o.bankForRequirements ? "bank" : "nobank");
    out.add("timeout=" + Math.max(0, o.timeoutMinutes));
    out.add("gang=" + QuestHandler.SHIELD_GANG);
    if (DEBUGGING) out.add("debug");
    StringBuilder q = new StringBuilder();
    for (QuestDef d : o.quests) {
      if (d == null || d.getName() == null) continue;
      if (q.length() > 0) q.append(',');
      q.append(d.getName().replaceAll("[^A-Za-z0-9]", ""));
    }
    if (q.length() > 0) out.add("quests=" + q);
    return out.toArray(new String[0]);
  }

  /** Status hook shown after the script name, e.g. "AIOQuester (Ernest the Chicken 33%)". */
  public String getStatusSuffix() {
    QuestDef q = QuestHandler.QUEST;
    if (q == null || q.getName() == null) return null;
    String out = q.getName();
    int total = q.getStagesTotal();
    if (!q.isMiniquest() && total > 0) {
      int stage = QuestHandler.QUEST_STAGE;
      int pct = stage < 0 ? 100 : Math.max(0, Math.min(99, stage * 100 / (total + 1)));
      out += " " + pct + "%";
    }
    String queue = QuestHandler.QUEUE_STATUS;
    if (queue != null) {
      java.util.regex.Matcher m = java.util.regex.Pattern.compile("Queue (\\d+)/(\\d+)").matcher(queue);
      if (m.find() && !"1".equals(m.group(2))) out += " - quest " + m.group(1) + "/" + m.group(2);
    }
    return out;
  }
  @Override
  public void serverMessageInterrupt(String message) {
    // Do server message checks in the QuestHandler method below instead here.
    qh._serverMessageInterrupt(message);
  }

  @Override
  public void questMessageInterrupt(String message) {
    // Do server message checks in the QuestHandler method below instead here.
    qh._questMessageInterrupt(message);
  }

  @Override
  public void tradeMessageInterrupt(String message) {
    // Do trade message checks in the QuestHandler method below instead here.
    qh._tradeMessageInterrupt(message);
  }

  @Override
  public void chatMessageInterrupt(String message) {
    // Do chat message checks in the QuestHandler method below instead here.
    qh._chatMessageInterrupt(message);
  }

  @Override
  public void privateMessageReceivedInterrupt(String sender, String message) {
    // Do private message checks in the QuestHandler method below instead here.
    qh._privateMessageInterrupt(sender, message);
  }

  @Override
  public void paintInterrupt() {
    if (c != null && qh != null) qh.drawPaint();
  }
}
