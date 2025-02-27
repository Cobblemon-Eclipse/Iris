package net.irisshaders.iris.pipeline.programs;

import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.opengl.Uniform;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.GLDebug;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.blending.BufferBlendOverride;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.image.ImageHolder;
import net.irisshaders.iris.gl.program.ProgramImages;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.gl.uniform.DynamicLocationalUniformHolder;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.samplers.IrisSamplers;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.irisshaders.iris.vertices.ImmediateState;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.ARBTextureSwizzle;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.opengl.KHRDebug;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class ExtendedShader extends GlProgram {
	private static final Matrix4f identity;
	private static ExtendedShader lastApplied;

	static {
		identity = new Matrix4f();
		identity.identity();
	}

	private final boolean intensitySwizzle;
	private final List<BufferBlendOverride> bufferBlendOverrides;
	private final boolean hasOverrides;
	private final Uniform modelViewInverse;
	private final Uniform projectionInverse;
	private final Matrix3f normalMatrix = new Matrix3f();
	private final CustomUniforms customUniforms;
	private final IrisRenderingPipeline parent;
	private final ProgramUniforms uniforms;
	private final ProgramSamplers samplers;
	private final ProgramImages images;
	private final GlFramebuffer writingToBeforeTranslucent;
	private final GlFramebuffer writingToAfterTranslucent;
	private final BlendModeOverride blendModeOverride;
	private final float alphaTest;
	private final boolean usesTessellation;
	private final Matrix4f tempMatrix4f = new Matrix4f();
	private final Matrix3f tempMatrix3f = new Matrix3f();
	private final float[] tempFloats = new float[16];
	private final float[] tempFloats2 = new float[9];
	private int textureToUnswizzle;
	private int normalMatLocation;

	public ExtendedShader(int programId, String string, VertexFormat vertexFormat, boolean usesTessellation,
						  GlFramebuffer writingToBeforeTranslucent, GlFramebuffer writingToAfterTranslucent,
						  BlendModeOverride blendModeOverride, AlphaTest alphaTest,
						  Consumer<DynamicLocationalUniformHolder> uniformCreator, BiConsumer<SamplerHolder, ImageHolder> samplerCreator, boolean isIntensity,
						  IrisRenderingPipeline parent, @Nullable List<BufferBlendOverride> bufferBlendOverrides, CustomUniforms customUniforms) throws IOException {
		super(programId, string);

		List<RenderPipeline.UniformDescription> uniformList = new ArrayList<>();
		List<String> samplerList = new ArrayList<>();
		uniformList.add(new RenderPipeline.UniformDescription("iris_ModelViewMat", UniformType.MATRIX4X4));
		uniformList.add(new RenderPipeline.UniformDescription("iris_ProjMat", UniformType.MATRIX4X4));
		uniformList.add(new RenderPipeline.UniformDescription("iris_TextureMat", UniformType.MATRIX4X4));
		uniformList.add(new RenderPipeline.UniformDescription("iris_ColorModulator", UniformType.VEC4));
		uniformList.add(new RenderPipeline.UniformDescription("iris_FogColor", UniformType.VEC4));
		uniformList.add(new RenderPipeline.UniformDescription("iris_ModelOffset", UniformType.VEC3));
		uniformList.add(new RenderPipeline.UniformDescription("iris_FogStart", UniformType.FLOAT));
		uniformList.add(new RenderPipeline.UniformDescription("iris_FogEnd", UniformType.FLOAT));
		uniformList.add(new RenderPipeline.UniformDescription("iris_GlintAlpha", UniformType.FLOAT));
		uniformList.add(new RenderPipeline.UniformDescription("iris_ModelViewMatInverse", UniformType.MATRIX4X4));
		uniformList.add(new RenderPipeline.UniformDescription("iris_ProjMatInverse", UniformType.MATRIX4X4));

		if (vertexFormat.contains(VertexFormatElement.UV0)) {
			samplerList.add("Sampler0");
		}
		if (vertexFormat.contains(VertexFormatElement.UV1)) {
			samplerList.add("Sampler1");
		}
		if (vertexFormat.contains(VertexFormatElement.UV2)) {
			samplerList.add("Sampler2");
		}
		setupUniforms(uniformList, samplerList);


		GLDebug.nameObject(KHRDebug.GL_PROGRAM, programId, string);

		ProgramUniforms.Builder uniformBuilder = ProgramUniforms.builder(string, programId);

		this.normalMatLocation = GlStateManager._glGetUniformLocation(programId, "iris_NormalMat");
		ProgramSamplers.Builder samplerBuilder = ProgramSamplers.builder(programId, IrisSamplers.WORLD_RESERVED_TEXTURE_UNITS);
		uniformCreator.accept(uniformBuilder);
		ProgramImages.Builder builder = ProgramImages.builder(programId);
		samplerCreator.accept(samplerBuilder, builder);
		customUniforms.mapholderToPass(uniformBuilder, this);
		this.usesTessellation = usesTessellation;

		uniforms = uniformBuilder.buildUniforms();
		this.customUniforms = customUniforms;
		samplers = samplerBuilder.build();
		images = builder.build();
		this.writingToBeforeTranslucent = writingToBeforeTranslucent;
		this.writingToAfterTranslucent = writingToAfterTranslucent;
		this.blendModeOverride = blendModeOverride;
		this.bufferBlendOverrides = bufferBlendOverrides;
		this.hasOverrides = bufferBlendOverrides != null && !bufferBlendOverrides.isEmpty();
		this.alphaTest = alphaTest.reference();
		this.parent = parent;

		this.modelViewInverse = this.getUniform("ModelViewMatInverse");
		this.projectionInverse = this.getUniform("ProjMatInverse");

		this.intensitySwizzle = isIntensity;
	}

	public boolean isIntensitySwizzle() {
		return intensitySwizzle;
	}

	@Override
	public void clear() {
		ProgramUniforms.clearActiveUniforms();
		ProgramSamplers.clearActiveSamplers();

		if (this.blendModeOverride != null || hasOverrides) {
			BlendModeOverride.restore();
		}

		GlStateManager._glUseProgram(0);
	}

	@Override
	public void setDefaultUniforms(VertexFormat.Mode mode, Matrix4f modelView, Matrix4f projection, float f, float g) {
		super.setDefaultUniforms(mode, modelView, projection, f, g);
		if (modelViewInverse != null) {
			modelViewInverse.set(modelView.invert(tempMatrix4f));
		}

		if (normalMatrix != null) {
			normalMatrix.set(modelView.invert(tempMatrix4f).transpose3x3(tempMatrix3f));
		}

		if (projectionInverse != null) {
			projectionInverse.set(projection.invert(tempMatrix4f));
		}

		apply();
	}

	private static float[] tmpFloat9 = new float[9];

	public void apply() {
		CapturedRenderingState.INSTANCE.setCurrentAlphaTest(alphaTest);

		GlStateManager._glUseProgram(getProgramId());

		int i = GlStateManager._getActiveTexture();

		GlStateManager._activeTexture(i);

		if (intensitySwizzle) {
			IrisRenderSystem.addUnswizzle(((GlTexture) RenderSystem.getShaderTexture(0)).glId());
			IrisRenderSystem.texParameteriv(((GlTexture) RenderSystem.getShaderTexture(0)).glId(), TextureType.TEXTURE_2D.getGlType(), ARBTextureSwizzle.GL_TEXTURE_SWIZZLE_RGBA,
				new int[]{GL30C.GL_RED, GL30C.GL_RED, GL30C.GL_RED, GL30C.GL_RED});
		}

		//if (RenderSystem.getShaderTexture(0) != null) RenderSystem.getShaderTexture(0).bindToUnit(0);
		if (RenderSystem.getShaderTexture(1) != null) RenderSystem.getShaderTexture(1).bindToUnit(1);
		if (RenderSystem.getShaderTexture(2) != null) RenderSystem.getShaderTexture(2).bindToUnit(2);

		ImmediateState.usingTessellation = usesTessellation;

		uploadIfNotNull(projectionInverse);
		uploadIfNotNull(modelViewInverse);

		samplers.update();
		uniforms.update();

		if (this.normalMatLocation > -1) {
			GL46C.glUniformMatrix3fv(normalMatLocation, false, normalMatrix.get(tmpFloat9));
		}

		List<Uniform> uniformList = super.getUniforms();
		for (Uniform uniform : uniformList) {
			uploadIfNotNull(uniform);
		}

		customUniforms.push(this);

		images.update();

		//GL46C.glUniform1i(GlStateManager._glGetUniformLocation(getProgramId(), "iris_overlay"), 1);

		if (this.blendModeOverride != null) {
			this.blendModeOverride.apply();
		}

		if (hasOverrides) {
			bufferBlendOverrides.forEach(BufferBlendOverride::apply);
		}

		if (parent.isBeforeTranslucent) {
			writingToBeforeTranslucent.bind();
		} else {
			writingToAfterTranslucent.bind();
		}
	}

	@Nullable
	@Override
	public Uniform getUniform(@NotNull String name) {
		// Prefix all uniforms with Iris to help avoid conflicts with existing names within the shader.
		Uniform uniform = super.getUniform("iris_" + name);

		if (uniform == null && (name.equalsIgnoreCase("OverlayUV") || name.equalsIgnoreCase("LightUV"))) {
			return null;
		} else {
			return uniform;
		}
	}

	@Override
	public void setupUniforms(List<RenderPipeline.UniformDescription> list, List<String> list2) {
		RenderSystem.assertOnRenderThread();

		for (RenderPipeline.UniformDescription uniformDescription : list) {
			String string = uniformDescription.name();
			int i = Uniform.glGetUniformLocation(this.getProgramId(), string);
			if (i != -1) {
				Uniform uniform = this.createUniform(uniformDescription);
				uniform.setLocation(i);
				super.uniforms.add(uniform);
				this.uniformsByName.put(string, uniform);
			}
		}

		for (String string2 : list2) {
			int j = Uniform.glGetUniformLocation(this.getProgramId(), string2);
			super.samplers.add(string2);
			super.samplerLocations.add(j);
		}

		this.MODEL_VIEW_MATRIX = super.getUniform("iris_ModelViewMat");
		this.PROJECTION_MATRIX = super.getUniform("iris_ProjMat");
		this.TEXTURE_MATRIX = super.getUniform("iris_TextureMat");
		this.SCREEN_SIZE = super.getUniform("iris_ScreenSize");
		this.COLOR_MODULATOR = super.getUniform("iris_ColorModulator");
		this.LIGHT0_DIRECTION = super.getUniform("iris_Light0_Direction");
		this.LIGHT1_DIRECTION = super.getUniform("iris_Light1_Direction");
		this.GLINT_ALPHA = super.getUniform("iris_GlintAlpha");
		this.FOG_START = super.getUniform("iris_FogStart");
		this.FOG_END = super.getUniform("iris_FogEnd");
		this.FOG_COLOR = super.getUniform("iris_FogColor");
		this.FOG_SHAPE = super.getUniform("iris_FogShape");
		this.LINE_WIDTH = super.getUniform("iris_LineWidth");
		this.GAME_TIME = super.getUniform("iris_GameTime");
		this.MODEL_OFFSET = super.getUniform("iris_ModelOffset");
	}


	private void uploadIfNotNull(Uniform uniform) {
		if (uniform != null) {
			uniform.upload();
		}
	}

	public boolean hasActiveImages() {
		return images.getActiveImages() > 0;
	}
}
