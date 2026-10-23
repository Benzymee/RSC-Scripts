import bot.Main;
import bot.tabs.MassStart;
import bot.tabs.MassStartSettings;
import bot.ui.scriptselector.models.Category;
import bot.ui.scriptselector.models.ScriptInfo;
import com.openrsc.client.entityhandling.instances.Item;
import controller.Controller;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import models.entities.ItemId;
import orsc.ORSCharacter;
import scripting.idlescript.IdleScript;

/**
 * Reads one account's bank, then either deposits that account's inventory or withdraws and trades.
 *
 * <p>Mass start and a script queue copy the choice (deposit, or withdraw and trade, and whether it
 * is only coins, and the player name). Every account then does that with its own bank and
 * inventory. The setup window's button is Start so the other accounts can press it too.
 *
 * <p>Worn items stay worn. A trade window holds 12 slots. A stack bigger than 500 million, or an
 * item named Unobtanium, is left out.
 */
public class BensTrade extends IdleScript implements MassStartSettings {
  public static final ScriptInfo info =
      new ScriptInfo(
          new Category[] {Category.TRADING},
          "Ben",
          "Opens the bank, then deposits the inventory or withdraws items and trades them.\n\n"
              + "Mule trade withdraws the ticked items and trades them to the name in the box.\n"
              + "Untick it to deposit the inventory at the closest bank. Just coins limits either one to coins.\n"
              + "Mass start and a script queue use the same choice on every account.",
          null,
          "Ben's Trade");

  private static final int COINS = ItemId.COINS.getId();
  private static final int TRADE_SLOTS = 12;
  private static final int BANK_CHEST = 942;
  /** A single real stack is nowhere near this. Bigger numbers are a bad bank read. */
  private static final int MAX_STACK = 500_000_000;

  private enum Phase {
    SCAN,
    WAIT,
    TRADE,
    DEPOSIT
  }

  /** Item table. Arrays only, so mass start can copy the choice and each account refills its own. */
  private int count;
  private int[] ids = new int[0];
  private int[] bankAmt = new int[0];
  private int[] invAmt = new int[0];
  private int[] wornAmt = new int[0];
  private boolean[] picked = new boolean[0];
  private String[] names = new String[0];

  private volatile Phase phase = Phase.SCAN;
  private volatile boolean started;
  private volatile boolean stopped;
  private volatile boolean configured;
  private volatile boolean muleTrade = true;
  private volatile boolean coinsOnly;
  private volatile boolean allItems = true;
  private volatile int[] chosenIds = new int[0];
  private volatile String setupAccount = "";
  private boolean quietScan;
  private volatile String recipient = "";
  private String prefill = "";
  private String status = "Checking the bank";
  private boolean guiShown;
  private boolean bankFresh;
  private boolean offered;
  private boolean notedOffer;
  private long lastRequest;
  private int trades;
  private boolean warnedSkip;
  private final Set<Integer> skipDeposit = new HashSet<Integer>();
  private int pendingDepositId = -1;
  private int pendingDepositLeft = -1;
  private long pendingDepositAt;
  private boolean holding;
  private boolean fromMassStart;
  private long holdUntil;
  private String holdReason = "";

  @Override
  public String[] massStartArguments() {
    if (!configured) return null;
    String items = coinsOnly ? "coins" : allItems ? "all" : joinIds(chosenIds);
    if (!muleTrade) return new String[] {"deposit", items};
    return new String[] {"trade", recipient == null ? "" : recipient, items};
  }

