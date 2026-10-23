package scripting.sbot;

import bot.ui.scriptselector.models.Category;
import bot.ui.scriptselector.models.ScriptInfo;
import compatibility.sbot.Script;

public class Steal extends Script {
  public static final ScriptInfo info =
      new ScriptInfo(
          new Category[] {Category.THIEVING},
          "SBot",
          "Steals from stalls or NPCs.",
          null,
          "Steal");




  public String[] getCommands() {
    return (new String[] {"steal"});
  }

  public void start(String s, String[] s1) {
    while (Running()) {
      DisplayMessage("@gre@SBoT: @whi@Nat stealer - Billy www.wildrscheats.com", 3);
      while (Running()) {
        AtObject2(582, 1527);
        Wait(100);
        if (Fatigue() >= 95 && Running()) {
          for (; !Sleeping() && Running(); Wait(2500)) Use(FindInv(1263));

          for (; Sleeping() && Running(); Wait(100)) ;
        }
      }
    }
  }
}
