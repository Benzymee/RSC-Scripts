package scripting.idlescript.SeattaScript;

import bot.Main;
import bot.tabs.MassStart;
import bot.tabs.MassStartSettings;
import bot.ui.scriptselector.models.Category;
import bot.ui.scriptselector.models.ScriptInfo;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import models.entities.Location;
import scripting.idlescript.SeattaScript.AIOQuester.QuestWalker;

/**
 * LocationWalker - walks to a Location (or x,y) and stops on arrival.
 *
 * <p>Rewritten for Ben (originally Seatta's):
 *
 * <ul>
 *   <li>Walking uses AIOQuester's QuestWalker (collision A*, the gate/ladder-aware WebWalker graph,
 *       stuck detection with escalation, and the guard against WebWalker's unreachable first node
 *       that made Controller.walkDamnit wait forever / "brute force" for minutes).
 *   <li>Mass start: one argument, a location name or "x,y". With an argument (and not the leader's
 *       setup) no window opens; the window's Start button produces that argument for the others.
 *   <li>Favourites (shared by all tabs) and recent destinations, stored in
 *       accounts\locationwalker-favourites.txt / accounts\locationwalker-recent.txt.
 *   <li>Double-click any location (all / favourites / recent) to start walking immediately.
 * </ul>
 */
public class LocationWalker extends SeattaScript implements MassStartSettings {
  public static ScriptInfo info =
      new ScriptInfo(
          new Category[] {Category.MISCELLANEOUS},
          "Seatta, fixed for Ben",
          "Walks to a location and stops on arrival.\n"
              + "Favourites/recent lists; double-click a location to start.\n"
              + "Param (mass start / nogui): a location name, or x,y");

  private static final String TAG = "[LocationWalker] ";
  private static final long WALK_TIMEOUT_MS = 30 * 60_000L;
  private static final int MAX_RECENT = 10;

  private final Location[] hiddenLocations = {
    Location.ZZ_RUNECRAFT_MYSTERIOUS_RUINS_AIR,
    Location.ZZ_RUNECRAFT_MYSTERIOUS_RUINS_BODY,
    Location.ZZ_RUNECRAFT_MYSTERIOUS_RUINS_CHAOS,
    Location.ZZ_RUNECRAFT_MYSTERIOUS_RUINS_COSMIC,
    Location.ZZ_RUNECRAFT_MYSTERIOUS_RUINS_EARTH,
    Location.ZZ_RUNECRAFT_MYSTERIOUS_RUINS_FIRE,
    Location.ZZ_RUNECRAFT_MYSTERIOUS_RUINS_MIND,
    Location.ZZ_RUNECRAFT_MYSTERIOUS_RUINS_NATURE,
    Location.ZZ_RUNECRAFT_MYSTERIOUS_RUINS_WATER
  };

  // Plain fields (mass start copies these too)
  private String destName = null; // Location description, or null for raw coordinates
  private int destX = -1, destY = -1;
  private volatile boolean started = false;

  // GUI
  private JFrame frame;
  private JTextField filter;
  private JTable allTable, favTable, recentTable;
  private DefaultTableModel allModel, favModel, recentModel;
  private JButton startBtn, favBtn;
  private JLabel selLabel;
  private volatile String selName = null;

  // ============================================================================ start

  @Override
  public int start(String[] parameters) {
    started = false;
    String arg = parameters == null ? "" : String.join(" ", parameters).trim();
    boolean leader = false;
    try {
      leader = MassStart.isLeaderSetup();
    } catch (Throwable ignored) {
      // older client
    }
    if (!arg.isEmpty() && parseDestination(arg)) {
      log("Destination from parameters: " + describeDest());
    } else if (!arg.isEmpty()) {
      log("Couldn't understand the parameter '" + arg + "' (use a location name, or x,y)");
      destName = null;
      destX = destY = -1;
    }
    boolean haveDest = destX > 0 && destY > 0;
    if (!haveDest || leader) {
      if (!showUI(arg)) {
        log("No location chosen - stopping");
        return quit();
      }
    }
    if (!c.isRunning()) return quit();
    walk();
    return quit();
  }

