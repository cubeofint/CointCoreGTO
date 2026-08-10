package Crazer.cubeofinterest.cointcoregto.battlepass.client;

import Crazer.cubeofinterest.cointcoregto.battlepass.network.BattlePassClaimPacket;
import Crazer.cubeofinterest.cointcoregto.battlepass.network.BattlePassNetwork;
import Crazer.cubeofinterest.cointcoregto.battlepass.network.BattlePassStatePacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public final class BattlePassScreen extends Screen {
    private static final int PANEL = 0xE51B2330;
    private static final int CARD = 0xFF293446;
    private static final int CARD_CURRENT = 0xFF3A506D;
    private static final int CARD_CLAIMED = 0xFF202733;
    private static final int BORDER = 0xFF53677F;
    private static final int ACCENT = 0xFFFFC857;
    private static final int SUCCESS = 0xFF6BD18A;
    private static final int PREMIUM = 0xFFC795EA;
    private static final int TEXT = 0xFFF2F5F8;
    private static final int MUTED = 0xFF9BA8B8;

    private BattlePassStatePacket state;
    private Button previousButton;
    private Button nextButton;
    private Button claimButton;
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private int visibleDays;
    private int freeRowY;
    private int premiumRowY;
    private int cardHeight;
    private int statusY;
    private int targetStart;
    private double animatedStart;

    public BattlePassScreen(BattlePassStatePacket state) {
        super(Component.literal(state.title()));
        this.state = state;
        this.visibleDays = clamp(state.visibleDays(), 7, 10);
        this.targetStart = centeredStart();
        this.animatedStart = this.targetStart;
    }

    @Override
    protected void init() {
        this.panelWidth = Math.max(220, Math.min(390, this.width - 8));
        this.panelHeight = Math.max(170, Math.min(228, this.height - 8));
        this.panelLeft = (this.width - this.panelWidth) / 2;
        this.panelTop = Math.max(4, (this.height - this.panelHeight) / 2);

        this.freeRowY = this.panelTop + 45;
        int rowsAvailable = this.panelHeight - 45 - 52;
        this.cardHeight = Math.max(36, Math.min(54, (rowsAvailable - 14) / 2));
        this.premiumRowY = this.freeRowY + this.cardHeight + 14;
        this.statusY = this.panelTop + this.panelHeight - 43;

        int navY = (this.freeRowY + this.premiumRowY + this.cardHeight) / 2 - 20;
        this.previousButton = addRenderableWidget(Button.builder(
                        Component.literal("<"),
                        ignored -> moveWindow(-1)
                ).bounds(this.panelLeft + 8, navY, 20, 40).build());
        this.nextButton = addRenderableWidget(Button.builder(
                        Component.literal(">"),
                        ignored -> moveWindow(1)
                ).bounds(this.panelLeft + this.panelWidth - 28, navY, 20, 40).build());
        this.claimButton = addRenderableWidget(Button.builder(
                        Component.literal("Забрать награду"),
                        ignored -> BattlePassNetwork.CHANNEL.sendToServer(new BattlePassClaimPacket())
                ).bounds(this.panelLeft + this.panelWidth / 2 - 76, this.panelTop + this.panelHeight - 28, 152, 22).build());
        updateButtons();
    }

    public void updateState(BattlePassStatePacket packet) {
        int oldDay = currentDayIndex();
        this.state = packet;
        this.visibleDays = clamp(packet.visibleDays(), 7, 10);
        if (oldDay != currentDayIndex()) {
            this.targetStart = centeredStart();
        }
        this.targetStart = clamp(this.targetStart, 0, maxStart());
        updateButtons();
    }

    @Override
    public void tick() {
        super.tick();
        double difference = this.targetStart - this.animatedStart;
        this.animatedStart += difference * 0.24D;
        if (Math.abs(difference) < 0.002D) {
            this.animatedStart = this.targetStart;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.fill(this.panelLeft, this.panelTop, this.panelLeft + this.panelWidth, this.panelTop + this.panelHeight, PANEL);
        drawBorder(graphics, this.panelLeft, this.panelTop, this.panelWidth, this.panelHeight, BORDER);

        graphics.drawCenteredString(this.font, this.state.title(), this.width / 2, this.panelTop + 13, TEXT);
        String progress = "День серии: " + Math.max(1, this.state.streak())
                + " из " + this.state.days().size();
        graphics.drawCenteredString(this.font, progress, this.width / 2, this.panelTop + 29, MUTED);

        int trackLeft = this.panelLeft + 34;
        int trackRight = this.panelLeft + this.panelWidth - 34;
        int trackWidth = trackRight - trackLeft;
        double slotWidth = trackWidth / (double) this.visibleDays;
        int freeY = this.freeRowY;
        int premiumY = this.premiumRowY;

        graphics.drawString(this.font, "Бесплатные награды", trackLeft, freeY - 13, TEXT, false);
        String premiumTitle = this.state.premiumLabel()
                + (this.state.premiumUnlocked() ? "" : " (закрыто)");
        graphics.drawString(this.font, premiumTitle, trackLeft, premiumY - 13, this.state.premiumUnlocked() ? PREMIUM : MUTED, false);

        int hoveredDay = -1;
        boolean hoveredPremium = false;
        for (int dayIndex = 0; dayIndex < this.state.days().size(); dayIndex++) {
            double position = dayIndex - this.animatedStart;
            double x = trackLeft + position * slotWidth + 2.0D;
            int cardWidth = Math.max(22, (int) Math.floor(slotWidth - 4.0D));
            if (x + cardWidth < trackLeft || x > trackRight) {
                continue;
            }

            BattlePassStatePacket.DayState day = this.state.days().get(dayIndex);
            boolean current = dayIndex == currentDayIndex();
            int freeCardY = freeY;
            int premiumCardY = premiumY;
            renderRewardCard(graphics, (int) Math.round(x), freeCardY, cardWidth, this.cardHeight,
                    dayIndex, day.freeRewards(), day.freeClaimed(), current, false, true);
            renderRewardCard(graphics, (int) Math.round(x), premiumCardY, cardWidth, this.cardHeight,
                    dayIndex, day.premiumRewards(), day.premiumClaimed(), current, true, this.state.premiumUnlocked());

            if (inside(mouseX, mouseY, (int) Math.round(x), freeCardY, cardWidth, this.cardHeight)) {
                hoveredDay = dayIndex;
                hoveredPremium = false;
            } else if (inside(mouseX, mouseY, (int) Math.round(x), premiumCardY, cardWidth, this.cardHeight)) {
                hoveredDay = dayIndex;
                hoveredPremium = true;
            }
        }

        renderMarker(graphics, trackLeft, trackRight, slotWidth, freeY);
        renderStatus(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        if (hoveredDay >= 0 && hoveredDay < this.state.days().size()) {
            BattlePassStatePacket.DayState day = this.state.days().get(hoveredDay);
            List<ItemStack> stacks = hoveredPremium ? day.premiumRewards() : day.freeRewards();
            if (!stacks.isEmpty()) {
                graphics.renderTooltip(this.font, stacks.get(0), mouseX, mouseY);
            }
        }
    }

    private void renderRewardCard(
            GuiGraphics graphics,
            int x,
            int y,
            int width,
            int height,
            int dayIndex,
            List<ItemStack> rewards,
            boolean claimed,
            boolean current,
            boolean premiumTrack,
            boolean trackUnlocked
    ) {
        int fill = claimed ? CARD_CLAIMED : current ? CARD_CURRENT : CARD;
        graphics.fill(x, y, x + width, y + height, fill);
        drawBorder(graphics, x, y, width, height, current ? ACCENT : premiumTrack ? PREMIUM : BORDER);

        String dayText = Integer.toString(dayIndex + 1);
        graphics.drawCenteredString(this.font, dayText, x + width / 2, y + 4, current ? ACCENT : TEXT);

        if (!rewards.isEmpty()) {
            ItemStack display = rewards.get(0);
            int itemX = x + width / 2 - 8;
            int itemY = y + 19;
            graphics.pose().pushPose();
            if (current) {
                graphics.pose().translate(x + width / 2.0F, y + 28.0F, 0.0F);
                graphics.pose().scale(1.12F, 1.12F, 1.0F);
                graphics.pose().translate(-(x + width / 2.0F), -(y + 28.0F), 0.0F);
            }
            graphics.renderItem(display, itemX, itemY);
            graphics.renderItemDecorations(this.font, display, itemX, itemY);
            graphics.pose().popPose();
            if (rewards.size() > 1) {
                graphics.drawString(this.font, "+" + (rewards.size() - 1), x + Math.max(2, width - 13), y + height - 11, TEXT, false);
            }
        } else {
            graphics.drawCenteredString(this.font, "—", x + width / 2, y + 26, MUTED);
        }

        if (!trackUnlocked) {
            graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xA010131A);
        }
        if (claimed) {
            graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0x7010151D);
            for (int offset = -height; offset < width; offset += 8) {
                drawDottedDiagonal(graphics, x, y, width, height, offset, SUCCESS);
            }
            graphics.drawCenteredString(this.font, "✓", x + width / 2, y + 22, SUCCESS);
        }
    }

    private void renderMarker(GuiGraphics graphics, int trackLeft, int trackRight, double slotWidth, int freeY) {
        int current = currentDayIndex();
        if (current < 0) {
            return;
        }
        boolean claimed = false;
        if (current < this.state.days().size()) {
            BattlePassStatePacket.DayState day = this.state.days().get(current);
            claimed = day.freeClaimed() || day.premiumClaimed();
        }
        double position = current - this.animatedStart + (claimed ? 0.92D : 0.5D);
        if (position < 0.0D || position > this.visibleDays) {
            return;
        }
        int markerX = (int) Math.round(trackLeft + position * slotWidth);
        if (markerX < trackLeft || markerX > trackRight) {
            return;
        }
        int markerY = freeY - 7;
        graphics.fill(markerX - 4, markerY, markerX + 5, markerY + 2, ACCENT);
        graphics.fill(markerX - 3, markerY + 2, markerX + 4, markerY + 4, ACCENT);
        graphics.fill(markerX - 2, markerY + 4, markerX + 3, markerY + 6, ACCENT);
    }

    private void renderStatus(GuiGraphics graphics) {
        String status;
        int color;
        if (!this.state.enabled()) {
            status = "Боевой пропуск временно отключён";
            color = MUTED;
        } else if (!this.state.statusMessage().isBlank()) {
            status = this.state.statusMessage();
            color = this.state.statusMessage().contains("получена") ? SUCCESS : ACCENT;
        } else {
            status = "Колесо мыши или стрелки — листать награды";
            color = MUTED;
        }
        graphics.drawCenteredString(this.font, status, this.width / 2, this.statusY, color);
    }

    private void updateButtons() {
        if (this.previousButton != null) {
            this.previousButton.active = this.targetStart > 0;
        }
        if (this.nextButton != null) {
            this.nextButton.active = this.targetStart < maxStart();
        }
        if (this.claimButton != null) {
            int current = currentDayIndex();
            boolean canClaim = false;
            if (this.state.enabled() && current >= 0 && current < this.state.days().size()) {
                BattlePassStatePacket.DayState day = this.state.days().get(current);
                canClaim = (!day.freeClaimed() && !day.freeRewards().isEmpty())
                        || (this.state.premiumUnlocked()
                        && !day.premiumClaimed()
                        && !day.premiumRewards().isEmpty());
            }
            this.claimButton.active = canClaim;
            this.claimButton.setMessage(Component.literal(
                    canClaim ? "Забрать награду" : "Награда получена"
            ));
        }
    }

    private void moveWindow(int direction) {
        this.targetStart = clamp(this.targetStart + direction, 0, maxStart());
        updateButtons();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (inside(mouseX, mouseY, this.panelLeft, this.panelTop, this.panelWidth, this.panelHeight)) {
            if (delta > 0.0D) {
                moveWindow(-1);
            } else if (delta < 0.0D) {
                moveWindow(1);
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int currentDayIndex() {
        if (this.state.days().isEmpty()) {
            return -1;
        }
        return clamp(Math.max(1, this.state.streak()) - 1, 0, this.state.days().size() - 1);
    }

    private int centeredStart() {
        return clamp(currentDayIndex() - this.visibleDays / 2, 0, maxStart());
    }

    private int maxStart() {
        return Math.max(0, this.state.days().size() - this.visibleDays);
    }

    private static void drawBorder(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y, x + 1, y + height, color);
        graphics.fill(x + width - 1, y, x + width, y + height, color);
    }

    private static void drawDottedDiagonal(
            GuiGraphics graphics,
            int x,
            int y,
            int width,
            int height,
            int offset,
            int color
    ) {
        for (int step = 0; step < height; step += 4) {
            int px = x + offset + step;
            int py = y + height - 2 - step;
            if (px >= x + 2 && px < x + width - 2 && py >= y + 2 && py < y + height - 2) {
                graphics.fill(px, py, px + 2, py + 2, color);
            }
        }
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Mth.clamp(value, minimum, maximum);
    }
}
