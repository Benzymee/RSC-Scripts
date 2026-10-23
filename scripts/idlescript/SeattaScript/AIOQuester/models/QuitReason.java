package scripting.idlescript.SeattaScript.AIOQuester.models;

import scripting.idlescript.SeattaScript.AIOQuester.QuestHandler;

public enum QuitReason {
  // %s is replaced with the current quest's name when the message is requested
  SCRIPT_STOPPED("The quest '%s' has been stopped"),
  QUEST_COMPLETED("Quest Completed"),
  // Players should never see these messages if quest scripts are written correctly
  INVENTORY_FULL("Unable to pick up item because inventory is full"),
  NPC_NOT_FOUND("An npc with the given id was not found nearby"),
  OBJECT_NOT_FOUND("An object was not found. Check the coordinates"),
  PATH_TILE_NOT_REACHABLE("The walk path tile was not reachable"),
  QUEST_STAGE_NOT_IN_SWITCH("The current quest stage is not defined in the switch case"),
  UNABLE_TO_WALK_TO_LOCATION(
      "Failed to walk to specified location. WebWalker may need to be updated."),
  REQUIREMENTS_NOT_MET("The character doesn't meet a requirement of this quest route"),
  // Used by the quest queue when a quest exceeds its time limit
  TIMED_OUT("The quest exceeded its time limit"),
  // The user pressed the paint's "Skip quest" button (or typed ::skip)
  SKIPPED_BY_USER("Skipped by the user (Skip quest button)"),
  /** Shield of Arrav needs the other account for the half it cannot get. */
  COOP_PARTNER_NEEDED(
      "Needs the other Shield of Arrav account. This gang's half is as far as one account can go. Trade the missing piece, then run again."),
  /** Knight's Sword portrait is given only while another player is talking to Sir Vyvin. */
  VYVIN_DISTRACTION_NEEDED(
      "Sir Vyvin is watching the cupboard. Another account has to keep him talking, then run this quest again."),
  /** The same server/quest message or client action arrived too many times in a row. */
  REPEATED_GAME_MESSAGE("The same server or quest message or client action kept repeating"),
  /** Player died ("Oh dear! You are dead" / "granted another life"). */
  DIED("The character died"),
  /** A fight is too dangerous for this account (e.g. Alomone is combat 42). */
  TOO_WEAK("Too weak for this fight - train first (Alomone is combat 42)");

  private final String reason;

  QuitReason(String reason) {
    this.reason = reason;
  }

  public String getMessage() {
    if (!reason.contains("%s")) return reason;
    QuestDef quest = QuestHandler.QUEST;
    return String.format(reason, quest == null ? "unknown" : quest.getName());
  }
}
