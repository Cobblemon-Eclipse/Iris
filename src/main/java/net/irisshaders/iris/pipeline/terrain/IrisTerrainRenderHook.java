package net.irisshaders.iris.pipeline.terrain;

import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.blending.BlendModeStorage;
import net.irisshaders.iris.gl.blending.BufferBlendOverride;
import net.irisshaders.iris.gl.blending.DepthColorStorage;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.pipeline.VulkanTerrainPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.minecraft.client.Minecraft;
import net.vulkanmod.render.PipelineManager;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Orchestrates Iris terrain shader integration with VulkanMod's terrain draw path.
 *
 * Hooks into PipelineManager.setShaderGetter() to provide Iris's compiled terrain
 * pipelines instead of VulkanMod's defaults. Manages framebuffer binding, blend state,
 * texture binding, and uniform updates for both gbuffer and shadow passes.
 */
public class IrisTerrainRenderHook {

	private static final IrisTerrainRenderHook INSTANCE = new IrisTerrainRenderHook();

	// Standard Iris texture slot assignments (matches IrisSamplers convention)
	private static final int GL_TEXTURE_2D = 0x0DE1;
	private static final int SLOT_COLORTEX0 = 1;
	private static final int SLOT_NORMALS = 3;
	private static final int SLOT_SPECULAR = 4;
	private static final int SLOT_COLORTEX3 = 5;  // colortex3-8 occupy slots 5-10
	private static final int SLOT_COLORTEX1 = 11;
	private static final int SLOT_DEPTHTEX0 = 12;
	private static final int SLOT_DEPTHTEX1 = 13;
	private static final int SLOT_SHADOWTEX0 = 14;
	private static final int SLOT_SHADOWTEX1 = 15;
	private static final int SLOT_SHADOWCOLOR0 = 16;
	private static final int SLOT_SHADOWCOLOR1 = 17;
	private static final int SLOT_NOISETEX = 18;
	private static final int SLOT_COLORTEX2 = 19;

	// Minecraft's standard translucent blend factors
	private static final BlendModeOverride TRANSLUCENT_BLEND =
		new BlendModeOverride(new BlendMode(0x0302, 0x0303, 1, 0x0303));

	private final IrisTerrainPipelineCompiler compiler = new IrisTerrainPipelineCompiler();
	private VulkanTerrainPipeline terrainPipeline;
	private boolean active = false;
	private GlFramebuffer shadowFramebuffer;
	private TerrainRenderType lastRenderType;

	private IrisTerrainRenderHook() {}

	public static IrisTerrainRenderHook getInstance() {
		return INSTANCE;
	}

	public void activate(VulkanTerrainPipeline terrainPipeline) {
		this.terrainPipeline = terrainPipeline;
		compiler.compile(terrainPipeline);
		PipelineManager.setShaderGetter(this::getTerrainPipeline);
		active = true;
		Iris.logger.info("[IrisTerrainRenderHook] Activated");
	}

	public void deactivate() {
		active = false;
		terrainPipeline = null;
		shadowFramebuffer = null;
		PipelineManager.setDefaultShader();
		compiler.destroy();
		Iris.logger.info("[IrisTerrainRenderHook] Deactivated");
	}

	public void setShadowFramebuffer(GlFramebuffer fb) {
		this.shadowFramebuffer = fb;
	}

	public boolean isActive() {
		return active;
	}

	// --- Pipeline selection ---

	private GraphicsPipeline getTerrainPipeline(TerrainRenderType renderType) {
		if (shadowFramebuffer != null) {
			GraphicsPipeline pipeline = switch (renderType) {
				case SOLID, CUTOUT_MIPPED -> compiler.getShadowSolidPipeline();
				case CUTOUT -> compiler.getShadowCutoutPipeline();
				case TRANSLUCENT, TRIPWIRE -> compiler.getShadowSolidPipeline();
			};
			if (pipeline != null) return pipeline;
		}

		GraphicsPipeline pipeline = switch (renderType) {
			case SOLID, CUTOUT_MIPPED -> compiler.getSolidPipeline();
			case CUTOUT -> compiler.getCutoutPipeline();
			case TRANSLUCENT, TRIPWIRE -> compiler.getTranslucentPipeline();
		};

		if (pipeline == null) {
			pipeline = PipelineManager.getTerrainDirectShader(null);
			Iris.logger.warn("[IrisTerrainRenderHook] {} → FALLBACK (Iris pipeline null)", renderType);
		}
		return pipeline;
	}

