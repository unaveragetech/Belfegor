# Troubleshooting

Most Belfegor bugs are easiest to fix when the debug log shows the last few seconds before failure.

Primary log:

```text
.minecraft/belfegor/belfegor_debug.log
```

Crash reports:

```text
.minecraft/crash-reports/
```

## Emergency stop

Use either:

```text
@stop
```

or press:

```text
+
```

The `+` key is a global abort while a task is running.

## Common symptoms

| Symptom | Likely cause | What to inspect |
|---|---|---|
| Inventory open, cursor stuck on one slot | Interrupted slot transaction or cursor recovery failed. | `CRAFT-CURSOR`, `SCREEN-OPEN`, `TASK-STOP`. |
| Shulker opens/closes repeatedly | Competing tasks interrupting retrieval/storage. | `SHULKER-STATE`, `SHULKER-FORCE`, `TASK-START`, `TASK-STOP`. |
| Bot ignores item inside shulker | Shulker NBT/memory not synced or item name mismatch. | `belfegor_shulker_memory.json`, `SHULKER-CATALOG`, `@shulker list`. |
| Bot says recipe missing ingredient even though a variant exists | Recipe requires material unification/flexible ingredient support. | Recipe target in `TASK-START`, crafting logs. |
| Bot walks on shulker instead of opening it | Placement/opening task selected movement path or block above is blocked. | `SHULKER-STATE`, block above placement position. |
| Stack overflow crash | Recursive task creation or repeated progress checker creation. | Crash report stack trace. |
| Bot gets stuck switching between two tasks | Scheduler oscillation. | Alternating `TASK-STOP` / `TASK-START` pairs. |
| `C` does not open the Belfegor UI | Another client mod captured the key or a screen/overlay conflict replaced the panel. | Try `@ui`; both paths now call the same `openScreen()` method. |
| `@ui` logs but panel is not visible | Client-screen conflict or overlay mod is replacing/closing the custom screen. | Test in a cleaner profile; verify `BelfegorScreen` is excluded from screen auto-close; check other mods that open/replace screens. |
| `@status` or another `@` command shows a Baritone unknown-command error | Baritone consumed the message before Belfegor. | Current builds hook `ChatScreen.sendMessage` before Baritone; rebuild/reinstall and verify `@status` prints `[Belfegor] No tasks currently running.` |

## Reading a task oscillation

A healthy task chain has stretches of repeated work. A bad oscillation often looks like this:

```text
TASK-START A
TASK-STOP A reason=B
TASK-START B
TASK-STOP B reason=A
TASK-START A
TASK-STOP A reason=B
```

That means the scheduler thinks both tasks are urgent. The fix is usually one of:

- make one task finish its transaction before interruption;
- make one task use inventory-only checks instead of counting container/shulker items as usable;
- cache the active subtask until it is actually satisfied;
- add force-continuation while cursor or container screen is active.

## Useful debug tags

| Tag | Meaning |
|---|---|
| `TASK-START` | Task began running. |
| `TASK-STOP` | Task stopped or was interrupted. |
| `CRAFT-2X2` | Player-inventory crafting gate state. |
| `CRAFT-CURSOR` | Cursor recovery around crafting. |
| `SCREEN-OPEN` | Inventory screen opening diagnostics. |
| `SCREEN-WATCHDOG` | Screen opening took too long / render stack snapshot. |
| `SHULKER-STATE` | Shulker transaction phase transition. |
| `SHULKER-TRANSFER` | Shulker item movement. |
| `SHULKER-CATALOG` | Shulker scan result. |
| `SHULKER-ERROR` | Shulker-specific failure. |
| `SHULKER-FORCE` | Shulker transaction refused interruption. |
| `CONTAINER-FORCE` | Container pickup refused interruption. |

## Good bug report checklist

Include:

1. command you ran;
2. what you expected;
3. what happened;
4. whether the UI/cursor was stuck;
5. the last 300-800 lines of `belfegor_debug.log`;
6. crash report if the client crashed;
7. shulker memory file if the issue involves shulkers;
8. screenshot if the inventory screen was stuck.

## Quick self-checks

### Confirm command system

```text
@help
@ui
@status
@coords
```

### Confirm basic crafting

```text
@get oak_log 4
@get crafting_table
@get stick 4
```

### Confirm table crafting

```text
@get wooden_pickaxe
@get stone_pickaxe
```

### Confirm shulker memory

```text
@shulker list
@shulker store cobblestone 16
@shulker list
@shulker retrieve cobblestone 8
```

### Confirm higher-level tasking

```text
@toolset stone
@get diamond_shovel
@stacked
```
