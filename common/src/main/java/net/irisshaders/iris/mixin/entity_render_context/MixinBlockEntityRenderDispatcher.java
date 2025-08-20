package net.irisshaders.iris.mixin.entity_render_context;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.irisshaders.iris.layer.BlockEntityRenderStateShard;
import net.irisshaders.iris.layer.BufferSourceWrapper;
import net.irisshaders.iris.layer.OuterWrappedRenderType;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.vertices.ImmediateState;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wraps block entity rendering functions in order to create additional render layers
 * that provide context to shaders about what block entity is currently being
 * rendered.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public class MixinBlockEntityRenderDispatcher {
	@Unique
	private static final String RUN_REPORTED =
		"Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;tryRender(Lnet/minecraft/world/level/block/entity/BlockEntity;Ljava/lang/Runnable;)V";

	// I inject here in the method so that:
	//
	// 1. we can know that some checks we need have already been done
	// 2. if someone cancels this method hopefully it gets cancelled before this point, so we
	//    aren't running any redundant computations.
	//
	// NOTE: This is the last location that we can inject at, because the MultiBufferSource variable gets
	// captured by the lambda shortly afterwards, and therefore our ModifyVariable call becomes ineffective!
	@WrapMethod(method = "submit")
	private <E extends BlockEntity> void iris$wrapSubmission(E blockEntity, float f, PoseStack poseStack, ModelFeatureRenderer.CrumblingOverlay crumblingOverlay, SubmitNodeCollector submitNodeCollector, Operation<Void> original) {
		BlockState state = blockEntity.getBlockState();

		ImmediateState.isRenderingBEs = true;

		Object2IntMap<BlockState> blockStateIds = WorldRenderingSettings.INSTANCE.getBlockStateIds();

		if (blockStateIds == null || !ImmediateState.isRenderingLevel) {
			original.call(blockEntity, f, poseStack, crumblingOverlay, submitNodeCollector);

			return;
		}

		int intId = blockStateIds.getOrDefault(state, -1);

		CapturedRenderingState.INSTANCE.setCurrentBlockEntity(intId);

		original.call(blockEntity, f, poseStack, crumblingOverlay, submitNodeCollector);

		CapturedRenderingState.INSTANCE.setCurrentBlockEntity(0);
		ImmediateState.isRenderingBEs = false;
	}
}
