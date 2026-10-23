package scripting.idlescript.SeattaScript.AIOQuester;

import static scripting.idlescript.SeattaScript.AIOQuester.AIOQuester.c;

import bot.Main;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.swing.*;
import scripting.idlescript.SeattaScript.AIOQuester.models.Quest;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuestRequirement;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuestDef;

/**
 * AIOQuester setup window. Pick quests on the left, add them to the ordered queue on the right (or
 * tick "Do all available quests"), then start. Returns a QuestQueue.Options.
 */
// TODO: Add amount checking for required equipped items for ammunition
public class QuestUI {
  private static volatile boolean started;
  private static volatile boolean closed;

  private static JFrame frame;
  private static DefaultListModel<QuestDef> availableModel;
  private static DefaultListModel<QuestDef> queueModel;
  private static JList<QuestDef> availableList;
  private static JList<QuestDef> queueList;
  private static JTextPane descriptionTextPane;
  private static JCheckBox showCanStartCheckbox;
  private static JCheckBox ironmanCheckbox;
  private static QuestDef described;
  private static JCheckBox doAllCheckbox;
  private static JCheckBox bankCheckbox;
  private static JCheckBox gatherOnlyCheckbox;
  private static JCheckBox auctionCheckbox;
  /** Queued quests whose Auction House box is ticked. */
  private static final Set<QuestDef> auctionQuests = new LinkedHashSet<>();
  private static JSpinner timeoutSpinner;
  private static JButton startBtn;
  private static QuestQueue.Options result;

  /**
   * Shows the script ui and waits for the user to start a queue.
   *
   * @param defaults QuestQueue.Options -- initial values (may be null)
   * @return QuestQueue.Options, or null if the window was closed
   */
  public static QuestQueue.Options showFrame(QuestQueue.Options defaults) {
    started = false;
    closed = false;
    result = null;
    final QuestQueue.Options init = defaults != null ? defaults : new QuestQueue.Options();
    try {
      SwingUtilities.invokeAndWait(() -> buildFrame(init));
    } catch (Exception e) {
      Main.logError("AIOQuester: failed to open the setup window", e);
      return null;
    }

    while (c.isRunning() && !started && !closed) c.sleep(640);
    if (!started) {
      SwingUtilities.invokeLater(
          () -> {
            if (frame != null) frame.dispose();
          });
      return null;
    }
    return result;
  }

  /** @return what the last setup window was started with, or null (used by mass start) */
  public static QuestQueue.Options getLastResult() {
    return started ? result : null;
  }

  /** Backwards-compatible single-quest entry point: returns the first queued quest. */
  public static QuestDef showFrame() {
    QuestQueue.Options opts = showFrame(null);
    if (opts == null || opts.quests.isEmpty()) return null;
    return opts.quests.get(0);
  }

