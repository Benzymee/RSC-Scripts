package scripting.idlescript.SeattaScript.AIOQuester;

import bot.Main;
import controller.Controller;
import java.util.ArrayList;
import java.util.List;
import models.entities.ItemId;
import models.entities.NpcId;
import orsc.ORSCharacter;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuestDef;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuestRequirement;

/**
 * Items a quest lists as obtainable before it starts: withdraw them from a bank, or buy the
 * shortfall from the Auction House. Clerks stand at the banks. The auctioneers stand in Varrock
 * centre (132, 505).
 */
final class QuestSupplies {
  private static final Controller c = Main.getController();
  /** Cheapest listing above this price per item is left alone. */
  private static final int MAX_AUCTION_PRICE = 15_000;

  /** Ground-floor banks. Gnome Stronghold (715, 1452) and Zanaris are left out. */
  private static final int[][] BANKS = {
    {102, 512}, // Varrock east
    {150, 502}, // Varrock west
    {220, 635}, // Draynor
    {216, 451}, // Edgeville
    {283, 570}, // Falador east
    {328, 552}, // Falador west
    {440, 494}, // Catherby
    {501, 451}, // Seers
    {581, 574}, // Ardougne north
    {552, 613}, // Ardougne south
    {89, 694}, // Al Kharid
    {587, 754} // Yanille
  };

  /** Clerks at the banks, plus the auctioneers in Varrock centre. */
  private static final int[][] HOUSES = {
    {132, 505}, // Varrock centre auctioneer
    {126, 506},
    {127, 509},
    {133, 509},
    {217, 450}, // Edgeville clerk
    {219, 636}, // Draynor
    {283, 568}, // Falador
    {102, 512}, // Varrock east
    {150, 502}, // Varrock west
    {440, 494}, // Catherby
    {501, 451}, // Seers
    {552, 613}, // Ardougne south
    {581, 574}, // Ardougne north
    {89, 694}, // Al Kharid
    {587, 754} // Yanille
  };

  private QuestSupplies() {}

  /** Item rows from the requirements list (not notes, skills, or quests). */
  static List<QuestRequirement> beforeStart(QuestDef quest) {
    List<QuestRequirement> out = new ArrayList<>();
    if (quest == null) return out;
    addItems(out, quest.getNormalRequirements());
    addItems(out, quest.getIronmanRequirements());
    return out;
  }

  private static void addItems(List<QuestRequirement> out, QuestRequirement[] shown) {
    if (shown == null) return;
    for (QuestRequirement req : shown) {
      if (req == null || req.id < 0) continue;
      if (req.kind != QuestRequirement.Kind.ITEM && req.kind != QuestRequirement.Kind.EQUIPPED)
        continue;
      boolean seen = false;
      for (QuestRequirement have : out) if (have.id == req.id) seen = true;
      if (!seen) out.add(req);
    }
  }

  static int carried(QuestRequirement req) {
    int have = c.getUnnotedInventoryItemCount(req.id);
    if (req.amount <= 1 && c.isItemIdEquipped(req.id)) have++;
    return have;
  }

  /**
   * Fills the before-start items from the bank and, when asked, the Auction House. Does not talk
   * to the quest giver.
   *
   * @return items still missing after the attempt
   */
  static List<String> gather(QuestDef quest, boolean fromBank, boolean auction) {
    List<String> missing = new ArrayList<>();
    List<QuestRequirement> items = beforeStart(quest);
    if (items.isEmpty()) {
      c.log("AIOQuester: " + quest.getName() + " has no items to gather before it starts", "yel");
      return missing;
    }
    QuestHandler.CURRENT_QUEST_STEP = "Gathering items for " + quest.getName();
    if (fromBank) withdrawFromBank(quest, items);
    if (auction) buyAtAuction(items);
    for (QuestRequirement req : items) {
      if (carried(req) < req.amount) missing.add(req.label + " (" + carried(req) + "/" + req.amount + ")");
    }
    if (missing.isEmpty()) {
      c.log("AIOQuester: items for " + quest.getName() + " are in the inventory", "gre");
    } else {
      c.log(
          "AIOQuester: still missing for " + quest.getName() + ": " + String.join(", ", missing),
          "yel");
    }
    return missing;
  }

