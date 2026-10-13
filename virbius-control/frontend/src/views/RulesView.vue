<template>
  <div class="rules-view">
    <el-card shadow="never">
      <div class="rules-title-row">
        <h2 class="v-card-title" data-testid="rules-title">{{ t('rules.title') }}</h2>
        <el-tooltip placement="top" :show-after="200" :content="t('rules.desc-short')">
          <span class="rules-help" aria-hidden="true">?</span>
        </el-tooltip>
      </div>

      <div class="rules-toolbar">
        <span class="v-hint" data-testid="rules-current-layer">{{ t('rules.current-layer', [rules.currentLayer]) }}</span>
        <el-input v-model="filterQ" :placeholder="t('rules.filter-id')" clearable style="width: 220px" />
        <el-select v-model="filterState" clearable :placeholder="t('rules.filter-state')" style="width: 150px">
          <el-option value="draft" label="draft" />
          <el-option value="dry_run" label="dry_run" />
          <el-option value="canary" label="canary" />
          <el-option value="full" label="full" />
          <el-option value="disabled" label="disabled" />
        </el-select>
        <el-button type="primary" data-testid="rules-new" @click="openNew">{{ t('rules.btn-new') }}</el-button>
      </div>

      <p class="v-hint" style="margin: 0 0 8px">{{ t('rules.click-to-edit') }}</p>

      <el-table
        ref="tableRef"
        :data="paginatedRules"
        size="small"
        border
        stripe
        highlight-current-row
        :empty-text="t('rules.empty')"
        style="width: 100%"
        data-testid="rules-table"
        @row-click="onRowClick"
      >
        <el-table-column :label="t('rules.header-id')" min-width="150">
          <template #default="{ row }"><code>{{ row.ruleId }}</code></template>
        </el-table-column>
        <el-table-column :label="t('rules.header-runtime')" width="110" prop="runtime" />
        <el-table-column :label="t('rules.header-bind')" min-width="140">
          <template #default="{ row }">{{ formatBindScope(row.scope) }}</template>
        </el-table-column>
        <el-table-column :label="t('rules.header-rollout')" width="110">
          <template #default="{ row }">
            <el-tag :type="row.rolloutState === 'disabled' ? 'info' : row.rolloutState === 'draft' ? 'warning' : 'success'" size="small">
              {{ row.rolloutState }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('rules.header-rev')" width="70" prop="currentRevision" />
        <el-table-column :label="t('rules.header-risk')" width="70" prop="riskScore" />
        <el-table-column :label="t('rules.header-intent')" width="90" prop="intentAction" />
        <el-table-column :label="t('rules.header-enforce')" width="110">
          <template #default="{ row }">{{ row.enforceMode }}<template v-if="row.canaryPercent">@{{ row.canaryPercent }}%</template></template>
        </el-table-column>
        <el-table-column :label="t('rules.header-async')" width="80">
          <template #default="{ row }"><span v-if="row.isAsync">✓</span><span v-else>—</span></template>
        </el-table-column>
        <el-table-column :label="t('rules.header-reason')" min-width="120" prop="reasonCode" />
      </el-table>

      <el-pagination
        v-if="filteredRules.length > pageSize"
        small
        background
        layout="prev, pager, next"
        :current-page="page"
        :page-size="pageSize"
        :total="filteredRules.length"
        @current-change="(p: number) => (page = p)"
      />
    </el-card>

    <el-drawer
      v-model="editorVisible"
      :title="drawerTitle"
      direction="rtl"
      size="760px"
      :close-on-click-modal="false"
      :before-close="onBeforeClose"
      class="rules-drawer"
      data-testid="rules-drawer"
    >
      <div class="rules-form">
        <section class="rules-section">
          <h3 class="rules-section-title">{{ t('rules.section-basic') }}</h3>
          <div class="rules-grid">
            <div v-if="isNew" class="rules-field">
              <label class="rules-field-label">{{ t('rules.label-id') }}</label>
              <el-input v-model="form.ruleId" maxlength="128" data-testid="rules-ruleid" />
            </div>
            <div v-if="isNew" class="rules-field">
              <label class="rules-field-label">{{ t('rules.label-runtime') }}</label>
              <el-select v-model="form.runtime" data-testid="rules-runtime" @change="onRuntimeChange">
                <el-option v-for="rt in layerRuntimes" :key="rt" :value="rt" :label="rt" />
              </el-select>
            </div>
            <div class="rules-field">
              <label class="rules-field-label">{{ t('rules.label-reason') }}</label>
              <el-input v-model="form.reason" maxlength="64" :disabled="isReadOnly" />
            </div>
            <div class="rules-field">
              <label class="rules-field-label">{{ t('rules.label-risk') }}</label>
              <el-input-number v-model="form.risk" :min="0" :max="100" :disabled="isReadOnly || isDlp" controls-position="right" />
            </div>
            <div class="rules-field">
              <label class="rules-field-label">{{ t('rules.label-intent') }}</label>
              <el-select v-model="form.intent" :disabled="isReadOnly || form.isAsync || isDlp">
                <el-option value="deny" label="deny" />
                <el-option value="allow" label="allow" />
                <el-option value="captcha" label="captcha" />
                <el-option value="review" label="review" />
              </el-select>
            </div>
            <div v-if="showAsync" class="rules-field">
              <label class="rules-field-label">{{ t('rules.header-async') }}</label>
              <el-checkbox v-model="form.isAsync" :disabled="isReadOnly" @change="onAsyncChange">{{ t('rules.label-async') }}</el-checkbox>
            </div>
          </div>
        </section>

        <div v-if="form.isAsync" class="async-card">
          <div class="rules-toolbar">
            <el-select v-model="asyncCfg.type" style="width: 150px">
              <el-option value="redis_stream" label="Redis Stream" />
              <el-option value="webhook" label="Webhook" />
            </el-select>
            <el-input v-if="asyncCfg.type === 'redis_stream'" v-model="asyncCfg.streamKey" :placeholder="t('rules.stream-key')" style="width: 260px" />
            <el-input v-else v-model="asyncCfg.url" :placeholder="t('rules.webhook-url')" style="width: 260px" />
          </div>
          <label class="rules-field-label">{{ t('rules.msg-content') }}</label>
          <el-input v-model="asyncCfg.message" type="textarea" :rows="5" />
          <AsyncPreview :message="asyncCfg.message" :type="asyncCfg.type" :stream-key="asyncCfg.streamKey" :webhook-url="asyncCfg.url" />
        </div>

        <section v-if="showBindScope" class="rules-section">
          <h3 class="rules-section-title">{{ t('rules.section-bind') }}</h3>
          <div class="rules-grid">
            <div class="rules-field">
              <label class="rules-field-label">{{ t('rules.label-bind') }}</label>
              <el-select v-model="form.bindScope" :disabled="isReadOnly" @change="rules.markDirty">
                <el-option value="global" :label="t('shell.global')" />
                <el-option v-if="!isEdgeForm" value="route" :label="t('shell.route')" />
                <el-option value="service" :label="t('shell.service')" />
              </el-select>
            </div>
            <div v-if="form.bindScope === 'route'" class="rules-field">
              <label class="rules-field-label">scenes</label>
              <el-select v-model="sceneSelection" multiple filterable allow-create default-first-option collapse-tags :disabled="isReadOnly" @change="rules.markDirty">
                <el-option v-for="s in sceneOptions" :key="s" :value="s" :label="s" />
              </el-select>
            </div>
            <div v-if="form.bindScope === 'service'" class="rules-field">
              <label class="rules-field-label">app_ids</label>
              <el-select v-model="appIdSelection" multiple filterable allow-create default-first-option collapse-tags :disabled="isReadOnly" @change="rules.markDirty">
                <el-option v-for="a in appIdOptions" :key="a" :value="a" :label="a" />
              </el-select>
            </div>
          </div>
        </section>

        <section class="rules-section">
          <h3 class="rules-section-title">{{ t('rules.section-body') }}</h3>
          <div v-if="isScript && !isReadOnly" class="rules-toolbar">
            <span class="rules-field-label">{{ t('rules.editor-mode') }}</span>
            <el-select v-model="form.editorMode" style="width: 170px" @change="rules.markDirty">
              <el-option value="simple" :label="t('rules.editor-simple')" />
              <el-option value="advanced" :label="t('rules.editor-advanced')" />
            </el-select>
          </div>

          <div v-if="isScript && form.editorMode === 'simple'">
            <p class="v-hint">{{ t('rules.condition-hint') }}</p>
            <div v-for="(leaf, i) in conditionLeaves" :key="i" class="cond-row">
              <template v-if="leaf.type === 'list_match'">
                <span>{{ t('rules.cond-list') }}</span>
                <el-select v-model="leaf.list_name" size="small" style="width: 160px" @change="rules.markDirty">
                  <el-option v-for="n in listCatalog" :key="n" :value="n" :label="n" />
                </el-select>
                <span>{{ t('rules.cond-match') }}</span>
                <el-select v-model="leaf.value_source" size="small" style="width: 150px" @change="rules.markDirty">
                  <el-option value="content" label="content" />
                </el-select>
              </template>
              <template v-else>
                <span>{{ t('rules.cond-cum') }}</span>
                <el-select v-model="leaf.cumulative_name" size="small" style="width: 160px" @change="rules.markDirty">
                  <el-option v-for="n in cumNames" :key="n" :value="n" :label="n" />
                </el-select>
                <el-select v-model="leaf.compare" size="small" style="width: 80px" @change="rules.markDirty">
                  <el-option value="gte" label="≥" />
                  <el-option value="gt" label=">" />
                  <el-option value="eq" label="=" />
                </el-select>
                <el-input-number v-model="leaf.threshold" :min="1" size="small" style="width: 110px" @change="rules.markDirty" />
              </template>
              <el-button size="small" link type="danger" @click="removeLeaf(i)">{{ t('common.delete') }}</el-button>
            </div>
            <div class="rules-toolbar">
              <el-button size="small" @click="addListCond">{{ t('rules.btn-add-list-cond') }}</el-button>
              <el-button size="small" @click="addCumCond">{{ t('rules.btn-add-cum-cond') }}</el-button>
            </div>
          </div>

          <div v-if="isEdgeDsl && form.editorMode === 'simple'">
            <el-select v-model="edgeBody.listType" style="width: 180px" @change="rules.markDirty">
              <el-option value="deny" :label="t('edge.list-type.deny')" />
              <el-option value="allow" :label="t('edge.list-type.allow')" />
            </el-select>
            <label class="rules-field-label" style="display: block; margin-top: 8px">{{ t('edge.keywords-label') }}</label>
            <el-input v-model="edgeBody.keywordsText" type="textarea" :rows="5" @input="rules.markDirty" />
          </div>

          <div v-if="isDlp && form.editorMode === 'simple'">
            <div class="rules-toolbar">
              <el-select v-model="dlpBody.entityType" style="width: 200px" @change="rules.markDirty">
                <el-option value="phone_cn" :label="t('dlp.phone-cn')" />
                <el-option value="idcard_cn" :label="t('dlp.idcard-cn')" />
                <el-option value="email" :label="t('dlp.email')" />
                <el-option value="bank_card_cn" :label="t('dlp.bank-card-cn')" />
                <el-option value="custom_regex" :label="t('dlp.custom-regex')" />
              </el-select>
              <el-input-number v-model="dlpBody.priority" style="width: 120px" @change="rules.markDirty" />
            </div>
            <el-input v-if="dlpBody.entityType === 'custom_regex'" v-model="dlpBody.pattern" :placeholder="t('dlp.pattern-label')" @input="rules.markDirty" />
            <el-input v-model="dlpBody.maskTemplate" :placeholder="t('dlp.placeholder-mask')" style="margin-top: 6px" @input="rules.markDirty" />
            <DlpRegexTest v-if="dlpBody.entityType === 'custom_regex'" :initial-pattern="dlpBody.pattern" />
          </div>

          <div v-if="showBodyEditor" data-testid="rules-body">
            <ScriptEditor
              ref="scriptEditorRef"
              v-model="form.body"
              :language="editorLanguage"
              :completion-sources="completionSources"
              :lint-fn="lintFn"
              :read-only="isReadOnly"
              max-height="420px"
              @update:model-value="rules.markDirty"
              @save="saveWithDiff"
            />
          </div>
        </section>

        <div v-if="showValidate" class="rules-toolbar">
          <el-button :disabled="isReadOnly" @click="doValidate">{{ t('rules.btn-validate') }}</el-button>
          <span class="v-hint" :style="validateStyle">{{ validateMsg }}</span>
        </div>

        <div v-if="showSimulate" class="rules-toolbar">
          <el-checkbox v-model="enableSimulate">{{ t('rules.enable-simulate') }}</el-checkbox>
        </div>
        <RuleSimulate
          v-if="showSimulate && enableSimulate"
          :rule-id="selectedRuleId || form.ruleId"
          :layer="rules.currentLayer"
          :runtime="form.runtime"
          :body="form.body"
          :scope="buildScope()"
          :intent="form.intent"
          :risk="form.risk"
          :reason="form.reason"
          :editor-mode="form.editorMode"
          :condition="readConditionPayload()"
          :bundle-id="bundleId()"
        />
      </div>

      <template #footer>
        <div class="drawer-foot">
          <el-button v-if="canSave" type="primary" :loading="saving" data-testid="rules-save" @click="saveWithDiff">
            {{ isNew ? t('rules.btn-create') : t('rules.btn-save') }}
          </el-button>
          <el-button v-if="!isNew && editMeta?.rolloutState === 'draft'" type="success" @click="runRollout('publish')">{{ t('rules.btn-activate') }}</el-button>
          <el-button v-if="!isNew && editMeta && editMeta.rolloutState !== 'disabled'" type="danger" plain @click="runRollout('disable')">{{ t('rules.btn-disable') }}</el-button>
          <el-button v-if="!isNew && editMeta?.rolloutState === 'disabled'" @click="runRollout('recover')">{{ t('rules.btn-enable') }}</el-button>
          <el-button @click="requestClose">{{ t('common.close') }}</el-button>
        </div>
      </template>
    </el-drawer>

    <DiffConfirm ref="diffConfirmRef" :old-text="previousBody" :new-text="form.body" @confirm="doSave" @cancel="() => undefined" />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  bundleId,
  compileCondition,
  getRule,
  inExecutionPlane,
  listRules,
  parseCondition,
  ruleRollout,
  saveRule,
  validateScript,
  formatBindScope,
  type ConditionLeaf,
  type CondPayload,
  type RuleDetail,
  type RuleItem,
  type RuleScope
} from '@/api/rules';import { getMetadata } from '@/api/metadata';
import { listLists } from '@/api/lists';
import { listCumulatives } from '@/api/cumulatives';
import { useRulesStore, LAYER_RUNTIMES } from '@/stores/rules';
import { buildCompletionSources, runLint, type ScriptCompletionContext } from '@/utils/scriptAssist';
import ScriptEditor, { type EditorLanguage } from '@/components/ScriptEditor.vue';
import DiffConfirm from '@/components/DiffConfirm.vue';
import RuleSimulate from '@/components/RuleSimulate.vue';
import AsyncPreview from '@/components/AsyncPreview.vue';
import DlpRegexTest from '@/components/DlpRegexTest.vue';

