# Baritone Command Reference for Belfegor Development

This file records the Baritone commands exposed by the live Minecraft 1.21.4 test instance. It was refreshed from in-game `#help` output on 2026-06-29.

Observed runtime:

- Minecraft: `1.21.4`
- Baritone: `1.11.1-2-ga0f100f4`
- Prefix: `#`, for example `#help`, `#proc`, `#sel cleararea`

Belfegor should prefer Baritone Java APIs for repeatable automation, but these chat commands are useful for live research, manual recovery, and mapping future base-builder features onto native Baritone capabilities. Belfegor exposes a controlled bridge as `@baritone ...`, which runs a safe subset through Baritone's command manager without requiring the user to type raw `#` commands.

## Full command catalogue

Live `#help` returned 6 pages:

| Page | Commands |
| --- | --- |
| 1/6 | `help`, `set`, `modified`, `reset`, `goal`, `goto`, `path`, `proc` |
| 2/6 | `eta`, `version`, `repack`, `build`, `litematica`, `come`, `axis`, `forcecancel` |
| 3/6 | `gc`, `invert`, `tunnel`, `render`, `farm`, `follow`, `explorefilter`, `reloadall` |
| 4/6 | `saveall`, `explore`, `blacklist`, `find`, `mine`, `click`, `surface`, `thisway` |
| 5/6 | `waypoints`, `sethome`, `home`, `sel`, `elytra`, `pause`, `resume`, `paused` |
| 6/6 | `cancel` |

## Command discovery and process control

| Command | Aliases | Expected values / usage | Notes for Belfegor |
| --- | --- | --- | --- |
| `#help` |  | `#help`; `#help <command>`; `#help <page>` | Canonical live help. Use this when Baritone behavior changes. |
| `#proc` |  | `#proc` | Shows the active Baritone process. Use when Belfegor appears to have overlapping movement/build goals. |
| `#eta` |  | `#eta` | Shows current ETA when a path/process exists. |
| `#version` |  | `#version` | Prints Baritone version information. |
| `#pause` | `#p`, `#paws` | `#pause` | Pauses active pathing/building/following. |
| `#resume` | `#r`, `#unpause`, `#unpaws` | `#resume` | Resumes a paused process. |
| `#paused` |  | `#paused` | Reports whether Baritone is paused. |
| `#cancel` | `#c`, `#stop` | `#cancel` | Normal cancel. |
| `#forcecancel` |  | `#forcecancel` | Stronger cancel. Use for stale builder/pathing state before rerunning a Belfegor phase. |

## Settings and cache commands

| Command | Aliases | Expected values / usage | Notes |
| --- | --- | --- | --- |
| `#set` | `#setting`, `#settings` | `#set`; `#set list [page]`; `#set <setting>`; `#set <setting> <value>`; `#set toggle <setting>`; `#set save`; `#set load [filename]` | Almost all Baritone behavior is setting-driven. Document any non-default setting used in tests. |
| `#modified` | `#mod`, `#baritone`, `#modifiedsettings` | Alias for `#set modified ...` | Lists modified settings. |
| `#reset` |  | Alias for `#set reset ...`; `#set reset all`; `#set reset <setting>` | Reset carefully during tests. |
| `#repack` | `#rescan` | `#repack` | Re-caches chunks around the player. |
| `#reloadall` |  | `#reloadall` | Reloads Baritone's cache for this world. |
| `#saveall` |  | `#saveall` | Saves Baritone's cache for this world. |
| `#gc` |  | `#gc` | Calls `System.gc()`. Diagnostic only. |
| `#render` |  | `#render` | Fixes glitched chunk rendering. |

## Navigation and goals

