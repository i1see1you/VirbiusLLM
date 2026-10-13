<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  deleteExtendedVar,
  getMetadata,
  saveExtendedVars,
  type ContextVar,
  type ExtendedVar,
  type Scope
} from '@/api/metadata';
import ScopeEditor from '@/components/ScopeEditor.vue';

const { t } = useI18n();

const vars = ref<ExtendedVar[]>([]);
const contextVars = ref<ContextVar[]>([]);
const appIds = ref<string[]>([]);
const scenes = ref<string[]>([]);
const loading = ref(false);

const form = reactive<{ logical: string; expr: string; scope: Scope | undefined }>({
  logical: '',
  expr: '',
  scope: { bind_scope: 'global' }
});

const exprShort = computed(() => (s: string) => (s.length > 60 ? s.slice(0, 57) + '...' : s));

function scopeText(v: ExtendedVar): string {
  const s = v.scope;
  if (!s || s.bind_scope === 'global') return t('bind.scope-global');
  if (s.bind_scope === 'service') return `${t('bind.scope-service')}: ${(s.app_ids || []).join(', ')}`;
  if (s.bind_scope === 'route') return `${t('bind.scope-route')}: ${(s.scenes || []).join(', ')}`;
  return t('bind.scope-global');
}

async function load(): Promise<void> {
  loading.value = true;
  try {
    const data = await getMetadata();
    vars.value = data.extendedVars;
    contextVars.value = data.contextVars;
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

function insertVar(logical: string): void {
  form.expr += `ctx.var('${logical}')`;
}

function addRow(): void {
  const logical = form.logical.trim();
  const expr = form.expr.trim();
  if (!logical || !expr) return;
  if (!/^[a-z][a-z0-9_]*$/.test(logical)) {
    ElMessage.warning(t('ext.err-name'));
    return;
  }
  if (contextVars.value.some((v) => v.logical === logical)) {
    ElMessage.warning(t('ext.err-conflict'));
    return;
  }
  if (vars.value.some((v) => v.logical === logical)) {
    ElMessage.warning(t('ext.err-duplicate'));
    return;
  }
  vars.value.push({ logical, expr, scope: form.scope });
  form.logical = '';
  form.expr = '';
}

async function doDelete(v: ExtendedVar): Promise<void> {
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
    await deleteExtendedVar(v.logical);
    vars.value = vars.value.filter((x) => x.logical !== v.logical);
    ElMessage.success(t('shell.saved'));
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

async function saveAll(): Promise<void> {
  try {
    await saveExtendedVars(vars.value);
    await load();
    ElMessage.success(t('shell.synced'));
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

onMounted(load);
</script>

<template>
  <div class="ext-view">
    <el-card shadow="never">
      <h2 class="panel-title" data-testid="ext-title">{{ t('ext.title') }}</h2>
      <p class="panel-desc">{{ t('ext.desc') }}</p>

      <div class="form-row">
        <el-input v-model="form.logical" placeholder="logical" style="width: 150px" />
        <ScopeEditor v-model="form.scope" :app-ids="appIds" :scenes="scenes" />
        <el-button type="primary" plain data-testid="ext-add" @click="addRow">{{ t('ext.btn-add') }}</el-button>
        <el-button type="primary" data-testid="ext-save" @click="saveAll">{{ t('ext.btn-save') }}</el-button>
      </div>
      <el-input v-model="form.expr" type="textarea" :rows="3" placeholder="expr" data-testid="ext-expr" />

      <div class="ext-help">
        <span class="panel-desc">{{ t('ext.help-avail-vars') }}:</span>
        <el-tag
          v-for="cv in contextVars"
          :key="cv.logical"
          size="small"
          class="ext-chip"
          @click="insertVar(cv.logical)"
        >
          {{ cv.logical }}
        </el-tag>
        <span v-if="!contextVars.length" class="panel-desc">({{ t('ext.help-no-vars') }})</span>
      </div>

      <el-table v-loading="loading" :data="vars" data-testid="ext-table" style="width: 100%" size="default">
        <el-table-column :label="t('ext.header-logical')" min-width="140">
          <template #default="{ row }"><code>{{ row.logical }}</code></template>
        </el-table-column>
        <el-table-column :label="t('ext.header-expr')" min-width="260">
          <template #default="{ row }"><code :title="row.expr">{{ exprShort(row.expr) }}</code></template>
        </el-table-column>
        <el-table-column :label="t('ext.header-scope')" min-width="180">
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
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.form-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
  align-items: center;
}
.ext-help {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  margin: 8px 0 12px;
}
.ext-chip {
  cursor: pointer;
}
</style>
