# Installation guide

This guide is for installing Belfegor as a local Fabric client mod for Minecraft `1.21.4`.

## Requirements

| Requirement | Version / note |
|---|---|
| Minecraft | `1.21.4` |
| Mod loader | Fabric Loader `0.16.10` or compatible |
| API | Fabric API for Minecraft `1.21.4` |
| Belfegor jar | `releases/belfegor-1.21.4-beta1.jar` |
| Java | Java 21 for development/building; launcher runtime must support MC `1.21.4` |

## Standard launcher setup

1. Install Fabric Loader for Minecraft `1.21.4`.
2. Install Fabric API for Minecraft `1.21.4`.
3. Open your Minecraft folder:

   ```text
   .minecraft/
   ```

4. Create `mods/` if it does not exist.
5. Copy the jar:

   ```text
   releases/belfegor-1.21.4-beta1.jar
   ```

   into:

   ```text
   .minecraft/mods/
   ```

6. Launch the Fabric profile.

## MultiMC / Prism Launcher setup

1. Create or select a Minecraft `1.21.4` instance.
2. Install Fabric Loader for that instance.
3. Add Fabric API to the instance mods.
4. Open the instance folder.
5. Copy:

   ```text
   belfegor-1.21.4-beta1.jar
   ```

   into:

   ```text
   <instance>/.minecraft/mods/
   ```

6. Launch the instance.

## First launch verification

After Minecraft loads, Belfegor should create:

```text
.minecraft/belfegor/
```

Inside that folder you should eventually see:

```text
belfegor_settings.json
belfegor_debug.log
belfegor_shulker_memory.json
```

Open chat and try:

```text
@help
@status
@coords
```

Then test a small task:

```text
@get crafting_table
```

## UI verification

Press:

```text
C
```

You should see the Belfegor UI with tabs for tasks, commands, settings, shulkers, and logs.

## Emergency abort

If a task behaves badly:

```text
@stop
```

or press:

```text
+
```

## Common setup mistakes

| Problem | Fix |
|---|---|
| Minecraft crashes on load | Confirm Minecraft, Fabric Loader, Fabric API, and Belfegor are all for `1.21.4`. |
| Commands do nothing | Confirm the prefix is `@` in `belfegor_settings.json` and that the mod loaded. |
| No `belfegor/` folder appears | The mod did not initialize; check the Minecraft log and mod list. |
| Build fails locally | Make sure Java 21 is used and `../baritone/dist/baritone-api.jar` exists for development builds. |
| In-game UI does not open | Check keybind conflicts for `C`. |
| Bot pathing is erratic | Check Baritone/Fabric compatibility and avoid stale old Baritone configs from unrelated installs. |

## Updating

1. Stop Minecraft.
2. Replace the old Belfegor jar in `mods/`.
3. Keep your `.minecraft/belfegor/` folder if you want settings and memory preserved.
4. Launch again.
5. Run:

   ```text
   @reload_settings
   ```

   if you edited settings by hand.

## Clean reset

To reset generated Belfegor state:

1. Stop Minecraft.
2. Back up `.minecraft/belfegor/` if needed.
3. Delete or rename `.minecraft/belfegor/`.
4. Launch Minecraft again.

Belfegor will recreate default files.
