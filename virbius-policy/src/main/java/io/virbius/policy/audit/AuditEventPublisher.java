package io.virbius.policy.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.StreamEntryID;

/** Publish audit events to Redis Stream (default) or log-only Kafka stub. */
public class AuditEventPublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AuditEventPublisher.class);

    private final Optional<JedisPool> redisPool;
    private final String streamKey;
    private final String backend;
    private final boolean async;
    private final int batchSize;
    private final long flushIntervalMs;
    private final BlockingQueue<Map<String, String>> queue;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread worker;

    public AuditEventPublisher(Optional<JedisPool> redisPool, String backend, String streamKey) {
        this(redisPool, backend, streamKey, false, 2000, 64, 100L);
    }

    public AuditEventPublisher(
            Optional<JedisPool> redisPool,
            String backend,
            String streamKey,
            boolean async,
            int queueMax,
            int batchSize,
            long flushIntervalMs) {
        this.redisPool = redisPool;
        this.backend = backend != null ? backend : "redis-stream";
        this.streamKey = streamKey != null && !streamKey.isBlank() ? streamKey : "virbius:audit:events";
        this.batchSize = batchSize > 0 ? batchSize : 64;
        this.flushIntervalMs = flushIntervalMs > 0 ? flushIntervalMs : 100L;
        boolean useAsync = async && redisPool.isPresent() && !"kafka".equalsIgnoreCase(this.backend);
        this.async = useAsync;
        if (useAsync) {
            int cap = queueMax > 0 ? queueMax : 2000;
            this.queue = new ArrayBlockingQueue<>(cap);
            this.worker = new Thread(this::workerLoop, "virbius-audit-publish");
            this.worker.setDaemon(true);
            this.worker.start();
        } else {
            this.queue = null;
            this.worker = null;
        }
    }

    public void publish(Map<String, String> fields) {
        if (fields == null || fields.isEmpty()) {
            return;
        }
        if ("kafka".equalsIgnoreCase(backend)) {
            log.info("audit kafka publish (stub) topic=virbius.audit.events fields={}", fields.keySet());
            return;
        }
        if (async) {
            if (!queue.offer(fields)) {
                log.warn("audit publish queue full, dropping event");
            }
            return;
        }
        publishSync(fields);
    }

    public String streamKey() {
        return streamKey;
    }

    public boolean asyncEnabled() {
        return async;
    }

    public int queueDepth() {
        return queue != null ? queue.size() : 0;
    }

    @Override
    public void close() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        flushQueue();
    }

    private void workerLoop() {
        List<Map<String, String>> batch = new ArrayList<>(batchSize);
        while (running.get()) {
            try {
                Map<String, String> first =
                        queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch, batchSize - 1);
                }
                if (!batch.isEmpty()) {
                    publishBatch(batch);
                    batch.clear();
                }
            } catch (InterruptedException e) {
                if (!running.get()) {
                    break;
                }
            } catch (Exception e) {
                log.warn("audit async publish failed: {}", e.getMessage());
                batch.clear();
            }
        }
        flushQueue();
    }

    private void flushQueue() {
        if (queue == null || queue.isEmpty()) {
            return;
        }
        List<Map<String, String>> batch = new ArrayList<>(batchSize);
        queue.drainTo(batch, batchSize);
        while (!batch.isEmpty()) {
            try {
                publishBatch(batch);
            } catch (Exception e) {
                log.warn("audit flush on shutdown failed: {}", e.getMessage());
            }
            batch.clear();
            queue.drainTo(batch, batchSize);
        }
    }

    private void publishBatch(List<Map<String, String>> batch) {
        if (redisPool.isEmpty() || batch.isEmpty()) {
            return;
        }
        try (Jedis jedis = redisPool.get().getResource()) {
            Pipeline pipeline = jedis.pipelined();
            for (Map<String, String> fields : batch) {
                pipeline.xadd(streamKey, StreamEntryID.NEW_ENTRY, fields);
            }
            pipeline.sync();
        } catch (Exception e) {
            log.warn("audit redis batch publish failed: {}", e.getMessage());
        }
    }

    private void publishSync(Map<String, String> fields) {
        if (redisPool.isEmpty()) {
            return;
        }
        try (Jedis jedis = redisPool.get().getResource()) {
            StreamEntryID id = jedis.xadd(streamKey, StreamEntryID.NEW_ENTRY, fields);
            if (id == null) {
                log.warn("audit redis xadd returned null for stream {}", streamKey);
            }
        } catch (Exception e) {
            log.warn("audit redis publish failed: {}", e.getMessage());
        }
    }
}
