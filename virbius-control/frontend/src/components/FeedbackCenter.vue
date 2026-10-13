<script setup lang="ts">
import { computed, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { useFeedbackStore, type FeedbackItem } from '@/stores/feedback';

const { t } = useI18n();
const fb = useFeedbackStore();
const popoverVisible = ref(false);
const badgeCount = computed(() => fb.banners.length + fb.toasts.length);

function displayText(item: FeedbackItem): string {
  if (/^(shell|nav|common|topbar)\./.test(item.text)) {
    return t(item.text, (item.params || []).map(String));
  }
  return item.text;
}

function kindOf(item: FeedbackItem): 'banner' | 'toast' {
  return fb.banners.some((b) => b.id === item.id) ? 'banner' : 'toast';
}
</script>

<template>
  <el-popover v-model:visible="popoverVisible" placement="bottom-end" :width="360" trigger="click">
    <template #reference>
      <el-badge :value="badgeCount || undefined" :hidden="badgeCount === 0">
        <el-button text size="small" data-testid="feedback-btn">{{ t('shell.feedback') }}</el-button>
      </el-badge>
    </template>
    <div class="fb-list" data-testid="feedback-list">
      <el-empty v-if="badgeCount === 0" :description="t('shell.feedback-empty')" :image-size="48" />
      <div v-for="item in [...fb.banners, ...fb.toasts]" :key="item.id" class="fb-item" :class="'fb-item--' + item.level">
        <span class="fb-item__text">{{ displayText(item) }}</span>
        <el-button text size="small" @click="fb.dismiss(item.id, kindOf(item))">×</el-button>
      </div>
    </div>
  </el-popover>
</template>

<style scoped>
.fb-item {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 4px;
  font-size: 13px;
}
.fb-item--error {
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
}
.fb-item--warning {
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning);
}
.fb-item--info {
  background: var(--el-color-info-light-9);
}
.fb-item--success {
  background: var(--el-color-success-light-9);
  color: var(--el-color-success);
}
</style>
