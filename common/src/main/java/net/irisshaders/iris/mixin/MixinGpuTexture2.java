package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.textures.GpuTexture;
import net.irisshaders.iris.mixinterface.GpuTextureInterface;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(GpuTexture.class)
public abstract class MixinGpuTexture2 implements GpuTextureInterface {
	@Override
	public int getGlId() {
		throw new AssertionError("Why.");
	}

	@Override
	public void markMipmapNonLinear() {
		throw new AssertionError("Why.");
	}
}
