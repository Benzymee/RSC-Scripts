package scripting.idlescript.ultimate;

import java.util.Arrays;
import java.util.List;
import models.entities.Location;
import models.entities.SceneryId;
import models.entities.SkillId;
import orsc.ORSCharacter;

/** Skill and money activities. Spots and shops are the ones already used by AIOAIO and Kaila's scripts. */
public final class Activities {
  private Activities() {}

  public static List<Activity> all() {
    return Arrays.asList(
        new Melee(),
        new Ranged(),
        new Magic(),
        new Prayer(),
        new Woodcutting(),
        new Fishing(),
        new Mining(),
        new Smithing(),
        new Cooking(),
        new Firemaking(),
        new Thieving(),
        new Crafting(),
        new Fletching(),
        new Agility(),
        new Runecrafting(),
        new Harvesting(),
        new Money());
  }

  public static final class Melee implements Activity {
    public String id() {
      return "melee";
    }

    public String displayName() {
      return "Melee";
    }

    public String summary() {
      return "Attack, Strength and Defense on chickens, cows, then goblins. Buys a scimitar.";
    }

    public SkillId skill() {
      return SkillId.ATTACK;
    }

    public boolean canTrain(World w) {
      boolean gear = w.ownsAny(Ids.SCIMITARS) || missedShop(w);
      boolean food = w.level(SkillId.ATTACK) < 10 || w.hasFood() || w.hitsPercent() >= 70;
      return gear && food;
    }

    public int prepare(World w) {
      w.prepDetail = "Buying a scimitar and food";
      if (!w.ownsAny(Ids.SCIMITARS)) {
        int bought =
            w.buyBest(
                Ids.SCIMITARS,
                Ids.SCIMITAR_LEVELS,
                SkillId.ATTACK,
                Location.VARROCK_SWORD_SHOP.getX(),
                Location.VARROCK_SWORD_SHOP.getY(),
                Ids.SWORD_NPCS,
                "Varrock sword shop");
        if (bought > 0) return bought;
        if (!w.ownsAny(Ids.SCIMITARS) && missedShop(w)) {
          w.say("No scimitar in stock. Training with fists.");
        }
      }
      return w.getFood();
    }

    public int train(World w) {
      int gear =
          w.buyBest(
              Ids.SCIMITARS,
              Ids.SCIMITAR_LEVELS,
              SkillId.ATTACK,
              Location.VARROCK_SWORD_SHOP.getX(),
              Location.VARROCK_SWORD_SHOP.getY(),
              Ids.SWORD_NPCS,
              "Varrock sword shop");
      if (gear > 0 && w.ownsAny(Ids.SCIMITARS)) return gear;
      if (w.level(SkillId.ATTACK) < 15) {
        return w.fight(new int[] {Ids.CHICKEN}, 117, 609, "Lumbridge chickens");
      }
      if (w.level(SkillId.ATTACK) < 40) {
        return w.fight(new int[] {Ids.COW}, 99, 617, "Lumbridge cows");
      }
      return w.fight(Ids.GOBLINS, Location.LUMBRIDGE_GOBLIN_HUT.getX(), Location.LUMBRIDGE_GOBLIN_HUT.getY(), "goblins");
    }

    private boolean missedShop(World w) {
      for (int id : Ids.SCIMITARS) if (!w.shopMiss.contains(id)) return false;
      return true;
    }
  }

  public static final class Ranged implements Activity {
    public String id() {
      return "ranged";
    }

    public String displayName() {
      return "Ranged";
    }

    public String summary() {
      return "Bows from Lowe, bronze arrows, then the same combat spots as melee.";
    }

    public SkillId skill() {
      return SkillId.RANGED;
    }

    public boolean canTrain(World w) {
      return w.ownsAny(Ids.BOWS) && w.count(Ids.BRONZE_ARROWS) > 0;
    }

    public int prepare(World w) {
      w.prepDetail = "Getting a bow and arrows";
      if (!w.ownsAny(Ids.BOWS)) {
        int bought =
            w.buyBest(
                Ids.LOWE_BOWS,
                Ids.LOWE_BOW_LEVELS,
                SkillId.RANGED,
                Location.VARROCK_ARCHERY_SHOP.getX(),
                Location.VARROCK_ARCHERY_SHOP.getY(),
                new int[] {Ids.LOWE},
                "Lowe's archery shop");
        if (bought > 0) return bought;
        if (!w.ownsAny(Ids.BOWS)) {
          w.requestSwitch("No bow available");
          return 400;
        }
      }
      if (w.count(Ids.BRONZE_ARROWS) < 30) {
        int arrows =
            w.obtain(
                Ids.BRONZE_ARROWS,
                100,
                Location.VARROCK_ARCHERY_SHOP.getX(),
                Location.VARROCK_ARCHERY_SHOP.getY(),
                new int[] {Ids.LOWE},
                "Lowe's archery shop");
        if (arrows > 0) return arrows;
        if (w.count(Ids.BRONZE_ARROWS) < 1) {
          w.requestSwitch("Out of arrows");
          return 400;
        }
      }
      return w.getFood();
    }

