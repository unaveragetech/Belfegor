package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandException;

public class UiCommand extends Command {
    public UiCommand() {
        super("ui", "Open the Belfegor control panel");
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) throws CommandException {
        // Keep this command aligned with the keybind: the shared method owns the
        // client-thread handoff and screen instance.
        mod.log("Opening Belfegor control panel.");
        mod.openScreen();
        finish();
    }
}
