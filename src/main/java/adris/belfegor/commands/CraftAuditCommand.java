package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.Arg;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandException;
import adris.belfegor.tasks.development.CraftAuditTask;

public class CraftAuditCommand extends Command {

    public CraftAuditCommand() throws CommandException {
        super("craftaudit",
                "Developer tool: offline recipe audit that /gives leaf resources, crafts items, stores outputs, and logs failures.",
                new Arg(String.class, "target item or all", "all", 0),
                new Arg(Integer.class, "limit", 0, 1));
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) throws CommandException {
        String target = parser.get(String.class);
        Integer limit = parser.get(Integer.class);
        mod.runUserTask(new CraftAuditTask(target, limit == null ? 0 : limit), this::finish);
    }
}
