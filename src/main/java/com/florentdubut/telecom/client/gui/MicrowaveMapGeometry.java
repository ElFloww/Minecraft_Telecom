package com.florentdubut.telecom.client.gui;

/** Screen-space beam clipping keeps rendering bounded even for distant peers. */
public final class MicrowaveMapGeometry {
    private MicrowaveMapGeometry() {}

    public static int color(String state) {
        return switch (state) {
            case "ready" -> 0xFF38BDF8;
            case "degraded" -> 0xFFFBBF24;
            case "pending", "unknown", "limit" -> 0xFF94A3B8;
            case "disabled", "unpaired" -> 0xFF64748B;
            default -> 0xFFEF4444;
        };
    }

    public static double[] clip(double x1, double y1, double x2, double y2, int width, int top, int bottom) {
        double dx = x2 - x1, dy = y2 - y1;
        if (dx == 0 && dy == 0) return null;
        double start = 0, end = 1;
        double[] p = {-dx, dx, -dy, dy}, q = {x1, width - x1, y1 - top, bottom - y1};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) return null;
            } else {
                double t = q[i] / p[i];
                if (p[i] < 0) start = Math.max(start, t);
                else end = Math.min(end, t);
                if (start > end) return null;
            }
        }
        return new double[]{x1 + dx * start, y1 + dy * start, x1 + dx * end, y1 + dy * end};
    }

    public static boolean hit(double[] line, double x, double y) {
        if (line == null) return false;
        double dx = line[2] - line[0], dy = line[3] - line[1], length = dx * dx + dy * dy;
        if (length == 0) return false;
        double t = Math.clamp(((x - line[0]) * dx + (y - line[1]) * dy) / length, 0, 1);
        return Math.hypot(x - line[0] - t * dx, y - line[1] - t * dy) <= 5;
    }
}
