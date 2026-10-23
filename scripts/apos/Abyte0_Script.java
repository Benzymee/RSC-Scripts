package scripting.apos;

import bot.ui.scriptselector.models.Category;
import bot.ui.scriptselector.models.ScriptInfo;
public class Abyte0_Script extends Storm_Script {
  public static final ScriptInfo info =
      new ScriptInfo(
          new Category[] {Category.MISCELLANEOUS},
          "Abyte0",
          "Shared helpers for Abyte0 scripts. Start one of those scripts instead of this.",
          null,
          "Script");




  // public Extension client;

  boolean waitingBeforeLastDrop = false;

  int oakTree = 306;
  int oakLog = 632;
  int oakLongBow = 648;
  int oakLongBowU = 658;

  int willowTree = 307;
  int willowLog = 633;
  int willowLongBow = 650;
  int willowLongBowU = 660;

  int yewTree = 309;
  int yewLog = 635;
  int yewLongBow = 654;
  int yewLongBowU = 664;

  int magicTree = 310;
  int magicLog = 636;
  int magicLongBow = 656;
  int magicLongBowU = 666;

  int bowString = 676;

  public static final String[] PROPERTY_NAMES =
      new String[] {
        "nom",
        "money",
        "feathers",
        "chaosRunes",
        "natureRunes",
        "ironOres",
        "coalOres",
        "mithOres",
        "addyOres",
        "runiteOres",
        "ironBars",
        "steelBars",
        "runiteBars",
        "bowStrings",
        "yewLongU",
        "yewLong",
        "magicLongU",
        "magicLong",
        "rawLobs",
        "cookLobs",
        "rawSharks",
        "cookSharks"
      };

  public static final String[] PROPERTY_NAMES_STATS =
      new String[] {
        "nom",
        "attack",
        "defence",
        "strength",
        "hits",
        "prayer",
        "magic",
        "ranged",
        "fishing",
        "cooking",
        "mining",
        "smithing",
        "woodcut",
        "fletching",
        "agility",
        "firemaking",
        "crafting",
        "herblaw",
        "thieving"
      };

  public Abyte0_Script(String e) {
    super(e);
  }

  public void useSleepingBag() {
    // sendPosition(AutoLogin.user,getX(),getY());
    printInventory();
    printStats();
    printStatsXp();
    // printBot("@mag@Thieving Xp: @or3@" + getExperience(17));
    super.useSleepingBag();
  }

  public void buyItemIdFromShop(int id, int amount) {
    int position = getShopItemById(id);
    if (position == -1) return;

    buyShopItem(position, amount);
  }

  /**
   * print the input text in the console and on the game messages screen
   *
   * @param gameText String value to be output
   */
  public void print(String gameText) {
    System.out.println(gameText);
    printBot(gameText);
  }

  // * BUILDS METHODS *//

  public void printInventory() {
    // String nom = AutoLogin.user;
    int money = 10;
    int feathers = 381;
    int chaosRunes = 41;
    int natureRunes = 40;
    int ironOres = 151;
    int coalOres = 155;
    int mithOres = 153;
    int addyOres = 154;
    int runiteOres = 409;
    int ironBars = 170;
    int steelBars = 171;
    int runiteBars = 408;
    int bowStrings = 676;
    int yewLongU = 664;
    int yewLong = 654;
    int magicLongU = 666;
    int magicLong = 656;
    int rawLobs = 372;
    int cookLobs = 373;
    int rawSharks = 545;
    int cookSharks = 546;

    int[] ids =
        new int[] {
          money,
          feathers,
          chaosRunes,
          natureRunes,
          ironOres,
          coalOres,
          mithOres,
          addyOres,
          runiteOres,
          ironBars,
          steelBars,
          runiteBars,
          bowStrings,
          yewLongU,
          yewLong,
          magicLongU,
          magicLong,
          rawLobs,
          cookLobs,
          rawSharks,
          cookSharks
        };

    String[] valeurs = new String[22];
    // valeurs[0] = nom;

    for (int i = 0; i < 21; i++) {
      int[] bk = new int[] {ids[i]};

      valeurs[i + 1] = getInventoryCount(bk) + "";
    }

    sendInventory(valeurs);
  }

