import bot.Main;
import bot.ui.scriptselector.models.Category;
import bot.ui.scriptselector.models.ScriptInfo;
import com.openrsc.client.entityhandling.instances.Item;
import controller.Controller;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import models.entities.SkillId;
import orsc.ORSCharacter;
import scripting.idlescript.IdleScript;
import scripting.idlescript.SeattaScript.AIOQuester.pathfinding.CollisionMap;
import scripting.idlescript.SeattaScript.AIOQuester.pathfinding.GridPlanner;

/**
 * Withdraws the best food in Varrock west bank, climbs the north-west ladder inside Varrock
 * Castle, and pickpockets the palace guards on that floor. Climbs back down and restocks when the
 * food runs out.
 *
 * <p>The ladder is scenery 5 at 123,463 (Climb-Up), which comes out at the down ladder scenery 6
 * at 123,1407. The east ladder at 142,454 is Captain Rovin's room and is not used. The guards are
 * npc 65, wandering 133-147, 1390-1411. They need Thieving 40. A failed pickpocket makes the guard
 * attack, so the script walks out of combat and eats.
 *
 * <p>Food is ranked by the heal amount in ItemEdibleHeals. The best kind the account actually has
 * in that bank or in the inventory is withdrawn. Worn items stay worn. Everything else is
 * deposited so the inventory can be filled with food, leaving one spare slot for the coins.
 */
public class BensUpstairsGuardThiever extends IdleScript {
  public static final ScriptInfo info =
      new ScriptInfo(
          new Category[] {Category.THIEVING, Category.IRONMAN_SUPPORTED},
          "Ben",
          "Takes the best food from Varrock west bank, climbs the north-west ladder in\n"
              + "Varrock Castle, and pickpockets the guards upstairs. Climbs back down to\n"
              + "the bank when the food runs out.\n\n"
              + "Needs Thieving 40. Worn armour stays on.",
          null,
          "Bens Upstairs Guard Thiever");

  private static final int FLOOR = 944;
  private static final int GUARD = 65;
  private static final int THIEVING = 40;
  private static final int COINS = 10;
  private static final int[] BANK = {150, 502};
  private static final int[] LADDER_UP = {123, 463};
  private static final int[] LADDER_DOWN = {123, 1407};
  private static final int[] ROVIN_LADDER = {142, 1398};
  /**
   * Gap between the benches, on the guards' side of the floor. Everything south of y=1399 is
   * behind a wall, and the door under the ladder only leads into the bedroom.
   */
  private static final int[] STAND = {134, 1399};
  private static final int[][] UP_STANDS = {{122, 463}, {123, 464}, {124, 463}, {123, 462}};

  /** {item id, heal}, best first. Poison, burnt, and rotten food is left out. */
  private static final int[][] FOODS = {
    {1191, 20}, {1193, 20}, {546, 20}, {1102, 19}, {923, 19}, {709, 19}, {924, 19},
    {907, 15}, {950, 15}, {910, 15}, {677, 14}, {422, 14}, {370, 14}, {555, 13},
    {373, 12}, {908, 12}, {951, 12}, {909, 12}, {952, 12}, {325, 11}, {953, 11},
    {906, 11}, {949, 11}, {901, 11}, {944, 11}, {905, 11}, {948, 11}, {903, 11},
    {946, 11}, {590, 11}, {902, 11}, {945, 11}, {904, 11}, {947, 11}, {751, 10},
    {750, 10}, {367, 10}, {357, 9}, {346, 9}, {327, 8}, {1269, 8}, {329, 8}, {364, 8},
    {913, 8}, {956, 8}, {912, 8}, {955, 8}, {911, 7}, {954, 7}, {551, 7}, {328, 7},
    {326, 7}, {914, 7}, {957, 7}, {359, 7}, {553, 6}, {718, 6}, {332, 5}, {336, 5},
    {263, 5}, {362, 5}, {334, 5}, {257, 5}, {330, 4}, {1245, 4}, {261, 4}, {333, 4},
    {1061, 4}, {355, 4}, {335, 4}, {138, 4}, {259, 4}, {337, 3}, {1103, 3}, {262, 3},
    {258, 3}, {350, 3}, {896, 3}, {132, 3}, {319, 2}, {860, 2}, {859, 2}, {861, 2},
    {210, 2}, {897, 2}, {862, 2}, {749, 2}, {320, 2}, {249, 2}, {871, 2}, {765, 2},
    {885, 2}, {900, 2}, {855, 2}, {856, 2}, {863, 2}, {864, 2}, {865, 2}, {857, 2},
    {858, 2}, {179, 2}, {352, 1}, {18, 1}, {228, 1}, {873, 1}
  };

