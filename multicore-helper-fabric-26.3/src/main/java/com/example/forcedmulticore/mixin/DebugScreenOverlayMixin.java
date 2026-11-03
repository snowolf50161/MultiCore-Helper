package com.example.forcedmulticore.mixin;

import com.example.forcedmulticore.MultiCoreHelperFabric;
import com.example.forcedmulticore.MultiCoreHelperFabricClient;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.List;

@Mixin(DebugScreenOverlay.class)
public abstract class DebugScreenOverlayMixin {
    @ModifyArg(
            method = "extractRenderState",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/DebugScreenOverlay;extractLines(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Ljava/util/List;ZI)V",
                    ordinal = 1),
            index = 1)
    private List<String> forcedmulticore$appendInfo(List<String> lines) {
        if (MultiCoreHelperFabricClient.isF3InfoEnabled()) {
            lines.addAll(MultiCoreHelperFabric.f3Lines());
        }
        return lines;
    }
}
