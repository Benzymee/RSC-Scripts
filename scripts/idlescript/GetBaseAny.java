package scripting.idlescript;

import bot.Main;
import bot.tabs.MassStart;
import bot.tabs.MassStartSettings;
import bot.ui.scriptselector.models.Category;
import bot.ui.scriptselector.models.Parameter;
import bot.ui.scriptselector.models.ScriptInfo;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.ThreadLocalRandom;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import models.entities.ItemId;
import models.entities.NpcId;
import models.entities.SceneryId;
import models.entities.SkillId;
import orsc.Config;
import orsc.ORSCharacter;
import orsc.mudclient;

/**
 * GetBaseAny - IdleRSC port of the Plutonium script get_base_50s.py (with its sub-scripts
 * skip_tutorial_script.py, get_acc_builder_equip.py and full_shrimp_port_sarim.py).
 *
 * <p>Takes a brand new account from Tutorial Island to Port Sarim:
 *
 * <ol>
 *   <li>Accepts the character-design screen (random look, Regular account, world XP rate) and skips
 *       Tutorial Island.
 *   <li>Pickpockets men in Lumbridge for coins and buys a tinderbox (Lumbridge general store), a
 *       bronze axe (Bob's Axes) and a small fishing net (Gerrant, Port Sarim). A sleeping bag is
 *       only bought on worlds with fatigue (or with the "bag" parameter).
 *   <li>Nets shrimp south-west of Port Sarim, chops a tree, lights a fire and cooks the shrimp
 *       until the inventory holds 25+ items with no raw/burnt fish.
 *   <li>Each inventory of cooked shrimp is then banked at the closest bank (Draynor), taken back to
 *       Lumbridge and eaten to heal while pickpocketing men, or dropped (start GUI, or the "bank",
 *       "heal:&lt;hp&gt;" and "drop" parameters). With "loop" it repeats until Fishing, Cooking,
 *       Firemaking and Woodcutting all reach the chosen level (default 50, "level:&lt;n&gt;");
 *       otherwise it stops after one cycle. A death ("Oh dear") drops the current trip, walks back
 *       from Lumbridge and buys any kit that was lost.
 * </ol>
 *
 * <p>Started with no parameters it shows a setup window. Started with parameters but without a
 * shrimp mode it behaves exactly like the original: keep the shrimp and stop after one inventory
 * (or drop them and repeat with "loop").
 *
 * <p>Like the original, it drops every item that is not part of the kit while gathering coins, so
 * only run it on a fresh account.
 *
 * <p>Original Plutonium scripts from OpenRSC-Plutonium-Scripts; ported to IdleRSC for RSC Coleslaw.
 */
public class GetBaseAny extends IdleScript implements MassStartSettings {
  public static final ScriptInfo info =
      new ScriptInfo(
          new Category[] {
            Category.MISCELLANEOUS,
            Category.FISHING,
            Category.COOKING,
            Category.FIREMAKING,
            Category.WOODCUTTING,
            Category.THIEVING
          },
          "RLN (Plutonium original), IdleRSC port",
          "Fresh account: skips Tutorial Island, thieves coins in Lumbridge, buys tinderbox, axe and"
              + " net, then fishes/cooks shrimp south-west of Port Sarim. Only use on a new"
              + " account: it drops non-kit items while thieving.",
          new Parameter[] {
            new Parameter(
                "bank", "Bank each inventory of cooked shrimp at the closest bank (Draynor)."),
            new Parameter(
                "heal:<hp>",
                "Take the cooked shrimp to Lumbridge and pickpocket men, eating a shrimp at or below"
                    + " <hp> hits (default "
                    + GetBaseAny.DEFAULT_HEAL_HP
                    + "). Fish again when the shrimp run out."),
            new Parameter("drop", "Drop each inventory of cooked shrimp."),
            new Parameter(
                "loop",
                "Repeat until Fishing, Cooking, Firemaking and Woodcutting all reach the level"
                    + " (default "
                    + GetBaseAny.DEFAULT_TARGET_LEVEL
                    + ", or level:<n>). Default: stop after one cycle."),
            new Parameter(
                "level:<n>",
                "With loop, stop when those four skills all reach <n> (1-99, default "
                    + GetBaseAny.DEFAULT_TARGET_LEVEL
                    + ")."),
            new Parameter(
                "bag", "Buy a sleeping bag even if the world has fatigue disabled (Coleslaw).")
          });

  // ---- Item / NPC / scenery ids (IdleRSC constants; verified against OpenRSC server defs) ----
  private static final int COINS = ItemId.COINS.getId(); // 10
  private static final int SLEEPING_BAG = ItemId.SLEEPING_BAG.getId(); // 1263
  private static final int TINDERBOX = ItemId.TINDERBOX.getId(); // 166
  private static final int BRONZE_AXE = ItemId.BRONZE_AXE.getId(); // 87
  private static final int FISHING_NET = ItemId.NET.getId(); // 376
  private static final int LOGS = ItemId.LOGS.getId(); // 14
  private static final int RAW_SHRIMP = ItemId.RAW_SHRIMP.getId(); // 349
  private static final int COOKED_SHRIMP = ItemId.SHRIMP.getId(); // 350
  private static final int BURNT_FISH = ItemId.BURNT_FISH.getId(); // 353
  // The Plutonium script used 321 here, which is actually "Pizza Base". Raw anchovies are 351.
  private static final int RAW_ANCHOVIES = ItemId.RAW_ANCHOVIES.getId(); // 351

  private static final int MAN = NpcId.MAN.getId(); // 11
  private static final int[] GENERAL_STORE_NPCS = {
    NpcId.SHOP_ASSISTANT_LUMBRIDGE.getId(), NpcId.SHOPKEEPER_LUMBRIDGE.getId()
  }; // 83, 55
  private static final int BOB = NpcId.BOB.getId(); // 1
  private static final int GERRANT = NpcId.GERRANT.getId(); // 167

  private static final int[] TREE_IDS = {
    SceneryId.POINTY_TREE.getId(), SceneryId.LEAFY_TREE.getId()
  }; // 0, 1
  private static final int FISH_SPOT_ID = SceneryId.FISH_NET_BAIT.getId(); // 193
  private static final int FIRE_ID = SceneryId.FIRE.getId(); // 97
  private static final int CLOSED_DOOR_ID = 2;

  // ---- Coordinates (from the original script; checked against OpenRSC location data) ----
  private static final int[] THIEVE_PT = {128, 641};
  private static final int[] SHOP_PT = {133, 643};
  private static final int[] BOB_PT = {122, 668};
  private static final int[] FISH_STORE_PT = {277, 649};
  private static final int[] FISH_SPOT = {310, 686};
  // Doors that can be closed by other players on the way to each shop.
  private static final int[][] GENERAL_STORE_DOORS = {{132, 641}};
  private static final int[][] BOB_DOORS = {{122, 669}, {124, 664}};
  private static final int[][] FISH_STORE_DOORS = {{277, 651}};

  /**
   * Gate-free road from the Lumbridge men to the Port Sarim fishing spot (webwalker graph nodes,
   * listed in walking order). The webwalker's shortest Lumbridge -> Port Sarim path cuts through the
   * north sheep pen gate, and right after it the nearest graph node flips to the chicken pen node,
   * so it kept opening the chicken pen gate and walking back (see walkRoute). Following this road
   * needs no gate at all; only the shop doors are opened, and only when they block the shop.
   */
  private static final int[][] ROUTE = {
    {128, 641}, // Lumbridge men (THIEVE_PT)
    {132, 635},
    {130, 625},
    {138, 617},
    {153, 615}, // road north of the sheep/chicken pens (no gate)
    {167, 611},
    {173, 608},
    {184, 606},
    {199, 605},
    {210, 606},
    {223, 611},
    {232, 610},
    {243, 610},
    {259, 611},
    {260, 626},
    {273, 629},
    {277, 644}, // Port Sarim, north of Gerrant's
    {284, 652},
    {291, 659},
    {297, 665},
    {309, 668},
    {309, 678},
    {310, 686} // FISH_SPOT
  };

