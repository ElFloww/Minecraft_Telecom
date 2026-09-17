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
    
    public void updatePayload(RouterGuiSyncPayload newPayload) {
        if (!payload.pos().equals(newPayload.pos())) return;
        this.payload = newPayload;
        restoreSpeedtest();
    }
    
    @Override
    public void tick() {
        super.tick();
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
        // Networking has already accepted or discarded this packet. Never bypass its session guard.
        restoreSpeedtest();
    }

    private void restoreSpeedtest() {
        var state = ClientSpeedtestState.get(speedtestKey);
        speedtestActive = state.active();
        speedtestPending = state.pending();
        currentSpeedtestData = state.pending() ? null : state.payload();
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
        int startX = centerX - 260 / 2;
        int startY = centerY - 180 / 2;

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
                ClientPacketDistributor.sendToServer(new com.florentdubut.telecom.network.packet.StartSpeedtestPayload(payload.pos(), payload.ipAddress(), confDown, confUp, 0, 0, DURATION_TICKS[durationIndex]));
                restoreSpeedtest();
            } else {
                String reason = !payload.isConnected() ? "No network connection!" : "Bandwidth not configured!";
                if (minecraft.player != null) {
                    minecraft.player.displayClientMessage(Component.literal("Failed to start: " + reason), false);
                }
            }
        }).bounds(startX + 20, startY + 145, 100, 20).build());
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

        int boxWidth = 260;
        int boxHeight = 180;
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

        guiGraphics.drawString(this.font, "Press ESC to save and close", startX + 130, startY + 150, 0xFF555555);

        // Speedtest overlay logic (shifted right)
        int stX = startX + 265;
        int stY = startY + 30;
        int stW = 120;
        int stH = 100;
        
        if (currentSpeedtestData != null || speedtestPending || payload.lastPing() > 0) {
            guiGraphics.fill(stX, stY, stX + stW, stY + stH, 0xFF111111);
            guiGraphics.renderOutline(stX, stY, stW, stH, 0xFF555555);
            
            String state = speedtestPending ? "Waiting..." : currentSpeedtestData == null ? "FINISHED" : currentSpeedtestData.state();
            guiGraphics.drawString(this.font, speedtestActive && !speedtestPending ? "TESTING: " + state : state, stX + 10, stY + 10, 0xFFFFFFFF);
            if (!speedtestPending) {
                int ping = currentSpeedtestData == null ? payload.lastPing() : currentSpeedtestData.pingMs();
                guiGraphics.drawString(this.font, "Ping: " + ping + " ms", stX + 10, stY + 30, 0xFF00FF00);
                guiGraphics.drawString(this.font, "Down: " + this.lastDownBw + " Mbps", stX + 10, stY + 50, 0xFF00FFFF);
                guiGraphics.drawString(this.font, "Up: " + this.lastUpBw + " Mbps", stX + 10, stY + 70, 0xFFFF8800);
            }
        }

        guiGraphics.nextStratum();
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }
}