  public void printStats() {
    // String nom = AutoLogin.user;

    int attack = getLevel(0);
    int defence = getLevel(1);
    int strength = getLevel(2);
    int hits = getLevel(3);
    int prayer = getLevel(5);
    int magic = getLevel(6);
    int ranged = getLevel(4);

    int fishing = getLevel(10);
    int cooking = getLevel(7);
    int mining = getLevel(14);
    int smithing = getLevel(13);
    int woodcut = getLevel(8);
    int fletching = getLevel(9);
    int agility = getLevel(16);
    int firemaking = getLevel(11);
    int crafting = getLevel(12);
    int herblaw = getLevel(15);
    int thieving = getLevel(17);

    int[] ids =
        new int[] {
          attack,
          defence,
          strength,
          hits,
          prayer,
          magic,
          ranged,
          fishing,
          cooking,
          mining,
          smithing,
          woodcut,
          fletching,
          agility,
          firemaking,
          crafting,
          herblaw,
          thieving
        };

    String[] valeurs = new String[19];
    // valeurs[0] = nom;

    for (int i = 0; i < 18; i++) {
      valeurs[i + 1] = ids[i] + "";
    }

    sendStats(valeurs);
  }

  public void printStatsXp() {
    // String nom = AutoLogin.user;

    int attack = getExperience(0);
    int defence = getExperience(1);
    int strength = getExperience(2);
    int hits = getExperience(3);
    int prayer = getExperience(5);
    int magic = getExperience(6);
    int ranged = getExperience(4);

    int fishing = getExperience(10);
    int cooking = getExperience(7);
    int mining = getExperience(14);
    int smithing = getExperience(13);
    int woodcut = getExperience(8);
    int fletching = getExperience(9);
    int agility = getExperience(16);
    int firemaking = getExperience(11);
    int crafting = getExperience(12);
    int herblaw = getExperience(15);
    int thieving = getExperience(17);

    int[] ids =
        new int[] {
          attack,
          defence,
          strength,
          hits,
          prayer,
          magic,
          ranged,
          fishing,
          cooking,
          mining,
          smithing,
          woodcut,
          fletching,
          agility,
          firemaking,
          crafting,
          herblaw,
          thieving
        };

    String[] valeurs = new String[19];
    // valeurs[0] = nom;

    for (int i = 0; i < 18; i++) {
      valeurs[i + 1] = ids[i] + "";
    }

    sendStatsXp(valeurs);
  }

  // * SEND METHODS *//
  public void sendPosition(String name, int x, int y) {}

  public void sendInventory(String[] propertyUsed, String[] values) {}

  public void sendStats(String[] propertyUsed, String[] values) {}

  public void sendStatsXp(String[] propertyUsed, String[] values) {}

  public void sendStats(String[] values) {
    sendStats(PROPERTY_NAMES_STATS, values);
  }

  public void sendStatsXp(String[] values) {
    sendStatsXp(PROPERTY_NAMES_STATS, values);
  }

  public void sendInventory(String[] values) {
    sendInventory(PROPERTY_NAMES, values);
  }

  public void createAccount(String name) {}

  public int getExperience(int skill) {
    return getXpForLevel(skill);
    // return (int) client.getExperience(skill);
  }