  private enum Phase {
    BANK,
    CLIMB,
    THIEVE
  }

  private Phase phase = Phase.BANK;
  private String status = "Starting";
  private String foodName = "food";
  private int foodId = -1;
  private int standTry;
  private int picks;
  private int fails;
  private int eaten;
  private int startXp;
  private long startTime;
  private long failAt;
  private long fightSeenAt;
  private long doorOpenedAt;
  private long stuckSince;
  private int stuckX = -1;
  private int stuckY = -1;
  private boolean ready;

  @Override
  public int start(String[] parameters) {
    Controller c = controller;
    if (c == null || !c.isLoggedIn()) return 1000;
    if (!ready) {
      startTime = System.currentTimeMillis();
      startXp = c.getStatXp(SkillId.THIEVING.getId());
      ready = true;
    }
    if (c.getNeedToMove()) c.moveCharacter();
    if (c.getShouldSleep()) {
      status = "Sleeping";
      c.sleepHandler(true);
      return 600;
    }
    if (c.isSleeping()) return 600;

    if (fighting(c)) return escape(c);
    if (shouldEat(c) && invFood(c) > 0 && !c.isInCombat()) {
      eat(c);
      return 900;
    }

    switch (phase) {
      case BANK:
        return bank(c);
      case CLIMB:
        return climb(c);
      case THIEVE:
        return thieve(c);
      default:
        phase = Phase.BANK;
        return 300;
    }
  }

  private int bank(Controller c) {
    if (upstairs(c)) return climbDown(c);
    if (c.currentY() >= FLOOR) return leaveOtherFloor(c);
    if (cheb(c, BANK[0], BANK[1]) > 5) {
      status = "Walking to Varrock west bank";
      noteStuck(c);
      if (stuck(c)) openNearestDoor(c);
      c.walkTowards(BANK[0], BANK[1]);
      return 300;
    }
    if (!c.isInBank()) {
      status = "Opening Varrock west bank";
      if (!openBank(c)) return 600;
    }
    if (c.getBaseStat(SkillId.THIEVING.getId()) < THIEVING) {
      c.closeBank();
      status = "Needs Thieving " + THIEVING;
      c.log("Thieving " + THIEVING + " is required to pickpocket the palace guards.", "red");
      c.stop();
      return 1000;
    }
    foodId = bestFood(c, true);
    if (foodId < 0 && invFood(c) == 0) {
      c.closeBank();
      status = "No food in the bank";
      c.log("No food in Varrock west bank or the inventory. Stopping.", "red");
      c.stop();
      return 1000;
    }
    if (foodId < 0) foodId = bestCarried(c);
    foodName = c.getItemName(foodId);
    status = "Withdrawing " + foodName;
    depositOther(c, foodId);
    int want = 29 - (c.getInventoryItemCount() - c.getInventoryItemCount(foodId));
    int have = c.getInventoryItemCount(foodId);
    int inBank = Math.max(0, c.getBankItemCount(foodId));
    int take = Math.min(inBank, Math.max(0, want - have));
    if (take > 0) {
      int before = have;
      c.withdrawItem_apos(foodId, take);
      c.sleepUntil(() -> c.getInventoryItemCount(foodId) > before, 4000);
    }
    c.closeBank();
    c.sleep(400);
    if (invFood(c) == 0) {
      status = "No food in the bank";
      c.log("Could not withdraw any " + foodName + ". Stopping.", "red");
      c.stop();
      return 1000;
    }
    c.log("Taking " + foodName + " up to the palace guards.", "gre");
    phase = Phase.CLIMB;
    standTry = 0;
    return 300;
  }

