# Belfegor `v1.21.4-beta1` release notes

This is the first public Belfegor beta release for Minecraft `1.21.4`.

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

6. Press `C` to open the Belfegor UI.

## What this release does

Belfegor is a client-side Minecraft automation agent. It can:

- parse `@` commands;
- gather resources;
- craft recipe-driven targets such as tools, workstations, armor, and anvils;
- use Baritone-style movement/pathing;
- manage carried shulker boxes as sub-inventories;
- run PvP preparation commands such as `@stacked` and `@toolset`;
- run autonomous player mode with `@player`;
- expose task state, command help, logs, and shulker memory through the `C` UI;
- optionally allow trusted users to control the bot through Butler whispers.

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

