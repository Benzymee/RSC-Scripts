IdleRSC scripts
===============

Since v15.3 every bot script lives in this folder as a .java file, so scripts can be
changed or added without rebuilding IdleRSC:
  idlescript/  Native (IdleScript) scripts, e.g. idlescript/SeattaScript/AIOQuester/...
  apos/        APOS scripts
  sbot/        SBot scripts
  common/      shared helpers (package scripting)
Each file keeps its package (package scripting.idlescript...; etc.), so the folders
don't have to match the packages. Your own scripts can go anywhere in here.

IdleRSC installs the shipped scripts itself. It never overwrites a file you changed:
the new version is saved next to it as NAME.java.new and listed in INSTALL-REPORT.txt.

After changing a script press "Reload scripts" in the script selector; no restart is
needed. Only the changed files (and files that use them) are recompiled. Compiled
classes are cached in .cache/ (safe to delete; it is rebuilt). A tab only loads the
classes of the script it runs.

What you can put here:
  * .java source files (needs a JDK 21+: IdleRSC uses the javac of the Java it runs on,
    or finds a JDK via JAVA_HOME / the usual install folders).
  * .class files (in package subfolders) and .jar files.

A script is any class that extends one of:
  scripting.idlescript.IdleScript   (Native)
  compatibility.apos.Script         (APOS)
  compatibility.sbot.Script         (SBot)
If two files define the same class, your own file wins over a shipped one.

Compile errors are shown with file and line numbers when you reload; a broken script
doesn't stop the others from loading.

The examples folder is NOT loaded: copy examples/ExampleScript.java up into this folder
to try it. Mass start and group start find scripts by name in every tab.
Command line: --script-name YourScript works as before.

Folders starting with '.' (the .cache compile cache) and examples/ are ignored.
