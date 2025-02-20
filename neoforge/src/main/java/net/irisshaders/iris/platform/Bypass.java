package net.irisshaders.iris.platform;

import net.irisshaders.iris.vertices.ImmediateState;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.ShaderProgram;

import java.util.function.Supplier;

public class Bypass extends RenderStateShard.ShaderStateShard {
	public Bypass(ShaderProgram original) {
		super(original);
	}

	@Override
	public void setupRenderState() {
		ImmediateState.bypass = true;
		super.setupRenderState();
		ImmediateState.bypass = false;
	}
}
