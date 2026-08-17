<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { Plus, Trash2 } from '@lucide/vue'
import { useAppSettingsStore } from '../stores/appSettingsStore'

const props = defineProps({
  active: {
    type: Boolean,
    default: false
  }
})
const settingsStore = useAppSettingsStore()
const draftFields = ref([])
const draftUrgentRules = ref([])
const aliasInputs = reactive({})
const fieldErrors = reactive({})
const urgentTextInput = ref('')
const urgentMatchType = ref('EXACT')
const urgentRuleError = ref('')
const actionError = ref('')
const savedMessage = ref('')

const busy = computed(() =>
  settingsStore.importFieldSettingsLoading || settingsStore.importFieldSettingsSaving
)

watch(
  () => settingsStore.importFieldSettings,
  resetDraft,
  { deep: true, immediate: true }
)

watch(
  () => props.active,
  (active, wasActive) => {
    if (!active && wasActive) {
      clearFeedback()
    }
  }
)

function resetDraft() {
  const settings = settingsStore.importFieldSettings || {}

  draftFields.value = (settings.fields || []).map((field) => ({
    ...field,
    builtInAliases: (field.builtInAliases || []).filter((alias) =>
      normalizeAlias(alias) !== normalizeAlias(field.label)
    ),
    customAliases: [...(field.customAliases || [])]
  }))
  draftUrgentRules.value = (settings.urgentRules?.custom || []).map((rule) => ({
    text: rule.text,
    matchType: rule.matchType
  }))

  Object.keys(aliasInputs).forEach((key) => delete aliasInputs[key])
  Object.keys(fieldErrors).forEach((key) => delete fieldErrors[key])
  draftFields.value.forEach((field) => {
    aliasInputs[field.key] = ''
    fieldErrors[field.key] = ''
  })
  urgentTextInput.value = ''
  urgentMatchType.value = 'EXACT'
  urgentRuleError.value = ''
  actionError.value = ''
  savedMessage.value = ''
}

function clearFeedback() {
  Object.keys(fieldErrors).forEach((key) => {
    fieldErrors[key] = ''
  })
  urgentRuleError.value = ''
  actionError.value = ''
  savedMessage.value = ''
}

async function addAlias(field) {
  if (busy.value) {
    return
  }

  const alias = String(aliasInputs[field.key] || '').trim()

  if (!alias) {
    fieldErrors[field.key] = '字段名不能为空。'
    return
  }

  if (!normalizeAlias(alias)) {
    fieldErrors[field.key] = '字段名不能只包含空格、下划线或连字符。'
    return
  }

  const duplicateOwner = findAliasOwner(alias)

  if (duplicateOwner) {
    fieldErrors[field.key] = `该字段名已用于“${duplicateOwner}”。`
    return
  }

  field.customAliases.push(alias)
  fieldErrors[field.key] = ''
  const saved = await persistDraft('别名已自动保存')

  if (saved) {
    aliasInputs[field.key] = ''
  } else {
    aliasInputs[field.key] = alias
  }
}

async function removeAlias(field, index) {
  if (busy.value) {
    return
  }

  const alias = field.customAliases[index]
  if (!window.confirm(`是否删除[${field.label}]别名：${alias}？`)) {
    return
  }

  field.customAliases.splice(index, 1)
  await persistDraft('别名已删除并自动保存')
}

function findAliasOwner(value) {
  const normalized = normalizeAlias(value)

  for (const field of draftFields.value) {
    const aliases = [field.label, ...field.builtInAliases, ...field.customAliases]

    if (aliases.some((alias) => normalizeAlias(alias) === normalized)) {
      return field.label
    }
  }

  return ''
}

async function addUrgentRule() {
  if (busy.value) {
    return
  }

  const text = urgentTextInput.value.trim()

  if (!text) {
    urgentRuleError.value = '表示加急的文字不能为空。'
    return
  }

  if (allUrgentRules().some((rule) =>
    normalizeUrgentText(rule.text) === normalizeUrgentText(text)
  )) {
    urgentRuleError.value = '该文字已经存在。'
    return
  }

  draftUrgentRules.value.push({
    text,
    matchType: urgentMatchType.value
  })
  urgentRuleError.value = ''
  const saved = await persistDraft('加急文字已自动保存')

  if (saved) {
    urgentTextInput.value = ''
  } else {
    urgentTextInput.value = text
  }
}