  // Prices used as thresholds by the original script.
  private static final int PRICE_SLEEPING_BAG = 39;
  private static final int PRICE_TINDERBOX = 1;
  private static final int PRICE_BRONZE_AXE = 16;
  private static final int PRICE_NET = 5;

  private static final int[] HEAD_TYPES = {0, 3, 5, 6, 7};
  private static final int[] BODY_TYPES = {1, 4};

  private static final int[] LEVEL_SKILLS = {
    SkillId.FISHING.getId(),
    SkillId.COOKING.getId(),
    SkillId.FIREMAKING.getId(),
    SkillId.WOODCUTTING.getId()
  };
  private static final int DEFAULT_TARGET_LEVEL = 50;
  private static final int MIN_TARGET_LEVEL = 1;
  private static final int MAX_TARGET_LEVEL = 99;

  // What to do with each inventory of cooked shrimp.
  private static final String MODE_BANK = "bank";
  private static final String MODE_HEAL = "heal";
  private static final String MODE_DROP = "drop";
  // Parameters given without a shrimp mode: the original behaviour (keep and stop, or drop+loop).
  private static final String MODE_KEEP = "keep";
  private static final int DEFAULT_HEAL_HP = 6;
  private static final int MIN_HEAL_HP = 1;
  private static final int MAX_HEAL_HP = 98;

  // ---- Settings (plain fields so the tab client's mass start can copy them) ----
  private String shrimpMode = MODE_DROP;
  private int healAtHp = DEFAULT_HEAL_HP;
  private boolean loopMode = false;
  private int targetLevel = DEFAULT_TARGET_LEVEL;
  private boolean forceBag = false;
  private volatile boolean guiSetup = false; // the setup window has been opened
  private volatile boolean scriptStarted = false; // settings chosen (GUI Start or parameters)
  private JFrame scriptFrame = null;
  private boolean prefilled = false; // the setup window starts from parsed parameters

  // ---- State ----
  private boolean initialised = false;
  private boolean bankTrip = false; // carrying cooked shrimp to the bank
  private final java.util.Set<Integer> notBankable = new java.util.HashSet<>();
  private boolean cookedThisRun = false; // this run has cooked shrimp itself
  private boolean cycleIsOwn = false; // the inventory being handled was cooked by this run
  private boolean foodTrip = false; // thieving in Lumbridge with cooked shrimp as food
  private int shrimpBanked = 0;
  private int shrimpEaten = 0;
  private boolean waitedForAppearance = false;
  private volatile boolean unstuck = false;
  private boolean fishingClicked = false; // last skilling click was the fishing spot
  private String status = "Starting";
  private int cookedInventories = 0;
  private int deaths = 0;
  private volatile boolean diedFlag = false;
  private long lastDeathAt = 0;
  private long startTime = 0;

  @Override
  public int start(String[] parameters) {
    if (!initialised) init(parameters);
    if (!controller.isRunning()) return 1000;
    if (!scriptStarted) {
      if (!guiSetup) {
        guiSetup = true;
        status = "Waiting for setup window";
        SwingUtilities.invokeLater(this::setupGUI);
      }
      return 500;
    }
    if (!controller.isLoggedIn()) return 1000;

    controller.setAutoLogin(true);

    if (noteDeath()) return 1500;

    // A new account (or one that never confirmed its look) gets the character-design screen.
    if (isAppearanceScreen()) {
      if (!waitedForAppearance) {
        waitedForAppearance = true;
        log("Waiting for appearance packet");
        return 2000;
      }
      sendRandomAppearance();
      return 2000;
    }

    // Idle-logout protection: IdleRSC sets needToMove when the server says
    // "@cya@You have been standing here for 10 mins!" (replaces move_timer/walk_adjacent).
    if (controller.getNeedToMove()) {
      log("Moving otherwise we'll log out..");
      controller.moveCharacter();
      return 1000;
    }

    // Uranium (and any world with fatigue) needs the sleeping bag. The client solves the
    // captcha while sleepHandler waits. Coleslaw reports fatigue 0, so this does not run there.
    if (fatigueEnabled()
        && (controller.getShouldSleep() || controller.getFatigue() > 95)
        && has(SLEEPING_BAG)) {
      log("Sleeping. The captcha is solved while the bag is in use.");
      controller.sleepHandler(false);
      return 5000;
    }

    if (!tutorialDone()) {
      status = "Tutorial";
      return tutorial();
    }
    // A death drops the kit on the ground. Buy it again before banking, eating or fishing.
    if (!accBuilderDone()) {
      bankTrip = false;
      foodTrip = false;
      status = deaths > 0 ? "Replacing kit after death" : "Getting acc builder equip";
      return accBuilder();
    }
    if (bankTrip) return bankShrimp();
    if (foodTrip) return healTrip();
    if (!shrimpDone()) {
      status = "Fishing shrimp";
      return shrimp();
    }

    // An inventory of cooked shrimp is ready.
    cycleIsOwn = cookedThisRun;
    int cooked = controller.getInventoryItemCount(COOKED_SHRIMP);
    if (cycleIsOwn) {
      cookedInventories++;
    } else {
      // e.g. a previous run stopped with a full inventory: handle it, never just quit.
      log(
          "Inventory already holds "
              + controller.getInventoryItemCount()
              + " items ("
              + cooked
              + " cooked shrimp) at start. Handling it ("
              + modeText()
              + "), then doing a full fish/cook cycle.");
      if (MODE_KEEP.equals(shrimpMode) && !loopMode) {
        bankTrip = true; // keep mode: bank them rather than lose them or quit
        return 600;
      }
    }
    switch (shrimpMode) {
      case MODE_BANK:
        log("Inventory of cooked shrimp done (" + cookedInventories + "). Banking " + cooked + ".");
        bankTrip = true;
        return 600;
      case MODE_HEAL:
        log(
            "Inventory of cooked shrimp done ("
                + cookedInventories
                + "). Pickpocketing men in Lumbridge with "
                + cooked
                + " shrimp as food.");
        foodTrip = true;
        return 600;
      case MODE_DROP:
        log(
            "Inventory of cooked shrimp done ("
                + cookedInventories
                + "). Dropping "
                + cooked
                + ".");
        status = "Dropping shrimp";
        dropAll(COOKED_SHRIMP);
        return afterCycle();
      default: // MODE_KEEP: the original behaviour
        if (!cycleIsOwn || (loopMode && !levelGoalReached())) {
          log(
              "Inventory of cooked shrimp done ("
                  + cookedInventories
                  + "). Dropping and continuing.");
          dropAll(COOKED_SHRIMP);
          return 1200;
        }
        return finish("Kept one inventory of cooked shrimp (original behaviour, loop is off)");
    }
  }

  /** One cycle (bank / food trip / drop) is over: go fish again, or stop. */
  private int afterCycle() {
    if (loopMode) {
      if (levelGoalReached()) {
        return finish(
            "Fishing, Cooking, Firemaking and Woodcutting are all "
                + targetLevel
                + " (loop goal)");
      }
      status = "Back to fishing";
      return 1200;
    }
    if (!cycleIsOwn) {
      log("Leftover inventory handled; now fishing and cooking one inventory of our own.");
      status = "Back to fishing";
      return 1200;
    }
    return finish("One cycle complete (" + modeText() + ") and loop is off");
  }

  private int finish(String reason) {
    status = "Done";
    log(
        "Stopping: "
            + reason
            + ". Skills Fish/Cook/FM/WC = "
            + controller.getBaseStat(SkillId.FISHING.getId())
            + "/"
            + controller.getBaseStat(SkillId.COOKING.getId())
            + "/"
            + controller.getBaseStat(SkillId.FIREMAKING.getId())
            + "/"
            + controller.getBaseStat(SkillId.WOODCUTTING.getId())
            + ", "
            + controller.getInventoryItemCount()
            + " items in inventory. Logging out.");
    controller.setAutoLogin(false);
    controller.logout();
    controller.stop();
    return 10000;
  }