  private static void withdrawFromBank(QuestDef quest, List<QuestRequirement> items) {
    if (c.getPlayerMode() == 2) return; // ultimate ironman has no bank
    boolean need = false;
    for (QuestRequirement req : items) if (carried(req) < req.amount) need = true;
    if (!need) return;
    QuestHandler.CURRENT_QUEST_STEP = "Withdrawing quest items for " + quest.getName();
    if (!openNearestBank()) return;
    uncertOpenBank(wantedIds(items));
    java.util.Map<Integer, Integer> contents = c.getDebuggerBank();
    if (contents != null) QuestHandler.bankItems = contents;
    for (QuestRequirement req : items) {
      int shortfall = req.amount - carried(req);
      if (shortfall <= 0) continue;
      Integer banked = QuestHandler.bankItems == null ? null : QuestHandler.bankItems.get(req.id);
      if (banked == null || banked <= 0) continue;
      c.withdrawItem(req.id, Math.min(shortfall, banked));
      c.sleep(600);
    }
    c.closeBank();
    c.sleep(600);
  }

  private static void buyAtAuction(List<QuestRequirement> items) {
    boolean need = false;
    for (QuestRequirement req : items) if (carried(req) < req.amount) need = true;
    if (!need) return;
    if (c.isPlayerAnIronMode()) {
      c.log("AIOQuester: this account is an ironman, so the Auction House will not sell to it", "yel");
      return;
    }
    int total = 0;
    try {
      for (int i = 0; i < c.getSkillNamesLong().length; i++) total += c.getBaseStat(i);
    } catch (RuntimeException ignored) {
      total = 100;
    }
    if (total < 100) {
      c.log("AIOQuester: the Auction House wants 100 total level (have " + total + ")", "yel");
      return;
    }
    withdrawAllCoins();
    if (!QuestHandler.running()) return;
    QuestHandler.CURRENT_QUEST_STEP = "Walking to an Auction House clerk";
    int[] house = nearest(HOUSES);
    QuestHandler.walker.walkTo(house[0], house[1], 2, 0);
    if (!QuestHandler.running()) return;
    if (!openHouse()) {
      depositCarriedCoins();
      return;
    }
    try {
      for (QuestRequirement req : items) {
        int shortfall = req.amount - carried(req);
        if (shortfall <= 0 || !QuestHandler.running()) continue;
        QuestHandler.CURRENT_QUEST_STEP = "Buying " + req.label + " at the Auction House";
        models.entities.AuctionItem offer = c.auctionSearchCheapestOfItemId(req.id);
        if (offer == null) {
          c.log("AIOQuester: nothing listed for " + req.label, "yel");
          continue;
        }
        if (offer.getPricePerItem() > MAX_AUCTION_PRICE) {
          c.log(
              "AIOQuester: "
                  + req.label
                  + " is "
                  + offer.getPricePerItem()
                  + " coins each, over the "
                  + MAX_AUCTION_PRICE
                  + " limit",
              "yel");
          continue;
        }
        int coins = c.getInventoryItemCount(ItemId.COINS.getId());
        if (coins < offer.getPricePerItem()) {
          c.log(
              "AIOQuester: not enough coins for "
                  + req.label
                  + " ("
                  + offer.getPricePerItem()
                  + " each)",
              "yel");
          continue;
        }
        c.auctionBuy(offer, shortfall);
        c.sleep(800);
      }
    } finally {
      if (c.isInAuctionHouse()) c.closeAuctionHouse();
    }
    if (!QuestHandler.running()) return;
    if (!holdsCertOrNote(items) && c.getInventoryItemCount(ItemId.COINS.getId()) <= 0) return;
    if (!openNearestBank()) return;
    try {
      if (holdsCertOrNote(items)) uncertOpenBank(wantedIds(items));
      for (QuestRequirement req : items) {
        int shortfall = req.amount - carried(req);
        if (shortfall <= 0) continue;
        int banked = c.isInBank() ? c.getBankItemCount(req.id) : 0;
        if (banked <= 0) continue;
        int before = c.getUnnotedInventoryItemCount(req.id);
        c.withdrawItem_apos(req.id, Math.min(shortfall, banked));
        long until = System.currentTimeMillis() + 4000;
        while (QuestHandler.running()
            && c.getUnnotedInventoryItemCount(req.id) <= before
            && System.currentTimeMillis() < until) {
          c.sleep(200);
        }
      }
      depositCarriedCoins();
    } finally {
      if (c.isInBank()) c.closeBank();
      c.sleep(400);
    }
  }

