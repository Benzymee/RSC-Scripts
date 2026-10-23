# RSC-Scripts

A collection of Java scripts for IdleRSC/OpenRSC automation, focused on practical in-game tasks such as fishing, thieving, woodcutting, travel, and account setup.

This repository is a personal script library for running and experimenting with botting workflows in the OpenRSC/IdleRSC ecosystem. The scripts are designed to be loaded into the external scripts folder used by IdleRSC, allowing them to be reloaded and run without rebuilding the client.

## What is in this repository?

The project includes a variety of utility and leveling scripts, including:

- Fishing scripts for efficient resource gathering
- Thieving scripts for stealth-based money or item generation
- Woodcutting scripts for training and resource collection
- Travel and routing helpers such as Ardougne and Sarim movement scripts
- New-account setup and questing utility scripts
- General automation examples and helper classes

The scripts live under the `scripts/` directory and are meant to be used as external scripts in IdleRSC.

## Repository structure

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
│   ├── ...
│   ├── common/
│   ├── examples/
│   ├── idlescript/
│   ├── apos/
│   ├── sbot/
│   └── run_linux_console.sh
└── ...
```

## How to use

1. Open your IdleRSC installation.
2. Place or edit scripts in the external scripts directory used by the client.
3. Reload the script list in IdleRSC.
4. Start the desired script from the script selector.

For scripts that are meant to be hot-loaded, the project follows the standard IdleRSC external script workflow: add Java files, save them, and reload from within the client.

## Notes

- This is a personal collection and not a polished public framework.
- Scripts may be tailored to specific training routes, account goals, or playstyles.
- Some files may be experiments, utilities, or examples rather than production-ready scripts.
- Use responsibly and in line with the rules of your game environment.

## Recommended references

- `scripts/README.md` for IdleRSC external script usage details
- `scripts/README.txt` for a shorter usage note
- `scripts/ExampleScript.java` for a template/example script

## License

This repository does not currently declare a specific license. If you plan to reuse or redistribute scripts, check the individual files and contact the author before doing so.
