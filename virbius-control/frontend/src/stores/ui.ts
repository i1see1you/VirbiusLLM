import { defineStore } from 'pinia';
import { ref } from 'vue';

export const useUiStore = defineStore('ui', () => {
  const routeError = ref<string | null>(null);
  /** 全链路脏表单守卫：编辑中的面板置 true，路由离开前弹确认（§5-2 DrawerEditor 集成） */
  const hasDirty = ref(false);

  function setRouteError(message: string | null): void {
    routeError.value = message;
  }
  function markDirty(value: boolean): void {
    hasDirty.value = value;
  }

  return { routeError, hasDirty, setRouteError, markDirty };
});
