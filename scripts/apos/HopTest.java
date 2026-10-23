package scripting.apos;

import bot.ui.scriptselector.models.Category;
import bot.ui.scriptselector.models.ScriptInfo;
import compatibility.apos.Script;

public final class HopTest extends Script {
  public static final ScriptInfo info =
      new ScriptInfo(
          new Category[] {Category.MISCELLANEOUS},
          "APOS",
          "Runs hop test.",
          null,
          "Hop Test");




  public HopTest(String ex) {
    // super(ex);
  }

  @Override
  public void init(String params) {}

  @Override
  public int main() {
    autohop(false);
    stopScript();
    return 1000;
  }

  @Override
  public void paint() {}

  @Override
  public void onServerMessage(String str) {}
}
