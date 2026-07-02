# Belfegor command reference

All commands use the prefix from `belfegor_settings.json`; by default this is `@`.

Item-list arguments accept one item, an item with a count, or a bracketed comma-separated list:

```text
diamond
diamond 3
[diamond 3, stick 16, oak_planks 64]
```

Coordinates accept several forms:

```text
100 64 -200
100 -200
64
nether
100 64 -200 overworld
```

## Core commands

| Command | Usage | What it does | Examples |
|---|---|---|---|
| `help` | `@help <command>` | Lists commands or shows detailed help for one command. | `@help`, `@help shulker` |
| `ui` | `@ui` | Opens the same Belfegor control panel as pressing `C`. | `@ui` |
| `stop` | `@stop` | Stops active automation. The global `+` key also aborts while a task is running. | `@stop` |
| `status` | `@status <mode> <count>` | Prints the current active task status, active task chain, and the last task-chain interrupt snapshot when available. Use `@status history` or `@status history 10` to show recent task-chain switches newest-first. | `@status`, `@status history`, `@status history 10` |
| `coords` | `@coords` | Prints the bot position. | `@coords` |
| `reload_settings` | `@reload_settings` | Reloads settings and butler whitelist/blacklist files. | `@reload_settings` |
| `gamma` | `@gamma <value=1.0>` | Sets brightness/gamma. | `@gamma 10` |
| `idle` | `@idle` | Stands still and clears the active task. | `@idle` |
| `inventory` | `@inventory <item>` | Prints inventory or a count for a specific item. | `@inventory`, `@inventory diamond` |
| `list` | `@list` | Lists obtainable/catalogued items. | `@list` |

## Resource, crafting, and storage commands

| Command | Usage | What it does | Examples |
|---|---|---|---|
| `get` | `@get [items]` | Gets requested items using inventory, dropped items, containers, shulkers, gathering, crafting, mining, and smelting. | `@get diamond 3`, `@get diamond_shovel`, `@get [crafting_table, stick 8]` |
| `toolset` | `@toolset [tier]` | Crafts a full tool set for `wood`, `stone`, `iron`, or `diamond`. | `@toolset iron`, `@toolset diamond` |
| `stacked` | `@stacked` | Gets a PvP loadout: diamond armor, diamond sword, shield, cooked beef, golden apples, planks, and ender pearls. | `@stacked` |
| `equip` | `@equip [armors]` | Gets and equips armor items. | `@equip diamond_helmet`, `@equip [diamond_helmet, diamond_chestplate]` |
| `food` | `@food [count]` | Collects a target amount of food value. | `@food 32` |
| `meat` | `@meat [count]` | Collects meat. | `@meat 32` |
| `store` | `@store [items]` | Stores requested items in the nearest usable container. | `@store diamond 3`, `@store [diamond 3, gold_ingot 8]` |
| `retrieve` | `@retrieve [items]` | Retrieves requested items from nearby/known containers. | `@retrieve diamond 3`, `@retrieve stick 8` |
| `deposit` | `@deposit <items>` | Deposits requested items, or all non-gear items when empty. | `@deposit`, `@deposit cobblestone 64` |
| `stash` | `@stash [x0] [y0] [z0] [x1] [y1] [z1] <items>` | Deposits into a container stash region. | `@stash 100 64 100 110 70 110 diamond 3` |

## Shulker commands

| Command | Usage | What it does | Examples |
|---|---|---|---|
| `shulker list` | `@shulker list` | Lists remembered carried and placed shulkers and their contents. | `@shulker list` |
| `shulker find` | `@shulker find [item]` | Searches remembered shulker memory for an item. | `@shulker find diamond` |
| `shulker store` | `@shulker store [items]` | Places a carried shulker, stores exact requested quantities, catalogs it, breaks it, and picks it up. | `@shulker store diamond 3`, `@shulker store [diamond 3, stick 16]` |
| `shulker retrieve` | `@shulker retrieve [items]` | Places a matching carried shulker and retrieves exact requested quantities. | `@shulker retrieve stick 8` |
| `shulker auto on` | `@shulker auto on` | Enables automatic shulker sorting. | `@shulker auto on` |
| `shulker auto off` | `@shulker auto off` | Disables automatic shulker sorting. | `@shulker auto off` |
| `shulker auto status` | `@shulker auto status` | Prints auto-shulker mode, timer, and detection settings. | `@shulker auto status` |
| `shulker auto run` | `@shulker auto run` | Immediately sorts eligible inventory items into carried shulkers. | `@shulker auto run` |
| `shulker auto timer` | `@shulker auto timer` | Uses periodic idle deposits. | `@shulker auto timer` |
| `shulker auto detection` | `@shulker auto detection` | Sorts when inventory fill/new items trigger detection. | `@shulker auto detection` |

See [Shulker management](SHULKER_MANAGEMENT.md) for exact behavior and exclusions.

## Movement and world commands

