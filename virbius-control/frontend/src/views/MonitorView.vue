<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import {
  degradation,
  eventTimeline,
  ingestStatus,
  monitorMetrics,
  ruleRanking,
  sceneTraffic,
  type DegradationPoint,
  type EventRow,
  type IngestStatus,
  type MetricBucket,
  type RuleRankRow,
  type SceneTrafficRow
} from '@/api/monitor';
import { fmtTime } from '@/utils/time';

const { t } = useI18n();
const HOURS = [1, 6, 24, 72, 168];

const hours = ref(24);
const totals = ref<Record<string, number>>({});
const series = ref<MetricBucket[]>([]);
const ranking = ref<RuleRankRow[]>([]);
const scenes = ref<SceneTrafficRow[]>([]);
const degradationSeries = ref<DegradationPoint[]>([]);
const events = ref<EventRow[]>([]);
const ingest = ref<IngestStatus | null>(null);
const loading = ref(false);
let timer: ReturnType<typeof setInterval> | null = null;

const NO_DATA = '—';

function fmtNum(n?: number | null): string {
  if (n == null) return '0';
  if (n >= 100000000) return (n / 100000000).toFixed(1) + '亿';
  if (n >= 10000) return (n / 10000).toFixed(1) + '万';
  return n.toLocaleString();
}
function fmtPct(v?: number | null): string {
  if (v == null || Number.isNaN(v)) return NO_DATA;
  return (v * 100).toFixed(2) + '%';
}

async function load(): Promise<void> {
  loading.value = true;
  try {
    const [m, rk, sc, dg, ev, ing] = await Promise.all([
      monitorMetrics(hours.value).catch(() => ({ totals: {}, series: [] })),
      ruleRanking(hours.value).catch(() => []),
      sceneTraffic(hours.value).catch(() => []),
      degradation(hours.value).catch(() => []),
      eventTimeline(hours.value).catch(() => []),
      ingestStatus().catch(() => null)
    ]);
    totals.value = m.totals;
    series.value = m.series;
    ranking.value = rk;
    scenes.value = sc;
    degradationSeries.value = dg;
    events.value = ev;
    ingest.value = ing;
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    loading.value = false;
  }
}

function setHours(h: number): void {
  hours.value = h;
  load();
}

const blockRate = () => {
  const total = totals.value.total_requests || 0;
  return total > 0 ? (totals.value.block || 0) / total : 0;
};
const reviewRate = () => {
  const total = totals.value.total_requests || 0;
  return total > 0 ? (totals.value.review || 0) / total : 0;
};
const degRate = () => {
  const total = totals.value.total_requests || 0;
  return total > 0 ? (totals.value.cnt_degraded || 0) / total : 0;
};
const activeRules = () => series.value.filter((s) => (s.total_requests || 0) > 0).length;

function exportDashboard(): void {
  const dump = JSON.stringify({ exportedAt: new Date().toISOString(), hours: hours.value, totals: totals.value, series: series.value, ranking: ranking.value, scenes: scenes.value, degradation: degradationSeries.value, events: events.value }, null, 2);
  navigator.clipboard?.writeText(dump).then(
    () => ElMessage.success(t('monitor.export-success')),
    () => ElMessage.error(t('monitor.export-fail', ''))
  );
}

onMounted(() => {
  load();
  timer = setInterval(load, 30000);
});
onBeforeUnmount(() => {
  if (timer) clearInterval(timer);
});
</script>