  private void init(String[] parameters) {
    initialised = true;
    startTime = System.currentTimeMillis();
    controller.setBatchBars(true);
    if (scriptStarted) {
      // Settings were copied in (mass start from the tab client): no window, no parsing.
      announceSettings();
      return;
    }
    boolean anyParameter = false;
    String mode = null;
    if (parameters != null) {
      for (int i = 0; i < parameters.length; i++) {
        String p = parameters[i];
        if (p == null || p.trim().isEmpty()) continue;
        anyParameter = true;
        String s = p.trim().toLowerCase();
        if (s.equals("loop")) loopMode = true;
        else if (s.equals("bag")) forceBag = true;
        else if (s.startsWith("level:") || s.startsWith("level=") || s.startsWith("cap:")) {
          String n = s.substring(s.indexOf(':') >= 0 ? s.indexOf(':') + 1 : s.indexOf('=') + 1);
          if (isInt(n) && validTargetLevel(Integer.parseInt(n.trim()))) {
            targetLevel = Integer.parseInt(n.trim());
          } else {
            controller.displayMessage(
                "@red@GetBaseAny: @whi@bad level '"
                    + n
                    + "' (use "
                    + MIN_TARGET_LEVEL
                    + "-"
                    + MAX_TARGET_LEVEL
                    + "), using "
                    + targetLevel);
          }
        }
        else if (s.equals(MODE_BANK)) mode = MODE_BANK;
        else if (s.equals(MODE_DROP)) mode = MODE_DROP;
        else if (s.equals(MODE_HEAL) || s.startsWith("heal:") || s.startsWith("heal=")) {
          mode = MODE_HEAL;
          String hp = s.length() > 5 ? s.substring(5) : "";
          // Also accept "heal 6" (two separate parameters).
          if (hp.isEmpty() && i + 1 < parameters.length && isInt(parameters[i + 1])) {
            hp = parameters[++i].trim();
          }
          if (!hp.isEmpty()) {
            if (isInt(hp) && validHealHp(Integer.parseInt(hp.trim()))) {
              healAtHp = Integer.parseInt(hp.trim());
            } else {
              controller.displayMessage(
                  "@red@GetBaseAny: @whi@bad heal HP '"
                      + hp
                      + "' (use "
                      + MIN_HEAL_HP
                      + "-"
                      + MAX_HEAL_HP
                      + "), using "
                      + healAtHp);
            }
          }
        } else {
          controller.displayMessage("@red@GetBaseAny: @whi@unknown parameter '" + p.trim() + "'");
        }
      }
    }
    if (!anyParameter) return; // the setup window chooses the settings
    if (MassStart.isLeaderSetup()) {
      // Mass start: the first tab always shows the setup window, pre-filled with the parameters.
      // The window has no "keep" choice, so parameters without a shrimp mode pre-select Drop.
      shrimpMode = mode != null ? mode : MODE_DROP;
      prefilled = true;
      return;
    }
    shrimpMode = mode != null ? mode : MODE_KEEP;
    scriptStarted = true;
    announceSettings();
  }

  private void announceSettings() {
    controller.displayMessage(
        "@gre@GetBaseAny started. @whi@Shrimp: @yel@"
            + modeText()
            + " @whi@loop="
            + loopMode
            + (loopMode ? " to " + targetLevel : "")
            + " bag="
            + forceBag);
    controller.displayMessage(
        "@red@WARNING: @whi@drops non-kit items while thieving. Use on a fresh account only.");
    log(
        "Shrimp mode: "
            + modeText()
            + ", loop="
            + loopMode
            + ", level="
            + targetLevel
            + ", bag="
            + forceBag);
  }

