package com.shadowqft.gtnhqueueeta.client;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.GuiScreenEvent;

import com.shadowqft.gtnhqueueeta.Config;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

public class QueueEtaTooltipHandler {

    private static final long SNAPSHOT_REFRESH_MS = 200L;
    private static final long SNAPSHOT_STALE_GRACE_MS = 600L;
    private static final int LABEL_MARGIN = 6;
    private static final int ACTIVE_TEXT_COLOR = 0xFF404040;
    private static final int DEBUG_TEXT_COLOR = 0xFF8888;
    private static final int DEBUG_INFO_COLOR = 0xFFFFFF;
    private static final String LABEL_PREFIX = "ETA: ";
    private static final String DEBUG_LABEL = "ETA?";
    private Optional<QueueEtaSnapshot> cachedSnapshot = Optional.empty();
    private long lastSnapshotAt;
    private long lastNonEmptySnapshotAt;

    @SubscribeEvent
    public void onDrawScreen(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (!Config.enableTooltip || event.gui == null) {
            return;
        }

        if (!(event.gui instanceof GuiContainer)) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        Optional<QueueEtaSnapshot> snapshot = getSnapshot(minecraft);
        boolean showDebugOverlay = Config.enableDebugOverlay;
        if (!snapshot.isPresent() && !showDebugOverlay) {
            return;
        }

        AnchorRect anchor = resolveAnchor(event.gui);
        HoverRect hover = snapshot.isPresent() ? drawEtaLabel(minecraft, anchor, snapshot.get())
            : drawDebugPill(minecraft, anchor);
        if (isMouseWithin(event.mouseX, event.mouseY, hover)) {
            List<String> tooltip = snapshot.isPresent() ? buildTooltip(snapshot.get()) : buildDebugTooltip(minecraft);
            GuiTooltipRenderer.draw(tooltip, event.mouseX, event.mouseY, minecraft.fontRenderer);
        }

        if (showDebugOverlay) {
            drawDebugOverlay(minecraft, event.gui, anchor.debugRightX(), anchor.debugStartY(), snapshot);
        }
    }

    private Optional<QueueEtaSnapshot> getSnapshot(Minecraft minecraft) {
        long now = Minecraft.getSystemTime();
        if (now - lastSnapshotAt >= SNAPSHOT_REFRESH_MS) {
            Optional<QueueEtaSnapshot> freshSnapshot = QueueEtaSnapshotFinder.find(minecraft);
            if (freshSnapshot.isPresent()) {
                cachedSnapshot = freshSnapshot;
                lastNonEmptySnapshotAt = now;
            } else if (!cachedSnapshot.isPresent() || now - lastNonEmptySnapshotAt > SNAPSHOT_STALE_GRACE_MS) {
                cachedSnapshot = Optional.empty();
            }
            lastSnapshotAt = now;
        }
        return cachedSnapshot;
    }

    private static HoverRect drawEtaLabel(Minecraft minecraft, AnchorRect anchor, QueueEtaSnapshot snapshot) {
        int x = anchor.panelX();
        int y = anchor.panelY();
        String label = LABEL_PREFIX + snapshot.formattedDuration();
        minecraft.fontRenderer.drawString(label, x, y, ACTIVE_TEXT_COLOR);
        return new HoverRect(x, y, minecraft.fontRenderer.getStringWidth(label), 8);
    }

    private static HoverRect drawDebugPill(Minecraft minecraft, AnchorRect anchor) {
        int x = anchor.panelX();
        int y = anchor.panelY();
        minecraft.fontRenderer.drawStringWithShadow(DEBUG_LABEL, x, y, DEBUG_TEXT_COLOR);
        return new HoverRect(x, y, minecraft.fontRenderer.getStringWidth(DEBUG_LABEL), 8);
    }

