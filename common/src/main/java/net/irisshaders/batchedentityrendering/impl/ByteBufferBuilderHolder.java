package net.irisshaders.batchedentityrendering.impl;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;

public class ByteBufferBuilderHolder implements MemoryTrackingBuffer {
	private final ByteBufferBuilder builder;
	private long lastUse;
	private boolean wasUsedSince;

	public ByteBufferBuilderHolder(ByteBufferBuilder builder) {
		this.lastUse = System.currentTimeMillis();
		this.builder = builder;
	}

	public ByteBufferBuilder getBuffer() {
		return builder;
	}

	public boolean deleteOrClear(int clearTime) {
		// If it's been 10 seconds since the last use, delete the buffer.
		if (System.currentTimeMillis() - lastUse > clearTime) {
			this.builder.close();
			return true;
		} else {
			if (!wasUsedSince) return false;
			// TODO POSSIBLE MEMORY LEAK
			this.builder.clear();
			wasUsedSince = false;
			return false;
		}
	}

	public boolean delete(int clearTime) {
		// If it's been 10 seconds since the last use, delete the buffer.
		if (System.currentTimeMillis() - lastUse > clearTime) {
			this.builder.close();
			return true;
		} else {
			return false;
		}
	}

	public void forceDelete() {
		this.builder.close();
	}

	@Override
	public long getAllocatedSize() {
		return ((MemoryTrackingBuffer) builder).getAllocatedSize();
	}

	@Override
	public long getUsedSize() {
		return ((MemoryTrackingBuffer) builder).getUsedSize();
	}

	@Override
	public void freeAndDeleteBuffer() {
		builder.close();
	}

	public void wasUsed() {
		this.lastUse = System.currentTimeMillis();
		wasUsedSince = true;
	}
}
