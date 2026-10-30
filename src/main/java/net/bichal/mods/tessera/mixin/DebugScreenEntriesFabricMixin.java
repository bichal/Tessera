package net.bichal.mods.tessera.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if fabric && >=1.21.10 {
/*import net.bichal.mods.tessera.compat.TesseraCompat;
import net.bichal.mods.tessera.gui.DebugOverlay;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.client.gui.components.debug.DebugScreenProfile;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Fabric, 1.21.10+: registers Tessera's two F3 screen entries (NeoForge: RegisterDebugEntriesEvent).
 * The target is named as a string: DebugScreenEntries does not exist before 1.21.10. Empty elsewhere.
 */
@Mixin(targets = "net.minecraft.client.gui.components.debug.DebugScreenEntries")
public abstract class DebugScreenEntriesFabricMixin {

    //? if fabric && >=1.21.11 {
    /*@Shadow
    private static net.minecraft.resources.Identifier register(net.minecraft.resources.Identifier id, DebugScreenEntry entry) {
        throw new AssertionError();
    }
    *///?}
    //? if fabric && >=1.21.10 && <1.21.11 {
    /*@Shadow
    private static net.minecraft.resources.ResourceLocation register(net.minecraft.resources.ResourceLocation id, DebugScreenEntry entry) {
        throw new AssertionError();
    }
    *///?}

    //? if fabric && >=1.21.10 {
    /*@Inject(method = "<clinit>", at = @At("TAIL"))
    private static void tessera$registerEntries(CallbackInfo ci) {
        var summary = TesseraCompat.id("atlas_compression");
        var breakdown = TesseraCompat.id("atlas_compression_breakdown");
        register(summary, (displayer, level, clientChunk, serverChunk) -> displayer.addToGroup(summary, DebugOverlay.summaryLines()));
        register(breakdown, (displayer, level, clientChunk, serverChunk) -> displayer.addToGroup(breakdown, DebugOverlay.breakdownLines()));
        var profile = DebugScreenEntries.PROFILES.get(DebugScreenProfile.DEFAULT);
        if (profile != null) {
            try {
                profile.put(summary, DebugOverlay.shownStatus());
            } catch (UnsupportedOperationException ignored) {
                // an immutable profile: the entry is still listed in the debug options
            }
        }
    }
    *///?}
}
