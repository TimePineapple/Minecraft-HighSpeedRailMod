package com.timepineapple.highspeedrail.minecart;

import com.timepineapple.highspeedrail.HighSpeedRail;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.entity.vehicle.MinecartEntity;
import net.minecraft.predicate.entity.EntityPredicates;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

public final class RailGeometryMover {
    static final double MAX_HORIZONTAL_SUBSTEP = 0.75;
    static final int MAX_SUBSTEPS = 64;
    static final double SLOPE_FACTOR = Math.sqrt(2.0);

    private static final double EPSILON = 1.0E-6;
    private static final double RAIL_HEIGHT_OFFSET = 0.0625;
    private static final long WARNING_INTERVAL_TICKS = 1200L;
    private static long nextLimitWarningTick = Long.MIN_VALUE;

    public static boolean moveActive(AbstractMinecartEntity abstractCart, ServerWorld world) {
        if (!(abstractCart instanceof MinecartEntity cart)) {
            return false;
        }

        double phaseTrackSpeed = MinecartSpeedManager.activeProfileSpeed(cart);
        if (!Double.isFinite(phaseTrackSpeed) || phaseTrackSpeed < 0.0) {
            cart.setVelocity(Vec3d.ZERO);
            record(cart, MovementResult.collision(Vec3d.ZERO));
            return true;
        }

        BlockPos railPos = cart.getRailOrMinecartPos();
        if (!world.isChunkLoaded(railPos)) {
            record(cart, MovementResult.waiting(
                cart.getVelocity().getHorizontal(), phaseTrackSpeed, 0.0
            ));
            return true;
        }
        BlockState railState = world.getBlockState(railPos);
        if (!RailPathScanner.isPoweredRail(railState)) {
            return finishCollision(cart, cart.getVelocity().getHorizontal(), false, 0.0, 0.0);
        }

        Vec3d direction = cart.getVelocity().getHorizontal();
        RailPathScanner.RailFrame frame = RailPathScanner.frame(
            railPos, railState, cart.getEntityPos(), direction
        );
        if (frame == null) {
            return finishCollision(cart, direction, false, 0.0, 0.0);
        }

        cart.setOnRail(true);
        cart.onLanding();
        double movementTrackSpeed = MinecartSpeedManager.frictionCompensatedSpeed(
            phaseTrackSpeed, cart.hasPassengers(), cart.isTouchingWater()
        );
        double remainingTrack = movementTrackSpeed;
        long handoffRailPos = MinecartSpeedManager.activeHandoffRailPos(cart);
        double horizontalTravel = 0.0;
        double trackTravel = 0.0;
        int substeps = 0;
        Vec3d endingTangent = frame.tangent();
        double progress = frame.progress();

        if (railPos.asLong() == handoffRailPos) {
            return finishHandoff(cart, frame, horizontalTravel, trackTravel);
        }

        while (remainingTrack > EPSILON) {
            double horizontalLength = segmentHorizontalLength(
                frame.backwardEndpoint(), frame.forwardEndpoint()
            );
            double factor = trackFactor(frame.backwardEndpoint(), frame.forwardEndpoint());
            if (horizontalLength <= EPSILON || !Double.isFinite(factor)) {
                return finishCollision(
                    cart, endingTangent, isSlope(frame), horizontalTravel, trackTravel
                );
            }

            double availableHorizontal = (1.0 - progress) * horizontalLength;
            while (availableHorizontal > EPSILON && remainingTrack > EPSILON) {
                if (substeps >= MAX_SUBSTEPS) {
                    warnAboutLimit(world, cart, movementTrackSpeed);
                    double endingHorizontalSpeed = movementTrackSpeed / factor;
                    cart.setVelocity(endingTangent.multiply(endingHorizontalSpeed));
                    record(cart, new MovementResult(
                        Outcome.SUBSTEP_LIMIT,
                        horizontalTravel,
                        trackTravel,
                        endingTangent,
                        movementTrackSpeed,
                        endingHorizontalSpeed,
                        isSlope(frame)
                    ));
                    return true;
                }

                double stepHorizontal = Math.min(
                    MAX_HORIZONTAL_SUBSTEP,
                    Math.min(availableHorizontal, remainingTrack / factor)
                );
                double progressDelta = stepHorizontal / horizontalLength;
                double nextProgress = Math.min(1.0, progress + progressDelta);
                Vec3d before = cart.getEntityPos();
                double collisionY = pointOnRail(
                    railPos, frame.backwardEndpoint(), frame.forwardEndpoint(), progress
                ).y;
                cart.setPosition(before.x, collisionY, before.z);
                Vec3d requestedMovement = new Vec3d(
                    endingTangent.x * stepHorizontal,
                    0.0,
                    endingTangent.z * stepHorizontal
                );
                boolean entityContact = hasEntityContact(world, cart, requestedMovement);
                cart.move(MovementType.SELF, requestedMovement);

                Vec3d afterHorizontal = cart.getEntityPos();
                double actualHorizontal = Math.clamp(
                    (afterHorizontal.x - before.x) * endingTangent.x
                        + (afterHorizontal.z - before.z) * endingTangent.z,
                    0.0,
                    stepHorizontal
                );
                double completedFraction = actualHorizontal / stepHorizontal;
                double actualTrack = actualHorizontal * factor;
                progress += progressDelta * completedFraction;
                horizontalTravel += actualHorizontal;
                trackTravel += actualTrack;
                remainingTrack -= actualTrack;
                availableHorizontal -= actualHorizontal;
                substeps++;

                snap(cart, pointOnRail(
                    railPos, frame.backwardEndpoint(), frame.forwardEndpoint(), progress
                ));
                if (completedFraction < 1.0 - 1.0E-4 || entityContact) {
                    return finishCollision(
                        cart, endingTangent, isSlope(frame), horizontalTravel, trackTravel
                    );
                }
            }

            if (remainingTrack <= EPSILON) {
                break;
            }

            NextRail next = resolveForwardRail(world, railPos, frame.forwardEndpoint());
            if (next.status() == NextRailStatus.UNLOADED) {
                cart.setVelocity(Vec3d.ZERO);
                record(cart, new MovementResult(
                    Outcome.WAITING_UNLOADED,
                    horizontalTravel,
                    trackTravel,
                    endingTangent,
                    phaseTrackSpeed,
                    horizontalSpeedFromTrack(phaseTrackSpeed, isSlope(frame)),
                    isSlope(frame)
                ));
                return true;
            }
            if (next.status() == NextRailStatus.END) {
                return finishCollision(
                    cart, endingTangent, isSlope(frame), horizontalTravel, trackTravel
                );
            }

            BlockState nextState = world.getBlockState(next.pos());
            if (!RailPathScanner.isPoweredRail(nextState)) {
                return finishCollision(
                    cart, endingTangent, isSlope(frame), horizontalTravel, trackTravel
                );
            }
            RailPathScanner.RailFrame nextFrame = RailPathScanner.frame(
                next.pos(), nextState, cart.getEntityPos(), endingTangent
            );
            if (nextFrame == null) {
                return finishCollision(
                    cart, endingTangent, isSlope(frame), horizontalTravel, trackTravel
                );
            }
            if (next.pos().asLong() == handoffRailPos) {
                return finishHandoff(cart, nextFrame, horizontalTravel, trackTravel);
            }

            railPos = next.pos();
            railState = nextState;
            frame = nextFrame;
            endingTangent = frame.tangent();
            progress = frame.progress();
        }

        double endingHorizontalSpeed = movementTrackSpeed
            / trackFactor(frame.backwardEndpoint(), frame.forwardEndpoint());
        cart.setVelocity(endingTangent.multiply(endingHorizontalSpeed));
        record(cart, new MovementResult(
            Outcome.MOVED,
            horizontalTravel,
            trackTravel,
            endingTangent,
            movementTrackSpeed,
            endingHorizontalSpeed,
            isSlope(frame)
        ));
        return true;
    }