	// --- Per-type lookups (eliminates switch duplication) ---

	private BlendModeOverride getBlendOverrideForType(TerrainRenderType renderType) {
		return switch (renderType) {
			case SOLID, CUTOUT_MIPPED -> terrainPipeline.getTerrainSolidBlendOverride();
			case CUTOUT -> terrainPipeline.getTerrainCutoutBlendOverride();
			case TRANSLUCENT, TRIPWIRE -> terrainPipeline.getTranslucentBlendOverride();
		};
	}

	private List<BufferBlendOverride> getBufferOverridesForType(TerrainRenderType renderType) {
		return switch (renderType) {
			case SOLID, CUTOUT_MIPPED -> terrainPipeline.getTerrainSolidBufferOverrides();
			case CUTOUT -> terrainPipeline.getTerrainCutoutBufferOverrides();
			case TRANSLUCENT, TRIPWIRE -> terrainPipeline.getTranslucentBufferOverrides();
		};
	}

	private GlFramebuffer getFramebufferForType(TerrainRenderType renderType) {
		return switch (renderType) {
			case SOLID, CUTOUT_MIPPED -> terrainPipeline.getTerrainSolidFramebuffer();
			case CUTOUT -> terrainPipeline.getTerrainCutoutFramebuffer();
			case TRANSLUCENT, TRIPWIRE -> terrainPipeline.getTranslucentFramebuffer();
		};
	}

	private boolean isTranslucentType(TerrainRenderType renderType) {
		return renderType == TerrainRenderType.TRANSLUCENT || renderType == TerrainRenderType.TRIPWIRE;
	}

	/**
	 * Applies the correct blend state for a terrain render type.
	 * Vulkan bakes blend state into the pipeline, so this must run
	 * BEFORE VulkanMod creates/selects the pipeline variant.
	 */
	private void applyBlendState(TerrainRenderType renderType) {
		BlendModeOverride blendOverride = getBlendOverrideForType(renderType);
		if (blendOverride != null) {
			blendOverride.apply();
		} else if (isTranslucentType(renderType)) {
			TRANSLUCENT_BLEND.apply();
		} else {
			BlendModeOverride.OFF.apply();
		}

		List<BufferBlendOverride> bufferOverrides = getBufferOverridesForType(renderType);
		if (bufferOverrides != null) {
			bufferOverrides.forEach(BufferBlendOverride::apply);
		}
	}

	// --- Terrain pass lifecycle ---

	public void beginTerrainPass(TerrainRenderType renderType, Matrix4f modelView, Matrix4f projection) {
		if (!active || terrainPipeline == null) return;
		this.lastRenderType = renderType;

		// Unlock Iris blend/depth overrides from previous passes
		if (DepthColorStorage.isDepthColorLocked()) DepthColorStorage.unlockDepthColor();
		if (BlendModeStorage.isBlendLocked()) BlendModeStorage.restoreBlend();

		applyBlendState(renderType);

		if (shadowFramebuffer != null) {
			shadowFramebuffer.bind();
		} else {
			bindGbufferFramebuffer(renderType);
		}

		compiler.updateUniforms(modelView, projection, shadowFramebuffer != null);
	}

	public void endTerrainPass() {
		BlendModeOverride.restore();
	}

	/**
	 * Re-binds the correct framebuffer after setupRenderState() overwrites it.
	 * VulkanMod intercepts bindWrite() calls from OutputStateShard and starts
	 * a new render pass with the vanilla framebuffer, discarding Iris's MRT setup.
	 */
	public void rebindAfterSetupRenderState() {
		if (!active || terrainPipeline == null || lastRenderType == null) return;

		// Shadow pass: setupRenderState may have switched to main FB
		if (shadowFramebuffer != null) {
			shadowFramebuffer.bind();
			return;
		}

		applyBlendState(lastRenderType);

		GlFramebuffer framebuffer = getFramebufferForType(lastRenderType);
		if (framebuffer != null) {
			framebuffer.bind();
		}

		restoreFullScreenViewport();
	}

	// --- Framebuffer binding ---

	private void bindGbufferFramebuffer(TerrainRenderType renderType) {
		GlFramebuffer framebuffer = getFramebufferForType(renderType);
		if (framebuffer != null) {
			framebuffer.bind();
		} else if (isTranslucentType(renderType)) {
			Iris.logger.warn("[IrisTerrainRenderHook] Translucent framebuffer is NULL");
		}
		restoreFullScreenViewport();
	}

