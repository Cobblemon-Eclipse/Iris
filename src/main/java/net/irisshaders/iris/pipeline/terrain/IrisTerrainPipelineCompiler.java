package net.irisshaders.iris.pipeline.terrain;

import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.shader.ShaderType;
import net.irisshaders.iris.gl.uniform.IrisUniformBuffer;
import net.irisshaders.iris.pipeline.VulkanTerrainPipeline;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import org.joml.Vector3d;
import net.irisshaders.iris.vulkan.shader.IrisSPIRVCompiler;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.ManualUBO;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.shader.layout.AlignedStruct;
import net.vulkanmod.vulkan.shader.layout.PushConstants;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.util.*;

import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_ALL_GRAPHICS;

/**
 * Compiles Iris terrain shader GLSL sources into VulkanMod GraphicsPipeline objects.
 *
 * Takes transformed shader sources from VulkanTerrainPipeline, preprocesses them
 * for Vulkan (UBO wrapping, vertex attribute type corrections, push constants),
 * compiles to SPIR-V, and builds GraphicsPipeline with COMPRESSED_TERRAIN format.
 */
public class IrisTerrainPipelineCompiler {

	private final TerrainGlslPreprocessor glslPreprocessor = new TerrainGlslPreprocessor();

	private GraphicsPipeline solidPipeline;
	private GraphicsPipeline cutoutPipeline;
	private GraphicsPipeline translucentPipeline;

	// Uniform buffers and ManualUBOs — one per pipeline since UBO layouts may differ
	private IrisUniformBuffer solidUniformBuffer;
	private IrisUniformBuffer cutoutUniformBuffer;
	private IrisUniformBuffer translucentUniformBuffer;
	private ManualUBO solidManualUBO;

	// Shadow terrain pipelines — use the shader pack's shadow.vsh/shadow.fsh
	// which apply the same distortion as the fragment shader's GetShadowPos() lookup
	private GraphicsPipeline shadowSolidPipeline;
	private GraphicsPipeline shadowCutoutPipeline;
	private IrisUniformBuffer shadowUniformBuffer;

	public void compile(VulkanTerrainPipeline terrainPipeline) {

		solidPipeline = tryCompileIrisShader("iris_terrain_solid",
			terrainPipeline.getTerrainSolidVertexShaderSource(),
			terrainPipeline.getTerrainSolidFragmentShaderSource());

		cutoutPipeline = tryCompileIrisShader("iris_terrain_cutout",
			terrainPipeline.getTerrainCutoutVertexShaderSource(),
			terrainPipeline.getTerrainCutoutFragmentShaderSource());

		translucentPipeline = tryCompileIrisShader("iris_terrain_translucent",
			terrainPipeline.getTranslucentVertexShaderSource(),
			terrainPipeline.getTranslucentFragmentShaderSource());

		// Compile shadow terrain shaders from the pack's shadow.vsh/shadow.fsh.
		// These include the shadow distortion that matches GetShadowPos() in the fragment shader.
		// Without these, the shadow depth map coordinates don't match the lookup → artifacts.
		shadowSolidPipeline = tryCompileShadowShader("iris_shadow_solid",
			terrainPipeline.getShadowVertexShaderSource(),
			terrainPipeline.getShadowFragmentShaderSource());
		shadowCutoutPipeline = tryCompileShadowShader("iris_shadow_cutout",
			terrainPipeline.getShadowVertexShaderSource(),
			terrainPipeline.getShadowCutoutFragmentShaderSource());
		if (shadowCutoutPipeline == null) shadowCutoutPipeline = shadowSolidPipeline;

		Iris.logger.info("[IrisTerrainPipelineCompiler] Compiled terrain pipelines (real Iris shaders with MinimalTest fallback). Shadow: {}",
			shadowSolidPipeline != null ? "compiled" : "not available");
	}

	private GraphicsPipeline tryCompileIrisShader(String name,
			java.util.Optional<String> vertOpt, java.util.Optional<String> fragOpt) {
		if (vertOpt != null && vertOpt.isPresent() && fragOpt != null && fragOpt.isPresent()) {
			try {
				GraphicsPipeline pipeline = compilePipeline(name, vertOpt.get(), fragOpt.get());
				Iris.logger.info("[IrisTerrainPipelineCompiler] Compiled REAL Iris shader: {}", name);
				return pipeline;
			} catch (Exception e) {
				Iris.logger.error("[IrisTerrainPipelineCompiler] Failed to compile Iris shader {}, falling back", name, e);
			}
		}

		try {
			GraphicsPipeline pipeline = compileMinimalTestPipeline(name);
			Iris.logger.info("[IrisTerrainPipelineCompiler] Compiled fallback test pipeline: {}", name);
			return pipeline;
		} catch (Exception e) {
			Iris.logger.error("[IrisTerrainPipelineCompiler] Failed to compile test pipeline: {}", name, e);
			return null;
		}
	}

	private GraphicsPipeline tryCompileShadowShader(String name,
			java.util.Optional<String> vertOpt, java.util.Optional<String> fragOpt) {
		if (vertOpt != null && vertOpt.isPresent() && fragOpt != null && fragOpt.isPresent()) {
			try {
				GraphicsPipeline pipeline = compilePipeline(name, vertOpt.get(), fragOpt.get(), true);
				Iris.logger.info("[IrisTerrainPipelineCompiler] Compiled shadow shader: {}", name);
				return pipeline;
			} catch (Exception e) {
				Iris.logger.error("[IrisTerrainPipelineCompiler] Failed to compile shadow shader {}", name, e);
			}
		}
		return null;
	}

	private GraphicsPipeline compilePipeline(String name, String vertSource, String fragSource) {
		return compilePipeline(name, vertSource, fragSource, false);
	}

