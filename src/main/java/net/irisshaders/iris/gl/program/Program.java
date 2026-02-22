package net.irisshaders.iris.gl.program;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.GlResource;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.uniform.IrisUniformBuffer;
import net.irisshaders.iris.mixin.GameRendererAccessor;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.descriptor.ManualUBO;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;

/**
 * Represents a shader program - Vulkan Port.
 *
 * In OpenGL, this was a linked GL program with attached vertex/fragment shaders.
 * In Vulkan, the equivalent is a VkPipeline (graphics or compute) which bakes
 * together shaders, vertex format, blend state, depth state, and render pass.
 *
 * Now holds a Vulkan GraphicsPipeline + IrisUniformBuffer + ManualUBO for
 * composite/final pass rendering.
 */
public final class Program extends GlResource {
	// Static previous-frame matrix tracking for gbufferPreviousModelView/Projection.
	// These track across all composite/entity programs since they share the same camera.
	// Frame detection: CapturedRenderingState.setGbufferProjection() creates a new Matrix4f
	// each frame, so reference comparison detects frame boundaries.
	private static float[] prevMvArr = null;
	private static float[] prevProjArr = null;
	private static float[] savedMvArr = null;   // current frame's MV (becomes prev next frame)
	private static float[] savedProjArr = null;  // current frame's proj (becomes prev next frame)
	private static Matrix4fc lastSeenProj = null; // for frame boundary detection

	// Diagnostic: log uniform values written to UBO for first N frames
	private static int diagLogCount = 0;
	private static int mvLogTick = 0;
	// Separate counter for deferred1-only MV logging (so periodic logs actually fire)
	private static int deferred1MvLogCount = 0;
	// Periodic FOV tracking (every 120 frames) to detect if 6.9° FOV persists
	private static int fovLogCounter = 0;

	// DIAGNOSTIC FLAG: When true, writes a Y-FLIPPED IDENTITY to gbufferModelViewInverse.
	// This should make the aurora appear BELOW the horizon (flipped upside down) if data
	// reaches the GPU. If the aurora stays in the sky normally, data doesn't reach the GPU.
	// Set to false for normal operation.
	private static final boolean DIAG_FLIP_MVINV = false;

	private final String name;
	private final ProgramUniforms uniforms;
	private final ProgramSamplers samplers;
	private final ProgramImages images;
	private final GraphicsPipeline pipeline;
	private final IrisUniformBuffer uniformBuffer;
	private final ManualUBO manualUBO;

	Program(int program, String name, ProgramUniforms uniforms, ProgramSamplers samplers, ProgramImages images,
			GraphicsPipeline pipeline, IrisUniformBuffer uniformBuffer, ManualUBO manualUBO) {
		super(program);

		this.name = name != null ? name : "unknown";
		this.uniforms = uniforms;
		this.samplers = samplers;
		this.images = images;
		this.pipeline = pipeline;
		this.uniformBuffer = uniformBuffer;
		this.manualUBO = manualUBO;
	}

	public static void unbind() {
		ProgramUniforms.clearActiveUniforms();
		ProgramSamplers.clearActiveSamplers();
		IrisRenderSystem.setActiveUniformBuffer(null);
	}

