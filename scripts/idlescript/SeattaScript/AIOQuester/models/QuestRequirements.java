package scripting.idlescript.SeattaScript.AIOQuester.models;

import models.entities.ItemId;
import models.entities.SkillId;

/**
 * Display lists for the 23 F2P quests. Sources: the local OpenRSC plugins in Core-Framework
 * (same tree as gitlab.com/openrsc/openrsc develop), classic.runescape.wiki, and
 * RSC_F2P_Quest_Guide.md. The lists are display-only. Every script still gathers its own items.
 *
 * <p>Called from the Quest enum constructor. Switch on the enum name string so this does not touch
 * other Quest constants while they are still initialising.
 */
public final class QuestRequirements {
  private QuestRequirements() {}

  public static void attach(Quest q) {
    QuestDef d = q.getQuestDef();
    switch (q.name()) {
      case "COOKS_ASSISTANT":
        show(
            d,
            items(item(ItemId.EGG, 1, "Egg"), item(ItemId.MILK, 1, "Bucket of milk"), item(ItemId.POT_OF_FLOUR, 1, "Pot of flour")),
            "Cook's Assistant buys a pot or bucket from the Lumbridge general store whenever the shelf is not empty. Stock above the NPC base (pot 3, bucket 2) is player-sold.");
        break;
      case "SHEEP_SHEARER":
        show(
            d,
            items(item(ItemId.BALL_OF_WOOL, 20, "Ball of wool")),
            "Sheep Shearer sells a picked mind rune, then buys shears from the general store without a base-stock cap.");
        break;
      case "DORICS_QUEST":
        show(
            d,
            items(
                skill(SkillId.MINING, 15, "Mining"),
                item(ItemId.CLAY, 6, "Clay"),
                item(ItemId.COPPER_ORE, 4, "Copper ore"),
                item(ItemId.IRON_ORE, 2, "Iron ore")),
            "Doric's Quest buys a bronze pickaxe without a base-stock cap.");
        break;
      case "RUNE_MYSTERIES":
        show(d, items(note("No skill requirement. The air talisman comes from the Duke.", false)), null);
        break;
      case "THE_RESTLESS_GHOST":
        show(d, items(note("No skill requirement. Ghostspeak amulet from Father Urhney.", false)), null);
        break;
      case "IMP_CATCHER":
        show(d, items(note("Four coloured beads, dropped by imps.", false)), null);
        break;
      case "WITCHS_POTION":
        show(
            d,
            items(
                item(ItemId.ONION, 1, "Onion"),
                item(ItemId.BURNTMEAT, 1, "Burnt meat"),
                note("Rat's tail from a rat. Eye of newt from Betty.", false)),
            "Witch's Potion buys an eye of newt from Betty whenever her shelf is not empty.");
        break;
      case "VAMPIRE_SLAYER":
        show(
            d,
            items(
                item(ItemId.STAKE, 1, "Stake"),
                item(ItemId.HAMMER, 1, "Hammer"),
                item(ItemId.BEER, 1, "Beer"),
                item(ItemId.GARLIC, 1, "Garlic"),
                note("Defeat Count Draynor. The quest text says level 43; the F2P guide says 34.", true)),
            "Vampire Slayer buys a hammer from the Varrock general store whenever stock is above 0.");
        break;
      case "PRINCE_ALI_RESCUE":
        show(
            d,
            items(note("Coins, rope, pink skirt, wig paste, skin paste and the bronze key. No skill gate.", false)),
            "Prince Ali Rescue buys shears from the general store and a pink skirt from Thessalia without a base-stock cap.");
        break;
      case "ROMEO_AND_JULIET":
        show(d, items(note("Cadava berries and the message. No skill gate.", false)), null);
        break;
      case "ERNEST_THE_CHICKEN":
        show(d, items(note("Items are all inside Draynor Manor. No skill gate.", false)), null);
        break;
      case "GOBLIN_DIPLOMACY":
        show(
            d,
            items(
                item(ItemId.GOBLIN_ARMOUR, 3, "Goblin armour"),
                item(ItemId.REDBERRIES, 3, "Redberries"),
                item(ItemId.ONION, 2, "Onion"),
                item(ItemId.WOAD_LEAF, 2, "Woad leaf"),
                note("About 35 coins for Wyson and Aggie. Drops, bushes, the onion field and those two NPCs.", false)),
            null);
        break;
      case "DEMON_SLAYER":
        show(
            d,
            items(
                item(ItemId.COINS, 1, "Coin"),
                item(ItemId.BONES, 25, "Bones"),
                item(ItemId.BUCKET, 1, "Bucket"),
                note("Defeat Delrith. Sources disagree on the combat level needed.", true)),
            null);
        break;
      case "BLACK_KNIGHTS_FORTRESS":
        show(
            d,
            items(
                qp(12),
                item(ItemId.IRON_CHAIN_MAIL_BODY, 1, "Iron chainbody"),
                item(ItemId.MEDIUM_BRONZE_HELMET, 1, "Bronze medium helmet"),
                item(ItemId.CABBAGE, 1, "Cabbage")),
            null);
        break;
      case "PIRATES_TREASURE":
        show(
            d,
            items(
                item(ItemId.KARAMJA_RUM, 1, "Karamja rum"),
                item(ItemId.BANANA, 10, "Banana"),
                item(ItemId.WHITE_APRON, 1, "White apron"),
                item(ItemId.SPADE, 1, "Spade"),
                note("Boat fares and Zambo's rum (his base stock is 3). Rum is smuggled in Luthas's crate.", false)),
            null);
        break;
      case "SHIELD_OF_ARRAV":
        show(
            d,
            items(
                item(ItemId.COINS, 20, "Coins"),
                note("Two accounts. One finishes a gang half, then they trade. gang=phoenix or gang=blackarm.", false)),
            null);
        break;
      case "THE_KNIGHTS_SWORD":
        d.setDisplayRequirements(
            items(skill(SkillId.MINING, 10, "Mining"), note("Redberry pie, the portrait, blurite ore and 2 iron bars. Bars can be traded on a normal account.", false)),
            items(
                skill(SkillId.MINING, 10, "Mining"),
                skill(SkillId.SMITHING, 15, "Smithing"),
                item(ItemId.REDBERRY_PIE, 1, "Redberry pie"),
                item(ItemId.IRON_BAR, 2, "Iron bar"),
                item(ItemId.BLURITE_ORE, 1, "Blurite ore")),
            null);
        break;
      case "DRAGON_SLAYER":
        d.setDisplayRequirements(
            items(
                qp(32),
                note("33 Magic is on some guides for telegrab. The door also opens with a mind bomb, silk, a lobster pot and an unfired bowl, so Magic is not required.", true),
                note("Defeat Elvarg. The F2P guide says combat 110. Treat that figure as uncertain.", true)),
            items(
                qp(32),
                skill(SkillId.MINING, 30, "Mining"),
                skill(SkillId.SMITHING, 34, "Smithing"),
                item(ItemId.PLANK, 3, "Plank"),
                item(ItemId.NAILS, 12, "Steel nails"),
                item(ItemId.HAMMER, 1, "Hammer"),
                item(ItemId.COINS, 2000, "Coins"),
                item(ItemId.SILK, 1, "Silk"),
                item(ItemId.LOBSTER_POT, 1, "Lobster pot"),
                item(ItemId.UNFIRED_BOWL, 1, "Unfired bowl"),
                item(ItemId.WIZARDS_MIND_BOMB, 1, "Wizard's mind bomb"),
                note("Defeat Elvarg. The F2P guide says combat 110. Treat that figure as uncertain.", true)),
            null);
        break;
      case "BARCRAWL":
        show(d, items(note("A few coins for the bars. Those are NPC drinks, which an ironman may buy.", false)), null);
        break;
      case "FISHING_CONTEST":
        show(
            d,
            items(skill(SkillId.FISHING, 10, "Fishing"), note("A fishing rod, bait, a spade and garlic.", false)),
            "Fishing Contest buys a rod and bait from Harry whenever stock is above 0.");
        break;
      case "SCORPION_CATCHER":
        show(d, items(note("Scorpion cages from Thormac. No item shop.", false)), null);
        break;
      case "TRIBAL_TOTEM":
        show(d, items(note("Coins for the taxidermist and the boat. NPC services.", false)), null);
        break;
      case "MONKS_FRIEND":
        show(d, items(note("A child's blanket and jug of water, both gathered.", false)), null);
        break;
      case "GERTRUDES_CAT":
        show(
            d,
            items(
                note("100 coins for Shilop.", false),
                note("A bucket of milk, a raw sardine and doogle leaves.", false)),
            "Gertrude's Cat needs Fishing 5. It buys a fishing rod and bait from Gerrant (not a net), milks a cow, and picks doogle leaves behind Gertrude's house.");
        break;
      case "DRUIDIC_RITUAL":
        show(
            d,
            items(note("Raw chicken, beef, rat meat and bear meat, each dipped in the cauldron.", false)),
            "Druidic Ritual kills a chicken, a cow, a rat and a bear, then dips the raw meat in the Taverley dungeon cauldron.");
        break;
      case "CLOCK_TOWER":
        show(
            d,
            items(note("Four cogs, rat poison and a bucket of water, all found.", false)),
            "Clock Tower picks the cogs and the poison out of the cellar and fills a bucket at the well beside the tower.");
        break;
      case "THE_HAZEEL_CULT":
        show(
            d,
            items(
                note("The Carnillean armour, taken from Alomone.", false),
                note(
                    "Combat 30 and 30 Hitpoints. Alomone is combat 42; the script quits TOO_WEAK below that.",
                    false)),
            "The Hazeel Cult refuses Clivet and kills Alomone for the armour. It does not poison the family. Alomone is combat 42; the script quits if combat or Hitpoints are under 30.");
        break;
      case "SEA_SLUG":
        show(
            d,
            items(note("Swamp paste, made from milled flour and swamp tar, and a lit torch.", false)),
            "Sea Slug mills its own flour, picks swamp tar, and lights the torch on the platform. Lighting it needs 30 Firemaking.");
        break;
      case "JUNGLE_POTION":
        show(
            d,
            items(note("Five jungle herbs, identified after Druidic Ritual.", false)),
            "Jungle Potion is skipped until Druidic Ritual is complete. Identify also needs 3 Herblaw, which the ritual reward provides. The Karamja boat costs 30 coins.");
        break;
      case "MURDER_MYSTERY":
        show(
            d,
            items(note("An empty pot from Arhein, flour from the mansion barrel, and flypaper.", false)),
            "Murder Mystery buys one pot from Arhein's own stock and fills it at the mansion barrel. It does not name the killer.");
        break;
      case "PLAGUE_CITY":
        show(
            d,
            items(
                note("Dwellberries, a spade, a bucket of water, rope, a gas mask and a picture.", false),
                note("Chocolate dust, a bucket of milk and snape grass for the hangover cure.", false)),
            "Plague City picks its own berries, picture and spade, buys rope from Ned, and buys chocolate dust from Hudo's own stock.");
        break;
      default:
        break;
    }
  }

  private static void show(QuestDef d, QuestRequirement[] list, String ironmanNote) {
    d.setDisplayRequirements(list, list, ironmanNote);
  }

  private static QuestRequirement[] items(QuestRequirement... reqs) {
    return reqs;
  }

  private static QuestRequirement qp(int n) {
    return QuestRequirement.qp(n);
  }

  private static QuestRequirement skill(SkillId skill, int level, String name) {
    return QuestRequirement.skill(skill.getId(), level, name);
  }

  private static QuestRequirement item(ItemId item, int amount, String name) {
    return QuestRequirement.item(item.getId(), amount, name);
  }

  private static QuestRequirement note(String text, boolean uncertain) {
    return QuestRequirement.note(text, uncertain);
  }
}
