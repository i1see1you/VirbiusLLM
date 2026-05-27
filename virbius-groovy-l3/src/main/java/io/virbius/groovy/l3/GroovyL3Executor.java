package io.virbius.groovy.l3;

import groovy.lang.Binding;
import groovy.lang.GroovyClassLoader;
import groovy.lang.Script;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.codehaus.groovy.control.CompilerConfiguration;

/**
 * 沙箱执行 L3 {@code decide(ctx)}（G3–G5）。超时或异常由调用方 fail-open。
 */
public final class GroovyL3Executor {

    public static final long DEFAULT_TIMEOUT_MS = 50;

    private final long timeoutMs;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, Class<? extends Script>> scriptClassCache = new ConcurrentHashMap<>();
    private final CompilerConfiguration compilerConfig;
    private final GroovyClassLoader classLoader;

    public GroovyL3Executor() {
        this(DEFAULT_TIMEOUT_MS);
    }

    public GroovyL3Executor(long timeoutMs) {
        this.timeoutMs = timeoutMs;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "groovy-l3");
            t.setDaemon(true);
            return t;
        });
        this.compilerConfig = GroovyL3Validator.executionConfiguration();
        this.classLoader = new GroovyClassLoader(GroovyL3Executor.class.getClassLoader(), compilerConfig);
    }

    public GroovyL3Decision execute(String scriptBody, PolicyContext ctx) throws Exception {
        Objects.requireNonNull(ctx, "ctx");
        String body = normalizeBody(scriptBody);
        String cacheKey = Integer.toHexString(body.hashCode());
        Class<? extends Script> scriptClass = scriptClassCache.computeIfAbsent(cacheKey, k -> compileClass(body));

        Future<GroovyL3Decision> future =
                executor.submit(() -> runScript(scriptClass, ctx));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TimeoutException("groovy L3 exceeded " + timeoutMs + "ms");
        }
    }

    @SuppressWarnings("unchecked")
    private Class<? extends Script> compileClass(String body) {
        Class<?> clazz = classLoader.parseClass(wrapScript(body), "L3_" + Integer.toHexString(body.hashCode()));
        return (Class<? extends Script>) clazz;
    }

    private static String wrapScript(String body) {
        return body.strip() + "\ndecide(ctx)\n";
    }

    private static GroovyL3Decision runScript(Class<? extends Script> scriptClass, PolicyContext ctx)
            throws ReflectiveOperationException {
        Script instance = scriptClass.getDeclaredConstructor().newInstance();
        Binding binding = new Binding();
        binding.setVariable("ctx", ctx);
        instance.setBinding(binding);
        Object raw = instance.run();
        return mapResult(raw, ctx.enforceMode(ctx.currentRuleId()));
    }

    @SuppressWarnings("unchecked")
    private static GroovyL3Decision mapResult(Object raw, String enforceMode) {
        if (raw instanceof Map<?, ?> map) {
            Object action = map.get("action");
            Object wouldBlock = map.get("would_block");
            if (wouldBlock == null) {
                wouldBlock = map.get("wouldBlock");
            }
            String effective = action != null ? action.toString() : "allow";
            boolean wb = wouldBlock instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(wouldBlock));
            Object safe = map.get("safe_reply_id");
            if (safe == null) {
                safe = map.get("safeReplyId");
            }
            return new GroovyL3Decision(effective, wb, safe != null ? safe.toString() : null, enforceMode);
        }
        if (raw instanceof String s) {
            return new GroovyL3Decision(s, "block".equalsIgnoreCase(s), null, enforceMode);
        }
        return GroovyL3Decision.allow(enforceMode);
    }

    static String normalizeBody(String scriptBody) {
        if (scriptBody == null || scriptBody.isBlank()) {
            return GroovyL3Defaults.DEFAULT_DECIDE_SCRIPT.strip();
        }
        String t = scriptBody.strip();
        if (t.startsWith("\"") && t.endsWith("\"") && t.length() > 1) {
            return t.substring(1, t.length() - 1).replace("\\n", "\n").replace("\\\"", "\"");
        }
        return t;
    }
}
