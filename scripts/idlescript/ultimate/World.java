package scripting.idlescript.ultimate;

import com.openrsc.client.entityhandling.EntityHandler;
import controller.Controller;
import java.util.HashSet;
import java.util.Set;
import models.entities.Location;
import models.entities.SkillId;
import orsc.ORSCharacter;

/**
 * Shared helpers. Walking, shops and banking follow the same step-at-a-time style as AIOAIO in
 * this client: each call does one thing and returns how long the script should sleep.
 */
public final class World {
  public final Controller c;
  public final TrainerConfig config;
  public final SessionStats stats;
  public String status = "Starting";
  public String prepDetail = "";
  public String activityName = "Starting";
  public String styleName = "Controlled";
  public String activityId = "";
  public boolean preparing;
  public long millisLeft;
  public SkillId highlight = SkillId.HITS;
  public boolean deathPending;
  public boolean switchRequested;
  public final Set<String> blocked = new HashSet<String>();
  public final Set<Integer> bankMiss = new HashSet<Integer>();
  public final Set<Integer> shopMiss = new HashSet<Integer>();
  private int stuck;
  private int lastX = -1;
  private int lastY = -1;
  private int styleCursor;
  /** Non-blocking shop/bank open retries — Controller.openShop/openBank can hang forever. */
  private int shopAttempts;
  private int bankAttempts;
  /**
   * When a shop visit shows we cannot afford an item, stay on makeCoins (pickpocket/sell) until we
   * hold at least this many coins. Without this, prepare() walks back to the shop every tick.
   */
  private int coinGoalItem = -1;
  private int coinGoalAmount;

  public World(Controller c, TrainerConfig config, SessionStats stats) {
    this.c = c;
    this.config = config;
    this.stats = stats;
  }

  public boolean coleslaw() {
    return !c.isAuthentic();
  }

  public int level(SkillId skill) {
    return c.getBaseStat(skill.getId());
  }

  public int count(int id) {
    return c.getInventoryItemCount(id);
  }

  public boolean owns(int id) {
    return count(id) > 0 || c.isItemIdEquipped(id);
  }

  public boolean ownsAny(int[] ids) {
    for (int id : ids) if (owns(id)) return true;
    return false;
  }

  public int invCoins() {
    return count(Ids.COINS);
  }

  public boolean full() {
    return c.getInventoryItemCount() >= 30;
  }

  public int hitsPercent() {
    int max = Math.max(1, c.getBaseStat(SkillId.HITS.getId()));
    return c.getCurrentStat(SkillId.HITS.getId()) * 100 / max;
  }

  public String name(int id) {
    String value = c.getItemName(id);
    return value == null || value.length() == 0 ? ("item " + id) : value;
  }

  public int basePrice(int id) {
    try {
      return Math.max(0, EntityHandler.getItemDef(id).getBasePrice());
    } catch (Exception exception) {
      return 0;
    }
  }

  public void say(String text) {
    status = text;
    c.setStatus(text);
  }

  public void requestSwitch(String reason) {
    switchRequested = true;
    prepDetail = reason;
    say(reason);
  }

  public boolean isBlocked(String id) {
    return blocked.contains(id);
  }

  public void onNewActivity(Activity activity) {
    bankMiss.clear();
    shopMiss.clear();
    stuck = 0;
    shopAttempts = 0;
    bankAttempts = 0;
    clearCoinGoal();
    switchRequested = false;
    activityId = activity.id();
    if ("Rotate".equals(config.meleeStyle)) styleCursor++;
    say("Starting " + activity.displayName());
  }

  private void clearCoinGoal() {
    coinGoalItem = -1;
    coinGoalAmount = 0;
  }

  /** True while we are earning toward a remembered shop price instead of re-walking to the shop. */
  private boolean earningFor(int itemId) {
    return coinGoalItem == itemId && coinGoalAmount > 0 && invCoins() < coinGoalAmount;
  }

  private void setCoinGoal(int itemId, int unitPrice, int units) {
    coinGoalItem = itemId;
    int need = Math.max(1, units);
    int price = Math.max(1, unitPrice);
    coinGoalAmount = config.minCoins + price * need;
  }

  public void noteMessage(String message) {
    if (message == null) return;
    String lower = message.toLowerCase();
    if (lower.contains("you are dead") || lower.contains("oh dear")) {
      deathPending = true;
      stats.deaths++;
    }
  }

