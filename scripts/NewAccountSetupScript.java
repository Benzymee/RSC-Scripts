import bot.Main;
import bot.ui.scriptselector.models.Category;
import bot.ui.scriptselector.models.ScriptInfo;
import com.openrsc.client.entityhandling.EntityHandler;
import controller.Controller;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import orsc.Config;
import orsc.mudclient;
import scripting.idlescript.IdleScript;

/**
 * New Account Setup - the side panel's "New account" button as a script, so it can be picked from
 * the script list and mass started across tabs. Same steps and packets as the client's
 * bot.ui.NewAccountSetup (commits 69d3fc62 / a548a957), run synchronously so the script can stop
 * itself when done:
 *
 * <ol>
 *   <li>Character design screen: random valid appearance, accepted (param keepdesign: accept the
 *       design currently shown instead of randomising).
 *   <li>Tutorial Island: asks the server to skip it, every 4s for up to 30s.
 *   <li>Manual camera, sound off, fog off, login box hidden (param nosettings: skip this).
 *   <li>Closes the welcome / server message boxes for 10s.
 * </ol>
 *
 * Each step checks the state first, so it's safe on an account that's already set up. The client
 * class isn't reused directly: only its async start() is public (no options, no completion), so
 * its logic is copied here.
 */
public class NewAccountSetupScript extends IdleScript {
  public static final ScriptInfo info =
      new ScriptInfo(
          new Category[] {Category.MISCELLANEOUS},
          "Ben",
          "The 'New account' button as a script: random character design, skip Tutorial Island,\n"
              + "manual camera / fog off / sound off / hide login box, close the login boxes,\n"
              + "then stops. Params (optional): keepdesign, nosettings",
          null,
          "New Account Setup");

  private static final int[] SERVER_HEADS = {0, 3, 5, 6, 7};
  private static final int[] SERVER_BODIES = {1, 4};
  private static final int LEGS = 2;
  private static final int OPCODE_APPEARANCE = 235;
  private static final int OPCODE_GAME_SETTING = 111;
  private static final int SETTING_CAMERA_AUTO = 0;
  private static final int SETTING_SOUND_DISABLED = 2;
  private static final int SETTING_HIDE_FOG = 27;
  private static final int SETTING_HIDE_LOGIN_BOX = 40;

  private Controller c;
  private boolean keepDesign = false;
  private boolean noSettings = false;

  @Override
  public int start(String[] params) {
    c = Main.getController();
    if (c == null) return 1000;
    if (params != null)
      for (String p : params) {
        if (p == null) continue;
        for (String t : p.toLowerCase(Locale.ROOT).split("[\\s,;]+")) {
          if (t.equals("keepdesign")) keepDesign = true;
          if (t.equals("nosettings")) noSettings = true;
        }
      }
    try {
      run();
    } catch (Throwable e) {
      Main.logError("[NewAccountSetup] failed", e);
      log("@red@Failed: " + e);
    }
    log("@gre@Done - stopping the script");
    c.stop();
    return 1000;
  }

