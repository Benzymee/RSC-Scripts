package scripting.idlescript.ultimate;

import models.entities.ItemId;
import models.entities.NpcId;
import models.entities.SceneryId;

/**
 * Item, NPC and scenery ids taken from IdleRSC's generated enums. Numbers are not inlined so a
 * definition change in those enums stays in one place.
 */
public final class Ids {
  private Ids() {}

  public static final int COINS = ItemId.COINS.getId();
  public static final int CABBAGE = ItemId.CABBAGE.getId();
  public static final int BONES = ItemId.BONES.getId();
  public static final int BIG_BONES = ItemId.BIG_BONES.getId();
  public static final int BAT_BONES = ItemId.BAT_BONES.getId();
  public static final int DRAGON_BONES = ItemId.DRAGON_BONES.getId();
  public static final int COW_HIDE = ItemId.COW_HIDE.getId();
  public static final int ASHES = ItemId.ASHES.getId();
  public static final int KNIFE = ItemId.KNIFE.getId();
  public static final int TINDERBOX = ItemId.TINDERBOX.getId();
  public static final int SHEARS = ItemId.SHEARS.getId();
  public static final int SLEEPING_BAG = ItemId.SLEEPING_BAG.getId();
  public static final int LOGS = ItemId.LOGS.getId();
  public static final int OAK_LOGS = ItemId.OAK_LOGS.getId();
  public static final int WILLOW_LOGS = ItemId.WILLOW_LOGS.getId();
  public static final int MAPLE_LOGS = ItemId.MAPLE_LOGS.getId();
  public static final int YEW_LOGS = ItemId.YEW_LOGS.getId();
  public static final int MAGIC_LOGS = ItemId.MAGIC_LOGS.getId();
  public static final int WOOL = ItemId.WOOL.getId();
  public static final int BALL_OF_WOOL = ItemId.BALL_OF_WOOL.getId();
  public static final int FLAX = ItemId.FLAX.getId();
  public static final int BOW_STRING = ItemId.BOW_STRING.getId();
  public static final int NET = ItemId.NET.getId();
  public static final int FLY_ROD = ItemId.FLY_FISHING_ROD.getId();
  public static final int LOBSTER_POT = ItemId.LOBSTER_POT.getId();
  public static final int FEATHER = ItemId.FEATHER.getId();
  public static final int RAW_SHRIMP = ItemId.RAW_SHRIMP.getId();
  public static final int SHRIMP = ItemId.SHRIMP.getId();
  public static final int RAW_TROUT = ItemId.RAW_TROUT.getId();
  public static final int TROUT = ItemId.TROUT.getId();
  public static final int RAW_SALMON = ItemId.RAW_SALMON.getId();
  public static final int SALMON = ItemId.SALMON.getId();
  public static final int RAW_LOBSTER = ItemId.RAW_LOBSTER.getId();
  public static final int LOBSTER = ItemId.LOBSTER.getId();
  public static final int COPPER_ORE = ItemId.COPPER_ORE.getId();
  public static final int TIN_ORE = ItemId.TIN_ORE.getId();
  public static final int IRON_ORE = ItemId.IRON_ORE.getId();
  public static final int COAL = ItemId.COAL.getId();
  public static final int SILVER = ItemId.SILVER.getId();
  public static final int GOLD = ItemId.GOLD.getId();
  public static final int MITHRIL_ORE = ItemId.MITHRIL_ORE.getId();
  public static final int ADAMANTITE_ORE = ItemId.ADAMANTITE_ORE.getId();
  public static final int RUNITE_ORE = ItemId.RUNITE_ORE.getId();
  public static final int BRONZE_BAR = ItemId.BRONZE_BAR.getId();
  public static final int IRON_BAR = ItemId.IRON_BAR.getId();
  public static final int STEEL_BAR = ItemId.STEEL_BAR.getId();
  public static final int SILVER_BAR = ItemId.SILVER_BAR.getId();
  public static final int GOLD_BAR = ItemId.GOLD_BAR.getId();
  public static final int MITHRIL_BAR = ItemId.MITHRIL_BAR.getId();
  public static final int ADAMANTITE_BAR = ItemId.ADAMANTITE_BAR.getId();
  public static final int RUNITE_BAR = ItemId.RUNITE_BAR.getId();
  public static final int AIR_RUNE = ItemId.AIR_RUNE.getId();
  public static final int MIND_RUNE = ItemId.MIND_RUNE.getId();
  public static final int WATER_RUNE = ItemId.WATER_RUNE.getId();
  public static final int EARTH_RUNE = ItemId.EARTH_RUNE.getId();
  public static final int FIRE_RUNE = ItemId.FIRE_RUNE.getId();
  public static final int STAFF_OF_AIR = ItemId.STAFF_OF_AIR.getId();
  public static final int RUNE_STONE = ItemId.RUNE_STONE.getId();
  public static final int AIR_TALISMAN = ItemId.AIR_TALISMAN.getId();
  public static final int HERB_CLIPPERS = ItemId.HERB_CLIPPERS.getId();
  public static final int CAKE = ItemId.CAKE.getId();
  public static final int COOKED_MEAT = ItemId.COOKEDMEAT.getId();
  public static final int BRONZE_ARROWS = ItemId.BRONZE_ARROWS.getId();