	private GraphicsPipeline compilePipeline(String name, String vertSource, String fragSource, boolean isShadow) {
		@SuppressWarnings("unchecked")
		List<IrisSPIRVCompiler.UniformField> merged = IrisSPIRVCompiler.mergeUniforms(
			IrisSPIRVCompiler.collectLooseUniforms(vertSource),
			IrisSPIRVCompiler.collectLooseUniforms(fragSource)
		);

		// Remove u_RegionOffset from uniform list — it becomes a push constant
		merged.removeIf(f -> f.name().equals("u_RegionOffset"));

		String vshVulkan = IrisSPIRVCompiler.prepareForVulkan(vertSource, merged);
		String fshVulkan = IrisSPIRVCompiler.prepareForVulkan(fragSource, merged);

		vshVulkan = glslPreprocessor.fixTerrainVertexAttributes(vshVulkan);
		vshVulkan = glslPreprocessor.fixTerrainPositionDecoding(vshVulkan);
		vshVulkan = glslPreprocessor.addPushConstantBlock(vshVulkan);
		vshVulkan = glslPreprocessor.renameRegionOffset(vshVulkan);
		fshVulkan = glslPreprocessor.renameRegionOffset(fshVulkan);

		if (isShadow) {
			vshVulkan = glslPreprocessor.addShadowDepthCorrection(vshVulkan);
		} else {
			fshVulkan = glslPreprocessor.injectFragmentNormals(fshVulkan);
		}

		vshVulkan = glslPreprocessor.resolveGauxAliases(vshVulkan);
		fshVulkan = glslPreprocessor.resolveGauxAliases(fshVulkan);

		List<String> samplerNames = new ArrayList<>();
		glslPreprocessor.collectSamplerNames(vshVulkan, samplerNames);
		glslPreprocessor.collectSamplerNames(fshVulkan, samplerNames);
		List<String> uniqueSamplers = new ArrayList<>(new LinkedHashSet<>(samplerNames));

		Map<String, Integer> samplerBindings = new LinkedHashMap<>();
		for (int i = 0; i < uniqueSamplers.size(); i++) {
			samplerBindings.put(uniqueSamplers.get(i), i + 1);
		}

		vshVulkan = glslPreprocessor.addExplicitBindings(vshVulkan, samplerBindings);
		fshVulkan = glslPreprocessor.addExplicitBindings(fshVulkan, samplerBindings);

		fshVulkan = glslPreprocessor.fixVaryingTypeMismatches(vshVulkan, fshVulkan);

		String uboSource = vshVulkan.contains("IrisUniforms") ? vshVulkan : fshVulkan;
		IrisUniformBuffer uniformBuffer = IrisUniformBuffer.fromVulkanGLSL(uboSource);

		if (isShadow) {
			if (this.shadowUniformBuffer == null) {
				this.shadowUniformBuffer = uniformBuffer;
			}
		} else if (name.contains("solid")) {
			this.solidUniformBuffer = uniformBuffer;
		} else if (name.contains("cutout")) {
			this.cutoutUniformBuffer = uniformBuffer;
		} else if (name.contains("translucent")) {
			this.translucentUniformBuffer = uniformBuffer;
		}

		glslPreprocessor.dumpShaderToFile(name + ".vsh", vshVulkan);
		glslPreprocessor.dumpShaderToFile(name + ".fsh", fshVulkan);

		Iris.logger.info("[IrisTerrainPipelineCompiler] Compiling SPIR-V for {}", name);

		ByteBuffer vertSpirv = IrisSPIRVCompiler.compilePreprocessed(name + ".vsh", vshVulkan, ShaderType.VERTEX);
		ByteBuffer fragSpirv = IrisSPIRVCompiler.compilePreprocessed(name + ".fsh", fshVulkan, ShaderType.FRAGMENT);

		// Fix SPIR-V interface location mismatches: shaderc auto_map_locations may
		// assign different locations to the same varying in vertex vs fragment shaders.
		// This patches the fragment's input locations to match the vertex's outputs.
		IrisSPIRVCompiler.fixSpirvLocations(vertSpirv, fragSpirv, name);

		SPIRVUtils.SPIRV vertSPIRV = new SPIRVUtils.SPIRV(0, vertSpirv);
		SPIRVUtils.SPIRV fragSPIRV = new SPIRVUtils.SPIRV(0, fragSpirv);

		List<UBO> ubos = new ArrayList<>();
		List<ImageDescriptor> imageDescriptors = new ArrayList<>();

		int uboSizeBytes = Math.max(uniformBuffer.getUsedSize(), 16);
		int uboSizeWords = (uboSizeBytes + 3) / 4;
		ManualUBO manualUBO = new ManualUBO(0, VK_SHADER_STAGE_ALL_GRAPHICS, uboSizeWords);
		manualUBO.setSrc(uniformBuffer.getPointer(), uniformBuffer.getUsedSize());
		ubos.add(manualUBO);

		if (name.contains("solid")) {
			this.solidManualUBO = manualUBO;
		}

		for (int i = 0; i < uniqueSamplers.size(); i++) {
			String samplerName = uniqueSamplers.get(i);
			int texIdx = TerrainGlslPreprocessor.mapSamplerToTextureIndex(samplerName);
			imageDescriptors.add(new ImageDescriptor(i + 1, "sampler2D", samplerName, texIdx));
		}

		Pipeline.Builder builder = new Pipeline.Builder(CustomVertexFormat.COMPRESSED_TERRAIN, name);
		builder.setUniforms(ubos, imageDescriptors);
		builder.setSPIRVs(vertSPIRV, fragSPIRV);

		// Add push constants for ChunkOffset (vec3) — matches VulkanMod's terrain push constants
		AlignedStruct.Builder pcBuilder = new AlignedStruct.Builder();
		pcBuilder.addUniformInfo("float", "ChunkOffset", 3);
		PushConstants pushConstants = pcBuilder.buildPushConstant();
		builder.setPushConstants(pushConstants);

		GraphicsPipeline pipeline = builder.createGraphicsPipeline();

		Iris.logger.info("[IrisTerrainPipelineCompiler] Built pipeline {} ({} samplers, {} UBO bytes)",
			name, uniqueSamplers.size(), uboSizeBytes);

		return pipeline;
	}

	/**
	 * Updates the IrisUniforms UBO data with current render state.
	 * Must be called before terrain draw commands.
	 */
	private int uniformLogCounter = 0;

	// Frame timing state
	private long lastFrameNanos = System.nanoTime();
	private float cumulativeTime = 0.0f;
	private int frameCount = 0;
	private float[] prevMvArr = new float[16];
	private float[] prevProjArr = new float[16];
	private double prevCamX, prevCamY, prevCamZ;
	private boolean hasPreviousFrame = false;
	private boolean hasPrevCamPos = false;
	private float currentVelocity = 0.0f;

