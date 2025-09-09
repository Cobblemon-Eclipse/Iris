package net.irisshaders.iris.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.layer.OuterWrappedRenderType;
import net.irisshaders.iris.layer.SetStateShard;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SkyRenderer.class)
public class MixinSkyRenderer {
	@Inject(method = "renderSkyDisc",
		at = @At(value = "HEAD"))
	private void iris$renderSky$beginNormalSky(float f, float g, float h, CallbackInfo ci) {
		// None of the vanilla sky is rendered until after this call, so if anything is rendered before, it's
		// CUSTOM_SKY.
		setPhase(WorldRenderingPhase.SKY);
	}

	@Inject(method = "renderSun", at = @At("HEAD"), cancellable = true)
	private void iris$beforeDrawSun(float f, MultiBufferSource multiBufferSource, PoseStack poseStack, CallbackInfo ci) {
		if (!Iris.getPipelineManager().getPipeline().map(WorldRenderingPipeline::shouldRenderSun).orElse(true)) {
			ci.cancel();
		}
	}

	@Inject(method = "renderMoon", at = @At("HEAD"), cancellable = true)
	private void iris$beforeDrawMoon(int i, float f, MultiBufferSource multiBufferSource, PoseStack poseStack, CallbackInfo ci) {
		if (!Iris.getPipelineManager().getPipeline().map(WorldRenderingPipeline::shouldRenderMoon).orElse(true)) {
			ci.cancel();
		}
	}

	@WrapOperation(method = "renderSun", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MultiBufferSource;getBuffer(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
	private VertexConsumer iris$setSunRenderStage(MultiBufferSource instance, RenderType renderType, Operation<VertexConsumer> original) {
		return original.call(instance, new OuterWrappedRenderType("iris_sun", renderType, SetStateShard.SUN));
	}

	@WrapOperation(method = "renderSunriseAndSunset", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;getBuffer(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
	private VertexConsumer iris$setSunsetRenderStage(MultiBufferSource.BufferSource instance, RenderType renderType, Operation<VertexConsumer> original) {
		return original.call(instance, new OuterWrappedRenderType("iris_sunset", renderType, SetStateShard.SUNSET));

	}

	@WrapOperation(method = "renderMoon", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MultiBufferSource;getBuffer(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
	private VertexConsumer iris$setMoonRenderStage(MultiBufferSource instance, RenderType renderType, Operation<VertexConsumer> original) {
		return original.call(instance, new OuterWrappedRenderType("iris_moon", renderType, SetStateShard.MOON));
	}

	@Inject(method = "renderStars", at = @At(value = "HEAD"))
	private void iris$setStarRenderStage(float f, PoseStack poseStack, CallbackInfo ci) {
		setPhase(WorldRenderingPhase.STARS);
	}

	@Inject(method = "renderDarkDisc", at = @At(value = "HEAD"))
	private void iris$setVoidRenderStage(CallbackInfo ci) {
		setPhase(WorldRenderingPhase.VOID);
	}

	@Inject(method = "renderSunMoonAndStars", at = @At(value = "INVOKE", target = "Lcom/mojang/math/Axis;rotationDegrees(F)Lorg/joml/Quaternionf;", ordinal = 1))
	private void iris$renderSky$tiltSun(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource, float f, int i, float g, float h, CallbackInfo ci) {
		poseStack.mulPose(Axis.ZP.rotationDegrees(getSunPathRotation()));
	}

	private float getSunPathRotation() {
		if (Iris.getPipelineManager().getPipelineNullable() == null) return 0;
		return Iris.getPipelineManager().getPipelineNullable().getSunPathRotation();
	}

	public void setPhase(WorldRenderingPhase phase) {
		if (Iris.getPipelineManager().getPipelineNullable() == null) return;

		Iris.getPipelineManager().getPipelineNullable().setPhase(phase);
	}
}
