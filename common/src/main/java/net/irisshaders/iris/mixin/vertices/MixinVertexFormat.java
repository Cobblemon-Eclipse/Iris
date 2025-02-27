package net.irisshaders.iris.mixin.vertices;

import com.google.common.collect.ImmutableSet;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.mojang.blaze3d.opengl.VertexArrayCache;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.pipeline.programs.VertexFormatExtension;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.irisshaders.iris.vertices.ImmediateState;
import net.irisshaders.iris.vertices.IrisVertexFormats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Ensures that the correct state for the extended vertex format is set up when needed.
 */
@Mixin(GlCommandEncoder.class)
public abstract class MixinVertexFormat implements VertexFormatExtension {
	@Redirect(method = "createRenderPass(Lcom/mojang/blaze3d/textures/GpuTexture;Ljava/util/OptionalInt;Lcom/mojang/blaze3d/textures/GpuTexture;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPass;", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;_viewport(IIII)V"))
	private void changeViewport(int i, int j, int k, int l) {
		if (ShadowRenderingState.areShadowsCurrentlyBeingRendered()) {
			return;//GlStateManager._viewport();
		} else {
			GlStateManager._viewport(i, j, k, l);
		}
	}

	//@Redirect(method = "drawFromBuffers", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/opengl/VertexArrayCache;bindVertexArray(Lcom/mojang/blaze3d/vertex/VertexFormat;Lcom/mojang/blaze3d/opengl/GlBuffer;)V"))
	private void iris$onSetupBufferState(VertexArrayCache instance, VertexFormat vertexFormat, GlBuffer glBuffer) {
		if (Iris.isPackInUseQuick() && ImmediateState.renderWithExtendedVertexFormat) {
			if ((Object) this == DefaultVertexFormat.BLOCK) {
				instance.bindVertexArray(IrisVertexFormats.TERRAIN, glBuffer);
				return;

			} else if ((Object) this == DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP) {
				instance.bindVertexArray(IrisVertexFormats.GLYPH, glBuffer);
				return;

			} else if ((Object) this == DefaultVertexFormat.NEW_ENTITY) {
				instance.bindVertexArray(IrisVertexFormats.ENTITY, glBuffer);
				return;
			}
		}

		instance.bindVertexArray(vertexFormat, glBuffer);

	}


}
