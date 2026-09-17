package com.florentdubut.telecom.client.gui;

import com.florentdubut.telecom.client.ClientSpeedtestState;
import com.florentdubut.telecom.network.packet.RouterGuiSyncPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

public class RouterScreen extends Screen {

    private RouterGuiSyncPayload payload;
    private final ClientSpeedtestState.Key speedtestKey;
    private Button startButton;
    private boolean speedtestPending;
    private int refreshTick = 0;
    private String selectedServerId = "";
    private String selectedServerName = "";
    private String speedtestError = "";
    
    public void updatePayload(RouterGuiSyncPayload newPayload) {
        if (!payload.pos().equals(newPayload.pos())) return;
        this.payload = newPayload;
        restoreSpeedtest();
    }
    
    @Override
    public void tick() {
        super.tick();
        if (minecraft != null && minecraft.player != null && (minecraft.level == null
                || !speedtestKey.dimension().equals(ClientSpeedtestState.currentDimension())
                || !minecraft.player.isWithinBlockInteractionRange(payload.pos(), 0))) {
            minecraft.setScreen(null);
            return;
        }
        restoreSpeedtest();
        refreshTick++;
        if (refreshTick >= 20) {
            refreshTick = 0;
            ClientPacketDistributor.sendToServer(new com.florentdubut.telecom.network.packet.GuiRefreshRequestPayload(payload.pos()));
        }
    }


    private boolean speedtestActive = false;
    private com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload currentSpeedtestData = null;
    private int lastDownBw = 0;
    private int lastUpBw = 0;
    private int durationIndex = 0;
    private final int[] DURATION_TICKS = {300, 600, 1200, 6000, 12000};
    private final String[] DURATION_LABELS = {"15s", "30s", "60s", "5m", "10m"};

    public void updateSpeedtestProgress(com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload payload) {
        if (!speedtestKey.matches(payload)) return;
        speedtestError = ClientSpeedtestState.get(speedtestKey).errorCode();
        // Networking has already accepted or discarded this packet. Never bypass its session guard.
        restoreSpeedtest();
    }

    private void restoreSpeedtest() {
        var state = ClientSpeedtestState.get(speedtestKey);
        speedtestActive = state.active();
        speedtestPending = state.pending();
        currentSpeedtestData = state.pending() ? null : state.payload();
        if (!state.errorCode().isEmpty()) speedtestError = state.errorCode();
        lastDownBw = currentSpeedtestData != null ? currentSpeedtestData.downloadBandwidth() : payload.lastDownBw();
        lastUpBw = currentSpeedtestData != null ? currentSpeedtestData.uploadBandwidth() : payload.lastUpBw();
        if (startButton != null) {
            startButton.active = !speedtestActive;
            startButton.setMessage(SpeedtestPanel.text(speedtestPending ? "pending" : "start"));
        }
    }

    public RouterScreen(RouterGuiSyncPayload payload) {
        super(Component.literal("Router Interface"));
        this.payload = payload;
        this.speedtestKey = ClientSpeedtestState.routerKey(ClientSpeedtestState.currentDimension(), payload.pos());
        restoreSpeedtest();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Do nothing
    }

