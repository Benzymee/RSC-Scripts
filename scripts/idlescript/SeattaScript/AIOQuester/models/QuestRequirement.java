package scripting.idlescript.SeattaScript.AIOQuester.models;

import bot.Main;
import controller.Controller;

/** One line in the requirements view. Display only: it does not gate starting a quest. */
public final class QuestRequirement {
  public enum Kind {
    QUEST_POINTS,
    SKILL,
    ITEM,
    EQUIPPED,
    QUEST,
    NOTE
  }

  public final Kind kind;
  public final String label;
  public final int id;
  public final int amount;
  /** Combat levels and other figures the sources disagree on. The view shows "?". */
  public final boolean uncertain;

  private QuestRequirement(Kind kind, String label, int id, int amount, boolean uncertain) {
    this.kind = kind;
    this.label = label;
    this.id = id;
    this.amount = amount;
    this.uncertain = uncertain;
  }

  public static QuestRequirement qp(int amount) {
    return new QuestRequirement(Kind.QUEST_POINTS, amount + " Quest Points", -1, amount, false);
  }

  public static QuestRequirement skill(int skillId, int level, String name) {
    return new QuestRequirement(Kind.SKILL, level + " " + name, skillId, level, false);
  }

  public static QuestRequirement item(int itemId, int amount, String name) {
    String label = amount > 1 ? amount + " " + name : name;
    return new QuestRequirement(Kind.ITEM, label, itemId, amount, false);
  }

  public static QuestRequirement equipped(int itemId, String name) {
    return new QuestRequirement(Kind.EQUIPPED, name + " (equipped)", itemId, 1, false);
  }

  public static QuestRequirement quest(int questId, String name) {
    return new QuestRequirement(Kind.QUEST, name, questId, 1, false);
  }

  public static QuestRequirement note(String text, boolean uncertain) {
    return new QuestRequirement(Kind.NOTE, text, -1, 0, uncertain);
  }

  /**
   * @return TRUE met, FALSE not met, null when it cannot be checked (a note, or uncertain)
   */
  public Boolean met() {
    if (uncertain || kind == Kind.NOTE) return null;
    Controller c = Main.getController();
    switch (kind) {
      case QUEST_POINTS:
        return c.getQuestPoints() >= amount;
      case SKILL:
        return c.getBaseStat(id) >= amount;
      case ITEM:
        int have = c.getInventoryItemCount(id);
        if (amount <= 1 && c.isItemIdEquipped(id)) have++;
        return have >= amount;
      case EQUIPPED:
        return c.isItemIdEquipped(id);
      case QUEST:
        return c.getQuestStage(id) < 0;
      default:
        return null;
    }
  }
}
