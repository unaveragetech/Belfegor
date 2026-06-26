package adris.altoclef.ui;

import java.util.ArrayList;
import java.util.List;

public class EventLogPanel {
    private static final int MAX_ENTRIES = 200;
    private final List<LogEntry> entries = new ArrayList<>();
    private int scrollOffset = 0;
    private String filter = "";

    public static class LogEntry {
        public final long timestamp;
        public final String message;
        public final LogLevel level;

        public LogEntry(String message, LogLevel level) {
            this.timestamp = System.currentTimeMillis();
            this.message = message;
            this.level = level;
        }
    }

    public enum LogLevel {
        INFO, WARNING, ERROR, DEBUG, TASK
    }

    public void addEntry(String message, LogLevel level) {
        entries.add(new LogEntry(message, level));
        if (entries.size() > MAX_ENTRIES) {
            entries.remove(0);
        }
    }

    public void info(String message) { addEntry(message, LogLevel.INFO); }
    public void warn(String message) { addEntry(message, LogLevel.WARNING); }
    public void error(String message) { addEntry(message, LogLevel.ERROR); }
    public void debug(String message) { addEntry(message, LogLevel.DEBUG); }
    public void task(String message) { addEntry(message, LogLevel.TASK); }

    public List<LogEntry> getFilteredEntries() {
        if (filter.isEmpty()) return entries;
        List<LogEntry> filtered = new ArrayList<>();
        for (LogEntry entry : entries) {
            if (entry.message.toLowerCase().contains(filter.toLowerCase())) {
                filtered.add(entry);
            }
        }
        return filtered;
    }

    public void setFilter(String filter) { this.filter = filter; }
    public String getFilter() { return filter; }
    public void scrollUp() { scrollOffset = Math.max(0, scrollOffset - 3); }
    public void scrollDown() { scrollOffset++; }
    public void scrollToBottom() {
        List<LogEntry> filtered = getFilteredEntries();
        scrollOffset = Math.max(0, filtered.size() - 20);
    }
    public int getScrollOffset() { return scrollOffset; }
    public void clear() { entries.clear(); scrollOffset = 0; }
}
