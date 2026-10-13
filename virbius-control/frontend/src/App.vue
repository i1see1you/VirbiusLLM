<script setup lang="ts">
import { onMounted, watch } from 'vue';
import { RouterView } from 'vue-router';
import DefaultLayout from '@/layouts/DefaultLayout.vue';
import { useSessionStore } from '@/stores/session';
import { i18n } from '@/locales';
import { useUiStore } from '@/stores/ui';

const session = useSessionStore();
const ui = useUiStore();

watch(
  () => session.locale,
  (loc) => {
    i18n.global.locale.value = loc;
    document.documentElement.lang = loc === 'zh' ? 'zh-CN' : 'en';
  },
  { immediate: true }
);

// 路由级渲染错误 → 可恢复错误页
watch(
  () => ui.routeError,
  () => {
    /* 模板读取该值切换渲染分支 */
  }
);

onMounted(() => {
  // beforeunload 脏守卫挂点：后续 DrawerEditor 通过 ui store 注册（Phase 2）
  window.addEventListener('beforeunload', () => undefined);
});
</script>

<template>
  <DefaultLayout>
    <template v-if="ui.routeError">
      <div class="error-page">
        <el-result icon="error" :title="$t('shell.boundary-title')" :sub-title="ui.routeError">
          <template #extra>
            <el-button type="primary" @click="ui.setRouteError(null)">{{ $t('shell.boundary-reload') }}</el-button>
          </template>
        </el-result>
      </div>
    </template>
    <RouterView v-else />
  </DefaultLayout>
</template>
