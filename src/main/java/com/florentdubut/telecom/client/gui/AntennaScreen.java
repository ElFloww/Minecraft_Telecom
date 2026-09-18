package com.florentdubut.telecom.client.gui;

import com.florentdubut.telecom.network.AntennaRadioConfig;
import com.florentdubut.telecom.network.TelecomFrequency;
import com.florentdubut.telecom.network.packet.AntennaConfigPayload;
import com.florentdubut.telecom.network.packet.AntennaGuiSyncPayload;
import com.florentdubut.telecom.network.packet.AntennaRefreshRequestPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.function.Consumer;

public class AntennaScreen extends Screen {
    private AntennaGuiSyncPayload payload;
    // Draft state is independent of telemetry and survives tab changes and resize.
    private String draftName;
    private int draftMask;
    private int sectors;
    private int bandwidth;
    private String azimuth;
    private String tilt;
    private String power;
    private Button saveButton;
    private int tab;
    private int page;
    private int refreshTick;
    private int winX, winY, winW, winH, rows;

    public AntennaScreen(AntennaGuiSyncPayload payload) {
        super(text("title"));
        this.payload = payload;
        draftName = payload.antennaName();
        draftMask = payload.enabledFrequenciesMask();
        AntennaRadioConfig config = payload.radioConfig();
        sectors = config.sectors();
        bandwidth = config.bandwidthPercent();
        azimuth = Integer.toString(config.azimuthDegrees());
        tilt = Integer.toString(config.downtiltDegrees());
        power = Integer.toString(config.powerDbm());
    }

    public void receiveUpdate(AntennaGuiSyncPayload update) {
        if (payload.pos().equals(update.pos()) && payload.dimension().equals(update.dimension())
                && payload.viewId().equals(update.viewId())) payload = update;
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("gui.telecom.antenna." + key, args);
    }

    @Override
    protected void init() {
        super.init();
        winW = Math.min(520, width - 12);
        winH = Math.min(320, height - 12);
        winX = (width - winW) / 2;
        winY = (height - winH) / 2;
        rows = Math.max(1, (winH - 130) / 22);
        page = Math.min(page, (TelecomFrequency.values().length - 1) / rows);

        EditBox name = new EditBox(font, winX + 66, winY + 25, winW - 78, 18, text("name"));
        name.setMaxLength(32);
        name.setValue(draftName);
        name.setResponder(value -> draftName = value);
        addRenderableWidget(name);

        String[] tabs = {"radio", "bands", "live"};
        int tabWidth = (winW - 24) / 3;
        for (int i = 0; i < tabs.length; i++) {
            int selected = i;
            Button button = Button.builder(text(tabs[i]), ignored -> {
                tab = selected;
                page = 0;
                rebuildWidgets();
            }).bounds(winX + 12 + i * tabWidth, winY + 49, tabWidth - 2, 20).build();
            button.active = tab != i;
            addRenderableWidget(button);
        }

        if (tab == 0) {
            int fieldX = winX + winW - 104;
            addRenderableWidget(Button.builder(sectorLabel(), button -> {
                sectors = (sectors + 1) % 4;
                button.setMessage(sectorLabel());
            }).bounds(fieldX, winY + 77, 92, 20).tooltip(Tooltip.create(text("sectors_hint"))).build());
            numberField("azimuth", azimuth, 1, value -> azimuth = value);
            numberField("tilt", tilt, 2, value -> tilt = value);
            numberField("power", power, 3, value -> power = value);
            addRenderableWidget(Button.builder(text("percent", bandwidth), button -> {
                bandwidth = bandwidth == 25 ? 50 : bandwidth == 50 ? 100 : 25;
                button.setMessage(text("percent", bandwidth));
            }).bounds(fieldX, winY + 165, 92, 20).tooltip(Tooltip.create(text("bandwidth_hint"))).build());
        } else {
            TelecomFrequency[] frequencies = TelecomFrequency.values();
            if (tab == 1) {
                for (int i = page * rows; i < Math.min(frequencies.length, (page + 1) * rows); i++) {
                    TelecomFrequency frequency = frequencies[i];
                    int bit = 1 << frequency.ordinal();
                    Checkbox box = Checkbox.builder(Component.literal(frequency.getTechnology() + " " + frequency.getFrequencyLabel()), font)
                            .pos(winX + 12, winY + 88 + (i % rows) * 22)
                            .selected((draftMask & bit) != 0)
                            .onValueChange((checkbox, selected) -> draftMask = selected ? draftMask | bit : draftMask & ~bit)
                            .build();
                    box.setTooltip(Tooltip.create(text("band_hint", referenceWidthMhz(frequency), bandwidth,
                            referenceWidthMhz(frequency) * bandwidth / 100.0)));
                    addRenderableWidget(box);
                }
            }
            addRenderableWidget(Button.builder(Component.literal("<"), ignored -> { page--; rebuildWidgets(); })
                    .bounds(winX + winW - 100, winY + winH - 28, 24, 20).build()).active = page > 0;
            addRenderableWidget(Button.builder(Component.literal(">"), ignored -> { page++; rebuildWidgets(); })
                    .bounds(winX + winW - 36, winY + winH - 28, 24, 20).build()).active = (page + 1) * rows < frequencies.length;
        }

        saveButton = addRenderableWidget(Button.builder(text("save"), ignored -> saveAndClose())
                .bounds(winX + 12, winY + winH - 28, 104, 20).tooltip(Tooltip.create(text("shared_hint"))).build());
        saveButton.active = pendingConfig() != null;
    }

