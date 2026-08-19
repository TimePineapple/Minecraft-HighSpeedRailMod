package com.timepineapple.highspeedrail.minecart;

import com.timepineapple.highspeedrail.config.ModConfig;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

public final class SpeedProfileSelfTest {
    private static final double EPSILON = 1.0E-9;
    private static final double SQRT_TWO = Math.sqrt(2.0);

    public static void main(String[] args) {
        testDerivedPhysics();
        testActivationAndHandoffBoundaries();
        testActivationCandidateLifecycle();
        testTwoSampleActivationSpeed();
        testTrackSpeedConversions();
        testFixedTrackAcceleration();
        testFlatAndSlopeBrakeCaches();
        testPhaseSnapshotsAndLiveMaxReduction();
        testUnloadedFreezeAndCollisionCooldown();
        testExperimentalRetentionCompensation();
        testNoSlopeGravityAdjustment();
        testGeometryBudgetsAndSubsteps();
        testRailGeometry();
        testFullMovementTakeover();
        testDiagnosticHelpers();
    }

    private static void testDerivedPhysics() {
        PhysicsProfile profile = PhysicsProfile.defaults(config(4.0, 9, 30));
        require(profile.effectiveActivationBlocks() == 17,
            "activeBlocks=9 must activate at 17 full rails ahead");
        requireClose(profile.vanillaLandSpeed(), 0.4, "default cached land maximum");
        requireClose(profile.vanillaWaterSpeed(), 0.2, "default cached water maximum");
        requireClose(profile.vanillaTrackSpeed(false, true), 0.4 * SQRT_TWO,
            "slope handoff preserves the cached vanilla horizontal component");
        requireClose(profile.vanillaTrackSpeed(true, true), 0.2 * SQRT_TWO,
            "water slope handoff preserves the cached vanilla horizontal component");
        requireClose(profile.acceleration(), 4.0 / 600.0, "configured track acceleration");
        requireClose(profile.brakeLandFlat(), (16.0 - 0.16) / 18.0,
            "land flat brake cache");
        requireClose(profile.brakeWaterFlat(), (16.0 - 0.04) / 18.0,
            "water flat brake cache");
        requireClose(profile.brakeLandSlope(), (16.0 - 0.32) / 18.0,
            "land slope brake cache");
        requireClose(profile.brakeWaterSlope(), (16.0 - 0.08) / 18.0,
            "water slope brake cache");
    }

    private static void testActivationAndHandoffBoundaries() {
        int effective = PhysicsProfile.effectiveActivationBlocks(9);
        RailPathScanner.PoweredPath nPlus7 = path(16, false, true, 15.75, 16.75);
        RailPathScanner.PoweredPath nPlus8 = path(17, false, true, 16.75, 17.75);
        RailPathScanner.PoweredPath nPlus9 = path(18, false, false, 17.75, 18.75);

        require(!MinecartSpeedManager.hasActivationDistance(true, nPlus7, effective),
            "N+7 loaded rails must not activate");
        require(MinecartSpeedManager.hasActivationDistance(true, nPlus8, effective),
            "N+8 confirmed powered rails activate");
        require(MinecartSpeedManager.hasActivationDistance(true, nPlus9, effective),
            "N+9 confirmed powered rails activate");
        require(!MinecartSpeedManager.hasActivationDistance(false, nPlus9, effective),
            "the rail under the cart must be powered");

        RailPathScanner.PoweredPath knownEnd = path(17, true, false, 16.75, 17.75);
        requireClose(MinecartSpeedManager.handoffDistance(knownEnd), 9.75,
            "handoff remains the entry to the last eight powered rails");
        require(Double.isInfinite(MinecartSpeedManager.handoffDistance(nPlus8)),
            "an unloaded boundary is never a handoff target");
        require(MinecartSpeedManager.missedHandoffBoundary(path(7, true, false, 6.75, 7.75)),
            "a shortened path already inside the last eight rails hands off immediately");
    }

    private static void testTrackSpeedConversions() {
        requireClose(RailGeometryMover.horizontalSpeedFromTrack(4.0, false), 4.0,
            "flat horizontal speed equals track speed");
        requireClose(RailGeometryMover.horizontalSpeedFromTrack(4.0, true), 4.0 / SQRT_TWO,
            "slope horizontal component uses the square-root-two conversion");
        requireClose(
            RailGeometryMover.trackSpeedFromHorizontal(4.0 / SQRT_TWO, true),
            4.0,
            "slope activation reconstructs track speed without a jump"
        );
        requireClose(RailGeometryMover.trackSpeedFromHorizontal(0.4, true),
            0.4 * SQRT_TWO, "vanilla slope entry converts to track speed");
    }

