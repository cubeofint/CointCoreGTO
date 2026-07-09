package Crazer.cubeofinterest.cointcoregto.compat.radio;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@OnlyIn(Dist.CLIENT)
public class CointRadioScreen extends Screen {
    private final BlockPos pos;
    private final List<String> stations;
    private final String currentStation;
    private final boolean active;
    private int radius;
    private final String customUrl;

    private static final int STATIONS_PER_PAGE = 5;

    private int volumePercent;
    private EditBox urlBox;
    private EditBox searchBox;

    private int stationPage = 0;
    private final List<Button> stationButtons = new ArrayList<>();
    private Button prevPageButton;
    private Button nextPageButton;
    private Component pageLabel = Component.empty();

    public CointRadioScreen(
            BlockPos pos,
            List<String> stations,
            String currentStation,
            boolean active,
            int radius,
            String customUrl
    ) {
        super(Component.literal("Радио"));
        this.pos = pos;
        this.stations = new ArrayList<>(stations);
        this.currentStation = currentStation == null ? "" : currentStation;
        this.active = active;
        this.radius = radius;
        this.customUrl = customUrl == null ? "" : customUrl;
        this.volumePercent = CointRadioPlayer.getVolumePercent();
    }

    @Override
    protected void init() {
        super.init();

        int buttonWidth = 220;
        int buttonHeight = 20;
        int startY = 78;

        this.urlBox = new EditBox(
                this.font,
                this.width / 2 - buttonWidth / 2,
                startY,
                buttonWidth,
                buttonHeight,
                Component.literal("URL")
        );

        this.urlBox.setMaxLength(2048);
        this.urlBox.setValue(customUrl);
        this.urlBox.setHint(Component.literal("https://radio.example.com/stream"));
        this.addRenderableWidget(this.urlBox);

        this.addRenderableWidget(
                Button.builder(
                                Component.literal("§aПрименить URL"),
                                button -> {
                                    CointRadioNetwork.sendSetCustomUrlToServer(pos, urlBox.getValue());
                                    this.onClose();
                                }
                        )
                        .bounds(this.width / 2 - buttonWidth / 2, startY + 24, buttonWidth, buttonHeight)
                        .build()
        );

        this.addRenderableWidget(
                Button.builder(
                                Component.literal("§6Добавить в закладки"),
                                button -> {
                                    CointRadioBookmarks.add(urlBox.getValue());

                                    if (this.minecraft != null && this.minecraft.player != null) {
                                        this.minecraft.player.displayClientMessage(
                                                Component.literal("§a[CointMusic] Радио добавлено в закладки."),
                                                true
                                        );
                                    }

                                    this.onClose();
                                }
                        )
                        .bounds(this.width / 2 + buttonWidth / 2 + 6, startY, 130, buttonHeight)
                        .build()
        );

        int bookmarkX = this.width / 2 + buttonWidth / 2 + 6;
        int bookmarkY = startY + 24;

        List<CointRadioBookmarks.Bookmark> bookmarks = CointRadioBookmarks.getBookmarks();

        for (int i = 0; i < Math.min(5, bookmarks.size()); i++) {
            CointRadioBookmarks.Bookmark bookmark = bookmarks.get(i);

            this.addRenderableWidget(
                    Button.builder(
                                    Component.literal("§b★ " + bookmark.name()),
                                    button -> {
                                        CointRadioNetwork.sendSetCustomUrlToServer(pos, bookmark.url());

                                        if (!active) {
                                            CointRadioNetwork.sendToggleActiveToServer(pos);
                                        }

                                        this.onClose();
                                    }
                            )
                            .bounds(bookmarkX, bookmarkY + i * 24, 130, buttonHeight)
                            .build()
            );
        }

        this.addRenderableWidget(
                Button.builder(
                                Component.literal("§cОчистить URL"),
                                button -> {
                                    CointRadioNetwork.sendClearCustomUrlToServer(pos);
                                    this.onClose();
                                }
                        )
                        .bounds(this.width / 2 - buttonWidth / 2, startY + 48, buttonWidth, buttonHeight)
                        .build()
        );

        this.addRenderableWidget(
                Button.builder(
                                Component.literal(active ? "§cВыключить радио" : "§aВключить радио"),
                                button -> {
                                    CointRadioNetwork.sendToggleActiveToServer(pos);
                                    this.onClose();
                                }
                        )
                        .bounds(this.width / 2 - buttonWidth / 2, startY + 78, buttonWidth, buttonHeight)
                        .build()
        );

        this.addRenderableWidget(
                Button.builder(
                                Component.literal("§eСледующая станция"),
                                button -> {
                                    CointRadioNetwork.sendNextStationToServer(pos);
                                    this.onClose();
                                }
                        )
                        .bounds(this.width / 2 - buttonWidth / 2, startY + 102, buttonWidth, buttonHeight)
                        .build()
        );

        this.addRenderableWidget(
                Button.builder(
                                Component.literal("§dСлучайная станция"),
                                button -> {
                                    CointRadioNetwork.sendRandomStationToServer(pos);
                                    this.onClose();
                                }
                        )
                        .bounds(this.width / 2 - buttonWidth / 2, startY + 126, buttonWidth, buttonHeight)
                        .build()
        );

        this.addRenderableWidget(
                new AbstractSliderButton(
                        this.width / 2 - buttonWidth / 2,
                        startY + 150,
                        buttonWidth,
                        buttonHeight,
                        Component.literal("§bРадиус: §f" + radius),
                        radius / 32.0D
                ) {
                    @Override
                    protected void updateMessage() {
                        int newRadius = (int) Math.round(this.value * 32.0D);
                        this.setMessage(Component.literal("§bРадиус: §f" + newRadius));
                    }

                    @Override
                    protected void applyValue() {
                        int newRadius = (int) Math.round(this.value * 32.0D);

                        if (newRadius < 0) {
                            newRadius = 0;
                        }

                        if (newRadius > 32) {
                            newRadius = 32;
                        }

                        radius = newRadius;
                        CointRadioNetwork.sendSetRadiusToServer(pos, newRadius);
                    }
                }
        );

        this.addRenderableWidget(
                new AbstractSliderButton(
                        this.width / 2 - buttonWidth / 2,
                        startY + 174,
                        buttonWidth,
                        buttonHeight,
                        Component.literal("§eГромкость: §f" + volumePercent + "%"),
                        volumePercent / 100.0D
                ) {
                    @Override
                    protected void updateMessage() {
                        int percent = (int) Math.round(this.value * 100.0D);
                        this.setMessage(Component.literal("§eГромкость: §f" + percent + "%"));
                    }

                    @Override
                    protected void applyValue() {
                        int percent = (int) Math.round(this.value * 100.0D);
                        volumePercent = percent;
                        CointRadioPlayer.setVolumePercent(percent);
                    }
                }
        );

        this.searchBox = new EditBox(
                this.font,
                this.width / 2 - buttonWidth / 2,
                startY + 208,
                buttonWidth,
                buttonHeight,
                Component.literal("Поиск станции")
        );

        this.searchBox.setMaxLength(64);
        this.searchBox.setHint(Component.literal("Поиск станции..."));
        this.searchBox.setResponder(value -> {
            stationPage = 0;
            rebuildStationButtons();
        });
        this.addRenderableWidget(this.searchBox);

        rebuildStationButtons();

        this.addRenderableWidget(
                Button.builder(Component.literal("Закрыть"), button -> this.onClose())
                        .bounds(this.width / 2 - buttonWidth / 2, this.height - 32, buttonWidth, buttonHeight)
                        .build()
        );
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);

