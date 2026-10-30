//? if fabric {
/*package net.bichal.mods.tessera;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

/^*
 * Mod entry point on Fabric (fabric.mod.json: "client"). The same startup as
 * NeoForge's {@link Tessera}; settings live in {@code config/tessera.properties}.
 ^/
public final class TesseraFabric implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        Tessera.init();
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> Tessera.warmUpGl());
    }
}
*///?}
