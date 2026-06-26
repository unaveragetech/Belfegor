# Development guide

## Requirements

- Java 21
- Fabric Loom through the Gradle wrapper
- Minecraft `1.21.4`
- Fabric Loader `0.16.10`
- Fabric API `0.117.0+1.21.4`
- Local Baritone API jar for development builds:

```text
../baritone/dist/baritone-api.jar
```

Versions are configured in [`gradle.properties`](../gradle.properties).

## Build

From the repo root:

```powershell
.\gradlew.bat build
```

Output:

```text
build/libs/belfegor-1.21.4-beta1.jar
```

Release copy:

```text
releases/belfegor-1.21.4-beta1.jar
```

## Local install

Copy the built jar to the target instance:

```powershell
Copy-Item build\libs\belfegor-1.21.4-beta1.jar "<instance>\.minecraft\mods\belfegor-1.21.4-beta1.jar" -Force
```

There is also a helper script:

```text
build_and_install.bat
```

## Important source areas

| Area | Purpose |
|---|---|
| `AltoClef.java` | Mod entrypoint, tick loop, command initialization, auto-shulker trigger. |
| `AltoClefCommands.java` | Registered command list. |
| `commands/` | Chat commands. |
| `tasks/` | Core task system and gameplay tasks. |
| `tasks/container/` | Crafting table, storage, shulker, smelting, retrieve/store tasks. |
| `tasks/resources/` | Resource collection and recipe material tasks. |
| `memory/` | Persistent crafting/location/shulker memory. |
| `ui/AltoclefScreen.java` | `C` UI. |
| `util/helpers/InventoryManager.java` | Higher-level inventory click helper. |
| `debug/DebugLogger.java` | Debug log writer. |

## Debugging inventory issues

Most hard bugs are caused by one of these:

- cursor stack not empty during a screen transition;
- task interruption during a slot transaction;
- stale screen handler or slot mapping;
- shulker box placed where it cannot open;
- container cache saying an item exists before it is actually in inventory;
- recipe logic counting container/shulker items as usable before retrieval.

Useful log tags:

- `TASK-START` / `TASK-STOP`
- `CRAFT-2X2`
- `CRAFT-CURSOR`
- `SCREEN-OPEN`
- `SCREEN-WATCHDOG`
- `SHULKER-STATE`
- `SHULKER-TRANSFER`
- `SHULKER-CATALOG`
- `SHULKER-FORCE`
- `CONTAINER-FORCE`

## Packaging release artifacts

After a successful build:

```powershell
Copy-Item build\libs\belfegor-1.21.4-beta1.jar releases\belfegor-1.21.4-beta1.jar -Force
Get-FileHash -Algorithm SHA256 releases\belfegor-1.21.4-beta1.jar
```

The `releases/` folder is intentionally unignored so the production jar can be uploaded with the repo.
