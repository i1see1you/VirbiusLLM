package io.virbius.control.domain;

/** 规则风险分：0–100。0=放行（白名单），1–99=灰区预留，100=应拦截。 */
public final class RiskScore {

    public static final int MIN = 0;
    public static final int ALLOW = 0;
    public static final int MAX = 100;
    public static final int DEFAULT = 100;
    public static final int BLOCK_THRESHOLD = 100;

    private RiskScore() {}

    public static int normalize(Integer value) {
        if (value == null) {
            return DEFAULT;
        }
        int v = value;
        if (v < MIN) {
            return MIN;
        }
        if (v > MAX) {
            return MAX;
        }
        return v;
    }

    public static boolean isBlock(int riskScore) {
        return riskScore >= BLOCK_THRESHOLD;
    }

    public static boolean isAllow(int riskScore) {
        return riskScore == ALLOW;
    }

    public static boolean isGray(int riskScore) {
        return riskScore > ALLOW && riskScore < BLOCK_THRESHOLD;
    }

    /** engine signal.suggest：0→allow，100→block，1–99→review（灰区，PoC 不触发 wouldHit）。 */
    public static String suggest(int riskScore) {
        if (isBlock(riskScore)) {
            return "block";
        }
        if (isAllow(riskScore)) {
            return "allow";
        }
        return "review";
    }
}
