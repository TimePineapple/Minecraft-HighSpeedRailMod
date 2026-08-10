package com.timepineapple.highspeedrail.minecart;

public final class SpeedProfile {
    private static final double EPSILON = 1.0E-9;

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
        return Math.abs((targetSpeed - startSpeed) * (targetSpeed + startSpeed)) / (2.0 * baseAcceleration);
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
        double eased = smootherstep(progress);
        double startSquared = startSpeed * startSpeed;
        double targetSquared = targetSpeed * targetSpeed;
        double speedSquared = startSquared + (targetSquared - startSquared) * eased;
        return Math.sqrt(Math.max(0.0, speedSquared));
    }

    public static double smootherstep(double progress) {
        double value = Math.clamp(progress, 0.0, 1.0);
        return value * value * value * (value * (value * 6.0 - 15.0) + 10.0);
    }

    private SpeedProfile() {
    }
}
