package adris.belfegor.debug;

import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Centralized file-based debug logger for Belfegor.
 * Logs all slot interactions, task transitions, crafting steps, and bot actions
 * to belfegor/belfegor_debug.log in the game directory.
 *
 * Thread-safe: uses a concurrent queue and flushes on the main thread.
 */
public class DebugLogger {

    private static DebugLogger _instance;
    private static final String LOG_FILE = "belfegor_debug.log";
    private static final String FOLDER = "belfegor";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final ConcurrentLinkedQueue<String> _queue = new ConcurrentLinkedQueue<>();
    private BufferedWriter _writer;
    private boolean _enabled = true;

    private DebugLogger() {
    }

    public static DebugLogger getInstance() {
        if (_instance == null) {
            _instance = new DebugLogger();
        }
        return _instance;
    }

    /**
     * Initialize with the game directory. Call once at startup.
     */
    public void init(File gameDir) {
        try {
            File dir = new File(gameDir, FOLDER);
            dir.mkdirs();
            File logFile = new File(dir, LOG_FILE);
            _writer = new BufferedWriter(new FileWriter(logFile, true), 8192);
            log("SYSTEM", "=== DebugLogger initialized ===");
            log("SYSTEM", "Log file: " + logFile.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("[Belfegor] Failed to open debug log: " + e.getMessage());
            _enabled = false;
        }
    }

    /**
     * Log a slot interaction (click).
     */
    public void slotClick(String handler, int windowSlot, int button, SlotActionType type, ItemStack cursorBefore, ItemStack slotBefore) {
        if (!_enabled) return;
        String cursorName = cursorBefore.isEmpty() ? "empty" : cursorBefore.getCount() + "x " + cursorBefore.getItem().getName().getString();
        String slotName = slotBefore.isEmpty() ? "empty" : slotBefore.getCount() + "x " + slotBefore.getItem().getName().getString();
        String buttonName = button == 0 ? "LEFT" : button == 1 ? "RIGHT" : "MID";
        String typeName = type.name();
        _queue.offer(String.format("[SLOT] %s click slot=%d %s cursor_before=[%s] slot_before=[%s]",
                handler, windowSlot, typeName, cursorName, slotName));
    }

    /**
     * Log a slot interaction with force override.
     */
    public void slotClickForce(String handler, int windowSlot, int button, SlotActionType type, ItemStack cursorBefore, ItemStack slotBefore) {
        if (!_enabled) return;
        String cursorName = cursorBefore.isEmpty() ? "empty" : cursorBefore.getCount() + "x " + cursorBefore.getItem().getName().getString();
        String slotName = slotBefore.isEmpty() ? "empty" : slotBefore.getCount() + "x " + slotBefore.getItem().getName().getString();
        String buttonName = button == 0 ? "LEFT" : button == 1 ? "RIGHT" : "MID";
        _queue.offer(String.format("[SLOT-FORCE] %s click slot=%d %s cursor_before=[%s] slot_before=[%s]",
                handler, windowSlot, type.name(), cursorName, slotName));
    }

    /**
     * Log a slot click that was blocked by the timer.
     */
    public void slotClickBlocked(String handler, int windowSlot, SlotActionType type) {
        if (!_enabled) return;
        _queue.offer(String.format("[SLOT-BLOCKED] %s click slot=%d %s (timer not elapsed)",
                handler, windowSlot, type.name()));
    }

    /**
     * Log task lifecycle events.
     */
    public void taskStart(String taskName) {
        log("TASK-START", taskName);
    }

    public void taskStop(String taskName, String reason) {
        log("TASK-STOP", taskName + " reason=" + reason);
    }

    public void taskInterrupt(String taskName, String byTask) {
        log("TASK-INTERRUPT", taskName + " interrupted by " + byTask);
    }

    /**
     * Log crafting-specific actions.
     */
    public void craftStep(String step, String detail) {
        log("CRAFT", step + " | " + detail);
    }

    public void craftPhase(String phase) {
        log("CRAFT-PHASE", phase);
    }

    /**
     * Log inventory state changes.
     */
    public void inventoryState(String cursor, String grid, String output) {
        if (!_enabled) return;
        _queue.offer(String.format("[INV] cursor=[%s] grid=[%s] output=[%s]", cursor, grid, output));
    }

    /**
     * Log resource collection.
     */
    public void resourceCollect(String item, int count, String source) {
        log("RESOURCE", "collect " + count + "x " + item + " from " + source);
    }

    /**
     * Log pathfinding / movement.
     */
    public void movement(String action, BlockPos target) {
        log("MOVE", action + " to " + target);
    }

    /**
     * Log generic messages.
     */
    public void log(String category, String message) {
        if (!_enabled) return;
        _queue.offer(String.format("[%s] %s", category, message));
    }

    /**
     * Flush all queued log entries to disk. Call once per tick on main thread.
     */
    public synchronized void flush() {
        if (_writer == null || _queue.isEmpty()) return;
        try {
            String time = LocalDateTime.now().format(TIME_FMT);
            String line;
            while ((line = _queue.poll()) != null) {
                _writer.write(time + " " + line);
                _writer.newLine();
            }
            _writer.flush();
        } catch (IOException e) {
            System.err.println("[Belfegor] Failed to flush debug log: " + e.getMessage());
        }
    }

    /**
     * Write directly from a watchdog/background thread. Use only for diagnostics
     * that must survive a blocked render tick.
     */
    public synchronized void logImmediate(String category, String message) {
        if (!_enabled || _writer == null) return;
        try {
            String time = LocalDateTime.now().format(TIME_FMT);
            _writer.write(time + " [" + category + "] " + message);
            _writer.newLine();
            _writer.flush();
        } catch (IOException e) {
            System.err.println("[Belfegor] Failed immediate debug write: " + e.getMessage());
        }
    }

    /**
     * Close the log file.
     */
    public void close() {
        if (_writer != null) {
            try {
                log("SYSTEM", "=== DebugLogger shutting down ===");
                flush();
                _writer.close();
            } catch (IOException e) {
                // ignore
            }
            _writer = null;
        }
    }

    public boolean isEnabled() {
        return _enabled;
    }
}
