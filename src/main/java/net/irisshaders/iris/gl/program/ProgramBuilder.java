package net.irisshaders.iris.gl.program;

import com.google.common.collect.ImmutableSet;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.image.ImageHolder;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.gl.shader.GlShader;
import net.irisshaders.iris.gl.shader.ProgramCreator;
import net.irisshaders.iris.gl.shader.ShaderCompileException;
import net.irisshaders.iris.gl.shader.ShaderType;
import net.irisshaders.iris.gl.state.ValueUpdateNotifier;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.gl.uniform.IrisUniformBuffer;
import net.irisshaders.iris.pipeline.programs.ExtendedShader;
import net.irisshaders.iris.vulkan.shader.IrisSPIRVCompiler;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.ManualUBO;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.IntSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProgramBuilder extends ProgramUniforms.Builder implements SamplerHolder, ImageHolder {
	private static final Pattern SAMPLER_PATTERN = Pattern.compile(
		"^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+((?:sampler|isampler|usampler)\\w+)\\s+(\\w+)");

	private final int program;
	private final ProgramSamplers.Builder samplers;
	private final ProgramImages.Builder images;

	// Vulkan pipeline data (populated in begin(), used in build())
	private String name;
	private ByteBuffer vertSpirv;
	private ByteBuffer fragSpirv;
	private String vshVulkan;
	private String fshVulkan;
	private IrisUniformBuffer uniformBuffer;
	private List<IrisSPIRVCompiler.UniformField> sharedUniforms;

	// DIAGNOSTIC: When true, injects GLSL code into deferred1 fragment shader that
	// overrides the output with aurora reconstruction diagnostics.
	private static final boolean DIAG_GPU_MATRIX_TEST = false;

	// DIAGNOSTIC: When true, overrides deferred1 output to visualize cloud/noise info.
	// Left half: raw noisetex sampled at texCoord (R,G,B channels)
	// Right half: cloud opacity (R) + nPlayerPos.y (G) + skyFade (B)
	private static final boolean DIAG_CLOUD_DEBUG = false;

	// DIAGNOSTIC: When true, overrides deferred1 output to visualize terrain viewPos.
	// Left third:  lViewPos heat map (blue=near, red=far, scaled by renderDistance)
	// Middle third: playerPos.y heat map (red=below y63, green=above y63)
	// Right third:  fog amount visualization (R=skyFade, G=lViewPos/256, B=z0)
	private static final boolean DIAG_FOG_DEBUG = false;

	// DIAGNOSTIC: When true, disables volumetric light in composite1 to isolate
	// whether the camera-following overlay is from VL or other effects (fog, bloom).
	private static final boolean DIAG_DISABLE_VL = false;

	private ProgramBuilder(String name, int program, ImmutableSet<Integer> reservedTextureUnits) {
		super(name, program);

		this.program = program;
		this.name = name;
		this.samplers = ProgramSamplers.builder(program, reservedTextureUnits);
		this.images = ProgramImages.builder(program);
	}

	public static ProgramBuilder begin(String name, @Nullable String vertexSource, @Nullable String geometrySource,
									   @Nullable String fragmentSource, ImmutableSet<Integer> reservedTextureUnits) {
		RenderSystem.assertOnRenderThread();

		int programId;
		ByteBuffer vertSpirv = null;
		ByteBuffer fragSpirv = null;
		String vshVulkan = null;
		String fshVulkan = null;
		IrisUniformBuffer uniformBuffer = null;
		List<IrisSPIRVCompiler.UniformField> sharedUniforms = null;

		try {
			if (vertexSource != null && fragmentSource != null) {
				// Allocate a unique program ID for Vulkan uniform/sampler tracking
				programId = IrisRenderSystem.allocateIrisProgramId();

				// Collect and merge uniforms from both shader stages
				@SuppressWarnings("unchecked")
				List<IrisSPIRVCompiler.UniformField> merged = IrisSPIRVCompiler.mergeUniforms(
					IrisSPIRVCompiler.collectLooseUniforms(vertexSource),
					IrisSPIRVCompiler.collectLooseUniforms(fragmentSource)
				);
				// Inject standard Iris uniforms that may not be declared in shader source.
				// In OpenGL Iris, these are injected at runtime via glGetUniformLocation.
				// In Vulkan, they must be in the UBO text for fromVulkanGLSL() to find them.
				int collectedCount = merged.size();
				List<String> injected = IrisSPIRVCompiler.ensureStandardIrisUniforms(merged);
				if (!injected.isEmpty()) {
					Iris.logger.info("[ProgramBuilder] '{}': collected {} uniforms from source, injected {} standard: {}",
						name, collectedCount, injected.size(), String.join(", ", injected));
				}

				sharedUniforms = merged;

				// Preprocess both shaders for Vulkan with shared UBO layout
				vshVulkan = IrisSPIRVCompiler.prepareForVulkan(vertexSource, merged);
				fshVulkan = IrisSPIRVCompiler.prepareForVulkan(fragmentSource, merged);

				// Fix vertex input locations to match POSITION_TEX format:
				// location 0 = Position (vec3), location 1 = UV0 (vec2)
				// The LayoutTransformer does NOT process vertex shader inputs (only
				// cross-stage out→in), so shaderc's auto-map assigns by declaration
				// order which may not match the vertex buffer layout.
				vshVulkan = fixCompositeVertexInputLocations(vshVulkan);

				// Parse IrisUniforms block to create uniform buffer with std140 layout
				uniformBuffer = IrisUniformBuffer.fromVulkanGLSL(fshVulkan);
				IrisRenderSystem.registerUniformBuffer(programId, uniformBuffer);

				// Register sampler names so getUniformLocation returns non-(-1) for them
				Set<String> samplerNames = new HashSet<>();
				collectSamplerNamesFromGLSL(vshVulkan, samplerNames);
				collectSamplerNamesFromGLSL(fshVulkan, samplerNames);
				IrisRenderSystem.registerSamplerNames(programId, samplerNames);

				// Fix vertex-fragment varying type mismatches before SPIR-V compilation
				fshVulkan = ExtendedShader.fixVaryingTypeMismatches(vshVulkan, fshVulkan);

				// Dump composite shader GLSL to iris-debug/ for inspection
				dumpCompositeShader(name + ".vsh", vshVulkan);
				dumpCompositeShader(name + ".fsh", fshVulkan);

				// Compile preprocessed GLSL to SPIR-V
				vertSpirv = IrisSPIRVCompiler.compilePreprocessed(name + ".vsh", vshVulkan, ShaderType.VERTEX);
				fragSpirv = IrisSPIRVCompiler.compilePreprocessed(name + ".fsh", fshVulkan, ShaderType.FRAGMENT);

				Iris.logger.info("[ProgramBuilder] '{}' UBO layout: {} bytes, {} fields, {} samplers | sunPosition={} gbufferProjection={}",
					name, uniformBuffer.getUsedSize(), uniformBuffer.getFields().size(), samplerNames.size(),
					uniformBuffer.getFieldOffset("sunPosition"), uniformBuffer.getFieldOffset("gbufferProjection"));
			} else {
				// Fallback: use ProgramCreator for incomplete shader sets
				GlShader vertex = buildShader(ShaderType.VERTEX, name + ".vsh", vertexSource);
				GlShader fragment = buildShader(ShaderType.FRAGMENT, name + ".fsh", fragmentSource);
				programId = ProgramCreator.create(name, vertex, fragment);
				vertex.destroy();
				fragment.destroy();
			}
		} catch (Exception e) {
			Iris.logger.error("Vulkan pipeline setup failed for composite program {}, using fallback", name, e);
			// Fallback: allocate a basic ID so the builder still works
			programId = IrisRenderSystem.allocateIrisProgramId();
			vertSpirv = null;
			fragSpirv = null;
		}

		ProgramBuilder builder = new ProgramBuilder(name, programId, reservedTextureUnits);
		builder.vertSpirv = vertSpirv;
		builder.fragSpirv = fragSpirv;
		builder.vshVulkan = vshVulkan;
		builder.fshVulkan = fshVulkan;
		builder.uniformBuffer = uniformBuffer;
		builder.sharedUniforms = sharedUniforms;
		return builder;
	}

	public static ProgramBuilder beginCompute(String name, @Nullable String source, ImmutableSet<Integer> reservedTextureUnits) {
		RenderSystem.assertOnRenderThread();

		if (!IrisRenderSystem.supportsCompute()) {
			throw new IllegalStateException("This PC does not support compute shaders, but it's attempting to be used???");
		}

		GlShader compute = buildShader(ShaderType.COMPUTE, name + ".csh", source);

		int programId = ProgramCreator.create(name, compute);

		compute.destroy();

		return new ProgramBuilder(name, programId, reservedTextureUnits);
	}

	private static GlShader buildShader(ShaderType shaderType, String name, @Nullable String source) {
		try {
			return new GlShader(shaderType, name, source);
		} catch (ShaderCompileException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new RuntimeException("Failed to compile " + shaderType + " shader for program " + name, e);
		}
	}

	private static void collectSamplerNamesFromGLSL(String source, Set<String> names) {
		for (String line : source.split("\n")) {
			Matcher m = SAMPLER_PATTERN.matcher(line.trim());
			if (m.find()) {
				names.add(m.group(2));
			}
		}
	}

	public void bindAttributeLocation(int index, String name) {
		IrisRenderSystem.bindAttributeLocation(program, index, name);
	}

	public Program build() {
		GraphicsPipeline pipeline = null;
		ManualUBO manualUBO = null;

		if (vertSpirv != null && fragSpirv != null) {
			try {
				pipeline = createVulkanPipeline();
				if (pipeline != null && uniformBuffer != null) {
					// ManualUBO is created inside createVulkanPipeline - get reference from field
					manualUBO = this.builtManualUBO;
				}
			} catch (Exception e) {
				Iris.logger.error("Failed to create Vulkan pipeline for composite program {}", name, e);
			}
		}

		return new Program(program, name, super.buildUniforms(), this.samplers.build(), this.images.build(),
			pipeline, uniformBuffer, manualUBO);
	}

	// Stored during createVulkanPipeline for retrieval
	private ManualUBO builtManualUBO;

	/**
	 * Creates a VulkanMod GraphicsPipeline for this composite program.
	 * Uses POSITION_TEX vertex format (full-screen quad).
	 */
	private GraphicsPipeline createVulkanPipeline() {
		// Collect sampler names from preprocessed sources
		List<String> allSamplers = new ArrayList<>();
		collectSamplerNamesToList(vshVulkan, allSamplers);
		collectSamplerNamesToList(fshVulkan, allSamplers);
		List<String> uniqueSamplers = new ArrayList<>(new LinkedHashSet<>(allSamplers));

		// Sampler binding map: binding 1, 2, 3... (UBO at binding 0)
		Map<String, Integer> samplerBindings = new LinkedHashMap<>();
		for (int i = 0; i < uniqueSamplers.size(); i++) {
			samplerBindings.put(uniqueSamplers.get(i), i + 1);
		}

		// Add explicit bindings to GLSL
		String vshFinal = addExplicitBindings(vshVulkan, samplerBindings);
		String fshFinal = addExplicitBindings(fshVulkan, samplerBindings);

		// Fix vertex-fragment varying type mismatches
		fshFinal = ExtendedShader.fixVaryingTypeMismatches(vshFinal, fshFinal);

		// Fix viewPos reconstruction: bypass broken gbufferProjectionInverse and compute
		// eye-space direction directly from gbufferProjection (forward matrix).
		// The projection inverse on the GPU gives wrong results (root cause TBD), but
		// the forward projection's m00 and m11 are correct. This computes the same
		// result as projInverse * ndc, but using 1/m00 and -1/m11 directly.
		fshFinal = fixViewPosReconstruction(fshFinal, name);
		fshFinal = fixCompositeProjectionInverse(fshFinal, name);

		// Re-compile with bindings
		ByteBuffer vSpirv = IrisSPIRVCompiler.compilePreprocessed(name + ".vsh", vshFinal, ShaderType.VERTEX);
		ByteBuffer fSpirv = IrisSPIRVCompiler.compilePreprocessed(name + ".fsh", fshFinal, ShaderType.FRAGMENT);

		SPIRVUtils.SPIRV vertSPIRV = new SPIRVUtils.SPIRV(0, vSpirv);
		SPIRVUtils.SPIRV fragSPIRV = new SPIRVUtils.SPIRV(0, fSpirv);

		// Create ManualUBO
		List<UBO> ubos = new ArrayList<>();
		List<ImageDescriptor> imageDescriptors = new ArrayList<>();

		int vkShaderStageAllGraphics = 0x0000001F;
		int uboSizeInBytes = (uniformBuffer != null) ? Math.max(uniformBuffer.getUsedSize(), 16) : 16;
		int uboSizeInWords = (uboSizeInBytes + 3) / 4;
		this.builtManualUBO = new ManualUBO(0, vkShaderStageAllGraphics, uboSizeInWords);
		if (uniformBuffer != null) {
			this.builtManualUBO.setSrc(uniformBuffer.getPointer(), uniformBuffer.getUsedSize());
		}
		ubos.add(this.builtManualUBO);

		// Log ManualUBO descriptor details — critical for diagnosing GPU UBO reads
		Iris.logger.info("[DIAG_UBO] prog='{}' ManualUBO: binding={} descriptorSize={} srcPtr=0x{} srcSize={} uboSizeInBytes={} uboSizeInWords={}" +
				" mvInvOff={} projInvOff={} viewWidthOff={}",
			name, builtManualUBO.getBinding(), builtManualUBO.getSize(),
			Long.toHexString(builtManualUBO.getSrcPtr()), builtManualUBO.getSrcSize(),
			uboSizeInBytes, uboSizeInWords,
			uniformBuffer != null ? uniformBuffer.getFieldOffset("gbufferModelViewInverse") : -1,
			uniformBuffer != null ? uniformBuffer.getFieldOffset("gbufferProjectionInverse") : -1,
			uniformBuffer != null ? uniformBuffer.getFieldOffset("viewWidth") : -1);

		// Sampler ImageDescriptors at bindings 1, 2, 3...
		// Use the ProgramSamplers mapping for texture unit indices
		Map<String, Integer> samplerUnitMap = this.samplers.getSamplerNameToUnit();

		StringBuilder samplerDiag = new StringBuilder();
		for (int i = 0; i < uniqueSamplers.size(); i++) {
			String samplerName = uniqueSamplers.get(i);
			boolean fromMap = samplerUnitMap.containsKey(samplerName);
			int textureIdx = fromMap ? samplerUnitMap.get(samplerName) : mapSamplerToTextureIndex(samplerName);
			imageDescriptors.add(new ImageDescriptor(i + 1, "sampler2D", samplerName, textureIdx));
			samplerDiag.append(String.format("\n  [binding=%d] %s -> texIdx=%d (%s)",
				i + 1, samplerName, textureIdx, fromMap ? "samplerUnitMap" : "FALLBACK"));
		}

		// Build with POSITION_TEX format (composite full-screen quad)
		Pipeline.Builder builder = new Pipeline.Builder(DefaultVertexFormat.POSITION_TEX, name);
		builder.setUniforms(ubos, imageDescriptors);
		builder.setSPIRVs(vertSPIRV, fragSPIRV);
		GraphicsPipeline pipeline = builder.createGraphicsPipeline();

		Iris.logger.info("Created Vulkan pipeline for composite '{}': {} samplers, {} UBO bytes, samplerUnitMap={}{}",
			name, uniqueSamplers.size(), uboSizeInBytes, samplerUnitMap, samplerDiag);

		// DIAGNOSTIC: Parse SPIR-V to verify UBO layout offsets match our std140 computation
		if (uniformBuffer != null) {
			try {
				IrisSPIRVCompiler.SPIRVLayout spirvLayout = IrisSPIRVCompiler.parseSPIRVLayout(fSpirv, name + ".fsh");
				if (spirvLayout != null) {
					// Compare by MEMBER INDEX since names are stripped by optimization.
					// Map Java field offsets by declaration order (matching GLSL UBO order).
					var javaFields = uniformBuffer.getFields().values().stream()
						.sorted((a, b) -> Integer.compare(a.byteOffset, b.byteOffset))
						.toList();
					var spirvMembers = spirvLayout.members.stream()
						.sorted((a, b) -> Integer.compare(a.memberIndex(), b.memberIndex()))
						.toList();

					Iris.logger.info("[SPIRV_VERIFY] '{}' members: java={} spirv={}", name, javaFields.size(), spirvMembers.size());

					// Compare first N members by offset
					int compareCount = Math.min(javaFields.size(), spirvMembers.size());
					StringBuilder mismatches = new StringBuilder();
					for (int idx = 0; idx < compareCount; idx++) {
						int javaOff = javaFields.get(idx).byteOffset;
						int spirvOff = spirvMembers.get(idx).byteOffset();
						String javaName = javaFields.get(idx).name;
						if (javaOff != spirvOff) {
							mismatches.append(String.format("  idx=%d '%s': java=%d spirv=%d\n", idx, javaName, javaOff, spirvOff));
						}
					}
					if (mismatches.length() > 0) {
						Iris.logger.error("[SPIRV_VERIFY] '{}' INDEX-BASED OFFSET MISMATCHES:\n{}", name, mismatches);
					} else {
						Iris.logger.info("[SPIRV_VERIFY] '{}' All {} compared offsets MATCH", name, compareCount);
					}
					// Log RowMajor status
					if (spirvLayout.hasAnyRowMajor()) {
						Iris.logger.warn("[SPIRV_VERIFY] '{}' WARNING: SPIR-V has RowMajor matrix decorations!", name);
					}
					// Log key fields with their byte offsets
					String[] keyFields = {"gbufferModelView", "gbufferModelViewInverse",
						"gbufferProjection", "gbufferProjectionInverse"};
					for (String f : keyFields) {
						Iris.logger.info("[SPIRV_VERIFY] '{}' java offset '{}' = {}", name, f, uniformBuffer.getFieldOffset(f));
					}
				} else {
					Iris.logger.warn("[SPIRV_VERIFY] '{}' Failed to parse SPIR-V layout", name);
				}
				// Also dump the Java-computed field map (once)
				Iris.logger.info("[UBO_FIELDMAP] '{}':\n{}", name, uniformBuffer.dumpFieldMap());
			} catch (Exception e) {
				Iris.logger.warn("[SPIRV_VERIFY] '{}' Exception during SPIR-V verification: {}", name, e.getMessage());
			}
		}

		return pipeline;
	}

	/**
	 * Fix ALL uses of gbufferProjectionInverse in composite/deferred shaders.
	 *
	 * gbufferProjectionInverse is broken on the GPU (root cause TBD). This injects
	 * a helper function that reconstructs eye-space position from the FORWARD
	 * projection matrix elements (which ARE correct), then replaces all known
	 * usage patterns with calls to that helper.
	 *
	 * Uses exact string matching (not regex) to avoid the nested-parenthesis bugs
	 * that broke the previous fixProjectionInverseGlobal attempt.
	 *
	 * The y-component uses -ndc.y / P[1][1] to compensate for VulkanMod's negated
	 * P[1][1] (Y-flip). This gives the same result as ndc.y / P_gl[1][1].
	 */
	private static String fixCompositeProjectionInverse(String fshSource, String programName) {
		if (!fshSource.contains("gbufferProjectionInverse")) {
			return fshSource;
		}

		// Check for actual uses (not just the uniform declaration inside UBO)
		boolean hasActualUse = false;
		for (String line : fshSource.split("\n")) {
			String trimmed = line.trim();
			if (trimmed.contains("gbufferProjectionInverse") &&
				!trimmed.startsWith("uniform") && !trimmed.startsWith("mat4")) {
				hasActualUse = true;
				break;
			}
		}
		if (!hasActualUse) return fshSource;

		int replacements = 0;

		// Helper function: reconstructs eye-space from forward projection elements
		String helperFunc =
			"\n// Iris Vulkan fix: reconstruct eye-space from forward projection\n" +
			"// bypassing broken gbufferProjectionInverse on GPU.\n" +
			"vec4 _fixedProjInv(vec4 ndc) {\n" +
			"\tvec3 _ed = vec3(\n" +
			"\t\tndc.x / gbufferProjection[0][0],\n" +
			"\t\t-ndc.y / gbufferProjection[1][1],\n" +
			"\t\t-1.0\n" +
			"\t);\n" +
			"\tfloat _t = gbufferProjection[3][2] / (ndc.z + gbufferProjection[2][2]);\n" +
			"\treturn vec4(_ed * _t, 1.0);\n" +
			"}\n";

		// Inject helper at earliest safe point (before first function using projInverse)
		boolean injected = false;
		String[] earlyAnchors = {
			"vec3 ScreenToView(vec3 pos) {",   // composite.fsh
			"vec4 GetVolumetricLight(",         // composite1.fsh
			"void DoTAA(",                      // composite6.fsh
		};
		for (String anchor : earlyAnchors) {
			if (fshSource.contains(anchor)) {
				fshSource = fshSource.replace(anchor, helperFunc + anchor);
				injected = true;
				break;
			}
		}
		if (!injected && fshSource.contains("void main() {")) {
			fshSource = fshSource.replace("void main() {", helperFunc + "void main() {");
			injected = true;
		}
		if (!injected) {
			Iris.logger.warn("[FIX_PROJINV] No injection point found in '{}'", programName);
			return fshSource;
		}

		// Pattern 1: screenPos (composite1 main, composite5 main, composite main)
		String p1old = "gbufferProjectionInverse * (screenPos * 2.0f - 1.0f)";
		String p1new = "_fixedProjInv(screenPos * 2.0f - 1.0f)";
		int c = countOccurrences(fshSource, p1old);
		if (c > 0) { fshSource = fshSource.replace(p1old, p1new); replacements += c; }

		// Pattern 2: screenPos1 (composite1 main, composite6 DoTAA)
		String p2old = "gbufferProjectionInverse * (screenPos1 * 2.0f - 1.0f)";
		String p2new = "_fixedProjInv(screenPos1 * 2.0f - 1.0f)";
		c = countOccurrences(fshSource, p2old);
		if (c > 0) { fshSource = fshSource.replace(p2old, p2new); replacements += c; }

		// Pattern 3: reprojection vec4(pos, 1.0f) (composite Reprojection/FHalfReprojection)
		String p3old = "gbufferProjectionInverse * vec4(pos, 1.0f)";
		String p3new = "_fixedProjInv(vec4(pos, 1.0f))";
		c = countOccurrences(fshSource, p3old);
		if (c > 0) { fshSource = fshSource.replace(p3old, p3new); replacements += c; }

		// Pattern 4: reflection ray tracing (composite)
		String p4old = "gbufferProjectionInverse * vec4(rfragpos * 2.0f - 1.0f, 1.0f)";
		String p4new = "_fixedProjInv(vec4(rfragpos * 2.0f - 1.0f, 1.0f))";
		c = countOccurrences(fshSource, p4old);
		if (c > 0) { fshSource = fshSource.replace(p4old, p4new); replacements += c; }

		// Pattern 5: volumetric light ray-march (composite1 GetVolumetricLight)
		// Uses inline vec4(texCoord, GetDistX(currentDist), 1.0f) instead of screenPos
		String p5old = "gbufferProjectionInverse * (vec4(texCoord, GetDistX(currentDist), 1.0f) * 2.0f - 1.0f)";
		String p5new = "_fixedProjInv(vec4(texCoord, GetDistX(currentDist), 1.0f) * 2.0f - 1.0f)";
		c = countOccurrences(fshSource, p5old);
		if (c > 0) { fshSource = fshSource.replace(p5old, p5new); replacements += c; }

		// Pattern 6: ScreenToView diagonal optimization (composite)
		String stv_old =
			"\tvec4 iProjDiag = vec4(gbufferProjectionInverse[0].x, gbufferProjectionInverse[1].y, gbufferProjectionInverse[2].zw);\n" +
			"\tvec3 p3 = pos * 2.0f - 1.0f;\n" +
			"\tvec4 viewPos = iProjDiag * p3.xyzz + gbufferProjectionInverse[3];\n" +
			"\treturn viewPos.xyz / viewPos.w;";
		String stv_new =
			"\treturn _fixedProjInv(vec4(pos * 2.0f - 1.0f, 1.0f)).xyz;";
		if (fshSource.contains(stv_old)) {
			fshSource = fshSource.replace(stv_old, stv_new);
			replacements++;
		}

		// Pattern 7: Reprojection Y-flip fix (composite6 TAA, composite SSR)
		// gbufferPreviousProjection has VulkanMod's negated P[1][1], so Y in clip
		// space is inverted. After perspective divide + remap to [0,1], UV.y is
		// flipped. Fix by inverting Y: 1.0 - y.
		String reprojOld = "return previousPosition.xy / previousPosition.w * 0.5f + 0.5f;";
		String reprojNew = "vec2 _rp = previousPosition.xy / previousPosition.w * 0.5f + 0.5f;\n\treturn vec2(_rp.x, 1.0 - _rp.y);";
		c = countOccurrences(fshSource, reprojOld);
		if (c > 0) { fshSource = fshSource.replace(reprojOld, reprojNew); replacements += c; }

		if (replacements > 0) {
			Iris.logger.info("[FIX_PROJINV] Fixed {} gbufferProjectionInverse pattern(s) in '{}'", replacements, programName);
		}

		// Warn about any remaining unhandled uses
		int remaining = 0;
		for (String line : fshSource.split("\n")) {
			String trimmed = line.trim();
			if (trimmed.contains("gbufferProjectionInverse") &&
				!trimmed.startsWith("uniform") && !trimmed.startsWith("mat4") &&
				!trimmed.startsWith("//") && !trimmed.contains("_fixedProjInv")) {
				remaining++;
			}
		}
		if (remaining > 0) {
			Iris.logger.warn("[FIX_PROJINV] {} unhandled gbufferProjectionInverse use(s) in '{}'", remaining, programName);
		}

		// DIAG: Disable volumetric light AND bloom fog to isolate the camera-following overlay
		if (DIAG_DISABLE_VL) {
			String vlAdd = "color += volumetricEffect.rgb;";
			if (fshSource.contains(vlAdd)) {
				fshSource = fshSource.replace(vlAdd, "// DIAG_DISABLE_VL: " + vlAdd);
				Iris.logger.info("[DIAG_VL] Disabled volumetric light in '{}'", programName);
			}
			String bloomFog = "color *= GetBloomFog(lViewPos);";
			if (fshSource.contains(bloomFog)) {
				fshSource = fshSource.replace(bloomFog, "// DIAG_DISABLE_VL: " + bloomFog);
				Iris.logger.info("[DIAG_VL] Disabled bloom fog in '{}'", programName);
			}
			// Also disable the inverse bloom fog in composite5
			String invBloomFog = "color /= GetBloomFog(lViewPos);";
			if (fshSource.contains(invBloomFog)) {
				fshSource = fshSource.replace(invBloomFog, "// DIAG_DISABLE_VL: " + invBloomFog);
				Iris.logger.info("[DIAG_VL] Disabled inverse bloom fog in '{}'", programName);
			}
			// Also disable TAA in composite6
			String taaCall = "DoTAA(color, temp, z1);";
			if (fshSource.contains(taaCall)) {
				fshSource = fshSource.replace(taaCall, "// DIAG_DISABLE_VL: " + taaCall);
				Iris.logger.info("[DIAG_VL] Disabled TAA in '{}'", programName);
			}
		}

		return fshSource;
	}

	private static int countOccurrences(String str, String sub) {
		int count = 0;
		int idx = 0;
		while ((idx = str.indexOf(sub, idx)) != -1) {
			count++;
			idx += sub.length();
		}
		return count;
	}

	/**
	 * Fix aurora rendering by replacing the GetAuroraBorealis call with one that uses
	 * a direct eye direction, bypassing the broken gbufferProjectionInverse.
	 * Replaces at the SOURCE so the correct aurora flows into all downstream effects
	 * (clouds, color mixing, etc.).
	 */
	private static String fixViewPosReconstruction(String fshSource, String programName) {
		// FIX 1: Override viewPos and all derived values for ALL PIXELS.
		// gbufferProjectionInverse is broken on GPU, so viewPos from projInverse * ndc
		// gives wrong results. This affects sky effects (aurora, clouds) AND terrain
		// effects (fog, AO, reflections, atmospheric haze).
		// Reconstruct viewPos from the FORWARD projection matrix elements which are correct:
		//   gbufferProjection[0][0] (m00), [1][1] (m11), [2][2] (m22), [3][2] (m32)
		// Sky pixels (z0 >= 1.0): direction only, at large distance.
		// Terrain pixels (z0 < 1.0): linearize depth for actual eye-space position.
		String vdotSLine = "float VdotS = dot(nViewPos, sunVec);";
		if (fshSource.contains(vdotSLine)) {
			String viewPosFix =
				vdotSLine + "\n" +
				"\t{\n" +
				"\t\tvec3 _eyeDir = vec3(\n" +
				"\t\t\t(texCoord.x * 2.0f - 1.0f) / gbufferProjection[0][0],\n" +
				"\t\t\t(1.0f - texCoord.y * 2.0f) / gbufferProjection[1][1],\n" +
				"\t\t\t-1.0f\n" +
				"\t\t);\n" +
				"\t\tif (z0 >= 1.0f) {\n" +
				"\t\t\tvec3 skyEyeDir = _eyeDir * 256.0f;\n" +
				"\t\t\tviewPos = vec4(skyEyeDir, 1.0f);\n" +
				"\t\t\tlViewPos = 1.0e4f;\n" +
				"\t\t\tnViewPos = normalize(skyEyeDir);\n" +
				"\t\t\tplayerPos = (gbufferModelViewInverse * vec4(skyEyeDir, 0.0f)).xyz;\n" +
				"\t\t\tdither = textureLod(noisetex, texCoord * vec2(viewWidth, viewHeight) / 128.0f, 0.0f).b;\n" +
				"\t\t} else {\n" +
				"\t\t\tfloat _ndcZ = z0 * 2.0f - 1.0f;\n" +
				"\t\t\tfloat _t = gbufferProjection[3][2] / (_ndcZ + gbufferProjection[2][2]);\n" +
				"\t\t\tviewPos = vec4(_eyeDir * _t, 1.0f);\n" +
				"\t\t\tlViewPos = length(viewPos.xyz);\n" +
				"\t\t\tnViewPos = normalize(viewPos.xyz);\n" +
				"\t\t\tplayerPos = (gbufferModelViewInverse * vec4(viewPos.xyz, 1.0f)).xyz;\n" +
				"\t\t}\n" +
				"\t\tVdotU = dot(nViewPos, upVec);\n" +
				"\t\tVdotS = dot(nViewPos, sunVec);\n" +
				"\t}";
			fshSource = fshSource.replace(vdotSLine, viewPosFix);
			Iris.logger.info("[FIX_VIEWPOS] Injected viewPos override for ALL pixels in '{}'", programName);
		}

		// FIX 2: Replace aurora call with static dither version.
		// Even with fixed viewPos, aurora needs spatial-only dither (no frameCounter
		// offset) to avoid temporal jitter since TAA isn't working in the Vulkan port.
		String auroraCall = "auroraBorealis = GetAuroraBorealis(viewPos.xyz, VdotU, dither);";
		if (fshSource.contains(auroraCall)) {
			String replacement =
				"{\n" +
				"            float fixDither = textureLod(noisetex, texCoord * vec2(viewWidth, viewHeight) / 128.0, 0.0).b;\n" +
				"            auroraBorealis = GetAuroraBorealis(viewPos.xyz, VdotU, fixDither);\n" +
				"        }";
			fshSource = fshSource.replace(auroraCall, replacement);
			Iris.logger.info("[FIX_AURORA] Replaced aurora dither in '{}'", programName);
		}

		// FIX 3: Clamp the tangent-plane projection in GetAuroraBorealis to prevent
		// the "trailing off into a straight line" artifact near the world-horizon.
		// The function uses wpos.xz /= wpos.y (gnomonic projection). When wpos.y is
		// small (near horizon), coordinates explode, noise creates sharp ridges/lines.
		// The visibility check uses VdotU (eye-space) but the singularity is in
		// world-space (wpos.y), so the check doesn't catch it when camera has pitch.
		// Fix: clamp wpos.y to at least 30% of the direction magnitude, preventing
		// extreme coordinates. Also apply a smooth world-space horizon fade.
		String wpDivOrig = "wpos.xz /= wpos.y;";
		if (fshSource.contains(wpDivOrig)) {
			String wpDivFix =
				"{\n" +
				"\t\t\tfloat _wpLen = length(wpos);\n" +
				"\t\t\tfloat _wpYnorm = wpos.y / _wpLen;\n" +
				"\t\t\twpos.xz /= max(wpos.y, 0.3 * _wpLen);\n" +
				"\t\t\tvisibility *= smoothstep(0.1, 0.4, _wpYnorm);\n" +
				"\t\t}";
			fshSource = fshSource.replace(wpDivOrig, wpDivFix);
			Iris.logger.info("[FIX_AURORA] Clamped aurora tangent-plane projection in '{}'", programName);
		}

		// DIAG: Cloud debug visualization
		if (DIAG_CLOUD_DEBUG) {
			// Replace final output to show diagnostic info.
			// Left third: raw noisetex at texCoord (verify noise texture is proper random)
			// Middle third: raw noisetex at scaled coords matching cloud usage (verify wrapping works)
			// Right third: cloud opacity (R), view direction up component (G), skyFade (B)
			String outputLine = "iris_FragData0 = vec4(color.rgb, 1.0f);";
			if (fshSource.contains(outputLine)) {
				String diag =
					"{\n" +
					"\t\tvec3 diagColor;\n" +
					"\t\tif (texCoord.x < 0.33) {\n" +
					"\t\t\t// Left: raw noise at screen UV — should show colorful random pattern\n" +
					"\t\t\tdiagColor = textureLod(noisetex, texCoord * 4.0, 0.0).rgb;\n" +
					"\t\t} else if (texCoord.x < 0.66) {\n" +
					"\t\t\t// Middle: noise at large UV (2.5+offset) — tests REPEAT wrapping\n" +
					"\t\t\t// If CLAMP: solid color. If REPEAT: same pattern as left.\n" +
					"\t\t\tdiagColor = textureLod(noisetex, texCoord * 4.0 + vec2(2.5, 3.7), 0.0).rgb;\n" +
					"\t\t} else {\n" +
					"\t\t\t// Right: R=cloud opacity, G=nPlayerPos.y (up component), B=skyFade\n" +
					"\t\t\tvec3 npDir = normalize((gbufferModelViewInverse * vec4(normalize(viewPos.xyz), 0.0)).xyz);\n" +
					"\t\t\tdiagColor = vec3(clouds.a, npDir.y * 0.5 + 0.5, skyFade);\n" +
					"\t\t}\n" +
					"\t\tiris_FragData0 = vec4(diagColor, 1.0);\n" +
					"\t}";
				fshSource = fshSource.replace(outputLine, diag);
				Iris.logger.info("[DIAG_CLOUD] Injected cloud debug visualization in '{}'", programName);
			}
		}

		// DIAG: Fog/viewPos debug visualization
		if (DIAG_FOG_DEBUG) {
			String outputLine = "iris_FragData0 = vec4(color.rgb, 1.0f);";
			if (fshSource.contains(outputLine)) {
				String diag =
					"{\n" +
					"\t\tvec3 diagColor;\n" +
					"\t\tif (texCoord.x < 0.33) {\n" +
					"\t\t\t// Left: lViewPos heat map (blue=near 0, red=far renderDistance)\n" +
					"\t\t\tfloat dist = clamp(lViewPos / renderDistance, 0.0, 1.0);\n" +
					"\t\t\tdiagColor = mix(vec3(0.0, 0.0, 1.0), vec3(1.0, 0.0, 0.0), dist);\n" +
					"\t\t\tif (z0 >= 1.0) diagColor = vec3(1.0); // sky = white\n" +
					"\t\t} else if (texCoord.x < 0.66) {\n" +
					"\t\t\t// Middle: playerPos.y + cameraPosition.y (altitude)\n" +
					"\t\t\t// Green = high altitude, Red = low altitude, black = y=63\n" +
					"\t\t\tfloat alt = (playerPos.y + cameraPosition.y - 63.0) / 128.0;\n" +
					"\t\t\tdiagColor = alt > 0.0 ? vec3(0.0, alt, 0.0) : vec3(-alt, 0.0, 0.0);\n" +
					"\t\t\tdiagColor = clamp(diagColor, 0.0, 1.0);\n" +
					"\t\t\tif (z0 >= 1.0) diagColor = vec3(1.0); // sky = white\n" +
					"\t\t} else {\n" +
					"\t\t\t// Right: R=skyFade, G=lViewPos/256, B=z0\n" +
					"\t\t\tdiagColor = vec3(skyFade, lViewPos / 256.0, z0);\n" +
					"\t\t}\n" +
					"\t\tiris_FragData0 = vec4(diagColor, 1.0);\n" +
					"\t}";
				fshSource = fshSource.replace(outputLine, diag);
				Iris.logger.info("[DIAG_FOG] Injected fog debug visualization in '{}'", programName);
			}
		}

		return fshSource;
	}

	private static void collectSamplerNamesToList(String source, List<String> names) {
		for (String line : source.split("\n")) {
			Matcher m = SAMPLER_PATTERN.matcher(line.trim());
			if (m.find()) {
				String samplerName = m.group(2);
				if (!names.contains(samplerName)) {
					names.add(samplerName);
				}
			}
		}
	}

	/**
	 * Adds explicit layout(location=N) qualifiers to vertex shader input declarations
	 * to match VulkanMod's POSITION_TEX vertex format:
	 *   location 0 = Position (vec3)
	 *   location 1 = UV0 (vec2)
	 *
	 * Without explicit qualifiers, shaderc's auto-map assigns locations by declaration
	 * order in the source, which may not match the vertex buffer layout if
	 * CompositeTransformer injected UV0 before Position.
	 */
	private static String fixCompositeVertexInputLocations(String vshSource) {
		// Only fix declarations that don't already have layout qualifiers
		vshSource = vshSource.replaceAll(
			"(?m)^(\\s*)in\\s+vec3\\s+Position\\s*;",
			"$1layout(location = 0) in vec3 Position;");
		vshSource = vshSource.replaceAll(
			"(?m)^(\\s*)in\\s+vec2\\s+UV0\\s*;",
			"$1layout(location = 1) in vec2 UV0;");
		return vshSource;
	}

	/**
	 * Maps well-known sampler names to VTextureSelector texture indices.
	 * Must match ExtendedShader.mapSamplerToTextureIndex() for consistency.
	 * Follows the standard Iris/OptiFine texture unit convention.
	 */
	private static int mapSamplerToTextureIndex(String name) {
		return switch (name) {
			case "gtexture", "gcolor", "colortex0", "tex", "texture" -> 0;
			case "overlay" -> 1;
			case "lightmap" -> 2;
			case "normals", "gnormal", "colortex1" -> 3;
			case "specular", "gspecular", "colortex2" -> 4;
			case "shadow", "watershadow", "shadowtex0" -> 5;
			case "shadowtex1" -> 6;
			case "shadowcolor0", "shadowcolor", "colortex3" -> 7;
			case "shadowcolor1", "colortex4" -> 8;
			case "noisetex" -> 9;
			case "depthtex0", "colortex5" -> 10;
			case "depthtex1", "colortex6" -> 11;
			default -> {
				// colortexN (N >= 7) → N + 5 to continue after slot 11
				if (name.startsWith("colortex")) {
					try {
						int n = Integer.parseInt(name.substring(8));
						if (n >= 7) yield n + 5;
						yield 0;
					}
					catch (NumberFormatException e) { yield 0; }
				}
				if (name.startsWith("gaux")) {
					try { yield Integer.parseInt(name.substring(4)) + 15; }
					catch (NumberFormatException e) { yield 0; }
				}
				yield 0;
			}
		};
	}

	private static String addExplicitBindings(String source, Map<String, Integer> samplerBindings) {
		String[] lines = source.split("\n", -1);
		List<String> output = new ArrayList<>();

		for (String line : lines) {
			String trimmed = line.trim();

			if (trimmed.equals("layout(std140) uniform IrisUniforms {")) {
				output.add("layout(std140, binding = 0) uniform IrisUniforms {");
				continue;
			}

			Matcher m = SAMPLER_PATTERN.matcher(trimmed);
			if (m.find()) {
				String samplerType = m.group(1);
				String samplerName = m.group(2);
				Integer binding = samplerBindings.get(samplerName);
				if (binding != null) {
					String fullLine = trimmed.substring(m.end());
					output.add("layout(binding = " + binding + ") uniform " + samplerType + " " + samplerName + fullLine);
					continue;
				}
			}

			output.add(line);
		}

		return String.join("\n", output);
	}

	public ComputeProgram buildCompute() {
		return new ComputeProgram(program, super.buildUniforms(), this.samplers.build(), this.images.build());
	}

	@Override
	public void addExternalSampler(int textureUnit, String... names) {
		samplers.addExternalSampler(textureUnit, names);
	}

	@Override
	public boolean hasSampler(String name) {
		return samplers.hasSampler(name);
	}

	@Override
	public boolean addDefaultSampler(IntSupplier sampler, String... names) {
		return samplers.addDefaultSampler(sampler, names);
	}

	@Override
	public boolean addDefaultSampler(TextureType type, IntSupplier texture, ValueUpdateNotifier notifier, GlSampler sampler, String... names) {
		return samplers.addDefaultSampler(type, texture, notifier, sampler, names);
	}

	@Override
	public boolean addDynamicSampler(IntSupplier sampler, String... names) {
		return samplers.addDynamicSampler(sampler, names);
	}

	@Override
	public boolean addDynamicSampler(TextureType type, IntSupplier texture, GlSampler sampler, String... names) {
		return samplers.addDynamicSampler(type, texture, sampler, names);
	}

	public boolean addDynamicSampler(IntSupplier sampler, ValueUpdateNotifier notifier, String... names) {
		return samplers.addDynamicSampler(sampler, notifier, names);
	}

	@Override
	public boolean addDynamicSampler(TextureType type, IntSupplier texture, ValueUpdateNotifier notifier, GlSampler sampler, String... names) {
		return samplers.addDynamicSampler(type, texture, notifier, sampler, names);
	}

	@Override
	public boolean hasImage(String name) {
		return images.hasImage(name);
	}

	@Override
	public void addTextureImage(IntSupplier textureID, InternalTextureFormat internalFormat, String name) {
		images.addTextureImage(textureID, internalFormat, name);
	}

	private static void dumpCompositeShader(String filename, String source) {
		if (source == null) return;
		try {
			java.io.File dir = new java.io.File("iris-debug");
			if (!dir.exists()) dir.mkdirs();
			java.io.File file = new java.io.File(dir, filename + ".glsl");
			java.nio.file.Files.writeString(file.toPath(), source);
			Iris.logger.info("[ProgramBuilder] Dumped composite shader to {}", file.getAbsolutePath());
		} catch (Exception e) {
			Iris.logger.warn("[ProgramBuilder] Failed to dump composite shader {}: {}", filename, e.getMessage());
		}
	}
}
