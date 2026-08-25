package Crazer.cubeofinterest.cointcoregto.monitor;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.Comparator;
import java.util.List;

public class ClusterMonitorScreen extends AbstractContainerScreen<ClusterMonitorMenu> {
    private static final int PANEL_COLOR = 0xEE171A1F;
    private static final int INNER_COLOR = 0xFF252A31;
    private static final int ROW_COLOR = 0xFF1D2229;
    private static final int ROW_ALT_COLOR = 0xFF20262E;
    private static final int SELECTED_COLOR = 0xFF334452;
    private static final int TEXT = 0xFFE7EDF4;
    private static final int MUTED = 0xFF9AA5B1;
    private static final int GREEN = 0xFF55FF55;
    private static final int RED = 0xFFFF5555;
    private static final int YELLOW = 0xFFFFCC55;
    private static final int CYAN = 0xFF55DDEB;

    private static final int LIST_X = 10;
    private static final int LIST_Y = 58;
    private static final int LIST_W = 340;
    private static final int ROW_H = 18;
    private static final int VISIBLE_NODE_ROWS = 8;
    private static final int VISIBLE_BUFFER_ROWS = 5;

    private enum Tab {
        NODES,
        BUFFERS
    }

    private Tab tab = Tab.NODES;
    private ClusterMonitorSnapshot snapshot;
    private int nodeScroll;
    private int bufferScroll;
    private int selectedBuffer = -1;
    private int refreshTicks;
    private boolean requestPending;

    private Button nodesButton;
    private Button buffersButton;

    public ClusterMonitorScreen(
            ClusterMonitorMenu menu,
            Inventory inventory,
            Component title
    ) {
        super(menu, inventory, title);
        this.imageWidth = 360;
        this.imageHeight = 320;
        this.inventoryLabelY = 10_000;
    }

    @Override
    protected void init() {
        super.init();

        nodesButton = addRenderableWidget(Button.builder(
                Component.literal("Ноды"),
                button -> {
                    tab = Tab.NODES;
                    updateTabButtons();
                }
        ).bounds(leftPos + 10, topPos + 34, 70, 20).build());

        buffersButton = addRenderableWidget(Button.builder(
                Component.literal("Supply Buffer"),
                button -> {
                    tab = Tab.BUFFERS;
                    updateTabButtons();
                }
        ).bounds(leftPos + 84, topPos + 34, 104, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal("Обновить"),
                button -> requestSnapshot()
        ).bounds(leftPos + imageWidth - 86, topPos + 34, 76, 20).build());

        updateTabButtons();
        requestSnapshot();
    }

