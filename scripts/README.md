# IdleRSC external scripts (hot-loaded)

Put your own scripts in this folder. IdleRSC compiles and loads them while it runs, so you do not
need to rebuild the client. They show up in the script selector with an **[ext]** mark (and in the
"External [ext]" category).

## Quick start

1. Open the script selector and press **New script**, or use Settings > External scripts > New
   script. Give it a class name. A template `YourName.java` is created here and its path is shown.
2. Edit it in any editor and save.
3. Press **Reload scripts** in the script selector (or Settings > External scripts > Reload
   scripts). Compile errors are shown in a window, in the console and in the probe log.
4. Pick "YourName [ext]" and run it, by double-clicking or with the Run button. Mass start in the
   multi-account window works the same way.

Turn on **Auto-reload** (in the selector or the Settings menu) to reload by itself whenever a file
in this folder is saved. With auto-reload on, compile errors show up in the console, the probe and
as a chat message in the game window.

## What can be in here

- `.java` files: compiled at runtime. Subfolders work too; use a matching `package` line or
  none at all. Folders whose name starts with `.` and a folder named `disabled` are ignored.
- `.jar` files: added to the script classpath, and any script classes inside are listed.
- `.class` files without a package, directly in this folder.

A script is any public, non-abstract class that extends one of the normal base classes:

- `scripting.idlescript.IdleScript` (native IdleRSC script, recommended)
- `compatibility.apos.Script` (APOS)
- `compatibility.sbot.Script` (SBot)

Give it a `public static final ScriptInfo info = new ScriptInfo(...)` field for its categories,
author and description, the same as a built-in script.

## Rules

- Use `Main.getController()` to get the `Controller`. The `controller` field inherited from
  `IdleScript` is package-private and cannot be used from an external script. Declaring
  `package scripting.idlescript;` does not help: the script comes from another classloader.
- You can use every public class of the client (the controller, `bot.Main`, other scripts' public
  helpers). The script is compiled against the running IdleRSC.jar.
- If your script has the same class name as a built-in script, the built-in one wins when it is
  started by name (for example with `--script-name`). Picking the **[ext]** entry in the
  selector always starts yours. Use a different name to avoid confusion.
- A running script keeps its old code until you stop it and start it again after a reload.
- Each tab of the multi-account window loads its own copy. Tabs pick up changes when the script
  selector is opened, when a script is started, or right away with auto-reload. Compiled output is
  cached in `.build/` and shared, so only one tab actually compiles. You can delete `.build/`
  at any time while IdleRSC is closed.

## Compiler

IdleRSC uses the Java compiler of the JDK it runs on. On a plain JRE, which has no compiler, it uses
the Eclipse compiler bundled in IdleRSC.jar instead (Java 8 language level).

`scripts.properties` stores the auto-reload setting, which is shared by all tabs.