| Command | Usage | What it does | Examples |
|---|---|---|---|
| `goto` | `@goto [coordinates]` | Travels to coordinates or dimension. | `@goto 100 64 -200`, `@goto nether` |
| `follow` | `@follow <username>` | Follows a player. If sent through Butler, default can be the sender. | `@follow Steve` |
| `locate_structure` | `@locate_structure [structure]` | Locates supported structures such as strongholds/desert temples. | `@locate_structure stronghold` |
| `coverwithsand` | `@coverwithsand` | Covers nether lava with sand. | `@coverwithsand` |
| `coverwithblocks` | `@coverwithblocks` | Covers nether lava with blocks. | `@coverwithblocks` |

## Combat and autonomous modes

| Command | Usage | What it does | Examples |
|---|---|---|---|
| `pvp` | `@pvp [playerName]` | Gears up, hunts, heals, and repeats against a player. | `@pvp Steve` |
| `punk` | `@punk [playerName]` | Attacks a specific player. | `@punk Steve` |
| `hero` | `@hero` | Kills nearby hostile mobs. | `@hero` |
| `selfcare` | `@selfcare` | Experimental self-care/survival helper. | `@selfcare` |
| `player` | `@player` | Starts autonomous exploration/learning/home-base mode. | `@player` |
| `build` | `@build full [radius] [here]`, `@build validate`, `@build repair`, or `@build <roomType> [name]` | Builds the complete modular base, validates/repairs remembered rooms, or expands the remembered base with one connected room. Room placement avoids overlapping remembered footprints. Full mode builds/validates core camp, storage, workshop, hydrated crop farm, and roofed mob-farm room. | `@build full 12 here`, `@build repair`, `@build farmland wheat_wing` |
| `home` | `@home [room]` | Navigates to the remembered camp center or named room/module center. | `@home farmland` |
| `baritone` | `@baritone <safe native command>` | Runs a controlled subset of native Baritone diagnostics/selection/build commands through Baritone's command manager. Useful for `#proc`, `#help sel`, `#sel clear`, `#surface`, `#forcecancel`, `#build`, and `#litematica` testing. | `@baritone proc`, `@baritone help sel`, `@baritone sel clear` |
| `gamer` | `@gamer` | Runs the classic beat-the-game task. | `@gamer` |
| `marvion` | `@marvion` | Runs the Marvion beat-the-game route. | `@marvion` |

## Utility and testing

| Command | Usage | What it does | Examples |
|---|---|---|---|
| `give` | `@give <username> [item] <count=1>` | Gets an item and gives it to a player. | `@give Steve diamond 3` |
| `ai` | `@ai <prompt>` | Queues or polls the Packaged llama.cpp advisor. It reads the command catalogue, context snapshot, inventory, shulker memory, goal, and action log, then returns chat text and/or a safe command suggestion. Defaults to `belfegor/models/Qwen3-1.7B-Q4_K_M.gguf`. | `@ai "what should I do next?"`, `@ai "why am I stuck?"` |
| `test` | `@test <extra>` | Runs experimental test tasks. | `@test stacked` |
| `craftaudit` | `@craftaudit <target=all> <limit=0>` | Developer-only recipe audit. Starts each item from a clean inventory, gives bundled-recipe leaf resources, crafts through Belfegor, stores outputs in ordinary containers, and logs pass/skip/fail results. Requires cheats/op in a test world. | `@craftaudit anvil`, `@craftaudit all 25` |

`@craftaudit` writes logs to:

```text
.minecraft/belfegor/craft_audit_*.log
```

Use `@craftaudit all 25` for a small batch before running the full catalogue. The command intentionally exercises Belfegor's real crafting logic after resources are given, so failures are useful for fixing recipe, inventory, and storage bugs. Targets without a craftable recipe/task are skipped instead of being counted as recipe failures.

`@ai` uses local llama.cpp. Enable it in `belfegor_settings.json` with `llmAdvisorEnabled=true`. See [Local llama.cpp LLM advisor](LLM_ADVISOR.md).

## Command chaining

Multiple commands can be chained with semicolons:

```text
@get iron_axe; get oak_log 64; goto 0 64 0; give Steve oak_log 64
```

## UI command page

Press `C` or run `@ui`, open the command tab, select a command, and double-click an example to run it. Both menu paths use the same `openScreen()` implementation. The UI shows categories, expected argument values, detailed descriptions, and examples from the same command metadata used by `@help` and the local LLM advisor.

The command list can be searched by command name, category, description, or usage text. Categories include Reference, Control, Resources, Crafting, Storage, Navigation, Base, Autonomy, PvP, Combat, Survival, Nether, Client, Development, and Game completion.

## Native Baritone bridge

Belfegor prefers Java API calls for repeatable automation, but some native Baritone commands are useful for diagnostics, recovery, and schematic/interoperability testing. `@baritone` routes a safe subset through Baritone's command manager without exposing an unrestricted chat-command tunnel.

Useful examples:

```text
@baritone proc
@baritone help sel
@baritone sel clear
@baritone surface
@baritone forcecancel
@baritone build house 100 64 100
@baritone litematica
```

The base builder also uses native Baritone APIs internally:

- `BuildRegionSchematicTask` uses Baritone's builder process for whole-region schematic placement.
- `ClearRegionTask` uses Baritone's native clear-area builder operation for rectangular clearance.
- Both tasks now select their active region through Baritone's selection manager and log `BARITONE-SEL`/`BARITONE-PROC` diagnostics so stuck builds can be inspected with `@baritone proc`.