    private static void testActivationCandidateLifecycle() {
        MinecartSpeedState state = new MinecartSpeedState();
        Vec3d direction = new Vec3d(1.0, 0.0, 0.0);
        state.completeNormalTickSample(0.4, direction);
        state.armActivationCandidate(direction);
        require(state.mode() == MinecartSpeedMode.NORMAL,
            "the first qualifying tick only arms a NORMAL candidate");
        require(state.hasActivationCandidate(), "the activation candidate is recorded");
        require(state.activationSampleCount() == 1,
            "the previous complete NORMAL tick supplies the first actual movement sample");
        require(!state.activationCandidateReady(),
            "the candidate is not ready before the candidate vanilla tick completes");
        state.completeNormalTickSample(0.4, direction);
        require(state.activationCandidateReady(),
            "two consecutive actual movement samples make the candidate ready");
        requireClose(state.activationFirstHorizontalSpeed(), 0.4,
            "the first projected horizontal sample is preserved");
        requireClose(state.activationSecondHorizontalSpeed(), 0.4,
            "the second projected horizontal sample is preserved");
        require(MinecartSpeedManager.sameDirection(direction, new Vec3d(0.9, 0.0, 0.0)),
            "adjacent straight powered rails keep the candidate");
        require(!MinecartSpeedManager.sameDirection(direction, new Vec3d(-1.0, 0.0, 0.0)),
            "a direction reversal invalidates the candidate");
        require(!MinecartSpeedManager.sameRailDirection(direction, new Vec3d(1.0, 0.0, 1.0)),
            "a curve direction change invalidates an actual movement sample");
        state.clearActivationCandidate();
        require(!state.hasActivationCandidate(), "invalid conditions clear the candidate");

        state.armActivationCandidate(direction);
        state.resetToNormal(0.4, direction, 1);
        require(!state.hasActivationCandidate(),
            "collision cooldown reset clears any pending candidate");
    }

    private static void testTwoSampleActivationSpeed() {
        double acceleration = 2.0 / 200.0;
        requireClose(
            MinecartSpeedManager.actualProjectedHorizontalDistance(
                new Vec3d(0.0, 64.0, 0.0),
                new Vec3d(0.4, 64.0, 0.0),
                new Vec3d(1.0, 0.0, 0.0)
            ),
            0.4,
            "activation uses actual server displacement instead of the stored velocity vector"
        );
        requireClose(MinecartSpeedManager.stableActivationHorizontalSpeed(0.4, 4.0), 0.4,
            "a second-sample spike cannot launch the cart");
        requireClose(MinecartSpeedManager.stableActivationHorizontalSpeed(4.0, 0.4), 0.4,
            "a first-sample spike cannot launch the cart");
        requireClose(
            MinecartSpeedManager.initialActivationTrackSpeed(0.4, 0.4, false, 2.0, acceleration),
            0.4 + acceleration,
            "stored velocity 2.054 cannot override two actual 0.4 movement samples"
        );
        requireClose(
            MinecartSpeedManager.initialActivationTrackSpeed(0.4, 0.4, true, 2.0, acceleration),
            0.4 * SQRT_TWO + acceleration,
            "slope activation converts the stable horizontal sample before acceleration"
        );
        requireClose(
            MinecartSpeedManager.initialActivationTrackSpeed(1.0, 1.0, false, 2.0, acceleration),
            1.0 + acceleration,
            "two sustained legal high samples preserve the real entry speed"
        );
        requireClose(
            MinecartSpeedManager.initialActivationTrackSpeed(5.0, 5.0, false, 4.0, acceleration),
            5.0,
            "a sustained speed above custom max is preserved for the later deceleration phase"
        );
    }

    private static void testFixedTrackAcceleration() {
        PhysicsProfile profile = PhysicsProfile.defaults(config(1.2, 16, 5));
        MinecartSpeedState state = new MinecartSpeedState();
        state.setSpeed(0.0);
        state.startPhase(MinecartSpeedMode.ACCELERATING, 1.2, profile, false, false);
        for (int tick = 0; tick < 100; tick++) {
            state.setSpeed(MinecartSpeedManager.advancePhaseSpeed(state, false));
        }
        requireClose(state.speed(), 1.2,
            "zero to maxSpeed takes accelerationSeconds in track-distance units");
        require(state.mode() == MinecartSpeedMode.HIGH_SPEED,
            "track acceleration completes at the configured target");
    }

