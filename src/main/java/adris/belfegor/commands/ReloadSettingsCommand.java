package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.util.helpers.ConfigHelper;

public class ReloadSettingsCommand extends Command {
    public ReloadSettingsCommand() {
        super("reload_settings", "Reloads bot settings and butler whitelist/blacklist.");
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) {
        ConfigHelper.reloadAllConfigs();
        mod.log("Reload successful!");
        finish();
    }
}