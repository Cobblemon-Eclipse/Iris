package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.GlRenderPass;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.opengl.Uniform;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.textures.FilterMode;
import it.unimi.dsi.fastutil.ints.IntList;
import net.irisshaders.iris.gl.blending.DepthColorStorage;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.irisshaders.iris.vertices.ImmediateState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GlCommandEncoder.class)
public class MixinGlCommandEncoder {
	@Shadow
	@Nullable
	private RenderPipeline lastPipeline;

	@Shadow
	private boolean inRenderPass;

	// Do not change the viewport in the shadow pass.
	@Redirect(method = "createRenderPass(Lcom/mojang/blaze3d/textures/GpuTexture;Ljava/util/OptionalInt;Lcom/mojang/blaze3d/textures/GpuTexture;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPass;", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/opengl/GlStateManager;_viewport(IIII)V"))
	private void changeViewport(int i, int j, int k, int l) {
		if (ShadowRenderingState.areShadowsCurrentlyBeingRendered()) {
			return;
		} else {
			GlStateManager._viewport(i, j, k, l);
		}
	}

	@Redirect(method = "writeToBuffer", at = @At(value = "FIELD", target = "Lcom/mojang/blaze3d/opengl/GlCommandEncoder;inRenderPass:Z"))
	private boolean ignore(GlCommandEncoder instance) {
		if (ImmediateState.temporarilyIgnorePass) {
			return false;
		} else {
			return this.inRenderPass;
		}
	}

	@Redirect(method = {
		"writeToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Ljava/nio/IntBuffer;Lcom/mojang/blaze3d/platform/NativeImage$Format;IIIII)V",
		"writeToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Lcom/mojang/blaze3d/platform/NativeImage;IIIIIII)V",
		"writeToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Lcom/mojang/blaze3d/platform/NativeImage;)V"
	}, at = @At(value = "FIELD", target = "Lcom/mojang/blaze3d/opengl/GlCommandEncoder;inRenderPass:Z"))
	private boolean ignore2(GlCommandEncoder instance) {
		if (ImmediateState.temporarilyIgnorePass) {
			return false;
		} else {
			return this.inRenderPass;
		}
	}

	@Unique
	private static GlRenderPass lastPass;

	@Inject(method = "trySetup", at = @At("HEAD"), cancellable = true)
	private void iris$bypassSetup(GlRenderPass glRenderPass, CallbackInfoReturnable<Boolean> cir) {
		DepthColorStorage.unlockDepthColor();

		if (lastPass == glRenderPass && false) {
			cir.cancel();
		}

		lastPass = glRenderPass;

		if (glRenderPass.iris$getCustomPass() != null) {
			cir.setReturnValue(true);

			glRenderPass.iris$getCustomPass().setupState();

			RenderPipeline renderPipeline = glRenderPass.pipeline.info();

			if (glRenderPass.scissorState.isEnabled()) {
				GlStateManager._enableScissorTest();
				GlStateManager._scissorBox(glRenderPass.scissorState.getX(), glRenderPass.scissorState.getY(), glRenderPass.scissorState.getWidth(), glRenderPass.scissorState.getHeight());
			} else {
				GlStateManager._disableScissorTest();
			}

			if (this.lastPipeline != renderPipeline) {
				this.lastPipeline = renderPipeline;

				if (renderPipeline.getDepthTestFunction() != DepthTestFunction.NO_DEPTH_TEST) {
					GlStateManager._enableDepthTest();
					GlStateManager._depthFunc(GlConst.toGl(renderPipeline.getDepthTestFunction()));
				} else {
					GlStateManager._disableDepthTest();
				}

				if (renderPipeline.isCull()) {
					GlStateManager._enableCull();
				} else {
					GlStateManager._disableCull();
				}

				GlStateManager._polygonMode(1032, GlConst.toGl(renderPipeline.getPolygonMode()));
				GlStateManager._depthMask(renderPipeline.isWriteDepth());
				GlStateManager._colorMask(renderPipeline.isWriteColor(), renderPipeline.isWriteColor(), renderPipeline.isWriteColor(), renderPipeline.isWriteAlpha());

				if (renderPipeline.getDepthBiasConstant() == 0.0F && renderPipeline.getDepthBiasScaleFactor() == 0.0F) {
					GlStateManager._disablePolygonOffset();
				} else {
					GlStateManager._polygonOffset(renderPipeline.getDepthBiasScaleFactor(), renderPipeline.getDepthBiasConstant());
					GlStateManager._enablePolygonOffset();
				}

				switch (renderPipeline.getColorLogic()) {
					case NONE:
						GlStateManager._disableColorLogicOp();
						break;
					case OR_REVERSE:
						GlStateManager._enableColorLogicOp();
						GlStateManager._logicOp(5387);
				}
			}
		}
	}
}
