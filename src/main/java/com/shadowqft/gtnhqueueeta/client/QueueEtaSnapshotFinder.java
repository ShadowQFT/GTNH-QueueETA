package com.shadowqft.gtnhqueueeta.client;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.tileentity.TileEntity;

import com.cleanroommc.modularui.api.IMuiScreen;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.factory.GuiData;
import com.cleanroommc.modularui.factory.PosGuiData;
import com.cleanroommc.modularui.screen.ModularContainer;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.value.sync.DoubleSyncValue;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.value.sync.SyncHandler;
import com.cleanroommc.modularui.widgets.ProgressWidget;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;

final class QueueEtaSnapshotFinder {

    private static final int MAX_DEPTH = 12;
    private static final int MAX_VISITED = 256;

    private QueueEtaSnapshotFinder() {}

    static Optional<QueueEtaSnapshot> find(Minecraft minecraft) {
        if (minecraft == null || minecraft.currentScreen == null || minecraft.thePlayer == null) {
            return Optional.empty();
        }

        Optional<Double> progressRatio = findPreferredProgressRatio(minecraft);
        Optional<MTEBasicMachine> machine = snapshotFromModularUiScreen(minecraft.currentScreen);
        if (machine.isPresent()) {
            return QueueWorkloadAnalyzer.analyze(machine.get(), progressRatio);
        }

        machine = searchGraph(minecraft.currentScreen);
        if (machine.isPresent()) {
            return QueueWorkloadAnalyzer.analyze(machine.get(), progressRatio);
        }

        machine = snapshotFromModularContainer(minecraft.thePlayer.openContainer);
        if (machine.isPresent()) {
            return QueueWorkloadAnalyzer.analyze(machine.get(), progressRatio);
        }

        machine = searchGraph(minecraft.thePlayer.openContainer);
        if (machine.isPresent()) {
            return QueueWorkloadAnalyzer.analyze(machine.get(), progressRatio);
        }

        return Optional.empty();
    }

    private static Optional<Double> findPreferredProgressRatio(Minecraft minecraft) {
        Optional<Double> progressRatio = findPanelProgressRatio(minecraft);
        if (progressRatio.isPresent()) {
            return progressRatio;
        }

        progressRatio = findProgressRatio(minecraft.currentScreen);
        if (progressRatio.isPresent()) {
            return progressRatio;
        }

        return findSyncedProgressRatio(minecraft);
    }

    private static Optional<Double> findSyncedProgressRatio(Minecraft minecraft) {
        if (minecraft == null) {
            return Optional.empty();
        }

        Object containerCandidate = null;
        if (minecraft.currentScreen instanceof GuiContainer guiContainer) {
            containerCandidate = guiContainer.inventorySlots;
        } else if (minecraft.thePlayer != null) {
            containerCandidate = minecraft.thePlayer.openContainer;
        }

        if (!(containerCandidate instanceof ModularContainer modularContainer)) {
            return Optional.empty();
        }

        return findProgressRatioFromSyncHandlers(modularContainer).map(SyncProbe::value);
    }

    static DebugProbe probe(Minecraft minecraft) {
        DebugProbe probe = new DebugProbe();
        if (minecraft == null) {
            return probe;
        }

        probe.screenClassName = className(minecraft.currentScreen);
        probe.containerClassName = minecraft.thePlayer == null ? null : className(minecraft.thePlayer.openContainer);
        PanelProgressProbe panelProbe = findPanelProgressProbe(minecraft.currentScreen).orElse(null);
        if (panelProbe != null) {
            probe.panelProgressKey = panelProbe.key();
            probe.panelProgressRatio = panelProbe.value();
        }
        probe.widgetProgressRatio = findProgressRatio(minecraft.currentScreen).orElse(Double.NaN);

        Object containerCandidate = null;
        if (minecraft.currentScreen instanceof GuiContainer guiContainer) {
            containerCandidate = guiContainer.inventorySlots;
        } else if (minecraft.thePlayer != null) {
            containerCandidate = minecraft.thePlayer.openContainer;
        }

        if (containerCandidate instanceof ModularContainer modularContainer) {
            SyncProbe syncProbe = findProgressRatioFromSyncHandlers(modularContainer).orElse(null);
            if (syncProbe != null) {
                probe.syncProgressKey = syncProbe.key();
                probe.syncProgressRatio = syncProbe.value();
            }
            GuiData guiData = modularContainer.getGuiData();
            probe.guiDataClassName = className(guiData);
            if (guiData instanceof PosGuiData posGuiData) {
                TileEntity tileEntity = posGuiData.getTileEntity();
                probe.tileEntityClassName = className(tileEntity);
                if (tileEntity instanceof IGregTechTileEntity gregTechTileEntity) {
                    probe.baseActive = gregTechTileEntity.isActive();
                    probe.metaTileClassName = className(gregTechTileEntity.getMetaTileEntity());
                    if (gregTechTileEntity.getMetaTileEntity() instanceof MTEBasicMachine machine) {
                        probe.progressTicks = machine.getProgresstime();
                        probe.maxProgressTicks = machine.maxProgresstime();
                    }
                }
            }
        }

        return probe;
    }

