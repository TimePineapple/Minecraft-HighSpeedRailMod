package com.timepineapple.highspeedrail.minecart;

import com.timepineapple.highspeedrail.config.ModConfig;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.rule.GameRules;

public record PhysicsProfile(
    String startupController,
    boolean startupCaptured,
    double vanillaLandSpeed,
    double vanillaWaterSpeed,
    double acceleration,
    double brakeLandFlat,
    double brakeWaterFlat,
    double brakeLandSlope,
    double brakeWaterSlope,
    int activeBlocks,
    int effectiveActivationBlocks,
    double configuredMaxSpeed
) {
    private static final double DEFAULT_LAND_SPEED = 0.4;
    private static final double DEFAULT_WATER_SPEED = 0.2;
    private static final int END_HANDOFF_RAILS = 8;
    private static final double SLOPE_FACTOR = Math.sqrt(2.0);

    public static PhysicsProfile defaults(ModConfig config) {
        return create("default", false, DEFAULT_LAND_SPEED, DEFAULT_WATER_SPEED, config);
    }

    public static PhysicsProfile capture(ServerWorld world, ModConfig config) {
        boolean experimental = AbstractMinecartEntity.areMinecartImprovementsEnabled(world);
        double land = experimental
            ? world.getGameRules().getValue(GameRules.MAX_MINECART_SPEED) / 20.0
            : DEFAULT_LAND_SPEED;
        double water = experimental ? land * 0.5 : DEFAULT_WATER_SPEED;
        return create(experimental ? "experimental" : "default", true, land, water, config);
    }

    public PhysicsProfile withConfig(ModConfig config) {
        return create(
            startupController,
            startupCaptured,
            vanillaLandSpeed,
            vanillaWaterSpeed,
            config
        );
    }

    public double vanillaSpeed(boolean touchingWater) {
        return touchingWater ? vanillaWaterSpeed : vanillaLandSpeed;
    }

    public double vanillaTrackSpeed(boolean touchingWater, boolean slope) {
        return vanillaSpeed(touchingWater) * (slope ? SLOPE_FACTOR : 1.0);
    }

    public double effectiveMaxTrackSpeed(boolean touchingWater, boolean slope) {
        return Math.max(configuredMaxSpeed, vanillaTrackSpeed(touchingWater, slope));
    }

    public double brakeAcceleration(boolean touchingWater, boolean slope) {
        if (slope) {
            return touchingWater ? brakeWaterSlope : brakeLandSlope;
        }
        return touchingWater ? brakeWaterFlat : brakeLandFlat;
    }

    public static double experimentalRetention(boolean hasPassengers, boolean touchingWater) {
        double retention = hasPassengers ? 0.997 : 0.975;
        return touchingWater ? retention * 0.95 : retention;
    }

    public static double compensatedSpeed(double desiredSpeed, boolean hasPassengers, boolean touchingWater) {
        double retention = experimentalRetention(hasPassengers, touchingWater);
        double slowed = desiredSpeed * retention;
        return slowed + (desiredSpeed - slowed);
    }

    private static PhysicsProfile create(
        String controller,
        boolean captured,
        double vanillaLand,
        double vanillaWater,
        ModConfig config
    ) {
        int effective = effectiveActivationBlocks(config.activeBlocks);
        double acceleration = config.configuredAcceleration();
        return new PhysicsProfile(
            controller,
            captured,
            vanillaLand,
            vanillaWater,
            acceleration,
            brakingAcceleration(config.maxSpeed, vanillaLand, config.activeBlocks),
            brakingAcceleration(config.maxSpeed, vanillaWater, config.activeBlocks),
            brakingAcceleration(config.maxSpeed, vanillaLand * SLOPE_FACTOR, config.activeBlocks),
            brakingAcceleration(config.maxSpeed, vanillaWater * SLOPE_FACTOR, config.activeBlocks),
            config.activeBlocks,
            effective,
            config.maxSpeed
        );
    }

    static int effectiveActivationBlocks(int activeBlocks) {
        return activeBlocks > Integer.MAX_VALUE - END_HANDOFF_RAILS
            ? Integer.MAX_VALUE
            : activeBlocks + END_HANDOFF_RAILS;
    }

    static double brakingAcceleration(double maxSpeed, double vanillaSpeed, int activeBlocks) {
        if (!Double.isFinite(maxSpeed) || !Double.isFinite(vanillaSpeed)
            || maxSpeed <= vanillaSpeed || activeBlocks <= 0) {
            return 0.0;
        }
        return (maxSpeed - vanillaSpeed) * (maxSpeed + vanillaSpeed)
            / (2.0 * activeBlocks);
    }
}
