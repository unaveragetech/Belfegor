# Belfegor jar history

This directory keeps immutable copies of release jars so users and developers can compare the exact artifact that changed between pushes.

The moving release filename remains:

```text
releases/belfegor-1.21.4-beta1.jar
```

That file always points at the current recommended beta jar. Archived copies live under:

```text
releases/jars/
```

## Tracked jars

| Jar | Commit | Size | SHA256 | Role |
|---|---:|---:|---|---|
| `releases/jars/belfegor-1.21.4-beta1-341eae3.jar` | `341eae3` | 3,817,167 bytes | `961e6b5994976312a84f0fbf9e588c5d55272072470e3a4e9ceb43c456c1f2ec` | Previous public beta jar before the base-builder hardening pass. |
| `releases/jars/belfegor-1.21.4-beta1-718e0b7.jar` | `718e0b7` | 3,886,205 bytes | `27f4eaf257b0a3e67688ad9f984b137510e4b2912903bc17e929a8e137bcc2d4` | Beta jar with base expansion floor planning, water placement, and unsupported-footprint guards. |
| `releases/jars/belfegor-1.21.4-beta1-6050309.jar` | `6050309` | 4,049,926 bytes | `12dbe3564b8586a2f2fd43aa7f02379df225b3452f0e5519d315e1a385488a41` | Beta jar with the full autonomy overhaul and the cake milk-bucket fix. |
| `releases/jars/belfegor-1.21.4-beta1-067bcdf.jar` | `067bcdf` | 4,056,350 bytes | `8d664411632119a6482b22c2989ef9e712b2b68210fa1a38a8f0b3441acb141d` | Beta jar with the full MLG clutch overhaul. |
| `releases/jars/belfegor-1.21.4-beta1-dbad9b5.jar` | `dbad9b5` | 4056365 bytes | `a5da77272904851159d9901bb091354afd5bc9dd0d5a371a20d8f4018cd35922` | Beta jar with sweet-berry-bush and vine fall clutches. |
| `releases/jars/belfegor-1.21.4-beta1-262a855.jar` | `262a855` | 4062997 bytes | `c867f1482b5ed760bd25926ce4fd50bd3fcda797b1c96a00c0895a478685904b` | Beta jar with @armor and @equipment loadout commands. |
| `releases/jars/belfegor-1.21.4-beta1-ab907a5.jar` | `ab907a5` | 4063089 bytes | `3df8e1f289e2c741ce067fd459e8887fc654b5c4624f838543a7967607d81941` | Beta jar with the crafting screen-storm hang fix. |
| `releases/jars/belfegor-1.21.4-beta1-9af2ea7.jar` | `9af2ea7` | 4066189 bytes | `ba559945ca7667a812408f5f9fa12804f1f5403d45c77f3cf68615701ccb3751` | Beta jar with forced axe requirements for wood gathering. |
| `releases/jars/belfegor-1.21.4-beta1-234a743.jar` | `234a743` | 4067128 bytes | `ff1b4a8628e9c52d14463c1308badf53b602f9d3b29979060817c70f2342d314` | Beta jar that uses nearby crafting tables instead of crafting/placing new ones. |
| `releases/jars/belfegor-1.21.4-beta1-c774be5.jar` | `c774be5` | 4067423 bytes | `1881c30577f0c4e7ce90ea92f6a78e17fd32cb6bd6b49c8bf923db0f03052e8c` | Current beta jar whose @pillar breaks overhead blocks to work underground. |

## What changed in the current jar

Compared with `341eae3`, the `718e0b7` jar focuses on base-builder reliability:

- Storage/workshop expansions no longer try to replace already-solid natural floors with cobblestone unless the floor cell is unsafe or missing.
- Farmland floors accept existing grass/dirt/farmland surfaces and avoid creating support-block jobs under already-valid natural ground.
- Farm water placement can click a side support block when the block below the water hole is unavailable.
- Expansion planning now rejects room footprints that are mostly unsupported air, water, or lava.
- If every possible room placement is unsupported or overlapping, the expansion stops cleanly and logs `placement-blocked` instead of falling back to a far floating room.
- The generic region schematic builder now treats natural solid non-farm floors as already valid, keeping Baritone from fighting the terrain.

## What changed from `718e0b7` to `6050309`

This jar includes the full autonomy overhaul (doors and door repair, base-aware navigation, resumable construction, the storage economy with stash errands, tool reserves, the persistent `@goal` game plan, the rebuilt llama.cpp advisor, and the new command set) plus:

- `@get cake` no longer misreads owned milk buckets: `CollectMilkTask` recognizes milk already in inventory or a carried shulker, finishes when the requirement is met, and only crafts the empty buckets still missing.
- Milking no longer re-equips the bucket every tick and logs when it makes no progress.
- `SatisfyMiningRequirementTask` returns immediately when the mining requirement is already met, preventing the repeated best-tool equip loop while mining.

## What changed from `6050309` to `067bcdf`

