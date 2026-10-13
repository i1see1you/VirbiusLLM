<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage, ElMessageBox } from 'element-plus';
import { getMetadata, saveSceneRegistry, type SceneEntry, type SceneRegistry } from '@/api/metadata';

const { t } = useI18n();

const entries = ref<SceneEntry[]>([]);
const flags = reactive<SceneRegistry>({ failOnUnknownApp: false, failOnUnresolvedScene: false });
const loading = ref(false);
const hydrating = ref(false);
const editIndex = ref(-1);

const form = reactive<{
  sceneId: string;
  appId: string;
  defaultScene: boolean;
  uris: string[];
  priority: number;
  kv: { k: string; v: string }[];
}>({
  sceneId: '',
  appId: '',
  defaultScene: false,
  uris: [],
  priority: 0,
  kv: []
});

const isEdit = computed(() => editIndex.value >= 0);

function validateUri(uri: string): string | null {
  if (!uri.startsWith('/')) return t('sr.uri-err-format');
  if (uri.lastIndexOf('*') > 0 && uri.indexOf('*') !== uri.length - 1) return t('sr.uri-err-wildcard');
  return null;
}

function matchText(q: Record<string, string>): string {
  const keys = Object.keys(q || {});
  if (!keys.length) return '—';
  return keys.map((k) => `${k}=${q[k]}`).join(', ');
}

async function load(): Promise<void> {
  loading.value = true;
  hydrating.value = true;
  try {
    const data = await getMetadata();
    entries.value = data.sceneEntries;
    flags.failOnUnknownApp = data.sceneRegistry.failOnUnknownApp;
    flags.failOnUnresolvedScene = data.sceneRegistry.failOnUnresolvedScene;
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    loading.value = false;
    hydrating.value = false;
  }
}

async function persist(sync: boolean): Promise<void> {
  if (!entries.value.length) {
    throw new Error(t('sr.at-least-one'));
  }
  await saveSceneRegistry(flags, entries.value, sync);
  await load();
}

async function onFlagsChange(): Promise<void> {
  if (hydrating.value || !entries.value.length) return;
  try {
    await saveSceneRegistry(flags, entries.value, false);
    ElMessage.success(t('sr.saved'));
  } catch (e) {
    ElMessage.error((e as Error).message);
    await load();
  }
}

function resetForm(): void {
  form.sceneId = '';
  form.appId = '';
  form.defaultScene = false;
  form.uris = [];
  form.priority = 0;
  form.kv = [];
  editIndex.value = -1;
}

function fillForm(row: SceneEntry, idx: number): void {
  form.sceneId = row.sceneId;
  form.appId = row.appId;
  form.defaultScene = row.defaultScene;
  form.uris = [...row.uris];
  form.priority = row.priority;
  form.kv = Object.entries(row.matchQuery || {}).map(([k, v]) => ({ k, v }));
  editIndex.value = idx;
}

function kvToMap(): Record<string, string> {
  const out: Record<string, string> = {};
  form.kv.forEach((p) => {
    if (p.k.trim()) out[p.k.trim()] = p.v;
  });
  return out;
}

async function submit(): Promise<void> {
  const sceneId = form.sceneId.trim();
  const appId = form.appId.trim();
  if (!sceneId || !appId) {
    ElMessage.warning(t('sr.fill-id-and-app'));
    return;
  }
  if (!isEdit.value && entries.value.some((e) => e.sceneId === sceneId)) {
    ElMessage.warning(t('sr.id-exists'));
    return;
  }
  for (const u of form.uris) {
    const err = validateUri(u);
    if (err) {
      ElMessage.warning(`${err}: ${u}`);
      return;
    }
  }
  const entry: SceneEntry = {
    sceneId,
    appId,
    defaultScene: form.defaultScene,
    uris: [...form.uris],
    priority: Number(form.priority) || 0,
    matchQuery: kvToMap()
  };
  if (isEdit.value) entries.value[editIndex.value] = entry;
  else entries.value.push(entry);
  resetForm();
  try {
    await persist(false);
  } catch (e) {
    ElMessage.error((e as Error).message);
    await load();
  }
}

async function doDelete(row: SceneEntry): Promise<void> {
  try {
    await ElMessageBox.confirm(t('sr.confirm-delete', row.sceneId), t('shell.confirm-title'), {
      type: 'warning',
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel')
    });
  } catch {
    return;
  }
  entries.value = entries.value.filter((e) => e !== row);
  try {
    await persist(false);
  } catch (e) {
    ElMessage.error((e as Error).message);
    await load();
  }
}