    private static Optional<Double> findPanelProgressRatio(Minecraft minecraft) {
        if (minecraft == null) {
            return Optional.empty();
        }
        return findPanelProgressProbe(minecraft.currentScreen).map(PanelProgressProbe::value);
    }

    private static Optional<PanelProgressProbe> findPanelProgressProbe(Object screenCandidate) {
        if (!(screenCandidate instanceof IMuiScreen muiScreen)) {
            return Optional.empty();
        }

        ModularPanel panel = muiScreen.getScreen()
            .getMainPanel();
        ArrayDeque<IWidget> queue = new ArrayDeque<>();
        queue.add(panel);

        while (!queue.isEmpty()) {
            IWidget widget = queue.removeFirst();
            if (widget instanceof ProgressWidget progressWidget) {
                try {
                    if (progressWidget.getSyncHandler() instanceof DoubleSyncValue doubleSyncValue) {
                        double value = doubleSyncValue.getDoubleValue();
                        if (Double.isFinite(value) && value > 0.0d && value <= 1.0d) {
                            return Optional.of(
                                new PanelProgressProbe(
                                    progressWidget.getSyncHandler()
                                        .getKey(),
                                    value));
                        }
                    }
                } catch (RuntimeException ignored) {
                    // Widget exists, but sync may not be fully initialized yet.
                }
            }

            for (IWidget child : widget.getChildren()) {
                if (child != null) {
                    queue.addLast(child);
                }
            }
        }

        return Optional.empty();
    }

    private static Optional<MTEBasicMachine> snapshotFromModularUiScreen(Object screen) {
        if (!(screen instanceof GuiContainer guiContainer)) {
            return Optional.empty();
        }

        return snapshotFromModularContainer(guiContainer.inventorySlots);
    }

    private static Optional<MTEBasicMachine> snapshotFromModularContainer(Object containerCandidate) {
        if (!(containerCandidate instanceof ModularContainer modularContainer)) {
            return Optional.empty();
        }

        GuiData guiData = modularContainer.getGuiData();
        if (!(guiData instanceof PosGuiData posGuiData)) {
            return Optional.empty();
        }

        TileEntity tileEntity = posGuiData.getTileEntity();
        if (!(tileEntity instanceof IGregTechTileEntity gregTechTileEntity)) {
            return Optional.empty();
        }

        return findMachine(gregTechTileEntity);
    }

