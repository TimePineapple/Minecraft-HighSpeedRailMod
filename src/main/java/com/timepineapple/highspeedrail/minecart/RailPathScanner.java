package com.timepineapple.highspeedrail.minecart;

import com.mojang.datafixers.util.Pair;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PoweredRailBlock;
import net.minecraft.block.enums.RailShape;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

public final class RailPathScanner {
    private static final long NO_RAIL = Long.MIN_VALUE;
    private static final long UNLOADED = Long.MAX_VALUE;
    private static final double MIN_DIRECTION_SQUARED = 1.0E-8;

    public static PoweredPath scanPoweredDistanceAhead(
        ServerWorld world,
        BlockPos startingRailPos,
        Vec3d entityPos,
        Vec3d velocity,
        double stopAfterDistance
    ) {
        if (stopAfterDistance <= 0.0 || velocity.horizontalLengthSquared() < MIN_DIRECTION_SQUARED
            || !world.isChunkLoaded(startingRailPos)) {
            return PoweredPath.EMPTY;
        }

        BlockState startingState = world.getBlockState(startingRailPos);
        if (!isPoweredRail(startingState)) {
            return PoweredPath.EMPTY;
        }

        RailFrame startingFrame = frame(startingRailPos, startingState, entityPos, velocity);
        if (startingFrame == null) {
            return PoweredPath.EMPTY;
        }

        double distance = Math.max(0.0, 1.0 - startingFrame.progress());
        if (distance >= stopAfterDistance) {
            return new PoweredPath(distance, false, false);
        }

        BlockPos.Mutable currentScratch = new BlockPos.Mutable();
        BlockPos.Mutable firstScratch = new BlockPos.Mutable();
        BlockPos.Mutable secondScratch = new BlockPos.Mutable();
        long previous = startingRailPos.asLong();
        long current = resolveEndpoint(
            world,
            startingRailPos.getX(),
            startingRailPos.getY(),
            startingRailPos.getZ(),
            startingFrame.forwardEndpoint(),
            currentScratch
        );
        int maximumSteps = Math.max(2, (int) Math.min(Integer.MAX_VALUE, Math.ceil(stopAfterDistance) + 2.0));

        for (int scanned = 0; scanned < maximumSteps; scanned++) {
            if (current == UNLOADED) {
                return new PoweredPath(distance, false, true);
            }
            if (current == NO_RAIL) {
                return new PoweredPath(distance, true, false);
            }

            currentScratch.set(current);
            BlockState state = world.getBlockState(currentScratch);
            if (!isPoweredRail(state)) {
                return new PoweredPath(distance, true, false);
            }

            distance += 1.0;
            if (distance >= stopAfterDistance) {
                return new PoweredPath(distance, false, false);
            }

            long next = nextRail(world, previous, current, state, currentScratch, firstScratch, secondScratch);
            previous = current;
            current = next;
        }

        return new PoweredPath(distance, false, false);
    }

