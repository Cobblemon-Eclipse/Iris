package net.irisshaders.iris.targets.backed;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.texture.TextureAccess;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.IntSupplier;

public class NativeImageBackedCustomTexture extends DynamicTexture implements TextureAccess {
	public NativeImageBackedCustomTexture(CustomTextureData.PngData textureData) throws IOException {
		super(() -> "PNG", create(textureData.getContent()));

		// By default, images are unblurred and not clamped.

		getTexture().setAddressMode(textureData.getFilteringData().shouldClamp() ? AddressMode.CLAMP_TO_EDGE : AddressMode.REPEAT);
		getTexture().setTextureFilter(textureData.getFilteringData().shouldBlur() ? FilterMode.LINEAR : FilterMode.NEAREST, false);
	}

	private static NativeImage create(byte[] content) throws IOException {
		ByteBuffer buffer = ByteBuffer.allocateDirect(content.length);
		buffer.put(content);
		buffer.flip();

		return NativeImage.read(buffer);
	}

	@Override
	public void upload() {
		NativeImage image = Objects.requireNonNull(getPixels());

		RenderSystem.getDevice().createCommandEncoder().writeToTexture(this.texture, image);
	}

	@Override
	public TextureType getType() {
		return TextureType.TEXTURE_2D;
	}

	@Override
	public IntSupplier getTextureId() {
		return this.getTexture()::flushAndId;
	}
}
