package net.irisshaders.iris.mixinterface;

public interface GpuTextureExtension {
	default void bindToUnit(int unit) {
		throw new IllegalStateException();
	}
}
