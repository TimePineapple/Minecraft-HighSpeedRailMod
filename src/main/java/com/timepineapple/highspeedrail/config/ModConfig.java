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
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("highspeedrail.json");
    private static final Path LEGACY_CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("highspeedminecart.json");
    private static final double FALLBACK_VANILLA_MAX_SPEED = 0.4;

    public boolean enable = true;
    public double maxSpeed = 1.2;
    public int activeBlocks = 16;

    public static ModConfig defaults() {
        return new ModConfig();
    }

    public static LoadResult load(ModConfig lastValidConfig) {
        Path sourcePath = Files.exists(CONFIG_PATH) ? CONFIG_PATH : LEGACY_CONFIG_PATH;
        if (Files.notExists(sourcePath)) {
            ModConfig defaults = defaults();
            if (!write(defaults)) {
                return new LoadResult(lastValidConfig, false, "Could not create highspeedrail.json.");
            }
            return new LoadResult(defaults, true, null);
        }

        try (Reader reader = Files.newBufferedReader(sourcePath)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            ModConfig parsed = GSON.fromJson(root, ModConfig.class);
            if (parsed == null || root == null) {
                throw new JsonParseException("configuration root is null");
            }

            migrateLegacyNames(root, parsed);
            Optional<String> validationError = parsed.validationError();
            if (validationError.isPresent()) {
                String message = validationError.get();
                HighSpeedRail.LOGGER.warn("illegal value in {}: {}", sourcePath, message);
                return new LoadResult(lastValidConfig, false, "illegal value: " + message);
            }
            // Always rewrite with the canonical three-key schema. Gson ignores legacy keys while reading.
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

    public double derivedBaseAcceleration(double vanillaMaxSpeed) {
        if (maxSpeed <= vanillaMaxSpeed) {
            return 0.0;
        }
        double acceleration = (maxSpeed - vanillaMaxSpeed) * (maxSpeed + vanillaMaxSpeed)
            / (2.0 * activeBlocks);
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
        return copy;
    }

    public Optional<String> validationError() {
        if (!Double.isFinite(maxSpeed) || maxSpeed <= FALLBACK_VANILLA_MAX_SPEED) {
            return Optional.of("maxSpeed must be finite and greater than 0.4");
        }
        if (activeBlocks < 1) {
            return Optional.of("activeBlocks must be at least 1");
        }
        return Optional.empty();
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

    public record LoadResult(ModConfig config, boolean success, String errorMessage) {
    }
}
