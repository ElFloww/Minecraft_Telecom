package com.florentdubut.telecom.client.gui;

import com.florentdubut.telecom.block.entity.MicrowaveDishBlockEntity;
import com.florentdubut.telecom.network.MicrowaveConfig;
import com.florentdubut.telecom.network.MicrowaveNetworking;
import com.florentdubut.telecom.network.packet.MicrowaveConfigPayload;
import com.florentdubut.telecom.network.packet.MicrowaveGuiSyncPayload;
import com.florentdubut.telecom.network.packet.MicrowaveRefreshRequestPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.Locale;
import java.util.function.Consumer;

public class MicrowaveDishScreen extends Screen {
    private MicrowaveGuiSyncPayload payload;
    // Telemetry never writes draft fields, including while rebuilding tabs or resizing.
    private String draftName, channel, azimuth, elevation;
    private final String[] target = new String[3];
    private int frequency;
    private boolean enabled;
    private int tab, refreshTick, winX, winY, winW, winH;
    private Button saveButton, aimButton;

    public MicrowaveDishScreen(MicrowaveGuiSyncPayload payload) {
        super(text("title"));
        this.payload = payload;
        draftName = payload.name();
        MicrowaveConfig config = payload.config();
        channel = Integer.toString(config.channel());
        frequency = config.frequencyGhz();
        azimuth = Integer.toString(config.azimuthDegrees());
        elevation = Integer.toString(config.elevationDegrees());
        enabled = config.enabled();
        BlockPos peer = config.peer();
        target[0] = peer == null ? "" : Integer.toString(peer.getX());
        target[1] = peer == null ? "" : Integer.toString(peer.getY());
        target[2] = peer == null ? "" : Integer.toString(peer.getZ());
    }

    public void receiveUpdate(MicrowaveGuiSyncPayload update) {
        if (!update.opening() && payload.pos().equals(update.pos()) && payload.dimension().equals(update.dimension())
                && payload.viewId().equals(update.viewId())) payload = update;
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("gui.telecom.microwave." + key, args);
    }