    public int train(World w) {
      if (w.count(Ids.BRONZE_ARROWS) < 1) return prepare(w);
      if (w.level(SkillId.RANGED) < 20) {
        return w.fight(new int[] {Ids.CHICKEN}, 117, 609, "Lumbridge chickens");
      }
      return w.fight(new int[] {Ids.COW}, 99, 617, "Lumbridge cows");
    }
  }

  public static final class Magic implements Activity {
    private int spellId = -2;

    public String id() {
      return "magic";
    }

    public String displayName() {
      return "Magic";
    }

    public String summary() {
      return "Wind Strike on chickens. Air and mind runes come from the bank or Aubury.";
    }

    public SkillId skill() {
      return SkillId.MAGIC;
    }

    public boolean canTrain(World w) {
      return spell(w) >= 0 && w.count(Ids.AIR_RUNE) > 0 && w.count(Ids.MIND_RUNE) > 0;
    }

    public int prepare(World w) {
      w.prepDetail = "Getting air and mind runes";
      if (spell(w) < 0) {
        w.requestSwitch("Wind Strike is not on this spell book");
        return 400;
      }
      if (w.count(Ids.AIR_RUNE) < 20) {
        int got =
            w.obtain(
                Ids.AIR_RUNE,
                200,
                Location.VARROCK_RUNE_SHOP.getX(),
                Location.VARROCK_RUNE_SHOP.getY(),
                new int[] {Ids.AUBURY},
                "Aubury's rune shop");
        if (got > 0) return got;
      }
      if (w.count(Ids.MIND_RUNE) < 20) {
        int got =
            w.obtain(
                Ids.MIND_RUNE,
                100,
                Location.VARROCK_RUNE_SHOP.getX(),
                Location.VARROCK_RUNE_SHOP.getY(),
                new int[] {Ids.AUBURY},
                "Aubury's rune shop");
        if (got > 0) return got;
      }
      if (w.count(Ids.AIR_RUNE) < 1 || w.count(Ids.MIND_RUNE) < 1) {
        w.requestSwitch("Out of runes for Wind Strike");
        return 400;
      }
      return 200;
    }

    public int train(World w) {
      if (w.c.isInCombat()) return 400;
      ORSCharacter npc = w.c.getNearestNpcById(Ids.CHICKEN, false);
      if (npc == null) return w.walkTile(117, 609, 10, "Walking to Lumbridge chickens");
      w.say("Casting Wind Strike");
      w.styleName = "Wind Strike";
      w.c.castSpellOnNpc(npc.serverIndex, spell(w));
      return 800;
    }

    private int spell(World w) {
      if (spellId == -2) spellId = w.c.getSpellIdFromName("Wind strike");
      return spellId;
    }
  }

  public static final class Prayer implements Activity {
    public String id() {
      return "prayer";
    }

    public String displayName() {
      return "Prayer";
    }

    public String summary() {
      return "Kills chickens and cows, loots the bones, and buries them.";
    }

    public SkillId skill() {
      return SkillId.PRAYER;
    }

    public boolean canTrain(World w) {
      return true;
    }

    public int prepare(World w) {
      return w.getFood();
    }

    public int train(World w) {
      if (w.count(Ids.BONES) > 0 || w.count(Ids.BIG_BONES) > 0) {
        int bone = w.count(Ids.BIG_BONES) > 0 ? Ids.BIG_BONES : Ids.BONES;
        w.say("Burying " + w.name(bone));
        w.c.itemCommand(bone);
        return 600;
      }
      if (w.level(SkillId.ATTACK) < 20) {
        return w.fight(new int[] {Ids.CHICKEN}, 117, 609, "Lumbridge chickens");
      }
      return w.fight(new int[] {Ids.COW}, 99, 617, "Lumbridge cows");
    }
  }

  public static final class Woodcutting implements Activity {
    public String id() {
      return "woodcutting";
    }

    public String displayName() {
      return "Woodcutting";
    }

    public String summary() {
      return "Best tree the level allows. Axes come from the bank or Bob's shop.";
    }

    public SkillId skill() {
      return SkillId.WOODCUTTING;
    }

    public boolean canTrain(World w) {
      return w.ownsAny(Ids.AXES);
    }

