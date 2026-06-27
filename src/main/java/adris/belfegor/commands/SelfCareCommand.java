package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.tasks.entity.SelfCareTask;

public class SelfCareCommand extends Command {
    public SelfCareCommand() {
        super("selfcare", "Care for self (Not finished and tested yet)");
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) {
        mod.runUserTask(new SelfCareTask(), this::finish);
    }
}
