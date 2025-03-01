package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.opengl.GlRenderPass;
import net.irisshaders.iris.gl.GlCustomState;
import net.irisshaders.iris.mixinterface.RenderPassExtension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(GlRenderPass.class)
public class MixinGlRenderPass implements RenderPassExtension {
	@Unique
	private GlCustomState iris$customProgram = null;

	@Override
	public void iris$setCustomState(GlCustomState program) {
		this.iris$customProgram = program;
	}

	@Override
	public GlCustomState iris$getCustomState() {
		return this.iris$customProgram;
	}
}