    public void applySnapshot(ClusterMonitorSnapshot snapshot) {
        this.snapshot = snapshot;
        this.requestPending = false;
        this.refreshTicks = 0;

        int maxNodeScroll = Math.max(0, sortedNodes().size() - VISIBLE_NODE_ROWS);
        nodeScroll = Math.min(nodeScroll, maxNodeScroll);

        int maxBufferScroll = Math.max(0, sortedBuffers().size() - VISIBLE_BUFFER_ROWS);
        bufferScroll = Math.min(bufferScroll, maxBufferScroll);
        if (selectedBuffer >= sortedBuffers().size()) {
            selectedBuffer = -1;
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        refreshTicks++;
        if (refreshTicks >= 60 && !requestPending) {
            requestSnapshot();
        }
    }

    private void requestSnapshot() {
        if (requestPending) {
            return;
        }
        requestPending = true;
        ClusterMonitorNetwork.CHANNEL.sendToServer(
                new ClusterMonitorRequestPacket(menu.getBlockPos())
        );
    }

    private void updateTabButtons() {
        if (nodesButton != null) {
            nodesButton.active = tab != Tab.NODES;
        }
        if (buffersButton != null) {
            buffersButton.active = tab != Tab.BUFFERS;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        graphics.fill(x, y, x + imageWidth, y + imageHeight, PANEL_COLOR);
        graphics.fill(x + 4, y + 4, x + imageWidth - 4, y + imageHeight - 4, INNER_COLOR);

        int visibleRows = tab == Tab.NODES ? VISIBLE_NODE_ROWS : VISIBLE_BUFFER_ROWS;
        for (int row = 0; row < visibleRows; row++) {
            int rowY = y + LIST_Y + row * ROW_H;
            int color = (row & 1) == 0 ? ROW_COLOR : ROW_ALT_COLOR;
            if (tab == Tab.BUFFERS) {
                int index = bufferScroll + row;
                if (index == selectedBuffer) {
                    color = SELECTED_COLOR;
                }
            }
            graphics.fill(x + LIST_X, rowY, x + LIST_X + LIST_W, rowY + ROW_H - 1, color);
        }

        if (tab == Tab.BUFFERS) {
            int detailsY = y + 151;
            graphics.fill(x + 10, detailsY, x + imageWidth - 10, y + imageHeight - 10, 0xFF191E24);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, Component.literal("Кластерный монитор"), 10, 8, TEXT, false);

        if (snapshot == null) {
            graphics.drawString(font, Component.literal("Получение данных кластера..."), 118, 9, YELLOW, false);
            return;
        }

        int onlineNodes = 0;
        for (ClusterMonitorSnapshot.NodeEntry node : snapshot.nodes()) {
            if (node.online()) {
                onlineNodes++;
            }
        }

        int onlineBuffers = 0;
        for (ClusterMonitorSnapshot.BufferEntry buffer : snapshot.buffers()) {
            if (buffer.endpointOnline()) {
                onlineBuffers++;
            }
        }

        int clusterColor = snapshot.clusterEnabled() && snapshot.error().isBlank() ? GREEN : RED;
        String clusterText = snapshot.clusterEnabled() ? "CLUSTER ONLINE" : "CLUSTER OFFLINE";
        graphics.drawString(font, Component.literal(clusterText), 118, 9, clusterColor, false);

        String node = snapshot.currentNodeId().isBlank() ? "?" : snapshot.currentNodeId();
        graphics.drawString(
                font,
                Component.literal("Node: " + node),
                10,
                21,
                MUTED,
                false
        );
        graphics.drawString(
                font,
                Component.literal("Ноды " + onlineNodes + "/" + snapshot.nodes().size()
                        + "  •  Buffer " + onlineBuffers + "/" + snapshot.buffers().size()
                        + "  •  Операции " + snapshot.activeOperations()),
                118,
                21,
                snapshot.activeOperations() > 0 ? YELLOW : MUTED,
                false
        );

        if (!snapshot.error().isBlank()) {
            graphics.drawString(
                    font,
                    Component.literal(crop(snapshot.error(), 54)),
                    10,
                    58,
                    RED,
                    false
            );
            return;
        }

        if (tab == Tab.NODES) {
            renderNodes(graphics);
        } else {
            renderBuffers(graphics);
        }
    }

    private void renderNodes(GuiGraphics graphics) {
        List<ClusterMonitorSnapshot.NodeEntry> nodes = sortedNodes();
        graphics.drawString(font, Component.literal("Статус"), 14, 48, MUTED, false);
        graphics.drawString(font, Component.literal("Нода"), 54, 48, MUTED, false);
        graphics.drawString(font, Component.literal("Роль"), 177, 48, MUTED, false);
        graphics.drawString(font, Component.literal("Игроки"), 245, 48, MUTED, false);
        graphics.drawString(font, Component.literal("Миры"), 302, 48, MUTED, false);

        for (int row = 0; row < VISIBLE_NODE_ROWS; row++) {
            int index = nodeScroll + row;
            if (index >= nodes.size()) {
                break;
            }
            ClusterMonitorSnapshot.NodeEntry node = nodes.get(index);
            int y = LIST_Y + row * ROW_H + 5;
            graphics.drawString(font, Component.literal(node.online() ? "●" : "○"), 16, y,
                    node.online() ? GREEN : RED, false);
            graphics.drawString(font, Component.literal(crop(node.nodeId(), 19)), 54, y,
                    node.nodeId().equals(snapshot.currentNodeId()) ? CYAN : TEXT, false);
            graphics.drawString(font, Component.literal(crop(node.role(), 10)), 177, y, MUTED, false);
            graphics.drawString(font, Component.literal(Integer.toString(node.playerCount())), 259, y, TEXT, false);
            graphics.drawString(font, Component.literal(Integer.toString(node.dimensionCount())), 314, y, TEXT, false);
        }

        graphics.drawString(
                font,
                Component.literal("Heartbeat: ○ = offline • колесо мыши — прокрутка"),
                10,
                303,
                MUTED,
                false
        );
    }

    private void renderBuffers(GuiGraphics graphics) {
        List<ClusterMonitorSnapshot.BufferEntry> buffers = sortedBuffers();
        graphics.drawString(font, Component.literal("Статус"), 14, 48, MUTED, false);
        graphics.drawString(font, Component.literal("Роль"), 52, 48, MUTED, false);
        graphics.drawString(font, Component.literal("Нода"), 103, 48, MUTED, false);
        graphics.drawString(font, Component.literal("Владелец"), 210, 48, MUTED, false);
        graphics.drawString(font, Component.literal("Pending"), 302, 48, MUTED, false);

        for (int row = 0; row < VISIBLE_BUFFER_ROWS; row++) {
            int index = bufferScroll + row;
            if (index >= buffers.size()) {
                break;
            }
            ClusterMonitorSnapshot.BufferEntry buffer = buffers.get(index);
            int y = LIST_Y + row * ROW_H + 5;
            int statusColor = buffer.endpointOnline() && buffer.linkOnline() ? GREEN
                    : buffer.endpointOnline() ? YELLOW : RED;
            graphics.drawString(font, Component.literal(buffer.endpointOnline() ? "●" : "○"), 16, y, statusColor, false);
            graphics.drawString(font, Component.literal(shortRole(buffer.role())), 52, y,
                    "PROVIDER".equalsIgnoreCase(buffer.role()) ? CYAN : TEXT, false);
            graphics.drawString(font, Component.literal(crop(buffer.nodeId(), 16)), 103, y, TEXT, false);
            graphics.drawString(font, Component.literal(crop(buffer.ownerName().isBlank() ? "-" : buffer.ownerName(), 13)), 210, y, MUTED, false);
            graphics.drawString(font, Component.literal(Integer.toString(buffer.pendingCount())), 319, y,
                    buffer.pendingCount() > 0 ? YELLOW : TEXT, false);
        }

        renderSelectedBuffer(graphics, buffers);
    }

    private void renderSelectedBuffer(GuiGraphics graphics, List<ClusterMonitorSnapshot.BufferEntry> buffers) {
        if (selectedBuffer < 0 || selectedBuffer >= buffers.size()) {
            graphics.drawString(font, Component.literal("Выбери Supply Buffer для подробностей"), 18, 163, MUTED, false);
            return;
        }

        ClusterMonitorSnapshot.BufferEntry selected = buffers.get(selectedBuffer);
        String link = selected.linkId().length() > 8 ? selected.linkId().substring(0, 8) : selected.linkId();
        graphics.drawString(font, Component.literal(shortRole(selected.role()) + " • link " + link), 18, 158, CYAN, false);
        graphics.drawString(font,
                Component.literal(crop(selected.dimensionId(), 35) + " @ " + selected.blockPosition()),
                18, 170, MUTED, false);

        String state;
        int stateColor;
        if (!selected.endpointOnline()) {
            state = "Endpoint offline • heartbeat " + selected.heartbeatAgeSeconds() + "s";
            stateColor = RED;
        } else if ("PROVIDER".equalsIgnoreCase(selected.role()) && !selected.aeOnline()) {
            state = "Provider online, ME offline";
            stateColor = YELLOW;
        } else if (!selected.linkOnline()) {
            state = "Связь с Provider offline";
            stateColor = YELLOW;
        } else {
            state = "ONLINE";
            stateColor = GREEN;
        }
        graphics.drawString(font, Component.literal(state), 18, 182, stateColor, false);

        if (selected.resources().isEmpty()) {
            if ("PROVIDER".equalsIgnoreCase(selected.role())) {
                graphics.drawString(font, Component.literal("Provider хранит ресурсы в главной ME; локальных фильтров нет."),
                        18, 201, MUTED, false);
            } else {
                graphics.drawString(font, Component.literal("На этом Remote Buffer пока нет настроенных фильтров."),
                        18, 201, MUTED, false);
            }
            return;
        }

        graphics.drawString(font, Component.literal("Предметы"), 18, 198, TEXT, false);
        graphics.drawString(font, Component.literal("Жидкости"), 184, 198, TEXT, false);
        renderResourceColumn(graphics, selected.resources(), "ITEM", 18);
        renderResourceColumn(graphics, selected.resources(), "FLUID", 184);
    }

    private void renderResourceColumn(
            GuiGraphics graphics,
            List<ClusterMonitorSnapshot.ResourceEntry> resources,
            String type,
            int x
    ) {
        for (ClusterMonitorSnapshot.ResourceEntry resource : resources) {
            if (!type.equalsIgnoreCase(resource.type())
                    || resource.displayName().isBlank()
                    || resource.filterIndex() < 0
                    || resource.filterIndex() >= 9) {
                continue;
            }

            long percent = resource.capacity() <= 0L
                    ? 0L
                    : Math.min(100L, resource.amount() * 100L / resource.capacity());
            boolean fluid = "FLUID".equalsIgnoreCase(type);
            String text = (resource.filterIndex() + 1) + " "
                    + crop(resource.displayName(), 12) + " "
                    + percent + "% "
                    + formatAmount(resource.amount(), fluid) + "/" + formatAmount(resource.capacity(), fluid);
            int color = percent < resource.refillBelowPercent()
                    ? RED
                    : percent < resource.refillToPercent() ? YELLOW : GREEN;
            graphics.drawString(
                    font,
                    Component.literal(crop(text, 28)),
                    x,
                    210 + resource.filterIndex() * 10,
                    color,
                    false
            );
        }
    }

    private List<ClusterMonitorSnapshot.NodeEntry> sortedNodes() {
        if (snapshot == null) {
            return List.of();
        }
        return snapshot.nodes().stream()
                .sorted(Comparator.comparing(ClusterMonitorSnapshot.NodeEntry::online).reversed()
                        .thenComparing(ClusterMonitorSnapshot.NodeEntry::nodeId))
                .toList();
    }

    private List<ClusterMonitorSnapshot.BufferEntry> sortedBuffers() {
        if (snapshot == null) {
            return List.of();
        }
        return snapshot.buffers().stream()
                .sorted(Comparator.comparing(ClusterMonitorSnapshot.BufferEntry::endpointOnline).reversed()
                        .thenComparing(ClusterMonitorSnapshot.BufferEntry::nodeId)
                        .thenComparing(ClusterMonitorSnapshot.BufferEntry::role)
                        .thenComparing(ClusterMonitorSnapshot.BufferEntry::endpointId))
                .toList();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (tab == Tab.BUFFERS && button == 0) {
            double localX = mouseX - leftPos;
            double localY = mouseY - topPos;
            if (localX >= LIST_X && localX < LIST_X + LIST_W
                    && localY >= LIST_Y && localY < LIST_Y + VISIBLE_BUFFER_ROWS * ROW_H) {
                int row = (int) ((localY - LIST_Y) / ROW_H);
                int index = bufferScroll + row;
                if (index >= 0 && index < sortedBuffers().size()) {
                    selectedBuffer = index;
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (snapshot == null || delta == 0.0D) {
            return super.mouseScrolled(mouseX, mouseY, delta);
        }

        int direction = delta > 0.0D ? -1 : 1;
        if (tab == Tab.NODES) {
            int max = Math.max(0, sortedNodes().size() - VISIBLE_NODE_ROWS);
            nodeScroll = Math.max(0, Math.min(max, nodeScroll + direction));
            return true;
        }

        int max = Math.max(0, sortedBuffers().size() - VISIBLE_BUFFER_ROWS);
        bufferScroll = Math.max(0, Math.min(max, bufferScroll + direction));
        return true;
    }

    private String crop(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maxChars - 1)) + "…";
    }

    private static String shortRole(String role) {
        if ("PROVIDER".equalsIgnoreCase(role)) {
            return "MAIN";
        }
        if ("REMOTE".equalsIgnoreCase(role)) {
            return "REMOTE";
        }
        return role == null || role.isBlank() ? "?" : role;
    }

    private static String formatAmount(long amount, boolean fluid) {
        long safe = Math.max(0L, amount);
        if (safe >= 1_000_000_000L) {
            return String.format(java.util.Locale.ROOT, "%.1fG%s", safe / 1_000_000_000.0D, fluid ? "mB" : "");
        }
        if (safe >= 1_000_000L) {
            return String.format(java.util.Locale.ROOT, "%.1fM%s", safe / 1_000_000.0D, fluid ? "mB" : "");
        }
        if (safe >= 1_000L) {
            return String.format(java.util.Locale.ROOT, "%.1fk%s", safe / 1_000.0D, fluid ? "mB" : "");
        }
        return Long.toString(safe) + (fluid ? "mB" : "");
    }
}