	private void restoreFullScreenViewport() {
		com.mojang.blaze3d.pipeline.RenderTarget mainRT = Minecraft.getInstance().getMainRenderTarget();
		RenderSystem.viewport(0, 0, mainRT.width, mainRT.height);
	}

	// --- Texture binding ---

	/**
	 * Binds all Iris textures (shadow maps, PBR, gbuffer, depth, noise).
	 * Runs AFTER VTextureSelector.bindShaderTextures() to prevent overwrite.
	 */
	public void bindTerrainTextures(TerrainRenderType renderType) {
		if (!active || terrainPipeline == null || shadowFramebuffer != null) return;

		bindShadowTextures();
		bindPbrAndNoiseTextures();

		boolean isTranslucent = isTranslucentType(renderType);
		bindGbufferColorTextures(isTranslucent);
		bindCustomPackTextures();
		bindDepthTextures();
	}

	private void bindShadowTextures() {
		GlFramebuffer shadowFB = terrainPipeline.getShadowFramebuffer();
		if (shadowFB == null) return;

		int shadowDepth = shadowFB.getDepthAttachment();
		if (shadowDepth > 0) {
			IrisRenderSystem.bindTextureToUnit(GL_TEXTURE_2D, SLOT_SHADOWTEX0, shadowDepth);
			IrisRenderSystem.bindTextureToUnit(GL_TEXTURE_2D, SLOT_SHADOWTEX1, shadowDepth);
		}
		bindIfPositive(shadowFB.getColorAttachment(0), SLOT_SHADOWCOLOR0);
		bindIfPositive(shadowFB.getColorAttachment(1), SLOT_SHADOWCOLOR1);
	}

	private void bindPbrAndNoiseTextures() {
		WorldRenderingPipeline worldPipeline = Iris.getPipelineManager().getPipeline().orElse(null);
		if (worldPipeline == null) return;

		bindIfPositive(worldPipeline.getCurrentNormalTexture(), SLOT_NORMALS);
		bindIfPositive(worldPipeline.getCurrentSpecularTexture(), SLOT_SPECULAR);
		bindIfPositive(worldPipeline.getNoiseTextureId(), SLOT_NOISETEX);
	}

	private void bindGbufferColorTextures(boolean isTranslucent) {
		// colortex0/1/2 use non-contiguous slots to avoid colliding with block atlas (slot 0)
		int[] specialSlots = {SLOT_COLORTEX0, SLOT_COLORTEX1, SLOT_COLORTEX2};
		for (int i = 0; i < 3; i++) {
			int texId = isTranslucent
				? terrainPipeline.getGbufferTextureIdForTranslucent(i)
				: terrainPipeline.getGbufferTextureId(i);
			bindIfPositive(texId, specialSlots[i]);
		}

		// colortex3-8 → contiguous slots 5-10
		for (int i = 3; i <= 8; i++) {
			int texId = isTranslucent
				? terrainPipeline.getGbufferTextureIdForTranslucent(i)
				: terrainPipeline.getGbufferTextureId(i);
			bindIfPositive(texId, SLOT_COLORTEX3 + (i - 3));
		}
	}

	private void bindCustomPackTextures() {
		WorldRenderingPipeline worldPipeline = Iris.getPipelineManager().getPipeline().orElse(null);
		if (worldPipeline == null) return;

		// gaux1-4 are aliases for colortex4-7; shader packs can override them with custom textures
		String[][] gauxNames = {{"gaux1", "colortex4"}, {"gaux2", "colortex5"}, {"gaux3", "colortex6"}, {"gaux4", "colortex7"}};
		int[] gauxSlots = {6, 7, 8, 9};

		for (int i = 0; i < gauxNames.length; i++) {
			for (String name : gauxNames[i]) {
				int customTex = worldPipeline.getCustomGbufferTextureId(name);
				if (customTex > 0) {
					IrisRenderSystem.bindTextureToUnit(GL_TEXTURE_2D, gauxSlots[i], customTex);
					break;
				}
			}
		}
	}

	private void bindDepthTextures() {
		bindIfPositive(terrainPipeline.getDepthTextureId(), SLOT_DEPTHTEX0);
		bindIfPositive(terrainPipeline.getDepthTextureNoTranslucentsId(), SLOT_DEPTHTEX1);
	}

	private void bindIfPositive(int texId, int slot) {
		if (texId > 0) {
			IrisRenderSystem.bindTextureToUnit(GL_TEXTURE_2D, slot, texId);
		}
	}
}