    static double horizontalMovementBudget(double trackSpeed, boolean slope) {
        return horizontalSpeedFromTrack(trackSpeed, slope);
    }

    static double horizontalSpeedFromTrack(double trackSpeed, boolean slope) {
        if (!Double.isFinite(trackSpeed) || trackSpeed <= 0.0) {
            return 0.0;
        }
        return trackSpeed / (slope ? SLOPE_FACTOR : 1.0);
    }

    static double trackSpeedFromHorizontal(double horizontalSpeed, boolean slope) {
        if (!Double.isFinite(horizontalSpeed) || horizontalSpeed <= 0.0) {
            return 0.0;
        }
        return horizontalSpeed * (slope ? SLOPE_FACTOR : 1.0);
    }

    static int minimumSubstepCount(double horizontalBudget) {
        if (!Double.isFinite(horizontalBudget) || horizontalBudget <= 0.0) {
            return 0;
        }
        return (int) Math.min(
            Integer.MAX_VALUE,
            Math.ceil(horizontalBudget / MAX_HORIZONTAL_SUBSTEP)
        );
    }

    static double segmentHorizontalLength(Vec3i backward, Vec3i forward) {
        double deltaX = (forward.getX() - backward.getX()) * 0.5;
        double deltaZ = (forward.getZ() - backward.getZ()) * 0.5;
        return Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
    }

