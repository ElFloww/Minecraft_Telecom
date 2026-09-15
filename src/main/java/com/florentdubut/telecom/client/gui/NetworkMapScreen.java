package com.florentdubut.telecom.client.gui;

import com.florentdubut.telecom.network.packet.MapNodeData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

public class NetworkMapScreen extends Screen {
    private List<MapNodeData> nodes = new ArrayList<>();
    private boolean loading = true;
    
    // View state
    private double panX = 0;
    private double panY = 0;
    private double zoom = 1.0;
    private boolean isDragging = false;

    public NetworkMapScreen() {
        super(Component.literal("Network Map"));
    }

    public void receiveData(List<MapNodeData> nodesData) {
        this.nodes = nodesData;
        this.loading = false;
        
        // Center on player initially
        Player player = minecraft.player;
        if (player != null) {
            panX = -player.getX() * zoom;
            panY = -player.getZ() * zoom;
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int width = this.width;
        int height = this.height;
        
        int centerX = width / 2;
        int centerY = height / 2;

        if (loading) {
            guiGraphics.drawCenteredString(this.font, "Loading Map Data...", centerX, centerY, 0xFFFFFFFF);
            return;
        }

        // Draw grid
        guiGraphics.fill(0, 0, width, height, 0xFF111111);
        
        // Render origin (Player pos)
        Player player = minecraft.player;
        if (player != null) {
            double px = player.getX();
            double pz = player.getZ();
            int sx = (int) (centerX + (px * zoom) + panX);
            int sy = (int) (centerY + (pz * zoom) + panY);
            guiGraphics.fill(sx - 2, sy - 2, sx + 2, sy + 2, 0xFF00FF00);
            guiGraphics.drawString(this.font, "You", sx + 5, sy - 5, 0xFF00FF00);
        }

        MapNodeData hoveredNode = null;

        for (MapNodeData node : nodes) {
            int nx = (int) (centerX + (node.pos().getX() * zoom) + panX);
            int ny = (int) (centerY + (node.pos().getZ() * zoom) + panY);

            // Skip off-screen
            if (nx < -50 || nx > width + 50 || ny < -50 || ny > height + 50) continue;

            int color = 0xFFFFFFFF;
            switch(node.type()) {
                case "SERVER": color = 0xFFFF0000; break;
                case "ROUTER": color = 0xFFFFAA00; break;
                case "ANTENNA": color = 0xFF00FFFF; break;
                case "NRO": color = 0xFFFF00FF; break;
                case "NRA": color = 0xFFAAFF00; break;
                case "PM": color = 0xFFFFFF00; break;
                case "SR": color = 0xFF55FF55; break;
            }

            int size = node.type().equals("ANTENNA") ? 4 : 3;
            guiGraphics.fill(nx - size, ny - size, nx + size, ny + size, color);

            // Check hover
            if (mouseX >= nx - size && mouseX <= nx + size && mouseY >= ny - size && mouseY <= ny + size) {
                hoveredNode = node;
            }
        }

        // Overlay text
        guiGraphics.drawString(this.font, "Zoom: " + String.format("%.2fx", zoom), 10, 10, 0xFFFFFFFF);
        guiGraphics.drawString(this.font, "Nodes: " + nodes.size(), 10, 25, 0xFFFFFFFF);

        // Render Tooltip
        if (hoveredNode != null) {
            List<Component> tooltip = new ArrayList<>();
            tooltip.add(Component.literal(hoveredNode.type()).withStyle(net.minecraft.ChatFormatting.BOLD));
            tooltip.add(Component.literal("Pos: " + hoveredNode.pos().toShortString()));
            if (hoveredNode.ipAddress() != null && !hoveredNode.ipAddress().isEmpty()) {
                tooltip.add(Component.literal("IP: " + hoveredNode.ipAddress()).withStyle(net.minecraft.ChatFormatting.AQUA));
            }
            if (hoveredNode.extraInfo() != null && !hoveredNode.extraInfo().isEmpty()) {
                tooltip.add(Component.literal("Tech: " + hoveredNode.extraInfo()).withStyle(net.minecraft.ChatFormatting.GREEN));
            }
            guiGraphics.setComponentTooltipForNextFrame(this.font, tooltip, mouseX, mouseY);
        }
        
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 || event.button() == 1) { // Left or Right click to drag
            isDragging = true;
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0 || event.button() == 1) {
            isDragging = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (isDragging) {
            panX += dragX;
            panY += dragY;
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        double oldZoom = zoom;
        if (scrollY > 0) {
            zoom *= 1.2;
        } else if (scrollY < 0) {
            zoom /= 1.2;
        }
        
        // Clamp zoom
        if (zoom < 0.05) zoom = 0.05;
        if (zoom > 5.0) zoom = 5.0;

        // Keep centered on mouse pointer during zoom
        int centerX = width / 2;
        int centerY = height / 2;
        
        double worldX = (mouseX - centerX - panX) / oldZoom;
        double worldY = (mouseY - centerY - panY) / oldZoom;

        panX = (mouseX - centerX) - (worldX * zoom);
        panY = (mouseY - centerY) - (worldY * zoom);

        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
