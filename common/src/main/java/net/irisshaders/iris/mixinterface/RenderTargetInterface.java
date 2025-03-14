package net.irisshaders.iris.mixinterface;

public interface RenderTargetInterface {
	default void bindFramebuffer() {
		throw new AssertionError("Impossible to access.");
	}
}
