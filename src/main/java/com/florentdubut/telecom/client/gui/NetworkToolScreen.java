package com.florentdubut.telecom.client.gui;

import com.florentdubut.telecom.network.packet.NetworkToolSyncPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class NetworkToolScreen extends Screen {
    private NetworkToolSyncPayload payload;
    private final int imageWidth = 310;
    private final int imageHeight = 190;
    private int tickCounter = 0;

    public NetworkToolScreen(NetworkToolSyncPayload payload) {
        super(Component.translatable("gui.telecom.tool.title"));
        this.payload = payload;
    }

    public void updatePayload(NetworkToolSyncPayload payload) {
        this.payload = payload;
    }

    @Override
    protected void init() {
        addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds((width - 100) / 2, (height + imageHeight) / 2 - 25, 100, 20).build());
    }

    static double utilization(long usage, int capacity) {
        return capacity <= 0 ? 0 : Math.max(0, (double) usage / capacity);
    }

    @Override
    public void tick() {
        super.tick();
        tickCounter++;
        if (tickCounter % 10 == 0) { // Refresh every 0.5s
            net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(
                new com.florentdubut.telecom.network.packet.NetworkToolRefreshRequestPayload(payload.clickedPos())
            );
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Do nothing to keep the world fully visible and unblurred
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int startX = (this.width - this.imageWidth) / 2;
        int startY = (this.height - this.imageHeight) / 2;

        // Tablet-like background (Semi-transparent so we can see the cables behind)
        guiGraphics.fill(startX, startY, startX + this.imageWidth, startY + this.imageHeight, 0xDD222222);
        guiGraphics.fill(startX + 2, startY + 2, startX + this.imageWidth - 2, startY + this.imageHeight - 2, 0xDD111111);

        guiGraphics.nextStratum();

        // Header
        guiGraphics.fill(startX + 2, startY + 2, startX + this.imageWidth - 2, startY + 20, 0xDD0055AA);
        guiGraphics.drawString(this.font, title, startX + 10, startY + 6, 0xFFFFFFFF);

        // Content
        guiGraphics.drawString(this.font, Component.translatable(payload.edgeType()), startX + 10, startY + 30, 0xFF00FFFF);
        boolean shared = payload.mode() == NetworkToolSyncPayload.CapacityMode.SHARED;
        guiGraphics.drawString(this.font, Component.translatable(shared ? "gui.telecom.capacity.shared" : "gui.telecom.capacity.directional"),
                startX + 10, startY + 45, 0xFFCCCCCC);
        if (shared) {
            guiGraphics.drawString(this.font, Component.translatable("gui.telecom.tool.length", payload.length()), startX + 10, startY + 60, 0xFFCCCCCC);
            renderUsage(guiGraphics, startX + 10, startY + 80, "gui.telecom.capacity.total",
                    (long) payload.usageDown() + payload.usageUp(), payload.maxBandwidth(), 0xFF00FFFF);
            guiGraphics.drawString(this.font, Component.translatable("gui.telecom.tool.traffic", payload.usageDown(), payload.usageUp()),
                    startX + 10, startY + 115, 0xFFFFAA00);
        } else {
            renderUsage(guiGraphics, startX + 10, startY + 75, "gui.telecom.capacity.down", payload.usageDown(), payload.maxBandwidth(), 0xFF00FFFF);
            renderUsage(guiGraphics, startX + 10, startY + 115, "gui.telecom.capacity.up", payload.usageUp(), payload.capacityUp(), 0xFFFF8800);
        }

        guiGraphics.nextStratum();
        
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private void renderUsage(GuiGraphics graphics, int x, int y, String label, long usage, int capacity, int color) {
        double fraction = utilization(usage, capacity);
        graphics.drawString(font, Component.translatable("gui.telecom.capacity.rate", Component.translatable(label), usage, capacity), x, y, color);
        graphics.drawString(font, Component.translatable("gui.telecom.capacity.load", String.format(java.util.Locale.ROOT, "%.1f", fraction * 100)), x, y + 12, color);
        int barWidth = imageWidth - 20;
        graphics.fill(x, y + 24, x + barWidth, y + 29, 0xFF444444);
        graphics.fill(x, y + 24, x + (int) (barWidth * Math.min(1, fraction)), y + 29, color);
    }
}
