package net.irisshaders.iris.mixinterface;

import net.irisshaders.iris.gl.GlCustomState;

public interface RenderPassExtension {
	default void iris$setCustomState(GlCustomState program) {
		throw new AssertionError("Something has gone horribly wrong in interface injection");
	}

	default GlCustomState iris$getCustomState() {
		throw new AssertionError("Something has gone horribly wrong in interface injection");
	}
}