    private static void testFlatAndSlopeBrakeCaches() {
        PhysicsProfile profile = PhysicsProfile.defaults(config(4.0, 9, 30));
        MinecartSpeedState flat = new MinecartSpeedState();
        flat.setSpeed(4.0);
        flat.startPhase(MinecartSpeedMode.DECELERATING, 0.4, profile, true, false);
        requireClose(
            MinecartSpeedManager.advancePhaseSpeed(flat, false),
            4.0 - profile.brakeLandFlat(),
            "flat rail-end braking uses the flat cache"
        );

        MinecartSpeedState slope = new MinecartSpeedState();
        slope.setSpeed(4.0);
        slope.startPhase(
            MinecartSpeedMode.DECELERATING,
            0.4 * SQRT_TWO,
            profile,
            true,
            true
        );
        requireClose(
            MinecartSpeedManager.advancePhaseSpeed(slope, false),
            4.0 - profile.brakeLandSlope(),
            "slope rail-end braking uses the slope cache"
        );
        requireClose(PhysicsProfile.brakingAcceleration(1.2, 1.4, 9), 0.0,
            "a vanilla track target above custom max never creates active braking");
    }

    private static void testPhaseSnapshotsAndLiveMaxReduction() {
        PhysicsProfile oldProfile = PhysicsProfile.defaults(config(4.0, 128, 30));
        PhysicsProfile changedSeconds = PhysicsProfile.defaults(config(4.0, 128, 60));
        MinecartSpeedState state = new MinecartSpeedState();
        state.setSpeed(1.0);
        state.startPhase(MinecartSpeedMode.ACCELERATING, 4.0, oldProfile, false, false);
        requireClose(state.acceleration(), 4.0 / 600.0,
            "active phase snapshots the old acceleration");
        requireClose(changedSeconds.acceleration(), 4.0 / 1200.0,
            "changed seconds is available to the next phase");

        PhysicsProfile reduced = PhysicsProfile.defaults(config(2.0, 128, 30));
        state.setSpeed(3.0);
        state.startPhase(MinecartSpeedMode.DECELERATING, 2.0, reduced, false, true);
        requireClose(
            MinecartSpeedManager.advancePhaseSpeed(state, false),
            Math.max(2.0, 3.0 - reduced.brakeLandSlope()),
            "live max reduction snapshots the selected track geometry cache"
        );
    }

    private static void testUnloadedFreezeAndCollisionCooldown() {
        PhysicsProfile profile = PhysicsProfile.defaults(config(4.0, 128, 30));
        MinecartSpeedState state = new MinecartSpeedState();
        state.setSpeed(2.0);
        state.setDirection(new Vec3d(1.0, 0.0, 0.0));
        state.startPhase(MinecartSpeedMode.ACCELERATING, 4.0, profile, false, false);
        state.beginTick();
        state.setSpeed(MinecartSpeedManager.advancePhaseSpeed(state, false));
        state.startPhase(
            MinecartSpeedMode.DECELERATING,
            1.0,
            PhysicsProfile.defaults(config(2.0, 9, 10)),
            false,
            true
        );
        state.restoreFrozenTick();
        requireClose(state.speed(), 2.0, "unloaded boundary restores tick-start track speed");
        require(state.mode() == MinecartSpeedMode.ACCELERATING,
            "unloaded boundary restores tick-start phase");
        requireClose(state.acceleration(), profile.acceleration(),
            "unloaded boundary restores phase constants");

        state.resetToNormal(0.4, new Vec3d(1.0, 0.0, 0.0), 1);
        require(state.normalCooldownTicks() == 1,
            "collision schedules one complete NORMAL tick");
        state.consumeNormalCooldownTick();
        require(state.normalCooldownTicks() == 0,
            "the full NORMAL tick consumes the collision cooldown");
    }

    private static void testExperimentalRetentionCompensation() {
        requireClose(PhysicsProfile.experimentalRetention(true, false), 0.997,
            "occupied land retention");
        requireClose(PhysicsProfile.experimentalRetention(false, false), 0.975,
            "empty land retention");
        requireClose(PhysicsProfile.experimentalRetention(true, true), 0.997 * 0.95,
            "occupied water retention");
        requireClose(PhysicsProfile.experimentalRetention(false, true), 0.975 * 0.95,
            "empty water retention");
        for (boolean passengers : new boolean[]{false, true}) {
            for (boolean water : new boolean[]{false, true}) {
                requireClose(
                    MinecartSpeedManager.frictionCompensatedSpeed(4.0, passengers, water),
                    4.0,
                    "retention compensation preserves final track speed"
                );
            }
        }
    }

