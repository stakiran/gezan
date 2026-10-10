package com.stakiran.mopup;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MopupMod implements ModInitializer {
    public static final String MOD_ID = "mopup";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[Mopup] Initializing...");

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            MopupCommand.register(dispatcher);
        });

        GameManager.registerEvents();

        LOGGER.info("[Mopup] Ready!");
    }
}