    static double segmentTrackLength(Vec3i backward, Vec3i forward) {
        double horizontal = segmentHorizontalLength(backward, forward);
        double vertical = forward.getY() - backward.getY();
        return Math.sqrt(horizontal * horizontal + vertical * vertical);
    }

    static double trackFactor(Vec3i backward, Vec3i forward) {
        double horizontal = segmentHorizontalLength(backward, forward);
        return horizontal <= EPSILON
            ? Double.NaN
            : segmentTrackLength(backward, forward) / horizontal;
    }

    static Vec3d pointOnRail(
        BlockPos railPos,
        Vec3i backward,
        Vec3i forward,
        double progress
    ) {
        double clamped = Math.clamp(progress, 0.0, 1.0);
        boolean slope = backward.getY() != forward.getY();
        Vec3d start = endpointPosition(railPos, backward, slope);
        Vec3d end = endpointPosition(railPos, forward, slope);
        return new Vec3d(
            start.x + (end.x - start.x) * clamped,
            start.y + (end.y - start.y) * clamped,
            start.z + (end.z - start.z) * clamped
        );
    }

    private static boolean isSlope(RailPathScanner.RailFrame frame) {
        return frame.backwardEndpoint().getY() != frame.forwardEndpoint().getY();
    }

    private static Vec3d endpointPosition(BlockPos railPos, Vec3i endpoint, boolean slope) {
        double y = slope
            ? railPos.getY() + 1.0 + RAIL_HEIGHT_OFFSET + endpoint.getY()
            : railPos.getY() + RAIL_HEIGHT_OFFSET;
        return new Vec3d(
            railPos.getX() + 0.5 + endpoint.getX() * 0.5,
            y,
            railPos.getZ() + 0.5 + endpoint.getZ() * 0.5
        );
    }

    private static boolean finishHandoff(
        MinecartEntity cart,
        RailPathScanner.RailFrame handoffFrame,
        double horizontalTravel,
        double trackTravel
    ) {
        boolean slope = isSlope(handoffFrame);
        double horizontalSpeed = MinecartSpeedManager.cachedVanillaSpeed(cart);
        double trackSpeed = trackSpeedFromHorizontal(horizontalSpeed, slope);
        cart.setVelocity(handoffFrame.tangent().multiply(horizontalSpeed));
        record(cart, new MovementResult(
            Outcome.HANDOFF,
            horizontalTravel,
            trackTravel,
            handoffFrame.tangent(),
            trackSpeed,
            horizontalSpeed,
            slope
        ));
        return true;
    }

    private static boolean finishCollision(
        MinecartEntity cart,
        Vec3d tangent,
        boolean slope,
        double horizontalTravel,
        double trackTravel
    ) {
        double horizontalSpeed = MinecartSpeedManager.cachedVanillaSpeed(cart);
        Vec3d direction = tangent.horizontalLengthSquared() > EPSILON
            ? tangent.getHorizontal().normalize()
            : cart.getVelocity().getHorizontal().normalize();
        cart.setVelocity(direction.multiply(horizontalSpeed));
        record(cart, new MovementResult(
            Outcome.COLLISION,
            horizontalTravel,
            trackTravel,
            direction,
            trackSpeedFromHorizontal(horizontalSpeed, slope),
            horizontalSpeed,
            slope
        ));
        return true;
    }