  private void run() {
    if (!c.isLoggedIn()) {
      log("Waiting for login...");
      long end = System.currentTimeMillis() + 60_000;
      while (!c.isLoggedIn() && System.currentTimeMillis() < end && c.isRunning()) c.sleep(250);
      if (!c.isLoggedIn()) {
        log("@red@Not logged in after 60s, nothing done");
        return;
      }
    }
    String state =
        isAppearanceScreen(c)
            ? "character design screen"
            : onTutorialIsland(c) ? "Tutorial Island" : "logged in at " + c.currentX() + "," + c.currentY();
    log("@gre@Starting (" + state + (keepDesign ? ", keepdesign" : "") + (noSettings ? ", nosettings" : "") + ")");

    // 1. Character design (a brand new character gets the screen a moment after login)
    if (!isAppearanceScreen(c) && onTutorialIsland(c)) {
      long end = System.currentTimeMillis() + 3000;
      while (!isAppearanceScreen(c) && System.currentTimeMillis() < end) c.sleep(100);
    }
    if (isAppearanceScreen(c)) {
      if (keepDesign) sendCurrentAppearance(c);
      else sendRandomAppearance(c);
      long end = System.currentTimeMillis() + 5000;
      while (isAppearanceScreen(c) && System.currentTimeMillis() < end) c.sleep(100);
      c.sleep(1200);
    } else {
      log("Character design already done, skipping it");
    }

    // 2. Tutorial Island
    if (onTutorialIsland(c)) {
      if (!Boolean.TRUE.equals(c.getMudClientValue("insideTutorial")))
        log("@yel@The server is not offering the tutorial skip yet, trying anyway");
      log("Skipping Tutorial Island...");
      long end = System.currentTimeMillis() + 30_000;
      long nextSend = 0;
      while (onTutorialIsland(c) && System.currentTimeMillis() < end && c.isRunning()) {
        if (System.currentTimeMillis() >= nextSend) {
          c.skipTutorialIsland();
          nextSend = System.currentTimeMillis() + 4000;
        }
        c.sleep(250);
      }
      if (onTutorialIsland(c)) {
        log("@red@Still on Tutorial Island: the server did not accept the skip request (is the"
            + " tutorial skip option enabled on this server?)");
      } else {
        c.sleep(1200);
        log("Tutorial skipped, now at " + c.currentX() + ", " + c.currentY());
      }
    } else {
      log("Not on Tutorial Island, skipping that step");
    }

    // 3. Settings
    if (noSettings) log("nosettings: leaving camera/fog/sound/login box as they are");
    else applySettings(c);

    // 4. Login message boxes (can arrive a few seconds after the tutorial skip)
    log("Closing login message boxes...");
    int closed = 0;
    long end = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < end && c.isLoggedIn() && c.isRunning()) {
      closed += closeLoginBoxes(c);
      c.sleep(250);
    }
    closed += closeLoginBoxes(c);
    log(closed > 0 ? "Closed " + closed + " login message box(es)" : "No login message boxes open");
  }

  /** keepdesign: accept what the design screen currently shows (the client's own fields). */
  private void sendCurrentAppearance(Controller c) {
    mudclient mud = c.getMud();
    int gender = getInt(mud, "appearanceHeadGender", 1);
    int head = getInt(mud, "appearanceHeadType", 0);
    int body = getInt(mud, "appearanceBodyGender", 1);
    int hair = getInt(mud, "appearanceHairColour", 2);
    int top = getInt(mud, "characterTopColour", 8);
    int bottom = getInt(mud, "characterBottomColour", 14);
    int skin = getInt(mud, "appearanceSkinColour", 0);
    log(String.format("keepdesign: accepting the shown design (head %d, body %d, hair %d, top %d, bottom %d, skin %d)",
        head, body, hair, top, bottom, skin));
    sendAppearance(c, gender, head, body, hair, top, bottom, skin);
  }

  private void sendAppearance(Controller c, int gender, int head, int body, int hair, int top, int bottom, int skin) {
    mudclient mud = c.getMud();
    waitForStream(c);
    mud.packetHandler.getClientStream().newPacket(OPCODE_APPEARANCE);
    mud.packetHandler.getClientStream().bufferBits.putByte(gender);
    mud.packetHandler.getClientStream().bufferBits.putByte(head);
    mud.packetHandler.getClientStream().bufferBits.putByte(body);
    mud.packetHandler.getClientStream().bufferBits.putByte(LEGS);
    mud.packetHandler.getClientStream().bufferBits.putByte(hair);
    mud.packetHandler.getClientStream().bufferBits.putByte(top);
    mud.packetHandler.getClientStream().bufferBits.putByte(bottom);
    mud.packetHandler.getClientStream().bufferBits.putByte(skin);
    mud.packetHandler.getClientStream().bufferBits.putByte(0);
    mud.packetHandler.getClientStream().bufferBits.putByte(0);
    mud.packetHandler.getClientStream().finishPacket();
    mud.setShowAppearanceChange(false);
    log("Character design accepted");
  }

  private int getInt(mudclient mud, String field, int fallback) {
    try {
      Field f = mudclient.class.getDeclaredField(field);
      f.setAccessible(true);
      return f.getInt(mud);
    } catch (Exception e) {
      return fallback;
    }
  }

  /**
   * Closes the welcome box (last login / recovery info, showDialogMessage) and the server message
   * box (showDialogServerMessage, the server's message-of-the-day/notice popup), the same way
   * clicking them away does.
   *
   * @return how many were open
   */
  private int closeLoginBoxes(Controller c) {
    mudclient mud = c.getMud();
    int closed = 0;
    if (Boolean.TRUE.equals(c.getMudClientValue("showDialogMessage"))) {
      mud.setShowDialogMessage(false);
      closed++;
    }
    if (Boolean.TRUE.equals(c.getMudClientValue("showDialogServerMessage"))) {
      mud.setShowDialogServerMessage(false);
      closed++;
    }
    return closed;
  }

  // ---------------------------------------------------------------------------------------------
  // Character design
  // ---------------------------------------------------------------------------------------------

  private boolean isAppearanceScreen(Controller c) {
    return Boolean.TRUE.equals(c.getMudClientValue("showAppearanceChange"));
  }

  private void sendRandomAppearance(Controller c) {
    ThreadLocalRandom r = ThreadLocalRandom.current();
    mudclient mud = c.getMud();

    int gender = 1 + r.nextInt(2); // 1 = male, 2 = female (the client's appearanceHeadGender)
    List<Integer> heads = validTypes(SERVER_HEADS, 1, gender);
    List<Integer> bodies = validTypes(SERVER_BODIES, 2, gender);
    if (bodies.isEmpty()) bodies.add(gender == 1 ? 1 : 4);
    if (heads.isEmpty()) for (int h : SERVER_HEADS) heads.add(h);
    int body = bodies.get(r.nextInt(bodies.size()));
    int head = heads.get(r.nextInt(heads.size()));
    // The server flags head 6 with the female body as suspicious, so avoid that combination
    for (int i = 0; i < 20 && head == 6 && body == 4; i++)
      head = heads.get(r.nextInt(heads.size()));
    if (head == 6 && body == 4) head = 3;

    int hair = r.nextInt(Math.min(10, arrayLength(mud, "playerHairColors", 10)));
    int clothing = Math.min(15, arrayLength(mud, "playerClothingColors", 15)); // server: <= 14
    int top = r.nextInt(clothing);
    int bottom = r.nextInt(clothing);
    int skin = randomSkin(mud, r);

    log(
        String.format(
            "Random design: %s, head %d, body %d, hair %d, top %d, bottom %d, skin %d",
            gender == 1 ? "male" : "female", head, body, hair, top, bottom, skin));

    // Show it on the design screen as well (same fields the arrows change)
    setInt(mud, "appearanceHeadGender", gender);
    setInt(mud, "appearanceHeadType", head);
    setInt(mud, "appearanceBodyGender", body);
    setInt(mud, "appearanceHairColour", hair);
    setInt(mud, "characterTopColour", top);
    setInt(mud, "characterBottomColour", bottom);
    setInt(mud, "appearanceSkinColour", skin);

    sendAppearance(c, gender, head, body, hair, top, bottom, skin);
  }

  /**
   * Head (part 1) or body (part 2) animation ids allowed for this gender by the client's animation
   * data (the same check the design screen's arrows use), limited to ids the server accepts.
   */
  private List<Integer> validTypes(int[] serverIds, int part, int gender) {
    List<Integer> out = new ArrayList<>();
    for (int id : serverIds) {
      try {
        if (id >= EntityHandler.animationCount()) continue;
        int model = EntityHandler.getAnimationDef(id).getGenderModel();
        if ((model & 3) == part && (model & gender * 4) != 0) out.add(id);
      } catch (Throwable ignored) {
        // Animation data not available: fall back to the defaults below
      }
    }
    return out;
  }

  /**
   * One of the five original skin colours. Others need to be unlocked on the server, which would
   * reject (and flag) the appearance if they were not.
   */
  private int randomSkin(mudclient mud, ThreadLocalRandom r) {
    int count = Math.min(5, arrayLength(mud, "playerSkinColors", 5));
    List<Integer> unlocked = new ArrayList<>();
    boolean[] flags = mud.unlockedSkinColours;
    for (int i = 0; i < count; i++)
      if (flags == null || (i < flags.length && flags[i])) unlocked.add(i);
    if (unlocked.isEmpty()) return r.nextInt(count);
    return unlocked.get(r.nextInt(unlocked.size()));
  }

  // ---------------------------------------------------------------------------------------------
  // Tutorial Island
  // ---------------------------------------------------------------------------------------------

  private boolean onTutorialIsland(Controller c) {
    if (Boolean.TRUE.equals(c.getMudClientValue("insideTutorial"))) return true;
    // Server: Point.onTutorialIsland() covers x 190..240 and y 720..770
    int x = c.currentX();
    int y = c.currentY();
    return x >= 190 && x <= 244 && y >= 719 && y <= 770;
  }

  // ---------------------------------------------------------------------------------------------
  // Settings
  // ---------------------------------------------------------------------------------------------

  private void applySettings(Controller c) {
    mudclient mud = c.getMud();

    // Manual camera (camera mode "auto" off)
    if (Boolean.FALSE.equals(c.getMudClientValue("optionCameraModeAuto"))) {
      log("Camera is already manual");
    } else {
      mud.setOptionCameraModeAuto(false);
      sendSetting(c, SETTING_CAMERA_AUTO, false);
      log("Camera set to manual");
    }

    // Sound off
    if (mudclient.optionSoundDisabled) {
      log("Sound is already off");
    } else {
      mud.setOptionSoundDisabled(true);
      sendSetting(c, SETTING_SOUND_DISABLED, true);
      log("Sound turned off");
    }

    // Fog off
    if (!Config.S_FOG_TOGGLE) {
      log("@yel@This server has no fog setting");
    } else if (Config.C_HIDE_FOG) {
      log("Fog is already off");
    } else {
      mud.setOptionHideFog(true);
      sendSetting(c, SETTING_HIDE_FOG, true);
      log("Fog turned off");
    }

    // Hide login box
    if (!Config.S_HIDE_LOGIN_BOX) {
      log("@yel@This server has no hide login box setting");
    } else if (Config.C_HIDE_LOGIN_BOX) {
      log("Login box is already hidden");
    } else {
      mud.setHideLoginBox(true);
      sendSetting(c, SETTING_HIDE_LOGIN_BOX, true);
      log("Login box hidden");
    }
  }

  /** Same packet the client's settings menu sends: setting index, then 1/0. */
  private void sendSetting(Controller c, int index, boolean value) {
    mudclient mud = c.getMud();
    waitForStream(c);
    mud.packetHandler.getClientStream().newPacket(OPCODE_GAME_SETTING);
    mud.packetHandler.getClientStream().bufferBits.putByte(index);
    mud.packetHandler.getClientStream().bufferBits.putByte(value ? 1 : 0);
    mud.packetHandler.getClientStream().finishPacket();
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  private void waitForStream(Controller c) {
    mudclient mud = c.getMud();
    long end = System.currentTimeMillis() + 5000;
    while (mud.packetHandler.getClientStream().hasFinishedPackets()
        && System.currentTimeMillis() < end) c.sleep(1);
  }

  private int arrayLength(mudclient mud, String field, int fallback) {
    try {
      Field f = mudclient.class.getDeclaredField(field);
      f.setAccessible(true);
      Object value = f.get(mud);
      if (value instanceof int[] && ((int[]) value).length > 0) return ((int[]) value).length;
    } catch (Exception ignored) {
      // fall back
    }
    return fallback;
  }

  private void setInt(mudclient mud, String field, int value) {
    try {
      Field f = mudclient.class.getDeclaredField(field);
      f.setAccessible(true);
      f.setInt(mud, value);
    } catch (Exception ignored) {
      // Only cosmetic (the preview on the design screen)
    }
  }

  private void log(String message) {
    String plain = message.replaceAll("@...@", "");
    Main.log("[NewAccountSetup] " + plain);
    System.out.println("[NewAccountSetup] " + plain);
    try {
      bot.probe.Probe.recordEvent("new_account_setup", plain);
    } catch (Throwable ignored) {
      // older client without the probe
    }
    if (c != null && c.isLoggedIn()) c.displayMessage("@cya@[NewAccountSetup] @whi@" + message);
  }
}
