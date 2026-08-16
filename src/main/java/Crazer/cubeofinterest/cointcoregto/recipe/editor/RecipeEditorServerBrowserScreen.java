package Crazer.cubeofinterest.cointcoregto.recipe.editor;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Client-side remote browser for the server recipe folders. */
public final class RecipeEditorServerBrowserScreen extends Screen {
    private static final int ROW_HEIGHT = 22;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT);

    private final Screen parent;
    private boolean crafting;

    private final List<RecipeEditorServerFileService.Entry> allEntries = new ArrayList<>();
    private final List<RecipeEditorServerFileService.Entry> filteredEntries = new ArrayList<>();
    private final List<FormattedCharSequence> cachedPreviewLines = new ArrayList<>();
    private int cachedPreviewWidth = -1;
    private String cachedPreviewPath = "";

    private EditBox searchBox;
    private Button gtoTabButton;
    private Button craftingTabButton;
    private Button editButton;
    private Button deleteButton;
    private Button refreshButton;
    private Button backButton;

    private RecipeEditorServerFileService.Entry selected;
    private String loadedPath = "";
    private String loadedJson = "";
    private String status = "Загрузка списка с сервера...";
    private boolean statusSuccess = true;
    private int listScroll;
    private int previewScroll;

    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int listX;
    private int listY;
    private int listWidth;
    private int listHeight;
    private int previewX;
    private int previewY;
    private int previewWidth;
    private int previewHeight;

    public RecipeEditorServerBrowserScreen(Screen parent, boolean crafting) {
        super(Component.literal("Серверные рецепты CointCoreGTO"));
        this.parent = parent;
        this.crafting = crafting;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(760, width - 20);
        panelHeight = Math.min(430, height - 20);
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;

        listX = panelX + 10;
        listY = panelY + 58;
        listWidth = Math.min(330, Math.max(220, panelWidth / 2 - 15));
        listHeight = panelHeight - 112;

        previewX = listX + listWidth + 10;
        previewY = listY;
        previewWidth = panelX + panelWidth - 10 - previewX;
        previewHeight = listHeight;

        searchBox = new EditBox(font, listX, panelY + 30, listWidth, 20, Component.literal("Поиск"));
        searchBox.setHint(Component.literal("ID / type / путь"));
        searchBox.setResponder(ignored -> rebuildFilter());
        addRenderableWidget(searchBox);

        gtoTabButton = Button.builder(Component.literal("GT/GTO"), button -> switchMode(false))
                .bounds(panelX + panelWidth - 166, panelY + 7, 74, 20)
                .build();
        craftingTabButton = Button.builder(Component.literal("Верстак"), button -> switchMode(true))
                .bounds(panelX + panelWidth - 86, panelY + 7, 74, 20)
                .build();
        addRenderableWidget(gtoTabButton);
        addRenderableWidget(craftingTabButton);

        int buttonY = panelY + panelHeight - 42;
        editButton = Button.builder(Component.literal("Редактировать"), button -> editSelected())
                .bounds(listX, buttonY, 100, 20)
                .build();
        deleteButton = Button.builder(Component.literal("Удалить"), button -> confirmDeleteStageOne())
                .bounds(listX + 106, buttonY, 72, 20)
                .build();
        refreshButton = Button.builder(Component.literal("Обновить"), button -> requestList())
                .bounds(listX + 184, buttonY, 78, 20)
                .build();
        backButton = Button.builder(Component.literal("Назад"), button -> returnToParent())
                .bounds(panelX + panelWidth - 80, buttonY, 70, 20)
                .build();

        addRenderableWidget(editButton);
        addRenderableWidget(deleteButton);
        addRenderableWidget(refreshButton);
        addRenderableWidget(backButton);
        updateButtons();

        if (allEntries.isEmpty()) {
            requestList();
        } else {
            rebuildFilter();
        }
    }

    private void switchMode(boolean craftingMode) {
        if (this.crafting == craftingMode) {
            return;
        }
        this.crafting = craftingMode;
        allEntries.clear();
        filteredEntries.clear();
        selected = null;
        loadedPath = "";
        loadedJson = "";
        listScroll = 0;
        previewScroll = 0;
        clearPreviewCache();
        statusSuccess = true;
        status = crafting ? "Открываю серверные рецепты верстака..." : "Открываю серверные GT/GTO-рецепты...";
        updateButtons();
        requestList();
    }


    private void requestList() {
        statusSuccess = true;
        status = "Запрашиваю список файлов...";
        RecipeEditorNetwork.CHANNEL.sendToServer(new RecipeEditorServerFilesRequestPacket(crafting));
    }

    public void onList(RecipeEditorServerFilesListPacket packet) {
        if (packet.crafting() != crafting) {
            return;
        }
        statusSuccess = packet.success();
        status = packet.message();
        allEntries.clear();
        if (packet.entries() != null) {
            allEntries.addAll(packet.entries());
        }
        selected = null;
        loadedPath = "";
        loadedJson = "";
        clearPreviewCache();
        listScroll = 0;
        previewScroll = 0;
        rebuildFilter();
    }

    public void onContent(RecipeEditorServerFileContentPacket packet) {
        if (packet.crafting() != crafting) {
            return;
        }
        statusSuccess = packet.success();
        status = packet.message();
        if (packet.success()) {
            loadedPath = packet.relativePath();
            loadedJson = packet.json();
            clearPreviewCache();
            previewScroll = 0;
            if (loadedJson.length() > 64_000) {
                status = status + " Файл доступен для просмотра/удаления, но слишком большой для GUI-редактирования.";
            }
        } else {
            loadedPath = "";
            loadedJson = "";
            clearPreviewCache();
        }
        updateButtons();
    }

    public void onDeleteResult(RecipeEditorServerFileDeleteResultPacket packet) {
        if (packet.crafting() != crafting) {
            return;
        }
        statusSuccess = packet.success();
        status = packet.message();
        if (packet.success()) {
            selected = null;
            loadedPath = "";
            loadedJson = "";
            clearPreviewCache();
            requestList();
        }
        updateButtons();
    }

    private void rebuildFilter() {
        String query = searchBox == null ? "" : searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        filteredEntries.clear();
        for (RecipeEditorServerFileService.Entry entry : allEntries) {
            if (query.isEmpty()
                    || entry.relativePath().toLowerCase(Locale.ROOT).contains(query)
                    || entry.recipeId().toLowerCase(Locale.ROOT).contains(query)
                    || entry.recipeType().toLowerCase(Locale.ROOT).contains(query)) {
                filteredEntries.add(entry);
            }
        }
        listScroll = Mth.clamp(listScroll, 0, maxListScroll());
        updateButtons();
    }

    private void select(RecipeEditorServerFileService.Entry entry) {
        selected = entry;
        loadedPath = "";
        loadedJson = "";
        clearPreviewCache();
        previewScroll = 0;
        statusSuccess = true;
        status = "Загружаю JSON: " + entry.relativePath();
        updateButtons();
        RecipeEditorNetwork.CHANNEL.sendToServer(
                new RecipeEditorServerFileReadPacket(crafting, entry.relativePath())
        );
    }

    private void editSelected() {
        if (selected == null || loadedJson.isBlank() || !samePath(selected.relativePath(), loadedPath)) {
            return;
        }

        RecipeEditorPendingOpen.queue(crafting, loadedPath, loadedJson);
        statusSuccess = true;
        status = crafting
                ? "Открываю Crafting Recipe Editor..."
                : "Открываю GT/GTO Recipe Editor...";
        RecipeEditorNetwork.CHANNEL.sendToServer(new RecipeEditorOpenModePacket(crafting));
    }

    private void confirmDeleteStageOne() {
        if (selected == null) {
            return;
        }
        String path = selected.relativePath();
        Minecraft.getInstance().setScreen(new ConfirmScreen(
                confirmed -> {
                    if (!confirmed) {
                        Minecraft.getInstance().setScreen(this);
                        return;
                    }
                    confirmDeleteStageTwo(path);
                },
                Component.literal("Удаление рецепта — подтверждение 1/2"),
                Component.literal("Удалить файл с сервера? " + path)
        ));
    }

    private void confirmDeleteStageTwo(String path) {
        Minecraft.getInstance().setScreen(new ConfirmScreen(
                confirmed -> {
                    Minecraft.getInstance().setScreen(this);
                    if (!confirmed) {
                        return;
                    }
                    statusSuccess = true;
                    status = "Удаляю: " + path;
                    RecipeEditorNetwork.CHANNEL.sendToServer(
                            new RecipeEditorServerFileDeletePacket(crafting, path)
                    );
                },
                Component.literal("Удаление рецепта — подтверждение 2/2").withStyle(ChatFormatting.RED),
                Component.literal("Последнее подтверждение: " + path + ". Файл будет удалён физически.")
        ));
    }

    private void updateButtons() {
        if (editButton == null || deleteButton == null) {
            return;
        }
        boolean hasSelection = selected != null;
        editButton.active = hasSelection
                && selected.validJson()
                && !loadedJson.isBlank()
                && loadedJson.length() <= 64_000
                && samePath(selected.relativePath(), loadedPath);
        deleteButton.active = hasSelection;
        if (gtoTabButton != null) {
            gtoTabButton.active = crafting;
        }
        if (craftingTabButton != null) {
            craftingTabButton.active = !crafting;
        }
    }

    private static boolean samePath(String first, String second) {
        if (first == null || second == null) {
            return false;
        }
        return first.replace('\\', '/').equals(second.replace('\\', '/'));
    }

    private void returnToParent() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void onClose() {
        returnToParent();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { // GLFW_ESCAPE without a hard GLFW dependency here.
            returnToParent();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && inside(mouseX, mouseY, listX, listY, listWidth, listHeight)) {
            int row = (int) ((mouseY - listY) / ROW_HEIGHT) + listScroll;
            if (row >= 0 && row < filteredEntries.size()) {
                select(filteredEntries.get(row));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int direction = delta > 0.0D ? -1 : 1;
        if (inside(mouseX, mouseY, listX, listY, listWidth, listHeight)) {
            listScroll = Mth.clamp(listScroll + direction, 0, maxListScroll());
            return true;
        }
        if (inside(mouseX, mouseY, previewX, previewY, previewWidth, previewHeight)) {
            previewScroll = Math.max(0, previewScroll + direction * 3);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private int maxListScroll() {
        int visibleRows = Math.max(1, listHeight / ROW_HEIGHT);
        return Math.max(0, filteredEntries.size() - visibleRows);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xF0161D2A);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 2, 0xFF65E6FF);

        Component visibleTitle = Component.literal(crafting ? "Серверные crafting-рецепты" : "Серверные GT/GTO-рецепты");
        graphics.drawString(font, visibleTitle, panelX + 10, panelY + 9, 0xFFFFFF, false);
        String root = crafting
                ? "config/cointcoregto/crafting_recipes/"
                : "config/cointcoregto/gto_recipes/";
        graphics.drawString(font, root, panelX + 10, panelY + 19, 0x8291A6, false);

        graphics.fill(listX, listY, listX + listWidth, listY + listHeight, 0xAA0D121B);
        graphics.fill(previewX, previewY, previewX + previewWidth, previewY + previewHeight, 0xAA0D121B);

        renderList(graphics, mouseX, mouseY);
        renderPreview(graphics);

        String visibleStatus = status == null ? "" : status;
        if (visibleStatus.length() > 100) {
            visibleStatus = visibleStatus.substring(0, 100) + "...";
        }
        graphics.drawString(
                font,
                visibleStatus,
                panelX + 10,
                panelY + panelHeight - 56,
                statusSuccess ? 0x6FFF8B : 0xFF7070,
                false
        );

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderList(GuiGraphics graphics, int mouseX, int mouseY) {
        int visibleRows = Math.max(1, listHeight / ROW_HEIGHT);
        int end = Math.min(filteredEntries.size(), listScroll + visibleRows);
        for (int index = listScroll; index < end; index++) {
            RecipeEditorServerFileService.Entry entry = filteredEntries.get(index);
            int rowY = listY + (index - listScroll) * ROW_HEIGHT;
            boolean selectedRow = selected != null && selected.relativePath().equals(entry.relativePath());
            boolean hovered = inside(mouseX, mouseY, listX, rowY, listWidth, ROW_HEIGHT - 1);

            int background = selectedRow ? 0xFF314D65 : hovered ? 0xFF243243 : 0xFF171F2B;
            graphics.fill(listX + 1, rowY + 1, listX + listWidth - 1, rowY + ROW_HEIGHT - 1, background);

            String id = entry.recipeId();
            if (id.length() > 42) {
                id = id.substring(0, 39) + "...";
            }
            graphics.drawString(font, id, listX + 5, rowY + 4, entry.validJson() ? 0xFFFFFF : 0xFF7777, false);

            String path = entry.relativePath();
            if (path.length() > 48) {
                path = "..." + path.substring(path.length() - 45);
            }
            graphics.drawString(font, path, listX + 5, rowY + 13, 0x8291A6, false);
        }
    }

    private void renderPreview(GuiGraphics graphics) {
        if (selected == null) {
            graphics.drawString(font, "Выбери рецепт слева", previewX + 6, previewY + 6, 0xA9B7CC, false);
            return;
        }

        graphics.drawString(font, selected.recipeId(), previewX + 6, previewY + 5, 0xFFFFFF, false);
        graphics.drawString(font, selected.recipeType(), previewX + 6, previewY + 16, 0x6FE9FF, false);
        graphics.drawString(
                font,
                formatBytes(selected.size()) + "  |  " + DATE_FORMAT.format(new Date(selected.modified())),
                previewX + 6,
                previewY + 27,
                0x8291A6,
                false
        );

        int textY = previewY + 43;
        int lineHeight = 10;
        int available = Math.max(1, (previewHeight - 48) / lineHeight);
        List<FormattedCharSequence> lines = previewLines();
        previewScroll = Mth.clamp(previewScroll, 0, Math.max(0, lines.size() - available));
        int end = Math.min(lines.size(), previewScroll + available);
        for (int i = previewScroll; i < end; i++) {
            graphics.drawString(font, lines.get(i), previewX + 6, textY, 0xC8D4E3, false);
            textY += lineHeight;
        }
    }

    private List<FormattedCharSequence> previewLines() {
        if (loadedJson.isBlank() || selected == null || !samePath(selected.relativePath(), loadedPath)) {
            return List.of(Component.literal("Загрузка JSON...").getVisualOrderText());
        }

        int wrapWidth = Math.max(80, previewWidth - 12);
        if (cachedPreviewWidth == wrapWidth && loadedPath.equals(cachedPreviewPath) && !cachedPreviewLines.isEmpty()) {
            return cachedPreviewLines;
        }

        cachedPreviewLines.clear();
        cachedPreviewWidth = wrapWidth;
        cachedPreviewPath = loadedPath;
        String[] rawLines = loadedJson.split("\\R", -1);
        for (String line : rawLines) {
            List<FormattedCharSequence> wrapped = font.split(Component.literal(line), wrapWidth);
            if (wrapped.isEmpty()) {
                cachedPreviewLines.add(Component.empty().getVisualOrderText());
            } else {
                cachedPreviewLines.addAll(wrapped);
            }
        }
        return cachedPreviewLines;
    }

    private void clearPreviewCache() {
        cachedPreviewLines.clear();
        cachedPreviewWidth = -1;
        cachedPreviewPath = "";
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1_024) {
            return bytes + " B";
        }
        if (bytes < 1_048_576) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0D);
        }
        return String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0D);
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}
