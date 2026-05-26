package com.florentdubut.telecom.client.gui;

import com.florentdubut.telecom.network.TelecomFrequency;
import com.florentdubut.telecom.network.packet.AntennaConfigPayload;
import com.florentdubut.telecom.network.packet.AntennaGuiSyncPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;

public class AntennaScreen extends Screen {

    private final AntennaGuiSyncPayload payload;
    private EditBox nameBox;
    private final Map<TelecomFrequency, Checkbox> checkboxes = new HashMap<>();

    public AntennaScreen(AntennaGuiSyncPayload payload) {
        super(Component.literal("Configuration Antenne"));
        this.payload = payload;
    }

    @Override
    protected void init() {
        super.init();
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        // Box dimensions
        int boxWidth = 480;
        int boxHeight = 260;
        int startX = centerX - boxWidth / 2;
        int startY = centerY - boxHeight / 2;

        // Name field
        this.nameBox = new EditBox(this.font, startX + 20, startY + 30, 200, 16, Component.literal("Nom"));
        this.nameBox.setValue(payload.antennaName());
        this.nameBox.setMaxLength(32);
        this.addRenderableWidget(this.nameBox);

        // Frequency checkboxes grouped by technology
        int[] colOffsets = new int[4]; // 2G, 3G, 4G, 5G
        int colWidth = 115;
        int checkStartX = startX + 10;
        int checkStartY = startY + 75;

        for (TelecomFrequency freq : TelecomFrequency.values()) {
            int colIndex = switch (freq.getTechnology()) {
                case "2G" -> 0;
                case "3G" -> 1;
                case "4G" -> 2;
                default   -> 3;
            };
            int x = checkStartX + colIndex * colWidth;
            int y = checkStartY + colOffsets[colIndex] * 18;
            boolean enabled = (payload.enabledFrequenciesMask() & (1 << freq.ordinal())) != 0;

            Checkbox box = Checkbox.builder(Component.literal(freq.getFrequencyLabel()), this.font)
                    .pos(x, y)
                    .selected(enabled)
                    .build();
            this.addRenderableWidget(box);
            checkboxes.put(freq, box);
            colOffsets[colIndex]++;
        }

        // Save button
        this.addRenderableWidget(Button.builder(Component.literal("Sauvegarder"), button -> saveAndClose())
                .bounds(centerX - 50, startY + boxHeight - 30, 100, 20)
                .build());
    }

    private void saveAndClose() {
        int mask = 0;
        for (TelecomFrequency freq : TelecomFrequency.values()) {
            Checkbox box = checkboxes.get(freq);
            if (box != null && box.selected()) {
                mask |= (1 << freq.ordinal());
            }
        }
        PacketDistributor.sendToServer(new AntennaConfigPayload(payload.pos(), nameBox.getValue(), mask));
        this.onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int boxWidth = 480;
        int boxHeight = 260;
        int startX = centerX - boxWidth / 2;
        int startY = centerY - boxHeight / 2;

        // Background box
        g.fill(startX, startY, startX + boxWidth, startY + boxHeight, 0xDD1A1A2E);
        g.renderOutline(startX, startY, boxWidth, boxHeight, 0xDD44AAFF);

        // Title
        g.drawCenteredString(this.font, "CONFIGURATION ANTENNE", centerX, startY + 10, 0xFFFFFF);

        // Name label
        g.drawString(this.font, "Nom :", startX + 20, startY + 20, 0xAAAAAA);

        // Technology column headers
        int colWidth = 115;
        int checkStartX = startX + 10;
        g.drawString(this.font, "2G (GSM)", checkStartX + 4,       startY + 62, 0xAA88FF);
        g.drawString(this.font, "3G (UMTS)", checkStartX + colWidth + 4, startY + 62, 0xFF8844);
        g.drawString(this.font, "4G (LTE)", checkStartX + colWidth * 2 + 4, startY + 62, 0x44DDAA);
        g.drawString(this.font, "5G (NR)", checkStartX + colWidth * 3 + 4, startY + 62, 0x44AAFF);

        // Utilization bars section (right side of screen)
        int utilX = startX + boxWidth / 2 + 10;
        int utilY = startY + 55;
        g.drawString(this.font, "=== Utilisation en temps réel ===", utilX, utilY - 12, 0xAAAAAA);

        TelecomFrequency[] allFreqs = TelecomFrequency.values();
        int lineHeight = 16;

        for (int i = 0; i < allFreqs.length; i++) {
            TelecomFrequency freq = allFreqs[i];
            if ((payload.enabledFrequenciesMask() & (1 << freq.ordinal())) == 0) continue;

            int[] stats = payload.freqUtilization().get(freq.ordinal());
            int actual = stats != null ? stats[0] : 0;
            int max = stats != null ? stats[1] : freq.getMaxSpeedMb();
            float ratio = max > 0 ? Math.min(1f, (float) actual / max) : 0f;
            int pct = (int)(ratio * 100);

            // Colour bar: green < 50%, yellow < 80%, red >= 80%
            int barColor = pct < 50 ? 0xFF00CC44 : (pct < 80 ? 0xFFFFCC00 : 0xFFFF3333);

            int barMaxW = 140;
            int barH = 8;
            int barFill = (int)(barMaxW * ratio);

            String label = freq.getTechnology() + " " + freq.getFrequencyLabel();
            g.drawString(this.font, label, utilX, utilY + i * lineHeight, 0xDDDDDD);

            // Bar background
            g.fill(utilX + 80, utilY + i * lineHeight, utilX + 80 + barMaxW, utilY + i * lineHeight + barH, 0xFF333333);
            // Bar fill
            if (barFill > 0) {
                g.fill(utilX + 80, utilY + i * lineHeight, utilX + 80 + barFill, utilY + i * lineHeight + barH, barColor);
            }
            // Percentage text
            g.drawString(this.font, pct + "% (" + actual + "/" + max + " Mbps)",
                utilX + 80 + barMaxW + 4, utilY + i * lineHeight, 0xCCCCCC);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