  @Override
  public int start(String[] parameters) {
    Controller c = controller;
    if (c == null) return 1000;
    if (MassStart.isFollowerStart()) fromMassStart = true;
    if (holding) return hold(c);
    if (stopped) {
      c.stop();
      return 0;
    }
    if (prefill.isEmpty() && parameters != null) {
      for (String parameter : parameters) {
        if (parameter == null) continue;
        String value = parameter.trim();
        if (value.isEmpty() || value.equalsIgnoreCase("deposit") || value.equalsIgnoreCase("trade"))
          continue;
        if (value.equalsIgnoreCase("coins") || value.equalsIgnoreCase("all") || value.indexOf(',') >= 0)
          continue;
        prefill = value;
        break;
      }
    }
    if (!c.isLoggedIn()) {
      status = "Waiting to log in";
      return 1000;
    }
    // The other accounts are started before their copied settings arrive. Wait, or they open a
    // second setup window and mass start stops them.
    if (!configured && MassStart.isFollowerStart()) {
      status = "Waiting for the mass start settings";
      return 300;
    }
    if (c.getNeedToMove()) c.moveCharacter();
    if (c.getShouldSleep()) {
      status = "Sleeping";
      c.sleepHandler(true);
      return 1000;
    }

    if (configured && muleTrade && !sameAccount(c)) {
      String me = c.getPlayerName();
      if (me == null || me.isEmpty()) {
        status = "Waiting to log in";
        return 500;
      }
      quietScan = true;
      phase = Phase.SCAN;
      bankFresh = false;
      guiShown = true;
    }

    if (phase == Phase.SCAN) return scan(c);
    if (phase == Phase.WAIT) {
      status = "Choose mule trade or deposit, then press Start";
      if (!configured) return 400;
      phase = muleTrade ? Phase.TRADE : Phase.DEPOSIT;
    }
    if (phase == Phase.DEPOSIT) return depositInventory(c);
    return trade(c);
  }

  @Override
  public void paintInterrupt() {
    if (controller == null) return;
    int y = 36;
    controller.drawString("Ben's Trade", 6, y, 0xD4AF37, 1);
    y += 14;
    controller.drawString(status, 6, y, 0xFFFFFF, 1);
    y += 14;
    String detail =
        configured && !muleTrade
            ? (coinsOnly ? "Coins left in bag " : "Left in bag ") + format(leftToDeposit(controller))
            : "Trades " + trades + "   still to send " + format(remaining(controller));
    controller.drawString(detail, 6, y, 0xFFE066, 1);
  }

  @Override
  public void serverMessageInterrupt(String message) {
    if (message == null) return;
    String text = message.toLowerCase(Locale.ENGLISH);
    if (text.contains("declin")) offered = false;
    if (text.contains("not near") || text.contains("too far") || text.contains("can't reach")) {
      lastRequest = 0;
    }
  }

  private int scan(Controller c) {
    if (c.isInTrade()) {
      c.declineTrade();
      return 600;
    }
    if (c.isInShop()) {
      c.closeShop();
      return 600;
    }
    if (!c.isInBank()) {
      if (!nearBank(c)) {
        status = quietScan ? "Reading this account's bank" : "Walking to a bank";
        c.walkTowardsBank();
        return 800;
      }
      status = "Opening the bank";
      c.openBank();
      return 600;
    }

    readItems(c);
    String me = c.getPlayerName();
    if (me != null && !me.isEmpty()) setupAccount = me;
    c.closeBank();
    if (quietScan || configured) {
      quietScan = false;
      applyChoice();
      bankFresh = false;
      phase = muleTrade ? Phase.TRADE : Phase.DEPOSIT;
      status =
          muleTrade
              ? "Withdrawing and trading to " + recipient
              : coinsOnly ? "Depositing coins" : "Depositing the inventory";
      return 400;
    }
    phase = Phase.WAIT;
    status = count == 0 ? "Nothing tradeable in the bank or inventory" : "Choose what to trade";
    if (!guiShown) {
      guiShown = true;
      SwingUtilities.invokeLater(
          new Runnable() {
            @Override
            public void run() {
              showGui();
            }
          });
    }
    return 400;
  }

