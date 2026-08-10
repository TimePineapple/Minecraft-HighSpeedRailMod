package com.timepineapple.highspeedrail.minecart;

public final class SpeedProfile {
    private static final double EPSILON = 1.0E-9;
    public static final double VANILLA_POWERED_RAIL_ACCELERATION = 0.06;

    public static double baseAcceleration(double maxSpeed, double vanillaSpeed, int activeBlocks) {
        if (!Double.isFinite(maxSpeed) || !Double.isFinite(vanillaSpeed) || activeBlocks < 1 || maxSpeed <= vanillaSpeed) {
            return 0.0;
        }
        return (maxSpeed - vanillaSpeed) * (maxSpeed + vanillaSpeed) / (2.0 * activeBlocks);
    }

    public static double distance(double startSpeed, double targetSpeed, double baseAcceleration) {
        if (!Double.isFinite(startSpeed) || !Double.isFinite(targetSpeed)
            || !Double.isFinite(baseAcceleration) || baseAcceleration <= EPSILON) {
            return 0.0;
        }
        return Math.abs((targetSpeed - startSpeed) * (targetSpeed + startSpeed))
            / (2.0 * effectiveAverageAcceleration(baseAcceleration));
    }

    public static double effectiveAverageAcceleration(double requestedAverageAcceleration) {
        if (!Double.isFinite(requestedAverageAcceleration) || requestedAverageAcceleration <= 0.0) {
            return 0.0;
        }
        return Math.max(requestedAverageAcceleration, VANILLA_POWERED_RAIL_ACCELERATION);
    }

    public static double finalAcceleration(double requestedAverageAcceleration) {
        double effectiveAverage = effectiveAverageAcceleration(requestedAverageAcceleration);
        if (effectiveAverage <= 0.0) {
            return 0.0;
        }
        return 2.0 * effectiveAverage - VANILLA_POWERED_RAIL_ACCELERATION;
    }

    public static double speedAt(
        double startSpeed,
        double targetSpeed,
        double distanceTravelled,
        double totalDistance
    ) {
        if (totalDistance <= EPSILON || distanceTravelled >= totalDistance) {
            return targetSpeed;
        }
        if (distanceTravelled <= 0.0) {
            return startSpeed;
        }

        double progress = Math.clamp(distanceTravelled / totalDistance, 0.0, 1.0);
        double startSquared = startSpeed * startSpeed;
        double targetSquared = targetSpeed * targetSpeed;
        double energyDifference = Math.abs(targetSquared - startSquared);
        double averageAcceleration = energyDifference / (2.0 * totalDistance);
        if (!Double.isFinite(averageAcceleration) || averageAcceleration <= EPSILON) {
            return targetSpeed;
        }

        double initialRatio = Math.clamp(
            VANILLA_POWERED_RAIL_ACCELERATION / averageAcceleration,
            0.0,
            1.0
        );
        double energyProgress;
        if (targetSpeed >= startSpeed) {
            energyProgress = initialRatio * progress
                + (1.0 - initialRatio) * progress * progress;
        } else {
            energyProgress = (2.0 - initialRatio) * progress
                - (1.0 - initialRatio) * progress * progress;
        }

        double speedSquared = startSquared + (targetSquared - startSquared) * energyProgress;
        return Math.sqrt(Math.max(0.0, speedSquared));
    }

    private SpeedProfile() {
    }
}