  private int climb(Controller c) {
    if (invFood(c) == 0) {
      phase = Phase.BANK;
      return 300;
    }
    if (upstairs(c)) {
      if (inRovinRoom(c)) return leaveRovin(c);
      phase = Phase.THIEVE;
      return 300;
    }
    int[] stand = UP_STANDS[standTry % UP_STANDS.length];
    if (cheb(c, LADDER_UP[0], LADDER_UP[1]) > 1) {
      status = "Walking to the castle ladder";
      noteStuck(c);
      if (stuck(c)) {
        stuckSince = System.currentTimeMillis();
        if (!openNearestDoor(c)) standTry++;
      }
      if (cheb(c, stand[0], stand[1]) > 12) c.walkTowards(stand[0], stand[1]);
      else if (c.isReachable(stand[0], stand[1], false)) c.walkToAsync(stand[0], stand[1], 0);
      else c.walkTowards(stand[0], stand[1]);
      return 300;
    }
    status = "Climbing the north-west ladder";
    int y = c.currentY();
    c.atObject(LADDER_UP[0], LADDER_UP[1]);
    boolean up = c.sleepUntil(() -> c.currentY() >= FLOOR && Math.abs(c.currentY() - y) > 400, 6000);
    if (!up) standTry++;
    return 400;
  }

  private int thieve(Controller c) {
    if (!upstairs(c)) {
      phase = Phase.CLIMB;
      return 300;
    }
    if (inRovinRoom(c)) return leaveRovin(c);
    if (invFood(c) == 0) {
      status = "Out of food, heading to the bank";
      phase = Phase.BANK;
      return 300;
    }
    if (c.getCurrentStat(SkillId.THIEVING.getId()) < THIEVING) {
      status = "Thieving drained below " + THIEVING + ", waiting";
      return 1200;
    }
    if (shouldEat(c)) {
      if (eat(c)) return 900;
      status = "HP too low, heading to the bank";
      phase = Phase.BANK;
      return 300;
    }
    if (c.isBatching()) {
      status = "Pickpocketing a guard";
      return 400;
    }
    ORSCharacter guard = findGuard(c);
    int[] at = guard == null ? null : c.getNpcCoordsByServerIndex(guard.serverIndex);
    if (at != null && cheb(c, at[0], at[1]) <= 2) {
      // close enough to pick the pocket; fall through
    } else if (at != null && canReach(c, at[0], at[1])) {
      return walkNext(c, at[0], at[1], "Walking to a guard");
    } else if (cheb(c, STAND[0], STAND[1]) > 1) {
      return walkNext(c, STAND[0], STAND[1], "Walking to the guards");
    } else {
      status = "Waiting for a guard";
      return 600;
    }
    status = "Pickpocketing a guard";
    int xp = c.getStatXp(SkillId.THIEVING.getId());
    long sent = System.currentTimeMillis();
    c.thieveNpc(guard.serverIndex);
    c.sleepUntil(
        () ->
            c.getStatXp(SkillId.THIEVING.getId()) > xp
                || fighting(c)
                || failAt > sent
                || shouldEat(c),
        5000);
    long until = System.currentTimeMillis() + 20_000;
    while (c.isRunning() && c.isBatching() && !fighting(c) && !shouldEat(c) && System.currentTimeMillis() < until)
      c.sleep(300);
    return 300;
  }

  /** Any upper floor that is not the palace guard floor: take the nearest way down. */
  private int leaveOtherFloor(Controller c) {
    status = "Climbing down";
    int[] ladder = c.getNearestObjectByIds(new int[] {6, 42, 44});
    if (ladder == null) return 600;
    int y = c.currentY();
    c.atObject(ladder[0], ladder[1]);
    c.sleepUntil(() -> Math.abs(c.currentY() - y) > 400, 6000);
    return 400;
  }

  /** True when the upstairs collision map can reach within one tile of the target. */
  private boolean canReach(Controller c, int tx, int ty) {
    CollisionMap map = CollisionMap.get();
    if (map == null) return false;
    return new GridPlanner(map).find(c.currentX(), c.currentY(), tx, ty, 1, null) != null;
  }

  /**
   * One tile toward the target on the real upstairs map. A straight step south from the ladder
   * walks into a wall, and the client then drops the walk without moving.
   */
  private int walkNext(Controller c, int tx, int ty, String doing) {
    CollisionMap map = CollisionMap.get();
    if (map == null) {
      status = "No route upstairs";
      return 1000;
    }
    GridPlanner.Path path = new GridPlanner(map).find(c.currentX(), c.currentY(), tx, ty, 1, null);
    if (path == null || path.length() < 2) {
      status = doing;
      return 800;
    }
    if (path.door[1]) {
      CollisionMap.Door door = map.doorBetween(path.xs[0], path.ys[0], path.xs[1], path.ys[1], 2);
      status = "Opening a door";
      if (door != null && System.currentTimeMillis() - doorOpenedAt > 1800) {
        doorOpenedAt = System.currentTimeMillis();
        if (door.wall) {
          if (door.command == 2) c.atWallObject2(door.x, door.y);
          else c.atWallObject(door.x, door.y);
        } else c.atObject(door.x, door.y);
      }
      return 800;
    }
    int nx = path.xs[1], ny = path.ys[1];
    status = doing;
    c.walkToAsync(nx, ny, 0);
    c.sleepUntil(
        () -> (c.currentX() == nx && c.currentY() == ny) || !c.isCurrentlyWalking(), 2500);
    return 200;
  }

