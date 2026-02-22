package net.irisshaders.iris.uniforms;

import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;

public class CapturedRenderingState {
	public static final CapturedRenderingState INSTANCE = new CapturedRenderingState();

	private static final Vector3d ZERO_VECTOR_3d = new Vector3d();
	private static int projLogCount = 0;

	private Matrix4fc gbufferModelView;
	private Matrix4fc gbufferProjection;
	private Vector3d fogColor;
	private float fogDensity;
	private float darknessLightFactor;
	private float tickDelta;
	private float realTickDelta;
	private int currentRenderedBlockEntity;

	private int currentRenderedEntity = -1;
	private int currentRenderedItem = -1;

	private float currentAlphaTest;
	private float cloudTime;

	private CapturedRenderingState() {
	}

	public Matrix4fc getGbufferModelView() {
		return gbufferModelView;
	}

	public void setGbufferModelView(Matrix4fc gbufferModelView) {
		// Defensive copy: the caller (MixinLevelRenderer) passes the renderLevel
		// modelView parameter by reference. Minecraft may mutate that Matrix4f
		// during the frame (PoseStack operations, etc.), so by composite/deferred
		// time the reference could point at a modified matrix. Copy it now.
		this.gbufferModelView = new Matrix4f(gbufferModelView);
	}

	// Diagnostic: track getter calls to catch stale/modified values
	private static int projGetLogCount = 0;

	public Matrix4fc getGbufferProjection() {
		projGetLogCount++;
		if (gbufferProjection != null && (projGetLogCount <= 10 || projGetLogCount % 600 == 0)) {
			org.slf4j.LoggerFactory.getLogger("PROJ_TRACE").info(
				"[PROJ_GET] #{} m00={} m11={} hash={}",
				projGetLogCount,
				String.format("%.6f", gbufferProjection.m00()),
				String.format("%.6f", gbufferProjection.m11()),
				System.identityHashCode(gbufferProjection));
		}
		return gbufferProjection;
	}

