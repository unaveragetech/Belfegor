# Testing Belfegor

This file tracks the practical in-game regression tests used for the `1.21.4` Belfegor jar. These are intentionally command-level tests because most serious failures happen at the boundary between tasks, inventory screens, cursor state, and world interaction.

## Test environment

| Field | Value |
|---|---|
| Instance | MultiMC `1.21.4` test instance |
| Launcher shortcut | `C:\Users\b0052\Desktop\belfegor launch path MultiMC 5.lnk` |
| Mod jar | `.minecraft/mods/belfegor-1.21.4-beta1.jar` |
| Debug log | `.minecraft/belfegor/belfegor_debug.log` |
| UI key/command | `C` or `@ui` |
| Abort key | `+` / Shift+`=` |

Before a reinstall test, close Minecraft and MultiMC, build the jar, copy it into the instance `mods` folder, clear `belfegor_debug.log`, launch, then run the command sequence.

## Inventory and shulker regression matrix

| Test | Setup | Expected result | Debug tags to inspect |
|---|---|---|---|
| Manual shulker store | `/give @s minecraft:diamond 1`, `/give @s minecraft:stick 2`, then `@shulker store [diamond 1, stick 2]` | Places shulker, opens it, transfers eligible items, catalogs twice, breaks, picks up, restores slot. | `SHULKER-STATE`, `SHULKER-TRANSFER`, `SLOT-FORCE`, `SLOT-RESULT` |
| Shulker-backed craft | Put diamond in carried shulker, keep sticks available, run `@get diamond_shovel` | Retrieves diamond from shulker before gathering, then crafts shovel. | `SHULKER-CHECK`, `SHULKER-TRANSFER retrieved`, `CRAFT-STATE` |
| Slot-level shulker catalogue | Put distinct items in different shulker slots, pick it up, let Belfegor idle or run `@shulker list` | `belfegor_shulker_memory.json` records `slots[]` with internal slot numbers, counts, free slots, and fingerprint before the shulker is opened. | `SHULKER-CATALOG`, `SHULKER-CHECK` |
| Mixed slab recipe | `/give @s minecraft:oak_slab 4`, `/give @s minecraft:birch_slab 3`, then `@get composter` | Uses both slab variants in one composter craft. | `CRAFT-STATE`, output `composter` |
| Nested craft | `/give @s minecraft:iron_ingot 31`, then `@get anvil` | Crafts iron blocks, then crafts anvil. | `CRAFT-STATE`, output `iron_block`, output `anvil` |
| Abort key | Start any long task, press `+` | Active task stops and inventory control returns. | Chat message `Automation aborted with +` |
| Player-mode shulker pressure | Seed a full inventory plus carried shulker, run `@player` | Auto shulker transactions complete or recover; lock is released. | `SHULKER-LOCK released`, no repeated `pickup timeout` spam |
| Jump-place shulker | Clear a 3x5 headroom column, carry a shulker, run `@shulker store diamond 1` | Shulker is placed directly under the player by jumping, then opened/used/recovered. | `SHULKER-PLACE jump-placed under player` |

## Player mode and base tests

Seed useful supplies:

```text
/difficulty peaceful
/time set day
/weather clear
/give @s minecraft:oak_log 64
/give @s minecraft:cobblestone 128
/give @s minecraft:dirt 128
/give @s minecraft:wheat_seeds 16
/give @s minecraft:crafting_table 1
/give @s minecraft:furnace 1
/give @s minecraft:chest 2
/give @s minecraft:shulker_box 1
@player
```

Expected behavior:

- `.minecraft/belfegor/belfegor_bases.json` is created or updated.
- `.minecraft/belfegor/belfegor_spatial_awareness.json` is created or updated.
- `@player` sets a home base and eventually starts `Build expandable base`.
- Base memory records a larger expandable base plan: four-high perimeter wall, five-block exterior clearance, interior room dividers, core room, crafting workshop, smelting workshop, storage wing, crop farm, and roofed mob-farm chamber with a two-wide entrance.
- Spatial awareness records nearby block counts, liquids, headroom, flat floor columns, entities, dropped items, and notable blocks.

## Current tested results

The latest local test pass verified:

- `@help ui`, `@status`, `@coords`, `@inventory`, and `@list` executed without command errors in the `1.21.4` test instance.
- `@status` is intercepted at chat-screen submit time and no longer falls through to Baritone as an unknown `@status` command in the heavily modded profile.
- `@baritone proc`, `@baritone help sel`, `@baritone sel clear`, and `@help baritone` were run in-game after rebuild/restart. The chat showed Belfegor responses and `belfegor_debug.log` recorded `BARITONE-CMD`/`BARITONE-PROC`.
- `@status` was re-smoked after the task-interrupt diagnostic patch. Idle state still prints `No tasks currently running`; after a future chain switch it will also show the last interrupt snapshot.
- `@status history 10` was run live after rebuild/reinstall and correctly reported `No task interruptions recorded this session` in a fresh idle test world. `@help status` also showed the new optional `mode` and `count` inputs plus `@status history` examples.
- The Tasks tab now renders an Interrupt History panel backed by the same in-session history as `@status history`. The heavily modded local profile still may not visually display the panel in captured output, but `@ui` was submitted live and `belfegor_debug.log` confirmed `UI-OPEN after-set currentScreen=adris.belfegor.ui.BelfegorScreen` and `visible=true`.
- `@ui` is registered and now calls the same shared screen-opening method used by the `C` keybind. Regression check: press `C`, close the menu, then run `@ui`; both should open the same Belfegor control panel without the generic screen-recovery chain closing it.
- the Macros tab code now provides create/save/reload/run/pause/stop/duplicate/delete/loop/add/remove/reorder controls and compiles cleanly.
- `@craftaudit all 5` passed after recipe-registry cleanup. The audit gave matching wood-family resources: acacia wood used acacia logs, birch wood used birch logs, dark oak wood used dark oak logs, oak wood used oak logs, and jungle wood used jungle logs.
- `@get cake` no longer produced a local scan storm in the sampled run. The bot advanced through normal dependencies and the recent log tail contained one `RESOURCE-LOCALITY` line while the Java process remained responsive.
- `@shulker store [diamond 1, stick 2]` no longer produced the old repeated `SLOT-STUCK slot=28` loop.
- `@get diamond_shovel` retrieved from a catalogued carried shulker and crafted successfully.
- `@get composter` crafted successfully using birch and oak slabs together.
- `@get anvil` crafted successfully from ingots through iron blocks.
- `@player` generated base memory and spatial awareness.
- `@player` begins from a radius-8 home plan and can expand toward radius 18 over later passes.
- `@build full [radius] [here]` serializes the complete base build: core camp, storage, workshop, hydrated crop farm, roofed mob-farm chamber, and route validation to remembered room centers.
- Full-base farm validation must start from a clean inventory, then give the bot at least one chest, one shulker box, four water buckets, seeds, a hoe, dirt, and cobblestone. The farm is not considered complete unless the room contains a 2x2 infinite water source before tilling/planting begins.
- `@player` skips auto-shulker sorting while in HOME/build mode so base construction is not starved by inventory-pressure sorting.
- `@player` began clearing a modular base and wrote planned modules with room centers, inspection records, and progress counters.
- Shulker pickup timeout spam was fixed by targeting one dropped placed shulker and by releasing/recovering the transaction when a matching carried shulker is present.
- `#help sel` was verified in-game against the bundled Baritone command set and documented in [Baritone Command Reference](BARITONE_COMMAND_REFERENCE.md).
- `@craftaudit diamond_shovel` now starts from a clean inventory, gives `1 diamond`, `2 stick`, `1 crafting_table`, and `1 chest`, crafts through the real crafting-table flow, stores into a nearby overflow chest, and logs `DONE passed=1 failed=0`.
- Overflow chest storage ignores far stale cached containers during chest-only overflow, preventing the old path to distant cached chests such as underground structure loot.
- A clean staged showcase video was recorded at [docs/media/belfegor-showcase-20260628-v2.mp4](media/belfegor-showcase-20260628-v2.mp4).

## Known edge cases to keep testing

- UI conflicts remain possible in heavily modded profiles if another mod consumes `C` or replaces screens. `@ui` should now behave like pressing `C`; if either path fails, inspect screen conflicts rather than command registration.
- Shulker placement can still choose awkward geometry where the block above is air but the bot cannot open the shulker reliably. Current behavior recovers by breaking and picking up the shulker; placement scoring should improve.
- Auto shulker mode can interrupt long base-building work when inventory pressure is high. The transaction is safe, but scheduling should become less jumpy.
- Some command smoke tests are intentionally shallow because destructive/world-changing commands need controlled worlds.
- Server inventories, lag, anticheat, and custom plugins are not covered by the local singleplayer matrix.

## UI and command-registry regression tests

Run this set after every UI/command change:

| Test | Command/action | Expected result |
|---|---|---|
| Key menu | Press `C` while no screen is open | Belfegor control panel opens. |
| Chat menu | Run `@ui` | Same control panel opens through the same `openScreen()` path as `C`. |
| Help registry | Run `@help ui`, `@help build`, `@help shulker`, `@help craftaudit` | Each command has category, description, inputs, and runnable examples. |
| Commands tab | Open UI -> Commands | Search matches command names, categories, and examples. Clicking runnable examples should load/copy usable command text. |
| Macros tab | Open UI -> Macros | New/save/reload/run/pause/stop/duplicate/delete/loop/add/remove/reorder controls respond without closing the screen. |
| Shulkers tab | Open UI -> Shulkers | Indexed carried/placed shulkers and slot-level contents appear if memory exists. |
| Schematics tab | Open UI -> Schematics | Imported/internal schematic entries list command-ready build/validate actions. |

## Native Baritone bridge tests

Run these after changing construction/pathing integration:

| Test | Command/action | Expected result |
|---|---|---|
| Process diagnostics | `@baritone proc` | Baritone prints process state and `belfegor_debug.log` records `BARITONE-PROC`. |
| Help passthrough | `@baritone help sel` | Native Baritone selection help appears in chat/log output. |
| Selection clear | `@baritone sel clear` | Current native Baritone selections are cleared without stopping Belfegor tasks. |
| Surface recovery | Put bot underground or underwater with blocks, then `@baritone surface` | Baritone starts native surface/top recovery. Use `@stop`/`@baritone forcecancel` if needed. |
| Build-region selection | Run a small `@build full 8 here` or room build | Debug log records `BARITONE-SEL select reason=build-region...` and `BARITONE-PROC reason=build-region-before-native-builder...`. |
| Clear-region selection | Run a base build that clears terrain | Debug log records `BARITONE-SEL select reason=clear-region` and `BARITONE-PROC reason=clear-region-before-native-cleararea`. |

## Log checklist

After each run, search for:

```text
SLOT-STUCK
INVMGR-STUCK
SHULKER-ERROR
Exception
StackOverflow
ERROR
```

Some `SHULKER-ERROR` lines are recoverable diagnostics, such as open retry limit recovery. Repeated unbounded lines are bugs.