  private int climbDown(Controller c) {
    if (c.currentY() < FLOOR) return 300;
    if (inRovinRoom(c)) return leaveRovin(c);
    if (cheb(c, LADDER_DOWN[0], LADDER_DOWN[1]) > 1)
      return walkNext(c, 122, 1406, "Walking back to the ladder");
    status = "Climbing down";
    int y = c.currentY();
    c.atObject(LADDER_DOWN[0], LADDER_DOWN[1]);
    boolean down = c.sleepUntil(() -> c.currentY() < FLOOR && Math.abs(c.currentY() - y) > 400, 6000);
    if (!down) standTry++;
    return 400;
  }

  private int leaveRovin(Controller c) {
    status = "Wrong ladder, climbing back down";
    if (cheb(c, ROVIN_LADDER[0], ROVIN_LADDER[1]) > 1) {
      if (c.isReachable(ROVIN_LADDER[0], ROVIN_LADDER[1] + 1, false))
        c.walkToAsync(ROVIN_LADDER[0], ROVIN_LADDER[1] + 1, 0);
      return 400;
    }
    int y = c.currentY();
    c.atObject(ROVIN_LADDER[0], ROVIN_LADDER[1]);
    c.sleepUntil(() -> Math.abs(c.currentY() - y) > 400, 6000);
    phase = Phase.CLIMB;
    return 400;
  }

  private boolean openNearestDoor(Controller c) {
    int[][] doors = c.getNearbyClosedDoors(8);
    if (doors == null || doors.length == 0) return false;
    stuckSince = System.currentTimeMillis();
    status = "Opening a door";
    c.openDoor(doors[0][0], doors[0][1]);
    c.sleep(600);
    return true;
  }

  private ORSCharacter findGuard(Controller c) {
    return c.getNearestNPCByLambda(
        n -> {
          if (n.npcId != GUARD) return false;
          if (c.isNpcInCombat(n.serverIndex)) return false;
          int[] at = c.getNpcCoordsByServerIndex(n.serverIndex);
          if (at == null || at[0] < 0) return false;
          if (at[1] < FLOOR) return false;
          return at[0] >= 125 && at[0] <= 148 && at[1] >= 1388 && at[1] <= 1412;
        });
  }

  private int escape(Controller c) {
    status = "Caught, walking away";
    if (c.isBatching()) c.stopBatching();
    if (upstairs(c)) return walkNext(c, STAND[0], STAND[1], "Caught, walking away");
    ThreadLocalRandom r = ThreadLocalRandom.current();
    c.walkToAsync(c.currentX() + r.nextInt(-4, 5), c.currentY() + r.nextInt(-4, 5), 2);
    c.sleep(700);
    return 300;
  }

  private boolean fighting(Controller c) {
    return c.isInCombat() || System.currentTimeMillis() - fightSeenAt < 2000;
  }

  private boolean upstairs(Controller c) {
    int y = c.currentY();
    return y >= 1350 && y < 1500;
  }

  private boolean inRovinRoom(Controller c) {
    return upstairs(c)
        && cheb(c, ROVIN_LADDER[0], ROVIN_LADDER[1]) <= 4
        && !c.isReachable(130, 1400, false);
  }

  private boolean shouldEat(Controller c) {
    int hp = c.getCurrentStat(SkillId.HITS.getId());
    int max = Math.max(1, c.getBaseStat(SkillId.HITS.getId()));
    return hp * 100 < 50 * max || hp < Math.min(15, max);
  }

  private boolean eat(Controller c) {
    int id = bestCarried(c);
    if (id < 0) return false;
    int before = c.getInventoryItemCount(id);
    c.itemCommand(id);
    boolean ate = c.sleepUntil(() -> c.getInventoryItemCount(id) < before, 2500);
    if (ate) eaten++;
    return ate;
  }

  private int invFood(Controller c) {
    int n = 0;
    for (int[] food : FOODS) n += c.getUnnotedInventoryItemCount(food[0]);
    return n;
  }

