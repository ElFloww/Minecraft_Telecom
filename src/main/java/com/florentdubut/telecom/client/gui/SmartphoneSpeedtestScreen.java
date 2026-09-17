package com.florentdubut.telecom.client.gui;

import com.florentdubut.telecom.client.ClientSpeedtestState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

public class SmartphoneSpeedtestScreen extends Screen {

    private final Screen parentScreen;
    private final ClientSpeedtestState.Key speedtestKey;
    private Button startButton;
    private boolean speedtestPending;
    private String selectedServerId = "";
    private String selectedServerName = "";
    private String speedtestError = "";
    private boolean speedtestActive = false;
    private com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload currentSpeedtestData = null;
    
    private int lastDownBw = 0;
    private int lastUpBw = 0;
    private int durationIndex = 0;
    private final int[] DURATION_TICKS = {300, 600, 1200, 6000, 12000};
    private final String[] DURATION_LABELS = {"15s", "30s", "60s", "5m", "10m"};

    public SmartphoneSpeedtestScreen(Screen parentScreen) {
        super(Component.literal("Speedtest"));
        this.parentScreen = parentScreen;
        var player = Minecraft.getInstance().player;
        this.speedtestKey = player == null ? null
                : ClientSpeedtestState.mobileKey(ClientSpeedtestState.currentDimension(), player.getUUID());
        restoreSpeedtest();
    }
    
    public void updateSpeedtestProgress(com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload payload) {
        if (speedtestKey == null || !speedtestKey.matches(payload)) return;
        if (!payload.errorCode().isEmpty()) speedtestError = payload.errorCode();
        else if (currentSpeedtestData == null || !currentSpeedtestData.sessionId().equals(payload.sessionId())) speedtestError = "";
        restoreSpeedtest();
    }

    private void restoreSpeedtest() {
        var state = ClientSpeedtestState.get(speedtestKey);
        speedtestActive = state.active();
        speedtestPending = state.pending();
        currentSpeedtestData = state.pending() ? null : state.payload();
        if (currentSpeedtestData != null && !currentSpeedtestData.errorCode().isEmpty()) speedtestError = currentSpeedtestData.errorCode();
        lastDownBw = currentSpeedtestData == null ? 0 : currentSpeedtestData.downloadBandwidth();
        lastUpBw = currentSpeedtestData == null ? 0 : currentSpeedtestData.uploadBandwidth();
        if (startButton != null) {
            startButton.active = speedtestKey != null && !speedtestActive;
            startButton.setMessage(Component.literal(speedtestPending ? "Waiting..." : "Start Test"));
        }
    }

