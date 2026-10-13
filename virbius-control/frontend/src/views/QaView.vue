<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  ackState,
  createQa,
  deleteQa,
  listEntries,
  listJobs,
  listQa,
  publishQa,
  saveEntries,
  updateQa
} from '@/api/qa';
import type { QaAckState, QaJob, QaMeta, QaRow } from '@/api/types';
import { useUiStore } from '@/stores/ui';
import { fmtTime, fmtTimeAgo } from '@/utils/time';

const { t } = useI18n();
const ui = useUiStore();

const ACTIVE_JOB_STATES = ['pending', 'checking', 'publishing', 'waiting_ack'];
const POLL_MS = 2000;
const LIMITS = { entries: 10000, regex: 200, question: 512, answer: 4096, variants: 20 };
const MODES = ['exact', 'contains', 'regex'];
const ROLLOUTS = ['dry_run', 'canary', 'full', 'disabled'];

interface EditRow {
  entryId: string;
  question: string;
  variantsText: string;
  answer: string;
  matchMode: string;
  priority: number;
  enabled: boolean;
  expiresAt: string;
  lastPrecheckAt?: string | null;
  lastPrecheckOk?: boolean | null;
}

const metas = ref<QaMeta[]>([]);
const selected = ref<QaMeta | null>(null);
const rows = ref<EditRow[]>([]);
const jobs = ref<QaJob[]>([]);
const ack = ref<QaAckState | null>(null);
const dirty = ref(false);
const loading = ref(false);
const savingEntries = ref(false);
const publishing = ref(false);

const createForm = reactive({ name: '', description: '', rollout: 'dry_run', canary: 10 });
const metaForm = reactive({ name: '', description: '', rollout: 'dry_run', canary: 10 });

let pollTimer: ReturnType<typeof setInterval> | null = null;
let pollPublishId: string | null = null;

const activeJob = computed(() => jobs.value.find((j) => ACTIVE_JOB_STATES.includes(j.status)) || null);
const ackEntries = computed(() => Object.entries(ack.value?.acks || {}));

function setDirty(v: boolean): void {
  dirty.value = v;
  ui.markDirty(v);
}

function toEditRow(r: QaRow): EditRow {
  return {
    entryId: r.entryId || '',
    question: r.question || '',
    variantsText: (r.variants || []).join('\n'),
    answer: r.answer || '',
    matchMode: r.matchMode || 'exact',
    priority: r.priority ?? 0,
    enabled: r.enabled !== false,
    expiresAt: r.expiresAt || '',
    lastPrecheckAt: r.lastPrecheckAt,
    lastPrecheckOk: r.lastPrecheckOk
  };
}

function rolloutText(m: QaMeta): string {
  return m.rolloutState === 'canary' ? `${m.rolloutState} ${m.canaryPercent}%` : m.rolloutState;
}

async function loadList(): Promise<void> {
  loading.value = true;
  try {
    metas.value = await listQa();
    if (selected.value) {
      const found = metas.value.find((m) => m.qaId === selected.value!.qaId);
      if (found) {
        selected.value = found;
        fillMetaForm(found);
      } else {
        clearSelection();
      }
    }
  } finally {
    loading.value = false;
  }
}

function clearSelection(): void {
  stopPoll();
  selected.value = null;
  rows.value = [];
  jobs.value = [];
  ack.value = null;
  setDirty(false);
}

function fillMetaForm(m: QaMeta): void {
  metaForm.name = m.name;
  metaForm.description = m.description || '';
  metaForm.rollout = m.rolloutState;
  metaForm.canary = m.canaryPercent ?? 0;
}

async function confirmDirty(): Promise<boolean> {
  if (!dirty.value) return true;
  try {
    await ElMessageBox.confirm(t('qa.confirm-unsaved'), t('shell.confirm-title'), {
      type: 'warning',
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel')
    });
    return true;
  } catch {
    return false;
  }
}

async function openQa(qaId: string): Promise<void> {
  if (dirty.value && selected.value && selected.value.qaId !== qaId) {
    if (!(await confirmDirty())) return;
  }
  setDirty(false);
  selected.value = metas.value.find((m) => m.qaId === qaId) || null;
  if (!selected.value) return;
  fillMetaForm(selected.value);
  await Promise.all([loadEntries(), loadJobs(), loadAck()]);
}