  public static final int[] BONE_IDS = {BONES, BIG_BONES, BAT_BONES, DRAGON_BONES};

  /** Best axe first. Classic has no Woodcutting level requirement to wield an axe. */
  public static final int[] AXES = {
    ItemId.RUNE_AXE.getId(),
    ItemId.ADAMANTITE_AXE.getId(),
    ItemId.MITHRIL_AXE.getId(),
    ItemId.STEEL_AXE.getId(),
    ItemId.IRON_AXE.getId(),
    ItemId.BRONZE_AXE.getId()
  };

  /** Bob's Axes sells bronze through steel only. */
  public static final int[] BOB_AXES = {
    ItemId.STEEL_AXE.getId(), ItemId.IRON_AXE.getId(), ItemId.BRONZE_AXE.getId()
  };

  public static final int[] BOB_AXE_LEVELS = {1, 1, 1};

  /** Best pick first. Levels are the Mining requirement from the classic wiki. */
  public static final int[] PICKS = {
    ItemId.RUNE_PICKAXE.getId(),
    ItemId.ADAMANTITE_PICKAXE.getId(),
    ItemId.MITHRIL_PICKAXE.getId(),
    ItemId.STEEL_PICKAXE.getId(),
    ItemId.IRON_PICKAXE.getId(),
    ItemId.BRONZE_PICKAXE.getId()
  };

  public static final int[] PICK_LEVELS = {41, 31, 21, 6, 1, 1};

  public static final int[] SCIMITARS = {
    ItemId.RUNE_SCIMITAR.getId(),
    ItemId.ADAMANTITE_SCIMITAR.getId(),
    ItemId.MITHRIL_SCIMITAR.getId(),
    ItemId.STEEL_SCIMITAR.getId(),
    ItemId.IRON_SCIMITAR.getId(),
    ItemId.BRONZE_SCIMITAR.getId()
  };

  /** Attack levels required to wield each scimitar, best first. */
  public static final int[] SCIMITAR_LEVELS = {40, 30, 20, 5, 1, 1};

  /** Ranged levels from IdleRSC AIOFighter, best first. */
  public static final int[] BOWS = {
    ItemId.MAGIC_LONGBOW.getId(),
    ItemId.MAGIC_SHORTBOW.getId(),
    ItemId.YEW_LONGBOW.getId(),
    ItemId.YEW_SHORTBOW.getId(),
    ItemId.MAPLE_LONGBOW.getId(),
    ItemId.MAPLE_SHORTBOW.getId(),
    ItemId.WILLOW_LONGBOW.getId(),
    ItemId.WILLOW_SHORTBOW.getId(),
    ItemId.OAK_LONGBOW.getId(),
    ItemId.OAK_SHORTBOW.getId(),
    ItemId.LONGBOW.getId(),
    ItemId.SHORTBOW.getId()
  };

  public static final int[] BOW_LEVELS = {50, 45, 40, 35, 30, 25, 20, 15, 10, 5, 1, 1};

  /** Lowe stocks the lower bows. Higher bows are used if they are already owned. */
  public static final int[] LOWE_BOWS = {
    ItemId.WILLOW_SHORTBOW.getId(),
    ItemId.OAK_SHORTBOW.getId(),
    ItemId.LONGBOW.getId(),
    ItemId.SHORTBOW.getId()
  };

  public static final int[] LOWE_BOW_LEVELS = {15, 5, 1, 1};

  public static final int[] TOOLS;

  static {
    int[] tools =
        new int
            [AXES.length + PICKS.length + SCIMITARS.length + BOWS.length + 8];
    int n = 0;
    for (int id : AXES) tools[n++] = id;
    for (int id : PICKS) tools[n++] = id;
    for (int id : SCIMITARS) tools[n++] = id;
    for (int id : BOWS) tools[n++] = id;
    tools[n++] = KNIFE;
    tools[n++] = TINDERBOX;
    tools[n++] = SHEARS;
    tools[n++] = NET;
    tools[n++] = FLY_ROD;
    tools[n++] = LOBSTER_POT;
    tools[n++] = SLEEPING_BAG;
    tools[n++] = HERB_CLIPPERS;
    TOOLS = tools;
  }

