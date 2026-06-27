package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.Debug;
import adris.belfegor.commandsystem.Arg;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandException;
import net.minecraft.client.MinecraftClient;

public class SetGammaCommand extends Command {

    public SetGammaCommand() throws CommandException {
        super("gamma", "Sets the brightness to a value", new Arg<>(Double.class, "gamma", 1.0, 0));
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) throws CommandException {
        double gammaValue = parser.get(Double.class);
        Debug.logMessage("Gamma set to " + gammaValue);
        MinecraftClient.getInstance().options.getGamma().setValue(gammaValue);
    }
}