    private Component sectorLabel() {
        return sectors == 0 ? text("omni") : text("sector_count", sectors);
    }

    private void numberField(String key, String value, int row, Consumer<String> setter) {
        EditBox box = new EditBox(font, winX + winW - 104, winY + 77 + row * 22, 92, 18, text(key));
        box.setMaxLength(4);
        box.setValue(value);
        box.setTooltip(Tooltip.create(text(key + "_hint")));
        box.setResponder(updated -> {
            setter.accept(updated);
            if (saveButton != null) saveButton.active = pendingConfig() != null;
        });
        addRenderableWidget(box);
    }

    AntennaRadioConfig pendingConfig() {
        try {
            int a = Integer.parseInt(azimuth);
            int t = Integer.parseInt(tilt);
            int p = Integer.parseInt(power);
            if (a < 0 || a > 359 || t < -15 || t > 45 || p < 0 || p > 50) return null;
            return new AntennaRadioConfig(sectors, a, t, p, bandwidth);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    AntennaConfigPayload savePayload() {
        AntennaRadioConfig config = pendingConfig();
        return config == null ? null : new AntennaConfigPayload(payload.pos(), draftName, draftMask, config, payload.dimension());
    }

    private void saveAndClose() {
        AntennaConfigPayload save = savePayload();
        if (save == null) return;
        ClientPacketDistributor.sendToServer(save);
        onClose();
    }

    static double referenceWidthMhz(TelecomFrequency frequency) {
        return AntennaRadioConfig.referenceWidthMhz(frequency);
    }

    @Override
    public void tick() {
        super.tick();
        if (++refreshTick >= 40) {
            refreshTick = 0;
            ClientPacketDistributor.sendToServer(new AntennaRefreshRequestPayload(payload.pos(), payload.dimension(), payload.viewId()));
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(winX, winY, winX + winW, winY + winH, 0xEE111122);
        g.renderOutline(winX, winY, winW, winH, 0xFF3366CC);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, winY + 8, 0xFF88BBFF);
        g.drawString(font, text("name"), winX + 12, winY + 30, 0xFFCCCCCC);
        if (tab == 0) {
            String[] labels = {"sectors", "azimuth", "tilt", "power", "bandwidth"};
            for (int i = 0; i < labels.length; i++) {
                g.drawString(font, text(labels[i]), winX + 12, winY + 83 + i * 22, 0xFFCCCCCC);
            }
            g.drawString(font, text(pendingConfig() == null ? "invalid" : "shared"), winX + 12, winY + 190,
                    pendingConfig() == null ? 0xFFFF6666 : 0xFF888888);
        } else {
            g.drawString(font, text(tab == 1 ? "preview" : "live_hint"), winX + 12, winY + 76, 0xFF888888);
            TelecomFrequency[] frequencies = TelecomFrequency.values();
            AntennaRadioConfig config = tab == 1 ? pendingConfig() : payload.radioConfig();
            for (int i = page * rows; i < Math.min(frequencies.length, (page + 1) * rows); i++) {
                TelecomFrequency frequency = frequencies[i];
                int y = winY + 93 + i % rows * 22;
                if (tab == 1) {
                    Component capacity = config == null ? text("unavailable") : text("nominal", config.capacityMbps(frequency));
                    g.drawString(font, capacity, winX + winW - 12 - font.width(capacity), y, 0xFF88BBFF);
                } else {
                    g.drawString(font, Component.literal(frequency.getTechnology() + " " + frequency.getFrequencyLabel()), winX + 12, y, 0xFFCCCCCC);
                    int[] stats = payload.freqUtilization().get(i);
                    Component usage = (payload.enabledFrequenciesMask() & (1 << i)) == 0 ? text("disabled")
                            : text("usage", stats == null ? 0 : stats[0], config.capacityMbps(frequency));
                    g.drawString(font, usage, winX + winW - 12 - font.width(usage), y, 0xFF88BBFF);
                }
            }
            g.drawCenteredString(font, Component.literal((page + 1) + "/" + ((frequencies.length + rows - 1) / rows)),
                    winX + winW - 56, winY + winH - 22, 0xFF888888);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
