package com.timepineapple.highspeedrail.minecart;

import net.minecraft.util.math.Vec3d;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

public final class MinecartSpeedState {
    private MinecartSpeedMode mode = MinecartSpeedMode.NORMAL;
    private double speed;
    private Vec3d direction = Vec3d.ZERO;
    private double phaseTargetSpeed;
    private double acceleration;
    private double brakeLandFlat;
    private double brakeWaterFlat;
    private double brakeLandSlope;
    private double brakeWaterSlope;
    private boolean phaseBrakeSlope;
    private int activeBlocks;
    private double configuredMaxSpeed = Double.NaN;
    private boolean railEndBrake;
    private boolean waitingAtUnloadedBoundary;
    private int normalCooldownTicks;
    private boolean activationCandidate;
    private int activationSampleCount;
    private double activationFirstHorizontalSpeed;
    private double activationSecondHorizontalSpeed;
    private Vec3d activationDirection = Vec3d.ZERO;
    private boolean normalSampleValid;
    private double normalSampleHorizontalSpeed;
    private Vec3d normalSampleDirection = Vec3d.ZERO;
    private boolean normalTickSampleActive;
    private Vec3d normalTickStartPosition = Vec3d.ZERO;
    private Vec3d normalTickDirection = Vec3d.ZERO;
    private RegistryKey<World> normalTickWorld;
    private double tickStartSpeed;
    private MinecartSpeedMode tickStartMode = MinecartSpeedMode.NORMAL;
    private double tickStartTargetSpeed;
    private double tickStartAcceleration;
    private double tickStartBrakeLandFlat;
    private double tickStartBrakeWaterFlat;
    private double tickStartBrakeLandSlope;
    private double tickStartBrakeWaterSlope;
    private boolean tickStartPhaseBrakeSlope;
    private int tickStartActiveBlocks;
    private double tickStartConfiguredMaxSpeed;
    private boolean tickStartRailEndBrake;
    private long handoffRailPos = Long.MIN_VALUE;
    private RailGeometryMover.MovementResult movementResult = RailGeometryMover.MovementResult.NONE;

    public MinecartSpeedMode mode() {
        return mode;
    }

    public void setMode(MinecartSpeedMode mode) {
        this.mode = mode;
    }

    public double speed() {
        return speed;
    }

    public void setSpeed(double speed) {
        this.speed = Math.max(0.0, speed);
    }

    public Vec3d direction() {
        return direction;
    }

    public void setDirection(Vec3d direction) {
        Vec3d horizontal = direction.getHorizontal();
        if (horizontal.lengthSquared() > 1.0E-8) {
            this.direction = horizontal.normalize();
        }
    }

    public double phaseTargetSpeed() {
        return phaseTargetSpeed;
    }

    public double acceleration() {
        return acceleration;
    }

    public double brakeAcceleration(boolean touchingWater) {
        if (phaseBrakeSlope) {
            return touchingWater ? brakeWaterSlope : brakeLandSlope;
        }
        return touchingWater ? brakeWaterFlat : brakeLandFlat;
    }

    public int activeBlocks() {
        return activeBlocks;
    }

    public int effectiveActivationBlocks() {
        return PhysicsProfile.effectiveActivationBlocks(activeBlocks);
    }

    public void startPhase(
        MinecartSpeedMode mode,
        double targetSpeed,
        PhysicsProfile profile,
        boolean railEndBrake,
        boolean brakeSlope
    ) {
        this.mode = mode;
        this.phaseTargetSpeed = Math.max(0.0, targetSpeed);
        this.acceleration = Math.max(0.0, profile.acceleration());
        this.brakeLandFlat = Math.max(0.0, profile.brakeLandFlat());
        this.brakeWaterFlat = Math.max(0.0, profile.brakeWaterFlat());
        this.brakeLandSlope = Math.max(0.0, profile.brakeLandSlope());
        this.brakeWaterSlope = Math.max(0.0, profile.brakeWaterSlope());
        this.phaseBrakeSlope = brakeSlope;
        this.activeBlocks = profile.activeBlocks();
        this.configuredMaxSpeed = profile.configuredMaxSpeed();
        this.railEndBrake = railEndBrake;
    }

    public void startRailEndBrake(double targetSpeed, boolean brakeSlope) {
        mode = MinecartSpeedMode.DECELERATING;
        phaseTargetSpeed = Math.max(0.0, targetSpeed);
        railEndBrake = true;
        phaseBrakeSlope = brakeSlope;
    }

    public boolean railEndBrake() {
        return railEndBrake;
    }

    public boolean maxSpeedMatches(double maxSpeed) {
        return Double.compare(configuredMaxSpeed, maxSpeed) == 0;
    }

    public void beginTick() {
        movementResult = RailGeometryMover.MovementResult.NONE;
        tickStartSpeed = speed;
        tickStartMode = mode;
        tickStartTargetSpeed = phaseTargetSpeed;
        tickStartAcceleration = acceleration;
        tickStartBrakeLandFlat = brakeLandFlat;
        tickStartBrakeWaterFlat = brakeWaterFlat;
        tickStartBrakeLandSlope = brakeLandSlope;
        tickStartBrakeWaterSlope = brakeWaterSlope;
        tickStartPhaseBrakeSlope = phaseBrakeSlope;
        tickStartActiveBlocks = activeBlocks;
        tickStartConfiguredMaxSpeed = configuredMaxSpeed;
        tickStartRailEndBrake = railEndBrake;
        handoffRailPos = Long.MIN_VALUE;
    }

