<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { adminRoot, field } from '@/api/client';
import { useSessionStore } from '@/stores/session';

const session = useSessionStore();
const options = ref<{ value: string; label: string }[]>([]);
const loading = ref(false);

async function load(): Promise<void> {
  loading.value = true;
  try {
    const data = (await adminRoot('/tenants')) as unknown[] | null;
    options.value = (data || []).map((t) => {
      const id = String(field<string>(t, 'tenant_id', 'tenantId') || '');
      const name = String(field<string>(t, 'name', 'name') || '');
      return { value: id, label: name ? `${id} · ${name}` : id };
    });
    if (options.value.length && !options.value.some((o) => o.value === session.tenant)) {
      session.setTenant(options.value[0].value);
    }
  } catch {
    // 拉取失败不阻塞壳层；保持当前 tenant 值（服务端 dev 兜底）
  } finally {
    loading.value = false;
  }
}

function onChange(value: string): void {
  session.setTenant(value);
}

onMounted(load);
</script>

<template>
  <span class="tenant-select">
    <span class="tenant-select__label">{{ $t('topbar.tenant') }}:</span>
    <el-select
      data-testid="tenant-select"
      :model-value="session.tenant"
      :loading="loading"
      filterable
      style="width: 220px"
      size="small"
      @change="onChange"
    >
      <el-option v-for="opt in options" :key="opt.value" :value="opt.value" :label="opt.label" />
      <el-option v-if="!options.length" :value="session.tenant" :label="session.tenant" />
    </el-select>
  </span>
</template>

<style scoped>
.tenant-select__label {
  color: var(--el-text-color-regular);
  font-size: 13px;
}
</style>
