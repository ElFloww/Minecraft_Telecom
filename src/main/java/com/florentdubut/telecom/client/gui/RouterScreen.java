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
        if (!payload.errorCode().isEmpty()) speedtestError = payload.errorCode();
        else if (currentSpeedtestData == null || !currentSpeedtestData.sessionId().equals(payload.sessionId())) speedtestError = "";
        // Networking has already accepted or discarded this packet. Never bypass its session guard.
        restoreSpeedtest();
    }

    private void restoreSpeedtest() {
        var state = ClientSpeedtestState.get(speedtestKey);
        speedtestActive = state.active();
        speedtestPending = state.pending();
        currentSpeedtestData = state.pending() ? null : state.payload();
        if (currentSpeedtestData != null && !currentSpeedtestData.errorCode().isEmpty()) speedtestError = currentSpeedtestData.errorCode();
        lastDownBw = currentSpeedtestData != null ? currentSpeedtestData.downloadBandwidth() : payload.lastDownBw();
        lastUpBw = currentSpeedtestData != null ? currentSpeedtestData.uploadBandwidth() : payload.lastUpBw();
        if (startButton != null) {
            startButton.active = !speedtestActive;
            startButton.setMessage(Component.literal(speedtestPending ? "Waiting..." : "START SPEEDTEST"));
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

        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int startX = centerX - 360 / 2;
        int startY = centerY - 240 / 2;

        this.addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal("Durée: " + DURATION_LABELS[durationIndex]), button -> {
            durationIndex = (durationIndex + 1) % DURATION_TICKS.length;
            button.setMessage(Component.literal("Durée: " + DURATION_LABELS[durationIndex]));
        }).bounds(startX + 20, startY + 120, 100, 20).build());

        this.startButton = this.addRenderableWidget(Button.builder(Component.literal("START SPEEDTEST"), b -> {
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
        }).bounds(startX + 20, startY + 145, 100, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.telecom.speedtest.servers"), button ->
                minecraft.setScreen(new SpeedtestServerSelectionScreen(this, false, payload.pos(), speedtestKey, selectedServerId, option -> {
                    selectedServerId = option.id();
                    selectedServerName = option.name();
                }))).bounds(startX + 140, startY + 145, 200, 20).build());
        restoreSpeedtest();
    }

    @Override
    public void onClose() {
        super.onClose();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Dark background overlay
        guiGraphics.fillGradient(0, 0, this.width, this.height, 0x40101010, 0x60101010);

        int centerX = this.width / 2;
        int centerY = this.height / 2;

        int boxWidth = 360;
        int boxHeight = 240;
        int startX = centerX - boxWidth / 2;
        int startY = centerY - boxHeight / 2;

        // Router Box Background
        guiGraphics.fill(startX, startY, startX + boxWidth, startY + boxHeight, 0xDD222222);
        // Border
        guiGraphics.renderOutline(startX, startY, boxWidth, boxHeight, 0xDD44AAFF);

        guiGraphics.nextStratum();

        // Title
        guiGraphics.drawCenteredString(this.font, "ROUTER CONFIGURATION", centerX, startY + 10, 0xFFFFFFFF);

        // Status
        String statusText = payload.isConnected() ? "CONNECTED TO NETWORK" : "OFFLINE";
        int statusColor = payload.isConnected() ? 0xFF00FF00 : 0xFFFF0000;
        guiGraphics.drawString(this.font, "Status: " + statusText, startX + 20, startY + 30, statusColor);

        // IP Address
        guiGraphics.drawString(this.font, "IP Address: " + (!payload.ipAddress().isEmpty() ? payload.ipAddress() : "N/A"), startX + 20, startY + 45, 0xFFCCCCCC);

        // Max Hardware Bandwidth
        guiGraphics.drawString(this.font, "Hardware Max: " + (payload.isConnected() ? payload.bandwidthMbps() + " Mbps" : "---"), startX + 20, startY + 60, 0xFFCCCCCC);
        
        guiGraphics.drawString(this.font, "Plan Down: " + payload.configuredMaxDown() + " Mbps", startX + 20, startY + 90, 0xFF00FFFF);
        guiGraphics.drawString(this.font, "Plan Up: " + payload.configuredMaxUp() + " Mbps", startX + 20, startY + 110, 0xFFFF8800);

        guiGraphics.drawString(font, font.plainSubstrByWidth(Component.translatable("gui.telecom.speedtest.selected",
                SpeedtestServerSelectionScreen.destination(selectedServerId, selectedServerName)).getString(), 320), startX + 20, startY + 180, 0xFFCCCCCC);
        if (currentSpeedtestData != null && !"REJECTED".equals(currentSpeedtestData.state()) && !currentSpeedtestData.serverId().isEmpty()) {
            guiGraphics.drawString(font, font.plainSubstrByWidth(Component.translatable("gui.telecom.speedtest.used",
                    SpeedtestServerSelectionScreen.destination(currentSpeedtestData.serverId(), currentSpeedtestData.serverName())).getString(), 320),
                    startX + 20, startY + 195, 0xFF99DD99);
        }
        if (!speedtestError.isEmpty()) guiGraphics.drawString(font, font.plainSubstrByWidth(
                SpeedtestServerSelectionScreen.error(speedtestError).getString(), 320), startX + 20, startY + 215, 0xFFFF8888);

        // Speedtest overlay logic (shifted right)
        int stX = startX + 215;
        int stY = startY + 75;
        int stW = 120;
        int stH = 65;
        
        if (currentSpeedtestData != null || speedtestPending || payload.lastPing() > 0) {
            guiGraphics.fill(stX, stY, stX + stW, stY + stH, 0xFF111111);
            guiGraphics.renderOutline(stX, stY, stW, stH, 0xFF555555);
            
            String state = speedtestPending ? "Waiting..." : currentSpeedtestData == null ? "FINISHED" : currentSpeedtestData.state();
            guiGraphics.drawString(this.font, speedtestActive && !speedtestPending ? "TESTING: " + state : state, stX + 10, stY + 10, 0xFFFFFFFF);
            if (!speedtestPending) {
                int ping = currentSpeedtestData == null ? payload.lastPing() : currentSpeedtestData.pingMs();
                guiGraphics.drawString(this.font, "Ping: " + ping + " ms", stX + 10, stY + 25, 0xFF00FF00);
                guiGraphics.drawString(this.font, "Down: " + this.lastDownBw + " Mbps", stX + 10, stY + 40, 0xFF00FFFF);
                guiGraphics.drawString(this.font, "Up: " + this.lastUpBw + " Mbps", stX + 10, stY + 53, 0xFFFF8800);
            }
        }

        guiGraphics.nextStratum();
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }
}