    public int prepare(World w) {
      w.prepDetail = "Getting an axe";
      int got =
          w.buyBest(
              Ids.BOB_AXES,
              Ids.BOB_AXE_LEVELS,
              SkillId.WOODCUTTING,
              Location.LUMBRIDGE_BOBS_AXES.getX(),
              Location.LUMBRIDGE_BOBS_AXES.getY(),
              new int[] {Ids.BOB},
              "Bob's Axes");
      if (got > 0) return got;
      if (!w.ownsAny(Ids.AXES)) {
        int fromBank = w.withdraw(Ids.AXES[Ids.AXES.length - 1], 1);
        if (fromBank > 0) return fromBank;
      }
      if (!w.ownsAny(Ids.AXES)) w.requestSwitch("No axe available");
      return 400;
    }

    public int train(World w) {
      if (w.full()) return w.bankExcept();
      int wc = w.level(SkillId.WOODCUTTING);
      if (wc >= 75) {
        return w.useObject(
            new int[] {Ids.MAGIC_TREE},
            Location.SEERS_VILLAGE_MAGIC_TREES.getX(),
            Location.SEERS_VILLAGE_MAGIC_TREES.getY(),
            "Chopping magic trees",
            false);
      }
      if (wc >= 60) {
        return w.useObject(
            new int[] {Ids.YEW},
            Location.SEERS_VILLAGE_YEW_TREES.getX(),
            Location.SEERS_VILLAGE_YEW_TREES.getY(),
            "Chopping yew trees",
            false);
      }
      if (wc >= 45) {
        return w.useObject(new int[] {Ids.MAPLE}, 500, 453, "Chopping maple trees", false);
      }
      if (wc >= 30) {
        return w.useObject(
            new int[] {Ids.WILLOW},
            Location.SEERS_VILLAGE_WILLOW_TREES.getX(),
            Location.SEERS_VILLAGE_WILLOW_TREES.getY(),
            "Chopping willow trees",
            false);
      }
      if (wc >= 15) return w.useObject(new int[] {Ids.OAK}, 500, 453, "Chopping oak trees", false);
      return w.useObject(Ids.NORMAL_TREES, 500, 453, "Chopping trees", false);
    }
  }

  public static final class Fishing implements Activity {
    public String id() {
      return "fishing";
    }

    public String displayName() {
      return "Fishing";
    }

    public String summary() {
      return "Net shrimp, fly-fish trout and salmon, then cage lobsters. Tools from Gerrant.";
    }

    public SkillId skill() {
      return SkillId.FISHING;
    }

    public boolean canTrain(World w) {
      int fishing = w.level(SkillId.FISHING);
      if (fishing >= 40) return w.owns(Ids.LOBSTER_POT);
      if (fishing >= 20) return w.owns(Ids.FLY_ROD) && w.count(Ids.FEATHER) > 0;
      return w.owns(Ids.NET);
    }

    public int prepare(World w) {
      w.prepDetail = "Getting fishing gear from Gerrant";
      int x = Location.PORT_SARIM_FISHING_SHOP.getX();
      int y = Location.PORT_SARIM_FISHING_SHOP.getY();
      int[] shop = {Ids.GERRANT};
      int fishing = w.level(SkillId.FISHING);
      if (fishing >= 40 && !w.owns(Ids.LOBSTER_POT)) {
        int got = w.obtain(Ids.LOBSTER_POT, 1, x, y, shop, "Gerrant's fish shop");
        if (got > 0) return got;
        if (!w.owns(Ids.LOBSTER_POT)) w.requestSwitch("No lobster pot");
        return 400;
      }
      if (fishing >= 20 && fishing < 40) {
        if (!w.owns(Ids.FLY_ROD)) {
          int got = w.obtain(Ids.FLY_ROD, 1, x, y, shop, "Gerrant's fish shop");
          if (got > 0) return got;
        }
        if (w.count(Ids.FEATHER) < 20) {
          int got = w.obtain(Ids.FEATHER, 200, x, y, shop, "Gerrant's fish shop");
          if (got > 0) return got;
        }
        if (!w.owns(Ids.FLY_ROD) || w.count(Ids.FEATHER) < 1) {
          w.requestSwitch("No fly rod or feathers");
          return 400;
        }
      }
      if (fishing < 20 && !w.owns(Ids.NET)) {
        int got = w.obtain(Ids.NET, 1, x, y, shop, "Gerrant's fish shop");
        if (got > 0) return got;
        if (!w.owns(Ids.NET)) w.requestSwitch("No small net");
        return 400;
      }
      return 200;
    }

