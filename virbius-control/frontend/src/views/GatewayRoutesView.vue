<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import { getMetadata, parseMethodsText, saveGatewayRoutes, type GatewayRoute, type GatewaySettings } from '@/api/metadata';

const { t } = useI18n();
const FAIL_MODES = ['open', 'closed'];

const routes = ref<GatewayRoute[]>([]);
const gw = reactive<GatewaySettings>({ evaluate: true, failMode: 'open', agentUrl: '', timeoutMs: 3000 });
const loading = ref(false);

const form = reactive({ uri: '', methods: 'POST' });

async function load(): Promise<void> {
  loading.value = true;
  try {
    const data = await getMetadata();
    routes.value = data.gatewayRoutes;
    Object.assign(gw, data.gateway);
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    loading.value = false;
  }
}

function addRow(): void {
  const uri = form.uri.trim();
  if (!uri) {
    ElMessage.warning(t('gw.uri-required'));
    return;
  }
  if (routes.value.some((r) => r.uri === uri)) {
    ElMessage.warning(t('gw.uri-exists'));
    return;
  }
  routes.value.push({ uri, methods: parseMethodsText(form.methods) });
  form.uri = '';
  form.methods = 'POST';
}

function removeRow(idx: number): void {
  routes.value.splice(idx, 1);
}

async function save(): Promise<void> {
  if (!routes.value.length) {
    ElMessage.warning(t('gw.at-least-one'));
    return;
  }
  try {
    await saveGatewayRoutes(gw, routes.value);
    await load();
    ElMessage.success(t('shell.synced'));
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

onMounted(load);
</script>

<template>
  <div class="gw-view">
    <el-card shadow="never">
      <h2 class="panel-title" data-testid="gw-title">{{ t('gw.title') }}</h2>
      <p class="panel-desc">{{ t('gw.desc') }}</p>

      <div class="form-row">
        <el-checkbox v-model="gw.evaluate">{{ t('shell.evaluate') }}</el-checkbox>
        <span class="panel-desc">{{ t('shell.fail-mode') }}:</span>
        <el-select v-model="gw.failMode" style="width: 120px">
          <el-option v-for="m in FAIL_MODES" :key="m" :value="m" :label="m" />
        </el-select>
        <el-input v-model="gw.agentUrl" :placeholder="t('shell.agent-url')" style="width: 240px" />
        <el-input v-model.number="gw.timeoutMs" type="number" :placeholder="t('shell.timeout-ms')" style="width: 130px" />
      </div>

      <div class="form-row">
        <el-input v-model="form.uri" :placeholder="t('gw.placeholder-uri')" style="width: 220px" />
        <el-input v-model="form.methods" :placeholder="t('gw.placeholder-methods')" style="width: 180px" />
        <el-button type="primary" plain data-testid="gw-add" @click="addRow">{{ t('gw.btn-add-uri') }}</el-button>
        <el-button type="primary" data-testid="gw-save" @click="save">{{ t('gw.btn-save') }}</el-button>
      </div>

      <el-table v-loading="loading" :data="routes" data-testid="gw-table" style="width: 100%" size="default">
        <el-table-column :label="t('gw.header-uri')" min-width="240">
          <template #default="{ row }"><code>{{ row.uri }}</code></template>
        </el-table-column>
        <el-table-column :label="t('gw.header-methods')" min-width="160">
          <template #default="{ row }">{{ (row.methods || []).join(', ') }}</template>
        </el-table-column>
        <el-table-column width="90" fixed="right">
          <template #default="{ $index }">
            <el-button size="small" type="danger" plain @click="removeRow($index)">{{ t('common.delete') }}</el-button>
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
</style>
