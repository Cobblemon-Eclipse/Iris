package net.irisshaders.iris.pbr.texture;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.mixin.texture.SpriteContentsAnimatedTextureAccessor;
import net.irisshaders.iris.mixin.texture.SpriteContentsFrameInfoAccessor;
import net.irisshaders.iris.mixin.texture.SpriteContentsTickerAccessor;
import net.irisshaders.iris.pbr.loader.AtlasPBRLoader.PBRTextureAtlasSprite;
import net.irisshaders.iris.pbr.util.TextureManipulationUtil;
import net.irisshaders.iris.platform.IrisPlatformHelpers;
import net.irisshaders.iris.vertices.ImmediateState;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteContents.FrameInfo;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;

public class PBRAtlasTexture extends AbstractTexture implements PBRDumpable {
	protected final TextureAtlas atlasTexture;
	protected final PBRType type;
	protected final ResourceLocation location;
	private List<PBRTextureAtlasSprite> sprites = List.of();
	protected final Map<ResourceLocation, PBRTextureAtlasSprite> texturesByNameToAdd = new HashMap<>();
	protected Map<ResourceLocation, PBRTextureAtlasSprite> texturesByName = new HashMap<>();
	private List<SpriteContents.AnimationState> animatedTexturesStates = List.of();
	protected int width;
	protected int height;
	private GpuBuffer spriteUbos;
	private int mipLevelCount;
	private GpuTextureView[] mipViews = new GpuTextureView[0];
	private int maxMipLevel;
	private TextureAtlasSprite missingSprite;

	public PBRAtlasTexture(TextureAtlas atlasTexture, PBRType type) {
		this.atlasTexture = atlasTexture;
		this.type = type;
		location = ResourceLocation.fromNamespaceAndPath(atlasTexture.location().getNamespace(), atlasTexture.location().getPath().replace(".png", "") + type.getSuffix() + ".png");
	}

	public static void syncAnimation(SpriteContents.AnimatedTexture source, SpriteContents.AnimationState target) {
		SpriteContentsTickerAccessor sourceAccessor = (SpriteContentsTickerAccessor) source;
		List<FrameInfo> sourceFrames = ((SpriteContentsAnimatedTextureAccessor) sourceAccessor.getAnimationInfo()).getFrames();

		int ticks = 0;
		for (int f = 0; f < sourceAccessor.getFrame(); f++) {
			ticks += ((SpriteContentsFrameInfoAccessor) (Object) sourceFrames.get(f)).getTime();
		}

		SpriteContentsTickerAccessor targetAccessor = (SpriteContentsTickerAccessor) target;
		List<FrameInfo> targetFrames = ((SpriteContentsAnimatedTextureAccessor) targetAccessor.getAnimationInfo()).getFrames();

		int cycleTime = 0;
		int frameCount = targetFrames.size();
		for (FrameInfo frame : targetFrames) {
			cycleTime += ((SpriteContentsFrameInfoAccessor) (Object) frame).getTime();
		}
		ticks %= cycleTime;

		int targetFrame = 0;
		while (true) {
			int time = ((SpriteContentsFrameInfoAccessor) (Object) targetFrames.get(targetFrame)).getTime();
			if (ticks >= time) {
				targetFrame++;
				ticks -= time;
			} else {
				break;
			}
		}

		targetAccessor.setFrame(targetFrame);
		targetAccessor.setSubFrame(ticks + sourceAccessor.getSubFrame());
	}

	protected static void dumpSpriteNames(Path dir, String fileName, Map<ResourceLocation, PBRTextureAtlasSprite> sprites) {
		Path path = dir.resolve(fileName + ".txt");
		try (BufferedWriter writer = Files.newBufferedWriter(path)) {
			for (Map.Entry<ResourceLocation, PBRTextureAtlasSprite> entry : sprites.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
				PBRTextureAtlasSprite sprite = entry.getValue();
				writer.write(String.format(Locale.ROOT, "%s\tx=%d\ty=%d\tw=%d\th=%d%n", entry.getKey(), sprite.getX(), sprite.getY(), sprite.contents().width(), sprite.contents().height()));
			}
		} catch (IOException e) {
			Iris.logger.warn("Failed to write file {}", path, e);
		}
	}

	public PBRType getType() {
		return type;
	}

	public ResourceLocation getAtlasId() {
		return location;
	}

	public void addSprite(PBRTextureAtlasSprite sprite) {
		texturesByNameToAdd.put(sprite.contents().name(), sprite);
	}