  public static final int CHICKEN = NpcId.CHICKEN.getId();
  public static final int COW = NpcId.COW_ATTACKABLE.getId();
  public static final int GOBLIN_7 = NpcId.GOBLIN_LVL7.getId();
  public static final int GOBLIN_13 = NpcId.GOBLIN_LVL13.getId();
  public static final int MAN = NpcId.MAN.getId();
  public static final int FARMER = NpcId.FARMER.getId();
  public static final int WARRIOR = NpcId.ALKHARID_WARRIOR.getId();
  public static final int GUARD = NpcId.GUARD.getId();
  public static final int SHEEP = NpcId.SHEEP.getId();
  public static final int BOB = NpcId.BOB.getId();
  public static final int GERRANT = NpcId.GERRANT.getId();
  public static final int LOWE = NpcId.LOWE.getId();
  public static final int AUBURY = NpcId.AUBURY.getId();
  public static final int WYDIN = NpcId.WYDIN.getId();
  public static final int NURMOF = NpcId.NURMOF.getId();
  public static final int LUM_SHOP = NpcId.SHOPKEEPER_LUMBRIDGE.getId();
  public static final int LUM_ASSIST = NpcId.SHOP_ASSISTANT_LUMBRIDGE.getId();
  public static final int VAR_SHOP = NpcId.SHOPKEEPER_VARROCK.getId();
  public static final int VAR_ASSIST = NpcId.SHOP_ASSISTANT_VARROCK.getId();
  public static final int SWORD_SHOP = NpcId.SHOPKEEPER_VARROCK_SWORD.getId();
  public static final int SWORD_ASSIST = NpcId.SHOP_ASSISTANT_VARROCK_SWORD.getId();

  public static final int TREE = SceneryId.LEAFY_TREE.getId();
  public static final int TREE_POINTY = SceneryId.POINTY_TREE.getId();
  public static final int OAK = SceneryId.TREE_OAK.getId();
  public static final int WILLOW = SceneryId.TREE_WILLOW.getId();
  public static final int MAPLE = SceneryId.TREE_MAPLE.getId();
  public static final int YEW = SceneryId.TREE_YEW.getId();
  public static final int MAGIC_TREE = SceneryId.TREE_MAGIC.getId();
  public static final int COPPER_ROCK = SceneryId.ROCK_COPPER.getId();
  public static final int TIN_ROCK = SceneryId.ROCK_TIN.getId();
  public static final int IRON_ROCK = SceneryId.ROCK_IRON.getId();
  public static final int COAL_ROCK = SceneryId.ROCK_COAL.getId();
  public static final int MITHRIL_ROCK = SceneryId.ROCK_MITHRIL.getId();
  public static final int ADAMANT_ROCK = SceneryId.ROCK_ADAMITE.getId();
  public static final int RUNE_ROCK = SceneryId.ROCK_RUNITE.getId();
  public static final int FURNACE = SceneryId.FURNACE.getId();
  public static final int RANGE = SceneryId.RANGE.getId();
  public static final int COOKS_RANGE = SceneryId.COOKS_RANGE.getId();
  public static final int SPINNING_WHEEL = SceneryId.SPINNING_WHEEL.getId();
  public static final int FLAX_PLANT = SceneryId.FLAX.getId();
  public static final int NET_SPOT = SceneryId.FISH_NET_BAIT.getId();
  public static final int LURE_SPOT = SceneryId.FISH_LURE_BAIT.getId();
  public static final int CAGE_SPOT = SceneryId.FISH_HARPOON_CAGE.getId();
  public static final int HERB = SceneryId.HERB.getId();
  public static final int REDBERRY = SceneryId.REDBERRY_BUSH.getId();
  public static final int ESS_ROCK = SceneryId.RAW_ESSENCE.getId();
  public static final int ESS_PORTAL = SceneryId.PORTAL_RAW_ESSENCE_MINE.getId();

  public static final int[] LUM_GENERAL = {LUM_SHOP, LUM_ASSIST};
  public static final int[] VAR_GENERAL = {VAR_SHOP, VAR_ASSIST};
  public static final int[] SWORD_NPCS = {SWORD_SHOP, SWORD_ASSIST};

  public static final int[] NORMAL_TREES = {TREE, TREE_POINTY};
  public static final int[] GOBLINS = {GOBLIN_7, GOBLIN_13};
  public static final int[] RANGES = {RANGE, COOKS_RANGE};

  public static boolean isTool(int id) {
    for (int tool : TOOLS) if (tool == id) return true;
    return false;
  }

  public static boolean isBone(int id) {
    for (int bone : BONE_IDS) if (bone == id) return true;
    return false;
  }
}
