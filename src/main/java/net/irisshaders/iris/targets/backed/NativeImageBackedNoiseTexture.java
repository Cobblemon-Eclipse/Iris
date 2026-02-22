package net.irisshaders.iris.targets.backed;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import net.irisshaders.iris.gl.texture.TextureAccess;
import net.irisshaders.iris.gl.texture.TextureType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.lwjgl.opengl.GL11;

import java.util.Objects;
import java.util.Random;
import java.util.function.IntSupplier;

public class NativeImageBackedNoiseTexture extends DynamicTexture implements TextureAccess {
	public NativeImageBackedNoiseTexture(int size) {
		super(create(size));
	}

	private static NativeImage create(int size) {
		NativeImage image = new NativeImage(NativeImage.Format.RGBA, size, size, false);
		Random random = new Random(0);

		for (int x = 0; x < size; x++) {
			for (int y = 0; y < size; y++) {
				int color = random.nextInt() | (255 << 24);

				image.setPixelRGBA(x, y, color);
			}
		}

		return image;
	}

	@Override
	public void upload() {
		NativeImage image = Objects.requireNonNull(getPixels());

		bind();
		image.upload(0, 0, 0, 0, 0, image.getWidth(), image.getHeight(), true, false, false, false);

		// Noise texture must use REPEAT wrapping. Shader Noise3D() samples with UV
		// coordinates far outside [0,1]. Without REPEAT, VulkanMod creates a VkSampler
		// with CLAMP_TO_EDGE (DynamicTexture default), causing noise to degenerate to
		// constant edge texel values — producing straight-line artifacts in clouds.
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
	}

	@Override
	public TextureType getType() {
		return TextureType.TEXTURE_2D;
	}

	@Override
	public IntSupplier getTextureId() {
		return this::getId;
	}
}
