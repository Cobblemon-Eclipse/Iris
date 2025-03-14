package net.irisshaders.batchedentityrendering.impl;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.irisshaders.iris.vertices.ImmediateState;

public class BufferSegmentRenderer {
	public BufferSegmentRenderer() {
	}
	/**
	 * Like draw(), but it doesn't setup / tear down the render type.
	 */
	public void drawInner(RenderPipeline renderPipeline, RenderPass pass, BufferSegment segment) {
		MeshData meshData = segment.meshData();

		ImmediateState.temporarilyIgnorePass = true;
		GpuBuffer vertexBuffer = renderPipeline.getVertexFormat().uploadImmediateVertexBuffer(meshData.vertexBuffer());
		GpuBuffer indexBuffer;
		VertexFormat.IndexType indexType;
		if (meshData.indexBuffer() == null) {
			RenderSystem.AutoStorageIndexBuffer autoStorageIndexBuffer = RenderSystem.getSequentialBuffer(meshData.drawState().mode());
			indexBuffer = autoStorageIndexBuffer.getBuffer(meshData.drawState().indexCount());
			indexType = autoStorageIndexBuffer.type();
		} else {
			indexBuffer = renderPipeline.getVertexFormat().uploadImmediateIndexBuffer(meshData.indexBuffer());
			indexType = meshData.drawState().indexType();
		}

		pass.setVertexBuffer(0, vertexBuffer);
		pass.setIndexBuffer(indexBuffer, indexType);
		pass.drawIndexed(0, segment.meshData().drawState().indexCount());

		ImmediateState.temporarilyIgnorePass = false;

	}
}
