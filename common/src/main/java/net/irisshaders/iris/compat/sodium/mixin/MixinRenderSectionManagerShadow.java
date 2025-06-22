package net.irisshaders.iris.compat.sodium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.TreeSectionCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.tree.RemovableMultiForest;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.irisshaders.iris.mixinterface.ShadowRenderRegion;
import net.irisshaders.iris.mixinterface.ShadowRenderRegion;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.minecraft.client.Camera;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderSectionManager.class)
public abstract class MixinRenderSectionManagerShadow {
	@Shadow(remap = false)
	private @NotNull SortedRenderLists renderLists;

	@Shadow(remap = false)
	private boolean needsGraphUpdate;

	@Shadow
	@Final
	private RenderRegionManager regions;
	@Shadow
	private int frame;

	@Unique
	private @NotNull SortedRenderLists shadowRenderLists = SortedRenderLists.empty();

	@Unique
	private boolean shadowNeedsRenderListUpdate = true;

	@Unique
	private boolean renderListStateIsShadow = false;

	@Inject(method = "needsUpdate", at = @At(value = "HEAD"))
	private void notifyChangedCamera(CallbackInfoReturnable<Boolean> cir) {
		this.shadowNeedsRenderListUpdate = true;
	}

	@Inject(method = "update", at = @At(target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;createTerrainRenderList(Lnet/minecraft/client/Camera;Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;Lnet/caffeinemc/mods/sodium/client/util/FogParameters;IZ)Z", value = "INVOKE"))
	private void updateRenderLists(Camera camera, Viewport viewport, FogParameters fogParameters, boolean spectator, CallbackInfo ci) {
		this.shadowNeedsRenderListUpdate |= this.needsGraphUpdate;
	}

	@Shadow(remap = false)
	public abstract int getVisibleChunkCount();

	@Shadow(remap = false)
	public abstract int getTotalSections();

	@Shadow
	@Final
	private RemovableMultiForest renderableSectionTree;

	@Shadow
	@Final
	private Long2ReferenceMap<RenderSection> sectionByPosition;

	@Shadow
	protected abstract float getRenderDistance();

	@Inject(method = "createTerrainRenderList", at = @At("HEAD"), cancellable = true)
	private void updateShadowRenderLists(Camera camera, Viewport viewport, FogParameters fogParameters, int frame, boolean spectator, CallbackInfoReturnable<Boolean> ci) {
		if (!ShadowRenderingState.areShadowsCurrentlyBeingRendered()) {
			if (this.renderListStateIsShadow) {
				for (var region : this.regions.getLoadedRegions()) {
					((net.irisshaders.iris.mixinterface.ShadowRenderRegion) region).swapToRegularRenderList();
				}
				this.renderListStateIsShadow = false;
			}
			return;
		}

		TaskQueueType importantRebuildQueueType = SodiumClientMod.options().performance.chunkBuildDeferMode.getImportantRebuildQueueType();

		if (this.shadowNeedsRenderListUpdate) {
			if (!this.renderListStateIsShadow) {
				for (var region : this.regions.getLoadedRegions()) {
					((ShadowRenderRegion) region).swapToShadowRenderList();
				}
				this.renderListStateIsShadow = true;
			}

			var visitor = new TreeSectionCollector(frame, importantRebuildQueueType, this.sectionByPosition);
			this.renderableSectionTree.prepareForTraversal();
			this.renderableSectionTree.traverse(visitor, viewport, this.getRenderDistance());

			this.shadowRenderLists = visitor.createRenderLists(viewport);
			this.shadowNeedsRenderListUpdate = false;
			ci.setReturnValue(visitor.needsRevisitForPendingUpdates());
		}
	}

	@Inject(method = "updateSectionInfo", at = @At("HEAD"))
	private void updateSectionInfo(RenderSection render, BuiltSectionInfo info, CallbackInfoReturnable<Boolean> cir) {
		this.shadowNeedsRenderListUpdate = true;
	}

	@Inject(method = "onSectionRemoved", at = @At("HEAD"))
	private void onSectionRemoved(int x, int y, int z, CallbackInfo ci) {
		this.shadowNeedsRenderListUpdate = true;
	}

	@Redirect(method = {
		"getRenderLists",
		"getVisibleChunkCount",
		"renderLayer"
	}, at = @At(value = "FIELD", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;renderLists:Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/SortedRenderLists;"), remap = false)
	private SortedRenderLists useShadowRenderList(RenderSectionManager instance) {
		return ShadowRenderingState.areShadowsCurrentlyBeingRendered() ? this.shadowRenderLists : this.renderLists;
	}

	@Inject(method = "getVisibleChunkCount", at = @At("HEAD"), cancellable = true)
	private void iris$useShadowList(CallbackInfoReturnable<Integer> cir) {
		if (ShadowRenderingState.areShadowsCurrentlyBeingRendered()) {
			var sections = 0;
			var iterator = this.shadowRenderLists.iterator();

			while (iterator.hasNext()) {
				var renderList = iterator.next();
				sections += renderList.getSectionsWithGeometryCount();
			}

			cir.setReturnValue(sections);
		}
	}
}
