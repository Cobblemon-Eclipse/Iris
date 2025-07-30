package net.irisshaders.iris.mixin.entity_render_context;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.irisshaders.iris.mixinterface.ModelStorage;
import net.minecraft.client.renderer.SubmitNodeStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

@Mixin(SubmitNodeStorage.class)
public class MixinModelStorageTrigger {
	@WrapOperation(method = "submitModel", at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z"))
	private <E> boolean iris$capture(List instance, E e, Operation<Boolean> original) {
		((ModelStorage) e).iris$capture();
		return original.call(instance, e);
	}

	@WrapOperation(method = "submitCustomGeometry", at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z"))
	private <E> boolean iris$capture2(List instance, E e, Operation<Boolean> original) {
		((ModelStorage) e).iris$capture();
		return original.call(instance, e);
	}
}
