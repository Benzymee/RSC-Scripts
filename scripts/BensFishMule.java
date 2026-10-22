import bot.Main;
import bot.ui.scriptselector.models.Category;
import bot.ui.scriptselector.models.ScriptInfo;
import com.openrsc.client.entityhandling.instances.Item;
import controller.Controller;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import orsc.ORSCharacter;
import scripting.idlescript.IdleScript;

/**
 * Bens Trade Acceptor. Stands in Catherby bank (walks back if moved), accepts trade requests one
 * at a time, never offers anything, and accepts both trade screens. Banks everything it received
 * straight away, unless Mass trade is ticked: then it holds the items and only banks after 10
 * seconds with no new trade request. Optional whitelist (comma separated names): only those
 * players are traded; empty = anyone. Stays logged in with a small step every few minutes. Fish
 * certificates from the guild (swordfish / shark certs, 5 fish each) are accepted and banked like
 * fish; the log and paint show what they are worth.
 *
 * <p>Script arguments (skip the window): {@code whitelist=name1,name2}
 */
public class BensFishMule extends IdleScript {
  public static final ScriptInfo info =
      new ScriptInfo(
          new Category[] {Category.MISCELLANEOUS, Category.IRONMAN_SUPPORTED},
          "Ben",
          "Accepts trades in Catherby bank.\n\n"
              + "Never offers anything. Banks what it receives, or with Mass trade waits\n"
              + "10 seconds after the last trade request before banking.\n"
              + "Optional whitelist. Fish certificates count as 5 fish.",
          null,
          "Bens Trade Acceptor");

  static final int[] HOME = {440, 493};
  static final int HOME_RADIUS = 3;
  static final long ANTI_IDLE_MS = 4 * 60_000L;
  /** Mass trade: bank only after this long with no new trade request. */
  static final long MASS_TRADE_QUIET_MS = 10_000L;

  String whitelist = "";
  /** Hold items between trades instead of banking after each one. */
  boolean massTrade = false;
  /** When the last accepted trade request arrived, or when the last trade closed. */
  long lastTradeRequestAt = 0;
  volatile boolean setupDone = false;
  volatile boolean setupCancelled = false;

  final Set<String> pending = new LinkedHashSet<>();
  int trades = 0;
  int declined = 0;
  final Map<String, Integer> banked = new LinkedHashMap<>();
  /** Fish received as certificates (5 per certificate). */
  int certFish = 0;
  long lastMove = System.currentTimeMillis();

  private Controller c() {
    return Main.getController();
  }

  private void log(String s) {
    Main.log("[Bens Trade Acceptor] " + s);
    Controller c = c();
    if (c != null) c.setStatus("@cya@" + s);
  }

  /** Waiting for farmers is the mule's job, not a stall. */
  private void notStalled(long ms) {
    try {
      bot.probe.Probe.noteIntentionalWait(ms);
    } catch (Throwable ignored) {
      // older clients have no stall watch
    }
  }

  private static String norm(String n) {
    return n == null ? "" : n.replace('\u00a0', ' ').replace('_', ' ').trim().toLowerCase();
  }

  private boolean allowed(String name) {
    String n = norm(name);
    if (n.isEmpty()) return false;
    String wl = whitelist == null ? "" : whitelist.trim();
    if (wl.isEmpty()) return true;
    for (String w : wl.split(",")) if (norm(w).equals(n)) return true;
    return false;
  }

  public int start(String[] param) {
    Controller controller = c();
    if (controller == null) return 1000;
    if (!setup(param)) return 1000;
    log(
        "Ready in Catherby bank - "
            + (massTrade ? "mass trade, banking after 10 quiet seconds - " : "")
            + (whitelist.trim().isEmpty() ? "trading anyone" : "whitelist: " + whitelist));
    paintBuilder.start(4, 18, 200);
    while (controller.isRunning()) {
      if (controller.getNeedToMove()) controller.moveCharacter();
      if (controller.getShouldSleep()) controller.sleepHandler(true);
      if (!controller.isLoggedIn()) {
        controller.sleep(1000);
        continue;
      }
      if (controller.isInTrade()) {
        handleTrade();
        continue;
      }
      String who = nextPending();
      if (who != null) {
        answer(who);
        continue;
      }
      if (controller.getInventoryItemCount() > 0 && shouldBank()) {
        log(massTrade ? "No trade request for 10 seconds - banking" : "Banking");
        bankAll();
        continue;
      }
      stayHome();
      if (massTrade && controller.getInventoryItemCount() > 0) {
        long left = Math.max(0, MASS_TRADE_QUIET_MS - (System.currentTimeMillis() - lastTradeRequestAt));
        controller.setStatus("@cya@Mass trade: holding items (" + ((left + 999) / 1000) + "s)");
      } else {
        controller.setStatus("@cya@Waiting for trades (" + trades + " done)");
      }
      notStalled(5000);
      controller.sleep(300);
    }
    return 1000;
  }

