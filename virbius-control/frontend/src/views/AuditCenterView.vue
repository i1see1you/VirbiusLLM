<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import { auditRecent, auditTrace, type AuditEvent } from '@/api/audit';
import { fmtTime } from '@/utils/time';

const { t } = useI18n();

const traceId = ref('');
const events = ref<AuditEvent[]>([]);
const dbCount = ref(0);
const note = ref('');
const loading = ref(false);

function rolloutLabel(st: string, pct: number | null): string {
  if (!st) return '';
  return pct != null ? `${st} ${pct}%` : st;
}

async function loadRecent(): Promise<void> {
  loading.value = true;
  try {
    const res = await auditRecent(100);
    dbCount.value = res.dbCount;
    note.value = res.note;
    events.value = res.events;
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    loading.value = false;
  }
}

async function search(): Promise<void> {
  const tid = traceId.value.trim();
  if (!tid) {
    ElMessage.warning(t('ac.input-trace-id'));
    return;
  }
  loading.value = true;
  try {
    const res = await auditTrace(tid);
    dbCount.value = res.dbCount;
    note.value = res.note;
    events.value = res.events;
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    loading.value = false;
  }
}

onMounted(loadRecent);
</script>

<template>
  <div class="audit-view">
    <el-card shadow="never">
      <h2 class="panel-title" data-testid="audit-title">{{ t('ac.title') }}</h2>
      <div class="form-row">
        <el-input v-model="traceId" :placeholder="t('ac.placeholder-trace')" style="width: 360px" @keyup.enter="search" />
        <el-button type="primary" data-testid="audit-search" @click="search">{{ t('ac.search') }}</el-button>
        <el-button @click="loadRecent">{{ t('common.refresh') }}</el-button>
        <span class="panel-desc">db_count: {{ dbCount }}</span>
        <span class="panel-desc">{{ note }}</span>
      </div>

      <el-table v-loading="loading" :data="events" data-testid="audit-table" style="width: 100%" size="small">
        <el-table-column :label="t('ac.col-time')" width="170">
          <template #default="{ row }">{{ fmtTime(row.interceptedAt) }}</template>
        </el-table-column>
        <el-table-column label="layer" width="90" prop="layer" />
        <el-table-column label="scene" width="120" prop="scene" />
        <el-table-column label="action" width="100" prop="effectiveAction" />
        <el-table-column label="rule" min-width="180">
          <template #default="{ row }">{{ row.ruleId }}@{{ row.ruleRevision }}</template>
        </el-table-column>
        <el-table-column label="reason" width="140" prop="reasonCode" />
        <el-table-column label="risk" width="80" prop="maxRiskScore" />
        <el-table-column label="rollout" width="120">
          <template #default="{ row }">{{ rolloutLabel(row.rolloutState, row.canaryPercent) }}</template>
        </el-table-column>
        <el-table-column label="user" width="130" prop="userId" />
      </el-table>
      <p v-if="!events.length && !loading" class="panel-desc">{{ t('ac.no-db-records') }}</p>
    </el-card>
  </div>
</template>

<style scoped>
.panel-title {
  font-size: 16px;
  margin: 0 0 12px;
}
.form-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin-bottom: 12px;
}
.panel-desc {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
