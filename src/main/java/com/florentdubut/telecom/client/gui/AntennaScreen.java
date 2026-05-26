package com.florentdubut.telecom.client.gui;

import com.florentdubut.telecom.network.TelecomFrequency;
import com.florentdubut.telecom.network.packet.AntennaConfigPayload;
import com.florentdubut.telecom.network.packet.AntennaGuiSyncPayload;
import com.florentdubut.telecom.network.packet.AntennaRefreshRequestPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

public class AntennaScreen extends Screen {

    private AntennaGuiSyncPayload payload;
    private EditBox nameBox;
    private final Map<TelecomFrequency, Checkbox> checkboxes = new LinkedHashMap<>();

    // Auto-refresh timer
    private int refreshTick = 0;
    private static final int REFRESH_INTERVAL = 40; // every 40 ticks = 2 seconds

    // Layout constants
    private static final int WIN_W = 680;
    private static final int WIN_H = 300;
    private static final int LEFT_W  = 320; // config panel width
    private static final int RIGHT_W = 340; // utilization panel width
    private static final int PADDING = 12;

    public AntennaScreen(AntennaGuiSyncPayload payload) {
        super(Component.literal("Configuration Antenne"));
        this.payload = payload;
    }

    /** Called when the server sends a fresh sync (on refresh) */
    public void receiveUpdate(AntennaGuiSyncPayload newPayload) {
        this.payload = newPayload;
        // Re-sync checkbox states if mask changed
        for (TelecomFrequency freq : TelecomFrequency.values()) {
            Checkbox cb = checkboxes.get(freq);
            if (cb != null) {
                boolean shouldBeSelected = (payload.enabledFrequenciesMask() & (1 << freq.ordinal())) != 0;
                // We can't directly set checkbox state without re-init, but that's OK —
                // the utilization data (right panel) updates without touching checkboxes.
            }
        }
    }

    @Override
    protected void init() {
        super.init();

        int winX = (this.width  - WIN_W) / 2;
        int winY = (this.height - WIN_H) / 2;

        // ── Name field ────────────────────────────────
        this.nameBox = new EditBox(this.font, winX + PADDING, winY + 32, 180, 16, Component.literal("Nom"));
        this.nameBox.setValue(payload.antennaName());
        this.nameBox.setMaxLength(32);
        this.addRenderableWidget(this.nameBox);

        // ── Frequency checkboxes (left panel, 4 columns) ──
        String[] techs   = {"2G", "3G", "4G", "5G"};
        int[] colOffsets = new int[4];
        int colW      = (LEFT_W - PADDING * 2) / 4; // ~72px per tech column
        int checkTopY = winY + 70;

        for (TelecomFrequency freq : TelecomFrequency.values()) {
            int colIdx = switch (freq.getTechnology()) {
                case "2G" -> 0;
                case "3G" -> 1;
                case "4G" -> 2;
                default   -> 3;
            };
            int x = winX + PADDING + colIdx * colW;
            int y = checkTopY + colOffsets[colIdx] * 17;
            boolean enabled = (payload.enabledFrequenciesMask() & (1 << freq.ordinal())) != 0;

            Checkbox box = Checkbox.builder(Component.literal(freq.getFrequencyLabel()), this.font)
                    .pos(x, y).selected(enabled).build();
            this.addRenderableWidget(box);
            checkboxes.put(freq, box);
            colOffsets[colIdx]++;
        }

        // ── Save button ────────────────────────────────
        this.addRenderableWidget(Button.builder(Component.literal("Sauvegarder"), button -> saveAndClose())
                .bounds(winX + PADDING, winY + WIN_H - 30, 110, 20)
                .build());
    }

    private void saveAndClose() {
        int mask = 0;
        for (TelecomFrequency freq : TelecomFrequency.values()) {
            Checkbox box = checkboxes.get(freq);
            if (box != null && box.selected()) mask |= (1 << freq.ordinal());
        }
        PacketDistributor.sendToServer(new AntennaConfigPayload(payload.pos(), nameBox.getValue(), mask));
        this.onClose();
    }

