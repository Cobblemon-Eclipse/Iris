package net.irisshaders.iris.gl;

import com.mojang.blaze3d.platform.GlStateManager;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.framebuffer.ViewportData;
import net.irisshaders.iris.gl.program.Program;
import org.joml.Vector2i;

public record GlCustomState(Program program, GlFramebuffer framebuffer, BlendModeOverride blendModeOverride, ViewportData viewportScale, Vector2i size) {
	public void apply() {
		int viewWidth = size.x;
		int viewHeight = size.y;
		float scaledWidth = viewWidth * viewportScale.scale();
		float scaledHeight = viewHeight * viewportScale.scale();
		int beginWidth = (int) (viewWidth * viewportScale.viewportX());
		int beginHeight = (int) (viewHeight * viewportScale.viewportY());
		GlStateManager._viewport(beginWidth, beginHeight, (int) scaledWidth, (int) scaledHeight);

		framebuffer.bind();
		//program.use();
		BlendModeOverride.restore();

		if (blendModeOverride != null) {
			blendModeOverride.apply();
		} else {
			GlStateManager._disableBlend();
		}
	}
}
