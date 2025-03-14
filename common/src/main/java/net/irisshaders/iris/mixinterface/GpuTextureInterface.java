package net.irisshaders.iris.mixinterface;

public interface GpuTextureInterface {
	default int getGlId() {
		throw new AssertionError("Not accessible.");
	}
}
