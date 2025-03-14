package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.caffeinemc.mods.sodium.client.render.immediate.CloudRenderer;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.vertices.ImmediateState;
import net.irisshaders.iris.vertices.IrisVertexFormats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderPipeline.class)
public class MixinRenderPipeline {
	@Inject(method = "getVertexFormat", at = @At("RETURN"), cancellable = true)
	private void iris$change(CallbackInfoReturnable<VertexFormat> cir) {
		if (Iris.isPackInUseQuick() && ImmediateState.renderWithExtendedVertexFormat && ImmediateState.isRenderingLevel) {
			VertexFormat vf = cir.getReturnValue();
			RenderPipeline thiss = (RenderPipeline) (Object) this;
			if (vf == DefaultVertexFormat.BLOCK) {
				cir.setReturnValue(IrisVertexFormats.TERRAIN);
			} else if (vf == DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP) {
				cir.setReturnValue(IrisVertexFormats.GLYPH);
			} else if (vf == DefaultVertexFormat.NEW_ENTITY) {
				cir.setReturnValue(IrisVertexFormats.ENTITY);
			} else if (thiss == CloudRenderer.CLOUDS_FLAT || thiss == CloudRenderer.CLOUDS_FULL) {
				cir.setReturnValue(IrisVertexFormats.CLOUDS);
			}
		}
	}
}
