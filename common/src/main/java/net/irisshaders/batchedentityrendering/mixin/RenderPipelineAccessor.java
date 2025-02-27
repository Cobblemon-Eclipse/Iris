package net.irisshaders.batchedentityrendering.mixin;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import net.minecraft.client.renderer.RenderStateShard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Optional;

@Mixin(RenderPipeline.class)
public interface RenderPipelineAccessor {
	@Accessor("blendFunction")
	Optional<BlendFunction> getTransparency();

	@Accessor("depthTestFunction")
	DepthTestFunction getDepth();
}
