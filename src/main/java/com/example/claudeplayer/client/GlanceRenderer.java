package com.example.claudeplayer.client;

import com.example.claudeplayer.body.Glance;
import com.example.claudeplayer.core.GlanceFrame;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import org.slf4j.Logger;

/**
 * glance's eyes: renders the level from an entity's eyes into an off-screen framebuffer and hands back a PNG. Client
 * side only; everything here runs on the client thread.
 *
 * <p>The render is vanilla's panorama screenshot path ({@code Minecraft.grabPanoramixScreenshot}): panoramic mode, an
 * off-screen TextureTarget, the window size set to the target's (the projection reads its shape from the window), then
 * {@code GameRenderer.renderLevel}. Panoramic mode is what keeps it off-screen: it turns off entity outlines and
 * Fabulous transparency, the only steps that switch back to the main framebuffer mid-render, and the hand. It also
 * fixes the field of view at 90 degrees, so {@link GlanceFrame} renders larger and keeps a 70 degree centre. The main
 * framebuffer, camera entity, camera, crosshair target and options are put back right after.
 *
 * <p>The sections the level renderer draws come from a background search from the camera that skips what it can't see
 * into, last run from the player's camera. From somewhere else (Claude in a tunnel, the player above) that list would
 * leave holes. So a capture first turns occlusion off ({@code Minecraft.smartCull}) and asks for a new search, renders
 * {@link #SEARCH_TICKS} client ticks later with every loaded section in the list, then turns occlusion back on.
 */
public final class GlanceRenderer implements Glance.Camera {
    private static final Logger LOG = LogUtils.getLogger();
    /** Client ticks between asking for a search without occlusion and rendering: 0.2 s, for the search to finish. */
    private static final int SEARCH_TICKS = 4;
    private static final GlanceRenderer INSTANCE = new GlanceRenderer();

    private record Request(int entityId, int width, int height, CompletableFuture<byte[]> picture) {}

    // client thread only
    private final List<Request> waiting = new ArrayList<>();
    private int tick;
    private int dueTick;
    private boolean smartCull;

    private GlanceRenderer() {}

    /** Mod load, client only: glance can render from now on. */
    public static void install() {
        Glance.install(INSTANCE);
        MinecraftForge.EVENT_BUS.addListener(GlanceRenderer::onClientTick);
    }

    /** Any thread. The request is queued on the client thread (tell, never execute, as on the server). */
    @Override
    public CompletableFuture<byte[]> capture(int entityId, int width, int height) {
        CompletableFuture<byte[]> picture = new CompletableFuture<>();
        Request r = new Request(entityId, width, height, picture);
        Minecraft.getInstance().tell(() -> {
            try {
                begin(r);
            } catch (RuntimeException e) {
                LOG.warn("glance couldn't start: {}", e.toString());
                picture.completeExceptionally(new Glance.Failure("render_error"));
            }
        });
        return picture;
    }

    private void begin(Request r) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            r.picture().completeExceptionally(new Glance.Failure("no_client"));
            return;
        }
        if (waiting.isEmpty()) {
            smartCull = mc.smartCull;
            mc.smartCull = false; // the next search ignores occlusion: every loaded section is listed
            mc.levelRenderer.needsUpdate();
            dueTick = tick + SEARCH_TICKS;
        }
        waiting.add(r);
    }

    private static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase == TickEvent.Phase.END) INSTANCE.tick();
    }

    private void tick() {
        tick++;
        if (waiting.isEmpty() || tick < dueTick) return;
        Minecraft mc = Minecraft.getInstance();
        List<Request> due = List.copyOf(waiting);
        waiting.clear();
        try {
            for (Request r : due) {
                try {
                    r.picture().complete(render(mc, r));
                } catch (Glance.Failure f) {
                    r.picture().completeExceptionally(f);
                } catch (RuntimeException | IOException ex) {
                    LOG.warn("glance render failed: {}", ex.toString());
                    r.picture().completeExceptionally(new Glance.Failure("render_error"));
                }
            }
        } finally {
            mc.smartCull = smartCull;
            mc.levelRenderer.needsUpdate(); // the player's own search, with occlusion again
        }
    }

    private static byte[] render(Minecraft mc, Request r) throws IOException {
        if (mc.level == null || mc.player == null) throw new Glance.Failure("no_client");
        Entity eyes = mc.level.getEntity(r.entityId());
        if (eyes == null) throw new Glance.Failure("not_loaded"); // too far from this player, or another dimension
        GlanceFrame frame = GlanceFrame.of(r.width(), r.height(), RenderSystem.maxSupportedTextureSize());
        Window window = mc.getWindow();
        int windowWidth = window.getWidth();
        int windowHeight = window.getHeight();
        Entity camera = mc.getCameraEntity();
        HitResult hit = mc.hitResult;
        Entity picked = mc.crosshairPickEntity;
        CameraType view = mc.options.getCameraType();
        boolean bob = mc.options.bobView().get();
        boolean fabulous = Minecraft.useShaderTransparency();
        TextureTarget target = new TextureTarget(frame.renderWidth(), frame.renderHeight(), true, Minecraft.ON_OSX);
        NativeImage shot;
        try {
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            mc.options.bobView().set(false);
            mc.gameRenderer.setRenderBlockOutline(false);
            mc.gameRenderer.setPanoramicMode(true);
            if (fabulous) mc.levelRenderer.graphicsChanged(); // panoramic mode turns Fabulous transparency off
            window.setWidth(frame.renderWidth());
            window.setHeight(frame.renderHeight());
            mc.setCameraEntity(eyes);
            target.bindWrite(true);
            mc.gameRenderer.renderLevel(1.0F, 0L, new PoseStack());
            shot = Screenshot.takeScreenshot(target);
        } finally {
            Entity back = camera != null ? camera : mc.player;
            mc.setCameraEntity(back);
            mc.hitResult = hit; // renderLevel picked from Claude's eyes; the player's next clicks use these
            mc.crosshairPickEntity = picked;
            window.setWidth(windowWidth);
            window.setHeight(windowHeight);
            mc.gameRenderer.setPanoramicMode(false);
            if (fabulous) mc.levelRenderer.graphicsChanged();
            mc.gameRenderer.setRenderBlockOutline(true);
            mc.options.bobView().set(bob);
            mc.options.setCameraType(view);
            // the main camera is still in Claude's eyes, and the sound listener reads it before the next frame
            mc.gameRenderer.getMainCamera().setup(mc.level, back, !view.isFirstPerson(), view.isMirrored(), 1.0F);
            target.destroyBuffers();
            mc.getMainRenderTarget().bindWrite(true);
        }
        try (NativeImage full = shot; NativeImage picture = new NativeImage(frame.width(), frame.height(), false)) {
            int[] pixels = frame.shrink(full.getPixelsRGBA());
            for (int y = 0; y < frame.height(); y++) {
                for (int x = 0; x < frame.width(); x++) picture.setPixelRGBA(x, y, pixels[x + y * frame.width()]);
            }
            return picture.asByteArray();
        }
    }
}
