package net.irisshaders.iris.mixin.entity_render_context;

import net.irisshaders.iris.mixinterface.ModelStorage;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.renderer.SubmitNodeStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(SubmitNodeStorage.ModelSubmit.class)
public class MixinModelStorage implements ModelStorage {
	@Unique
	private int entityId, beId, itemId;

	@Override
	public void iris$capture() {
		entityId = CapturedRenderingState.INSTANCE.getCurrentRenderedEntity();
		beId = CapturedRenderingState.INSTANCE.getCurrentRenderedBlockEntity();
		itemId = CapturedRenderingState.INSTANCE.getCurrentRenderedItem();
	}

	@Override
	public void iris$set() {
		CapturedRenderingState.INSTANCE.setCurrentEntity(entityId);
		CapturedRenderingState.INSTANCE.setCurrentBlockEntity(beId);
		CapturedRenderingState.INSTANCE.setCurrentRenderedItem(itemId);
	}
}
