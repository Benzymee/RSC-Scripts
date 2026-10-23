package scripting.apos;

import bot.ui.scriptselector.models.Category;
import bot.ui.scriptselector.models.ScriptInfo;
import compatibility.apos.Script;

public final class GetObjectAt extends Script {
  public static final ScriptInfo info =
      new ScriptInfo(
          new Category[] {Category.MISCELLANEOUS},
          "APOS",
          "Runs get object at.",
          null,
          "Get Object At");




  public GetObjectAt(String ex) {
    // super(ex);
  }

  @Override
  public void init(String params) {
    String[] split = params.split(",");
    System.out.println(
        getObjectIdFromCoords(Integer.parseInt(split[0]), Integer.parseInt(split[1])));
  }
}
