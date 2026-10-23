package scripting.idlescript.SeattaScript.AIOQuester;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * One sentence per quest stage, in stage order. The paint marks earlier stages done and the
 * current stage in progress. Quests without an entry fall back to the live step text.
 */
final class QuestSteps {
  private static final Map<String, String[]> STEPS;

  static {
    Map<String, String[]> m = new HashMap<>();
    m.put(
        "Demon Slayer",
        new String[] {
          "Pay Gypsy Aris in Varrock square and learn how to banish Delrith",
          "Ask Sir Prysin in Varrock palace where the three Silverlight keys are",
          "Take Captain Rovin's key from the palace ladder at 142,454",
          "Bring Traiborn 25 bones for the Wizards' Tower key",
          "Wash Sir Prysin's key down the palace drain and pick it up in the sewer",
          "Give all three keys to Sir Prysin and take Silverlight",
          "Fight Delrith in Varrock square and say the incantation"
        });
    m.put(
        "Vampire Slayer",
        new String[] {
          "Offer to help Morgan in Draynor Village",
          "Buy Dr Harlow a beer at the Jolly Boar and take his stake",
          "Buy a hammer and take the garlic from Morgan's upstairs cupboard",
          "Go down the manor crypt stairs and open Count Draynor's coffin",
          "Kill Count Draynor with the stake equipped and a hammer in the inventory"
        });
    m.put(
        "Goblin Diplomacy",
        new String[] {
          "Ask the Port Sarim bartender about the goblins",
          "Offer to choose an armour colour for the generals in Goblin Village",
          "Dye one set of goblin mail orange and give it to them",
          "Dye a second set blue and give it to them",
          "Give them a third set of plain goblin mail"
        });
    m.put(
        "Gertrude's Cat",
        new String[] {
          "Ask Gertrude in Varrock about her missing cat Fluffs",
          "Pay the boys behind the house for the cat's location",
          "Season a sardine with doogle leaves and milk, then feed Fluffs",
          "Collect the kittens from the lumber-mill crate and return them to Gertrude"
        });
    m.put(
        "Plague City",
        new String[] {
          "Offer to look for Elena at Edmond's house in East Ardougne",
          "Give Alrena the dwellberries and take Elena's picture and the gas mask",
          "Dig the muddy patch, rope the grill, and climb into the Ardougne sewer",
          "Enter West Ardougne and ask Milli, then the clerk, then Bravek",
          "Make the hangover cure and give it to Bravek for the warrant",
          "Free Elena from the plague house and tell Edmond she is safe"
        });
    m.put(
        "Clock Tower",
        new String[] {
          "Ask Brother Kojo what is wrong with the Clock Tower",
          "Place the blue, red, purple, and black cogs, then tell Brother Kojo the clock is working"
        });
    m.put(
        "The Hazeel Cult",
        new String[] {
          "Ask Ceril Carnillean in the house south of Ardougne about the thieves",
          "Refuse Clivet in the sewer and side with the Carnilleans",
          "Turn the five sewer valves and raft to Alomone",
          "Defeat Alomone and take the Carnillean armour",
          "Return the armour to Ceril",
          "Search the butler's cupboard upstairs"
        });
    m.put(
        "Sea Slug",
        new String[] {
          "Ask Caroline, south of Ardougne, about the fishing platform",
          "Ask Holgart on the jetty for a ride",
          "Mill flour, collect swamp tar, and cook the swamp paste",
          "Give Holgart the paste and cross to the platform",
          "Find Kennith, then find Kent",
          "Light the torch, kick the panel, and turn the crane to free Kennith",
          "Tell Caroline that Kennith is safe"
        });
    m.put(
        "Druidic Ritual",
        new String[] {
          "Ask Kaqemeex in Taverley for a quest",
          "Talk to Sanfew in the Taverley herblaw shop",
          "Gather raw beef, rat, bear, and chicken, and dip them in the dungeon cauldron",
          "Give the enchanted meats to Sanfew, then return to Kaqemeex"
        });
    m.put(
        "Jungle Potion",
        new String[] {
          "Offer to help Trufitus in Tai Bwo Wannai",
          "Find and identify the snake weed",
          "Find and identify the ardrigal",
          "Find and identify the sito foil",
          "Find and identify the volencia moss",
          "Find and identify the rogue's purse, then give it to Trufitus"
        });
    m.put(
        "Cook's Assistant",
        new String[] {
          "Ask the Lumbridge cook what he needs for the cake",
          "Bring him an egg, a bucket of milk, and a pot of flour"
        });
    m.put(
        "Sheep Shearer",
        new String[] {
          "Ask Fred the Farmer for a quest",
          "Shear sheep, spin the wool, and give Fred 20 balls of wool"
        });
    m.put(
        "Doric's Quest",
        new String[] {
          "Ask Doric, north of Falador, for a quest",
          "Bring him 6 clay, 4 copper ore, and 2 iron ore"
        });
    m.put(
        "Witch's Potion",
        new String[] {
          "Ask Hetty in Rimmington what she needs",
          "Bring an onion, burnt meat, a rat's tail, and an eye of newt",
          "Drink the potion"
        });
    m.put(
        "Imp Catcher",
        new String[] {
          "Ask Wizard Mizgog in the Wizards' Tower for a quest",
          "Bring him a red, yellow, black, and white bead from imps"
        });
    m.put(
        "The Restless Ghost",
        new String[] {
          "Ask Father Aereck in Lumbridge church for a quest",
          "Get a ghostspeak amulet from Father Urhney in the swamp",
          "Talk to the ghost in the graveyard",
          "Take the skull from the wizard's basement and put it back in the coffin"
        });
    m.put(
        "Rune Mysteries",
        new String[] {
          "Ask Duke Horacio on the top floor of Lumbridge castle",
          "Take the air talisman to Sedridor in the Wizards' Tower basement",
          "Take the research package to Aubury in Varrock",
          "Return Aubury's notes to Sedridor"
        });
    m.put(
        "Romeo and Juliet",
        new String[] {
          "Talk to Romeo in Varrock square",
          "Talk to Juliet upstairs in the house west of Varrock",
          "Ask Father Lawrence what to do",
          "Bring the cadava potion to Juliet, then tell Romeo"
        });
    m.put(
        "Ernest the Chicken",
        new String[] {
          "Ask Veronica outside Draynor Manor about Ernest",
          "Talk to Professor Oddenstein on the manor's top floor",
          "Find the pressure gauge, rubber tube, and oil can",
          "Give the three parts to Oddenstein"
        });
    m.put(
        "Pirate's Treasure",
        new String[] {
          "Ask Redbeard Frank in Port Sarim for the treasure",
          "Smuggle the Karamja rum off the banana crate",
          "Have the banana crate shipped and collect it from Wydin",
          "Dig up the treasure in Falador park"
        });
    m.put(
        "Prince Ali Rescue",
        new String[] {
          "Ask Hassan in the Al Kharid palace, then Osman",
          "Get the key print from Lady Keli and have it made into a bronze key",
          "Make the wig, skin paste, and pink skirt disguise",
          "Free Prince Ali from the Al Kharid jail",
          "Tell Hassan that Ali is free"
        });
    m.put(
        "Dragon Slayer",
        new String[] {
          "Ask the Guildmaster, then Oziach, then start the ship with the map",
          "Collect the maze piece, the map piece, and the worm piece",
          "Buy the boat from Klarense and have Ned fix the hole",
          "Have the Oracle tell you the shield words and buy a shield from the Duke",
          "Sail to Crandor and kill Elvarg"
        });
    m.put(
        "Black Knight's Fortress",
        new String[] {
          "Ask Sir Amik Varze on the top floor of Falador castle",
          "Get the cabbage from the monastery patch and the bronze medium helmet",
          "Enter the fortress, listen at the grill, and sabotage the potion"
        });
    m.put(
        "The Knight's Sword",
        new String[] {
          "Ask the squire in the Falador castle courtyard",
          "Ask Reldo about the Imcando dwarves, then talk to Thurgo",
          "Take a portrait from Sir Vyvin's cupboard to Thurgo",
          "Mine blurite ore and give Thurgo the ore and two iron bars"
        });
    m.put(
        "Shield of Arrav",
        new String[] {
          "Ask Reldo in Varrock palace library about the Shield of Arrav",
          "Read the book and join either the Phoenix Gang or the Black Arm Gang",
          "Take one shield half and trade the other half with the opposite gang",
          "Give both halves to King Roald, or to Curator Haig Halen"
        });
    m.put(
        "Murder Mystery",
        new String[] {
          "Offer to help the guard, take a thread from the window, and ask the poison salesman who he sold to",
          "Ask the family, search the six poison sites, match the fingerprints, and tell the guard who did it"
        });
    STEPS = Collections.unmodifiableMap(m);
  }