  /** Takes coins out until {@code needed} are carried. Does nothing for an ultimate ironman. */
  static void withdrawCoins(int needed) {
    if (c.getPlayerMode() == 2 || !QuestHandler.running()) return;
    int coinsId = ItemId.COINS.getId();
    int shortfall = needed - c.getInventoryItemCount(coinsId);
    if (shortfall <= 0) return;
    QuestHandler.CURRENT_QUEST_STEP = "Checking the bank for coins";
    if (!openNearestBank()) return;
    try {
      int banked = c.getBankItemCount(coinsId);
      if (banked <= 0) {
        c.log("AIOQuester: no coins in the bank", "yel");
        return;
      }
      int take = Math.min(banked, shortfall);
      int before = c.getInventoryItemCount(coinsId);
      c.withdrawItem_apos(coinsId, take);
      long until = System.currentTimeMillis() + 4000;
      while (QuestHandler.running()
          && c.getInventoryItemCount(coinsId) <= before
          && System.currentTimeMillis() < until) {
        c.sleep(200);
      }
      c.log(
          "AIOQuester: withdrew coins, carrying " + c.getInventoryItemCount(coinsId),
          "cya");
    } finally {
      if (c.isInBank()) c.closeBank();
      c.sleep(400);
    }
  }

  /** Puts every carried coin back. The bank stays open when this is called with it already open. */
  private static void depositCarriedCoins() {
    if (c.getPlayerMode() == 2 || !QuestHandler.running()) return;
    int coinsId = ItemId.COINS.getId();
    int have = c.getInventoryItemCount(coinsId);
    if (have <= 0) return;
    QuestHandler.CURRENT_QUEST_STEP = "Depositing coins";
    boolean openedHere = !c.isInBank();
    if (openedHere && !openNearestBank()) return;
    c.depositItem(coinsId, c.getInventoryItemCount(coinsId));
    long until = System.currentTimeMillis() + 4000;
    while (QuestHandler.running()
        && c.getInventoryItemCount(coinsId) > 0
        && System.currentTimeMillis() < until) {
      c.sleep(200);
    }
    c.log(
        "AIOQuester: coins deposited, carrying " + c.getInventoryItemCount(coinsId),
        "cya");
    if (openedHere && c.isInBank()) {
      c.closeBank();
      c.sleep(400);
    }
  }

  /** Banks every food item still carried. Used after Demon Slayer, before the next quest or stop. */
  static void depositCarriedFood() {
    if (c.getPlayerMode() == 2 || !QuestHandler.running()) return;
    int[] foods = c.getFoodIds();
    boolean any = false;
    if (foods != null) {
      for (int id : foods) if (c.getInventoryItemCount(id) > 0) any = true;
    }
    if (!any) return;
    long untilCombat = System.currentTimeMillis() + 15_000;
    while (c.isInCombat() && QuestHandler.running() && System.currentTimeMillis() < untilCombat)
      c.sleep(600);
    if (!QuestHandler.running()) return;
    QuestHandler.CURRENT_QUEST_STEP = "Banking leftover food";
    if (!openNearestBank()) return;
    try {
      for (int id : foods) {
        int n = c.getInventoryItemCount(id);
        if (n <= 0 || !QuestHandler.running()) continue;
        c.depositItem(id, n);
        long until = System.currentTimeMillis() + 4000;
        while (QuestHandler.running()
            && c.getInventoryItemCount(id) > 0
            && System.currentTimeMillis() < until) {
          c.sleep(200);
        }
      }
      c.log("AIOQuester: leftover food banked", "cya");
    } finally {
      if (c.isInBank()) c.closeBank();
      c.sleep(400);
    }
  }