async function removeUrgentRule(index) {
  if (busy.value) {
    return
  }

  const rule = draftUrgentRules.value[index]
  if (!window.confirm(`是否删除加急文字：${rule.text}？`)) {
    return
  }

  draftUrgentRules.value.splice(index, 1)
  await persistDraft('加急文字已删除并自动保存')
}

async function persistDraft(successMessage) {
  actionError.value = ''
  savedMessage.value = ''

  if (!validateDraft()) {
    return false
  }

  try {
    await settingsStore.updateImportFieldSettings(currentPayload())
    savedMessage.value = successMessage
    return true
  } catch (error) {
    const message = error.message
    resetDraft()
    actionError.value = message
    return false
  }
}

function validateDraft() {
  const aliases = new Map()
  let valid = true

  for (const field of draftFields.value) {
    for (const alias of [field.label, ...field.builtInAliases]) {
      const normalized = normalizeAlias(alias)

      if (normalized && !aliases.has(normalized)) {
        aliases.set(normalized, field.label)
      }
    }
  }

  for (const field of draftFields.value) {
    for (const alias of field.customAliases) {
      const normalized = normalizeAlias(alias)

      if (!normalized) {
        fieldErrors[field.key] = '字段名不能为空。'
        valid = false
      } else if (aliases.has(normalized)) {
        fieldErrors[field.key] = `字段名“${alias}”已用于“${aliases.get(normalized)}”。`
        valid = false
      } else {
        aliases.set(normalized, field.label)
      }
    }
  }

  const urgentTexts = new Set()

  for (const rule of allUrgentRules()) {
    const normalized = normalizeUrgentText(rule.text)

    if (!normalized) {
      urgentRuleError.value = '表示加急的文字不能为空。'
      valid = false
    } else if (urgentTexts.has(normalized)) {
      urgentRuleError.value = `文字“${rule.text}”重复。`
      valid = false
    } else {
      urgentTexts.add(normalized)
    }
  }

  return valid
}

function allUrgentRules() {
  return [
    ...(settingsStore.importFieldSettings.urgentRules?.builtIn || []),
    ...draftUrgentRules.value
  ]
}

function currentPayload() {
  return {
    fields: draftFields.value.map((field) => ({
      key: field.key,
      customAliases: [...field.customAliases]
    })),
    urgentRules: {
      custom: draftUrgentRules.value.map((rule) => ({
        text: rule.text,
        matchType: rule.matchType
      }))
    }
  }
}

function normalizeAlias(value) {
  return String(value || '')
    .trim()
    .toLowerCase()
    .replace(/[_\s-]/g, '')
}

function normalizeUrgentText(value) {
  return String(value || '').trim().toLowerCase()
}

function isUrgentField(field) {
  return field.key === 'urgent'
}

function fieldInputId(key) {
  return `import-field-alias-${String(key).replace(/[^a-zA-Z0-9_-]/g, '-')}`
}

function fieldErrorId(key) {
  return `${fieldInputId(key)}-error`
}

function ruleTypeLabel(matchType) {
  return matchType === 'CONTAINS' ? '包含文字' : '完全匹配'
}
</script>