	@Nullable
	public PBRTextureAtlasSprite getSprite(ResourceLocation id) {
		return texturesByName.get(id);
	}

	public boolean tryUpload(int atlasWidth, int atlasHeight, int mipLevel) {
		try {
			upload(atlasWidth, atlasHeight, mipLevel);
			return true;
		} catch (Throwable t) {
			if (IrisPlatformHelpers.getInstance().isDevelopmentEnvironment()) {
				t.printStackTrace();
			}
			return false;
		}
	}

	private void createTexture(int i, int j, int k) {
		Iris.logger.info("Created: {}x{}x{} {}-atlas", i, j, k, this.location);
		GpuDevice gpuDevice = RenderSystem.getDevice();
		this.close();
		this.texture = gpuDevice.createTexture(this.location::toString, 15, TextureFormat.RGBA8, i, j, 1, k + 1);
		this.textureView = gpuDevice.createTextureView(this.texture);
		this.width = i;
		this.height = j;
		this.maxMipLevel = k;
		this.mipLevelCount = k + 1;
		this.mipViews = new GpuTextureView[this.mipLevelCount];
		TextureManipulationUtil.fillWithColor(texture.iris$getGlId(), maxMipLevel, type.getDefaultValue());

		for (int l = 0; l <= this.maxMipLevel; l++) {
			this.mipViews[l] = gpuDevice.createTextureView(this.texture, l, 1);
		}
	}

	public void clearTextureData() {
		this.sprites.forEach(TextureAtlasSprite::close);
		this.sprites = List.of();
		this.animatedTexturesStates = List.of();
		this.texturesByName = Map.of();
		this.missingSprite = null;
	}

	public void upload(int atlasWidth, int atlasHeight, int mipLevel) {
		this.createTexture(atlasWidth, atlasHeight, mipLevel);
		this.clearTextureData();
		this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		this.texturesByName = Map.copyOf(texturesByNameToAdd);
		this.missingSprite = null;
		List<PBRTextureAtlasSprite> list = new ArrayList();
		List<SpriteContents.AnimationState> list2 = new ArrayList();
		int i = (int)texturesByName.values().stream().filter(TextureAtlasSprite::isAnimated).count();
		int j = Mth.roundToward(SpriteContents.UBO_SIZE, RenderSystem.getDevice().getUniformOffsetAlignment());
		int k = j * this.mipLevelCount;
		ByteBuffer byteBuffer = MemoryUtil.memAlloc(i * k);
		int l = 0;

		for (TextureAtlasSprite textureAtlasSprite : texturesByName.values()) {
			if (textureAtlasSprite.isAnimated()) {
				textureAtlasSprite.uploadSpriteUbo(byteBuffer, l * k, this.maxMipLevel, this.width, this.height, j);
				l++;
			}
		}

		GpuBuffer gpuBuffer = l > 0 ? RenderSystem.getDevice().createBuffer(() -> this.location + " sprite UBOs", 128, byteBuffer) : null;
		l = 0;

		for (PBRTextureAtlasSprite textureAtlasSprite2 : texturesByName.values()) {
			list.add(textureAtlasSprite2);
			if (textureAtlasSprite2.isAnimated() && gpuBuffer != null) {
				SpriteContents.AnimationState animationState = textureAtlasSprite2.createAnimationState(gpuBuffer.slice(l * k, k), j);
				l++;
				if (animationState != null) {
					list2.add(animationState);
				}
			}
		}

		this.spriteUbos = gpuBuffer;
		this.sprites = list;
		this.animatedTexturesStates = List.copyOf(list2);
		this.uploadInitialContents();
		if (SharedConstants.DEBUG_DUMP_TEXTURE_ATLAS) {
			Path path = TextureUtil.getDebugTexturePath();

			try {
				Files.createDirectories(path);
				this.dumpContents(this.location, path);
			} catch (IOException var13) {
				Iris.logger.warn("Failed to dump atlas contents to {}", path);
			}
		}

		PBRAtlasHolder pbrHolder = ((TextureAtlasExtension) atlasTexture).getOrCreatePBRHolder();

		switch (type) {
			case NORMAL:
				pbrHolder.setNormalAtlas(this);
				break;
			case SPECULAR:
				pbrHolder.setSpecularAtlas(this);
				break;
		}
	}

