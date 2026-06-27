package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.llm.LlmAdvisor;
import adris.altoclef.ui.MessagePriority;

/**
 * Developer/user-facing entry point for the local Ollama advisor.
 */
public class AiCommand extends Command {

    public AiCommand() throws CommandException {
        super("ai", "Ask the local Ollama advisor using current context. Quote prompts that contain spaces.");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        var completed = LlmAdvisor.getInstance().pollDecision();
        if (completed.isPresent()) {
            var decision = completed.get();
            if (!decision.chat().isBlank()) {
                mod.log("AI: " + decision.chat(), MessagePriority.OPTIONAL);
            }
            if (!decision.command().isBlank()) {
                mod.log("AI suggested command: " + decision.command(), MessagePriority.OPTIONAL);
            }
            if (!decision.reason().isBlank()) {
                mod.log("AI reason: " + decision.reason(), MessagePriority.OPTIONAL);
            }
        }
        String prompt = String.join(" ", parser.getArgUnits()).trim();
        if (prompt.isBlank()) {
            prompt = "Given current context, summarize what Belfegor should do next.";
        }
        boolean requested = LlmAdvisor.getInstance().requestChatDecision(mod, prompt);
        if (!requested) {
            mod.log("LLM advisor is disabled, busy, or missing llmOllamaModel. Check belfegor_settings.json and ollama list.",
                    MessagePriority.OPTIONAL);
        } else {
            mod.log("LLM advisor request queued. Watch belfegor/llm_actions.log and run @ai again after it responds.",
                    MessagePriority.OPTIONAL);
        }
        finish();
    }

    @Override
    public java.util.List<String> getExamples() {
        return java.util.List.of("@ai \"what should I do next?\"", "@ai \"why am I stuck?\"");
    }

    @Override
    public String getDetailedDescription() {
        return "Asks the local Ollama advisor for help using the exported command catalogue, "
                + "current context snapshot, inventory, shulker memory, last action, planned action, "
                + "goal, and running action/reaction log. Requires llmAdvisorEnabled=true and an installed llmOllamaModel.";
    }
}