  /** Puts the inventory in the closest bank. Just coins deposits only the coins. */
  private int depositInventory(Controller c) {
    if (c.isInTrade()) {
      c.declineTrade();
      return 400;
    }
    if (c.isInShop()) {
      c.closeShop();
      return 400;
    }
    if (pendingDepositId >= 0) {
      int left = unequippedCount(c, pendingDepositId);
      if (left < pendingDepositLeft) {
        pendingDepositId = -1;
      } else if (System.currentTimeMillis() - pendingDepositAt < 3000) {
        return 400;
      } else {
        skipDeposit.add(pendingDepositId);
        String skipped = c.getItemName(pendingDepositId);
        if (skipped == null || skipped.isEmpty()) skipped = "item " + pendingDepositId;
        c.log("Left " + skipped + " in the inventory. The bank would not take it.", "red");
        pendingDepositId = -1;
      }
    }
    if (!c.isInBank()) {
      if (!nearBank(c)) {
        status = "Walking to the closest bank";
        c.walkTowardsBank();
        return 800;
      }
      status = "Opening the bank";
      c.openBank();
      return 600;
    }

    int id = nextDepositId(c);
    if (id < 0) {
      c.closeBank();
      finish(c, coinsOnly ? "Deposited the coins" : "Deposited the inventory");
      return 0;
    }
    int have = unequippedCount(c, id);
    String name = c.getItemName(id);
    status = "Depositing " + (name == null || name.isEmpty() ? "item " + id : name);
    c.depositItem(id, have);
    pendingDepositId = id;
    pendingDepositLeft = have;
    pendingDepositAt = System.currentTimeMillis();
    return 700;
  }

  private int nextDepositId(Controller c) {
    if (coinsOnly) {
      if (skipDeposit.contains(COINS) || unequippedCount(c, COINS) < 1) return -1;
      return COINS;
    }
    if (!allItems && chosenIds.length > 0) {
      for (int id : chosenIds) {
        if (skipDeposit.contains(id) || unequippedCount(c, id) < 1) continue;
        return id;
      }
      return -1;
    }
    for (Item item : c.getInventoryItems()) {
      if (item == null || item.getEquipped()) continue;
      int id = item.getCatalogID();
      if (id < 0 || skipDeposit.contains(id)) continue;
      if (unequippedCount(c, id) > 0) return id;
    }
    return -1;
  }

  private int leftToDeposit(Controller c) {
    if (coinsOnly) return Math.max(0, unequippedCount(c, COINS));
    if (!allItems && chosenIds.length > 0) {
      int total = 0;
      for (int id : chosenIds) total += Math.max(0, unequippedCount(c, id));
      return total;
    }
    int total = 0;
    for (Item item : c.getInventoryItems()) {
      if (item == null || item.getEquipped()) continue;
      int id = item.getCatalogID();
      if (id < 0 || skipDeposit.contains(id)) continue;
      int amount = item.getAmount();
      total += amount < 1 ? 1 : amount;
    }
    return total;
  }

  private int unequippedCount(Controller c, int id) {
    if (c.isItemStackable(id)) return Math.max(0, c.getInventoryItemCount(id));
    return Math.max(0, c.getInventoryItemCount(id) - equippedCount(c, id));
  }

  private int trade(Controller c) {
    if (c.isInShop()) {
      c.closeShop();
      return 400;
    }
    if (c.isInTradeConfirmation()) {
      if (wrongPerson(c)) {
        c.declineTrade();
        offered = false;
        return 600;
      }
      status = "Confirming trade with " + recipient;
      c.acceptTradeConfirmation();
      return 900;
    }
    if (c.isInTrade()) {
      if (c.isInBank()) c.closeBank();
      if (wrongPerson(c)) {
        c.declineTrade();
        offered = false;
        return 600;
      }
      if (!offered) {
        if (!putItemsUp(c)) {
          status = "Nothing in the inventory to offer";
          c.declineTrade();
          return 600;
        }
        offered = true;
        status = "Offering items to " + recipient;
        return 900;
      }
      status = "Accepting trade with " + recipient;
      c.acceptTrade();
      return 900;
    }

    if (offered) {
      trades++;
      offered = false;
    }

    if (bagCount(c) > 0) return sendTrade(c);

    if (!c.isInBank()) {
      if (!nearBank(c)) {
        status = "Walking to a bank";
        c.walkTowardsBank();
        return 800;
      }
      status = "Opening the bank";
      bankFresh = false;
      c.openBank();
      return 600;
    }

    if (!bankFresh) {
      for (int i = 0; i < count; i++) bankAmt[i] = bankCount(c, ids[i]);
      bankFresh = true;
    }
    if (selectedBankCount() < 1) {
      c.closeBank();
      finish(c, "Traded " + trades + " loads to " + recipient);
      return 0;
    }

    int next = nextWithdraw(c);
    if (next < 0) {
      if (c.getInventoryItemCount() >= 30) {
        finish(c, "Inventory is full of other items, so the rest cannot be withdrawn");
        return 0;
      }
      c.closeBank();
      finish(c, "Traded " + trades + " loads to " + recipient);
      return 0;
    }
    int take = withdrawAmount(c, next);
    if (take < 1) {
      c.closeBank();
      return 600;
    }
    status = "Withdrawing " + names[next];
    c.withdrawItem_apos(ids[next], take);
    bankAmt[next] = Math.max(0, bankAmt[next] - take);
    return 800;
  }