	public void use() {
		// Memory barrier before each program use, matching original Iris behavior.
		// Ensures compute shader writes (images, SSBOs, textures) are visible to
		// subsequent fragment shader reads.
		IrisRenderSystem.memoryBarrier(0x00000020 | 0x00000008 | 0x00002000);

		// Set active uniform buffer so IrisRenderSystem.uniform*() writes to our buffer
		if (uniformBuffer != null) {
			IrisRenderSystem.setActiveUniformBuffer(uniformBuffer);
		}

		// Update Iris uniforms (writes to active buffer via IrisRenderSystem)
		uniforms.update();
		images.update();

		// Write gbufferModelView, gbufferProjection, and other OptiFine-compatible
		// uniforms directly to the UBO. In original OpenGL Iris, composite programs
		// don't register these through ProgramUniforms, but in Vulkan all uniforms
		// live in a single UBO so we must write them explicitly.
		if (uniformBuffer != null) {
			writeGbufferUniforms();
		}

		// Update ManualUBO source pointer so VulkanMod copies our data at draw time
		if (manualUBO != null && uniformBuffer != null) {
			manualUBO.setSrc(uniformBuffer.getPointer(), uniformBuffer.getUsedSize());

			// Log ManualUBO state for first few frames — confirms pointer, size, and data integrity
			if (diagLogCount < 3) {
				int mvInvOff = uniformBuffer.getFieldOffset("gbufferModelViewInverse");
				int projInvOff = uniformBuffer.getFieldOffset("gbufferProjectionInverse");
				int viewWidthOff = uniformBuffer.getFieldOffset("viewWidth");
				float[] mvInvRB = (mvInvOff >= 0) ? uniformBuffer.readbackMat4f(mvInvOff) : null;
				float viewWidthVal = (viewWidthOff >= 0) ? MemoryUtil.memGetFloat(uniformBuffer.getPointer() + viewWidthOff) : -1f;
				Iris.logger.info("[DIAG_UBO_FRAME] prog='{}' manualUBO: srcPtr=0x{} srcSize={} descSize={}" +
						" | mvInv[5]={} (GPU should read this as [1][1])" +
						" | projInv[5]={}" +
						" | viewWidth={} (off={})",
					this.name,
					Long.toHexString(manualUBO.getSrcPtr()), manualUBO.getSrcSize(), manualUBO.getSize(),
					mvInvRB != null ? String.format("%.4f", mvInvRB[5]) : "null",
					projInvOff >= 0 ? String.format("%.4f", MemoryUtil.memGetFloat(uniformBuffer.getPointer() + projInvOff + 5 * 4)) : "null",
					String.format("%.1f", viewWidthVal), viewWidthOff);
			}
		}

		// Bind the Vulkan pipeline for subsequent draw calls
		if (pipeline != null) {
			Renderer renderer = Renderer.getInstance();
			renderer.bindGraphicsPipeline(pipeline);

			// Bind Iris samplers AFTER pipeline bind so they set VTextureSelector correctly.
			// Iris's ProgramSamplers handles all texture binding via IrisRenderSystem.bindTextureToUnit(),
			// which sets VTextureSelector.boundTextures[] directly. Do NOT call
			// VTextureSelector.bindShaderTextures() here — that would overwrite Iris's
			// bindings for slots 0-11 with stale RenderSystem textures.
			samplers.update();

			renderer.uploadAndBindUBOs(pipeline);
		}
	}

