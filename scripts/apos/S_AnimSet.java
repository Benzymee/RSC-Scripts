package scripting.apos;

import bot.ui.scriptselector.models.Category;
import bot.ui.scriptselector.models.ScriptInfo;
import bot.Main;
import compatibility.apos.Script;
import java.util.Arrays;
import orsc.ORSCharacter;

/**
 * Client-side only: makes your own character look like a single animation/sprite layer (default
 * 133) by overwriting the local player's worn-layer array every second, as the APOS original did.
 * Nothing is sent to the server and nobody else sees it; the server's next appearance update is
 * simply overwritten again on the next loop. Stop the script and relog (or change equipment) to
 * restore the normal look.
 *
 * <p>Port notes: APOS poked the obfuscated client field {@code ex.wi.m}; IdleRSC exposes the same
 * array as {@code ORSCharacter.layerAnimation} on the local player. Optional parameter: the
 * animation id to show in layer 0 (default 133).
 */
public final class S_AnimSet extends Script {
  public static final ScriptInfo info =
      new ScriptInfo(
          new Category[] {Category.MISCELLANEOUS},
          "APOS",
          "Client-side only: makes your own character look like a single animation/sprite layer (default 133) by overwriting the local player's worn-layer array every second, as the APOS original did. Nothing is sent to the server and nobody else sees it; the server's next appearance update is simply overwritten again on the next loop. Stop the script and relog (or change equipment) to restore the normal look. Port notes: APOS poked the obfuscated client field { array as { animation id to show in layer 0 (default 133).",
          null,
          "Anim Set");




  private int anim = 133;

  public S_AnimSet(String ex) {
    // super(ex);
  }

  @Override
  public void init(String params) {
    if (params != null && !params.trim().isEmpty()) {
      try {
        anim = Integer.parseInt(params.trim());
      } catch (NumberFormatException e) {
        System.out.println("S_AnimSet: parameter must be an animation id; using 133.");
        anim = 133;
      }
    }
    System.out.println("S_AnimSet: client-side look set to animation " + anim + ".");
  }

  @Override
  public int main() {
    ORSCharacter self = Main.getController().getPlayer();
    if (self != null && self.layerAnimation != null && self.layerAnimation.length > 0) {
      Arrays.fill(self.layerAnimation, 0);
      self.layerAnimation[0] = anim;
    }
    return 1000;
  }
}
