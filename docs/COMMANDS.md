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
| `stop` | `@stop` | Stops active automation. The global `+` key also aborts while a task is running. | `@stop` |
| `status` | `@status` | Prints the current active task status. | `@status` |
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
| `gamer` | `@gamer` | Runs the classic beat-the-game task. | `@gamer` |
| `marvion` | `@marvion` | Runs the Marvion beat-the-game route. | `@marvion` |

## Utility and testing

| Command | Usage | What it does | Examples |
|---|---|---|---|
| `give` | `@give <username> [item] <count=1>` | Gets an item and gives it to a player. | `@give Steve diamond 3` |
| `test` | `@test <extra>` | Runs experimental test tasks. | `@test stacked` |
| `craftaudit` | `@craftaudit <target=all> <limit=0>` | Developer-only recipe audit. Uses bundled offline recipe data, `/give @s` leaf resources, crafts through Belfegor, stores outputs, and logs pass/fail results. Requires cheats/op in a test world. | `@craftaudit anvil`, `@craftaudit all 25` |

`@craftaudit` writes logs to:

```text
.minecraft/belfegor/craft_audit_*.log
```

Use `@craftaudit all 25` for a small batch before running the full catalogue. The command intentionally exercises Belfegor's real crafting logic after resources are given, so failures are useful for fixing recipe, inventory, and storage bugs.

## Command chaining

Multiple commands can be chained with semicolons:

```text
@get iron_axe; get oak_log 64; goto 0 64 0; give Steve oak_log 64
```

## UI command page

Press `C`, open the command tab, select a command, and double-click an example to run it. The UI shows expected argument values, detailed descriptions, and examples from the same command metadata used by `@help`.