	/**
	 * Writes gbufferModelView, gbufferProjection, their inverses, and cameraPosition
	 * to the UBO. These standard OptiFine/Iris uniforms are used by shader packs for
	 * camera-space calculations in composite/deferred/final passes.
	 */
	private void writeGbufferUniforms() {
		// Construct model-view from camera angles. CapturedRenderingState is STALE
		// in VulkanMod (MixinLevelRenderer capture doesn't fire on subsequent frames).
		Matrix4f gbufferMV = null;
		Minecraft mcCam = Minecraft.getInstance();
		if (mcCam.gameRenderer != null && mcCam.gameRenderer.getMainCamera() != null) {
			net.minecraft.client.Camera camera = mcCam.gameRenderer.getMainCamera();
			gbufferMV = new Matrix4f()
				.rotateX(camera.getXRot() * ((float) Math.PI / 180.0f))
				.rotateY((camera.getYRot() + 180.0f) * ((float) Math.PI / 180.0f));
		}
		Matrix4fc gbufferProj = CapturedRenderingState.INSTANCE.getGbufferProjection();

		// Detect frame boundary: setGbufferProjection() creates a new Matrix4f each frame,
		// so a reference change means we've entered a new frame. Rotate saved→prev.
		if (gbufferProj != null && gbufferProj != lastSeenProj) {
			prevMvArr = savedMvArr;
			prevProjArr = savedProjArr;
			savedMvArr = null;
			savedProjArr = null;
			lastSeenProj = gbufferProj;
		}

		if (gbufferMV != null) {
			float[] arr = new float[16];
			gbufferMV.get(arr);
			writeMatIfPresent("gbufferModelView", arr);
			// Per-frame MV check for deferred1 ONLY (reduced frequency)
			if ("deferred1".equals(this.name)) {
				deferred1MvLogCount++;
				if (deferred1MvLogCount <= 5 || deferred1MvLogCount % 300 == 0) {
					float camPitch = 0;
					try {
						Minecraft mcDiag = Minecraft.getInstance();
						if (mcDiag.gameRenderer != null && mcDiag.gameRenderer.getMainCamera() != null) {
							camPitch = mcDiag.gameRenderer.getMainCamera().getXRot();
						}
					} catch (Exception ignored) {}
					float expectedCol1y = (float) Math.cos(Math.toRadians(camPitch));
					Iris.logger.info("[MV_CHECK] frame={} pitch={} col1.y={} expected={} match={}",
						deferred1MvLogCount,
						String.format("%.1f", camPitch),
						String.format("%.4f", arr[5]),
						String.format("%.4f", expectedCol1y),
						Math.abs(arr[5] - expectedCol1y) < 0.01 ? "YES" : "NO");
				}
			}
			// Save this frame's MV for next frame (only first call per frame)
			if (savedMvArr == null) savedMvArr = arr.clone();
			// Inverse
			Matrix4f inv = new Matrix4f(gbufferMV);
			inv.invert();
			inv.get(arr);
			if (DIAG_FLIP_MVINV) {
				// DIAGNOSTIC: Write Y-flipped identity to gbufferModelViewInverse.
				// This makes mat3(M)*viewPos = (vx, -vy, vz) — aurora should flip below horizon.
				// If aurora flips → GPU reads our data. If aurora stays same → GPU ignores our data.
				arr = new float[] {
					1, 0, 0, 0,   // column 0: X unchanged
					0, -1, 0, 0,  // column 1: Y negated
					0, 0, 1, 0,   // column 2: Z unchanged
					0, 0, 0, 1    // column 3: translation zero
				};
				if (diagLogCount < 3) {
					Iris.logger.info("[DIAG_FLIP_MVINV] Writing Y-FLIPPED IDENTITY to gbufferModelViewInverse for '{}'", this.name);
				}
			}
			writeMatIfPresent("gbufferModelViewInverse", arr);
			// Previous frame model view
			if (prevMvArr != null) {
				writeMatIfPresent("gbufferPreviousModelView", prevMvArr);
			} else {
				gbufferMV.get(arr);
				writeMatIfPresent("gbufferPreviousModelView", arr);
			}
		}

		if (gbufferProj != null) {
			Matrix4f proj = new Matrix4f(gbufferProj);

			// Save raw values for diagnostic
			float rawM00 = proj.m00(), rawM11 = proj.m11(), rawM22 = proj.m22();
			float rawM23 = proj.m23(), rawM32 = proj.m32(), rawM33 = proj.m33();

			// ALWAYS rebuild the perspective projection from actual game parameters.
			// CapturedRenderingState's stored m00/m11 can mismatch the renderLevel
			// projection (observed: stored=0.5003 vs renderLevel=0.7077). This causes
			// a 29% horizontal FOV error in depth reconstruction, producing cloud bands
			// and aurora line artifacts. Computing from getFov() + window dimensions
			// ensures the UBO matches the actual rendering projection.
			if (proj.m23() != 0) { // perspective projection
				Minecraft mcClient = Minecraft.getInstance();
				float far = mcClient.gameRenderer != null ? mcClient.gameRenderer.getRenderDistance() : 256.0f;
				float near = 0.05f;

				// ALWAYS rebuild m00/m11 from actual FOV and aspect ratio.
				// The terrain rasterizer uses the renderLevel projection (VRenderSystem.applyMVP),
				// so depth reconstruction must match. GameRendererAccessor.invokeGetFov() returns
				// the exact FOV used to create that projection, and window dimensions give the
				// actual aspect ratio.
				{
					double fovDegrees = 70.0; // default
					try {
						if (mcClient.gameRenderer != null) {
							fovDegrees = ((net.irisshaders.iris.mixin.GameRendererAccessor) mcClient.gameRenderer)
								.invokeGetFov(mcClient.gameRenderer.getMainCamera(),
									mcClient.getTimer().getGameTimeDeltaPartialTick(true), true);
						}
					} catch (Exception ignored) {}
					if (fovDegrees < 1.0 || fovDegrees > 170.0 || !Double.isFinite(fovDegrees)) fovDegrees = 70.0;
					float fovRad = (float)(fovDegrees * Math.PI / 180.0);
					float tanHalfFov = (float) Math.tan(fovRad / 2.0);
					var window = mcClient.getWindow();
					float aspect = (float) window.getWidth() / (float) window.getHeight();
					float newM00 = 1.0f / (aspect * tanHalfFov);
					float newM11 = 1.0f / tanHalfFov;

					// Diagnostic: log the correction
					if (diagLogCount < 5 || (deferred1MvLogCount > 0 && deferred1MvLogCount % 300 == 0)) {
						Iris.logger.info("[PROJ_REBUILD] prog='{}' old m00={} m11={} -> new m00={} m11={} | fov={}deg aspect={} winW={} winH={}",
							this.name,
							String.format("%.6f", proj.m00()), String.format("%.6f", proj.m11()),
							String.format("%.6f", newM00), String.format("%.6f", newM11),
							String.format("%.2f", fovDegrees), String.format("%.4f", aspect),
							window.getWidth(), window.getHeight());
					}

					proj.m00(newM00);
					proj.m11(newM11);
				}

				// Fix infinite-far depth elements (m22, m32) with finite far
				// Vulkan zZeroToOne: m22 = -far/(far-near), m32 = -far*near/(far-near)
				proj.m22(-far / (far - near));
				proj.m32(-far * near / (far - near));
			}

			// VulkanMod's Matrix4fM mixin forces all projection matrices to use
			// Vulkan [0,1] depth range (zZeroToOne=true). But shader packs expect
			// OpenGL [-1,1] depth range because they reconstruct position via:
			//   clipPos.z = depth * 2.0 - 1.0  (converts [0,1] buffer to [-1,1] NDC)
			//   viewPos = gbufferProjectionInverse * clipPos
			// Convert from Vulkan [0,1] to OpenGL [-1,1] depth range:
			//   m22_gl = 2*m22_vk - m23_vk
			//   m32_gl = 2*m32_vk - m33_vk
			vulkanToOpenGLDepthRange(proj);

			// setInvertedViewport() UNDOES the normal Y-flip that setViewport() applies.
			// setViewport(x,y,w,h) applies viewport.y=h+y, viewport.height=-h (negative).
			// setInvertedViewport(x,y,w,h) calls setViewport(x,y+h,w,-h), which resolves
			// to viewport.y=0, viewport.height=+h (POSITIVE = standard Vulkan, no flip).
			// Result: texCoord.y=0 at TOP, texCoord.y=1 at BOTTOM (Vulkan convention).
			// screenPos.y = texCoord.y*2-1 = -1 at top (should be +1 in GL convention).
			// Column-1 negation on projInverse corrects this.

			float[] arr = new float[16];
			proj.get(arr);

			int projOff = uniformBuffer.getFieldOffset("gbufferProjection");
			int projInvOff = uniformBuffer.getFieldOffset("gbufferProjectionInverse");

			// Diagnostic: log projection for first few frames AND periodically for deferred1
			boolean logProj = diagLogCount < 3;
			if (!logProj && "deferred1".equals(this.name)) {
				logProj = (deferred1MvLogCount <= 5 || deferred1MvLogCount % 300 == 0);
			}
			if (logProj) {
				if (diagLogCount < 3) diagLogCount++;
				Matrix4f invCheck = new Matrix4f(proj).invert();
				Iris.logger.info("[COMP_PROJ] prog='{}' raw=[{},{},{},{},{},{}] final=[{},{},{},{},{},{}] inv11={} off=proj:{} projInv:{}",
					this.name,
					String.format("%.4f", rawM00), String.format("%.4f", rawM11),
					String.format("%.6f", rawM22), String.format("%.4f", rawM23),
					String.format("%.6f", rawM32), String.format("%.4f", rawM33),
					String.format("%.4f", proj.m00()), String.format("%.4f", proj.m11()),
					String.format("%.6f", proj.m22()), String.format("%.4f", proj.m23()),
					String.format("%.6f", proj.m32()), String.format("%.4f", proj.m33()),
					String.format("%.4f", invCheck.m11()),
					projOff, projInvOff);
			}

			writeMatIfPresent("gbufferProjection", arr);
			// Save this frame's converted projection for next frame (only first call per frame)
			if (savedProjArr == null) savedProjArr = arr.clone();
			// Inverse — with column-1 negation to correct Vulkan texCoord Y.
			// texCoord.y=0 at top → screenPos.y=-1 at top (GL expects +1).
			// Negating column 1 flips the Y interpretation in the inverse.
			Matrix4f inv = new Matrix4f(proj);
			inv.invert();
			inv.m10(-inv.m10());
			inv.m11(-inv.m11());
			inv.m12(-inv.m12());
			inv.m13(-inv.m13());
			inv.get(arr);
			writeMatIfPresent("gbufferProjectionInverse", arr);

			// Diagnostic: readback projection inverse to verify UBO data integrity
			if (diagLogCount <= 3 && projInvOff >= 0) {
				float[] pInvRB = uniformBuffer.readbackMat4f(projInvOff);
				if (pInvRB != null) {
					// Critical check: col2[3] (arr[11]) should be ~-10 (=1/m32), col3[2] (arr[14]) should be ~-1
					// If swapped, matrix is transposed or inverse is wrong
					Iris.logger.info("[DIAG_PROJINV] prog='{}' col2=({},{},{},{}) col3=({},{},{},{})",
						this.name,
						String.format("%.4f", pInvRB[8]), String.format("%.4f", pInvRB[9]),
						String.format("%.4f", pInvRB[10]), String.format("%.4f", pInvRB[11]),
						String.format("%.4f", pInvRB[12]), String.format("%.4f", pInvRB[13]),
						String.format("%.4f", pInvRB[14]), String.format("%.4f", pInvRB[15]));

					// Simulate shader viewPos reconstruction for top-center sky pixel
					// setInvertedViewport gives standard VK viewport: texCoord.y=0 at top.
					// texCoord=(0.5, 0.0) at top-center → screenPos=(0, -1, 1, 1)
					// Column 1 of projInverse is negated to correct VK texCoord Y.
					float ndc_x = 0.0f, ndc_y = -1.0f, ndc_z = 1.0f, ndc_w = 1.0f;
					float vx = pInvRB[0]*ndc_x + pInvRB[4]*ndc_y + pInvRB[8]*ndc_z + pInvRB[12]*ndc_w;
					float vy = pInvRB[1]*ndc_x + pInvRB[5]*ndc_y + pInvRB[9]*ndc_z + pInvRB[13]*ndc_w;
					float vz = pInvRB[2]*ndc_x + pInvRB[6]*ndc_y + pInvRB[10]*ndc_z + pInvRB[14]*ndc_w;
					float vw = pInvRB[3]*ndc_x + pInvRB[7]*ndc_y + pInvRB[11]*ndc_z + pInvRB[15]*ndc_w;
					float viewY = vy / vw;
					float viewZ = vz / vw;
					float len = (float) Math.sqrt(viewY*viewY + viewZ*viewZ);
					float VdotU_approx = viewY / len; // assuming level camera, upVec=(0,1,0)
					Iris.logger.info("[DIAG_VIEWPOS] prog='{}' topCenter: viewY={} viewZ={} vw={} VdotU_approx={}",
						this.name,
						String.format("%.4f", viewY), String.format("%.4f", viewZ),
						String.format("%.6f", vw), String.format("%.4f", VdotU_approx));
				}

				// Also readback MV and MV inverse for upVec verification
				int mvOff = uniformBuffer.getFieldOffset("gbufferModelView");
				float[] mvRB = uniformBuffer.readbackMat4f(mvOff);
				if (mvRB != null) {
					// Column 1 = upVec direction: (mvRB[4], mvRB[5], mvRB[6])
					Iris.logger.info("[DIAG_MV] prog='{}' col1(upVec)=({},{},{}) col0(eastVec)=({},{},{})",
						this.name,
						String.format("%.4f", mvRB[4]), String.format("%.4f", mvRB[5]), String.format("%.4f", mvRB[6]),
						String.format("%.4f", mvRB[0]), String.format("%.4f", mvRB[1]), String.format("%.4f", mvRB[2]));
				}
				// Readback MV inverse to check if it's actually written (not identity/zero)
				int mvInvOff = uniformBuffer.getFieldOffset("gbufferModelViewInverse");
				float[] mvInvRB = uniformBuffer.readbackMat4f(mvInvOff);
				Iris.logger.info("[DIAG_MVINV] prog='{}' off={} data={}", this.name, mvInvOff,
					mvInvRB != null ? String.format("col0=(%.4f,%.4f,%.4f) col1=(%.4f,%.4f,%.4f) col3=(%.4f,%.4f,%.4f)",
						mvInvRB[0], mvInvRB[1], mvInvRB[2], mvInvRB[4], mvInvRB[5], mvInvRB[6],
						mvInvRB[12], mvInvRB[13], mvInvRB[14]) : "null");
			}

			// Previous frame projection (same convention as gbufferProjection: no Y-flip)
			if (prevProjArr != null) {
				writeMatIfPresent("gbufferPreviousProjection", prevProjArr);
			} else {
				// First frame: use current projection as-is
				proj.get(arr);
				writeMatIfPresent("gbufferPreviousProjection", arr);
			}
		}

		// Periodic FOV tracking — log every 120 calls to monitor if FOV stabilizes
		fovLogCounter++;
		if (fovLogCounter % 120 == 1) {
			Minecraft mcFov = Minecraft.getInstance();
			double fovCheck = -1;
			try {
				if (mcFov.gameRenderer != null) {
					fovCheck = ((net.irisshaders.iris.mixin.GameRendererAccessor) mcFov.gameRenderer)
						.invokeGetFov(mcFov.gameRenderer.getMainCamera(),
							mcFov.getTimer().getGameTimeDeltaPartialTick(true), true);
				}
			} catch (Exception ignored) {}
			Matrix4fc currentProj = CapturedRenderingState.INSTANCE.getGbufferProjection();
			Iris.logger.info("[FOV_TRACK] frame={} fovDeg={} projM00={} projM11={} prog='{}'",
				fovLogCounter,
				String.format("%.2f", fovCheck),
				currentProj != null ? String.format("%.4f", currentProj.m00()) : "null",
				currentProj != null ? String.format("%.4f", currentProj.m11()) : "null",
				this.name);
		}

		// cameraPosition — from the Minecraft camera entity
		Minecraft mc = Minecraft.getInstance();
		if (mc.gameRenderer != null && mc.gameRenderer.getMainCamera() != null) {
			var camPos = mc.gameRenderer.getMainCamera().getPosition();
			writeVec3IfPresent("cameraPosition", (float) camPos.x, (float) camPos.y, (float) camPos.z);
		}

		// viewWidth / viewHeight
		if (mc.getWindow() != null) {
			writeFloatIfPresent("viewWidth", (float) mc.getWindow().getWidth());
			writeFloatIfPresent("viewHeight", (float) mc.getWindow().getHeight());
		}

		// near / far
		writeFloatIfPresent("near", 0.05f);
		if (mc.gameRenderer != null) {
			writeFloatIfPresent("far", mc.gameRenderer.getRenderDistance());
		}

		// Shadow matrices — read from ShadowRenderer's static fields
		Matrix4f shadowMV = net.irisshaders.iris.shadows.ShadowRenderer.MODELVIEW;
		Matrix4f shadowProj = net.irisshaders.iris.shadows.ShadowRenderer.PROJECTION;
		if (shadowMV != null) {
			float[] arr = new float[16];
			shadowMV.get(arr);
			writeMatIfPresent("shadowModelView", arr);
			Matrix4f smvInv = new Matrix4f(shadowMV).invert();
			smvInv.get(arr);
			writeMatIfPresent("shadowModelViewInverse", arr);
		}
		if (shadowProj != null) {
			Matrix4f sp = new Matrix4f(shadowProj);
			// ShadowMatrices.createOrthoMatrix() uses raw column values (NOT .ortho()),
			// so VulkanMod's Matrix4fM mixin does NOT affect it — it's already OpenGL-style.
			// Do NOT apply vulkanToOpenGLDepthRange() — that would double-convert.
			// Do NOT negate m11 — composite/final fragment shaders sample the shadow texture
			// directly (no rasterizer Y-flip), so shadow UV Y must match the stored layout.
			// The shadow vertex shader has m11 negated via the terrain UBO (which cancels
			// VulkanMod's negative viewport during shadow rendering).

			float[] arr = new float[16];
			sp.get(arr);
			writeMatIfPresent("shadowProjection", arr);
			Matrix4f spInv = new Matrix4f(sp).invert();
			spInv.get(arr);
			writeMatIfPresent("shadowProjectionInverse", arr);
		}

		// Celestial light positions — CRITICAL for deferred lighting.
		// ProgramUniforms/CelestialUniforms should write these via callbacks, but
		// we also write them explicitly as a safety net. Without sunPosition,
		// deferred shaders compute sunVec = normalize(vec3(0)) = NaN → black output.
		if (gbufferMV != null && mc.level != null) {
			float tickDelta = CapturedRenderingState.INSTANCE.getTickDelta();
			float skyAngle = mc.level.getTimeOfDay(tickDelta);
			float sunAngle = skyAngle < 0.75f ? skyAngle + 0.25f : skyAngle - 0.75f;

			float sunPathRotation = 0.0f;
			try {
				var pm = net.irisshaders.iris.Iris.getPipelineManager();
				if (pm != null) {
					var wp = pm.getPipelineNullable();
					if (wp instanceof net.irisshaders.iris.pipeline.IrisRenderingPipeline irp) {
						sunPathRotation = irp.getSunPathRotation();
					}
				}
			} catch (Exception ignored) {}

			Matrix4f celestial = new Matrix4f(gbufferMV);
			celestial.rotateY((float) Math.toRadians(-90.0));
			celestial.rotateZ((float) Math.toRadians(sunPathRotation));
			celestial.rotateX((float) Math.toRadians(skyAngle * 360.0f));

			org.joml.Vector4f sunPos = new org.joml.Vector4f(0, 100, 0, 0);
			celestial.transform(sunPos);
			writeVec3IfPresent("sunPosition", sunPos.x(), sunPos.y(), sunPos.z());

			org.joml.Vector4f moonPos = new org.joml.Vector4f(0, -100, 0, 0);
			celestial.transform(moonPos);
			writeVec3IfPresent("moonPosition", moonPos.x(), moonPos.y(), moonPos.z());

			boolean isDay = sunAngle <= 0.5f;
			if (isDay) {
				writeVec3IfPresent("shadowLightPosition", sunPos.x(), sunPos.y(), sunPos.z());
			} else {
				writeVec3IfPresent("shadowLightPosition", moonPos.x(), moonPos.y(), moonPos.z());
			}

			writeFloatIfPresent("sunAngle", sunAngle);
			float shadowAngle = isDay ? sunAngle : sunAngle - 0.5f;
			writeFloatIfPresent("shadowAngle", shadowAngle);

			// upPosition: modelView * rotY(-90) * (0, 100, 0, 0)
			Matrix4f preCelestial = new Matrix4f(gbufferMV);
			preCelestial.rotateY((float) Math.toRadians(-90.0));
			org.joml.Vector4f upPos = new org.joml.Vector4f(0, 100, 0, 0);
			preCelestial.transform(upPos);
			writeVec3IfPresent("upPosition", upPos.x(), upPos.y(), upPos.z());
		}

	}

