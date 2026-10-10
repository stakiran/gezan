package com.stakiran.gezan;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GezanMod implements ModInitializer {
    public static final String MOD_ID = "gezan";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[Gezan] Initializing...");

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            GezanCommand.register(dispatcher);
        });

        GameManager.registerEvents();

        LOGGER.info("[Gezan] Ready!");
    }
}