	public void updateUniforms(org.joml.Matrix4f modelView, org.joml.Matrix4f projection, boolean isShadowPass) {
		IrisUniformBuffer buf;
		if (isShadowPass && shadowUniformBuffer != null) {
			buf = shadowUniformBuffer;
		} else if (solidUniformBuffer != null) {
			buf = solidUniformBuffer;
		} else {
			return;
		}

		net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();

		writeMatrixUniforms(buf, modelView, projection, isShadowPass, client);
		writeCameraUniforms(buf, client);
		writeViewportUniforms(buf, client);
		writeTimeUniforms(buf, modelView, client);
		writeWeatherUniforms(buf, client);
		writePlayerStateUniforms(buf, client);
		writeBiomeUniforms(buf, client);
		writeShadowMatrixUniforms(buf, isShadowPass);
		writeMiscUniforms(buf, client);

		if (uniformLogCounter < 1 && !isShadowPass) {
			uniformLogCounter++;
		}

		copyToPassBuffers(buf, isShadowPass);
	}

	private void writeMatrixUniforms(IrisUniformBuffer buf, org.joml.Matrix4f modelView,
			org.joml.Matrix4f projection, boolean isShadowPass, net.minecraft.client.Minecraft client) {
		float[] mvArr = new float[16];
		modelView.get(mvArr);
		org.joml.Matrix4f projection_clean = new org.joml.Matrix4f(projection);
		// VulkanMod may pass infinite/incorrect m00/m11 — rebuild from actual game parameters
		if (!isShadowPass && projection_clean.m23() != 0) {
			rebuildPerspectiveProjection(projection_clean, client);
		}

		float[][] projArrays = buildProjectionArrays(projection_clean, isShadowPass);
		float[] projVkArr = projArrays[0];
		float[] projVkInvArr = projArrays[1];
		float[] projGLArr = projArrays[2];
		float[] projGLInvArr = projArrays[3];
		float[] projGLShadowArr = projArrays[4];
		float[] projGLShadowInvArr = projArrays[5];

		org.joml.Matrix4f mvInv = new org.joml.Matrix4f(modelView).invert();
		float[] mvInvArr = new float[16];
		mvInv.get(mvInvArr);

		org.joml.Matrix3f normalMat = new org.joml.Matrix3f(modelView).invert().transpose();
		float[] normArr = new float[9];
		normalMat.get(normArr);

		writeMatrixFields(buf, mvArr, mvInvArr, projVkArr, projVkInvArr,
			projGLArr, projGLInvArr, projGLShadowArr, projGLShadowInvArr,
			normArr, isShadowPass);

		if (hasPreviousFrame) {
			int off = buf.getFieldOffset("gbufferPreviousModelView");
			if (off >= 0) buf.writeMat4f(off, prevMvArr);
			off = buf.getFieldOffset("gbufferPreviousProjection");
			if (off >= 0) buf.writeMat4f(off, prevProjArr);
		}
		System.arraycopy(mvArr, 0, prevMvArr, 0, 16);
		System.arraycopy(projGLArr, 0, prevProjArr, 0, 16);
		hasPreviousFrame = true;
	}

	/**
	 * Builds all projection matrix variants needed for uniform writing.
	 * Returns [projVk, projVkInv, projGL, projGLInv, projGLShadow, projGLShadowInv].
	 * Shadow arrays may be null when not in shadow pass.
	 */
	private float[][] buildProjectionArrays(org.joml.Matrix4f projection_clean, boolean isShadowPass) {
		float[] projVkArr = new float[16];
		projection_clean.get(projVkArr);
		float[] projVkInvArr = new float[16];
		new org.joml.Matrix4f(projection_clean).invert().get(projVkInvArr);

		// OpenGL-style projection for gbufferProjection (shader pack position reconstruction)
		org.joml.Matrix4f projGL = new org.joml.Matrix4f(projection_clean);
		// VulkanMod's Matrix4fM mixin forces zZeroToOne=true on perspective matrices —
		// convert back to OpenGL [-1,1] depth range. Skip for shadow pass because the
		// shadow ortho matrix is constructed with raw column values, already OpenGL-style.
		if (!isShadowPass) {
			projGL.m22(2.0f * projGL.m22() - projGL.m23());
			projGL.m32(2.0f * projGL.m32() - projGL.m33());
		}
		// Negate m11 for Vulkan screen coordinate convention (negative viewport Y-flip)
		if (!isShadowPass) {
			projGL.m11(-projGL.m11());
		}
		float[] projGLArr = new float[16];
		projGL.get(projGLArr);
		float[] projGLInvArr = new float[16];
		new org.joml.Matrix4f(projGL).invert().get(projGLInvArr);

		// Shadow pass iris_ProjectionMatrix needs m11 negated for VulkanMod's negative viewport
		float[] projGLShadowArr = null;
		float[] projGLShadowInvArr = null;
		if (isShadowPass) {
			org.joml.Matrix4f projGLShadow = new org.joml.Matrix4f(projGL);
			projGLShadow.m11(-projGLShadow.m11());
			projGLShadowArr = new float[16];
			projGLShadow.get(projGLShadowArr);
			projGLShadowInvArr = new float[16];
			new org.joml.Matrix4f(projGLShadow).invert().get(projGLShadowInvArr);
		}

		return new float[][] { projVkArr, projVkInvArr, projGLArr, projGLInvArr,
			projGLShadowArr, projGLShadowInvArr };
	}

	private void writeMatrixFields(IrisUniformBuffer buf, float[] mvArr, float[] mvInvArr,
			float[] projVkArr, float[] projVkInvArr, float[] projGLArr, float[] projGLInvArr,
			float[] projGLShadowArr, float[] projGLShadowInvArr, float[] normArr,
			boolean isShadowPass) {
		for (String name : new String[]{"iris_ModelViewMatrix", "gbufferModelView"}) {
			int off = buf.getFieldOffset(name);
			if (off >= 0) buf.writeMat4f(off, mvArr);
		}
		// iris_ProjectionMatrix: OpenGL-style with m11 negated during shadow pass,
		// Vulkan-style for gbuffer
		{
			int off = buf.getFieldOffset("iris_ProjectionMatrix");
			if (off >= 0) buf.writeMat4f(off, isShadowPass ? projGLShadowArr : projVkArr);
		}
		{
			int off = buf.getFieldOffset("gbufferProjection");
			if (off >= 0) buf.writeMat4f(off, projGLArr);
		}
		for (String name : new String[]{"iris_ModelViewMatrixInverse", "gbufferModelViewInverse"}) {
			int off = buf.getFieldOffset(name);
			if (off >= 0) buf.writeMat4f(off, mvInvArr);
		}
		{
			int off = buf.getFieldOffset("iris_ProjectionMatrixInverse");
			if (off >= 0) buf.writeMat4f(off, isShadowPass ? projGLShadowInvArr : projVkInvArr);
		}
		{
			int off = buf.getFieldOffset("gbufferProjectionInverse");
			if (off >= 0) buf.writeMat4f(off, projGLInvArr);
		}
		writeFloat(buf, "iris_NormalMatrix", normalOffset -> buf.writeMat3f(normalOffset, normArr));
	}