	/**
	 * Converts a projection matrix from Vulkan [0,1] depth range to OpenGL [-1,1]
	 * depth range. VulkanMod's Matrix4fM mixin forces zZeroToOne=true on all
	 * projection matrices, but shader packs expect OpenGL-style depth.
	 *
	 * The conversion is derived from: P_gl = D_inv * P_vk, where D maps OpenGL
	 * clip-space Z to Vulkan clip-space Z: z_vk = 0.5*z_gl + 0.5*w.
	 * Only the Z row (row 2) of the projection matrix is affected:
	 *   m22_gl = 2*m22_vk - m23_vk
	 *   m32_gl = 2*m32_vk - m33_vk
	 */
	private static void vulkanToOpenGLDepthRange(Matrix4f proj) {
		proj.m22(2.0f * proj.m22() - proj.m23());
		proj.m32(2.0f * proj.m32() - proj.m33());
	}

	private void writeMatIfPresent(String name, float[] arr) {
		int off = uniformBuffer.getFieldOffset(name);
		if (off >= 0) uniformBuffer.writeMat4f(off, arr);
	}

	private void writeVec3IfPresent(String name, float x, float y, float z) {
		int off = uniformBuffer.getFieldOffset(name);
		if (off >= 0) uniformBuffer.writeVec3f(off, x, y, z);
	}

	private void writeFloatIfPresent(String name, float val) {
		int off = uniformBuffer.getFieldOffset(name);
		if (off >= 0) uniformBuffer.writeFloat(off, val);
	}

	public GraphicsPipeline getPipeline() {
		return pipeline;
	}

	public void destroyInternal() {
		if (uniformBuffer != null) {
			uniformBuffer.free();
		}
	}

	/**
	 * @return the program ID (tracking ID in Vulkan port)
	 * @deprecated this should be encapsulated eventually
	 */
	@Deprecated
	public int getProgramId() {
		return getGlId();
	}

	public int getActiveImages() {
		return images.getActiveImages();
	}
}
