package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;
import com.mojang.logging.LogUtils;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;

/**
 * glance: asks the game window for a vision_width x vision_height picture from the body's eyes and waits for it, at
 * most {@link #TIMEOUT_TICKS} ({@code failed:render_timeout}). AgentLoop already admitted this glance (one per turn,
 * then the cooldown), so there is no gate here. The picture is made on the client thread; this handle only polls for
 * it on the server thread.
 */
final class GlanceHandle implements ActionRunner.Handle {
    private static final Logger LOG = LogUtils.getLogger();
    /** 2 s. The window waits about 0.2 s before it renders (see GlanceRenderer). */
    static final int TIMEOUT_TICKS = 40;

    private final CompletableFuture<byte[]> picture;
    private final int width;
    private final int height;
    private int ticks;

    private GlanceHandle(CompletableFuture<byte[]> picture, int width, int height) {
        this.picture = picture;
        this.width = width;
        this.height = height;
    }

    /** {@code failed:no_client} on a dedicated server, where no game window can render. */
    static ActionRunner.Handle start(AgentRuntime rt, AgentPlayer body) {
        Glance.Camera camera = Glance.camera();
        if (camera == null) return ActionRunner.Handle.done(ActionResult.failed("no_client"));
        int w = rt.settings().visionWidth();
        int h = rt.settings().visionHeight();
        return new GlanceHandle(camera.capture(body.getId(), w, h), w, h);
    }

    @Override
    public ActionResult poll() {
        if (picture.isDone()) {
            try {
                byte[] png = picture.join();
                LOG.info("glance {}x{} {}B", width, height, png.length);
                return ActionResult.picture(png);
            } catch (CompletionException | CancellationException e) {
                String code = Glance.code(e);
                LOG.info("glance failed: {}", code);
                return ActionResult.failed(code);
            }
        }
        if (++ticks < TIMEOUT_TICKS) return null;
        LOG.info("glance failed: render_timeout");
        return ActionResult.failed("render_timeout");
    }

    @Override
    public int timeoutTicks() {
        return TIMEOUT_TICKS + 20;
    }
}