    @Override
    protected void init() {
        super.init();
        saveButton = null;
        aimButton = null;
        winW = Math.min(460, width - 12);
        winH = Math.min(260, height - 12);
        winX = (width - winW) / 2;
        winY = (height - winH) / 2;
        EditBox name = new EditBox(font, winX + 62, winY + 25, winW - 74, 18, text("name"));
        name.setMaxLength(MicrowaveDishBlockEntity.MAX_NAME_LENGTH);
        name.setValue(draftName);
        name.setResponder(value -> { draftName = value; updateButtons(); });
        addRenderableWidget(name);

        String[] tabs = {"pairing", "radio", "status"};
        int tabWidth = (winW - 24) / 3;
        for (int i = 0; i < tabs.length; i++) {
            int selected = i;
            addRenderableWidget(Button.builder(text(tabs[i]), ignored -> { tab = selected; rebuildWidgets(); })
                    .bounds(winX + 12 + i * tabWidth, winY + 49, tabWidth - 2, 20).build()).active = tab != i;
        }
        int fieldX = winX + winW - 114;
        if (tab == 0) {
            String[] keys = {"target_x", "target_y", "target_z"};
            for (int i = 0; i < keys.length; i++) {
                int index = i;
                numberField(keys[i], target[i], i, value -> target[index] = value);
            }
            addRenderableWidget(Button.builder(text("clear_peer"), ignored -> {
                java.util.Arrays.fill(target, "");
                rebuildWidgets();
            }).bounds(winX + 12, winY + 146, (winW - 28) / 2, 20).tooltip(Tooltip.create(text("pairing_hint"))).build());
            aimButton = addRenderableWidget(Button.builder(text("aim"), ignored -> {
                aimAtPeer();
                rebuildWidgets();
            }).bounds(winX + winW / 2 + 2, winY + 146, (winW - 28) / 2, 20)
                    .tooltip(Tooltip.create(text("aim_hint"))).build());
        } else if (tab == 1) {
            addRenderableWidget(Button.builder(text(enabled ? "enabled" : "disabled"), button -> {
                enabled = !enabled;
                button.setMessage(text(enabled ? "enabled" : "disabled"));
            }).bounds(fieldX, winY + 77, 102, 20).build());
            numberField("channel", channel, 1, value -> channel = value);
            addRenderableWidget(Button.builder(text("ghz", frequency), button -> {
                frequency = switch (frequency) { case 6 -> 11; case 11 -> 18; case 18 -> 38; default -> 6; };
                button.setMessage(text("ghz", frequency));
            }).bounds(fieldX, winY + 121, 102, 20).build());
            numberField("azimuth", azimuth, 3, value -> azimuth = value);
            numberField("elevation", elevation, 4, value -> elevation = value);
        }
        saveButton = addRenderableWidget(Button.builder(text("save"), ignored -> {
            MicrowaveConfigPayload save = savePayload();
            if (save != null) {
                ClientPacketDistributor.sendToServer(save);
                onClose();
            }
        }).bounds(winX + 12, winY + winH - 28, 108, 20).tooltip(Tooltip.create(text("save_hint"))).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), ignored -> onClose())
                .bounds(winX + winW - 120, winY + winH - 28, 108, 20).build());
        updateButtons();
    }

    private void numberField(String key, String value, int row, Consumer<String> setter) {
        EditBox field = new EditBox(font, winX + winW - 114, winY + 77 + row * 22, 102, 18, text(key));
        field.setMaxLength(11);
        field.setValue(value);
        field.setTooltip(Tooltip.create(text(key + "_hint")));
        field.setResponder(updated -> { setter.accept(updated); updateButtons(); });
        addRenderableWidget(field);
    }

    private BlockPos typedPeer() {
        if (target[0].isBlank() && target[1].isBlank() && target[2].isBlank()) return null;
        BlockPos peer = new BlockPos(Integer.parseInt(target[0]), Integer.parseInt(target[1]), Integer.parseInt(target[2]));
        if (!MicrowaveNetworking.validPeer(payload.pos(), peer)) throw new IllegalArgumentException("Invalid peer");
        if (minecraft != null && minecraft.level != null
                && (peer.getY() < minecraft.level.getMinY() || peer.getY() > minecraft.level.getMaxY())) {
            throw new IllegalArgumentException("Peer outside dimension height");
        }
        return peer;
    }

    private boolean canAim() {
        try { return typedPeer() != null; }
        catch (IllegalArgumentException invalid) { return false; }
    }

    void aimAtPeer() {
        if (!canAim()) return;
        BlockPos peer = typedPeer();
        double dx = (double) peer.getX() - payload.pos().getX();
        double dy = (double) peer.getY() - payload.pos().getY();
        double dz = (double) peer.getZ() - payload.pos().getZ();
        azimuth = Integer.toString(Math.floorMod((int) Math.round(Math.toDegrees(Math.atan2(-dx, dz))), 360));
        elevation = Integer.toString((int) Math.round(Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)))));
    }

    MicrowaveConfig pendingConfig() {
        try {
            MicrowaveConfig config = new MicrowaveConfig(typedPeer(), Integer.parseInt(channel), frequency,
                    Integer.parseInt(azimuth), Integer.parseInt(elevation), enabled);
            return MicrowaveConfigPayload.validConfig(config) ? config : null;
        } catch (IllegalArgumentException invalid) { return null; }
    }

    MicrowaveConfigPayload savePayload() {
        MicrowaveConfig config = pendingConfig();
        return config == null || !MicrowaveDishBlockEntity.validName(draftName) ? null
                : new MicrowaveConfigPayload(payload.pos(), draftName, config, payload.dimension(), payload.viewId());
    }

    private void updateButtons() {
        if (saveButton != null) saveButton.active = savePayload() != null;
        if (aimButton != null) aimButton.active = canAim();
    }

    @Override
    public void tick() {
        super.tick();
        if (++refreshTick >= 40) {
            refreshTick = 0;
            ClientPacketDistributor.sendToServer(new MicrowaveRefreshRequestPayload(payload.pos(), payload.dimension(), payload.viewId()));
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(winX, winY, winX + winW, winY + winH, 0xEE111122);
        graphics.renderOutline(winX, winY, winW, winH, 0xFF3366CC);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, winY + 8, 0xFF88BBFF);
        graphics.drawString(font, text("name"), winX + 12, winY + 30, 0xFFCCCCCC);
        if (tab != 2) {
            String[] labels = tab == 0 ? new String[]{"target_x", "target_y", "target_z"}
                    : new String[]{"power", "channel", "frequency", "azimuth", "elevation"};
            for (int i = 0; i < labels.length; i++) line(graphics, text(labels[i]), 83 + i * 22, 0xFFCCCCCC);
            line(graphics, text(savePayload() == null ? "invalid" : tab == 0 ? "reciprocal" : "local_only"), 190,
                    savePayload() == null ? 0xFFFF6666 : 0xFF888888);
        } else {
            line(graphics, text("authoritative"), 78, 0xFF888888);
            line(graphics, text("state." + payload.state()), 96, 0xFF88BBFF);
            line(graphics, text("capacity", payload.capacityMbps(), payload.nominalCapacityMbps()), 114, 0xFFCCCCCC);
            line(graphics, text("latency", String.format(Locale.ROOT, "%.2f", payload.latencyMs())), 132, 0xFFCCCCCC);
            line(graphics, payload.config().peer() == null ? text("no_peer") : text("peer", coordinates(payload.target())), 150, 0xFFCCCCCC);
            line(graphics, payload.blocker() == null ? text("no_blocker") : text("blocker", ""), 168, 0xFFCCCCCC);
            if (payload.blocker() != null) line(graphics, Component.literal(coordinates(payload.blocker())), 186, 0xFFCCCCCC);
        }
    }

    private static String coordinates(BlockPos pos) { return pos.getX() + ", " + pos.getY() + ", " + pos.getZ(); }

    private void line(GuiGraphics graphics, Component component, int y, int color) {
        graphics.drawString(font, component, winX + 12, winY + y, color);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
