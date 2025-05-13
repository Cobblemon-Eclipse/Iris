package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.opengl.GlTexture;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.mixinterface.GpuTextureInterface;
import org.lwjgl.opengl.GL46C;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GlTexture.class)
public abstract class MixinGpuTexture implements GpuTextureInterface {
	@Unique
	private boolean mipmapNonLinear;

	@Shadow
	public abstract int glId();

	@Shadow
	public abstract void flushModeChanges(int i);

	@Shadow
	@Final
	protected int id;

	@Shadow
	protected boolean modesDirty;

	@Override
	public int iris$getGlId() {
		this.flushModeChanges(GL46C.GL_TEXTURE_2D);
		return this.glId();
	}

	@Override
	public void iris$markMipmapNonLinear() {
		boolean wasNonLinear = this.mipmapNonLinear;
		this.mipmapNonLinear = true;
		this.modesDirty = modesDirty || !wasNonLinear;
	}

	@Redirect(method = "flushModeChanges", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/opengl/GlStateManager;_texParameter(III)V"))
	private void useDSA(int i, int j, int k) {
		int newId = k;

		if (this.mipmapNonLinear && (k == GL46C.GL_NEAREST_MIPMAP_LINEAR || k == GL46C.GL_LINEAR_MIPMAP_LINEAR)) {
			newId = (k == GL46C.GL_LINEAR_MIPMAP_LINEAR ? GL46C.GL_LINEAR_MIPMAP_NEAREST : GL46C.GL_NEAREST_MIPMAP_NEAREST);
		}

		IrisRenderSystem.texParameteri(this.id, i, j, newId);
	}
}
