package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;

public class StopCommand extends Command {

    public StopCommand() {
        super("stop", "Stop task runner (stops all automation)");
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) {
        mod.getUserTaskChain().cancel(mod);
        finish();
    }
}