    public static TravelMeasure measureRailTravel(
        ServerWorld world,
        BlockPos startingRailPos,
        Vec3d startingEntityPos,
        BlockPos endingRailPos,
        Vec3d endingEntityPos,
        Vec3d startingVelocity,
        int maximumSteps
    ) {
        if (!world.isChunkLoaded(startingRailPos) || !world.isChunkLoaded(endingRailPos)) {
            return TravelMeasure.INVALID;
        }

        BlockState startingState = world.getBlockState(startingRailPos);
        if (!(startingState.getBlock() instanceof AbstractRailBlock)) {
            return TravelMeasure.INVALID;
        }
        RailFrame startingFrame = frame(startingRailPos, startingState, startingEntityPos, startingVelocity);
        if (startingFrame == null) {
            return TravelMeasure.INVALID;
        }

        if (startingRailPos.equals(endingRailPos)) {
            double endingProgress = progress(startingRailPos, startingFrame.backwardEndpoint(), startingFrame.forwardEndpoint(), endingEntityPos);
            double travelled = endingProgress - startingFrame.progress();
            return travelled >= -1.0E-5
                ? new TravelMeasure(Math.max(0.0, travelled), startingFrame.tangent(), true)
                : TravelMeasure.INVALID;
        }

        BlockPos.Mutable currentScratch = new BlockPos.Mutable();
        BlockPos.Mutable firstScratch = new BlockPos.Mutable();
        BlockPos.Mutable secondScratch = new BlockPos.Mutable();
        long previous = startingRailPos.asLong();
        long current = resolveEndpoint(
            world,
            startingRailPos.getX(),
            startingRailPos.getY(),
            startingRailPos.getZ(),
            startingFrame.forwardEndpoint(),
            currentScratch
        );
        double travelled = Math.max(0.0, 1.0 - startingFrame.progress());

        for (int scanned = 0; scanned < Math.max(1, maximumSteps); scanned++) {
            if (current == NO_RAIL || current == UNLOADED) {
                return TravelMeasure.INVALID;
            }

            currentScratch.set(current);
            BlockState state = world.getBlockState(currentScratch);
            if (!(state.getBlock() instanceof AbstractRailBlock rail)) {
                return TravelMeasure.INVALID;
            }
            Pair<Vec3i, Vec3i> endpoints = AbstractMinecartEntity.getAdjacentRailPositionsByShape(state.get(rail.getShapeProperty()));
            long first = resolveEndpoint(world, currentScratch.getX(), currentScratch.getY(), currentScratch.getZ(), endpoints.getFirst(), firstScratch);
            long second = resolveEndpoint(world, currentScratch.getX(), currentScratch.getY(), currentScratch.getZ(), endpoints.getSecond(), secondScratch);
            Vec3i backward;
            Vec3i forward;
            if (first == previous) {
                backward = endpoints.getFirst();
                forward = endpoints.getSecond();
            } else if (second == previous) {
                backward = endpoints.getSecond();
                forward = endpoints.getFirst();
            } else {
                return TravelMeasure.INVALID;
            }

            Vec3d tangent = tangent(backward, forward);
            if (current == endingRailPos.asLong()) {
                travelled += progress(currentScratch, backward, forward, endingEntityPos);
                return new TravelMeasure(travelled, tangent, true);
            }

            travelled += 1.0;
            long next = forward == endpoints.getFirst() ? first : second;
            previous = current;
            current = next;
        }

        return TravelMeasure.INVALID;
    }

    public static RailFrame frame(BlockPos railPos, BlockState state, Vec3d entityPos, Vec3d velocity) {
        if (!(state.getBlock() instanceof AbstractRailBlock rail)) {
            return null;
        }
        RailShape shape = state.get(rail.getShapeProperty());
        Pair<Vec3i, Vec3i> endpoints = AbstractMinecartEntity.getAdjacentRailPositionsByShape(shape);
        Vec3i forward = chooseForwardEndpoint(endpoints, velocity.x, velocity.z);
        if (forward == null) {
            return null;
        }
        Vec3i backward = forward == endpoints.getFirst() ? endpoints.getSecond() : endpoints.getFirst();
        return new RailFrame(
            progress(railPos, backward, forward, entityPos),
            tangent(backward, forward),
            backward,
            forward
        );
    }

    public static double railSpeed(Vec3d velocity, Vec3d tangent) {
        double lengthSquared = tangent.lengthSquared();
        return lengthSquared < MIN_DIRECTION_SQUARED ? 0.0 : Math.abs(velocity.dotProduct(tangent) / lengthSquared);
    }