  /**
   * Returns the position of the item with the given ID in the client's inventory.
   *
   * @param ids the identifiers of the items to search for.
   * @return the position of the first item with the given id(s). May range from 0 to MAX_INV_SIZE.
   */
  public int getLastInventoryIndex(int... ids) {
    for (int i = getInventoryCount() - 1; i >= 0; i--) {
      if (inArray(ids, getInventoryId(i))) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Drop Item id or wait for next drop tick
   *
   * @param id itemId to be dropped
   * @return unknown sleep time
   */
  public int dropItemIdOrWait(int id) {

    int firstInstanceIndex = getInventoryIndex(id);
    if (firstInstanceIndex == -1) return -1;

    int lastInstanceIndex = getLastInventoryIndex(id);

    if (!waitingBeforeLastDrop
        && firstInstanceIndex == lastInstanceIndex) // Let's wait a bit before dropping the last one
    {
      waitingBeforeLastDrop = true;
      return 2000;
    }

    dropItem(firstInstanceIndex);
    waitingBeforeLastDrop = false;

    return 1500;
  }

  //	public int[][] getAllNpcsById(int... ids)
  //	{
  //		int cpt = 0;
  //		for (int i = 0; i < countNpcs(); i++) {
  //			if (inArray(ids, client.getNpcId(client.getNpc(i))))
  //				cpt++;
  //		}
  //
  //		int[][] npcS = new int[cpt][];
  //
  //		int cptAdded = 0;
  //
  //		for (int i = 0; i < countNpcs(); i++) {
  //			if (inArray(ids, client.getNpcId(client.getNpc(i)))) {
  //				final int x = client.getMobLocalX(client.getNpc(i)) + client.getAreaX();
  //				final int y = client.getMobLocalY(client.getNpc(i)) + client.getAreaY();
  //				final int dist = distanceTo(x, y, getX(), getY());
  //				if (dist < 10)
  //				{
  //					final int[] npc = new int[]{-1, -1, -1};
  //
  //					npc[0] = i;
  //					npc[1] = x;
  //					npc[2] = y;
  //
  //					npcS[cptAdded]  = npc;
  //				}
  //			}
  //		}
  //		return npcS;
  //	}

  public void RunFromCombat() {
    walkTo(getX(), getY());
  }

  /**
   * Checks if you still have food items
   *
   * @param foodId id value of food item
   * @return boolean true if you have food, false if not
   */
  public boolean IsStillHavingFood(int foodId) {
    if (foodId == -1) return true;
    if (foodId == 330) return getInventoryCount(foodId, 333, 335) > 0;
    else return getInventoryCount(foodId) > 0;
  }

  /**
   * Eat the food id
   *
   * @param foodId id value of food item
   */
  public final void EatFood(int foodId) {
    if (foodId == -1) return;

    if (foodId == 330) {
      EatCake();
    } else {
      int foodIndex = getInventoryIndex(foodId);
      useItem(foodIndex);
    }
  }

  private void EatCake() {
    int part1 = getInventoryIndex(335);
    int part2 = getInventoryIndex(333);
    int part3 = getInventoryIndex(330);
    if (part1 != -1) {
      useItem(part1);
    } else if (part2 != -1) {
      useItem(part2);
    } else if (part3 != -1) {
      useItem(part3);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers from the newer APOS Abyte0_Script, added so the remaining Abyte0 scripts compile.
  // The APOS auto-reply onChatMessage handler was deliberately not ported.
  // ---------------------------------------------------------------------------------------------

  /** The greater the number, the less often SayRandomQuote() actually says something. */
  int delayDice = 50000;

  /**
   * Returns how many of the given item the open shop has in stock.
   *
   * @param id the item id.
   * @return the stock, or 0 if the shop doesn't sell it.
   */
  public int getShopItemIdAmount(int id) {
    int position = getShopItemById(id);
    if (position == -1) return 0;

    return getShopItemAmount(position);
  }

  /**
   * Quotes used by SayRandomQuote(). Scripts override this.
   *
   * @return the quotes.
   */
  public String[] getRandomQuotes() {
    return new String[] {""};
  }

  /**
   * APOS said one of getRandomQuotes() in public chat now and then. Disabled in this port: public
   * chat from a bot account draws attention, so this does nothing.
   */
  public void SayRandomQuote() {
    // Public chat disabled.
  }

  /**
   * APOS typed {@code content} into public chat. Disabled in this port: the text is only written to
   * the bot log, nothing is sent to the game.
   *
   * @param content the text APOS would have said
   */
  public void Say(String content) {
    print("(public chat disabled) would have said: " + content);
  }

  public String getDateTime() {
    return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .format(java.time.LocalDateTime.now());
  }

  public String getSctiptVersion() {
    return "";
  }

  /**
   * Returns every visible NPC with one of the given ids within 10 tiles.
   *
   * @param ids the NPC ids.
   * @return {local index, x, y} per NPC. Unused trailing entries are null.
   */
  public int[][] getAllNpcsById(int... ids) {
    int cpt = 0;
    for (int i = 0; i < countNpcs(); i++) {
      if (inArray(ids, getNpcId(i))) cpt++;
    }

    int[][] npcS = new int[cpt][];
    int cptAdded = 0;
    for (int i = 0; i < countNpcs() && cptAdded < cpt; i++) {
      if (inArray(ids, getNpcId(i))) {
        final int x = getNpcX(i);
        final int y = getNpcY(i);
        if (distanceTo(x, y, getX(), getY()) < 10) {
          npcS[cptAdded++] = new int[] {i, x, y};
        }
      }
    }
    return npcS;
  }

  protected int getFmodeXp(int fmode) {
    if (fmode == 1) return getXpForLevel(2);
    if (fmode == 2) return getXpForLevel(0);
    if (fmode == 3) return getXpForLevel(1);

    return getXpForLevel(0) + getXpForLevel(1) + getXpForLevel(2);
  }

  protected int getThievingXp() {
    return getXpForLevel(17);
  }

  protected int getFishingXp() {
    return getXpForLevel(10);
  }

  protected int getWoodcuttingXp() {
    return getXpForLevel(8);
  }

  protected int getFletchingXp() {
    return getXpForLevel(9);
  }

  protected int getFmodeLevel(int fmode) {
    if (fmode == 1) return getLevel(2);
    if (fmode == 2) return getLevel(0);
    if (fmode == 3) return getLevel(1);

    return 0;
  }

  protected boolean hasStatistics = false;

  protected void printHelp() {
    if (hasStatistics) {
      print("Press # or ' or type --status to display statistics");
      print("Press F2 to reset statistics");
    }
  }

  protected void printParams() {}

  protected void reportXpChange() {}

  /**
   * Switching to another script is not supported (it was a no-op in APOS too).
   *
   * @param scriptName ignored.
   * @param scriptParams ignored.
   */
  protected void switchToScript(String scriptName, String scriptParams) {
    print("Switching to script " + scriptName + " is not supported; continuing.");
  }

  // Herblaw
  boolean doHerbMixing = true;
  int waterVial = 464;

  int dirtyGuam = 165;
  int dirtyMarrentil = 435;
  int dirtyTarromin = 436;
  int dirtyHarralander = 437;
  int dirtyRanarr = 438;

  int cleanGuam = 444;
  int cleanMarrentil = 445;
  int cleanTarromin = 446;
  int cleanHarralander = 447;
  int cleanRanarr = 448;

  int unfinishGuam = 454;
  int unfinishMarrentil = 455;
  int unfinishTarromin = 456;
  int unfinishHarralander = 457;
  int unfinishRanarr = 458;

  /**
   * Identifies one dirty herb or makes one unfinished potion.
   *
   * @return sleep time, or -1 if there is nothing to do.
   */
  protected int MakeUnfinishPotion() {
    if (getLevel(15) < 3) return -1;

    if (getInventoryCount(dirtyGuam) > 0) {
      useItem(getInventoryIndex(dirtyGuam));
      return 1000;
    }
    if (getInventoryCount(dirtyMarrentil) > 0 && getLevel(15) >= 5) {
      useItem(getInventoryIndex(dirtyMarrentil));
      return 1000;
    }
    if (getInventoryCount(dirtyTarromin) > 0 && getLevel(15) >= 11) {
      useItem(getInventoryIndex(dirtyTarromin));
      return 1000;
    }
    if (getInventoryCount(dirtyHarralander) > 0 && getLevel(15) >= 20) {
      useItem(getInventoryIndex(dirtyHarralander));
      return 1000;
    }
    if (getInventoryCount(dirtyRanarr) > 0 && getLevel(15) >= 25) {
      useItem(getInventoryIndex(dirtyRanarr));
      return 1000;
    }

    if (getInventoryCount(waterVial) == 0) return -1;

    int firstWaterVialPosition = getInventoryIndex(waterVial);
    if (getInventoryCount(cleanGuam) > 0) {
      useItemWithItem(firstWaterVialPosition, getInventoryIndex(cleanGuam));
      return 1000;
    }
    if (getInventoryCount(cleanTarromin) > 0 && getLevel(15) >= 12) {
      useItemWithItem(firstWaterVialPosition, getInventoryIndex(cleanTarromin));
      return 1000;
    }
    if (getInventoryCount(cleanHarralander) > 0 && getLevel(15) >= 22) {
      useItemWithItem(firstWaterVialPosition, getInventoryIndex(cleanHarralander));
      return 1000;
    }
    if (getInventoryCount(cleanRanarr) > 0 && getLevel(15) >= 30) {
      useItemWithItem(firstWaterVialPosition, getInventoryIndex(cleanRanarr));
      return 1000;
    }

    return -1;
  }
}
