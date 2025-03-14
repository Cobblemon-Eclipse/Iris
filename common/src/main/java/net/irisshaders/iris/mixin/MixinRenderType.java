package net.irisshaders.iris.mixin;

import net.irisshaders.iris.NeoLambdas;
import net.irisshaders.iris.pipeline.programs.ShaderAccess;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(RenderType.class)
public class MixinRenderType {
}