	/**
	 * Rebuilds perspective projection m00/m11/m22/m32 from actual game FOV,
	 * aspect ratio, and render distance.
	 */
	private void rebuildPerspectiveProjection(org.joml.Matrix4f proj, net.minecraft.client.Minecraft client) {
		float far = client.options != null ? client.options.getEffectiveRenderDistance() * 16.0f : 256.0f;
		float near = 0.05f;

		double fovDegrees = 70.0;
		try {
			if (client.gameRenderer != null) {
				fovDegrees = ((net.irisshaders.iris.mixin.GameRendererAccessor) client.gameRenderer)
					.invokeGetFov(client.gameRenderer.getMainCamera(),
						client.getTimer().getGameTimeDeltaPartialTick(true), true);
			}
		} catch (Exception ignored) {}
		if (fovDegrees < 1.0 || !Double.isFinite(fovDegrees)) fovDegrees = 70.0;

		float fovRad = (float)(fovDegrees * Math.PI / 180.0);
		float tanHalfFov = (float) Math.tan(fovRad / 2.0);
		var window = client.getWindow();
		float aspect = (float) window.getWidth() / (float) window.getHeight();
		proj.m00(1.0f / (aspect * tanHalfFov));
		proj.m11(1.0f / tanHalfFov);
		proj.m22(-far / (far - near));
		proj.m32(-far * near / (far - near));
	}

	private void writeCameraUniforms(IrisUniformBuffer buf, net.minecraft.client.Minecraft client) {
		writeFloatField(buf, "near", 0.05f);
		if (client.options != null) {
			writeFloatField(buf, "far", client.options.getEffectiveRenderDistance() * 16.0f);
		}

		if (client.gameRenderer != null && client.gameRenderer.getMainCamera() != null) {
			net.minecraft.world.phys.Vec3 camPos = client.gameRenderer.getMainCamera().getPosition();
			writeVec3Field(buf, "cameraPosition", (float) camPos.x, (float) camPos.y, (float) camPos.z);
			writeFloatField(buf, "eyeAltitude", (float) camPos.y);

			writeVec3iField(buf, "cameraPositionInt",
				(int) Math.floor(camPos.x), (int) Math.floor(camPos.y), (int) Math.floor(camPos.z));
			writeVec3Field(buf, "cameraPositionFract",
				(float)(camPos.x - Math.floor(camPos.x)),
				(float)(camPos.y - Math.floor(camPos.y)),
				(float)(camPos.z - Math.floor(camPos.z)));

			writeVec3Field(buf, "relativeEyePosition", 0.0f, 0.0f, 0.0f);

			if (hasPrevCamPos) {
				writeVec3Field(buf, "previousCameraPosition", (float) prevCamX, (float) prevCamY, (float) prevCamZ);
				writeVec3iField(buf, "previousCameraPositionInt",
					(int) Math.floor(prevCamX), (int) Math.floor(prevCamY), (int) Math.floor(prevCamZ));
				writeVec3Field(buf, "previousCameraPositionFract",
					(float)(prevCamX - Math.floor(prevCamX)),
					(float)(prevCamY - Math.floor(prevCamY)),
					(float)(prevCamZ - Math.floor(prevCamZ)));
				float dx = (float)(camPos.x - prevCamX);
				float dy = (float)(camPos.y - prevCamY);
				float dz = (float)(camPos.z - prevCamZ);
				currentVelocity = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
			}

			prevCamX = camPos.x;
			prevCamY = camPos.y;
			prevCamZ = camPos.z;
			hasPrevCamPos = true;
		}
	}

	private void writeViewportUniforms(IrisUniformBuffer buf, net.minecraft.client.Minecraft client) {
		if (client.getMainRenderTarget() != null) {
			float w = client.getMainRenderTarget().width;
			float h = client.getMainRenderTarget().height;
			writeFloatField(buf, "viewWidth", w);
			writeFloatField(buf, "viewHeight", h);
			writeFloatField(buf, "aspectRatio", h > 0 ? w / h : 1.0f);
		}
	}

	private void writeTimeUniforms(IrisUniformBuffer buf, org.joml.Matrix4f modelView,
			net.minecraft.client.Minecraft client) {
		long nowNanos = System.nanoTime();
		float frameTimeSec = (nowNanos - lastFrameNanos) / 1_000_000_000.0f;
		lastFrameNanos = nowNanos;
		cumulativeTime += frameTimeSec;
		if (cumulativeTime > 3600.0f) cumulativeTime -= 3600.0f;
		frameCount = (frameCount + 1) % 720720;

		writeFloatField(buf, "frameTime", frameTimeSec);
		writeFloatField(buf, "frameTimeCounter", cumulativeTime);
		writeIntField(buf, "frameCounter", frameCount);
		writeFloatField(buf, "frameTimeSmooth", frameTimeSec);

		if (client.level != null) {
			long dayTime = client.level.getDayTime();
			writeIntField(buf, "worldTime", (int)(dayTime % 24000L));
			writeIntField(buf, "worldDay", (int)(dayTime / 24000L));
			writeIntField(buf, "moonPhase", client.level.getMoonPhase());

			float tickDelta = client.getTimer().getGameTimeDeltaPartialTick(true);
			float skyAngle = client.level.getTimeOfDay(tickDelta);
			float sunAngle = skyAngle < 0.75f ? skyAngle + 0.25f : skyAngle - 0.75f;
			writeFloatField(buf, "sunAngle", sunAngle);

			writeCelestialPositions(buf, modelView, skyAngle, sunAngle);

			float timeAngle = (dayTime % 24000L) / 24000.0f;
			writeFloatField(buf, "timeAngle", timeAngle);
			writeFloatField(buf, "timeBrightness", (float) Math.max(Math.sin(timeAngle * Math.PI * 2.0), 0.0));
			writeFloatField(buf, "moonBrightness", (float) Math.max(Math.sin(timeAngle * Math.PI * -2.0), 0.0));
			writeFloatField(buf, "shadowFade", (float) Math.max(0.0, Math.min(1.0,
				1.0 - (Math.abs(Math.abs(sunAngle - 0.5) - 0.25) - 0.23) * 100.0)));
			writeFloatField(buf, "shdFade", (float) Math.max(0.0, Math.min(1.0,
				1.0 - (Math.abs(Math.abs(sunAngle - 0.5) - 0.25) - 0.225) * 40.0)));
		}
	}

