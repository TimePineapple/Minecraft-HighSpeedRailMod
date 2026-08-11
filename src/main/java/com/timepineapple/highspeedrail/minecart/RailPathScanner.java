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
    private static final int HANDOFF_RAILS = 8;

    public static PoweredPath scanPoweredDistanceAhead(
        ServerWorld world,
        BlockPos startingRailPos,
        Vec3d entityPos,
        Vec3d direction,
        double stopAfterDistance
    ) {
        if (stopAfterDistance <= 0.0
            || direction.horizontalLengthSquared() < MIN_DIRECTION_SQUARED
            || !world.isChunkLoaded(startingRailPos)) {
            return PoweredPath.EMPTY;
        }

        BlockState startingState = world.getBlockState(startingRailPos);
        if (!isPoweredRail(startingState)) {
            return PoweredPath.EMPTY;
        }
        RailFrame frame = frame(startingRailPos, startingState, entityPos, direction);
        if (frame == null) {
            return PoweredPath.EMPTY;
        }

        double distance = Math.max(0.0, 1.0 - frame.progress());
        int fullRails = 0;
        double lastEntryDistance = -frame.progress();
        long lastPoweredRail = startingRailPos.asLong();
        long[] lastEight = new long[HANDOFF_RAILS];
        if (distance >= stopAfterDistance) {
            return path(distance, fullRails, lastEntryDistance, lastPoweredRail,
                lastEight, false, false);
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
            frame.forwardEndpoint(),
            currentScratch
        );
        int maximumSteps = Math.max(
            2,
            (int) Math.min(Integer.MAX_VALUE, Math.ceil(stopAfterDistance) + 2.0)
        );

        for (int scanned = 0; scanned < maximumSteps; scanned++) {
            if (current == UNLOADED) {
                return path(distance, fullRails, lastEntryDistance, lastPoweredRail,
                    lastEight, false, true);
            }
            if (current == NO_RAIL) {
                return path(distance, fullRails, lastEntryDistance, lastPoweredRail,
                    lastEight, true, false);
            }

            currentScratch.set(current);
            BlockState state = world.getBlockState(currentScratch);
            if (!isPoweredRail(state)) {
                return path(distance, fullRails, lastEntryDistance, lastPoweredRail,
                    lastEight, true, false);
            }

            lastEntryDistance = distance;
            lastPoweredRail = current;
            lastEight[fullRails % HANDOFF_RAILS] = current;
            fullRails++;
            distance += 1.0;
            if (distance >= stopAfterDistance) {
                return path(distance, fullRails, lastEntryDistance, lastPoweredRail,
                    lastEight, false, false);
            }

            long next = nextRail(
                world, previous, current, state,
                currentScratch, firstScratch, secondScratch
            );
            previous = current;
            current = next;
        }

        return path(distance, fullRails, lastEntryDistance, lastPoweredRail,
            lastEight, false, false);
    }

    public static RailFrame frame(
        BlockPos railPos,
        BlockState state,
        Vec3d entityPos,
        Vec3d direction
    ) {
        if (!(state.getBlock() instanceof AbstractRailBlock rail)) {
            return null;
        }
        Pair<Vec3i, Vec3i> endpoints = AbstractMinecartEntity.getAdjacentRailPositionsByShape(
            state.get(rail.getShapeProperty())
        );
        Vec3i forward = chooseForwardEndpoint(endpoints, direction.x, direction.z);
        if (forward == null) {
            return null;
        }
        Vec3i backward = forward == endpoints.getFirst()
            ? endpoints.getSecond()
            : endpoints.getFirst();
        return new RailFrame(
            progress(railPos, backward, forward, entityPos),
            tangent(backward, forward),
            backward,
            forward
        );
    }

    public static double railSpeed(Vec3d velocity, Vec3d tangent) {
        double lengthSquared = tangent.lengthSquared();
        return lengthSquared < MIN_DIRECTION_SQUARED
            ? 0.0
            : Math.abs(velocity.dotProduct(tangent) / lengthSquared);
    }

    public static boolean isPoweredRail(BlockState state) {
        return state.isOf(Blocks.POWERED_RAIL)
            && state.getBlock() instanceof PoweredRailBlock
            && state.contains(PoweredRailBlock.POWERED)
            && state.get(PoweredRailBlock.POWERED);
    }

    private static PoweredPath path(
        double distance,
        int fullRails,
        double lastEntryDistance,
        long lastPoweredRail,
        long[] lastEight,
        boolean reachedEnd,
        boolean stoppedAtUnloaded
    ) {
        long handoffRail = fullRails < HANDOFF_RAILS
            ? Long.MIN_VALUE
            : lastEight[(fullRails - HANDOFF_RAILS) % HANDOFF_RAILS];
        return new PoweredPath(
            distance,
            fullRails,
            lastEntryDistance,
            lastPoweredRail,
            handoffRail,
            reachedEnd,
            stoppedAtUnloaded
        );
    }

    private static Vec3i chooseForwardEndpoint(
        Pair<Vec3i, Vec3i> endpoints,
        double directionX,
        double directionZ
    ) {
        Vec3i first = endpoints.getFirst();
        Vec3i second = endpoints.getSecond();
        double firstDot = first.getX() * directionX + first.getZ() * directionZ;
        double secondDot = second.getX() * directionX + second.getZ() * directionZ;
        return Math.abs(firstDot - secondDot) < 1.0E-9
            ? null
            : firstDot > secondDot ? first : second;
    }

    private static double progress(
        BlockPos railPos,
        Vec3i backward,
        Vec3i forward,
        Vec3d entityPos
    ) {
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
        return Math.clamp(
            ((entityPos.x - startX) * deltaX + (entityPos.z - startZ) * deltaZ)
                / lengthSquared,
            0.0,
            1.0
        );
    }

    static Vec3d tangent(Vec3i backward, Vec3i forward) {
        Vec3d horizontal = new Vec3d(
            (forward.getX() - backward.getX()) * 0.5,
            0.0,
            (forward.getZ() - backward.getZ()) * 0.5
        );
        return horizontal.lengthSquared() < MIN_DIRECTION_SQUARED
            ? Vec3d.ZERO
            : horizontal.normalize();
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
        long first = resolveEndpoint(
            world, currentScratch.getX(), currentScratch.getY(), currentScratch.getZ(),
            endpoints.getFirst(), firstScratch
        );
        long second = resolveEndpoint(
            world, currentScratch.getX(), currentScratch.getY(), currentScratch.getZ(),
            endpoints.getSecond(), secondScratch
        );
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

    public record PoweredPath(
        double distance,
        int fullPoweredRailsAhead,
        double lastRailEntryDistance,
        long lastPoweredRailPos,
        long handoffRailPos,
        boolean reachedEnd,
        boolean stoppedAtUnloadedChunk
    ) {
        private static final PoweredPath EMPTY = new PoweredPath(
            0.0, 0, 0.0, Long.MIN_VALUE, Long.MIN_VALUE, true, false
        );
    }

    public record RailFrame(
        double progress,
        Vec3d tangent,
        Vec3i backwardEndpoint,
        Vec3i forwardEndpoint
    ) {
    }

    private RailPathScanner() {
    }
}