  /** Eat, loot, finish a Coleslaw batch, or walk off a death respawn. 0 means the activity can run. */
  public int safety() {
    if (!c.isLoggedIn()) return 1000;
    if (c.getNeedToMove()) {
      c.moveCharacter();
      return 400;
    }
    if (c.isAuthentic() && c.getShouldSleep()) {
      say("Sleeping");
      c.sleepHandler(true);
      return 600;
    }
    if (deathPending) {
      deathPending = false;
      prepDetail = "Recovering in Lumbridge";
      say(prepDetail);
      return walkTile(122, 648, 6, prepDetail);
    }
    if (c.isBatching()) {
      if (hitsPercent() <= config.eatPercent && hasFood()) return eat();
      say("Finishing batch");
      return 300;
    }
    if (hitsPercent() > 0 && hitsPercent() <= config.eatPercent) {
      if (hasFood()) return eat();
      if (c.isInCombat()) {
        say("Out of food, leaving combat");
        c.walkToAsync(c.currentX(), Math.max(0, c.currentY() - 3), 0);
        return 700;
      }
      prepDetail = "Getting food";
      return getFood();
    }
    int looted = lootNearby();
    if (looted > 0) return looted;
    if (config.lootBones && count(Ids.BONES) > 0 && !c.isInCombat()) {
      say("Burying bones");
      c.itemCommand(Ids.BONES);
      return 500;
    }
    return 0;
  }

  public boolean hasFood() {
    if (count(config.foodId) > 0 || count(Ids.CABBAGE) > 0) return true;
    int[] foods = c.getFoodIds();
    if (foods == null) return false;
    for (int id : foods) if (id > 0 && count(id) > 0) return true;
    return false;
  }

  public int eat() {
    int id = -1;
    if (count(config.foodId) > 0) id = config.foodId;
    else if (count(Ids.CABBAGE) > 0) id = Ids.CABBAGE;
    else {
      int[] foods = c.getFoodIds();
      if (foods != null) {
        for (int food : foods) {
          if (food > 0 && count(food) > 0) {
            id = food;
            break;
          }
        }
      }
    }
    if (id < 0) return 0;
    say("Eating " + name(id));
    c.itemCommand(id);
    return 600;
  }

  public int getFood() {
    prepDetail = "Getting food";
    if (hasFood()) return 0;
    if (config.foodId != Ids.CABBAGE) {
      int bought =
          obtain(
              config.foodId,
              6,
              Location.PORT_SARIM_GENERAL_STORE.getX(),
              Location.PORT_SARIM_GENERAL_STORE.getY(),
              new int[] {Ids.WYDIN},
              "Wydin's food shop");
      if (bought > 0) return bought;
      if (count(config.foodId) > 0) return 0;
    }
    return pickCabbage();
  }

  public int pickCabbage() {
    if (count(Ids.CABBAGE) >= 10) return 0;
    if (c.currentX() >= 137 && c.currentX() <= 154 && c.currentY() >= 597 && c.currentY() <= 614) {
      say("Picking cabbage");
      c.pickupItem(Ids.CABBAGE);
      return 500;
    }
    return walkTile(142, 608, 3, "Walking to the cabbage field");
  }

  public int walk(Location location) {
    if (location.isAtLocation()) {
      stuck = 0;
      return 0;
    }
    // Never call Location.walkTowards() here — it blocks until arrival / retries for minutes.
    return walkTile(
        location.getX(), location.getY(), 2, "Walking to " + location.getDescription());
  }

  public int walkTile(int x, int y, int radius, String label) {
    if (c.distanceTo(x, y) <= radius) {
      stuck = 0;
      return 0;
    }
    if (noteStuck()) {
      requestSwitch("Could not reach " + label);
      return 400;
    }
    say(label);
    // One webwalk hop per tick. Still may wait briefly inside walkDamnit, but returns.
    c.walkTowards(x, y);
    return 700;
  }

  private boolean noteStuck() {
    int x = c.currentX();
    int y = c.currentY();
    if (x == lastX && y == lastY) stuck++;
    else stuck = 0;
    lastX = x;
    lastY = y;
    if (stuck > 35) {
      stuck = 0;
      return true;
    }
    return false;
  }

