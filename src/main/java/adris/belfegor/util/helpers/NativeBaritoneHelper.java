package adris.belfegor.util.helpers;

import adris.belfegor.Belfegor;
import adris.belfegor.debug.DebugLogger;
import baritone.api.selection.ISelection;
import baritone.api.utils.BetterBlockPos;
import net.minecraft.util.math.BlockPos;

import java.util.Locale;
import java.util.Set;

/**
 * Small, explicit bridge to native Baritone features.
 *
 * Belfegor should keep repeatable automation in Java tasks, but exposing a
 * controlled subset of Baritone's own command/process/selection surface is
 * useful for debugging and for efficient region operations. This helper keeps
 * that bridge auditable instead of scattering raw "#..." strings through tasks.
 */
public final class NativeBaritoneHelper {

    private static final Set<String> SAFE_COMMANDS = Set.of(
            "help",
            "proc",
            "version",
            "modified",
            "modifiedsettings",
            "set",
            "sel",
            "selection",
            "s",
            "surface",
            "top",
            "pause",
            "resume",
            "paused",
            "forcecancel",
            "cancel",
            "build",
            "litematica"
    );

    private NativeBaritoneHelper() {
    }

    public static boolean execute(Belfegor mod, String commandLine) {
        if (mod == null || commandLine == null) return false;
        String normalized = normalize(commandLine);
        if (normalized.isBlank()) return false;
        String root = rootCommand(normalized);
        if (!SAFE_COMMANDS.contains(root)) {
            DebugLogger.getInstance().log("BARITONE-CMD",
                    "blocked command=" + normalized + " root=" + root);
            return false;
        }
        try {
            boolean result = mod.getClientBaritone().getCommandManager().execute(normalized);
            DebugLogger.getInstance().log("BARITONE-CMD",
                    "execute result=" + result + " command=" + normalized);
            return result;
        } catch (Throwable t) {
            DebugLogger.getInstance().log("BARITONE-CMD",
                    "error command=" + normalized + " message=" + t.getMessage());
            return false;
        }
    }

    public static void clearSelections(Belfegor mod, String reason) {
        try {
            ISelection[] removed = mod.getClientBaritone().getSelectionManager().removeAllSelections();
            DebugLogger.getInstance().log("BARITONE-SEL",
                    "clear reason=" + reason + " count=" + removed.length);
        } catch (Throwable t) {
            DebugLogger.getInstance().log("BARITONE-SEL",
                    "clear-error reason=" + reason + " message=" + t.getMessage());
        }
    }

    public static void selectBox(Belfegor mod, BlockPos from, BlockPos to, String reason) {
        if (mod == null || from == null || to == null) return;
        try {
            clearSelections(mod, "replace-before-" + reason);
            ISelection selection = mod.getClientBaritone().getSelectionManager().addSelection(
                    BetterBlockPos.from(from),
                    BetterBlockPos.from(to));
            DebugLogger.getInstance().log("BARITONE-SEL",
                    "select reason=" + reason
                            + " from=" + from.toShortString()
                            + " to=" + to.toShortString()
                            + " min=" + selection.min()
                            + " max=" + selection.max());
        } catch (Throwable t) {
            DebugLogger.getInstance().log("BARITONE-SEL",
                    "select-error reason=" + reason
                            + " from=" + from.toShortString()
                            + " to=" + to.toShortString()
                            + " message=" + t.getMessage());
        }
    }

    public static void logProcessState(Belfegor mod, String reason) {
        if (mod == null) return;
        try {
            DebugLogger.getInstance().log("BARITONE-PROC",
                    "reason=" + reason
                            + " pathing=" + mod.getClientBaritone().getPathingBehavior().isPathing()
                            + " builderActive=" + mod.getClientBaritone().getBuilderProcess().isActive()
                            + " builderPaused=" + mod.getClientBaritone().getBuilderProcess().isPaused()
                            + " mineActive=" + mod.getClientBaritone().getMineProcess().isActive()
                            + " exploreActive=" + mod.getClientBaritone().getExploreProcess().isActive()
                            + " customGoalActive=" + mod.getClientBaritone().getCustomGoalProcess().isActive());
        } catch (Throwable t) {
            DebugLogger.getInstance().log("BARITONE-PROC",
                    "error reason=" + reason + " message=" + t.getMessage());
        }
    }

    public static boolean runProc(Belfegor mod) {
        logProcessState(mod, "native-proc-command");
        return execute(mod, "proc");
    }

    private static String normalize(String commandLine) {
        String normalized = commandLine.trim();
        while (normalized.startsWith("#")) {
            normalized = normalized.substring(1).trim();
        }
        return normalized;
    }

    private static String rootCommand(String normalized) {
        String[] parts = normalized.split("\\s+", 2);
        return parts.length == 0 ? "" : parts[0].toLowerCase(Locale.ROOT);
    }
}
