package net.irisshaders.iris.pipeline.programs;

import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.opengl.Uniform;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.irisshaders.iris.compat.SkipList;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.blending.DepthColorStorage;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.mixinterface.ShaderInstanceInterface;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.samplers.IrisSamplers;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class FallbackShader extends GlProgram {
	private final IrisRenderingPipeline parent;
	private final BlendModeOverride blendModeOverride;
	private final GlFramebuffer writingToBeforeTranslucent;
	private final GlFramebuffer writingToAfterTranslucent;

	@Nullable
	private final Uniform FOG_DENSITY;

	@Nullable
	private final Uniform FOG_IS_EXP2;
	private final int gtexture;
	private final int overlay;
	private final int lightmap;

	public FallbackShader(int programId, RenderPipeline pipeline, String string, VertexFormat vertexFormat,
						  GlFramebuffer writingToBeforeTranslucent, GlFramebuffer writingToAfterTranslucent,
						  BlendModeOverride blendModeOverride, float alphaValue, IrisRenderingPipeline parent) throws IOException {
		super(programId, string);
		((ShaderInstanceInterface) this).setShouldSkip(SkipList.NONE);

		List<RenderPipeline.UniformDescription> uniforms = new ArrayList<>(pipeline.getUniforms());

		uniforms.add(new RenderPipeline.UniformDescription("AlphaTestValue", UniformType.FLOAT));
		uniforms.add(new RenderPipeline.UniformDescription("FogDensity", UniformType.FLOAT));
		uniforms.add(new RenderPipeline.UniformDescription("FogIsExp2", UniformType.INT));
		uniforms.add(new RenderPipeline.UniformDescription("ModelOffset", UniformType.VEC3));
		uniforms.add(new RenderPipeline.UniformDescription("TextureMat", UniformType.MATRIX4X4));
		uniforms.add(new RenderPipeline.UniformDescription("LineWidth", UniformType.FLOAT));
		uniforms.add(new RenderPipeline.UniformDescription("ScreenSize", UniformType.VEC2));

		setupUniforms(uniforms, pipeline.getSamplers());

		this.parent = parent;
		this.blendModeOverride = blendModeOverride;
		this.writingToBeforeTranslucent = writingToBeforeTranslucent;
		this.writingToAfterTranslucent = writingToAfterTranslucent;

		this.FOG_DENSITY = this.getUniform("FogDensity");
		this.FOG_IS_EXP2 = this.getUniform("FogIsExp2");

		this.gtexture = GlStateManager._glGetUniformLocation(programId, "gtexture");
		this.overlay = GlStateManager._glGetUniformLocation(programId, "overlay");
		this.lightmap = GlStateManager._glGetUniformLocation(programId, "lightmap");

		GlStateManager._glUseProgram(programId);


		Uniform ALPHA_TEST_VALUE = this.getUniform("AlphaTestValue");

		if (ALPHA_TEST_VALUE != null) {
			ALPHA_TEST_VALUE.set(alphaValue);
			ALPHA_TEST_VALUE.upload();
		}
	}

	@Override
	public void clear() {
		super.clear();

		if (this.blendModeOverride != null) {
			BlendModeOverride.restore();
		}
	}

	@Override
	public void setDefaultUniforms(VertexFormat.Mode mode, Matrix4f matrix4f, Matrix4f matrix4f2, float width, float height) {
		DepthColorStorage.unlockDepthColor();

		super.setDefaultUniforms(mode, matrix4f, matrix4f2, width, height);


		if (FOG_DENSITY != null && FOG_IS_EXP2 != null) {
			float fogDensity = CapturedRenderingState.INSTANCE.getFogDensity();

			if (fogDensity >= 0.0) {
				FOG_DENSITY.set(fogDensity);
				FOG_IS_EXP2.set(1);
			} else {
				FOG_DENSITY.set(0.0F);
				FOG_IS_EXP2.set(0);
			}
		}

		if (FOG_DENSITY != null) {
			FOG_DENSITY.upload();
		}

		if (FOG_IS_EXP2 != null) {
			FOG_IS_EXP2.upload();
		}

		GlStateManager._glUniform1i(gtexture, 0);
		GlStateManager._glUniform1i(overlay, 1);
		GlStateManager._glUniform1i(lightmap, 2);

		if (this.blendModeOverride != null) {
			this.blendModeOverride.apply();
		}

		if (parent.isBeforeTranslucent) {
			writingToBeforeTranslucent.bind();
		} else {
			writingToAfterTranslucent.bind();
		}
	}

	private void uploadIfNotNull(Uniform uniform) {
		if (uniform != null) {
			uniform.upload();
		}
	}
}
