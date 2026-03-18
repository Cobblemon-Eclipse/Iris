package net.irisshaders.iris.pipeline.terrain;

import net.irisshaders.iris.Iris;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GLSL preprocessing for terrain shaders before SPIR-V compilation.
 * Handles vertex attribute fixes, push constants, sampler binding, and
 * other Vulkan-specific transformations of shader pack GLSL source.
 */
class TerrainGlslPreprocessor {

	private static final Pattern SAMPLER_PATTERN = Pattern.compile(
		"^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+((?:sampler|isampler|usampler)\\w+)\\s+(\\w+)");

	// Pattern for layout-qualified varying declarations
	private static final Pattern VARYING_DECL_PATTERN = Pattern.compile(
		"^\\s*layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*((?:(?:flat|smooth|noperspective)\\s+)*)(out|in)\\s+(\\w+)\\s+(\\w+)\\s*;");

	/**
	 * Fix vertex attribute types to match COMPRESSED_TERRAIN VkFormat:
	 * - Position (SHORT×4) → VK_FORMAT_R16G16B16A16_SINT → ivec4
	 * - Color (UBYTE×4) → VK_FORMAT_R8G8B8A8_UNORM → vec4
	 * - UV0 (USHORT×2) → VK_FORMAT_R16G16_UINT → uvec2
	 * - UV2 (SHORT×2) → VK_FORMAT_R16G16_SINT → ivec2
	 *
	 * Also strips 'in' qualifier from standard Iris vertex inputs (_vert_position,
	 * _vert_color, etc.) that are computed by _vert_init() from terrain attributes.
	 * Without this, shaderc auto_map_locations assigns them conflicting locations.
	 *
	 * Converts Iris-specific vertex inputs (mc_midTexCoord, mc_Entity) to constants
	 * since COMPRESSED_TERRAIN format doesn't include them.
	 */
	String fixTerrainVertexAttributes(String source) {
		// Fix a_PosId: uvec4 → ivec4 (SINT format)
		source = source.replaceAll("(?:layout\\s*\\([^)]*\\)\\s*)?in\\s+[ui]?vec4\\s+a_PosId",
			"layout(location=0) in ivec4 a_PosId");

		// Fix a_Color: already vec4 (UNORM format) — just add location
		source = source.replaceAll("(?:layout\\s*\\([^)]*\\)\\s*)?in\\s+vec4\\s+a_Color",
			"layout(location=1) in vec4 a_Color");

		// Fix a_TexCoord: vec2 → uvec2 (UINT format), with #define for automatic conversion
		if (source.matches("(?s).*\\bin\\s+vec2\\s+a_TexCoord.*")) {
			source = source.replaceAll("(?:layout\\s*\\([^)]*\\)\\s*)?in\\s+vec2\\s+a_TexCoord\\s*;",
				"layout(location=2) in uvec2 _iris_TexCoord_raw;\n#define a_TexCoord vec2(_iris_TexCoord_raw)");
		} else {
			source = source.replaceAll("(?:layout\\s*\\([^)]*\\)\\s*)?in\\s+uvec2\\s+a_TexCoord",
				"layout(location=2) in uvec2 a_TexCoord");
		}

		// Convert a_LightCoord to a constant instead of a vertex input.
		// VulkanMod's CompressedVertexBuilder never writes UV2 (bytes 16-19 of the
		// 20-byte stride), so location 3 reads uninitialized garbage.
		// Light data is correctly extracted from a_PosId.w in fixTerrainPositionDecoding().
		source = source.replaceAll("(?:layout\\s*\\([^)]*\\)\\s*)?in\\s+ivec2\\s+a_LightCoord\\s*;",
			"ivec2 a_LightCoord = ivec2(0); // UV2 not written by CompressedVertexBuilder");

		// Strip 'in' from standard Iris vertex inputs — these are computed by _vert_init()
		// from the terrain attributes, NOT from vertex buffer. If left as 'in', shaderc
		// auto_map_locations gives them locations 0-N that conflict with terrain attributes.
		String[] irisVertInputs = {
			"_vert_position", "_vert_color", "_vert_tex_diffuse_coord",
			"_vert_tex_light_coord", "_draw_id", "_material_params"
		};
		for (String varName : irisVertInputs) {
			source = source.replaceAll(
				"(?:layout\\s*\\([^)]*\\)\\s*)?\\bin\\s+(\\w+\\s+" + Pattern.quote(varName) + ")",
				"$1");
		}

		// Convert Iris-specific vertex inputs to constants — COMPRESSED_TERRAIN doesn't include them
		String[] irisSpecialInputs = {
			"mc_midTexCoord", "at_midBlock", "at_tangent", "iris_Normal"
		};
		for (String varName : irisSpecialInputs) {
			source = source.replaceAll(
				"(?:layout\\s*\\([^)]*\\)\\s*)?\\bin\\s+(\\w+)\\s+" + Pattern.quote(varName) + "\\s*;",
				"$1 " + varName + " = " + getDefaultInitializer(varName) + ";");
		}

		// Read mc_Entity from UV2 bytes (block entity ID) — our MixinTerrainBufferBuilder
		// writes the shader pack's block entity ID into UV2 bytes during chunk compilation.
		// Since a_LightCoord was converted to a constant above, location 3 is free for entity data.
		source = iris$handleMcEntity(source);

		// Catch-all: strip 'in' from ANY remaining non-terrain declarations to prevent
		// shaderc auto_map_locations from creating location conflicts
		String[] terrainAttribs = {"a_PosId", "a_Color", "_iris_TexCoord_raw", "a_TexCoord", "_iris_EntityId_raw"};
		StringBuilder sb = new StringBuilder();
		for (String line : source.split("\n", -1)) {
			String trimmed = line.trim();
			if (trimmed.matches("(?:layout\\s*\\([^)]*\\)\\s*)?in\\s+\\w+\\s+\\w+.*")) {
				boolean isTerrain = false;
				for (String ta : terrainAttribs) {
					if (trimmed.contains(" " + ta)) {
						isTerrain = true;
						break;
					}
				}
				if (!isTerrain) {
					line = line.replaceAll("(?:layout\\s*\\([^)]*\\)\\s*)?\\bin\\s+", "");
					Iris.logger.warn("[TerrainGlslPreprocessor] Stripped unexpected 'in' decl: {}", trimmed);
				}
			}
			sb.append(line).append("\n");
		}
		source = sb.toString();

		return source;
	}