    private static void testNoSlopeGravityAdjustment() {
        PhysicsProfile profile = PhysicsProfile.defaults(config(4.0, 128, 30));
        for (boolean water : new boolean[]{false, true}) {
            MinecartSpeedState state = new MinecartSpeedState();
            state.setSpeed(4.0);
            state.startPhase(MinecartSpeedMode.HIGH_SPEED, 4.0, profile, false, false);
            for (int tick = 0; tick < 256; tick++) {
                state.setSpeed(MinecartSpeedManager.advancePhaseSpeed(state, water));
                requireClose(state.speed(), 4.0,
                    "slope gravity must not alter HIGH_SPEED at tick " + tick);
            }
        }
    }

    private static void testGeometryBudgetsAndSubsteps() {
        double slopeHorizontal = RailGeometryMover.horizontalMovementBudget(4.0, true);
        requireClose(slopeHorizontal, 4.0 / SQRT_TWO,
            "slope horizontal movement consumes a square-root-two track budget");
        requireClose(RailGeometryMover.horizontalMovementBudget(4.0, false), 4.0,
            "flat horizontal movement equals track budget");
        requireClose(Math.hypot(slopeHorizontal, slopeHorizontal), 4.0,
            "slope three-dimensional movement equals configured track speed");
        require(RailGeometryMover.minimumSubstepCount(slopeHorizontal) == 4,
            "track speed four uses four horizontal slope substeps");
        require(RailGeometryMover.minimumSubstepCount(4.0) == 6,
            "track speed four uses six flat substeps");
        require(RailGeometryMover.minimumSubstepCount(48.0) == RailGeometryMover.MAX_SUBSTEPS,
            "the exact 64-substep flat budget is supported");
    }

    private static void testRailGeometry() {
        Vec3i flatBack = new Vec3i(-1, 0, 0);
        Vec3i flatForward = new Vec3i(1, 0, 0);
        Vec3i curveForward = new Vec3i(0, 0, 1);
        Vec3i slopeBack = new Vec3i(-1, -1, 0);
        Vec3i slopeForward = new Vec3i(1, 0, 0);
        requireClose(RailGeometryMover.segmentTrackLength(flatBack, flatForward), 1.0,
            "flat rail track length is one");
        requireClose(RailGeometryMover.segmentTrackLength(flatBack, curveForward), Math.sqrt(0.5),
            "flat curve keeps its existing chord geometry");
        requireClose(RailGeometryMover.segmentTrackLength(slopeBack, slopeForward), SQRT_TWO,
            "slope rail track length is square root two");
        requireClose(RailGeometryMover.trackFactor(slopeBack, slopeForward), SQRT_TWO,
            "slope track-to-horizontal factor is square root two");

        BlockPos rail = new BlockPos(10, 64, 20);
        RailPathScanner.RailFrame flatFrame = new RailPathScanner.RailFrame(
            0.5,
            RailPathScanner.tangent(flatBack, flatForward),
            flatBack,
            flatForward
        );
        requireClose(RailGeometryMover.collisionPlaneY(rail, flatFrame), 64.0625,
            "flat rail keeps its real rail collision height");
        Vec3i[][] slopes = {
            {new Vec3i(-1, -1, 0), new Vec3i(1, 0, 0)},
            {new Vec3i(1, -1, 0), new Vec3i(-1, 0, 0)},
            {new Vec3i(0, -1, -1), new Vec3i(0, 0, 1)},
            {new Vec3i(0, -1, 1), new Vec3i(0, 0, -1)}
        };
        for (int index = 0; index < slopes.length; index++) {
            RailPathScanner.RailFrame frame = new RailPathScanner.RailFrame(
                0.5,
                RailPathScanner.tangent(slopes[index][0], slopes[index][1]),
                slopes[index][0],
                slopes[index][1]
            );
            Vec3d start = RailGeometryMover.pointOnRail(rail, slopes[index][0], slopes[index][1], 0.0);
            Vec3d middle = RailGeometryMover.pointOnRail(rail, slopes[index][0], slopes[index][1], 0.5);
            Vec3d end = RailGeometryMover.pointOnRail(rail, slopes[index][0], slopes[index][1], 1.0);
            requireClose(end.y - start.y, 1.0, "slope climbs one block " + index);
            requireClose(middle.y - start.y, 0.5, "slope midpoint snaps by formula " + index);
            requireClose(end.subtract(start).length(), SQRT_TWO,
                "slope centerline distance is square root two " + index);
            requireClose(RailGeometryMover.collisionPlaneY(rail, frame), 65.0625,
                "slope horizontal collision uses the highest rail plane " + index);
        }
    }