  /**
   * One certificate is five of the real item. The bank's Uncert deposit (packet 199, option 0) is
   * what turns the certificate into those items. Noted copies of the same id are deposited too;
   * the bank stores them as the plain item.
   */
  private static final int[][] CERTS = {
    {517, 151}, {518, 155}, {519, 153}, {520, 383}, {521, 152},
    {528, 170}, {529, 171}, {530, 173}, {531, 384}, {532, 172},
    {533, 373}, {534, 372}, {535, 370}, {536, 369},
    {628, 555}, {629, 554}, {630, 546}, {631, 545},
    {711, 635}, {712, 634}, {713, 633},
    {1270, 814}, {1271, 220}, {1272, 483}, {1273, 486}, {1274, 495}, {1275, 492}
  };

  private static java.util.Set<Integer> wantedIds(List<QuestRequirement> items) {
    java.util.Set<Integer> ids = new java.util.LinkedHashSet<>();
    for (QuestRequirement req : items) ids.add(req.id);
    return ids;
  }

  private static boolean holdsCertOrNote(List<QuestRequirement> items) {
    for (QuestRequirement req : items) {
      if (c.getNotedInventoryItemCount(req.id) > 0) return true;
      for (int[] row : CERTS) {
        if (row[1] == req.id && c.getInventoryItemCount(row[0]) > 0) return true;
      }
    }
    return false;
  }

  /** Bank is already open. Certificates and notes of the wanted ids become plain bank items. */
  private static void uncertOpenBank(java.util.Set<Integer> wanted) {
    if (wanted.isEmpty() || !c.isInBank()) return;
    for (int[] row : CERTS) {
      if (!wanted.contains(row[1])) continue;
      int banked = c.getBankItemCount(row[0]);
      if (banked <= 0) continue;
      int before = c.getInventoryItemCount(row[0]);
      c.withdrawItem_apos(row[0], banked);
      long until = System.currentTimeMillis() + 4000;
      while (QuestHandler.running()
          && c.getInventoryItemCount(row[0]) <= before
          && System.currentTimeMillis() < until) {
        c.sleep(200);
      }
    }
    boolean holdingCert = false;
    for (int[] row : CERTS) {
      if (wanted.contains(row[1]) && c.getInventoryItemCount(row[0]) > 0) holdingCert = true;
    }
    if (holdingCert) {
      setCertMode(true);
      try {
        for (int[] row : CERTS) {
          if (!wanted.contains(row[1]) || !QuestHandler.running()) continue;
          int n = c.getInventoryItemCount(row[0]);
          if (n <= 0) continue;
          c.log("AIOQuester: uncerting " + n + " " + c.getItemName(row[0]), "cya");
          int before = n;
          c.depositItem(row[0], n);
          long until = System.currentTimeMillis() + 4000;
          while (QuestHandler.running()
              && c.getInventoryItemCount(row[0]) >= before
              && System.currentTimeMillis() < until) {
            c.sleep(200);
          }
        }
      } finally {
        setCertMode(false);
      }
    }
    for (int id : wanted) {
      int guard = 0;
      while (c.getNotedInventoryItemCount(id) > 0 && QuestHandler.running() && guard++ < 6) {
        int noted = c.getNotedInventoryItemCount(id);
        c.log("AIOQuester: depositing noted " + c.getItemName(id) + " so the bank can uncert them", "cya");
        int before = noted;
        c.depositItem(id, noted);
        long until = System.currentTimeMillis() + 4000;
        while (QuestHandler.running()
            && c.getNotedInventoryItemCount(id) >= before
            && System.currentTimeMillis() < until) {
          c.sleep(200);
        }
        if (c.getNotedInventoryItemCount(id) >= before) break;
      }
    }
  }

