package com.example.claudeplayer.core.nav;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure maths for flee: which way is "away" from a crowd of threats, which spots to try around the body, and in
 * what order. Ground-plane (x, z) numbers only; the game-side planner path-checks the result in order.
 */
public final class EscapeScore {
    public record Point(double x, double z) {}

    private EscapeScore() {}

    /**
     * The angle (atan2 of z, x) pointing away from the crowd: the sum of the unit vectors away from each threat,
     * each weighted by 1 / distance squared so the closest threats count most. When they cancel (two threats on
     * opposite sides) it is the angle away from the nearest threat; 0 when the body stands on it.
     */
    public static double awayAngle(double bx, double bz, List<Point> threats) {
        double sx = 0;
        double sz = 0;
        for (Point t : threats) {
            double dx = bx - t.x();
            double dz = bz - t.z();
            double d = Math.hypot(dx, dz);
            if (d < 1e-9) continue;
            double w = 1 / (Math.max(d, 1) * Math.max(d, 1));
            sx += dx / d * w;
            sz += dz / d * w;
        }
        if (Math.hypot(sx, sz) > 1e-9) return Math.atan2(sz, sx);
        Point nearest = null;
        double best = Double.MAX_VALUE;
        for (Point t : threats) {
            double d = Math.hypot(bx - t.x(), bz - t.z());
            if (d < best) {
                best = d;
                nearest = t;
            }
        }
        if (nearest == null || best < 1e-9) return 0;
        return Math.atan2(bz - nearest.z(), bx - nearest.x());
    }

    /**
     * {@code count} points on a circle of {@code radius} around the body, the first at {@code angle}, then one step to
     * the left, one to the right, two to the left, and so on, so the order already prefers the aimed direction.
     */
    public static List<Point> ring(double bx, double bz, double angle, double radius, int count) {
        List<Point> points = new ArrayList<>(count);
        double step = 2 * Math.PI / count;
        for (int i = 0; i < count; i++) {
            int turn = (i + 1) / 2 * (i % 2 == 1 ? 1 : -1);
            double a = angle + step * turn;
            points.add(new Point(bx + Math.cos(a) * radius, bz + Math.sin(a) * radius));
        }
        return points;
    }

    /** Whether every threat is at least {@code escapeDistance} from the spot. */
    public static boolean clear(double x, double z, List<Point> threats, double escapeDistance) {
        for (Point t : threats) {
            if (Math.hypot(x - t.x(), z - t.z()) < escapeDistance) return false;
        }
        return true;
    }

    /**
     * The candidates worth path-checking, best first. A candidate within {@code escapeDistance} of ANY threat is
     * dropped (not only the nearest one). The rest are scored by their distance to the nearest threat, their mean
     * distance to all of them, and how well they point away from the crowd; equal scores keep the input order.
     */
    public static List<Point> order(double bx, double bz, List<Point> threats, List<Point> candidates, double escapeDistance) {
        double away = awayAngle(bx, bz, threats);
        double ax = Math.cos(away);
        double az = Math.sin(away);
        List<Point> kept = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        for (Point c : candidates) {
            if (!clear(c.x(), c.z(), threats, escapeDistance)) continue;
            double min = Double.MAX_VALUE;
            double sum = 0;
            for (Point t : threats) {
                double d = Math.hypot(c.x() - t.x(), c.z() - t.z());
                min = Math.min(min, d);
                sum += d;
            }
            double score = 0;
            if (!threats.isEmpty()) {
                double dx = c.x() - bx;
                double dz = c.z() - bz;
                double length = Math.hypot(dx, dz);
                double align = length < 1e-9 ? 0 : (dx * ax + dz * az) / length;
                score = min + 0.5 * sum / threats.size() + 0.25 * escapeDistance * align;
            }
            kept.add(c);
            scores.add(score);
        }
        List<Integer> index = new ArrayList<>(kept.size());
        for (int i = 0; i < kept.size(); i++) index.add(i);
        index.sort(Comparator.comparingDouble((Integer i) -> scores.get(i)).reversed()); // List.sort is stable
        List<Point> ordered = new ArrayList<>(kept.size());
        for (int i : index) ordered.add(kept.get(i));
        return ordered;
    }
}
