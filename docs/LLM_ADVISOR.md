# Local llama.cpp LLM advisor

Belfegor packages the LLM advisor code inside the mod jar and calls a local llama.cpp command-line runtime. The intended runtime layout is bundled with the Minecraft instance so users do not need a separate model server.

The current default model is:

```text
belfegor/models/Qwen3-1.7B-Q4_K_M.gguf
```

That model path points at a GGUF thinking model copied into the instance. Belfegor does not require a background model service.

## What it does

The advisor gives Belfegor a bounded â€œwhat should I do next?â€ brain. It does not click inventory slots or replace the task engine. It reads context and returns either chat text or a safe Belfegor command.

It writes/reads these files under `.minecraft/belfegor/`:

| File | Purpose |
|---|---|
| `llm_commands.md` | Full command catalogue exported from the live command registry. |
| `llm_context.json` | Current goal, player state, inventory, remembered shulkers, last action, and planned action. |
| `llm_prompt.txt` | Prompt sent to llama.cpp. |
| `llm_response.json` | Parsed model response. |
| `llm_actions.log` | Running log of actions and reactions. |

## Setup

1. Place the llama.cpp runtime in the instance:

```powershell
.minecraft\belfegor\llama.cpp\llama-cli.exe
```

2. Place the GGUF model in the instance:

```powershell
.minecraft\belfegor\models\Qwen3-1.7B-Q4_K_M.gguf
```

3. Edit `.minecraft/belfegor/belfegor_settings.json`:

```json
{
  "llmAdvisorEnabled": true,
  "llmAdvisorInPlayerMode": true,
  "llmAdvisorCanChat": true,
  "llmLlamaCppExecutable": "",
  "llmLlamaModelPath": "belfegor/models/Qwen3-1.7B-Q4_K_M.gguf",
  "llmAdvisorCooldownSeconds": 90,
  "llmAdvisorTimeoutSeconds": 45,
  "llmContextSize": 8192,
  "llmMaxTokens": 384
}
```

Leave `llmLlamaCppExecutable` blank to use the bundled default path. Set it only if you want to point at a custom `llama-cli` binary.

4. Run:

```text
@reload_settings
```

## Commands

Ask the advisor directly:

```text
@ai "what should I do next?"
@ai "why am I stuck?"
```

The first call queues the model request. A later `@ai` call prints any completed response and queues another prompt.

## Player mode

When `llmAdvisorInPlayerMode` is enabled, `@player` periodically asks the model for the next command based on the current phase and context. The model can see:

- full command list and examples;
- current phase and fallback plan;
- goal, last action, and planned action;
- position, health, hunger, dimension, and home base;
- inventory contents;
- remembered shulker contents;
- remembered base rooms, halls, module progress, and spatial-awareness snapshots.

The returned command must:

- start with the configured command prefix, normally `@`;
- exist in the live command registry;
- not be on the automatic denylist.

Denied automatic commands currently include:

```text
@stop
@reload_settings
@craftaudit
@test
@ai
@player
@build
@home
```

If llama.cpp is unavailable, busy, times out, returns an invalid command, or the task/inventory lane is busy, player mode continues with its deterministic fallback behavior. Valid advisor commands are deferred instead of injected into the middle of active tasks.

