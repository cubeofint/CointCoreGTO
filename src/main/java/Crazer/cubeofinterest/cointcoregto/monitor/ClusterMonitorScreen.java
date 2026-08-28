package Crazer.cubeofinterest.cointcoregto.monitor;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class ClusterMonitorScreen extends AbstractContainerScreen<ClusterMonitorMenu> {
    private static final int PANEL_COLOR = 0xEE171A1F;
    private static final int INNER_COLOR = 0xFF252A31;
    private static final int ROW_COLOR = 0xFF1D2229;
    private static final int ROW_ALT_COLOR = 0xFF20262E;
    private static final int SELECTED_COLOR = 0xFF334452;
    private static final int DETAILS_COLOR = 0xFF191E24;
    private static final int BAR_BG = 0xFF0F1318;
    private static final int TEXT = 0xFFE7EDF4;
    private static final int MUTED = 0xFF9AA5B1;
    private static final int GREEN = 0xFF55FF55;
    private static final int RED = 0xFFFF5555;
    private static final int YELLOW = 0xFFFFCC55;
    private static final int CYAN = 0xFF55DDEB;

    private static final int LIST_X = 10;
    private static final int LIST_Y = 68;
    private static final int LIST_W = 420;
    private static final int ROW_H = 18;
    private static final int VISIBLE_NODE_ROWS = 4;
    private static final int VISIBLE_BUFFER_ROWS = 4;
    private static final int VISIBLE_OPERATION_ROWS = 7;
    private static final int OPERATION_DETAILS_Y = 202;
    private static final int OPERATION_BAR_W = 248;

    private static final int DETAILS_Y = 144;
    private static final int RESOURCE_HEADER_Y = 196;
    private static final int RESOURCE_START_Y = 209;
    private static final int RESOURCE_ROW_H = 18;
    private static final int ITEM_COL_X = 18;
    private static final int FLUID_COL_X = 226;
    private static final int RESOURCE_NAME_W = 168;
    private static final int RESOURCE_AMOUNT_W = 62;
    private static final int RESOURCE_BAR_W = 62;
    private static final int RESOURCE_TEXT_X = 30;
    private static final int RESOURCE_BAR_X = 96;
    private static final int RESOURCE_PERCENT_X = 164;
    private static final int RESOURCE_COLUMN_W = 198;

    private static final int NODE_BUFFER_HEADER_Y = DETAILS_Y + 66;
    private static final int NODE_BUFFER_COLUMNS_Y = DETAILS_Y + 79;
    private static final int NODE_BUFFER_START_Y = DETAILS_Y + 91;
    private static final int NODE_BUFFER_VISIBLE_ROWS = 7;

    private enum Tab {
        NODES,
        BUFFERS,
        OPERATIONS
    }

    private Tab tab = Tab.NODES;
    private ClusterMonitorSnapshot snapshot;
    private int nodeScroll;
    private int bufferScroll;
    private int operationScroll;
    private String selectedNodeId = "";
    private String selectedEndpointId = "";
    private String selectedOperationId = "";
    private int refreshTicks;
    private boolean requestPending;

    private Button nodesButton;
    private Button buffersButton;
    private Button operationsButton;

    public ClusterMonitorScreen(
            ClusterMonitorMenu menu,
            Inventory inventory,
            Component title
    ) {
        super(menu, inventory, title);
        this.imageWidth = 440;
        this.imageHeight = 384;
        this.inventoryLabelY = 10_000;
    }

    @Override
    protected void init() {
        super.init();

        nodesButton = addRenderableWidget(Button.builder(
                Component.literal("Ноды"),
                button -> switchTab(Tab.NODES)
        ).bounds(leftPos + 10, topPos + 34, 80, 20).build());

        buffersButton = addRenderableWidget(Button.builder(
                Component.literal("Supply Buffer"),
                button -> switchTab(Tab.BUFFERS)
        ).bounds(leftPos + 94, topPos + 34, 112, 20).build());

        operationsButton = addRenderableWidget(Button.builder(
                Component.literal("Операции"),
                button -> switchTab(Tab.OPERATIONS)
        ).bounds(leftPos + 210, topPos + 34, 96, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal("Обновить"),
                button -> requestSnapshot()
        ).bounds(leftPos + imageWidth - 92, topPos + 34, 82, 20).build());

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

        int maxOperationScroll = Math.max(0, sortedOperations().size() - VISIBLE_OPERATION_ROWS);
        operationScroll = Math.min(operationScroll, maxOperationScroll);

        List<ClusterMonitorSnapshot.NodeEntry> nodes = sortedNodes();
        if (selectedNodeId.isBlank()
                || nodes.stream().noneMatch(node -> selectedNodeId.equals(node.nodeId()))) {
            selectedNodeId = chooseDefaultNodeId(nodes);
        }

        if (!selectedEndpointId.isBlank()
                && sortedBuffers().stream().noneMatch(buffer -> selectedEndpointId.equals(buffer.endpointId()))) {
            selectedEndpointId = "";
        }

        List<ClusterMonitorSnapshot.OperationEntry> operations = sortedOperations();
        if (!selectedOperationId.isBlank()
                && operations.stream().noneMatch(operation -> selectedOperationId.equals(operation.operationId()))) {
            selectedOperationId = "";
        }
        if (tab == Tab.OPERATIONS && selectedOperationId.isBlank() && !operations.isEmpty()) {
            selectedOperationId = operations.get(0).operationId();
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
                new ClusterMonitorRequestPacket(menu.getBlockPos(), tab == Tab.OPERATIONS)
        );
    }

    private void switchTab(Tab newTab) {
        if (tab == newTab) {
            return;
        }
        tab = newTab;
        updateTabButtons();
        refreshTicks = 60;
        requestSnapshot();
    }

    private void updateTabButtons() {
        if (nodesButton != null) {
            nodesButton.active = tab != Tab.NODES;
        }
        if (buffersButton != null) {
            buffersButton.active = tab != Tab.BUFFERS;
        }
        if (operationsButton != null) {
            operationsButton.active = tab != Tab.OPERATIONS;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        renderResourceTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;

        graphics.fill(x, y, x + imageWidth, y + imageHeight, PANEL_COLOR);
        graphics.fill(x + 4, y + 4, x + imageWidth - 4, y + imageHeight - 4, INNER_COLOR);

        if (tab == Tab.OPERATIONS) {
            List<ClusterMonitorSnapshot.OperationEntry> operations = sortedOperations();
            for (int row = 0; row < VISIBLE_OPERATION_ROWS; row++) {
                int rowY = y + LIST_Y + row * ROW_H;
                int color = (row & 1) == 0 ? ROW_COLOR : ROW_ALT_COLOR;
                int index = operationScroll + row;
                if (index < operations.size()
                        && operations.get(index).operationId().equals(selectedOperationId)) {
                    color = SELECTED_COLOR;
                }
                graphics.fill(x + LIST_X, rowY, x + LIST_X + LIST_W, rowY + ROW_H - 1, color);
            }
            graphics.fill(
                    x + 10,
                    y + OPERATION_DETAILS_Y,
                    x + imageWidth - 10,
                    y + imageHeight - 10,
                    DETAILS_COLOR
            );
            return;
        }

        int visibleRows = tab == Tab.NODES ? VISIBLE_NODE_ROWS : VISIBLE_BUFFER_ROWS;
        List<ClusterMonitorSnapshot.NodeEntry> nodes = tab == Tab.NODES ? sortedNodes() : List.of();
        List<ClusterMonitorSnapshot.BufferEntry> buffers = tab == Tab.BUFFERS ? sortedBuffers() : List.of();

        for (int row = 0; row < visibleRows; row++) {
            int rowY = y + LIST_Y + row * ROW_H;
            int color = (row & 1) == 0 ? ROW_COLOR : ROW_ALT_COLOR;

            if (tab == Tab.NODES) {
                int index = nodeScroll + row;
                if (index < nodes.size()
                        && nodes.get(index).nodeId().equals(selectedNodeId)) {
                    color = SELECTED_COLOR;
                }
            } else {
                int index = bufferScroll + row;
                if (index < buffers.size()
                        && buffers.get(index).endpointId().equals(selectedEndpointId)) {
                    color = SELECTED_COLOR;
                }
            }

            graphics.fill(x + LIST_X, rowY, x + LIST_X + LIST_W, rowY + ROW_H - 1, color);
        }

        graphics.fill(
                x + 10,
                y + DETAILS_Y,
                x + imageWidth - 10,
                y + imageHeight - 10,
                DETAILS_COLOR
        );

        if (tab == Tab.NODES) {
            ClusterMonitorSnapshot.NodeEntry selected = selectedNode();
            if (selected != null) {
                List<ClusterMonitorSnapshot.BufferEntry> nodeBuffers = buffersForNode(selected.nodeId());
                for (int row = 0; row < Math.min(NODE_BUFFER_VISIBLE_ROWS, nodeBuffers.size()); row++) {
                    int rowY = y + NODE_BUFFER_START_Y + row * ROW_H;
                    int color = (row & 1) == 0 ? ROW_COLOR : ROW_ALT_COLOR;
                    graphics.fill(x + 16, rowY, x + imageWidth - 16, rowY + ROW_H - 1, color);
                }
            }
        } else {
            ClusterMonitorSnapshot.BufferEntry selected = selectedBuffer();
            if (selected != null && !selected.resources().isEmpty()) {
                renderResourceBars(graphics, selected.resources());
            }
        }
    }

    private void renderResourceBars(
            GuiGraphics graphics,
            List<ClusterMonitorSnapshot.ResourceEntry> resources
    ) {
        for (ClusterMonitorSnapshot.ResourceEntry resource : resources) {
            if (resource.displayName().isBlank()
                    || resource.filterIndex() < 0
                    || resource.filterIndex() >= 9) {
                continue;
            }

            int columnX = "FLUID".equalsIgnoreCase(resource.type()) ? FLUID_COL_X : ITEM_COL_X;
            int rowY = RESOURCE_START_Y + resource.filterIndex() * RESOURCE_ROW_H;
            int color = resourceColor(resource);
            int barX = columnX + RESOURCE_BAR_X;

            graphics.fill(
                    leftPos + barX,
                    topPos + rowY + 11,
                    leftPos + barX + RESOURCE_BAR_W,
                    topPos + rowY + 17,
                    BAR_BG
            );

            int fill = progressWidth(resource, RESOURCE_BAR_W - 2);
            if (fill > 0) {
                graphics.fill(
                        leftPos + barX + 1,
                        topPos + rowY + 12,
                        leftPos + barX + 1 + fill,
                        topPos + rowY + 16,
                        color
                );
            }

            int thresholdX = leftPos + barX + 1
                    + (RESOURCE_BAR_W - 2) * clampPercent(resource.refillBelowPercent()) / 100;
            graphics.fill(
                    thresholdX,
                    topPos + rowY + 11,
                    thresholdX + 1,
                    topPos + rowY + 17,
                    MUTED
            );
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, Component.literal("Кластерный монитор"), 10, 8, TEXT, false);

        if (snapshot == null) {
            graphics.drawString(
                    font,
                    Component.literal("Получение данных кластера..."),
                    158,
                    9,
                    YELLOW,
                    false
            );
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
        graphics.drawString(font, Component.literal(clusterText), 158, 9, clusterColor, false);

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
                158,
                21,
                snapshot.activeOperations() > 0 ? YELLOW : MUTED,
                false
        );

        if (!snapshot.error().isBlank()) {
            graphics.drawString(
                    font,
                    Component.literal(cropPixels(snapshot.error(), imageWidth - 24)),
                    10,
                    58,
                    RED,
                    false
            );
            return;
        }

        if (tab == Tab.NODES) {
            renderNodes(graphics);
        } else if (tab == Tab.BUFFERS) {
            renderBuffers(graphics);
        } else {
            renderOperations(graphics);
        }
    }

    private void renderOperations(GuiGraphics graphics) {
        List<ClusterMonitorSnapshot.OperationEntry> operations = sortedOperations();

        graphics.drawString(font, Component.literal("Статус"), 14, 58, MUTED, false);
        graphics.drawString(font, Component.literal("Ресурс"), 78, 58, MUTED, false);
        graphics.drawString(font, Component.literal("Откуда"), 222, 58, MUTED, false);
        graphics.drawString(font, Component.literal("Куда"), 278, 58, MUTED, false);
        graphics.drawString(font, Component.literal("Кол-во"), 330, 58, MUTED, false);
        graphics.drawString(font, Component.literal("Время"), 389, 58, MUTED, false);

        if (operations.isEmpty()) {
            graphics.drawString(
                    font,
                    Component.literal("Последних операций пока нет."),
                    18,
                    LIST_Y + 7,
                    MUTED,
                    false
            );
            graphics.drawString(
                    font,
                    Component.literal("История запрашивается только когда открыта эта вкладка."),
                    18,
                    OPERATION_DETAILS_Y + 12,
                    MUTED,
                    false
            );
            return;
        }

        for (int row = 0; row < VISIBLE_OPERATION_ROWS; row++) {
            int index = operationScroll + row;
            if (index >= operations.size()) {
                break;
            }

            ClusterMonitorSnapshot.OperationEntry operation = operations.get(index);
            int y = LIST_Y + row * ROW_H + 5;
            int statusColor = operationStatusColor(operation.status());

            graphics.drawString(font, Component.literal("●"), 14, y, statusColor, false);
            String operationState = operationStatusLabel(operation.status())
                    + (operation.priority() > 0 ? " P:" + operation.priority() : "");
            graphics.drawString(
                    font,
                    Component.literal(cropPixels(operationState, 48)),
                    26,
                    y,
                    statusColor,
                    false
            );

            renderOperationIcon(graphics, operation, 62, y - 4);
            String resourceName = operation.displayName().isBlank()
                    ? (operation.resourceKey().isBlank() ? operation.resourceType() : operation.resourceKey())
                    : operation.displayName();
            graphics.drawString(
                    font,
                    Component.literal(cropPixels(resourceName, 136)),
                    80,
                    y,
                    TEXT,
                    false
            );

            graphics.drawString(
                    font,
                    Component.literal(cropPixels(operationFromNode(operation), 50)),
                    222,
                    y,
                    MUTED,
                    false
            );
            graphics.drawString(
                    font,
                    Component.literal(cropPixels(operationToNode(operation), 46)),
                    278,
                    y,
                    MUTED,
                    false
            );
            graphics.drawString(
                    font,
                    Component.literal(cropPixels(formatAmount(
                            operation.requestedAmount(),
                            "FLUID".equalsIgnoreCase(operation.resourceType())
                    ), 52)),
                    330,
                    y,
                    TEXT,
                    false
            );
            graphics.drawString(
                    font,
                    Component.literal(formatAge(operation.updatedAgeSeconds())),
                    389,
                    y,
                    MUTED,
                    false
            );
        }

        renderSelectedOperation(graphics, operations);
    }

    private void renderSelectedOperation(
            GuiGraphics graphics,
            List<ClusterMonitorSnapshot.OperationEntry> operations
    ) {
        ClusterMonitorSnapshot.OperationEntry operation = selectedOperation(operations);
        if (operation == null) {
            graphics.drawString(
                    font,
                    Component.literal("Выбери операцию для подробностей"),
                    18,
                    OPERATION_DETAILS_Y + 12,
                    MUTED,
                    false
            );
            return;
        }

        int statusColor = operationStatusColor(operation.status());
        String shortId = operation.operationId().length() > 8
                ? operation.operationId().substring(0, 8)
                : operation.operationId();
        String header = "Operation " + shortId + "  •  " + operationStatusLabel(operation.status())
                + "  •  P:" + operation.priority();
        graphics.drawString(font, Component.literal(header), 18, OPERATION_DETAILS_Y + 8, statusColor, false);

        renderOperationIcon(graphics, operation, 18, OPERATION_DETAILS_Y + 23);
        String name = operation.displayName().isBlank()
                ? (operation.resourceKey().isBlank() ? operation.resourceType() : operation.resourceKey())
                : operation.displayName();
        graphics.drawString(
                font,
                Component.literal(cropPixels(name, imageWidth - 74)),
                40,
                OPERATION_DETAILS_Y + 25,
                TEXT,
                false
        );
        if (!operation.resourceKey().isBlank()) {
            graphics.drawString(
                    font,
                    Component.literal(cropPixels(operation.resourceKey(), imageWidth - 74)),
                    40,
                    OPERATION_DETAILS_Y + 36,
                    MUTED,
                    false
            );
        }

        String route = operationFromNode(operation) + "  →  " + operationToNode(operation)
                + "  •  " + directionLabel(operation.direction());
        graphics.drawString(
                font,
                Component.literal(cropPixels(route, imageWidth - 36)),
                18,
                OPERATION_DETAILS_Y + 54,
                CYAN,
                false
        );

        boolean fluid = "FLUID".equalsIgnoreCase(operation.resourceType());
        long remaining = Math.max(0L, operation.requestedAmount() - operation.deliveredAmount());
        String amounts = "Запрошено " + formatTooltipAmount(operation.requestedAmount(), fluid)
                + "  •  Доставлено " + formatTooltipAmount(operation.deliveredAmount(), fluid)
                + "  •  Осталось " + formatTooltipAmount(remaining, fluid);
        graphics.drawString(
                font,
                Component.literal(cropPixels(amounts, imageWidth - 36)),
                18,
                OPERATION_DETAILS_Y + 68,
                MUTED,
                false
        );

        int barX = 18;
        int barY = OPERATION_DETAILS_Y + 82;
        graphics.fill(barX, barY, barX + OPERATION_BAR_W, barY + 7, BAR_BG);
        int fill = operationProgressWidth(operation, OPERATION_BAR_W - 2);
        if (fill > 0) {
            graphics.fill(barX + 1, barY + 1, barX + 1 + fill, barY + 6, statusColor);
        }
        long percent = operation.requestedAmount() <= 0L
                ? 0L
                : Math.min(100L, operation.deliveredAmount() * 100L / operation.requestedAmount());
        graphics.drawString(
                font,
                Component.literal(percent + "%"),
                barX + OPERATION_BAR_W + 8,
                barY,
                statusColor,
                false
        );

        String timing = "Создано " + formatAge(operation.createdAgeSeconds())
                + "  •  Обновлено " + formatAge(operation.updatedAgeSeconds())
                + "  •  link " + (operation.linkId().isBlank() ? "?" : operation.linkId());
        graphics.drawString(
                font,
                Component.literal(cropPixels(timing, imageWidth - 36)),
                18,
                OPERATION_DETAILS_Y + 98,
                MUTED,
                false
        );

        if (!operation.errorText().isBlank()) {
            graphics.drawString(
                    font,
                    Component.literal("Ошибка: " + cropPixels(operation.errorText(), imageWidth - 82)),
                    18,
                    OPERATION_DETAILS_Y + 113,
                    RED,
                    false
            );
        }
    }

    private void renderNodes(GuiGraphics graphics) {
        List<ClusterMonitorSnapshot.NodeEntry> nodes = sortedNodes();

        graphics.drawString(font, Component.literal("Статус"), 14, 58, MUTED, false);
        graphics.drawString(font, Component.literal("Нода"), 58, 58, MUTED, false);
        graphics.drawString(font, Component.literal("Роль"), 216, 58, MUTED, false);
        graphics.drawString(font, Component.literal("Игроки"), 314, 58, MUTED, false);
        graphics.drawString(font, Component.literal("Миры"), 378, 58, MUTED, false);

        for (int row = 0; row < VISIBLE_NODE_ROWS; row++) {
            int index = nodeScroll + row;
            if (index >= nodes.size()) {
                break;
            }

            ClusterMonitorSnapshot.NodeEntry node = nodes.get(index);
            int y = LIST_Y + row * ROW_H + 5;

            graphics.drawString(
                    font,
                    Component.literal(node.online() ? "●" : "○"),
                    16,
                    y,
                    node.online() ? GREEN : RED,
                    false
            );
            graphics.drawString(
                    font,
                    Component.literal(cropPixels(node.nodeId(), 145)),
                    58,
                    y,
                    node.nodeId().equals(snapshot.currentNodeId()) ? CYAN : TEXT,
                    false
            );
            graphics.drawString(
                    font,
                    Component.literal(cropPixels(node.role(), 82)),
                    216,
                    y,
                    MUTED,
                    false
            );
            graphics.drawString(
                    font,
                    Component.literal(Integer.toString(node.playerCount())),
                    331,
                    y,
                    TEXT,
                    false
            );
            graphics.drawString(
                    font,
                    Component.literal(Integer.toString(node.dimensionCount())),
                    395,
                    y,
                    TEXT,
                    false
            );
        }

        renderSelectedNode(graphics, nodes);
    }

    private void renderSelectedNode(
            GuiGraphics graphics,
            List<ClusterMonitorSnapshot.NodeEntry> nodes
    ) {
        ClusterMonitorSnapshot.NodeEntry selected = selectedNode(nodes);
        if (selected == null) {
            graphics.drawString(
                    font,
                    Component.literal("Выбери ноду для подробностей"),
                    18,
                    DETAILS_Y + 11,
                    MUTED,
                    false
            );
            return;
        }

        boolean local = selected.nodeId().equals(snapshot.currentNodeId());
        String header = selected.nodeId()
                + "  •  " + (selected.role().isBlank() ? "unknown" : selected.role());
        graphics.drawString(
                font,
                Component.literal(cropPixels(header, imageWidth - 36)),
                18,
                DETAILS_Y + 7,
                local ? CYAN : TEXT,
                false
        );

        String state = (selected.online() ? "ONLINE" : "OFFLINE")
                + "  •  heartbeat " + selected.heartbeatAgeSeconds() + "s"
                + "  •  " + (local ? "LOCAL" : "REMOTE");
        graphics.drawString(
                font,
                Component.literal(cropPixels(state, imageWidth - 36)),
                18,
                DETAILS_Y + 20,
                selected.online() ? GREEN : RED,
                false
        );

        List<ClusterMonitorSnapshot.BufferEntry> localBuffers = buffersForNode(selected.nodeId());
        int onlineBuffers = 0;
        for (ClusterMonitorSnapshot.BufferEntry buffer : localBuffers) {
            if (buffer.endpointOnline()) {
                onlineBuffers++;
            }
        }

        String stats = "Игроки " + selected.playerCount()
                + "  •  Миры " + selected.dimensionCount()
                + "  •  Buffer " + onlineBuffers + "/" + localBuffers.size()
                + "  •  Операции " + activeOperationsForNode(selected.nodeId());
        graphics.drawString(
                font,
                Component.literal(cropPixels(stats, imageWidth - 36)),
                18,
                DETAILS_Y + 33,
                MUTED,
                false
        );

        List<String> linkedNodes = linkedNodeIds(selected.nodeId());
        int onlineLinks = 0;
        for (String nodeId : linkedNodes) {
            ClusterMonitorSnapshot.NodeEntry linked = findNode(nodeId);
            if (linked != null && linked.online()) {
                onlineLinks++;
            }
        }

        String linkState;
        int linkColor;
        if (!selected.online()) {
            linkState = "Связь: NODE OFFLINE";
            linkColor = RED;
        } else if (linkedNodes.isEmpty()) {
            linkState = "Связь: OK  •  связанных нод нет";
            linkColor = GREEN;
        } else if (onlineLinks == linkedNodes.size()) {
            linkState = "Связь: OK " + onlineLinks + "/" + linkedNodes.size()
                    + "  •  " + String.join(", ", linkedNodes);
            linkColor = GREEN;
        } else if (onlineLinks == 0) {
            linkState = "Связь: OFFLINE 0/" + linkedNodes.size()
                    + "  •  " + String.join(", ", linkedNodes);
            linkColor = RED;
        } else {
            linkState = "Связь: DEGRADED " + onlineLinks + "/" + linkedNodes.size()
                    + "  •  " + String.join(", ", linkedNodes);
            linkColor = YELLOW;
        }
        graphics.drawString(
                font,
                Component.literal(cropPixels(linkState, imageWidth - 36)),
                18,
                DETAILS_Y + 46,
                linkColor,
                false
        );

        graphics.drawString(
                font,
                Component.literal("Supply Buffer на ноде"),
                18,
                NODE_BUFFER_HEADER_Y,
                TEXT,
                false
        );
        graphics.drawString(font, Component.literal("Роль"), 32, NODE_BUFFER_COLUMNS_Y, MUTED, false);
        graphics.drawString(font, Component.literal("Владелец"), 92, NODE_BUFFER_COLUMNS_Y, MUTED, false);
        graphics.drawString(font, Component.literal("Связь"), 214, NODE_BUFFER_COLUMNS_Y, MUTED, false);
        graphics.drawString(font, Component.literal("Состояние"), 330, NODE_BUFFER_COLUMNS_Y, MUTED, false);

        if (localBuffers.isEmpty()) {
            graphics.drawString(
                    font,
                    Component.literal("На этой ноде Supply Buffer не найден."),
                    18,
                    NODE_BUFFER_START_Y + 5,
                    MUTED,
                    false
            );
            return;
        }

        int visible = Math.min(NODE_BUFFER_VISIBLE_ROWS, localBuffers.size());
        for (int row = 0; row < visible; row++) {
            ClusterMonitorSnapshot.BufferEntry buffer = localBuffers.get(row);
            BufferHealth health = bufferHealth(buffer);
            int y = NODE_BUFFER_START_Y + row * ROW_H + 5;

            graphics.drawString(
                    font,
                    Component.literal(buffer.endpointOnline() ? "●" : "○"),
                    18,
                    y,
                    health.color(),
                    false
            );
            graphics.drawString(
                    font,
                    Component.literal(shortRole(buffer.role())),
                    32,
                    y,
                    "PROVIDER".equalsIgnoreCase(buffer.role()) ? CYAN : TEXT,
                    false
            );
            graphics.drawString(
                    font,
                    Component.literal(cropPixels(buffer.ownerName().isBlank() ? "-" : buffer.ownerName(), 108)),
                    92,
                    y,
                    MUTED,
                    false
            );

            String link = nodeBufferLinkText(buffer);
            int linkBufferColor = nodeBufferLinkColor(buffer);
            graphics.drawString(
                    font,
                    Component.literal(cropPixels(link, 106)),
                    214,
                    y,
                    linkBufferColor,
                    false
            );
            String nodeHealthText = "REMOTE".equalsIgnoreCase(buffer.role())
                    ? "P:" + buffer.priority() + " • " + health.text()
                    : health.text();
            graphics.drawString(
                    font,
                    Component.literal(cropPixels(nodeHealthText, 92)),
                    330,
                    y,
                    health.color(),
                    false
            );
        }

        if (localBuffers.size() > NODE_BUFFER_VISIBLE_ROWS) {
            graphics.drawString(
                    font,
                    Component.literal("+ ещё " + (localBuffers.size() - NODE_BUFFER_VISIBLE_ROWS)),
                    18,
                    imageHeight - 17,
                    MUTED,
                    false
            );
        }
    }

    private void renderBuffers(GuiGraphics graphics) {
        List<ClusterMonitorSnapshot.BufferEntry> buffers = sortedBuffers();

        graphics.drawString(font, Component.literal("Статус"), 14, 58, MUTED, false);
        graphics.drawString(font, Component.literal("Роль"), 52, 58, MUTED, false);
        graphics.drawString(font, Component.literal("Нода"), 104, 58, MUTED, false);
        graphics.drawString(font, Component.literal("Владелец"), 194, 58, MUTED, false);
        graphics.drawString(font, Component.literal("Состояние"), 322, 58, MUTED, false);

        for (int row = 0; row < VISIBLE_BUFFER_ROWS; row++) {
            int index = bufferScroll + row;
            if (index >= buffers.size()) {
                break;
            }

            ClusterMonitorSnapshot.BufferEntry buffer = buffers.get(index);
            int y = LIST_Y + row * ROW_H + 5;
            BufferHealth health = bufferHealth(buffer);

            graphics.drawString(
                    font,
                    Component.literal(buffer.endpointOnline() ? "●" : "○"),
                    16,
                    y,
                    health.color(),
                    false
            );
            graphics.drawString(
                    font,
                    Component.literal(shortRole(buffer.role())),
                    52,
                    y,
                    "PROVIDER".equalsIgnoreCase(buffer.role()) ? CYAN : TEXT,
                    false
            );
            graphics.drawString(
                    font,
                    Component.literal(cropPixels(buffer.nodeId(), 78)),
                    104,
                    y,
                    TEXT,
                    false
            );
            graphics.drawString(
                    font,
                    Component.literal(cropPixels(buffer.ownerName().isBlank() ? "-" : buffer.ownerName(), 112)),
                    194,
                    y,
                    MUTED,
                    false
            );
            String healthText = "REMOTE".equalsIgnoreCase(buffer.role())
                    ? "P:" + buffer.priority() + " • " + health.text()
                    : health.text();
            graphics.drawString(
                    font,
                    Component.literal(cropPixels(healthText, 100)),
                    322,
                    y,
                    health.color(),
                    false
            );
        }

        renderSelectedBuffer(graphics, buffers);
    }

    private void renderSelectedBuffer(
            GuiGraphics graphics,
            List<ClusterMonitorSnapshot.BufferEntry> buffers
    ) {
        ClusterMonitorSnapshot.BufferEntry selected = selectedBuffer(buffers);
        if (selected == null) {
            graphics.drawString(
                    font,
                    Component.literal("Выбери Supply Buffer для подробностей"),
                    18,
                    DETAILS_Y + 11,
                    MUTED,
                    false
            );
            return;
        }

        String owner = selected.ownerName().isBlank() ? "-" : selected.ownerName();
        String header = shortRole(selected.role())
                + "  •  " + selected.nodeId()
                + "  •  " + owner
                + ("REMOTE".equalsIgnoreCase(selected.role()) ? "  •  P:" + selected.priority() : "");
        graphics.drawString(
                font,
                Component.literal(cropPixels(header, imageWidth - 36)),
                18,
                DETAILS_Y + 7,
                CYAN,
                false
        );

        String location = selected.dimensionId() + " @ " + selected.blockPosition();
        graphics.drawString(
                font,
                Component.literal(cropPixels(location, imageWidth - 36)),
                18,
                DETAILS_Y + 20,
                MUTED,
                false
        );

        BufferHealth health = bufferHealth(selected);
        String state = detailedState(selected, health);
        graphics.drawString(
                font,
                Component.literal(cropPixels(state, imageWidth - 36)),
                18,
                DETAILS_Y + 33,
                health.color(),
                false
        );

        if (selected.resources().isEmpty()) {
            String emptyText = "PROVIDER".equalsIgnoreCase(selected.role())
                    ? "MAIN Buffer использует главную ME-сеть; локальных 9+9 фильтров у него нет."
                    : "На этом Remote Buffer пока нет настроенных фильтров.";
            graphics.drawString(
                    font,
                    Component.literal(cropPixels(emptyText, imageWidth - 36)),
                    18,
                    DETAILS_Y + 56,
                    MUTED,
                    false
            );
            return;
        }

        graphics.drawString(font, Component.literal("Предметы"), ITEM_COL_X, RESOURCE_HEADER_Y, TEXT, false);
        graphics.drawString(font, Component.literal("Жидкости"), FLUID_COL_X, RESOURCE_HEADER_Y, TEXT, false);

        renderResourceColumn(graphics, selected.resources(), "ITEM", ITEM_COL_X);
        renderResourceColumn(graphics, selected.resources(), "FLUID", FLUID_COL_X);

    }

    private void renderResourceColumn(
            GuiGraphics graphics,
            List<ClusterMonitorSnapshot.ResourceEntry> resources,
            String type,
            int x
    ) {
        boolean fluid = "FLUID".equalsIgnoreCase(type);

        for (int filterIndex = 0; filterIndex < 9; filterIndex++) {
            int y = RESOURCE_START_Y + filterIndex * RESOURCE_ROW_H;
            ClusterMonitorSnapshot.ResourceEntry resource =
                    findResource(resources, type, filterIndex);

            graphics.drawString(
                    font,
                    Component.literal(Integer.toString(filterIndex + 1)),
                    x,
                    y + 4,
                    resource == null ? 0xFF68717C : MUTED,
                    false
            );

            if (resource == null || resource.displayName().isBlank()) {
                graphics.drawString(
                        font,
                        Component.literal("-"),
                        x + RESOURCE_TEXT_X,
                        y + 4,
                        0xFF46505A,
                        false
                );
                continue;
            }

            renderResourceIcon(graphics, resource, x + 10, y + 1);

            int color = resourceColor(resource);
            long percent = resourcePercent(resource);

            graphics.drawString(
                    font,
                    Component.literal(cropPixels(resource.displayName(), RESOURCE_NAME_W)),
                    x + RESOURCE_TEXT_X,
                    y,
                    TEXT,
                    false
            );

            String amounts = formatAmount(resource.amount(), fluid)
                    + " / " + formatAmount(targetAmount(resource), fluid);
            graphics.drawString(
                    font,
                    Component.literal(cropPixels(amounts, RESOURCE_AMOUNT_W)),
                    x + RESOURCE_TEXT_X,
                    y + 9,
                    MUTED,
                    false
            );

            graphics.drawString(
                    font,
                    Component.literal(percent + "%"),
                    x + RESOURCE_PERCENT_X,
                    y + 9,
                    color,
                    false
            );
        }
    }

    private void renderResourceIcon(
            GuiGraphics graphics,
            ClusterMonitorSnapshot.ResourceEntry resource,
            int x,
            int y
    ) {
        if ("ITEM".equalsIgnoreCase(resource.type())) {
            ItemStack stack = itemStackFor(resource);
            if (!stack.isEmpty()) {
                graphics.renderItem(stack, x, y);
            }
            return;
        }

        if (!"FLUID".equalsIgnoreCase(resource.type())) {
            return;
        }

        FluidStack stack = fluidStackFor(resource);
        if (stack.isEmpty()) {
            return;
        }

        try {
            IClientFluidTypeExtensions extension = IClientFluidTypeExtensions.of(stack.getFluid());
            ResourceLocation texture = extension.getStillTexture(stack);
            if (texture == null) {
                return;
            }

            TextureAtlasSprite sprite = minecraft.getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(texture);
            int tint = extension.getTintColor(stack);
            float alpha = ((tint >>> 24) & 0xFF) / 255.0F;
            if (alpha <= 0.0F) {
                alpha = 1.0F;
            }
            float red = ((tint >>> 16) & 0xFF) / 255.0F;
            float green = ((tint >>> 8) & 0xFF) / 255.0F;
            float blue = (tint & 0xFF) / 255.0F;
            graphics.blit(x, y, 100, 16, 16, sprite, red, green, blue, alpha);
        } catch (RuntimeException ignored) {
        }
    }

    private void renderOperationIcon(
            GuiGraphics graphics,
            ClusterMonitorSnapshot.OperationEntry operation,
            int x,
            int y
    ) {
        if ("ITEM".equalsIgnoreCase(operation.resourceType())) {
            ItemStack stack = itemStackFor(operation.resourceKey());
            if (!stack.isEmpty()) {
                graphics.renderItem(stack, x, y);
            }
            return;
        }

        if (!"FLUID".equalsIgnoreCase(operation.resourceType())) {
            return;
        }

        FluidStack stack = fluidStackFor(operation.resourceKey());
        if (stack.isEmpty()) {
            return;
        }

        try {
            IClientFluidTypeExtensions extension = IClientFluidTypeExtensions.of(stack.getFluid());
            ResourceLocation texture = extension.getStillTexture(stack);
            if (texture == null) {
                return;
            }
            TextureAtlasSprite sprite = minecraft.getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(texture);
            int tint = extension.getTintColor(stack);
            float alpha = ((tint >>> 24) & 0xFF) / 255.0F;
            if (alpha <= 0.0F) {
                alpha = 1.0F;
            }
            float red = ((tint >>> 16) & 0xFF) / 255.0F;
            float green = ((tint >>> 8) & 0xFF) / 255.0F;
            float blue = (tint & 0xFF) / 255.0F;
            graphics.blit(x, y, 100, 16, 16, sprite, red, green, blue, alpha);
        } catch (RuntimeException ignored) {
        }
    }

    private ItemStack itemStackFor(ClusterMonitorSnapshot.ResourceEntry resource) {
        return itemStackFor(resource.resourceKey());
    }

    private ItemStack itemStackFor(String resourceKey) {
        ResourceLocation key = parseResourceKey(resourceKey);
        if (key == null) {
            return ItemStack.EMPTY;
        }

        var item = ForgeRegistries.ITEMS.getValue(key);
        if (item == null || item == Items.AIR) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(item);
    }

    private FluidStack fluidStackFor(ClusterMonitorSnapshot.ResourceEntry resource) {
        return fluidStackFor(resource.resourceKey());
    }

    private FluidStack fluidStackFor(String resourceKey) {
        ResourceLocation key = parseResourceKey(resourceKey);
        if (key == null) {
            return FluidStack.EMPTY;
        }

        Fluid fluid = ForgeRegistries.FLUIDS.getValue(key);
        if (fluid == null || fluid == Fluids.EMPTY) {
            return FluidStack.EMPTY;
        }
        return new FluidStack(fluid, 1000);
    }

    private ResourceLocation parseResourceKey(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return ResourceLocation.tryParse(value);
    }

    private void renderResourceTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (tab != Tab.BUFFERS || snapshot == null || !snapshot.error().isBlank()) {
            return;
        }

        ClusterMonitorSnapshot.BufferEntry selected = selectedBuffer();
        if (selected == null || selected.resources().isEmpty()) {
            return;
        }

        double localX = mouseX - leftPos;
        double localY = mouseY - topPos;
        if (localY < RESOURCE_START_Y || localY >= RESOURCE_START_Y + 9 * RESOURCE_ROW_H) {
            return;
        }

        String type;
        int columnX;
        if (localX >= ITEM_COL_X && localX < ITEM_COL_X + RESOURCE_COLUMN_W) {
            type = "ITEM";
            columnX = ITEM_COL_X;
        } else if (localX >= FLUID_COL_X && localX < FLUID_COL_X + RESOURCE_COLUMN_W) {
            type = "FLUID";
            columnX = FLUID_COL_X;
        } else {
            return;
        }

        int filterIndex = (int) ((localY - RESOURCE_START_Y) / RESOURCE_ROW_H);
        ClusterMonitorSnapshot.ResourceEntry resource = findResource(selected.resources(), type, filterIndex);
        if (resource == null || resource.displayName().isBlank()) {
            return;
        }

        boolean fluid = "FLUID".equalsIgnoreCase(type);
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(resource.displayName()).withStyle(ChatFormatting.WHITE));
        if (!resource.resourceKey().isBlank()) {
            lines.add(Component.literal(resource.resourceKey()).withStyle(ChatFormatting.DARK_GRAY));
        }
        lines.add(Component.literal("Сейчас: " + formatTooltipAmount(resource.amount(), fluid))
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Цель: " + formatTooltipAmount(targetAmount(resource), fluid)
                + " / Ёмкость: " + formatTooltipAmount(resource.capacity(), fluid))
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Порог запроса: " + clampPercent(resource.refillBelowPercent()) + "%")
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Целевое заполнение: " + clampPercent(resource.refillToPercent()) + "%")
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Статус: " + resourceStatusText(resource))
                .withStyle(resourceStatusFormatting(resource)));

        graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
    }

    private String resourceStatusText(ClusterMonitorSnapshot.ResourceEntry resource) {
        long percent = resourcePercent(resource);
        if (percent < resource.refillBelowPercent()) {
            return "LOW";
        }
        if (percent < resource.refillToPercent()) {
            return "FILL";
        }
        return "OK";
    }

    private ChatFormatting resourceStatusFormatting(ClusterMonitorSnapshot.ResourceEntry resource) {
        long percent = resourcePercent(resource);
        if (percent < resource.refillBelowPercent()) {
            return ChatFormatting.RED;
        }
        if (percent < resource.refillToPercent()) {
            return ChatFormatting.YELLOW;
        }
        return ChatFormatting.GREEN;
    }

    private static String formatTooltipAmount(long amount, boolean fluid) {
        long safe = Math.max(0L, amount);
        return String.format(Locale.ROOT, "%,d%s", safe, fluid ? " mB" : "");
    }

    private ClusterMonitorSnapshot.ResourceEntry findResource(
            List<ClusterMonitorSnapshot.ResourceEntry> resources,
            String type,
            int filterIndex
    ) {
        for (ClusterMonitorSnapshot.ResourceEntry resource : resources) {
            if (type.equalsIgnoreCase(resource.type())
                    && resource.filterIndex() == filterIndex) {
                return resource;
            }
        }
        return null;
    }

    private ClusterMonitorSnapshot.NodeEntry selectedNode() {
        return selectedNode(sortedNodes());
    }

    private ClusterMonitorSnapshot.NodeEntry selectedNode(
            List<ClusterMonitorSnapshot.NodeEntry> nodes
    ) {
        if (selectedNodeId.isBlank()) {
            return null;
        }
        for (ClusterMonitorSnapshot.NodeEntry node : nodes) {
            if (selectedNodeId.equals(node.nodeId())) {
                return node;
            }
        }
        return null;
    }

    private String chooseDefaultNodeId(List<ClusterMonitorSnapshot.NodeEntry> nodes) {
        if (snapshot != null && !snapshot.currentNodeId().isBlank()) {
            for (ClusterMonitorSnapshot.NodeEntry node : nodes) {
                if (snapshot.currentNodeId().equals(node.nodeId())) {
                    return node.nodeId();
                }
            }
        }
        return nodes.isEmpty() ? "" : nodes.get(0).nodeId();
    }

    private ClusterMonitorSnapshot.NodeEntry findNode(String nodeId) {
        if (snapshot == null || nodeId == null || nodeId.isBlank()) {
            return null;
        }
        for (ClusterMonitorSnapshot.NodeEntry node : snapshot.nodes()) {
            if (nodeId.equals(node.nodeId())) {
                return node;
            }
        }
        return null;
    }

    private List<ClusterMonitorSnapshot.BufferEntry> buffersForNode(String nodeId) {
        if (snapshot == null || nodeId == null || nodeId.isBlank()) {
            return List.of();
        }

        List<ClusterMonitorSnapshot.BufferEntry> result = new ArrayList<>();
        for (ClusterMonitorSnapshot.BufferEntry buffer : snapshot.buffers()) {
            if (nodeId.equals(buffer.nodeId())) {
                result.add(buffer);
            }
        }
        result.sort(Comparator
                .comparing(ClusterMonitorSnapshot.BufferEntry::endpointOnline).reversed()
                .thenComparing(ClusterMonitorSnapshot.BufferEntry::role)
                .thenComparing(ClusterMonitorSnapshot.BufferEntry::ownerName)
                .thenComparing(ClusterMonitorSnapshot.BufferEntry::endpointId));
        return List.copyOf(result);
    }

    private List<String> linkedNodeIds(String nodeId) {
        if (snapshot == null || nodeId == null || nodeId.isBlank()) {
            return List.of();
        }

        List<String> result = new ArrayList<>();
        for (ClusterMonitorSnapshot.BufferEntry buffer : snapshot.buffers()) {
            if (nodeId.equals(buffer.nodeId())) {
                String providerNode = buffer.providerNode();
                if (!providerNode.isBlank()
                        && !providerNode.equals(nodeId)
                        && !result.contains(providerNode)) {
                    result.add(providerNode);
                }
            }

            if (nodeId.equals(buffer.providerNode())) {
                String remoteNode = buffer.nodeId();
                if (!remoteNode.isBlank()
                        && !remoteNode.equals(nodeId)
                        && !result.contains(remoteNode)) {
                    result.add(remoteNode);
                }
            }
        }
        result.sort(String::compareToIgnoreCase);
        return List.copyOf(result);
    }

    private int activeOperationsForNode(String nodeId) {
        if (snapshot == null || nodeId == null || nodeId.isBlank()) {
            return 0;
        }

        int total = 0;
        for (ClusterMonitorSnapshot.BufferEntry buffer : snapshot.buffers()) {
            if (!"REMOTE".equalsIgnoreCase(buffer.role())) {
                continue;
            }
            if (nodeId.equals(buffer.nodeId()) || nodeId.equals(buffer.providerNode())) {
                total += Math.max(0, buffer.pendingCount());
            }
        }
        return total;
    }

    private String nodeBufferLinkText(ClusterMonitorSnapshot.BufferEntry buffer) {
        if ("PROVIDER".equalsIgnoreCase(buffer.role())) {
            return buffer.aeOnline() ? "ME online" : "ME offline";
        }
        if (!buffer.providerNode().isBlank()) {
            return "→ " + buffer.providerNode();
        }
        return buffer.linkOnline() ? "link online" : "link offline";
    }

    private int nodeBufferLinkColor(ClusterMonitorSnapshot.BufferEntry buffer) {
        if ("PROVIDER".equalsIgnoreCase(buffer.role())) {
            return buffer.aeOnline() ? GREEN : YELLOW;
        }
        return buffer.linkOnline() ? GREEN : YELLOW;
    }

    private ClusterMonitorSnapshot.BufferEntry selectedBuffer() {
        return selectedBuffer(sortedBuffers());
    }

    private ClusterMonitorSnapshot.BufferEntry selectedBuffer(
            List<ClusterMonitorSnapshot.BufferEntry> buffers
    ) {
        if (selectedEndpointId.isBlank()) {
            return null;
        }
        for (ClusterMonitorSnapshot.BufferEntry buffer : buffers) {
            if (selectedEndpointId.equals(buffer.endpointId())) {
                return buffer;
            }
        }
        return null;
    }

    private BufferHealth bufferHealth(ClusterMonitorSnapshot.BufferEntry buffer) {
        if (!buffer.endpointOnline()) {
            return new BufferHealth("OFFLINE", RED, 0, 0);
        }

        if ("PROVIDER".equalsIgnoreCase(buffer.role()) && !buffer.aeOnline()) {
            return new BufferHealth("ME OFFLINE", YELLOW, 0, 0);
        }

        if (!buffer.linkOnline()) {
            return new BufferHealth("LINK OFFLINE", YELLOW, 0, 0);
        }

        int low = 0;
        int belowTarget = 0;
        for (ClusterMonitorSnapshot.ResourceEntry resource : buffer.resources()) {
            if (resource.displayName().isBlank() || resource.capacity() <= 0L) {
                continue;
            }

            long percent = resourcePercent(resource);
            if (percent < resource.refillBelowPercent()) {
                low++;
            } else if (percent < resource.refillToPercent()) {
                belowTarget++;
            }
        }

        if (low > 0) {
            String text = "LOW " + low;
            if (buffer.pendingCount() > 0) {
                text += " • Q" + buffer.pendingCount();
            }
            return new BufferHealth(text, RED, low, belowTarget);
        }

        if (buffer.pendingCount() > 0) {
            return new BufferHealth("REQUEST " + buffer.pendingCount(), YELLOW, 0, belowTarget);
        }

        if (belowTarget > 0) {
            return new BufferHealth(belowTarget + " FILL", YELLOW, 0, belowTarget);
        }

        return new BufferHealth("OK", GREEN, 0, 0);
    }

    private String detailedState(
            ClusterMonitorSnapshot.BufferEntry buffer,
            BufferHealth health
    ) {
        if (!buffer.endpointOnline()) {
            return "OFFLINE  •  heartbeat " + buffer.heartbeatAgeSeconds() + "s";
        }

        if ("PROVIDER".equalsIgnoreCase(buffer.role()) && !buffer.aeOnline()) {
            return "MAIN online  •  ME OFFLINE  •  heartbeat " + buffer.heartbeatAgeSeconds() + "s";
        }

        if (!buffer.linkOnline()) {
            return "LINK OFFLINE  •  heartbeat " + buffer.heartbeatAgeSeconds() + "s";
        }

        String state = health.text()
                + "  •  Pending " + buffer.pendingCount()
                + "  •  heartbeat " + buffer.heartbeatAgeSeconds() + "s";

        String providerNode = buffer.providerNode();
        if (!providerNode.isBlank() && !providerNode.equals(buffer.nodeId())) {
            state += "  •  Provider " + providerNode;
        }
        return state;
    }

    private long targetAmount(ClusterMonitorSnapshot.ResourceEntry resource) {
        if (resource.capacity() <= 0L) {
            return 0L;
        }
        double target = (double) resource.capacity()
                * (double) clampPercent(resource.refillToPercent()) / 100.0D;
        return Math.max(0L, (long) Math.floor(target));
    }

    private int resourceColor(ClusterMonitorSnapshot.ResourceEntry resource) {
        long percent = resourcePercent(resource);
        if (percent < resource.refillBelowPercent()) {
            return RED;
        }
        if (percent < resource.refillToPercent()) {
            return YELLOW;
        }
        return GREEN;
    }

    private long resourcePercent(ClusterMonitorSnapshot.ResourceEntry resource) {
        if (resource.capacity() <= 0L) {
            return 0L;
        }
        double percent = (double) Math.max(0L, resource.amount())
                * 100.0D / (double) resource.capacity();
        return Math.max(0L, Math.min(100L, (long) Math.floor(percent)));
    }

    private int progressWidth(
            ClusterMonitorSnapshot.ResourceEntry resource,
            int maxWidth
    ) {
        if (maxWidth <= 0 || resource.capacity() <= 0L) {
            return 0;
        }
        double ratio = (double) Math.max(0L, resource.amount())
                / (double) resource.capacity();
        ratio = Math.max(0.0D, Math.min(1.0D, ratio));
        return (int) Math.round(maxWidth * ratio);
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

    private List<ClusterMonitorSnapshot.OperationEntry> sortedOperations() {
        if (snapshot == null) {
            return List.of();
        }
        return snapshot.operations();
    }

    private ClusterMonitorSnapshot.OperationEntry selectedOperation(
            List<ClusterMonitorSnapshot.OperationEntry> operations
    ) {
        if (selectedOperationId.isBlank()) {
            return null;
        }
        for (ClusterMonitorSnapshot.OperationEntry operation : operations) {
            if (selectedOperationId.equals(operation.operationId())) {
                return operation;
            }
        }
        return null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            double localX = mouseX - leftPos;
            double localY = mouseY - topPos;

            if (tab == Tab.NODES
                    && localX >= 16
                    && localX < imageWidth - 16
                    && localY >= NODE_BUFFER_START_Y
                    && localY < NODE_BUFFER_START_Y + NODE_BUFFER_VISIBLE_ROWS * ROW_H) {
                ClusterMonitorSnapshot.NodeEntry selectedNode = selectedNode();
                if (selectedNode != null) {
                    int row = (int) ((localY - NODE_BUFFER_START_Y) / ROW_H);
                    List<ClusterMonitorSnapshot.BufferEntry> nodeBuffers = buffersForNode(selectedNode.nodeId());
                    if (row >= 0 && row < nodeBuffers.size() && row < NODE_BUFFER_VISIBLE_ROWS) {
                        selectedEndpointId = nodeBuffers.get(row).endpointId();
                        scrollBufferIntoView(selectedEndpointId);
                        switchTab(Tab.BUFFERS);
                        return true;
                    }
                }
            }

            if (localX >= LIST_X && localX < LIST_X + LIST_W) {
                if (tab == Tab.NODES
                        && localY >= LIST_Y
                        && localY < LIST_Y + VISIBLE_NODE_ROWS * ROW_H) {
                    int row = (int) ((localY - LIST_Y) / ROW_H);
                    int index = nodeScroll + row;
                    List<ClusterMonitorSnapshot.NodeEntry> nodes = sortedNodes();
                    if (index >= 0 && index < nodes.size()) {
                        selectedNodeId = nodes.get(index).nodeId();
                        return true;
                    }
                }

                if (tab == Tab.BUFFERS
                        && localY >= LIST_Y
                        && localY < LIST_Y + VISIBLE_BUFFER_ROWS * ROW_H) {
                    int row = (int) ((localY - LIST_Y) / ROW_H);
                    int index = bufferScroll + row;
                    List<ClusterMonitorSnapshot.BufferEntry> buffers = sortedBuffers();
                    if (index >= 0 && index < buffers.size()) {
                        selectedEndpointId = buffers.get(index).endpointId();
                        return true;
                    }
                }

                if (tab == Tab.OPERATIONS
                        && localY >= LIST_Y
                        && localY < LIST_Y + VISIBLE_OPERATION_ROWS * ROW_H) {
                    int row = (int) ((localY - LIST_Y) / ROW_H);
                    int index = operationScroll + row;
                    List<ClusterMonitorSnapshot.OperationEntry> operations = sortedOperations();
                    if (index >= 0 && index < operations.size()) {
                        selectedOperationId = operations.get(index).operationId();
                        return true;
                    }
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
        if (tab == Tab.BUFFERS) {
            int max = Math.max(0, sortedBuffers().size() - VISIBLE_BUFFER_ROWS);
            bufferScroll = Math.max(0, Math.min(max, bufferScroll + direction));
            return true;
        }

        int max = Math.max(0, sortedOperations().size() - VISIBLE_OPERATION_ROWS);
        operationScroll = Math.max(0, Math.min(max, operationScroll + direction));
        return true;
    }

    private void scrollBufferIntoView(String endpointId) {
        List<ClusterMonitorSnapshot.BufferEntry> buffers = sortedBuffers();
        for (int index = 0; index < buffers.size(); index++) {
            if (!endpointId.equals(buffers.get(index).endpointId())) {
                continue;
            }
            if (index < bufferScroll) {
                bufferScroll = index;
            } else if (index >= bufferScroll + VISIBLE_BUFFER_ROWS) {
                bufferScroll = Math.max(0, index - VISIBLE_BUFFER_ROWS + 1);
            }
            return;
        }
    }

    private int operationStatusColor(String status) {
        if ("FAILED".equalsIgnoreCase(status)) {
            return RED;
        }
        if ("PENDING".equalsIgnoreCase(status)
                || "CLAIMED".equalsIgnoreCase(status)
                || "APPLIED".equalsIgnoreCase(status)) {
            return YELLOW;
        }
        if ("CONSUMED".equalsIgnoreCase(status)) {
            return GREEN;
        }
        return MUTED;
    }

    private String operationStatusLabel(String status) {
        if ("PENDING".equalsIgnoreCase(status)) {
            return "WAIT";
        }
        if ("CLAIMED".equalsIgnoreCase(status)) {
            return "ACTIVE";
        }
        if ("APPLIED".equalsIgnoreCase(status)) {
            return "APPLIED";
        }
        if ("CONSUMED".equalsIgnoreCase(status)) {
            return "DONE";
        }
        if ("FAILED".equalsIgnoreCase(status)) {
            return "ERROR";
        }
        return status == null || status.isBlank() ? "?" : status;
    }

    private String operationFromNode(ClusterMonitorSnapshot.OperationEntry operation) {
        if ("REMOTE_TO_MAIN".equalsIgnoreCase(operation.direction())) {
            return emptyNode(operation.sourceNode());
        }
        return emptyNode(operation.providerNode());
    }

    private String operationToNode(ClusterMonitorSnapshot.OperationEntry operation) {
        if ("REMOTE_TO_MAIN".equalsIgnoreCase(operation.direction())) {
            return emptyNode(operation.providerNode());
        }
        return emptyNode(operation.sourceNode());
    }

    private static String emptyNode(String node) {
        return node == null || node.isBlank() ? "?" : node;
    }

    private static String directionLabel(String direction) {
        if ("MAIN_TO_REMOTE".equalsIgnoreCase(direction)) {
            return "MAIN → REMOTE";
        }
        if ("REMOTE_TO_MAIN".equalsIgnoreCase(direction)) {
            return "REMOTE → MAIN";
        }
        return direction == null || direction.isBlank() ? "?" : direction;
    }

    private static String formatAge(long seconds) {
        long safe = Math.max(0L, seconds);
        if (safe < 60L) {
            return safe + "s";
        }
        if (safe < 3600L) {
            return (safe / 60L) + "m";
        }
        if (safe < 86_400L) {
            return (safe / 3600L) + "h";
        }
        return (safe / 86_400L) + "d";
    }

    private static int operationProgressWidth(
            ClusterMonitorSnapshot.OperationEntry operation,
            int maxWidth
    ) {
        if (maxWidth <= 0 || operation.requestedAmount() <= 0L) {
            return 0;
        }
        double ratio = (double) operation.deliveredAmount() / (double) operation.requestedAmount();
        ratio = Math.max(0.0D, Math.min(1.0D, ratio));
        return (int) Math.round(maxWidth * ratio);
    }

    private String cropPixels(String value, int maxWidth) {
        if (value == null || value.isBlank() || maxWidth <= 0) {
            return value == null ? "" : value;
        }

        if (font.width(value) <= maxWidth) {
            return value;
        }

        String ellipsis = "…";
        int ellipsisWidth = font.width(ellipsis);
        if (ellipsisWidth >= maxWidth) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (font.width(builder.toString() + character) + ellipsisWidth > maxWidth) {
                break;
            }
            builder.append(character);
        }
        return builder + ellipsis;
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
        String suffix = fluid ? "mB" : "";

        if (safe >= 1_000_000_000L) {
            return String.format(Locale.ROOT, "%.1fG%s", safe / 1_000_000_000.0D, suffix);
        }
        if (safe >= 1_000_000L) {
            return String.format(Locale.ROOT, "%.1fM%s", safe / 1_000_000.0D, suffix);
        }
        if (safe >= 1_000L) {
            return String.format(Locale.ROOT, "%.1fk%s", safe / 1_000.0D, suffix);
        }
        return safe + suffix;
    }

    private static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    private record BufferHealth(
            String text,
            int color,
            int lowCount,
            int belowTargetCount
    ) {
    }
}
