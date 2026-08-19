package com.timepineapple.highspeedrail.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.timepineapple.highspeedrail.HighSpeedRail;
import com.timepineapple.highspeedrail.config.ModConfig;
import com.timepineapple.highspeedrail.minecart.PhysicsProfile;
import com.timepineapple.highspeedrail.minecart.HighSpeedRailDiagnostics;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionCheck;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.entity.vehicle.MinecartEntity;
import net.minecraft.text.Text;

import java.util.function.Consumer;

public final class HighSpeedRailCommand {
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            CommandManager.literal("highspeedrail")
                .requires(CommandManager.requirePermissionLevel(
                    new PermissionCheck.Require(new Permission.Level(PermissionLevel.GAMEMASTERS))
                ))
                .then(CommandManager.literal("get").executes(HighSpeedRailCommand::showConfig))
                .then(CommandManager.literal("debug")
                    .then(CommandManager.literal("start")
                        .then(CommandManager.argument("seconds", IntegerArgumentType.integer(1, 60))
                            .executes(HighSpeedRailCommand::startDiagnostic)))
                    .then(CommandManager.literal("stop").executes(HighSpeedRailCommand::stopDiagnostic))
                    .then(CommandManager.literal("status").executes(HighSpeedRailCommand::diagnosticStatus)))
                .then(CommandManager.literal("set")
                    .then(CommandManager.literal("enable")
                        .then(CommandManager.argument("value", BoolArgumentType.bool())
                            .executes(context -> update(context, "enable", String.valueOf(BoolArgumentType.getBool(context, "value")),
                                config -> config.enable = BoolArgumentType.getBool(context, "value")))))
                    .then(CommandManager.literal("maxSpeed")
                        .then(CommandManager.argument("value", DoubleArgumentType.doubleArg(0.4))
                            .executes(context -> updateDouble(context, "maxSpeed", value -> value > 0.4,
                                config -> config.maxSpeed = DoubleArgumentType.getDouble(context, "value")))))
                    .then(CommandManager.literal("activeBlocks")
                        .then(CommandManager.argument("value", IntegerArgumentType.integer(ModConfig.MIN_ACTIVE_BLOCKS))
                            .executes(context -> update(context, "activeBlocks",
                                String.valueOf(IntegerArgumentType.getInteger(context, "value")),
                                config -> config.activeBlocks = IntegerArgumentType.getInteger(context, "value")))))
                    .then(CommandManager.literal("accelerationSeconds")
                        .then(CommandManager.argument("value", IntegerArgumentType.integer(1))
                            .executes(context -> update(context, "accelerationSeconds",
                                String.valueOf(IntegerArgumentType.getInteger(context, "value")),
                                config -> config.accelerationSeconds = IntegerArgumentType.getInteger(context, "value")))))
                )
                .then(CommandManager.literal("reload").executes(context -> {
                    ModConfig.LoadResult result = HighSpeedRail.reloadConfig();
                    if (!result.success()) {
                        return error(context, result.errorMessage());
                    }
                    context.getSource().sendFeedback(() -> Text.literal("highSpeedRail configuration reloaded."), true);
                    return 1;
                }))
        ));
    }

    private static int showConfig(CommandContext<ServerCommandSource> context) {
        ModConfig config = HighSpeedRail.config();
        PhysicsProfile physics = HighSpeedRail.physicsProfile();
        context.getSource().sendFeedback(() -> Text.literal(
            "highSpeedRail configuration:\n"
                + "version=" + modVersion() + "\n"
                + "enable=" + config.enable + "\n"
                + "maxSpeed=" + config.maxSpeed + "\n"
                + "activeBlocks=" + config.activeBlocks + "\n"
                + "accelerationSeconds=" + config.accelerationSeconds + "\n"
                + "effectiveActivationBlocks=" + physics.effectiveActivationBlocks() + "\n"
                + "startupController=" + physics.startupController() + "\n"
                + "startupCacheCaptured=" + physics.startupCaptured() + "\n"
                + "cachedVanillaLandSpeed=" + physics.vanillaLandSpeed() + "\n"
                + "cachedVanillaWaterSpeed=" + physics.vanillaWaterSpeed() + "\n"
                + "cachedVanillaLandSlopeTrackSpeed="
                + physics.vanillaTrackSpeed(false, true) + "\n"
                + "cachedVanillaWaterSlopeTrackSpeed="
                + physics.vanillaTrackSpeed(true, true) + "\n"
                + "aAccel=" + physics.acceleration() + "\n"
                + "aBrakeLandFlat=" + physics.brakeLandFlat() + "\n"
                + "aBrakeWaterFlat=" + physics.brakeWaterFlat() + "\n"
                + "aBrakeLandSlope=" + physics.brakeLandSlope() + "\n"
                + "aBrakeWaterSlope=" + physics.brakeWaterSlope() + "\n"
                + "speedUnit=track-centerline-blocks-per-tick\n"
                + "slopePhysics=no-uphill-or-downhill-gravity-adjustment\n"
                + "activationProfile=two-normal-tick-actual-displacement-min\n"
                + "activePhysics=experimental-retention-compensated-with-horizontal-rail-snap\n"
                + "Startup controller limits are cached; restart the server to refresh them."
        ), false);
        return 1;
    }

    private static String modVersion() {
        return FabricLoader.getInstance()
            .getModContainer(HighSpeedRail.MOD_ID)
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
    }

    private static int startDiagnostic(CommandContext<ServerCommandSource> context) {
        ServerPlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            return error(context, "debug start must be executed by a player riding an ordinary minecart");
        }
        if (!(player.getVehicle() instanceof MinecartEntity cart)) {
            return error(context, "you must be riding an ordinary minecart");
        }
        int seconds = IntegerArgumentType.getInteger(context, "seconds");
        HighSpeedRailDiagnostics.StartResult result = HighSpeedRailDiagnostics.start(
            context.getSource().getServer(), player, cart, seconds
        );
        if (!result.success()) {
            return error(context, result.message());
        }
        context.getSource().sendFeedback(
            () -> Text.literal("highSpeedRail diagnostic started: " + result.path()),
            false
        );
        return 1;
    }

    private static int stopDiagnostic(CommandContext<ServerCommandSource> context) {
        HighSpeedRailDiagnostics.StopResult result = HighSpeedRailDiagnostics.stop("manual_stop");
        if (!result.success()) {
            return error(context, result.message());
        }
        context.getSource().sendFeedback(
            () -> Text.literal("highSpeedRail diagnostic saved: " + result.path()),
            false
        );
        return 1;
    }

    private static int diagnosticStatus(CommandContext<ServerCommandSource> context) {
        HighSpeedRailDiagnostics.Status status = HighSpeedRailDiagnostics.status();
        String message = status.active()
            ? "highSpeedRail diagnostic active: " + status.elapsedTicks() + "/"
                + status.durationTicks() + " ticks, file=" + status.path()
            : "highSpeedRail diagnostic inactive; lastFile=" + status.path()
                + ", lastReason=" + status.lastReason();
        context.getSource().sendFeedback(() -> Text.literal(message), false);
        return 1;
    }

    private static int updateDouble(
        CommandContext<ServerCommandSource> context,
        String key,
        java.util.function.DoublePredicate validator,
        Consumer<ModConfig> setter
    ) {
        double value = DoubleArgumentType.getDouble(context, "value");
        if (!Double.isFinite(value) || !validator.test(value)) {
            return error(context, "illegal value: " + key + " must be finite and greater than 0.4");
        }
        return update(context, key, String.valueOf(value), setter);
    }

    private static int update(
        CommandContext<ServerCommandSource> context,
        String key,
        String value,
        Consumer<ModConfig> setter
    ) {
        ModConfig updated = HighSpeedRail.config().copy();
        setter.accept(updated);
        java.util.Optional<String> validationError = updated.validationError();
        if (validationError.isPresent()) {
            return error(context, "illegal value: " + validationError.get());
        }
        if (!HighSpeedRail.installConfig(updated)) {
            return error(context, "Could not write highspeedrail.json. The previous value remains active.");
        }
        context.getSource().sendFeedback(
            () -> Text.literal("highSpeedRail: " + key + "=" + value),
            true
        );
        return 1;
    }

    private static int error(CommandContext<ServerCommandSource> context, String message) {
        context.getSource().sendError(Text.literal(message));
        return 0;
    }

    private HighSpeedRailCommand() {
    }
}
