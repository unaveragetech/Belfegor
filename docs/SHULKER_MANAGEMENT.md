# Shulker-box management

Belfegor treats carried shulker boxes as sub-inventories. This lets the bot keep the main inventory lighter while still using stored resources for future `@get` and crafting tasks.

## Core behavior

For `@shulker store ...` and `@shulker retrieve ...`, Belfegor performs a full transaction:

1. Selects the best carried shulker for the requested item(s).
2. Remembers its original inventory slot.
3. Places it nearby on solid ground.
4. Ensures there is no block above the shulker so it can be opened.
5. Opens the shulker.
6. Moves exact quantities with the inventory helper.
7. Scans/catalogs the open shulker contents twice.
8. Closes the shulker.
9. Mines it.
10. Picks it back up.
11. Restores it to the original inventory slot when possible.

This is intentionally slower than unsafe slot spam, but it is much safer for cursor state and container sync.

## Commands

```text
@shulker list
@shulker find diamond
@shulker store diamond 3
@shulker store [diamond 3, stick 16]
@shulker retrieve stick 8
@shulker auto on
@shulker auto off
@shulker auto status
@shulker auto timer
@shulker auto detection
@shulker auto run
```

## Auto shulker mode

Auto mode stores eligible ordinary inventory items into carried shulkers while the bot is idle.

Modes:

- `timer` — periodically redeposits eligible items after `autoShulkerTimerSeconds`.
- `detection` — sorts when inventory fill/new item detection crosses `autoShulkerInventoryThreshold`.

Auto mode excludes:

- shulker boxes;
- tools;
- weapons;
- armor;
- shields;
- bows/crossbows/tridents/maces;
- flint and steel;
- shears;
- fishing rods;
- damaged gear.

Shulkers are never stored inside shulkers.

## Memory file

Shulker memory is stored at:

```text
.minecraft/belfegor/belfegor_shulker_memory.json
```

The `C` UI shulker tab displays indexed shulkers and their known contents. `@shulker list` prints the same information in chat/log form.

## Crafting integration

When `@get` needs an ingredient and that item exists inside a carried shulker, Belfegor should retrieve from the shulker before gathering or crafting more. For example:

```text
@get diamond_shovel
```

If the diamond or sticks are inside a carried shulker, the resource task should place/open/retrieve/recatalog/pick up the shulker, then continue crafting.

## Debugging

Relevant debug tags in `belfegor_debug.log`:

- `SHULKER-STATE` — phase transitions.
- `SHULKER-TRANSFER` — individual moved item actions.
- `SHULKER-CATALOG` — catalog passes and contents.
- `SHULKER-ERROR` — failed shulker assumptions.
- `SHULKER-FORCE` — transaction lock prevented interruption.
- `CONTAINER-FORCE` — container pickup transaction lock prevented interruption.

If a shulker is being opened and closed repeatedly, search the log for `SHULKER-FORCE`, `TASK-STOP`, and `TASK-START` around the same timestamp to find the competing task.
