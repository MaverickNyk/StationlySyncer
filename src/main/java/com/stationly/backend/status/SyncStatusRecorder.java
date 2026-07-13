package com.stationly.backend.status;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Async sink for {@link SyncRunRecord}s. The sync loops call {@link #record}
 * (a non-blocking enqueue, microseconds); a single dedicated daemon thread
 * batch-drains the queue and does the SQLite write — so persisting telemetry can
 * never block or fail the actual sync.
 *
 * <p>Mirrors the dedicated-thread pattern already used by {@code FcmService}.
 * On overflow we DROP (and count) rather than block; the queue is sized for
 * hours of backlog so this only happens if the DB is wedged.
 */
@Component
@Slf4j
public class SyncStatusRecorder {

    private final SyncLogRepository repository;
    private final BlockingQueue<SyncRunRecord> queue;
    private final int batchSize;

    private final Thread writerThread;
    private volatile boolean running = true;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong written = new AtomicLong();

    public SyncStatusRecorder(SyncLogRepository repository,
                              @Value("${syncer.status.queue-capacity:2000}") int queueCapacity,
                              @Value("${syncer.status.write-batch:200}") int batchSize) {
        this.repository = repository;
        this.queue = new ArrayBlockingQueue<>(Math.max(16, queueCapacity));
        this.batchSize = Math.max(1, batchSize);
        this.writerThread = new Thread(this::drainLoop, "sync-log-writer");
        this.writerThread.setDaemon(true);
    }

    @PostConstruct
    public void start() {
        writerThread.start();
        log.info("SYNC-LOG: ✍️  async writer started (capacity={}, batch={})",
                queue.remainingCapacity(), batchSize);
    }

    /**
     * Hand a completed run to the async writer. Non-blocking: if the queue is
     * full the record is dropped (and counted) rather than ever stalling the
     * caller's sync thread.
     */
    public void record(SyncRunRecord record) {
        if (record == null) return;
        if (!queue.offer(record)) {
            long n = dropped.incrementAndGet();
            if (n == 1 || n % 100 == 0) {
                log.warn("SYNC-LOG: ⚠️ queue full — dropped {} run(s) so far (latest job: {})",
                        n, record.getJobType());
            }
        }
    }

    private void drainLoop() {
        final List<SyncRunRecord> batch = new ArrayList<>(batchSize);
        while (running) {
            try {
                SyncRunRecord first = queue.poll(1, TimeUnit.SECONDS);
                if (first != null) flush(first, batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break; // shutting down — fall through to the final drain
            } catch (Exception e) {
                log.error("SYNC-LOG: ❌ writer batch failed", e);
            }
        }
        // Best-effort flush of whatever is still queued at shutdown. Non-blocking
        // poll(), so it works even with the interrupt flag set (no busy-spin).
        try {
            SyncRunRecord r;
            while ((r = queue.poll()) != null) flush(r, batch);
        } catch (Exception e) {
            log.error("SYNC-LOG: ❌ final flush failed", e);
        }
    }

    /** Coalesce up to {@code batchSize} queued records into one insert. */
    private void flush(SyncRunRecord first, List<SyncRunRecord> batch) {
        batch.clear();
        batch.add(first);
        queue.drainTo(batch, batchSize - 1);
        repository.insertBatch(batch);
        written.addAndGet(batch.size());
    }

    @PreDestroy
    public void stop() {
        running = false;
        writerThread.interrupt();
        try {
            writerThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public int queueDepth() { return queue.size(); }
    public long droppedCount() { return dropped.get(); }
    public long writtenCount() { return written.get(); }
}
