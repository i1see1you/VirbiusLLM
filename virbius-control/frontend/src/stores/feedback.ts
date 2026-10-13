import { defineStore } from 'pinia';
import { ref } from 'vue';

export interface FeedbackItem {
  id: number;
  level: 'info' | 'success' | 'warning' | 'error';
  /** i18n key 或已翻译文本 */
  text: string;
  params?: unknown[];
  ts: number;
}

let nextId = 1;

export const useFeedbackStore = defineStore('feedback', () => {
  const banners = ref<FeedbackItem[]>([]);
  const toasts = ref<FeedbackItem[]>([]);

  function banner(level: FeedbackItem['level'], text: string, params?: unknown[]): void {
    banners.value.push({ id: nextId++, level, text, params, ts: Date.now() });
  }
  function toast(level: FeedbackItem['level'], text: string, params?: unknown[]): void {
    toasts.value.push({ id: nextId++, level, text, params, ts: Date.now() });
    const id = nextId - 1;
    setTimeout(() => dismiss(id, 'toast'), level === 'error' ? 5000 : 3000);
  }
  function dismiss(id: number, kind: 'banner' | 'toast'): void {
    const list = kind === 'banner' ? banners : toasts;
    const idx = list.value.findIndex((i) => i.id === id);
    if (idx >= 0) list.value.splice(idx, 1);
  }
  function clearBanners(): void {
    banners.value = [];
  }

  return { banners, toasts, banner, toast, dismiss, clearBanners };
});
