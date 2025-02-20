package net.irisshaders.iris.mixin;

import com.google.common.collect.ImmutableSet;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.GLDebug;
import net.irisshaders.iris.gl.blending.DepthColorStorage;
import net.irisshaders.iris.mixinterface.ShaderInstanceInterface;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.ShaderRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ExtendedShader;
import net.irisshaders.iris.pipeline.programs.FallbackShader;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CompiledShaderProgram;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.lwjgl.opengl.KHRDebug;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.invoke.MethodHandle;

import static net.irisshaders.iris.compat.SkipList.ALWAYS;
import static net.irisshaders.iris.compat.SkipList.NONE;
import static net.irisshaders.iris.compat.SkipList.shouldSkipList;

@Mixin(CompiledShaderProgram.class)
public abstract class MixinCompiledShaderProgram implements ShaderInstanceInterface {
	@Unique
	private static final ImmutableSet<String> ATTRIBUTE_LIST = ImmutableSet.of("Position", "Color", "Normal", "UV0", "UV1", "UV2");

	@Unique
	private static CompiledShaderProgram lastAppliedShader;

	@Inject(method = "apply", at = @At("HEAD"))
	private void iris$lockDepthColorState(CallbackInfo ci) {
		if (lastAppliedShader != null) {
			lastAppliedShader.clear();
			lastAppliedShader = null;
		}
	}

	@Unique
	private MethodHandle shouldSkip;

	static {
		shouldSkipList.put(ExtendedShader.class, NONE);
		shouldSkipList.put(FallbackShader.class, NONE);
	}

	@Override
	public void setShouldSkip(MethodHandle s) {
		shouldSkip = s;
	}

	public boolean iris$shouldSkipThis() {
		if (Iris.getIrisConfig().shouldAllowUnknownShaders()) {
			if (ShadowRenderer.ACTIVE) return true;

			if (!shouldOverrideShaders()) return false;

			if (shouldSkip == NONE) return false;
			if (shouldSkip == ALWAYS) return true;

			try {
				return (boolean) shouldSkip.invoke(((CompiledShaderProgram) (Object) this));
			} catch (Throwable e) {
				throw new RuntimeException(e);
			}
		} else {
			return !(((Object) this) instanceof ExtendedShader || ((Object) this) instanceof FallbackShader || !shouldOverrideShaders());
		}
	}

	@Unique
	private static boolean shouldOverrideShaders() {
		WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();

		if (pipeline instanceof ShaderRenderingPipeline) {
			return ((ShaderRenderingPipeline) pipeline).shouldOverrideShaders();
		} else {
			return false;
		}
	}

	@Inject(method = "apply", at = @At("TAIL"))
	private void onTail(CallbackInfo ci) {
		if (!iris$shouldSkipThis()) {
			if (!isKnownShader() && shouldOverrideShaders()) {
				WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();

				if (pipeline instanceof IrisRenderingPipeline) {
					if (ShadowRenderer.ACTIVE) {
						// ((IrisRenderingPipeline) pipeline).bindDefaultShadow(); don't rn
					} else {
						((IrisRenderingPipeline) pipeline).bindDefault();
					}
				}
			}

			return;
		}

		DepthColorStorage.disableDepthColor();
	}

	private boolean isKnownShader() {
		return ((Object) this) instanceof ExtendedShader || ((Object) this) instanceof FallbackShader;
	}

	@Inject(method = "clear", at = @At("HEAD"))
	private void iris$unlockDepthColorState(CallbackInfo ci) {
		if (!iris$shouldSkipThis()) {
			if (!isKnownShader() && shouldOverrideShaders()) {
				WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();

				if (pipeline instanceof IrisRenderingPipeline) {
					Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
				}
			}

			return;
		}

		DepthColorStorage.unlockDepthColor();
	}
}
