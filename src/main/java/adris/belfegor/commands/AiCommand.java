package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandException;
import adris.belfegor.llm.LlmAdvisor;

/**
 * Developer/user-facing entry point for the local llama.cpp advisor.
 */
public class AiCommand extends Command {

    public AiCommand() throws CommandException {
        super("ai", "Ask the local llama.cpp advisor using current context. Quote prompts that contain spaces.");
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) throws CommandException {
        String prompt = String.join(" ", parser.getArgUnits()).trim();
        if (prompt.isBlank()) {
            prompt = "Given current context, summarize what Belfegor should do next.";
        }

        // Show any completed decision first, then queue a fresh request. The
        // result is delivered to game chat when it arrives.
        var completed = LlmAdvisor.getInstance().pollChatDecision();
        if (completed.isPresent()) {
            var decision = completed.get();
            logDecision(mod, decision);
        } else {
            boolean requested = LlmAdvisor.getInstance().requestChatDecision(mod, prompt);
            if (requested) {
                mod.log("AI advisor is thinking... the answer will appear in chat.");
            } else {
                String error = "LLM advisor could not start a request. "
                        + LlmAdvisor.getInstance().availabilityReport(mod)
                        + " Check llmAdvisorEnabled and the model/executable paths in belfegor_settings.json.";
                LlmAdvisor.getInstance().recordExchange("chat", prompt,
                        error, "", "", false);
                mod.log(error);
            }
        }
        finish();
    }

    /** Prints the parsed AI response (chat, command, reason) to game chat. */
    public static void logDecision(Belfegor mod, LlmAdvisor.AdvisorDecision decision) {
        if (mod == null || decision == null) return;
        if (!decision.chat().isBlank()) mod.log("AI: " + decision.chat());
        if (!decision.command().isBlank()) mod.log("AI command: " + decision.command());
        if (!decision.reason().isBlank()) mod.log("AI reason: " + decision.reason());
    }

    @Override
    public java.util.List<String> getExamples() {
        return java.util.List.of("@ai \"what should I do next?\"", "@ai \"why am I stuck?\"");
    }

    @Override
    public String getDetailedDescription() {
        return "Asks the local llama.cpp advisor for help using the exported command catalogue, "
                + "current context snapshot, inventory, shulker memory, last action, planned action, "
                + "goal, and running action/reaction log. The response is printed to game chat "
                + "(chat text plus the parsed command and reason) and is listed in the AI tab of the C menu. "
                + "Requires llmAdvisorEnabled=true and a local GGUF model at llmLlamaModelPath.";
    }
}
