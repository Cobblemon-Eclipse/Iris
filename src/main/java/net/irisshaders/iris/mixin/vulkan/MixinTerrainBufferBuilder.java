package net.irisshaders.iris.mixin.vulkan;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.minecraft.world.level.block.state.BlockState;
import net.vulkanmod.render.vertex.TerrainBufferBuilder;
import net.vulkanmod.render.vertex.VertexBuilder;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects Iris block entity IDs into VulkanMod's terrain vertex buffer.
 *
 * VulkanMod's COMPRESSED_TERRAIN format has 4 unused bytes at offset 16-19
 * (declared as UV2 but zero-filled since lightmap comes from position.w).
 * We repurpose these bytes to store the shader pack's block entity ID
 * (mc_Entity), which terrain shaders need to identify block types like
 * water (ID 32000), ice, glass, etc.
 *
 * Without this, mc_Entity is always -1, so shader packs can't apply
 * water-specific effects (waves, reflections, material IDs for composites).
 */
@Mixin(value = TerrainBufferBuilder.class, remap = false)
public class MixinTerrainBufferBuilder {

	@Shadow protected long bufferPtr;
	@Shadow protected int nextElementByte;
	@Shadow protected VertexBuilder vertexBuilder;

	/**
	 * The Iris block entity ID for the current block being compiled.
	 * Set by setBlockAttributes() per-block, then written into UV2 bytes
	 * for every vertex of that block's quads.
	 * Value of -1 means no shader pack mapping (skip write, leave as zero).
	 */
	@Unique
	private short iris$currentBlockId = -1;

	@Unique
	private static boolean iris$loggedOnce = false;

	/**
	 * Capture the BlockState and look up its shader pack entity ID.
	 * Called once per block during chunk section compilation.
	 */
	@Inject(method = "setBlockAttributes", at = @At("HEAD"))
	private void iris$captureBlockState(BlockState blockState, CallbackInfo ci) {
		Object2IntMap<BlockState> idMap = WorldRenderingSettings.INSTANCE.getBlockStateIds();
		if (idMap != null) {
			iris$currentBlockId = (short) idMap.getOrDefault(blockState, -1);
			if (!iris$loggedOnce && iris$currentBlockId != -1) {
				iris$loggedOnce = true;
				net.irisshaders.iris.Iris.logger.info(
					"[ENTITY_ID] First block ID write: block={} id={} mapSize={}",
					blockState, iris$currentBlockId, idMap.size());
			}
		} else {
			iris$currentBlockId = -1;
			if (!iris$loggedOnce) {
				iris$loggedOnce = true;
				net.irisshaders.iris.Iris.logger.warn(
					"[ENTITY_ID] blockStateIds map is NULL during chunk build for block={}",
					blockState);
			}
		}
	}

	/**
	 * After each vertex is written, overwrite the UV2 bytes (offset 16-19)
	 * with the block entity ID. The CompressedVertexBuilder zeros these bytes,
	 * and we replace them with meaningful data for the shader.
	 *
	 * UV2 layout (4 bytes at offset 16):
	 *   short[0] (offset 16-17): block entity ID (mc_Entity.x)
	 *   short[1] (offset 18-19): reserved (mc_Entity.y), always 0
	 */
	@Inject(method = "vertex", at = @At("RETURN"))
	private void iris$writeEntityId(float x, float y, float z, int color,
			float u, float v, int light, int packedNormal, CallbackInfo ci) {
		if (iris$currentBlockId != -1) {
			// endVertex() already advanced nextElementByte, so go back one stride
			long ptr = this.bufferPtr + this.nextElementByte - vertexBuilder.getStride();
			MemoryUtil.memPutShort(ptr + 16, iris$currentBlockId);
			// offset 18-19 stays zero (written by CompressedVertexBuilder)
		}
	}
}