	private void uploadInitialContents() {
		GpuDevice gpuDevice = RenderSystem.getDevice();
		int i = Mth.roundToward(SpriteContents.UBO_SIZE, RenderSystem.getDevice().getUniformOffsetAlignment());
		int j = i * this.mipLevelCount;
		GpuSampler gpuSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		List<PBRTextureAtlasSprite> list = this.sprites.stream().filter(textureAtlasSprite -> !textureAtlasSprite.isAnimated()).toList();
		List<GpuTextureView[]> list2 = new ArrayList<>();
		ByteBuffer byteBuffer = MemoryUtil.memAlloc(list.size() * j);

		for (int k = 0; k < list.size(); k++) {
			TextureAtlasSprite textureAtlasSprite = list.get(k);
			textureAtlasSprite.uploadSpriteUbo(byteBuffer, k * j, this.maxMipLevel, this.width, this.height, i);
			GpuTexture gpuTexture = gpuDevice.createTexture(
				() -> textureAtlasSprite.contents().name().toString(),
				5,
				TextureFormat.RGBA8,
				textureAtlasSprite.contents().width(),
				textureAtlasSprite.contents().height(),
				1,
				this.mipLevelCount
			);
			GpuTextureView[] gpuTextureViews = new GpuTextureView[this.mipLevelCount];

			for (int l = 0; l <= this.maxMipLevel; l++) {
				textureAtlasSprite.uploadFirstFrame(gpuTexture, l);
				gpuTextureViews[l] = gpuDevice.createTextureView(gpuTexture);
			}

			list2.add(gpuTextureViews);
		}

		try (GpuBuffer gpuBuffer = gpuDevice.createBuffer(() -> "SpriteAnimationInfo", 128, byteBuffer)) {
			for (int m = 0; m < this.mipLevelCount; m++) {
				try (RenderPass renderPass = RenderSystem.getDevice()
					.createCommandEncoder()
					.createRenderPass(() -> "Animate " + this.location, this.mipViews[m], OptionalInt.empty())) {
					renderPass.setPipeline(RenderPipelines.ANIMATE_SPRITE_BLIT);

					for (int n = 0; n < list.size(); n++) {
						renderPass.bindTexture("Sprite", ((GpuTextureView[])list2.get(n))[m], gpuSampler);
						renderPass.setUniform("SpriteAnimationInfo", gpuBuffer.slice(n * j + m * i, SpriteContents.UBO_SIZE));
						renderPass.draw(0, 6);
					}
				}
			}
		}

		for (GpuTextureView[] gpuTextureViews2 : list2) {
			for (GpuTextureView gpuTextureView : gpuTextureViews2) {
				gpuTextureView.close();
				gpuTextureView.texture().close();
			}
		}

		MemoryUtil.memFree(byteBuffer);
	}

	public void cycleAnimationFrames() {
		if (this.texture != null) {
			for (SpriteContents.AnimationState animationState : this.animatedTexturesStates) {
				animationState.tick();
			}

			if (this.animatedTexturesStates.stream().anyMatch(SpriteContents.AnimationState::needsToDraw)) {
				for (int i = 0; i <= this.maxMipLevel; i++) {
					try (RenderPass renderPass = RenderSystem.getDevice()
						.createCommandEncoder()
						.createRenderPass(() -> "Animate " + this.location, this.mipViews[i], OptionalInt.empty())) {
						for (SpriteContents.AnimationState animationState2 : this.animatedTexturesStates) {
							if (animationState2.needsToDraw()) {
								animationState2.drawToAtlas(renderPass, animationState2.getDrawUbo(i));
							}
						}
					}
				}
			}
		}
	}

	@Override
	public void close() {
		PBRAtlasHolder pbrHolder = ((TextureAtlasExtension) atlasTexture).getPBRHolder();
		if (pbrHolder != null) {
			switch (type) {
				case NORMAL:
					pbrHolder.setNormalAtlas(null);
					break;
				case SPECULAR:
					pbrHolder.setSpecularAtlas(null);
					break;
			}
		}

		super.close();

		for (GpuTextureView gpuTextureView : this.mipViews) {
			gpuTextureView.close();
		}

		for (SpriteContents.AnimationState animationState : this.animatedTexturesStates) {
			animationState.close();
		}

		if (this.spriteUbos != null) {
			this.spriteUbos.close();
			this.spriteUbos = null;
		}
	}

	@Override
	public void dumpContents(ResourceLocation id, Path path) {
		String string = id.toDebugFileName();
		TextureUtil.writeAsPNG(path, string, this.getTexture(), this.maxMipLevel, i -> i);
		dumpSpriteNames(path, string, this.texturesByName);
	}

	@Override
	public ResourceLocation getDefaultDumpLocation() {
		return location;
	}
}