    public int train(World w) {
      if (w.full()) return w.bankExcept(Ids.FEATHER);
      int fishing = w.level(SkillId.FISHING);
      if (fishing >= 40) {
        return w.useObject(
            new int[] {Ids.CAGE_SPOT},
            Location.CATHERBY_FISHING_SPOT.getX(),
            Location.CATHERBY_FISHING_SPOT.getY(),
            "Cage fishing",
            false);
      }
      if (fishing >= 20) {
        if (w.count(Ids.FEATHER) < 1) return prepare(w);
        return w.useObject(new int[] {Ids.LURE_SPOT}, 211, 500, "Fly fishing", false);
      }
      return w.useObject(new int[] {Ids.NET_SPOT}, 310, 686, "Net fishing", false);
    }
  }

  public static final class Mining implements Activity {
    public String id() {
      return "mining";
    }

    public String displayName() {
      return "Mining";
    }

    public String summary() {
      return "Copper, tin, iron and coal. Picks come from the bank or Nurmof.";
    }

    public SkillId skill() {
      return SkillId.MINING;
    }

    public boolean canTrain(World w) {
      return w.ownsAny(Ids.PICKS);
    }

    public int prepare(World w) {
      w.prepDetail = "Getting a pickaxe";
      int fromBank = withdrawBestPick(w);
      if (fromBank > 0) return fromBank;
      if (w.ownsAny(Ids.PICKS)) return 200;
      int bought =
          w.buyBest(
              Ids.PICKS,
              Ids.PICK_LEVELS,
              SkillId.MINING,
              293,
              3329,
              new int[] {Ids.NURMOF},
              "Nurmof's pickaxe shop");
      if (bought > 0) return bought;
      if (!w.ownsAny(Ids.PICKS)) w.requestSwitch("No pickaxe available");
      return 400;
    }

    public int train(World w) {
      if (w.full()) return w.bankExcept();
      int mining = w.level(SkillId.MINING);
      if (mining >= 30) {
        return w.useObject(new int[] {Ids.COAL_ROCK}, 228, 515, "Mining coal", false);
      }
      if (mining >= 15) {
        return w.useObject(new int[] {Ids.IRON_ROCK}, 160, 543, "Mining iron", false);
      }
      int[] rocks = (mining % 2 == 0) ? new int[] {Ids.COPPER_ROCK} : new int[] {Ids.TIN_ROCK};
      String label = rocks[0] == Ids.COPPER_ROCK ? "Mining copper" : "Mining tin";
      return w.useObject(rocks, 160, 543, label, false);
    }

    private int withdrawBestPick(World w) {
      int lvl = w.level(SkillId.MINING);
      for (int i = 0; i < Ids.PICKS.length; i++) {
        if (Ids.PICK_LEVELS[i] > lvl) continue;
        if (w.owns(Ids.PICKS[i])) return 0;
        if (w.bankMiss.contains(Ids.PICKS[i])) continue;
        int got = w.withdraw(Ids.PICKS[i], 1);
        if (got > 0) return got;
        if (w.owns(Ids.PICKS[i])) return 200;
      }
      return 0;
    }
  }

  public static final class Smithing implements Activity {
    public String id() {
      return "smithing";
    }

    public String displayName() {
      return "Smithing";
    }

    public String summary() {
      return "Smelts the best bar the ores and Smithing level allow. Mines the ore if the bank is empty.";
    }

    public SkillId skill() {
      return SkillId.SMITHING;
    }

    public boolean canTrain(World w) {
      return recipeOre(w) > 0;
    }

    public int prepare(World w) {
      w.prepDetail = "Getting ore to smelt";
      if (!w.ownsAny(Ids.PICKS)) {
        Mining mining = new Mining();
        return mining.prepare(w);
      }
      int smithing = w.level(SkillId.SMITHING);
      if (smithing >= 15 && w.count(Ids.IRON_ORE) < 1 && !w.bankMiss.contains(Ids.IRON_ORE)) {
        int got = w.withdraw(Ids.IRON_ORE, 20);
        if (got > 0) return got;
      }
      if (w.count(Ids.COPPER_ORE) < 1 && !w.bankMiss.contains(Ids.COPPER_ORE)) {
        int got = w.withdraw(Ids.COPPER_ORE, 14);
        if (got > 0) return got;
      }
      if (w.count(Ids.TIN_ORE) < 1 && !w.bankMiss.contains(Ids.TIN_ORE)) {
        int got = w.withdraw(Ids.TIN_ORE, 14);
        if (got > 0) return got;
      }
      if (w.c.isInBank()) w.c.closeBank();
      if (recipeOre(w) > 0) return 200;
      Mining mining = new Mining();
      return mining.train(w);
    }

