<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  deleteCumulative,
  formatCumWindow,
  getCumulative,
  listCumulatives,
  saveCumulative,
  type Cumulative
} from '@/api/cumulatives';
import { buildVarDimension, formatCumDimension } from '@/utils/listHelpers';

const { t } = useI18n();
const DIMS = ['user_id', 'device_id', 'ip_cidr'];
const WIN_KINDS = ['rolling', 'calendar_day'];

const rows = ref<Cumulative[]>([]);
const loading = ref(false);
const editing = ref(false);
const isNew = ref(false);
const selectedName = ref<string | null>(null);

const form = reactive({
  name: '',
  description: '',
  dim: 'user_id',
  varLogical: '',
  status: 'active',
  priority: 10,
  winKind: 'rolling',
  winLen: 60,
  winUnit: 'minutes' as 'minutes' | 'hours',
  timezone: 'Asia/Shanghai',
  ingestPredicate: ''
});

async function load(): Promise<void> {
  loading.value = true;
  try {
    rows.value = await listCumulatives();
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    loading.value = false;
  }
}

function reset(): void {
  Object.assign(form, {
    name: '',
    description: '',
    dim: 'user_id',
    varLogical: '',
    status: 'active',
    priority: 10,
    winKind: 'rolling',
    winLen: 60,
    winUnit: 'minutes',
    timezone: 'Asia/Shanghai',
    ingestPredicate: ''
  });
}

function openNew(): void {
  isNew.value = true;
  selectedName.value = null;
  reset();
  editing.value = true;
}

