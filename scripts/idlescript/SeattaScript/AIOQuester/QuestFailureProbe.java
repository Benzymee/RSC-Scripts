package scripting.idlescript.SeattaScript.AIOQuester;

import bot.Main;
import com.openrsc.client.entityhandling.instances.Item;
import controller.Controller;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import orsc.ORSCharacter;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuestDef;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuestRequirement;
import scripting.idlescript.SeattaScript.AIOQuester.models.QuitReason;

/**
 * Quest failure probe (added by the tester at Ben's request, 21 Oct 2026; please keep it).
 *
 * <p>Whenever QuestQueue ends a quest as FAILED (quit reason, death, timeout, stage retries used
 * up, exception, or the quest script returning before the quest was completed) it writes a report
 * with everything needed to fix the quest to
 * logs/aioquester-failures/&lt;account&gt;/&lt;yyyyMMdd-HHmmss&gt;-&lt;quest&gt;.md and .json, and
 * appends one line to logs/aioquester-failures/INDEX.md.
 *
 * <p>Small ring buffers keep the last 50 server/quest/chat messages, the last 30 client actions
 * and the last 20 QuestWalker lines; they are fed from hooks that already existed in QuestHandler
 * and QuestWalker. Everything here is wrapped in try/catch: the probe never breaks the script.
 */
public final class QuestFailureProbe {
  private static final int MAX_MESSAGES = 50;
  private static final int MAX_ACTIONS = 30;
  private static final int MAX_WALKER = 20;
  private static final int RADIUS = 10;

  private static final ArrayDeque<String> MESSAGES = new ArrayDeque<>();
  private static final ArrayDeque<String> ACTIONS = new ArrayDeque<>();
  private static final ArrayDeque<String> WALKER = new ArrayDeque<>();
  private static volatile String lastWalkTarget = "";

  private QuestFailureProbe() {}

  // ---- ring buffers -----------------------------------------------------------------------------

  private static String now() {
    return new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
  }

  private static void push(ArrayDeque<String> q, int max, String line) {
    synchronized (q) {
      q.addLast(line);
      while (q.size() > max) q.removeFirst();
    }
  }

  private static List<String> copy(ArrayDeque<String> q) {
    synchronized (q) {
      return new ArrayList<>(q);
    }
  }

  /** A server/quest/chat message (type: server, quest, chat). Never throws. */
  public static void noteMessage(String type, String message) {
    try {
      if (message == null) return;
      push(MESSAGES, MAX_MESSAGES, now() + " [" + type + "] " + message);
    } catch (Throwable ignored) {
      // the probe must never break the script
    }
  }

  /** A client action (walk, object, wall, climb, talk, option). Never throws. */
  public static void noteAction(String action) {
    try {
      if (action == null) return;
      String pos = "";
      try {
        Controller c = Main.getController();
        if (c != null) pos = " @" + c.currentX() + "," + c.currentY();
      } catch (Throwable ignored) {
        // position is optional
      }
      push(ACTIONS, MAX_ACTIONS, now() + " " + action + pos);
      if (action.startsWith("walk to ")) lastWalkTarget = action.substring(8);
    } catch (Throwable ignored) {
      // the probe must never break the script
    }
  }

  /** An option menu answer, with the option text when available. Never throws. */
  public static void noteOption(int index) {
    try {
      Controller c = Main.getController();
      String text = safe(() -> c.getOptionsMenuText(index), "?");
      noteAction("option " + index + " \"" + text + "\"");
    } catch (Throwable ignored) {
      // the probe must never break the script
    }
  }

  /** A talk-to-NPC click. Never throws. */
  public static void noteTalk(int npcId, int attempt) {
    try {
      Controller c = Main.getController();
      noteAction("talk " + npcId + " " + safe(() -> c.getNpcName(npcId), "?") + " (attempt " + attempt + ")");
    } catch (Throwable ignored) {
      // the probe must never break the script
    }
  }

  /** A QuestWalker log line (route, door, stuck, retry). Never throws. */
  public static void noteWalker(String line) {
    try {
      if (line == null) return;
      push(WALKER, MAX_WALKER, now() + " " + line);
    } catch (Throwable ignored) {
      // the probe must never break the script
    }
  }