	/**
	 * Returns a sensible GLSL default initializer for Iris-specific vertex inputs
	 * that aren't available in COMPRESSED_TERRAIN format.
	 */
	String getDefaultInitializer(String varName) {
		return switch (varName) {
			case "mc_midTexCoord" -> "vec2(0.0)"; // mid-texture coord, will use type from declaration
			case "at_midBlock" -> "vec3(0.0)";    // mid-block offset
			case "at_tangent" -> "vec4(1.0, 0.0, 0.0, 1.0)"; // tangent
			case "iris_Normal" -> "vec3(0.0, 1.0, 0.0)";     // up normal
			default -> "0";
			// Note: mc_Entity handled by iris$handleMcEntity() — reads from UV2 attribute
		};
	}

	/**
	 * Handles mc_Entity by reading it from UV2 bytes instead of using a constant.
	 * Our MixinTerrainBufferBuilder writes block entity IDs into UV2 offset 16-19.
	 *
	 * Replaces: in vec4 mc_Entity;
	 * With:     layout(location=3) in ivec2 _iris_EntityId_raw;
	 *           vec4 mc_Entity = vec4(float(_iris_EntityId_raw.x), float(_iris_EntityId_raw.y), 0.0, 0.0);
	 */
	String iris$handleMcEntity(String source) {
		Matcher m = Pattern.compile(
			"(?:layout\\s*\\([^)]*\\)\\s*)?\\bin\\s+(\\w+)\\s+mc_Entity\\s*;").matcher(source);
		if (!m.find()) {
			return source;
		}

		String type = m.group(1);

		String initValue = switch (type) {
			case "vec4" -> "vec4(float(_iris_EntityId_raw.x), float(_iris_EntityId_raw.y), 0.0, 0.0)";
			case "vec3" -> "vec3(float(_iris_EntityId_raw.x), float(_iris_EntityId_raw.y), 0.0)";
			case "vec2" -> "vec2(float(_iris_EntityId_raw.x), float(_iris_EntityId_raw.y))";
			case "ivec4" -> "ivec4(_iris_EntityId_raw.x, _iris_EntityId_raw.y, 0, 0)";
			case "ivec3" -> "ivec3(_iris_EntityId_raw.x, _iris_EntityId_raw.y, 0)";
			case "ivec2" -> "ivec2(_iris_EntityId_raw.x, _iris_EntityId_raw.y)";
			default -> type + "(float(_iris_EntityId_raw.x))";
		};

		String replacement = "layout(location=3) in ivec2 _iris_EntityId_raw;\n" +
			type + " mc_Entity = " + initValue + ";";

		source = source.replaceAll(
			"(?:layout\\s*\\([^)]*\\)\\s*)?\\bin\\s+" + Pattern.quote(type) + "\\s+mc_Entity\\s*;",
			Matcher.quoteReplacement(replacement));

		Iris.logger.info("[TerrainGlslPreprocessor] mc_Entity ({}) reads from UV2 attribute (block entity ID from mixin)",
			type);

		return source;
	}