  private int sendTrade(Controller c) {
    if (c.isInBank()) {
      c.closeBank();
      return 400;
    }
    int index = c.getPlayerServerIndexByName(recipient);
    if (index < 0) {
      status = "Waiting for " + recipient + " to stand nearby";
      return 1000;
    }
    int[] xy = c.getPlayerCoordsByServerIndex(index);
    if (xy != null && xy[0] != -1 && c.distanceTo(xy[0], xy[1]) > 2) {
      status = "Walking to " + recipient;
      c.walkTo(xy[0], xy[1]);
      return 700;
    }
    long now = System.currentTimeMillis();
    if (now - lastRequest < 2500) return 400;
    status = "Trading " + recipient;
    c.tradePlayer(index);
    lastRequest = now;
    return 800;
  }

  /** Puts up to 12 trade slots on the table. Stacks and notes use one slot. */
  private boolean putItemsUp(Controller c) {
    List<Integer> offerIds = new ArrayList<Integer>();
    List<Integer> amounts = new ArrayList<Integer>();
    int slots = 0;
    notedOffer = !hasPlainItems(c);
    for (int i = 0; i < count; i++) {
      if (!picked[i] || slots >= TRADE_SLOTS) continue;
      if (c.isItemStackable(ids[i])) {
        if (notedOffer) continue;
        int have = c.getInventoryItemCount(ids[i]);
        if (have < 1) continue;
        offerIds.add(ids[i]);
        amounts.add(have);
        slots++;
        continue;
      }
      if (!notedOffer) {
        int have = plainCount(c, ids[i]);
        int give = Math.min(have, TRADE_SLOTS - slots);
        if (give < 1) continue;
        offerIds.add(ids[i]);
        amounts.add(give);
        slots += give;
      } else {
        int have = c.getNotedInventoryItemCount(ids[i]);
        if (have < 1) continue;
        offerIds.add(ids[i]);
        amounts.add(have);
        slots++;
      }
    }
    if (offerIds.isEmpty()) return false;
    int[] idArr = new int[offerIds.size()];
    int[] amtArr = new int[amounts.size()];
    for (int i = 0; i < offerIds.size(); i++) {
      idArr[i] = offerIds.get(i);
      amtArr[i] = amounts.get(i);
    }
    return c.setTradeItems(idArr, amtArr, notedOffer);
  }

  private boolean hasPlainItems(Controller c) {
    for (int i = 0; i < count; i++) {
      if (!picked[i]) continue;
      if (c.isItemStackable(ids[i]) && c.getInventoryItemCount(ids[i]) > 0) return true;
      if (plainCount(c, ids[i]) > 0) return true;
    }
    return false;
  }

  private int bagCount(Controller c) {
    int total = 0;
    for (int i = 0; i < count; i++) {
      if (!picked[i]) continue;
      if (c.isItemStackable(ids[i])) total += Math.max(0, c.getInventoryItemCount(ids[i]));
      else {
        total += plainCount(c, ids[i]);
        total += Math.max(0, c.getNotedInventoryItemCount(ids[i]));
      }
    }
    return total;
  }

  private int nextWithdraw(Controller c) {
    int empty = 30 - c.getInventoryItemCount();
    for (int i = 0; i < count; i++) {
      if (!picked[i] || bankAmt[i] < 1) continue;
      if (c.isItemStackable(ids[i])) {
        if (c.getInventoryItemCount(ids[i]) > 0 || empty > 0) return i;
        continue;
      }
      if (empty > 0) return i;
    }
    return -1;
  }