| Command | Aliases | Expected values / usage | Notes for Belfegor |
| --- | --- | --- | --- |
| `#goal` |  | `#goal`; `#goal reset/clear/none`; `#goal <y>`; `#goal <x> <z>`; `#goal <x> <y> <z>` | Sets a goal without necessarily starting pathing. Coordinates accept `~`. |
| `#path` |  | `#path` | Starts pathing toward the current goal. |
| `#goto` |  | `#goto <block>`; `#goto <y>`; `#goto <x> <z>`; `#goto <x> <y> <z>` | Direct path command. Coordinates accept `~`. |
| `#come` |  | `#come` | Heads toward the camera; useful with freecam/manual recovery. |
| `#axis` | `#highway` | `#axis` | Goals nearest axis where `X=0` or `Z=0`. |
| `#invert` |  | `#invert` | Runs away from the current goal. |
| `#surface` | `#top` | `#surface`; `#top` | Heads toward closest surface-like/highest air space. Useful when a bot is trapped underground or inside a bad build. |
| `#thisway` | `#forward` | `#thisway <distance>` | Creates a `GoalXZ` in the current look direction. |
| `#follow` |  | `#follow entities`; `#follow entity <entity1> <entity2> ...`; `#follow players`; `#follow player <username1> <username2> ...` | Follow entity/player targets. |
| `#elytra` |  | `#elytra`; `#elytra reset`; `#elytra repack`; `#elytra supported` | Nether elytra pathing to the current goal when native support exists. |

## Resource and world interaction

| Command | Aliases | Expected values / usage | Notes for Belfegor |
| --- | --- | --- | --- |
| `#mine` |  | `#mine <block>`; example `#mine diamond_ore` | Searches for and mines individual blocks. Help points to legit mine settings via `#set l legitMine`. |
| `#find` |  | `#find <block> [...]` | Searches Baritone cache. Tab completion only suggests cached blocks; uncached blocks cannot be found. |
| `#farm` |  | `#farm`; `#farm <range>`; `#farm <range> <waypoint>` | Harvests mature crops and replants. Future Belfegor farm-room maintenance can use this within remembered farm bounds. |
| `#tunnel` |  | `#tunnel`; `#tunnel <height> <width> <depth>` | Mines straight in look direction. Default is 1x2. |
| `#explore` |  | `#explore`; `#explore <x> <z>` | Random exploration from current or specified X/Z. |
| `#explorefilter` |  | `#explorefilter <path> [invert]` | JSON format: `[{"x":0,"z":0},...]`. With `invert`, listed chunks are treated as not explored. |
| `#blacklist` |  | `#blacklist` | While going to a block, blacklists the closest block so Baritone will not attempt it again. |
| `#click` |  | `#click` | Opens Baritone click helper/debug UI. |

## Waypoints

| Command | Aliases | Expected values / usage | Notes |
| --- | --- | --- | --- |
| `#waypoints` | `#waypoint`, `#wp` | `#wp list`; `#wp list <tag>`; `#wp save`; `#wp save [tag] [name] [pos]`; `#wp info <tag/name>`; `#wp delete <tag/name>`; `#wp restore <n>`; `#wp clear <tag>`; `#wp goal <tag/name>`; `#wp goto <tag/name>` | Waypoints have a tag and optional name. Missing save args default to a `USER` waypoint at current position. |
| `#sethome` |  | Alias for `#waypoints save home ...` | Sets a Baritone home waypoint. Separate from Belfegor `@home` memory. |
| `#home` |  | Alias for `#waypoints goto home ...` | Paths to Baritone home waypoint. |

## Selection and area operations

The `#sel` command is the most important live Baritone command family for base-building research. It exposes aliases `#sel`, `#selection`, and `#s`.

Live help notes:

- Selections are “WorldEdit-like”.
- Selections can clear areas, fill blocks, build walls/shells, copy, paste, and reshape regions.
- `expand`, `contract`, and `shift` target selectors support `a/all`, `n/newest`, and `o/oldest`.

