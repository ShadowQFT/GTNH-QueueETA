package com.shadowqft.gtnhqueueeta.client;

import net.minecraftforge.common.MinecraftForge;

public final class ClientEventRegistrar {

    private static boolean registered;

    private ClientEventRegistrar() {}

    public static void register() {
        if (registered) {
            return;
        }

        MinecraftForge.EVENT_BUS.register(new QueueEtaTooltipHandler());
        registered = true;
    }
}
