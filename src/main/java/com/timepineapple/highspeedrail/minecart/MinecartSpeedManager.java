package com.timepineapple.highspeedrail.minecart;

import com.timepineapple.highspeedrail.HighSpeedRail;
import com.timepineapple.highspeedrail.config.ModConfig;
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
        double previousVanillaMax = state.vanillaMaxSpeed();
        Vec3d velocity = cart.getVelocity();
        Vec3d position = cart.getEntityPos();
        BlockPos railPos = cart.getRailOrMinecartPos();
        BlockState railState = world.isChunkLoaded(railPos) ? world.getBlockState(railPos) : null;
        RailPathScanner.RailFrame frame = railState == null
            ? null
            : RailPathScanner.frame(railPos, railState, position, velocity);
        double speed = frame == null
            ? velocity.horizontalLength()
            : RailPathScanner.railSpeed(velocity, frame.tangent());

        state.setVanillaMaxSpeed(vanillaMax);
        state.setSpeedBeforeVanillaTick(speed);
        state.setVelocityBeforeVanillaTick(velocity);
        state.setPositionBeforeVanillaTick(position);
        state.setRailPosBeforeVanillaTick(frame == null ? Long.MIN_VALUE : railPos.asLong());
        state.setTangentBeforeVanillaTick(frame == null ? Vec3d.ZERO : frame.tangent());

        if (!config.enable || !cart.isAlive() || !isFinite(velocity)) {
            transition(cart, state, MinecartSpeedMode.NORMAL, speed, vanillaMax, 0);
            return;
        }

        double configuredAcceleration = config.configuredAcceleration();
        if (config.maxSpeed <= vanillaMax + SPEED_EPSILON || configuredAcceleration <= 0.0) {
            transition(cart, state, MinecartSpeedMode.NORMAL, speed, vanillaMax, 0);
            return;
        }

        if (frame == null || velocity.horizontalLengthSquared() < MIN_DIRECTION_SQUARED) {
            handleMissingRailOrDirection(cart, state, speed, vanillaMax);
            return;
        }

        double scanLimit = scanLimit(config, vanillaMax);
        RailPathScanner.PoweredPath poweredPath = RailPathScanner.scanPoweredDistanceAhead(
            world, railPos, position, velocity, scanLimit
        );
        double brakeBoundary = brakeBoundaryDistance(poweredPath, config.activeBlocks);
        state.setPoweredPathBeforeVanillaTick(poweredPath, brakeBoundary);

        boolean currentPowered = RailPathScanner.isPoweredRail(railState);
        boolean maxSpeedChanged = !state.maxSpeedMatches(config.maxSpeed);
        boolean vanillaTargetChanged = Double.compare(previousVanillaMax, vanillaMax) != 0;

        switch (state.mode()) {
            case NORMAL -> {
                if (hasActivationDistance(currentPowered, poweredPath, config.activeBlocks)) {
                    startTimedAdjustment(
                        cart, state, speed, config.maxSpeed, configuredAcceleration, vanillaMax, poweredPath.distance()
                    );
                }
            }
            case ACCELERATING, HIGH_SPEED -> {
                if (!currentPowered) {
                    startEmergencyVanillaHold(cart, state, speed, vanillaMax, poweredPath.distance());
                } else if (shouldBrakeForConfirmedEnd(currentPowered, poweredPath, config.activeBlocks)) {
                    startRailEndDeceleration(cart, state, speed, vanillaMax, poweredPath, poweredPath.distance());
                } else if (maxSpeedChanged) {
                    startTimedAdjustment(
                        cart, state, speed, config.maxSpeed, configuredAcceleration, vanillaMax, poweredPath.distance()
                    );
                }
            }
            case DECELERATING -> {
                if (!currentPowered) {
                    startEmergencyVanillaHold(cart, state, speed, vanillaMax, poweredPath.distance());
                } else if (state.railEndBrake()) {
                    handleActiveRailEndBrake(
                        cart,
                        state,
                        speed,
                        vanillaMax,
                        vanillaTargetChanged,
                        poweredPath,
                        config,
                        configuredAcceleration
                    );
                } else if (shouldBrakeForConfirmedEnd(currentPowered, poweredPath, config.activeBlocks)) {
                    startRailEndDeceleration(cart, state, speed, vanillaMax, poweredPath, poweredPath.distance());
                } else if (maxSpeedChanged) {
                    startTimedAdjustment(
                        cart, state, speed, config.maxSpeed, configuredAcceleration, vanillaMax, poweredPath.distance()
                    );
                }
            }
            case BRAKE_HOLD -> {
                if (!currentPowered) {
                    transition(cart, state, MinecartSpeedMode.NORMAL, speed, vanillaMax, poweredPath.distance());
                } else if (poweredPath.fullPoweredRailsAhead() >= config.activeBlocks) {
                    startTimedAdjustment(
                        cart, state, speed, config.maxSpeed, configuredAcceleration, vanillaMax, poweredPath.distance()
                    );
                }
            }
        }

        state.setConfiguredMaxSpeed(config.maxSpeed);
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
            transition(cart, state, MinecartSpeedMode.NORMAL, 0.0, state.vanillaMaxSpeed(), 0);
            return;
        }

        double teleportLimit = Math.max(4.0, config.effectiveMaxSpeed(state.vanillaMaxSpeed()) * 6.0);
        if (afterPosition.squaredDistanceTo(state.positionBeforeVanillaTick()) > teleportLimit * teleportLimit) {
            transition(
                cart,
                state,
                MinecartSpeedMode.NORMAL,
                afterVelocity.horizontalLength(),
                state.vanillaMaxSpeed(),
                0
            );
            return;
        }

        Vec3d beforeVelocity = state.velocityBeforeVanillaTick();
        double directionDot = beforeVelocity.x * afterVelocity.x + beforeVelocity.z * afterVelocity.z;
        if (directionDot < -SPEED_EPSILON * SPEED_EPSILON) {
            transition(
                cart,
                state,
                MinecartSpeedMode.NORMAL,
                afterVelocity.horizontalLength(),
                state.vanillaMaxSpeed(),
                0
            );
            return;
        }

        BlockPos endingRailPos = cart.getRailOrMinecartPos();
        boolean endingChunkLoaded = world.isChunkLoaded(endingRailPos);
        BlockState endingRailState = endingChunkLoaded ? world.getBlockState(endingRailPos) : null;
        RailPathScanner.RailFrame endingFrame = endingRailState == null
            ? null
            : RailPathScanner.frame(endingRailPos, endingRailState, afterPosition, afterVelocity);
        RailPathScanner.TravelMeasure travelMeasure = measureTravel(
            world, state, endingRailPos, afterPosition, afterVelocity
        );

        double travelled;
        Vec3d tangent;
        boolean onRail = endingFrame != null && travelMeasure.valid();
        if (onRail) {
            travelled = travelMeasure.distance();
            tangent = travelMeasure.tangent();
        } else if (canFinishKnownBrakeBoundary(state)) {
            Vec3d displacement = afterPosition.subtract(state.positionBeforeVanillaTick());
            travelled = Math.max(
                displacement.horizontalLength(),
                state.brakeTargetDistanceBeforeVanillaTick()
            );
            tangent = Vec3d.ZERO;
        } else if (!endingChunkLoaded) {
            Vec3d displacement = afterPosition.subtract(state.positionBeforeVanillaTick());
            travelled = displacement.horizontalLength();
            tangent = Vec3d.ZERO;
        } else if (state.mode() == MinecartSpeedMode.DECELERATING
            || state.mode() == MinecartSpeedMode.BRAKE_HOLD) {
            Vec3d displacement = afterPosition.subtract(state.positionBeforeVanillaTick());
            travelled = displacement.horizontalLength();
            tangent = Vec3d.ZERO;
        } else {
            transition(
                cart,
                state,
                MinecartSpeedMode.NORMAL,
                afterVelocity.horizontalLength(),
                state.vanillaMaxSpeed(),
                0
            );
            return;
        }

        double afterSpeed = onRail
            ? RailPathScanner.railSpeed(afterVelocity, tangent)
            : afterVelocity.horizontalLength();
        if (afterSpeed < Math.min(
            state.vanillaMaxSpeed() - SPEED_EPSILON,
            state.speedBeforeVanillaTick() * 0.45
        )) {
            transition(cart, state, MinecartSpeedMode.NORMAL, afterSpeed, state.vanillaMaxSpeed(), 0);
            return;
        }
        if (afterSpeed * afterSpeed < MIN_DIRECTION_SQUARED) {
            transition(cart, state, MinecartSpeedMode.NORMAL, afterSpeed, state.vanillaMaxSpeed(), 0);
            return;
        }

        double phaseTravel = splitAtBrakeBoundaryIfNeeded(
            cart, state, travelled, config.maxSpeed, state.vanillaMaxSpeed()
        );
        double targetSpeed = advancePhase(
            cart, state, phaseTravel, config.maxSpeed, state.vanillaMaxSpeed()
        );
        applyTargetVelocity(cart, afterVelocity, tangent, targetSpeed, onRail);
        state.setPoweredDistanceBeforeVanillaTick(
            confirmedDistanceAfterTravel(state.poweredDistanceBeforeVanillaTick(), travelled)
        );
    }

    public static double speedCap(MinecartEntity cart, double vanillaReturnValue) {
        ModConfig config = HighSpeedRail.config();
        MinecartSpeedState state = state(cart);
        if (!config.enable || state.mode() == MinecartSpeedMode.NORMAL || config.maxSpeed <= vanillaReturnValue) {
            return vanillaReturnValue;
        }
        double configuredCap = config.effectiveMaxSpeed(Math.max(vanillaReturnValue, state.vanillaMaxSpeed()));
        // A live maxSpeed reduction must use the configured constant acceleration instead of being
        // clamped by vanilla before the post-tick controller can apply the next profile sample.
        return Math.max(configuredCap, state.speedBeforeVanillaTick());
    }

    private static void handleActiveRailEndBrake(
        MinecartEntity cart,
        MinecartSpeedState state,
        double speed,
        double vanillaMax,
        boolean vanillaTargetChanged,
        RailPathScanner.PoweredPath poweredPath,
        ModConfig config,
        double configuredAcceleration
    ) {
        if (poweredPath.fullPoweredRailsAhead() >= config.activeBlocks) {
            startTimedAdjustment(
                cart,
                state,
                speed,
                config.maxSpeed,
                configuredAcceleration,
                vanillaMax,
                poweredPath.distance()
            );
        } else if (poweredPath.reachedEnd()) {
            if (state.brakeTerminalRailPos() != poweredPath.lastPoweredRailPos()
                || vanillaTargetChanged) {
                startRailEndDeceleration(cart, state, speed, vanillaMax, poweredPath, poweredPath.distance());
            }
        } else if (vanillaTargetChanged) {
            double remainingDistance = Math.max(0.0, state.phaseDistance() - state.phaseProgress());
            startRailEndDeceleration(
                cart,
                state,
                speed,
                vanillaMax,
                remainingDistance,
                state.brakeTerminalRailPos(),
                state.poweredDistanceBeforeVanillaTick()
            );
        }
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
        int maximumSteps = Math.max(
            EXTRA_MEASURE_STEPS,
            (int) Math.ceil(worldDistance * 3.0) + EXTRA_MEASURE_STEPS
        );
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
        double vanillaSpeed
    ) {
        if (state.railEndBrake()
            || (state.mode() != MinecartSpeedMode.ACCELERATING
                && state.mode() != MinecartSpeedMode.HIGH_SPEED
                && state.mode() != MinecartSpeedMode.DECELERATING)
            || !state.poweredPathReachedEndBeforeVanillaTick()
            || travelled <= SPEED_EPSILON) {
            return travelled;
        }

        double boundaryDistance = state.brakeBoundaryDistanceBeforeVanillaTick();
        if (!Double.isFinite(boundaryDistance) || travelled + SPEED_EPSILON < boundaryDistance) {
            return travelled;
        }

        double boundarySpeed = timedSpeedAfterNextTick(state, maxSpeed);
        double targetDistance = Math.max(
            0.0,
            state.brakeTargetDistanceBeforeVanillaTick() - boundaryDistance
        );
        startRailEndDeceleration(
            cart,
            state,
            boundarySpeed,
            vanillaSpeed,
            targetDistance,
            state.scannedLastPoweredRailPos(),
            state.poweredDistanceBeforeVanillaTick()
        );
        return Math.max(0.0, travelled - boundaryDistance);
    }

    private static boolean canFinishKnownBrakeBoundary(MinecartSpeedState state) {
        return !state.railEndBrake()
            && state.poweredPathReachedEndBeforeVanillaTick()
            && Double.isFinite(state.brakeBoundaryDistanceBeforeVanillaTick())
            && (state.mode() == MinecartSpeedMode.ACCELERATING
                || state.mode() == MinecartSpeedMode.HIGH_SPEED
                || state.mode() == MinecartSpeedMode.DECELERATING);
    }

    private static double timedSpeedAfterNextTick(MinecartSpeedState state, double maxSpeed) {
        return switch (state.mode()) {
            case ACCELERATING, DECELERATING -> SpeedProfile.moveTowards(
                state.phaseStartSpeed(),
                state.phaseTargetSpeed(),
                state.phaseAcceleration(),
                state.phaseProgress() + 1.0
            );
            case HIGH_SPEED -> maxSpeed;
            default -> state.speedBeforeVanillaTick();
        };
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
                state.setPhaseProgress(state.phaseProgress() + 1.0);
                double target = SpeedProfile.moveTowards(
                    state.phaseStartSpeed(),
                    state.phaseTargetSpeed(),
                    state.phaseAcceleration(),
                    state.phaseProgress()
                );
                if (target >= state.phaseTargetSpeed() - SPEED_EPSILON) {
                    transition(
                        cart,
                        state,
                        MinecartSpeedMode.HIGH_SPEED,
                        maxSpeed,
                        vanillaSpeed,
                        state.poweredDistanceBeforeVanillaTick()
                    );
                    yield maxSpeed;
                }
                yield target;
            }
            case HIGH_SPEED -> maxSpeed;
            case DECELERATING -> {
                double target;
                if (state.distanceBasedPhase()) {
                    state.setPhaseProgress(state.phaseProgress() + travelled);
                    target = SpeedProfile.speedAt(
                        state.phaseStartSpeed(),
                        state.phaseTargetSpeed(),
                        state.phaseProgress(),
                        state.phaseDistance()
                    );
                    if (state.phaseProgress() >= state.phaseDistance() - SPEED_EPSILON) {
                        transition(
                            cart,
                            state,
                            MinecartSpeedMode.BRAKE_HOLD,
                            state.phaseTargetSpeed(),
                            vanillaSpeed,
                            state.poweredDistanceBeforeVanillaTick()
                        );
                        yield state.phaseTargetSpeed();
                    }
                } else {
                    state.setPhaseProgress(state.phaseProgress() + 1.0);
                    target = SpeedProfile.moveTowards(
                        state.phaseStartSpeed(),
                        state.phaseTargetSpeed(),
                        state.phaseAcceleration(),
                        state.phaseProgress()
                    );
                    if (target <= state.phaseTargetSpeed() + SPEED_EPSILON) {
                        transition(
                            cart,
                            state,
                            MinecartSpeedMode.HIGH_SPEED,
                            state.phaseTargetSpeed(),
                            vanillaSpeed,
                            state.poweredDistanceBeforeVanillaTick()
                        );
                        yield state.phaseTargetSpeed();
                    }
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

    private static void startTimedAdjustment(
        MinecartEntity cart,
        MinecartSpeedState state,
        double startSpeed,
        double targetSpeed,
        double acceleration,
        double vanillaSpeed,
        double remainingPoweredDistance
    ) {
        if (startSpeed < targetSpeed - SPEED_EPSILON) {
            state.setTimedPhase(startSpeed, targetSpeed, acceleration);
            transition(
                cart,
                state,
                MinecartSpeedMode.ACCELERATING,
                startSpeed,
                vanillaSpeed,
                remainingPoweredDistance
            );
        } else if (startSpeed > targetSpeed + SPEED_EPSILON) {
            state.setTimedPhase(startSpeed, targetSpeed, acceleration);
            transition(
                cart,
                state,
                MinecartSpeedMode.DECELERATING,
                startSpeed,
                vanillaSpeed,
                remainingPoweredDistance
            );
        } else {
            transition(
                cart,
                state,
                MinecartSpeedMode.HIGH_SPEED,
                targetSpeed,
                vanillaSpeed,
                remainingPoweredDistance
            );
        }
    }

    private static void startRailEndDeceleration(
        MinecartEntity cart,
        MinecartSpeedState state,
        double startSpeed,
        double targetSpeed,
        RailPathScanner.PoweredPath path,
        double remainingPoweredDistance
    ) {
        startRailEndDeceleration(
            cart,
            state,
            startSpeed,
            targetSpeed,
            path.brakingTargetDistance(),
            path.lastPoweredRailPos(),
            remainingPoweredDistance
        );
    }

    private static void startRailEndDeceleration(
        MinecartEntity cart,
        MinecartSpeedState state,
        double startSpeed,
        double targetSpeed,
        double targetDistance,
        long terminalRailPos,
        double remainingPoweredDistance
    ) {
        if (startSpeed <= targetSpeed + SPEED_EPSILON || targetDistance <= SPEED_EPSILON) {
            transition(
                cart,
                state,
                MinecartSpeedMode.BRAKE_HOLD,
                targetSpeed,
                targetSpeed,
                remainingPoweredDistance
            );
            return;
        }
        double acceleration = SpeedProfile.brakingAcceleration(startSpeed, targetSpeed, targetDistance);
        if (acceleration <= 0.0) {
            transition(
                cart,
                state,
                MinecartSpeedMode.BRAKE_HOLD,
                targetSpeed,
                targetSpeed,
                remainingPoweredDistance
            );
            return;
        }
        state.setRailEndPhase(startSpeed, targetSpeed, targetDistance, acceleration, terminalRailPos);
        transition(
            cart,
            state,
            MinecartSpeedMode.DECELERATING,
            startSpeed,
            targetSpeed,
            remainingPoweredDistance
        );
    }

    private static void startEmergencyVanillaHold(
        MinecartEntity cart,
        MinecartSpeedState state,
        double speed,
        double vanillaSpeed,
        double poweredDistance
    ) {
        if (speed > vanillaSpeed + SPEED_EPSILON) {
            transition(cart, state, MinecartSpeedMode.BRAKE_HOLD, speed, vanillaSpeed, poweredDistance);
        } else {
            transition(cart, state, MinecartSpeedMode.NORMAL, speed, vanillaSpeed, poweredDistance);
        }
    }

    private static void handleMissingRailOrDirection(
        MinecartEntity cart,
        MinecartSpeedState state,
        double speed,
        double vanillaSpeed
    ) {
        state.setPoweredDistanceBeforeVanillaTick(0.0);
        if (state.mode() != MinecartSpeedMode.NORMAL && speed > vanillaSpeed + SPEED_EPSILON) {
            transition(cart, state, MinecartSpeedMode.BRAKE_HOLD, speed, vanillaSpeed, 0);
        } else {
            transition(cart, state, MinecartSpeedMode.NORMAL, speed, vanillaSpeed, 0);
        }
    }

    private static double scanLimit(ModConfig config, double vanillaSpeed) {
        double perTickTravel = Math.max(config.maxSpeed, vanillaSpeed);
        return Math.min(
            Integer.MAX_VALUE - 4.0,
            config.activeBlocks + Math.ceil(perTickTravel) + 3.0
        );
    }

    static double brakeBoundaryDistance(RailPathScanner.PoweredPath path, int activeBlocks) {
        if (!path.reachedEnd()) {
            return Double.POSITIVE_INFINITY;
        }
        if (path.fullPoweredRailsAhead() < activeBlocks) {
            return 0.0;
        }
        return Math.max(0.0, path.lastRailEntryDistance() - (activeBlocks - 1.0));
    }

    static boolean hasActivationDistance(
        boolean currentPowered,
        RailPathScanner.PoweredPath path,
        int activeBlocks
    ) {
        return currentPowered && path.fullPoweredRailsAhead() >= activeBlocks;
    }

    static boolean shouldBrakeForConfirmedEnd(
        boolean currentPowered,
        RailPathScanner.PoweredPath path,
        int activeBlocks
    ) {
        return currentPowered && path.reachedEnd() && path.fullPoweredRailsAhead() < activeBlocks;
    }

    static double confirmedDistanceAfterTravel(double confirmedDistance, double travelled) {
        return Math.max(0.0, confirmedDistance - Math.max(0.0, travelled));
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
