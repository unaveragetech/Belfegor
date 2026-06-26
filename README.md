# Belfegor

Belfegor is a Fabric client-side Minecraft automation mod for **Minecraft 1.21.4**. It is a production-focused fork and evolution of AltoClef/Baritone-style task automation with stronger inventory handling, safer crafting, managed shulker-box sub-inventories, PvP loadout automation, an in-game command UI, persistent memory, and better debug logs for diagnosing hard inventory/crafting failures.

The goal is simple: give a Minecraft client an extensible task system that can gather resources, craft items, manage storage, survive, travel, and execute higher-level routines from compact commands such as `@get diamond_shovel`, `@stacked`, `@shulker store diamond 3`, or `@player`.

## Current release

- Minecraft: `1.21.4`
- Mod version: `1.21.4-beta1`
- Built jar: [`releases/belfegor-1.21.4-beta1.jar`](releases/belfegor-1.21.4-beta1.jar)
- Jar SHA256: `C3B24C02E960F059686D1B779B998F6680413945424A153BF97684DD775D85F1`
- Mod id: `belfegor`
- Default command prefix: `@`
- In-game UI key: `C`
- Global abort key while a task is running: `+`

## What Belfegor does

Belfegor runs a stack of Minecraft automation tasks:

- Gets resources and crafted items with `@get`, including multi-step recipes.
- Crafts in the player inventory and crafting tables with cursor recovery and transaction guards.
- Uses containers and carried shulker boxes as extended storage.
- Catalogs shulker contents and can retrieve needed crafting supplies from them.
- Sorts non-tool inventory items into shulkers in automatic timer or detection modes.
- Runs PvP preparation commands such as `@stacked`, `@toolset`, and `@pvp`.
- Provides `@player`, an autonomous exploration/learning mode that sets a home base, builds a small campsite, gathers resources, practices crafts, and uses shulkers as sub-inventories.
- Exposes a richer `C` UI with task state, command help, settings, logs, and shulker memory.
- Writes detailed debug logs for inventory/crafting/shulker problems.

## How it works

Belfegor is built around composable tasks:

1. A command parses user intent, for example `@get diamond_shovel`.
2. The task catalogue resolves the requested item into a resource/crafting task.
3. Resource tasks prefer nearby dropped items, inventory, carried shulkers, remembered containers, then mining/crafting.
4. Crafting tasks collect missing recipe materials, open the correct grid, move ingredients, receive output, and recover the cursor.
5. Shulker transactions place a carried shulker, ensure the block above is open, transfer exact quantities, catalog contents twice, close it, mine it, pick it up, and restore it to its original inventory slot when possible.
6. Memory systems persist useful state such as shulker contents, learned crafting paths, and home-base locations.

The key design rule is that inventory transactions should be atomic: once Belfegor starts moving items in a shulker/container/crafting UI, other tasks should not interrupt it mid-click and leave the cursor stuck.

## Install

1. Install Fabric Loader for Minecraft `1.21.4`.
2. Install Fabric API for Minecraft `1.21.4`.
3. Put [`releases/belfegor-1.21.4-beta1.jar`](releases/belfegor-1.21.4-beta1.jar) in your `.minecraft/mods` folder.
4. Ensure your environment has the compatible Baritone/Fabric API setup used by this project.
5. Launch Minecraft once. Belfegor creates its config folder at:

   ```text
   .minecraft/belfegor/
   ```

Important generated files:

- `belfegor_settings.json` — user settings.
- `belfegor_debug.log` — detailed runtime diagnostics.
- `belfegor_shulker_memory.json` — remembered shulker-box contents.

## Quick start

Open chat and run commands with `@`:

```text
@help
@help shulker
@get crafting_table
@get diamond_shovel
@toolset iron
@stacked
@shulker list
@shulker store diamond 3
@shulker retrieve stick 8
@shulker auto on
@player
@stop
```

Press `C` to open the Belfegor UI. The command tab includes real examples that can be run by double-clicking them.

Press `+` while a task is running to globally abort the active automation.

## Documentation

- [Full command reference](docs/COMMANDS.md)
- [Shulker-box management](docs/SHULKER_MANAGEMENT.md)
- [Settings and generated files](docs/CONFIGURATION.md)
- [Build and development guide](docs/DEVELOPMENT.md)
- [Roadmap](docs/ROADMAP.md)

## Production assets

- Built jar: [`releases/belfegor-1.21.4-beta1.jar`](releases/belfegor-1.21.4-beta1.jar)
- Mod icon: [`src/main/resources/assets/belfegor/icon.png`](src/main/resources/assets/belfegor/icon.png)
- Fabric metadata: [`src/main/resources/fabric.mod.json`](src/main/resources/fabric.mod.json)
- Mixin config: [`src/main/resources/belfegor.mixins.json`](src/main/resources/belfegor.mixins.json)
- Recipe registry data: [`src/main/resources/belfegor_recipes.json`](src/main/resources/belfegor_recipes.json)

## Build

Requirements:

- Java 21
- Gradle wrapper from this repo
- Minecraft/Fabric dependencies from `gradle.properties`
- Local Baritone API jar at `../baritone/dist/baritone-api.jar` for development builds

Build:

```powershell
.\gradlew.bat build
```

The remapped jar is produced at:

```text
build/libs/belfegor-1.21.4-beta1.jar
```

## Project status

Belfegor is beta software. Core command execution, resource gathering, crafting, UI, shulker storage, and PvP preparation are active. The hardest ongoing area is Minecraft inventory correctness: cursor state, slot mappings, screen transitions, shulker NBT, container sync, and task interruption. The current code includes extensive debug logging to make those failures diagnosable.

## Credits

Belfegor builds on the AltoClef-style Minecraft automation architecture and Baritone pathing concepts, with additional Belfegor-specific work around Minecraft `1.21.4`, managed shulkers, UI, crafting stability, PvP tooling, autonomous player mode, and debugging.

## License

MIT. See [LICENSE](LICENSE).
