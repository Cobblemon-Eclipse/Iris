package net.irisshaders.iris.pipeline;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

public enum WorldRenderingPhase {
	NONE,
	SKY,
	SUNSET,
	CUSTOM_SKY,
	SUN,
	MOON,
	STARS,
	VOID,
	TERRAIN_SOLID,
	TERRAIN_CUTOUT_MIPPED,
	TERRAIN_CUTOUT,
	ENTITIES,
	BLOCK_ENTITIES,
	DESTROY,
	OUTLINE,
	DEBUG,
	HAND_SOLID,
	TERRAIN_TRANSLUCENT,
	TRIPWIRE,
	PARTICLES,
	CLOUDS,
	RAIN_SNOW,
	WORLD_BORDER,
	HAND_TRANSLUCENT;

	public static WorldRenderingPhase fromTerrainRenderType(ChunkSectionLayer renderType) {
		if (renderType == ChunkSectionLayer.SOLID) {
			return WorldRenderingPhase.TERRAIN_SOLID;
		} else if (renderType == ChunkSectionLayer.CUTOUT) {
			return WorldRenderingPhase.TERRAIN_CUTOUT;
		} else if (renderType == ChunkSectionLayer.CUTOUT_MIPPED) {
			return WorldRenderingPhase.TERRAIN_CUTOUT_MIPPED;
		} else if (renderType == ChunkSectionLayer.TRANSLUCENT) {
			return WorldRenderingPhase.TERRAIN_TRANSLUCENT;
		} else if (renderType == ChunkSectionLayer.TRIPWIRE) {
			return WorldRenderingPhase.TRIPWIRE;
		} else {
			throw new IllegalStateException("Illegal render type!");
		}
	}
}
