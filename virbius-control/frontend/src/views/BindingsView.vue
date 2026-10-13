<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  deleteContextBinding,
  getMetadata,
  saveContextBindings,
  type ContextVar,
  type Scope
} from '@/api/metadata';
import ScopeEditor from '@/components/ScopeEditor.vue';

const { t } = useI18n();
const FROM_OPTS = ['query', 'header', 'subject', 'network'];

const vars = ref<ContextVar[]>([]);
const appIds = ref<string[]>([]);
const scenes = ref<string[]>([]);
const loading = ref(false);

const form = reactive<{ logical: string; from: string; wire: string; scope: Scope | undefined }>({
  logical: '',
  from: 'query',
  wire: '',
  scope: { bind_scope: 'global' }
});

const wireLabel = computed(() => (form.from === 'subject' || form.from === 'network' ? 'field' : 'name'));

function scopeText(v: ContextVar): string {
  const s = v.scope;
  if (!s || s.bind_scope === 'global') return t('bind.scope-global');
  if (s.bind_scope === 'service') return `${t('bind.scope-service')}: ${(s.app_ids || []).join(', ')}`;
  if (s.bind_scope === 'route') return `${t('bind.scope-route')}: ${(s.scenes || []).join(', ')}`;
  return t('bind.scope-global');
}

function wireOf(v: ContextVar): string {
  return v.from === 'subject' || v.from === 'network' ? v.field || '' : v.name || v.logical;
}

async function load(): Promise<void> {
  loading.value = true;
  try {
    const data = await getMetadata();
    vars.value = data.contextVars;
    const apps = new Set<string>();
    const scns = new Set<string>();
    data.sceneEntries.forEach((e) => {
      if (e.appId) apps.add(e.appId);
      if (e.sceneId) scns.add(e.sceneId);
    });
    appIds.value = Array.from(apps);
    scenes.value = Array.from(scns);
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    loading.value = false;
  }
}

async function copyLogical(logical: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(logical);
    ElMessage.success(t('bind.copied'));
  } catch {
    ElMessage.error(t('bind.copy-failed'));
  }
}

function addRow(): void {
  const logical = form.logical.trim();
  if (!logical) return;
  const wire = form.wire.trim() || logical;
  const row: ContextVar = { logical, from: form.from, scope: form.scope };
  if (form.from === 'subject' || form.from === 'network') row.field = wire;
  else row.name = wire;
  if (!vars.value.some((x) => x.logical === logical)) vars.value.push(row);
  form.logical = '';
  form.wire = '';
}

async function doDelete(v: ContextVar): Promise<void> {
  try {
    await ElMessageBox.confirm(t('ext.confirm-delete', v.logical), t('shell.confirm-title'), {
      type: 'warning',
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel')
    });
  } catch {
    return;
  }
  try {
    await deleteContextBinding(v.logical);
    vars.value = vars.value.filter((x) => x.logical !== v.logical);
    ElMessage.success(t('shell.saved'));
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

async function saveAll(): Promise<void> {
  try {
    await saveContextBindings(vars.value);
    await load();
    ElMessage.success(t('shell.synced'));
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

onMounted(load);
</script>

<template>
  <div class="bind-view">
    <el-card shadow="never">
      <h2 class="panel-title" data-testid="bind-title">{{ t('bind.title') }}</h2>
      <p class="panel-desc">{{ t('bind.desc') }}</p>

      <div class="form-row">
        <el-input v-model="form.logical" :placeholder="t('bind.placeholder-logical')" style="width: 160px" size="default" />
        <el-select v-model="form.from" style="width: 120px">
          <el-option v-for="f in FROM_OPTS" :key="f" :value="f" :label="f" />
        </el-select>
        <el-input v-model="form.wire" :placeholder="t('bind.placeholder-wire')" style="width: 160px" />
        <ScopeEditor v-model="form.scope" :app-ids="appIds" :scenes="scenes" />
        <el-button type="primary" plain data-testid="bind-add" @click="addRow">{{ t('bind.btn-add') }}</el-button>
        <el-button type="primary" data-testid="bind-save" @click="saveAll">{{ t('bind.btn-save') }}</el-button>
      </div>

      <el-table v-loading="loading" :data="vars" data-testid="bind-table" style="width: 100%" size="default">
        <el-table-column :label="t('bind.header-logical')" min-width="140">
          <template #default="{ row }">
            <code style="cursor: pointer" :title="t('bind.click-to-copy')" @click="copyLogical(row.logical)">{{ row.logical }}</code>
          </template>
        </el-table-column>
        <el-table-column :label="t('bind.header-from')" width="110" prop="from" />
        <el-table-column :label="t('bind.header-wire')" min-width="140">
          <template #default="{ row }"><code>{{ wireOf(row) }}</code></template>
        </el-table-column>
        <el-table-column :label="t('bind.header-scope')" min-width="180">
          <template #default="{ row }">{{ scopeText(row) }}</template>
        </el-table-column>
        <el-table-column width="90" fixed="right">
          <template #default="{ row }">
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
  margin: 0 0 12px;
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
</style>
