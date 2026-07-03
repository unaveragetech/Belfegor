# Audit Proof Recordings — 2026-07-02

This directory contains the full in-game proof recordings and matching audit output logs for the Belfegor craft and screen audit commands.

## Files

| File | Purpose | Result |
| --- | --- | --- |
| `craft_audit_full_2026-07-02.mp4` | Full recorded in-game run of `@craftaudit all`. | `DONE passed=799 failed=0` |
| `craft_audit_1783036904527.log` | Matching output log for the recorded craft audit run. | 799 craftable recipe targets passed. |
| `screen_audit_full_2026-07-02.mp4` | Full recorded in-game run of `@craftaudit screens`. | `DONE passed=9 failed=0 checks=9` |
| `screen_audit_1783039056680.log` | Matching output log for the recorded screen audit run. | 9 supported container/screen checks passed. |

## What the videos show

### Full craft audit

The craft audit video records Belfegor running:

```text
@craftaudit all
```

The audit iterates through the internal craftable-item registry, gives the exact required ingredients for each craftable target, executes the craft through Belfegor's normal crafting helpers, verifies that the output appears in inventory, clears state, and continues to the next item.

The matching log ends with:

```text
DONE passed=799 failed=0
STOP interrupt=null passed=799 failed=0
```

### Full screen audit

The screen audit video records Belfegor running:

```text
@craftaudit screens
```

The audit places supported screen fixtures on a dry test pad, opens each screen/container, validates the expected screen handler, closes it, resets the fixture area, and continues through the screen list.

The matching log ends with:

```text
DONE passed=9 failed=0 checks=9
STOP interrupt=null passed=9 failed=0
```

## Media metadata

| File | Size | Duration |
| --- | ---: | ---: |
| `craft_audit_full_2026-07-02.mp4` | 227.39 MB | 2132.9 seconds |
| `screen_audit_full_2026-07-02.mp4` | 2.87 MB | 32.8 seconds |

## SHA-256 checksums

```text
92D0940A02032806D1F1F8B0905C22797324B1F0AD7507A77FC19EB5D3CA1C28  craft_audit_1783036904527.log
9A3FFB2CFC70331DE9817723878D794D55DFD74C31A9BE4481E091C62F2A16EE  craft_audit_full_2026-07-02.mp4
30D7CB0E0D3719EFA021AAEE0AE123160843C70DB7CDCAC8F549EB303AD38DF8  screen_audit_1783039056680.log
13E58282B43A6DC9321C368AF8F87D589BBE5D754169E5594B7139930E0A7C88  screen_audit_full_2026-07-02.mp4
```

## Notes

The recordings were captured from the Minecraft 1.21.4 test instance after rebuilding and installing the current Belfegor jar. The logs in this directory are the exact audit output files produced during the recorded runs.
