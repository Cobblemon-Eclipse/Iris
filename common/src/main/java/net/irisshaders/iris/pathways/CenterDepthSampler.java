package net.irisshaders.iris.pathways;

import com.google.common.collect.ImmutableSet;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.irisshaders.iris.gl.GLDebug;
import net.irisshaders.iris.gl.GlCustomState;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.framebuffer.ViewportData;
import net.irisshaders.iris.gl.program.Program;
import net.irisshaders.iris.gl.program.ProgramBuilder;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.gl.texture.DepthCopyStrategy;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.PixelType;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.mixin.GlTextureInvoker;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.io.IOUtils;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2i;
import org.lwjgl.opengl.GL21C;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public class CenterDepthSampler {
	private static final double LN2 = Math.log(2);
	private static final Matrix4f PROJECTION = new Matrix4f(2, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, -1, -1, 0, 1);
	private final Program program;
	private final GlFramebuffer framebuffer;
	private final GlTexture texture;
	private final GlTexture altTexture;
	private final Supplier<GpuTexture> depthSupplier;
	private boolean hasFirstSample;
	private boolean everRetrieved;
	private boolean destroyed;

	private RenderPipeline pipeline = RenderPipeline.builder().withoutBlend().withLocation(ResourceLocation.fromNamespaceAndPath("iris", "depth_smoothing"))
		.withVertexShader("core/blit_screen")
		.withFragmentShader("core/blit_screen")
		.withoutBlend()
		.withDepthWrite(false)
		.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
		.withColorWrite(true, true)
		.withVertexFormat(DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS)
		.build();

	public CenterDepthSampler(Supplier<GpuTexture> depthSupplier, float halfLife) {
		int tTex = GlStateManager._genTexture();
		int aTex = GlStateManager._genTexture();
		this.framebuffer = new GlFramebuffer();

		InternalTextureFormat format = InternalTextureFormat.R32F;
		setupColorTexture(tTex, format);
		setupColorTexture(aTex, format);
		GlStateManager._bindTexture(0);

		this.texture = GlTextureInvoker.create("Center Depth Sampler", TextureFormat.RGBA8, 1, 1, 1, tTex);
		this.altTexture = GlTextureInvoker.create("Center Depth Sampler 2", TextureFormat.RGBA8, 1, 1, 1, aTex);

		this.framebuffer.addColorAttachment(0, tTex);
		ProgramBuilder builder;

		try {
			String fsh = new String(IOUtils.toByteArray(Objects.requireNonNull(getClass().getResourceAsStream("/centerDepth.fsh"))), StandardCharsets.UTF_8);
			String vsh = new String(IOUtils.toByteArray(Objects.requireNonNull(getClass().getResourceAsStream("/centerDepth.vsh"))), StandardCharsets.UTF_8);

			builder = ProgramBuilder.begin("centerDepthSmooth", vsh, null, fsh, ImmutableSet.of(0, 1, 2));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		this.depthSupplier = depthSupplier;
		builder.addDynamicSampler(() -> depthSupplier.get().flushAndId(), "depth");
		builder.addDynamicSampler(() -> aTex, "altDepth");
		builder.uniform1f(UniformUpdateFrequency.PER_FRAME, "lastFrameTime", SystemTimeUniforms.TIMER::getLastFrameTime);
		builder.uniform1f(UniformUpdateFrequency.ONCE, "decay", () -> (1.0f / ((halfLife * 0.1) / LN2)));
		// TODO: can we just do this for all composites?
		builder.uniformMatrix(UniformUpdateFrequency.ONCE, "projection", () -> PROJECTION);
		this.program = builder.build();
	}

	public void sampleCenterDepth() {
		if ((hasFirstSample && (!everRetrieved)) || destroyed) {
			// If the shaderpack isn't reading center depth values, don't bother sampling it
			// This improves performance with most shaderpacks
			return;
		}

		hasFirstSample = true;

		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(texture, OptionalInt.empty())) {
			pass.bindSampler("depth", depthSupplier.get());
			pass.bindSampler("altDepth", altTexture);
			pass.setPipeline(pipeline);
			pass.iris$setCustomState(new GlCustomState(this.program, this.framebuffer, null, ViewportData.defaultValue(), new Vector2i(1, 1)));
			pass.setVertexBuffer(0, FullScreenQuadRenderer.INSTANCE.getQuad());
			RenderSystem.AutoStorageIndexBuffer buf = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
			pass.setIndexBuffer(buf.getBuffer(6), buf.type());

			program.use();
			pass.drawIndexed(0, 6);

			BlendModeOverride.restore();
			GLDebug.popGroup();
		}

		// The API contract of DepthCopyStrategy claims it can only copy depth, however the 2 non-stencil methods used are entirely capable of copying color as of now.
		DepthCopyStrategy.fastest(false).copy(this.framebuffer, texture.glId(), null, altTexture.glId(), 1, 1);
	}

	public void setupColorTexture(int texture, InternalTextureFormat format) {
		IrisRenderSystem.texImage2D(texture, GL21C.GL_TEXTURE_2D, 0, format.getGlFormat(), 1, 1, 0, format.getPixelFormat().getGlFormat(), PixelType.FLOAT.getGlFormat(), null);

		IrisRenderSystem.texParameteri(texture, GL21C.GL_TEXTURE_2D, GL21C.GL_TEXTURE_MIN_FILTER, GL21C.GL_LINEAR);
		IrisRenderSystem.texParameteri(texture, GL21C.GL_TEXTURE_2D, GL21C.GL_TEXTURE_MAG_FILTER, GL21C.GL_LINEAR);
		IrisRenderSystem.texParameteri(texture, GL21C.GL_TEXTURE_2D, GL21C.GL_TEXTURE_WRAP_S, GL21C.GL_CLAMP_TO_EDGE);
		IrisRenderSystem.texParameteri(texture, GL21C.GL_TEXTURE_2D, GL21C.GL_TEXTURE_WRAP_T, GL21C.GL_CLAMP_TO_EDGE);
	}

	public int getCenterDepthTexture() {
		return altTexture.glId();
	}

	public void setUsage(boolean usage) {
		everRetrieved |= usage;
	}

	public void destroy() {
		texture.close();
		altTexture.close();
		framebuffer.destroy();
		program.destroy();
		destroyed = true;
	}
}