    public int train(World w) {
      int ore = recipeOre(w);
      if (ore < 0) return prepare(w);
      return w.useItemOnNearest(ore, Ids.FURNACE, 311, 545, "Smelting at the furnace");
    }

    private int recipeOre(World w) {
      int smithing = w.level(SkillId.SMITHING);
      if (smithing >= 50 && w.count(Ids.MITHRIL_ORE) > 0 && w.count(Ids.COAL) >= 4) return Ids.COAL;
      if (smithing >= 30 && w.count(Ids.IRON_ORE) > 0 && w.count(Ids.COAL) >= 2) return Ids.COAL;
      if (smithing >= 15 && w.count(Ids.IRON_ORE) > 0) return Ids.IRON_ORE;
      if (w.count(Ids.COPPER_ORE) > 0 && w.count(Ids.TIN_ORE) > 0) return Ids.COPPER_ORE;
      return -1;
    }
  }

  public static final class Cooking implements Activity {
    public String id() {
      return "cooking";
    }

    public String displayName() {
      return "Cooking";
    }

    public String summary() {
      return "Cooks raw fish on the Catherby range. Fishes some first when the inventory is empty.";
    }

    public SkillId skill() {
      return SkillId.COOKING;
    }

    public boolean canTrain(World w) {
      return rawFish(w) > 0;
    }

    public int prepare(World w) {
      w.prepDetail = "Catching fish to cook";
      if (w.c.isInBank()) {
        int[] raw = {Ids.RAW_LOBSTER, Ids.RAW_SALMON, Ids.RAW_TROUT, Ids.RAW_SHRIMP};
        for (int id : raw) {
          if (w.bankMiss.contains(id)) continue;
          int got = w.withdraw(id, 20);
          if (got > 0) return got;
          if (w.count(id) > 0) {
            w.c.closeBank();
            return 200;
          }
        }
        w.c.closeBank();
      } else if (!w.bankMiss.contains(Ids.RAW_SHRIMP)) {
        int got = w.withdraw(Ids.RAW_SHRIMP, 20);
        if (got > 0) return got;
      }
      Fishing fishing = new Fishing();
      if (!fishing.canTrain(w)) return fishing.prepare(w);
      return fishing.train(w);
    }

    public int train(World w) {
      int raw = rawFish(w);
      if (raw < 0) return prepare(w);
      return w.useItemOnNearest(raw, SceneryId.RANGE.getId(), 432, 482, "Cooking on the range");
    }

    private int rawFish(World w) {
      int cooking = w.level(SkillId.COOKING);
      if (cooking >= 40 && w.count(Ids.RAW_LOBSTER) > 0) return Ids.RAW_LOBSTER;
      if (cooking >= 25 && w.count(Ids.RAW_SALMON) > 0) return Ids.RAW_SALMON;
      if (cooking >= 15 && w.count(Ids.RAW_TROUT) > 0) return Ids.RAW_TROUT;
      if (w.count(Ids.RAW_SHRIMP) > 0) return Ids.RAW_SHRIMP;
      return -1;
    }
  }

  public static final class Firemaking implements Activity {
    public String id() {
      return "firemaking";
    }

    public String displayName() {
      return "Firemaking";
    }

    public String summary() {
      return "Lights normal logs. Buys a tinderbox and chops a tree when the logs run out.";
    }

    public SkillId skill() {
      return SkillId.FIREMAKING;
    }

    public boolean canTrain(World w) {
      return w.owns(Ids.TINDERBOX) && w.count(Ids.LOGS) > 0;
    }

    public int prepare(World w) {
      w.prepDetail = "Getting a tinderbox and logs";
      if (!w.owns(Ids.TINDERBOX)) {
        int got =
            w.obtain(
                Ids.TINDERBOX,
                1,
                Location.LUMBRIDGE_GENERAL_STORE.getX(),
                Location.LUMBRIDGE_GENERAL_STORE.getY(),
                Ids.LUM_GENERAL,
                "Lumbridge general store");
        if (got > 0) return got;
        if (!w.owns(Ids.TINDERBOX)) {
          w.requestSwitch("No tinderbox");
          return 400;
        }
      }
      if (w.count(Ids.LOGS) < 1) {
        if (!w.ownsAny(Ids.AXES)) {
          Woodcutting woodcutting = new Woodcutting();
          return woodcutting.prepare(w);
        }
        return w.useObject(Ids.NORMAL_TREES, 500, 453, "Chopping logs to burn", false);
      }
      return 200;
    }

