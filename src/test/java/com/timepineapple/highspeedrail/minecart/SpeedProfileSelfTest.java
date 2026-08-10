package com.timepineapple.highspeedrail.minecart;

public final class SpeedProfileSelfTest {
    private static final double EPSILON = 1.0E-9;
    private static final int SAMPLE_COUNT = 256;

    public static void main(String[] args) {
        testConfiguredAccelerationFromZero();
        testArbitraryStartUsesTheSameAcceleration();
        testLiveSecondsWaitForTheNextPhase();
        testConstantRailEndBraking();
        testActivationCountsOnlyFullRailsAhead();
        testUnloadedBoundaryDoesNotLowerThresholdOrBrake();
        testBrakeBoundaryAndFinalRailTarget();
    }

    private static void testConfiguredAccelerationFromZero() {
        double acceleration = SpeedProfile.configuredAcceleration(1.2, 5);
        requireClose(acceleration, 0.012, "default configured acceleration");
        requireClose(
            SpeedProfile.moveTowards(0.0, 1.2, acceleration, 99.0),
            1.188,
            "speed after 99 ticks"
        );
        requireClose(
            SpeedProfile.moveTowards(0.0, 1.2, acceleration, 100.0),
            1.2,
            "zero to maxSpeed in 100 ticks"
        );
        requireClose(
            SpeedProfile.moveTowards(0.0, 1.2, acceleration, 200.0),
            1.2,
            "configured max clamps later ticks"
        );
    }

    private static void testArbitraryStartUsesTheSameAcceleration() {
        double acceleration = SpeedProfile.configuredAcceleration(1.2, 5);
        double ticksFromVanilla = (1.2 - 0.4) / acceleration;
        requireClose(ticksFromVanilla, 200.0 / 3.0, "remaining ticks from vanilla speed");
        requireClose(
            SpeedProfile.moveTowards(0.4, 1.2, acceleration, ticksFromVanilla),
            1.2,
            "arbitrary start reaches max with fixed acceleration"
        );
        requireClose(
            SpeedProfile.moveTowards(2.0, 1.2, acceleration, ticksFromVanilla),
            1.2,
            "live max reduction uses the same acceleration magnitude"
        );
        require(
            acceleration < 0.06,
            "the old vanilla powered-rail acceleration floor must not remain"
        );
    }

    private static void testLiveSecondsWaitForTheNextPhase() {
        MinecartSpeedState state = new MinecartSpeedState();
        double fiveSecondAcceleration = SpeedProfile.configuredAcceleration(1.2, 5);
        state.setTimedPhase(0.4, 1.2, fiveSecondAcceleration);

        double tenSecondAcceleration = SpeedProfile.configuredAcceleration(1.2, 10);
        requireClose(state.phaseAcceleration(), fiveSecondAcceleration,
            "active phase keeps its acceleration snapshot");
        requireClose(tenSecondAcceleration, 0.006, "next phase uses the new seconds value");
    }

    private static void testConstantRailEndBraking() {
        double startSpeed = 4.0;
        double targetSpeed = 0.4;
        double distance = 15.0;
        double acceleration = SpeedProfile.brakingAcceleration(startSpeed, targetSpeed, distance);
        requireClose(acceleration, 0.528, "constant braking acceleration");
        requireClose(
            SpeedProfile.distance(startSpeed, targetSpeed, acceleration),
            distance,
            "braking distance round trip"
        );
        requireClose(
            SpeedProfile.speedAt(startSpeed, targetSpeed, distance, distance),
            targetSpeed,
            "final powered rail entry speed"
        );

        double stepDistance = distance / SAMPLE_COUNT;
        double previousSpeed = startSpeed;
        for (int step = 1; step <= SAMPLE_COUNT; step++) {
            double speed = SpeedProfile.speedAt(
                startSpeed,
                targetSpeed,
                stepDistance * step,
                distance
            );
            double measuredAcceleration = (previousSpeed * previousSpeed - speed * speed)
                / (2.0 * stepDistance);
            requireClose(measuredAcceleration, acceleration, "constant braking sample " + step);
            previousSpeed = speed;
        }
    }

