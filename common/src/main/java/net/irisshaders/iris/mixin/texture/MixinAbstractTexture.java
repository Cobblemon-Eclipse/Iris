package net.irisshaders.iris.mixin.texture;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.irisshaders.iris.mixinterface.AbstractTextureExtended;
import net.irisshaders.iris.pbr.TextureTracker;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractTexture.class)
public abstract class MixinAbstractTexture implements AbstractTextureExtended {
	@Unique
	private int lastId;

	@Shadow
	public abstract void bind();

	@Shadow
	public abstract GpuTexture getTexture();

	@Shadow
	@Nullable
	protected GpuTexture texture;

	@Inject(method = "bind", at = @At("HEAD"))
	private void iris$check(CallbackInfo ci) {
		check();
	}

	@Inject(method = "getTexture", at = @At("HEAD"))
	private void iris$check2(CallbackInfoReturnable<GpuTexture> cir) {
		check();
	}

	@Inject(method = "getId", at = @At("HEAD"))
	private void iris$check3(CallbackInfoReturnable<GpuTexture> cir) {
		check();
	}

	@Unique
	private void check() {
		if (this.texture.glId() != lastId) {
			TextureTracker.INSTANCE.trackTexture(this.texture.glId(), (AbstractTexture) (Object) this);
			lastId = this.texture.glId();
		}
	}
}
