# Belfegor roadmap

## Near term

- Continue hardening inventory cursor recovery in every container/crafting path.
- Add more structured shulker transaction tests and debug summaries.
- Improve `@get` planning so carried shulker resources are chosen before gathering duplicates.
- Expand the `C` UI settings controls for auto-shulker thresholds and timers.
- Add command-level progress estimates and clearer failure reasons.

## Crafting and resources

- Broaden recipe coverage for Minecraft `1.21.4`.
- Improve mixed-material recipe handling, especially recipes that allow multiple wood/stone variants.
- Add safer fallback behavior when a recipe is known but one ingredient source fails.
- Record successful crafting routes into persistent memory and prefer faster routes later.

## Shulker system

- Keep shulker contents synchronized from NBT, open-container scans, and memory.
- Support better shulker selection when multiple shulkers contain overlapping resources.
- Add configurable auto-sort categories.
- Add optional reserved shulkers for food, ores, blocks, crafting supplies, and valuables.

## Player mode

- Expand campsite building from simple walls into reusable home-base modules.
- Add farm, mine entrance, storage wall, and safe sleep area modules.
- Improve exploration scoring so the bot can choose between mining, hunting, crafting, and returning home.
- Persist more observations about useful biomes, structures, and resource zones.

## PvP

- Improve `@pvp` target tracking and retreat/heal behavior.
- Add loadout profiles.
- Add smarter use of shields, pearls, blocks, food, and golden apples.

## Release engineering

- Add automated CI builds for Minecraft/Fabric version matrix.
- Publish GitHub Releases with jar checksums.
- Add smoke-test worlds for `@get`, `@stacked`, and shulker workflows.
- Add issue templates with required `belfegor_debug.log` snippets.
