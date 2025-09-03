package net.irisshaders.iris.mixin.fantastic;

import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SingleQuadParticle.Layer.class)
public interface LayerAccessor {
	@Invoker("<init>")
	private SingleQuadParticle.Layer create(String name, int ordinal, RenderType type) {
		return null;
	}
}