	/**
	 * Fix position decoding for VulkanMod's COMPRESSED_TERRAIN format.
	 *
	 * Iris/Sodium encodes terrain positions as unsigned 16-bit with an 8-block offset:
	 *   encoded = (blockPos + 8.0) * 2048  →  decode: val * (1/2048) - 8.0
	 *
	 * VulkanMod encodes as signed 16-bit without offset:
	 *   encoded = blockPos * 2048  →  decode: val * (1/2048)
	 *
	 * Iris extracts chunk draw-translation from a_PosId.w, but VulkanMod stores
	 * lightmap data there instead. VulkanMod passes chunk offset via gl_InstanceIndex.
	 *
	 * This method patches _vert_init() and _get_draw_translation() to use VulkanMod's
	 * encoding scheme.
	 */
	String fixTerrainPositionDecoding(String source) {
		// Replace Iris/Sodium position decoding (unsigned + offset) with VulkanMod's (signed, no offset).
		// The glsl_transformer AST printer may reformat the expression: drop outer parentheses,
		// change numeric literal format, rewrite "+-" as "-", wrap negatives in parens, etc.
		// We use multiple patterns in priority order to handle these variations.
		//
		// Iris:     _vert_position = (vec3(a_PosId.xyz) * 4.8828125E-4f + -8.0f);
		// VulkanMod: pos = fma(Position.xyz, vec3(1/2048), ChunkOffset + baseOffset);
		// We include baseOffset (from gl_InstanceIndex) in _vert_position so _get_draw_translation() can return 0.
		String posReplacement = "_vert_position = vec3(a_PosId.xyz) * vec3(1.0 / 2048.0) + " +
			"vec3(bitfieldExtract(ivec3(gl_InstanceIndex) >> ivec3(0, 16, 8), 0, 8))";

		// Pattern 1: Original — with outer parens: (vec3(a_PosId.xyz) * CONST + -8.0)
		String replaced = source.replaceAll(
			"_vert_position\\s*=\\s*\\(vec3\\(a_PosId\\.xyz\\)\\s*\\*\\s*[0-9.eE+-]+f?\\s*\\+\\s*-8\\.0f?\\)",
			posReplacement);

		// Pattern 2: No outer parens, "- 8.0" instead of "+ -8.0"
		if (replaced.equals(source)) {
			replaced = source.replaceAll(
				"_vert_position\\s*=\\s*vec3\\s*\\(\\s*a_PosId\\.xyz\\s*\\)\\s*\\*\\s*[0-9.eE+-]+f?\\s*(?:\\+\\s*-|-)\\s*8\\.0f?",
				posReplacement);
		}

		// Pattern 3: Parenthesized negative: + (-8.0) or (+ (-8.0f))
		if (replaced.equals(source)) {
			replaced = source.replaceAll(
				"_vert_position\\s*=\\s*\\(?\\s*vec3\\s*\\(\\s*a_PosId\\.xyz\\s*\\)\\s*\\*\\s*[0-9.eE+-]+f?\\s*\\+\\s*\\(\\s*-\\s*8\\.0f?\\s*\\)\\s*\\)?",
				posReplacement);
		}

		// Pattern 4: Broadest fallback — any _vert_position assignment referencing a_PosId.xyz
		// in the same statement (before the semicolon). Safe because this only appears in _vert_init().
		if (replaced.equals(source)) {
			replaced = source.replaceAll(
				"_vert_position\\s*=\\s*[^;]*?vec3\\s*\\(\\s*a_PosId\\.xyz\\s*\\)[^;]*",
				posReplacement);
		}

		if (replaced.equals(source)) {
			Iris.logger.warn("[TerrainGlslPreprocessor] WARNING: _vert_position decoding pattern NOT matched! " +
				"Position decoding may be wrong. Searching for context...");
			int idx = source.indexOf("_vert_position");
			if (idx >= 0) {
				int end = source.indexOf(";", idx);
				if (end > idx) {
					Iris.logger.warn("[TerrainGlslPreprocessor] Found: {}",
						source.substring(idx, Math.min(end + 1, idx + 200)).trim());
				}
			} else {
				Iris.logger.warn("[TerrainGlslPreprocessor] _vert_position NOT FOUND in shader source at all!");
			}
		} else {
			Iris.logger.info("[TerrainGlslPreprocessor] Fixed _vert_position decoding for VulkanMod COMPRESSED_TERRAIN");
		}
		source = replaced;

		// Replace draw_id extraction — a_PosId.w contains lightmap data in VulkanMod, not draw ID
		String drawIdBefore = source;
		source = source.replaceAll(
			"_draw_id\\s*=\\s*\\(?\\s*a_PosId\\.w\\s*>>\\s*8u?\\s*\\)?\\s*&\\s*(?:0x[0-9a-fA-F]+u?|\\d+u?)",
			"_draw_id = 0u");
		if (source.equals(drawIdBefore)) {
			source = source.replaceAll(
				"_draw_id\\s*=\\s*[^;]*a_PosId[^;]*",
				"_draw_id = 0u");
		}

		// Replace _material_params extraction — a_PosId.w is lightmap data
		source = source.replaceAll(
			"_material_params\\s*=\\s*\\(?\\s*a_PosId\\.w\\s*>>\\s*0u?\\s*\\)?\\s*&\\s*(?:0x[0-9a-fA-F]+u?|\\d+u?)",
			"_material_params = 0u");

		// Extract lightmap from a_PosId.w instead of a_LightCoord (UV2).
		// VulkanMod's COMPRESSED_TERRAIN format packs light into position.W (offset 6-7)
		// but never writes UV2 (offset 16-19), so a_LightCoord is always ivec2(0,0).
		// The packed short contains: low byte = blockLight (0-240), high byte = skyLight (0-240).
		// VulkanMod packing: short l = (short)(((light >>> 8) & 0xFF00) | (light & 0xFF))
		// where light = blockLight | (skyLight << 16), both in 0-240 range (level * 16).
		source = source.replaceAll(
			"_vert_tex_light_coord\\s*=\\s*a_LightCoord\\s*;",
			"_vert_tex_light_coord = ivec2(a_PosId.w & 0xFF, (a_PosId.w >> 8) & 0xFF);");

		// Fix midCoord: mc_midTexCoord is unavailable in COMPRESSED_TERRAIN (defaults to vec2(0)),
		// which makes iris_MidTex = (0,0,0,1) and midCoord = (0,0). This breaks atlas border
		// color averaging and tile randomization. Use texCoord instead — not the exact center
		// of the block's atlas region, but much better than (0,0).
		source = source.replaceAll(
			"midCoord\\s*=\\s*\\(mat4\\(1\\.0f?\\)\\s*\\*\\s*iris_MidTex\\)\\.st\\s*;",
			"midCoord = texCoord;");

		// Replace _get_draw_translation() — offset is already in _vert_position via baseOffset
		source = source.replaceAll(
			"vec3\\s+_get_draw_translation\\s*\\(uint\\s+\\w+\\)\\s*\\{[^}]*\\}",
			"vec3 _get_draw_translation(uint pos) { return vec3(0.0); }");

		// Replace _get_relative_chunk_coord() — not needed in VulkanMod
		source = source.replaceAll(
			"uvec3\\s+_get_relative_chunk_coord\\s*\\(uint\\s+\\w+\\)\\s*\\{[^}]*\\}",
			"uvec3 _get_relative_chunk_coord(uint pos) { return uvec3(0u); }");

		return source;
	}

