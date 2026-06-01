package io.virbius.groovy.l3;

/** PoC 默认 L3 脚本：dry_run/canary 返回 review，full 返回 block。 */
public final class GroovyL3Defaults {

    public static final String DEFAULT_DECIDE_SCRIPT =
            """
            def decide(ctx) {
              def ruleId = ctx.currentRuleId()
              def mode = ctx.enforceMode(ruleId)
              if (!ctx.wouldHitBlock()) {
                return [action: 'allow']
              }
              if (mode == 'dry_run') {
                return [action: 'review']
              }
              if (mode == 'canary' && !ctx.inCanaryBucket(ctx.sessionId(), ctx.canaryPercent(ruleId))) {
                return [action: 'review']
              }
              return [action: 'block']
            }
            """;

    private GroovyL3Defaults() {}
}
