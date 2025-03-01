package net.irisshaders.iris.pathways;

import com.mojang.blaze3d.buffers.BufferType;
import com.mojang.blaze3d.buffers.BufferUsage;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.irisshaders.iris.gl.IrisRenderSystem;

/**
 * Renders a full-screen textured quad to the screen. Used in composite / deferred rendering.
 */
public class FullScreenQuadRenderer {
	public static final FullScreenQuadRenderer INSTANCE = new FullScreenQuadRenderer();

	private final GpuBuffer quad;

	private FullScreenQuadRenderer() {
		BufferBuilder bufferBuilder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
		bufferBuilder.addVertex(0.0F, 0.0F, 0.0F).setUv(0.0F, 0.0F);
		bufferBuilder.addVertex(1.0F, 0.0F, 0.0F).setUv(1.0F, 0.0F);
		bufferBuilder.addVertex(1.0F, 1.0F, 0.0F).setUv(1.0F, 1.0F);
		bufferBuilder.addVertex(0.0F, 1.0F, 0.0F).setUv(0.0F, 1.0F);
		MeshData meshData = bufferBuilder.build();

		quad = RenderSystem.getDevice().createBuffer(() -> "Fullscreen Quad", BufferType.VERTICES, BufferUsage.STATIC_WRITE, meshData.vertexBuffer());
		meshData.close();

		Tesselator.getInstance().clear();
	}

	public void render() {
		begin();

		renderQuad();

		end();
	}

	public void begin() {
	}

	public void renderQuad() {
		GlStateManager._disableDepthTest();
		IrisRenderSystem.overridePolygonMode();
		GlStateManager._drawElements(GlConst.toGl(VertexFormat.Mode.QUADS), 6, GlConst.toGl(RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS).type()), 0);
		IrisRenderSystem.restorePolygonMode();
	}

	public void end() {
		// NB: No need to clear the buffer state by calling glDisableVertexAttribArray - this VAO will always
		// have the same format, and buffer state is only associated with a given VAO, so we can keep it bound.
		//
		// Using quad.getFormat().clearBufferState() causes some Intel drivers to freak out:
		// https://github.com/IrisShaders/Iris/issues/1214

	}

	public GpuBuffer getQuad() {
		return quad;
	}
}
