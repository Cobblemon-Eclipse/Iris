package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GlTexture.class)
public interface GlTextureInvoker {
	@Invoker("<init>")
	static GlTexture create(String string, TextureFormat textureFormat, int i, int j, int k, int l) {
		return null;
	}
}