  /** "x,y" / "x y" or a location name (exact, then all words contained). */
  private boolean parseDestination(String arg) {
    Matcher m = Pattern.compile("^\\(?\\s*(\\d{1,4})\\s*[, ]\\s*(\\d{1,4})\\s*\\)?$").matcher(arg);
    if (m.matches()) {
      destX = Integer.parseInt(m.group(1));
      destY = Integer.parseInt(m.group(2));
      destName = null;
      return true;
    }
    Location loc = findLocation(arg);
    if (loc == null) return false;
    destName = loc.getDescription();
    destX = loc.getX();
    destY = loc.getY();
    return true;
  }

  private Location findLocation(String name) {
    String n = name.trim();
    for (Location l : Location.values()) if (l.getDescription().equalsIgnoreCase(n)) return l;
    String[] words = n.toLowerCase(Locale.ROOT).split("\\s+");
    for (Location l : Location.values()) {
      String d = l.getDescription().toLowerCase(Locale.ROOT);
      if (Arrays.stream(words).allMatch(d::contains)) return l;
    }
    return null;
  }

  private String describeDest() {
    if (destName != null) return destName + " (" + destX + "," + destY + ")";
    String d = "";
    try {
      d = Location.getDescriptionFromStandableTile(destX, destY);
    } catch (Throwable ignored) {
    }
    return (d == null || d.isEmpty() ? "" : d + " ") + "(" + destX + "," + destY + ")";
  }

  @Override
  public String[] massStartArguments() {
    if (destX <= 0) return null;
    return new String[] {destName != null ? destName : destX + "," + destY};
  }

  // ============================================================================ walking

  private void walk() {
    String what = describeDest();
    if (destName != null) {
      Location loc = findLocation(destName);
      if (loc != null && !loc.isWalkConditionMet()) {
        log("Can't walk to " + destName + ": its walk requirements aren't met (e.g. quest/items/fare)");
        c.displayMessage("@red@" + TAG + "@whi@requirements not met for " + destName);
        return;
      }
    }
    addRecent(destName != null ? destName : destX + "," + destY);
    int width = Math.max(paintW, c.getStringWidth(what, 1) + 8);
    paintBuilder.start(paintX, paintY, width);
    log("Walking to " + what + " from " + c.currentX() + "," + c.currentY());
    c.displayMessage("@cya@" + TAG + "@whi@Walking to " + what);
    QuestWalker walker = new QuestWalker(c);
    for (int attempt = 1; attempt <= 3 && c.isRunning(); attempt++) {
      while (!c.isLoggedIn() && c.isRunning()) c.sleep(640);
      if (walker.walkTo(destX, destY, 2, WALK_TIMEOUT_MS)) {
        log("Arrived at " + what + " (now at " + c.currentX() + "," + c.currentY() + "). Stopping.");
        c.displayMessage("@gre@" + TAG + "@whi@Arrived at " + what);
        return;
      }
      if (!c.isRunning()) return;
      log("Walk attempt " + attempt + "/3 didn't arrive (at " + c.currentX() + "," + c.currentY() + ")");
      c.sleep(1500);
    }
    log("Couldn't reach " + what + " after 3 attempts - stopping (at " + c.currentX() + "," + c.currentY() + ")");
    c.displayMessage("@red@" + TAG + "@whi@couldn't reach " + what);
  }

  // ============================================================================ favourites

  private static File dataFile(String name) {
    File dir = new File("accounts");
    if (!dir.isDirectory()) {
      try {
        File jar = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        dir = new File(jar.isFile() ? jar.getParentFile() : jar, "accounts");
      } catch (Exception ignored) {
        // keep the relative folder
      }
      dir.mkdirs();
    }
    return new File(dir, name);
  }