const { t } = useI18n();
const rules = useRulesStore();

const page = ref(1);
const pageSize = ref(50);
const ruleList = ref<RuleItem[]>([]);
const filterQ = ref('');
const filterState = ref('');
const tableRef = ref();

const editorVisible = ref(false);
const isNew = ref(false);
const selectedRuleId = ref<string | null>(null);
const editMeta = ref<RuleDetail | null>(null);
const saving = ref(false);
const validateMsg = ref('');
const validateOk = ref<boolean | null>(null);
const previousBody = ref('');
const enableSimulate = ref(false);

const listCatalog = ref<string[]>([]);
const cumNames = ref<string[]>([]);
const sceneCatalog = ref<string[]>([]);
const appIdCatalog = ref<string[]>([]);
const conditionLeaves = ref<ConditionLeaf[]>([]);
const scriptEditorRef = ref<InstanceType<typeof ScriptEditor> | null>(null);
const diffConfirmRef = ref<InstanceType<typeof DiffConfirm> | null>(null);

const form = reactive<{
  ruleId: string;
  runtime: string;
  reason: string;
  risk: number;
  intent: string;
  isAsync: boolean;
  bindScope: RuleScope['bind_scope'];
  scenes: string;
  appIds: string;
  editorMode: 'simple' | 'advanced';
  body: string;
}>({
  ruleId: '',
  runtime: 'groovy',
  reason: '',
  risk: 100,
  intent: 'deny',
  isAsync: false,
  bindScope: 'global',
  scenes: '',
  appIds: '',
  editorMode: 'advanced',
  body: ''
});
const asyncCfg = reactive({ type: 'redis_stream' as 'redis_stream' | 'webhook', streamKey: '', url: '', message: '' });
const edgeBody = reactive({ listType: 'deny', keywordsText: '' });
const dlpBody = reactive({ entityType: 'phone_cn', priority: 0, pattern: '', maskTemplate: '' });

