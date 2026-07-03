package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.Arg;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandException;
import adris.belfegor.tasks.development.CraftAuditTask;
import adris.belfegor.tasks.development.ScreenAuditTask;

public class CraftAuditCommand extends Command {

    public CraftAuditCommand() throws CommandException {
        super("craftaudit",
                "Developer tool: recipe audit or screen audit. Use screens, a target item, or all.",
                new Arg(String.class, "target item, screens, or all", "all", 0),
                new Arg(Integer.class, "limit", 0, 1));
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) throws CommandException {
        String target = parser.get(String.class);
        Integer limit = parser.get(Integer.class);
        if (target != null && target.equalsIgnoreCase("screens")) {
            mod.runUserTask(new ScreenAuditTask(), this::finish);
            return;
        }
        mod.runUserTask(new CraftAuditTask(target, limit == null ? 0 : limit), this::finish);
    }
}
