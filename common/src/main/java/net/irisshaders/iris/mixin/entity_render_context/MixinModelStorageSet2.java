package net.irisshaders.iris.mixin.entity_render_context;

import com.llamalad7.mixinextras.sugar.Local;
import net.irisshaders.iris.mixinterface.ModelStorage;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.feature.EntityModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CustomFeatureRenderer.class)
public class MixinModelStorageSet2 {
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector$CustomGeometryRenderer;render(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V"))
	private void iris$set(SubmitNodeStorage submitNodeStorage, MultiBufferSource.BufferSource bufferSource, CallbackInfo ci, @Local SubmitNodeStorage.CustomGeometrySubmit modelSubmit) {
		((ModelStorage) (Object) modelSubmit).iris$set();
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void iris$clear(SubmitNodeStorage submitNodeStorage, MultiBufferSource.BufferSource bufferSource, CallbackInfo ci) {
		CapturedRenderingState.INSTANCE.setCurrentRenderedItem(0);
		CapturedRenderingState.INSTANCE.setCurrentEntity(0);
		CapturedRenderingState.INSTANCE.setCurrentBlockEntity(0);
	}
}