    @Override
    public void tick() {
        super.tick();
        restoreSpeedtest();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        super.init();
        int screenW = 220;
        int screenH = 260;
        int startX = (this.width - screenW) / 2;
        int startY = (this.height - screenH) / 2;

        this.addRenderableWidget(Button.builder(Component.literal("Durée: " + DURATION_LABELS[durationIndex]), b -> {
            durationIndex = (durationIndex + 1) % DURATION_TICKS.length;
            b.setMessage(Component.literal("Durée: " + DURATION_LABELS[durationIndex]));
        }).bounds(startX + 30, startY + 195, 100, 20).build());

        this.startButton = this.addRenderableWidget(Button.builder(Component.literal("Start Test"), b -> {
            restoreSpeedtest();
            if (speedtestKey == null || this.speedtestActive) return;
            com.florentdubut.telecom.network.packet.NetworkScanResponsePayload scan = SmartphoneHUD.latestScan;
            if (scan != null && scan.found()) {
                if (!ClientSpeedtestState.markPending(speedtestKey)) return;
                speedtestError = "";
                ClientPacketDistributor.sendToServer(new com.florentdubut.telecom.network.packet.StartSpeedtestPayload(
                    scan.antennaPos(), 
                    scan.ipAddress(), 
                    scan.maxDown(),
                    scan.maxUp(),
                    0,
                    scan.frequenciesMask(),
                    DURATION_TICKS[durationIndex], selectedServerId, speedtestKey.dimension()
                ));
                restoreSpeedtest();
            } else {
                speedtestError = "no_server";
                if (minecraft.player != null) {
                    minecraft.player.displayClientMessage(Component.literal("No Network Signal!"), false);
                }
            }
        }).bounds(startX + 30, startY + 220, 100, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.telecom.speedtest.servers"), button ->
                minecraft.setScreen(new SpeedtestServerSelectionScreen(this, true, net.minecraft.core.BlockPos.ZERO,
                        speedtestKey, selectedServerId, option -> {
                    selectedServerId = option.id();
                    selectedServerName = option.name();
                }))).bounds(startX + 10, startY + 177, 200, 16).build());
        restoreSpeedtest();
        
        this.addRenderableWidget(Button.builder(Component.literal("< Back"), b -> {
            net.minecraft.client.Minecraft.getInstance().setScreen(parentScreen);
        }).bounds(startX + 5, startY + 20, 50, 15).build());
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        int screenW = 220;
        int screenH = 260;
        int startX = (this.width - screenW) / 2;
        int startY = (this.height - screenH) / 2;

        // Phone bezel
        guiGraphics.fill(startX - 5, startY - 5, startX + screenW + 5, startY + screenH + 5, 0xFF222222);
        // App background
        guiGraphics.fill(startX, startY, startX + screenW, startY + screenH, 0xFF111122);
        
        // Status bar
        guiGraphics.fill(startX, startY, startX + screenW, startY + 15, 0x88000000);
        com.florentdubut.telecom.network.packet.NetworkScanResponsePayload scan = SmartphoneHUD.latestScan;
        if (scan != null && scan.found()) {
            guiGraphics.drawString(this.font, scan.tech(), startX + 5, startY + 4, 0xFFFFFFFF);
        } else {
            guiGraphics.drawString(this.font, "No Service", startX + 5, startY + 4, 0xFFFF5555);
        }

        guiGraphics.drawCenteredString(this.font, "SPEEDTEST", startX + screenW / 2, startY + 45, 0xFFFFFFFF);
        guiGraphics.drawString(font, font.plainSubstrByWidth(Component.translatable("gui.telecom.speedtest.selected",
                SpeedtestServerSelectionScreen.destination(selectedServerId, selectedServerName)).getString(), 200), startX + 10, startY + 144, 0xFFCCCCCC);
        if (currentSpeedtestData != null && !"REJECTED".equals(currentSpeedtestData.state()) && !currentSpeedtestData.serverId().isEmpty()) {
            guiGraphics.drawString(font, font.plainSubstrByWidth(Component.translatable("gui.telecom.speedtest.used",
                    SpeedtestServerSelectionScreen.destination(currentSpeedtestData.serverId(), currentSpeedtestData.serverName())).getString(), 200),
                    startX + 10, startY + 155, 0xFF99DD99);
        }
        if (!speedtestError.isEmpty()) guiGraphics.drawString(font, font.plainSubstrByWidth(
                SpeedtestServerSelectionScreen.error(speedtestError).getString(), 200), startX + 10, startY + 166, 0xFFFF8888);
        
        if (speedtestPending) {
            guiGraphics.drawCenteredString(this.font, "Waiting...", startX + screenW / 2, startY + 70, 0xFFAAAAAA);
        } else if (currentSpeedtestData != null) {
            String state = currentSpeedtestData.state();
            int color = speedtestActive ? 0xFFAAAAAA : "FINISHED".equals(state) ? 0xFF00FF00 : 0xFFFF5555;
            guiGraphics.drawCenteredString(this.font, speedtestActive ? "Testing: " + state : state, startX + screenW / 2, startY + 70, color);
            
            guiGraphics.drawString(this.font, "Ping: " + currentSpeedtestData.pingMs() + " ms", startX + 20, startY + 90, 0xFF00FF00);
            
            guiGraphics.drawString(this.font, "Down: " + this.lastDownBw + " Mbps", startX + 20, startY + 110, 0xFF00FFFF);
            guiGraphics.drawString(this.font, "Up: " + this.lastUpBw + " Mbps", startX + 20, startY + 130, 0xFFFF8800);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() { Minecraft.getInstance().setScreen(parentScreen); }
}
