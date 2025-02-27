package net.irisshaders.iris.pipeline.programs;

import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.ShaderRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.ResourceLocation;

public class ShaderAccess {
	public static final VertexFormat IE_FORMAT = VertexFormat.builder()
		.add("Position", VertexFormatElement.POSITION)
		.add("Color", VertexFormatElement.COLOR)
		.add("UV0", VertexFormatElement.UV0)
		.add("Normal", VertexFormatElement.NORMAL)
		.padding(1)
		.build();

	public static GlProgram getParticleTranslucentShader() {
		WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();

		if (pipeline instanceof ShaderRenderingPipeline) {
			GlProgram override = ((ShaderRenderingPipeline) pipeline).getShaderMap().getShader(ShaderKey.PARTICLES_TRANS);

			if (override != null) {
				return override;
			}
		}

		return null;//Minecraft.getInstance().getShaderManager().getProgram(RenderPipelines.TRANSLUCENT_PARTICLE);
	}

	public static GlProgram getIEVBOShader() {
		WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();

		if (pipeline instanceof ShaderRenderingPipeline) {

			return null;//((ShaderRenderingPipeline) pipeline).getShaderMap().getShader(ShadowRenderingState.areShadowsCurrentlyBeingRendered() ? ShaderKey.IE_COMPAT_SHADOW : ShaderKey.IE_COMPAT);
		}

		return null;
	}

	// TODO SPS 1.21.2
}
