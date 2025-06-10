package net.irisshaders.iris.mixin.forge;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.properties.ParticleRenderingSettings;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Predicate;

/**
 * Uses the PhasedParticleManager changes to render opaque particles much earlier than other particles.
 * <p>
 * See the comments in {@link MixinParticleEngine} for more details.
 */
@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer {
	@WrapOperation(method = "lambda$addMainPass$3", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/ParticleEngine;render(Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V"))
	private void redirectSolidParticles(ParticleEngine instance, Camera camera, float v, MultiBufferSource.BufferSource bufferSource, Frustum frustum, Predicate<ParticleRenderType> predicate, Operation<Void> original) {
		ParticleRenderingSettings settings = getRenderingSettings();

		Predicate<ParticleRenderType> newPredicate = predicate;

		if (settings == ParticleRenderingSettings.BEFORE) {
			newPredicate = (t) -> true;
		} else if (settings == ParticleRenderingSettings.AFTER) {
			return;
		}

		original.call(instance, camera, v, bufferSource, frustum, newPredicate);
	}

	@WrapOperation(method = "lambda$addParticlesPass$6", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/ParticleEngine;render(Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V"))
	private void redirectTransParticles(ParticleEngine instance, Camera camera, float v, MultiBufferSource.BufferSource bufferSource, Frustum frustum, Predicate<ParticleRenderType> predicate, Operation<Void> original) {
		ParticleRenderingSettings settings = getRenderingSettings();

		Predicate<ParticleRenderType> newPredicate = predicate;

		if (settings == ParticleRenderingSettings.BEFORE) {
			return;
		} else if (settings == ParticleRenderingSettings.AFTER) {
			newPredicate = (t) -> true;
		}

		original.call(instance, camera, v, bufferSource, frustum, newPredicate);
	}

	private ParticleRenderingSettings getRenderingSettings() {
		return Iris.getPipelineManager().getPipeline().map(WorldRenderingPipeline::getParticleRenderingSettings).orElse(ParticleRenderingSettings.MIXED);
	}
}
