package net.irisshaders.batchedentityrendering.mixin;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.irisshaders.batchedentityrendering.impl.BlendingStateHolder;
import net.irisshaders.batchedentityrendering.impl.TransparencyType;
import net.minecraft.client.renderer.DepthTestFunction;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

@Mixin(RenderType.CompositeRenderType.class)
public abstract class MixinCompositeRenderType extends RenderType implements BlendingStateHolder {
	@Unique
	private static final String INIT =
		"<init>(Ljava/lang/String;IZZLcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/client/renderer/RenderType$CompositeState;)V";

	@Unique
	private TransparencyType transparencyType;

	public MixinCompositeRenderType(String string, int i, boolean bl, boolean bl2, Runnable runnable, Runnable runnable2) {
		super(string, i, bl, bl2, runnable, runnable2);
	}

	@Inject(method = INIT, at = @At("RETURN"))
	private void batchedentityrendering$onCompositeInit(String string, int i, boolean bl, boolean bl2, RenderPipeline renderPipeline, CompositeState compositeState, CallbackInfo ci) {
		Optional<BlendFunction> transparency = ((RenderPipelineAccessor) (Object) renderPipeline).getTransparency();
		DepthTestFunction depth = ((RenderPipelineAccessor) (Object) renderPipeline).getDepth();

		if ("water_mask".equals(name) || depth == DepthTestFunction.NO_DEPTH_TEST) {
			transparencyType = TransparencyType.WATER_MASK;
		} else if ("lines".equals(name)) {
			transparencyType = TransparencyType.LINES;
		} else if (transparency.isEmpty() || "sunrise_sunset".equals(name)) {
			transparencyType = TransparencyType.OPAQUE;
		} else if (transparency.orElseThrow() == BlendFunction.GLINT) {
			transparencyType = TransparencyType.DECAL;
		} else {
			transparencyType = TransparencyType.GENERAL_TRANSPARENT;
		}
	}

	@Override
	public TransparencyType getTransparencyType() {
		return transparencyType;
	}

	@Override
	public void setTransparencyType(TransparencyType type) {
		transparencyType = type;
	}
}
