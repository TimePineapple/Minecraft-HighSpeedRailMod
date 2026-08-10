package com.timepineapple.highspeedrail.minecart;

import net.minecraft.util.math.Vec3d;

public final class MinecartSpeedState {
    private MinecartSpeedMode mode = MinecartSpeedMode.NORMAL;
    private double vanillaMaxSpeed = 0.4;
    private double speedBeforeVanillaTick;
    private Vec3d velocityBeforeVanillaTick = Vec3d.ZERO;
    private Vec3d positionBeforeVanillaTick = Vec3d.ZERO;
    private long railPosBeforeVanillaTick = Long.MIN_VALUE;
    private Vec3d tangentBeforeVanillaTick = Vec3d.ZERO;
    private int poweredRailsAhead;
    private double poweredDistanceBeforeVanillaTick;
    private double phaseStartSpeed;
    private double phaseTargetSpeed;
    private double phaseDistance;
    private double phaseProgress;
    private boolean railEndBrake;
    private double configuredMaxSpeed = Double.NaN;
    private int configuredActiveBlocks = -1;
    private double configuredVanillaSpeed = Double.NaN;

    public MinecartSpeedMode mode() {
        return mode;
    }

    public void setMode(MinecartSpeedMode mode) {
        this.mode = mode;
    }

    public double vanillaMaxSpeed() {
        return vanillaMaxSpeed;
    }

    public void setVanillaMaxSpeed(double vanillaMaxSpeed) {
        this.vanillaMaxSpeed = vanillaMaxSpeed;
    }

    public double speedBeforeVanillaTick() {
        return speedBeforeVanillaTick;
    }

    public void setSpeedBeforeVanillaTick(double speedBeforeVanillaTick) {
        this.speedBeforeVanillaTick = speedBeforeVanillaTick;
    }

    public Vec3d velocityBeforeVanillaTick() {
        return velocityBeforeVanillaTick;
    }

    public void setVelocityBeforeVanillaTick(Vec3d velocityBeforeVanillaTick) {
        this.velocityBeforeVanillaTick = velocityBeforeVanillaTick;
    }

    public Vec3d positionBeforeVanillaTick() {
        return positionBeforeVanillaTick;
    }

    public void setPositionBeforeVanillaTick(Vec3d positionBeforeVanillaTick) {
        this.positionBeforeVanillaTick = positionBeforeVanillaTick;
    }

    public long railPosBeforeVanillaTick() {
        return railPosBeforeVanillaTick;
    }

    public void setRailPosBeforeVanillaTick(long railPosBeforeVanillaTick) {
        this.railPosBeforeVanillaTick = railPosBeforeVanillaTick;
    }

    public Vec3d tangentBeforeVanillaTick() {
        return tangentBeforeVanillaTick;
    }

    public void setTangentBeforeVanillaTick(Vec3d tangentBeforeVanillaTick) {
        this.tangentBeforeVanillaTick = tangentBeforeVanillaTick;
    }

    public int poweredRailsAhead() {
        return poweredRailsAhead;
    }

    public void setPoweredRailsAhead(int poweredRailsAhead) {
        this.poweredRailsAhead = poweredRailsAhead;
    }

    public double poweredDistanceBeforeVanillaTick() {
        return poweredDistanceBeforeVanillaTick;
    }

    public void setPoweredDistanceBeforeVanillaTick(double poweredDistanceBeforeVanillaTick) {
        this.poweredDistanceBeforeVanillaTick = poweredDistanceBeforeVanillaTick;
    }

    public double phaseStartSpeed() {
        return phaseStartSpeed;
    }

    public double phaseTargetSpeed() {
        return phaseTargetSpeed;
    }

    public double phaseDistance() {
        return phaseDistance;
    }

    public double phaseProgress() {
        return phaseProgress;
    }

    public void setPhase(double startSpeed, double targetSpeed, double distance, boolean railEndBrake) {
        phaseStartSpeed = startSpeed;
        phaseTargetSpeed = targetSpeed;
        phaseDistance = Math.max(0.0, distance);
        phaseProgress = 0.0;
        this.railEndBrake = railEndBrake;
    }

    public void setPhaseProgress(double phaseProgress) {
        this.phaseProgress = Math.max(0.0, phaseProgress);
    }

    public boolean railEndBrake() {
        return railEndBrake;
    }

    public boolean parametersMatch(double maxSpeed, int activeBlocks, double vanillaSpeed) {
        return Double.compare(configuredMaxSpeed, maxSpeed) == 0
            && configuredActiveBlocks == activeBlocks
            && Double.compare(configuredVanillaSpeed, vanillaSpeed) == 0;
    }

    public void setParameters(double maxSpeed, int activeBlocks, double vanillaSpeed) {
        configuredMaxSpeed = maxSpeed;
        configuredActiveBlocks = activeBlocks;
        configuredVanillaSpeed = vanillaSpeed;
    }
}
