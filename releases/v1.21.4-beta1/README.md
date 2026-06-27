# Belfegor `v1.21.4-beta1` runtime release

This folder contains the runtime files for Belfegor on Minecraft `1.21.4`.

This runtime package has been refreshed with the full Belfegor namespace deployment. The mod jar no longer ships the legacy project package names in its compiled classes, entrypoints, or mixin metadata.

It includes the offline recipe catalogue and the developer command `@craftaudit`.

It also includes the packaged llama.cpp advisor code. To use it, place `llama-cli.exe` under `.minecraft/belfegor/llama.cpp/`, place the GGUF model at `.minecraft/belfegor/models/lfm2.5-thinking.gguf`, enable `llmAdvisorEnabled` in `belfegor_settings.json`, and run `@ai "what should I do next?"`.

## Included jars

Copy these files into your Minecraft instance's `.minecraft/mods/` folder:

| File | Purpose |
|---|---|
| `mods/belfegor-1.21.4-beta1.jar` | Belfegor mod jar. |
| `mods/fabric-api-0.114.3+1.21.4.jar` | Fabric API jar copied from the working instance. |
| `mods/baritone-api.jar` | Baritone API jar copied from the working instance. |

These are the exact jars pulled from the working MultiMC instance:

```text
C:\Users\b0052\Desktop\python projects\Projects\mmc-develop-win32\MultiMC\instances\1.21.4\.minecraft\mods
```

## Requirements

- Minecraft `1.21.4`
- Fabric Loader `0.16.10` or compatible
- Java 21

## Quick install

1. Install Fabric Loader for Minecraft `1.21.4`.
2. Open your instance's `.minecraft/mods/` folder.
3. Copy all three jars from this release's `mods/` folder into `.minecraft/mods/`.
4. Launch Minecraft once.
5. In game, run:

```text
@help
@status
@get crafting_table
```

6. Press `C` to open the Belfegor UI.

For cheat-enabled developer test worlds, you can verify recipe/crafting coverage with:

```text
@craftaudit anvil
@craftaudit all 25
```

Audit logs are written to `.minecraft/belfegor/craft_audit_*.log`.

## Documentation included in the repository

Read the main project docs for full setup and usage:

- `README.md`
- `docs/WHITEPAPER.md`
- `docs/INSTALLATION.md`
- `docs/COMMANDS.md`
- `docs/SHULKER_MANAGEMENT.md`
- `docs/BUTLER_AND_SERVERS.md`
- `docs/TROUBLESHOOTING.md`

## Notes

The Fabric API jar in this runtime package is the one currently present in the working instance: `fabric-api-0.114.3+1.21.4.jar`. Development metadata may reference a newer compatible Fabric API, but this release package intentionally mirrors the tested instance files.