    @Override
    public void tick() {
        super.tick();
        refreshTick++;
        if (refreshTick >= REFRESH_INTERVAL) {
            refreshTick = 0;
            // Ask the server for fresh utilization data
            PacketDistributor.sendToServer(new AntennaRefreshRequestPayload(payload.pos()));
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float pt) {
        super.renderBackground(g, mouseX, mouseY, pt);

        int winX = (this.width  - WIN_W) / 2;
        int winY = (this.height - WIN_H) / 2;

        // ── Window background ────────────────────────
        g.fill(winX, winY, winX + WIN_W, winY + WIN_H, 0xEE111122);
        g.renderOutline(winX, winY, WIN_W, WIN_H, 0xFF3366CC);

        // ── Left panel: config ────────────────────────
        g.fill(winX + 1, winY + 1, winX + LEFT_W, winY + WIN_H - 1, 0x551A1A2E);
        g.drawCenteredString(this.font, "⚙ Configuration", winX + LEFT_W / 2, winY + 8, 0x88BBFF);
        g.drawString(this.font, "Nom :", winX + PADDING, winY + 22, 0x888888);

        // Tech column headers
        String[] techLabels  = {"2G (GSM)", "3G", "4G (LTE)", "5G (NR)"};
        int[]    techColors  = {0xAA88FF, 0xFF9944, 0x44DDAA, 0x44AAFF};
        int colW = (LEFT_W - PADDING * 2) / 4;
        for (int i = 0; i < 4; i++) {
            g.drawString(this.font, techLabels[i],
                winX + PADDING + i * colW + 2, winY + 59, techColors[i]);
        }

        // Divider between panels
        g.fill(winX + LEFT_W, winY + 8, winX + LEFT_W + 1, winY + WIN_H - 8, 0xFF3366CC);

        // ── Right panel: utilization ──────────────────
        int rightX = winX + LEFT_W + PADDING;
        g.drawCenteredString(this.font, "📡 Utilisation en temps réel",
            winX + LEFT_W + RIGHT_W / 2, winY + 8, 0x88BBFF);

        TelecomFrequency[] allFreqs = TelecomFrequency.values();
        int lineH = 15;
        int barMaxW = 140;
        int barH    = 6;
        int yOff    = winY + 28;

        String currentTech = null;
        for (TelecomFrequency freq : allFreqs) {
            if ((payload.enabledFrequenciesMask() & (1 << freq.ordinal())) == 0) continue;

            // Tech separator header
            if (!freq.getTechnology().equals(currentTech)) {
                currentTech = freq.getTechnology();
                int headerColor = switch (currentTech) {
                    case "2G" -> 0xAA88FF;
                    case "3G" -> 0xFF9944;
                    case "4G" -> 0x44DDAA;
                    default   -> 0x44AAFF;
                };
                g.drawString(this.font, "── " + currentTech + " ──", rightX, yOff, headerColor);
                yOff += lineH;
            }

            int[] stats  = payload.freqUtilization().get(freq.ordinal());
            int actual   = stats != null ? stats[0] : 0;
            int max      = stats != null ? stats[1] : freq.getMaxSpeedMb();
            float ratio  = max > 0 ? Math.min(1f, (float) actual / max) : 0f;
            int pct      = (int)(ratio * 100);

            int barColor = pct < 50 ? 0xFF00CC44 : (pct < 80 ? 0xFFFFCC00 : 0xFFFF3333);

            String label = freq.getFrequencyLabel();
            g.drawString(this.font, label, rightX, yOff, 0xCCCCCC);

            int barX = rightX + 60;
            // Background
            g.fill(barX, yOff, barX + barMaxW, yOff + barH, 0xFF2A2A3A);
            // Fill
            if (ratio > 0) g.fill(barX, yOff, barX + (int)(barMaxW * ratio), yOff + barH, barColor);
            // Border
            g.renderOutline(barX, yOff, barMaxW, barH, 0xFF444466);

            // Percentage + Mbps
            g.drawString(this.font, pct + "% | " + actual + "/" + max + " Mbps",
                barX + barMaxW + 4, yOff, 0xAAAAAA);

            yOff += lineH;
        }

        if (currentTech == null) {
            g.drawCenteredString(this.font, "Aucune fréquence activée",
                winX + LEFT_W + RIGHT_W / 2, winY + WIN_H / 2, 0x666666);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        super.render(g, mouseX, mouseY, pt);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
