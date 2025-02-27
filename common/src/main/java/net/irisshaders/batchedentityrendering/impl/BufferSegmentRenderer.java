package net.irisshaders.batchedentityrendering.impl;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;

public class BufferSegmentRenderer {
	public BufferSegmentRenderer() {
	}

	/**
	 * Like draw(), but it doesn't setup / tear down the render type.
	 */
	public void drawInner(RenderPass currentPass, BufferSegment segment) {
		GpuBuffer vertexBuffer = segment.meshData().drawState().format().uploadImmediateVertexBuffer(segment.meshData().vertexBuffer());

		GpuBuffer gpuBuffer2 = segment.meshData().indexBuffer() == null ? null : segment.type().getRenderPipeline().getVertexFormat().uploadImmediateIndexBuffer(segment.meshData().indexBuffer());

		if (gpuBuffer2 != null) {
			currentPass.setIndexBuffer(gpuBuffer2, segment.meshData().drawState().indexType());
		} else {
			RenderSystem.AutoStorageIndexBuffer autoStorageIndexBuffer = RenderSystem.getSequentialBuffer(segment.meshData().drawState().mode());
			currentPass.setIndexBuffer(autoStorageIndexBuffer.getBuffer(segment.meshData().drawState().indexCount()), autoStorageIndexBuffer.type());
		}

		currentPass.setVertexBuffer(0,  vertexBuffer);
		currentPass.drawIndexed(0, segment.meshData().drawState().indexCount());

		segment.meshData().close();
	}
}