    public void restoreFrozenTick() {
        speed = tickStartSpeed;
        mode = tickStartMode;
        phaseTargetSpeed = tickStartTargetSpeed;
        acceleration = tickStartAcceleration;
        brakeLandFlat = tickStartBrakeLandFlat;
        brakeWaterFlat = tickStartBrakeWaterFlat;
        brakeLandSlope = tickStartBrakeLandSlope;
        brakeWaterSlope = tickStartBrakeWaterSlope;
        phaseBrakeSlope = tickStartPhaseBrakeSlope;
        activeBlocks = tickStartActiveBlocks;
        configuredMaxSpeed = tickStartConfiguredMaxSpeed;
        railEndBrake = tickStartRailEndBrake;
        waitingAtUnloadedBoundary = true;
    }

    public boolean waitingAtUnloadedBoundary() {
        return waitingAtUnloadedBoundary;
    }

    public void clearUnloadedBoundaryWait() {
        waitingAtUnloadedBoundary = false;
    }

    public int normalCooldownTicks() {
        return normalCooldownTicks;
    }

    public void consumeNormalCooldownTick() {
        if (normalCooldownTicks > 0) {
            normalCooldownTicks--;
        }
    }

    public boolean hasActivationCandidate() {
        return activationCandidate;
    }

    public boolean activationCandidateReady() {
        return activationCandidate && activationSampleCount >= 2;
    }

    public int activationSampleCount() {
        return activationSampleCount;
    }

    public double activationFirstHorizontalSpeed() {
        return activationFirstHorizontalSpeed;
    }

    public double activationSecondHorizontalSpeed() {
        return activationSecondHorizontalSpeed;
    }

    public Vec3d activationDirection() {
        return activationDirection;
    }

    public Vec3d normalTickStartPosition() {
        return normalTickStartPosition;
    }

    public void armActivationCandidate(Vec3d direction) {
        activationCandidate = true;
        activationSampleCount = 0;
        activationFirstHorizontalSpeed = 0.0;
        activationSecondHorizontalSpeed = 0.0;
        activationDirection = direction.getHorizontal().normalize();
        if (normalSampleValid && MinecartSpeedManager.sameRailDirection(
            normalSampleDirection, activationDirection
        )) {
            addActivationSample(normalSampleHorizontalSpeed, normalSampleDirection);
        }
    }

    public void addActivationSample(double horizontalSpeed, Vec3d direction) {
        if (!activationCandidate
            || !Double.isFinite(horizontalSpeed)
            || horizontalSpeed < 0.0
            || !MinecartSpeedManager.sameRailDirection(activationDirection, direction)) {
            return;
        }
        if (activationSampleCount == 0) {
            activationFirstHorizontalSpeed = horizontalSpeed;
            activationSampleCount = 1;
        } else if (activationSampleCount == 1) {
            activationSecondHorizontalSpeed = horizontalSpeed;
            activationSampleCount = 2;
        }
    }

    public void beginNormalTickSample(
        Vec3d position,
        Vec3d direction,
        RegistryKey<World> world
    ) {
        normalTickSampleActive = true;
        normalTickStartPosition = position;
        normalTickDirection = direction.getHorizontal().normalize();
        normalTickWorld = world;
    }

    public boolean normalTickSampleActive() {
        return normalTickSampleActive;
    }

    public Vec3d normalTickDirection() {
        return normalTickDirection;
    }

    public RegistryKey<World> normalTickWorld() {
        return normalTickWorld;
    }

    public void completeNormalTickSample(double horizontalSpeed, Vec3d direction) {
        normalSampleValid = true;
        normalSampleHorizontalSpeed = Math.max(0.0, horizontalSpeed);
        normalSampleDirection = direction.getHorizontal().normalize();
        normalTickSampleActive = false;
        normalTickStartPosition = Vec3d.ZERO;
        normalTickDirection = Vec3d.ZERO;
        normalTickWorld = null;
        addActivationSample(normalSampleHorizontalSpeed, normalSampleDirection);
    }

    public void clearNormalMotionSamples() {
        normalSampleValid = false;
        normalSampleHorizontalSpeed = 0.0;
        normalSampleDirection = Vec3d.ZERO;
        normalTickSampleActive = false;
        normalTickStartPosition = Vec3d.ZERO;
        normalTickDirection = Vec3d.ZERO;
        normalTickWorld = null;
    }

    public void clearActivationCandidate() {
        activationCandidate = false;
        activationSampleCount = 0;
        activationFirstHorizontalSpeed = 0.0;
        activationSecondHorizontalSpeed = 0.0;
        activationDirection = Vec3d.ZERO;
    }

    public long handoffRailPos() {
        return handoffRailPos;
    }

    public void setHandoffRailPos(long handoffRailPos) {
        this.handoffRailPos = handoffRailPos;
    }

    public RailGeometryMover.MovementResult movementResult() {
        return movementResult;
    }

    public void setMovementResult(RailGeometryMover.MovementResult movementResult) {
        this.movementResult = movementResult;
    }

    public void resetToNormal(double speed, Vec3d direction, int cooldownTicks) {
        mode = MinecartSpeedMode.NORMAL;
        setSpeed(speed);
        setDirection(direction);
        phaseTargetSpeed = 0.0;
        railEndBrake = false;
        phaseBrakeSlope = false;
        waitingAtUnloadedBoundary = false;
        handoffRailPos = Long.MIN_VALUE;
        normalCooldownTicks = Math.max(0, cooldownTicks);
        clearActivationCandidate();
        clearNormalMotionSamples();
    }
}
