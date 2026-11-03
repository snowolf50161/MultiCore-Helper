package com.example.forcedmulticore.mixin;

import com.example.forcedmulticore.MultiCoreHelperFabric;
import com.example.forcedmulticore.MultiCoreHelperFabricClient;
import net.minecraft.client.gui.hud.DebugHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(DebugHud.class)
public abstract class DebugHudMixin {
    @Inject(method = "getRightText", at = @At("RETURN"))
    private void forcedmulticore$appendInfo(CallbackInfoReturnable<List<String>> cir) {
        if (MultiCoreHelperFabricClient.isF3InfoEnabled()) {
            cir.getReturnValue().addAll(MultiCoreHelperFabric.f3Lines());
        }
    }
}
