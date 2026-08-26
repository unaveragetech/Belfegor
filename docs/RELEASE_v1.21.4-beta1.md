# Belfegor `v1.21.4-beta1` release notes

This is the first public Belfegor beta release for Minecraft `1.21.4`.

This release bundle has been refreshed with the current craft-audit and packaged llama.cpp-advisor build. The jar now includes the offline recipe catalogue improvements, the developer command `@craftaudit`, and the optional local AI advisor for `belfegor/models/Qwen3-1.7B-Q4_K_M.gguf`.

## Jar tracking and comparison

The repo now keeps immutable jar copies under `releases/jars/` so the current beta jar can be compared against the previous public jar without checking out old commits.

| Jar | Commit | SHA256 | Notes |
|---|---:|---|---|
| `releases/jars/belfegor-1.21.4-beta1-341eae3.jar` | `341eae3` | `961e6b5994976312a84f0fbf9e588c5d55272072470e3a4e9ceb43c456c1f2ec` | Previous public beta jar. |
| `releases/jars/belfegor-1.21.4-beta1-718e0b7.jar` | `718e0b7` | `27f4eaf257b0a3e67688ad9f984b137510e4b2912903bc17e929a8e137bcc2d4` | Current beta jar with base expansion floor/placement hardening. |
| `releases/jars/belfegor-1.21.4-beta1-6050309.jar` | `6050309` | `12dbe3564b8586a2f2fd43aa7f02379df225b3452f0e5519d315e1a385488a41` | Beta jar with the autonomy overhaul and cake milk-bucket fix. |
| `releases/jars/belfegor-1.21.4-beta1-067bcdf.jar` | `067bcdf` | `8d664411632119a6482b22c2989ef9e712b2b68210fa1a38a8f0b3441acb141d` | Beta jar with the full MLG clutch overhaul. |
| `releases/jars/belfegor-1.21.4-beta1-dbad9b5.jar` | `dbad9b5` | `a5da77272904851159d9901bb091354afd5bc9dd0d5a371a20d8f4018cd35922` | Beta jar with sweet-berry-bush and vine fall clutches. |
| `releases/jars/belfegor-1.21.4-beta1-262a855.jar` | `262a855` | `c867f1482b5ed760bd25926ce4fd50bd3fcda797b1c96a00c0895a478685904b` | Beta jar with @armor and @equipment loadout commands. |
| `releases/jars/belfegor-1.21.4-beta1-ab907a5.jar` | `ab907a5` | `3df8e1f289e2c741ce067fd459e8887fc654b5c4624f838543a7967607d81941` | Beta jar with the crafting screen-storm hang fix. |
| `releases/jars/belfegor-1.21.4-beta1-9af2ea7.jar` | `9af2ea7` | `ba559945ca7667a812408f5f9fa12804f1f5403d45c77f3cf68615701ccb3751` | Beta jar with forced axe requirements for wood gathering. |
| `releases/jars/belfegor-1.21.4-beta1-234a743.jar` | `234a743` | `ff1b4a8628e9c52d14463c1308badf53b602f9d3b29979060817c70f2342d314` | Current beta jar that uses nearby crafting tables instead of crafting/placing new ones. |

The moving install jar remains `releases/belfegor-1.21.4-beta1.jar` and currently matches the `234a743` archive copy.

### What changed from `341eae3` to `718e0b7`

- Natural grass/dirt/cobblestone floors are accepted for storage/workshop-style expansions instead of being replaced unnecessarily.
- Farmland rooms no longer schedule support blocks below already-valid grass/dirt/farmland floor cells.
- Farm water placement can use side support faces when the block below the hole cannot be clicked.
- Expansion placement rejects unsupported footprints before scheduling Baritone.
- If every expansion candidate is unsupported or overlapping, the task logs `placement-blocked` and stops cleanly instead of falling back to a floating room.
- The release jar and runtime bundle checksums were refreshed after rebuilding and installing the tested jar.

### What changed from `718e0b7` to `6050309`

- `@get cake` no longer misreads owned milk buckets: `CollectMilkTask` recognizes milk already in inventory or a carried shulker, finishes when the requirement is met, and only crafts the empty buckets still missing.
- Milking no longer re-equips the bucket every tick and logs when it makes no progress.
- `SatisfyMiningRequirementTask` returns immediately when the mining requirement is already met, preventing the repeated best-tool equip loop while mining.
- This jar also includes the full autonomy overhaul: doors and door repair, base-aware navigation, resumable construction, the storage economy with stash errands, tool reserves, the persistent `@goal` game plan, the rebuilt llama.cpp advisor, and the new command set.

### What changed from `6050309` to `067bcdf`

- Clutch items now have real per-item usage modes: fluid placement (water,
  powder snow), top-face block placement (hay, honey, slime, cobweb,
  scaffolding, twisting vines), side-face placement with steering (ladder,
  weeping vines), orientation-aware 2-block bed placement, offhand totems, and
  ender-pearl throws at impact.
- Beds place with the head in an empty cell and verify the placement; ladders
  and vines are placed against a side and the bot steers into them and climbs.
- Totem of undying is equipped in the offhand (combined with the best block
  clutch when both exist), and the offhand-equip helper no longer strands items
  in the cursor.
- Ender pearls are thrown steeply downward 9-18 blocks above impact.
- The best available item is chosen by effectiveness; sweet berries are ignored
  with a warning; placement is verified/retried and never stacked.

### What changed from `067bcdf` to `dbad9b5`

- Sweet berry bushes are planted into the fall column as a full fall clutch
  (they slow the fall like water), and the bot no longer jumps inside them.
- Twisting and weeping vines are scored as full fall protectors; grabbing a
  vine cancels the fall because climbing resets fall distance every tick.

### What changed from `dbad9b5` to `262a855`

