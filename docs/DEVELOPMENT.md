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
| `Belfegor.java` | Mod entrypoint, tick loop, command initialization, auto-shulker trigger. |
| `BelfegorCommands.java` | Registered command list. |
| `commands/` | Chat commands. |
| `tasks/` | Core task system and gameplay tasks. |
| `tasks/container/` | Crafting table, storage, shulker, smelting, retrieve/store tasks. |
| `tasks/resources/` | Resource collection and recipe material tasks. |
| `memory/` | Persistent crafting/location/shulker memory. |
| `ui/BelfegorScreen.java` | `C` UI. |
| `util/helpers/InventoryManager.java` | Higher-level inventory click helper. |
| `debug/DebugLogger.java` | Debug log writer. |
| `util/RecipeRegistry.java` | Offline recipe catalogue loader, output/input indexes, recursive craft plans, and leaf-resource expansion. |
| `commands/CraftAuditCommand.java` | Developer command entry point for recipe audit runs. |
| `tasks/container/CraftAuditTask.java` | Cheat-enabled craft audit task that provisions resources, crafts through real tasks, stores outputs, and logs results. |
| `llm/LlmAdvisor.java` | Packaged llama.cpp advisor bridge, command export, context snapshot, action log, response validation, and player-mode decision requests. |

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

## Offline recipe and craft-audit testing

Belfegor carries a local recipe catalogue in:

```text
src/main/resources/belfegor_recipes.json
```

The runtime loader is `RecipeRegistry`. It normalizes item ids, indexes recipes by output/input, and can expand a target into a recursive `CraftPlan` with leaf resources. This is used by the developer-only audit command:

```text
@craftaudit <target=all> <limit=0>
```

Recommended test-world workflow:

1. Create a singleplayer creative or cheat-enabled survival world.
2. Make sure commands are allowed, because the audit uses `/clear @s`, `/kill @e[type=item,distance=..16]`, and `/give @s`.
3. Run a focused craft first:

```text
@craftaudit anvil
@craftaudit diamond_shovel
```

4. Then run a bounded catalogue pass:

```text
@craftaudit all 25
```

5. Read the generated log:

```text
.minecraft/belfegor/craft_audit_*.log
```

The audit should fail loudly rather than loop forever. A failure usually points at one of four areas: bad recipe data, missing ingredient-group normalization, missing source/acquisition support, or an unsafe inventory transaction during the real craft.

Each audited item now starts from a clean inventory, receives only the normalized leaf resources required for that item plus required utilities such as a crafting table/chest, crafts through the normal task system, and stores the result in ordinary container storage. Shulkers are intentionally excluded from audit storage so recipe/crafting failures are not confused with shulker-management failures.

For Baritone command research and base-building recovery commands, see [Baritone Command Reference](BARITONE_COMMAND_REFERENCE.md).

## Local llama.cpp advisor development

The advisor is packaged in Java and calls llama.cpp directly:

```text
.minecraft/belfegor/llama.cpp/llama-cli.exe -m .minecraft/belfegor/models/Qwen3-1.7B-Q4_K_M.gguf -f .minecraft/belfegor/llm_prompt.txt
```

It writes inspectable files under `.minecraft/belfegor/`:

```text
llm_commands.md
llm_context.json
llm_prompt.txt
llm_response.json
llm_actions.log
```

Test flow:

1. Confirm `.minecraft/belfegor/llama.cpp/llama-cli.exe` and `.minecraft/belfegor/models/Qwen3-1.7B-Q4_K_M.gguf` exist.
2. Set `llmAdvisorEnabled=true`.
3. Run:

```text
@ai "what should I do next?"
```

For player-mode testing, set `llmAdvisorInPlayerMode=true` and run `@player`. Returned commands must exist in the command registry and cannot be control/dev commands such as `@stop`, `@reload_settings`, `@craftaudit`, `@test`, `@ai`, or `@player`.

## Packaging release artifacts

After a successful build:

```powershell
Copy-Item build\libs\belfegor-1.21.4-beta1.jar releases\belfegor-1.21.4-beta1.jar -Force
Get-FileHash -Algorithm SHA256 releases\belfegor-1.21.4-beta1.jar
```

The `releases/` folder is intentionally unignored so the production jar can be uploaded with the repo.

