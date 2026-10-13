import { defineStore } from 'pinia';
import { ref } from 'vue';

export type LayerKey = 'cloud' | 'gateway' | 'edge';

export const LAYER_RUNTIMES: Record<LayerKey, string[]> = {
  cloud: ['prompt', 'groovy'],
  gateway: ['lua'],
  edge: ['lua-dsl', 'dlp-dsl']
};

export const LAYER_ORDER: LayerKey[] = ['cloud', 'gateway', 'edge'];

export const useRulesStore = defineStore('rules', () => {
  const currentLayer = ref<LayerKey>('cloud');
  const contextVars = ref<string[]>([]);
  const ruleFormDirty = ref(false);

  function setLayer(l: LayerKey): void {
    currentLayer.value = l;
  }
  function setContextVars(vars: string[]): void {
    contextVars.value = vars;
  }
  function markDirty(): void {
    ruleFormDirty.value = true;
  }
  function resetDirty(): void {
    ruleFormDirty.value = false;
  }

  return { currentLayer, contextVars, ruleFormDirty, setLayer, setContextVars, markDirty, resetDirty };
});