	/**
	 * Adds Vulkan depth correction to a shadow vertex shader.
	 *
	 * Shadow vertex shaders (from the shader pack) output clip-space z in OpenGL NDC [-1,1]
	 * range because they apply custom z manipulation (distortion, compression) that expects
	 * the OpenGL depth pipeline. Vulkan's depth buffer stores z_ndc directly (no (z+1)/2
	 * viewport transform like OpenGL), so we manually apply the conversion.
	 *
	 * This is DIFFERENT from gbuffer terrain shaders, which use iris_ProjectionMatrix
	 * (already Vulkan [0,1]) and don't apply custom z manipulation.
	 */
	String addShadowDepthCorrection(String vshSource) {
		int lastBrace = vshSource.lastIndexOf('}');
		if (lastBrace >= 0) {
			vshSource = vshSource.substring(0, lastBrace) +
				"    // Vulkan depth correction: map OpenGL NDC [-1,1] to Vulkan [0,1]\n" +
				"    gl_Position.z = gl_Position.z * 0.5 + gl_Position.w * 0.5;\n" +
				vshSource.substring(lastBrace);
			Iris.logger.info("[TerrainGlslPreprocessor] Added shadow depth correction");
		}
		return vshSource;
	}

	/**
	 * Injects face normal computation into the terrain fragment shader.
	 *
	 * VulkanMod's COMPRESSED_TERRAIN vertex format doesn't include per-vertex normals,
	 * so iris_Normal is a constant vec3(0,1,0) — making all terrain faces appear to
	 * face upward. This causes wrong lighting for side faces (blue/purple speckling
	 * in the gbuffer normals).
	 *
	 * Fix: compute the geometric face normal from dFdx/dFdy on the interpolated
	 * player-space position varying, then transform to view-space. Uses dFdy×dFdx
	 * (swapped order) to compensate for VulkanMod's viewport Y-flip (negative height).
	 */
	String injectFragmentNormals(String fshSource) {
		// Different shader programs use different names for the position varying:
		// - solid/cutout: "vertexPos" (from _vert_init())
		// - translucent:  "playerPos" (from ComplementaryUnbound naming)
		if (!fshSource.contains("in vec3 normal")) {
			Iris.logger.warn("[TerrainGlslPreprocessor] Fragment shader missing 'in vec3 normal' varying, skipping normal injection");
			return fshSource;
		}

		String posVarying;
		if (fshSource.contains("in vec3 vertexPos")) {
			posVarying = "vertexPos";
		} else if (fshSource.contains("in vec3 playerPos")) {
			posVarying = "playerPos";
		} else {
			Iris.logger.warn("[TerrainGlslPreprocessor] Fragment shader missing position varying (vertexPos/playerPos), skipping normal injection");
			return fshSource;
		}

		// Convert "layout(location=N) in vec3 normal;" to a regular variable so we can write to it
		String before = fshSource;
		fshSource = fshSource.replaceAll(
			"layout\\s*\\(\\s*location\\s*=\\s*\\d+\\s*\\)\\s*in\\s+vec3\\s+normal\\s*;",
			"vec3 normal;");

		if (fshSource.equals(before)) {
			fshSource = fshSource.replaceAll("\\bin\\s+vec3\\s+normal\\s*;", "vec3 normal;");
		}

		if (fshSource.equals(before)) {
			Iris.logger.warn("[TerrainGlslPreprocessor] Could not patch 'in vec3 normal' declaration");
			return fshSource;
		}

		// dFdy×dFdx (swapped) compensates for VulkanMod's viewport Y-flip.
		// Position varying is in player-space (world relative to camera);
		// gbufferModelView transforms from player-space to view-space.
		// We snap the raw derivative-based normal to the nearest axis because:
		// 1) Minecraft terrain faces are always axis-aligned (±X, ±Y, ±Z)
		// 2) dFdx/dFdy produces garbage at triangle edges from GPU helper invocations,
		//    causing visible grid lines at block boundaries. Snapping fixes these artifacts.
		fshSource = fshSource.replaceFirst(
			"(void\\s+main\\s*\\(\\s*\\)\\s*\\{)",
			"$1\n" +
			"    // Compute geometric face normal from position derivatives,\n" +
			"    // snapped to nearest axis (all Minecraft terrain is axis-aligned)\n" +
			"    {\n" +
			"        vec3 rawN = normalize(cross(dFdy(" + posVarying + "), dFdx(" + posVarying + ")));\n" +
			"        vec3 absN = abs(rawN);\n" +
			"        vec3 snappedN;\n" +
			"        if (absN.x >= absN.y && absN.x >= absN.z) snappedN = vec3(sign(rawN.x), 0.0, 0.0);\n" +
			"        else if (absN.y >= absN.z) snappedN = vec3(0.0, sign(rawN.y), 0.0);\n" +
			"        else snappedN = vec3(0.0, 0.0, sign(rawN.z));\n" +
			"        normal = normalize(mat3(gbufferModelView) * snappedN);\n" +
			"    }\n");

		Iris.logger.info("[TerrainGlslPreprocessor] Injected fragment face normal computation (using {})", posVarying);
		return fshSource;
	}

