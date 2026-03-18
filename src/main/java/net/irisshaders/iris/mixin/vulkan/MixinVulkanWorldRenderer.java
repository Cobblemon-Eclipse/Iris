package net.irisshaders.iris.mixin.vulkan;

import net.irisshaders.iris.pipeline.terrain.IrisTerrainRenderHook;
import net.minecraft.client.renderer.RenderType;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin on VulkanMod's WorldRenderer to intercept terrain rendering.
 */
@Mixin(value = WorldRenderer.class, remap = false)
public class MixinVulkanWorldRenderer {

	@Inject(method = "renderSectionLayer", at = @At("HEAD"))
	private void iris$beforeTerrainDraw(RenderType renderType, double camX, double camY, double camZ,
										Matrix4f modelView, Matrix4f projection, CallbackInfo ci) {
		IrisTerrainRenderHook hook = IrisTerrainRenderHook.getInstance();
		if (hook.isActive()) {
			TerrainRenderType terrainRenderType = TerrainRenderType.get(renderType);
			hook.beginTerrainPass(terrainRenderType, modelView, projection);
		}
	}

	/**
	 * Re-bind Iris framebuffer and blend state AFTER renderType.setupRenderState().
	 *
	 * setupRenderState() includes OutputStateShard (e.g. TRANSLUCENT_TARGET) which calls
	 * RenderTarget.bindWrite() → VulkanMod intercepts → starts a new render pass with
	 * Minecraft's vanilla framebuffer (1 color attachment), overwriting the Iris gbuffer
	 * render pass (2+ attachments). Without this re-bind, the VkPipeline is compiled
	 * against the wrong render pass and MRT outputs (colortex3 etc.) are silently discarded.
	 *
	 * We inject before PipelineManager.getTerrainShader() (a VulkanMod method, safe with
	 * remap=false) rather than after setupRenderState() (vanilla, needs remapping).
	 * This runs after setupRenderState() and before bindGraphicsPipeline().
	 */
	@Inject(method = "renderSectionLayer",
		at = @At(value = "INVOKE",
			target = "Lnet/vulkanmod/render/PipelineManager;getTerrainShader(Lnet/vulkanmod/render/vertex/TerrainRenderType;)Lnet/vulkanmod/vulkan/shader/GraphicsPipeline;"))
	private void iris$beforeGetTerrainShader(RenderType renderType, double camX, double camY, double camZ,
											 Matrix4f modelView, Matrix4f projection, CallbackInfo ci) {
		IrisTerrainRenderHook hook = IrisTerrainRenderHook.getInstance();
		if (hook.isActive()) {
			hook.rebindAfterSetupRenderState();
		}
	}

	/**
	 * Bind Iris textures AFTER VTextureSelector.bindShaderTextures() to prevent overwrite.
	 * bindShaderTextures() reads from RenderSystem.getShaderTexture() for slots < 12,
	 * which overwrites our texture bindings with stale values or the missing texture sprite.
	 */
	@Inject(method = "renderSectionLayer",
		at = @At(value = "INVOKE",
			target = "Lnet/vulkanmod/vulkan/texture/VTextureSelector;bindShaderTextures(Lnet/vulkanmod/vulkan/shader/Pipeline;)V",
			shift = At.Shift.AFTER))
	private void iris$afterBindShaderTextures(RenderType renderType, double camX, double camY, double camZ,
											  Matrix4f modelView, Matrix4f projection, CallbackInfo ci) {
		IrisTerrainRenderHook hook = IrisTerrainRenderHook.getInstance();
		if (hook.isActive()) {
			TerrainRenderType terrainRenderType = TerrainRenderType.get(renderType);
			hook.bindTerrainTextures(terrainRenderType);
		}
	}

	@Inject(method = "renderSectionLayer", at = @At("RETURN"))
	private void iris$afterTerrainDraw(RenderType renderType, double camX, double camY, double camZ,
									   Matrix4f modelView, Matrix4f projection, CallbackInfo ci) {
		IrisTerrainRenderHook hook = IrisTerrainRenderHook.getInstance();
		if (hook.isActive()) {
			hook.endTerrainPass();
		}
	}
}
