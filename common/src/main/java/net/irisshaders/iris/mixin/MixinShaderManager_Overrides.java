package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import it.unimi.dsi.fastutil.Function;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import net.caffeinemc.mods.sodium.client.render.immediate.CloudRenderer;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.compat.sodium.mixin.CloudRendererAccessor;
import net.irisshaders.iris.mixinterface.ShaderInstanceInterface;
import net.irisshaders.iris.pathways.HandRenderer;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ShaderAccess;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.programs.ShaderOverrides;
import net.irisshaders.iris.platform.IrisPlatformHelpers;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.irisshaders.iris.vertices.ImmediateState;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static net.irisshaders.iris.compat.SkipList.ALWAYS;
import static net.irisshaders.iris.compat.SkipList.NONE;
import static net.irisshaders.iris.compat.SkipList.shouldSkipList;
import static net.irisshaders.iris.pipeline.programs.ShaderOverrides.isBlockEntities;

@Mixin(GlDevice.class)
public abstract class MixinShaderManager_Overrides {
	private static final Function<IrisRenderingPipeline, ShaderKey> FAKE_FUNCTION = p -> null;

	@Unique
	private static final Map<RenderPipeline, Function<IrisRenderingPipeline, ShaderKey>> coreShaderMap = new Object2ObjectArrayMap<>();
	private static final Map<RenderPipeline, Function<IrisRenderingPipeline, ShaderKey>> coreShaderMapShadow = new Object2ObjectArrayMap<>();