| Command | Aliases | Expected values / usage | Belfegor mapping |
| --- | --- | --- | --- |
| `#sel pos1` | `p1`, `1` | `#sel pos1`; `#sel pos1 <x> <y> <z>` | Set selection position 1 to current or relative position. |
| `#sel pos2` | `p2`, `2` | `#sel pos2`; `#sel pos2 <x> <y> <z>` | Set selection position 2 to current or relative position. |
| `#sel clear` | `c` | `#sel clear` | Clear the selection. |
| `#sel undo` | `u` | `#sel undo` | Undo last selection action. |
| `#sel set` | `fill`, `s`, `f` | `#sel set [block]` | Fill all selections with a block. Conceptually maps to generated floor/fill phases. |
| `#sel walls` | `w` | `#sel walls [block]` | Fill walls only. Conceptually maps to perimeter/room wall phases. |
| `#sel shell` | `shl` | `#sel shell [block]` | Fill walls, ceiling, and floor. Useful for mob-room shells. |
| `#sel sphere` | `sph` | `#sel sphere [block]` | Fill selected bounds with a sphere. Not currently used by base builder. |
| `#sel hsphere` | `hsph` | `#sel hsphere [block]` | Hollow sphere. |
| `#sel cylinder` | `cyl` | `#sel cylinder [block] <axis>` | Cylinder bounded by selection; default axis is `y`. |
| `#sel hcylinder` | `hcyl` | `#sel hcylinder [block] <axis>` | Hollow cylinder. |
| `#sel cleararea` | `ca` | `#sel cleararea` | Equivalent to `set air`. Conceptually maps to `ClearRegionTask`. |
| `#sel replace` | `r` | `#sel replace <blocks...> <with>` | Replace matching blocks inside selection. |
| `#sel copy` | `cp` | `#sel copy <x> <y> <z>` | Copy selected area relative to specified/current position. |
| `#sel paste` | `p` | `#sel paste <x> <y> <z>` | Build copied area relative to specified/current position. |
| `#sel expand` |  | `#sel expand <target> <direction> <blocks>` | Useful model for deriving clearance volumes from room/hall boxes. |
| `#sel contract` |  | `#sel contract <target> <direction> <blocks>` | Resize target selection inward. |
| `#sel shift` |  | `#sel shift <target> <direction> <blocks>` | Move target selection without resizing. |

## Schematic/build commands

| Command | Expected values / usage | Notes |
| --- | --- | --- |
| `#build` | `#build <filename>`; `#build <filename> <x> <y> <z>` | Loads and builds `<filename>.schematic`, optionally at custom origin. |
| `#litematica` | `#litematica`; `#litematica <#>` | Builds the currently loaded Litematica schematic. |

Belfegor now saves an internal on-disk blueprint for the core campsite at `.minecraft/belfegor/schematics/base_core_<dimension>_<x>_<y>_<z>.belfegor_schematic.json`. Validation loads that file and checks every expected block against the world before marking the core complete.

Current code uses generated in-memory Baritone schematics through `BuildRegionSchematicTask` for execution. The saved blueprint is the validation/source-of-truth layer; Baritone's `#build` and `#litematica` command families are exposed for diagnostics/interoperability through `@baritone build ...` and `@baritone litematica`.

`BuildRegionSchematicTask` and `ClearRegionTask` now also set native Baritone selections for active regions. This makes construction work visible/debuggable with Baritone's selection tools while the actual repeatable operation remains owned by Belfegor's task system.

## Belfegor base-building policy

Belfegor's campsite system should not place one block, walk away, then come back for another block. That pattern indicates overlapping small goals or stale Baritone processes. The intended construction policy is:

1. Run supply preflight without placing overflow storage into unbuilt terrain.
2. Build the central campsite/core first.
3. Place or reuse the central construction staging chest inside the campsite storage anchor.
4. Free inventory into that central chest only after the floor/walls exist.
5. Clear the full room/hall space before placing new room blocks.
6. Place floor, walls, and roof as generated schematic phases.
7. Place fixtures after the shell is done.
8. For farmland, dig/fill the 2x2 water source first, then till only hydrated soil, then plant.
9. Save every module center, hall, direction, dimensions, and status in `BaseMemory` and `LocationMemory`.

For test recovery, use `@baritone proc` to inspect active Baritone processes and `@baritone forcecancel` if a stale process survives after Belfegor has stopped.
