package com.timepineapple.highspeedrail.minecart;

import com.timepineapple.highspeedrail.HighSpeedRail;
import com.timepineapple.highspeedrail.config.ModConfig;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.vehicle.MinecartEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public final class MinecartSpeedManager {
    private static final double MIN_DIRECTION_SQUARED = 1.0E-8;
    private static final double SPEED_EPSILON = 0.005;

    public static void beforeVanillaTick(MinecartEntity cart, ServerWorld world) {
        MinecartSpeedState state = state(cart);
        ModConfig config = HighSpeedRail.config();
        double vanillaMax = cart.getController().getMaxSpeed(world);
        Vec3d velocity = cart.getVelocity();
        double speed = velocity.horizontalLength();

        state.setVanillaMaxSpeed(vanillaMax);
        state.setSpeedBeforeVanillaTick(speed);
        state.setVelocityBeforeVanillaTick(velocity);
        state.setPositionBeforeVanillaTick(cart.getEntityPos());

        if (!config.enable || !cart.isAlive() || !isFinite(velocity)) {
            transition(cart, state, MinecartSpeedMode.NORMAL, speed, vanillaMax, 0);
            return;
        }

        BlockPos railPos = cart.getRailOrMinecartPos();
        BlockState railState = world.isChunkLoaded(railPos) ? world.getBlockState(railPos) : null;
        boolean onRail = railState != null && railState.getBlock() instanceof AbstractRailBlock;
        if (!onRail || velocity.horizontalLengthSquared() < MIN_DIRECTION_SQUARED) {
            fallbackFromInvalidMotion(cart, state, speed, vanillaMax);
            return;
        }

        switch (state.mode()) {
            case NORMAL -> handleNormal(cart, world, railPos, railState, velocity, speed, vanillaMax, state, config);
            case ACCELERATING, HIGH_SPEED -> handleFast(cart, world, railPos, railState, velocity, speed, vanillaMax, state, config);
            case DECELERATING -> handleDecelerating(cart, world, railPos, railState, velocity, speed, vanillaMax, state, config);
        }
    }

    public static void afterVanillaTick(MinecartEntity cart, ServerWorld world) {
        MinecartSpeedState state = state(cart);
        ModConfig config = HighSpeedRail.config();
        if (!config.enable || state.mode() == MinecartSpeedMode.NORMAL || !cart.isAlive()) {
            return;
        }

        Vec3d afterVelocity = cart.getVelocity();
        if (!isFinite(afterVelocity)) {
            cart.setVelocity(Vec3d.ZERO);
            transition(cart, state, MinecartSpeedMode.NORMAL, 0.0, state.vanillaMaxSpeed(), state.poweredRailsAhead());
            return;
        }

        double teleportLimit = Math.max(4.0, config.effectiveMaxSpeed(state.vanillaMaxSpeed()) * 4.0);
        if (cart.getEntityPos().squaredDistanceTo(state.positionBeforeVanillaTick()) > teleportLimit * teleportLimit) {
            transition(cart, state, MinecartSpeedMode.NORMAL, afterVelocity.horizontalLength(), state.vanillaMaxSpeed(), state.poweredRailsAhead());
            return;
        }

        Vec3d beforeVelocity = state.velocityBeforeVanillaTick();
        double directionDot = beforeVelocity.x * afterVelocity.x + beforeVelocity.z * afterVelocity.z;
        if (directionDot < -SPEED_EPSILON * SPEED_EPSILON) {
            transition(cart, state, MinecartSpeedMode.DECELERATING, afterVelocity.horizontalLength(), state.vanillaMaxSpeed(), state.poweredRailsAhead());
        }

        double afterSpeed = afterVelocity.horizontalLength();
        double beforeSpeed = state.speedBeforeVanillaTick();
        if (afterSpeed < Math.min(state.vanillaMaxSpeed() - SPEED_EPSILON, beforeSpeed * 0.45)) {
            transition(cart, state, MinecartSpeedMode.NORMAL, afterSpeed, state.vanillaMaxSpeed(), state.poweredRailsAhead());
            return;
        }
        if (afterSpeed * afterSpeed < MIN_DIRECTION_SQUARED) {
            transition(cart, state, MinecartSpeedMode.NORMAL, afterSpeed, state.vanillaMaxSpeed(), state.poweredRailsAhead());
            return;
        }

        BlockPos currentRailPos = cart.getRailOrMinecartPos();
        boolean stillOnRail = world.isChunkLoaded(currentRailPos)
            && world.getBlockState(currentRailPos).getBlock() instanceof AbstractRailBlock;
        if (!stillOnRail && state.mode() != MinecartSpeedMode.DECELERATING) {
            transition(cart, state, MinecartSpeedMode.DECELERATING, afterSpeed, state.vanillaMaxSpeed(), state.poweredRailsAhead());
        }

        double effectiveMax = config.effectiveMaxSpeed(state.vanillaMaxSpeed());
        double speedChange = config.derivedSpeedChangePerTick(state.vanillaMaxSpeed());
        double targetSpeed = switch (state.mode()) {
            case ACCELERATING -> Math.min(beforeSpeed + speedChange, effectiveMax);
            case HIGH_SPEED -> effectiveMax;
            case DECELERATING -> Math.max(beforeSpeed - speedChange, state.vanillaMaxSpeed());
            case NORMAL -> afterSpeed;
        };

        double scale = targetSpeed / afterSpeed;
        cart.setVelocity(afterVelocity.x * scale, afterVelocity.y, afterVelocity.z * scale);

        if (state.mode() == MinecartSpeedMode.ACCELERATING && targetSpeed >= effectiveMax - SPEED_EPSILON) {
            transition(cart, state, MinecartSpeedMode.HIGH_SPEED, targetSpeed, state.vanillaMaxSpeed(), state.poweredRailsAhead());
        } else if (state.mode() == MinecartSpeedMode.DECELERATING && targetSpeed <= state.vanillaMaxSpeed() + SPEED_EPSILON) {
            transition(cart, state, MinecartSpeedMode.NORMAL, targetSpeed, state.vanillaMaxSpeed(), state.poweredRailsAhead());
        }
    }

    public static double speedCap(MinecartEntity cart, double vanillaReturnValue) {
        ModConfig config = HighSpeedRail.config();
        MinecartSpeedState state = state(cart);
        if (!config.enable || state.mode() == MinecartSpeedMode.NORMAL) {
            return vanillaReturnValue;
        }
        return config.effectiveMaxSpeed(Math.max(vanillaReturnValue, state.vanillaMaxSpeed()));
    }

    private static void handleNormal(
        MinecartEntity cart,
        ServerWorld world,
        BlockPos railPos,
        BlockState railState,
        Vec3d velocity,
        double speed,
        double vanillaMax,
        MinecartSpeedState state,
        ModConfig config
    ) {
        if (speed < vanillaMax - SPEED_EPSILON) {
            state.setPoweredRailsAhead(0);
            return;
        }
        if (!RailPathScanner.isPoweredRail(railState)) {
            state.setPoweredRailsAhead(0);
            return;
        }
        RailPathScanner.ScanResult scan = scan(world, railPos, velocity, config.activeBlocks);
        state.setPoweredRailsAhead(scan.poweredRailCount());
        if (scan.poweredRailCount() >= config.activeBlocks) {
            transition(cart, state, MinecartSpeedMode.ACCELERATING, speed, vanillaMax, scan.poweredRailCount());
        }
    }

    private static void handleFast(
        MinecartEntity cart,
        ServerWorld world,
        BlockPos railPos,
        BlockState railState,
        Vec3d velocity,
        double speed,
        double vanillaMax,
        MinecartSpeedState state,
        ModConfig config
    ) {
        RailPathScanner.ScanResult scan = scan(world, railPos, velocity, config.activeBlocks);
        state.setPoweredRailsAhead(scan.poweredRailCount());
        boolean currentPowered = RailPathScanner.isPoweredRail(railState);

        if (!currentPowered || scan.poweredRailCount() < config.activeBlocks) {
            transition(cart, state, MinecartSpeedMode.DECELERATING, speed, vanillaMax, scan.poweredRailCount());
        }
    }

    private static void handleDecelerating(
        MinecartEntity cart,
        ServerWorld world,
        BlockPos railPos,
        BlockState railState,
        Vec3d velocity,
        double speed,
        double vanillaMax,
        MinecartSpeedState state,
        ModConfig config
    ) {
        if (speed <= vanillaMax + SPEED_EPSILON) {
            transition(cart, state, MinecartSpeedMode.NORMAL, speed, vanillaMax, 0);
            return;
        }
        RailPathScanner.ScanResult scan = scan(world, railPos, velocity, config.activeBlocks);
        state.setPoweredRailsAhead(scan.poweredRailCount());
        if (speed >= vanillaMax - SPEED_EPSILON
            && RailPathScanner.isPoweredRail(railState)
            && scan.poweredRailCount() >= config.activeBlocks) {
            transition(cart, state, MinecartSpeedMode.ACCELERATING, speed, vanillaMax, scan.poweredRailCount());
        }
    }

    private static RailPathScanner.ScanResult scan(
        ServerWorld world,
        BlockPos railPos,
        Vec3d velocity,
        int stopAfter
    ) {
        return RailPathScanner.countPoweredRailsAhead(world, railPos, velocity, stopAfter);
    }

    private static void fallbackFromInvalidMotion(MinecartEntity cart, MinecartSpeedState state, double speed, double vanillaMax) {
        MinecartSpeedMode fallback = speed > vanillaMax ? MinecartSpeedMode.DECELERATING : MinecartSpeedMode.NORMAL;
        transition(cart, state, fallback, speed, vanillaMax, 0);
    }

    private static MinecartSpeedState state(MinecartEntity cart) {
        return ((MinecartSpeedStateHolder) cart).highSpeedRail$getSpeedState();
    }

    private static void transition(
        MinecartEntity cart,
        MinecartSpeedState state,
        MinecartSpeedMode next,
        double speed,
        double vanillaMax,
        int poweredAhead
    ) {
        MinecartSpeedMode previous = state.mode();
        state.setMode(next);
        if (previous != next) {
            HighSpeedRail.LOGGER.debug(
                "[{} -> {}] cart={} speed={} vanillaMax={} poweredAhead={}",
                previous,
                next,
                cart.getUuid(),
                String.format("%.4f", speed),
                String.format("%.4f", vanillaMax),
                poweredAhead
            );
        }
    }

    private static boolean isFinite(Vec3d velocity) {
        return Double.isFinite(velocity.x) && Double.isFinite(velocity.y) && Double.isFinite(velocity.z);
    }

    private MinecartSpeedManager() {
    }
}