async function loadEntries(): Promise<void> {
  if (!selected.value) return;
  const list = await listEntries(selected.value.qaId);
  rows.value = list.map(toEditRow);
  setDirty(false);
}

async function loadJobs(): Promise<void> {
  if (!selected.value) return;
  jobs.value = await listJobs(selected.value.qaId);
}

async function loadAck(): Promise<void> {
  if (!selected.value) return;
  ack.value = await ackState(selected.value.qaId);
}

function addRow(): void {
  rows.value.push({
    entryId: '',
    question: '',
    variantsText: '',
    answer: '',
    matchMode: 'exact',
    priority: 0,
    enabled: true,
    expiresAt: ''
  });
  setDirty(true);
}

function removeRow(idx: number): void {
  rows.value.splice(idx, 1);
  setDirty(true);
}

function unsupportedRegex(p: string): boolean {
  return p.includes('(?=') || p.includes('(?!') || p.includes('(?<=') || p.includes('(?<!') || /\\[1-9]/.test(p);
}

function validate(): { msg: string; idx?: number } | null {
  if (!rows.value.length) return { msg: t('qa.err-entries-required') };
  if (rows.value.length > LIMITS.entries) return { msg: t('qa.err-entries-max', LIMITS.entries) };
  const ids = new Set<string>();
  let regexCount = 0;
  for (let i = 0; i < rows.value.length; i++) {
    const r = rows.value[i];
    if (!String(r.answer).trim()) return { msg: t('qa.err-answer-required'), idx: i };
    if (String(r.answer).length > LIMITS.answer) return { msg: t('qa.err-answer-len', LIMITS.answer), idx: i };
    if ((r.question || '').length > LIMITS.question) return { msg: t('qa.err-question-len', LIMITS.question), idx: i };
    const variants = r.variantsText.split('\n').map((s) => s.trim()).filter(Boolean);
    if (variants.length > LIMITS.variants) return { msg: t('qa.err-variants', LIMITS.variants), idx: i };
    if (!MODES.includes(r.matchMode)) return { msg: t('qa.err-mode'), idx: i };
    const patterns = [r.question].concat(variants).filter((p) => p && p.trim());
    if (!patterns.length) return { msg: t('qa.err-question-required'), idx: i };
    if (r.entryId) {
      if (ids.has(r.entryId)) return { msg: t('qa.err-dup-id', r.entryId), idx: i };
      ids.add(r.entryId);
    }
    if (r.matchMode === 'regex') {
      regexCount++;
      for (const p of patterns) {
        if (unsupportedRegex(p)) return { msg: t('qa.err-regex-unsupported', p), idx: i };
        try {
          new RegExp(p);
        } catch {
          return { msg: t('qa.err-regex-invalid', p), idx: i };
        }
      }
    }
  }
  if (regexCount > LIMITS.regex) return { msg: t('qa.err-regex-count', LIMITS.regex) };
  return null;
}

async function doSaveEntries(): Promise<void> {
  if (!selected.value) return;
  const err = validate();
  if (err) {
    ElMessage.error(err.msg);
    return;
  }
  savingEntries.value = true;
  try {
    const payload: QaRow[] = rows.value.map((r) => ({
      entryId: r.entryId.trim() || null,
      question: r.question.trim(),
      variants: r.variantsText.split('\n').map((s) => s.trim()).filter(Boolean),
      answer: r.answer,
      matchMode: r.matchMode,
      priority: Number(r.priority || 0),
      enabled: r.enabled,
      expiresAt: r.expiresAt.trim() || null
    }));
    const saved = await saveEntries(selected.value.qaId, payload);
    rows.value = saved.map(toEditRow);
    setDirty(false);
    ElMessage.success(t('qa.saved'));
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    savingEntries.value = false;
  }
}

async function reloadEntries(): Promise<void> {
  if (!selected.value) return;
  if (!(await confirmDirty())) return;
  await loadEntries();
}

