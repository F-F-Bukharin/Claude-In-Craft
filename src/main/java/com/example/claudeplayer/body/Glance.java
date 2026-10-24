package com.example.claudeplayer.body;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;

/**
 * glance's link to the game window. A client installs its {@link Camera} when the mod loads; a dedicated server has
 * none, so a glance there fails with {@code no_client}. The camera renders on the client thread and completes the
 * future there; the server side only reads it.
 */
public final class Glance {
    private static volatile Camera camera;

    private Glance() {}

    /** Renders what an entity's eyes see, as a width x height PNG. */
    public interface Camera {
        CompletableFuture<byte[]> capture(int entityId, int width, int height);
    }

    /** Why a capture failed, as the code the model gets: {@code no_client}, {@code not_loaded} or {@code render_error}. */
    public static final class Failure extends RuntimeException {
        public Failure(String code) {
            super(code, null, false, false);
        }
    }

    public static void install(Camera c) {
        camera = c;
    }

    /** The game window's camera, or null on a dedicated server. */
    public static @Nullable Camera camera() {
        return camera;
    }

    /** The failure code of a capture that ended exceptionally. */
    public static String code(Throwable t) {
        Throwable cause = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
        if (cause instanceof Failure f) return f.getMessage();
        if (cause instanceof TimeoutException) return "render_timeout";
        if (cause instanceof CancellationException) return "cancelled";
        return "render_error";
    }
}
