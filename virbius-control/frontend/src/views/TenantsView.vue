<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import {
  createTenant,
  issuePlatformKey,
  issueTenantKey,
  listCredentials,
  listTenants,
  revokeCredential,
  type Credential,
  type Tenant
} from '@/api/tenants';
import { useSessionStore } from '@/stores/session';
import { useFeedbackStore } from '@/stores/feedback';
import { fmtTime } from '@/utils/time';

const { t } = useI18n();
const session = useSessionStore();
const fb = useFeedbackStore();
const ROLES = ['tenant_viewer', 'tenant_admin'];

const tenants = ref<Tenant[]>([]);
const creds = ref<Credential[]>([]);
const loading = ref(false);
const credLoading = ref(false);

const createForm = reactive({ id: '', name: '' });
const issueForm = reactive({ role: 'tenant_admin', label: '' });

async function loadTenants(): Promise<void> {
  loading.value = true;
  try {
    tenants.value = await listTenants();
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    loading.value = false;
  }
}

async function loadCreds(): Promise<void> {
  credLoading.value = true;
  try {
    creds.value = await listCredentials(session.tenant);
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    credLoading.value = false;
  }
}

async function doCreate(): Promise<void> {
  if (!createForm.id.trim() || !createForm.name.trim()) {
    ElMessage.warning(t('tenants.id-name-required'));
    return;
  }
  try {
    await createTenant(createForm.id.trim(), createForm.name.trim());
    const tid = createForm.id.trim();
    createForm.id = '';
    createForm.name = '';
    await loadTenants();
    await switchTenant(tid);
    ElMessage.success(t('tenants.refreshed'));
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

async function switchTenant(tid: string): Promise<void> {
  session.setTenant(tid);
  await loadCreds();
}

async function doIssueKey(): Promise<void> {
  try {
    const data = await issueTenantKey(issueForm.role, issueForm.label.trim() || null);
    if (data.apiKey) {
      session.setApiKey(data.apiKey);
      ElMessage.warning({ message: `${t('tenants.key-show-once')} ${data.apiKey}`, duration: 8000 });
    }
    await loadCreds();
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

async function doIssuePlatformKey(): Promise<void> {
  try {
    const data = await issuePlatformKey('platform_admin', issueForm.label.trim() || null);
    if (data.apiKey) {
      session.setApiKey(data.apiKey);
      ElMessage.warning({ message: `${t('tenants.platform-key-show-once')} ${data.apiKey}`, duration: 8000 });
    }
    await loadCreds();
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

async function doRevoke(c: Credential): Promise<void> {
  try {
    await revokeCredential(c.tenantId || '*', c.credentialId);
    await loadCreds();
    ElMessage.success(t('tenants.revoke'));
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

onMounted(async () => {
  await loadTenants();
  await loadCreds();
  // 提示鉴权说明（对齐旧面板有提示文案）
  fb.toast('info', 'tenants.desc');
});
</script>

<template>
  <div class="tenants-view">
    <el-card shadow="never">
      <h2 class="panel-title" data-testid="tenants-title">{{ t('tenants.title') }}</h2>

      <div class="form-row">
        <el-input v-model="createForm.id" :placeholder="t('tenants.placeholder-id')" style="width: 180px" />
        <el-input v-model="createForm.name" :placeholder="t('tenants.placeholder-name')" style="width: 200px" />
        <el-button type="primary" data-testid="tenants-create" @click="doCreate">{{ t('tenants.btn-create') }}</el-button>
        <el-button @click="loadTenants">{{ t('common.refresh') }}</el-button>
      </div>

      <el-table v-loading="loading" :data="tenants" data-testid="tenants-table" style="width: 100%" size="default">
        <el-table-column :label="t('tenants.header-id')" min-width="140">
          <template #default="{ row }"><code>{{ row.tenantId }}</code></template>
        </el-table-column>
        <el-table-column :label="t('tenants.header-name')" min-width="140" prop="name" />
        <el-table-column :label="t('tenants.header-rules')" width="90" prop="ruleCount" />
        <el-table-column :label="t('tenants.header-created')" width="170">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column :label="t('tenants.header-actions')" width="150" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="switchTenant(row.tenantId)">{{ t('common.switch') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <span>{{ t('tenants.cred-title') }} · <code>{{ session.tenant }}</code></span>
      </template>

      <div class="form-row">
        <el-select v-model="issueForm.role" style="width: 170px">
          <el-option v-for="r in ROLES" :key="r" :value="r" :label="r" />
        </el-select>
        <el-input v-model="issueForm.label" :placeholder="t('tenants.remark-label')" style="width: 200px" />
        <el-button type="primary" @click="doIssueKey">{{ t('tenants.btn-issue-key') }}</el-button>
        <el-button @click="doIssuePlatformKey">{{ t('tenants.btn-issue-platform') }}</el-button>
      </div>

      <el-table v-loading="credLoading" :data="creds" data-testid="tenants-creds" style="width: 100%" size="default">
        <el-table-column :label="t('tenants.header-prefix')" min-width="140">
          <template #default="{ row }"><code>{{ row.keyPrefix }}****</code></template>
        </el-table-column>
        <el-table-column :label="t('tenants.header-role')" width="150" prop="role" />
        <el-table-column :label="t('tenants.header-remark')" min-width="140" prop="label" />
        <el-table-column :label="t('tenants.header-status')" width="110" prop="status" />
        <el-table-column :label="t('tenants.header-last-used')" width="170">
          <template #default="{ row }">{{ row.lastUsedAt ? fmtTime(row.lastUsedAt) : '—' }}</template>
        </el-table-column>
        <el-table-column :label="t('tenants.header-actions')" width="110" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 'active'" size="small" type="danger" plain @click="doRevoke(row)">
              {{ t('tenants.revoke') }}
            </el-button>
            <span v-else>—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.tenants-view {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.panel-title {
  margin: 0 0 12px;
  font-size: 16px;
}
.form-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
  align-items: center;
}
</style>