  /** Highest-healing food in the bank and inventory, or -1. Bank counts only while it is open. */
  private int bestFood(Controller c, boolean bankOpen) {
    int best = -1, heal = -1;
    for (int[] food : FOODS) {
      int have = c.getUnnotedInventoryItemCount(food[0]);
      if (bankOpen) have += Math.max(0, c.getBankItemCount(food[0]));
      if (have > 0 && food[1] > heal) {
        heal = food[1];
        best = food[0];
      }
    }
    return best;
  }

  private int bestCarried(Controller c) {
    for (int[] food : FOODS) if (c.getUnnotedInventoryItemCount(food[0]) > 0) return food[0];
    return -1;
  }

  /** Bank open. Deposits every unequipped item other than the food being taken. */
  private void depositOther(Controller c, int keep) {
    Set<Integer> seen = new HashSet<>();
    for (Item item : c.getInventoryItems()) {
      if (item == null || item.getItemDef() == null) continue;
      int id = item.getItemDef().id;
      if (id == keep || !seen.add(id)) continue;
      try {
        if (item.getEquipped()) continue;
      } catch (Throwable ignored) {
        continue;
      }
      int count = c.getInventoryItemCount(id);
      if (count > 0) {
        c.depositItem(id, count);
        c.sleep(220);
      }
    }
  }

  private boolean openBank(Controller c) {
    for (int attempt = 0; attempt < 5 && c.isRunning() && !c.isInBank(); attempt++) {
      ORSCharacter banker = c.getNearestNpcByIds(c.bankerIds, false);
      if (banker == null || cheb(c, BANK[0], BANK[1]) > 8) {
        c.walkToAsync(BANK[0], BANK[1], 1);
        c.sleep(1000);
        continue;
      }
      if ("Bank".equalsIgnoreCase(c.getNpcCommand1(banker.npcId))) c.npcCommand1(banker.serverIndex);
      else {
        c.talkToNpc(banker.serverIndex);
        if (c.sleepUntil(() -> c.isInOptionMenu(), 6000)) {
          int idx = 0;
          for (int i = 0; i < c.getOptionMenuCount(); i++) {
            String s = c.getOptionsMenuText(i);
            if (s != null && s.toLowerCase().contains("access my bank")) idx = i;
          }
          c.optionAnswer(idx);
        }
      }
      c.sleepUntil(() -> c.isInBank(), 8000);
    }
    return c.isInBank();
  }

  private void noteStuck(Controller c) {
    int x = c.currentX(), y = c.currentY();
    if (x != stuckX || y != stuckY) {
      stuckX = x;
      stuckY = y;
      stuckSince = System.currentTimeMillis();
    }
  }

  private boolean stuck(Controller c) {
    noteStuck(c);
    return System.currentTimeMillis() - stuckSince > 6000;
  }

  private int cheb(Controller c, int x, int y) {
    return Math.max(Math.abs(c.currentX() - x), Math.abs(c.currentY() - y));
  }

  @Override
  public void serverMessageInterrupt(String message) {
    onMessage(message);
  }

  @Override
  public void questMessageInterrupt(String message) {
    onMessage(message);
  }

  private void onMessage(String message) {
    if (message == null) return;
    String m = message.toLowerCase();
    long now = System.currentTimeMillis();
    if (m.contains("you pick the")) picks++;
    else if (m.contains("you fail to pick")) {
      fails++;
      failAt = now;
      fightSeenAt = now;
    } else if (m.contains("under attack") || m.contains("whilst you are fighting")) fightSeenAt = now;
    else if (m.contains("oh dear") && m.contains("dead")) {
      phase = Phase.BANK;
      status = "Died, walking back to the bank";
    }
  }

  @Override
  public void paintInterrupt() {
    Controller c = Main.getController();
    if (c == null || startTime == 0) return;
    long run = Math.max(1, (System.currentTimeMillis() - startTime) / 1000);
    int xp = c.getStatXp(SkillId.THIEVING.getId()) - startXp;
    int y = 36;
    c.drawString("Bens Upstairs Guard Thiever", 6, y, 0xD4AF37, 1);
    y += 14;
    c.drawString(status, 6, y, 0xFFFFFF, 1);
    y += 14;
    c.drawString(
        "Picked " + picks + "  failed " + fails + "  ate " + eaten + "  " + foodName,
        6,
        y,
        0xFFE066,
        1);
    y += 14;
    c.drawString("Thieving +" + xp + " xp  (" + (run / 60) + "m)", 6, y, 0xFFE066, 1);
  }
}
