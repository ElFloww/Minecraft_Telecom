package com.florentdubut.telecom.client.gui;

import com.florentdubut.telecom.network.TelecomFrequency;
import com.florentdubut.telecom.network.packet.CoverageTilePayload;
import com.florentdubut.telecom.network.packet.MapNodeData;
import com.florentdubut.telecom.network.packet.MapMicrowaveData;
import com.florentdubut.telecom.network.packet.NetworkMapResponsePayload;
import com.florentdubut.telecom.network.packet.RequestNetworkMapPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class NetworkMapScreen extends Screen {
    private static final String[] TECHNOLOGIES = {"all", "2G", "3G", "4G", "5G"};
    private static final int[] PRECISIONS = {1, 8, 16};
    private static final String[] LEGEND = {"strong", "medium", "weak", "below", "none", "unknown"};
    private final CoverageMapState coverage = new CoverageMapState();
    private List<MapNodeData> nodes = List.of();
    private List<MapMicrowaveData> microwaveLinks = List.of();
    private UUID viewId = UUID.randomUUID();
    private boolean loading = true;
    private boolean centered;
    private boolean closed;
    private boolean coverageEnabled;
    private double panX;
    private double panY;
    private double zoom = 1.0;
    private boolean isDragging;
    private long ticks;
    private long viewChangedAt;
    private String dimension = "";
    private int technologyIndex;
    private String band = "all";
    private String antenna = "all";
    private int precisionIndex = 2;
    private int heightMode; // Surface, live player Y, custom Y.
    private String customY = "64";
    private String lastHeight = "surface";
    private boolean validHeight = true;
    private int mapTop;
    private int mapBottom;
    private EditBox heightBox;
    private Button coverageButton;
    private Button technologyButton;
    private Button bandButton;
    private Button antennaButton;
    private Button heightButton;
    private Button precisionButton;

    public NetworkMapScreen() {
        super(text("title"));
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("screen.telecom.map." + key, args);
    }

    @Override
    protected void init() {
        super.init();
        if (closed || !dimension.equals(currentDimension())) {
            viewId = UUID.randomUUID();
            nodes = List.of();
            microwaveLinks = List.of();
            loading = true;
        }
        closed = false;
        dimension = currentDimension();
        int columns = width >= 640 ? 6 : 3;
        int gap = 4;
        int buttonWidth = Math.max(20, (width - (columns + 1) * gap) / columns);
        mapTop = 4 + ((8 + columns - 1) / columns) * 22;
        mapBottom = Math.max(mapTop + 1, height - 48);
        zoom = Math.max(zoom, CoverageMapState.minimumZoom(width, mapBottom - mapTop));
        coverageButton = button(0, columns, buttonWidth, button -> {
            coverageEnabled = !coverageEnabled;
            viewChanged();
        });
        technologyButton = button(1, columns, buttonWidth, button -> {
            technologyIndex = (technologyIndex + 1) % TECHNOLOGIES.length;
            band = "all";
            viewChanged();
        });
        bandButton = button(2, columns, buttonWidth, button -> {
            List<String> bands = new ArrayList<>();
            bands.add("all");
            Arrays.stream(TelecomFrequency.values())
                    .filter(f -> technologyIndex == 0 || f.getTechnology().equals(TECHNOLOGIES[technologyIndex]))
                    .forEach(f -> bands.add(f.name()));
            band = bands.get((bands.indexOf(band) + 1) % bands.size());
            viewChanged();
        });
        antennaButton = button(3, columns, buttonWidth, button -> {
            antenna = "all";
            viewChanged();
        });
        antennaButton.setTooltip(Tooltip.create(text("antenna_hint")));
        heightButton = button(4, columns, buttonWidth, button -> {
            heightMode = (heightMode + 1) % 3;
            viewChanged();
        });
        precisionButton = button(5, columns, buttonWidth, button -> {
            precisionIndex = (precisionIndex + 1) % PRECISIONS.length;
            viewChanged();
        });
        precisionButton.setTooltip(Tooltip.create(text("precision_hint")));
        heightBox = new EditBox(font, gap + (6 % columns) * (buttonWidth + gap),
                4 + (6 / columns) * 22, buttonWidth, 20, text("custom_y"));
        heightBox.setMaxLength(11);
        heightBox.setValue(customY);
        heightBox.setTooltip(Tooltip.create(text("custom_y_hint")));
        heightBox.setResponder(value -> {
            customY = value;
            viewChanged();
        });
        addRenderableWidget(heightBox);
        button(7, columns, buttonWidth, button -> {
            centerOnPlayer();
            viewChanged(true);
        }).setMessage(text("center"));
        if (!centered) centerOnPlayer();
        viewChanged();
    }

    private Button button(int index, int columns, int buttonWidth, Button.OnPress action) {
        return addRenderableWidget(Button.builder(Component.empty(), action)
                .bounds(4 + (index % columns) * (buttonWidth + 4), 4 + (index / columns) * 22, buttonWidth, 20).build());
    }

    private void centerOnPlayer() {
        if (minecraft != null && minecraft.player != null) {
            panX = -minecraft.player.getX() * zoom;
            panY = -minecraft.player.getZ() * zoom;
            centered = true;
        }
    }

    private String currentDimension() {
        return minecraft == null || minecraft.level == null ? "" : minecraft.level.dimension().identifier().toString();
    }

    private String selectedHeight() {
        validHeight = true;
        if (heightMode == 0) return "surface";
        if (heightMode == 1) return minecraft != null && minecraft.player != null
                ? Integer.toString(minecraft.player.blockPosition().above().getY()) : "64";
        try {
            return Integer.toString(Integer.parseInt(customY));
        } catch (NumberFormatException exception) {
            validHeight = false;
            return "";
        }
    }

    private void viewChanged() {
        viewChanged(false);
    }

    private void viewChanged(boolean preserveVisible) {
        String height = selectedHeight();
        preserveVisible &= lastHeight.equals(height);
        lastHeight = height;
        viewChangedAt = ticks;
        var tiles = coverageEnabled && validHeight
                ? CoverageMapState.visibleTiles(width, mapBottom - mapTop, width / 2.0 + panX,
                    (mapBottom - mapTop) / 2.0 + panY, zoom, PRECISIONS[precisionIndex]) : List.<CoverageMapState.Tile>of();
        if (preserveVisible) coverage.move(tiles);
        else coverage.reset(dimension, tiles);
        updateLabels();
    }

    private void updateLabels() {
        if (heightBox == null) return;
        coverageButton.setMessage(text(coverageEnabled ? "coverage_on" : "coverage_off"));
        technologyButton.setMessage(text("technology", technologyIndex == 0 ? text("all") : TECHNOLOGIES[technologyIndex]));
        bandButton.setMessage(text("band", band.equals("all") ? text("all") : bandLabel(band)));
        antennaButton.setMessage(text(antenna.equals("all") ? "antennas_all" : "antenna_selected"));
        heightButton.setMessage(text("height", heightMode == 0 ? text("surface") : heightMode == 1 ? text("player_y") : text("custom_y")));
        heightButton.setTooltip(Tooltip.create(text("sample_y", heightMode == 0 ? text("surface") : lastHeight)));
        precisionButton.setMessage(text("precision", PRECISIONS[precisionIndex]));
        heightBox.active = heightMode == 2;
        heightBox.setTextColor(validHeight ? 0xFFFFFFFF : 0xFFFF6666);
    }

    private static String bandLabel(String value) {
        for (TelecomFrequency frequency : TelecomFrequency.values()) {
            if (frequency.name().equals(value)) return frequency.getTechnology() + " " + frequency.getFrequencyLabel();
        }
        return value;
    }

    public void receiveData(NetworkMapResponsePayload payload) {
        if (closed || !viewId.equals(payload.viewId()) || !dimension.equals(payload.dimension())
                || !dimension.equals(currentDimension()) || minecraft == null || minecraft.screen != this) return;
        nodes = payload.nodes();
        microwaveLinks = payload.microwaveLinks();
        loading = false;
    }

    public void receiveCoverage(CoverageTilePayload payload) {
        if (!closed && coverageEnabled && minecraft != null && minecraft.screen == this
                && dimension.equals(currentDimension())) coverage.receive(payload, ticks);
    }

    @Override
    public void tick() {
        super.tick();
        ticks++;
        if (closed) return;
        if (minecraft == null || minecraft.player == null || minecraft.level == null
                || !dimension.equals(currentDimension())) {
            onClose();
            return;
        }
        if (!lastHeight.equals(selectedHeight())) viewChanged();
        if (minecraft.getConnection() != null && ticks % 40 == 1) {
            ClientPacketDistributor.sendToServer(new RequestNetworkMapPayload(viewId, dimension));
        }
        // Let a pan, zoom, or edit settle before creating expensive server jobs.
        if (!coverageEnabled || !validHeight || isDragging || ticks - viewChangedAt < 4
                || minecraft.getConnection() == null) return;
        var request = coverage.nextRequest(ticks, lastHeight, antenna, TECHNOLOGIES[technologyIndex], band);
        if (request != null) ClientPacketDistributor.sendToServer(request);
    }

    @Override
    public void removed() {
        closed = true;
        isDragging = false;
        coverage.reset("", List.of());
        super.removed();
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The map supplies its own opaque background; do not blur it behind the controls.
    }

    private int screenX(double x) { return (int) Math.floor(width / 2.0 + panX + x * zoom); }
    private int screenZ(double z) { return (int) Math.floor((mapTop + mapBottom) / 2.0 + panY + z * zoom); }
    private boolean inMap(double x, double y) { return x >= 0 && x < width && y >= mapTop && y < mapBottom; }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xFF111111);
        graphics.enableScissor(0, mapTop, width, mapBottom);
        List<Component> tooltip = new ArrayList<>();
        if (coverageEnabled) renderCoverage(graphics, mouseX, mouseY, tooltip);
        MapMicrowaveData hoveredLink = renderMicrowaveLinks(graphics, mouseX, mouseY);
        MapNodeData hoveredNode = null;
        for (MapNodeData node : nodes) {
            int x = screenX(node.pos().getX()), y = screenZ(node.pos().getZ());
            if (x < -5 || x > width + 5 || y < mapTop - 5 || y > mapBottom + 5) continue;
            int color = switch (node.type()) {
                case "SERVER" -> 0xFFFF0000;
                case "ROUTER" -> 0xFFFFAA00;
                case "ANTENNA" -> 0xFF00FFFF;
                case "MICROWAVE_DISH" -> 0xFFA78BFA;
                case "NRO" -> 0xFFFF00FF;
                case "NRA" -> 0xFFAAFF00;
                case "PM" -> 0xFFFFFF00;
                case "SR" -> 0xFF55FF55;
                default -> 0xFFFFFFFF;
            };
            int size = node.type().equals("ANTENNA") ? 4 : 3;
            graphics.fill(x - size - 1, y - size - 1, x + size + 1, y + size + 1, 0xFF000000);
            graphics.fill(x - size, y - size, x + size, y + size, color);
            if (antenna.equals(Long.toString(node.pos().asLong()))) graphics.renderOutline(x - 6, y - 6, 12, 12, 0xFFFFFFFF);
            if (Math.abs(mouseX - x) <= size && Math.abs(mouseY - y) <= size) hoveredNode = node;
        }
        if (minecraft != null && minecraft.player != null) {
            int x = screenX(minecraft.player.getX()), y = screenZ(minecraft.player.getZ());
            graphics.fill(x - 2, y - 2, x + 2, y + 2, 0xFF00FF00);
            graphics.drawString(font, text("you"), x + 5, y - 5, 0xFF00FF00);
        }
        if (loading) graphics.drawString(font, text("loading"), 5, mapTop + 4, 0xFFFFFFFF);
        graphics.disableScissor();
        renderFooter(graphics, mouseX, mouseY);
        graphics.nextStratum();
        super.render(graphics, mouseX, mouseY, partialTick);
        if (inMap(mouseX, mouseY)) {
            if (hoveredNode != null) {
                tooltip.add(Component.literal(hoveredNode.type()));
                tooltip.add(text("position", hoveredNode.pos().toShortString()));
                if (hoveredNode.ipAddress() != null && !hoveredNode.ipAddress().isEmpty()) tooltip.add(text("ip", hoveredNode.ipAddress()));
                if (hoveredNode.extraInfo() != null && !hoveredNode.extraInfo().isEmpty()) tooltip.add(
                        hoveredNode.type().equals("MICROWAVE_DISH") ? Component.literal(hoveredNode.extraInfo())
                                : text("technology", hoveredNode.extraInfo()));
                if (hoveredNode.type().equals("ANTENNA")) tooltip.add(text("antenna_hint"));
                if (hoveredNode.type().equals("MICROWAVE_DISH")) {
                    for (var link : microwaveLinks) {
                        if (link.source().equals(hoveredNode.pos()) || link.target().equals(hoveredNode.pos())) {
                            addMicrowaveTooltip(tooltip, link);
                        }
                    }
                }
            } else if (hoveredLink != null) {
                addMicrowaveTooltip(tooltip, hoveredLink);
            }
            if (!tooltip.isEmpty()) graphics.setComponentTooltipForNextFrame(font, tooltip, mouseX, mouseY);
        }
    }

    private MapMicrowaveData renderMicrowaveLinks(GuiGraphics graphics, int mouseX, int mouseY) {
        MapMicrowaveData hovered = null;
        for (var link : microwaveLinks) {
            double[] line = MicrowaveMapGeometry.clip(screenX(link.source().getX()), screenZ(link.source().getZ()),
                    screenX(link.target().getX()), screenZ(link.target().getZ()), width, mapTop, mapBottom);
            if (line == null) continue;
            double dx = line[2] - line[0], dy = line[3] - line[1];
            double length = Math.hypot(dx, dy);
            int color = MicrowaveMapGeometry.color(link.state());
            graphics.pose().pushMatrix();
            graphics.pose().translate((float) line[0], (float) line[1]);
            graphics.pose().rotate((float) Math.atan2(dy, dx));
            int dashes = Math.min(1024, Math.max(1, (int) Math.ceil(length / 12)));
            for (int i = 0; i < dashes; i++) {
                int x = (int) (length * i / dashes);
                graphics.fill(x, -1, Math.min(x + 6, (int) Math.ceil(length)), 1, color);
            }
            graphics.pose().popMatrix();
            if (MicrowaveMapGeometry.hit(line, mouseX, mouseY)) hovered = link;
            if (link.blocker() != null) {
                int x = screenX(link.blocker().getX()), y = screenZ(link.blocker().getZ());
                graphics.renderOutline(x - 3, y - 3, 6, 6, 0xFFEF4444);
            }
        }
        return hovered;
    }

    private static void addMicrowaveTooltip(List<Component> tooltip, MapMicrowaveData link) {
        tooltip.add(text("position", link.source().toShortString()));
        tooltip.add(Component.translatable("gui.telecom.microwave.peer", link.target().toShortString()));
        tooltip.add(Component.translatable("gui.telecom.microwave.state." + link.state()));
        tooltip.add(Component.translatable("gui.telecom.microwave.capacity", link.capacityMbps(), link.nominalCapacityMbps()));
        tooltip.add(Component.translatable("gui.telecom.microwave.latency", String.format(Locale.ROOT, "%.2f", link.latencyMs())));
        if (link.blocker() != null) tooltip.add(Component.translatable("gui.telecom.microwave.blocker", link.blocker().toShortString()));
    }

    private void renderCoverage(GuiGraphics graphics, int mouseX, int mouseY, List<Component> tooltip) {
        for (var tile : coverage.tiles()) {
            double originX = (double) tile.x() * tile.span(), originZ = (double) tile.z() * tile.span();
            int x = screenX(originX), y = screenZ(originZ);
            int right = screenX(originX + tile.span()), bottom = screenZ(originZ + tile.span());
            var data = coverage.data(tile, ticks);
            boolean ready = data != null && data.status().equals("ready");
            graphics.fill(x, y, right, bottom, CoverageMapState.color(ready ? "unknown" : "pending"));
            if (!ready) {
                graphics.renderOutline(x, y, right - x, bottom - y, 0xFF435366);
                if (mouseX >= x && mouseX < right && mouseY >= y && mouseY < bottom) {
                    tooltip.add(text("status." + (data == null ? "pending" : data.status())));
                    if (data != null) tooltip.add(text("progress", Math.round(data.progress() * 100)));
                }
                continue;
            }
            for (var cell : data.cells()) {
                // Service samples are block centres (integer step / 2, including step 1).
                int left = screenX((double) cell.x() - tile.step() / 2);
                int top = screenZ((double) cell.z() - tile.step() / 2);
                int cellRight = screenX((double) cell.x() - tile.step() / 2 + tile.step());
                int cellBottom = screenZ((double) cell.z() - tile.step() / 2 + tile.step());
                String state = CoverageMapState.signalState(cell);
                graphics.fill(left, top, cellRight, cellBottom, CoverageMapState.color(state));
                if (mouseX >= left && mouseX < cellRight && mouseY >= top && mouseY < cellBottom) {
                    tooltip.add(text("legend." + state));
                    tooltip.add(text("position", cell.x() + ", " + cell.y() + ", " + cell.z()));
                    tooltip.add(text("sample_y", cell.y()));
                    tooltip.add(text("power", "signal".equals(cell.state()) && Float.isFinite(cell.powerDbm())
                            ? String.format(Locale.ROOT, "%.1f dBm", cell.powerDbm()) : text("unavailable")));
                    tooltip.add(text("technology", valueOrUnknown(cell.technology())));
                    tooltip.add(text("band", valueOrUnknown(cell.band() == null ? null : bandLabel(cell.band()))));
                    tooltip.add(text("antenna", antennaPosition(cell.antenna())));
                    String service = "available".equals(cell.service()) ? "available"
                            : "unavailable".equals(cell.service()) ? "unavailable" : "unknown";
                    tooltip.add(text("service", text("service." + service)));
                }
            }
        }
    }

    private static Object valueOrUnknown(String value) {
        return value == null || value.isEmpty() ? text("unavailable") : value;
    }

    private static Object antennaPosition(String value) {
        if (value == null || value.isEmpty()) return text("unavailable");
        try {
            return BlockPos.of(Long.parseLong(value)).toShortString();
        } catch (NumberFormatException exception) {
            return value;
        }
    }

    private void renderFooter(GuiGraphics graphics, int mouseX, int mouseY) {
        int ready = 0, pending = 0, busy = 0, invalid = 0;
        float progress = 0;
        List<CoverageMapState.Tile> tiles = coverage.tiles();
        for (var tile : tiles) {
            var data = coverage.data(tile, ticks);
            if (data == null) { pending++; continue; }
            switch (data.status()) {
                case "ready" -> { ready++; progress++; }
                case "busy" -> busy++;
                case "invalid", "limited" -> invalid++;
                default -> { pending++; progress += data.progress(); }
            }
        }
        Component status = !validHeight ? text("invalid_y") : !coverageEnabled
                ? text("navigation", String.format(Locale.ROOT, "%.2f", zoom), nodes.size())
                : text("summary", ready, tiles.size(), tiles.isEmpty() ? 0 : Math.round(progress * 100 / tiles.size()),
                    tiles.isEmpty() ? PRECISIONS[precisionIndex] : tiles.getFirst().step());
        graphics.drawString(font, font.plainSubstrByWidth(status.getString(), Math.max(1, width - 8)), 4, mapBottom + 3, 0xFFFFFFFF);
        if (!coverageEnabled) {
            graphics.fill(4, mapBottom + 17, 10, mapBottom + 23, 0xFFA78BFA);
            graphics.drawString(font, Component.translatable("block.telecom.microwave_dish"), 14, mapBottom + 16, 0xFFDDDDDD);
            for (int x = 4; x < 28; x += 12) graphics.fill(x, mapBottom + 32, x + 6, mapBottom + 34, 0xFF38BDF8);
            graphics.drawString(font, Component.literal("FH"), 32, mapBottom + 29, 0xFF38BDF8);
            return;
        }
        Component details = text("details", pending, busy, invalid);
        graphics.drawString(font, font.plainSubstrByWidth(details.getString(), Math.max(1, width - 8)), 4, mapBottom + 14, 0xFFBBBBBB);
        if (mouseY >= mapBottom && mouseY < mapBottom + 24) {
            graphics.setComponentTooltipForNextFrame(font, List.of(status, details, text("precision_hint")), mouseX, mouseY);
        }
        int slot = Math.max(1, width / 3);
        for (int i = 0; i < LEGEND.length; i++) {
            int x = (i % 3) * slot + 4, y = mapBottom + 25 + (i / 3) * 11;
            graphics.fill(x, y, x + 6, y + 7, CoverageMapState.color(LEGEND[i]));
            graphics.drawString(font, font.plainSubstrByWidth(text("legend_short." + LEGEND[i]).getString(), Math.max(1, slot - 16)), x + 9, y, 0xFFDDDDDD);
            if (mouseX >= x && mouseX < x + slot - 4 && mouseY >= y && mouseY < y + 10) {
                graphics.setComponentTooltipForNextFrame(font, List.of(text("legend." + LEGEND[i])), mouseX, mouseY);
            }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // Widgets (especially the Y editor) must consume clicks before map dragging.
        if (super.mouseClicked(event, doubleClick)) return true;
        if (!inMap(event.x(), event.y())) return false;
        setFocused(null);
        if (event.button() == 0 && coverageEnabled) {
            for (MapNodeData node : nodes) {
                if (node.type().equals("ANTENNA") && Math.abs(event.x() - screenX(node.pos().getX())) <= 5
                        && Math.abs(event.y() - screenZ(node.pos().getZ())) <= 5) {
                    String selected = Long.toString(node.pos().asLong());
                    antenna = antenna.equals(selected) ? "all" : selected;
                    viewChanged();
                    return true;
                }
            }
        }
        if (event.button() == 0 || event.button() == 1) {
            isDragging = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        boolean dragging = isDragging;
        if (event.button() == 0 || event.button() == 1) isDragging = false;
        return super.mouseReleased(event) || dragging;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (isDragging) {
            panX += dragX;
            panY += dragY;
            clampPan();
            viewChanged(true);
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    private void clampPan() {
        panX = Math.clamp(panX, -29_999_000 * zoom, 29_999_000 * zoom);
        panY = Math.clamp(panY, -29_999_000 * zoom, 29_999_000 * zoom);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) return true;
        if (!inMap(mouseX, mouseY) || scrollY == 0) return false;
        double oldZoom = zoom;
        zoom = Math.clamp(scrollY > 0 ? zoom * 1.2 : zoom / 1.2,
                CoverageMapState.minimumZoom(width, mapBottom - mapTop), 32.0);
        double cx = width / 2.0, cy = (mapTop + mapBottom) / 2.0;
        panX = mouseX - cx - (mouseX - cx - panX) / oldZoom * zoom;
        panY = mouseY - cy - (mouseY - cy - panY) / oldZoom * zoom;
        clampPan();
        if (zoom != oldZoom) viewChanged(true);
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
