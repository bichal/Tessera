package net.bichal.mods.tessera.util;

import net.bichal.mods.celeris.physics.CelerisPhysics;
import net.bichal.mods.celeris.physics.ChunkWorkers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Splits load-time work over Celeris's shared worker threads ({@link CelerisPhysics#workers()}),
 * with the calling thread working alongside them. Celeris runs a few batches from different threads
 * on that pool at once (one at a time before its multi-slot pool): when every slot is taken, or the
 * pool cannot start, the chunks run here, one after another, so callers never depend on it.
 *
 * <p>Chunks must be independent: each writes only its own slots of the caller's arrays, which the
 * caller reads once {@link #forEach} returns.
 */
public final class ParallelWork {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/ParallelWork");

    /** Below this many chunks, waking the workers costs more than it saves. */
    private static final int MIN_CHUNKS = 2;

    private static volatile boolean unavailable;

    private ParallelWork() {
    }

    /** Runs {@code task} for every chunk in {@code [0, chunks)} and returns when all are done. */
    public static void forEach(int chunks, ChunkWorkers.ChunkTask task) {
        if (chunks >= MIN_CHUNKS && !unavailable) {
            ChunkWorkers workers = workers();
            if (workers != null && workers.threadCount() > 0 && workers.tryRun(task, chunks)) {
                return;
            }
        }
        for (int c = 0; c < chunks; c++) {
            task.run(c);
        }
    }

    private static ChunkWorkers workers() {
        try {
            return CelerisPhysics.workers();
        } catch (Throwable t) {
            unavailable = true;
            LOGGER.info("Celeris worker threads unavailable; load-time work runs on one thread", t);
            return null;
        }
    }
}
