<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage, ElMessageBox } from 'element-plus';
import { addListEntries, deleteListEntry, listLists, createList, type ListMeta, type ListRow } from '@/api/lists';
import { buildVarDimension, countActiveListEntries, formatListDimension, formatListStorage } from '@/utils/listHelpers';
import { fmtTime } from '@/utils/time';

const { t } = useI18n();
const MEMORY_LIST_MAX_ACTIVE = 1000;
const DIMS = ['keyword', 'user_id', 'device_id', 'ip_cidr'];

const rows = ref<ListRow[]>([]);
const meta = ref<Record<string, ListMeta>>({});
const loading = ref(false);

const createForm = reactive({ name: '', remark: '', dim: 'keyword', varLogical: '' });
const entryForm = reactive({ listName: '', value: '', expires: '', remark: '' });

const listNames = computed(() => Object.keys(meta.value).sort());

async function load(): Promise<void> {
  loading.value = true;
  try {
    const data = await listLists();
    rows.value = data.rows;
    meta.value = data.meta;
    if (!entryForm.listName && listNames.value.length) entryForm.listName = listNames.value[0];
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    loading.value = false;
  }
}

async function doCreate(): Promise<void> {
  if (!createForm.name.trim()) {
    ElMessage.warning(t('lists.name-required'));
    return;
  }
  let dim: string;
  try {
    dim = buildVarDimension(createForm.dim, createForm.varLogical, 'lists.var-dim-required', 'lists.var-name-invalid');
  } catch (e) {
    ElMessage.error((e as Error).message);
    return;
  }
  try {
    await createList(createForm.name.trim(), dim, createForm.remark.trim() || null);
    createForm.name = '';
    createForm.remark = '';
    await load();
    ElMessage.success(t('lists.created'));
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

async function doAddEntry(): Promise<void> {
  const val = entryForm.value.trim();
  if (!entryForm.listName || !val) {
    ElMessage.warning(t('lists.select-name-and-value'));
    return;
  }
  const m = meta.value[entryForm.listName];
  if (m && m.storage === 'memory') {
    const active = m.activeEntryCount != null ? Number(m.activeEntryCount) : countActiveListEntries(m.entries);
    const newActive = !entryForm.expires.trim() || Date.parse(entryForm.expires.trim()) > Date.now();
    const duplicate = (m.entries || []).some((e) => {
      const rec = e as Record<string, unknown>;
      return (typeof e === 'string' ? e : rec?.value) === val;
    });
    if (newActive && !duplicate && active >= MEMORY_LIST_MAX_ACTIVE) {
      ElMessage.warning(t('lists.memory-limit', MEMORY_LIST_MAX_ACTIVE));
      return;
    }
  }
  const entry: Record<string, unknown> = { value: val };
  if (entryForm.remark.trim()) entry.remark = entryForm.remark.trim();
  if (entryForm.expires.trim()) entry.expires_at = entryForm.expires.trim();
  try {
    await addListEntries(entryForm.listName, [entry]);
    entryForm.value = '';
    entryForm.expires = '';
    entryForm.remark = '';
    await load();
    ElMessage.success(t('lists.entry-added'));
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

async function doDelete(row: ListRow): Promise<void> {
  try {
    await ElMessageBox.confirm(`${t('common.delete')} ${row.listName} / ${row.value}?`, t('shell.confirm-title'), {
      type: 'warning',
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel')
    });
  } catch {
    return;
  }
  try {
    await deleteListEntry(row.listName, row.value);
    await load();
    ElMessage.success(t('lists.entry-deleted'));
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

onMounted(load);
</script>

<template>
  <div class="lists-view">
    <el-card shadow="never">
      <h2 class="panel-title" data-testid="lists-title">{{ t('lists.title') }}</h2>
      <p class="panel-desc">{{ t('lists.desc') }}</p>

      <div class="form-row">
        <el-input v-model="createForm.name" :placeholder="t('lists.placeholder-name')" style="width: 180px" />
        <el-select v-model="createForm.dim" style="width: 140px">
          <el-option v-for="d in DIMS" :key="d" :value="d" :label="d" />
          <el-option value="var" :label="t('lists.dim-logical')" />
        </el-select>
        <el-input v-if="createForm.dim === 'var'" v-model="createForm.varLogical" :placeholder="t('shell.var-logical')" style="width: 160px" />
        <el-input v-model="createForm.remark" :placeholder="t('lists.placeholder-remark-list')" style="width: 180px" />
        <el-button type="primary" data-testid="lists-create" @click="doCreate">{{ t('lists.btn-create') }}</el-button>
      </div>

      <div class="form-row">
        <el-select v-model="entryForm.listName" :placeholder="t('lists.placeholder-select')" style="width: 180px">
          <el-option v-for="n in listNames" :key="n" :value="n" :label="n" />
        </el-select>
        <el-input v-model="entryForm.value" :placeholder="t('lists.placeholder-value')" style="width: 180px" />
        <el-input v-model="entryForm.expires" :placeholder="t('lists.placeholder-expires')" style="width: 200px" />
        <el-input v-model="entryForm.remark" :placeholder="t('lists.placeholder-remark-entry')" style="width: 160px" />
        <el-button type="primary" plain data-testid="lists-add-entry" @click="doAddEntry">{{ t('lists.btn-add-entry') }}</el-button>
      </div>

      <el-table v-loading="loading" :data="rows" data-testid="lists-table" style="width: 100%" size="default">
        <el-table-column :label="t('lists.header-name')" min-width="140">
          <template #default="{ row }"><code>{{ row.listName }}</code></template>
        </el-table-column>
        <el-table-column :label="t('lists.header-dim')" min-width="140">
          <template #default="{ row }">{{ formatListDimension(row.dimension) }}</template>
        </el-table-column>
        <el-table-column :label="t('lists.header-storage')" width="100">
          <template #default="{ row }">{{ formatListStorage(row.storage) }}</template>
        </el-table-column>
        <el-table-column :label="t('lists.header-value')" min-width="160">
          <template #default="{ row }"><code>{{ row.value }}</code></template>
        </el-table-column>
        <el-table-column :label="t('lists.header-created')" width="160">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column :label="t('lists.header-expires')" width="160">
          <template #default="{ row }">{{ fmtTime(row.expiresAt) }}</template>
        </el-table-column>
        <el-table-column :label="t('lists.header-remark')" min-width="120" prop="remark" />
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