	/**
	 * Add push constant block for ChunkOffset (replaces u_RegionOffset uniform).
	 * Inserts after the IrisUniforms block or at the top of the shader.
	 */
	String addPushConstantBlock(String source) {
		String pushConstantBlock = "\nlayout(push_constant) uniform PushConstants {\n    vec3 ChunkOffset;\n};\n";

		int uboEnd = source.indexOf("uniform IrisUniforms {");
		if (uboEnd >= 0) {
			int closingBrace = source.indexOf("};", uboEnd);
			if (closingBrace >= 0) {
				int insertPoint = closingBrace + 2;
				source = source.substring(0, insertPoint) + pushConstantBlock + source.substring(insertPoint);
				return source;
			}
		}

		int versionEnd = source.indexOf('\n');
		if (versionEnd >= 0) {
			source = source.substring(0, versionEnd + 1) + pushConstantBlock + source.substring(versionEnd + 1);
		}

		return source;
	}

	/**
	 * Resolve OptiFine/Iris gaux sampler aliases to their colortex equivalents.
	 * gaux1=colortex4, gaux2=colortex5, gaux3=colortex6, gaux4=colortex7.
	 *
	 * When both names appear in a shader, they get separate descriptor bindings
	 * that map to the same VTextureSelector slot. In practice, one binding gets
	 * the correct texture while the other remains empty — e.g., SSR reads gaux2
	 * (empty) instead of colortex5 (has scene color), producing black reflections.
	 *
	 * Fix: rename all gaux references to colortex and remove duplicate declarations.
	 */
	String resolveGauxAliases(String source) {
		String[][] aliases = {
			{"gaux1", "colortex4"},
			{"gaux2", "colortex5"},
			{"gaux3", "colortex6"},
			{"gaux4", "colortex7"},
		};
		boolean changed = false;
		for (String[] alias : aliases) {
			String gauxName = alias[0];
			String colortexName = alias[1];
			if (!source.contains(gauxName)) continue;

			boolean hasColortex = source.contains("sampler2D " + colortexName);
			source = source.replace(gauxName, colortexName);
			changed = true;

			if (hasColortex) {
				// Remove the duplicate sampler declaration created by the rename.
				// Keep the first occurrence, remove subsequent ones.
				String declPattern = "(?m)^.*uniform\\s+sampler2D\\s+" + Pattern.quote(colortexName) + "\\s*;.*\\R?";
				java.util.regex.Matcher m = Pattern.compile(declPattern).matcher(source);
				int count = 0;
				StringBuffer sb = new StringBuffer();
				while (m.find()) {
					count++;
					if (count > 1) {
						m.appendReplacement(sb, "");
					}
				}
				if (count > 1) {
					m.appendTail(sb);
					source = sb.toString();
					Iris.logger.info("[resolveGauxAliases] Merged {} → {} (removed {} duplicate declaration(s))",
						gauxName, colortexName, count - 1);
				}
			} else {
				Iris.logger.info("[resolveGauxAliases] Renamed {} → {} (no pre-existing colortex declaration)",
					gauxName, colortexName);
			}
		}
		return source;
	}

