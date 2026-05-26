package com.florentdubut.telecom.client.gui;

import com.florentdubut.telecom.network.packet.NetworkScanResponsePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.client.DeltaTracker;
import com.florentdubut.telecom.registry.ModItems;

public class SmartphoneHUD {
    public static NetworkScanResponsePayload latestScan = null;
    public static long lastScanTime = 0;

    public static void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Check if player has the smartphone in inventory
        boolean hasPhone = false;
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            if (mc.player.getInventory().getItem(i).is(ModItems.SMARTPHONE.get())) {
                hasPhone = true;
                break;
            }
        }
        if (!hasPhone) return;

        // If the scan is older than 3 seconds, clear it
        if (System.currentTimeMillis() - lastScanTime > 3000) {
            latestScan = null;
        }

        Font font = mc.font;
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();

        // Draw in top right corner
        int startX = width - 150;
        int startY = 10;

        // Background box
        guiGraphics.fill(startX, startY, width - 10, startY + 50, 0xAA000000);

        if (latestScan == null) {
            guiGraphics.drawString(font, "Scanning...", startX + 5, startY + 5, 0xAAAAAA);
        } else if (latestScan.found()) {
            guiGraphics.drawString(font, latestScan.name(), startX + 5, startY + 5, 0xFFFFFF);
            guiGraphics.drawString(font, latestScan.tech(), startX + 5, startY + 15, 0x00FF00);
            
            int signal = latestScan.signalStrength();
            guiGraphics.drawString(font, signal + " dBm", startX + 5, startY + 25, 0xAAAAAA);
            
            guiGraphics.drawString(font, "IP: " + latestScan.ipAddress(), startX + 5, startY + 35, 0x00FFFF);

            // Draw Signal Bars
            int bars = 0;
            if (signal > -60) bars = 4;
            else if (signal > -80) bars = 3;
            else if (signal > -100) bars = 2;
            else if (signal > -115) bars = 1;
            
            for (int i = 0; i < 4; i++) {
                int color = i < bars ? 0xFF00FF00 : 0xFF555555;
                guiGraphics.fill(startX + 120 + (i * 4), startY + 25 + (12 - (i * 3)), startX + 122 + (i * 4), startY + 37, color);
            }
        } else {
            guiGraphics.drawString(font, "No Service", startX + 5, startY + 5, 0xFF0000);
        }
    }
}
