package com.timepineapple.highspeedrail.minecart;

public final class SpeedProfile {
    private static final double EPSILON = 1.0E-9;
    private static final int TICKS_PER_SECOND = 20;

    public static double configuredAcceleration(double maxSpeed, int accelerationSeconds) {
        if (!Double.isFinite(maxSpeed) || maxSpeed <= 0.0 || accelerationSeconds < 1) {
            return 0.0;
        }
        return maxSpeed / (TICKS_PER_SECOND * (double) accelerationSeconds);
    }

    public static double moveTowards(
        double startSpeed,
        double targetSpeed,
        double acceleration,
        double elapsedTicks
    ) {
        if (!Double.isFinite(startSpeed) || !Double.isFinite(targetSpeed)
            || !Double.isFinite(acceleration) || !Double.isFinite(elapsedTicks)
            || acceleration <= EPSILON || elapsedTicks <= 0.0) {
            return startSpeed;
        }
        double delta = acceleration * elapsedTicks;
        return targetSpeed >= startSpeed
            ? Math.min(targetSpeed, startSpeed + delta)
            : Math.max(targetSpeed, startSpeed - delta);
    }

    public static double brakingAcceleration(double startSpeed, double targetSpeed, double distance) {
        if (!Double.isFinite(startSpeed) || !Double.isFinite(targetSpeed) || !Double.isFinite(distance)
            || startSpeed <= targetSpeed || distance <= EPSILON) {
            return 0.0;
        }
        return (startSpeed - targetSpeed) * (startSpeed + targetSpeed) / (2.0 * distance);
    }

    public static double distance(double startSpeed, double targetSpeed, double acceleration) {
        if (!Double.isFinite(startSpeed) || !Double.isFinite(targetSpeed)
            || !Double.isFinite(acceleration) || acceleration <= EPSILON) {
            return 0.0;
        }
        return Math.abs((targetSpeed - startSpeed) * (targetSpeed + startSpeed)) / (2.0 * acceleration);
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
        double speedSquared = startSquared + (targetSquared - startSquared) * progress;
        return Math.sqrt(Math.max(0.0, speedSquared));
    }

    private SpeedProfile() {
    }
}
