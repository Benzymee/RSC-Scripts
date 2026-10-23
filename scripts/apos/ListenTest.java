package scripting.apos;

import bot.ui.scriptselector.models.Category;
import bot.ui.scriptselector.models.ScriptInfo;
import static java.lang.String.format;

import compatibility.apos.Script;

public final class ListenTest extends Script {
  public static final ScriptInfo info =
      new ScriptInfo(
          new Category[] {Category.MISCELLANEOUS},
          "APOS",
          "Runs listen test.",
          null,
          "Listen Test");




  public ListenTest(String ex) {
    // super(ex);
  }

  @Override
  public void init(String params) {}

  @Override
  public int main() {
    return 1000;
  }

  @Override
  public void paint() {}

  @Override
  public void onServerMessage(String str) {
    writeLine(format("Server message: %s", str));
  }

  @Override
  public void onTradeRequest(String name) {
    writeLine(format("Traded by: %s", name));
  }

  @Override
  public void onChatMessage(String msg, String name, boolean mod, boolean admin) {
    writeLine(format("Public chat: %s %s", name, msg));
  }

  @Override
  public void onPrivateMessage(String msg, String name, boolean mod, boolean admin) {
    writeLine(format("Private chat: %s %s", name, msg));
  }

  @Override
  public void onKeyPress(int keycode) {
    writeLine(format("Key pressed: %d", keycode));
  }
}