	/**
	 * Rename u_RegionOffset references to ChunkOffset (push constant name).
	 * Also removes any remaining u_RegionOffset declarations that prepareForVulkan
	 * might have placed in the IrisUniforms block.
	 */
	String renameRegionOffset(String source) {
		source = source.replaceAll("\\s*vec3\\s+u_RegionOffset\\s*;", "");
		source = source.replace("u_RegionOffset", "ChunkOffset");
		return source;
	}

	void collectSamplerNames(String source, List<String> names) {
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

	String addExplicitBindings(String source, Map<String, Integer> samplerBindings) {
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
					String rest = trimmed.substring(m.end());
					output.add("layout(binding = " + binding + ") uniform " + samplerType + " " + samplerName + rest);
					continue;
				}
			}

			output.add(line);
		}

		return String.join("\n", output);
	}

	/**
	 * Maps Iris sampler names to VTextureSelector slot indices.
	 *
	 * Currently only the block atlas (slot 0) and lightmap (slot 2) are properly
	 * bound by VulkanMod. Iris-managed textures are mapped to VTextureSelector
	 * indices matching the standard Iris sampler unit convention.
	 * Shadow textures are bound by IrisTerrainRenderHook.beginTerrainPass().
	 */
	static int mapSamplerToTextureIndex(String name) {
		// colortex0/1/2 MUST be at different slots from tex/normals/specular.
		// In gbuffer passes, "tex" is the block atlas and "colortex0" is the
		// gbuffer color output from previous passes (used by translucent shader
		// for refraction/reflection). If they share a slot, the water shader
		// reads the block atlas instead of the scene → vanilla-looking water.
		return switch (name) {
			case "gtexture", "tex", "texture", "Sampler0" -> 0; // block atlas
			case "colortex0" -> 1; // gbuffer color 0 (scene from deferred)
			case "lightmap", "Sampler2" -> 2; // lightmap texture
			case "normals", "gnormal" -> 3; // PBR normal atlas
			case "specular", "gspecular" -> 4; // PBR specular atlas
			case "colortex1" -> 11; // gbuffer color 1
			case "colortex2" -> 19; // gbuffer color 2
			case "colortex3" -> 5;
			case "colortex4", "gaux1" -> 6;
			case "colortex5", "gaux2" -> 7;
			case "colortex6", "gaux3" -> 8;
			case "colortex7", "gaux4" -> 9;
			case "colortex8" -> 10;
			case "depthtex0" -> 12;
			case "depthtex1" -> 13;
			case "shadowtex0", "shadow", "watershadow" -> 14;
			case "shadowtex1" -> 15;
			case "shadowcolor0", "shadowcolor" -> 16;
			case "shadowcolor1" -> 17;
			case "noisetex" -> 18;
			default -> 0; // safe fallback to block atlas
		};
	}

	/**
	 * Writes transformed shader source to a file for debugging.
	 * Files are written to the game directory under iris-debug/.
	 */
	void dumpShaderToFile(String filename, String source) {
		try {
			java.io.File dir = new java.io.File("iris-debug");
			if (!dir.exists()) dir.mkdirs();
			java.io.File file = new java.io.File(dir, filename + ".glsl");
			java.nio.file.Files.writeString(file.toPath(), source);
			Iris.logger.info("[TerrainGlslPreprocessor] Dumped shader to {}", file.getAbsolutePath());
		} catch (Exception e) {
			Iris.logger.warn("[TerrainGlslPreprocessor] Failed to dump shader {}: {}", filename, e.getMessage());
		}
	}

	/**
	 * Fixes vertex-fragment varying interface mismatches.
	 * When a fragment input has the same name as a vertex output but a different type,
	 * the fragment input is converted to a zero-initialized constant.
	 */
	String fixVaryingTypeMismatches(String vshSource, String fshSource) {
		Map<String, String> vertexOutTypes = new LinkedHashMap<>();
		for (String line : vshSource.split("\n", -1)) {
			Matcher m = VARYING_DECL_PATTERN.matcher(line.trim());
			if (m.find() && "out".equals(m.group(3))) {
				vertexOutTypes.put(m.group(5), m.group(4));
			}
		}

		if (vertexOutTypes.isEmpty()) return fshSource;

		String[] lines = fshSource.split("\n", -1);
		List<String> output = new ArrayList<>();
		int fixCount = 0;

		for (String line : lines) {
			Matcher m = VARYING_DECL_PATTERN.matcher(line.trim());
			if (m.find() && "in".equals(m.group(3))) {
				String type = m.group(4);
				String name = m.group(5);
				String vertexType = vertexOutTypes.get(name);

				if (vertexType != null && !vertexType.equals(type)) {
					output.add("const " + type + " " + name + " = " + type + "(0);");
					fixCount++;
					continue;
				}
			}
			output.add(line);
		}

		if (fixCount > 0) {
			Iris.logger.info("[VaryingFix] Converted {} terrain fragment inputs with type mismatches to constants", fixCount);
		}
		return String.join("\n", output);
	}
}
