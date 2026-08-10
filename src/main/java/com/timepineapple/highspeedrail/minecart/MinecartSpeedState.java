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
    private double poweredDistanceBeforeVanillaTick;
    private int fullPoweredRailsAheadBeforeVanillaTick;
    private boolean poweredPathReachedEndBeforeVanillaTick;
    private boolean poweredPathStoppedAtUnloadedBeforeVanillaTick;
    private double brakeBoundaryDistanceBeforeVanillaTick = Double.POSITIVE_INFINITY;
    private double brakeTargetDistanceBeforeVanillaTick;
    private long scannedLastPoweredRailPos = Long.MIN_VALUE;
    private double phaseStartSpeed;
    private double phaseTargetSpeed;
    private double phaseDistance;
    private double phaseProgress;
    private double phaseAcceleration;
    private boolean distanceBasedPhase;
    private boolean railEndBrake;
    private long brakeTerminalRailPos = Long.MIN_VALUE;
    private double configuredMaxSpeed = Double.NaN;

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

    public int fullPoweredRailsAheadBeforeVanillaTick() {
        return fullPoweredRailsAheadBeforeVanillaTick;
    }

    public boolean poweredPathReachedEndBeforeVanillaTick() {
        return poweredPathReachedEndBeforeVanillaTick;
    }

    public boolean poweredPathStoppedAtUnloadedBeforeVanillaTick() {
        return poweredPathStoppedAtUnloadedBeforeVanillaTick;
    }

    public double brakeBoundaryDistanceBeforeVanillaTick() {
        return brakeBoundaryDistanceBeforeVanillaTick;
    }

    public double brakeTargetDistanceBeforeVanillaTick() {
        return brakeTargetDistanceBeforeVanillaTick;
    }

    public long scannedLastPoweredRailPos() {
        return scannedLastPoweredRailPos;
    }

    public void setPoweredPathBeforeVanillaTick(
        RailPathScanner.PoweredPath path,
        double brakeBoundaryDistance
    ) {
        poweredDistanceBeforeVanillaTick = Math.max(0.0, path.distance());
        fullPoweredRailsAheadBeforeVanillaTick = Math.max(0, path.fullPoweredRailsAhead());
        poweredPathReachedEndBeforeVanillaTick = path.reachedEnd();
        poweredPathStoppedAtUnloadedBeforeVanillaTick = path.stoppedAtUnloadedChunk();
        brakeBoundaryDistanceBeforeVanillaTick = brakeBoundaryDistance;
        brakeTargetDistanceBeforeVanillaTick = path.brakingTargetDistance();
        scannedLastPoweredRailPos = path.lastPoweredRailPos();
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

    public double phaseAcceleration() {
        return phaseAcceleration;
    }

    public boolean distanceBasedPhase() {
        return distanceBasedPhase;
    }

    public long brakeTerminalRailPos() {
        return brakeTerminalRailPos;
    }

    public void setTimedPhase(double startSpeed, double targetSpeed, double acceleration) {
        phaseStartSpeed = startSpeed;
        phaseTargetSpeed = targetSpeed;
        phaseDistance = 0.0;
        phaseProgress = 0.0;
        phaseAcceleration = Math.max(0.0, acceleration);
        distanceBasedPhase = false;
        railEndBrake = false;
        brakeTerminalRailPos = Long.MIN_VALUE;
    }

    public void setRailEndPhase(
        double startSpeed,
        double targetSpeed,
        double distance,
        double acceleration,
        long terminalRailPos
    ) {
        phaseStartSpeed = startSpeed;
        phaseTargetSpeed = targetSpeed;
        phaseDistance = Math.max(0.0, distance);
        phaseProgress = 0.0;
        phaseAcceleration = Math.max(0.0, acceleration);
        distanceBasedPhase = true;
        railEndBrake = true;
        brakeTerminalRailPos = terminalRailPos;
    }

    public void setPhaseProgress(double phaseProgress) {
        this.phaseProgress = Math.max(0.0, phaseProgress);
    }

    public boolean railEndBrake() {
        return railEndBrake;
    }

    public boolean maxSpeedMatches(double maxSpeed) {
        return Double.compare(configuredMaxSpeed, maxSpeed) == 0;
    }

    public void setConfiguredMaxSpeed(double maxSpeed) {
        configuredMaxSpeed = maxSpeed;
    }
}
