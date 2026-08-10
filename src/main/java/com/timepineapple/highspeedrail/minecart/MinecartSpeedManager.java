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
    private static final double SPEED_EPSILON = 1.0E-6;
    private static final int EXTRA_MEASURE_STEPS = 8;

    public static void beforeVanillaTick(MinecartEntity cart, ServerWorld world) {
        MinecartSpeedState state = state(cart);
        ModConfig config = HighSpeedRail.config();
        double vanillaMax = cart.getController().getMaxSpeed(world);
        Vec3d velocity = cart.getVelocity();
        Vec3d position = cart.getEntityPos();
        BlockPos railPos = cart.getRailOrMinecartPos();
        BlockState railState = world.isChunkLoaded(railPos) ? world.getBlockState(railPos) : null;
        RailPathScanner.RailFrame frame = railState == null ? null : RailPathScanner.frame(railPos, railState, position, velocity);
        double speed = frame == null ? velocity.horizontalLength() : RailPathScanner.railSpeed(velocity, frame.tangent());

        state.setVanillaMaxSpeed(vanillaMax);
        state.setSpeedBeforeVanillaTick(speed);
        state.setVelocityBeforeVanillaTick(velocity);
        state.setPositionBeforeVanillaTick(position);
        state.setRailPosBeforeVanillaTick(frame == null ? Long.MIN_VALUE : railPos.asLong());
        state.setTangentBeforeVanillaTick(frame == null ? Vec3d.ZERO : frame.tangent());

        if (!config.enable || !cart.isAlive() || !isFinite(velocity)) {
            transition(cart, state, MinecartSpeedMode.NORMAL, speed, vanillaMax, 0.0);
            return;
        }

        double baseAcceleration = config.derivedBaseAcceleration(vanillaMax);
        if (config.maxSpeed <= vanillaMax + SPEED_EPSILON || baseAcceleration <= 0.0) {
            transition(cart, state, MinecartSpeedMode.NORMAL, speed, vanillaMax, 0.0);
            return;
        }

        if (frame == null || velocity.horizontalLengthSquared() < MIN_DIRECTION_SQUARED) {
            handleMissingRailOrDirection(cart, state, speed, vanillaMax, baseAcceleration);
            return;
        }

        double brakeDistance = SpeedProfile.distance(Math.max(speed, vanillaMax), vanillaMax, baseAcceleration);
        double resumeDistance = SpeedProfile.distance(Math.min(speed, config.maxSpeed), config.maxSpeed, baseAcceleration)
            + config.activeBlocks;
        double scanLimit = Math.max(config.activeBlocks + config.maxSpeed + 2.0,
            Math.max(brakeDistance, resumeDistance) + config.maxSpeed + 2.0);
        RailPathScanner.PoweredPath poweredPath = RailPathScanner.scanPoweredDistanceAhead(
            world, railPos, position, velocity, scanLimit
        );
        double remainingPoweredDistance = poweredPath.stoppedAtUnloadedChunk() ? 0.0 : poweredPath.distance();
        state.setPoweredDistanceBeforeVanillaTick(remainingPoweredDistance);

        if (!state.parametersMatch(config.maxSpeed, config.activeBlocks, vanillaMax)) {
            reanchorForParameters(cart, state, speed, vanillaMax, config.maxSpeed, baseAcceleration);
            state.setParameters(config.maxSpeed, config.activeBlocks, vanillaMax);
        }

        boolean currentPowered = RailPathScanner.isPoweredRail(railState);
        switch (state.mode()) {
            case NORMAL -> {
                if (currentPowered && remainingPoweredDistance > config.activeBlocks + SPEED_EPSILON) {
                    startAcceleration(cart, state, speed, config.maxSpeed, baseAcceleration, vanillaMax, remainingPoweredDistance);
                }
            }
            case ACCELERATING, HIGH_SPEED -> {
                double required = SpeedProfile.distance(Math.max(speed, vanillaMax), vanillaMax, baseAcceleration);
                if (!currentPowered || remainingPoweredDistance <= required + SPEED_EPSILON) {
                    startDeceleration(cart, state, speed, vanillaMax, baseAcceleration, true, vanillaMax, remainingPoweredDistance);
                }
            }
            case DECELERATING -> {
                if (state.railEndBrake()) {
                    double requiredToResume = SpeedProfile.distance(Math.min(speed, config.maxSpeed), config.maxSpeed, baseAcceleration)
                        + config.activeBlocks;
                    if (currentPowered && remainingPoweredDistance > requiredToResume + SPEED_EPSILON) {
                        startAcceleration(cart, state, speed, config.maxSpeed, baseAcceleration, vanillaMax, remainingPoweredDistance);
                    }
                } else {
                    double requiredBrake = SpeedProfile.distance(Math.max(speed, vanillaMax), vanillaMax, baseAcceleration);
                    if (!currentPowered || remainingPoweredDistance <= requiredBrake + SPEED_EPSILON) {
                        startDeceleration(cart, state, speed, vanillaMax, baseAcceleration, true, vanillaMax, remainingPoweredDistance);
                    }
                }
            }
            case BRAKE_HOLD -> {
                double requiredToResume = SpeedProfile.distance(vanillaMax, config.maxSpeed, baseAcceleration)
                    + config.activeBlocks;
                if (!currentPowered) {
                    transition(cart, state, MinecartSpeedMode.NORMAL, speed, vanillaMax, remainingPoweredDistance);
                } else if (remainingPoweredDistance > requiredToResume + SPEED_EPSILON) {
                    startAcceleration(cart, state, speed, config.maxSpeed, baseAcceleration, vanillaMax, remainingPoweredDistance);
                }
            }
        }
    }

    public static void afterVanillaTick(MinecartEntity cart, ServerWorld world) {
        MinecartSpeedState state = state(cart);
        ModConfig config = HighSpeedRail.config();
        if (!config.enable || state.mode() == MinecartSpeedMode.NORMAL || !cart.isAlive()) {
            return;
        }

        Vec3d afterVelocity = cart.getVelocity();
        Vec3d afterPosition = cart.getEntityPos();
        if (!isFinite(afterVelocity) || !isFinite(afterPosition)) {
            cart.setVelocity(Vec3d.ZERO);
            transition(cart, state, MinecartSpeedMode.NORMAL, 0.0, state.vanillaMaxSpeed(), 0.0);
            return;
        }

        double teleportLimit = Math.max(4.0, config.effectiveMaxSpeed(state.vanillaMaxSpeed()) * 6.0);
        if (afterPosition.squaredDistanceTo(state.positionBeforeVanillaTick()) > teleportLimit * teleportLimit) {
            transition(cart, state, MinecartSpeedMode.NORMAL, afterVelocity.horizontalLength(), state.vanillaMaxSpeed(), 0.0);
            return;
        }

        Vec3d beforeVelocity = state.velocityBeforeVanillaTick();
        double directionDot = beforeVelocity.x * afterVelocity.x + beforeVelocity.z * afterVelocity.z;
        if (directionDot < -SPEED_EPSILON * SPEED_EPSILON) {
            transition(cart, state, MinecartSpeedMode.NORMAL, afterVelocity.horizontalLength(), state.vanillaMaxSpeed(), 0.0);
            return;
        }

        BlockPos endingRailPos = cart.getRailOrMinecartPos();
        BlockState endingRailState = world.isChunkLoaded(endingRailPos) ? world.getBlockState(endingRailPos) : null;
        RailPathScanner.RailFrame endingFrame = endingRailState == null
            ? null
            : RailPathScanner.frame(endingRailPos, endingRailState, afterPosition, afterVelocity);
        RailPathScanner.TravelMeasure travelMeasure = measureTravel(world, state, endingRailPos, afterPosition, afterVelocity);

        double travelled;
        Vec3d tangent;
        boolean onRail = endingFrame != null && travelMeasure.valid();
        if (onRail) {
            travelled = travelMeasure.distance();
            tangent = travelMeasure.tangent();
        } else if (state.mode() == MinecartSpeedMode.DECELERATING || state.mode() == MinecartSpeedMode.BRAKE_HOLD) {
            Vec3d displacement = afterPosition.subtract(state.positionBeforeVanillaTick());
            travelled = displacement.horizontalLength();
            tangent = Vec3d.ZERO;
        } else {
            transition(cart, state, MinecartSpeedMode.NORMAL, afterVelocity.horizontalLength(), state.vanillaMaxSpeed(), 0.0);
            return;
        }

        double afterSpeed = onRail
            ? RailPathScanner.railSpeed(afterVelocity, tangent)
            : afterVelocity.horizontalLength();
        if (afterSpeed < Math.min(state.vanillaMaxSpeed() - SPEED_EPSILON, state.speedBeforeVanillaTick() * 0.45)) {
            transition(cart, state, MinecartSpeedMode.NORMAL, afterSpeed, state.vanillaMaxSpeed(), 0.0);
            return;
        }
        if (afterSpeed * afterSpeed < MIN_DIRECTION_SQUARED) {
            transition(cart, state, MinecartSpeedMode.NORMAL, afterSpeed, state.vanillaMaxSpeed(), 0.0);
            return;
        }

        double baseAcceleration = config.derivedBaseAcceleration(state.vanillaMaxSpeed());
        if (baseAcceleration <= 0.0) {
            transition(cart, state, MinecartSpeedMode.NORMAL, afterSpeed, state.vanillaMaxSpeed(), 0.0);
            return;
        }

        double phaseTravel = splitAtBrakeBoundaryIfNeeded(
            cart, state, travelled, config.maxSpeed, state.vanillaMaxSpeed(), baseAcceleration
        );
        double targetSpeed = advancePhase(cart, state, phaseTravel, config.maxSpeed, state.vanillaMaxSpeed());
        applyTargetVelocity(cart, afterVelocity, tangent, targetSpeed, onRail);
    }

    public static double speedCap(MinecartEntity cart, double vanillaReturnValue) {
        ModConfig config = HighSpeedRail.config();
        MinecartSpeedState state = state(cart);
        if (!config.enable || state.mode() == MinecartSpeedMode.NORMAL || config.maxSpeed <= vanillaReturnValue) {
            return vanillaReturnValue;
        }
        return config.effectiveMaxSpeed(Math.max(vanillaReturnValue, state.vanillaMaxSpeed()));
    }

    private static RailPathScanner.TravelMeasure measureTravel(
        ServerWorld world,
        MinecartSpeedState state,
        BlockPos endingRailPos,
        Vec3d endingPosition,
        Vec3d afterVelocity
    ) {
        if (state.railPosBeforeVanillaTick() == Long.MIN_VALUE) {
            return new RailPathScanner.TravelMeasure(0.0, Vec3d.ZERO, false);
        }
        BlockPos startingRailPos = BlockPos.fromLong(state.railPosBeforeVanillaTick());
        double worldDistance = endingPosition.distanceTo(state.positionBeforeVanillaTick());
        int maximumSteps = Math.max(EXTRA_MEASURE_STEPS, (int) Math.ceil(worldDistance * 3.0) + EXTRA_MEASURE_STEPS);
        return RailPathScanner.measureRailTravel(
            world,
            startingRailPos,
            state.positionBeforeVanillaTick(),
            endingRailPos,
            endingPosition,
            state.velocityBeforeVanillaTick().horizontalLengthSquared() < MIN_DIRECTION_SQUARED
                ? afterVelocity
                : state.velocityBeforeVanillaTick(),
            maximumSteps
        );
    }

    private static double splitAtBrakeBoundaryIfNeeded(
        MinecartEntity cart,
        MinecartSpeedState state,
        double travelled,
        double maxSpeed,
        double vanillaSpeed,
        double baseAcceleration
    ) {
        if ((state.mode() != MinecartSpeedMode.ACCELERATING && state.mode() != MinecartSpeedMode.HIGH_SPEED)
            || travelled <= SPEED_EPSILON) {
            return travelled;
        }

        double remaining = state.poweredDistanceBeforeVanillaTick();
        double startValue = brakeBoundaryValue(state, 0.0, remaining, maxSpeed, vanillaSpeed, baseAcceleration);
        double endValue = brakeBoundaryValue(state, travelled, remaining, maxSpeed, vanillaSpeed, baseAcceleration);
        if (startValue <= 0.0 || endValue > 0.0) {
            return travelled;
        }

        double low = 0.0;
        double high = travelled;
        for (int iteration = 0; iteration < 32; iteration++) {
            double middle = (low + high) * 0.5;
            if (brakeBoundaryValue(state, middle, remaining, maxSpeed, vanillaSpeed, baseAcceleration) > 0.0) {
                low = middle;
            } else {
                high = middle;
            }
        }

        double accelerationDistance = (low + high) * 0.5;
        double boundarySpeed = speedAfterDistance(state, accelerationDistance, maxSpeed);
        startDeceleration(
            cart,
            state,
            boundarySpeed,
            vanillaSpeed,
            baseAcceleration,
            true,
            vanillaSpeed,
            Math.max(0.0, remaining - accelerationDistance)
        );
        return Math.max(0.0, travelled - accelerationDistance);
    }

    private static double brakeBoundaryValue(
        MinecartSpeedState state,
        double distance,
        double remaining,
        double maxSpeed,
        double vanillaSpeed,
        double baseAcceleration
    ) {
        double speed = speedAfterDistance(state, distance, maxSpeed);
        double brakingDistance = SpeedProfile.distance(Math.max(speed, vanillaSpeed), vanillaSpeed, baseAcceleration);
        return remaining - distance - brakingDistance;
    }

    private static double speedAfterDistance(MinecartSpeedState state, double distance, double maxSpeed) {
        if (state.mode() == MinecartSpeedMode.HIGH_SPEED) {
            return maxSpeed;
        }
        return SpeedProfile.speedAt(
            state.phaseStartSpeed(),
            state.phaseTargetSpeed(),
            state.phaseProgress() + distance,
            state.phaseDistance()
        );
    }

    private static double advancePhase(
        MinecartEntity cart,
        MinecartSpeedState state,
        double travelled,
        double maxSpeed,
        double vanillaSpeed
    ) {
        return switch (state.mode()) {
            case ACCELERATING -> {
                state.setPhaseProgress(state.phaseProgress() + travelled);
                double target = SpeedProfile.speedAt(
                    state.phaseStartSpeed(), state.phaseTargetSpeed(), state.phaseProgress(), state.phaseDistance()
                );
                if (state.phaseProgress() >= state.phaseDistance() - SPEED_EPSILON) {
                    transition(cart, state, MinecartSpeedMode.HIGH_SPEED, maxSpeed, vanillaSpeed, state.poweredDistanceBeforeVanillaTick());
                    yield maxSpeed;
                }
                yield target;
            }
            case HIGH_SPEED -> maxSpeed;
            case DECELERATING -> {
                state.setPhaseProgress(state.phaseProgress() + travelled);
                double target = SpeedProfile.speedAt(
                    state.phaseStartSpeed(), state.phaseTargetSpeed(), state.phaseProgress(), state.phaseDistance()
                );
                if (state.phaseProgress() >= state.phaseDistance() - SPEED_EPSILON) {
                    MinecartSpeedMode next = state.railEndBrake() ? MinecartSpeedMode.BRAKE_HOLD : MinecartSpeedMode.HIGH_SPEED;
                    transition(cart, state, next, state.phaseTargetSpeed(), vanillaSpeed, state.poweredDistanceBeforeVanillaTick());
                    yield state.phaseTargetSpeed();
                }
                yield target;
            }
            case BRAKE_HOLD -> vanillaSpeed;
            case NORMAL -> state.speedBeforeVanillaTick();
        };
    }

    private static void applyTargetVelocity(
        MinecartEntity cart,
        Vec3d vanillaVelocity,
        Vec3d tangent,
        double targetSpeed,
        boolean onRail
    ) {
        if (onRail && tangent.lengthSquared() >= MIN_DIRECTION_SQUARED) {
            cart.setVelocity(tangent.multiply(targetSpeed));
            return;
        }

        double horizontalSpeed = vanillaVelocity.horizontalLength();
        if (horizontalSpeed * horizontalSpeed < MIN_DIRECTION_SQUARED) {
            return;
        }
        double scale = targetSpeed / horizontalSpeed;
        cart.setVelocity(vanillaVelocity.x * scale, vanillaVelocity.y, vanillaVelocity.z * scale);
    }

    private static void reanchorForParameters(
        MinecartEntity cart,
        MinecartSpeedState state,
        double speed,
        double vanillaSpeed,
        double maxSpeed,
        double baseAcceleration
    ) {
        if (state.mode() == MinecartSpeedMode.NORMAL) {
            return;
        }
        if (state.railEndBrake() || state.mode() == MinecartSpeedMode.BRAKE_HOLD) {
            if (speed > vanillaSpeed + SPEED_EPSILON) {
                startDeceleration(cart, state, speed, vanillaSpeed, baseAcceleration, true, vanillaSpeed, 0.0);
            } else {
                transition(cart, state, MinecartSpeedMode.BRAKE_HOLD, vanillaSpeed, vanillaSpeed, 0.0);
            }
        } else if (speed > maxSpeed + SPEED_EPSILON) {
            startDeceleration(cart, state, speed, maxSpeed, baseAcceleration, false, vanillaSpeed, 0.0);
        } else if (speed < maxSpeed - SPEED_EPSILON) {
            startAcceleration(cart, state, speed, maxSpeed, baseAcceleration, vanillaSpeed, 0.0);
        } else {
            transition(cart, state, MinecartSpeedMode.HIGH_SPEED, speed, vanillaSpeed, 0.0);
        }
    }

    private static void startAcceleration(
        MinecartEntity cart,
        MinecartSpeedState state,
        double startSpeed,
        double maxSpeed,
        double baseAcceleration,
        double vanillaSpeed,
        double remainingPoweredDistance
    ) {
        if (startSpeed >= maxSpeed - SPEED_EPSILON) {
            transition(cart, state, MinecartSpeedMode.HIGH_SPEED, startSpeed, vanillaSpeed, remainingPoweredDistance);
            return;
        }
        state.setPhase(startSpeed, maxSpeed, SpeedProfile.distance(startSpeed, maxSpeed, baseAcceleration), false);
        transition(cart, state, MinecartSpeedMode.ACCELERATING, startSpeed, vanillaSpeed, remainingPoweredDistance);
    }

    private static void startDeceleration(
        MinecartEntity cart,
        MinecartSpeedState state,
        double startSpeed,
        double targetSpeed,
        double baseAcceleration,
        boolean railEndBrake,
        double vanillaSpeed,
        double remainingPoweredDistance
    ) {
        if (startSpeed <= targetSpeed + SPEED_EPSILON) {
            MinecartSpeedMode next = railEndBrake ? MinecartSpeedMode.BRAKE_HOLD : MinecartSpeedMode.HIGH_SPEED;
            transition(cart, state, next, targetSpeed, vanillaSpeed, remainingPoweredDistance);
            return;
        }
        state.setPhase(startSpeed, targetSpeed, SpeedProfile.distance(startSpeed, targetSpeed, baseAcceleration), railEndBrake);
        transition(cart, state, MinecartSpeedMode.DECELERATING, startSpeed, vanillaSpeed, remainingPoweredDistance);
    }

    private static void handleMissingRailOrDirection(
        MinecartEntity cart,
        MinecartSpeedState state,
        double speed,
        double vanillaSpeed,
        double baseAcceleration
    ) {
        state.setPoweredDistanceBeforeVanillaTick(0.0);
        if (speed > vanillaSpeed + SPEED_EPSILON
            && (state.mode() == MinecartSpeedMode.ACCELERATING || state.mode() == MinecartSpeedMode.HIGH_SPEED)) {
            startDeceleration(cart, state, speed, vanillaSpeed, baseAcceleration, true, vanillaSpeed, 0.0);
        } else if (speed <= vanillaSpeed + SPEED_EPSILON || state.mode() == MinecartSpeedMode.BRAKE_HOLD) {
            transition(cart, state, MinecartSpeedMode.NORMAL, speed, vanillaSpeed, 0.0);
        }
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
        double poweredDistance
    ) {
        MinecartSpeedMode previous = state.mode();
        state.setMode(next);
        if (previous != next) {
            HighSpeedRail.LOGGER.debug(
                "[{} -> {}] cart={} speed={} vanillaMax={} poweredDistance={}",
                previous,
                next,
                cart.getUuid(),
                String.format("%.4f", speed),
                String.format("%.4f", vanillaMax),
                String.format("%.4f", poweredDistance)
            );
        }
    }

    private static boolean isFinite(Vec3d vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private MinecartSpeedManager() {
    }
}
