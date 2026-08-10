package com.timepineapple.highspeedrail.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.timepineapple.highspeedrail.HighSpeedRail;
import com.timepineapple.highspeedrail.config.ModConfig;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionCheck;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
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
                .then(CommandManager.literal("set")
                    .then(CommandManager.literal("enable")
                        .then(CommandManager.argument("value", BoolArgumentType.bool())
                            .executes(context -> update(context, "enable", String.valueOf(BoolArgumentType.getBool(context, "value")),
                                config -> config.enable = BoolArgumentType.getBool(context, "value")))))
                    .then(CommandManager.literal("maxSpeed")
                        .then(CommandManager.argument("value", DoubleArgumentType.doubleArg(0.4))
                            .executes(context -> updateDouble(context, "maxSpeed", value -> value >= 0.4,
                                config -> config.maxSpeed = DoubleArgumentType.getDouble(context, "value")))))
                    .then(CommandManager.literal("activeBlocks")
                        .then(CommandManager.argument("value", IntegerArgumentType.integer(1))
                            .executes(context -> update(context, "activeBlocks",
                                String.valueOf(IntegerArgumentType.getInteger(context, "value")),
                                config -> config.activeBlocks = IntegerArgumentType.getInteger(context, "value")))))
                )
                .then(CommandManager.literal("reload").executes(context -> {
                    boolean loaded = HighSpeedRail.reloadConfig();
                    context.getSource().sendFeedback(
                        () -> Text.literal(loaded
                            ? "highSpeedRail configuration reloaded."
                            : "highSpeedRail is using safe defaults; check the server log."),
                        true
                    );
                    return loaded ? 1 : 0;
                }))
        ));
    }

    private static int showConfig(CommandContext<ServerCommandSource> context) {
        ModConfig config = HighSpeedRail.config();
        context.getSource().sendFeedback(() -> Text.literal(
            "highSpeedRail configuration:\n"
                + "enable=" + config.enable + "\n"
                + "maxSpeed=" + config.maxSpeed + "\n"
                + "activeBlocks=" + config.activeBlocks
        ), false);
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
            return error(context, key + " has an invalid value.");
        }
        return update(context, key, String.valueOf(value), setter);
    }

    private static int update(
        CommandContext<ServerCommandSource> context,
        String key,
        String value,
        Consumer<ModConfig> setter
    ) {
        ModConfig config = HighSpeedRail.config();
        setter.accept(config);
        if (!config.save()) {
            return error(context, "The live value changed, but writing highspeedrail.json failed. Check the server log.");
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