    private static Optional<MTEBasicMachine> searchGraph(Object root) {
        if (root == null) {
            return Optional.empty();
        }

        ArrayDeque<GraphNode> queue = new ArrayDeque<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        queue.add(new GraphNode(root, 0));

        while (!queue.isEmpty() && visited.size() < MAX_VISITED) {
            GraphNode node = queue.removeFirst();
            Object value = node.value;
            if (value == null || !visited.add(value)) {
                continue;
            }

            Optional<MTEBasicMachine> snapshot = findMachine(value);
            if (snapshot.isPresent()) {
                return snapshot;
            }

            if (node.depth >= MAX_DEPTH || shouldSkipExpansion(value)) {
                continue;
            }

            for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType()
                        .isPrimitive()) {
                        continue;
                    }

                    try {
                        field.setAccessible(true);
                        Object nested = field.get(value);
                        if (nested != null) {
                            queue.addLast(new GraphNode(nested, node.depth + 1));
                        }
                    } catch (IllegalAccessException ignored) {
                        // Skip fields we cannot read.
                    }
                }
            }
        }

        return Optional.empty();
    }

    private static Optional<MTEBasicMachine> findMachine(Object candidate) {
        if (candidate instanceof MTEBasicMachine machine) {
            return Optional.of(machine);
        }

        if (candidate instanceof IGregTechTileEntity tile) {
            if (tile.getMetaTileEntity() instanceof MTEBasicMachine machine) {
                return Optional.of(machine);
            }
        }

        if (candidate instanceof MetaTileEntity metaTileEntity && metaTileEntity.getBaseMetaTileEntity() != null
            && metaTileEntity.getBaseMetaTileEntity()
                .getMetaTileEntity() instanceof MTEBasicMachine machine) {
            return Optional.of(machine);
        }

        return Optional.empty();
    }

    private static Optional<Double> findProgressRatio(Object root) {
        if (root == null) {
            return Optional.empty();
        }

        ArrayDeque<GraphNode> queue = new ArrayDeque<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        queue.add(new GraphNode(root, 0));

        while (!queue.isEmpty() && visited.size() < MAX_VISITED) {
            GraphNode node = queue.removeFirst();
            Object value = node.value;
            if (value == null || !visited.add(value)) {
                continue;
            }

            if (value instanceof ProgressWidget progressWidget) {
                double progress = progressWidget.getCurrentProgress();
                if (Double.isFinite(progress) && progress > 0.0d && progress <= 1.0d) {
                    return Optional.of(progress);
                }
            }

            if (node.depth >= MAX_DEPTH || shouldSkipExpansion(value)) {
                continue;
            }

            for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType()
                        .isPrimitive()) {
                        continue;
                    }

                    try {
                        field.setAccessible(true);
                        Object nested = field.get(value);
                        if (nested != null) {
                            queue.addLast(new GraphNode(nested, node.depth + 1));
                        }
                    } catch (IllegalAccessException ignored) {
                        // Skip fields we cannot read.
                    }
                }
            }
        }

        return Optional.empty();
    }

    private static Optional<SyncProbe> findProgressRatioFromSyncHandlers(ModularContainer modularContainer) {
        try {
            PanelSyncManager panelSyncManager = modularContainer.getSyncManager()
                .getMainPSM();
            Field field = PanelSyncManager.class.getDeclaredField("syncHandlers");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, SyncHandler> syncHandlers = (Map<String, SyncHandler>) field.get(panelSyncManager);
            SyncProbe bestProbe = null;
            for (Map.Entry<String, SyncHandler> entry : syncHandlers.entrySet()) {
                if (!(entry.getValue() instanceof DoubleSyncValue doubleSyncValue)) {
                    continue;
                }

                double value = doubleSyncValue.getDoubleValue();
                if (!Double.isFinite(value) || value <= 0.0d || value > 1.0d) {
                    continue;
                }

                String key = entry.getKey();
                if (!looksLikeProgressKey(key)) {
                    continue;
                }

                if (bestProbe == null || isBetterSyncProbe(key, value, bestProbe)) {
                    bestProbe = new SyncProbe(key, value);
                }
            }
            return Optional.ofNullable(bestProbe);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static boolean looksLikeProgressKey(String key) {
        if (key == null) {
            return false;
        }

        String normalized = key.toLowerCase();
        return normalized.contains("progress") || normalized.contains("recipe") || normalized.contains("basicmachine");
    }

    private static boolean isBetterSyncProbe(String key, double value, SyncProbe currentBest) {
        String normalized = key.toLowerCase();
        String currentNormalized = currentBest.key()
            .toLowerCase();

        boolean keyLooksMoreSpecific = normalized.contains("progress") && !currentNormalized.contains("progress");
        if (keyLooksMoreSpecific) {
            return true;
        }

        boolean sameSpecificity = normalized.contains("progress") == currentNormalized.contains("progress");
        return sameSpecificity && value > currentBest.value();
    }

    private static boolean shouldSkipExpansion(Object value) {
        return value instanceof String || value instanceof Number
            || value instanceof Enum<?>
            || value instanceof Class<?>;
    }

    private static String className(Object value) {
        return value == null ? null
            : value.getClass()
                .getName();
    }

    static final class DebugProbe {

        private String screenClassName;
        private String containerClassName;
        private String guiDataClassName;
        private String tileEntityClassName;
        private String metaTileClassName;
        private boolean baseActive;
        private int progressTicks = Integer.MIN_VALUE;
        private int maxProgressTicks = Integer.MIN_VALUE;
        private String panelProgressKey;
        private double panelProgressRatio = Double.NaN;
        private double widgetProgressRatio = Double.NaN;
        private String syncProgressKey;
        private double syncProgressRatio = Double.NaN;

        String screenClassName() {
            return screenClassName;
        }

        String containerClassName() {
            return containerClassName;
        }

        String guiDataClassName() {
            return guiDataClassName;
        }

        String tileEntityClassName() {
            return tileEntityClassName;
        }

        String metaTileClassName() {
            return metaTileClassName;
        }

        boolean hasProgressData() {
            return progressTicks != Integer.MIN_VALUE || maxProgressTicks != Integer.MIN_VALUE;
        }

        boolean baseActive() {
            return baseActive;
        }

        int progressTicks() {
            return progressTicks;
        }

        int maxProgressTicks() {
            return maxProgressTicks;
        }

        boolean hasPanelProgressRatio() {
            return Double.isFinite(panelProgressRatio);
        }

        String panelProgressKey() {
            return panelProgressKey;
        }

        double panelProgressRatio() {
            return panelProgressRatio;
        }

        boolean hasWidgetProgressRatio() {
            return Double.isFinite(widgetProgressRatio);
        }

        double widgetProgressRatio() {
            return widgetProgressRatio;
        }

        boolean hasSyncProgressRatio() {
            return Double.isFinite(syncProgressRatio);
        }

        String syncProgressKey() {
            return syncProgressKey;
        }

        double syncProgressRatio() {
            return syncProgressRatio;
        }
    }

    private static final class SyncProbe {

        private final String key;
        private final double value;

        private SyncProbe(String key, double value) {
            this.key = key;
            this.value = value;
        }

        private String key() {
            return key;
        }

        private double value() {
            return value;
        }
    }

    private static final class PanelProgressProbe {

        private final String key;
        private final double value;

        private PanelProgressProbe(String key, double value) {
            this.key = key;
            this.value = value;
        }

        private String key() {
            return key;
        }

        private double value() {
            return value;
        }
    }

    private static final class GraphNode {

        private final Object value;
        private final int depth;

        private GraphNode(Object value, int depth) {
            this.value = value;
            this.depth = depth;
        }
    }
}