  private int withdrawAmount(Controller c, int index) {
    int bank = Math.min(bankAmt[index], bankCount(c, ids[index]));
    if (bank < 1) return 0;
    if (c.isItemStackable(ids[index])) return bank;
    int empty = 30 - c.getInventoryItemCount();
    return Math.min(bank, empty);
  }

  private int selectedBankCount() {
    int total = 0;
    for (int i = 0; i < count; i++) {
      if (picked[i] && bankAmt[i] > 0) total += bankAmt[i];
    }
    return total;
  }

  private long remaining(Controller c) {
    long total = 0;
    for (int i = 0; i < count; i++) {
      if (!picked[i]) continue;
      total += Math.max(0, bankAmt[i]);
      if (c.isItemStackable(ids[i])) total += Math.max(0, c.getInventoryItemCount(ids[i]));
      else {
        total += plainCount(c, ids[i]);
        total += Math.max(0, c.getNotedInventoryItemCount(ids[i]));
      }
    }
    return total;
  }

  private int plainCount(Controller c, int id) {
    return Math.max(0, c.getUnnotedInventoryItemCount(id) - equippedCount(c, id));
  }

  private int equippedCount(Controller c, int id) {
    int equipped = 0;
    for (Item item : c.getInventoryItems()) {
      if (item == null || item.getCatalogID() != id || !item.getEquipped()) continue;
      equipped += Math.max(1, item.getAmount());
    }
    return equipped;
  }

  private int bankCount(Controller c, int id) {
    int total = 0;
    for (Item item : c.getBankItems()) {
      if (item == null || item.getCatalogID() != id) continue;
      int amount = item.getAmount();
      if (amount > 0 && amount <= MAX_STACK) total += amount;
    }
    return total;
  }

  private void readItems(Controller c) {
    Map<Integer, Hold> found = new LinkedHashMap<Integer, Hold>();
    for (Item item : c.getBankItems()) addAmount(c, found, item, true);
    for (Item item : c.getInventoryItems()) addAmount(c, found, item, false);
    List<Hold> list = new ArrayList<Hold>(found.values());
    list.sort(
        new Comparator<Hold>() {
          @Override
          public int compare(Hold a, Hold b) {
            return a.name.compareToIgnoreCase(b.name);
          }
        });
    count = list.size();
    ids = new int[count];
    bankAmt = new int[count];
    invAmt = new int[count];
    wornAmt = new int[count];
    picked = new boolean[count];
    names = new String[count];
    for (int i = 0; i < count; i++) {
      Hold hold = list.get(i);
      ids[i] = hold.id;
      bankAmt[i] = hold.bank;
      invAmt[i] = hold.inv;
      wornAmt[i] = hold.worn;
      names[i] = hold.name;
      picked[i] = hold.bank + hold.inv > 0;
    }
  }

  /** Ticks this account's own items using the choice copied from the account that was set up. */
  private void applyChoice() {
    for (int i = 0; i < count; i++) {
      boolean can = bankAmt[i] + invAmt[i] > 0;
      if (!can) {
        picked[i] = false;
        continue;
      }
      if (coinsOnly) picked[i] = ids[i] == COINS;
      else if (allItems || chosenIds.length == 0) picked[i] = true;
      else picked[i] = contains(chosenIds, ids[i]);
    }
  }

  private void addAmount(Controller c, Map<Integer, Hold> found, Item item, boolean fromBank) {
    if (item == null) return;
    int id = item.getCatalogID();
    if (id < 0 || id > 100_000) return;
    int amount = item.getAmount();
    if (amount < 1) amount = 1;
    if (amount > MAX_STACK) {
      if (!warnedSkip) {
        warnedSkip = true;
        c.log("Skipped a stack over " + format(MAX_STACK) + ". That amount is not traded.", "red");
      }
      return;
    }
    String name = c.getItemName(id);
    if (name == null || name.trim().isEmpty()) name = "Item " + id;
    if (name.equalsIgnoreCase("Unobtanium")) return;
    if (!c.isItemTradeable(id)) return;

    Hold hold = found.get(id);
    if (hold == null) {
      hold = new Hold();
      hold.id = id;
      hold.name = name;
      found.put(id, hold);
    }
    if (fromBank) hold.bank += amount;
    else if (item.getEquipped()) hold.worn += amount;
    else hold.inv += amount;
  }

