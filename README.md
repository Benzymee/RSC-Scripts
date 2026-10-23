# RSC-Scripts

A personal collection of Java scripts for IdleRSC/OpenRSC automation and utility workflows.

This repository contains scripts for common in-game tasks such as fishing, thieving, woodcutting, travel, questing, and account setup. The scripts are designed to be used as external scripts in IdleRSC, where they can be hot-loaded and reloaded without rebuilding the client.

## Overview

RSC-Scripts is a curated personal library of automation scripts built for the OpenRSC/IdleRSC ecosystem. It includes both practical training scripts and utility scripts for account progression, navigation, and task automation.

The project is organized around the `scripts/` folder, which contains reusable Java classes, examples, and helper structures used by IdleRSC's external script loader.

## Features

- Fishing and gathering automation
- Thieving and stealth-based routines
- Woodcutting and resource collection scripts
- Travel and route-related scripts
- Questing and progression utilities
- New account setup helpers
- Example scripts and supporting code for external scripting

## Repository layout

```text
RSC-Scripts/
├── README.md
├── scripts/
│   ├── README.md
│   ├── README.txt
│   ├── ExampleScript.java
│   ├── NewAccountSetupScript.java
│   ├── F2PQuestGuide.java
│   ├── BensFishFarm.java
│   ├── BensArdyThiever.java
│   ├── BensSeersWoodcut.java
│   ├── BensTrade.java
│   ├── Thief2Ardougne.java
│   ├── Free Cloud Botting Tutorial.txt
│   ├── apos/
│   ├── common/
│   ├── examples/
│   ├── idlescript/
│   ├── sbot/
│   └── run_linux_console.sh
└── LICENSE (if added in future)
```

## Getting started

1. Open IdleRSC.
2. Navigate to the external scripts directory used by your client setup.
3. Place or edit the Java files in that folder.
4. Reload scripts from within IdleRSC.
5. Select the script you want to run and start it.

For most scripts in this repository, the expected workflow is the standard IdleRSC external script workflow: write or drop a Java class into the scripts folder, save it, then reload the script list in the client.

## Script categories

This repository includes a range of scripts for different play styles and account goals:

- Farm and resource scripts
- Utility and progression scripts
- Travel and movement bots
- Quest support and guidance scripts
- Example and template scripts for learning and modification

## Important notes

- This is a personal script collection and not a formal framework.
- Some files are highly specialized and may be tailored to a specific training route or account setup.
- Scripts may vary in quality, maintenance level, and suitability for your setup.
- Use scripts responsibly and ensure they comply with the rules and expectations of your environment.

## References

- `scripts/README.md` — IdleRSC external script usage and hot-loading guidance
- `scripts/README.txt` — quick reference notes
- `scripts/ExampleScript.java` — a template/example script to get started
- `scripts/Free Cloud Botting Tutorial.txt` — additional guidance and context

## License

This repository does not currently declare an explicit license. If you plan to reuse, redistribute, or modify the scripts, please check the contents of the files and confirm the appropriate permissions before doing so.

## Contributing

This repository is primarily a personal workspace for scripting experiments and utility automation. Improvements, refinements, and new scripts are welcome in spirit, but the project is not currently structured as a formal public contribution pipeline.
