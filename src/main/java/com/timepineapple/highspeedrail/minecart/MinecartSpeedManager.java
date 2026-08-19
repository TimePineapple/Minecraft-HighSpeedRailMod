package com.timepineapple.highspeedrail.minecart;

import com.timepineapple.highspeedrail.HighSpeedRail;
import com.timepineapple.highspeedrail.config.ModConfig;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.RailShape;
import net.minecraft.predicate.entity.EntityPredicates;
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
        HighSpeedRailDiagnostics.headBefore(cart, world, state);
        try {
            state.beginTick();
            ModConfig config = HighSpeedRail.config();
            PhysicsProfile currentProfile = HighSpeedRail.physicsProfile();

            if (!config.enable || !cart.isAlive() || !finite(cart.getVelocity())) {
                state.clearActivationCandidate();
                state.clearNormalMotionSamples();
                if (state.mode() != MinecartSpeedMode.NORMAL) {
                    leaveActive(cart, state, currentProfile.vanillaSpeed(cart.isTouchingWater()), 0);
                }
                return;
            }

            if (state.mode() == MinecartSpeedMode.NORMAL) {
                beginNormalTickSample(cart, world, state);
                if (state.normalCooldownTicks() > 0) {
                    state.clearActivationCandidate();
                    state.consumeNormalCooldownTick();
                    return;
                }
                tryActivate(cart, world, state, currentProfile);
                return;
            }

            prepareActiveTick(cart, world, state, currentProfile);
        } finally {
            HighSpeedRailDiagnostics.headAfter(cart, world, state);
        }
    }

    public static void afterVanillaTick(MinecartEntity cart, ServerWorld world) {
        MinecartSpeedState state = state(cart);
        HighSpeedRailDiagnostics.tailBefore(cart, world, state);
        try {
            if (state.mode() == MinecartSpeedMode.NORMAL) {
                finishNormalVanillaTick(cart, world, state);
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
        } finally {
            HighSpeedRailDiagnostics.tailAfter(cart, world, state);
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
            state.clearActivationCandidate();
            return;
        }
        BlockState railState = world.getBlockState(railPos);
        if (!RailPathScanner.isPoweredRail(railState)) {
            state.clearActivationCandidate();
            return;
        }

        Vec3d velocity = cart.getVelocity();
        if (velocity.horizontalLengthSquared() < MIN_DIRECTION_SQUARED) {
            state.clearActivationCandidate();
            return;
        }
        RailPathScanner.RailFrame frame = RailPathScanner.frame(
            railPos, railState, cart.getEntityPos(), velocity
        );
        if (frame == null) {
            state.clearActivationCandidate();
            return;
        }

        boolean slope = isSlope(frame);
        double vanillaTrack = profile.vanillaTrackSpeed(cart.isTouchingWater(), slope);
        if (profile.configuredMaxSpeed() <= vanillaTrack + EPSILON || profile.acceleration() <= 0.0) {
            state.clearActivationCandidate();
            return;
        }

        RailPathScanner.PoweredPath path = scan(
            world, railPos, cart.getEntityPos(), frame.tangent(), profile.effectiveActivationBlocks(),
            Math.max(profile.configuredMaxSpeed(), RailPathScanner.railSpeed(velocity, frame.tangent()))
        );
        HighSpeedRailDiagnostics.recordScan(cart, "activation", true, path);
        if (!hasActivationDistance(true, path, profile.effectiveActivationBlocks())) {
            state.clearActivationCandidate();
            return;
        }

        if (!state.hasActivationCandidate()) {
            state.armActivationCandidate(frame.tangent());
            return;
        }
        if (!sameRailDirection(state.activationDirection(), frame.tangent())) {
            state.clearActivationCandidate();
            state.armActivationCandidate(frame.tangent());
            return;
        }
        if (!state.activationCandidateReady()) {
            return;
        }

        double speed = initialActivationTrackSpeed(
            state.activationFirstHorizontalSpeed(),
            state.activationSecondHorizontalSpeed(),
            slope,
            profile.configuredMaxSpeed(),
            profile.acceleration()
        );
        state.clearActivationCandidate();
        state.clearNormalMotionSamples();
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

    private static void beginNormalTickSample(
        MinecartEntity cart,
        ServerWorld world,
        MinecartSpeedState state
    ) {
        BlockPos railPos = cart.getRailOrMinecartPos();
        Vec3d velocity = cart.getVelocity();
        if (!world.isChunkLoaded(railPos)
            || !finite(velocity)
            || velocity.horizontalLengthSquared() < MIN_DIRECTION_SQUARED) {
            state.clearActivationCandidate();
            state.clearNormalMotionSamples();
            return;
        }
        BlockState railState = world.getBlockState(railPos);
        if (!(railState.getBlock() instanceof AbstractRailBlock)) {
            state.clearActivationCandidate();
            state.clearNormalMotionSamples();
            return;
        }
        RailPathScanner.RailFrame frame = RailPathScanner.frame(
            railPos, railState, cart.getEntityPos(), velocity
        );
        if (frame == null) {
            state.clearActivationCandidate();
            state.clearNormalMotionSamples();
            return;
        }
        state.beginNormalTickSample(
            cart.getEntityPos(), frame.tangent(), world.getRegistryKey()
        );
    }

    private static void finishNormalVanillaTick(
        MinecartEntity cart,
        ServerWorld world,
        MinecartSpeedState state
    ) {
        if (!state.normalTickSampleActive()) {
            return;
        }
        Vec3d velocity = cart.getVelocity();
        BlockPos railPos = cart.getRailOrMinecartPos();
        double displacement = cart.getEntityPos().distanceTo(state.normalTickStartPosition());
        double teleportLimit = Math.max(
            4.0,
            HighSpeedRail.config().maxSpeed * 6.0
        );
        if (!cart.isAlive()
            || !finite(velocity)
            || !world.getRegistryKey().equals(state.normalTickWorld())
            || !world.isChunkLoaded(railPos)
            || cart.horizontalCollision
            || displacement > teleportLimit
            || hasCandidateEntityContact(world, cart)) {
            state.clearActivationCandidate();
            state.clearNormalMotionSamples();
            return;
        }
        BlockState railState = world.getBlockState(railPos);
        if (!(railState.getBlock() instanceof AbstractRailBlock)) {
            state.clearActivationCandidate();
            state.clearNormalMotionSamples();
            return;
        }
        RailPathScanner.RailFrame endFrame = RailPathScanner.frame(
            railPos, railState, cart.getEntityPos(), state.normalTickDirection()
        );
        if (endFrame == null
            || !sameRailDirection(state.normalTickDirection(), endFrame.tangent())) {
            state.clearActivationCandidate();
            state.clearNormalMotionSamples();
            return;
        }
        double actualHorizontal = actualProjectedHorizontalDistance(
            state.normalTickStartPosition(),
            cart.getEntityPos(),
            state.normalTickDirection()
        );
        if (!Double.isFinite(actualHorizontal)) {
            state.clearActivationCandidate();
            state.clearNormalMotionSamples();
            return;
        }
        if (state.hasActivationCandidate() && !RailPathScanner.isPoweredRail(railState)) {
            state.clearActivationCandidate();
        }
        state.completeNormalTickSample(actualHorizontal, endFrame.tangent());
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
        HighSpeedRailDiagnostics.recordScan(cart, "active", true, path);
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

    static double stableActivationHorizontalSpeed(double first, double second) {
        if (!Double.isFinite(first) || !Double.isFinite(second) || first < 0.0 || second < 0.0) {
            return 0.0;
        }
        return Math.min(first, second);
    }

    static double actualProjectedHorizontalDistance(
        Vec3d start,
        Vec3d end,
        Vec3d direction
    ) {
        if (!finite(start) || !finite(end)
            || direction.horizontalLengthSquared() < MIN_DIRECTION_SQUARED) {
            return Double.NaN;
        }
        return Math.abs(end.subtract(start).getHorizontal().dotProduct(
            direction.getHorizontal().normalize()
        ));
    }

    static double initialActivationTrackSpeed(
        double firstHorizontal,
        double secondHorizontal,
        boolean slope,
        double maxSpeed,
        double acceleration
    ) {
        double stableTrack = RailGeometryMover.trackSpeedFromHorizontal(
            stableActivationHorizontalSpeed(firstHorizontal, secondHorizontal), slope
        );
        if (stableTrack + EPSILON >= maxSpeed) {
            return stableTrack;
        }
        return Math.min(maxSpeed, stableTrack + Math.max(0.0, acceleration));
    }

    static boolean sameDirection(Vec3d first, Vec3d second) {
        return first.horizontalLengthSquared() >= MIN_DIRECTION_SQUARED
            && second.horizontalLengthSquared() >= MIN_DIRECTION_SQUARED
            && first.getHorizontal().dotProduct(second.getHorizontal()) > 0.0;
    }

    static boolean sameRailDirection(Vec3d first, Vec3d second) {
        if (first.horizontalLengthSquared() < MIN_DIRECTION_SQUARED
            || second.horizontalLengthSquared() < MIN_DIRECTION_SQUARED) {
            return false;
        }
        return first.getHorizontal().normalize().dotProduct(
            second.getHorizontal().normalize()
        ) >= 1.0 - 1.0E-6;
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

    private static boolean hasCandidateEntityContact(ServerWorld world, MinecartEntity cart) {
        return !world.getOtherEntities(
            cart,
            cart.getBoundingBox().expand(0.2, 0.0, 0.2),
            EntityPredicates.canBePushedBy(cart)
                .and(entity -> !cart.isConnectedThroughVehicle(entity))
        ).isEmpty();
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