  private boolean nearBank(Controller c) {
    ORSCharacter banker = c.getNearestNpcByIds(c.bankerIds, true);
    if (banker != null) {
      int[] coords = c.getNpcCoordsByServerIndex(banker.serverIndex);
      if (coords != null && coords[0] != -1 && c.distanceTo(coords[0], coords[1]) <= 5) return true;
    }
    int objects = c.getObjectsCount();
    int[] objectIds = c.getObjectsIds();
    int[] xs = c.getObjectsX();
    int[] zs = c.getObjectsZ();
    if (objectIds == null || xs == null || zs == null) return false;
    int n = Math.min(objects, Math.min(objectIds.length, Math.min(xs.length, zs.length)));
    for (int i = 0; i < n; i++) {
      if (objectIds[i] != BANK_CHEST) continue;
      if (c.distanceTo(c.offsetX(xs[i]), c.offsetZ(zs[i])) <= 5) return true;
    }
    return false;
  }

  private boolean sameAccount(Controller c) {
    String me = c.getPlayerName();
    return me != null && !me.isEmpty() && me.equalsIgnoreCase(setupAccount);
  }

  private boolean wrongPerson(Controller c) {
    String who = c.getTradeRecipientName();
    return who != null && !who.trim().isEmpty() && !who.trim().equalsIgnoreCase(recipient);
  }

  private int hold(Controller c) {
    status = holdReason;
    if (MassStart.isFollowerStart()) return 400;
    if (holdUntil == 0) holdUntil = System.currentTimeMillis() + 4500;
    if (System.currentTimeMillis() < holdUntil) return 400;
    holding = false;
    stopped = true;
    c.stop();
    return 0;
  }

  private void finish(Controller c, String reason) {
    status = reason;
    holdReason = reason;
    Main.log("[Ben's Trade] " + reason);
    c.setStatus("@gre@" + reason);
    c.log(reason, "gre");
    if (c.isInBank()) c.closeBank();
    // Mass start counts an account as failed if the script has already stopped. An account
    // standing in the bank can deposit and finish in a couple of seconds, so wait until that
    // check has seen it running, then stop.
    if (fromMassStart || MassStart.isFollowerStart()) {
      holding = true;
      return;
    }
    stopped = true;
    c.stop();
  }

