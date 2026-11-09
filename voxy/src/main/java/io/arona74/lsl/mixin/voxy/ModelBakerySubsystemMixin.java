package io.arona74.lsl.mixin.voxy;

import io.arona74.lsl.voxy.BlockTables;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.common.world.other.Mapper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bake requests for tall-plant slice ids: Voxy refuses ids beyond the mapper's count (and logs an
 * error), so these are queued here the way Voxy queues any block: once per id, like Voxy's own
 * {@code seenIds}. That matters: {@code ModelFactory.addEntry} on an id that is already baked marks it
 * in flight and never clears it, so the model thread never runs out of work and closing the world
 * froze waiting for it.
 */
@Mixin(value = ModelBakerySubsystem.class, remap = false)
public abstract class ModelBakerySubsystemMixin {
    @Shadow @Final private Mapper mapper;
    @Shadow @Final public ModelFactory factory;
    @Shadow @Final private Thread processingThread;
    @Shadow @Final private ReentrantLock enqueueLock;
    @Shadow private volatile boolean isRunning;

    @Unique private final Set<Integer> lsl$requestedSlices = ConcurrentHashMap.newKeySet();

    @Inject(method = "requestBlockBake", at = @At("HEAD"), cancellable = true)
    private void lsl$sliceBake(int blockId, CallbackInfo ci) {
        if (!BlockTables.of(this.mapper).isSlice(blockId)) return;
        ci.cancel();
        if (!this.isRunning || this.factory.hasModelForBlockId(blockId)) return;
        if (!this.lsl$requestedSlices.add(blockId)) return;
        this.enqueueLock.lock();
        try {
            this.factory.addEntry(blockId);
        } finally {
            this.enqueueLock.unlock();
        }
        LockSupport.unpark(this.processingThread);
    }
}