	/**
	 * Computes sun, moon, shadowLight, and up positions in view space.
	 * Critical for deferred lighting — composite shaders derive sunVec from sunPosition.
	 * Replicates CelestialUniforms.getCelestialPosition().
	 */
	private void writeCelestialPositions(IrisUniformBuffer buf, org.joml.Matrix4f modelView,
			float skyAngle, float sunAngle) {
		float sunPathRotation = getSunPathRotation();

		org.joml.Matrix4f celestial = new org.joml.Matrix4f(modelView);
		celestial.rotateY((float) Math.toRadians(-90.0));
		celestial.rotateZ((float) Math.toRadians(sunPathRotation));
		celestial.rotateX((float) Math.toRadians(skyAngle * 360.0f));

		org.joml.Vector4f sunPos = new org.joml.Vector4f(0, 100, 0, 0);
		celestial.transform(sunPos);
		writeVec3Field(buf, "sunPosition", sunPos.x(), sunPos.y(), sunPos.z());

		org.joml.Vector4f moonPos = new org.joml.Vector4f(0, -100, 0, 0);
		celestial.transform(moonPos);
		writeVec3Field(buf, "moonPosition", moonPos.x(), moonPos.y(), moonPos.z());

		boolean isDay = sunAngle <= 0.5f;
		org.joml.Vector4f lightPos = isDay ? sunPos : moonPos;
		writeVec3Field(buf, "shadowLightPosition", lightPos.x(), lightPos.y(), lightPos.z());
		writeFloatField(buf, "shadowAngle", isDay ? sunAngle : sunAngle - 0.5f);

		// upPosition: modelView * rotY(-90) * (0, 100, 0, 0) — no sky angle rotation
		org.joml.Matrix4f preCelestial = new org.joml.Matrix4f(modelView);
		preCelestial.rotateY((float) Math.toRadians(-90.0));
		org.joml.Vector4f upPos = new org.joml.Vector4f(0, 100, 0, 0);
		preCelestial.transform(upPos);
		writeVec3Field(buf, "upPosition", upPos.x(), upPos.y(), upPos.z());
	}

	private float getSunPathRotation() {
		try {
			var pm = net.irisshaders.iris.Iris.getPipelineManager();
			if (pm != null) {
				var wp = pm.getPipelineNullable();
				if (wp instanceof net.irisshaders.iris.pipeline.IrisRenderingPipeline irp) {
					return irp.getSunPathRotation();
				}
			}
		} catch (Exception ignored) {}
		return 0.0f;
	}

	private void writeWeatherUniforms(IrisUniformBuffer buf, net.minecraft.client.Minecraft client) {
		if (client.level == null) return;

		float tickDelta = client.getTimer().getGameTimeDeltaPartialTick(true);
		float rainLevel = client.level.getRainLevel(tickDelta);
		writeFloatField(buf, "rainStrength", rainLevel);
		writeFloatField(buf, "wetness", rainLevel);
		writeFloatField(buf, "rainFactor", rainLevel);
		// Smoothed rain variants — in terrain UBO we use the raw value since
		// we can't do temporal smoothing per-field here
		writeFloatField(buf, "rainStrengthS", rainLevel);
		writeFloatField(buf, "rainStrengthShiningStars", rainLevel);
		writeFloatField(buf, "rainStrengthS2", rainLevel);
		writeFloatField(buf, "isPrecipitationRain", rainLevel > 0 ? 1.0f : 0.0f);

		if (client.player != null) {
			net.minecraft.world.phys.Vec3 sky = client.level.getSkyColor(
				client.player.position(), tickDelta);
			writeVec3Field(buf, "skyColor", (float) sky.x, (float) sky.y, (float) sky.z);
		}

		writeFloatField(buf, "cloudHeight", client.level.effects().getCloudHeight());
	}

	private void writePlayerStateUniforms(IrisUniformBuffer buf, net.minecraft.client.Minecraft client) {
		// isEyeInWater: 0=air, 1=water, 2=lava, 3=powder_snow
		int eyeInWater = 0;
		if (client.gameRenderer != null && client.gameRenderer.getMainCamera() != null) {
			net.minecraft.world.level.material.FogType submersion = client.gameRenderer.getMainCamera().getFluidInCamera();
			if (submersion == net.minecraft.world.level.material.FogType.WATER) eyeInWater = 1;
			else if (submersion == net.minecraft.world.level.material.FogType.LAVA) eyeInWater = 2;
			else if (submersion == net.minecraft.world.level.material.FogType.POWDER_SNOW) eyeInWater = 3;
		}
		writeIntField(buf, "isEyeInWater", eyeInWater);

		writeEffectUniforms(buf, client);

		writeIntField(buf, "blockEntityId", -1);
		writeIntField(buf, "entityId", -1);
		writeIntField(buf, "currentRenderedItemId", -1);
		writeIntField(buf, "heldItemId", 0);
		writeIntField(buf, "heldItemId2", 0);
		writeIntField(buf, "heldBlockLightValue", 0);
		writeIntField(buf, "heldBlockLightValue2", 0);

		writeEyeBrightnessUniforms(buf, client);
	}

