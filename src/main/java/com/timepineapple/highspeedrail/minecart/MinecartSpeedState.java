package com.timepineapple.highspeedrail.minecart;

import net.minecraft.util.math.Vec3d;

public final class MinecartSpeedState {
    private MinecartSpeedMode mode = MinecartSpeedMode.NORMAL;
    private double vanillaMaxSpeed = 0.4;
    private double speedBeforeVanillaTick;
    private Vec3d velocityBeforeVanillaTick = Vec3d.ZERO;
    private Vec3d positionBeforeVanillaTick = Vec3d.ZERO;
    private int poweredRailsAhead;

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

    public int poweredRailsAhead() {
        return poweredRailsAhead;
    }

    public void setPoweredRailsAhead(int poweredRailsAhead) {
        this.poweredRailsAhead = poweredRailsAhead;
    }
}
