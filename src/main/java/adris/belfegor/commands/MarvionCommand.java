package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.tasks.speedrun.MarvionBeatMinecraftTask;

public class MarvionCommand extends Command {
    public MarvionCommand() {
        super("marvion", "Beats the game (Marvion version)");
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) {
        mod.runUserTask(new MarvionBeatMinecraftTask(), this::finish);
    }
}