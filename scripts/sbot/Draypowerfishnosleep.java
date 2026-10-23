package scripting.sbot;

import bot.ui.scriptselector.models.Category;
import bot.ui.scriptselector.models.ScriptInfo;
import compatibility.sbot.Script;

public class Draypowerfishnosleep extends Script {
  public static final ScriptInfo info =
      new ScriptInfo(
          new Category[] {Category.FISHING},
          "SBot",
          "Power-fishes in Draynor and does not sleep.",
          null,
          "Draynor Power Fish Nosleep");



  public String[] getCommands() {
    return new String[] {"fish"};
  }

  public void start(String command, String[] parameter) {
    DisplayMessage("@cya@we all live in a yellow submarine", 3);
    while (Running()) {
      int pid = PID();
      System.out.println(pid);
      {
        if (Fatigue() >= 99 && Running()) {

          Quit();
        }
        AtObject(224, 661);
        Wait(3000);
      }
    }
    DisplayMessage("@red@stoooop", 3);
  }
}