    public int train(World w) {
      if (w.count(Ids.ASHES) > 3) {
        w.say("Dropping ashes");
        w.c.dropItem(w.c.getInventoryItemSlotIndex(Ids.ASHES));
        return 300;
      }
      int tinder = w.c.getInventoryItemSlotIndex(Ids.TINDERBOX);
      int logs = w.c.getInventoryItemSlotIndex(Ids.LOGS);
      if (tinder < 0 || logs < 0) return prepare(w);
      w.say("Lighting a fire");
      w.c.useItemOnItemBySlot(tinder, logs);
      return 1200;
    }
  }

  public static final class Thieving implements Activity {
    public String id() {
      return "thieving";
    }

    public String displayName() {
      return "Thieving";
    }

    public String summary() {
      return "Men, farmers, Al Kharid warriors, then Varrock guards. Eats cabbage when stunned.";
    }

    public SkillId skill() {
      return SkillId.THIEVING;
    }

    public boolean canTrain(World w) {
      return w.level(SkillId.THIEVING) < 5 || w.hasFood() || w.hitsPercent() >= 70;
    }

    public int prepare(World w) {
      w.prepDetail = "Getting food before thieving";
      return w.getFood();
    }

    public int train(World w) {
      int thieving = w.level(SkillId.THIEVING);
      if (thieving >= 40) return w.thieve(Ids.GUARD, 139, 1400, "Varrock guards");
      if (thieving >= 25) return w.thieve(Ids.WARRIOR, 71, 690, "Al Kharid warriors");
      if (thieving >= 10) return w.thieve(Ids.FARMER, 116, 607, "Lumbridge farmers");
      return w.thieve(Ids.MAN, 128, 641, "Lumbridge men");
    }
  }

  public static final class Crafting implements Activity {
    public String id() {
      return "crafting";
    }

    public String displayName() {
      return "Crafting";
    }

    public String summary() {
      return "Picks flax in the Tree Gnome Stronghold and spins it from level 10.";
    }

    public SkillId skill() {
      return SkillId.CRAFTING;
    }

    public boolean canTrain(World w) {
      return true;
    }

    public int prepare(World w) {
      return 0;
    }

    public int train(World w) {
      if (w.count(Ids.BOW_STRING) > 20) return w.bankExcept();
      if (w.level(SkillId.CRAFTING) >= 10 && w.count(Ids.FLAX) > 0 && w.c.currentY() > 1000) {
        w.say("Spinning flax");
        w.c.useItemIdOnObject(693, 1459, Ids.FLAX);
        return 1000;
      }
      if (w.level(SkillId.CRAFTING) >= 10 && w.count(Ids.FLAX) >= 25 && w.c.currentY() < 1000) {
        w.say("Climbing to the spinning wheel");
        w.c.walkTowards(692, 515);
        if (w.c.distanceTo(692, 515) < 4) w.c.climb(691, 515);
        return 800;
      }
      if (w.c.currentY() > 1000) {
        w.say("Climbing down from the wheel");
        w.c.atObject(691, 1459);
        return 800;
      }
      if (w.full()) return w.bankExcept();
      return w.useObject(new int[] {Ids.FLAX_PLANT}, 693, 517, "Picking flax", false);
    }
  }

  public static final class Fletching implements Activity {
    public String id() {
      return "fletching";
    }

    public String displayName() {
      return "Fletching";
    }

    public String summary() {
      return "Knife on logs. The knife is withdrawn, bought, or picked up in Lumbridge.";
    }

    public SkillId skill() {
      return SkillId.FLETCHING;
    }

    public boolean canTrain(World w) {
      return w.owns(Ids.KNIFE) && w.count(Ids.LOGS) + w.count(Ids.OAK_LOGS) + w.count(Ids.WILLOW_LOGS) > 0;
    }

    public int prepare(World w) {
      w.prepDetail = "Getting a knife and logs";
      if (!w.owns(Ids.KNIFE)) {
        int got =
            w.obtain(
                Ids.KNIFE,
                1,
                Location.LUMBRIDGE_GENERAL_STORE.getX(),
                Location.LUMBRIDGE_GENERAL_STORE.getY(),
                Ids.LUM_GENERAL,
                "Lumbridge general store");
        if (got > 0) return got;
        if (w.c.distanceTo(130, 667) > 3) return w.walkTile(130, 667, 2, "Walking to the Lumbridge knife spawn");
        w.say("Picking up the knife");
        w.c.pickupItem(Ids.KNIFE);
        return 800;
      }
      if (w.count(Ids.LOGS) + w.count(Ids.OAK_LOGS) + w.count(Ids.WILLOW_LOGS) < 1) {
        if (!w.ownsAny(Ids.AXES)) return new Woodcutting().prepare(w);
        return new Woodcutting().train(w);
      }
      return 200;
    }

