package me.jasmine.gysf;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GysfClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("gysf");

    @Override
    public void onInitializeClient() {
        LOGGER.info("gysf client initialized");
    }
}