  private QuestSteps() {}

  /** Stage sentences, or null when this quest has no written outline. */
  static String[] of(String questName) {
    if (questName == null) return null;
    return STEPS.get(questName);
  }

  /**
   * Index of the step in progress. Demon Slayer's bone hand-in and the drain key share one server
   * stage, so those two lines follow which Silverlight keys are already held.
   */
  static int currentIndex(String questName, int stage) {
    String[] outline = of(questName);
    if (outline == null || outline.length == 0) return Math.max(stage, 0);
    if (stage < 0) return outline.length;
    if ("Demon Slayer".equals(questName)) return demonSlayerIndex(stage, outline.length);
    return Math.min(stage, outline.length - 1);
  }

  private static int demonSlayerIndex(int stage, int length) {
    if (stage <= 1) return stage;
    if (stage >= 4) return length - 1;
    int key2 = models.entities.ItemId.SILVERLIGHT_KEY_2.getId();
    int key1 = models.entities.ItemId.SILVERLIGHT_KEY_1.getId();
    int key3 = models.entities.ItemId.SILVERLIGHT_KEY_3.getId();
    if (QuestHandler.c.getInventoryItemCount(key2) < 1) return 2;
    if (QuestHandler.c.getInventoryItemCount(key1) < 1) return 3;
    if (QuestHandler.c.getInventoryItemCount(key3) < 1) return 4;
    return 5;
  }
}