    public int train(World w) {
      if (w.c.isInOptionMenu()) {
        int option = option(w);
        if (!w.c.isAuthentic() && w.c.getOptionMenuCount() > 2 && log(w) != Ids.LOGS) option++;
        w.c.optionAnswer(option);
        return 600;
      }
      int knife = w.c.getInventoryItemSlotIndex(Ids.KNIFE);
      int logs = w.c.getInventoryItemSlotIndex(log(w));
      if (knife < 0 || logs < 0) return prepare(w);
      if (w.full()) {
        w.say("Dropping a finished bow");
        int drop = finished(w);
        if (drop > 0) w.c.dropItem(w.c.getInventoryItemSlotIndex(drop));
        return 300;
      }
      w.say("Fletching " + w.name(log(w)));
      w.c.useItemOnItemBySlot(knife, logs);
      return 800;
    }

    private int log(World w) {
      int fletch = w.level(SkillId.FLETCHING);
      if (fletch >= 35 && w.count(Ids.WILLOW_LOGS) > 0) return Ids.WILLOW_LOGS;
      if (fletch >= 20 && w.count(Ids.OAK_LOGS) > 0) return Ids.OAK_LOGS;
      return Ids.LOGS;
    }

    private int option(World w) {
      int fletch = w.level(SkillId.FLETCHING);
      int logs = log(w);
      if (logs == Ids.LOGS) {
        if (fletch >= 10) return 2;
        if (fletch >= 5) return 1;
        return 0;
      }
      if (logs == Ids.OAK_LOGS) return fletch >= 25 ? 1 : 0;
      return fletch >= 40 ? 1 : 0;
    }

    private int finished(World w) {
      int[] bows = {
        189, 188, 649, 648, 651, 650, 277, 276, 659, 658, 661, 660
      };
      for (int id : bows) if (w.count(id) > 0) return id;
      return -1;
    }
  }

  public static final class Agility implements Activity {
    public String id() {
      return "agility";
    }

    public String displayName() {
      return "Agility";
    }

    public String summary() {
      return "Gnome Stronghold course, using the same obstacle order as AIOAIO.";
    }

    public SkillId skill() {
      return SkillId.AGILITY;
    }

    public boolean canTrain(World w) {
      return w.hitsPercent() >= 30 || w.hasFood();
    }

    public int prepare(World w) {
      w.prepDetail = "Getting food for the gnome course";
      return w.getFood();
    }

    public int train(World w) {
      if (w.c.distanceTo(692, 499) <= 3) {
        w.say("Climbing the net");
        w.c.atObject(SceneryId.NET_GNOME_COURSE_START);
        return 800;
      }
      if (w.c.distanceTo(692, 1448) <= 5) {
        w.say("Climbing the watchtower");
        w.c.atObject(SceneryId.WATCH_TOWER_GNOME_COURSE_1ST_F);
        return 800;
      }
      if (w.c.distanceTo(693, 2394) <= 3) {
        w.say("Crossing the rope");
        w.c.atObject(SceneryId.ROPESWING_GNOME_COURSE);
        return 800;
      }
      if (w.c.distanceTo(685, 2396) <= 3) {
        w.say("Climbing down");
        w.c.atObject(SceneryId.WATCH_TOWER_GNOME_COURSE_2ND_F);
        return 800;
      }
      if (w.c.distanceTo(683, 506) <= 3) {
        w.say("Climbing the second net");
        w.c.walkTo(683, 503);
        w.c.atObject(SceneryId.NET_GNOME_COURSE_END);
        return 800;
      }
      if (w.c.distanceTo(683, 501) <= 5) {
        w.say("Crawling through the pipe");
        w.c.atObject(SceneryId.PIPE_GNOME_COURSE);
        return 800;
      }
      if (inside(w)) {
        w.say("Crossing the log");
        w.c.atObject(SceneryId.LOG_GNOME_COURSE);
        return 800;
      }
      return w.walkTile(692, 494, 3, "Walking to the gnome agility course");
    }

    private boolean inside(World w) {
      return w.c.currentX() >= 681
          && w.c.currentY() >= 492
          && w.c.currentX() < 695
          && w.c.currentY() <= 510;
    }
  }

  public static final class Runecrafting implements Activity {
    public String id() {
      return "runecraft";
    }

    public String displayName() {
      return "Runecrafting";
    }

    public String summary() {
      return "Coleslaw only. Mines rune essence at Aubury, then crafts air runes when a talisman is owned.";
    }

    public SkillId skill() {
      return SkillId.RUNECRAFT;
    }

    public boolean supported(World w) {
      return w.coleslaw();
    }

