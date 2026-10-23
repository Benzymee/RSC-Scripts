package scripting.sbot;

import bot.ui.scriptselector.models.Category;
import bot.ui.scriptselector.models.ScriptInfo;
import compatibility.sbot.Script;

public class CopperMiner extends Script {
  public static final ScriptInfo info =
      new ScriptInfo(
          new Category[] {Category.MINING},
          "SBot",
          "Mines copper.",
          null,
          "Copper Miner");



  public String[] getCommands() {
    return new String[] {"copper"};
  }

  public void ServerMessage(String message) {}

  public void start(String command, String[] parameter) {

    while (Running()) {
      if (Fatigue() < 95 && !Sleeping()) {
        int[] RockPos = GetNearestObject(104);
        AtObject(RockPos[0], RockPos[1]);
        Wait(Rand(500, Rand(2000, 3000)));
      } else {
        if (!Sleeping()) {
          Wait(2000);
          Use(FindInv(1263));
          Wait(5000);
        }
      }
      Wait(100);
    }
    DisplayMessage("@red@STOPPED", 3);
  }
}
