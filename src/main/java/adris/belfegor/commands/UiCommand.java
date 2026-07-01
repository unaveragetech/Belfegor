package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandException;
import net.minecraft.client.MinecraftClient;

public class UiCommand extends Command {
    public UiCommand() {
        super("ui", "Open the Belfegor control panel");
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) throws CommandException {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(mod::openScreen);
        mod.log("Opening Belfegor control panel.");
        finish();
    }
}