  private void showGui() {
    final JFrame frame = new JFrame("Ben's Trade");
    frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    JPanel root = new JPanel(new BorderLayout(8, 8));
    root.setBorder(new EmptyBorder(10, 10, 10, 10));

    int tradeable = 0;
    for (int i = 0; i < count; i++) if (bankAmt[i] + invAmt[i] > 0) tradeable++;
    root.add(
        new JLabel(
            "<html><body style='width:420px'>Mule trade withdraws the ticked items and trades them to the player named below. "
                + "Untick it and Start deposits this account's inventory into the closest bank. "
                + "Just coins limits either one to coins. "
                + "Mass start and a script queue use this choice on every account. "
                + tradeable
                + " tradeable item"
                + (tradeable == 1 ? "" : "s")
                + " here. Worn items stay worn.</body></html>"),
        BorderLayout.NORTH);

    JPanel list = new JPanel();
    list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
    final JCheckBox[] boxes = new JCheckBox[count];
    if (count == 0) list.add(new JLabel("Nothing tradeable in the bank or inventory."));
    for (int i = 0; i < count; i++) {
      boolean canTrade = bankAmt[i] + invAmt[i] > 0;
      JCheckBox box = new JCheckBox(label(i));
      box.setAlignmentX(0f);
      box.setSelected(canTrade);
      box.setEnabled(canTrade);
      boxes[i] = box;
      list.add(box);
    }
    JScrollPane scroll = new JScrollPane(list);
    scroll.setPreferredSize(new Dimension(460, 320));
    root.add(scroll, BorderLayout.CENTER);

    JPanel south = new JPanel(new BorderLayout(6, 6));
    JPanel picks = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    final JCheckBox mule = new JCheckBox("Mule trade");
    mule.setSelected(true);
    final JCheckBox justCoins = new JCheckBox(coinsOnlyLabel());
    justCoins.setEnabled(coinIndex() >= 0);
    justCoins.addActionListener(
        e -> {
          if (mule.isSelected()) applyCoinsOnly(boxes, justCoins.isSelected());
        });
    JButton all = new JButton("All");
    JButton none = new JButton("None");
    all.addActionListener(
        e -> {
          if (!mule.isSelected()) return;
          justCoins.setSelected(false);
          applyCoinsOnly(boxes, false);
        });
    none.addActionListener(
        e -> {
          if (!mule.isSelected()) return;
          justCoins.setSelected(false);
          applyCoinsOnly(boxes, false);
          setAll(boxes, false);
        });
    picks.add(mule);
    picks.add(justCoins);
    picks.add(all);
    picks.add(none);
    south.add(picks, BorderLayout.NORTH);

    JPanel nameRow = new JPanel(new BorderLayout(8, 0));
    nameRow.add(new JLabel("Trade to"), BorderLayout.WEST);
    final JTextField nameField = new JTextField(prefill, 18);
    nameRow.add(nameField, BorderLayout.CENTER);
    south.add(nameRow, BorderLayout.CENTER);

    final JLabel error = new JLabel(" ");
    JPanel bottom = new JPanel(new BorderLayout(6, 6));
    bottom.add(error, BorderLayout.NORTH);
    JButton go = new JButton(count == 0 ? "Close" : "Start");
    mule.addActionListener(
        e -> {
          boolean on = mule.isSelected();
          nameField.setEnabled(on);
          go.setText(!on ? "Start" : count == 0 ? "Close" : "Start");
          if (on) applyCoinsOnly(boxes, justCoins.isSelected());
          else disableItemBoxes(boxes);
        });
    go.addActionListener(
        e -> {
          boolean tradeOn = mule.isSelected();
          boolean onlyCoins = justCoins.isSelected();
          if (!tradeOn) {
            if (onlyCoins && coinInv() < 1) {
              error.setText("No coins in the inventory to deposit.");
              return;
            }
            if (!onlyCoins && !hasUnequippedItems()) {
              error.setText("Nothing in the inventory to deposit.");
              return;
            }
          } else {
            if (count == 0) {
              stopped = true;
              frame.dispose();
              Controller live = Main.getController();
              if (live != null) live.stop();
              return;
            }
            String name = nameField.getText() == null ? "" : nameField.getText().trim();
            if (name.isEmpty()) {
              error.setText("Type the name of the player to trade.");
              return;
            }
            recipient = name;
            boolean any = false;
            for (int i = 0; i < boxes.length; i++) {
              picked[i] = boxes[i].isEnabled() && boxes[i].isSelected();
              if (picked[i]) any = true;
            }
            if (!any) {
              error.setText("Tick at least one item.");
              return;
            }
          }
          rememberChoice(tradeOn, onlyCoins, boxes);
          started = true;
          frame.dispose();
        });
    bottom.add(go, BorderLayout.CENTER);
    south.add(bottom, BorderLayout.SOUTH);
    root.add(south, BorderLayout.SOUTH);

    frame.addWindowListener(
        new java.awt.event.WindowAdapter() {
          @Override
          public void windowClosing(java.awt.event.WindowEvent e) {
            if (!started) {
              stopped = true;
              Controller live = Main.getController();
              if (live != null) live.stop();
            }
          }
        });
    frame.setContentPane(root);
    frame.pack();
    frame.setLocationRelativeTo(null);
    frame.setVisible(true);
    nameField.requestFocusInWindow();
  }

