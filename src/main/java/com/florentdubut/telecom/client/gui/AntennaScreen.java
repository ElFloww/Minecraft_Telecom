package com.florentdubut.telecom.client.gui;

import com.florentdubut.telecom.block.entity.AntennaBlockEntity;
import com.florentdubut.telecom.network.TelecomFrequency;
import com.florentdubut.telecom.network.packet.AntennaConfigPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.HashMap;
import java.util.Map;

public class AntennaScreen extends Screen {
    private final AntennaBlockEntity blockEntity;
    private EditBox nameBox;
    private final Map<TelecomFrequency, Checkbox> checkboxes = new HashMap<>();

    public AntennaScreen(AntennaBlockEntity blockEntity) {
        super(Component.literal("Antenna Configuration"));
        this.blockEntity = blockEntity;
    }

    @Override
    protected void init() {
        super.init();
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        this.nameBox = new EditBox(this.font, centerX - 100, centerY - 100, 200, 20, Component.literal("Name"));
        this.nameBox.setValue(blockEntity.getAntennaName());
        this.nameBox.setMaxLength(32);
        this.addRenderableWidget(this.nameBox);

        // Layout variables
        int startX = centerX - 180;
        int startY = centerY - 60;
        int colWidth = 90;
        int rowHeight = 20;

        int[] colOffsets = new int[]{0, 0, 0, 0}; // Track rows per col (2G, 3G, 4G, 5G)

        for (TelecomFrequency freq : TelecomFrequency.values()) {
            int colIndex = 0;
            if (freq.getTechnology().equals("3G")) colIndex = 1;
            else if (freq.getTechnology().equals("4G")) colIndex = 2;
            else if (freq.getTechnology().equals("5G")) colIndex = 3;

            int x = startX + (colIndex * colWidth);
            int y = startY + (colOffsets[colIndex] * rowHeight);

            String label = freq.getFrequencyLabel();
            if (!freq.getBandName().equals("-")) {
                label += " (" + freq.getBandName() + ")";
            }

            Checkbox box = Checkbox.builder(Component.literal(label), this.font)
                    .pos(x, y)
                    .selected(blockEntity.isFrequencyEnabled(freq))
                    .build();
            
            this.addRenderableWidget(box);
            checkboxes.put(freq, box);
            colOffsets[colIndex]++;
        }

        this.addRenderableWidget(Button.builder(Component.literal("Save"), button -> saveAndClose())
                .bounds(centerX - 50, centerY + 90, 100, 20)
                .build());
    }

    private void saveAndClose() {
        int mask = 0;
        for (TelecomFrequency freq : TelecomFrequency.values()) {
            Checkbox box = checkboxes.get(freq);
            if (box != null && box.selected()) {
                mask |= (1 << freq.ordinal());
            }
        }

        AntennaConfigPayload payload = new AntennaConfigPayload(
            blockEntity.getBlockPos(),
            nameBox.getValue(),
            mask
        );
        PacketDistributor.sendToServer(payload);
        this.onClose();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        
        guiGraphics.drawCenteredString(this.font, this.title, centerX, centerY - 120, 0xFFFFFF);

        // Column Headers
        guiGraphics.drawString(this.font, "2G (GSM)", centerX - 180, centerY - 75, 0xAAAAAA);
        guiGraphics.drawString(this.font, "3G (UMTS)", centerX - 90, centerY - 75, 0xAAAAAA);
        guiGraphics.drawString(this.font, "4G (LTE)", centerX, centerY - 75, 0xAAAAAA);
        guiGraphics.drawString(this.font, "5G (NR)", centerX + 90, centerY - 75, 0xAAAAAA);
    }
    
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