<template>
  <div class="import-field-settings">
    <div class="import-field-settings-intro">
      <p>为固定字段添加可识别的相似列名。新增或删除后会自动保存；同一文件同时命中时优先读取后添加的别名。</p>
    </div>

    <p v-if="settingsStore.importFieldSettingsSaving" class="dialog-status" role="status">
      自动保存中...
    </p>
    <p v-else-if="savedMessage" class="dialog-status" role="status">{{ savedMessage }}</p>
    <p v-if="actionError" class="dialog-error" role="alert">{{ actionError }}</p>

    <p
      v-if="settingsStore.importFieldSettingsLoading && draftFields.length === 0"
      class="import-field-settings-empty"
    >
      载入中...
    </p>

    <div v-else class="import-field-list">
      <article v-for="field in draftFields" :key="field.key" class="import-field-item">
        <div class="import-field-heading">
          <h3>{{ field.label }}</h3>
          <span class="field-requirement-badge" :class="{ required: field.required }">
            {{ field.required ? '必填' : '选填' }}
          </span>
        </div>

        <div class="import-alias-group">
          <h4>别名</h4>
          <div class="import-chip-list">
            <span
              v-for="alias in field.builtInAliases"
              :key="`built-in-${alias}`"
              class="import-chip built-in"
            >
              {{ alias }}
            </span>
            <span
              v-for="(alias, index) in field.customAliases"
              :key="`custom-${alias}-${index}`"
              class="import-chip custom"
            >
              <span>{{ alias }}</span>
              <button
                type="button"
                :aria-label="`删除 ${field.label} 的别名 ${alias}`"
                :disabled="busy"
                @click="removeAlias(field, index)"
              >
                <Trash2 :size="13" aria-hidden="true" />
              </button>
            </span>
          </div>

          <div class="import-add-row">
            <label class="visually-hidden" :for="fieldInputId(field.key)">
              新增 {{ field.label }} 的相似字段名
            </label>
            <input
              :id="fieldInputId(field.key)"
              v-model="aliasInputs[field.key]"
              type="text"
              maxlength="120"
              placeholder="输入相似字段名"
              :aria-describedby="fieldErrors[field.key] ? fieldErrorId(field.key) : undefined"
              :aria-invalid="Boolean(fieldErrors[field.key])"
              :disabled="busy"
              @keydown.enter.prevent="addAlias(field)"
            />
            <button
              class="icon-button"
              type="button"
              :disabled="busy"
              @click="addAlias(field)"
            >
              <Plus :size="16" />
              添加
            </button>
          </div>
          <small
            v-if="fieldErrors[field.key]"
            :id="fieldErrorId(field.key)"
            class="form-field-error"
            role="alert"
          >
            {{ fieldErrors[field.key] }}
          </small>
        </div>

        <div v-if="isUrgentField(field)" class="urgent-rule-settings">
          <div class="import-alias-group">
            <h4>表示加急的文字</h4>
            <p>匹配备注标签字段的内容后，工单会使用红框并按加急顺序显示。</p>
            <div class="import-chip-list">
              <span
                v-for="rule in settingsStore.importFieldSettings.urgentRules?.builtIn || []"
                :key="`${rule.text}-${rule.matchType}`"
                class="import-chip built-in"
              >
                {{ rule.text }} · {{ ruleTypeLabel(rule.matchType) }}
              </span>
              <span
                v-if="!(settingsStore.importFieldSettings.urgentRules?.builtIn || []).length"
                class="import-chip-empty"
              >
                暂无系统规则
              </span>
            </div>
          </div>

          <div class="import-alias-group">
            <h4>自定义加急文字</h4>
            <div v-if="draftUrgentRules.length" class="import-chip-list">
              <span
                v-for="(rule, index) in draftUrgentRules"
                :key="`${rule.text}-${rule.matchType}-${index}`"
                class="import-chip custom"
              >
                <span>{{ rule.text }} · {{ ruleTypeLabel(rule.matchType) }}</span>
                <button
                  type="button"
                  :aria-label="`删除加急文字 ${rule.text}`"
                  :disabled="busy"
                  @click="removeUrgentRule(index)"
                >
                  <Trash2 :size="13" aria-hidden="true" />
                </button>
              </span>
            </div>
            <span v-else class="import-chip-empty">尚未添加</span>

            <div class="import-add-row urgent-rule-add-row">
              <label class="visually-hidden" for="urgent-rule-text">新增表示加急的文字</label>
              <input
                id="urgent-rule-text"
                v-model="urgentTextInput"
                type="text"
                maxlength="120"
                placeholder="例如：红旗"
                :aria-describedby="urgentRuleError ? 'urgent-rule-error' : undefined"
                :aria-invalid="Boolean(urgentRuleError)"
                :disabled="busy"
                @keydown.enter.prevent="addUrgentRule"
              />
              <label class="visually-hidden" for="urgent-rule-match-type">加急文字匹配方式</label>
              <select id="urgent-rule-match-type" v-model="urgentMatchType" :disabled="busy">
                <option value="EXACT">完全匹配</option>
                <option value="CONTAINS">包含文字</option>
              </select>
              <button
                class="icon-button"
                type="button"
                :disabled="busy"
                @click="addUrgentRule"
              >
                <Plus :size="16" />
                添加
              </button>
            </div>
            <small
              v-if="urgentRuleError"
              id="urgent-rule-error"
              class="form-field-error"
              role="alert"
            >
              {{ urgentRuleError }}
            </small>
          </div>
        </div>
      </article>
    </div>

  </div>
</template>
