# Settings and generated files

Belfegor creates a config directory in the active Minecraft instance:

```text
.minecraft/belfegor/
```

## Important files

| File | Purpose |
|---|---|
| `belfegor_settings.json` | Main user settings. |
| `belfegor_debug.log` | Detailed debug log. |
| `belfegor_shulker_memory.json` | Remembered shulker-box contents. |
| `belfegor_butler_whitelist.txt` | Players allowed to command the bot by whisper when whitelist mode is enabled. |
| `belfegor_butler_blacklist.txt` | Players blocked from Butler commands. |

Butler also loads its own config from:

```text
configs/butler.json
```

For multiplayer, read [Butler and multiplayer guide](BUTLER_AND_SERVERS.md). The important safety point is that whitelist mode should be enabled before letting anyone command the bot on a public or semi-public server.

## Useful settings

| Setting | Purpose |
|---|---|
| `commandPrefix` | Command prefix, default `@`. |
| `chatLogPrefix` | Chat/log label, default `[Belfegor] `. |
| `autoShulkerMode` | Enables/disables automatic carried-shulker sorting. |
| `autoShulkerSortMode` | `timer` or `detection`. |
| `autoShulkerTimerSeconds` | Timer-mode interval. |
| `autoShulkerInventoryThreshold` | Detection-mode inventory fill threshold. |
| `homeBasePosition` | Saved home base used by player mode and idle return logic. |
| `returnHomeOnIdle` | Returns to home base when idle. |
| `defendHomeBase` | Defends the remembered home base. |
| `homeBaseDefenseRadius` | Radius for home-base defense behavior. |
| `llmAdvisorEnabled` | Enables the packaged local llama.cpp advisor. Defaults to `false`. |
| `llmAdvisorInPlayerMode` | Lets `@player` ask llama.cpp for the next safe command on a cooldown. |
| `llmAdvisorCanChat` | Allows `@ai` chat-style advisor prompts. |
| `llmLlamaCppExecutable` | Optional `llama-cli` path. Blank uses `.minecraft/belfegor/llama.cpp/llama-cli(.exe)`. |
| `llmLlamaModelPath` | GGUF model path, default `belfegor/models/lfm2.5-thinking.gguf`. |
| `llmAdvisorCooldownSeconds` | Minimum seconds between automatic player-mode advisor calls. |
| `llmAdvisorTimeoutSeconds` | Maximum seconds to wait before falling back to deterministic logic. |

After editing settings, run:

```text
@reload_settings
```

## UI

Press `C` to open the Belfegor UI. Tabs include:

- task state;
- macros;
- interactive command reference;
- settings;
- shulker memory;
- log view.

The command page includes real examples that can be run by double-clicking.

## Emergency stop

Press `+` while a task is running to globally abort the current automation.
