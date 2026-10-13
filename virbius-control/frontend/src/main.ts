import { createApp } from 'vue';
import { createPinia } from 'pinia';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import App from './App.vue';
import router from './router';
import { i18n, setupI18n } from './locales';
import { useFeedbackStore } from './stores/feedback';
import './styles/theme.css';

const app = createApp(App);
const pinia = createPinia();

app.use(pinia);
app.use(router);
app.use(i18n);
app.use(ElementPlus);
setupI18n();

app.config.errorHandler = (err, _instance, info) => {
  console.error('[ops-ui]', info, err);
  const fb = useFeedbackStore();
  fb.banner('error', String((err as Error)?.message || err));
};

app.mount('#app');
