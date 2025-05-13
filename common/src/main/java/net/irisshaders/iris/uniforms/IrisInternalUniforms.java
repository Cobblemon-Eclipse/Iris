package net.irisshaders.iris.uniforms;

import com.mojang.blaze3d.systems.RenderSystem;
import net.caffeinemc.mods.sodium.client.util.FogAccessor;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.gl.uniform.DynamicUniformHolder;
import net.minecraft.client.Minecraft;
import org.joml.Vector4f;

import static net.irisshaders.iris.gl.uniform.UniformUpdateFrequency.PER_FRAME;

/**
 * Internal Iris uniforms that are not directly accessible by shaders.
 */
public class IrisInternalUniforms {
	private static final Vector4f ONE = new Vector4f(1.0f, 1.0f, 1.0f, 1.0f);

	private IrisInternalUniforms() {
		// no construction
	}

	public static void addFogUniforms(DynamicUniformHolder uniforms, FogMode fogMode) {
		uniforms
			.uniform4f("iris_FogColor", () -> {
				FogParameters fog = ((FogAccessor) Minecraft.getInstance().levelRenderer).sodium$getFogParameters();

				if (fog == FogParameters.NONE) return ONE;
				return new Vector4f(fog.color());
			}, t -> {});

		uniforms.uniform1f("iris_FogStart", () -> ((FogAccessor) Minecraft.getInstance().levelRenderer).sodium$getFogParameters().envStart(), t -> {})
			.uniform1f("iris_FogEnd", () -> ((FogAccessor) Minecraft.getInstance().levelRenderer).sodium$getFogParameters().envEnd(), t -> {});

		uniforms.uniform1f("iris_FogDensity", () -> {
			// ensure that the minimum value is 0.0
			return Math.max(0.0F, CapturedRenderingState.INSTANCE.getFogDensity());
		}, notifier -> {
		});

		uniforms.uniform1f("iris_currentAlphaTest", CapturedRenderingState.INSTANCE::getCurrentAlphaTest, notifier -> {
		});

		// Optifine compatibility
		uniforms.uniform1f("alphaTestRef", CapturedRenderingState.INSTANCE::getCurrentAlphaTest, notifier -> {
		});
	}
}
