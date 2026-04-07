package com.shadowqft.gtnhqueueeta;

import com.shadowqft.gtnhqueueeta.client.ClientEventRegistrar;

import cpw.mods.fml.common.event.FMLInitializationEvent;

public class ClientProxy extends CommonProxy {

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        ClientEventRegistrar.register();
    }
}
