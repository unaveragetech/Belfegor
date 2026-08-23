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
| `releases/jars/belfegor-1.21.4-beta1-dbad9b5.jar` | `dbad9b5` | 4056365 bytes | `a5da77272904851159d9901bb091354afd5bc9dd0d5a371a20d8f4018cd35922` | Current beta jar with sweet-berry-bush and vine fall clutches. |

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

## Runtime bundle note

The runtime zip is tracked with Git LFS because it includes the bundled llama.cpp/model tree:

```text
releases/belfegor-1.21.4-beta1-runtime.zip
```

Current runtime bundle SHA256:

```text
a8536fe88f6f5cfe1972893e0c1e6573f7bd97cad765f09018aedd2710e4a5fb
```