const filteredRules = computed(() => {
  const q = filterQ.value.trim().toLowerCase();
  const st = filterState.value;
  return ruleList.value.filter((r) => {
    if (st && r.rolloutState !== st) return false;
    if (!q) return true;
    return r.ruleId.toLowerCase().includes(q) || r.reasonCode.toLowerCase().includes(q);
  });
});
const paginatedRules = computed(() => filteredRules.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value));

const layerRuntimes = computed(() => LAYER_RUNTIMES[rules.currentLayer] || []);
const isPrompt = computed(() => form.runtime === 'prompt');
const isScript = computed(() => form.runtime === 'groovy' || form.runtime === 'lua');
const isEdgeDsl = computed(() => form.runtime === 'lua-dsl');
const isDlp = computed(() => form.runtime === 'dlp-dsl');
const isEdgeForm = computed(() => isEdgeDsl.value || isDlp.value);
const isReadOnly = computed(() => !isNew.value && editMeta.value?.rolloutState === 'disabled');
const canSave = computed(() => isNew.value || (editMeta.value !== null && editMeta.value.rolloutState !== 'disabled' && !inExecutionPlane(editMeta.value.rolloutState)));
const showAsync = computed(() => rules.currentLayer === 'cloud' && (form.runtime === 'groovy' || form.runtime === 'prompt'));
const showBindScope = computed(() => ['lua', 'groovy', 'prompt'].includes(form.runtime) || isEdgeForm.value);
const showValidate = computed(() => isScript.value || isEdgeForm.value);
const showBodyEditor = computed(() => isPrompt.value || !((isEdgeForm.value || isScript.value) && form.editorMode === 'simple'));
const showSimulate = computed(() => rules.currentLayer === 'cloud' && (isScript.value || isPrompt.value));
const drawerTitle = computed(() => (isNew.value ? t('rules.edit-title-new') : `${t('rules.edit-title-edit')} ${selectedRuleId.value || ''}`.trim()));
const validateStyle = computed(() => ({ color: validateOk.value === false ? '#991b1b' : validateOk.value ? '#166534' : '#64748b' }));

