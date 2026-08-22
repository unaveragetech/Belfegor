package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandException;
import adris.belfegor.memory.GamePlanMemory;
import adris.belfegor.tasks.speedrun.GamePlanTask;

import java.util.Locale;
import java.util.Optional;

/**
 * @goal [start|stop|<stage>]
 *
 * Shows or drives the persistent long-term game plan. The plan is modeled on
 * the classic @gamer beat-the-game route but tracked stage by stage so the bot
 * always has a concrete next goal and its progress survives restarts.
 */
public class GoalCommand extends Command {

    public GoalCommand() throws CommandException {
        super("goal", "Show or drive the long-term game plan. Usage: @goal, @goal start, @goal stop, @goal <stage>");
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) throws CommandException {
        String[] args = parser.getArgUnits();
        String action = args.length == 0
                ? "status"
                : String.join("_", args).trim().toLowerCase(Locale.ROOT);
        switch (action) {
            case "start", "resume", "run", "continue", "begin" -> {
                mod.runUserTask(new GamePlanTask(), this::finish);
                return;
            }
            case "stop", "cancel", "halt" -> {
                mod.getUserTaskChain().cancel(mod);
                finish();
                return;
            }
            case "next", "current", "active", "hint" -> {
                GamePlanMemory memory = GamePlanMemory.getInstance();
                memory.ensureStages();
                Optional<GamePlanMemory.GameStage> stage = memory.nextStage();
                if (stage.isEmpty()) {
                    mod.log("Game plan complete! All stages done.");
                    finish();
                    return;
                }
                mod.log("Active stage: [" + stage.get().id + "] " + stage.get().name
                        + (memory.isActive() ? " (plan active)"
                        : " (plan paused - run @goal start)"));
                mod.log("Needed: " + new GamePlanTask().stageHint(mod, stage.get()));
                finish();
                return;
            }
            default -> {
                GamePlanMemory memory = GamePlanMemory.getInstance();
                memory.ensureStages();
                if (memory.getStage(action).isPresent()) {
                    mod.runUserTask(new GamePlanTask(action), this::finish);
                    return;
                }
                if (!action.equals("status") && !action.equals("list") && !action.equals("show")) {
                    throw new CommandException("Unknown game plan action or stage `" + action
                            + "`. Use @goal, @goal start, @goal stop, or @goal <stage>.");
                }
                mod.log("Game plan " + (memory.isActive() ? "ACTIVE" : "inactive")
                        + " completed=" + memory.completedCount()
                        + "/" + memory.getStages().size());
                for (GamePlanMemory.GameStage stage : memory.getStages()) {
                    mod.log("[" + stage.id + "] " + stage.status
                            + (stage.note.isBlank() ? "" : " - " + stage.note)
                            + " :: " + stage.description);
                }
                finish();
            }
        }
    }

    @Override
    public java.util.List<String> getExamples() {
        return java.util.List.of("@goal", "@goal next", "@goal start", "@goal resume", "@goal stop", "@goal iron_tools");
    }

    @Override
    public String getDetailedDescription() {
        return "Displays the persistent game plan and its stage statuses. "
                + "@goal next shows the active stage and exactly what it still needs; "
                + "@goal start resumes the plan at the first unfinished stage; "
                + "@goal <stage> starts from a specific stage (wood_tools, stone_tools, iron_tools, "
                + "diamond_tools, food_supply, base_camp, base_expansion, nether_resources, stronghold, end_dragon); "
                + "@goal stop halts the plan.";
    }
}