    private static List<String> buildTooltip(QueueEtaSnapshot snapshot) {
        List<String> tooltip = new ArrayList<>();
        tooltip.add(
            EnumChatFormatting.GOLD + (snapshot.includesQueuedWork() ? "Queued workload ETA" : "Current recipe ETA"));
        tooltip.add(EnumChatFormatting.GRAY + snapshot.formattedDuration());

        if (snapshot.includesQueuedWork()) {
            tooltip.add(EnumChatFormatting.DARK_GRAY + "Current: " + snapshot.formattedCurrentRecipeDuration());
            tooltip.add(EnumChatFormatting.DARK_GRAY + "Queued after current: " + snapshot.formattedQueuedDuration());
        }

        if (Config.enableDebugOverlay) {
            tooltip.add(EnumChatFormatting.BLUE + "Machine: " + snapshot.machineClassName());
            if (snapshot.queueDebugSummary() != null) {
                tooltip.add(EnumChatFormatting.DARK_GRAY + snapshot.queueDebugSummary());
            }
        }

        if (Config.showDebugTicks) {
            tooltip.add(EnumChatFormatting.DARK_GRAY.toString() + snapshot.totalRemainingTicks() + " ticks");
        }

        return tooltip;
    }

    private static List<String> buildDebugTooltip(Minecraft minecraft) {
        List<String> tooltip = new ArrayList<>();
        QueueEtaSnapshotFinder.DebugProbe probe = QueueEtaSnapshotFinder.probe(minecraft);
        tooltip.add(EnumChatFormatting.RED + "No GT machine snapshot found");
        addDebugLine(tooltip, EnumChatFormatting.BLUE, "Screen", probe.screenClassName());
        addDebugLine(tooltip, EnumChatFormatting.BLUE, "Container", probe.containerClassName());
        addDebugLine(tooltip, EnumChatFormatting.BLUE, "GuiData", probe.guiDataClassName());
        addDebugLine(tooltip, EnumChatFormatting.BLUE, "TileEntity", probe.tileEntityClassName());
        addDebugLine(tooltip, EnumChatFormatting.BLUE, "MetaTile", probe.metaTileClassName());
        if (probe.metaTileClassName() != null) {
            tooltip.add(EnumChatFormatting.BLUE + "BaseActive: " + probe.baseActive());
        }
        if (probe.hasProgressData()) {
            tooltip
                .add(EnumChatFormatting.BLUE + "Progress: " + probe.progressTicks() + " / " + probe.maxProgressTicks());
        }
        if (probe.hasPanelProgressRatio()) {
            tooltip.add(EnumChatFormatting.BLUE + "PanelProgress: " + probe.panelProgressRatio());
            tooltip.add(EnumChatFormatting.DARK_GRAY + "PanelKey: " + probe.panelProgressKey());
        }
        if (probe.hasWidgetProgressRatio()) {
            tooltip.add(EnumChatFormatting.BLUE + "WidgetProgress: " + probe.widgetProgressRatio());
        }
        if (probe.hasSyncProgressRatio()) {
            tooltip.add(EnumChatFormatting.BLUE + "SyncProgress: " + probe.syncProgressRatio());
            tooltip.add(EnumChatFormatting.DARK_GRAY + "SyncKey: " + probe.syncProgressKey());
        }
        tooltip.add(EnumChatFormatting.DARK_GRAY + "Open an active GregTech machine GUI");
        return tooltip;
    }