  public int openTheBank() {
    if (c.isInBank()) {
      bankAttempts = 0;
      return 0;
    }
    // Prefer a bank chest when visible — one click, no wait.
    try {
      int[][] chests = c.getObjectsById(942);
      if (chests != null && chests.length > 0) {
        say("Opening bank chest");
        c.atObject(942);
        bankAttempts++;
        if (bankAttempts > 15) {
          bankAttempts = 0;
          requestSwitch("Could not open bank");
        }
        return 700;
      }
    } catch (Exception ignored) {
      // Fall through to banker NPCs.
    }
    ORSCharacter banker = c.getNearestNpcByIds(c.bankerIds, false);
    if (banker == null || dist(banker) > 8) {
      say("Walking to a bank");
      Location bank = Location.getNearestBank();
      if (bank != null) {
        return walkTile(bank.getX(), bank.getY(), 3, "Walking to a bank");
      }
      c.walkTowardsBank();
      return 700;
    }
    if (dist(banker) > 2) {
      int[] coords = c.getNpcCoordsByServerIndex(banker.serverIndex);
      if (coords != null && coords.length >= 2) {
        c.walkToAsync(coords[0], coords[1], 0);
        return 600;
      }
    }
    // Do NOT call Controller.openBank() — it loops forever until the UI opens, which stalls
    // tabbed-out / mass-started clients with "no state change" for 90s+.
    say("Opening bank");
    c.npcCommand1(banker.serverIndex);
    bankAttempts++;
    if (bankAttempts > 15) {
      bankAttempts = 0;
      requestSwitch("Could not open bank");
    }
    return c.isInBank() ? 200 : 700;
  }

  /**
   * Click Trade on a shop NPC once and return. Never waits for the shop window (that is what
   * Controller.openShop does, and it hangs mass-started background clients).
   *
   * @return 0 if already in shop, positive delay if clicked/walking, -1 if NPC missing (walk), -2 if
   *     gave up
   */
  public int openShopOnce(int[] npcs, String shop) {
    if (c.isInShop()) {
      shopAttempts = 0;
      return 0;
    }
    ORSCharacter npc = c.getNearestNpcByIds(npcs, false);
    if (npc == null || dist(npc) > 8) return -1;
    if (dist(npc) > 2) {
      int[] coords = c.getNpcCoordsByServerIndex(npc.serverIndex);
      if (coords != null && coords.length >= 2) {
        c.walkToAsync(coords[0], coords[1], 0);
        return 600;
      }
    }
    say("Opening " + shop);
    c.npcCommand1(npc.serverIndex);
    shopAttempts++;
    if (shopAttempts > 12) {
      shopAttempts = 0;
      return -2;
    }
    return 700;
  }

  private int dist(ORSCharacter npc) {
    return c.distanceTo(c.convertX(npc.currentX), c.convertZ(npc.currentZ));
  }

  public int withdraw(int id, int amount) {
    if (amount <= 0 || count(id) >= amount) return 0;
    if (bankMiss.contains(id)) return 0;
    int step = openTheBank();
    if (!c.isInBank()) return step > 0 ? step : 600;
    int banked = c.getBankItemCount(id);
    if (banked <= 0) {
      bankMiss.add(id);
      return 0;
    }
    int take = Math.min(banked, amount - count(id));
    say("Withdrawing " + name(id));
    c.withdrawItem(id, take);
    return 400;
  }

  public int bankExcept(int... keep) {
    int step = openTheBank();
    if (!c.isInBank()) return step > 0 ? step : 600;
    Set<Integer> held = new HashSet<Integer>();
    if (keep != null) for (int id : keep) held.add(id);
    held.add(Ids.COINS);
    held.add(Ids.CABBAGE);
    held.add(config.foodId);
    for (int tool : Ids.TOOLS) held.add(tool);
    for (int id : c.getInventoryItemIds()) {
      if (id <= 0 || held.contains(id) || count(id) <= 0) continue;
      say("Banking " + name(id));
      c.depositItem(id, count(id));
    }
    c.closeBank();
    return 500;
  }

  /** Withdraw from the bank, then buy the rest. Returns 0 when the item is owned or unavailable. */
  public int obtain(int id, int amount, int x, int y, int[] npcs, String shop) {
    if (count(id) >= amount || c.isItemIdEquipped(id)) return 0;
    int fromBank = withdraw(id, amount);
    if (fromBank > 0) return fromBank;
    if (count(id) >= amount) {
      if (c.isInBank()) c.closeBank();
      return 200;
    }
    if (c.isInBank()) c.closeBank();
    return buy(id, amount, x, y, npcs, shop);
  }