const sceneOptions = computed(() => mergeOptions(sceneCatalog.value, csvList(form.scenes)));
const appIdOptions = computed(() => mergeOptions(appIdCatalog.value, csvList(form.appIds)));
const sceneSelection = computed<string[]>({ get: () => csvList(form.scenes), set: (v) => (form.scenes = v.join(', ')) });
const appIdSelection = computed<string[]>({ get: () => csvList(form.appIds), set: (v) => (form.appIds = v.join(', ')) });

const editorLanguage = computed<EditorLanguage>(() => {
  if (form.runtime === 'groovy') return 'groovy';
  if (form.runtime === 'lua') return 'lua';
  if (form.runtime === 'lua-dsl' || form.runtime === 'dlp-dsl') return 'json';
  return 'prompt';
});
const completionCtx = computed<ScriptCompletionContext>(() => ({
  runtime: isScript.value ? (form.runtime as 'groovy' | 'lua') : editorLanguage.value === 'json' ? 'json' : 'prompt',
  listNames: listCatalog.value,
  cumNames: cumNames.value,
  contextVars: rules.contextVars
}));
const completionSources = computed(() => buildCompletionSources(completionCtx.value));
const lintFn = computed<((view: import('@codemirror/view').EditorView) => ReturnType<typeof runLint>) | null>(() => {
  if (!showValidate.value || form.editorMode === 'simple' || isReadOnly.value) return null;
  const lang = editorLanguage.value;
  return (view) =>
    runLint(view, {
      runtime: lang,
      layer: rules.currentLayer,
      listNames: listCatalog.value,
      cumNames: cumNames.value,
      contextVars: rules.contextVars
    });
});