    private static void drawDebugOverlay(Minecraft minecraft, GuiScreen gui, int rightX, int startY,
        Optional<QueueEtaSnapshot> snapshot) {
        List<String> lines = new ArrayList<>();
        QueueEtaSnapshotFinder.DebugProbe probe = QueueEtaSnapshotFinder.probe(minecraft);
        lines.add(snapshot.isPresent() ? "GT machine snapshot found" : "No GT machine snapshot found");
        if (snapshot.isPresent()) {
            lines.add(
                "Snapshot ETA: " + snapshot.get()
                    .formattedDuration());
            lines.add(
                "Snapshot machine: " + snapshot.get()
                    .machineClassName());
            if (snapshot.get()
                .queueDebugSummary() != null) {
                lines.add(
                    snapshot.get()
                        .queueDebugSummary());
            }
        }
        addDebugLine(lines, "Screen", probe.screenClassName());
        addDebugLine(lines, "Container", probe.containerClassName());
        addDebugLine(lines, "GuiData", probe.guiDataClassName());
        addDebugLine(lines, "TileEntity", probe.tileEntityClassName());
        addDebugLine(lines, "MetaTile", probe.metaTileClassName());
        if (probe.metaTileClassName() != null) {
            lines.add("BaseActive: " + probe.baseActive());
        }
        if (probe.hasProgressData()) {
            lines.add("Progress: " + probe.progressTicks() + " / " + probe.maxProgressTicks());
        }
        if (probe.hasPanelProgressRatio()) {
            lines.add("PanelProgress: " + probe.panelProgressRatio());
            lines.add("PanelKey: " + probe.panelProgressKey());
        }
        if (probe.hasWidgetProgressRatio()) {
            lines.add("WidgetProgress: " + probe.widgetProgressRatio());
        }
        if (probe.hasSyncProgressRatio()) {
            lines.add("SyncProgress: " + probe.syncProgressRatio());
            lines.add("SyncKey: " + probe.syncProgressKey());
        }

        int y = startY;
        for (String line : lines) {
            int lineX = gui.width - 8 - minecraft.fontRenderer.getStringWidth(line);
            minecraft.fontRenderer.drawStringWithShadow(line, lineX, y, DEBUG_INFO_COLOR);
            y += 10;
        }
    }

    private static AnchorRect resolveAnchor(GuiScreen gui) {
        if (gui instanceof GuiContainer guiContainer) {
            Integer guiLeft = readIntField(guiContainer, "guiLeft");
            Integer guiTop = readIntField(guiContainer, "guiTop");
            Integer xSize = readIntField(guiContainer, "xSize");
            Integer ySize = readIntField(guiContainer, "ySize");
            if (guiLeft != null && guiTop != null && xSize != null && ySize != null) {
                int panelX = guiLeft + LABEL_MARGIN;
                int panelY = guiTop + LABEL_MARGIN;
                return new AnchorRect(panelX, panelY, gui.width - 8, 8);
            }
        }

        int panelX = 8;
        int panelY = 8;
        return new AnchorRect(panelX, panelY, gui.width - 8, panelY + 16);
    }

    private static Integer readIntField(Object target, String fieldName) {
        for (Class<?> type = target.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return (Integer) field.get(target);
            } catch (ReflectiveOperationException ignored) {
                // Keep walking up the hierarchy.
            }
        }
        return null;
    }

    private static boolean isMouseWithin(int mouseX, int mouseY, HoverRect rect) {
        return mouseX >= rect.x() && mouseX < rect.x() + rect.width()
            && mouseY >= rect.y()
            && mouseY < rect.y() + rect.height();
    }

    private static void addDebugLine(List<String> tooltip, EnumChatFormatting color, String label, String value) {
        if (value != null) {
            tooltip.add(color + label + ": " + value);
        }
    }

    private static void addDebugLine(List<String> lines, String label, String value) {
        if (value != null) {
            lines.add(label + ": " + value);
        }
    }

    private static final class HoverRect {

        private final int x;
        private final int y;
        private final int width;
        private final int height;

        private HoverRect(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        private int x() {
            return x;
        }

        private int y() {
            return y;
        }

        private int width() {
            return width;
        }

        private int height() {
            return height;
        }
    }

    private static final class AnchorRect {

        private final int panelX;
        private final int panelY;
        private final int debugRightX;
        private final int debugStartY;

        private AnchorRect(int panelX, int panelY, int debugRightX, int debugStartY) {
            this.panelX = panelX;
            this.panelY = panelY;
            this.debugRightX = debugRightX;
            this.debugStartY = debugStartY;
        }

        private int panelX() {
            return panelX;
        }

        private int panelY() {
            return panelY;
        }

        private int debugRightX() {
            return debugRightX;
        }

        private int debugStartY() {
            return debugStartY;
        }
    }
}