  public int buy(int id, int amount, int x, int y, int[] npcs, String shop) {
    if (count(id) >= amount) {
      if (coinGoalItem == id) clearCoinGoal();
      return 0;
    }
    if (shopMiss.contains(id)) return 0;
    // Stay pickpocketing/selling until we can afford the last quoted price — do not walk back
    // to the shop every tick (that looked like shop ↔ pickpocket flicker).
    if (earningFor(id)) {
      prepDetail =
          "Earning coins for "
              + name(id)
              + " ("
              + invCoins()
              + "/"
              + coinGoalAmount
              + " gp)";
      say(prepDetail);
      return makeCoins();
    }
    if (coinGoalItem == id && invCoins() >= coinGoalAmount) clearCoinGoal();
    int step = walkTile(x, y, 4, "Walking to " + shop);
    if (step > 0) return step;
    if (!c.isInShop()) {
      int open = openShopOnce(npcs, shop);
      if (open == -1) return walkTile(x, y, 4, "Walking to " + shop);
      if (open == -2) {
        shopMiss.add(id);
        prepDetail = "Could not open " + shop;
        return 400;
      }
      return open;
    }
    int stock = c.getShopItemCount(id);
    int price = c.getShopItemPrice(id);
    if (stock < 1 || price < 0) {
      c.closeShop();
      shopMiss.add(id);
      return 0;
    }
    int units = Math.max(1, amount - count(id));
    int affordable = Math.max(0, invCoins() - config.minCoins) / Math.max(1, price);
    int buying = Math.min(units, Math.min(stock, affordable));
    if (buying < 1) {
      c.closeShop();
      setCoinGoal(id, price, units);
      prepDetail =
          "Earning coins for "
              + name(id)
              + " ("
              + invCoins()
              + "/"
              + coinGoalAmount
              + " gp)";
      say(prepDetail);
      return makeCoins();
    }
    say("Buying " + name(id));
    int before = invCoins();
    c.shopBuy(id, buying);
    int after = invCoins();
    if (before > after) stats.coinsSpent += before - after;
    c.closeShop();
    shopAttempts = 0;
    if (coinGoalItem == id) clearCoinGoal();
    return 400;
  }

  /**
   * Wield the best owned tool, or buy one. ids are best-first and levels are the skill requirement
   * for the same index.
   */
  public int buyBest(int[] ids, int[] levels, SkillId skill, int x, int y, int[] npcs, String shop) {
    int owned = bestOwned(ids, levels, skill);
    if (owned >= 0) {
      if (count(owned) > 0 && !c.isItemIdEquipped(owned)) {
        say("Wielding " + name(owned));
        c.equipItemById(owned);
        return 500;
      }
      return 0;
    }
    int id = bestBuyable(ids, levels, skill);
    if (id < 0 || shopMiss.contains(id)) return 0;
    return buy(id, 1, x, y, npcs, shop);
  }

  public int bestOwned(int[] ids, int[] levels, SkillId skill) {
    int lvl = level(skill);
    for (int i = 0; i < ids.length; i++) {
      if (i < levels.length && levels[i] > lvl) continue;
      if (owns(ids[i])) return ids[i];
    }
    return -1;
  }

  private int bestBuyable(int[] ids, int[] levels, SkillId skill) {
    int lvl = level(skill);
    for (int i = 0; i < ids.length; i++) {
      if (i < levels.length && levels[i] > lvl) continue;
      if (!shopMiss.contains(ids[i])) return ids[i];
    }
    return -1;
  }

  public int makeCoins() {
    if (prepDetail == null || prepDetail.isEmpty() || !prepDetail.startsWith("Earning coins")) {
      prepDetail = "Making coins";
    }
    int sold = sellJunk();
    if (sold > 0) return sold;
    return thieve(Ids.MAN, 128, 641, "Lumbridge men");
  }

  public int sellJunk() {
    int id = nextSellable();
    if (id < 0) return 0;
    int step =
        walkTile(
            Location.LUMBRIDGE_GENERAL_STORE.getX(),
            Location.LUMBRIDGE_GENERAL_STORE.getY(),
            4,
            "Walking to the Lumbridge general store");
    if (step > 0) return step;
    if (!c.isInShop()) {
      int open = openShopOnce(Ids.LUM_GENERAL, "the Lumbridge general store");
      if (open == -1) {
        return walkTile(
            Location.LUMBRIDGE_GENERAL_STORE.getX(),
            Location.LUMBRIDGE_GENERAL_STORE.getY(),
            4,
            "Walking to the Lumbridge general store");
      }
      if (open == -2) {
        // Shop UI never opened — skip selling this tick instead of recursing into makeCoins.
        return thieve(Ids.MAN, 128, 641, "Lumbridge men");
      }
      return open;
    }
    int before = invCoins();
    int amount = Math.min(5, count(id));
    say("Selling " + name(id));
    c.shopSell(id, amount);
    int after = invCoins();
    if (after > before) stats.coinsEarned += after - before;
    shopAttempts = 0;
    return 500;
  }