  private synchronized String nextPending() {
    if (pending.isEmpty()) return null;
    String n = pending.iterator().next();
    pending.remove(n);
    return n;
  }

  @Override
  public void tradeMessageInterrupt(String sender) {
    if (sender == null || sender.trim().isEmpty()) return;
    if (!allowed(sender)) {
      declined++;
      log("Ignoring trade request from " + sender + " (not on the whitelist)");
      return;
    }
    lastTradeRequestAt = System.currentTimeMillis();
    synchronized (this) {
      pending.add(sender.trim());
    }
  }

  /** Mass trade keeps the bag until the requests stop. */
  private boolean shouldBank() {
    if (!massTrade) return true;
    if (lastTradeRequestAt == 0) return true;
    return System.currentTimeMillis() - lastTradeRequestAt >= MASS_TRADE_QUIET_MS;
  }

  /** Trades back a player who asked (that opens the trade). */
  private void answer(String who) {
    Controller controller = c();
    ORSCharacter p = null;
    for (ORSCharacter o : controller.getPlayers())
      if (o != null && norm(o.displayName).equals(norm(who))) p = o;
    if (p == null) {
      log(who + " asked to trade but isn't in sight");
      return;
    }
    log("Accepting a trade request from " + who);
    controller.tradePlayer(p.serverIndex);
    long until = System.currentTimeMillis() + 10_000;
    while (controller.isRunning() && !controller.isInTrade() && System.currentTimeMillis() < until)
      controller.sleep(200);
    if (controller.isInTrade()) handleTrade();
  }

  private Map<Integer, Integer> inventory() {
    Map<Integer, Integer> m = new LinkedHashMap<>();
    for (Item it : c().getInventoryItems()) m.merge(it.getCatalogID(), it.getAmount(), Integer::sum);
    return m;
  }

  private void handleTrade() {
    Controller controller = c();
    String who = controller.getTradeRecipientName();
    if (!allowed(who)) {
      declined++;
      log("Declining a trade with " + who + " (not on the whitelist)");
      controller.declineTrade();
      controller.sleep(1000);
      return;
    }
    Map<Integer, Integer> before = inventory();
    long until = System.currentTimeMillis() + 90_000;
    long lastAccept = 0;
    while (controller.isRunning() && controller.isInTrade() && System.currentTimeMillis() < until) {
      if (controller.getLocalTradeItemsCount() > 0) { // the mule never offers anything
        controller.removeAllTradeItems();
        controller.sleep(600);
        continue;
      }
      if (System.currentTimeMillis() - lastAccept > 1500) {
        if (controller.isInTradeConfirmation()) {
          controller.acceptTradeConfirmation();
          lastAccept = System.currentTimeMillis();
        } else if (controller.getRecipientTradeItemsCount() > 0) {
          controller.acceptTrade();
          lastAccept = System.currentTimeMillis();
        }
      }
      controller.setStatus("@cya@Trading " + who + (controller.isInTradeConfirmation() ? " (confirming)" : ""));
      notStalled(5000);
      controller.sleep(250);
    }
    if (controller.isInTrade()) {
      log("Trade with " + who + " timed out - declining");
      controller.declineTrade();
      controller.sleep(1000);
      return;
    }
    controller.sleep(1200);
    Map<Integer, Integer> after = inventory();
    List<String> got = new ArrayList<>();
    for (Map.Entry<Integer, Integer> e : after.entrySet()) {
      int d = e.getValue() - before.getOrDefault(e.getKey(), 0);
      if (d > 0) {
        String nm = controller.getItemName(e.getKey());
        boolean cert = nm != null && nm.toLowerCase().contains("certificate");
        if (cert) certFish += d * 5;
        got.add(d + " " + nm + (cert ? " (= " + (d * 5) + " fish)" : ""));
      }
    }
    if (got.isEmpty()) {
      log("Trade with " + who + " closed without anything received");
      return;
    }
    trades++;
    if (massTrade) {
      lastTradeRequestAt = System.currentTimeMillis();
      log("Trade " + trades + " from " + who + ": " + String.join(", ", got) + " - holding for more trades");
      return;
    }
    log("Trade " + trades + " from " + who + ": " + String.join(", ", got) + " - banking");
    bankAll();
  }

  private void bankAll() {
    Controller controller = c();
    if (!controller.isInBank()) {
      controller.openBank();
      long until = System.currentTimeMillis() + 8000;
      while (controller.isRunning() && !controller.isInBank() && System.currentTimeMillis() < until)
        controller.sleep(200);
    }
    if (!controller.isInBank()) return;
    for (Map.Entry<Integer, Integer> e : inventory().entrySet()) {
      int id = e.getKey();
      int n = controller.getInventoryItemCount(id);
      if (n <= 0) continue;
      controller.depositItem(id, n);
      long until = System.currentTimeMillis() + 4000;
      while (controller.isRunning() && controller.getInventoryItemCount(id) > 0 && System.currentTimeMillis() < until)
        controller.sleep(200);
      if (controller.getInventoryItemCount(id) == 0) banked.merge(controller.getItemName(id), n, Integer::sum);
    }
    controller.closeBank();
    controller.sleep(640);
    step(); // a step ends the bank session on the server (trades can't start while banking)
  }