async function doCreate(): Promise<void> {
  if (!createForm.name.trim()) {
    ElMessage.warning(t('qa.err-name-required'));
    return;
  }
  try {
    const meta = await createQa({
      name: createForm.name.trim(),
      description: createForm.description.trim() || null,
      rollout_state: createForm.rollout,
      canary_percent: Number(createForm.canary || 0)
    });
    createForm.name = '';
    createForm.description = '';
    await loadList();
    if (meta.qaId) await openQa(meta.qaId);
    ElMessage.success(t('qa.created'));
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

async function saveMeta(): Promise<void> {
  if (!selected.value) return;
  if (!metaForm.name.trim()) {
    ElMessage.warning(t('qa.err-name-required'));
    return;
  }
  try {
    const meta = await updateQa(selected.value.qaId, {
      name: metaForm.name.trim(),
      description: metaForm.description.trim() || null,
      rollout_state: metaForm.rollout,
      canary_percent: Number(metaForm.canary || 0)
    });
    selected.value = meta;
    await loadList();
    fillMetaForm(selected.value!);
    ElMessage.success(t('qa.meta-saved'));
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

async function cancelMeta(): Promise<void> {
  if (!(await confirmDirty())) return;
  clearSelection();
}

async function confirmDelete(m: QaMeta): Promise<void> {
  try {
    await ElMessageBox.confirm(t('qa.confirm-delete', m.name || m.qaId), t('shell.confirm-title'), {
      type: 'warning',
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel')
    });
  } catch {
    return;
  }
  try {
    await deleteQa(m.qaId);
    if (selected.value && selected.value.qaId === m.qaId) clearSelection();
    await loadList();
    ElMessage.success(t('qa.deleted'));
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

function stopPoll(): void {
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
}

function startPoll(): void {
  stopPoll();
  pollTimer = setInterval(() => {
    pollJob().catch(() => undefined);
  }, POLL_MS);
}

async function pollJob(): Promise<void> {
  if (!selected.value) {
    stopPoll();
    return;
  }
  await loadJobs();
  await loadAck();
  const target = jobs.value.find((j) => j.publishId === pollPublishId);
  if (target && (target.status === 'acked' || target.status === 'failed')) {
    stopPoll();
    if (target.status === 'acked') {
      ElMessage.success(t('qa.job-acked'));
      await loadList();
      if (selected.value) await loadEntries();
    } else {
      ElMessage.error(t('qa.job-failed'));
    }
  }
}

async function doPublish(): Promise<void> {
  if (!selected.value || activeJob.value) return;
  publishing.value = true;
  try {
    const job = await publishQa(selected.value.qaId);
    pollPublishId = job?.publishId || null;
    ElMessage.success(t('qa.published', pollPublishId || ''));
    await loadJobs();
    startPoll();
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    publishing.value = false;
  }
}

function formatErrors(s?: string | null): string {
  if (!s) return '—';
  try {
    const arr = JSON.parse(s);
    if (Array.isArray(arr)) return arr.join('; ') || '—';
  } catch {
    /* fall through */
  }
  return s;
}

onMounted(loadList);
onBeforeUnmount(() => {
  stopPoll();
  ui.markDirty(false);
});
</script>

<template>
  <div class="qa-view">
    <el-card shadow="never">
      <h2 class="qa-title" data-testid="qa-title">{{ t('qa.title') }}</h2>
      <p class="qa-desc">{{ t('qa.desc') }}</p>

      <div class="qa-create">
        <el-input v-model="createForm.name" :placeholder="t('qa.name-ph')" style="width: 180px" size="default" />
        <el-input v-model="createForm.description" :placeholder="t('qa.desc-ph')" style="width: 200px" size="default" />
        <el-select v-model="createForm.rollout" style="width: 130px">
          <el-option v-for="r in ROLLOUTS" :key="r" :value="r" :label="r" />
        </el-select>
        <el-input
          v-if="createForm.rollout === 'canary'"
          v-model.number="createForm.canary"
          type="number"
          :min="0"
          :max="100"
          style="width: 90px"
        />
        <el-button type="primary" data-testid="qa-create" @click="doCreate">{{ t('qa.btn-create') }}</el-button>
      </div>

      <el-table v-loading="loading" :data="metas" data-testid="qa-table" style="width: 100%" size="default">
        <el-table-column :label="t('qa.col-name')" min-width="180">
          <template #default="{ row }">
            <strong>{{ row.name || '—' }}</strong>
            <div v-if="row.description" class="qa-hint">{{ row.description }}</div>
          </template>
        </el-table-column>
        <el-table-column :label="t('qa.col-id')" min-width="140">
          <template #default="{ row }"><code>{{ row.qaId }}</code></template>
        </el-table-column>
        <el-table-column :label="t('qa.col-status')" width="110">
          <template #default="{ row }">
            <el-tag :type="row.status === 'disabled' ? 'info' : 'success'" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('qa.col-rollout')" width="130">
          <template #default="{ row }">{{ rolloutText(row) }}</template>
        </el-table-column>
        <el-table-column :label="t('qa.col-revision')" width="90">
          <template #default="{ row }">{{ row.revision }}</template>
        </el-table-column>
        <el-table-column :label="t('qa.col-updated')" width="130">
          <template #default="{ row }">{{ fmtTimeAgo(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column width="150" fixed="right">
          <template #default="{ row }">
            <el-button size="small" data-testid="qa-open" @click="openQa(row.qaId)">{{ t('qa.btn-entries') }}</el-button>
            <el-button size="small" type="danger" plain data-testid="qa-del" @click="confirmDelete(row)">{{ t('common.delete') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card v-if="selected" shadow="never" class="qa-editor">
      <template #header>
        <div class="qa-editor-head">
          <span>{{ t('qa.entries-title') }} <code>{{ selected.name || selected.qaId }}</code></span>
          <div class="qa-meta-row">
            <el-input v-model="metaForm.name" size="small" style="width: 160px" />
            <el-input v-model="metaForm.description" size="small" style="width: 180px" />
            <el-select v-model="metaForm.rollout" size="small" style="width: 120px">
              <el-option v-for="r in ROLLOUTS" :key="r" :value="r" :label="r" />
            </el-select>
            <el-input
              v-if="metaForm.rollout === 'canary'"
              v-model.number="metaForm.canary"
              type="number"
              size="small"
              style="width: 90px"
            />
            <el-button size="small" type="primary" @click="saveMeta">{{ t('qa.btn-save-meta') }}</el-button>
            <el-button size="small" @click="cancelMeta">{{ t('common.cancel') }}</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" data-testid="qa-entries" style="width: 100%" size="small" border>
        <el-table-column :label="t('qa.entry-enabled')" width="70">
          <template #default="{ row }">
            <el-checkbox v-model="row.enabled" @change="setDirty(true)" />
          </template>
        </el-table-column>
        <el-table-column :label="t('qa.entry-id')" width="130">
          <template #default="{ row }">
            <el-input v-model="row.entryId" size="small" placeholder="auto" @input="setDirty(true)" />
          </template>
        </el-table-column>
        <el-table-column :label="t('qa.entry-question')" min-width="200">
          <template #default="{ row }">
            <el-input v-model="row.question" size="small" @input="setDirty(true)" />
          </template>
        </el-table-column>
        <el-table-column :label="t('qa.variants-ph')" min-width="160">
          <template #default="{ row }">
            <el-input
              v-model="row.variantsText"
              type="textarea"
              :rows="2"
              size="small"
              @input="setDirty(true)"
            />
          </template>
        </el-table-column>
        <el-table-column :label="t('qa.entry-answer')" min-width="200">
          <template #default="{ row }">
            <el-input v-model="row.answer" type="textarea" :rows="2" size="small" @input="setDirty(true)" />
          </template>
        </el-table-column>
        <el-table-column :label="t('qa.entry-mode')" width="120">
          <template #default="{ row }">
            <el-select v-model="row.matchMode" size="small" @change="setDirty(true)">
              <el-option v-for="m in MODES" :key="m" :value="m" :label="m" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column :label="t('qa.entry-priority')" width="90">
          <template #default="{ row }">
            <el-input v-model.number="row.priority" type="number" size="small" @input="setDirty(true)" />
          </template>
        </el-table-column>
        <el-table-column :label="t('qa.entry-expires')" width="180">
          <template #default="{ row }">
            <el-input
              v-model="row.expiresAt"
              size="small"
              placeholder="2026-01-01T00:00:00Z"
              @input="setDirty(true)"
            />
          </template>
        </el-table-column>
        <el-table-column :label="t('qa.entry-precheck')" width="110">
          <template #default="{ row }">
            <span v-if="!row.lastPrecheckAt" class="qa-hint">{{ t('common.none') }}</span>
            <el-tag v-else :type="row.lastPrecheckOk ? 'success' : 'danger'" size="small" :title="fmtTime(row.lastPrecheckAt)">
              {{ row.lastPrecheckOk ? t('qa.precheck-ok') : t('qa.precheck-fail') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column width="60" fixed="right">
          <template #default="{ $index }">
            <el-button size="small" type="danger" plain @click="removeRow($index)">×</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="qa-actions">
        <el-button @click="addRow" data-testid="qa-entry-add">{{ t('qa.entry-add') }}</el-button>
        <el-button type="primary" :loading="savingEntries" data-testid="qa-entry-save" @click="doSaveEntries">
          {{ t('qa.entry-save') }}
        </el-button>
        <el-button @click="reloadEntries">{{ t('qa.entry-reload') }}</el-button>
        <span class="qa-hint">
          {{ t('qa.entries-count', rows.length) }}<template v-if="dirty"> · {{ t('qa.unsaved') }}</template>
        </span>
      </div>

      <div class="qa-publish">
        <el-button type="primary" :disabled="!!activeJob" :loading="publishing" data-testid="qa-publish" @click="doPublish">
          {{ t('qa.btn-publish') }}
        </el-button>
        <span v-if="activeJob" class="qa-hint">{{ t('qa.publish-active', activeJob.status) }}</span>
      </div>

      <div v-if="jobs.length" class="qa-jobs">
        <el-table :data="jobs.slice(0, 10)" size="small" style="width: 100%">
          <el-table-column :label="t('qa.job-id')" prop="publishId" min-width="160">
            <template #default="{ row }"><code>{{ row.publishId }}</code></template>
          </el-table-column>
          <el-table-column :label="t('qa.job-status')" width="130">
            <template #default="{ row }">
              <el-tag :type="row.status === 'failed' ? 'danger' : row.status === 'acked' ? 'success' : 'info'" size="small">
                {{ row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('qa.col-updated')" width="150">
            <template #default="{ row }">{{ fmtTime(row.updatedAt) }}</template>
          </el-table-column>
          <el-table-column :label="t('qa.job-errors')" min-width="200">
            <template #default="{ row }">{{ formatErrors(row.errors) }}</template>
          </el-table-column>
        </el-table>
      </div>
      <p v-else class="qa-hint">{{ t('qa.job-none') }}</p>

      <div class="qa-ack">
        <h3 class="qa-ack-title">{{ t('qa.ack-title') }}</h3>
        <p class="qa-hint">
          {{ t('qa.ack-revision') }} <code>{{ ack?.revision ?? '—' }}</code> ·
          {{ t('qa.ack-instances', ackEntries.length) }}
        </p>
        <p v-if="!ackEntries.length" class="qa-hint">{{ t('qa.ack-none') }}</p>
        <el-table v-else :data="ackEntries" size="small" style="width: 100%">
          <el-table-column :label="t('qa.ack-instance')" width="200">
            <template #default="{ row }"><code>{{ row[0] }}</code></template>
          </el-table-column>
          <el-table-column label="revision" width="100">
            <template #default="{ row }">{{ row[1].revision }}</template>
          </el-table-column>
          <el-table-column :label="t('qa.ack-status')" width="110">
            <template #default="{ row }">
              <el-tag :type="row[1].status && row[1].status !== 'ok' ? 'danger' : 'success'" size="small">
                {{ row[1].status || 'ok' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('qa.ack-updated')" width="150">
            <template #default="{ row }">{{ fmtTime(row[1].updatedAt) }}</template>
          </el-table-column>
          <el-table-column label="errors" min-width="160">
            <template #default="{ row }">{{ formatErrors(row[1].errors) }}</template>
          </el-table-column>
        </el-table>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.qa-view {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.qa-title {
  margin: 0 0 8px;
  font-size: 16px;
}
.qa-desc {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
.qa-create {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.qa-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.qa-editor-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.qa-meta-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}
.qa-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
}
.qa-publish {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 16px;
}
.qa-jobs {
  margin-top: 12px;
}
.qa-ack {
  margin-top: 16px;
}
.qa-ack-title {
  margin: 0 0 4px;
  font-size: 14px;
}
</style>
