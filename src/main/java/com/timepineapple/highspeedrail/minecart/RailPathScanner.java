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

    private RailPathScanner() {
    }
}