    public boolean canTrain(World w) {
      return w.count(Ids.RUNE_STONE) > 0;
    }

    public int prepare(World w) {
      w.prepDetail = "Mining rune essence";
      if (w.full() && w.count(Ids.RUNE_STONE) > 0) return w.bankExcept(Ids.AIR_TALISMAN);
      int[] rock = w.c.getNearestObjectById(Ids.ESS_ROCK);
      if (rock != null) {
        w.say("Mining rune essence");
        w.c.atObject(rock[0], rock[1]);
        return 1000;
      }
      int[] portal = w.c.getNearestObjectById(Ids.ESS_PORTAL);
      if (portal != null && w.c.currentY() < 100) {
        w.say("Leaving the essence mine");
        w.c.atObject(portal[0], portal[1]);
        return 1000;
      }
      if (w.c.distanceTo(103, 512) > 6) return w.walkTile(103, 512, 5, "Walking to Aubury");
      ORSCharacter aubury = w.c.getNearestNpcById(Ids.AUBURY, false);
      if (aubury == null) return 600;
      w.say("Asking Aubury to teleport");
      w.c.npcCommand1(aubury.serverIndex);
      return 1200;
    }

    public int train(World w) {
      if (!w.owns(Ids.AIR_TALISMAN)) {
        int got = w.withdraw(Ids.AIR_TALISMAN, 1);
        if (got > 0) return got;
        w.prepDetail = "No air talisman, banking essence";
        if (w.full()) return w.bankExcept();
        return prepare(w);
      }
      if (w.c.currentY() < 100) {
        int[] altar = w.c.getNearestObjectById(SceneryId.AIR_ALTAR.getId());
        if (altar != null) {
          w.say("Crafting air runes");
          w.c.atObject(altar[0], altar[1]);
          return 1000;
        }
        int[] exit = w.c.getNearestObjectById(SceneryId.PORTAL_AIR_ALTAR.getId());
        if (exit != null && w.count(Ids.RUNE_STONE) < 1) {
          w.say("Leaving the air altar");
          w.c.atObject(exit[0], exit[1]);
          return 1000;
        }
      }
      int[] ruins = w.c.getNearestObjectById(SceneryId.MYSTERIOUS_RUINS_AIR.getId());
      if (ruins != null && w.c.distanceTo(ruins[0], ruins[1]) < 8) {
        w.say("Entering the air ruins");
        w.c.atObject(ruins[0], ruins[1]);
        return 1000;
      }
      return w.walkTile(
          Location.ZZ_RUNECRAFT_MYSTERIOUS_RUINS_AIR.getX(),
          Location.ZZ_RUNECRAFT_MYSTERIOUS_RUINS_AIR.getY(),
          4,
          "Walking to the air altar");
    }
  }

  public static final class Harvesting implements Activity {
    public String id() {
      return "harvesting";
    }

    public String displayName() {
      return "Harvesting";
    }

    public String summary() {
      return "Coleslaw only. Snips the Edgeville monastery grapes with herb clippers, or picks redberries.";
    }

    public SkillId skill() {
      return SkillId.HARVESTING;
    }

    public boolean supported(World w) {
      return w.coleslaw();
    }

    public boolean canTrain(World w) {
      return true;
    }

    public int prepare(World w) {
      if (w.owns(Ids.HERB_CLIPPERS)) return 0;
      w.prepDetail = "Looking for herb clippers";
      int got = w.withdraw(Ids.HERB_CLIPPERS, 1);
      if (got > 0) return got;
      return 0;
    }

    public int train(World w) {
      if (w.full()) return w.bankExcept(Ids.HERB_CLIPPERS);
      if (w.owns(Ids.HERB_CLIPPERS)) {
        return w.useObject(new int[] {SceneryId.MYSTERIOUS_GRAPE_VINE.getId()}, 251, 454, "Harvesting grapes", false);
      }
      return w.useObject(
          new int[] {Ids.REDBERRY},
          Location.VARROCK_BERRY_BUSHES_EAST.getX(),
          Location.VARROCK_BERRY_BUSHES_EAST.getY(),
          "Picking redberries",
          false);
    }
  }

  public static final class Money implements Activity {
    public String id() {
      return "money";
    }

    public String displayName() {
      return "Money";
    }

    public String summary() {
      return "Sells gathered goods, then pickpockets Lumbridge men until the timer ends.";
    }

    public SkillId skill() {
      return SkillId.THIEVING;
    }

    public boolean canTrain(World w) {
      return true;
    }

    public int prepare(World w) {
      return 0;
    }

    public int train(World w) {
      return w.makeCoins();
    }
  }
}
