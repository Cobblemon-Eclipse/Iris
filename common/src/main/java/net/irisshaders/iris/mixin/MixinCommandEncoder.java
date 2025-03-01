package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.mojang.blaze3d.opengl.GlRenderPass;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GlCommandEncoder.class)
public abstract class MixinCommandEncoder {
	@Shadow
	protected abstract void applyPipelineState(RenderPipeline renderPipeline);

	@Inject(method = "trySetup", at = @At(value = "HEAD"), cancellable = true)
	public void iris$useCustomProgram(GlRenderPass glRenderPass, CallbackInfoReturnable<Boolean> cir) {
		if (glRenderPass.iris$getCustomState() != null) {
			cir.setReturnValue(true);

			this.applyPipelineState(glRenderPass.pipeline.info());

			if (glRenderPass.scissorState.isEnabled()) {
				GlStateManager._enableScissorTest();
				GlStateManager._scissorBox(
					glRenderPass.scissorState.getX(), glRenderPass.scissorState.getY(), glRenderPass.scissorState.getWidth(), glRenderPass.scissorState.getHeight()
				);
			} else {
				GlStateManager._disableScissorTest();
			}

			glRenderPass.iris$getCustomState().apply();
		}
	}
}