function csvList(s: string): string[] {
  return String(s || '')
    .split(',')
    .map((x) => x.trim())
    .filter(Boolean);
}
function mergeOptions(catalog: string[], selected: string[]): string[] {
  return [...new Set([...catalog, ...selected])].sort((a, b) => a.localeCompare(b));
}
function defaultBody(runtime: string): string {
  if (runtime === 'groovy') return "def decide(ctx) {\n  return ctx.listMatch('deny_keyword')\n}";
  if (runtime === 'lua') return "function decide(ctx)\n  return listMatch('deny_keyword', ctx.content)\nend";
  if (runtime === 'prompt') return '';
  if (runtime === 'lua-dsl') return JSON.stringify({ list_type: 'deny', keywords: [] }, null, 2);
  if (runtime === 'dlp-dsl') return JSON.stringify({ entity_type: 'phone_cn', priority: 0 }, null, 2);
  return '{}';
}

function buildScope(): RuleScope {
  let bs = form.bindScope;
  if (isEdgeForm.value && bs === 'route') bs = 'global';
  const scope: RuleScope = { bind_scope: bs };
  const ref: RuleScope['bind_ref'] = {};
  if (bs === 'route') {
    const scenes = csvList(form.scenes);
    if (scenes.length) ref.scenes = scenes;
  } else if (bs === 'service') {
    const ids = csvList(form.appIds);
    if (ids.length) ref.app_ids = ids;
  }
  if (Object.keys(ref).length) scope.bind_ref = ref;
  return scope;
}

function readConditionPayload(): CondPayload | null {
  if (!conditionLeaves.value.length) return null;
  if (conditionLeaves.value.length === 1) return { ...conditionLeaves.value[0] } as CondPayload;
  return { op: 'and', children: conditionLeaves.value.map((c) => ({ ...c })) };
}

