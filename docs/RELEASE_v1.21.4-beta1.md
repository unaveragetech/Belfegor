# Belfegor `v1.21.4-beta1` release notes

This is the first public Belfegor beta release for Minecraft `1.21.4`.

This release bundle has been refreshed with the current craft-audit and packaged llama.cpp-advisor build. The jar now includes the offline recipe catalogue improvements, the developer command `@craftaudit`, and the optional local AI advisor for `belfegor/models/Qwen3-1.7B-Q4_K_M.gguf`.

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

