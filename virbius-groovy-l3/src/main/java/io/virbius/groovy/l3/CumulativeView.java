package io.virbius.groovy.l3;

/** Read-only cumulative counter snapshot for script {@code getCumulative(name)}. */
public final class CumulativeView {

    private final long count;
    private final String value;

    public CumulativeView(long count, String value) {
        this.count = count;
        this.value = value != null ? value : "";
    }

    public long count() {
        return count;
    }

    public String value() {
        return value;
    }

    public boolean exceeded(long threshold) {
        return count >= threshold;
    }
}
