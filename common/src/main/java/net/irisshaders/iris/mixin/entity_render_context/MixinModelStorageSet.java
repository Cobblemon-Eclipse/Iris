package net.irisshaders.iris.mixin.entity_render_context;

import com.llamalad7.mixinextras.sugar.Local;
import net.irisshaders.iris.mixinterface.ModelStorage;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.EntityModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityModelFeatureRenderer.class)
public class MixinModelStorageSet {
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/EntityModelFeatureRenderer;renderModel(Lnet/minecraft/client/renderer/SubmitNodeStorage$ModelSubmit;Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/renderer/OutlineBufferSource;)V"))
	private void iris$set(SubmitNodeStorage submitNodeStorage, MultiBufferSource.BufferSource bufferSource, OutlineBufferSource outlineBufferSource, CallbackInfo ci, @Local SubmitNodeStorage.ModelSubmit<?> modelSubmit) {
		((ModelStorage) (Object) modelSubmit).iris$set();
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void iris$clear(SubmitNodeStorage submitNodeStorage, MultiBufferSource.BufferSource bufferSource, OutlineBufferSource outlineBufferSource, CallbackInfo ci) {
		CapturedRenderingState.INSTANCE.setCurrentRenderedItem(0);
		CapturedRenderingState.INSTANCE.setCurrentEntity(0);
		CapturedRenderingState.INSTANCE.setCurrentBlockEntity(0);
	}
}
