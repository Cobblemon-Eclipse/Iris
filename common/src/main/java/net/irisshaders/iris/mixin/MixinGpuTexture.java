package net.irisshaders.iris.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.platform.GlConst;
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

@Mixin(GlTexture.class)
public abstract class MixinGpuTexture extends GpuTexture implements GpuTextureExtension {
	@Shadow
	private boolean modesDirty;

	@Shadow
	public abstract int glId();

	public MixinGpuTexture(String string, TextureFormat textureFormat, int i, int j, int k) {
		super(string, textureFormat, i, j, k);
	}


	@Override
	public void bindToUnit(int unit) {
		if (this.modesDirty) {
			IrisRenderSystem.texParameteri(glId(), GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_WRAP_S, GlConst.toGl(this.addressModeU));
			IrisRenderSystem.texParameteri(glId(), GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_WRAP_T, GlConst.toGl(this.addressModeV));
			switch (this.minFilter) {
				case NEAREST -> IrisRenderSystem.texParameteri(glId(), GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, this.useMipmaps ? GL46C.GL_NEAREST_MIPMAP_LINEAR : GL46C.GL_NEAREST);
				case LINEAR -> IrisRenderSystem.texParameteri(glId(), GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, this.useMipmaps ? GL46C.GL_LINEAR_MIPMAP_LINEAR : GL46C.GL_LINEAR);
			}

			switch (this.magFilter) {
				case NEAREST -> IrisRenderSystem.texParameteri(glId(), GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_NEAREST);
				case LINEAR -> IrisRenderSystem.texParameteri(glId(), GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_LINEAR);
			}

			this.modesDirty = false;
		}

		IrisRenderSystem.bindTextureToUnit(GL46C.GL_TEXTURE_2D, unit, this.glId());
	}

	@Override
	public int flushAndId() {
		if (this.modesDirty) {
			IrisRenderSystem.texParameteri(glId(), GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_WRAP_S, GlConst.toGl(this.addressModeU));
			IrisRenderSystem.texParameteri(glId(), GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_WRAP_T, GlConst.toGl(this.addressModeV));
			switch (this.minFilter) {
				case NEAREST -> IrisRenderSystem.texParameteri(glId(), GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, this.useMipmaps ? GL46C.GL_NEAREST_MIPMAP_LINEAR : GL46C.GL_NEAREST);
				case LINEAR -> IrisRenderSystem.texParameteri(glId(), GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, this.useMipmaps ? GL46C.GL_LINEAR_MIPMAP_LINEAR : GL46C.GL_LINEAR);
			}

			switch (this.magFilter) {
				case NEAREST -> IrisRenderSystem.texParameteri(glId(), GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_NEAREST);
				case LINEAR -> IrisRenderSystem.texParameteri(glId(), GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_LINEAR);
			}

			this.modesDirty = false;
		}

		return this.glId();
	}
}
