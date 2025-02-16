package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import it.unimi.dsi.fastutil.Function;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import net.caffeinemc.mods.sodium.client.render.immediate.CloudRenderer;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.compat.sodium.mixin.CloudRendererAccessor;
import net.irisshaders.iris.pathways.HandRenderer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ShaderAccess;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.programs.ShaderOverrides;
import net.irisshaders.iris.platform.IrisPlatformHelpers;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.CompiledShaderProgram;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShaderManager;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static net.irisshaders.iris.pipeline.programs.ShaderOverrides.isBlockEntities;

@Mixin(ShaderManager.class)
public abstract class MixinShaderManager_Overrides {
	@Shadow
	public abstract @Nullable CompiledShaderProgram getProgram(RenderPipeline shaderProgram);

	private static final Function<IrisRenderingPipeline, ShaderKey> FAKE_FUNCTION = p -> null;

	@Unique
	private static final Map<RenderPipeline, Function<IrisRenderingPipeline, ShaderKey>> coreShaderMap = new Object2ObjectArrayMap<>();
	private static final Map<RenderPipeline, Function<IrisRenderingPipeline, ShaderKey>> coreShaderMapShadow = new Object2ObjectArrayMap<>();

	static {
		coreShaderMap.put(RenderPipelines.ARMOR_CUTOUT_NO_CULL, MixinShaderManager_Overrides::getCutout);
		coreShaderMap.put(RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL, MixinShaderManager_Overrides::getCutout);
		coreShaderMap.put(RenderPipelines.ENTITY_CUTOUT, MixinShaderManager_Overrides::getCutout);
		coreShaderMap.put(RenderPipelines.ENTITY_CUTOUT_NO_CULL, MixinShaderManager_Overrides::getCutout);
		coreShaderMap.put(RenderPipelines.ENTITY_CUTOUT_NO_CULL_Z_OFFSET, MixinShaderManager_Overrides::getCutout);
		coreShaderMap.put(RenderPipelines.ENTITY_SMOOTH_CUTOUT, MixinShaderManager_Overrides::getCutout);
		coreShaderMap.put(RenderPipelines.ENTITY_DECAL, MixinShaderManager_Overrides::getCutout);
		coreShaderMap.put(RenderPipelines.ENTITY_SOLID, MixinShaderManager_Overrides::getSolid);
		coreShaderMap.put(RenderPipelines.ENTITY_SHADOW, MixinShaderManager_Overrides::getTranslucent);
		coreShaderMap.put(RenderPipelines.EYES, t -> ShaderKey.ENTITIES_EYES);
		coreShaderMap.put(RenderPipelines.WEATHER_DEPTH_WRITE, t -> ShaderKey.WEATHER);
		coreShaderMap.put(RenderPipelines.WEATHER_NO_DEPTH_WRITE, t -> ShaderKey.WEATHER);
		coreShaderMap.put(RenderPipelines.OPAQUE_PARTICLE, t -> ShaderKey.PARTICLES);
		coreShaderMap.put(RenderPipelines.TRANSLUCENT_PARTICLE, t -> ShaderKey.PARTICLES_TRANS);
		coreShaderMap.put(RenderPipelines.CRUMBLING, t -> ShaderKey.CRUMBLING);
		coreShaderMap.put(RenderPipelines.DRAGON_RAYS, t -> ShaderKey.LIGHTNING);
		coreShaderMap.put(RenderPipelines.DRAGON_RAYS_DEPTH, t -> ShaderKey.LIGHTNING);
		coreShaderMap.put(RenderPipelines.LIGHTNING, t -> ShaderKey.LIGHTNING);
		coreShaderMap.put(RenderPipelines.END_GATEWAY, t -> ShaderKey.BLOCK_ENTITY);
		coreShaderMap.put(RenderPipelines.LEASH, t -> ShaderKey.LEASH);
		coreShaderMap.put(RenderPipelines.END_PORTAL, t -> ShaderKey.BLOCK_ENTITY);
		coreShaderMap.put(RenderPipelines.WATER_MASK, t -> ShaderKey.BASIC);
		coreShaderMap.put(RenderPipelines.TEXT, t -> ShaderKey.TEXT);
		coreShaderMap.put(RenderPipelines.TEXT_INTENSITY, t -> ShaderKey.TEXT_INTENSITY);
		coreShaderMap.put(RenderPipelines.TEXT_SEE_THROUGH, t -> ShaderKey.TEXT);
		coreShaderMap.put(RenderPipelines.TEXT_POLYGON_OFFSET, t -> ShaderKey.TEXT);
		coreShaderMap.put(RenderPipelines.TEXT_BACKGROUND, t -> ShaderKey.TEXT_BG);
		coreShaderMap.put(RenderPipelines.TEXT_BACKGROUND_SEE_THROUGH, t -> ShaderKey.TEXT_BG);
		coreShaderMap.put(RenderPipelines.TEXT_INTENSITY_SEE_THROUGH, t -> ShaderKey.TEXT);
		coreShaderMap.put(RenderPipelines.WORLD_BORDER, t -> ShaderKey.TEXTURED);
		coreShaderMap.put(RenderPipelines.SOLID, t -> ShaderKey.TERRAIN_SOLID);
		coreShaderMap.put(RenderPipelines.CUTOUT, t -> ShaderKey.TERRAIN_CUTOUT);
		coreShaderMap.put(RenderPipelines.TRANSLUCENT, t -> ShaderKey.TERRAIN_TRANSLUCENT);
		coreShaderMap.put(RenderPipelines.TRIPWIRE, t -> ShaderKey.TERRAIN_TRANSLUCENT);
		coreShaderMap.put(RenderPipelines.DRAGON_EXPLOSION_ALPHA, t -> ShaderKey.ENTITIES_ALPHA);
		coreShaderMap.put(RenderPipelines.TRANSLUCENT_MOVING_BLOCK, t -> ShaderKey.TERRAIN_TRANSLUCENT);
		coreShaderMap.put(RenderPipelines.ARMOR_TRANSLUCENT, MixinShaderManager_Overrides::getTranslucent);
		coreShaderMap.put(RenderPipelines.ENTITY_NO_OUTLINE, MixinShaderManager_Overrides::getTranslucent);
		coreShaderMap.put(RenderPipelines.BEACON_BEAM_OPAQUE, t -> ShaderKey.BEACON);
		coreShaderMap.put(RenderPipelines.BEACON_BEAM_TRANSLUCENT, t -> ShaderKey.BEACON);
		coreShaderMap.put(RenderPipelines.BREEZE_WIND, MixinShaderManager_Overrides::getTranslucent);
		coreShaderMap.put(RenderPipelines.GLINT, t -> ShaderKey.GLINT);
		coreShaderMap.put(RenderPipelines.LINES, t -> ShaderKey.LINES);
		coreShaderMap.put(RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD, MixinShaderManager_Overrides::getSolid);
		coreShaderMap.put(RenderPipelines.ENTITY_TRANSLUCENT, MixinShaderManager_Overrides::getTranslucent);
		coreShaderMap.put(RenderPipelines.ITEM_ENTITY_TRANSLUCENT_CULL, MixinShaderManager_Overrides::getTranslucent);
		coreShaderMap.put(RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE, i -> ShaderKey.ENTITIES_EYES_TRANS);
		coreShaderMap.put(CloudRenderer.CLOUDS_FULL, i -> ShaderKey.CLOUDS_SODIUM);
		coreShaderMap.put(CloudRenderer.CLOUDS_FLAT, i -> ShaderKey.CLOUDS_SODIUM);
		coreShaderMap.put(RenderPipelines.CELESTIAL, i -> ShaderKey.SKY_TEXTURED_COLOR);
		coreShaderMap.put(RenderPipelines.SUNRISE_SUNSET, i -> ShaderKey.SKY_BASIC_COLOR);
		coreShaderMap.put(RenderPipelines.SKY, i -> ShaderKey.SKY_BASIC);
		coreShaderMap.put(RenderPipelines.STARS, i -> ShaderKey.SKY_BASIC);



		coreShaderMapShadow.put(RenderPipelines.ARMOR_CUTOUT_NO_CULL, t -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL, t -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.ENTITY_CUTOUT, t -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.END_SKY, t -> ShaderKey.TEXTURED);
		coreShaderMapShadow.put(RenderPipelines.SOLID, t -> ShaderKey.SHADOW_TERRAIN_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.CUTOUT, t -> ShaderKey.SHADOW_TERRAIN_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.TRANSLUCENT, t -> ShaderKey.SHADOW_TRANSLUCENT);
		coreShaderMapShadow.put(RenderPipelines.TRIPWIRE, t -> ShaderKey.SHADOW_TRANSLUCENT);
		coreShaderMapShadow.put(RenderPipelines.ENTITY_CUTOUT_NO_CULL, t -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.ENTITY_NO_OUTLINE, t -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.BREEZE_WIND, t -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.ENTITY_CUTOUT_NO_CULL_Z_OFFSET, t -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.ENTITY_SMOOTH_CUTOUT, t -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.ENTITY_DECAL, t -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.ENTITY_SOLID, t -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.ENTITY_SHADOW, t -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.EYES, t -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.WEATHER_DEPTH_WRITE, t -> ShaderKey.SHADOW_PARTICLES);
		coreShaderMapShadow.put(RenderPipelines.WEATHER_NO_DEPTH_WRITE, t -> ShaderKey.SHADOW_PARTICLES);
		coreShaderMapShadow.put(RenderPipelines.OPAQUE_PARTICLE, t -> ShaderKey.SHADOW_PARTICLES);
		coreShaderMapShadow.put(RenderPipelines.TRANSLUCENT_PARTICLE, t -> ShaderKey.SHADOW_PARTICLES);
		coreShaderMapShadow.put(RenderPipelines.CRUMBLING, t -> ShaderKey.SHADOW_BASIC);
		coreShaderMapShadow.put(RenderPipelines.DRAGON_RAYS, t -> ShaderKey.SHADOW_LIGHTNING);
		coreShaderMapShadow.put(RenderPipelines.DRAGON_RAYS_DEPTH, t -> ShaderKey.SHADOW_LIGHTNING);
		coreShaderMapShadow.put(RenderPipelines.LIGHTNING, t -> ShaderKey.SHADOW_LIGHTNING);
		coreShaderMapShadow.put(RenderPipelines.END_GATEWAY, t -> ShaderKey.SHADOW_BLOCK);
		coreShaderMapShadow.put(RenderPipelines.LEASH, t -> ShaderKey.SHADOW_LEASH);
		coreShaderMapShadow.put(RenderPipelines.END_PORTAL, t -> ShaderKey.SHADOW_BLOCK);
		coreShaderMapShadow.put(RenderPipelines.WATER_MASK, t -> ShaderKey.SHADOW_BASIC);
		coreShaderMapShadow.put(RenderPipelines.TEXT, t -> ShaderKey.SHADOW_TEXT);
		coreShaderMapShadow.put(RenderPipelines.TEXT_INTENSITY, t -> ShaderKey.SHADOW_TEXT_INTENSITY);
		coreShaderMapShadow.put(RenderPipelines.TEXT_SEE_THROUGH, t -> ShaderKey.SHADOW_TEXT);
		coreShaderMapShadow.put(RenderPipelines.TEXT_POLYGON_OFFSET, t -> ShaderKey.SHADOW_TEXT);
		coreShaderMapShadow.put(RenderPipelines.TEXT_BACKGROUND, t -> ShaderKey.SHADOW_TEXT_BG);
		coreShaderMapShadow.put(RenderPipelines.TEXT_BACKGROUND_SEE_THROUGH, t -> ShaderKey.SHADOW_TEXT_BG);
		coreShaderMapShadow.put(RenderPipelines.TEXT_INTENSITY_SEE_THROUGH, t -> ShaderKey.SHADOW_TEXT);
		coreShaderMapShadow.put(RenderPipelines.DRAGON_EXPLOSION_ALPHA, t -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.TRANSLUCENT_MOVING_BLOCK, t -> ShaderKey.SHADOW_TERRAIN_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.ARMOR_TRANSLUCENT, t -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.GLINT, t -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.LINES, t -> ShaderKey.LINES);
		coreShaderMapShadow.put(RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD, t -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.ENTITY_TRANSLUCENT, t -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.ITEM_ENTITY_TRANSLUCENT_CULL, t -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE, i -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(CloudRenderer.CLOUDS_FULL, i -> ShaderKey.SHADOW_CLOUDS);
		coreShaderMapShadow.put(CloudRenderer.CLOUDS_FLAT, i -> ShaderKey.SHADOW_CLOUDS);
		coreShaderMapShadow.put(RenderPipelines.CELESTIAL, i -> ShaderKey.SHADOW_TEX);
		coreShaderMapShadow.put(RenderPipelines.SUNRISE_SUNSET, i -> ShaderKey.SHADOW_BASIC);
		coreShaderMapShadow.put(RenderPipelines.SKY, i -> ShaderKey.SHADOW_BASIC);
		coreShaderMapShadow.put(RenderPipelines.STARS, i -> ShaderKey.SHADOW_BASIC);
	}

	private static ShaderKey getCutout(Object p) {
		IrisRenderingPipeline pipeline = (IrisRenderingPipeline) p;

		if (HandRenderer.INSTANCE.isActive()) {
			return (HandRenderer.INSTANCE.isRenderingSolid() ? ShaderKey.HAND_CUTOUT_DIFFUSE : ShaderKey.HAND_WATER_DIFFUSE);
		} else if (isBlockEntities(pipeline)) {
			return (ShaderKey.BLOCK_ENTITY_DIFFUSE);
		} else {
			return (ShaderKey.ENTITIES_CUTOUT_DIFFUSE);
		}
	}

	private static ShaderKey getSolid(Object p) {
		IrisRenderingPipeline pipeline = (IrisRenderingPipeline) p;

		if (HandRenderer.INSTANCE.isActive()) {
			return (HandRenderer.INSTANCE.isRenderingSolid() ? ShaderKey.HAND_CUTOUT : ShaderKey.HAND_TRANSLUCENT);
		} else if (isBlockEntities(pipeline)) {
			return (ShaderKey.BLOCK_ENTITY);
		} else {
			return (ShaderKey.ENTITIES_SOLID);
		}
	}

	private static ShaderKey getTranslucent(Object p) {
		IrisRenderingPipeline pipeline = (IrisRenderingPipeline) p;

		if (HandRenderer.INSTANCE.isActive()) {
			return (HandRenderer.INSTANCE.isRenderingSolid() ? ShaderKey.HAND_CUTOUT_DIFFUSE : ShaderKey.HAND_WATER_DIFFUSE);
		} else if (isBlockEntities(pipeline)) {
			return (ShaderKey.BLOCK_ENTITY);
		} else {
			return (ShaderKey.ENTITIES_TRANSLUCENT);
		}
	}

	private Set<RenderPipeline> missingShaders = new HashSet<>();

	@Inject(method = "getProgram", at = @At(value = "HEAD"), cancellable = true)
	private void redirectIrisProgram(RenderPipeline shaderProgram, CallbackInfoReturnable<CompiledShaderProgram> cir) {
		WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();

		if (pipeline instanceof IrisRenderingPipeline irisPipeline && irisPipeline.shouldOverrideShaders()) {
			RenderPipeline newProgram = shaderProgram;


			CompiledShaderProgram program = override(irisPipeline, newProgram);

			if (program != null) {
				cir.setReturnValue(program);
			} else if (missingShaders.add(shaderProgram)) {
				Iris.logger.error("Missing program " + shaderProgram.getLocation() + " in override list. This is not a critical problem, but it could lead to weird rendering.", new Throwable());
			}
		} else {
			/*if (shaderProgram == ShaderAccess.MEKANISM_FLAME) {
				cir.setReturnValue(getProgram(CoreShaders.POSITION_TEX_COLOR));
			} else if (shaderProgram == ShaderAccess.MEKASUIT) {
				cir.setReturnValue(getProgram(CoreShaders.RENDERTYPE_ENTITY_CUTOUT));
			} else if (shaderProgram == ShaderAccess.IE_COMPAT) {
				// TODO when IE updates
			} else if (shaderProgram == ShaderAccess.TRANSLUCENT_PARTICLE_SHADER) {
				cir.setReturnValue(getProgram(CoreShaders.PARTICLE));
			} else if (shaderProgram == ShaderAccess.WEATHER_SHADER) {
				cir.setReturnValue(getProgram(CoreShaders.PARTICLE));
			}*/
		}
	}

	private static CompiledShaderProgram override(IrisRenderingPipeline pipeline, RenderPipeline shaderProgram) {
		ShaderKey shaderKey = convertToShaderKey(pipeline, shaderProgram);

		return shaderKey == null ? null : pipeline.getShaderMap().getShader(shaderKey);
	}

	private static ShaderKey convertToShaderKey(IrisRenderingPipeline pipeline, RenderPipeline shaderProgram) {
		return ShadowRenderingState.areShadowsCurrentlyBeingRendered()? coreShaderMapShadow.getOrDefault(shaderProgram, FAKE_FUNCTION).apply(pipeline) : coreShaderMap.getOrDefault(shaderProgram, FAKE_FUNCTION).apply(pipeline);
	}
}
