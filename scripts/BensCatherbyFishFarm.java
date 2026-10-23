import bot.ui.scriptselector.models.Category;
import bot.ui.scriptselector.models.ScriptInfo;

/**
 * Old name of Ben's Fish Farm (BensFishFarm.java). Kept so saved script queues, groups and mass
 * starts that still say "BensCatherbyFishFarm" keep working: it is exactly Ben's Fish Farm (same
 * settings fields, so copied queue settings still apply). Hidden from the script list; it keeps
 * using its own remembered setup file (BensCatherbyFishFarm.properties).
 */
public class BensCatherbyFishFarm extends BensFishFarm {
  public static final ScriptInfo info =
      new ScriptInfo(
          new Category[] {Category.HIDDEN_FROM_SELECTOR},
          "Ben",
          "Old name of Ben's Fish Farm - use Ben's Fish Farm (BensFishFarm) for new starts.",
          null,
          "Ben's Fish Farm (old name)");
}
