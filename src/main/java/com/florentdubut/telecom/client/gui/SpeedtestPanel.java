package com.florentdubut.telecom.client.gui;

import com.florentdubut.telecom.client.ClientSpeedtestState;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/** A view of received samples only. Rendering never records measurements. */
public final class SpeedtestPanel {
    public static final int DOWN = 0xFF00FFFF;
    public static final int UP = 0xFFFF8800;
    private static final int MUTED = 0xFFAAAABB;

    private SpeedtestPanel() {}

    public static Component text(String key, Object... args) {
        return Component.translatable("gui.telecom.speedtest." + key, args);
    }

    public static String rate(double mbps) {
        long whole = (long) Math.max(0, mbps);
        return whole >= 1000 ? String.format(Locale.ROOT, "%.3f Gbps", whole / 1000.0) : whole + " Mbps";
    }

    public static void clipped(GuiGraphics graphics, Font font, Component text, int x, int y, int width, int color) {
        graphics.drawString(font, font.plainSubstrByWidth(text.getString(), width), x, y, color);
    }

    public static void render(GuiGraphics graphics, Font font, int x, int y, int width, int height,
                              ClientSpeedtestState.Snapshot snapshot, int legacyDown, int legacyUp,
                              int legacyPing, int selectedDuration, long now) {
        var payload = snapshot.pending() ? null : snapshot.payload();
        boolean legacy = payload == null && !snapshot.pending() && legacyPing > 0;
        boolean finished = payload != null && "FINISHED".equals(payload.state());
        boolean live = payload != null && !payload.terminal();
        boolean waiting = snapshot.waiting(now);
        String phase = snapshot.pending() ? "pending" : payload == null ? legacy ? "saved" : "ready"
                : payload.state().toLowerCase(Locale.ROOT);
        int color = payload != null && "UPLOAD".equals(payload.state()) ? UP : DOWN;
        if (waiting) color = MUTED;
        graphics.fill(x, y, x + width, y + height, 0xFF10101B);
        graphics.renderOutline(x, y, width, height, 0xFF404054);
        graphics.fill(x, y, x + 2, y + height, color);
        boolean hasPing = payload != null || legacy;
        clipped(graphics, font, text(waiting ? "waiting_data" : phase), x + 7, y + 5, width - (hasPing ? 60 : 14), color);

        int down = snapshot.pending() ? 0 : payload == null ? legacyDown : payload.downloadBandwidth();
        int up = snapshot.pending() ? 0 : payload == null ? legacyUp : payload.uploadBandwidth();
        int ping = payload == null ? legacyPing : payload.pingMs();
        if (hasPing) {
            String latency = ping + " ms";
            graphics.drawString(font, latency, x + width - 7 - font.width(latency), y + 5, MUTED);
        }
        String value = payload != null && "PING".equals(payload.state()) ? ping + " ms"
                : rate(finished || legacy ? down : snapshot.instantaneous(now));
        graphics.pose().pushMatrix();
        graphics.pose().translate(x + 7, y + 18);
        graphics.pose().scale(1.6f, 1.6f);
        graphics.drawString(font, value, 0, 0, color);
        graphics.pose().popMatrix();
        clipped(graphics, font, text(legacy ? "saved_down" : "average_down", rate(down)), x + 7, y + 34, width - 14, DOWN);
        clipped(graphics, font, text(legacy ? "saved_up" : "average_up", rate(up)), x + 7, y + 44, width - 14, UP);

        int graphTop = y + 74;
        int graphBottom = y + height - 19;
        int window = snapshot.pending() ? 1 : windowTicks(snapshot.download(), snapshot.upload());
        clipped(graphics, font, text("local_max", rate(snapshot.pending() ? 0 : snapshot.maxObserved())),
                x + 7, y + 54, width - 14, MUTED);
        clipped(graphics, font, text("window", String.format(Locale.ROOT, "%.2f", window / 20.0)),
                x + 7, y + 64, width - 14, MUTED);
        graphics.fill(x + 7, graphBottom, x + width - 7, graphBottom + 1, 0xFF454553);
        graphics.fill(x + 7, graphTop, x + 8, graphBottom, 0xFF454553);
        if (!snapshot.pending()) {
            curve(graphics, snapshot.download(), x + 8, graphTop, width - 16, graphBottom - graphTop,
                    window, snapshot.maxObserved(), DOWN);
            curve(graphics, snapshot.upload(), x + 8, graphTop, width - 16, graphBottom - graphTop,
                    window, snapshot.maxObserved(), UP);
        }

        int total = payload == null ? Math.min(selectedDuration, 60) + 2 * selectedDuration : snapshot.totalTicks();
        int percent = snapshot.percent(now);
        String totalSeconds = String.format(Locale.ROOT, "%.0f", total / 20.0);
        Component progress = percent < 0 ? text("progress_unknown", totalSeconds)
                : text("progress", percent, String.format(Locale.ROOT, "%.1f", Math.floor(snapshot.elapsedTicks(now) / 2) / 10), totalSeconds);
        clipped(graphics, font, progress, x + 7, y + height - 15, width - 14, MUTED);
        int barWidth = width - 14;
        graphics.fill(x + 7, y + height - 4, x + 7 + barWidth, y + height - 2, 0xFF333344);
        graphics.fill(x + 7, y + height - 4, x + 7 + barWidth * Math.clamp(percent, 0, 100) / 100, y + height - 2, color);
        if (live && !waiting) {
            // Time-based activity, independent of FPS; not a bandwidth sample or a progress claim.
            int cursor = (int) (Math.floorMod(now, 1_200_000_000L) * (barWidth - 4) / 1_200_000_000L);
            graphics.fill(x + 7 + cursor, y + height - 17, x + 11 + cursor, y + height - 16, color);
        }
    }

    static int windowTicks(List<ClientSpeedtestState.Point> down, List<ClientSpeedtestState.Point> up) {
        int downSpan = down.isEmpty() ? 0 : down.getLast().ticksElapsed() - down.getFirst().ticksElapsed();
        int upSpan = up.isEmpty() ? 0 : up.getLast().ticksElapsed() - up.getFirst().ticksElapsed();
        return Math.max(1, Math.max(downSpan, upSpan));
    }

    static void curve(GuiGraphics graphics, List<ClientSpeedtestState.Point> points, int x, int y,
                      int width, int height, int window, int maximum, int color) {
        if (points.isEmpty()) return;
        int startTick = points.getLast().ticksElapsed() - Math.max(1, window);
        int previousX = 0;
        int previousY = 0;
        boolean first = true;
        for (var point : points) {
            int px = x + (int) (Math.clamp((double) (point.ticksElapsed() - startTick) / Math.max(1, window), 0, 1) * (width - 1));
            int py = y + height - (int) (Math.clamp((double) point.bandwidth() / Math.max(1, maximum), 0, 1) * height);
            if (!first) {
                // Straight segments only: no spline can manufacture peaks between real samples.
                int steps = Math.max(Math.abs(px - previousX), Math.abs(py - previousY));
                for (int i = 1; i < steps; i++) {
                    int sx = previousX + (px - previousX) * i / steps;
                    int sy = previousY + (py - previousY) * i / steps;
                    graphics.fill(sx, sy, sx + 1, sy + 1, color);
                }
            }
            graphics.fill(px, py, px + 1, py + 1, color);
            previousX = px;
            previousY = py;
            first = false;
        }
    }
}
