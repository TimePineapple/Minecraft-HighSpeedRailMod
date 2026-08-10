package com.timepineapple.highspeedrail.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import com.timepineapple.highspeedrail.HighSpeedRail;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("highspeedrail.json");
    private static final Path LEGACY_CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("highspeedminecart.json");
    private static final double FALLBACK_VANILLA_MAX_SPEED = 0.4;

    public boolean enable = true;
    public double maxSpeed = 1.2;
    public int activeBlocks = 16;

    public static ModConfig defaults() {
        return new ModConfig();
    }

    public static LoadResult load() {
        Path sourcePath = Files.exists(CONFIG_PATH) ? CONFIG_PATH : LEGACY_CONFIG_PATH;
        if (Files.notExists(sourcePath)) {
            ModConfig defaults = defaults();
            write(defaults);
            return new LoadResult(defaults, false);
        }

        try (Reader reader = Files.newBufferedReader(sourcePath)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            ModConfig parsed = GSON.fromJson(root, ModConfig.class);
            if (parsed == null || root == null) {
                throw new JsonParseException("configuration root is null");
            }

            migrateLegacyNames(root, parsed);
            parsed.validate();
            // Always rewrite with the canonical three-key schema. Gson ignores legacy keys while reading.
            write(parsed);
            return new LoadResult(parsed, true);
        } catch (IOException | RuntimeException exception) {
            HighSpeedRail.LOGGER.warn("Could not read {}. Safe defaults will be used: {}", sourcePath, exception.getMessage());
            ModConfig defaults = defaults();
            write(defaults);
            return new LoadResult(defaults, false);
        }
    }

    public double effectiveMaxSpeed(double vanillaMaxSpeed) {
        return Math.max(maxSpeed, vanillaMaxSpeed);
    }

    public double derivedSpeedChangePerTick(double vanillaMaxSpeed) {
        double effectiveMax = effectiveMaxSpeed(vanillaMaxSpeed);
        double change = (effectiveMax - vanillaMaxSpeed) * (effectiveMax + vanillaMaxSpeed)
            / (2.0 * activeBlocks);
        return Double.isFinite(change) ? change : effectiveMax - vanillaMaxSpeed;
    }

    public boolean save() {
        return write(this);
    }

    private void validate() {
        ModConfig defaults = defaults();

        if (!Double.isFinite(maxSpeed) || maxSpeed < FALLBACK_VANILLA_MAX_SPEED) {
            warn("maxSpeed", maxSpeed, defaults.maxSpeed);
            maxSpeed = defaults.maxSpeed;
        }
        if (activeBlocks < 1) {
            warn("activeBlocks", activeBlocks, defaults.activeBlocks);
            activeBlocks = defaults.activeBlocks;
        }
    }

    private static void migrateLegacyNames(JsonObject root, ModConfig config) {
        if (!root.has("enable") && root.has("enabled")) {
            config.enable = root.get("enabled").getAsBoolean();
        }
        if (!root.has("maxSpeed") && root.has("customMaxSpeed")) {
            config.maxSpeed = root.get("customMaxSpeed").getAsDouble();
        }
        if (!root.has("activeBlocks") && root.has("activationRailThreshold")) {
            config.activeBlocks = root.get("activationRailThreshold").getAsInt();
        }
    }

    private static boolean write(ModConfig config) {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(config, writer);
            }
            return true;
        } catch (IOException exception) {
            HighSpeedRail.LOGGER.warn("Could not write {}: {}", CONFIG_PATH, exception.getMessage());
            return false;
        }
    }

    private static void warn(String key, Object invalid, Object replacement) {
        HighSpeedRail.LOGGER.warn("Invalid config value {}={}; using {}", key, invalid, replacement);
    }

    public record LoadResult(ModConfig config, boolean loadedFromDisk) {
    }
}
