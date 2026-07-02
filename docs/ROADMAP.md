# Belfegor roadmap

This roadmap is intentionally practical. Belfegor’s main challenge is not “add more commands”; it is making the agent reliable enough that large commands can run for a long time without poisoning the cursor, losing inventory state, or getting trapped in a task loop.

## Near term

- Resolve the remaining visual UI display conflict observed in the heavily modded local profile: `@ui` executes, but the panel may not become visible when other overlay/screen mods are active.
- Re-test the `C`/`@ui` panel in a clean Fabric profile and capture a final UI screenshot/video once stable.
- Continue hardening inventory cursor recovery in every container/crafting path.
- Add more structured shulker transaction tests and debug summaries.
- Improve `@get` planning so carried shulker resources are chosen before gathering duplicates.
- Expand the `C`/`@ui` settings controls for auto-shulker thresholds and timers.
- Add command-level progress estimates and clearer failure reasons.
- Continue improving task oscillation diagnostics. Current builds keep a bounded in-session interruption history, show the last switch in `@status`, and expose a newest-first timeline through `@status history`; next step is showing repeated interrupt pairs directly in the UI.

## Crafting and resources

- Broaden recipe coverage for Minecraft `1.21.4`.
- Continue improving mixed-material recipe handling. Wood-family aliases now resolve contextually for tested recipes, but stone variants, dyes, and more complex tags still need broader validation.
- Add safer fallback behavior when a recipe is known but one ingredient source fails.
- Record successful crafting routes into persistent memory and prefer faster routes later.
- Separate “item visible in a container” from “item available in inventory right now.”
- Make recipe planning inventory-strict at the point of actual crafting.

## Automated catalogue of all craftable items

Long-term, Belfegor should be able to automate the catalogue and collection of every craftable item it can legally obtain in the current world.

Planned phases:

1. **Recipe ingestion**
   - Load all bundled Minecraft recipes from `belfegor_recipes.json`.
   - Parse shaped and shapeless recipes.
   - Index by output and by ingredient.

2. **Ingredient normalization**
   - Convert recipe alternatives into groups.
   - Support tags and interchangeable materials such as plank types, slab types, stone variants, dyes, logs, and wool.
   - Prevent false “missing one ingredient” failures when a valid variant is already present.

3. **Dependency graph**
   - Build a graph from item -> ingredients -> sub-ingredients.
   - Detect cycles.
   - Estimate cost and required tools.

4. **Task generation**
   - Convert each recipe into a task candidate.
   - Prefer inventory/shulker/container supplies before gathering.
   - Recursively craft prerequisites.

5. **Collection automation**
   - Add a command/mode that iterates through all craftable outputs.
   - Craft one sample of each possible item.
   - Store completed samples in base storage or shulkers.
   - Log successes, failures, missing resources, and blocked dimensions.

6. **Learning**
   - Save route timings.
   - Prefer successful/faster routes.
   - Mark impossible or currently blocked items with clear reasons.

7. **UI integration**
   - Show all craftable items.
   - Show status: known, craftable now, missing resources, blocked, succeeded, failed.
   - Let the user start collection runs from the UI.

Possible future commands:

```text
@catalog recipes
@catalog craftable
@catalog missing
@collect_all_craftable
@collect_all_craftable overworld_only
@collect_all_craftable store_shulkers
```

## Shulker system

- Keep shulker contents synchronized from NBT, open-container scans, and memory.
- Support better shulker selection when multiple shulkers contain overlapping resources.
- Add configurable auto-sort categories.

## Schematic engine

- Promote the current region builder into a general blueprint/printer engine.
- Load `.litematic` and Sponge `.schem` files into an internal expected-block overlay.
- Calculate exact material plans before building.
- Require build supplies to be staged in a construction chest or shulkers inside that chest before printing starts.
- Withdraw only working batches into inventory during construction.
- Pause with a clear missing-material reason when a structure cannot be staged yet.
- Compare the world to the blueprint every repair pass so dirt/air/wrong blocks cannot remain in completed floors or walls.
- Preserve full block states for stairs, slabs, doors, logs, water, crops, and redstone instead of treating every target as a simple block ID.

See [SCHEMATIC_ENGINE.md](SCHEMATIC_ENGINE.md) for the planned architecture.
- Add optional reserved shulkers for food, ores, blocks, crafting supplies, and valuables.
- Add shulker labels/fingerprints so the bot can distinguish boxes beyond slot position.
- Build base storage rules around shulker categories.

## Player mode

- Expand campsite building from simple walls into reusable home-base modules.
- Add farm, mine entrance, storage wall, and safe sleep area modules.
- Improve exploration scoring so the bot can choose between mining, hunting, crafting, and returning home.
- Persist more observations about useful biomes, structures, and resource zones.
- Let `@player` run catalogue practice: craft useful items, store them, and remember what was possible.
- Make home expansion resumable after interruption or crash.

## Base building

Current base behavior is intentionally simple: square two-high wall, doorway, crafting table, furnace, chest.

Planned modules:

- storage wall;
- shulker station;
- bedroom;
- furnace room;
- starter farm;
- mine entrance;
- portal pad;
- animal pen;
- watch/defense lighting;
- pathable gate.

The eventual goal is not decorative building. It is a functional operating base where the bot can deposit, retrieve, craft, smelt, sleep, sort shulkers, and resume long-term collection goals.

## PvP

- Improve `@pvp` target tracking and retreat/heal behavior.
- Add loadout profiles.
- Add smarter use of shields, pearls, blocks, food, and golden apples.
- Add configurable risk thresholds for chasing vs healing.

## Release engineering

- Add automated CI builds for Minecraft/Fabric version matrix.
- Publish GitHub Releases with jar checksums.
- Add smoke-test worlds for `@get`, `@stacked`, and shulker workflows.
- Add issue templates with required `belfegor_debug.log` snippets.
- Add docs screenshots/GIFs once the UI stabilizes.