async function openEdit(name: string): Promise<void> {
  try {
    const c = await getCumulative(name);
    isNew.value = false;
    selectedName.value = name;
    reset();
    form.name = c.cumulativeName;
    form.description = c.description || '';
    if (c.dimension.startsWith('var:')) {
      form.dim = 'var';
      form.varLogical = c.dimension.slice(4);
    } else {
      form.dim = c.dimension || 'user_id';
    }
    form.status = c.status || 'active';
    form.priority = c.priority ?? 10;
    form.winKind = c.windowKind || 'rolling';
    if (c.windowHours && c.windowHours > 0) {
      form.winUnit = 'hours';
      form.winLen = c.windowHours;
    } else {
      form.winUnit = 'minutes';
      form.winLen = c.windowMinutes ?? 60;
    }
    form.timezone = c.timezone || 'Asia/Shanghai';
    form.ingestPredicate = c.ingestPredicate || '';
    editing.value = true;
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

async function doSave(): Promise<void> {
  const name = isNew.value ? form.name.trim() : selectedName.value;
  if (!name) {
    ElMessage.warning(t('cum.name-required'));
    return;
  }
  let dimension: string;
  try {
    dimension = buildVarDimension(form.dim, form.varLogical, 'cum.var-dim-required', 'cum.var-name-invalid');
  } catch (e) {
    ElMessage.error((e as Error).message);
    return;
  }
  const body = {
    description: form.description.trim() || null,
    dimension,
    window_kind: form.winKind,
    priority: Number(form.priority) || 0,
    status: form.status,
    ingest_predicate_runtime: form.ingestPredicate.trim() ? 'lua' : null,
    ingest_predicate: form.ingestPredicate.trim() || null,
    window_hours: null as number | null,
    window_minutes: null as number | null,
    timezone: null as string | null
  };
  if (form.winKind === 'rolling') {
    if (form.winUnit === 'hours') body.window_hours = form.winLen > 0 ? form.winLen : 1;
    else body.window_minutes = form.winLen > 0 ? form.winLen : 60;
  } else {
    body.timezone = form.timezone.trim() || 'UTC';
  }
  try {
    await saveCumulative(name, body);
    isNew.value = false;
    selectedName.value = name;
    await load();
    await openEdit(name);
    ElMessage.success(t('cum.list-refreshed'));
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

async function doDelete(): Promise<void> {
  if (!selectedName.value) return;
  try {
    await ElMessageBox.confirm(t('cum.confirm-delete', selectedName.value), t('shell.confirm-title'), {
      type: 'warning',
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel')
    });
  } catch {
    return;
  }
  try {
    await deleteCumulative(selectedName.value);
    selectedName.value = null;
    editing.value = false;
    await load();
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

onMounted(load);
</script>

<template>
  <div class="cum-view">
    <el-card shadow="never">
      <h2 class="panel-title" data-testid="cum-title">{{ t('cum.title') }}</h2>
      <div class="form-row">
        <el-button type="primary" data-testid="cum-new" @click="openNew">{{ t('cum.btn-new') }}</el-button>
        <el-button @click="load">{{ t('cum.btn-refresh') }}</el-button>
      </div>

      <el-table v-loading="loading" :data="rows" data-testid="cum-table" style="width: 100%" size="default">
        <el-table-column :label="t('cum.header-name')" min-width="160">
          <template #default="{ row }"><code>{{ row.cumulativeName }}</code></template>
        </el-table-column>
        <el-table-column :label="t('cum.header-dim')" min-width="140">
          <template #default="{ row }">{{ formatCumDimension(row.dimension) }}</template>
        </el-table-column>
        <el-table-column :label="t('cum.header-window')" min-width="160">
          <template #default="{ row }">{{ formatCumWindow(row) }}</template>
        </el-table-column>
        <el-table-column :label="t('cum.header-status')" width="110">
          <template #default="{ row }">
            <el-tag :type="row.status === 'disabled' ? 'info' : 'success'" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column width="90" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openEdit(row.cumulativeName)">{{ t('common.edit') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card v-if="editing" shadow="never">
      <template #header>
        <span>{{ isNew ? t('cum.edit-title-new') : t('cum.edit-title-edit') }}
          <code v-if="!isNew">{{ selectedName }}</code></span>
      </template>
      <el-form label-position="top" class="cum-form">
        <el-form-item v-if="isNew" :label="t('cum.header-name')">
          <el-input v-model="form.name" style="width: 240px" />
        </el-form-item>
        <el-form-item :label="t('cum.label-desc')">
          <el-input v-model="form.description" style="width: 320px" />
        </el-form-item>
        <el-form-item :label="t('cum.label-dimension')">
          <div class="form-row">
            <el-select v-model="form.dim" style="width: 150px">
              <el-option v-for="d in DIMS" :key="d" :value="d" :label="d" />
              <el-option value="var" :label="t('shell.var')" />
            </el-select>
            <el-input v-if="form.dim === 'var'" v-model="form.varLogical" :placeholder="t('shell.var-logical')" style="width: 160px" />
          </div>
        </el-form-item>
        <el-form-item :label="t('cum.label-status')">
          <el-select v-model="form.status" style="width: 150px">
            <el-option value="active" label="active" />
            <el-option value="disabled" label="disabled" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('cum.label-priority')">
          <el-input v-model.number="form.priority" type="number" style="width: 120px" />
        </el-form-item>
        <el-form-item :label="t('cum.label-window-kind')">
          <div class="form-row">
            <el-select v-model="form.winKind" style="width: 160px">
              <el-option v-for="k in WIN_KINDS" :key="k" :value="k" :label="k" />
            </el-select>
            <template v-if="form.winKind === 'rolling'">
              <el-input v-model.number="form.winLen" type="number" style="width: 110px" />
              <el-radio-group v-model="form.winUnit">
                <el-radio-button value="minutes">{{ t('cum.minutes') }}</el-radio-button>
                <el-radio-button value="hours">{{ t('cum.hours') }}</el-radio-button>
              </el-radio-group>
            </template>
            <el-input v-else v-model="form.timezone" :placeholder="t('cum.label-timezone')" style="width: 180px" />
          </div>
        </el-form-item>
        <el-form-item :label="t('cum.label-ingest-predicate')">
          <el-input v-model="form.ingestPredicate" type="textarea" :rows="3" style="width: 480px" />
          <div class="panel-desc">{{ t('cum.ingest-predicate-hint') }}</div>
        </el-form-item>
      </el-form>
      <div class="form-row">
        <el-button type="primary" data-testid="cum-save" @click="doSave">{{ t('cum.btn-save') }}</el-button>
        <el-button type="danger" plain :disabled="isNew" @click="doDelete">{{ t('cum.btn-delete') }}</el-button>
        <el-button @click="editing = false">{{ t('common.cancel') }}</el-button>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.cum-view {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.panel-title {
  margin: 0 0 12px;
  font-size: 16px;
}
.panel-desc {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.form-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
</style>
