package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.systems.RenderPass;
import net.irisshaders.iris.mixinterface.RenderPassExtension;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(RenderPass.class)
public interface MixinRenderPass extends RenderPassExtension {
	// Used to make Java happy :)
}
