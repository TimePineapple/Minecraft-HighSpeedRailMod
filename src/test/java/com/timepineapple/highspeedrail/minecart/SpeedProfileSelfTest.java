package com.timepineapple.highspeedrail.minecart;

public final class SpeedProfileSelfTest {
    private static final double EPSILON = 1.0E-9;
    private static final int SAMPLE_COUNT = 256;

    public static void main(String[] args) {
        testRequestedFourOverSixtyFourBlocks();
        testRequestedFourOverOneHundredTwentyEightBlocks();
        testVanillaAccelerationFloor();
        testUnloadedChunkKeepsStableConfirmedDistance();
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

    private static void testRequestedFourOverOneHundredTwentyEightBlocks() {
        double vanillaSpeed = 0.4;
        double maxSpeed = 4.0;
        int activeBlocks = 128;
        double requestedAverage = SpeedProfile.baseAcceleration(maxSpeed, vanillaSpeed, activeBlocks);
        double effectiveAverage = SpeedProfile.effectiveAverageAcceleration(requestedAverage);
        double distance = SpeedProfile.distance(vanillaSpeed, maxSpeed, requestedAverage);

        requireClose(requestedAverage, 0.061875, "requested average acceleration for 4/128");
        requireClose(effectiveAverage, 0.061875, "effective average acceleration for 4/128");
        requireClose(SpeedProfile.finalAcceleration(requestedAverage), 0.06375,
            "final acceleration for 4/128");
        requireClose(distance, activeBlocks, "v2 to v1 distance for 4/128");
        requireClose(SpeedProfile.distance(maxSpeed, vanillaSpeed, requestedAverage), activeBlocks,
            "v1 to v2 distance for 4/128");
        verifyLinearMirroredProfile(vanillaSpeed, maxSpeed, distance, 0.06, 0.06375);
    }

    private static void testUnloadedChunkKeepsStableConfirmedDistance() {
        RailPathScanner.PoweredPath belowThreshold =
            new RailPathScanner.PoweredPath(127.0, false, true);
        RailPathScanner.PoweredPath atThreshold =
            new RailPathScanner.PoweredPath(128.0, false, true);
        RailPathScanner.PoweredPath beyondThreshold =
            new RailPathScanner.PoweredPath(129.0, false, true);

        requireClose(MinecartSpeedManager.confirmedPoweredDistance(beyondThreshold), 129.0,
            "unloaded chunk keeps confirmed powered distance");
        require(!MinecartSpeedManager.hasActivationDistance(true, belowThreshold, 128),
            "127 confirmed blocks must not activate");
        require(!MinecartSpeedManager.hasActivationDistance(true, atThreshold, 128),
            "128 confirmed blocks must preserve the strict activation threshold");
        require(MinecartSpeedManager.hasActivationDistance(true, beyondThreshold, 128),
            "129 confirmed blocks must activate before an unloaded chunk");
        require(!MinecartSpeedManager.hasActivationDistance(false, beyondThreshold, 128),
            "activation still requires the current powered rail");

        double carriedDistance = MinecartSpeedManager.confirmedDistanceAfterTravel(160.0, 4.0);
        requireClose(carriedDistance, 156.0, "confirmed distance advances with the cart");
        requireClose(
            MinecartSpeedManager.stabilizedConfirmedPoweredDistance(belowThreshold, carriedDistance, true),
            156.0,
            "temporary unloaded boundary regression keeps previously confirmed rails"
        );
        requireClose(
            MinecartSpeedManager.stabilizedConfirmedPoweredDistance(beyondThreshold, 160.0, false),
            129.0,
            "normal mode cannot activate from stale carried distance"
        );

        RailPathScanner.PoweredPath knownEnd =
            new RailPathScanner.PoweredPath(120.0, true, false);
        requireClose(
            MinecartSpeedManager.stabilizedConfirmedPoweredDistance(knownEnd, carriedDistance, true),
            120.0,
            "a known rail end immediately replaces carried distance"
        );

        require(MinecartSpeedManager.shouldBrakeForConfirmedDistance(true, 129.0, 130.0),
            "unknown rails beyond current and carried confirmation are never assumed");
        require(!MinecartSpeedManager.shouldBrakeForConfirmedDistance(true, carriedDistance, 130.0),
            "sufficient confirmed distance must not force early braking");
        require(MinecartSpeedManager.shouldBrakeForConfirmedDistance(true, 120.0, 130.0),
            "a known end inside braking distance must force braking");
        require(MinecartSpeedManager.shouldBrakeForConfirmedDistance(false, carriedDistance, 120.0),
            "an unpowered current rail must force braking");
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
