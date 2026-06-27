package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.tasks.development.CraftAuditTask;

public class CraftAuditCommand extends Command {

    public CraftAuditCommand() throws CommandException {
        super("craftaudit",
                "Developer tool: offline recipe audit that /gives leaf resources, crafts items, stores outputs, and logs failures.",
                new Arg(String.class, "target item or all", "all", 0),
                new Arg(Integer.class, "limit", 0, 1));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String target = parser.get(String.class);
        Integer limit = parser.get(Integer.class);
        mod.runUserTask(new CraftAuditTask(target, limit == null ? 0 : limit), this::finish);
    }
}
