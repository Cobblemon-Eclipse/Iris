package net.irisshaders.iris.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.GLDebug;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.mixinterface.GpuTextureExtension;
import org.lwjgl.opengl.GL46C;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

@Mixin(GpuTexture.class)
public abstract class MixinGpuTexture implements GpuTextureExtension {
	@Shadow
	private boolean modesDirty;

	@Shadow
	private AddressMode addressModeU;

	@Shadow
	private AddressMode addressModeV;

	@Shadow
	private boolean useMipmaps;

	@Shadow
	private FilterMode minFilter;

	@Shadow
	private FilterMode magFilter;

	@Shadow
	@Final
	private int id;

	@Shadow
	public abstract int glId();

	@ModifyArg(method = "<init>(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/TextureFormat;III)V", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/textures/GpuTexture;<init>(Ljava/lang/String;Lcom/mojang/blaze3d/textures/TextureFormat;III)V"), index = 0)
	private static String modify(String string, @Local(argsOnly = true) Supplier<String> sup) {
		if (Iris.getIrisConfig().areDebugOptionsEnabled()) return sup.get();

		return string;
	}

	@Inject(method = "<init>(Ljava/lang/String;Lcom/mojang/blaze3d/textures/TextureFormat;III)V", at = @At("TAIL"))
	private void iris$addLabel(String string, TextureFormat textureFormat, int i, int j, int k, CallbackInfo ci) {
		if (string != null) GLDebug.nameObject(GL46C.GL_TEXTURE, this.id, string);
	}

	@Override
	public void bindToUnit(int unit) {
		if (this.modesDirty) {
			IrisRenderSystem.texParameteri(id, GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_WRAP_S, this.addressModeU.id);
			IrisRenderSystem.texParameteri(id, GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_WRAP_T, this.addressModeV.id);
			switch (this.minFilter) {
				case NEAREST -> IrisRenderSystem.texParameteri(id, GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, this.useMipmaps ? GL46C.GL_NEAREST_MIPMAP_LINEAR : GL46C.GL_NEAREST);
				case LINEAR -> IrisRenderSystem.texParameteri(id, GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, this.useMipmaps ? GL46C.GL_LINEAR_MIPMAP_LINEAR : GL46C.GL_LINEAR);
			}

			switch (this.magFilter) {
				case NEAREST -> IrisRenderSystem.texParameteri(id, GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_NEAREST);
				case LINEAR -> IrisRenderSystem.texParameteri(id, GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_LINEAR);
			}

			this.modesDirty = false;
		}

		IrisRenderSystem.bindTextureToUnit(GL46C.GL_TEXTURE_2D, unit, this.id);
	}

	@Override
	public int flushAndId() {
		if (this.modesDirty) {
			IrisRenderSystem.texParameteri(id, GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_WRAP_S, this.addressModeU.id);
			IrisRenderSystem.texParameteri(id, GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_WRAP_T, this.addressModeV.id);
			switch (this.minFilter) {
				case NEAREST -> IrisRenderSystem.texParameteri(id, GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, this.useMipmaps ? GL46C.GL_NEAREST_MIPMAP_LINEAR : GL46C.GL_NEAREST);
				case LINEAR -> IrisRenderSystem.texParameteri(id, GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, this.useMipmaps ? GL46C.GL_LINEAR_MIPMAP_LINEAR : GL46C.GL_LINEAR);
			}

			switch (this.magFilter) {
				case NEAREST -> IrisRenderSystem.texParameteri(id, GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_NEAREST);
				case LINEAR -> IrisRenderSystem.texParameteri(id, GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_LINEAR);
			}

			this.modesDirty = false;
		}

		return this.glId();
	}
}
