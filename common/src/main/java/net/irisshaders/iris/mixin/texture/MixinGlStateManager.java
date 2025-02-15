package net.irisshaders.iris.mixin.texture;

import com.mojang.blaze3d.platform.GlStateManager;
import net.irisshaders.iris.pbr.TextureTracker;
import net.irisshaders.iris.pbr.texture.PBRTextureManager;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.IntBuffer;

@Mixin(GlStateManager.class)
public class MixinGlStateManager {
	@Inject(method = "_deleteTexture(I)V", at = @At("TAIL"), remap = false)
	private static void iris$onDeleteTexture(int id, CallbackInfo ci) {
		iris$onDeleteTexture(id);
	}

	@Unique
	private static void iris$onDeleteTexture(int id) {
		TextureTracker.INSTANCE.onDeleteTexture(id);
		PBRTextureManager.INSTANCE.onDeleteTexture(id);
	}
}
