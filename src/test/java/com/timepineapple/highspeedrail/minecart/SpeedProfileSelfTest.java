package com.timepineapple.highspeedrail.minecart;

public final class SpeedProfileSelfTest {
    private static final double EPSILON = 1.0E-9;

    public static void main(String[] args) {
        double vanillaSpeed = 0.4;
        double maxSpeed = 1.2;
        int activeBlocks = 16;
        double acceleration = SpeedProfile.baseAcceleration(maxSpeed, vanillaSpeed, activeBlocks);

        requireClose(acceleration, 0.04, "base acceleration");
        requireClose(SpeedProfile.distance(vanillaSpeed, maxSpeed, acceleration), 16.0, "v2 to v1 distance");
        requireClose(SpeedProfile.distance(0.0, maxSpeed, acceleration), 18.0, "zero to v1 distance");
        requireClose(SpeedProfile.distance(maxSpeed, vanillaSpeed, acceleration), 16.0, "v1 to v2 distance");
        requireClose(SpeedProfile.speedAt(vanillaSpeed, maxSpeed, 0.0, 16.0), vanillaSpeed, "acceleration start");
        requireClose(SpeedProfile.speedAt(vanillaSpeed, maxSpeed, 16.0, 16.0), maxSpeed, "acceleration end");
        requireClose(SpeedProfile.speedAt(maxSpeed, vanillaSpeed, 16.0, 16.0), vanillaSpeed, "braking end");

        double previousAccelerationSpeed = vanillaSpeed;
        double previousBrakingSpeed = maxSpeed;
        double squaredSum = vanillaSpeed * vanillaSpeed + maxSpeed * maxSpeed;
        for (int step = 0; step <= 64; step++) {
            double distance = activeBlocks * step / 64.0;
            double accelerating = SpeedProfile.speedAt(vanillaSpeed, maxSpeed, distance, activeBlocks);
            double braking = SpeedProfile.speedAt(maxSpeed, vanillaSpeed, distance, activeBlocks);
            require(accelerating + EPSILON >= previousAccelerationSpeed, "acceleration must be monotonic");
            require(braking <= previousBrakingSpeed + EPSILON, "braking must be monotonic");
            requireClose(accelerating * accelerating + braking * braking, squaredSum, "mirrored energy curve");
            previousAccelerationSpeed = accelerating;
            previousBrakingSpeed = braking;
        }
    }

    private static void requireClose(double actual, double expected, String label) {
        require(Math.abs(actual - expected) <= EPSILON, label + ": expected " + expected + ", got " + actual);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private SpeedProfileSelfTest() {
    }
}