- `@armor <material>` crafts and equips a full armor set (helmet, chestplate,
  leggings, boots) for leather, chainmail, iron, gold, diamond, or netherite.
- `@equipment <material>` prepares a full loadout: complete tool set plus full
  armor set. wood/stone craft tools only, leather/chainmail craft armor only,
  and iron/gold/diamond/netherite craft both, equipping the armor once crafted.

### What changed from `262a855` to `ab907a5`

- Fixed the inventory screen open/close storm that hung crafting builds:
  placement parents no longer yank the screen away from their own crafting
  child, so planks and other 2x2 crafts complete instead of looping forever.

### What changed from `ab907a5` to `9af2ea7`

- Wood gathering now forces an axe at the bot's current tool tier: stone or
  better once the bot has a stone pickaxe, then iron and diamond as the tier
  rises. The first wooden axe is still crafted by hand-chop (bootstrap).
- MiningRequirement no longer reports a bogus DIAMOND requirement for blocks
  no pickaxe can break.

### What changed from `9af2ea7` to `234a743`

- The bot now uses a crafting table already in the world (within six blocks)
  instead of crafting and placing a new one, fixing the @equipment iron
  2x2/3x3 crafting restart storm caused by trying to make/place a table while
  tables were already in proximity.

## Download

Use the runtime bundle:

```text
releases/belfegor-1.21.4-beta1-runtime.zip
```

Or copy the individual jars from:

```text
releases/v1.21.4-beta1/mods/
```

## Included runtime files

| File | Purpose |
|---|---|
| `belfegor-1.21.4-beta1.jar` | The Belfegor mod. |
| `fabric-api-0.114.3+1.21.4.jar` | Fabric API from the working instance. |
| `baritone-api.jar` | Baritone API from the working instance. |

## Install

1. Install Minecraft `1.21.4`.
2. Install Fabric Loader `0.16.10` or compatible.
3. Copy the three included jars into your instance's `.minecraft/mods/` folder.
4. Launch Minecraft.
5. Run:

```text
@help
@status
@get crafting_table
```

6. Press `C` or run `@ui` to open the Belfegor UI. Both routes use the same screen-opening implementation.

## What this release does

Belfegor is a client-side Minecraft automation agent. It can:

- parse `@` commands;
- gather resources;
- craft recipe-driven targets such as tools, workstations, armor, and anvils;
- use Baritone-style movement/pathing;
- manage carried shulker boxes as sub-inventories;
- plan crafts from the bundled offline `1.21.4` recipe catalogue;
- run developer recipe audits with `@craftaudit <target=all> <limit=0>`;
- ask the Packaged llama.cpp advisor for contextual help with `@ai`;
- optionally let `@player` ask the advisor for the next validated command;
- run PvP preparation commands such as `@stacked` and `@toolset`;
- run autonomous player mode with `@player`;
- expose task state, command help, logs, and shulker memory through the `C` UI;
- open the same UI with `@ui`; this now calls the same screen-opening route as the `C` key;
- edit and run macros from the UI with create/save/reload/run/pause/stop/duplicate/delete/loop/add/remove/reorder controls;
- optionally allow trusted users to control the bot through Butler whispers.

## Current hardening notes

- `@craftaudit all 5` passed locally after the recipe-registry cleanup. The tested wood-family recipes received matching logs instead of generic wrong-family resources.
- The recipe registry now rejects invalid empty recipes and resolves contextual aliases such as `log`, `planks`, and `slab` based on the output family when appropriate.
- `@get cake` was rechecked after local loaded-block scan throttling; the client stayed responsive and the sampled log no longer showed the previous scan storm.
- `@help ui`, `@status`, `@coords`, `@inventory`, and `@list` executed without command errors in the test instance.
- UI note: `@ui` and `C` are intentionally aligned. If both fail to show the panel in a heavily modded profile, treat it as a screen/overlay conflict rather than a command-registry failure.
- Baritone integration note: Belfegor now captures `@...` commands at chat-screen submit time before Baritone can consume them, and includes `@baritone` for safe native diagnostics/testing. Verified: `@baritone proc`, `@baritone help sel`, and `@baritone sel clear`.

## What this release cannot promise

This is beta automation software. It cannot guarantee:

- compatibility with every server, plugin, anti-cheat, or custom inventory;
- flawless recipe handling for every ingredient variant/tag;
- perfect recovery from every interrupted inventory transaction;
- human-level survival, base building, PvP, or anarchy-server behavior;
- stealth, bypass, or ban evasion.

## Server/anarchy note

Belfegor can technically run while connected to multiplayer servers if the modded client can connect, but you must follow the server's rules. Private test servers and automation-friendly servers are the best place to use it. Anarchy servers may tolerate bots culturally, but they are hostile, laggy, trap-heavy, and unpredictable. Belfegor is not designed as an anti-cheat bypass tool.

## Butler note

The Butler system lets authorized players command Belfegor through whispers/private messages. For multiplayer use, enable whitelist mode and add only trusted players before exposing the bot on a server.

## Developer audit note

`@craftaudit` is intended for local test worlds with cheats/op enabled. It expands a target recipe through the bundled offline catalogue, gives the required leaf resources, executes the real Belfegor crafting path, stores outputs in containers, and writes logs under `.minecraft/belfegor/`. Use focused runs like `@craftaudit anvil` before broad catalogue runs like `@craftaudit all 25`.

## Documentation

This release is backed by the full repository docs:

- `README.md`
- `docs/WHITEPAPER.md`
- `docs/INSTALLATION.md`
- `docs/COMMANDS.md`
- `docs/SHULKER_MANAGEMENT.md`
- `docs/BUTLER_AND_SERVERS.md`
- `docs/TROUBLESHOOTING.md`
- `docs/ROADMAP.md`

