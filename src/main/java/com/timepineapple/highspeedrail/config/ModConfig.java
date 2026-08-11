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
import java.util.Optional;

public final class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final double FALLBACK_VANILLA_MAX_SPEED = 0.4;
    private static final int DEFAULT_ACCELERATION_SECONDS = 5;
    private static final int TICKS_PER_SECOND = 20;
    public static final int MIN_ACTIVE_BLOCKS = 8;

    public boolean enable = true;
    public double maxSpeed = 1.2;
    public int activeBlocks = 16;
    public int accelerationSeconds = DEFAULT_ACCELERATION_SECONDS;

    public static ModConfig defaults() {
        return new ModConfig();
    }

    public static LoadResult load(ModConfig lastValidConfig) {
        Path configPath = configPath();
        Path legacyConfigPath = legacyConfigPath();
        Path sourcePath = Files.exists(configPath) ? configPath : legacyConfigPath;
        if (Files.notExists(sourcePath)) {
            ModConfig defaults = defaults();
            if (!write(defaults)) {
                return new LoadResult(lastValidConfig, false, "Could not create highspeedrail.json.");
            }
            return new LoadResult(defaults, true, null);
        }

        try (Reader reader = Files.newBufferedReader(sourcePath)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            ModConfig parsed = parse(root);
            Optional<String> validationError = parsed.validationError();
            if (validationError.isPresent()) {
                String message = validationError.get();
                HighSpeedRail.LOGGER.warn("illegal value in {}: {}", sourcePath, message);
                return new LoadResult(lastValidConfig, false, "illegal value: " + message);
            }
            // Always rewrite with the canonical four-key schema. Gson ignores legacy keys while reading.
            if (!write(parsed)) {
                return new LoadResult(lastValidConfig, false, "Could not write highspeedrail.json.");
            }
            return new LoadResult(parsed, true, null);
        } catch (IOException | RuntimeException exception) {
            HighSpeedRail.LOGGER.warn("Could not read {}. The last valid configuration remains active: {}", sourcePath, exception.getMessage());
            return new LoadResult(lastValidConfig, false, "Could not read highspeedrail.json: " + exception.getMessage());
        }
    }

    public double effectiveMaxSpeed(double vanillaMaxSpeed) {
        return Math.max(maxSpeed, vanillaMaxSpeed);
    }

    public double configuredAcceleration() {
        double acceleration = maxSpeed / (TICKS_PER_SECOND * (double) accelerationSeconds);
        return Double.isFinite(acceleration) ? acceleration : 0.0;
    }

    public boolean save() {
        return write(this);
    }

    public ModConfig copy() {
        ModConfig copy = new ModConfig();
        copy.enable = enable;
        copy.maxSpeed = maxSpeed;
        copy.activeBlocks = activeBlocks;
        copy.accelerationSeconds = accelerationSeconds;
        return copy;
    }

    public Optional<String> validationError() {
        if (!Double.isFinite(maxSpeed) || maxSpeed <= FALLBACK_VANILLA_MAX_SPEED) {
            return Optional.of("maxSpeed must be finite and greater than 0.4");
        }
        if (activeBlocks < MIN_ACTIVE_BLOCKS) {
            return Optional.of("activeBlocks must be at least " + MIN_ACTIVE_BLOCKS);
        }
        if (accelerationSeconds < 1) {
            return Optional.of("accelerationSeconds must be an integer of at least 1");
        }
        return Optional.empty();
    }

    static ModConfig parse(JsonObject root) {
        if (root == null) {
            throw new JsonParseException("configuration root is null");
        }
        validateIntegerField(root, "accelerationSeconds");
        ModConfig parsed = GSON.fromJson(root, ModConfig.class);
        if (parsed == null) {
            throw new JsonParseException("configuration root is null");
        }
        migrateLegacyNames(root, parsed);
        return parsed;
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
        if (!root.has("accelerationSeconds")) {
            config.accelerationSeconds = DEFAULT_ACCELERATION_SECONDS;
        }
        if (config.activeBlocks > 0 && config.activeBlocks < MIN_ACTIVE_BLOCKS) {
            config.activeBlocks = MIN_ACTIVE_BLOCKS;
        }
    }

    private static void validateIntegerField(JsonObject root, String name) {
        if (!root.has(name)) {
            return;
        }
        String encoded = root.get(name).toString();
        if (!encoded.matches("-?(0|[1-9][0-9]*)")) {
            throw new JsonParseException(name + " must be a JSON integer");
        }
    }

    private static boolean write(ModConfig config) {
        Path configPath = configPath();
        try {
            Files.createDirectories(configPath.getParent());
            try (Writer writer = Files.newBufferedWriter(configPath)) {
                GSON.toJson(config, writer);
            }
            return true;
        } catch (IOException exception) {
            HighSpeedRail.LOGGER.warn("Could not write {}: {}", configPath, exception.getMessage());
            return false;
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("highspeedrail.json");
    }

    private static Path legacyConfigPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("highspeedminecart.json");
    }

    public record LoadResult(ModConfig config, boolean success, String errorMessage) {
    }
}