  private static boolean isInt(String s) {
    if (s == null) return false;
    try {
      Integer.parseInt(s.trim());
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private static boolean validHealHp(int hp) {
    return hp >= MIN_HEAL_HP && hp <= MAX_HEAL_HP;
  }

  private static boolean validTargetLevel(int level) {
    return level >= MIN_TARGET_LEVEL && level <= MAX_TARGET_LEVEL;
  }

  private String modeText() {
    switch (shrimpMode) {
      case MODE_BANK:
        return "Bank at nearest bank";
      case MODE_HEAL:
        return "Eat at " + healAtHp + " HP (Lumbridge men)";
      case MODE_DROP:
        return "Drop";
      default:
        return loopMode ? "Drop (original)" : "Keep (original)";
    }
  }

  // ===========================================================================================
  // Start GUI
  // ===========================================================================================

  private void setupGUI() {
    Color bg = Main.primaryBG != null ? Main.primaryBG : new Color(40, 40, 40);
    Color fg = Main.primaryFG != null ? Main.primaryFG : new Color(230, 230, 230);

    JFrame frame = new JFrame("GetBaseAny - Setup");
    scriptFrame = frame;
    frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

    JPanel content = new JPanel(new BorderLayout(0, 10));
    content.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

    JPanel header = new JPanel(new GridBagLayout());
    JLabel title = new JLabel("GetBaseAny");
    title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 4f));
    JLabel blurb =
        new JLabel(
            "<html>Fresh account: skips Tutorial Island, thieves coins, buys a tinderbox, axe and"
                + " net,<br>then fishes and cooks shrimp south-west of Port Sarim.</html>");
    GridBagConstraints hc = new GridBagConstraints();
    hc.gridx = 0;
    hc.anchor = GridBagConstraints.WEST;
    hc.gridy = 0;
    header.add(title, hc);
    hc.gridy = 1;
    hc.insets = new Insets(4, 0, 0, 0);
    header.add(blurb, hc);
    content.add(header, BorderLayout.NORTH);

    JPanel options = new JPanel(new GridBagLayout());
    GridBagConstraints c = new GridBagConstraints();
    c.gridx = 0;
    c.gridy = 0;
    c.gridwidth = 2;
    c.anchor = GridBagConstraints.WEST;
    c.insets = new Insets(2, 0, 2, 0);

    JLabel shrimpLabel = new JLabel("Each inventory of cooked shrimp:");
    shrimpLabel.setFont(shrimpLabel.getFont().deriveFont(Font.BOLD));
    options.add(shrimpLabel, c);

    JRadioButton bankButton = new JRadioButton("Bank it at the closest bank (Draynor)");
    JRadioButton healButton =
        new JRadioButton("Keep it and eat it to heal while pickpocketing men in Lumbridge");
    JRadioButton dropButton = new JRadioButton("Drop it");
    ButtonGroup group = new ButtonGroup();
    group.add(bankButton);
    group.add(healButton);
    group.add(dropButton);
    if (MODE_BANK.equals(shrimpMode)) bankButton.setSelected(true);
    else if (MODE_HEAL.equals(shrimpMode)) healButton.setSelected(true);
    else dropButton.setSelected(true);

    c.insets = new Insets(2, 12, 2, 0);
    c.gridy++;
    options.add(bankButton, c);
    c.gridy++;
    options.add(healButton, c);

    JPanel healRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    JLabel healLabel = new JLabel("Heal at HP:");
    JSpinner healSpinner =
        new JSpinner(new SpinnerNumberModel(healAtHp, MIN_HEAL_HP, MAX_HEAL_HP, 1));
    JSpinner.NumberEditor editor = new JSpinner.NumberEditor(healSpinner, "#");
    editor.getTextField().setColumns(3);
    healSpinner.setEditor(editor);
    JLabel healHint =
        new JLabel(
            "(" + MIN_HEAL_HP + "-" + MAX_HEAL_HP + "; a shrimp heals 3, new accounts have 10)");
    healHint.setFont(healHint.getFont().deriveFont(Font.ITALIC));
    healRow.add(healLabel);
    healRow.add(healSpinner);
    healRow.add(healHint);
    c.insets = new Insets(0, 36, 4, 0);
    c.gridy++;
    options.add(healRow, c);

    c.insets = new Insets(2, 12, 2, 0);
    c.gridy++;
    options.add(dropButton, c);

    JCheckBox loopBox =
        new JCheckBox(
            "Repeat until Fishing, Cooking, Firemaking and Woodcutting reach",
            !prefilled || loopMode);
    JSpinner levelSpinner = new JSpinner(new SpinnerNumberModel(targetLevel, MIN_TARGET_LEVEL, MAX_TARGET_LEVEL, 1));
    JSpinner.NumberEditor levelEditor = new JSpinner.NumberEditor(levelSpinner, "#");
    levelEditor.getTextField().setColumns(3);
    levelSpinner.setEditor(levelEditor);
    levelSpinner.setToolTipText("Base level to stop at when Repeat is on (1-99)");
    JPanel loopRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    loopRow.add(loopBox);
    loopRow.add(levelSpinner);
    JCheckBox bagBox = new JCheckBox("Buy a sleeping bag even if the world has no fatigue");
    bagBox.setSelected(forceBag);
    c.insets = new Insets(10, 0, 2, 0);
    c.gridy++;
    options.add(loopRow, c);
    c.insets = new Insets(2, 0, 2, 0);
    c.gridy++;
    options.add(bagBox, c);

    JLabel error = new JLabel(" ");
    error.setForeground(new Color(0xFF6B6B));
    c.gridy++;
    options.add(error, c);
    content.add(options, BorderLayout.CENTER);

    Runnable updateHeal =
        () -> {
          boolean on = healButton.isSelected();
          healLabel.setEnabled(on);
          healSpinner.setEnabled(on);
          healHint.setEnabled(on);
        };
    bankButton.addActionListener(e -> updateHeal.run());
    healButton.addActionListener(e -> updateHeal.run());
    dropButton.addActionListener(e -> updateHeal.run());
    updateHeal.run();

    JButton startButton = new JButton("Start");
    JButton cancelButton = new JButton("Cancel");
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    buttons.add(cancelButton);
    buttons.add(startButton);
    content.add(buttons, BorderLayout.SOUTH);

    // Closing the window without pressing Start cancels the script.
    final boolean[] confirmed = {false};
    frame.addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosed(WindowEvent e) {
            if (!confirmed[0] && !scriptStarted) {
              controller.displayMessage("@red@GetBaseAny cancelled.");
              log("Stopping: setup window closed without pressing Start.");
              controller.stop();
            }
          }
        });

    startButton.addActionListener(
        e -> {
          int hp;
          try {
            healSpinner.commitEdit();
            hp = ((Number) healSpinner.getValue()).intValue();
          } catch (java.text.ParseException ex) {
            hp = -1;
          }
          if (healButton.isSelected() && !validHealHp(hp)) {
            error.setText(
                "Heal at HP must be a whole number from " + MIN_HEAL_HP + " to " + MAX_HEAL_HP);
            healSpinner.requestFocusInWindow();
            return;
          }
          if (bankButton.isSelected()) shrimpMode = MODE_BANK;
          else if (healButton.isSelected()) shrimpMode = MODE_HEAL;
          else shrimpMode = MODE_DROP;
          if (validHealHp(hp)) healAtHp = hp;
          int level;
          try {
            levelSpinner.commitEdit();
            level = ((Number) levelSpinner.getValue()).intValue();
          } catch (java.text.ParseException ex) {
            level = -1;
          }
          if (!validTargetLevel(level)) {
            error.setText(
                "Level must be a whole number from "
                    + MIN_TARGET_LEVEL
                    + " to "
                    + MAX_TARGET_LEVEL);
            levelSpinner.requestFocusInWindow();
            return;
          }
          targetLevel = level;
          loopMode = loopBox.isSelected();
          forceBag = bagBox.isSelected();
          confirmed[0] = true;
          scriptStarted = true;
          announceSettings();
          frame.dispose();
        });
    cancelButton.addActionListener(e -> frame.dispose());

    frame.setContentPane(content);
    applyTheme(content, bg, fg);
    error.setForeground(new Color(0xFF6B6B));
    frame.getContentPane().setBackground(bg);
    frame.getRootPane().setDefaultButton(startButton);
    frame.pack();
    frame.setResizable(false);
    frame.setLocationRelativeTo(null);
    frame.setVisible(true);
    frame.toFront();
    frame.requestFocus();
  }

  /** Dark grey like the client theme (and other IdleScript windows such as PotionDrinker). */
  private static void applyTheme(Component comp, Color bg, Color fg) {
    if (comp instanceof JPanel
        || comp instanceof JLabel
        || comp instanceof JRadioButton
        || comp instanceof JCheckBox) {
      comp.setBackground(bg);
      comp.setForeground(fg);
      if (comp instanceof JComponent && !(comp instanceof JPanel)) {
        ((JComponent) comp).setOpaque(false);
      }
    }
    if (comp instanceof Container && !(comp instanceof JSpinner) && !(comp instanceof JButton)) {
      for (Component child : ((Container) comp).getComponents()) applyTheme(child, bg, fg);
    }
  }

  // ===========================================================================================
  // skip_tutorial_script.py
  // ===========================================================================================

  /** Plutonium: not in_rect(244, 719, 55, 49). Server: Point.onTutorialIsland(190,720..240,770). */
  private boolean tutorialDone() {
    int x = controller.currentX();
    int y = controller.currentY();
    return !(x >= 190 && x <= 244 && y >= 719 && y <= 770);
  }

  private int tutorial() {
    if (!waitedForAppearance) {
      waitedForAppearance = true;
      log("Waiting for appearance packet");
      return 2000;
    }

    log("Skipping tutorial");
    controller.skipTutorialIsland();
    return 5000;
  }

  private boolean isAppearanceScreen() {
    Object show = controller.getMudClientValue("showAppearanceChange");
    return show instanceof Boolean && (Boolean) show;
  }

  /**
   * Equivalent of Plutonium's send_appearance_update. Uses the OpenRSC custom-protocol layout the
   * patched client itself sends (opcode 235), with two extra bytes: account mode 0 = Regular (not
   * ironman) and XP mode 0 = "Yes please" (world XP rate, 2x on Coleslaw).
   */
  private void sendRandomAppearance() {
    ThreadLocalRandom r = ThreadLocalRandom.current();
    int bodyType = BODY_TYPES[r.nextInt(BODY_TYPES.length)];
    int headType = HEAD_TYPES[r.nextInt(HEAD_TYPES.length)];
    // The server flags head 6 + body 4 ("bearded lady") as suspicious; avoid it.
    while (headType == 6 && bodyType == 4) headType = HEAD_TYPES[r.nextInt(HEAD_TYPES.length)];
    int headGender = bodyType == 1 ? 1 : 2; // 1 = male; kept consistent with the body
    int hair = r.nextInt(10);
    int top = r.nextInt(15);
    int pants = r.nextInt(15);
    int skin = r.nextInt(5);

    log(
        String.format(
            "Appearance: gender=%d head=%d body=%d hair=%d top=%d pants=%d skin=%d",
            headGender, headType, bodyType, hair, top, pants, skin));

    mudclient mud = controller.getMud();
    while (mud.packetHandler.getClientStream().hasFinishedPackets()) controller.sleep(1);
    mud.packetHandler.getClientStream().newPacket(235);
    mud.packetHandler.getClientStream().bufferBits.putByte(headGender);
    mud.packetHandler.getClientStream().bufferBits.putByte(headType);
    mud.packetHandler.getClientStream().bufferBits.putByte(bodyType);
    mud.packetHandler.getClientStream().bufferBits.putByte(2); // must equal 2
    mud.packetHandler.getClientStream().bufferBits.putByte(hair);
    mud.packetHandler.getClientStream().bufferBits.putByte(top);
    mud.packetHandler.getClientStream().bufferBits.putByte(pants);
    mud.packetHandler.getClientStream().bufferBits.putByte(skin);
    mud.packetHandler.getClientStream().bufferBits.putByte(0); // Regular account (not ironman)
    mud.packetHandler.getClientStream().bufferBits.putByte(0); // use the world's XP rate
    mud.packetHandler.getClientStream().finishPacket();
    mud.setShowAppearanceChange(false);
    log("Sent appearance");
  }

  // ===========================================================================================
  // get_acc_builder_equip.py
  // ===========================================================================================

  private boolean needBag() {
    return forceBag || fatigueEnabled();
  }

  private boolean accBuilderDone() {
    return (!needBag() || has(SLEEPING_BAG))
        && has(TINDERBOX)
        && has(BRONZE_AXE)
        && has(FISHING_NET);
  }

  private int accBuilder() {
    if (controller.isInCombat()) return escapeCombat();
    if (controller.isBatching() && !controller.isInShop()) return 640; // e.g. batched pickpocket

    if (dropUnneededKitItems()) return 700;

    int coins = controller.getInventoryItemCount(COINS);
    boolean bagOk = !needBag() || has(SLEEPING_BAG);
    logChanged("kit",
        String.format(
            "# Coins = %d. Has sleeping bag = %s%s. Has tinderbox = %s. Has bronze axe = %s. Has net = %s",
            coins,
            has(SLEEPING_BAG),
            needBag() ? "" : " (not needed)",
            has(TINDERBOX),
            has(BRONZE_AXE),
            has(FISHING_NET)));

    if (needBag() && coins >= PRICE_SLEEPING_BAG && !has(SLEEPING_BAG)) {
      status = "Buying sleeping bag";
      return buyFromShop(
          SHOP_PT, 8, GENERAL_STORE_DOORS, GENERAL_STORE_NPCS, SLEEPING_BAG, 0, "selling");
    } else if (coins >= PRICE_TINDERBOX && !has(TINDERBOX) && bagOk) {
      status = "Buying tinderbox";
      return buyFromShop(
          SHOP_PT, 8, GENERAL_STORE_DOORS, GENERAL_STORE_NPCS, TINDERBOX, 0, "selling");
    } else if (coins >= PRICE_BRONZE_AXE && !has(BRONZE_AXE) && has(TINDERBOX)) {
      status = "Buying bronze axe";
      return buyFromShop(BOB_PT, 3, BOB_DOORS, new int[] {BOB}, BRONZE_AXE, 1, "sell");
    } else if (coins >= PRICE_NET && !has(FISHING_NET) && has(BRONZE_AXE)) {
      status = "Buying fishing net";
      return buyFromShop(
          FISH_STORE_PT, 8, FISH_STORE_DOORS, new int[] {GERRANT}, FISHING_NET, 0, "got");
    }
    status = "Thieving coins";
    return getCoins();
  }

  /** Drops anything that is not part of the kit (or coins). Returns true if something dropped. */
  private boolean dropUnneededKitItems() {
    int[] ids = controller.getInventoryItemIds();
    int count = controller.getInventoryItemCount();
    for (int slot = 0; slot < count && slot < ids.length; slot++) {
      int id = ids[slot];
      if (id < 0) continue;
      if (id != SLEEPING_BAG
          && id != TINDERBOX
          && id != BRONZE_AXE
          && id != COINS
          && id != FISHING_NET) {
        log("Dropping " + controller.getItemName(id));
        controller.dropItem(slot);
        return true;
      }
    }
    return false;
  }

  private int getCoins() {
    if (controller.isInShop()) controller.closeShop();

    if (controller.distanceTo(THIEVE_PT[0], THIEVE_PT[1]) > 8) {
      status = "Walking to thieving point";
      walkRoute(THIEVE_PT[0], THIEVE_PT[1]);
      return 600;
    }

    // Check if any of the items we need are on the ground
    for (int id : new int[] {SLEEPING_BAG, TINDERBOX, BRONZE_AXE, FISHING_NET}) {
      if (id == SLEEPING_BAG && !needBag()) continue;
      if (has(id)) continue;
      int[] item = controller.getNearestItemById(id);
      if (item != null
          && controller.distance(item[0], item[1], THIEVE_PT[0], THIEVE_PT[1]) <= 8
          && controller.isReachable(item[0], item[1], false)) {
        log("Picking up " + controller.getItemName(id));
        controller.pickupItem(item[0], item[1], id, true, true);
        return 700;
      }
    }

    ORSCharacter man =
        controller.getNearestNPCByLambda(
            n -> {
              if (n.npcId != MAN) return false;
              if (controller.isNpcInCombat(n.serverIndex) || unreachableMan(n.serverIndex)) return false;
              int[] c = controller.getNpcCoordsByServerIndex(n.serverIndex);
              return c[0] != -1
                  && controller.distance(c[0], c[1], THIEVE_PT[0], THIEVE_PT[1]) <= 8
                  && controller.isReachable(c[0], c[1], true);
            });
    if (man != null) {
      lastMan = man.serverIndex;
      controller.thieveNpc(man.serverIndex);
    }
    return 700;
  }

  /**
   * Shared shop routine (buy_sleeping_bag / buy_tinderbox / buy_bronze_axe / buy_fishing_net).
   * Talks to the shop NPC, picks the "show me your wares" dialogue option (by text, falling back to
   * the index the original used) and buys one item.
   */
  private int buyFromShop(
      int[] point,
      int radius,
      int[][] doors,
      int[] npcIds,
      int itemId,
      int fallbackOption,
      String optionKeyword) {
    if (controller.isInShop()) {
      // Buy, then always close the shop and check the inventory afterwards (same pattern as
      // IdleRSC's AIOAIO Fish script). Never re-open the shop while it is already open.
      int stock = controller.getShopItemCount(itemId);
      if (stock > 0) {
        log("Buying " + controller.getItemName(itemId));
        controller.shopBuy(itemId, 1);
        controller.sleepUntil(() -> has(itemId), 3000);
      } else {
        log("Shop has none of " + controller.getItemName(itemId) + " (stock " + stock + ")");
      }
      controller.closeShop();
      controller.sleep(1200);
      if (has(itemId)) log("Got " + controller.getItemName(itemId));
      else log("Failed to get " + controller.getItemName(itemId) + ", will retry");
      return 1200;
    }

    // Other players can close a shop door. Only open one when it actually blocks the way into
    // the shop: we are close, the shop tile is not reachable, and it is the nearest closed door.
    int[] door = blockingDoor(point, doors);
    if (door != null) {
      log("Opening door at " + door[0] + "," + door[1] + " (shop not reachable)");
      controller.openDoor(door[0], door[1]);
      return 1300;
    }

    if (controller.distanceTo(point[0], point[1]) > radius) {
      walkRoute(point[0], point[1]);
      return 600;
    }

    if (controller.isInOptionMenu()) {
      controller.optionAnswer(pickOption(optionKeyword, fallbackOption));
      controller.sleepUntil(() -> controller.isInShop(), 5000);
      return 600;
    }

    ORSCharacter npc =
        controller.getNearestNPCByLambda(
            n -> {
              boolean idMatch = false;
              for (int id : npcIds) if (n.npcId == id) idMatch = true;
              if (!idMatch || controller.isNpcTalking(n.serverIndex)) return false;
              int[] c = controller.getNpcCoordsByServerIndex(n.serverIndex);
              return c[0] != -1 && controller.isReachable(c[0], c[1], true);
            });
    if (npc != null) {
      // Prefer the NPC's right-click "Trade" option: it opens the shop directly with no dialogue.
      // Fall back to talking + picking the dialogue option for shopkeepers without "Trade".
      String cmd = controller.getNpcCommand1(npc.npcId);
      if (cmd != null && cmd.equalsIgnoreCase("Trade")) {
        log("Trading " + controller.getNpcName(npc.npcId));
        controller.npcCommand1(npc.serverIndex);
        controller.sleepUntil(() -> controller.isInShop(), 5000);
        return 600;
      }
      log("Talking to " + controller.getNpcName(npc.npcId));
      controller.talkToNpc(npc.serverIndex);
      controller.sleepUntil(() -> controller.isInOptionMenu() || controller.isInShop(), 5000);
      return 600;
    }
    return 3000;
  }

  private int pickOption(String keyword, int fallback) {
    int count = controller.getOptionMenuCount();
    for (int i = 0; i < count; i++) {
      String text = controller.getOptionsMenuText(i);
      if (text != null && text.toLowerCase().contains(keyword)) return i;
    }
    return fallback;
  }

  // ===========================================================================================
  // full_shrimp_port_sarim.py
  // ===========================================================================================

  private boolean shrimpDone() {
    return controller.getInventoryItemCount() >= 25
        && !has(BURNT_FISH)
        && !has(RAW_SHRIMP)
        && !has(RAW_ANCHOVIES);
  }

  private int shrimp() {
    if (controller.isInCombat()) return escapeCombat();
    if (controller.isInShop()) controller.closeShop();

    int invCount = controller.getInventoryItemCount();

    // Coleslaw batches skilling actions. Never interrupt a batch, except that fishing must stop at
    // 25 items (the original fished one catch per click) so there is room left for logs.
    if (controller.isBatching()) {
      if (fishingClicked && invCount >= 25) {
        log("25 items reached, stopping the fishing batch");
        controller.stopBatching();
        fishingClicked = false;
      }
      return 640;
    }
    fishingClicked = false;

    logChanged("shrimp",
        "# Cooked shrimp = "
            + controller.getInventoryItemCount(COOKED_SHRIMP)
            + ", # Raw shrimp = "
            + controller.getInventoryItemCount(RAW_SHRIMP));

    if (unstuck) {
      return getUnstuck();
    } else if (invCount < 25) {
      status = "Fishing";
      return goFish();
    } else if (has(RAW_SHRIMP) && controller.getNearestObjectById(FIRE_ID) != null) {
      status = "Cooking";
      return cookShrimp();
    } else if (has(RAW_SHRIMP) && controller.getNearestItemById(LOGS) != null) {
      status = "Lighting fire";
      return burnLogs();
    } else if (has(LOGS)) {
      status = "Dropping logs";
      return dropLogs();
    } else if (has(RAW_SHRIMP)) {
      status = "Chopping tree";
      return chopLogs();
    } else if (has(BURNT_FISH) || has(RAW_ANCHOVIES)) {
      dropJunkFish();
      return 1000;
    }

    log("Nothing to do");
    return 700;
  }

  /**
   * The original walked to (305, 880) via an undefined helper (walk_path_point), which would have
   * crashed and is nowhere near Port Sarim. Instead: pick the unlightable logs back up and move a
   * few tiles to a random reachable tile, so the next fire is lit somewhere else.
   */
  private int getUnstuck() {
    unstuck = false;
    log("Getting unstuck");
    int[] logs = controller.getNearestItemById(LOGS, 3);
    if (logs != null && controller.getInventoryItemCount() < 30) {
      controller.pickupItem(logs[0], logs[1], LOGS, true, false);
      controller.sleep(1200);
    }
    ThreadLocalRandom r = ThreadLocalRandom.current();
    for (int i = 0; i < 20; i++) {
      int x = controller.currentX() + r.nextInt(-4, 5);
      int y = controller.currentY() + r.nextInt(-4, 5);
      if (controller.distanceTo(x, y) >= 2
          && controller.isReachable(x, y, false)
          && controller.getObjectAtCoord(x, y) == -1) {
        controller.walkTo(x, y);
        break;
      }
    }
    return 1000;
  }

  private boolean dropJunkFish() {
    int[] ids = controller.getInventoryItemIds();
    int count = controller.getInventoryItemCount();
    for (int slot = 0; slot < count && slot < ids.length; slot++) {
      if (ids[slot] == BURNT_FISH || ids[slot] == RAW_ANCHOVIES) {
        controller.dropItem(slot);
        return true;
      }
    }
    return false;
  }

  private int goFish() {
    if (dropJunkFish()) return 700;

    if (controller.distanceTo(FISH_SPOT[0], FISH_SPOT[1]) > 8) {
      status = "Walking to fish spot";
      walkRoute(FISH_SPOT[0], FISH_SPOT[1]);
      return 600;
    }

    int[] spot = controller.getNearestObjectById(FISH_SPOT_ID);
    if (spot == null) {
      log("Could not find fishing spot");
      return 1000;
    }
    controller.atObject(spot[0], spot[1]);
    fishingClicked = true;
    return 2000;
  }

  private int cookShrimp() {
    int[] fire = controller.getNearestObjectById(FIRE_ID);
    if (fire == null) {
      log("Could not find fire");
      return 1000;
    }
    logChanged("fire", "Using raw shrimp on fire at " + fire[0] + "," + fire[1]);
    controller.useItemIdOnObject(fire[0], fire[1], RAW_SHRIMP);
    cookedThisRun = true;
    return 2000;
  }

  private int burnLogs() {
    int[] logs = controller.getNearestItemById(LOGS);
    if (logs == null) {
      log("Could not find logs");
      return 1000;
    }
    // OpenRSC only lights ground logs from the same tile (WalkToPointAction radius 0) and IdleRSC's
    // useItemOnGroundItem does not walk, so step onto the logs first.
    if (controller.currentX() != logs[0] || controller.currentY() != logs[1]) {
      controller.walkTo(logs[0], logs[1]);
    }
    controller.useItemOnGroundItem(logs[0], logs[1], TINDERBOX, LOGS);
    return 2000;
  }

  private int dropLogs() {
    // The original dropped the log and then stepped one tile (walk_adjacent) before lighting it.
    // Here the log is lit from the tile it is dropped on (see burnLogs), so no step is needed.
    controller.dropItem(controller.getInventoryItemSlotIndex(LOGS));
    return 1200;
  }

  private int chopLogs() {
    if (dropJunkFish()) return 700;
    if (controller.getInventoryItemCount() >= 30) {
      // Inventory full (e.g. a fishing batch overshot 25): make room for a log.
      log("Inventory full, dropping one raw shrimp to make room for logs");
      controller.dropItem(controller.getInventoryItemSlotIndex(RAW_SHRIMP), 1);
      return 1000;
    }

    int[] tree = controller.getNearestReachableObjectByIds(TREE_IDS, true);
    if (tree == null) tree = controller.getNearestObjectByIds(TREE_IDS);
    if (tree == null) {
      log("Could not find tree");
      return 1000;
    }
    controller.atObject(tree[0], tree[1]);
    // One log is enough per fire; stop a woodcutting batch once we have it.
    controller.sleepUntil(() -> has(LOGS), 15000);
    if (controller.isBatching()) controller.stopBatching();
    return 1000;
  }

  // ===========================================================================================
  // Helpers
  // ===========================================================================================

  // ===========================================================================================
  // Shrimp modes: bank / heal
  // ===========================================================================================

  /** Walks to the closest bank (Draynor from Port Sarim) and deposits the cooked shrimp. */
  private int bankShrimp() {
    if (controller.isInShop()) controller.closeShop();
    if (controller.isInBank()) {
      int count = controller.getInventoryItemCount(COOKED_SHRIMP);
      if (count > 0) {
        status = "Depositing shrimp";
        controller.depositItem(COOKED_SHRIMP, count);
        controller.sleepUntil(() -> !has(COOKED_SHRIMP), 3000);
        if (has(COOKED_SHRIMP)) return 640; // try again
        shrimpBanked += count;
        log("Banked " + count + " cooked shrimp (" + shrimpBanked + " total)");
      }
      // Also bank anything that is not part of the kit (e.g. a "Research package") so it does
      // not take up fishing space. Kit and coins stay in the inventory.
      int[] ids = controller.getInventoryItemIds();
      int n = controller.getInventoryItemCount();
      for (int slot = 0; slot < n && slot < ids.length; slot++) {
        int id = ids[slot];
        if (id < 0 || isKitItem(id) || notBankable.contains(id)) continue;
        log("Banking " + controller.getItemName(id));
        controller.depositItem(id, controller.getInventoryItemCount(id));
        controller.sleepUntil(() -> !has(id), 2000);
        if (has(id)) notBankable.add(id); // never retry forever
        return 640;
      }
      controller.closeBank();
      controller.sleep(640);
      bankTrip = false;
      return afterCycle();
    }
    if (!has(COOKED_SHRIMP)) {
      bankTrip = false; // nothing left to bank (e.g. eaten or dropped by hand)
      return afterCycle();
    }
    if (controller.isInCombat()) return escapeCombat();
    if (distanceToNearestBanker() > 5) {
      status = "Walking to " + nearestBankName();
      controller.walkTowardsBank();
      return 600;
    }
    status = "Opening bank";
    controller.openBank();
    return 1000;
  }

  private int distanceToNearestBanker() {
    ORSCharacter banker = controller.getNearestNpcByIds(controller.bankerIds, true);
    if (banker == null) return Integer.MAX_VALUE;
    int[] c = controller.getNpcCoordsByServerIndex(banker.serverIndex);
    if (c == null || c[0] == -1) return Integer.MAX_VALUE;
    return controller.distanceTo(c[0], c[1]);
  }

  private String nearestBankName() {
    int[] bank = controller.getNearestBank();
    if (bank != null && bank[0] == 220 && bank[1] == 635) return "Draynor bank";
    return "nearest bank";
  }

  private int currentHp() {
    return controller.getCurrentStat(SkillId.HITS.getId());
  }

  private int maxHp() {
    return controller.getBaseStat(SkillId.HITS.getId());
  }

  /** Eats one cooked shrimp if HP is at or below the threshold. Returns true if it ate. */
  private boolean eatIfNeeded() {
    int hp = currentHp();
    if (hp > healAtHp || hp >= maxHp() || !has(COOKED_SHRIMP)) return false;
    if (controller.isBatching()) controller.stopBatching();
    status = "Eating shrimp (" + hp + " HP)";
    int before = controller.getInventoryItemCount(COOKED_SHRIMP);
    controller.itemCommand(COOKED_SHRIMP);
    controller.sleepUntil(() -> controller.getInventoryItemCount(COOKED_SHRIMP) < before, 2000);
    if (controller.getInventoryItemCount(COOKED_SHRIMP) < before) {
      shrimpEaten++;
      log("Ate a shrimp at " + hp + " HP, now " + currentHp() + "/" + maxHp());
    }
    return true;
  }

  /**
   * Pickpockets men in Lumbridge, eating cooked shrimp at the HP threshold. A failed pickpocket
   * stuns us and the man attacks: we walk out of combat first, then eat if we are low. When the
   * shrimp run out we go back to Port Sarim (a safe spot) to fish and cook more.
   */
  private int healTrip() {
    if (controller.isInShop()) controller.closeShop();
    if (controller.isInCombat()) {
      // Eating is not possible in combat in RSC: get out first, eat on the next loop.
      return escapeCombat();
    }
    if (eatIfNeeded()) return 1300;

    if (!has(COOKED_SHRIMP)) {
      log(
          "Out of shrimp (ate "
              + shrimpEaten
              + " so far, HP "
              + currentHp()
              + "/"
              + maxHp()
              + "). Going back to fish.");
      if (controller.isBatching()) controller.stopBatching();
      foodTrip = false;
      return afterCycle();
    }

    if (controller.isBatching()) return 640; // batched pickpocket; HP is checked every loop
    status = "Thieving (eat at " + healAtHp + " HP)";
    if (controller.distanceTo(THIEVE_PT[0], THIEVE_PT[1]) > 8) {
      status = "Walking to Lumbridge men";
      walkRoute(THIEVE_PT[0], THIEVE_PT[1]);
      return 600;
    }
    ORSCharacter man =
        controller.getNearestNPCByLambda(
            n -> {
              if (n.npcId != MAN) return false;
              if (controller.isNpcInCombat(n.serverIndex) || unreachableMan(n.serverIndex)) return false;
              int[] c = controller.getNpcCoordsByServerIndex(n.serverIndex);
              return c[0] != -1
                  && controller.distance(c[0], c[1], THIEVE_PT[0], THIEVE_PT[1]) <= 8
                  && controller.isReachable(c[0], c[1], true);
            });
    if (man != null) {
      lastMan = man.serverIndex;
      controller.thieveNpc(man.serverIndex);
    }
    return 700;
  }

  private int escapeCombat() {
    status = "Escaping combat";
    controller.walkTo(controller.currentX(), controller.currentY(), 0, true, false);
    return 650;
  }

  /**
   * The shop door that is in the way, or null. A door is only "in the way" when we are near the
   * shop, the shop tile cannot be reached, and the door is closed; of those, the nearest one.
   */
  private int[] blockingDoor(int[] point, int[][] doors) {
    if (controller.distanceTo(point[0], point[1]) > 15) return null;
    if (controller.isReachable(point[0], point[1], true)) return null;
    int[] best = null;
    int bestDist = Integer.MAX_VALUE;
    for (int[] door : doors) {
      int d = controller.distanceTo(door[0], door[1]);
      if (d > 15 || controller.getWallObjectIdAtCoord(door[0], door[1]) != CLOSED_DOOR_ID) continue;
      if (d < bestDist) {
        bestDist = d;
        best = door;
      }
    }
    return best;
  }

  /**
   * Takes one step towards (x, y) along ROUTE instead of the webwalker, so no gate is ever opened
   * on the way between Lumbridge and Port Sarim. The webwalker picks its start node by distance
   * alone; on the road just past the Lumbridge sheep pen that is the chicken pen node on the other
   * side of the fence, so every step began with "open the chicken pen gate" and walked back. Here
   * the position on the road is the nearest road segment, so it only ever moves forward.
   */
  private void walkRoute(int x, int y) {
    int target = nearestRouteIndex(x, y);
    int[] end = ROUTE[target];
    // Last leg: at the route point closest to the destination (or already nearer the destination
    // than that point is): walk straight there. Shop doors are handled by blockingDoor.
    if (controller.distanceTo(end[0], end[1]) <= 4
        || controller.distanceTo(x, y) <= controller.distance(end[0], end[1], x, y)) {
      if (controller.isReachable(x, y, true)) walkDamnitSafe(x, y);
      else controller.walkTowards(x, y);
      return;
    }
    int seg = nearestRouteSegment();
    if (seg < 0) {
      // Off the road (e.g. Draynor bank, Bob's shop). West of Lumbridge's farm pens the webwalker
      // has no gates to open, so let it walk; otherwise get back onto the road first.
      if (controller.currentX() >= 200 && x >= 200) controller.walkTowards(x, y);
      else {
        int[] near = ROUTE[nearestRouteIndex(controller.currentX(), controller.currentY())];
        controller.walkTowards(near[0], near[1]);
      }
      return;
    }
    int dir = target > seg ? 1 : -1;
    int next = dir > 0 ? seg + 1 : seg;
    if (next != target && controller.distanceTo(ROUTE[next][0], ROUTE[next][1]) <= 3) next += dir;
    status = "Walking to " + x + "," + y + " (road point " + ROUTE[next][0] + "," + ROUTE[next][1] + ")";
    walkDamnitSafe(ROUTE[next][0], ROUTE[next][1]);
  }

  /** The live jar can NPE in BruteForceUtils before the walkable map is loaded. Walk straight. */
  private void walkDamnitSafe(int x, int y) {
    try {
      controller.walkDamnit(x, y);
    } catch (NullPointerException e) {
      logChanged(
          "walk-map",
          "Path map was not ready. Walking straight to " + x + "," + y);
      controller.walkTo(x, y);
    }
  }

  private int nearestRouteIndex(int x, int y) {
    int best = 0;
    int bestDist = Integer.MAX_VALUE;
    for (int i = 0; i < ROUTE.length; i++) {
      int d = controller.distance(ROUTE[i][0], ROUTE[i][1], x, y);
      if (d < bestDist) {
        bestDist = d;
        best = i;
      }
    }
    return best;
  }

  /** Index i of the road segment ROUTE[i] -> ROUTE[i + 1] nearest to us, or -1 if none is close. */
  private int nearestRouteSegment() {
    double px = controller.currentX();
    double py = controller.currentY();
    int best = -1;
    double bestDist = 25 * 25;
    for (int i = 0; i + 1 < ROUTE.length; i++) {
      double ax = ROUTE[i][0], ay = ROUTE[i][1];
      double bx = ROUTE[i + 1][0], by = ROUTE[i + 1][1];
      double dx = bx - ax, dy = by - ay;
      double t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy);
      t = Math.max(0, Math.min(1, t));
      double ex = ax + t * dx - px, ey = ay + t * dy - py;
      double d = ex * ex + ey * ey;
      if (d < bestDist) {
        bestDist = d;
        best = i;
      }
    }
    return best;
  }

  private boolean isKitItem(int id) {
    return id == SLEEPING_BAG
        || id == TINDERBOX
        || id == BRONZE_AXE
        || id == COINS
        || id == FISHING_NET;
  }

  private boolean has(int itemId) {
    return controller.getInventoryItemCount(itemId) > 0;
  }

  private void dropAll(int itemId) {
    while (controller.isRunning() && has(itemId)) {
      int slot = controller.getInventoryItemSlotIndex(itemId);
      if (slot < 0) break;
      controller.dropItem(slot);
      controller.sleep(640);
    }
  }

  private boolean fatigueEnabled() {
    if (controller.getFatigue() > 0) return true;
    try {
      if (compatibility.apos.ServerProfile.kind()
          == compatibility.apos.ServerProfile.Kind.URANIUM) return true;
    } catch (Throwable ignored) {
      // profile not ready yet
    }
    return Config.S_WANT_FATIGUE && controller.isAuthentic();
  }

  private boolean levelGoalReached() {
    for (int skill : LEVEL_SKILLS) {
      if (controller.getBaseStat(skill) < targetLevel) return false;
    }
    return true;
  }

  /** Closes the setup window if the script is stopped while it is open. */
  @Override
  public void cleanup() {
    JFrame frame = scriptFrame;
    if (frame != null && frame.isDisplayable()) SwingUtilities.invokeLater(frame::dispose);
  }

  // Log trim: script log only (controller.log also copies every line into the game chat, which
  // the probe then records a second time). Status lines that repeat every loop go through
  // logChanged, so they are logged when they change (or once a minute as a heartbeat).
  private void log(String msg) {
    bot.Main.logScript("[GetBaseAny] " + msg);
  }

  private final java.util.HashMap<String, String> lastLine = new java.util.HashMap<>();
  private final java.util.HashMap<String, Long> lastLineAt = new java.util.HashMap<>();

  private void logChanged(String key, String msg) {
    long now = System.currentTimeMillis();
    Long at = lastLineAt.get(key);
    if (msg.equals(lastLine.get(key)) && at != null && now - at < 60_000) return;
    lastLine.put(key, msg);
    lastLineAt.put(key, now);
    log(msg);
  }

  // ===========================================================================================
  // Interrupts
  // ===========================================================================================

  /**
   * A death message ("Oh dear! You are dead...") arrives on the server or quest channel. The next
   * loop drops the current trip and buys any kit that was lost.
   */
  private void noteDeathMessage(String message) {
    if (message == null) return;
    String m = message.toLowerCase();
    if (m.contains("you are dead") || m.contains("oh dear")) diedFlag = true;
  }

  /** Consumes one death. Duplicate messages within 8 seconds count once. */
  private boolean noteDeath() {
    if (!diedFlag) return false;
    diedFlag = false;
    long now = System.currentTimeMillis();
    if (now - lastDeathAt < 8000) return false;
    lastDeathAt = now;
    deaths++;
    bankTrip = false;
    foodTrip = false;
    fishingClicked = false;
    unstuck = false;
    if (controller.isBatching()) controller.stopBatching();
    status = "Died (" + deaths + ") - replacing kit";
    log("Died (" + deaths + "). Replacing any kit that was lost, then walking back.");
    return true;
  }

  /** full_shrimp_port_sarim.on_server_message: "You can't light a fire here" (a QUEST message). */
  @Override
  public void questMessageInterrupt(String message) {
    noteDeathMessage(message);
    if (message != null && message.contains("You can't light")) {
      log("Need to get unstuck");
      unstuck = true;
    }
  }

  // Reach guard: "I can't get close enough" = that man was already fighting someone when we got
  // there. Skip him for 60 s and pick another (counted, logged at most once a minute).
  private int lastMan = -1, cantReach = 0;
  private final java.util.HashMap<Integer, Long> manBlacklist = new java.util.HashMap<>();

  private boolean unreachableMan(int idx) {
    Long until = manBlacklist.get(idx);
    if (until == null) return false;
    if (until < System.currentTimeMillis()) {
      manBlacklist.remove(idx);
      return false;
    }
    return true;
  }

  @Override
  public void serverMessageInterrupt(String message) {
    noteDeathMessage(message);
    if (message != null && message.contains("t get close enough") && lastMan >= 0) {
      manBlacklist.put(lastMan, System.currentTimeMillis() + 60_000);
      cantReach++;
      logChanged("reach", "Couldn't reach " + cantReach + " men so far (someone else was fighting them) - skipping each for 60 s");
    }
    // The idle "You have been standing here" message is already handled by IdleRSC
    // (controller.getNeedToMove()); only the firemaking message is needed here.
    questMessageInterrupt(message);
  }

  @Override
  public void paintInterrupt() {
    if (controller == null) return;
    int y = 21;
    int lines = 8 + (MODE_HEAL.equals(shrimpMode) || MODE_BANK.equals(shrimpMode) ? 1 : 0);
    controller.drawBoxAlpha(7, 7, 260, 14 * lines + 8, 0x000000, 128);
    controller.drawString("@gre@GetBaseAny @whi@(Plutonium port)", 10, y, 0xFFFFFF, 1);
    y += 14;
    controller.drawString("@whi@Status: @yel@" + status, 10, y, 0xFFFFFF, 1);
    y += 14;
    controller.drawString(
        "@whi@Runtime: @yel@" + controller.msToString(System.currentTimeMillis() - startTime),
        10,
        y,
        0xFFFFFF,
        1);
    y += 14;
    controller.drawString(
        "@whi@Fish/Cook: @yel@"
            + controller.getBaseStat(SkillId.FISHING.getId())
            + "/"
            + controller.getBaseStat(SkillId.COOKING.getId())
            + " @whi@FM/WC: @yel@"
            + controller.getBaseStat(SkillId.FIREMAKING.getId())
            + "/"
            + controller.getBaseStat(SkillId.WOODCUTTING.getId()),
        10,
        y,
        0xFFFFFF,
        1);
    y += 14;
    controller.drawString(
        "@whi@Cooked inventories: @yel@" + cookedInventories + " @whi@Deaths: @yel@" + deaths,
        10,
        y,
        0xFFFFFF,
        1);
    y += 14;
    controller.drawString(
        "@whi@Shrimp: @yel@" + (scriptStarted ? modeText() : "(setup window)"), 10, y, 0xFFFFFF, 1);
    y += 14;
    if (MODE_HEAL.equals(shrimpMode)) {
      controller.drawString(
          "@whi@HP: @yel@"
              + currentHp()
              + "/"
              + maxHp()
              + " @whi@Eaten: @yel@"
              + shrimpEaten
              + " @whi@Food: @yel@"
              + controller.getInventoryItemCount(COOKED_SHRIMP),
          10,
          y,
          0xFFFFFF,
          1);
      y += 14;
    } else if (MODE_BANK.equals(shrimpMode)) {
      controller.drawString("@whi@Shrimp banked: @yel@" + shrimpBanked, 10, y, 0xFFFFFF, 1);
      y += 14;
    }
    controller.drawString(
        "@whi@Mode: @yel@" + (loopMode ? "loop to " + targetLevel : "one cycle"),
        10,
        y,
        0xFFFFFF,
        1);
  }
}
