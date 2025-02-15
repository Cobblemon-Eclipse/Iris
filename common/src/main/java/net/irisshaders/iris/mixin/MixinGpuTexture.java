package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.mixinterface.GpuTextureExtension;
import org.lwjgl.opengl.GL46C;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(GpuTexture.class)
public class MixinGpuTexture implements GpuTextureExtension {
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
}