    private static void snap(MinecartEntity cart, Vec3d point) {
        cart.setPosition(point.x, point.y, point.z);
    }

    private static boolean hasEntityContact(
        ServerWorld world,
        MinecartEntity cart,
        Vec3d requestedMovement
    ) {
        return !world.getOtherEntities(
            cart,
            cart.getBoundingBox().offset(requestedMovement).expand(0.2, 0.0, 0.2),
            EntityPredicates.canBePushedBy(cart)
                .and(entity -> !cart.isConnectedThroughVehicle(entity))
        ).isEmpty();
    }

    private static NextRail resolveForwardRail(
        ServerWorld world,
        BlockPos railPos,
        Vec3i forwardEndpoint
    ) {
        int x = railPos.getX() + forwardEndpoint.getX();
        int y = railPos.getY() + forwardEndpoint.getY();
        int z = railPos.getZ() + forwardEndpoint.getZ();
        BlockPos.Mutable scratch = new BlockPos.Mutable(x, y, z);
        if (!world.isChunkLoaded(scratch)) {
            return NextRail.UNLOADED;
        }
        if (AbstractRailBlock.isRail(world.getBlockState(scratch))) {
            return new NextRail(NextRailStatus.RAIL, scratch.toImmutable());
        }
        scratch.setY(y + 1);
        if (AbstractRailBlock.isRail(world.getBlockState(scratch))) {
            return new NextRail(NextRailStatus.RAIL, scratch.toImmutable());
        }
        scratch.setY(y - 1);
        if (AbstractRailBlock.isRail(world.getBlockState(scratch))) {
            return new NextRail(NextRailStatus.RAIL, scratch.toImmutable());
        }
        return NextRail.END;
    }

    private static void warnAboutLimit(ServerWorld world, MinecartEntity cart, double speed) {
        long time = world.getTime();
        if (time < nextLimitWarningTick) {
            return;
        }
        nextLimitWarningTick = time + WARNING_INTERVAL_TICKS;
        HighSpeedRail.LOGGER.warn(
            "Active minecart movement reached the {} substep limit; cart={} trackSpeed={}",
            MAX_SUBSTEPS,
            cart.getUuid(),
            String.format("%.4f", speed)
        );
    }

    private static void record(MinecartEntity cart, MovementResult result) {
        MinecartSpeedManager.recordGeometryMovement(cart, result);
    }

    public enum Outcome {
        NONE,
        MOVED,
        WAITING_UNLOADED,
        COLLISION,
        SUBSTEP_LIMIT,
        HANDOFF
    }

    public record MovementResult(
        Outcome outcome,
        double horizontalDistance,
        double trackDistance,
        Vec3d endingTangent,
        double endingTrackSpeed,
        double endingHorizontalSpeed,
        boolean endingSlope
    ) {
        public static final MovementResult NONE = new MovementResult(
            Outcome.NONE, 0.0, 0.0, Vec3d.ZERO, 0.0, 0.0, false
        );

        private static MovementResult waiting(
            Vec3d tangent,
            double trackSpeed,
            double horizontalSpeed
        ) {
            return new MovementResult(
                Outcome.WAITING_UNLOADED,
                0.0,
                0.0,
                tangent,
                trackSpeed,
                horizontalSpeed,
                false
            );
        }

        private static MovementResult collision(Vec3d tangent) {
            return new MovementResult(
                Outcome.COLLISION, 0.0, 0.0, tangent, 0.0, 0.0, false
            );
        }
    }

    private enum NextRailStatus {
        RAIL,
        END,
        UNLOADED
    }

    private record NextRail(NextRailStatus status, BlockPos pos) {
        private static final NextRail END = new NextRail(NextRailStatus.END, BlockPos.ORIGIN);
        private static final NextRail UNLOADED = new NextRail(NextRailStatus.UNLOADED, BlockPos.ORIGIN);
    }

    private RailGeometryMover() {
    }
}