    public static ScanResult countPoweredRailsAhead(
        ServerWorld world,
        BlockPos startingRailPos,
        Vec3d velocity,
        int stopAfter
    ) {
        if (stopAfter <= 0 || velocity.horizontalLengthSquared() < MIN_DIRECTION_SQUARED) {
            return ScanResult.EMPTY;
        }
        if (!world.isChunkLoaded(startingRailPos)) {
            return new ScanResult(0, true);
        }

        BlockState startingState = world.getBlockState(startingRailPos);
        if (!(startingState.getBlock() instanceof AbstractRailBlock startingRail)) {
            return ScanResult.EMPTY;
        }

        RailShape startingShape = startingState.get(startingRail.getShapeProperty());
        Pair<Vec3i, Vec3i> endpoints = AbstractMinecartEntity.getAdjacentRailPositionsByShape(startingShape);
        Vec3i forwardEndpoint = chooseForwardEndpoint(endpoints, velocity.x, velocity.z);
        if (forwardEndpoint == null) {
            return ScanResult.EMPTY;
        }

        // These three mutable positions are reused for the entire traversal. Active carts do not
        // allocate a list or one BlockPos per scanned rail.
        BlockPos.Mutable currentScratch = new BlockPos.Mutable();
        BlockPos.Mutable firstScratch = new BlockPos.Mutable();
        BlockPos.Mutable secondScratch = new BlockPos.Mutable();
        long previous = startingRailPos.asLong();
        long current = resolveEndpoint(
            world,
            startingRailPos.getX(),
            startingRailPos.getY(),
            startingRailPos.getZ(),
            forwardEndpoint,
            currentScratch
        );
        int poweredCount = 0;

        for (int scanned = 0; scanned < stopAfter; scanned++) {
            if (current == UNLOADED) {
                return new ScanResult(poweredCount, true);
            }
            if (current == NO_RAIL) {
                return new ScanResult(poweredCount, false);
            }

            currentScratch.set(current);
            BlockState state = world.getBlockState(currentScratch);
            if (!isPoweredRail(state)) {
                return new ScanResult(poweredCount, false);
            }

            poweredCount++;
            if (poweredCount >= stopAfter) {
                return new ScanResult(poweredCount, false);
            }

            AbstractRailBlock rail = (AbstractRailBlock) state.getBlock();
            RailShape shape = state.get(rail.getShapeProperty());
            Pair<Vec3i, Vec3i> currentEndpoints = AbstractMinecartEntity.getAdjacentRailPositionsByShape(shape);
            long first = resolveEndpoint(
                world,
                currentScratch.getX(),
                currentScratch.getY(),
                currentScratch.getZ(),
                currentEndpoints.getFirst(),
                firstScratch
            );
            long second = resolveEndpoint(
                world,
                currentScratch.getX(),
                currentScratch.getY(),
                currentScratch.getZ(),
                currentEndpoints.getSecond(),
                secondScratch
            );

            long next;
            if (first == previous) {
                next = second;
            } else if (second == previous) {
                next = first;
            } else {
                double travelX = currentScratch.getX() - BlockPos.unpackLongX(previous);
                double travelZ = currentScratch.getZ() - BlockPos.unpackLongZ(previous);
                Vec3i outgoing = chooseForwardEndpoint(currentEndpoints, travelX, travelZ);
                next = outgoing == currentEndpoints.getFirst() ? first : outgoing == currentEndpoints.getSecond() ? second : NO_RAIL;
            }

            if (next == previous) {
                return new ScanResult(poweredCount, false);
            }
            previous = current;
            current = next;
        }

        return new ScanResult(poweredCount, false);
    }

    public static boolean isPoweredRail(BlockState state) {
        // Activator rails also use PoweredRailBlock in 1.21.11, so the exact vanilla block is required.
        return state.isOf(Blocks.POWERED_RAIL)
            && state.getBlock() instanceof PoweredRailBlock
            && state.contains(PoweredRailBlock.POWERED)
            && state.get(PoweredRailBlock.POWERED);
    }

    private static Vec3i chooseForwardEndpoint(Pair<Vec3i, Vec3i> endpoints, double directionX, double directionZ) {
        Vec3i first = endpoints.getFirst();
        Vec3i second = endpoints.getSecond();
        double firstDot = first.getX() * directionX + first.getZ() * directionZ;
        double secondDot = second.getX() * directionX + second.getZ() * directionZ;
        if (Math.abs(firstDot - secondDot) < 1.0E-9) {
            return null;
        }
        return firstDot > secondDot ? first : second;
    }

