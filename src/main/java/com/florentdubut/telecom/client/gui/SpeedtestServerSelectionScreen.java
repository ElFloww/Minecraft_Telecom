package com.florentdubut.telecom.client.gui;

import com.florentdubut.telecom.client.ClientSpeedtestState;
import com.florentdubut.telecom.network.SpeedtestServerOption;
import com.florentdubut.telecom.network.packet.RequestSpeedtestServersPayload;
import com.florentdubut.telecom.network.packet.RouterGuiSyncPayload;
import com.florentdubut.telecom.network.packet.SpeedtestServersPayload;
import com.florentdubut.telecom.network.packet.SpeedtestUpdatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** The parent instance owns the choice and live test; this screen owns only discovery. */
public class SpeedtestServerSelectionScreen extends Screen {
    private final Screen parent;
    private final boolean mobile;
    private final BlockPos sourcePos;
    private final ClientSpeedtestState.Key key;
    private final Connection connection;
    private final String selectedId;
    private final Consumer<SpeedtestServerOption> choose;
    private UUID requestId;
    private long requestedAt;
    private boolean waiting;
    private boolean truncated;
    private String errorCode = "";
    private List<SpeedtestServerOption> servers = List.of();
    private int page;
    private Button refreshButton;

    public SpeedtestServerSelectionScreen(Screen parent, boolean mobile, BlockPos sourcePos,
                                         ClientSpeedtestState.Key key, String selectedId, Consumer<SpeedtestServerOption> choose) {
        super(Component.translatable("gui.telecom.speedtest.servers"));
        this.parent = parent;
        this.mobile = mobile;
        this.sourcePos = sourcePos;
        this.key = key;
        this.selectedId = selectedId;
        this.choose = choose;
        var listener = Minecraft.getInstance().getConnection();
        this.connection = listener == null ? null : listener.getConnection();
    }

    private int rows() { return Math.max(1, (height - 140) / 36); }

    @Override
    protected void init() {
        page = Math.min(page, Math.max(0, (servers.size() - 1) / rows()));
        int left = Math.max(10, (width - 420) / 2);
        int contentWidth = Math.min(420, width - 20);
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
                .bounds(left, height - 28, 90, 20).build());
        refreshButton = addRenderableWidget(Button.builder(Component.translatable("gui.telecom.speedtest.refresh"), button -> requestServers())
                .bounds(left + contentWidth - 100, height - 28, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.telecom.speedtest.auto"), button -> select(
                new SpeedtestServerOption("", "", 0, true, 0, "")))
                .bounds(left, 30, contentWidth, 20).build());
        int end = Math.min(servers.size(), (page + 1) * rows());
        for (int i = page * rows(); i < end; i++) {
            SpeedtestServerOption option = servers.get(i);
            String label = (selectedId.equals(option.id()) ? "> " : "") + option.name() + " [" + option.id() + "]";
            Button button = addRenderableWidget(Button.builder(Component.literal(label), ignored -> select(option))
                    .bounds(left, 58 + (i - page * rows()) * 36, contentWidth, 20).build());
            button.setTooltip(Tooltip.create(Component.literal(option.name() + " [" + option.id() + "]\n")
                    .append(optionStatus(option))));
            // Unavailable destinations remain selectable: never silently fall back to Auto.
        }
        addRenderableWidget(Button.builder(Component.literal("<"), button -> {
            page--;
            rebuildWidgets();
        }).bounds(left + 100, height - 28, 25, 20).build()).active = page > 0;
        addRenderableWidget(Button.builder(Component.literal(">"), button -> {
            page++;
            rebuildWidgets();
        }).bounds(left + 135, height - 28, 25, 20).build()).active = end < servers.size();
        if (requestId == null) requestServers();
    }

    private void requestServers() {
        if (key == null || connection == null || requestId != null && System.nanoTime() - requestedAt < 1_000_000_000L) return;
        requestId = UUID.randomUUID();
        requestedAt = System.nanoTime();
        waiting = true;
        errorCode = "";
        ClientPacketDistributor.sendToServer(new RequestSpeedtestServersPayload(mobile, sourcePos, requestId, key.dimension()));
    }

    public void receiveServers(Connection sourceConnection, SpeedtestServersPayload payload) {
        if (sourceConnection != connection || key == null || !payload.requestId().equals(requestId)
                || !key.dimension().equals(payload.dimension()) || !key.deviceId().equals(payload.deviceId())) return;
        waiting = false;
        servers = payload.servers();
        truncated = payload.truncated();
        errorCode = payload.errorCode();
        page = Math.min(page, Math.max(0, (servers.size() - 1) / rows()));
        if (minecraft != null) rebuildWidgets();
    }

    private void select(SpeedtestServerOption option) {
        choose.accept(option);
        onClose();
    }

    public void updateRouter(RouterGuiSyncPayload payload) {
        if (parent instanceof RouterScreen router) router.updatePayload(payload);
    }

    public void updateSpeedtestProgress(SpeedtestUpdatePayload payload) {
        if (parent instanceof RouterScreen router) router.updateSpeedtestProgress(payload);
        if (parent instanceof SmartphoneSpeedtestScreen phone) phone.updateSpeedtestProgress(payload);
    }

    @Override
    public void tick() {
        var client = Minecraft.getInstance();
        if (client.getConnection() == null || client.getConnection().getConnection() != connection
                || key == null || !key.dimension().equals(ClientSpeedtestState.currentDimension())) {
            client.setScreen(null);
            return;
        }
        parent.tick();
        long elapsed = System.nanoTime() - requestedAt;
        if (waiting && elapsed >= 3_000_000_000L) {
            waiting = false;
            errorCode = "timeout";
        }
        if (refreshButton != null) refreshButton.active = elapsed >= 1_000_000_000L;
    }

    @Override
    public void onClose() { Minecraft.getInstance().setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return false; }

    public static Component destination(String id, String name) {
        return id.isEmpty() ? Component.translatable("gui.telecom.speedtest.auto")
                : Component.literal(name.isEmpty() ? id : name + " [" + id + "]");
    }

    public static Component error(String code) {
        return Component.translatable("gui.telecom.speedtest.error." + code);
    }

    private static Component optionStatus(SpeedtestServerOption option) {
        return option.available()
                ? Component.translatable("gui.telecom.speedtest.estimated_ping", option.estimatedPingMs(), option.bandwidthMbps())
                : error(option.reason().isEmpty() ? "server_unavailable" : option.reason());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFFFF);
        int left = Math.max(10, (width - 420) / 2);
        for (int i = page * rows(); i < Math.min(servers.size(), (page + 1) * rows()); i++) {
            var option = servers.get(i);
            graphics.drawString(font, optionStatus(option), left + 4, 80 + (i - page * rows()) * 36,
                    option.available() ? 0xFF99DD99 : 0xFFFF8888);
        }
        Component status = waiting ? Component.translatable("gui.telecom.speedtest.loading")
                : !errorCode.isEmpty() ? error(errorCode)
                : Component.translatable(truncated ? "gui.telecom.speedtest.truncated" : "gui.telecom.speedtest.server_count", servers.size());
        graphics.drawCenteredString(font, status, width / 2, height - 47, 0xFFCCCCCC);
    }
}
