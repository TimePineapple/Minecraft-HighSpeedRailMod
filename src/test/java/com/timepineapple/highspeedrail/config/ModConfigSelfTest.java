package com.timepineapple.highspeedrail.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

public final class ModConfigSelfTest {
    public static void main(String[] args) {
        testLegacyThreeKeyMigration();
        testLegacyActiveBlocksMigration();
        testAccelerationSecondsMustBeAJsonInteger();
        testValidationLimits();
    }

    private static void testLegacyThreeKeyMigration() {
        JsonObject root = baseConfig();
        ModConfig parsed = ModConfig.parse(root);
        require(parsed.accelerationSeconds == 5, "missing accelerationSeconds must migrate to 5");
        require(parsed.activeBlocks == 16, "normal activeBlocks must remain unchanged");
    }

    private static void testLegacyActiveBlocksMigration() {
        for (int legacyValue = 1; legacyValue < ModConfig.MIN_ACTIVE_BLOCKS; legacyValue++) {
            JsonObject root = baseConfig();
            root.addProperty("activeBlocks", legacyValue);
            ModConfig parsed = ModConfig.parse(root);
            require(parsed.activeBlocks == ModConfig.MIN_ACTIVE_BLOCKS,
                "legacy positive activeBlocks below 8 must migrate to 8");
            require(parsed.validationError().isEmpty(), "migrated activeBlocks must validate");
        }
    }

    private static void testAccelerationSecondsMustBeAJsonInteger() {
        JsonObject valid = baseConfig();
        valid.addProperty("accelerationSeconds", 5);
        require(ModConfig.parse(valid).accelerationSeconds == 5, "JSON integer must parse");

        JsonObject decimal = baseConfig();
        decimal.addProperty("accelerationSeconds", 5.5);
        requireThrows(decimal, "JSON decimal must be rejected");

        JsonObject numericString = baseConfig();
        numericString.addProperty("accelerationSeconds", "5");
        requireThrows(numericString, "numeric string must be rejected");
    }

    private static void testValidationLimits() {
        JsonObject invalidSeconds = baseConfig();
        invalidSeconds.addProperty("accelerationSeconds", 0);
        require(ModConfig.parse(invalidSeconds).validationError().isPresent(),
            "accelerationSeconds below 1 must fail validation");

        JsonObject invalidBlocks = baseConfig();
        invalidBlocks.addProperty("activeBlocks", 0);
        require(ModConfig.parse(invalidBlocks).validationError().isPresent(),
            "non-positive activeBlocks must fail validation");
    }

    private static JsonObject baseConfig() {
        JsonObject root = new JsonObject();
        root.addProperty("enable", true);
        root.addProperty("maxSpeed", 1.2);
        root.addProperty("activeBlocks", 16);
        return root;
    }

    private static void requireThrows(JsonObject root, String message) {
        try {
            ModConfig.parse(root);
        } catch (JsonParseException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private ModConfigSelfTest() {
    }
}