    private static double progress(BlockPos railPos, Vec3i backward, Vec3i forward, Vec3d entityPos) {
        double startX = railPos.getX() + 0.5 + backward.getX() * 0.5;
        double startZ = railPos.getZ() + 0.5 + backward.getZ() * 0.5;
        double endX = railPos.getX() + 0.5 + forward.getX() * 0.5;
        double endZ = railPos.getZ() + 0.5 + forward.getZ() * 0.5;
        double deltaX = endX - startX;
        double deltaZ = endZ - startZ;
        double lengthSquared = deltaX * deltaX + deltaZ * deltaZ;
        if (lengthSquared < MIN_DIRECTION_SQUARED) {
            return 0.0;
        }
        double projected = ((entityPos.x - startX) * deltaX + (entityPos.z - startZ) * deltaZ) / lengthSquared;
        return Math.clamp(projected, 0.0, 1.0);
    }

    private static Vec3d tangent(Vec3i backward, Vec3i forward) {
        return new Vec3d(
            (forward.getX() - backward.getX()) * 0.5,
            Integer.compare(forward.getY(), backward.getY()),
            (forward.getZ() - backward.getZ()) * 0.5
        );
    }

    private static long nextRail(
        ServerWorld world,
        long previous,
        long current,
        BlockState state,
        BlockPos.Mutable currentScratch,
        BlockPos.Mutable firstScratch,
        BlockPos.Mutable secondScratch
    ) {
        AbstractRailBlock rail = (AbstractRailBlock) state.getBlock();
        RailShape shape = state.get(rail.getShapeProperty());
        Pair<Vec3i, Vec3i> endpoints = AbstractMinecartEntity.getAdjacentRailPositionsByShape(shape);
        long first = resolveEndpoint(world, currentScratch.getX(), currentScratch.getY(), currentScratch.getZ(), endpoints.getFirst(), firstScratch);
        long second = resolveEndpoint(world, currentScratch.getX(), currentScratch.getY(), currentScratch.getZ(), endpoints.getSecond(), secondScratch);
        if (first == previous) {
            return second;
        }
        if (second == previous) {
            return first;
        }
        return NO_RAIL;
    }

    private static long resolveEndpoint(
        ServerWorld world,
        int x,
        int y,
        int z,
        Vec3i endpoint,
        BlockPos.Mutable scratch
    ) {
        int targetX = x + endpoint.getX();
        int targetY = y + endpoint.getY();
        int targetZ = z + endpoint.getZ();
        scratch.set(targetX, targetY, targetZ);
        if (!world.isChunkLoaded(scratch)) {
            return UNLOADED;
        }

        if (AbstractRailBlock.isRail(world.getBlockState(scratch))) {
            return scratch.asLong();
        }
        scratch.setY(targetY + 1);
        if (AbstractRailBlock.isRail(world.getBlockState(scratch))) {
            return scratch.asLong();
        }
        scratch.setY(targetY - 1);
        if (AbstractRailBlock.isRail(world.getBlockState(scratch))) {
            return scratch.asLong();
        }
        return NO_RAIL;
    }

    public record ScanResult(int poweredRailCount, boolean stoppedAtUnloadedChunk) {
        private static final ScanResult EMPTY = new ScanResult(0, false);
    }

    public record PoweredPath(double distance, boolean reachedEnd, boolean stoppedAtUnloadedChunk) {
        private static final PoweredPath EMPTY = new PoweredPath(0.0, true, false);
    }

    public record RailFrame(double progress, Vec3d tangent, Vec3i backwardEndpoint, Vec3i forwardEndpoint) {
    }

    public record TravelMeasure(double distance, Vec3d tangent, boolean valid) {
        private static final TravelMeasure INVALID = new TravelMeasure(0.0, Vec3d.ZERO, false);
    }

    private RailPathScanner() {
    }
}