    @Override
    protected void init() {
        super.init();

        int boxWidth = Math.min(380, this.width - 12);
        int startX = (this.width - boxWidth) / 2;
        int startY = (this.height - Math.min(340, this.height - 12)) / 2;
        int controlWidth = (boxWidth - 24) / 3;

        this.addRenderableWidget(Button.builder(SpeedtestPanel.text("duration", DURATION_LABELS[durationIndex]), button -> {
            durationIndex = (durationIndex + 1) % DURATION_TICKS.length;
            button.setMessage(SpeedtestPanel.text("duration", DURATION_LABELS[durationIndex]));
        }).bounds(startX + 8, startY + 47, controlWidth, 18).build());

        this.startButton = this.addRenderableWidget(Button.builder(SpeedtestPanel.text("start"), b -> {
            int confDown = payload.configuredMaxDown();
            int confUp = payload.configuredMaxUp();
            restoreSpeedtest();
            if (this.speedtestActive) return;
            if (payload.isConnected() && confDown > 0 && confUp > 0) {
                if (!ClientSpeedtestState.markPending(speedtestKey)) return;
                speedtestError = "";
                ClientPacketDistributor.sendToServer(new com.florentdubut.telecom.network.packet.StartSpeedtestPayload(payload.pos(), payload.ipAddress(), confDown, confUp, 0, 0, DURATION_TICKS[durationIndex], selectedServerId, speedtestKey.dimension()));
                restoreSpeedtest();
            } else {
                speedtestError = "no_server";
                String reason = !payload.isConnected() ? "No network connection!" : "Bandwidth not configured!";
                if (minecraft.player != null) {
                    minecraft.player.displayClientMessage(Component.literal("Failed to start: " + reason), false);
                }
            }
        }).bounds(startX + 12 + controlWidth, startY + 47, controlWidth, 18).build());
        this.addRenderableWidget(Button.builder(SpeedtestPanel.text("server_button"), button ->
                minecraft.setScreen(new SpeedtestServerSelectionScreen(this, false, payload.pos(), speedtestKey, selectedServerId, option -> {
                    selectedServerId = option.id();
                    selectedServerName = option.name();
                }))).bounds(startX + 16 + 2 * controlWidth, startY + 47, controlWidth, 18).build());
        restoreSpeedtest();
    }

    @Override
    public void onClose() {
        super.onClose();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        restoreSpeedtest();
        // Dark background overlay
        guiGraphics.fillGradient(0, 0, this.width, this.height, 0x40101010, 0x60101010);

        int centerX = this.width / 2;
        int centerY = this.height / 2;

        int boxWidth = Math.min(380, this.width - 12);
        int boxHeight = Math.min(340, this.height - 12);
        int startX = centerX - boxWidth / 2;
        int startY = centerY - boxHeight / 2;

        // Router Box Background
        guiGraphics.fill(startX, startY, startX + boxWidth, startY + boxHeight, 0xDD222222);
        // Border
        guiGraphics.renderOutline(startX, startY, boxWidth, boxHeight, 0xDD44AAFF);

        guiGraphics.nextStratum();

        // Title
        guiGraphics.drawCenteredString(this.font, SpeedtestPanel.text("router_title"), centerX, startY + 8, 0xFFFFFFFF);
        int statusColor = payload.isConnected() ? 0xFF00FF00 : 0xFFFF0000;
        SpeedtestPanel.clipped(guiGraphics, font, SpeedtestPanel.text(payload.isConnected() ? "connected" : "offline", payload.ipAddress()),
                startX + 8, startY + 23, boxWidth - 16, statusColor);
        SpeedtestPanel.clipped(guiGraphics, font, SpeedtestPanel.text("plan", SpeedtestPanel.rate(payload.configuredMaxDown()),
                SpeedtestPanel.rate(payload.configuredMaxUp())), startX + 8, startY + 34, boxWidth - 16, 0xFFCCCCCC);

        guiGraphics.drawString(font, font.plainSubstrByWidth(Component.translatable("gui.telecom.speedtest.selected",
                SpeedtestServerSelectionScreen.destination(selectedServerId, selectedServerName)).getString(), boxWidth - 16), startX + 8, startY + 69, 0xFFCCCCCC);
        if (currentSpeedtestData != null && !"REJECTED".equals(currentSpeedtestData.state()) && !currentSpeedtestData.serverId().isEmpty()) {
            guiGraphics.drawString(font, font.plainSubstrByWidth(Component.translatable("gui.telecom.speedtest.used",
                    SpeedtestServerSelectionScreen.destination(currentSpeedtestData.serverId(), currentSpeedtestData.serverName())).getString(), boxWidth - 16),
                    startX + 8, startY + 80, 0xFF99DD99);
        }
        if (!speedtestError.isEmpty()) guiGraphics.drawString(font, font.plainSubstrByWidth(
                SpeedtestServerSelectionScreen.error(speedtestError).getString(), boxWidth - 16), startX + 8, startY + 91, 0xFFFF8888);

        SpeedtestPanel.render(guiGraphics, font, startX + 8, startY + 104, boxWidth - 16, boxHeight - 112,
                ClientSpeedtestState.get(speedtestKey), payload.lastDownBw(), payload.lastUpBw(), payload.lastPing(),
                DURATION_TICKS[durationIndex], System.nanoTime());

        guiGraphics.nextStratum();
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }
}
