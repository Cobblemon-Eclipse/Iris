package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.mixinterface.RenderTypeInterface;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderType.class)
public abstract class MixinRenderType2 implements RenderTypeInterface {
	@Shadow
	public static RenderType weather(ResourceLocation resourceLocation, boolean bl) {
		return null;
	}

	@Override
	public RenderTarget iris$getRenderTarget() {
		return null;
	}

	@Override
	public RenderPipeline iris$getPipeline() {
		return null;
	}


	@Inject(method = "weather", at = @At(value = "HEAD"), cancellable = true)
	private static void iris$writeRainAndSnowToDepthBuffer(ResourceLocation resourceLocation, boolean bl, CallbackInfoReturnable<RenderType> cir) {
		if (!bl && Iris.getPipelineManager().getPipeline().map(WorldRenderingPipeline::shouldWriteRainAndSnowToDepthBuffer).orElse(false)) {
			cir.setReturnValue(weather(resourceLocation, bl));
		}
	}
}