<template>
  <div class="monitor-view">
    <el-card shadow="never">
      <div class="form-row">
        <h2 class="panel-title" data-testid="monitor-title">{{ t('monitor.title') }}</h2>
        <el-radio-group :model-value="hours" @change="setHours">
          <el-radio-button v-for="h in HOURS" :key="h" :value="h">{{ h }}h</el-radio-button>
        </el-radio-group>
        <el-button @click="load">{{ t('common.refresh') }}</el-button>
        <el-button @click="exportDashboard">{{ t('monitor.btn-export') }}</el-button>
      </div>

      <div v-loading="loading" class="kpi-row">
        <div class="kpi"><div class="kpi-label">{{ t('monitor.kpi-total-requests') }}</div><div class="kpi-value">{{ fmtNum(totals.total_requests) }}</div></div>
        <div class="kpi"><div class="kpi-label">{{ t('monitor.kpi-block-rate') }}</div><div class="kpi-value">{{ fmtPct(blockRate()) }}</div></div>
        <div class="kpi"><div class="kpi-label">{{ t('monitor.kpi-review-rate') }}</div><div class="kpi-value">{{ fmtPct(reviewRate()) }}</div></div>
        <div class="kpi"><div class="kpi-label">{{ t('monitor.kpi-degraded-rate') }}</div><div class="kpi-value">{{ fmtPct(degRate()) }}</div></div>
        <div class="kpi"><div class="kpi-label">{{ t('monitor.kpi-active-rules') }}</div><div class="kpi-value">{{ activeRules() }}</div></div>
      </div>
    </el-card>

    <el-card shadow="never">
      <h3 class="sub">{{ t('monitor.ingest-health') }}</h3>
      <div v-if="ingest" class="panel-desc ingest" data-testid="monitor-ingest">
        <span>enabled: {{ ingest.enabled }}</span>
        <span>stream: {{ ingest.stream_key || ingest.layer || NO_DATA }}</span>
        <span>redis: {{ ingest.redis_ok ? 'OK' : 'ERR' }}</span>
        <span>db 24h: {{ fmtNum(ingest.db_events_24h ?? ingest.audit_events) }}</span>
        <span>db total: {{ fmtNum(ingest.db_events_total) }}</span>
        <span v-if="ingest.lag_estimate != null">lag: {{ fmtNum(ingest.lag_estimate) }}</span>
      </div>
      <p v-else class="panel-desc">{{ t('monitor.no-data') }}</p>
    </el-card>

    <el-card shadow="never">
      <h3 class="sub">{{ t('monitor.rule-ranking') }}</h3>
      <el-table :data="ranking" size="small" style="width: 100%" data-testid="monitor-ranking">
        <el-table-column label="rule_id" min-width="160" prop="rule_id" />
        <el-table-column label="hits" width="90"><template #default="{ row }">{{ fmtNum(row.total_hits) }}</template></el-table-column>
        <el-table-column label="block" width="90"><template #default="{ row }">{{ fmtNum(row.block) }}</template></el-table-column>
        <el-table-column label="review" width="90"><template #default="{ row }">{{ fmtNum(row.review) }}</template></el-table-column>
        <el-table-column label="hit_rate" width="100"><template #default="{ row }">{{ fmtPct(row.hit_rate) }}</template></el-table-column>
        <el-table-column label="block_rate" width="100"><template #default="{ row }">{{ fmtPct(row.block_rate) }}</template></el-table-column>
        <el-table-column label="requests" width="100"><template #default="{ row }">{{ fmtNum(row.total_requests) }}</template></el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <h3 class="sub">{{ t('monitor.scene-traffic') }}</h3>
      <el-table :data="scenes" size="small" style="width: 100%">
        <el-table-column label="scene" min-width="160" prop="scene" />
        <el-table-column label="layer" width="110" prop="layer" />
        <el-table-column label="requests" width="120"><template #default="{ row }">{{ fmtNum(row.total_requests) }}</template></el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <h3 class="sub">{{ t('monitor.event-timeline') }}</h3>
      <el-table :data="events" size="small" style="width: 100%">
        <el-table-column :label="t('qa.col-updated')" width="170"><template #default="{ row }">{{ fmtTime(row.effective_at) }}</template></el-table-column>
        <el-table-column label="rule_id" min-width="160" prop="rule_id" />
        <el-table-column label="state" width="110" prop="rollout_state" />
        <el-table-column label="revision" width="90" prop="rule_revision" />
        <el-table-column label="trigger" width="120" prop="trigger" />
        <el-table-column label="operator" width="140"><template #default="{ row }">{{ row.operator || '—' }}</template></el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <h3 class="sub">{{ t('monitor.traffic-buckets') }}</h3>
      <el-table :data="series.slice(-40).reverse()" size="small" max-height="320" style="width: 100%">
        <el-table-column label="bucket" width="180"><template #default="{ row }">{{ fmtTime(row.bucket) }}</template></el-table-column>
        <el-table-column label="total" width="100"><template #default="{ row }">{{ fmtNum(row.total_requests) }}</template></el-table-column>
        <el-table-column label="allow" width="90"><template #default="{ row }">{{ fmtNum(row.allow) }}</template></el-table-column>
        <el-table-column label="review" width="90"><template #default="{ row }">{{ fmtNum(row.review) }}</template></el-table-column>
        <el-table-column label="block" width="90"><template #default="{ row }">{{ fmtNum(row.block) }}</template></el-table-column>
        <el-table-column label="captcha" width="90"><template #default="{ row }">{{ fmtNum(row.captcha) }}</template></el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.monitor-view {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.panel-title {
  font-size: 16px;
  margin: 0;
}
.form-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}
.kpi-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 12px;
}
.kpi {
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  padding: 8px 14px;
  min-width: 130px;
}
.kpi-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.kpi-value {
  font-size: 20px;
  font-weight: 600;
}
.sub {
  font-size: 14px;
  margin: 0 0 8px;
}
.panel-desc {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.ingest {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  font-size: 13px;
}
</style>