  // ---- report -----------------------------------------------------------------------------------

  /**
   * Writes a failure report. Never throws.
   *
   * @param q the quest
   * @param reason quit reason, or null
   * @param error QuestQueue's error text, or null
   * @param thrown exception that ended the quest, or null
   * @param failure the one-line failure text QuestQueue logs
   * @param stageBefore quest stage when the quest started
   * @param startedAt System time the quest started
   * @return path of the .md written, or null
   */
  public static String report(
      QuestDef q,
      QuitReason reason,
      String error,
      Throwable thrown,
      String failure,
      int stageBefore,
      long startedAt) {
    try {
      Controller c = Main.getController();
      Map<String, Object> r = new LinkedHashMap<>();
      Date when = new Date();
      String questName = q == null ? "unknown" : q.getName();
      String account = safe(() -> c.getPlayerName(), "unknown");

      // quest
      Map<String, Object> quest = new LinkedHashMap<>();
      quest.put("name", questName);
      quest.put("id", q == null ? -1 : q.getId());
      quest.put("miniquest", q != null && q.isMiniquest());
      quest.put("stageBefore", stageBefore);
      quest.put(
          "stageAfter",
          safe(() -> q != null && !q.isMiniquest() ? c.getQuestStage(q.getId()) : null, null));
      quest.put("questHandlerStage", QuestHandler.QUEST_STAGE);
      quest.put("step", QuestHandler.CURRENT_QUEST_STEP);
      quest.put("walkStep", QuestHandler.CURRENT_QUEST_STEP_WALK);
      quest.put("quitReason", reason == null ? null : reason.name());
      quest.put("quitMessage", reason == null ? null : safe(reason::getMessage, reason.name()));
      quest.put("error", error);
      quest.put("failure", failure);
      quest.put("ranMs", System.currentTimeMillis() - startedAt);
      quest.put("ran", duration(System.currentTimeMillis() - startedAt));
      quest.put("startedAt", stamp(new Date(startedAt)));
      quest.put("queueStatus", QuestHandler.QUEUE_STATUS);
      if (thrown != null) {
        quest.put("exception", thrown.toString());
        quest.put("stackTrace", stack(thrown));
      }

      // player
      Map<String, Object> player = new LinkedHashMap<>();
      int x = safe(() -> c.currentX(), -1);
      int y = safe(() -> c.currentY(), -1);
      player.put("x", x);
      player.put("y", y);
      player.put("floor", y < 0 ? -1 : QuestWalker.floor(y));
      player.put("where", safe(() -> DraynorManor.describe(x, y), null));
      boolean wild = safe(() -> c.isInWilderness(), false);
      player.put("inWilderness", wild);
      player.put("wildernessLevel", wild ? wildLevel(y) : 0);
      player.put("hp", safe(() -> c.getCurrentStat(3), -1) + "/" + safe(() -> c.getBaseStat(3), -1));
      player.put("combatLevel", safe(() -> c.getPlayer().level, -1));
      player.put("fatigue", safe(() -> c.getFatigue(), -1));
      player.put("inCombat", safe(() -> c.isInCombat(), false));
      player.put("questPoints", safe(() -> c.getQuestPoints(), -1));
      List<String> skills = new ArrayList<>();
      String[] names = safe(() -> c.getSkillNamesLong(), null);
      if (names != null) {
        for (int i = 0; i < names.length; i++) {
          final int id = i;
          skills.add(
              names[i] + " " + safe(() -> c.getCurrentStat(id), -1) + "/"
                  + safe(() -> c.getBaseStat(id), -1));
        }
      }
      player.put("skills", skills);

      // inventory, equipment, bank
      Map<String, Object> inv = new LinkedHashMap<>();
      List<String> items = new ArrayList<>();
      try {
        for (Item it : c.getInventoryItems()) {
          if (it == null || it.getItemDef() == null) continue;
          int id = it.getItemDef().id;
          items.add(
              id + " " + safe(() -> c.getItemName(id), "?") + " x" + it.getAmount()
                  + (it.getNoted() ? " (noted)" : "") + (it.getEquipped() ? " (equipped)" : ""));
        }
      } catch (Throwable e) {
        items.add("unavailable: " + e);
      }
      inv.put("items", items);
      inv.put("freeSlots", 30 - safe(() -> c.getInventoryItemCount(), 0));
      List<String> equipped = new ArrayList<>();
      try {
        for (int id : c.getEquippedItemIds()) equipped.add(id + " " + safe(() -> c.getItemName(id), "?"));
      } catch (Throwable e) {
        equipped.add("unavailable: " + e);
      }
      inv.put("equipped", equipped);
      inv.put("inBank", safe(() -> c.isInBank(), false));
      Map<Integer, Integer> bank = QuestHandler.bankItems;
      inv.put("bankKnown", bank != null);
      inv.put("bankItemTypes", bank == null ? null : bank.size());
      if (q != null && bank != null && q.getRequiredBankItems() != null) {
        List<String> needBank = new ArrayList<>();
        for (int[] b : q.getRequiredBankItems()) {
          Integer have = bank.get(b[0]);
          needBank.add(b[1] + "x " + safe(() -> c.getItemName(b[0]), "?") + ": bank has " + (have == null ? 0 : have));
        }
        inv.put("requiredBankItems", needBank);
      }

      // dialog
      Map<String, Object> dialog = new LinkedHashMap<>();
      boolean menu = safe(() -> c.isInOptionMenu(), false);
      dialog.put("optionMenuOpen", menu);
      if (menu) {
        List<String> opts = new ArrayList<>();
        int n = safe(() -> c.getOptionMenuCount(), 0);
        for (int i = 0; i < n; i++) {
          final int idx = i;
          opts.add(i + ": " + safe(() -> c.getOptionsMenuText(idx), "?"));
        }
        dialog.put("options", opts);
      }
      dialog.put("inShop", safe(() -> c.isInShop(), false));

      // walker / repeat guards
      Map<String, Object> walker = new LinkedHashMap<>();
      walker.put("lastWalkTarget", lastWalkTarget);
      walker.putAll(safe(QuestHandler::probeGuardState, new LinkedHashMap<String, Object>()));
      walker.put("recentWalkerLines", copy(WALKER));

      // surroundings
      Map<String, Object> near = new LinkedHashMap<>();
      near.put("radius", RADIUS);
      near.put("npcs", npcs(c, x, y));
      near.put("objects", objects(c, x, y, false));
      near.put("wallObjects", objects(c, x, y, true));
      near.put("groundItems", ground(c, x, y));

      // requirements
      List<String> reqs = new ArrayList<>();
      try {
        QuestRequirement[] list = q == null ? null : q.getNormalRequirements();
        if (list != null) {
          for (QuestRequirement qr : list) {
            Boolean met = safe(qr::met, null);
            reqs.add((met == null ? "[?] " : met ? "[met] " : "[NOT met] ") + qr.label);
          }
        }
        if (q != null) reqs.add("isStartable now: " + safe(q::isStartable, null));
      } catch (Throwable e) {
        reqs.add("unavailable: " + e);
      }

      // version
      Map<String, Object> ver = new LinkedHashMap<>();
      ver.put("account", account);
      ver.put("client", clientVersion());
      ver.put("script", "AIOQuester (QuestHandler.java " + scriptStamp() + ")");
      ver.put("java", System.getProperty("java.version"));
      ver.put("time", stamp(when));

      String cause = likelyCause(reason, error, thrown, failure, quest, x, y);
      r.put("likelyCause", cause);
      r.put("quest", quest);
      r.put("player", player);
      r.put("inventory", inv);
      r.put("dialog", dialog);
      r.put("walker", walker);
      r.put("nearby", near);
      r.put("requirements", reqs);
      r.put("recentMessages", copy(MESSAGES));
      r.put("recentActions", copy(ACTIONS));
      r.put("version", ver);

      // write
      Path root = Paths.get("logs", "aioquester-failures");
      Path dir = root.resolve(fileSafe(account));
      Files.createDirectories(dir);
      String base = new SimpleDateFormat("yyyyMMdd-HHmmss").format(when) + "-" + fileSafe(questName);
      Path md = dir.resolve(base + ".md");
      Path json = dir.resolve(base + ".json");
      Files.write(json, toJson(r, 0).getBytes(StandardCharsets.UTF_8));
      Files.write(md, toMarkdown(r, questName, account).getBytes(StandardCharsets.UTF_8));
      String indexLine =
          String.format(
              "- %s | %s | %s | stage %s -> %s | %s | %s | [md](%s/%s)%n",
              stamp(when), account, questName, stageBefore, quest.get("stageAfter"),
              reason == null ? (thrown != null ? "EXCEPTION" : "FAILED") : reason.name(),
              oneLine(cause, 160), fileSafe(account), md.getFileName());
      Path index = root.resolve("INDEX.md");
      if (!Files.exists(index)) {
        Files.write(
            index,
            ("# AIOQuester failure reports\n\nOne line per failed quest, newest last.\n\n")
                .getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      }
      Files.write(index, indexLine.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);

      String path = md.toAbsolutePath().toString();
      String line = "AIOQuester: failure report for " + questName + " saved: " + path;
      System.out.println(line);
      try {
        c.log(line, "yel");
      } catch (Throwable ignored) {
        // console line above is enough
      }
      try {
        bot.probe.Probe.recordEvent("aioquester_failure", questName + ": " + oneLine(cause, 200) + " -> " + path);
      } catch (Throwable ignored) {
        // older client without the probe
      }
      return path;
    } catch (Throwable e) {
      try {
        System.out.println("AIOQuester: failure probe could not write a report: " + e);
      } catch (Throwable ignored) {
        // nothing else to do
      }
      return null;
    }
  }

  // ---- likely cause -----------------------------------------------------------------------------

  private static String likelyCause(
      QuitReason reason, String error, Throwable thrown, String failure, Map<String, Object> quest,
      int x, int y) {
    String step = String.valueOf(quest.get("step"));
    Map<String, Object> g = safe(QuestHandler::probeGuardState, new LinkedHashMap<String, Object>());
    String lastMsg = lastMessage();
    String at = " at " + x + "," + y + " (step '" + step + "', stage " + quest.get("stageAfter") + ")";
    if (thrown != null) {
      return "Exception " + thrown + topFrame(thrown) + at;
    }
    if (reason != null) {
      switch (reason) {
        case REPEATED_GAME_MESSAGE:
          Object rc = g.get("repeatMessageCount");
          Object ac = g.get("repeatActionCount");
          Object fc = g.get("floorFlipCount");
          if (rc instanceof Integer && (Integer) rc >= 15)
            return "Stuck: the same message came " + rc + " times: \"" + g.get("repeatMessage") + "\"" + at;
          if (ac instanceof Integer && (Integer) ac >= 5)
            return "Stuck: the same client action " + ac + " times without moving: \"" + g.get("repeatAction") + "\"" + at;
          if (fc instanceof Integer && (Integer) fc > 10)
            return "Stuck: floor flipped " + fc + " times in 60 s (ladder/stairs loop)" + at;
          return "Repeat guard fired; last message \"" + lastMsg + "\"" + at;
        case DIED:
          return "The character died" + at + "; last message \"" + lastMsg + "\"";
        case TIMED_OUT:
          return "Timed out" + at + "; last message \"" + lastMsg + "\"";
        case UNABLE_TO_WALK_TO_LOCATION:
        case PATH_TILE_NOT_REACHABLE:
          return "Walker couldn't reach " + lastWalkTarget + at;
        case NPC_NOT_FOUND:
          return "NPC not found" + at + "; last message \"" + lastMsg + "\"";
        case OBJECT_NOT_FOUND:
          return "Object not found" + at;
        default:
          return reason.name() + ": " + safe(reason::getMessage, "") + at;
      }
    }
    if (error != null) return error + at + "; last message \"" + lastMsg + "\"";
    return (failure == null ? "quest script ended before the quest was completed" : failure) + at
        + "; last message \"" + lastMsg + "\"";
  }

  private static String lastMessage() {
    List<String> m = copy(MESSAGES);
    return m.isEmpty() ? "" : m.get(m.size() - 1);
  }

  private static String topFrame(Throwable t) {
    for (StackTraceElement e : t.getStackTrace()) {
      if (e.getClassName().contains("AIOQuester")) return " in " + e;
    }
    StackTraceElement[] st = t.getStackTrace();
    return st.length > 0 ? " in " + st[0] : "";
  }

  // ---- surroundings -----------------------------------------------------------------------------

  private static List<String> npcs(Controller c, int px, int py) {
    List<String> out = new ArrayList<>();
    try {
      for (ORSCharacter n : c.getNpcs()) {
        if (n == null) continue;
        int nx = c.convertX(n.currentX), ny = c.convertZ(n.currentZ);
        if (Math.max(Math.abs(nx - px), Math.abs(ny - py)) > RADIUS) continue;
        out.add(
            n.npcId + " " + safe(() -> c.getNpcName(n.npcId), "?") + " @" + nx + "," + ny
                + " idx " + n.serverIndex + (safe(() -> c.isNpcInCombat(n.serverIndex), false) ? " IN COMBAT" : ""));
        if (out.size() >= 40) break;
      }
    } catch (Throwable e) {
      out.add("unavailable: " + e);
    }
    return out;
  }

  private static List<String> objects(Controller c, int px, int py, boolean walls) {
    List<String> out = new ArrayList<>();
    try {
      int n = walls ? c.getWallObjectsCount() : c.getObjectsCount();
      int[] ids = walls ? c.getWallObjectIds() : c.getObjectsIds();
      int[] xs = walls ? c.getWallObjectsX() : c.getObjectsX();
      int[] zs = walls ? c.getWallObjectsZ() : c.getObjectsZ();
      for (int i = 0; i < n && i < ids.length; i++) {
        int ox = c.offsetX(xs[i]), oy = c.offsetZ(zs[i]);
        if (Math.max(Math.abs(ox - px), Math.abs(oy - py)) > RADIUS) continue;
        int id = ids[i];
        String name = walls ? safe(() -> c.getWallObjectName(id), "?") : safe(() -> c.getObjectName(id), "?");
        out.add(id + " " + name + " @" + ox + "," + oy);
        if (out.size() >= 60) break;
      }
    } catch (Throwable e) {
      out.add("unavailable: " + e);
    }
    return out;
  }

  private static List<String> ground(Controller c, int px, int py) {
    List<String> out = new ArrayList<>();
    try {
      int n = c.getGroundItemsCount();
      int[] ids = c.getGroundItems();
      int[] xs = c.getGroundItemsX();
      int[] ys = c.getGroundItemsY();
      for (int i = 0; i < n && i < ids.length; i++) {
        if (Math.max(Math.abs(xs[i] - px), Math.abs(ys[i] - py)) > RADIUS) continue;
        int id = ids[i];
        out.add(id + " " + safe(() -> c.getItemName(id), "?") + " @" + xs[i] + "," + ys[i]);
        if (out.size() >= 40) break;
      }
    } catch (Throwable e) {
      out.add("unavailable: " + e);
    }
    return out;
  }

  /** RSC wilderness level from y (surface formula, other floors folded onto it). Approximate. */
  private static int wildLevel(int y) {
    int d = 2203 - ((y % 944) + 1776);
    return d >= 0 ? 1 + d / 6 : 0;
  }

  // ---- helpers ----------------------------------------------------------------------------------

  private interface Get<T> {
    T get() throws Throwable;
  }

  private static <T> T safe(Get<T> g, T fallback) {
    try {
      T v = g.get();
      return v == null ? fallback : v;
    } catch (Throwable e) {
      return fallback;
    }
  }

  private static String clientVersion() {
    try {
      return String.valueOf(Class.forName("bot.tabs.BuildInfo").getField("VERSION").get(null));
    } catch (Throwable e) {
      return "unknown";
    }
  }

  private static String scriptStamp() {
    try {
      File f = new File("scripts/idlescript/SeattaScript/AIOQuester/QuestHandler.java");
      return f.exists() ? "modified " + stamp(new Date(f.lastModified())) : "source not found";
    } catch (Throwable e) {
      return "unknown";
    }
  }

  private static String stamp(Date d) {
    return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z").format(d);
  }

  private static String duration(long ms) {
    long s = Math.max(0, ms / 1000);
    return String.format("%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
  }

  private static List<String> stack(Throwable t) {
    List<String> out = new ArrayList<>();
    StringWriter sw = new StringWriter();
    t.printStackTrace(new PrintWriter(sw));
    for (String l : sw.toString().split("\\r?\\n")) {
      out.add(l);
      if (out.size() >= 60) break;
    }
    return out;
  }

  private static String fileSafe(String s) {
    String t = s == null ? "unknown" : s.replaceAll("[^A-Za-z0-9._-]+", "_");
    if (t.length() > 60) t = t.substring(0, 60);
    return t.isEmpty() ? "unknown" : t;
  }

  private static String oneLine(String s, int max) {
    if (s == null) return "";
    String t = s.replace('\n', ' ').replace('\r', ' ').replace('|', '/');
    return t.length() > max ? t.substring(0, max) + "..." : t;
  }

  // ---- output -----------------------------------------------------------------------------------

  @SuppressWarnings("unchecked")
  private static String toMarkdown(Map<String, Object> r, String quest, String account) {
    StringBuilder b = new StringBuilder();
    b.append("**Likely cause:** ").append(oneLine(String.valueOf(r.get("likelyCause")), 600)).append("\n\n");
    b.append("# AIOQuester failure: ").append(quest).append(" (").append(account).append(")\n");
    for (Map.Entry<String, Object> e : r.entrySet()) {
      if (e.getKey().equals("likelyCause")) continue;
      b.append("\n## ").append(e.getKey()).append("\n\n");
      appendMd(b, e.getValue());
    }
    return b.toString();
  }

  @SuppressWarnings("unchecked")
  private static void appendMd(StringBuilder b, Object v) {
    if (v instanceof Map) {
      for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
        Object x = e.getValue();
        if (x instanceof List) {
          List<Object> l = (List<Object>) x;
          b.append("- **").append(e.getKey()).append("** (").append(l.size()).append("):\n");
          if (!l.isEmpty()) {
            b.append("```\n");
            for (Object o : l) b.append(o).append('\n');
            b.append("```\n");
          }
        } else if (x instanceof Map) {
          b.append("- **").append(e.getKey()).append("**:\n");
          appendMd(b, x);
        } else {
          b.append("- **").append(e.getKey()).append("**: ").append(x).append('\n');
        }
      }
    } else if (v instanceof List) {
      List<Object> l = (List<Object>) v;
      if (l.isEmpty()) {
        b.append("(none)\n");
      } else {
        b.append("```\n");
        for (Object o : l) b.append(o).append('\n');
        b.append("```\n");
      }
    } else {
      b.append(v).append('\n');
    }
  }

  @SuppressWarnings("unchecked")
  private static String toJson(Object v, int indent) {
    StringBuilder b = new StringBuilder();
    String pad = repeat("  ", indent + 1), end = repeat("  ", indent);
    if (v == null) return "null";
    if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
    if (v instanceof Map) {
      Map<String, Object> m = (Map<String, Object>) v;
      if (m.isEmpty()) return "{}";
      b.append("{\n");
      Iterator<Map.Entry<String, Object>> it = m.entrySet().iterator();
      while (it.hasNext()) {
        Map.Entry<String, Object> e = it.next();
        b.append(pad).append(str(e.getKey())).append(": ").append(toJson(e.getValue(), indent + 1));
        b.append(it.hasNext() ? ",\n" : "\n");
      }
      return b.append(end).append('}').toString();
    }
    if (v instanceof List) {
      List<Object> l = (List<Object>) v;
      if (l.isEmpty()) return "[]";
      b.append("[\n");
      for (int i = 0; i < l.size(); i++) {
        b.append(pad).append(toJson(l.get(i), indent + 1)).append(i + 1 < l.size() ? ",\n" : "\n");
      }
      return b.append(end).append(']').toString();
    }
    return str(String.valueOf(v));
  }

  private static String repeat(String s, int n) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < n; i++) b.append(s);
    return b.toString();
  }

  private static String str(String s) {
    StringBuilder b = new StringBuilder("\"");
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      switch (ch) {
        case '"': b.append("\\\""); break;
        case '\\': b.append("\\\\"); break;
        case '\n': b.append("\\n"); break;
        case '\r': b.append("\\r"); break;
        case '\t': b.append("\\t"); break;
        default:
          if (ch < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
          else b.append(ch);
      }
    }
    return b.append('"').toString();
  }
}
