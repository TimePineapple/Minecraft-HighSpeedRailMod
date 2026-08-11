package com.timepineapple.highspeedrail.mixin;

import com.timepineapple.highspeedrail.minecart.MinecartSpeedManager;
import com.timepineapple.highspeedrail.minecart.RailGeometryMover;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.entity.vehicle.DefaultMinecartController;
import net.minecraft.entity.vehicle.MinecartController;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DefaultMinecartController.class)
public abstract class DefaultMinecartControllerMixin extends MinecartController {
    protected DefaultMinecartControllerMixin(AbstractMinecartEntity minecart) {
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
        highSpeedRail$updateAngles(this.minecart.getEntityPos().subtract(before));
        this.minecart.tickBlockCollision();
        this.handleCollision();
        callbackInfo.cancel();
    }

    @Unique
    private void highSpeedRail$updateAngles(Vec3d movement) {
        if (movement.horizontalLengthSquared() <= 1.0E-5) {
            return;
        }
        float yaw = 180.0F - (float) (Math.atan2(movement.z, movement.x) * 180.0 / Math.PI);
        if (this.minecart.isYawFlipped()) {
            yaw += 180.0F;
        }
        this.setYaw(yaw % 360.0F);
        this.setPitch(0.0F);
    }
}
