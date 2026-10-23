import bot.Main;
import bot.ui.scriptselector.models.Category;
import bot.ui.scriptselector.models.ScriptInfo;
import controller.Controller;
import scripting.idlescript.IdleScript;

/**
 * Example external script: reports your position and fatigue every few seconds. Shows up in the
 * script selector as "ExampleScript [ext]". Delete this file if you do not want it.
 */
public class ExampleScript extends IdleScript {
  public static final ScriptInfo info =
      new ScriptInfo(
          new Category[] {Category.MISCELLANEOUS},
          "IdleRSC",
          "Example hot-loaded script from the scripts folder: prints your position and fatigue"
              + " every 5 seconds. Optional argument: the interval in seconds.");

  private final Controller c = Main.getController();

  @Override
  public int start(String[] parameters) {
    int seconds = 5;
    try {
      if (parameters != null && parameters.length > 0 && !parameters[0].trim().isEmpty())
        seconds = Math.max(1, Integer.parseInt(parameters[0].trim()));
    } catch (NumberFormatException ignored) {
      // keep the default
    }
    if (c.isLoggedIn()) {
      c.displayMessage(
          "@cya@ExampleScript: @whi@at "
              + c.currentX()
              + ","
              + c.currentY()
              + "  fatigue "
              + c.getFatigue()
              + "%");
    }
    return seconds * 1000;
  }
}
