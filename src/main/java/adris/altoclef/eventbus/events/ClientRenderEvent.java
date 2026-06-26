package adris.altoclef.eventbus.events;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

public class ClientRenderEvent {
    public DrawContext context;
    public RenderTickCounter tickDelta;

    public ClientRenderEvent(DrawContext context, RenderTickCounter tickDelta) {
        this.context = context;
        this.tickDelta = tickDelta;
    }
}
