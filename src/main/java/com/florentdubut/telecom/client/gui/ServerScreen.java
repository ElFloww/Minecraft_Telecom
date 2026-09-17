package com.florentdubut.telecom.client.gui;

import com.florentdubut.telecom.network.packet.ServerGuiSyncPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class ServerScreen extends Screen {
    private ServerGuiSyncPayload payload;
    private final net.minecraft.network.Connection sourceConnection;
    private final net.minecraft.client.multiplayer.ClientLevel sourceLevel;
    
    public void updatePayload(ServerGuiSyncPayload newPayload) {
        this.payload = newPayload;
        updateBandwidth(newPayload.totalBandwidthDown(), newPayload.totalBandwidthUp());
    }
    private int totalDown;
    private int totalUp;
    private int tickCounter;
    private final int imageWidth = 310;
    private final int imageHeight = 220;

    public ServerScreen(ServerGuiSyncPayload payload) {
        super(Component.translatable("gui.telecom.server.title"));
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        this.sourceConnection = minecraft.getConnection() == null ? null : minecraft.getConnection().getConnection();
        this.sourceLevel = minecraft.level;
        this.payload = payload;
        this.totalDown = payload.totalBandwidthDown();
        this.totalUp = payload.totalBandwidthUp();
    }

    public boolean matchesView(net.minecraft.network.Connection connection, ServerGuiSyncPayload update) {
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        return sourceConnection == connection && sourceLevel == minecraft.level
                && payload.viewId().equals(update.viewId()) && payload.dimension().equals(update.dimension())
                && payload.pos().equals(update.pos());
    }

    public void updateBandwidth(int down, int up) {
        this.totalDown = down;
        this.totalUp = up;
    }

    @Override
    protected void init() {
        addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds((width - 100) / 2, (height + imageHeight) / 2 - 25, 100, 20).build());
    }

    @Override
    public void tick() {
        super.tick();
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.level != sourceLevel || minecraft.level == null || minecraft.getConnection() == null
                || minecraft.getConnection().getConnection() != sourceConnection
                || !minecraft.level.dimension().identifier().toString().equals(payload.dimension())) {
            onClose();
            return;
        }
        if (++tickCounter % 10 == 0) {
            net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(
                    new com.florentdubut.telecom.network.packet.RequestServerRefreshPayload(payload.viewId(), payload.dimension(), payload.pos()));
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Do nothing to keep the world fully visible
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int startX = (this.width - this.imageWidth) / 2;
        int startY = (this.height - this.imageHeight) / 2;

        // Draw a dark datacenter-like panel (Semi-transparent)
        guiGraphics.fill(startX, startY, startX + this.imageWidth, startY + this.imageHeight, 0xDD111111);
        guiGraphics.fill(startX + 2, startY + 2, startX + this.imageWidth - 2, startY + this.imageHeight - 2, 0xDD222222);

        guiGraphics.nextStratum();

        // Title
        guiGraphics.drawString(this.font, title, startX + 10, startY + 10, 0xFF00FF00);

        // Stats
        guiGraphics.drawString(this.font, Component.translatable("gui.telecom.server.devices"), startX + 10, startY + 30, 0xFFAAAAAA);
        
        guiGraphics.drawString(this.font, Component.translatable("gui.telecom.server.routers", payload.routerCount()), startX + 20, startY + 43, 0xFFFFFFFF);
        guiGraphics.drawString(this.font, Component.translatable("gui.telecom.server.antennas", payload.antennaCount()), startX + 20, startY + 56, 0xFFFFFFFF);
        guiGraphics.drawString(this.font, Component.translatable("gui.telecom.server.phones", payload.phoneCount()), startX + 20, startY + 69, 0xFFFFFFFF);

        long totalDevices = (long) payload.routerCount() + payload.antennaCount();
        guiGraphics.drawString(this.font, Component.translatable("gui.telecom.server.endpoints", totalDevices), startX + 10, startY + 86, 0xFF00FFFF);

        // Bandwidth
        guiGraphics.drawString(this.font, Component.translatable("gui.telecom.capacity.directional"), startX + 10, startY + 105, 0xFFFFAA00);
        renderUsage(guiGraphics, startX + 10, startY + 122, "gui.telecom.capacity.down", totalDown, payload.capacityDown(), 0xFF00FFFF);
        renderUsage(guiGraphics, startX + 10, startY + 157, "gui.telecom.capacity.up", totalUp, payload.capacityUp(), 0xFFFF8800);

        guiGraphics.nextStratum();
        
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private void renderUsage(GuiGraphics graphics, int x, int y, String label, int usage, int capacity, int color) {
        double fraction = capacity <= 0 ? 0 : Math.max(0, (double) usage / capacity);
        graphics.drawString(font, Component.translatable("gui.telecom.capacity.rate", Component.translatable(label), usage, capacity), x, y, color);
        graphics.drawString(font, Component.translatable("gui.telecom.capacity.load", String.format(java.util.Locale.ROOT, "%.1f", fraction * 100)), x, y + 11, color);
        int barWidth = imageWidth - 20;
        graphics.fill(x, y + 22, x + barWidth, y + 27, 0xFF444444);
        graphics.fill(x, y + 22, x + (int) (barWidth * Math.min(1, fraction)), y + 27, color);
    }
}
