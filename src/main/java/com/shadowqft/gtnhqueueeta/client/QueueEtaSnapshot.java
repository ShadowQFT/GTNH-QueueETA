package com.shadowqft.gtnhqueueeta.client;

final class QueueEtaSnapshot {

    private final int totalRemainingTicks;
    private final int currentRecipeTicks;
    private final String machineClassName;
    private final String queueDebugSummary;

    QueueEtaSnapshot(int totalRemainingTicks, int currentRecipeTicks, String machineClassName) {
        this(totalRemainingTicks, currentRecipeTicks, machineClassName, null);
    }

    QueueEtaSnapshot(int totalRemainingTicks, int currentRecipeTicks, String machineClassName,
        String queueDebugSummary) {
        this.totalRemainingTicks = totalRemainingTicks;
        this.currentRecipeTicks = currentRecipeTicks;
        this.machineClassName = machineClassName;
        this.queueDebugSummary = queueDebugSummary;
    }

    int totalRemainingTicks() {
        return totalRemainingTicks;
    }

    int currentRecipeTicks() {
        return currentRecipeTicks;
    }

    int queuedTicks() {
        return Math.max(0, totalRemainingTicks - currentRecipeTicks);
    }

    boolean includesQueuedWork() {
        return queuedTicks() > 0;
    }

    String formattedDuration() {
        return QueueEtaFormatter.formatTicks(totalRemainingTicks);
    }

    String formattedCurrentRecipeDuration() {
        return QueueEtaFormatter.formatTicks(currentRecipeTicks);
    }

    String formattedQueuedDuration() {
        return QueueEtaFormatter.formatTicks(queuedTicks());
    }

    String machineClassName() {
        return machineClassName;
    }

    String queueDebugSummary() {
        return queueDebugSummary;
    }
}