	static {
		coreShaderMap.put(RenderPipelines.SOLID, p -> ShaderKey.TERRAIN_SOLID);
		coreShaderMap.put(RenderPipelines.CUTOUT, p -> ShaderKey.TERRAIN_CUTOUT);
		coreShaderMap.put(RenderPipelines.CUTOUT_MIPPED, p -> ShaderKey.TERRAIN_CUTOUT);
		coreShaderMap.put(RenderPipelines.TRANSLUCENT, p -> ShaderKey.TERRAIN_TRANSLUCENT);
		coreShaderMap.put(RenderPipelines.TRANSLUCENT_MOVING_BLOCK, p -> ShaderKey.TERRAIN_TRANSLUCENT);
		coreShaderMap.put(RenderPipelines.TRIPWIRE, p -> ShaderKey.TERRAIN_TRANSLUCENT);
		coreShaderMap.put(RenderPipelines.ENTITY_CUTOUT, p -> getCutout(p));
		coreShaderMap.put(RenderPipelines.ENTITY_CUTOUT_NO_CULL, p -> getCutout(p));
		coreShaderMap.put(RenderPipelines.ENTITY_CUTOUT_NO_CULL_Z_OFFSET, p -> getCutout(p));
		coreShaderMap.put(RenderPipelines.ENTITY_SMOOTH_CUTOUT, p -> getCutout(p));
		coreShaderMap.put(RenderPipelines.ITEM_ENTITY_TRANSLUCENT_CULL, p -> getTranslucent(p));
		coreShaderMap.put(RenderPipelines.ENTITY_TRANSLUCENT, p -> getTranslucent(p));
		coreShaderMap.put(RenderPipelines.ENTITY_SHADOW, p -> getTranslucent(p));
		coreShaderMap.put(RenderPipelines.ENTITY_NO_OUTLINE, p -> getTranslucent(p));
		coreShaderMap.put(RenderPipelines.ENTITY_DECAL, p -> getCutout(p));
		coreShaderMap.put(RenderPipelines.LINES, p -> ShaderKey.LINES);
		coreShaderMap.put(RenderPipelines.LINE_STRIP, p -> ShaderKey.LINES);
		coreShaderMap.put(RenderPipelines.SECONDARY_BLOCK_OUTLINE, p -> ShaderKey.LINES);
		coreShaderMap.put(RenderPipelines.STARS, p -> ShaderKey.SKY_BASIC);
		coreShaderMap.put(RenderPipelines.SUNRISE_SUNSET, p -> ShaderKey.SKY_BASIC_COLOR);
		coreShaderMap.put(RenderPipelines.SKY, p -> ShaderKey.SKY_BASIC);
		coreShaderMap.put(RenderPipelines.CELESTIAL, p -> ShaderKey.SKY_TEXTURED_COLOR);
		coreShaderMap.put(RenderPipelines.OPAQUE_PARTICLE, p -> ShaderKey.PARTICLES);
		coreShaderMap.put(RenderPipelines.TRANSLUCENT_PARTICLE, p -> ShaderKey.PARTICLES_TRANS);
		coreShaderMap.put(RenderPipelines.WATER_MASK, p -> ShaderKey.BASIC);
		coreShaderMap.put(RenderPipelines.GLINT, p -> ShaderKey.GLINT);
		coreShaderMap.put(RenderPipelines.ARMOR_CUTOUT_NO_CULL, p -> getCutout(p));
		coreShaderMap.put(RenderPipelines.EYES, p -> ShaderKey.ENTITIES_EYES);
		coreShaderMap.put(RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE, p -> ShaderKey.ENTITIES_EYES_TRANS);
		coreShaderMap.put(RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL, p -> getCutout(p));
		coreShaderMap.put(RenderPipelines.ARMOR_TRANSLUCENT, p -> getTranslucent(p));
		coreShaderMap.put(RenderPipelines.BREEZE_WIND, p -> getTranslucent(p));
		coreShaderMap.put(RenderPipelines.ENTITY_SOLID, p -> getSolid(p));
		coreShaderMap.put(RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD, p -> getSolid(p));
		coreShaderMap.put(RenderPipelines.END_GATEWAY, p -> ShaderKey.BLOCK_ENTITY);
		coreShaderMap.put(RenderPipelines.ENERGY_SWIRL, p -> ShaderKey.ENTITIES_CUTOUT);
		coreShaderMap.put(RenderPipelines.LIGHTNING, p -> ShaderKey.LIGHTNING);
		coreShaderMap.put(RenderPipelines.DRAGON_RAYS, p -> ShaderKey.LIGHTNING);
		coreShaderMap.put(RenderPipelines.DRAGON_RAYS_DEPTH, p -> ShaderKey.LIGHTNING);
		coreShaderMap.put(RenderPipelines.BEACON_BEAM_OPAQUE, p -> ShaderKey.BEACON);
		coreShaderMap.put(RenderPipelines.BEACON_BEAM_TRANSLUCENT, p -> ShaderKey.BEACON);
		coreShaderMap.put(RenderPipelines.END_PORTAL, p -> ShaderKey.BLOCK_ENTITY);
		coreShaderMap.put(RenderPipelines.END_SKY, p -> ShaderKey.SKY_TEXTURED);
		coreShaderMap.put(RenderPipelines.WEATHER_DEPTH_WRITE, p -> ShaderKey.WEATHER);
		coreShaderMap.put(RenderPipelines.WEATHER_NO_DEPTH_WRITE, p -> ShaderKey.WEATHER);
		coreShaderMap.put(RenderPipelines.TEXT, p -> ShaderKey.TEXT);
		coreShaderMap.put(RenderPipelines.TEXT_POLYGON_OFFSET, p -> ShaderKey.TEXT);
		coreShaderMap.put(RenderPipelines.TEXT_SEE_THROUGH, p -> ShaderKey.TEXT);
		coreShaderMap.put(RenderPipelines.TEXT_INTENSITY_SEE_THROUGH, p -> ShaderKey.TEXT_INTENSITY);
		coreShaderMap.put(RenderPipelines.TEXT_BACKGROUND, p -> ShaderKey.TEXT_BG);
		coreShaderMap.put(RenderPipelines.TEXT_BACKGROUND_SEE_THROUGH, p -> ShaderKey.TEXT_BG);
		coreShaderMap.put(RenderPipelines.TEXT_INTENSITY, p -> ShaderKey.TEXT_INTENSITY);
		coreShaderMap.put(RenderPipelines.DRAGON_EXPLOSION_ALPHA, p -> ShaderKey.ENTITIES_ALPHA);
		coreShaderMap.put(RenderPipelines.CRUMBLING, p -> ShaderKey.CRUMBLING);
		coreShaderMap.put(RenderPipelines.LEASH, p -> ShaderKey.LEASH);
		coreShaderMap.put(CloudRenderer.CLOUDS_FLAT, p -> ShaderKey.CLOUDS_SODIUM);
		coreShaderMap.put(CloudRenderer.CLOUDS_FULL, p -> ShaderKey.CLOUDS_SODIUM);
		coreShaderMap.put(RenderPipelines.DEBUG_LINE_STRIP, p -> ShaderKey.BASIC_COLOR);

		coreShaderMapShadow.put(RenderPipelines.SOLID, p -> ShaderKey.SHADOW_TERRAIN_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.CUTOUT, p -> ShaderKey.SHADOW_TERRAIN_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.CUTOUT_MIPPED, p -> ShaderKey.SHADOW_TERRAIN_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.TRANSLUCENT, p -> ShaderKey.SHADOW_TRANSLUCENT);
		coreShaderMapShadow.put(RenderPipelines.TRANSLUCENT_MOVING_BLOCK, p -> ShaderKey.SHADOW_TRANSLUCENT);
		coreShaderMapShadow.put(RenderPipelines.TRIPWIRE, p -> ShaderKey.SHADOW_TRANSLUCENT);
		coreShaderMapShadow.put(RenderPipelines.ENTITY_CUTOUT, p -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.ARMOR_CUTOUT_NO_CULL, p -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.ITEM_ENTITY_TRANSLUCENT_CULL, p -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL, p -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.ENTITY_SOLID, p -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD, p -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.ENTITY_CUTOUT_NO_CULL, p -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.ENTITY_CUTOUT_NO_CULL_Z_OFFSET, p -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.ENTITY_SMOOTH_CUTOUT, p -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.ENTITY_TRANSLUCENT, p -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE, p -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.BREEZE_WIND, p -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.EYES, p -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.DRAGON_EXPLOSION_ALPHA, p -> ShaderKey.SHADOW_ENTITIES_CUTOUT);

		coreShaderMapShadow.put(RenderPipelines.ENTITY_NO_OUTLINE, p -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.ENERGY_SWIRL, p -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.ENTITY_DECAL, p -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.GLINT, p -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.WEATHER_DEPTH_WRITE, p -> ShaderKey.SHADOW_PARTICLES);
		coreShaderMapShadow.put(RenderPipelines.WEATHER_NO_DEPTH_WRITE, p -> ShaderKey.SHADOW_PARTICLES);
		coreShaderMapShadow.put(RenderPipelines.OPAQUE_PARTICLE, p -> ShaderKey.SHADOW_PARTICLES);
		coreShaderMapShadow.put(RenderPipelines.TRANSLUCENT_PARTICLE, p -> ShaderKey.SHADOW_PARTICLES);
		coreShaderMapShadow.put(RenderPipelines.LINES, p -> ShaderKey.SHADOW_LINES);
		coreShaderMapShadow.put(RenderPipelines.LINE_STRIP, p -> ShaderKey.SHADOW_LINES);
		coreShaderMapShadow.put(RenderPipelines.LEASH, p -> ShaderKey.SHADOW_LEASH);
		coreShaderMapShadow.put(RenderPipelines.SECONDARY_BLOCK_OUTLINE, p -> ShaderKey.SHADOW_LINES);
		coreShaderMapShadow.put(RenderPipelines.TEXT, p -> ShaderKey.SHADOW_TEXT);
		coreShaderMapShadow.put(RenderPipelines.TEXT_POLYGON_OFFSET, p -> ShaderKey.SHADOW_TEXT);
		coreShaderMapShadow.put(RenderPipelines.TEXT_SEE_THROUGH, p -> ShaderKey.SHADOW_TEXT);
		coreShaderMapShadow.put(RenderPipelines.TEXT_INTENSITY_SEE_THROUGH, p -> ShaderKey.SHADOW_TEXT_INTENSITY);
		coreShaderMapShadow.put(RenderPipelines.TEXT_BACKGROUND, p -> ShaderKey.SHADOW_TEXT_BG);
		coreShaderMapShadow.put(RenderPipelines.TEXT_BACKGROUND_SEE_THROUGH, p -> ShaderKey.SHADOW_TEXT_BG);
		coreShaderMapShadow.put(RenderPipelines.TEXT_INTENSITY, p -> ShaderKey.SHADOW_TEXT_INTENSITY);
		coreShaderMapShadow.put(RenderPipelines.WATER_MASK, p -> ShaderKey.SHADOW_BASIC);
		coreShaderMapShadow.put(RenderPipelines.BEACON_BEAM_OPAQUE, p -> ShaderKey.SHADOW_BEACON_BEAM);
		coreShaderMapShadow.put(RenderPipelines.BEACON_BEAM_TRANSLUCENT, p -> ShaderKey.SHADOW_BEACON_BEAM);
		coreShaderMapShadow.put(RenderPipelines.END_PORTAL, p -> ShaderKey.SHADOW_BLOCK);
		coreShaderMapShadow.put(RenderPipelines.END_GATEWAY, p -> ShaderKey.SHADOW_BLOCK);
		coreShaderMapShadow.put(RenderPipelines.ARMOR_TRANSLUCENT, p -> ShaderKey.SHADOW_ENTITIES_CUTOUT);
		coreShaderMapShadow.put(RenderPipelines.LIGHTNING, p -> ShaderKey.SHADOW_LIGHTNING);

		// TODO: Currently impossible
		coreShaderMapShadow.put(CloudRenderer.CLOUDS_FLAT, p -> ShaderKey.CLOUDS_SODIUM);
		coreShaderMapShadow.put(CloudRenderer.CLOUDS_FULL, p -> ShaderKey.CLOUDS_SODIUM);
		// Check that all shaders are accounted for
		//for (RenderPipeline pipeline : RenderPipelines.getStaticPipelines()) {
		//	if (coreShaderMap.containsKey(pipeline) && !coreShaderMapShadow.containsKey(pipeline)) {
		//		Iris.logger.error("Shader program " + pipeline.getLocation() + " is not accounted for in the shadow list");
		//	}
		//}
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

	@Inject(method = "getOrCompilePipeline", at = @At(value = "HEAD"), cancellable = true)
	private void redirectIrisProgram(RenderPipeline renderPipeline, CallbackInfoReturnable<GlRenderPipeline> cir) {
		if (renderPipeline == CompositeRenderer.COMPOSITE_PIPELINE) return;

		WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();

		if (pipeline instanceof IrisRenderingPipeline irisPipeline && irisPipeline.shouldOverrideShaders() && !ImmediateState.bypass) {
			RenderPipeline newProgram = renderPipeline;

			GlProgram program = override(irisPipeline, newProgram);

			if (program != null) {
				cir.setReturnValue(new GlRenderPipeline(renderPipeline, program));
			} else if (missingShaders.add(renderPipeline)) {
				if (renderPipeline.getLocation().getNamespace().equals("minecraft")) {
					Iris.logger.fatal("Missing program " + renderPipeline.getLocation() + " in override list. This is likely an Iris bug!!!", new Throwable());
				} else {
					Iris.logger.error("Missing program " + renderPipeline.getLocation() + " in override list. This is not a critical problem, but it could lead to weird rendering.", new Throwable());
				}
			}
		}
	}

	/*@Inject(method = "compilePipeline", at = @At("RETURN"))
	private static void iris$setSkip(ShaderProgram shaderProgram, ShaderProgramConfig shaderProgramConfig, CompiledShader compiledShader, CompiledShader compiledShader2, CallbackInfoReturnable<CompiledShaderProgram> cir) {
		CompiledShaderProgram p = cir.getReturnValue();
		MethodHandle shouldSkip = shouldSkipList.computeIfAbsent(p.getClass(), x -> {
			try {
				MethodHandle iris$skipDraw = MethodHandles.lookup().findVirtual(x, "iris$skipDraw", MethodType.methodType(boolean.class));
				Iris.logger.warn("Class " + x.getName() + " has opted out of being rendered with shaders.");
				return iris$skipDraw;
			} catch (NoSuchMethodException | IllegalAccessException e) {
				return NONE;
			}
		});


		if (Iris.getIrisConfig().shouldSkip(shaderProgram.configId())) {
			shouldSkip = ALWAYS;
		}

		((ShaderInstanceInterface) p).setShouldSkip(shouldSkip);
	}*/

	private static GlProgram override(IrisRenderingPipeline pipeline, RenderPipeline shaderProgram) {
		ShaderKey shaderKey = convertToShaderKey(pipeline, shaderProgram);

		return shaderKey == null ? null : pipeline.getShaderMap().getShader(shaderKey);
	}

	private static ShaderKey convertToShaderKey(IrisRenderingPipeline pipeline, RenderPipeline shaderProgram) {
		return ShadowRenderingState.areShadowsCurrentlyBeingRendered()? coreShaderMapShadow.getOrDefault(shaderProgram, FAKE_FUNCTION).apply(pipeline) : coreShaderMap.getOrDefault(shaderProgram, FAKE_FUNCTION).apply(pipeline);
	}
}