	public void setGbufferProjection(Matrix4f gbufferProjection) {
		// DIAGNOSTIC: Log incoming projection values BEFORE any processing
		if (projLogCount < 10) {
			projLogCount++;
			float im00 = gbufferProjection.m00(), im11 = gbufferProjection.m11();
			float im22 = gbufferProjection.m22(), im23 = gbufferProjection.m23();
			float im32 = gbufferProjection.m32(), im33 = gbufferProjection.m33();
			boolean m00fin = Float.isFinite(im00), m11fin = Float.isFinite(im11);
			// Compute implied FOV and aspect from m11/m00
			float impliedFov = m11fin ? (float)(2.0 * Math.atan(1.0 / im11) * 180.0 / Math.PI) : Float.NaN;
			float impliedAspect = (m00fin && m11fin && im00 != 0) ? im11 / im00 : Float.NaN;
			org.slf4j.LoggerFactory.getLogger("PROJ_TRACE").info(
				"[PROJ_IN] #{} m00={} m11={} m22={} m23={} m32={} m33={} | m00_finite={} m11_finite={} | impliedFOV={}deg impliedAspect={}",
				projLogCount, im00, im11, im22, im23, im32, im33,
				m00fin, m11fin, String.format("%.2f", impliedFov), String.format("%.4f", impliedAspect));
		}

		Matrix4f proj = new Matrix4f(gbufferProjection);
		// VulkanMod uses infinite far plane, producing Infinity in m00/m11.
		// Fix here so ALL consumers (MatrixUniforms, writeGbufferUniforms,
		// customUniforms) get finite values and projectionInverse doesn't have NaN.
		if (!Float.isFinite(proj.m00()) || !Float.isFinite(proj.m11())) {
			Minecraft mc = Minecraft.getInstance();
			double fovDegrees = 70.0;
			try {
				if (mc.gameRenderer != null) {
					fovDegrees = ((net.irisshaders.iris.mixin.GameRendererAccessor) mc.gameRenderer)
						.invokeGetFov(mc.gameRenderer.getMainCamera(),
							mc.getTimer().getGameTimeDeltaPartialTick(true), true);
				}
			} catch (Exception ignored) {}
			// Sanity check: valid Minecraft FOV is 30-170 degrees. Values outside
			// this range indicate the camera/renderer isn't ready yet (e.g., at HEAD
			// of renderLevel before camera setup). Fall back to 70 degrees.
			if (fovDegrees < 30.0 || fovDegrees > 170.0 || !Double.isFinite(fovDegrees)) {
				fovDegrees = 70.0;
			}
			float fovRad = (float)(fovDegrees * Math.PI / 180.0);
			float tanHalfFov = (float) Math.tan(fovRad / 2.0);
			var window = mc.getWindow();
			float aspect = (float) window.getWidth() / (float) window.getHeight();
			proj.m00(1.0f / (aspect * tanHalfFov));
			proj.m11(1.0f / tanHalfFov);
			if (projLogCount <= 10) {
				org.slf4j.LoggerFactory.getLogger("PROJ_TRACE").info(
					"[PROJ_FIXUP] m00/m11 were INF! getFov={}deg (sanity-checked) aspect={} -> m00={} m11={}",
					fovDegrees, aspect, proj.m00(), proj.m11());
			}
		}
		// Also fix infinite m22/m32 from infinite far plane
		if (proj.m23() != 0 && (!Float.isFinite(proj.m22()) || !Float.isFinite(proj.m32()))) {
			Minecraft mc = Minecraft.getInstance();
			float far = mc.gameRenderer != null ? mc.gameRenderer.getRenderDistance() : 256.0f;
			float near = 0.05f;
			proj.m22(-far / (far - near));
			proj.m32(-far * near / (far - near));
		}

		// Convert from VK [0,1] depth to GL [-1,1] depth convention.
		// Iris shader packs are written for OpenGL and reconstruct position via:
		//   vec4 viewPos = gbufferProjectionInverse * (screenPos * 2.0 - 1.0);
		// The "* 2.0 - 1.0" maps gl_FragCoord.z from [0,1] to [-1,1] (GL NDC).
		// gbufferProjectionInverse must be the inverse of a GL-convention projection
		// for the reconstruction to be correct. Without this, depth reconstruction
		// is wrong and the sky renders with a horizontal bar artifact.
		// Note: iris_ProjMat stays in VK depth (for correct vertex gl_Position).
		{
			float m22 = proj.m22();
			float m23 = proj.m23(); // -1 for perspective, 0 for ortho
			float m32 = proj.m32();
			float m33 = proj.m33(); // 0 for perspective, 1 for ortho
			if (Float.isFinite(m22) && Float.isFinite(m32)) {
				proj.m22(2.0f * m22 - m23);
				proj.m32(2.0f * m32 - m33);
			}
		}

		this.gbufferProjection = proj;

		// DIAGNOSTIC: Log STORED value to confirm m00 after all processing
		if (projLogCount <= 30 || projLogCount % 600 == 0) {
			org.slf4j.LoggerFactory.getLogger("PROJ_TRACE").info(
				"[PROJ_STORED] #{} m00={} m11={} m22={} m32={} hash={}",
				projLogCount,
				String.format("%.6f", proj.m00()),
				String.format("%.6f", proj.m11()),
				String.format("%.6f", proj.m22()),
				String.format("%.6f", proj.m32()),
				System.identityHashCode(proj));
		}
	}

	public Vector3d getFogColor() {
		if (Minecraft.getInstance().level == null || fogColor == null) {
			return ZERO_VECTOR_3d;
		}

		return fogColor;
	}

	public void setFogColor(float red, float green, float blue) {
		fogColor = new Vector3d(red, green, blue);
	}

	public float getFogDensity() {
		return fogDensity;
	}

	public void setFogDensity(float fogDensity) {
		this.fogDensity = fogDensity;
	}

	public float getTickDelta() {
		return tickDelta;
	}

	public void setTickDelta(float tickDelta) {
		this.tickDelta = tickDelta;
	}

	public float getRealTickDelta() {
		return realTickDelta;
	}

	public void setRealTickDelta(float tickDelta) {
		this.realTickDelta = tickDelta;
	}

	public void setCurrentBlockEntity(int entity) {
		this.currentRenderedBlockEntity = entity;
	}

	public int getCurrentRenderedBlockEntity() {
		return currentRenderedBlockEntity;
	}

	public void setCurrentEntity(int entity) {
		this.currentRenderedEntity = entity;
	}

	public int getCurrentRenderedEntity() {
		return currentRenderedEntity;
	}

	public int getCurrentRenderedItem() {
		return currentRenderedItem;
	}

	public void setCurrentRenderedItem(int item) {
		this.currentRenderedItem = item;
	}

	public float getCurrentAlphaTest() {
		return currentAlphaTest;
	}

	public void setCurrentAlphaTest(float alphaTest) {
		this.currentAlphaTest = alphaTest;
	}

	public float getDarknessLightFactor() {
		return darknessLightFactor;
	}

	public void setDarknessLightFactor(float factor) {
		darknessLightFactor = factor;
	}

	public float getCloudTime() {
		return this.cloudTime;
	}

	public void setCloudTime(float cloudTime) {
		this.cloudTime = cloudTime;
	}
}
