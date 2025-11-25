package net.irisshaders.iris.compat.sodium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.structure.BooleanOptionBuilder;
import net.caffeinemc.mods.sodium.client.gui.SodiumConfigBuilder;
import net.irisshaders.iris.Iris;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SodiumConfigBuilder.class)
public class MixinSodiumOptions {
	@WrapOperation(method = "buildQualityPage", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/api/config/structure/BooleanOptionBuilder;setName(Lnet/minecraft/network/chat/Component;)Lnet/caffeinemc/mods/sodium/api/config/structure/BooleanOptionBuilder;"))
	private BooleanOptionBuilder iris$blockFabulous(BooleanOptionBuilder instance, Component component, Operation<BooleanOptionBuilder> original) {
		if (component.getContents() instanceof TranslatableContents contents && contents.getKey().contains("improved")) {
			return original.call(instance.setEnabledProvider(i -> Iris.getCurrentPack().isEmpty(), ConfigState.UPDATE_ON_REBUILD), component);
		}

		return original.call(instance, component);
	}

	@WrapOperation(method = "buildQualityPage", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/api/config/structure/BooleanOptionBuilder;setTooltip(Lnet/minecraft/network/chat/Component;)Lnet/caffeinemc/mods/sodium/api/config/structure/BooleanOptionBuilder;"))
	private BooleanOptionBuilder iris$blockFabulous2(BooleanOptionBuilder instance, Component component, Operation<BooleanOptionBuilder> original) {
		if (component.getContents() instanceof TranslatableContents contents && contents.getKey().contains("improved")) {
			return instance.setTooltip(i -> {
				if (Iris.getCurrentPack().isPresent()) {
					return Component.literal("This option is not relevant when a shader pack is active.");
				} else {
					return component;
				}
			});
		}

		return original.call(instance, component);
	}
}