  /** Stores a plain copy of the setup so mass start can hand it to the other accounts. */
  private void rememberChoice(boolean tradeOn, boolean onlyCoins, JCheckBox[] boxes) {
    muleTrade = tradeOn;
    coinsOnly = onlyCoins;
    Controller live = Main.getController();
    if (live != null) {
      String me = live.getPlayerName();
      if (me != null && !me.isEmpty()) setupAccount = me;
    }
    if (!tradeOn) {
      allItems = !onlyCoins;
      chosenIds = new int[0];
      phase = Phase.DEPOSIT;
    } else if (onlyCoins) {
      allItems = false;
      chosenIds = new int[0];
      for (int i = 0; i < count; i++) picked[i] = ids[i] == COINS && bankAmt[i] + invAmt[i] > 0;
      phase = Phase.TRADE;
    } else {
      List<Integer> chosen = new ArrayList<Integer>();
      boolean every = true;
      for (int i = 0; i < count; i++) {
        boolean can = bankAmt[i] + invAmt[i] > 0;
        boolean on = boxes[i] != null && boxes[i].isEnabled() && boxes[i].isSelected();
        picked[i] = on;
        if (can && !on) every = false;
        if (on) chosen.add(ids[i]);
      }
      allItems = every;
      chosenIds = allItems ? new int[0] : toArray(chosen);
      phase = Phase.TRADE;
    }
    configured = true;
    bankFresh = false;
    status =
        tradeOn
            ? "Withdrawing and trading to " + recipient
            : onlyCoins ? "Depositing coins" : "Depositing the inventory";
  }

  private String coinsOnlyLabel() {
    int index = coinIndex();
    if (index < 0) return "Just coins (none)";
    return "Just coins (" + format(bankAmt[index] + invAmt[index]) + ")";
  }

  private int coinIndex() {
    for (int i = 0; i < count; i++) {
      if (ids[i] == COINS && bankAmt[i] + invAmt[i] > 0) return i;
    }
    return -1;
  }

  private int coinInv() {
    int index = coinIndex();
    return index < 0 ? 0 : invAmt[index];
  }

  private String label(int index) {
    int total = bankAmt[index] + invAmt[index];
    if (total < 1) return names[index] + "  (worn " + format(wornAmt[index]) + ", left on)";
    String where;
    if (bankAmt[index] > 0 && invAmt[index] > 0) {
      where = "bank " + format(bankAmt[index]) + ", inventory " + format(invAmt[index]);
    } else if (bankAmt[index] > 0) {
      where = "bank";
    } else {
      where = "inventory";
    }
    String text = names[index] + "   " + format(total) + "   (" + where + ")";
    if (wornAmt[index] > 0) text += ", " + format(wornAmt[index]) + " worn left on";
    return text;
  }

  /** Tick only coins, or put every tradeable item back. */
  private void applyCoinsOnly(JCheckBox[] boxes, boolean onlyCoins) {
    for (int i = 0; i < boxes.length; i++) {
      JCheckBox box = boxes[i];
      if (box == null) continue;
      boolean canTrade = bankAmt[i] + invAmt[i] > 0;
      if (!canTrade) {
        box.setEnabled(false);
        box.setSelected(false);
        continue;
      }
      boolean coins = ids[i] == COINS;
      box.setEnabled(!onlyCoins || coins);
      box.setSelected(onlyCoins ? coins : true);
    }
  }

  private static void setAll(JCheckBox[] boxes, boolean selected) {
    for (JCheckBox box : boxes) {
      if (box != null && box.isEnabled()) box.setSelected(selected);
    }
  }

  private static void disableItemBoxes(JCheckBox[] boxes) {
    for (JCheckBox box : boxes) {
      if (box != null) box.setEnabled(false);
    }
  }

  private boolean hasUnequippedItems() {
    Controller c = Main.getController();
    if (c == null) return false;
    for (Item item : c.getInventoryItems()) {
      if (item != null && !item.getEquipped() && item.getCatalogID() >= 0) return true;
    }
    return false;
  }

  private static boolean contains(int[] values, int id) {
    for (int value : values) if (value == id) return true;
    return false;
  }

  private static int[] toArray(List<Integer> values) {
    int[] out = new int[values.size()];
    for (int i = 0; i < values.size(); i++) out[i] = values.get(i);
    return out;
  }

  private static String joinIds(int[] values) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < values.length; i++) {
      if (i > 0) out.append(',');
      out.append(values[i]);
    }
    return out.toString();
  }

  private static String format(long amount) {
    return String.format(Locale.US, "%,d", amount);
  }

  private static final class Hold {
    int id;
    String name;
    int bank;
    int inv;
    int worn;
  }
}
