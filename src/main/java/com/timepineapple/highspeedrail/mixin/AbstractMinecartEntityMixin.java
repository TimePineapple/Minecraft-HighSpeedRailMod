package com.timepineapple.highspeedrail.mixin;

import com.timepineapple.highspeedrail.minecart.MinecartSpeedManager;
import com.timepineapple.highspeedrail.minecart.MinecartSpeedState;
import com.timepineapple.highspeedrail.minecart.MinecartSpeedStateHolder;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.entity.vehicle.MinecartEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractMinecartEntity.class)
public abstract class AbstractMinecartEntityMixin implements MinecartSpeedStateHolder {
    @Unique
    private final MinecartSpeedState highSpeedRail$speedState = new MinecartSpeedState();

    @Override
    public MinecartSpeedState highSpeedRail$getSpeedState() {
        return highSpeedRail$speedState;
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void highSpeedRail$beforeTick(CallbackInfo callbackInfo) {
        if ((Object) this instanceof MinecartEntity cart && cart.getEntityWorld() instanceof ServerWorld serverWorld) {
            MinecartSpeedManager.beforeVanillaTick(cart, serverWorld);
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void highSpeedRail$afterTick(CallbackInfo callbackInfo) {
        if ((Object) this instanceof MinecartEntity cart && cart.getEntityWorld() instanceof ServerWorld serverWorld) {
            MinecartSpeedManager.afterVanillaTick(cart, serverWorld);
        }
    }

    @Inject(method = "getMaxSpeed", at = @At("RETURN"), cancellable = true)
    private void highSpeedRail$raisePerCartSpeedCap(ServerWorld world, CallbackInfoReturnable<Double> callbackInfo) {
        if ((Object) this instanceof MinecartEntity cart) {
            callbackInfo.setReturnValue(MinecartSpeedManager.speedCap(cart, callbackInfo.getReturnValue()));
        }
    }
}
