package com.timepineapple.highspeedrail.minecart;

public final class SpeedProfileSelfTest {
    private static final double EPSILON = 1.0E-9;
    private static final int SAMPLE_COUNT = 256;

    public static void main(String[] args) {
        testRequestedFourOverSixtyFourBlocks();
        testVanillaAccelerationFloor();
    }

    private static void testRequestedFourOverSixtyFourBlocks() {
        double vanillaSpeed = 0.4;
        double maxSpeed = 4.0;
        int activeBlocks = 64;
        double requestedAverage = SpeedProfile.baseAcceleration(maxSpeed, vanillaSpeed, activeBlocks);
        double effectiveAverage = SpeedProfile.effectiveAverageAcceleration(requestedAverage);
        double distance = SpeedProfile.distance(vanillaSpeed, maxSpeed, requestedAverage);

        requireClose(requestedAverage, 0.12375, "requested average acceleration for 4/64");
        requireClose(effectiveAverage, 0.12375, "effective average acceleration for 4/64");
        requireClose(SpeedProfile.finalAcceleration(requestedAverage), 0.1875, "final acceleration for 4/64");
        requireClose(distance, activeBlocks, "v2 to v1 distance for 4/64");
        requireClose(SpeedProfile.distance(maxSpeed, vanillaSpeed, requestedAverage), activeBlocks,
            "v1 to v2 distance for 4/64");
        verifyLinearMirroredProfile(vanillaSpeed, maxSpeed, distance, 0.06, 0.1875);
    }

    private static void testVanillaAccelerationFloor() {
        double vanillaSpeed = 0.4;
        double maxSpeed = 1.2;
        int activeBlocks = 16;
        double requestedAverage = SpeedProfile.baseAcceleration(maxSpeed, vanillaSpeed, activeBlocks);
        double distance = SpeedProfile.distance(vanillaSpeed, maxSpeed, requestedAverage);

        requireClose(requestedAverage, 0.04, "requested average acceleration below vanilla");
        requireClose(SpeedProfile.effectiveAverageAcceleration(requestedAverage), 0.06,
            "effective average acceleration floor");
        requireClose(SpeedProfile.finalAcceleration(requestedAverage), 0.06,
            "constant acceleration at vanilla floor");
        requireClose(distance, 32.0 / 3.0, "early arrival distance at vanilla floor");
        require(distance < activeBlocks, "vanilla acceleration floor must arrive before activeBlocks");
        requireClose(SpeedProfile.distance(0.0, maxSpeed, requestedAverage), 12.0,
            "stationary theoretical distance at vanilla floor");
        verifyLinearMirroredProfile(vanillaSpeed, maxSpeed, distance, 0.06, 0.06);
    }

    private static void verifyLinearMirroredProfile(
        double startSpeed,
        double targetSpeed,
        double totalDistance,
        double expectedInitialAcceleration,
        double expectedFinalAcceleration
    ) {
        requireClose(SpeedProfile.speedAt(startSpeed, targetSpeed, 0.0, totalDistance), startSpeed,
            "acceleration start speed");
        requireClose(SpeedProfile.speedAt(startSpeed, targetSpeed, totalDistance, totalDistance), targetSpeed,
            "acceleration end speed");
        requireClose(SpeedProfile.speedAt(targetSpeed, startSpeed, totalDistance, totalDistance), startSpeed,
            "braking end speed");

        double stepDistance = totalDistance / SAMPLE_COUNT;
        double previousAccelerationSpeed = startSpeed;
        double previousBrakingSpeed = targetSpeed;
        double previousAcceleration = Double.NEGATIVE_INFINITY;
        double previousBrakingMagnitude = Double.POSITIVE_INFINITY;
        for (int step = 1; step <= SAMPLE_COUNT; step++) {
            double distance = stepDistance * step;
            double accelerating = SpeedProfile.speedAt(startSpeed, targetSpeed, distance, totalDistance);
            double braking = SpeedProfile.speedAt(targetSpeed, startSpeed, distance, totalDistance);
            double acceleration = (accelerating * accelerating
                - previousAccelerationSpeed * previousAccelerationSpeed) / (2.0 * stepDistance);
            double brakingMagnitude = (previousBrakingSpeed * previousBrakingSpeed
                - braking * braking) / (2.0 * stepDistance);

            require(acceleration + EPSILON >= expectedInitialAcceleration,
                "acceleration must never fall below the vanilla floor");
            require(acceleration + EPSILON >= previousAcceleration,
                "acceleration must only increase");
            require(brakingMagnitude + EPSILON >= expectedInitialAcceleration,
                "braking magnitude must never fall below the vanilla floor");
            require(brakingMagnitude <= previousBrakingMagnitude + EPSILON,
                "braking magnitude must only decrease");

            double mirroredBraking = SpeedProfile.speedAt(
                targetSpeed, startSpeed, totalDistance - distance, totalDistance
            );
            requireClose(accelerating, mirroredBraking, "distance-mirrored speed");
            previousAccelerationSpeed = accelerating;
            previousBrakingSpeed = braking;
            previousAcceleration = acceleration;
            previousBrakingMagnitude = brakingMagnitude;
        }

        double halfStepAdjustment = (expectedFinalAcceleration - expectedInitialAcceleration)
            / (2.0 * SAMPLE_COUNT);
        requireClose(previousAcceleration, expectedFinalAcceleration - halfStepAdjustment,
            "last acceleration segment");
        requireClose(previousBrakingMagnitude, expectedInitialAcceleration + halfStepAdjustment,
            "last braking segment");
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