This jar overhauls the MLG fall-clutch system so every configured clutch item
is actually used the way a player would use it:

- Clutch items are now classified into real usage modes instead of treating
  everything like a water bucket: water/powder-snow fluid placement, top-face
  block placement (hay, honey, slime, cobweb, scaffolding, twisting vines),
  side-face placement with steering (ladder, weeping vines), orientation-aware
  2-block bed placement, offhand totems, and ender-pearl throws at impact.
- Beds pick a facing with an empty head block, aim the top face, and verify the
  foot/head landed instead of failing silently.
- Ladders and vines are placed against a side of the landing block; the bot
  steers into the cell and holds jump to climb.
- Totems are equipped to the offhand (with the best block clutch also used when
  both are available), and `forceEquipItemToOffhand` no longer leaves the item
  stuck in the cursor.
- Ender pearls are thrown steeply down 9-18 blocks above the landing block.
- Sweet berries are rejected as a fall clutch with a one-time warning.
- The best available item is chosen by effectiveness, not config order.
- Placement is verified and retried on a cooldown; once placed the bot stops
  clicking so it no longer stacks blocks upward, and already-present clutch
  blocks are not re-stacked after a bounce.
- Water pickup bookkeeping only tracks water clutches, so the fall chain never
  tries to recollect hay/bed blocks as water.
- Fall-deadliness accounting now understands honey, beds, slime, cobweb and
  powder snow landing modifiers.

## What changed from `067bcdf` to `dbad9b5`

- Sweet berry bushes are now a real clutch: the bot plants the bush into the
  fall column so it slows and cancels the fall like water, and it no longer
  tries to jump inside the bush.
- Twisting and weeping vines are scored as full fall protectors: once the bot
  is inside the vine (or grabs it against the landing block), climbing resets
  fall distance every tick, cancelling the fall.

## What changed from `dbad9b5` to `262a855`

- `@armor <material>` crafts and equips a complete armor set (helmet,
  chestplate, leggings, boots) for leather, chainmail, iron, gold, diamond, or
  netherite, skipping pieces that are already owned or equipped.
- `@equipment <material>` prepares a full loadout: a complete tool set plus a
  full armor set, equipping the armor once crafted. wood/stone craft tools
  only, leather/chainmail craft armor only, and iron/gold/diamond/netherite
  craft both (gold and netherite tools are gathered through the recipe
  catalogue).

## What changed from `262a855` to `ab907a5`

- Fixed the inventory screen open/close storm that hung crafting builds.
  PlaceBlockNearbyTask and PlaceBlockTask closed any open screen at the top of
  every tick, so when their own crafting child opened the inventory screen to
  craft planks the parent yanked it shut, the forced crafting child reopened
  it, and the two fought forever with no progress.
- PlaceBlockNearbyTask now clears the cursor/screen only right before an
  actual placement; PlaceBlockTask only closes a leftover screen while it is
  not collecting materials, so the crafting child keeps ownership of the
  screen until its transaction completes.

## What changed from `ab907a5` to `9af2ea7`

- Wood/log blocks are no longer silently treated as hand-mining with no tool
  evaluation. MineAndCollectTask now demands an axe for axe-suitable blocks
  (logs, planks, wood, bamboo) through the new SatisfyAxeRequirementTask.
- The axe requirement targets the bot's current tool tier, so once the bot has
  a stone pickaxe it is forced to carry a stone-or-better axe, then iron, then
  diamond as the pickaxe tier rises. Axes upgrade tier-by-tier so the
  upgrade's own wood gathering always has a working axe.
- The very first chop is still allowed by hand so the first wooden axe can be
  crafted (no bootstrap loop).
- MiningRequirement.getMinimumRequirementForBlock no longer reports a bogus
  DIAMOND requirement for blocks no pickaxe can break (logs, dirt); it now
  correctly reports HAND.

## What changed from `9af2ea7` to `234a743`

- Container tasks (crafting tables, furnaces, chests) now scan the loaded
  world for a nearby target block, so a crafting table the block tracker
  missed (freshly placed, tracker cleared, or a base fixture) is used instead
  of crafting/placing a new one. A container within six blocks always wins
  over placing, and an in-progress placement is abandoned in favor of it.
- CraftInInventoryTask no longer closes a legitimately open crafting-table
  screen and uses it for 2x2 crafts, which stops the 2x2/3x3 crafting
  restart storm that hit @equipment iron.

## What changed from `234a743` to `c774be5`

- `@pillar` now works underground: before every jump it looks up and breaks
  the first solid block in the three cells above the player's head, then
  resumes the normal jump-place-land loop. When the ceiling closes in again
  it repeats the break-and-pillar cycle until the target height is reached.

## Runtime bundle note

The runtime zip is tracked with Git LFS because it includes the bundled llama.cpp/model tree:

```text
releases/belfegor-1.21.4-beta1-runtime.zip
```

Current runtime bundle SHA256:

```text
5707e7137687b99acb70e4cfe1112d5173d58fb90376947a41c6e5704fe1478e
```