	private void writeEffectUniforms(IrisUniformBuffer buf, net.minecraft.client.Minecraft client) {
		float blindness = 0.0f;
		float darknessFactor = 0.0f;
		float nightVision = 0.0f;
		float playerMood = 0.0f;
		if (client.getCameraEntity() instanceof net.minecraft.world.entity.LivingEntity livingEntity) {
			var blindnessEffect = livingEntity.getEffect(net.minecraft.world.effect.MobEffects.BLINDNESS);
			if (blindnessEffect != null) {
				blindness = blindnessEffect.isInfiniteDuration() ? 1.0f
					: Math.min(1.0f, blindnessEffect.getDuration() / 20.0f);
			}
			var darknessEffect = livingEntity.getEffect(net.minecraft.world.effect.MobEffects.DARKNESS);
			if (darknessEffect != null) {
				float td = client.getTimer().getGameTimeDeltaPartialTick(true);
				darknessFactor = darknessEffect.getBlendFactor(livingEntity, td);
			}
			var nvEffect = livingEntity.getEffect(net.minecraft.world.effect.MobEffects.NIGHT_VISION);
			if (nvEffect != null) nightVision = 1.0f;
		}
		if (client.player != null) {
			playerMood = Math.max(0.0f, Math.min(1.0f, client.player.getCurrentMood()));
		}
		writeFloatField(buf, "blindness", blindness);
		float blindFactor = (float) Math.max(0.0, Math.min(1.0, blindness * 2.0 - 1.0));
		writeFloatField(buf, "blindFactor", blindFactor * blindFactor);
		writeFloatField(buf, "darknessFactor", darknessFactor);
		writeFloatField(buf, "darknessLightFactor", 0.0f);
		writeFloatField(buf, "maxBlindnessDarkness", Math.max(blindness, darknessFactor));
		writeFloatField(buf, "nightVision", nightVision);
		writeFloatField(buf, "screenBrightness", client.options != null ? client.options.gamma().get().floatValue() : 1.0f);
		writeFloatField(buf, "playerMood", playerMood);
	}

