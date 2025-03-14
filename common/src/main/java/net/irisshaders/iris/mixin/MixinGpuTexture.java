package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.opengl.GlTexture;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.mixinterface.GpuTextureInterface;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GlTexture.class)
public abstract class MixinGpuTexture implements GpuTextureInterface {
	@Shadow
	public abstract int glId();

	@Shadow
	public abstract void flushModeChanges();

	@Shadow
	@Final
	protected int id;

	@Override
	public int getGlId() {
		this.flushModeChanges();
		return this.glId();
	}

	@Redirect(method = "flushModeChanges", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/opengl/GlStateManager;_texParameter(III)V"))
	private void useDSA(int i, int j, int k) {
		IrisRenderSystem.texParameteri(this.id, i, j, k);
	}
}
