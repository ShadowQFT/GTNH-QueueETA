package com.shadowqft.gtnhqueueeta.client;

public final class QueueEtaFormatter {

    private static final int TICKS_PER_SECOND = 20;
    private static final int SECONDS_PER_MINUTE = 60;
    private static final int MINUTES_PER_HOUR = 60;

    private QueueEtaFormatter() {}

    public static String formatTicks(int ticks) {
        int totalSeconds = Math.max(0, (ticks + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND);
        int hours = totalSeconds / (SECONDS_PER_MINUTE * MINUTES_PER_HOUR);
        int minutes = (totalSeconds / SECONDS_PER_MINUTE) % MINUTES_PER_HOUR;
        int seconds = totalSeconds % SECONDS_PER_MINUTE;

        if (hours > 0) {
            return seconds > 0 ? String.format("%dh %dm %ds", hours, minutes, seconds)
                : String.format("%dh %dm", hours, minutes);
        }

        if (minutes > 0) {
            return seconds > 0 ? String.format("%dm %ds", minutes, seconds) : String.format("%dm", minutes);
        }

        return String.format("%ds", seconds);
    }
}
