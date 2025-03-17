package net.irisshaders.iris.compat.general;

import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import net.irisshaders.iris.Iris;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.StampedLock;

public class IrisModSupport {
	public static final IrisModSupport INSTANCE = new IrisModSupport();

	private final Map<Class<?>, MethodHandle> CACHE = new Object2ObjectArrayMap<>();
	private final MethodHandle EMPTY = MethodHandles.empty(MethodType.methodType(BlockState.class));
	private StampedLock lock = new StampedLock();

	public BlockState getModelPartState(BlockModelPart model) {
		var stamp = lock.readLock();
		MethodHandle handle;

		try {
			handle = CACHE.getOrDefault(model.getClass(), null);
		} finally {
			lock.unlockRead(stamp);
		}

		if (handle == null) {
			stamp = lock.writeLock();
			try {
				handle = computeClass(model.getClass());
				CACHE.put(model.getClass(), handle);
			} finally {
				lock.unlockWrite(stamp);
			}
		}

		if (handle == EMPTY) {
			return null;
		}

		try {
			return (BlockState) handle.invokeExact(model.getClass().cast(model));
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}

	private static final MethodType STATE_TYPE = MethodType.methodType(BlockState.class);

	private MethodHandle computeClass(Class<? extends BlockModelPart> aClass) {
		try {
			MethodHandle handle = MethodHandles.lookup().findVirtual(aClass, "getBlockAppearance", STATE_TYPE);
			return handle.asType(handle.type().changeParameterType(0, BlockModelPart.class));
		} catch (NoSuchMethodException e) {
			//Iris.logger.warn("Could not access MethodHandles.lookup() for " + aClass.getName(), e);

			return EMPTY;
		} catch (IllegalAccessException e) {
			Iris.logger.error("Could not access MethodHandles.lookup() for " + aClass.getName(), e);
			return EMPTY;
		}
	}
}
