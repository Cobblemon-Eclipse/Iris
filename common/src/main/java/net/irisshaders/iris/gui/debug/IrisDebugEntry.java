package net.irisshaders.iris.gui.debug;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gui.option.IrisVideoSettings;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

public class IrisDebugEntry implements DebugScreenEntry {
	@Override
	public void display(DebugScreenDisplayer debugScreenDisplayer, @Nullable Level level, @Nullable LevelChunk levelChunk, @Nullable LevelChunk levelChunk2) {
		debugScreenDisplayer.addToGroup(ResourceLocation.fromNamespaceAndPath("iris", "name"), "[" + Iris.MODNAME + "] Version: " + Iris.getFormattedVersion());

		if (Iris.getIrisConfig().areShadersEnabled()) {
			debugScreenDisplayer.addToGroup(ResourceLocation.fromNamespaceAndPath("iris", "name"), "[" + Iris.MODNAME + "] Shaderpack: " + Iris.getCurrentPackName() + (Iris.isFallback() ? " (fallback)" : ""));
			Iris.getCurrentPack().ifPresent(pack -> debugScreenDisplayer.addToGroup(ResourceLocation.fromNamespaceAndPath("iris", "name"), "[" + Iris.MODNAME + "] " + pack.getProfileInfo()));
			debugScreenDisplayer.addToGroup(ResourceLocation.fromNamespaceAndPath("iris", "name"), "[" + Iris.MODNAME + "] Color space: " + IrisVideoSettings.colorSpace.name());
		} else {
			debugScreenDisplayer.addToGroup(ResourceLocation.fromNamespaceAndPath("iris", "name"), "[" + Iris.MODNAME + "] Shaders are disabled");
		}

		//messages.add(3, "Direct Buffers: +" + iris$humanReadableByteCountBin(iris$directPool.getMemoryUsed()));

	}
}