async function resolveBody(): Promise<unknown> {
  if (isPrompt.value) {
    try {
      return JSON.parse(form.body);
    } catch {
      return form.body;
    }
  }
  if (isEdgeDsl.value) {
    if (form.editorMode === 'simple') {
      return { list_type: edgeBody.listType, keywords: edgeBody.keywordsText.split(/[\n,，]/).map((s) => s.trim()).filter(Boolean) };
    }
    return JSON.parse(form.body);
  }
  if (isDlp.value) {
    if (form.editorMode === 'simple') {
      const body: Record<string, unknown> = { entity_type: dlpBody.entityType, priority: Number(dlpBody.priority) || 0 };
      if (dlpBody.pattern.trim()) body.pattern = dlpBody.pattern.trim();
      if (dlpBody.maskTemplate.trim()) body.mask_template = dlpBody.maskTemplate.trim();
      return body;
    }
    return JSON.parse(form.body);
  }
  if (form.editorMode === 'simple' && conditionLeaves.value.length) {
    return compileCondition(rules.currentLayer, form.runtime, readConditionPayload()!);
  }
  try {
    return JSON.parse(form.body);
  } catch {
    return form.body;
  }
}

async function loadCatalogs(): Promise<void> {
  try {
    const [lists, cums, meta] = await Promise.all([listLists(), listCumulatives(), getMetadata()]);
    listCatalog.value = Object.keys(lists.meta).sort();
    cumNames.value = cums.map((c) => c.cumulativeName);
    rules.setContextVars(meta.contextVars.map((v) => v.logical).filter(Boolean));
    const scenes = new Set<string>();
    const apps = new Set<string>();
    meta.sceneEntries.forEach((e) => {
      if (e.sceneId) scenes.add(e.sceneId);
      if (e.appId) apps.add(e.appId);
    });
    sceneCatalog.value = [...scenes];
    appIdCatalog.value = [...apps];
  } catch {
    /* ignore */
  }
}

