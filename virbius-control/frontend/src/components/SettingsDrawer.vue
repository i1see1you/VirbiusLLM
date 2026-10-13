<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { useSessionStore } from '@/stores/session';
import { useFeedbackStore } from '@/stores/feedback';

const props = defineProps<{ modelValue: boolean }>();
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>();

const { t } = useI18n();
const session = useSessionStore();
const fb = useFeedbackStore();
const draft = ref('');

watch(
  () => props.modelValue,
  (open) => {
    if (open) draft.value = '';
  }
);

const configured = computed(() => !!session.apiKey);

function save(): void {
  const v = draft.value.trim();
  if (!v) return;
  session.setApiKey(v);
  draft.value = '';
  fb.toast('success', 'shell.cred-updated');
  emit('update:modelValue', false);
}
function clear(): void {
  session.setApiKey('');
  draft.value = '';
  fb.toast('info', 'shell.cred-cleared');
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="t('shell.settings')"
    size="420px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="settings-section">
      <div class="cred-status" data-testid="cred-status">
        <span class="label">{{ t('shell.cred-status') }}</span>
        <el-tag :type="configured ? 'success' : 'info'" size="small">
          {{ configured ? t('shell.cred-configured') : t('shell.cred-unconfigured') }}
        </el-tag>
      </div>
      <p class="hint">{{ t('shell.api-key-hint') }}</p>
    </div>

    <el-form label-position="top" class="settings-section">
      <el-form-item :label="t('topbar.api-key')">
        <el-input
          v-model="draft"
          type="password"
          show-password
          :placeholder="t('shell.cred-placeholder')"
          data-testid="cred-input"
        />
      </el-form-item>
      <div class="row">
        <el-button type="primary" :disabled="!draft.trim()" data-testid="cred-save" @click="save">{{ t('common.save') }}</el-button>
        <el-button v-if="configured" data-testid="cred-clear" @click="clear">{{ t('shell.cred-clear') }}</el-button>
      </div>
    </el-form>
  </el-drawer>
</template>

<style scoped>
.settings-section {
  margin-bottom: 20px;
}
.cred-status {
  display: flex;
  align-items: center;
  gap: 8px;
}
.cred-status .label {
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.hint {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
.row {
  display: flex;
  gap: 8px;
}
</style>