  private static void buildFrame(QuestQueue.Options init) {
    if (frame != null) frame.dispose();
    frame = new JFrame("AIOQuester - quest queue");
    frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    frame.addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosed(WindowEvent e) {
            if (!started) closed = true;
          }
        });

    availableModel = new DefaultListModel<>();
    queueModel = new DefaultListModel<>();
    auctionQuests.clear();
    for (QuestDef q : init.quests) if (q != null && !queueModel.contains(q)) queueModel.addElement(q);
    if (!init.auctionQuests.isEmpty()) {
      for (QuestDef q : init.auctionQuests) if (queueModel.contains(q)) auctionQuests.add(q);
    } else if (init.useAuctionHouse) {
      for (int i = 0; i < queueModel.size(); i++) auctionQuests.add(queueModel.get(i));
    }

    availableList = new JList<>(availableModel);
    availableList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
    availableList.setCellRenderer(new QuestRenderer(false));
    queueList = new JList<>(queueModel);
    queueList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
    queueList.setCellRenderer(new QueueRenderer());
    queueList.setToolTipText("Tick the box to buy that quest's before-start items at the Auction House");

    descriptionTextPane = new JTextPane();
    descriptionTextPane.setEditable(false);

    ironmanCheckbox = new JCheckBox("Ironman requirements", true);
    ironmanCheckbox.setToolTipText(
        "<html>Shows the Ironman item list. Normal shows what a regular account can trade for.<br>The script always gathers items. This box only changes the description.</html>");
    showCanStartCheckbox = new JCheckBox("Show only start-able quests", true);
    showCanStartCheckbox.setToolTipText(
        "<html>Shows only the quests that can be started by the player.<br>Completed quests and quests requiring missing skills or prerequisite quests are hidden.</html>");
    doAllCheckbox = new JCheckBox("Do all available quests", init.doAllAvailable);
    doAllCheckbox.setToolTipText(
        "<html>Runs every quest AIOQuester has a script for, in list order.<br>Completed quests and quests with unmet requirements are skipped and logged.</html>");
    bankCheckbox =
        new JCheckBox("Bank for item/space requirements", init.bankForRequirements);
    bankCheckbox.setToolTipText(
        "<html>Before a quest, walk to the nearest bank to deposit unneeded items and withdraw<br>required inventory/equipped items when those are the only missing requirements.<br>Not used for ultimate iron men.</html>");
    gatherOnlyCheckbox =
        new JCheckBox("Gather items only (don't start the quest)", init.gatherOnly);
    gatherOnlyCheckbox.setToolTipText(
        "<html>Collects the items listed as obtainable before the quest starts, then stops.<br>It does not talk to the quest giver. Use with the Auction House box to buy what you don't have.</html>");
    auctionCheckbox =
        new JCheckBox(
            "Buy missing items at the Auction House",
            !queueModel.isEmpty() && auctionQuests.size() == queueModel.size()
                || (init.useAuctionHouse && init.doAllAvailable));
    auctionCheckbox.setToolTipText(
        "<html>Ticks the Auction House box on every queued quest. Each box can be cleared on its own.<br>Before buying, every coin is withdrawn from a ground-floor bank, then the script walks to<br>a Varrock centre auctioneer or a bank clerk. Needs 100 total level. Ironmen are refused.</html>");
    if (c.getPlayerMode() == 2) {
      bankCheckbox.setSelected(false);
      bankCheckbox.setEnabled(false);
    }
    timeoutSpinner =
        new JSpinner(new SpinnerNumberModel(Math.max(0, init.timeoutMinutes), 0, 1440, 5));
    timeoutSpinner.setToolTipText("Per-quest time limit in minutes (0 = no limit)");

    JButton addBtn = new JButton("Add >");
    JButton removeBtn = new JButton("< Remove");
    JButton upBtn = new JButton("Move up");
    JButton downBtn = new JButton("Move down");
    JButton clearBtn = new JButton("Clear");
    JButton addAllBtn = new JButton("Add all >>");
    JButton resetBtn = new JButton("Reset");
    resetBtn.setToolTipText("Empty the queue and put every option back to its default");
    startBtn = new JButton("Add quests to the queue");

    // Layout
    JPanel left = new JPanel(new BorderLayout(4, 4));
    left.add(header("Quests"), BorderLayout.NORTH);
    left.add(new JScrollPane(availableList), BorderLayout.CENTER);
    left.add(showCanStartCheckbox, BorderLayout.SOUTH);
    left.setPreferredSize(new Dimension(230, 0));

    JPanel center = new JPanel(new BorderLayout(4, 4));
    center.add(header("Description"), BorderLayout.NORTH);
    center.add(new JScrollPane(descriptionTextPane), BorderLayout.CENTER);

    JPanel buttons = new JPanel(new GridLayout(0, 1, 4, 4));
    for (JButton b : new JButton[] {addBtn, addAllBtn, removeBtn, upBtn, downBtn, clearBtn, resetBtn})
      buttons.add(b);
    JPanel buttonsWrap = new JPanel(new BorderLayout());
    buttonsWrap.add(buttons, BorderLayout.NORTH);

    JPanel right = new JPanel(new BorderLayout(4, 4));
    right.add(header("Queue — tick AH"), BorderLayout.NORTH);
    right.add(new JScrollPane(queueList), BorderLayout.CENTER);
    right.add(buttonsWrap, BorderLayout.WEST);
    right.setPreferredSize(new Dimension(340, 0));

    JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
    options.add(ironmanCheckbox);
    options.add(doAllCheckbox);
    options.add(bankCheckbox);
    options.add(gatherOnlyCheckbox);
    options.add(auctionCheckbox);
    options.add(new JLabel("Timeout (min/quest):"));
    options.add(timeoutSpinner);

    JPanel south = new JPanel(new BorderLayout(4, 4));
    south.add(options, BorderLayout.NORTH);
    south.add(startBtn, BorderLayout.SOUTH);

    JPanel main = new JPanel(new BorderLayout(6, 6));
    main.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
    main.add(left, BorderLayout.WEST);
    main.add(center, BorderLayout.CENTER);
    main.add(right, BorderLayout.EAST);
    main.add(south, BorderLayout.SOUTH);

    theme(main);
    frame.setContentPane(main);
    frame.setPreferredSize(new Dimension(940, 480));

    // Listeners
    availableList.addListSelectionListener(
        e -> {
          if (!e.getValueIsAdjusting()) showDescription(availableList.getSelectedValue());
        });
    queueList.addListSelectionListener(
        e -> {
          if (!e.getValueIsAdjusting()) showDescription(queueList.getSelectedValue());
        });
    availableList.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 2) addSelected();
          }
        });
    queueList.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mousePressed(MouseEvent e) {
            int index = queueList.locationToIndex(e.getPoint());
            if (index < 0) return;
            java.awt.Rectangle cell = queueList.getCellBounds(index, index);
            if (cell == null || e.getX() > cell.x + 22) return;
            QuestDef q = queueModel.get(index);
            if (!auctionQuests.add(q)) auctionQuests.remove(q);
            auctionCheckbox.setSelected(auctionQuests.size() == queueModel.size() && !queueModel.isEmpty());
            queueList.repaint();
          }

          @Override
          public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 2 && e.getX() > 22) removeSelected();
          }
        });
    addBtn.addActionListener(e -> addSelected());
    addAllBtn.addActionListener(
        e -> {
          for (int i = 0; i < availableModel.size(); i++) {
            QuestDef q = availableModel.get(i);
            if (!queueModel.contains(q)) queueModel.addElement(q);
            if (auctionCheckbox.isSelected()) auctionQuests.add(q);
          }
          updateStartButton();
        });
    removeBtn.addActionListener(e -> removeSelected());
    upBtn.addActionListener(e -> move(-1));
    downBtn.addActionListener(e -> move(1));
    clearBtn.addActionListener(
        e -> {
          queueModel.clear();
          auctionQuests.clear();
          auctionCheckbox.setSelected(false);
          updateStartButton();
        });
    resetBtn.addActionListener(e -> resetPressed());
    showCanStartCheckbox.addActionListener(e -> populateList());
    ironmanCheckbox.addActionListener(e -> showDescription(described));
    doAllCheckbox.addActionListener(e -> updateStartButton());
    auctionCheckbox.addActionListener(
        e -> {
          auctionQuests.clear();
          if (auctionCheckbox.isSelected()) {
            for (int i = 0; i < queueModel.size(); i++) auctionQuests.add(queueModel.get(i));
          }
          queueList.repaint();
        });
    startBtn.addActionListener(e -> startPressed());

    populateList();
    updateStartButton();

    frame.pack();
    frame.setLocationRelativeTo(null);
    frame.setVisible(true);
  }

  private static JLabel header(String text) {
    JLabel label = new JLabel(text);
    label.setFont(label.getFont().deriveFont(Font.BOLD, 15f));
    label.setHorizontalAlignment(SwingConstants.CENTER);
    return label;
  }

  /** Applies the bot's theme colours to a component tree. */
  private static void theme(Component comp) {
    if (comp instanceof JButton) {
      comp.setBackground(Main.secondaryBG);
      comp.setForeground(Main.secondaryFG);
    } else if (comp instanceof JList || comp instanceof JTextPane) {
      comp.setBackground(Main.primaryBG.brighter());
      comp.setForeground(Main.primaryFG);
    } else if (!(comp instanceof JSpinner)) {
      comp.setBackground(Main.primaryBG);
      comp.setForeground(Main.primaryFG);
    }
    if (comp instanceof JScrollPane) {
      ((JScrollPane) comp).setBorder(BorderFactory.createEmptyBorder());
      ((JScrollPane) comp).getViewport().setBackground(Main.primaryBG.brighter());
    }
    if (comp instanceof Container && !(comp instanceof JSpinner)) {
      for (Component child : ((Container) comp).getComponents()) theme(child);
    }
  }

  /** Populates the quest list */
  private static void populateList() {
    availableModel.clear();
    boolean onlyStartable = showCanStartCheckbox.isSelected();
    for (Quest quest : Quest.values()) {
      QuestDef q = quest.getQuestDef();
      if (onlyStartable) {
        if (q.isDisplayedStartable()) availableModel.addElement(q);
      } else {
        if (q.isQuestDefined()) availableModel.addElement(q);
      }
    }
    descriptionTextPane.setText("");
  }

  private static void addSelected() {
    List<QuestDef> sel = availableList.getSelectedValuesList();
    for (QuestDef q : sel) if (!queueModel.contains(q)) queueModel.addElement(q);
    if (auctionCheckbox.isSelected()) auctionQuests.addAll(sel);
    updateStartButton();
  }

  private static void removeSelected() {
    List<QuestDef> sel = queueList.getSelectedValuesList();
    for (QuestDef q : sel) {
      queueModel.removeElement(q);
      auctionQuests.remove(q);
    }
    updateStartButton();
  }

  private static void move(int dir) {
    int[] idx = queueList.getSelectedIndices();
    if (idx.length == 0) return;
    if (dir < 0 && idx[0] == 0) return;
    if (dir > 0 && idx[idx.length - 1] == queueModel.size() - 1) return;
    if (dir < 0) {
      for (int i : idx) swap(i, i - 1);
    } else {
      for (int k = idx.length - 1; k >= 0; k--) swap(idx[k], idx[k] + 1);
    }
    for (int k = 0; k < idx.length; k++) idx[k] += dir;
    queueList.setSelectedIndices(idx);
  }

  private static void swap(int a, int b) {
    QuestDef qa = queueModel.get(a);
    queueModel.set(a, queueModel.get(b));
    queueModel.set(b, qa);
  }

  private static void updateStartButton() {
    boolean doAll = doAllCheckbox.isSelected();
    queueList.setEnabled(!doAll);
    if (doAll) {
      startBtn.setText("Start: do all available quests");
      startBtn.setEnabled(true);
    } else if (queueModel.isEmpty()) {
      startBtn.setText("Add quests to the queue (double-click or 'Add >')");
      startBtn.setEnabled(false);
    } else if (queueModel.size() == 1) {
      startBtn.setText("Start " + queueModel.get(0).getName());
      startBtn.setEnabled(true);
    } else {
      startBtn.setText("Start queue (" + queueModel.size() + " quests)");
      startBtn.setEnabled(true);
    }
  }

  /** Reset: empty queue and default options (after a confirm), and forget remembered settings. */
  private static void resetPressed() {
    int ok =
        JOptionPane.showConfirmDialog(
            frame,
            "Clear the quest queue and reset all options to their defaults?",
            "AIOQuester - reset",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.QUESTION_MESSAGE);
    if (ok != JOptionPane.OK_OPTION) return;
    QuestQueue.Options d = new QuestQueue.Options();
    queueModel.clear();
    auctionQuests.clear();
    doAllCheckbox.setSelected(d.doAllAvailable);
    if (bankCheckbox.isEnabled()) bankCheckbox.setSelected(d.bankForRequirements);
    gatherOnlyCheckbox.setSelected(d.gatherOnly);
    auctionCheckbox.setSelected(d.useAuctionHouse);
    timeoutSpinner.setValue(Math.max(0, d.timeoutMinutes));
    showCanStartCheckbox.setSelected(true);
    ironmanCheckbox.setSelected(true);
    // Settings remembered for this window (IdleRSC's "remember last settings", if present)
    try {
      java.io.File f =
          new java.io.File(new java.io.File("settings", "script-settings"), "AIOQuester.properties");
      if (f.isFile()) f.delete();
    } catch (Throwable ignored) {
      // nothing remembered
    }
    populateList();
    updateStartButton();
    descriptionTextPane.setText("");
  }

  private static void startPressed() {
    QuestQueue.Options opts = new QuestQueue.Options();
    opts.doAllAvailable = doAllCheckbox.isSelected();
    for (int i = 0; i < queueModel.size(); i++) opts.quests.add(queueModel.get(i));
    opts.bankForRequirements = bankCheckbox.isSelected();
    opts.gatherOnly = gatherOnlyCheckbox.isSelected();
    opts.useAuctionHouse = auctionCheckbox.isSelected() || !auctionQuests.isEmpty();
    opts.auctionQuests.clear();
    opts.auctionQuests.addAll(auctionQuests);
    opts.timeoutMinutes = ((Number) timeoutSpinner.getValue()).intValue();
    if (!opts.hasSelection()) return;
    result = opts;
    started = true;
    frame.dispose();
  }

  private static void showDescription(QuestDef q) {
    if (q == null) return;
    described = q;
    try {
      descriptionTextPane.setText(buildDescription(q));
      descriptionTextPane.setCaretPosition(0);
    } catch (RuntimeException e) {
      descriptionTextPane.setText(q.getDescription());
    }
  }

  private static String statusText(QuestDef q) {
    if (QuestQueue.isCompleted(q)) return "Already completed";
    String reason = QuestQueue.getSkipReason(q);
    return reason == null ? "Ready to start" : "Can't start yet (" + reason + ")";
  }

  /** Queue row: an Auction House tick box, then the quest name. */
  private static final class QueueRenderer implements ListCellRenderer<QuestDef> {
    private final JPanel row = new JPanel(new BorderLayout(4, 0));
    private final JCheckBox box = new JCheckBox();
    private final JLabel label = new JLabel();

    QueueRenderer() {
      box.setOpaque(false);
      box.setFocusable(false);
      row.add(box, BorderLayout.WEST);
      row.add(label, BorderLayout.CENTER);
    }

    @Override
    public Component getListCellRendererComponent(
        JList<? extends QuestDef> list,
        QuestDef value,
        int index,
        boolean isSelected,
        boolean cellHasFocus) {
      String text = value == null ? "" : value.getName();
      try {
        if (value != null && QuestQueue.isCompleted(value)) text += " (done)";
        else if (value != null && !value.isStartable()) text += " (reqs)";
      } catch (RuntimeException ignored) {
      }
      label.setText((index + 1) + ". " + text);
      box.setSelected(value != null && auctionQuests.contains(value));
      Color bg = isSelected ? list.getSelectionBackground() : Main.primaryBG.brighter();
      Color fg = isSelected ? list.getSelectionForeground() : Main.primaryFG;
      row.setBackground(bg);
      label.setForeground(fg);
      box.setBackground(bg);
      return row;
    }
  }

  /** Renders quest names, with a status suffix and queue position. */
  private static final class QuestRenderer extends DefaultListCellRenderer {
    private final boolean numbered;

    QuestRenderer(boolean numbered) {
      this.numbered = numbered;
    }

    @Override
    public Component getListCellRendererComponent(
        JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
      String text = String.valueOf(value);
      if (value instanceof QuestDef) {
        QuestDef q = (QuestDef) value;
        text = q.getName();
        try {
          if (QuestQueue.isCompleted(q)) text += " (done)";
          else if (!q.isStartable()) text += " (reqs)";
        } catch (RuntimeException ignored) {
        }
        if (numbered) text = (index + 1) + ". " + text;
      }
      Component comp =
          super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus);
      if (!isSelected) {
        comp.setBackground(Main.primaryBG.brighter());
        comp.setForeground(Main.primaryFG);
      }
      return comp;
    }
  }

  /** Builds the description text (with requirement checkmarks) for a quest. */
  private static String buildDescription(QuestDef selected) {
    StringBuilder description = new StringBuilder();
    description.append("Status: ").append(statusText(selected)).append("\n\n");
    description.append(selected.getDescription());

    // ✔ Leave this as a unicode escape sequence, or it'll apparently break on Windows
    @SuppressWarnings("UnnecessaryUnicodeEscape")
    String reqComplete = " \u2714";
    String reqUnknown = " ?";

    QuestRequirement[] shown =
        ironmanCheckbox != null && !ironmanCheckbox.isSelected()
            ? selected.getNormalRequirements()
            : selected.getIronmanRequirements();
    if (shown != null) {
      description.append(ironmanCheckbox != null && ironmanCheckbox.isSelected() ? "\n\nIronman requirements:" : "\n\nNormal requirements:");
      for (QuestRequirement req : shown) {
        description.append("\n - ").append(req.label);
        if (req.uncertain) description.append(reqUnknown);
        else if (Boolean.TRUE.equals(req.met())) description.append(reqComplete);
      }
      if (selected.getIronmanNote() != null) {
        description.append("\n\nIronman note: ").append(selected.getIronmanNote());
      }
    }
    java.util.List<scripting.idlescript.SeattaScript.AIOQuester.models.QuestRequirement> ahead =
        QuestSupplies.beforeStart(selected);
    if (!ahead.isEmpty()) {
      description.append("\n\nItems you can get before starting:");
      for (scripting.idlescript.SeattaScript.AIOQuester.models.QuestRequirement req : ahead) {
        description.append("\n - ").append(req.label);
        try {
          description.append(" (").append(QuestSupplies.carried(req)).append("/").append(req.amount).append(")");
        } catch (RuntimeException ignored) {
        }
      }
    }
    if (shown != null) return description.toString();

    if (selected.getRequiredQuests() != null && selected.getRequiredQuests().length > 0) {
      description.append("\n\nQuests Required: ");
      for (Quest quest : selected.getRequiredQuests()) {
        QuestDef def = quest.getQuestDef();
        description.append("\n - ").append(def.getName());
        if (!def.isMiniquest() && c.getQuestStage(def.getId()) == -1)
          description.append(reqComplete);
        if (def.isMiniquest()) description.append(" (Unable to check mini-quests)");
      }
    }

    if (selected.getRequiredSkills() != null && selected.getRequiredSkills().length > 0) {
      description.append("\n\nLevels Required: ");
      for (int[] skill : selected.getRequiredSkills()) {
        String skillName = c.getSkillNamesLong()[skill[0]];
        String level = String.valueOf(skill[1]);
        description.append("\n - ").append(level).append(" ").append(skillName);
        if (c.getBaseStat(skill[0]) >= skill[1]) description.append(reqComplete);
      }
    }

    if (selected.getRequiredEquippedItems() != null
        && selected.getRequiredEquippedItems().length > 0) {
      description.append("\n\nEquipped Items Required: ");
      for (int[] item : selected.getRequiredEquippedItems()) {
        int id = item[0];
        int amount = item[1];
        String name = c.getItemName(item[0]);
        name = name.substring(0, 1).toUpperCase() + name.substring(1);
        description.append("\n - ");
        if (amount > 1) description.append(amount).append(" ");
        description.append(name);
        // TODO: Eventually add amount checking here for equipped ammunition amount
        if (c.isItemIdEquipped(id)) description.append(reqComplete);
      }
    }

    if (selected.getRequiredInventoryItems() != null
        && selected.getRequiredInventoryItems().length > 0) {
      description.append("\n\nInventory Items Required: ");
      for (int[] item : selected.getRequiredInventoryItems()) {
        int id = item[0];
        int amount = item[1];
        String name = c.getItemName(item[0]);
        name = name.substring(0, 1).toUpperCase() + name.substring(1);
        description.append("\n - ").append(amount).append(" ").append(name);
        if (c.getInventoryItemCount(id) >= amount) description.append(reqComplete);
      }
    }

    if (selected.getRequiredBankItems() != null && selected.getRequiredBankItems().length > 0) {
      if (c.getPlayerMode() != 2 && QuestHandler.bankItems == null) {
        description.append("\n\nOPEN A BANK THEN RESTART THE SCRIPT TO POPULATE ITEMS");
        description.append("\nBanked Items Required: ");
      } else {
        description.append("\n\nBanked Items Required: ");
      }
      for (int[] item : selected.getRequiredBankItems()) {
        int id = item[0];
        int amount = item[1];
        String name = c.getItemName(item[0]);
        name = name.substring(0, 1).toUpperCase() + name.substring(1);

        description.append("\n - ").append(amount).append(" ").append(name);

        if (c.getPlayerMode() == 2) {
          description.append(" UIM");
        } else if (QuestHandler.bankItems == null) {
          description.append(reqUnknown);
        } else if (QuestHandler.bankItems.containsKey(id)
            && QuestHandler.bankItems.get(id) >= amount) {
          description.append(reqComplete);
        }
      }
    }

    int neededQP = selected.getRequiredQuestPoints();
    if (neededQP > 0) {
      description.append("\n\nQuest Points Required: ").append(selected.getRequiredQuestPoints());
      if (neededQP > c.getQuestPoints()) {
        description.append(" (").append(c.getQuestPoints()).append(")");
      } else {
        description.append(reqComplete);
      }
    }

    int neededSlots = selected.getRequiredEmptyInventorySlots();
    if (neededSlots > 0) {
      description.append("\n\nEmpty Inventory Slots Required: ").append(neededSlots);
      if (neededSlots <= 30 - c.getInventoryItemCount()) description.append(reqComplete);
    }

    return description.toString();
  }
}
