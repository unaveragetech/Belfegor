# Baritone Command Reference for Belfegor Development

This file records the Baritone commands exposed by the bundled local Baritone jar used during Belfegor testing:

`C:\Users\b0052\Desktop\baritone\dist\baritone-unoptimized-fabric-1.13.1.jar`

The commands below are available through Baritone chat control with the `#` prefix, for example `#help`, `#surface`, or `#sel clear`. Belfegor code should prefer Baritone's Java APIs for repeatable automation, but these commands are useful for in-game research, debugging, and manual recovery.

## Command discovery

| Command | Use |
| --- | --- |
| `#help` | Lists available Baritone commands. |
| `#help <command>` | Shows detailed help for one command, for example `#help sel`. |
| `#proc` | Shows active Baritone processes. Useful when Belfegor appears to have overlapping movement/build goals. |
| `#eta` | Shows estimated time for the active path/process when available. |
| `#version` | Prints Baritone version/build information. |
| `#set` | Lists/settings interface for Baritone settings. |
| `#set <setting> <value>` | Changes a Baritone setting for the current client. Use carefully during tests and document non-default settings. |

## Movement and navigation

| Command | Use |
| --- | --- |
| `#goto <x> <y> <z>` | Path to a coordinate. Supports relative coordinates such as `~ ~ ~`. |
| `#goto <x> <z>` | Path to an X/Z goal when the datatype parser accepts it. |
| `#goto <block>` | Path to the nearest matching block, for example `#goto chest`. |
| `#goal <x> <y> <z>` | Set a goal without necessarily starting a path. |
| `#path` | Start pathing to the current goal. |
| `#come` | Path to the player/camera target depending on context. Handy for manual recovery. |
| `#thisway <distance>` | Continue in the direction the player is facing for a distance. |
| `#surface` / `#top` | Path upward to the closest surface-like air space. Useful when base-building tests strand the bot underground. |
| `#follow <entity/player>` | Follow a player/entity target. |
| `#forcecancel` | Force-cancel Baritone's active work. Use when `#proc` shows a stale process. |

## Resource and world interaction

| Command | Use |
| --- | --- |
| `#mine <block...>` | Mine one or more block types. Example: `#mine cobblestone`. |
| `#farm` | Farm supported crops in the nearby area. |
| `#pickup <item...>` | Pick up nearby dropped items. |
| `#find <block...>` | Locate known cached blocks. |
| `#tunnel` | Dig a tunnel using Baritone's tunnel process. |
| `#explore` | Explore new chunks/terrain. |
| `#explorefilter` | Configure explore filtering. |
| `#blacklist` | Manage blacklisted blocks/locations for current Baritone processes. |

## Selection and area operations

The `#sel` command is the most relevant command family for future base-building work. The local class exposes aliases `#sel`, `#selection`, and `#s`.

| Command | Use |
| --- | --- |
| `#sel pos1` | Set selection position 1 to the viewer/player position. |
| `#sel pos1 <x> <y> <z>` | Set selection position 1 explicitly. |
| `#sel pos2` | Set selection position 2 and add the selection. `pos1` must be set first. |
| `#sel pos2 <x> <y> <z>` | Set selection position 2 explicitly and add the selection. |
| `#sel clear` | Clear all selections. |
| `#sel undo` | Undo the last selection step. |
| `#sel cleararea` | Clear selected area to air. This maps conceptually to Belfegor's `ClearRegionTask`. |
| `#sel set <block>` | Fill selected area with a block. |
| `#sel replace <from...> <to>` | Replace matching blocks inside the selection. |
| `#sel walls <block>` | Build/fill the walls of a selection. |
| `#sel shell <block>` | Fill the shell/perimeter of a selection. |
| `#sel expand <direction> <amount>` | Expand the current selection. |
| `#sel contract <direction> <amount>` | Contract the current selection. |
| `#sel shift <direction> <amount>` | Move the current selection. |
| `#sel copy` / `#sel paste` | Clipboard-style schematic operations, when available. |

For Belfegor base building, the equivalent internal pattern is:

1. Compute the room/hall bounding boxes.
2. Use `ClearRegionTask`, which calls Baritone builder clearing for the full region.
3. Generate floor, wall, roof, and fixture target lists.
4. Use `BuildRegionSchematicTask`, which calls Baritone's builder process with one in-memory schematic per phase.
5. Save the result into `BaseMemory` and `LocationMemory` so `@home <room>` can route to the remembered center.

## Schematic/build commands

| Command | Use |
| --- | --- |
| `#build <schematic>` | Load a schematic from the Minecraft `schematics` folder and build it at the current feet position. |
| `#build <schematic> <x> <y> <z>` | Build a schematic at an explicit origin. |
| `#schematica` | Schematica integration command where supported. |
| `#litematica` | Litematica integration command where supported. |

Belfegor does not need on-disk schematics for ordinary campsite rooms. It builds generated in-memory schematics for floors, walls, roofs, and halls so every room can be derived from base memory and room type.

## Render, cache, and maintenance

| Command | Use |
| --- | --- |
| `#render` | Toggle/query Baritone rendering settings. |
| `#reloadall` | Reload Baritone caches/settings where supported. |
| `#saveall` | Save Baritone cache data. |
| `#gc` | Request Java garbage collection. Useful only for diagnostics. |
| `#repack` | Repack Baritone cached world data. |
| `#axis` | Axis helper command. |
| `#click` | Click helper/debug command. |
| `#invert` | Invert selection/goal-related state where supported. |
| `#elytra` | Elytra pathing command where supported. |

## Belfegor base-building policy

Belfegor's campsite system should not place one block, walk away, then come back for another block. That pattern indicates overlapping small goals. The intended construction policy is:

- clear the full room and hall space first;
- place the floor as one generated schematic phase;
- place the wall shell as one generated schematic phase;
- place the roof as one generated schematic phase when the room type needs a roof, such as `mob_farm`;
- place fixtures after the shell is done;
- only then perform farmland-specific steps such as water placement, tilling, and planting;
- remember every module center, hall, direction, dimensions, and status in base memory.

For test recovery, use `#proc` to inspect active Baritone processes and `#forcecancel` if a stale Baritone process survives after Belfegor has stopped.