        graphics.drawCenteredString(
                this.font,
                "Радио",
                this.width / 2,
                12,
                0xFFFFFF
        );

        String stationText = customUrl.isBlank() ? getCurrentStationDisplayName() : "custom URL";

        graphics.drawCenteredString(
                this.font,
                "Источник: " + stationText,
                this.width / 2,
                28,
                0xDDDDDD
        );

        graphics.drawCenteredString(
                this.font,
                active ? "Состояние: включено" : "Состояние: выключено",
                this.width / 2,
                40,
                active ? 0x55FF55 : 0xFF5555
        );

        graphics.drawCenteredString(
                this.font,
                "Радиус: " + radius,
                this.width / 2,
                52,
                0xDDDDDD
        );

        graphics.drawCenteredString(
                this.font,
                "Ссылка или online-radio stream:",
                this.width / 2,
                66,
                0xAAAAAA
        );

        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(
                this.font,
                pageLabel,
                this.width / 2,
                78 + 232 + STATIONS_PER_PAGE * 24 + 9,
                0xFFFFFF
        );
    }

    private void rebuildStationButtons() {
        for (Button button : stationButtons) {
            this.removeWidget(button);
        }

        stationButtons.clear();

        if (prevPageButton != null) {
            this.removeWidget(prevPageButton);
            prevPageButton = null;
        }

        if (nextPageButton != null) {
            this.removeWidget(nextPageButton);
            nextPageButton = null;
        }

        int buttonWidth = 220;
        int buttonHeight = 20;
        int startY = 78;
        int centerX = this.width / 2 - buttonWidth / 2;
        int stationStartY = startY + 232;

        List<StationEntry> filteredStations = getFilteredStations();
        int maxPage = Math.max(0, (filteredStations.size() - 1) / STATIONS_PER_PAGE);

        if (stationPage > maxPage) {
            stationPage = maxPage;
        }

        if (stationPage < 0) {
            stationPage = 0;
        }

        int startIndex = stationPage * STATIONS_PER_PAGE;
        int endIndex = Math.min(filteredStations.size(), startIndex + STATIONS_PER_PAGE);

        for (int i = startIndex; i < endIndex; i++) {
            StationEntry stationEntry = filteredStations.get(i);
            String stationId = stationEntry.id();
            String stationName = stationEntry.name();

            Component label;

            if (!customUrl.isBlank()) {
                label = Component.literal("§8" + stationName);
            } else if (stationId.equalsIgnoreCase(currentStation)) {
                label = Component.literal("§a▶ " + stationName);
            } else {
                label = Component.literal("§f" + stationName);
            }

            int y = stationStartY + (i - startIndex) * 24;

            Button stationButton = Button.builder(label, button -> {
                        CointRadioNetwork.sendSelectStationToServer(pos, stationId);
                        this.onClose();
                    })
                    .bounds(centerX, y, buttonWidth, buttonHeight)
                    .build();

            if (!customUrl.isBlank()) {
                stationButton.active = false;
            }

            stationButtons.add(stationButton);
            this.addRenderableWidget(stationButton);
        }

        int pageY = stationStartY + STATIONS_PER_PAGE * 24 + 4;

        prevPageButton = Button.builder(Component.literal("§e← Назад"), button -> {
                    stationPage--;
                    rebuildStationButtons();
                })
                .bounds(centerX, pageY, 82, buttonHeight)
                .build();

        nextPageButton = Button.builder(Component.literal("§eВперёд →"), button -> {
                    stationPage++;
                    rebuildStationButtons();
                })
                .bounds(centerX + buttonWidth - 82, pageY, 82, buttonHeight)
                .build();

        prevPageButton.active = stationPage > 0;
        nextPageButton.active = stationPage < maxPage;

        pageLabel = Component.literal("Стр. " + (stationPage + 1) + "/" + (maxPage + 1));

        this.addRenderableWidget(prevPageButton);
        this.addRenderableWidget(nextPageButton);
    }

    private List<StationEntry> getFilteredStations() {
        String query = searchBox == null ? "" : searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        List<StationEntry> result = new ArrayList<>();

        for (String rawEntry : stations) {
            StationEntry stationEntry = parseStationEntry(rawEntry);

            if (stationEntry.id().isBlank() && stationEntry.name().isBlank()) {
                continue;
            }

            if (query.isBlank()
                    || stationEntry.id().toLowerCase(Locale.ROOT).contains(query)
                    || stationEntry.name().toLowerCase(Locale.ROOT).contains(query)) {
                result.add(stationEntry);
            }
        }

        return result;
    }

    private static StationEntry parseStationEntry(String entry) {
        return new StationEntry(stationIdFromEntry(entry), stationNameFromEntry(entry));
    }

    private record StationEntry(String id, String name) {
    }

    private static String stationIdFromEntry(String entry) {
        if (entry == null || entry.isBlank()) {
            return "";
        }

        int separator = entry.indexOf('\u001F');

        if (separator < 0) {
            return entry;
        }

        return entry.substring(0, separator);
    }

    private static String stationNameFromEntry(String entry) {
        if (entry == null || entry.isBlank()) {
            return "";
        }

        int separator = entry.indexOf('\u001F');

        if (separator < 0 || separator >= entry.length() - 1) {
            return entry;
        }

        return entry.substring(separator + 1);
    }

    private String getCurrentStationDisplayName() {
        for (String stationEntry : stations) {
            String stationId = stationIdFromEntry(stationEntry);

            if (stationId.equalsIgnoreCase(currentStation)) {
                return stationNameFromEntry(stationEntry);
            }
        }

        return currentStation;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}