  private static synchronized List<String> readList(String file) {
    try {
      File f = dataFile(file);
      if (!f.isFile()) return new ArrayList<>();
      List<String> out = new ArrayList<>();
      for (String l : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8))
        if (!l.trim().isEmpty() && !l.startsWith("#")) out.add(l.trim());
      return out;
    } catch (Exception e) {
      return new ArrayList<>();
    }
  }

  private static synchronized void writeList(String file, List<String> items) {
    try {
      Files.write(dataFile(file).toPath(), items, StandardCharsets.UTF_8);
    } catch (Exception e) {
      Main.log(TAG + "couldn't save " + file + ": " + e);
    }
  }

  private static List<String> favourites() {
    return readList("locationwalker-favourites.txt");
  }

  private static void setFavourites(List<String> f) {
    writeList("locationwalker-favourites.txt", new ArrayList<>(new LinkedHashSet<>(f)));
  }

  private static void addRecent(String entry) {
    List<String> r = readList("locationwalker-recent.txt");
    r.remove(entry);
    r.add(0, entry);
    while (r.size() > MAX_RECENT) r.remove(r.size() - 1);
    writeList("locationwalker-recent.txt", r);
  }

  // ============================================================================ GUI

  /** Shows the picker; returns once Start/double-click chose a destination (true) or it closed. */
  private boolean showUI(String prefill) {
    try {
      SwingUtilities.invokeAndWait(() -> buildUI(prefill));
    } catch (Exception e) {
      Main.logError(TAG + "couldn't open the window", e);
      return false;
    }
    while (c.isRunning() && !started && frame.isVisible()) c.sleep(250);
    SwingUtilities.invokeLater(() -> frame.dispose());
    return started && destX > 0;
  }

  private void buildUI(String prefill) {
    Color bg = Main.primaryBG, fg = Main.primaryFG, bg2 = Main.primaryBG.brighter();
    frame = new JFrame("LocationWalker");
    frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    JPanel root = new JPanel(new BorderLayout(4, 4));
    root.setBorder(new EmptyBorder(6, 6, 6, 6));
    root.setBackground(bg);

    filter = new JTextField();
    styleField(filter, bg2, fg);
    filter.setToolTipText("Filter location names (all words must match)");
    JLabel fl = label("Filter:", fg);
    JPanel top = new JPanel(new BorderLayout(4, 0));
    top.setBackground(bg);
    top.add(fl, BorderLayout.WEST);
    top.add(filter, BorderLayout.CENTER);

    allModel = model();
    favModel = model();
    recentModel = model();
    allTable = table(allModel, bg, bg2, fg);
    favTable = table(favModel, bg, bg2, fg);
    recentTable = table(recentModel, bg, bg2, fg);

    JTabbedPane quick = new JTabbedPane();
    quick.setBackground(bg);
    quick.setForeground(fg);
    quick.addTab("\u2605 Favourites", scroll(favTable, bg2));
    quick.addTab("Recent", scroll(recentTable, bg2));
    quick.setPreferredSize(new Dimension(420, 150));

    JSplitPane split =
        new JSplitPane(JSplitPane.VERTICAL_SPLIT, quick, scroll(allTable, bg2));
    split.setResizeWeight(0.3);
    split.setBorder(null);
    split.setBackground(bg);

    favBtn = button("\u2606 Add favourite", bg, fg);
    favBtn.setEnabled(false);
    JButton removeBtn = button("Remove favourite", bg, fg);
    startBtn = button("Start", bg, fg);
    startBtn.setEnabled(false);
    selLabel = label("Select a location (double-click to start)", fg);

    JPanel buttons = new JPanel(new GridLayout(1, 3, 4, 0));
    buttons.setBackground(bg);
    buttons.add(favBtn);
    buttons.add(removeBtn);
    buttons.add(startBtn);
    JPanel south = new JPanel(new BorderLayout(0, 4));
    south.setBackground(bg);
    south.add(selLabel, BorderLayout.NORTH);
    south.add(buttons, BorderLayout.SOUTH);

    root.add(top, BorderLayout.NORTH);
    root.add(split, BorderLayout.CENTER);
    root.add(south, BorderLayout.SOUTH);
    frame.setContentPane(root);

    // listeners
    for (JTable t : new JTable[] {allTable, favTable, recentTable}) {
      t.getSelectionModel()
          .addListSelectionListener(
              e -> {
                if (!e.getValueIsAdjusting()) select(t);
              });
      t.addMouseListener(
          new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
              if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                int row = t.rowAtPoint(e.getPoint());
                if (row >= 0) {
                  t.setRowSelectionInterval(row, row);
                  select(t);
                  startPressed();
                }
              }
            }
          });
    }
    startBtn.addActionListener(e -> startPressed());
    favBtn.addActionListener(
        e -> {
          if (selName == null) return;
          List<String> f = favourites();
          if (f.contains(selName)) {
            f.remove(selName);
            log("Removed favourite: " + selName);
          } else {
            f.add(selName);
            log("Added favourite: " + selName);
          }
          setFavourites(f);
          refreshQuickLists();
          updateSelectionUi();
        });
    removeBtn.addActionListener(
        e -> {
          int row = favTable.getSelectedRow();
          String name = row >= 0 ? String.valueOf(favModel.getValueAt(row, 0)) : selName;
          if (name == null) return;
          List<String> f = favourites();
          if (f.remove(name)) {
            setFavourites(f);
            log("Removed favourite: " + name);
            refreshQuickLists();
            updateSelectionUi();
          }
        });
    filter
        .getDocument()
        .addDocumentListener(
            new DocumentListener() {
              public void insertUpdate(DocumentEvent e) {
                populateAll();
              }

              public void removeUpdate(DocumentEvent e) {
                populateAll();
              }

              public void changedUpdate(DocumentEvent e) {
                populateAll();
              }
            });

    if (prefill != null && !prefill.isEmpty()) filter.setText(prefill);
    populateAll();
    refreshQuickLists();
    frame.setSize(460, 560);
    frame.setLocationRelativeTo(Main.rscFrame);
    frame.setVisible(true);
    filter.requestFocusInWindow();
  }

  private DefaultTableModel model() {
    return new DefaultTableModel(new String[] {"Location", "Coordinates"}, 0) {
      @Override
      public boolean isCellEditable(int r, int col) {
        return false;
      }
    };
  }

  private JTable table(DefaultTableModel m, Color bg, Color bg2, Color fg) {
    JTable t = new JTable(m);
    t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    t.setBackground(bg2);
    t.setForeground(fg);
    t.setGridColor(bg);
    t.setSelectionBackground(new Color(0x2f, 0x5f, 0x8f));
    t.setSelectionForeground(Color.WHITE);
    t.setFillsViewportHeight(true);
    t.getColumnModel().getColumn(1).setPreferredWidth(90);
    t.getColumnModel().getColumn(1).setMaxWidth(100);
    DefaultTableCellRenderer center = new DefaultTableCellRenderer();
    center.setHorizontalAlignment(SwingConstants.CENTER);
    t.getColumnModel().getColumn(1).setCellRenderer(center);
    JTableHeader h = t.getTableHeader();
    h.setReorderingAllowed(false);
    h.setBackground(bg);
    h.setForeground(fg);
    return t;
  }

  private JScrollPane scroll(JTable t, Color bg2) {
    JScrollPane s = new JScrollPane(t);
    s.getViewport().setBackground(bg2);
    s.setBorder(BorderFactory.createLineBorder(Main.primaryBG.darker()));
    return s;
  }

  private JButton button(String text, Color bg, Color fg) {
    JButton b = new JButton(text);
    b.setBackground(Main.secondaryBG);
    b.setForeground(Main.secondaryFG);
    b.setFocusable(false);
    return b;
  }

  private JLabel label(String text, Color fg) {
    JLabel l = new JLabel(text);
    l.setForeground(fg);
    return l;
  }

  private void styleField(JTextField f, Color bg2, Color fg) {
    f.setBackground(bg2);
    f.setForeground(fg);
    f.setCaretColor(fg);
    f.setBorder(BorderFactory.createCompoundBorder(
        BorderFactory.createLineBorder(Main.primaryBG.darker()), new EmptyBorder(2, 4, 2, 4)));
  }

  private boolean listable(Location loc) {
    try {
      return !Arrays.asList(hiddenLocations).contains(loc) && loc.isWalkable() && loc.isWalkConditionMet();
    } catch (Throwable e) {
      return true;
    }
  }

  private void populateAll() {
    String f = filter.getText().trim().toLowerCase(Locale.ROOT);
    String[] words = f.isEmpty() ? new String[0] : f.split("\\s+");
    allModel.setRowCount(0);
    for (Location loc : Location.values()) {
      if (!listable(loc)) continue;
      String d = loc.getDescription().toLowerCase(Locale.ROOT);
      if (!Arrays.stream(words).allMatch(d::contains)) continue;
      allModel.addRow(new Object[] {loc.getDescription(), "(" + loc.getX() + ", " + loc.getY() + ")"});
    }
  }

  private void refreshQuickLists() {
    fillQuick(favModel, favourites());
    fillQuick(recentModel, readList("locationwalker-recent.txt"));
  }

  private void fillQuick(DefaultTableModel m, List<String> entries) {
    m.setRowCount(0);
    Set<String> seen = new LinkedHashSet<>();
    for (String e : entries) {
      if (!seen.add(e)) continue;
      Matcher xy = Pattern.compile("^(\\d+),(\\d+)$").matcher(e);
      if (xy.matches()) {
        m.addRow(new Object[] {e, "(" + xy.group(1) + ", " + xy.group(2) + ")"});
        continue;
      }
      Location loc = findExact(e);
      if (loc != null) m.addRow(new Object[] {loc.getDescription(), "(" + loc.getX() + ", " + loc.getY() + ")"});
    }
  }

  private Location findExact(String name) {
    for (Location l : Location.values()) if (l.getDescription().equals(name)) return l;
    return null;
  }

  private void select(JTable t) {
    int row = t.getSelectedRow();
    if (row < 0) return;
    selName = String.valueOf(t.getModel().getValueAt(row, 0));
    // keep one selection across the three lists
    for (JTable o : new JTable[] {allTable, favTable, recentTable}) if (o != t) o.clearSelection();
    updateSelectionUi();
  }

  private void updateSelectionUi() {
    boolean has = selName != null;
    startBtn.setEnabled(has);
    favBtn.setEnabled(has);
    if (has) {
      boolean fav = favourites().contains(selName);
      favBtn.setText(fav ? "\u2605 Unfavourite" : "\u2606 Add favourite");
      selLabel.setText("Selected: " + selName);
    }
  }

  private void startPressed() {
    if (selName == null || !parseDestination(selName)) return;
    started = true;
    frame.setVisible(false);
  }

  // ============================================================================ misc

  private void log(String s) {
    Main.log(TAG + s);
    System.out.println(TAG + s);
    try {
      c.setStatus("@cya@" + s);
    } catch (Throwable ignored) {
    }
    try {
      bot.probe.Probe.recordEvent("locationwalker", s);
    } catch (Throwable ignored) {
      // older client without the probe
    }
  }

  @Override
  public void paintInterrupt() {
    if (c != null) {
      paintBuilder.setBorderColor(paintBuilder.colorRainbow);
      paintBuilder.setBackgroundColor(bgColor, 255);
      paintBuilder.setTitleCenteredSingleColor("LocationWalker", colorPurple, 4);
      paintBuilder.addRow(rowBuilder.centeredSingleStringRow("Seatta / Ben", colorPurple, 1));
      paintBuilder.addRow(
          rowBuilder.centeredSingleStringRow(paintBuilder.stringRunTime, colorGreen, 1));
      if (destX > 0) {
        paintBuilder.addSpacerRow(4);
        paintBuilder.addRow(rowBuilder.centeredSingleStringRow("Walking Towards: ", colorWhite, 1));
        paintBuilder.addRow(rowBuilder.centeredSingleStringRow(describeDest(), colorPurple, 1));
      }
      paintBuilder.draw();
    }
  }
}
