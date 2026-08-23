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
| `releases/jars/belfegor-1.21.4-beta1-718e0b7.jar` | `718e0b7` | 3,886,205 bytes | `27f4eaf257b0a3e67688ad9f984b137510e4b2912903bc17e929a8e137bcc2d4` | Current beta jar with base expansion floor planning, water placement, and unsupported-footprint guards. |
| `releases/jars/belfegor-1.21.4-beta1-6050309.jar` | `6050309` | 4,049,926 bytes | `12dbe3564b8586a2f2fd43aa7f02379df225b3452f0e5519d315e1a385488a41` | Current beta jar with the full autonomy overhaul and the cake milk-bucket fix. |

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

## Runtime bundle note

The runtime zip is tracked with Git LFS because it includes the bundled llama.cpp/model tree:

```text
releases/belfegor-1.21.4-beta1-runtime.zip
```

Current runtime bundle SHA256:

```text
49708a06cda6295c18b966527abf272e2c203a79df1da3f4da3611c146eadcb9
```
