package com.florentdubut.telecom.client.gui;

import com.florentdubut.telecom.block.entity.AntennaBlockEntity;
import com.florentdubut.telecom.network.packet.AntennaConfigPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

public class AntennaScreen extends Screen {
    private final AntennaBlockEntity blockEntity;
    private EditBox nameBox;
    private Checkbox box3G;
    private Checkbox box4G;
    private Checkbox box5G;

    public AntennaScreen(AntennaBlockEntity blockEntity) {
        super(Component.literal("Antenna Configuration"));
        this.blockEntity = blockEntity;
    }

    @Override
    protected void init() {
        super.init();
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        this.nameBox = new EditBox(this.font, centerX - 100, centerY - 60, 200, 20, Component.literal("Name"));
        this.nameBox.setValue(blockEntity.getAntennaName());
        this.nameBox.setMaxLength(32);
        this.addRenderableWidget(this.nameBox);

        this.box3G = Checkbox.builder(Component.literal("Enable 3G (900MHz)"), this.font)
                .pos(centerX - 100, centerY - 30)
                .selected(blockEntity.is3GEnabled())
                .build();
        this.addRenderableWidget(this.box3G);

        this.box4G = Checkbox.builder(Component.literal("Enable 4G (800/2600MHz)"), this.font)
                .pos(centerX - 100, centerY - 10)
                .selected(blockEntity.is4GEnabled())
                .build();
        this.addRenderableWidget(this.box4G);

        this.box5G = Checkbox.builder(Component.literal("Enable 5G (3500MHz)"), this.font)
                .pos(centerX - 100, centerY + 10)
                .selected(blockEntity.is5GEnabled())
                .build();
        this.addRenderableWidget(this.box5G);

        this.addRenderableWidget(Button.builder(Component.literal("Save"), button -> saveAndClose())
                .bounds(centerX - 50, centerY + 40, 100, 20)
                .build());
    }

    private void saveAndClose() {
        // Send packet to server
        AntennaConfigPayload payload = new AntennaConfigPayload(
            blockEntity.getBlockPos(),
            nameBox.getValue(),
            box3G.selected(),
            box4G.selected(),
            box5G.selected()
        );
        PacketDistributor.sendToServer(payload);
        this.onClose();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 80, 0xFFFFFF);
    }
    
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
