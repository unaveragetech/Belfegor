# Local Ollama LLM advisor

Belfegor packages the LLM advisor code inside the mod jar. The only external runtime requirement is Ollama with a local model installed.

The current default model is:

```text
lfm2.5-thinking:1.2b
```

That model was chosen because it is present in the local `ollama list` output and is small enough to be practical as an always-available planning helper.

## What it does

The advisor gives Belfegor a bounded “what should I do next?” brain. It does not click inventory slots or replace the task engine. It reads context and returns either chat text or a safe Belfegor command.

It writes/reads these files under `.minecraft/belfegor/`:

| File | Purpose |
|---|---|
| `llm_commands.md` | Full command catalogue exported from the live command registry. |
| `llm_context.json` | Current goal, player state, inventory, remembered shulkers, last action, and planned action. |
| `llm_prompt.txt` | Prompt sent to Ollama. |
| `llm_response.json` | Parsed model response. |
| `llm_actions.log` | Running log of actions and reactions. |

## Setup

1. Install and run Ollama.
2. Confirm the model exists:

```powershell
ollama list
```

3. If needed, pull the model:

```powershell
ollama pull lfm2.5-thinking:1.2b
```

4. Edit `.minecraft/belfegor/belfegor_settings.json`:

```json
{
  "llmAdvisorEnabled": true,
  "llmAdvisorInPlayerMode": true,
  "llmAdvisorCanChat": true,
  "llmOllamaExecutable": "ollama",
  "llmOllamaModel": "lfm2.5-thinking:1.2b",
  "llmAdvisorCooldownSeconds": 90,
  "llmAdvisorTimeoutSeconds": 45,
  "llmContextSize": 8192,
  "llmMaxTokens": 384
}
```

5. Run:

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
- remembered shulker contents.

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

If Ollama is unavailable, busy, times out, or returns an invalid command, player mode continues with its deterministic fallback behavior.
