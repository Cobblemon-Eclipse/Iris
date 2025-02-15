package net.irisshaders.iris.pathways.colorspace;

import com.mojang.blaze3d.textures.GpuTexture;

public interface ColorSpaceConverter {
	void rebuildProgram(int width, int height, ColorSpace colorSpace);

	void process(GpuTexture target);
}
