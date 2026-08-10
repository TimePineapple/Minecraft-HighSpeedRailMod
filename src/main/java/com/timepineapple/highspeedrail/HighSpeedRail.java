package com.timepineapple.highspeedrail;

import com.timepineapple.highspeedrail.command.HighSpeedRailCommand;
import com.timepineapple.highspeedrail.config.ModConfig;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HighSpeedRail implements ModInitializer {
    public static final String MOD_ID = "highspeedrail";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile ModConfig config = ModConfig.defaults();

    public HighSpeedRail() {
    }

    @Override
    public void onInitialize() {
        reloadConfig();
        HighSpeedRailCommand.register();
        LOGGER.info("highSpeedRail initialized (server-side only, Fabric Loader 0.19.3)");
    }

    public static ModConfig config() {
        return config;
    }

    public static synchronized ModConfig.LoadResult reloadConfig() {
        ModConfig.LoadResult result = ModConfig.load(config);
        if (result.success()) {
            config = result.config();
        }
        return result;
    }

    public static synchronized boolean installConfig(ModConfig next) {
        if (next.validationError().isPresent() || !next.save()) {
            return false;
        }
        config = next;
        return true;
    }
}