    private static void testFullMovementTakeover() {
        require(!MinecartSpeedManager.takeOverMovement(MinecartSpeedMode.NORMAL),
            "NORMAL uses the server-selected vanilla controller");
        require(MinecartSpeedManager.takeOverMovement(MinecartSpeedMode.ACCELERATING),
            "acceleration is fully owned by the mod");
        require(MinecartSpeedManager.takeOverMovement(MinecartSpeedMode.HIGH_SPEED),
            "high speed is fully owned by the mod");
        require(MinecartSpeedManager.takeOverMovement(MinecartSpeedMode.DECELERATING),
            "deceleration is fully owned by the mod");
        require(MinecartSpeedManager.takeOverMovement(MinecartSpeedMode.BRAKE_HOLD),
            "brake hold is fully owned by the mod");
    }

    private static void testDiagnosticHelpers() {
        require(!HighSpeedRailDiagnostics.validDuration(0),
            "diagnostic duration rejects zero seconds");
        require(HighSpeedRailDiagnostics.validDuration(1),
            "diagnostic duration accepts one second");
        require(HighSpeedRailDiagnostics.validDuration(60),
            "diagnostic duration accepts sixty seconds");
        require(!HighSpeedRailDiagnostics.validDuration(61),
            "diagnostic duration rejects more than sixty seconds");
        require(!HighSpeedRailDiagnostics.status().active(),
            "diagnostics are disabled by default and do not create a session");

        HighSpeedRailDiagnostics.DiagnosticStats stats =
            HighSpeedRailDiagnostics.newStatsForTest();
        stats.recordTick(
            0.4,
            0.4,
            0.4,
            MinecartSpeedMode.NORMAL,
            MinecartSpeedMode.ACCELERATING,
            RailGeometryMover.Outcome.MOVED
        );
        stats.recordTick(
            2.0,
            Math.sqrt(8.0),
            4.0,
            MinecartSpeedMode.ACCELERATING,
            MinecartSpeedMode.NORMAL,
            RailGeometryMover.Outcome.COLLISION
        );
        var summary = stats.json();
        require(summary.get("ticks").getAsLong() == 2L,
            "diagnostic summary counts ticks");
        requireClose(summary.get("maxHorizontalDisplacement").getAsDouble(), 2.0,
            "diagnostic summary records maximum horizontal displacement");
        require(summary.get("collisions").getAsLong() == 1L,
            "diagnostic summary counts collision outcomes");
        require(summary.get("stateTransitions").getAsLong() == 2L,
            "diagnostic summary counts state transitions");

        var tick = HighSpeedRailDiagnostics.sampleTickForTest();
        for (String required : new String[]{
            "event", "serverTick", "headBefore", "headAfter",
            "tailBefore", "tailAfter", "displacement", "movement"
        }) {
            require(tick.has(required), "diagnostic tick contains required field " + required);
        }

        MinecartSpeedState candidate = new MinecartSpeedState();
        Vec3d direction = new Vec3d(1.0, 0.0, 0.0);
        candidate.completeNormalTickSample(0.4, direction);
        candidate.armActivationCandidate(direction);
        candidate.completeNormalTickSample(0.4, direction);
        var diagnosticState = HighSpeedRailDiagnostics.sampleStateForTest(candidate);
        require(diagnosticState.get("activationSampleCount").getAsInt() == 2,
            "diagnostics report the actual movement sample count");
        requireClose(
            diagnosticState.get("activationFirstActualHorizontalDistance").getAsDouble(),
            0.4,
            "diagnostics report the first actual movement sample"
        );
        requireClose(
            diagnosticState.get("activationSecondActualHorizontalDistance").getAsDouble(),
            0.4,
            "diagnostics report the second actual movement sample"
        );
    }

    private static ModConfig config(double maxSpeed, int activeBlocks, int seconds) {
        ModConfig config = ModConfig.defaults();
        config.maxSpeed = maxSpeed;
        config.activeBlocks = activeBlocks;
        config.accelerationSeconds = seconds;
        return config;
    }

    private static RailPathScanner.PoweredPath path(
        int fullRailsAhead,
        boolean reachedEnd,
        boolean stoppedAtUnloaded,
        double lastRailEntryDistance,
        double distance
    ) {
        return new RailPathScanner.PoweredPath(
            distance,
            fullRailsAhead,
            lastRailEntryDistance,
            fullRailsAhead,
            Long.MIN_VALUE,
            reachedEnd,
            stoppedAtUnloaded
        );
    }

    private static void requireClose(double actual, double expected, String label) {
        require(Math.abs(actual - expected) <= EPSILON,
            label + ": expected " + expected + ", got " + actual);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private SpeedProfileSelfTest() {
    }
}
