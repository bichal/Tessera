package net.bichal.mods.tessera.mixin;

import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

//? >=1.21.11 {
/*import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

import java.util.List;
*///?}

/** The atlas's sprites by name and, from 1.21.11, as a list with their animation states. */
@Mixin(TextureAtlas.class)
public interface TextureAtlasAccessor {

    @Accessor("texturesByName")
    Map<?, net.minecraft.client.renderer.texture.TextureAtlasSprite> tessera$texturesByName();

    //? >=1.21.11 {
    /*@Accessor("sprites")
    List<TextureAtlasSprite> tessera$sprites();

    @Accessor("animatedTexturesStates")
    List<SpriteContents.AnimationState> tessera$animationStates();
    *///?}
}
