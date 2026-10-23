package scripting.apos;

import bot.ui.scriptselector.models.Category;
import bot.ui.scriptselector.models.ScriptInfo;
import compatibility.apos.Script;

public final class GetWallAt extends Script {
  public static final ScriptInfo info =
      new ScriptInfo(
          new Category[] {Category.MISCELLANEOUS},
          "APOS",
          "Runs get wall at.",
          null,
          "Get Wall At");




  public GetWallAt(String ex) {
    // super(ex);
  }

  @Override
  public void init(String params) {
    String[] split = params.split(",");
    System.out.println(
        getWallObjectIdFromCoords(Integer.parseInt(split[0]), Integer.parseInt(split[1])));
  }
}