  /** Same packet the bank interface sends for Uncert+Deposit. */
  private static void setCertMode(boolean on) {
    orsc.mudclient mud = c.getMud();
    while (mud.packetHandler.getClientStream().hasFinishedPackets()) c.sleep(1);
    mud.packetHandler.getClientStream().newPacket(199);
    mud.packetHandler.getClientStream().bufferBits.putByte(0);
    mud.packetHandler.getClientStream().bufferBits.putByte(on ? 1 : 0);
    mud.packetHandler.getClientStream().finishPacket();
    c.sleep(300);
  }

  /**
   * Takes every coin out of the bank before the Auction House is opened or searched. They are
   * deposited again when the trip finishes. withdrawItem skips the packet when the inventory
   * already holds that many, so the note-free withdraw is used and the bank count is checked again.
   */
  private static void withdrawAllCoins() {
    if (c.getPlayerMode() == 2) return;
    int coinsId = ItemId.COINS.getId();
    QuestHandler.CURRENT_QUEST_STEP = "Withdrawing all coins for the Auction House";
    if (!openNearestBank()) return;
    int banked = c.getBankItemCount(coinsId);
    if (banked < 0) banked = 0;
    if (banked > 0 && c.getInventoryItemCount(coinsId) == 0 && c.getInventoryItemCount() >= 30) {
      c.closeBank();
      c.sleep(400);
      QuestHandler.freeInventorySlots(1);
      if (!openNearestBank()) return;
      banked = c.getBankItemCount(coinsId);
    }
    int guard = 0;
    while (banked > 0 && QuestHandler.running() && guard++ < 4) {
      int before = c.getInventoryItemCount(coinsId);
      c.withdrawItem_apos(coinsId, banked);
      long until = System.currentTimeMillis() + 4000;
      while (QuestHandler.running()
          && c.getInventoryItemCount(coinsId) <= before
          && System.currentTimeMillis() < until) {
        c.sleep(200);
      }
      int taken = c.getInventoryItemCount(coinsId) - before;
      int still = c.isInBank() ? c.getBankItemCount(coinsId) : 0;
      if (still < 0) still = 0;
      if (taken >= banked) still = 0;
      else if (taken > 0 && still >= banked) still = banked - taken;
      banked = still;
    }
    if (c.isInBank()) c.closeBank();
    c.sleep(400);
    c.log(
        "AIOQuester: coins withdrawn for the Auction House, carrying "
            + c.getInventoryItemCount(coinsId),
        "cya");
  }

  static boolean openNearestBank() {
    int[] bank = nearest(BANKS);
    if (!QuestHandler.walker.walkTo(bank[0], bank[1], 3, 0)) return false;
    if (!QuestHandler.running()) return false;
    int away =
        Math.max(Math.abs(c.currentX() - bank[0]), Math.abs(c.currentY() - bank[1]));
    if (away > 8) {
      c.log(
          "AIOQuester: couldn't reach a bank from " + c.currentX() + "," + c.currentY(),
          "yel");
      return false;
    }
    if (!c.isInBank()) {
      c.openBank();
      long until = System.currentTimeMillis() + 8000;
      while (QuestHandler.running() && !c.isInBank() && System.currentTimeMillis() < until)
        c.sleep(200);
    }
    if (!c.isInBank()) {
      c.log("AIOQuester: couldn't open a bank", "yel");
      return false;
    }
    return true;
  }

  /**
   * Opens the house on a clerk or a Varrock-centre auctioneer. The client's openAuctionHouse only
   * talks to clerks and waits forever, so this stops after a few tries.
   */
  private static boolean openHouse() {
    int[] ids = new int[] {NpcId.AUCTION_CLERK.getId(), NpcId.AUCTIONEER.getId()};
    ORSCharacter npc = c.getNearestNpcByIds(ids, false);
    if (npc == null) {
      c.log("AIOQuester: no Auction House clerk is nearby", "yel");
      return false;
    }
    for (int i = 0; i < 6 && QuestHandler.running(); i++) {
      if (c.isInAuctionHouse()) return true;
      c.npcCommand1(npc.serverIndex);
      c.sleep(700);
    }
    if (c.isInAuctionHouse()) return true;
    c.log("AIOQuester: the Auction House did not open", "yel");
    return false;
  }

