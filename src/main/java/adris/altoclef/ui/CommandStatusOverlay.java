package adris.altoclef.ui;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

public class CommandStatusOverlay {

    private long _timeRunning;
    private long _lastTime = 0;
    private DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.from(ZoneOffset.of("+00:00")));

    public void render(AltoClef mod, DrawContext ctx) {
        if (MinecraftClient.getInstance().currentScreen != null) return;
        if (!mod.getModSettings().shouldShowTaskChain()) return;

        List<Task> tasks = Collections.emptyList();
        if (mod.getTaskRunner().getCurrentTaskChain() != null) {
            tasks = mod.getTaskRunner().getCurrentTaskChain().getTasks();
        }

        var tr = MinecraftClient.getInstance().textRenderer;
        int color = 0xFFFFFFFF;
        int x = 2;
        int y = 2;

        if (tasks.isEmpty()) {
            ctx.drawTextWithShadow(tr, " (no task running) ", x, y, color);
            if (_lastTime + 10000 < Instant.now().toEpochMilli() && mod.getModSettings().shouldShowTimer()) {
                _timeRunning = Instant.now().toEpochMilli();
            }
        } else {
            int fontHeight = tr.fontHeight;
            if (mod.getModSettings().shouldShowTimer()) {
                _lastTime = Instant.now().toEpochMilli();
                String _realTime = DATE_TIME_FORMATTER.format(Instant.now().minusMillis(_timeRunning));
                ctx.drawTextWithShadow(tr, "<" + _realTime + ">", x, y, color);
                y += fontHeight + 2;
            }
            int maxLines = 10;
            if (tasks.size() > maxLines) {
                for (int i = 0; i < tasks.size(); i++) {
                    if (i == 0 || i > tasks.size() - maxLines) {
                        ctx.drawTextWithShadow(tr, tasks.get(i).toString(), x, y, color);
                    } else if (i == 1) {
                        ctx.drawTextWithShadow(tr, " ... ", x, y, color);
                    } else {
                        continue;
                    }
                    y += fontHeight + 2;
                }
            } else {
                for (Task task : tasks) {
                    ctx.drawTextWithShadow(tr, task.toString(), x, y, color);
                    y += fontHeight + 2;
                }
            }
        }
    }
}
