package io.virbius.groovy.l3;

/** PoC 默认 L3 脚本：enforce / canary / dry_run + risk_score 命中（§11.6.7）。 */
public final class GroovyL3Defaults {

    public static final String DEFAULT_DECIDE_SCRIPT =
            """
            def decide(ctx) {
              def ruleId = ctx.currentRuleId()
              def mode = ctx.enforceMode(ruleId)
              def hit = ctx.wouldHitBlock()
              if (!hit) {
                return [action: 'allow', would_block: false]
              }
              if (mode == 'dry_run') {
                return [action: 'allow', would_block: true]
              }
              if (mode == 'canary' && !ctx.inCanaryBucket(ctx.sessionId(), ctx.canaryPercent(ruleId))) {
                return [action: 'allow', would_block: true]
              }
              return [action: 'block', would_block: false]
            }
            """;

    private GroovyL3Defaults() {}
}
