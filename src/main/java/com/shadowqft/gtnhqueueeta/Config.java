package com.shadowqft.gtnhqueueeta;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

public class Config {

    public static boolean enableTooltip = true;
    public static boolean showDebugTicks = false;
    public static boolean enableDebugOverlay = false;

    public static void synchronizeConfiguration(File configFile) {
        Configuration configuration = new Configuration(configFile);

        enableTooltip = configuration
            .getBoolean("enableTooltip", Configuration.CATEGORY_GENERAL, enableTooltip, "Enable queue ETA tooltips.");
        showDebugTicks = configuration.getBoolean(
            "showDebugTicks",
            Configuration.CATEGORY_GENERAL,
            showDebugTicks,
            "Show raw tick counts in the ETA tooltip.");
        enableDebugOverlay = configuration.getBoolean(
            "enableDebugOverlay",
            Configuration.CATEGORY_GENERAL,
            enableDebugOverlay,
            "Show GTNH Queue ETA debug overlay and machine diagnostics.");

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }
}
