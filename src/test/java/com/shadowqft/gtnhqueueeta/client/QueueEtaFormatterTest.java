package com.shadowqft.gtnhqueueeta.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class QueueEtaFormatterTest {

    @Test
    void formatsSecondsOnlyDurations() {
        assertEquals("1s", QueueEtaFormatter.formatTicks(20));
        assertEquals("45s", QueueEtaFormatter.formatTicks(900));
    }

    @Test
    void formatsMinuteDurations() {
        assertEquals("1m 5s", QueueEtaFormatter.formatTicks(1300));
        assertEquals("2m", QueueEtaFormatter.formatTicks(2400));
    }

    @Test
    void formatsHourDurations() {
        assertEquals("1h 1m 5s", QueueEtaFormatter.formatTicks(73300));
        assertEquals("2h 0m", QueueEtaFormatter.formatTicks(144000));
    }
}