  private void step() {
    Controller controller = c();
    int x = controller.currentX() == HOME[0] ? HOME[0] + 1 : HOME[0];
    controller.walkTo(x, HOME[1]);
    lastMove = System.currentTimeMillis();
  }

  /** Back to the bank if moved; a small step now and then so the server doesn't log us out idle. */
  private void stayHome() {
    Controller controller = c();
    int dx = Math.abs(controller.currentX() - HOME[0]);
    int dy = Math.abs(controller.currentY() - HOME[1]);
    if (Math.max(dx, dy) > HOME_RADIUS) {
      log("Walking back to Catherby bank");
      controller.walkTo(HOME[0], HOME[1]);
      lastMove = System.currentTimeMillis();
      return;
    }
    if (System.currentTimeMillis() - lastMove > ANTI_IDLE_MS) step();
  }

  // ---------------------------------------------------------------------------------------------
  // Setup window
  // ---------------------------------------------------------------------------------------------

  private boolean setup(String[] param) {
    String joined = param == null ? "" : String.join(" ", param).trim();
    if (!joined.isEmpty()) {
      whitelist = joined.toLowerCase().startsWith("whitelist=") ? joined.substring(10) : joined;
      return true;
    }
    if (java.awt.GraphicsEnvironment.isHeadless()) return true;
    SwingUtilities.invokeLater(this::showSetup);
    Controller controller = c();
    while (controller.isRunning() && !setupDone && !setupCancelled) controller.sleep(200);
    if (!setupDone) {
      log("Setup window closed - not starting");
      controller.stop();
      return false;
    }
    return true;
  }

  private void showSetup() {
    JFrame f = new JFrame("Bens Trade Acceptor");
    JPanel p = new JPanel(new GridLayout(0, 1, 4, 4));
    p.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
    JTextField wl = new JTextField(whitelist, 24);
    JCheckBox mass = new JCheckBox("Mass trade", massTrade);
    p.add(new JLabel("Only trade these players (comma separated, empty = anyone):"));
    p.add(wl);
    p.add(mass);
    p.add(new JLabel("Mass trade holds items and banks 10 seconds after the last trade request."));
    p.add(new JLabel("Stand this account in Catherby bank. It never offers anything."));
    JButton start = new JButton("Start");
    start.addActionListener(
        e -> {
          whitelist = wl.getText().trim();
          massTrade = mass.isSelected();
          setupDone = true;
          f.dispose();
        });
    f.addWindowListener(
        new java.awt.event.WindowAdapter() {
          @Override
          public void windowClosed(java.awt.event.WindowEvent e) {
            if (!setupDone) setupCancelled = true;
          }
        });
    p.add(start);
    f.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    f.setContentPane(p);
    f.pack();
    f.setLocationRelativeTo(null);
    f.setVisible(true);
  }

  /** "shark certificate (market)" -> "shark certs" so the paint row fits. */
  private static String shortName(String n) {
    if (n == null) return "?";
    return n.replaceAll("(?i)\\s*certificate\\s*\\(market\\)", " certs").replaceAll("(?i)\\s*certificate", " certs");
  }

  @Override
  public void paintInterrupt() {
    Controller controller = c();
    if (controller != null) {
      paintBuilder.setTitleCenteredSingleColor("Bens Trade Acceptor", 0xD4AF37, 4);
      paintBuilder.addSpacerRow(4);
      paintBuilder.setBackgroundColor(0x282828, 200);
      paintBuilder.setBorderColor(0xD4AF37);
      paintBuilder.addRow(
          rowBuilder.multipleStringRow(
              new String[] {"Trades:", paintBuilder.stringFormatInt(trades), declined > 0 ? declined + " declined" : ""},
              new int[] {0xD4AF37, 0xE0E0E0, 0xFFE066},
              new int[] {4, 82, 58},
              1));
      if (certFish > 0)
        paintBuilder.addRow(
            rowBuilder.multipleStringRow(
                new String[] {"Certs worth:", paintBuilder.stringFormatInt(certFish) + " fish"},
                new int[] {0xD4AF37, 0xE0E0E0},
                new int[] {4, 112},
                1));
      for (Map.Entry<String, Integer> e : new ArrayList<>(banked.entrySet())) {
        paintBuilder.addRow(
            rowBuilder.multipleStringRow(
                new String[] {shortName(e.getKey()) + ":", paintBuilder.stringFormatInt(e.getValue())},
                new int[] {0xD4AF37, 0xE0E0E0},
                new int[] {4, 112},
                1));
      }
    }
    paintBuilder.draw();
  }
}
