package com.example.claudeplayer.core;

/**
 * The geometry of a glance picture. The game renders it in panoramic mode, whose vertical field of view is fixed at
 * {@link #RENDER_FOV} degrees, so it renders a larger frame and keeps the centre: a {@link #FOV} degree view takes
 * tan(FOV / 2) / tan(RENDER_FOV / 2) of it. It also renders {@link #SUPERSAMPLE} times larger each way and averages
 * each block of pixels into one, which smooths block edges at sizes like 320x180.
 *
 * @param width        the picture
 * @param height       the picture
 * @param renderWidth  the frame to render, the same shape as the picture
 * @param renderHeight the frame to render
 * @param cropX        where the kept centre starts, in render pixels
 * @param cropY        where the kept centre starts, in render pixels
 * @param scale        render pixels per picture pixel, each way
 */
public record GlanceFrame(int width, int height, int renderWidth, int renderHeight, int cropX, int cropY, int scale) {
    /** Degrees, vertical: vanilla's default field of view. */
    public static final double FOV = 70;
    /** Degrees, vertical: what panoramic mode renders. */
    public static final double RENDER_FOV = 90;
    public static final int SUPERSAMPLE = 2;

    /** The frame for a width x height picture; without supersampling when that would make a side over {@code maxSide}. */
    public static GlanceFrame of(int width, int height, int maxSide) {
        GlanceFrame f = sized(width, height, SUPERSAMPLE);
        return f.renderWidth <= maxSide && f.renderHeight <= maxSide ? f : sized(width, height, 1);
    }

    private static GlanceFrame sized(int width, int height, int scale) {
        double share = Math.tan(Math.toRadians(FOV / 2)) / Math.tan(Math.toRadians(RENDER_FOV / 2));
        int keptWidth = width * scale;
        int keptHeight = height * scale;
        int renderHeight = (int) Math.ceil(keptHeight / share);
        int renderWidth = Math.max(keptWidth, (int) Math.round((double) renderHeight * width / height));
        return new GlanceFrame(width, height, renderWidth, renderHeight, (renderWidth - keptWidth) / 2,
                (renderHeight - keptHeight) / 2, scale);
    }

    /**
     * The picture from the rendered pixels (renderWidth x renderHeight, row by row, four 8-bit channels per int): the
     * kept centre, each scale x scale block averaged channel by channel.
     */
    public int[] shrink(int[] pixels) {
        if (pixels.length != renderWidth * renderHeight) {
            throw new IllegalArgumentException("expected " + renderWidth + "x" + renderHeight + " pixels, got " + pixels.length);
        }
        int[] out = new int[width * height];
        int n = scale * scale;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int c0 = 0;
                int c1 = 0;
                int c2 = 0;
                int c3 = 0;
                for (int dy = 0; dy < scale; dy++) {
                    int row = (cropY + y * scale + dy) * renderWidth + cropX + x * scale;
                    for (int dx = 0; dx < scale; dx++) {
                        int c = pixels[row + dx];
                        c0 += c & 0xFF;
                        c1 += c >>> 8 & 0xFF;
                        c2 += c >>> 16 & 0xFF;
                        c3 += c >>> 24;
                    }
                }
                out[x + y * width] = (c3 + n / 2) / n << 24 | (c2 + n / 2) / n << 16 | (c1 + n / 2) / n << 8 | (c0 + n / 2) / n;
            }
        }
        return out;
    }
}