async function sync(): Promise<void> {
  try {
    await persist(true);
    ElMessage.success(t('sr.synced'));
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

onMounted(load);
</script>

<template>
  <div class="sr-view">
    <el-card shadow="never">
      <h2 class="panel-title" data-testid="sr-title">{{ t('sr.title') }}</h2>
      <p class="panel-desc">{{ t('sr.desc') }}</p>

      <div class="form-row">
        <el-checkbox v-model="flags.failOnUnknownApp" @change="onFlagsChange">{{ t('sr.header-app') }} unknown → fail</el-checkbox>
        <el-checkbox v-model="flags.failOnUnresolvedScene" @change="onFlagsChange">unresolved scene → fail</el-checkbox>
        <el-button type="primary" data-testid="sr-sync" @click="sync">{{ t('sr.btn-sync') }}</el-button>
      </div>

      <div class="inline-title">{{ isEdit ? t('sr.edit-hint') : '' }}</div>
      <div class="form-row">
        <el-input v-model="form.sceneId" :placeholder="t('sr.placeholder-scene')" style="width: 160px" :disabled="isEdit" />
        <el-input v-model="form.appId" :placeholder="t('sr.placeholder-app')" style="width: 160px" />
        <el-checkbox v-model="form.defaultScene">{{ t('sr.header-default') }}</el-checkbox>
        <el-select v-model="form.uris" multiple filterable allow-create default-first-option style="width: 260px" :placeholder="t('sr.placeholder-uris')">
          <el-option v-for="u in form.uris" :key="u" :value="u" :label="u" />
        </el-select>
        <el-input v-model.number="form.priority" type="number" :placeholder="t('sr.header-priority')" style="width: 100px" />
        <el-button type="primary" plain data-testid="sr-submit" @click="submit">{{ isEdit ? t('sr.btn-update') : t('sr.btn-add') }}</el-button>
        <el-button v-if="isEdit" @click="resetForm">{{ t('sr.cancel-edit') }}</el-button>
      </div>

      <div class="kv-editor">
        <span class="panel-desc">{{ t('sr.match-query-title') }}:</span>
        <div v-for="(p, i) in form.kv" :key="i" class="kv-row">
          <el-input v-model="p.k" placeholder="key" style="width: 140px" size="small" />
          <span>=</span>
          <el-input v-model="p.v" placeholder="value" style="width: 160px" size="small" />
          <el-button size="small" text @click="form.kv.splice(i, 1)">×</el-button>
        </div>
        <el-button size="small" @click="form.kv.push({ k: '', v: '' })">{{ t('sr.add-kv') }}</el-button>
      </div>

      <el-table v-loading="loading" :data="entries" data-testid="sr-table" style="width: 100%" size="default">
        <el-table-column :label="t('sr.header-scene')" min-width="140">
          <template #default="{ row }"><code>{{ row.sceneId }}</code></template>
        </el-table-column>
        <el-table-column :label="t('sr.header-app')" min-width="130">
          <template #default="{ row }"><code>{{ row.appId }}</code></template>
        </el-table-column>
        <el-table-column :label="t('sr.header-default')" width="90">
          <template #default="{ row }">{{ row.defaultScene ? '✓' : '—' }}</template>
        </el-table-column>
        <el-table-column :label="t('sr.header-uris')" min-width="200">
          <template #default="{ row }"><code>{{ (row.uris || []).join(', ') }}</code></template>
        </el-table-column>
        <el-table-column :label="t('sr.header-priority')" width="90" prop="priority" />
        <el-table-column :label="t('sr.header-match')" min-width="160">
          <template #default="{ row }"><code>{{ matchText(row.matchQuery) }}</code></template>
        </el-table-column>
        <el-table-column width="150" fixed="right">
          <template #default="{ row, $index }">
            <el-button size="small" @click="fillForm(row, $index)">{{ t('sr.btn-edit') }}</el-button>
            <el-button size="small" type="danger" plain @click="doDelete(row)">{{ t('common.delete') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.panel-title {
  margin: 0 0 6px;
  font-size: 16px;
}
.panel-desc {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.form-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
  align-items: center;
}
.inline-title {
  font-size: 12px;
  color: var(--el-color-warning);
}
.kv-editor {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
}
.kv-row {
  display: inline-flex;
  gap: 6px;
  align-items: center;
}
</style>
