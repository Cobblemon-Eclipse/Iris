package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.textures.GpuTexture;
import net.irisshaders.iris.mixinterface.GpuTextureExtension;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(GpuTexture.class)
public class MixinGpuTexture2 implements GpuTextureExtension {
}
