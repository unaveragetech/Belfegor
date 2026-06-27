package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.tasks.speedrun.BeatMinecraft2Task;

public class GamerCommand extends Command {
    public GamerCommand() {
        super("gamer", "Beats the game");
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) {
        mod.runUserTask(new BeatMinecraft2Task(), this::finish);
    }
}
