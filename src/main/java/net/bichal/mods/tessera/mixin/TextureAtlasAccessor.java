package net.bichal.mods.tessera.mixin;

import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;


/** The atlas's sprites by name. */
@Mixin(TextureAtlas.class)
public interface TextureAtlasAccessor {

    @Accessor("texturesByName")
    Map<?, net.minecraft.client.renderer.texture.TextureAtlasSprite> tessera$texturesByName();
}
