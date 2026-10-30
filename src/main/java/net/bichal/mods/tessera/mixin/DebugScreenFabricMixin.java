package net.bichal.mods.tessera.mixin;

import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
//? if fabric && <1.21.10 {
/*import net.bichal.mods.tessera.gui.DebugOverlay;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
*///?}

/**
 * Fabric, up to 1.21.4: Tessera's lines at the end of F3's right column (NeoForge: the
 * CustomizeGuiOverlayEvent). Empty elsewhere; see {@link DebugScreenEntriesFabricMixin} for 1.21.10+.
 */
@Mixin(DebugScreenOverlay.class)
public abstract class DebugScreenFabricMixin {

    //? if fabric && <1.21.10 {
    /*@Inject(method = "getSystemInformation", at = @At("RETURN"))
    private void tessera$addLines(CallbackInfoReturnable<List<String>> cir) {
        DebugOverlay.addF3Lines(cir.getReturnValue());
    }
    *///?}
}
