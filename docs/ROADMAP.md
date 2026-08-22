# Belfegor roadmap

This roadmap is intentionally practical. Belfegor’s main challenge is not “add more commands”; it is making the agent reliable enough that large commands can run for a long time without poisoning the cursor, losing inventory state, or getting trapped in a task loop.

## Near term

- Resolve the remaining visual UI display conflict observed in the heavily modded local profile. Current diagnostics show `@ui` sets `BelfegorScreen` as the active Minecraft screen and logs `UI-OPEN`; the remaining issue is visual/capture/profile-layer visibility when other overlay mods are active.
- Re-test the `C`/`@ui` panel in a clean Fabric profile and capture a final UI screenshot/video once stable.
- Continue hardening inventory cursor recovery in every container/crafting path.
- Extend the exact-target container ownership/close-on-stop rule to any future handled-screen task before it is allowed into `@player`; current chest deposit/retrieval paths are covered and live-tested.
- Add deterministic tests for resource travel budgets and target replacement. Current mining honors `maxResourceTravelDistance` and replaces far targets before the bot reaches mining range, but biome/dimension-specific sourcing still needs policy-level limits.
- Add more structured shulker transaction tests and debug summaries.
- Improve `@get` planning so carried shulker resources are chosen before gathering duplicates.
- Expand the `C`/`@ui` settings controls for auto-shulker thresholds and timers.
- Add command-level progress estimates and clearer failure reasons.
- Continue improving task oscillation diagnostics. Current builds keep a bounded in-session interruption history, show the last switch in `@status`, expose a newest-first timeline through `@status history`, and show recent/repeated interrupt pairs in the Tasks tab; next step is using that data to suggest or auto-dampen repeated oscillation loops.

## Crafting and resources

- Maintain full `@craftaudit all` coverage for Minecraft `1.21.4`. The 2026-07-02 proof run passed 799/799 current craftable targets and is archived under `docs/media/audit-proof-2026-07-02/`.
- Continue improving mixed-material recipe handling. Wood-family aliases, pale-oak/bamboo families, dyed wool/candles/concrete/stained glass/terracotta, and dark prismarine are now covered by audit-tested corrections; stone variants and broader tag combinations still need survival-path validation.
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
- Make home expansion resumable after interruption or crash. (implemented: per-base build phases are persisted and resumed)
- @player maintains a full carried tool set plus a backup set stored at base. (implemented)
- @player triages inventory at home: surplus is stored in the base storage network, the field kit stays in hand. (implemented)
- @player auto-resumes the persistent @goal game plan once the base is complete. (implemented)
- Add @harvest to reap and replant the farm module. (next)
- Build a semantic map of explored regions so exploration can prioritize unvisited areas. (next)

## Base building

Current base behavior builds a persisted radius-based core with four-high cobblestone perimeter walls, a two-wide remembered doorway, interior room dividers, crafting/smelting/storage fixtures, a bed/spawn anchor, natural-flat-floor preservation, five-block exterior clearance, and separate connected expansion modules. Validation returns to the locked home and checks that home's exported internal blueprint rather than applying one global schematic at an arbitrary origin.

Implemented since the last roadmap pass:

- The bot opens its own doors while navigating, repairs entrance doors missing from the world, and routes through the remembered room graph without breaking finished walls.
- Construction phases are persisted per base, so @camp/@build <room>/@build full resume after interruption instead of starting over.
- Base storage is a real economy: chests are remembered in a network, stockpile/triage flows update known counts, and stash errands let the bot gather -> stash -> retrieve.
- @player keeps a full carried tool set plus a backup set at base, triages inventory at home, and auto-resumes the persistent @goal game plan once the base is complete.

The next quality step is to make the existing modules more schematic-driven and less procedural while preserving the working lifecycle rules: one Baritone owner at a time, staged working batches, protected completed blocks, exact fixture positions, bounded scans, and repair-before-expansion.

Next module upgrades:

- high-capacity sorted storage wall beyond the current networked camp chests;
- dedicated shulker station;
- bedroom upgrade around the current bed/spawn anchor;
- multi-furnace processing room beyond the current smelting fixture/workshop;
- crop rotation, harvest, and replant automation for the current hydrated farm module;
- mine entrance;
- portal pad;
- animal pen;
- watch/defense lighting;
- pathable gates and doors (doors are implemented: the bot opens and repairs them; gates remain);
- `@harvest` to reap and replant the farm module;
- procedural, terrain-aware base design instead of the fixed modular template;
- smarter site selection and pre-build staging from remembered storage;
- AI-driven build/expand goals through the advisor.

The eventual goal is not decorative building. It is a functional operating base where the bot can deposit, retrieve, craft, smelt, sleep, sort shulkers, and resume long-term collection goals.

## PvP

- Improve `@pvp` target tracking and retreat/heal behavior.
- Add loadout profiles.
- Add smarter use of shields, pearls, blocks, food, and golden apples.
- Add configurable risk thresholds for chasing vs healing.

## Release engineering

- Add automated CI builds for Minecraft/Fabric version matrix.
- Publish GitHub Releases with jar checksums.
- Keep dated media/log proof bundles for major regression passes. Current proof exists for full craft audit and full screen audit in `docs/media/audit-proof-2026-07-02/`.
- Add smoke-test worlds for `@get`, `@stacked`, and shulker workflows.
- Add issue templates with required `belfegor_debug.log` snippets.
- Add docs screenshots/GIFs once the UI stabilizes.
