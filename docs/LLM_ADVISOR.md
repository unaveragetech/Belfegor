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
| `llm_commands.md` | Human-readable command catalogue exported from the live command registry. |
| `llm_commands.json` | MCP-style JSON command catalogue (name, category, description, arguments, examples) that the model reads at runtime. |
| `llm_context.json` | Current goal, task status, player state, inventory, stored-at-base counts, errands, game plan, remembered shulkers, last action, and planned action. |
| `llm_prompt.txt` | Prompt sent to llama.cpp. |
| `llm_response.json` | Model response repaired into valid JSON (command/chat/goal/reason), even when the model wraps it in prose or fences. |
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
  "llmAdvisorCooldownSeconds": 60,
  "llmAdvisorTimeoutSeconds": 30,
  "llmContextSize": 8192,
  "llmMaxTokens": 320
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

`@ai` prints `AI advisor is thinking...` immediately. When llama.cpp finishes, the answer is printed to game chat automatically (`AI: ...`, `AI command: ...`, `AI reason: ...`) - there is no panel and no need to run `@ai` again. If a previous answer is still pending, the next `@ai` call prints it right away.

## Runtime details

The advisor runs llama.cpp as a single-turn process (`-st`) with reasoning disabled (`--reasoning off`) and stdin closed, so it exits after answering instead of hanging in the REPL. The default timeout is 30 seconds and the default token budget is 320. Responses are repaired into JSON (balanced-object scan, then key:value rebuild) and the command is validated against the live registry before it is ever executed.

Chat-mode decisions (`@ai`) are consumed only by the chat delivery path, and player-mode decisions only by `@player`, so one cannot steal the other's answer. Every exchange is also recorded in the AI tab of the `C` control panel.

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
```

If llama.cpp is unavailable, busy, times out, returns an invalid command, or the task/inventory lane is busy, player mode continues with its deterministic fallback behavior. Valid advisor commands are deferred instead of injected into the middle of active tasks.

