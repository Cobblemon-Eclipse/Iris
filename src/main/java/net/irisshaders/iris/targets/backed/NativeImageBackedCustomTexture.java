package net.irisshaders.iris.targets.backed;

import com.mojang.blaze3d.platform.NativeImage;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.texture.TextureAccess;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.minecraft.client.renderer.texture.DynamicTexture;

import net.irisshaders.iris.Iris;
import net.vulkanmod.gl.GlTexture;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.StagingBuffer;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.texture.ImageUtil;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.Synchronization;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * Native image backed custom texture - Vulkan Port.
 *
 * GL constants inlined to remove LWJGL OpenGL dependency.
 */
public class NativeImageBackedCustomTexture extends DynamicTexture implements TextureAccess {
	// GL constants inlined
	private static final int GL_TEXTURE_2D = 0x0DE1;

	public NativeImageBackedCustomTexture(CustomTextureData.PngData textureData) throws IOException {
		super(create(textureData.getContent()));

		if (textureData.getFilteringData().shouldBlur()) {
			IrisRenderSystem.texParameteri(getId(), GL_TEXTURE_2D, 0x2801, 0x2601); // MIN_FILTER = LINEAR
			IrisRenderSystem.texParameteri(getId(), GL_TEXTURE_2D, 0x2800, 0x2601); // MAG_FILTER = LINEAR
		}

		if (textureData.getFilteringData().shouldClamp()) {
			IrisRenderSystem.texParameteri(getId(), GL_TEXTURE_2D, 0x2802, 0x812F); // WRAP_S = CLAMP_TO_EDGE
			IrisRenderSystem.texParameteri(getId(), GL_TEXTURE_2D, 0x2803, 0x812F); // WRAP_T = CLAMP_TO_EDGE
		} else {
			// Vulkan defaults to CLAMP_TO_EDGE unlike OpenGL's REPEAT default.
			// Custom pack textures (e.g., gaux4 water noise) are sampled at world-space
			// coordinates that tile across blocks — they need REPEAT to produce correct
			// wave normals, cloud patterns, etc.
			IrisRenderSystem.texParameteri(getId(), GL_TEXTURE_2D, 0x2802, 0x2901); // WRAP_S = REPEAT
			IrisRenderSystem.texParameteri(getId(), GL_TEXTURE_2D, 0x2803, 0x2901); // WRAP_T = REPEAT
		}

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
		int w = image.getWidth(), h = image.getHeight();

		// Standard upload via DynamicTexture path (might not submit command buffer)
		bind();
		image.upload(0, 0, 0, 0, 0, w, h, false, false, false, false);

		// VulkanMod's uploadSubTextureAsync uses endIfNeeded() which may NOT submit
		// the command buffer containing the copy. Force an explicit upload with
		// submitCommands() to guarantee the pixel data reaches the GPU.
		GlTexture glTex = GlTexture.getTexture(getId());
		VulkanImage vkImg = glTex != null ? glTex.getVulkanImage() : null;
		if (vkImg != null) {
			forceUploadToGpu(vkImg, image, w, h);
		}
	}

	/**
	 * Explicitly uploads NativeImage pixel data to the VulkanImage using a command
	 * buffer that is guaranteed to be submitted. This bypasses the standard
	 * uploadSubTextureAsync path which uses endIfNeeded() and may silently skip
	 * submission during shader pack loading.
	 */
	private void forceUploadToGpu(VulkanImage vkImg, NativeImage image, int w, int h) {
		int bufSize = w * h * 4;
		ByteBuffer pixelBuf = org.lwjgl.system.MemoryUtil.memAlloc(bufSize);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				pixelBuf.putInt(image.getPixelRGBA(x, y));
			}
		}
		pixelBuf.flip();

		CommandPool.CommandBuffer cb = DeviceManager.getGraphicsQueue().getCommandBuffer();

		// The standard upload (uploadSubTextureAsync) may have set currentLayout to
		// TRANSFER_DST in Java without actually submitting the GPU command. Reset to
		// UNDEFINED so our barrier is properly recorded in THIS command buffer.
		vkImg.setCurrentLayout(org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED);

		// Transition to TRANSFER_DST
		try (MemoryStack stack = MemoryStack.stackPush()) {
			vkImg.transitionImageLayout(stack, cb.getHandle(),
				org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
		}

		// Copy pixel data via staging buffer
		StagingBuffer stagingBuffer = Vulkan.getStagingBuffer();
		stagingBuffer.align(vkImg.formatSize);
		stagingBuffer.copyBuffer(bufSize, pixelBuf);

		ImageUtil.copyBufferToImageCmd(cb.getHandle(), stagingBuffer.getId(), vkImg.getId(),
			0, w, h, 0, 0, (int) stagingBuffer.getOffset(), w, h);

		// Transition to SHADER_READ_ONLY
		try (MemoryStack stack = MemoryStack.stackPush()) {
			vkImg.transitionImageLayout(stack, cb.getHandle(),
				org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
		}

		// EXPLICITLY submit — guarantees the copy executes on the GPU
		DeviceManager.getGraphicsQueue().submitCommands(cb);
		Synchronization.INSTANCE.addCommandBuffer(cb);

		org.lwjgl.system.MemoryUtil.memFree(pixelBuf);
		Iris.logger.info("[CUSTOM_TEX_UPLOAD] Forced GPU upload for texId={} ({}x{})", getId(), w, h);
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
