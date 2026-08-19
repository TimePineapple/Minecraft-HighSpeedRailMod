package com.timepineapple.highspeedrail;

import com.timepineapple.highspeedrail.command.HighSpeedRailCommand;
import com.timepineapple.highspeedrail.config.ModConfig;
import com.timepineapple.highspeedrail.minecart.PhysicsProfile;
import com.timepineapple.highspeedrail.minecart.HighSpeedRailDiagnostics;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HighSpeedRail implements ModInitializer {
    public static final String MOD_ID = "highspeedrail";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile ModConfig config = ModConfig.defaults();
    private static volatile PhysicsProfile physicsProfile = PhysicsProfile.defaults(config);

    public HighSpeedRail() {
    }

    @Override
    public void onInitialize() {
        reloadConfig();
        HighSpeedRailCommand.register();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            physicsProfile = PhysicsProfile.capture(server.getOverworld(), config);
            LOGGER.info(
                "Cached {} minecart physics: landMax={}, waterMax={}",
                physicsProfile.startupController(),
                physicsProfile.vanillaLandSpeed(),
                physicsProfile.vanillaWaterSpeed()
            );
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            HighSpeedRailDiagnostics.onServerStopped(server);
            physicsProfile = PhysicsProfile.defaults(config);
        });
        ServerTickEvents.END_SERVER_TICK.register(HighSpeedRailDiagnostics::onServerTick);
        LOGGER.info("highSpeedRail initialized (server-side only, Fabric Loader 0.19.3)");
    }

    public static ModConfig config() {
        return config;
    }

    public static PhysicsProfile physicsProfile() {
        return physicsProfile;
    }

    public static synchronized ModConfig.LoadResult reloadConfig() {
        ModConfig.LoadResult result = ModConfig.load(config);
        if (result.success()) {
            config = result.config();
            physicsProfile = physicsProfile.withConfig(config);
        }
        return result;
    }

    public static synchronized boolean installConfig(ModConfig next) {
        if (next.validationError().isPresent() || !next.save()) {
            return false;
        }
        config = next;
        physicsProfile = physicsProfile.withConfig(next);
        return true;
    }
}