async function loadRules(): Promise<void> {
  try {
    ruleList.value = await listRules(rules.currentLayer);
    page.value = 1;
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

function syncTableHighlight(): void {
  const row = selectedRuleId.value ? ruleList.value.find((r) => r.ruleId === selectedRuleId.value) : undefined;
  tableRef.value?.setCurrentRow(row);
}

function resetEditor(): void {
  Object.assign(form, {
    ruleId: '',
    runtime: layerRuntimes.value[0],
    reason: '',
    risk: 100,
    intent: 'deny',
    isAsync: false,
    bindScope: 'global',
    scenes: '',
    appIds: '',
    editorMode: 'advanced',
    body: ''
  });
  Object.assign(asyncCfg, { type: 'redis_stream', streamKey: '', url: '', message: '' });
  Object.assign(edgeBody, { listType: 'deny', keywordsText: '' });
  Object.assign(dlpBody, { entityType: 'phone_cn', priority: 0, pattern: '', maskTemplate: '' });
  conditionLeaves.value = [];
  validateMsg.value = '';
  validateOk.value = null;
  previousBody.value = '';
  enableSimulate.value = false;
}

async function confirmLeaveUnsaved(): Promise<boolean> {
  if (!rules.ruleFormDirty) return true;
  try {
    await ElMessageBox.confirm(t('rules.confirm-unsaved'), t('shell.confirm-title'), {
      type: 'warning',
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel')
    });
    return true;
  } catch {
    return false;
  }
}

function closeDrawer(): void {
  editorVisible.value = false;
  rules.resetDirty();
}

function requestClose(): void {
  if (!rules.ruleFormDirty) {
    closeDrawer();
    return;
  }
  void confirmLeaveUnsaved().then((ok) => {
    if (ok) closeDrawer();
  });
}

function onBeforeClose(done: () => void): void {
  if (!rules.ruleFormDirty) {
    rules.resetDirty();
    done();
    return;
  }
  void confirmLeaveUnsaved().then((ok) => {
    if (ok) {
      rules.resetDirty();
      done();
    }
  });
}

function openNew(): void {
  if (rules.ruleFormDirty) {
    void confirmLeaveUnsaved().then((ok) => {
      if (ok) doOpenNew();
    });
  } else {
    doOpenNew();
  }
}
function doOpenNew(): void {
  isNew.value = true;
  selectedRuleId.value = null;
  editMeta.value = null;
  resetEditor();
  form.runtime = layerRuntimes.value[0];
  form.body = defaultBody(form.runtime);
  if (isScript.value) {
    form.editorMode = 'simple';
    conditionLeaves.value = [{ type: 'list_match', list_name: listCatalog.value[0] || 'deny_keyword', value_source: 'content' }];
  } else {
    form.editorMode = isEdgeForm.value ? 'simple' : 'advanced';
  }
  rules.resetDirty();
  editorVisible.value = true;
}

async function onRowClick(row: RuleItem): Promise<void> {
  if (!(await confirmLeaveUnsaved())) return;
  await selectRule(row.ruleId);
}

async function selectRule(ruleId: string): Promise<void> {
  try {
    const r = await getRule(ruleId);
    isNew.value = false;
    selectedRuleId.value = ruleId;
    editMeta.value = r;
    form.reason = r.reasonCode;
    form.risk = r.riskScore;
    form.intent = r.intentAction;
    form.isAsync = r.isAsync;
    form.runtime = r.runtime;
    loadAsyncCfg(r.asyncActionConfig);
    const body = r.body;
    form.body = typeof body === 'string' ? body : JSON.stringify(body, null, 2);
    previousBody.value = form.body;
    form.editorMode = 'advanced';
    conditionLeaves.value = [];
    form.bindScope = r.scope?.bind_scope || 'global';
    form.scenes = (r.scope?.bind_ref?.scenes || []).join(', ');
    form.appIds = (r.scope?.bind_ref?.app_ids || []).join(', ');
    if (isEdgeForm.value && form.bindScope === 'route') form.bindScope = 'global';
    if (isEdgeDsl.value) {
      try {
        const obj = JSON.parse(form.body);
        if (obj && (obj.list_type === 'deny' || obj.list_type === 'allow')) {
          form.editorMode = 'simple';
          edgeBody.listType = obj.list_type;
          edgeBody.keywordsText = Array.isArray(obj.keywords) ? obj.keywords.join('\n') : '';
        }
      } catch {
        /* keep advanced */
      }
    } else if (isDlp.value) {
      try {
        const obj = JSON.parse(form.body);
        if (obj && obj.entity_type) {
          form.editorMode = 'simple';
          dlpBody.entityType = obj.entity_type;
          dlpBody.priority = obj.priority ?? 0;
          dlpBody.pattern = obj.pattern || '';
          dlpBody.maskTemplate = obj.mask_template || '';
        }
      } catch {
        /* keep advanced */
      }
    } else if (isScript.value) {
      try {
        const parsed = await parseCondition(rules.currentLayer, r.runtime, form.body);
        if (parsed.parseable && parsed.condition) {
          form.editorMode = 'simple';
          const c = parsed.condition;
          conditionLeaves.value = c.op === 'and' && c.children ? c.children : c.type ? [c as ConditionLeaf] : [];
        }
      } catch {
        /* keep advanced */
      }
    }
    editorVisible.value = true;
    rules.resetDirty();
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

function loadAsyncCfg(jsonStr: string | null): void {
  Object.assign(asyncCfg, { type: 'redis_stream', streamKey: '', url: '', message: '' });
  if (!jsonStr) return;
  try {
    const cfg = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : (jsonStr as Record<string, unknown>);
    const type = String((cfg as Record<string, unknown>).type || 'redis_stream');
    asyncCfg.type = type === 'webhook' ? 'webhook' : 'redis_stream';
    if (type === 'webhook') {
      asyncCfg.url = String((cfg as Record<string, unknown>).url || '');
      asyncCfg.message = JSON.stringify((cfg as Record<string, unknown>).body || {}, null, 2);
    } else {
      asyncCfg.streamKey = String((cfg as Record<string, unknown>).stream_key || '');
      asyncCfg.message = JSON.stringify((cfg as Record<string, unknown>).message || {}, null, 2);
    }
  } catch {
    /* ignore */
  }
}

function onRuntimeChange(): void {
  form.body = defaultBody(form.runtime);
  if (isScript.value) {
    form.editorMode = 'simple';
    conditionLeaves.value = [{ type: 'list_match', list_name: listCatalog.value[0] || 'deny_keyword', value_source: 'content' }];
  } else if (isEdgeDsl.value || isDlp.value) {
    form.editorMode = 'simple';
  } else {
    form.editorMode = 'advanced';
  }
  rules.markDirty();
}
function onAsyncChange(): void {
  if (form.isAsync) form.intent = 'allow';
  rules.markDirty();
}
function addListCond(): void {
  conditionLeaves.value.push({ type: 'list_match', list_name: listCatalog.value[0] || 'deny_keyword', value_source: 'content' });
  rules.markDirty();
}
function addCumCond(): void {
  conditionLeaves.value.push({ type: 'cumulative', cumulative_name: cumNames.value[0] || 'user_req_1h', compare: 'gte', threshold: 120 });
  rules.markDirty();
}
function removeLeaf(i: number): void {
  conditionLeaves.value.splice(i, 1);
  rules.markDirty();
}

async function doValidate(): Promise<void> {
  try {
    const body = await resolveBody();
    const res = await validateScript(rules.currentLayer, form.runtime, body);
    validateOk.value = res.valid;
    validateMsg.value = res.valid ? t('rules.valid-pass') : t('rules.valid-fail', [(res.errors || []).join('; ')]);
  } catch (e) {
    validateOk.value = false;
    validateMsg.value = (e as Error).message;
  }
}

function buildAsyncCfg(): unknown {
  if (!form.isAsync) return null;
  if (asyncCfg.type === 'webhook') {
    let bodyObj: unknown = asyncCfg.message;
    try {
      bodyObj = JSON.parse(asyncCfg.message);
    } catch {
      /* keep raw */
    }
    return { type: 'webhook', url: asyncCfg.url, body: bodyObj };
  }
  let messageObj: unknown = asyncCfg.message;
  try {
    messageObj = JSON.parse(asyncCfg.message);
  } catch {
    /* keep raw */
  }
  return { type: 'redis_stream', stream_key: asyncCfg.streamKey, message: messageObj };
}

function saveWithDiff(): void {
  if (!isNew.value && previousBody.value && form.body !== previousBody.value && diffConfirmRef.value) {
    diffConfirmRef.value.open();
    return;
  }
  void doSave();
}

async function doSave(): Promise<void> {
  const ruleId = isNew.value ? form.ruleId.trim() : selectedRuleId.value;
  if (!ruleId) {
    ElMessage.warning(t('rules.id-required'));
    return;
  }
  saving.value = true;
  try {
    const body = await resolveBody();
    if (form.runtime === 'lua' || form.runtime === 'groovy') {
      const vr = await validateScript(rules.currentLayer, form.runtime, body);
      if (!vr.valid) {
        ElMessage.error(t('rules.valid-fail', [(vr.errors || []).join('; ')]));
        return;
      }
    }
    await saveRule({
      rule_id: ruleId,
      bundle_id: bundleId(),
      layer: rules.currentLayer,
      runtime: form.runtime,
      reason_code: form.reason,
      risk_score: isDlp.value ? 0 : Number(form.risk),
      intent_action: form.isAsync || isDlp.value ? 'allow' : form.intent,
      scope: buildScope(),
      body,
      editor_mode: isPrompt.value ? null : form.editorMode,
      condition: isPrompt.value || isEdgeForm.value || form.editorMode !== 'simple' ? null : readConditionPayload(),
      is_async: form.isAsync,
      async_action_config: buildAsyncCfg() as never
    });
    ElMessage.success(isNew.value ? t('rules.created') : t('rules.saved'));
    previousBody.value = form.body;
    isNew.value = false;
    await loadRules();
    await selectRule(ruleId);
  } catch (e) {
    ElMessage.error(t('rules.save-fail', [(e as Error).message]));
  } finally {
    saving.value = false;
  }
}

async function runRollout(action: 'publish' | 'disable' | 'recover'): Promise<void> {
  if (!selectedRuleId.value) return;
  try {
    await ruleRollout(selectedRuleId.value, action);
    await loadRules();
    await selectRule(selectedRuleId.value);
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

watch(() => [filterQ.value, filterState.value], () => (page.value = 1));
watch([selectedRuleId, editorVisible], () => syncTableHighlight());
watch(
  () => rules.currentLayer,
  async () => {
    editorVisible.value = false;
    rules.resetDirty();
    await loadRules();
  }
);
watch(
  () => form.editorMode,
  () => scriptEditorRef.value?.refresh()
);

onMounted(async () => {
  await Promise.all([loadCatalogs(), loadRules()]);
});
onBeforeUnmount(() => rules.resetDirty());
</script>

<style scoped>
.rules-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.v-card-title {
  margin: 0;
  font-size: 16px;
}
.rules-help {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  border: 1px solid var(--el-border-color);
  background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary);
  text-align: center;
  line-height: 20px;
  cursor: help;
  font-size: 12px;
}
.rules-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 10px;
}
.v-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.rules-section + .rules-section {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.rules-section-title {
  margin: 0 0 12px;
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
}
.rules-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 16px;
}
.rules-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.rules-field-label {
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.cond-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  background: var(--el-fill-color-light);
  padding: 6px;
  border-radius: 4px;
  margin-bottom: 6px;
}
.async-card {
  border: 1px dashed var(--el-border-color);
  border-radius: 6px;
  padding: 12px;
  margin: 12px 0;
}
.drawer-foot {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: flex-end;
}
@media (max-width: 640px) {
  .rules-grid {
    grid-template-columns: 1fr;
  }
}
</style>