  private int nextSellable() {
    int[] goods = {
      Ids.COW_HIDE,
      Ids.SHRIMP,
      Ids.TROUT,
      Ids.SALMON,
      Ids.LOBSTER,
      Ids.LOGS,
      Ids.OAK_LOGS,
      Ids.WILLOW_LOGS,
      Ids.MAPLE_LOGS,
      Ids.YEW_LOGS,
      Ids.BOW_STRING,
      Ids.BALL_OF_WOOL,
      Ids.BRONZE_BAR,
      Ids.IRON_BAR,
      Ids.STEEL_BAR,
      Ids.ASHES
    };
    for (int id : goods) if (count(id) > 0) return id;
    return -1;
  }

  public int thieve(int npcId, int x, int y, String label) {
    if (c.isInCombat()) {
      say("Leaving combat");
      c.walkToAsync(c.currentX() + 2, c.currentY(), 0);
      return 500;
    }
    int step = walkTile(x, y, 8, "Walking to " + label);
    if (step > 0) return step;
    ORSCharacter npc = c.getNearestNpcById(npcId, false);
    if (npc == null) {
      say("Looking for " + label);
      return 600;
    }
    say("Pickpocketing " + label);
    int before = invCoins();
    c.thieveNpc(npc.serverIndex);
    int after = invCoins();
    if (after > before) stats.coinsEarned += after - before;
    return 400;
  }

  public int fight(int[] npcIds, int x, int y, String label) {
    applyMeleeStyle();
    if (c.isInCombat()) return 400;
    ORSCharacter npc = c.getNearestNpcByIds(npcIds, false);
    if (npc == null) return walkTile(x, y, 12, "Walking to " + label);
    say("Fighting " + label);
    c.attackNpc(npc.serverIndex);
    return 600;
  }

  public void applyMeleeStyle() {
    String choice = config.meleeStyle == null ? "Controlled" : config.meleeStyle;
    if ("Rotate".equals(choice)) {
      String[] names = {"Accurate", "Aggressive", "Defensive", "Controlled"};
      choice = names[Math.floorMod(styleCursor, names.length)];
    }
    int mode = 0;
    if ("Accurate".equals(choice)) mode = 2;
    else if ("Aggressive".equals(choice)) mode = 1;
    else if ("Defensive".equals(choice)) mode = 3;
    styleName = choice;
    if (c.getFightMode() != mode) c.setFightMode(mode);
  }

  public int useObject(int[] objectIds, int x, int y, String label, boolean secondAction) {
    if (full()) return bankExcept();
    int[] coords = c.getNearestReachableObjectByIds(objectIds, true);
    if (coords == null) return walkTile(x, y, 10, "Walking to " + label);
    say(label);
    if (secondAction) c.atObject2(coords[0], coords[1]);
    else c.atObject(coords[0], coords[1]);
    return 1000;
  }

  public int useItemOnNearest(int itemId, int objectId, int x, int y, String label) {
    int[] coords = c.getNearestObjectById(objectId);
    if (coords == null || c.distanceTo(coords[0], coords[1]) > 10) {
      return walkTile(x, y, 6, "Walking to " + label);
    }
    say(label);
    c.useItemIdOnObject(coords[0], coords[1], itemId);
    return 900;
  }

  public int lootNearby() {
    if (full()) return 0;
    int total = c.getGroundItemsCount();
    int[] ids = c.getGroundItems();
    int[] xs = c.getGroundItemsX();
    int[] ys = c.getGroundItemsY();
    if (ids == null || xs == null || ys == null || total <= 0) return 0;
    int best = -1;
    int bestPrice = config.minLootValue - 1;
    int limit = Math.min(total, ids.length);
    for (int i = 0; i < limit; i++) {
      int id = ids[i];
      if (id <= 0) continue;
      if (c.distance(c.currentX(), c.currentY(), xs[i], ys[i]) > 6) continue;
      if (Ids.isBone(id)) {
        if (config.lootBones) {
          best = i;
          break;
        }
        continue;
      }
      if (id == Ids.COW_HIDE && config.lootHides) {
        best = i;
        bestPrice = 100000;
        continue;
      }
      if (Ids.isTool(id)) {
        best = i;
        break;
      }
      int price = basePrice(id);
      if (price >= config.minLootValue && price > bestPrice) {
        bestPrice = price;
        best = i;
      }
    }
    if (best < 0) return 0;
    int id = ids[best];
    say("Looting " + name(id));
    c.pickupItem(xs[best], ys[best], id, false, false);
    stats.addLoot(name(id), 1, basePrice(id));
    return 500;
  }
}
