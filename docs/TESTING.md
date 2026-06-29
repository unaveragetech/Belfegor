# Testing Belfegor

This file tracks the practical in-game regression tests used for the `1.21.4` Belfegor jar. These are intentionally command-level tests because most serious failures happen at the boundary between tasks, inventory screens, cursor state, and world interaction.

## Test environment

| Field | Value |
|---|---|
| Instance | MultiMC `1.21.4` test instance |
| Launcher shortcut | `C:\Users\b0052\Desktop\belfegor launch path MultiMC 5.lnk` |
| Mod jar | `.minecraft/mods/belfegor-1.21.4-beta1.jar` |
| Debug log | `.minecraft/belfegor/belfegor_debug.log` |
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

- `@shulker store [diamond 1, stick 2]` no longer produced the old repeated `SLOT-STUCK slot=28` loop.
- `@get diamond_shovel` retrieved from a catalogued carried shulker and crafted successfully.
- `@get composter` crafted successfully using birch and oak slabs together.
- `@get anvil` crafted successfully from ingots through iron blocks.
- `@player` generated base memory and spatial awareness.
- `@player` begins from a radius-8 home plan and can expand toward radius 18 over later passes.
- `@player` skips auto-shulker sorting while in HOME/build mode so base construction is not starved by inventory-pressure sorting.
- `@player` began clearing a modular base and wrote planned modules with room centers, inspection records, and progress counters.
- Shulker pickup timeout spam was fixed by targeting one dropped placed shulker and by releasing/recovering the transaction when a matching carried shulker is present.
- `#help sel` was verified in-game against the bundled Baritone command set and documented in [Baritone Command Reference](BARITONE_COMMAND_REFERENCE.md).
- `@craftaudit diamond_shovel` now starts from a clean inventory, gives `1 diamond`, `2 stick`, `1 crafting_table`, and `1 chest`, crafts through the real crafting-table flow, stores into a nearby overflow chest, and logs `DONE passed=1 failed=0`.
- Overflow chest storage ignores far stale cached containers during chest-only overflow, preventing the old path to distant cached chests such as underground structure loot.
- A clean staged showcase video was recorded at [docs/media/belfegor-showcase-20260628-v2.mp4](media/belfegor-showcase-20260628-v2.mp4).

## Known edge cases to keep testing

- Shulker placement can still choose awkward geometry where the block above is air but the bot cannot open the shulker reliably. Current behavior recovers by breaking and picking up the shulker; placement scoring should improve.
- Auto shulker mode can interrupt long base-building work when inventory pressure is high. The transaction is safe, but scheduling should become less jumpy.
- Some command smoke tests are intentionally shallow because destructive/world-changing commands need controlled worlds.
- Server inventories, lag, anticheat, and custom plugins are not covered by the local singleplayer matrix.

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