  /**
   * Opens a ground-floor bank and withdraws as much of the best healing food as will fit. Silverlight
   * (or whatever keepId is) stays in the inventory. Wine is skipped because it lowers Attack.
   */
  static void withdrawBestFood(int keepId) {
    if (c.getPlayerMode() == 2) return;
    if (!openNearestBank()) return;
    try {
      java.util.Set<Integer> foods = new java.util.LinkedHashSet<>();
      for (int id : c.getFoodIds()) foods.add(id);
      uncertOpenBank(foods);
      java.util.Map<Integer, Integer> contents = c.getDebuggerBank();
      if (contents != null) QuestHandler.bankItems = contents;
      int best = bestFoodId(contents);
      if (best < 0) {
        c.log("AIOQuester: no food in the bank or inventory for the fight", "yel");
        return;
      }
      int[] ids = c.getInventoryItemIds();
      if (ids != null) {
        java.util.Set<Integer> seen = new java.util.LinkedHashSet<>();
        for (int id : ids) if (id >= 0) seen.add(id);
        for (int id : seen) {
          if (id == keepId || id == best || QuestHandler.isKeptQuestItem(id)) continue;
          int n = c.getInventoryItemCount(id);
          if (n <= 0) continue;
          c.depositItem(id, n);
          c.sleep(400);
        }
      }
      int banked = c.isInBank() ? c.getBankItemCount(best) : 0;
      int free = 30 - c.getInventoryItemCount();
      int take = banked > 0 ? Math.min(banked, Math.max(free, 0)) : 0;
      if (take > 0) {
        int before = c.getInventoryItemCount(best);
        c.withdrawItem_apos(best, take);
        long until = System.currentTimeMillis() + 4000;
        while (QuestHandler.running()
            && c.getInventoryItemCount(best) <= before
            && System.currentTimeMillis() < until) {
          c.sleep(200);
        }
      }
      c.log(
          "AIOQuester: best food for the fight is "
              + c.getItemName(best)
              + " ("
              + c.getInventoryItemCount(best)
              + " carried)",
          "cya");
    } finally {
      if (c.isInBank()) c.closeBank();
      c.sleep(400);
    }
  }

  private static int bestFoodId(java.util.Map<Integer, Integer> bank) {
    int best = -1;
    int bestHeal = 0;
    for (int id : c.getFoodIds()) {
      int heal = foodHeal(id);
      if (heal <= 0) continue;
      int have = c.getUnnotedInventoryItemCount(id);
      if (bank != null) {
        Integer banked = bank.get(id);
        if (banked != null) have += banked;
      }
      if (have <= 0) continue;
      if (heal > bestHeal) {
        bestHeal = heal;
        best = id;
      }
    }
    return best;
  }

  /** Heal per bite. 0 means not food for a fight (wine lowers Attack). Unknown foods count as 1. */
  private static int foodHeal(int id) {
    switch (id) {
      case 546:
      case 1191:
      case 1193:
        return 20;
      case 709:
        return 19;
      case 370:
        return 14;
      case 555:
        return 13;
      case 373:
        return 12;
      case 346:
        return 11;
      case 367:
        return 10;
      case 327:
        return 9;
      case 357:
        return 9;
      case 326:
      case 364:
        return 8;
      case 359:
      case 551:
        return 7;
      case 325:
      case 553:
      case 718:
      case 257:
      case 259:
        return 6;
      case 362:
      case 332:
        return 5;
      case 355:
      case 330:
      case 258:
        return 4;
      case 350:
        return 3;
      case 142:
        return 0;
      default:
        for (int food : c.getFoodIds()) if (food == id) return 1;
        return 0;
    }
  }

  private static int[] nearest(int[][] tiles) {
    int px = c.currentX();
    int py = c.currentY() % QuestWalker.FLOOR_HEIGHT;
    int[] best = tiles[0];
    int bestD = Integer.MAX_VALUE;
    for (int[] tile : tiles) {
      int d = Math.max(Math.abs(px - tile[0]), Math.abs(py - tile[1]));
      if (d < bestD) {
        bestD = d;
        best = tile;
      }
    }
    return best;
  }
}
