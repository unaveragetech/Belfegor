package adris.belfegor;

import adris.belfegor.commands.*;
import adris.belfegor.commandsystem.CommandException;

/**
 * Initializes Belfegor's built in commands.
 */
public class BelfegorCommands {

    public BelfegorCommands() throws CommandException {
        // List commands here
        Belfegor.getCommandExecutor().registerNewCommand(
                new HelpCommand(),
                new UiCommand(),
                new GetCommand(),
                new FollowCommand(),
                new GiveCommand(),
                new EquipCommand(),
                new DepositCommand(),
                new StashCommand(),
                new GotoCommand(),
                new IdleCommand(),
                new CoordsCommand(),
                new StatusCommand(),
                new InventoryCommand(),
                new LocateStructureCommand(),
                new StopCommand(),
                new TestCommand(),
                new FoodCommand(),
                new MeatCommand(),
                new ReloadSettingsCommand(),
                new GamerCommand(),
                new MarvionCommand(),
                new PunkCommand(),
                new HeroCommand(),
                new SetGammaCommand(),
                new ListCommand(),
                new CoverWithSandCommand(),
                new CoverWithBlocksCommand(),
                new SelfCareCommand(),
                new PvpCommand(),
                new StackedCommand(),
                new PlayerCommand(),
                new CampCommand(),
                new BuildCommand(),
                new HomeCommand(),
                new BaritoneCommand(),
                new ToolSetCommand(),
                new StoreCommand(),
                new RetrieveCommand(),
                new ShulkerCommand(),
                new AiCommand(),
                new CraftAuditCommand()
        );
    }
}
