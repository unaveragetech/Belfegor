package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgBase;
import adris.altoclef.ui.MessagePriority;

public class HelpCommand extends Command {

    public HelpCommand() throws CommandException {
        super("help", "Lists every command, usage, input type, and accepted value",
                new Arg<>(String.class, "command", "", 0, false));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String requested = parser.get(String.class).trim().toLowerCase();
        String prefix = mod.getModSettings().getCommandPrefix();
        if (!requested.isEmpty()) {
            Command command = mod.getCommandExecutor().get(requested);
            if (command == null) {
                mod.log("Unknown command: " + requested, MessagePriority.OPTIONAL);
            } else {
                printCommand(mod, command, prefix);
            }
            finish();
            return;
        }

        mod.log("########## BELFEGOR COMMAND REFERENCE ##########", MessagePriority.OPTIONAL);
        mod.log("Use " + prefix + "help <command> for full argument details.", MessagePriority.OPTIONAL);
        var commands = new java.util.ArrayList<>(mod.getCommandExecutor().allCommands());
        commands.sort(java.util.Comparator.comparing(Command::getName));
        for (Command command : commands) {
            mod.log(prefix + command.getHelpRepresentation(), MessagePriority.OPTIONAL);
            mod.log("  " + command.getDetailedDescription(), MessagePriority.OPTIONAL);
            mod.log("  Examples: " + String.join(" | ", command.getExamples()),
                    MessagePriority.OPTIONAL);
        }
        mod.log("Item input: item | item count | [item count, item count]",
                MessagePriority.OPTIONAL);
        mod.log("Coordinates: x y z | x z | y, optionally with OVERWORLD/NETHER/END",
                MessagePriority.OPTIONAL);
        mod.log("Multiple commands may be separated with ';'. Global abort key: +",
                MessagePriority.OPTIONAL);
        mod.log("################################################", MessagePriority.OPTIONAL);
        finish();
    }

    private void printCommand(AltoClef mod, Command command, String prefix) {
        mod.log("Command: " + prefix + command.getHelpRepresentation(), MessagePriority.OPTIONAL);
        mod.log(command.getDetailedDescription(), MessagePriority.OPTIONAL);
        ArgBase[] arguments = command.getArguments();
        if (arguments.length == 0) {
            mod.log("Inputs: none", MessagePriority.OPTIONAL);
        } else {
            mod.log("Inputs:", MessagePriority.OPTIONAL);
            for (ArgBase argument : arguments) {
                mod.log(" - " + argument.getName() + " (" + argument.getTypeName() + ", "
                        + (argument.hasDefault() ? "optional" : "required") + "): "
                        + argument.getExpectedValues(), MessagePriority.OPTIONAL);
            }
        }
        mod.log("Runnable examples:", MessagePriority.OPTIONAL);
        for (String example : command.getExamples()) {
            mod.log(" - " + example, MessagePriority.OPTIONAL);
        }
    }
}
