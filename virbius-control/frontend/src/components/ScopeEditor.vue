<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import type { Scope } from '@/api/metadata';

const props = defineProps<{
  modelValue: Scope | undefined;
  appIds: string[];
  scenes: string[];
}>();
const emit = defineEmits<{ (e: 'update:modelValue', v: Scope | undefined): void }>();

const { t } = useI18n();

const scope = computed<Scope>(() => props.modelValue || { bind_scope: 'global' });

function setScope(bs: Scope['bind_scope']): void {
  if (bs === 'global') emit('update:modelValue', { bind_scope: 'global' });
  else emit('update:modelValue', { bind_scope: bs });
}
function setAppIds(v: string[]): void {
  emit('update:modelValue', { bind_scope: 'service', app_ids: v });
}
function setScenes(v: string[]): void {
  emit('update:modelValue', { bind_scope: 'route', scenes: v });
}
</script>

<template>
  <div class="scope-editor">
    <el-select :model-value="scope.bind_scope" size="small" style="width: 110px" @change="setScope">
      <el-option value="global" :label="t('shell.global')" />
      <el-option value="service" :label="t('shell.service')" />
      <el-option value="route" :label="t('shell.route')" />
    </el-select>
    <el-select
      v-if="scope.bind_scope === 'service'"
      :model-value="scope.app_ids || []"
      multiple
      filterable
      allow-create
      default-first-option
      size="small"
      style="width: 200px"
      :placeholder="t('bind.scope-service')"
      @update:model-value="setAppIds"
    >
      <el-option v-for="a in appIds" :key="a" :value="a" :label="a" />
    </el-select>
    <el-select
      v-if="scope.bind_scope === 'route'"
      :model-value="scope.scenes || []"
      multiple
      filterable
      allow-create
      default-first-option
      size="small"
      style="width: 200px"
      :placeholder="t('bind.scope-route')"
      @update:model-value="setScenes"
    >
      <el-option v-for="s in scenes" :key="s" :value="s" :label="s" />
    </el-select>
  </div>
</template>

<style scoped>
.scope-editor {
  display: inline-flex;
  gap: 6px;
  align-items: center;
}
</style>