    private static void testActivationCountsOnlyFullRailsAhead() {
        int activeBlocks = 16;
        RailPathScanner.PoweredPath below = path(15, true, false, 14.75, 15.75, 15L);
        RailPathScanner.PoweredPath equal = path(16, false, false, 15.75, 16.75, 16L);
        RailPathScanner.PoweredPath above = path(17, false, false, 16.75, 17.75, 17L);

        require(!MinecartSpeedManager.hasActivationDistance(true, below, activeBlocks),
            "activeBlocks-1 full rails must not activate");
        require(MinecartSpeedManager.hasActivationDistance(true, equal, activeBlocks),
            "exactly activeBlocks full rails must activate");
        require(MinecartSpeedManager.hasActivationDistance(true, above, activeBlocks),
            "more than activeBlocks full rails must activate");
        require(!MinecartSpeedManager.hasActivationDistance(false, above, activeBlocks),
            "the current rail must also be powered");
    }

    private static void testUnloadedBoundaryDoesNotLowerThresholdOrBrake() {
        RailPathScanner.PoweredPath fiveThenUnloaded = path(5, false, true, 4.75, 5.75, 5L);
        RailPathScanner.PoweredPath sixteenThenUnloaded = path(16, false, true, 15.75, 16.75, 16L);

        require(!MinecartSpeedManager.hasActivationDistance(true, fiveThenUnloaded, 64),
            "five confirmed rails cannot replace an activeBlocks=64 threshold");
        require(MinecartSpeedManager.hasActivationDistance(true, sixteenThenUnloaded, 16),
            "confirmed rails may activate even when the following chunk is unknown");
        require(!MinecartSpeedManager.shouldBrakeForConfirmedEnd(true, fiveThenUnloaded, 16),
            "an unloaded boundary is not a real powered-rail end");
        require(Double.isInfinite(MinecartSpeedManager.brakeBoundaryDistance(fiveThenUnloaded, 16)),
            "an unloaded boundary has no braking boundary");
    }

    private static void testBrakeBoundaryAndFinalRailTarget() {
        RailPathScanner.PoweredPath shortKnownEnd = path(15, true, false, 14.75, 15.75, 15L);
        RailPathScanner.PoweredPath exactKnownEnd = path(16, true, false, 15.75, 16.75, 16L);
        RailPathScanner.PoweredPath currentRailIsLast = path(0, true, false, -0.25, 0.75, 99L);

        require(MinecartSpeedManager.shouldBrakeForConfirmedEnd(true, shortKnownEnd, 16),
            "a confirmed end below activeBlocks must brake");
        require(!MinecartSpeedManager.shouldBrakeForConfirmedEnd(true, exactKnownEnd, 16),
            "an exact activeBlocks distance starts braking only after the next boundary");
        requireClose(
            MinecartSpeedManager.brakeBoundaryDistance(exactKnownEnd, 16),
            0.75,
            "exact threshold boundary is the next rail entry"
        );
        requireClose(shortKnownEnd.brakingTargetDistance(), 14.75,
            "normal target is the last powered rail entry");
        requireClose(currentRailIsLast.brakingTargetDistance(), 0.75,
            "missed entry falls back to the remaining final rail distance");
        requireClose(
            MinecartSpeedManager.confirmedDistanceAfterTravel(16.0, 1.25),
            14.75,
            "confirmed distance advances with the cart"
        );
    }

    private static RailPathScanner.PoweredPath path(
        int fullRailsAhead,
        boolean reachedEnd,
        boolean stoppedAtUnloaded,
        double lastRailEntryDistance,
        double totalDistance,
        long lastRailPos
    ) {
        return new RailPathScanner.PoweredPath(
            totalDistance,
            fullRailsAhead,
            lastRailEntryDistance,
            lastRailPos,
            reachedEnd,
            stoppedAtUnloaded
        );
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
