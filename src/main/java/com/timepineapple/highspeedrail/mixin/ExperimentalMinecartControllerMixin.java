package com.timepineapple.highspeedrail.mixin;

import com.timepineapple.highspeedrail.minecart.MinecartSpeedManager;
import com.timepineapple.highspeedrail.minecart.RailGeometryMover;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.entity.vehicle.ExperimentalMinecartController;
import net.minecraft.entity.vehicle.MinecartController;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ExperimentalMinecartController.class)
public abstract class ExperimentalMinecartControllerMixin extends MinecartController {
    @Shadow
    @Final
    public List<ExperimentalMinecartController.Step> stagingLerpSteps;

    protected ExperimentalMinecartControllerMixin(AbstractMinecartEntity minecart) {
        super(minecart);
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void highSpeedRail$runOnlyActiveProfile(CallbackInfo callbackInfo) {
        if (!MinecartSpeedManager.shouldTakeOverMovement(this.minecart)
            || !(this.minecart.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }

        Vec3d before = this.minecart.getEntityPos();
        RailGeometryMover.moveActive(this.minecart, world);
        Vec3d movement = this.minecart.getEntityPos().subtract(before);
        highSpeedRail$updateAngles(movement);
        double distance = movement.length();
        float weight = distance > 1.0E-5
            ? (float) Math.min(distance, this.getMaxSpeed(world))
            : 1.0F;
        this.stagingLerpSteps.add(new ExperimentalMinecartController.Step(
            this.minecart.getEntityPos(),
            this.minecart.getVelocity(),
            this.minecart.getYaw(),
            this.minecart.getPitch(),
            weight
        ));
        this.minecart.tickBlockCollision();
        callbackInfo.cancel();
    }

    @Unique
    private void highSpeedRail$updateAngles(Vec3d movement) {
        if (movement.horizontalLengthSquared() <= 1.0E-5) {
            return;
        }
        float yaw = 180.0F - (float) (Math.atan2(movement.z, movement.x) * 180.0 / Math.PI);
        float pitch = 90.0F - (float) (
            Math.atan2(movement.horizontalLength(), movement.y) * 180.0 / Math.PI
        );
        if (this.minecart.isYawFlipped()) {
            yaw += 180.0F;
            pitch *= -1.0F;
        }
        this.setYaw(yaw);
        this.setPitch(pitch);
    }
}
