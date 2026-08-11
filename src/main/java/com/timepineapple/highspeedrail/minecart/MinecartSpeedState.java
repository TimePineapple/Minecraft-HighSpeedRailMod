package com.timepineapple.highspeedrail.minecart;

import net.minecraft.util.math.Vec3d;

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
    }
}
