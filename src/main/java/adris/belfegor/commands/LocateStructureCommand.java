package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.Arg;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandException;
import adris.belfegor.tasks.movement.GoToStrongholdPortalTask;
import adris.belfegor.tasks.movement.LocateDesertTempleTask;

public class LocateStructureCommand extends Command {

    public LocateStructureCommand() throws CommandException {
        super("locate_structure", "Locate a world generated structure.", new Arg(Structure.class, "structure"));
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) throws CommandException {
        Structure structure = parser.get(Structure.class);
        switch (structure) {
            case STRONGHOLD:
                mod.runUserTask(new GoToStrongholdPortalTask(1), this::finish);
                break;
            case DESERT_TEMPLE:
                mod.runUserTask(new LocateDesertTempleTask(), this::finish);
                break;
        }
    }

    public enum Structure {
        DESERT_TEMPLE,
        STRONGHOLD
    }
}