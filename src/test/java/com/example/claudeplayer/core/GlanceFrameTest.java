package com.example.claudeplayer.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GlanceFrameTest {
    private static double verticalFov(GlanceFrame f) {
        // the kept rows' share of a 90 degree frame, back to degrees
        return 2 * Math.toDegrees(Math.atan((double) f.height() * f.scale() / f.renderHeight()));
    }

    @Test
    void theKeptCentreOfTheNinetyDegreeRenderIsASeventyDegreeView() {
        GlanceFrame f = GlanceFrame.of(320, 180, 16384);
        assertEquals(2, f.scale());
        double fov = verticalFov(f);
        assertTrue(fov > 69.5 && fov <= 70.0, "vertical fov " + fov);
        assertEquals(320.0 / 180.0, (double) f.renderWidth() / f.renderHeight(), 0.005, "the render keeps the picture's shape");
        assertTrue(f.cropX() >= 0 && f.cropX() + 320 * 2 <= f.renderWidth(), "crop inside the render: " + f);
        assertTrue(f.cropY() >= 0 && f.cropY() + 180 * 2 <= f.renderHeight(), "crop inside the render: " + f);
        assertTrue(Math.abs(f.renderWidth() - 2 * f.cropX() - 640) <= 1, "centred across: " + f);
        assertTrue(Math.abs(f.renderHeight() - 2 * f.cropY() - 360) <= 1, "centred down: " + f);
    }

    @Test
    void aTallPictureKeepsItsShapeToo() {
        GlanceFrame f = GlanceFrame.of(120, 200, 16384);
        assertEquals(120.0 / 200.0, (double) f.renderWidth() / f.renderHeight(), 0.01);
        assertTrue(f.cropX() + 120 * f.scale() <= f.renderWidth() && f.cropY() + 200 * f.scale() <= f.renderHeight(), f.toString());
    }

    @Test
    void aRenderTooLargeForTheGpuDropsTheSupersampling() {
        GlanceFrame big = GlanceFrame.of(640, 640, 1024);
        assertEquals(1, big.scale());
        assertTrue(big.renderWidth() <= 1024 && big.renderHeight() <= 1024, big.toString());
    }

    @Test
    void shrinkCropsTheCentreAndAveragesEachBlock() {
        // a 6x4 render; the kept centre is 4x2 starting at (1,1), halved to 2x1
        GlanceFrame f = new GlanceFrame(2, 1, 6, 4, 1, 1, 2);
        int[] pixels = new int[24];
        java.util.Arrays.fill(pixels, 0xFFFFFFFF); // white outside the crop
        // left block: two black, two at 0x80 in every channel; right block: all 0x10203040
        pixels[1 + 6] = 0xFF000000;
        pixels[2 + 6] = 0xFF000000;
        pixels[1 + 12] = 0xFF808080;
        pixels[2 + 12] = 0xFF808080;
        for (int i : new int[] {3 + 6, 4 + 6, 3 + 12, 4 + 12}) pixels[i] = 0x10203040;
        assertArrayEquals(new int[] {0xFF404040, 0x10203040}, f.shrink(pixels));
    }

    @Test
    void shrinkWantsTheWholeRender() {
        GlanceFrame f = new GlanceFrame(2, 1, 6, 4, 1, 1, 2);
        assertThrows(IllegalArgumentException.class, () -> f.shrink(new int[10]));
    }
}
