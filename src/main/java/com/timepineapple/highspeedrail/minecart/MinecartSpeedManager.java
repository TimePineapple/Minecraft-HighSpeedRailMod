package com.timepineapple.highspeedrail.minecart;

import com.timepineapple.highspeedrail.HighSpeedRail;
import com.timepineapple.highspeedrail.config.ModConfig;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.RailShape;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.entity.vehicle.MinecartEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public final class MinecartSpeedManager {
    private static final double EPSILON = 1.0E-6;
    private static final double MIN_DIRECTION_SQUARED = 1.0E-8;
    private static final int END_HANDOFF_RAILS = 8;
    private static final int SCAN_MARGIN = 3;

    public static void beforeVanillaTick(MinecartEntity cart, ServerWorld world) {
        MinecartSpeedState state = state(cart);
        state.beginTick();
        ModConfig config = HighSpeedRail.config();
        PhysicsProfile currentProfile = HighSpeedRail.physicsProfile();

        if (!config.enable || !cart.isAlive() || !finite(cart.getVelocity())) {
            if (state.mode() != MinecartSpeedMode.NORMAL) {
                leaveActive(cart, state, currentProfile.vanillaSpeed(cart.isTouchingWater()), 0);
            }
            return;
        }

        if (state.mode() == MinecartSpeedMode.NORMAL) {
            if (state.normalCooldownTicks() > 0) {
                state.consumeNormalCooldownTick();
                return;
            }
            tryActivate(cart, world, state, currentProfile);
            return;
        }

        prepareActiveTick(cart, world, state, currentProfile);
    }

    public static void afterVanillaTick(MinecartEntity cart, ServerWorld world) {
        MinecartSpeedState state = state(cart);
        if (state.mode() == MinecartSpeedMode.NORMAL) {
            return;
        }

        RailGeometryMover.MovementResult result = state.movementResult();
        PhysicsProfile profile = HighSpeedRail.physicsProfile();
        switch (result.outcome()) {
            case WAITING_UNLOADED -> {
                state.restoreFrozenTick();
                state.setDirection(result.endingTangent());
                cart.setVelocity(Vec3d.ZERO);
            }
            case COLLISION -> leaveAfterCollision(cart, state, 1);
            case HANDOFF -> {
                state.setDirection(result.endingTangent());
                leaveActive(
                    cart,
                    state,
                    profile.vanillaSpeed(cart.isTouchingWater()),
                    0
                );
            }
            case MOVED, SUBSTEP_LIMIT -> {
                if (movementWasInterrupted(cart.getVelocity(), result)) {
                    leaveAfterCollision(cart, state, 1);
                } else {
                    state.clearUnloadedBoundaryWait();
                    state.setSpeed(result.endingTrackSpeed());
                    state.setDirection(result.endingTangent());
                    cart.setVelocity(state.direction().multiply(result.endingHorizontalSpeed()));
                }
            }
            case NONE -> leaveActive(
                cart,
                state,
                profile.vanillaSpeed(cart.isTouchingWater()),
                1
            );
        }
    }

    private static void tryActivate(
        MinecartEntity cart,
        ServerWorld world,
        MinecartSpeedState state,
        PhysicsProfile profile
    ) {
        BlockPos railPos = cart.getRailOrMinecartPos();
        if (!world.isChunkLoaded(railPos)) {
            return;
        }
        BlockState railState = world.getBlockState(railPos);
        if (!RailPathScanner.isPoweredRail(railState)) {
            return;
        }

        Vec3d velocity = cart.getVelocity();
        if (velocity.horizontalLengthSquared() < MIN_DIRECTION_SQUARED) {
            return;
        }
        RailPathScanner.RailFrame frame = RailPathScanner.frame(
            railPos, railState, cart.getEntityPos(), velocity
        );
        if (frame == null) {
            return;
        }

        boolean slope = isSlope(frame);
        double vanillaTrack = profile.vanillaTrackSpeed(cart.isTouchingWater(), slope);
        if (profile.configuredMaxSpeed() <= vanillaTrack + EPSILON || profile.acceleration() <= 0.0) {
            return;
        }

        RailPathScanner.PoweredPath path = scan(
            world, railPos, cart.getEntityPos(), frame.tangent(), profile.effectiveActivationBlocks(),
            Math.max(profile.configuredMaxSpeed(), RailPathScanner.railSpeed(velocity, frame.tangent()))
        );
        if (!hasActivationDistance(true, path, profile.effectiveActivationBlocks())) {
            return;
        }

        double speed = RailGeometryMover.trackSpeedFromHorizontal(
            RailPathScanner.railSpeed(velocity, frame.tangent()), slope
        );
        state.setSpeed(speed);
        state.setDirection(frame.tangent());
        MinecartSpeedMode mode = speed + EPSILON < profile.configuredMaxSpeed()
            ? MinecartSpeedMode.ACCELERATING
            : MinecartSpeedMode.HIGH_SPEED;
        state.startPhase(mode, profile.configuredMaxSpeed(), profile, false, false);
        cart.setVelocity(state.direction().multiply(
            RailGeometryMover.horizontalSpeedFromTrack(speed, slope)
        ));
    }

    private static void prepareActiveTick(
        MinecartEntity cart,
        ServerWorld world,
        MinecartSpeedState state,
        PhysicsProfile currentProfile
    ) {
        double vanillaSpeed = currentProfile.vanillaSpeed(cart.isTouchingWater());
        BlockPos railPos = cart.getRailOrMinecartPos();
        if (!world.isChunkLoaded(railPos)) {
            return;
        }
        BlockState railState = world.getBlockState(railPos);
        if (!RailPathScanner.isPoweredRail(railState)) {
            leaveActive(cart, state, currentProfile.vanillaSpeed(cart.isTouchingWater()), 1);
            return;
        }

        RailPathScanner.RailFrame frame = RailPathScanner.frame(
            railPos, railState, cart.getEntityPos(), state.direction()
        );
        if (frame == null) {
            leaveActive(cart, state, currentProfile.vanillaSpeed(cart.isTouchingWater()), 1);
            return;
        }
        state.setDirection(frame.tangent());
        boolean currentSlope = isSlope(frame);
        double effectiveCurrentMax = currentProfile.effectiveMaxTrackSpeed(
            cart.isTouchingWater(), currentSlope
        );

        int effective = state.effectiveActivationBlocks();
        RailPathScanner.PoweredPath path = scan(
            world, railPos, cart.getEntityPos(), state.direction(), effective, state.speed()
        );
        if (state.waitingAtUnloadedBoundary() && path.stoppedAtUnloadedChunk()) {
            cart.setVelocity(state.direction().multiply(
                RailGeometryMover.horizontalSpeedFromTrack(state.speed(), currentSlope)
            ));
            return;
        }
        state.clearUnloadedBoundaryWait();
        if (path.reachedEnd()) {
            if (missedHandoffBoundary(path)) {
                leaveActive(cart, state, vanillaSpeed, 0);
                return;
            }
            double handoff = handoffDistance(path);
            state.setHandoffRailPos(path.handoffRailPos());
            boolean handoffSlope = isSlopeRail(world, path.handoffRailPos());
            if (!state.railEndBrake() && handoff <= state.activeBlocks() + state.speed() + EPSILON) {
                state.startRailEndBrake(
                    currentProfile.vanillaTrackSpeed(cart.isTouchingWater(), handoffSlope),
                    handoffSlope
                );
            }
        } else if (state.railEndBrake()
            && path.fullPoweredRailsAhead() >= effective) {
            state.startPhase(
                state.speed() + EPSILON < effectiveCurrentMax
                    ? MinecartSpeedMode.ACCELERATING
                    : MinecartSpeedMode.HIGH_SPEED,
                effectiveCurrentMax,
                currentProfile,
                false,
                false
            );
        }

        if (!state.maxSpeedMatches(currentProfile.configuredMaxSpeed())
            && state.speed() > effectiveCurrentMax + EPSILON) {
            state.startPhase(
                MinecartSpeedMode.DECELERATING,
                effectiveCurrentMax,
                currentProfile,
                false,
                currentSlope
            );
        } else if (state.mode() == MinecartSpeedMode.HIGH_SPEED
            && state.speed() > effectiveCurrentMax + EPSILON) {
            state.startPhase(
                MinecartSpeedMode.DECELERATING,
                effectiveCurrentMax,
                currentProfile,
                false,
                currentSlope
            );
        } else if (!state.railEndBrake()
            && state.mode() == MinecartSpeedMode.HIGH_SPEED
            && state.speed() + EPSILON < effectiveCurrentMax) {
            state.startPhase(
                MinecartSpeedMode.ACCELERATING,
                effectiveCurrentMax,
                currentProfile,
                false,
                false
            );
        }

        double next = advancePhaseSpeed(state, cart.isTouchingWater());
        state.setSpeed(next);
        cart.setVelocity(state.direction().multiply(
            RailGeometryMover.horizontalSpeedFromTrack(next, currentSlope)
        ));
    }

    static double advancePhaseSpeed(MinecartSpeedState state, boolean touchingWater) {
        double speed = state.speed();
        switch (state.mode()) {
            case ACCELERATING -> {
                speed = Math.min(state.phaseTargetSpeed(), speed + state.acceleration());
                if (speed >= state.phaseTargetSpeed() - EPSILON) {
                    state.setMode(MinecartSpeedMode.HIGH_SPEED);
                }
            }
            case DECELERATING -> {
                speed = directDecelerate(
                    speed, state.phaseTargetSpeed(), state.brakeAcceleration(touchingWater)
                );
                if (speed <= state.phaseTargetSpeed() + EPSILON) {
                    state.setMode(state.railEndBrake()
                        ? MinecartSpeedMode.BRAKE_HOLD
                        : MinecartSpeedMode.HIGH_SPEED);
                }
            }
            case BRAKE_HOLD -> speed = state.phaseTargetSpeed();
            case HIGH_SPEED, NORMAL -> {
            }
        }
        return Math.max(0.0, speed);
    }

    public static boolean shouldTakeOverMovement(AbstractMinecartEntity cart) {
        return cart instanceof MinecartSpeedStateHolder holder
            && takeOverMovement(holder.highSpeedRail$getSpeedState().mode());
    }

    static boolean takeOverMovement(MinecartSpeedMode mode) {
        return mode != MinecartSpeedMode.NORMAL;
    }

    public static double activeProfileSpeed(MinecartEntity cart) {
        return state(cart).speed();
    }

    public static long activeHandoffRailPos(MinecartEntity cart) {
        return state(cart).handoffRailPos();
    }

    public static void recordGeometryMovement(
        MinecartEntity cart,
        RailGeometryMover.MovementResult result
    ) {
        state(cart).setMovementResult(result);
    }

    public static double cachedVanillaSpeed(MinecartEntity cart) {
        return HighSpeedRail.physicsProfile().vanillaSpeed(cart.isTouchingWater());
    }

    static boolean hasActivationDistance(
        boolean currentPowered,
        RailPathScanner.PoweredPath path,
        int effectiveActivationBlocks
    ) {
        return currentPowered
            && path.fullPoweredRailsAhead() >= effectiveActivationBlocks;
    }

    static double handoffDistance(RailPathScanner.PoweredPath path) {
        if (!path.reachedEnd()) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.max(0.0, path.lastRailEntryDistance() - (END_HANDOFF_RAILS - 1.0));
    }

    static boolean missedHandoffBoundary(RailPathScanner.PoweredPath path) {
        return path.reachedEnd() && path.handoffRailPos() == Long.MIN_VALUE;
    }

    static double directDecelerate(double speed, double target, double brake) {
        if (!Double.isFinite(speed) || !Double.isFinite(target) || !Double.isFinite(brake)) {
            return Math.max(0.0, target);
        }
        return Math.max(target, speed - Math.max(0.0, brake));
    }

    static double frictionCompensatedSpeed(
        double desiredSpeed,
        boolean hasPassengers,
        boolean touchingWater
    ) {
        return PhysicsProfile.compensatedSpeed(desiredSpeed, hasPassengers, touchingWater);
    }

    private static boolean isSlope(RailPathScanner.RailFrame frame) {
        return frame.backwardEndpoint().getY() != frame.forwardEndpoint().getY();
    }

    private static boolean isSlopeRail(ServerWorld world, long packedPos) {
        if (packedPos == Long.MIN_VALUE) {
            return false;
        }
        BlockPos pos = BlockPos.fromLong(packedPos);
        if (!world.isChunkLoaded(pos)) {
            return false;
        }
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof AbstractRailBlock rail)) {
            return false;
        }
        RailShape shape = state.get(rail.getShapeProperty());
        return switch (shape) {
            case ASCENDING_EAST, ASCENDING_WEST, ASCENDING_NORTH, ASCENDING_SOUTH -> true;
            default -> false;
        };
    }

    private static RailPathScanner.PoweredPath scan(
        ServerWorld world,
        BlockPos railPos,
        Vec3d position,
        Vec3d direction,
        int effective,
        double speed
    ) {
        double limit = effective + Math.max(1.0, speed) + SCAN_MARGIN;
        return RailPathScanner.scanPoweredDistanceAhead(
            world, railPos, position, direction, limit
        );
    }

    private static void leaveActive(
        MinecartEntity cart,
        MinecartSpeedState state,
        double vanillaSpeed,
        int cooldownTicks
    ) {
        Vec3d direction = state.direction();
        if (direction.horizontalLengthSquared() < MIN_DIRECTION_SQUARED) {
            direction = cart.getVelocity().getHorizontal();
        }
        state.resetToNormal(vanillaSpeed, direction, cooldownTicks);
        cart.setVelocity(state.direction().multiply(vanillaSpeed));
    }

    private static void leaveAfterCollision(
        MinecartEntity cart,
        MinecartSpeedState state,
        int cooldownTicks
    ) {
        Vec3d velocity = cart.getVelocity();
        double speed = finite(velocity) ? velocity.horizontalLength() : 0.0;
        Vec3d direction = speed > EPSILON ? velocity.getHorizontal() : state.direction();
        state.resetToNormal(speed, direction, cooldownTicks);
        if (!finite(velocity)) {
            cart.setVelocity(Vec3d.ZERO);
        }
    }

    private static boolean movementWasInterrupted(
        Vec3d actualVelocity,
        RailGeometryMover.MovementResult result
    ) {
        if (!finite(actualVelocity)) {
            return true;
        }
        Vec3d expected = result.endingTangent().multiply(result.endingHorizontalSpeed());
        return actualVelocity.getHorizontal().subtract(expected).lengthSquared() > 1.0E-6;
    }

    private static MinecartSpeedState state(AbstractMinecartEntity cart) {
        return ((MinecartSpeedStateHolder) cart).highSpeedRail$getSpeedState();
    }

    private static boolean finite(Vec3d value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    private MinecartSpeedManager() {
    }
}