	/** Eye brightness uses eye position, not foot position, matching Iris's CommonUniforms. */
	private void writeEyeBrightnessUniforms(IrisUniformBuffer buf, net.minecraft.client.Minecraft client) {
		if (client.player == null || client.level == null) return;
		net.minecraft.world.phys.Vec3 feet = client.player.position();
		net.minecraft.core.BlockPos eyePos = net.minecraft.core.BlockPos.containing(
			feet.x, client.player.getEyeY(), feet.z);
		int blockLight = client.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, eyePos);
		int skyLight = client.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, eyePos);
		writeVec2iField(buf, "eyeBrightness", blockLight * 16, skyLight * 16);
		writeVec2iField(buf, "eyeBrightnessSmooth", blockLight * 16, skyLight * 16);
		// skyLight 0-15 → lightmap coordinate 0-240 → normalized 0.0-1.0
		writeFloatField(buf, "eyeBrightnessM", skyLight * 16.0f / 240.0f);
		writeFloatField(buf, "eyeBrightnessM2", skyLight * 16.0f / 240.0f);
	}

	private void writeBiomeUniforms(IrisUniformBuffer buf, net.minecraft.client.Minecraft client) {
		float isDry = 1.0f, isRainy = 0.0f, isSnowy = 0.0f;
		float isEyeInCave = 0.0f;
		if (client.level != null && client.getCameraEntity() != null) {
			net.minecraft.core.BlockPos camBlockPos = client.getCameraEntity().blockPosition();
			net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome =
				client.level.getBiome(camBlockPos);
			net.minecraft.world.level.biome.Biome.Precipitation precip =
				biome.value().getPrecipitationAt(camBlockPos);
			isDry = (precip == net.minecraft.world.level.biome.Biome.Precipitation.NONE) ? 1.0f : 0.0f;
			isRainy = (precip == net.minecraft.world.level.biome.Biome.Precipitation.RAIN) ? 1.0f : 0.0f;
			isSnowy = (precip == net.minecraft.world.level.biome.Biome.Precipitation.SNOW) ? 1.0f : 0.0f;
			// Low sky light at camera position = underground
			if (client.getCameraEntity().getEyeY() < 5.0) {
				int skyLight = client.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, camBlockPos);
				isEyeInCave = 1.0f - (skyLight * 16.0f) / 240.0f;
			}
		}
		writeFloatField(buf, "isDry", isDry);
		writeFloatField(buf, "isRainy", isRainy);
		writeFloatField(buf, "isSnowy", isSnowy);
		writeFloatField(buf, "inDry", isDry);
		writeFloatField(buf, "inRainy", isRainy);
		writeFloatField(buf, "inSnowy", isSnowy);
		writeFloatField(buf, "isEyeInCave", isEyeInCave);

		writeFloatField(buf, "inBasaltDeltas", 0.0f);
		writeFloatField(buf, "inCrimsonForest", 0.0f);
		writeFloatField(buf, "inNetherWastes", 0.0f);
		writeFloatField(buf, "inSoulValley", 0.0f);
		writeFloatField(buf, "inWarpedForest", 0.0f);
		writeFloatField(buf, "inPaleGarden", 0.0f);
	}

	private void writeShadowMatrixUniforms(IrisUniformBuffer buf, boolean isShadowPass) {
		org.joml.Matrix4f shadowMV = net.irisshaders.iris.shadows.ShadowRenderer.MODELVIEW;
		org.joml.Matrix4f shadowProj = net.irisshaders.iris.shadows.ShadowRenderer.PROJECTION;
		if (shadowMV != null) {
			float[] smvArr = new float[16];
			shadowMV.get(smvArr);
			int off = buf.getFieldOffset("shadowModelView");
			if (off >= 0) buf.writeMat4f(off, smvArr);
			org.joml.Matrix4f smvInv = new org.joml.Matrix4f(shadowMV).invert();
			smvInv.get(smvArr);
			off = buf.getFieldOffset("shadowModelViewInverse");
			if (off >= 0) buf.writeMat4f(off, smvArr);
		}
		if (shadowProj != null) {
			org.joml.Matrix4f sp = new org.joml.Matrix4f(shadowProj);
			// ShadowMatrices.createOrthoMatrix() uses raw column values (NOT .ortho()),
			// so VulkanMod's Matrix4fM mixin does NOT affect it — it's already OpenGL-style.
			// Do NOT apply vulkanToOpenGLDepthRange() — that would double-convert.

			if (isShadowPass) {
				// Negate m11 to compensate for VulkanMod's negative viewport Y-flip during shadow rendering
				sp.m11(-sp.m11());
			}
			// Gbuffer pass: don't negate m11 — the terrain fragment shader samples the shadow
			// texture directly (no rasterizer Y-flip), so shadow UV Y must match the stored layout.

			float[] spArr = new float[16];
			sp.get(spArr);
			int off = buf.getFieldOffset("shadowProjection");
			if (off >= 0) buf.writeMat4f(off, spArr);
			org.joml.Matrix4f spInv = new org.joml.Matrix4f(sp).invert();
			spInv.get(spArr);
			off = buf.getFieldOffset("shadowProjectionInverse");
			if (off >= 0) buf.writeMat4f(off, spArr);
		}
	}

	private void writeMiscUniforms(IrisUniformBuffer buf, net.minecraft.client.Minecraft client) {
		writeLightmapMatrix(buf);
		writeFogUniforms(buf);

		writeVec2iField(buf, "atlasSize", 1024, 1024); // TODO: get actual atlas dimensions
		writeIntField(buf, "renderStage", 1); // TERRAIN

		writeFloatField(buf, "framemod2", (float)(frameCount % 2));
		writeFloatField(buf, "framemod4", (float)(frameCount % 4));
		writeFloatField(buf, "framemod8", (float)(frameCount % 8));

		writeVec4Field(buf, "entityColor", 0.0f, 0.0f, 0.0f, 0.0f);
		writeVec4Field(buf, "lightningBoltPosition", 0.0f, -1000.0f, 0.0f, 0.0f);

		writeFloatField(buf, "velocity", currentVelocity);
		// starter gates TAA temporal accumulation — keep at 0.0 until reprojection
		// is verified. Setting to 1.0 prematurely causes VL ray and diagonal line artifacts.
		writeFloatField(buf, "starter", 0.0f);
	}

	/** Lightmap texture matrix: converts raw light coords (0-240) to UV space (0-1). */
	private void writeLightmapMatrix(IrisUniformBuffer buf) {
		float lmScale = 1.0f / 256.0f;
		float lmOffset = 1.0f / 32.0f;
		float[] lightmapMatrix = new float[]{
			lmScale, 0, 0, 0,
			0, lmScale, 0, 0,
			0, 0, 1, 0,
			lmOffset, lmOffset, 0, 1
		};
		int lmOff = buf.getFieldOffset("iris_LightmapTextureMatrix");
		if (lmOff >= 0) buf.writeMat4f(lmOff, lightmapMatrix);
	}

	/**
	 * Uses Iris's captured fog color from MixinFogRenderer — RenderSystem.getShaderFogColor()
	 * returns stale sky/horizon colors that cause an orange color cast.
	 */
	private void writeFogUniforms(IrisUniformBuffer buf) {
		Vector3d capturedFog = CapturedRenderingState.INSTANCE.getFogColor();
		float fogR = (float) capturedFog.x;
		float fogG = (float) capturedFog.y;
		float fogB = (float) capturedFog.z;
		writeVec4Field(buf, "iris_FogColor", fogR, fogG, fogB, 1.0f);
		writeVec3Field(buf, "fogColor", fogR, fogG, fogB);
		writeFloatField(buf, "iris_FogStart", RenderSystem.getShaderFogStart());
		writeFloatField(buf, "iris_FogEnd", RenderSystem.getShaderFogEnd());
		writeFloatField(buf, "iris_FogDensity", CapturedRenderingState.INSTANCE.getFogDensity());
		writeIntField(buf, "heavyFog", 0);
	}

	/**
	 * Copies solid UBO data to cutout and translucent buffers (gbuffer pass only).
	 * Their UBO layouts share the same field offsets for common uniforms,
	 * with pass-specific fields (iris_currentAlphaTest) appended at the end.
	 */
	private void copyToPassBuffers(IrisUniformBuffer buf, boolean isShadowPass) {
		if (isShadowPass) return;
		int solidSize = buf.getUsedSize();
		if (cutoutUniformBuffer != null) {
			int copySize = Math.min(solidSize, cutoutUniformBuffer.getUsedSize());
			org.lwjgl.system.MemoryUtil.memCopy(buf.getPointer(), cutoutUniformBuffer.getPointer(), copySize);
			writeFloatField(cutoutUniformBuffer, "iris_currentAlphaTest", 0.1f);
		}
		if (translucentUniformBuffer != null) {
			int copySize = Math.min(solidSize, translucentUniformBuffer.getUsedSize());
			org.lwjgl.system.MemoryUtil.memCopy(buf.getPointer(), translucentUniformBuffer.getPointer(), copySize);
			writeFloatField(translucentUniformBuffer, "iris_currentAlphaTest", 0.0f);
		}
	}

	// Helper methods to write uniforms by name (no-op if field doesn't exist)
	private void writeFloatField(IrisUniformBuffer buf, String name, float value) {
		int off = buf.getFieldOffset(name);
		if (off >= 0) buf.writeFloat(off, value);
	}

	private void writeIntField(IrisUniformBuffer buf, String name, int value) {
		int off = buf.getFieldOffset(name);
		if (off >= 0) buf.writeInt(off, value);
	}

	private void writeVec3Field(IrisUniformBuffer buf, String name, float x, float y, float z) {
		int off = buf.getFieldOffset(name);
		if (off >= 0) buf.writeVec3f(off, x, y, z);
	}

	private void writeVec4Field(IrisUniformBuffer buf, String name, float x, float y, float z, float w) {
		int off = buf.getFieldOffset(name);
		if (off >= 0) buf.writeVec4f(off, x, y, z, w);
	}

	private void writeVec3iField(IrisUniformBuffer buf, String name, int x, int y, int z) {
		int off = buf.getFieldOffset(name);
		if (off >= 0) {
			buf.writeInt(off, x);
			buf.writeInt(off + 4, y);
			buf.writeInt(off + 8, z);
		}
	}

	private void writeVec2iField(IrisUniformBuffer buf, String name, int x, int y) {
		int off = buf.getFieldOffset(name);
		if (off >= 0) {
			buf.writeInt(off, x);
			buf.writeInt(off + 4, y);
		}
	}

	@FunctionalInterface
	private interface OffsetConsumer { void accept(int offset); }

	private void writeFloat(IrisUniformBuffer buf, String name, OffsetConsumer writer) {
		int off = buf.getFieldOffset(name);
		if (off >= 0) writer.accept(off);
	}

	/**
	 * Compiles a textured terrain pipeline with block atlas sampling and vertex colors.
	 * Uses VulkanMod's standard MVP UBO mechanism + Sampler0 (block atlas).
	 * Position decoding matches VulkanMod's terrain.vsh exactly.
	 */
	private GraphicsPipeline compileMinimalTestPipeline(String name) {
		String minimalVert = """
			#version 460
			layout(location=0) in ivec4 a_PosId;
			layout(location=1) in vec4 a_Color;
			layout(location=2) in uvec2 a_TexCoord;
			layout(location=3) in ivec2 a_LightCoord;

			layout(push_constant) uniform PushConstants {
			    vec3 ChunkOffset;
			};

			layout(std140, binding=0) uniform MVPBlock {
			    mat4 MVP;
			};

			layout(location=0) out vec4 vertexColor;
			layout(location=1) out vec2 texCoord0;

			void main() {
			    const vec3 POSITION_INV = vec3(1.0 / 2048.0);
			    vec3 baseOffset = vec3(bitfieldExtract(ivec3(gl_InstanceIndex) >> ivec3(0, 16, 8), 0, 8));
			    vec4 pos = vec4(fma(vec3(a_PosId.xyz), POSITION_INV, ChunkOffset + baseOffset), 1.0);
			    gl_Position = MVP * pos;

			    vertexColor = a_Color;
			    texCoord0 = vec2(a_TexCoord) * (1.0 / 32768.0);
			}
			""";

		// Fallback fragment: solid GREEN output for pipeline testing
		String minimalFrag = """
			#version 460
			layout(binding=1) uniform sampler2D Sampler0;

			layout(location=0) in vec4 vertexColor;
			layout(location=1) in vec2 texCoord0;

			layout(location=0) out vec4 outColor;
			layout(location=1) out vec4 outNormal;
			layout(location=2) out vec4 outSpecular;

			void main() {
			    outColor = vec4(0.0, 1.0, 0.0, 1.0);
			    outNormal = vec4(0.5, 1.0, 0.9, 0.02);
			    outSpecular = vec4(0.0, 0.0, 0.0, 0.0);
			}
			""";

		Iris.logger.info("[IrisTerrainPipelineCompiler] Compiling TEXTURED TERRAIN SPIR-V for {}", name);
		ByteBuffer vertSpirv = IrisSPIRVCompiler.compilePreprocessed(name + "_tex.vsh", minimalVert, ShaderType.VERTEX);
		ByteBuffer fragSpirv = IrisSPIRVCompiler.compilePreprocessed(name + "_tex.fsh", minimalFrag, ShaderType.FRAGMENT);

		SPIRVUtils.SPIRV vertSPIRV = new SPIRVUtils.SPIRV(0, vertSpirv);
		SPIRVUtils.SPIRV fragSPIRV = new SPIRVUtils.SPIRV(0, fragSpirv);

		List<UBO> ubos = new ArrayList<>();
		List<ImageDescriptor> imageDescriptors = new ArrayList<>();

		net.vulkanmod.vulkan.shader.layout.Uniform.Info mvpInfo =
			net.vulkanmod.vulkan.shader.layout.Uniform.createUniformInfo("matrix4x4", "MVP", 16);
		UBO mvpUBO = new UBO(0, VK_SHADER_STAGE_ALL_GRAPHICS, 64, java.util.List.of(mvpInfo));
		ubos.add(mvpUBO);

		imageDescriptors.add(new ImageDescriptor(1, "sampler2D", "Sampler0", 0));

		Pipeline.Builder builder = new Pipeline.Builder(CustomVertexFormat.COMPRESSED_TERRAIN, name);
		builder.setUniforms(ubos, imageDescriptors);
		builder.setSPIRVs(vertSPIRV, fragSPIRV);

		AlignedStruct.Builder pcBuilder = new AlignedStruct.Builder();
		pcBuilder.addUniformInfo("float", "ChunkOffset", 3);
		PushConstants pushConstants = pcBuilder.buildPushConstant();
		builder.setPushConstants(pushConstants);

		GraphicsPipeline pipeline = builder.createGraphicsPipeline();
		Iris.logger.info("[IrisTerrainPipelineCompiler] Built TEXTURED TERRAIN pipeline {} (MVP UBO + Sampler0)", name);
		return pipeline;
	}

	public GraphicsPipeline getSolidPipeline() { return solidPipeline; }
	public GraphicsPipeline getCutoutPipeline() { return cutoutPipeline; }
	public GraphicsPipeline getTranslucentPipeline() { return translucentPipeline; }
	public GraphicsPipeline getShadowSolidPipeline() { return shadowSolidPipeline; }
	public GraphicsPipeline getShadowCutoutPipeline() { return shadowCutoutPipeline; }

	public void destroy() {
		// Avoid double-free when pipelines share the same instance
		Set<GraphicsPipeline> destroyed = new HashSet<>();
		if (solidPipeline != null && destroyed.add(solidPipeline)) {
			solidPipeline.cleanUp();
		}
		if (cutoutPipeline != null && destroyed.add(cutoutPipeline)) {
			cutoutPipeline.cleanUp();
		}
		if (translucentPipeline != null && destroyed.add(translucentPipeline)) {
			translucentPipeline.cleanUp();
		}
		if (shadowSolidPipeline != null && destroyed.add(shadowSolidPipeline)) {
			shadowSolidPipeline.cleanUp();
		}
		if (shadowCutoutPipeline != null && destroyed.add(shadowCutoutPipeline)) {
			shadowCutoutPipeline.cleanUp();
		}
		solidPipeline = null;
		cutoutPipeline = null;
		translucentPipeline = null;
		shadowSolidPipeline = null;
		shadowCutoutPipeline = null;

		if (solidUniformBuffer != null) {
			solidUniformBuffer.free();
			solidUniformBuffer = null;
		}
		if (cutoutUniformBuffer != null) {
			cutoutUniformBuffer.free();
			cutoutUniformBuffer = null;
		}
		if (translucentUniformBuffer != null) {
			translucentUniformBuffer.free();
			translucentUniformBuffer = null;
		}
		if (shadowUniformBuffer != null) {
			shadowUniformBuffer.free();
			shadowUniformBuffer = null;
		}
	}
}
