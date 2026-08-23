# MLG fall clutches

Belfegor automatically attempts an MLG clutch whenever you start falling with
the auto-MLG settings enabled (`autoMLGBucket` in `belfegor_settings.json`).

## Configuration

The clutch item list is stored in:

```text
belfegor/configs/mlg_clutch_settings.json
```

The file is created with defaults on first launch, relative to the game's
working directory. Item ids may be written with or without the `minecraft:`
namespace.

Example:

```json
{
  "clutchItems": [
    "water_bucket", "powder_snow_bucket", "slime_block", "honey_block",
    "hay_block", "scaffolding", "cobweb", "ladder", "twisting_vines",
    "weeping_vines", "totem_of_undying", "ender_pearl", "white_bed",
    "orange_bed", "magenta_bed", "light_blue_bed", "yellow_bed", "lime_bed",
    "pink_bed", "gray_bed", "light_gray_bed", "cyan_bed", "purple_bed",
    "blue_bed", "brown_bed", "green_bed", "red_bed", "black_bed"
  ]
}
```

## How each item is used

| Item | Behavior |
|---|---|
| `water_bucket` | Place water into the block you will fall into. |
| `powder_snow_bucket` | Place powder snow into the block you will fall into; you sink and take no fall damage. |
| `slime_block` | Place on the landing block; you bounce and take no fall damage. |
| `honey_block` | Place on the landing block; fall damage is reduced to 20%. |
| `hay_block` | Place on the landing block; fall damage is reduced to 20%. |
| `scaffolding` | Place on the landing block and hold jump to grab/climb it. |
| `cobweb` | Place into your fall column; the cobweb stops your fall. |
| `ladder` | Place against a side of the landing block, steer into it, and hold jump to climb. |
| `twisting_vines` | Place on the landing block and hold jump to climb. |
| `weeping_vines` | Place against a side of the landing block, steer into it, and hold jump to climb. |
| `totem_of_undying` | Equipped into the offhand; it activates automatically on a lethal fall. When a block clutch is also available, both are used. |
| `ender_pearl` | Thrown steeply downward 9-18 blocks above impact; the teleport cancels the fall. |
| beds (all 16 colors) | Placed as a 2-block bed with the head in an empty cell; beds cut fall damage in half and bounce you. |
| `sweet_berries` | Not a fall clutch; ignored with a one-time warning. |

## Selection order

When several configured items are available, the bot uses the most effective
one rather than the first item in the config list:

```text
water > powder snow > slime > cobweb > honey/hay > scaffolding >
vines/ladder > bed > totem > ender pearl
```

Clutches are verified after placement and retried on a short cooldown; once the
clutch is in place the bot stops clicking so it never stacks blocks upward.
