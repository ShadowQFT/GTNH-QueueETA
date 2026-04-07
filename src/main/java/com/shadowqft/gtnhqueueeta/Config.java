package com.shadowqft.gtnhqueueeta;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

public class Config {

    public static boolean enableTooltip = true;

    public static void synchronizeConfiguration(File configFile) {
        Configuration configuration = new Configuration(configFile);

        enableTooltip = configuration
            .getBoolean("enableTooltip", Configuration.CATEGORY_GENERAL, enableTooltip, "Enable queue ETA tooltips.");

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }
}
