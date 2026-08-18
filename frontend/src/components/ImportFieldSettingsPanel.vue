<script setup>
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { Save, X } from '@lucide/vue'
import { useAppSettingsStore } from '../stores/appSettingsStore'

const props = defineProps({
  active: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['saved'])
const settingsStore = useAppSettingsStore()
const draftFields = ref([])
const draftRemarkTags = ref([])
const selectedRemarkTagIndex = ref(-1)
const remarkTagInput = ref('')
const remarkContainsTextInput = ref('')
const aliasInputs = reactive({})
const fieldErrors = reactive({})
const remarkTagOptionsError = ref('')
const remarkTagEditorErrors = reactive({
  name: '',
  color: '',
  containsText: ''
})
const actionError = ref('')
const fieldNotices = reactive({})
const fieldNoticeTimers = new Map()

const busy = computed(() =>
  settingsStore.importFieldSettingsLoading || settingsStore.importFieldSettingsSaving
)
const selectedRemarkTag = computed(() =>
  draftRemarkTags.value[selectedRemarkTagIndex.value] || null
)
const selectedRemarkTagChanged = computed(() => {
  const tag = selectedRemarkTag.value

  if (!tag) {
    return false
  }

  const savedTag = findSavedRemarkTag(tag)
  if (!savedTag) {
    return true
  }

  return trimmedRemarkTagName(tag.name) !== trimmedRemarkTagName(savedTag.name)
    || normalizeHexColor(tag.color) !== normalizeHexColor(savedTag.color)
    || !sameTextList(tag.containsTexts, savedTag.containsTexts)
})
const dirtyRemarkTagIndexes = computed(() => draftRemarkTags.value
  .map((tag, index) => ({ tag, index }))
  .filter(({ tag }) => {
    const savedTag = findSavedRemarkTag(tag)

    return !savedTag
      || trimmedRemarkTagName(tag.name) !== trimmedRemarkTagName(savedTag.name)
      || normalizeHexColor(tag.color) !== normalizeHexColor(savedTag.color)
      || !sameTextList(tag.containsTexts, savedTag.containsTexts)
  })
  .map(({ index }) => index))

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

onBeforeUnmount(clearFieldNotices)

function resetDraft() {
  const settings = settingsStore.importFieldSettings || {}
  const previouslySelectedTag = selectedRemarkTag.value
    ? { ...selectedRemarkTag.value }
    : null

  draftFields.value = (settings.fields || []).map((field) => ({
    ...field,
    builtInAliases: (field.builtInAliases || []).filter((alias) =>
      normalizeAlias(alias) !== normalizeAlias(field.label)
    ),
    customAliases: [...(field.customAliases || [])]
  }))
  draftRemarkTags.value = (settings.remarkTags || []).map((tag) => ({
    id: tag.id ?? null,
    systemKey: tag.systemKey || null,
    name: tag.name || '',
    color: normalizeHexColor(tag.color || (tag.systemKey === 'URGENT' ? '#FF6F61' : '#3B82F6')),
    containsTexts: cloneTextList(tag.containsTexts)
  }))

  selectedRemarkTagIndex.value = previouslySelectedTag
    ? draftRemarkTags.value.findIndex((tag) => sameRemarkTag(tag, previouslySelectedTag))
    : -1

  Object.keys(aliasInputs).forEach((key) => delete aliasInputs[key])
  Object.keys(fieldErrors).forEach((key) => delete fieldErrors[key])
  draftFields.value.forEach((field) => {
    aliasInputs[field.key] = ''
    fieldErrors[field.key] = ''
  })
  remarkTagInput.value = ''
  remarkContainsTextInput.value = ''
  clearRemarkTagErrors()
  actionError.value = ''
}

function clearFeedback() {
  Object.keys(fieldErrors).forEach((key) => {
    fieldErrors[key] = ''
  })
  clearRemarkTagErrors()
  actionError.value = ''
  clearFieldNotices()
}

async function addAlias(field) {
  if (busy.value) {
    return
  }

  if (!ensureRemarkTagDefinitionsSaved()) {
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
  const saved = await persistDraft()

  if (saved) {
    aliasInputs[field.key] = ''
    setFieldNotice(field.key, `${alias}已添加`)
    await nextTick()
    document.getElementById(fieldInputId(field.key))?.focus()
  } else {
    aliasInputs[field.key] = alias
  }
}

async function removeAlias(field, index) {
  if (busy.value) {
    return
  }

  if (!ensureRemarkTagDefinitionsSaved()) {
    return
  }

  const alias = field.customAliases[index]
  if (!window.confirm(`是否删除[${field.label}]别名：${alias}？`)) {
    return
  }

  field.customAliases.splice(index, 1)
  if (await persistDraft()) {
    setFieldNotice(field.key, `${alias}已删除`, 'danger')
  }
}

function handleAliasKeydown(event, field) {
  if (event.key === 'Enter' || event.key === ',') {
    event.preventDefault()
    addAlias(field)
    return
  }

  if (event.key === 'Backspace'
      && !aliasInputs[field.key]
      && field.customAliases.length > 0) {
    event.preventDefault()
    removeAlias(field, field.customAliases.length - 1)
  }
}

function focusAliasInput(event) {
  event.currentTarget.querySelector('input')?.focus()
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

function addRemarkTag() {
  if (busy.value) {
    return
  }

  if (!ensureRemarkTagDefinitionsSaved()) {
    return
  }

  const name = String(remarkTagInput.value || '').trim()

  if (!name) {
    remarkTagOptionsError.value = '标签名称不能为空。'
    return
  }

  if (name.length > 80) {
    remarkTagOptionsError.value = '标签名称最长为 80 个字符。'
    return
  }

  if (findRemarkMatchOwner(name)) {
    remarkTagOptionsError.value = '标签名称已作为标签名称或包含文字使用。'
    return
  }

  draftRemarkTags.value.push({
    id: null,
    systemKey: null,
    name,
    color: '#3B82F6',
    containsTexts: []
  })
  selectedRemarkTagIndex.value = draftRemarkTags.value.length - 1
  remarkTagInput.value = ''
  clearRemarkTagErrors()
}

async function removeRemarkTag(tag, index) {
  if (busy.value || isProtectedRemarkTag(tag)) {
    return
  }

  if (!ensureRemarkTagDefinitionsSaved(index)) {
    return
  }

  if (!window.confirm(`是否删除备注标签「${tag.name}」？既有工单上的该标签也会一并移除。`)) {
    return
  }

  draftRemarkTags.value.splice(index, 1)
  if (selectedRemarkTagIndex.value === index) {
    selectedRemarkTagIndex.value = -1
  } else if (selectedRemarkTagIndex.value > index) {
    selectedRemarkTagIndex.value -= 1
  }

  if (tag.id === null || tag.id === undefined) {
    clearRemarkTagErrors()
    return
  }

  if (await persistDraft(true)) {
    setFieldNotice('urgent', `${tag.name}已删除`, 'danger')
  }
}

function selectRemarkTag(index) {
  if (index !== selectedRemarkTagIndex.value && !ensureRemarkTagDefinitionsSaved()) {
    return
  }

  selectedRemarkTagIndex.value = index
  clearRemarkTagErrors()
}

function handleRemarkTagInputFocus() {
  selectedRemarkTagIndex.value = -1
}

function handleRemarkTagInputKeydown(event) {
  if (event.key === 'Enter' || event.key === ',') {
    event.preventDefault()
    addRemarkTag()
  }
}

async function saveRemarkTag() {
  const tag = selectedRemarkTag.value

  if (busy.value || !tag || !selectedRemarkTagChanged.value) {
    return
  }

  if (await persistDraft(true)) {
    setFieldNotice('urgent', `${tag.name}已保存`)
  }
}

function addRemarkContainsText() {
  const tag = selectedRemarkTag.value

  if (busy.value || !tag) {
    return
  }

  const text = remarkContainsTextInput.value.trim()

  if (!text) {
    remarkTagEditorErrors.containsText = '包含文字不能为空。'
    return
  }

  if (text.length > 120) {
    remarkTagEditorErrors.containsText = '包含文字最长为 120 个字符。'
    return
  }

  if (findRemarkMatchOwner(text)) {
    remarkTagEditorErrors.containsText = '该文字已作为标签名称或包含文字使用。'
    return
  }

  tag.containsTexts.push(text)
  remarkContainsTextInput.value = ''
  remarkTagEditorErrors.containsText = ''
}

function removeRemarkContainsText(index) {
  const tag = selectedRemarkTag.value

  if (busy.value || !tag) {
    return
  }

  tag.containsTexts.splice(index, 1)
  remarkTagEditorErrors.containsText = ''
}

function handleRemarkContainsTextKeydown(event) {
  if (event.key === 'Enter' || event.key === ',') {
    event.preventDefault()
    addRemarkContainsText()
    return
  }

  const tag = selectedRemarkTag.value
  if (event.key === 'Backspace'
      && !remarkContainsTextInput.value
      && tag?.containsTexts.length > 0) {
    event.preventDefault()
    removeRemarkContainsText(tag.containsTexts.length - 1)
  }
}

function updateRemarkTagColor(event) {
  if (selectedRemarkTag.value) {
    const digits = sanitizeHexDigits(event.target.value)
    event.target.value = digits
    selectedRemarkTag.value.color = `#${digits}`
  }
}

function normalizeSelectedRemarkTagColor() {
  if (selectedRemarkTag.value) {
    selectedRemarkTag.value.color = normalizeHexColor(selectedRemarkTag.value.color)
  }
}

async function persistDraft(refreshWorkOrders = false) {
  actionError.value = ''

  if (!validateDraft()) {
    return false
  }

  try {
    await settingsStore.updateImportFieldSettings(currentPayload())
    if (refreshWorkOrders) {
      emit('saved')
    }
    return true
  } catch (error) {
    const message = error.message
    resetDraft()
    actionError.value = message
    return false
  }
}

function setFieldNotice(fieldKey, message, tone = 'info') {
  const previousTimer = fieldNoticeTimers.get(fieldKey)

  if (previousTimer) {
    window.clearTimeout(previousTimer)
  }

  fieldNotices[fieldKey] = { message, tone }
  fieldNoticeTimers.set(fieldKey, window.setTimeout(() => {
    delete fieldNotices[fieldKey]
    fieldNoticeTimers.delete(fieldKey)
  }, 5000))
}

function clearFieldNotices() {
  fieldNoticeTimers.forEach((timer) => window.clearTimeout(timer))
  fieldNoticeTimers.clear()
  Object.keys(fieldNotices).forEach((key) => delete fieldNotices[key])
}

function validateDraft() {
  const aliases = new Map()
  let valid = true

  Object.keys(fieldErrors).forEach((key) => {
    fieldErrors[key] = ''
  })
  clearRemarkTagErrors()

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

  const matchTextOwners = new Map()

  for (const [index, tag] of draftRemarkTags.value.entries()) {
    const normalizedName = normalizeRemarkTagName(tag.name)

    if (!normalizedName) {
      selectInvalidRemarkTag(index, 'name', '标签名称不能为空。')
      valid = false
    } else if (String(tag.name).trim().length > 80) {
      selectInvalidRemarkTag(index, 'name', '标签名称最长为 80 个字符。')
      valid = false
    } else if (matchTextOwners.has(normalizedName)) {
      selectInvalidRemarkTag(
        index,
        'name',
        `标签名称“${tag.name}”已作为${matchTextOwners.get(normalizedName)}使用。`
      )
      valid = false
    } else {
      matchTextOwners.set(normalizedName, '标签名称')
    }

    if (!/^#[0-9A-F]{6}$/.test(normalizeHexColor(tag.color))) {
      selectInvalidRemarkTag(index, 'color', '标签颜色须填写六位十六进制色码。')
      valid = false
    }

    for (const text of tag.containsTexts) {
      const normalizedText = normalizeRemarkText(text)

      if (!normalizedText) {
        selectInvalidRemarkTag(index, 'containsText', '包含文字不能为空。')
        valid = false
      } else if (String(text).trim().length > 120) {
        selectInvalidRemarkTag(index, 'containsText', '包含文字最长为 120 个字符。')
        valid = false
      } else if (matchTextOwners.has(normalizedText)) {
        selectInvalidRemarkTag(
          index,
          'containsText',
          `包含文字“${text}”已作为${matchTextOwners.get(normalizedText)}使用。`
        )
        valid = false
      } else {
        matchTextOwners.set(normalizedText, '包含文字')
      }
    }
  }

  return valid
}

function selectInvalidRemarkTag(index, field, message) {
  if (!remarkTagEditorErrors.name
      && !remarkTagEditorErrors.color
      && !remarkTagEditorErrors.containsText) {
    selectedRemarkTagIndex.value = index
    remarkTagEditorErrors[field] = message
  }
  remarkTagOptionsError.value ||= message
}

function findRemarkMatchOwner(value) {
  const normalized = normalizeRemarkText(value)

  for (const tag of draftRemarkTags.value) {
    if (normalizeRemarkTagName(tag.name) === normalized) {
      return `标签名称“${tag.name}”`
    }

    const containsText = tag.containsTexts.find((text) =>
      normalizeRemarkText(text) === normalized
    )
    if (containsText) {
      return `包含文字“${containsText}”`
    }
  }

  return ''
}

function currentPayload() {
  return {
    fields: draftFields.value.map((field) => ({
      key: field.key,
      customAliases: [...field.customAliases]
    })),
    remarkTags: draftRemarkTags.value.map((tag) => ({
      id: tag.id,
      systemKey: tag.systemKey,
      name: String(tag.name || '').trim(),
      color: normalizeHexColor(tag.color),
      containsTexts: cloneTextList(tag.containsTexts)
    }))
  }
}

function findSavedRemarkTag(tag) {
  const savedTags = settingsStore.importFieldSettings.remarkTags || []
  if (tag.id !== null && tag.id !== undefined) {
    return savedTags.find((savedTag) => String(savedTag.id) === String(tag.id))
  }
  if (tag.systemKey) {
    return savedTags.find((savedTag) => savedTag.systemKey === tag.systemKey)
  }
  return null
}

function cloneTextList(values) {
  return (values || []).map((value) => String(value || '').trim())
}

function sameTextList(left, right) {
  const leftTexts = cloneTextList(left)
  const rightTexts = cloneTextList(right)

  return leftTexts.length === rightTexts.length
    && leftTexts.every((text, index) => text === rightTexts[index])
}

function remarkTagIdentity(tag) {
  if (!tag) {
    return ''
  }
  if (tag.id !== null && tag.id !== undefined) {
    return `id:${tag.id}`
  }
  if (tag.systemKey) {
    return `system:${tag.systemKey}`
  }
  return `name:${normalizeRemarkTagName(tag.name)}`
}

function sameRemarkTag(left, right) {
  if (left.id !== null && left.id !== undefined && right.id !== null && right.id !== undefined) {
    return String(left.id) === String(right.id)
  }
  if (left.systemKey || right.systemKey) {
    return Boolean(left.systemKey) && left.systemKey === right.systemKey
  }
  return normalizeRemarkTagName(left.name) === normalizeRemarkTagName(right.name)
}

function normalizeAlias(value) {
  return String(value || '')
    .trim()
    .toLowerCase()
    .replace(/[_\s-]/g, '')
}

function normalizeRemarkTagName(value) {
  return trimmedRemarkTagName(value).toLocaleLowerCase('zh-CN')
}

function trimmedRemarkTagName(value) {
  return String(value || '').trim()
}

function normalizeRemarkText(value) {
  return String(value || '').trim().toLocaleLowerCase('zh-CN')
}

function sanitizeHexDigits(value) {
  return String(value || '')
    .replace(/^#/, '')
    .toUpperCase()
    .replace(/[^0-9A-F]/g, '')
    .slice(0, 6)
}

function normalizeHexColor(value) {
  return `#${sanitizeHexDigits(value)}`
}

function hexColorDigits(value) {
  return sanitizeHexDigits(value)
}

function pickerColor(value) {
  const color = normalizeHexColor(value)
  return /^#[0-9A-F]{6}$/.test(color) ? color : '#000000'
}

function clearRemarkTagErrors() {
  remarkTagOptionsError.value = ''
  remarkTagEditorErrors.name = ''
  remarkTagEditorErrors.color = ''
  remarkTagEditorErrors.containsText = ''
}

function ensureRemarkTagDefinitionsSaved(excludedIndex = -1) {
  const dirtyIndex = dirtyRemarkTagIndexes.value.find((index) => index !== excludedIndex)

  if (dirtyIndex === undefined) {
    return true
  }

  selectedRemarkTagIndex.value = dirtyIndex
  remarkTagOptionsError.value = '请先保存当前标签的名称、颜色和包含文字。'
  return false
}

function isRemarkTagField(field) {
  return field.key === 'urgent'
}

function isProtectedRemarkTag(tag) {
  return tag.systemKey === 'URGENT'
}

function fieldInputId(key) {
  return `import-field-alias-${String(key).replace(/[^a-zA-Z0-9_-]/g, '-')}`
}

function fieldErrorId(key) {
  return `${fieldInputId(key)}-error`
}

</script>

<template>
  <div class="import-field-settings">
    <div class="import-field-settings-intro">
      <p>为固定字段添加可识别的相似列名。新增或删除后会自动保存；同一文件同时命中时优先读取后添加的别名。</p>
    </div>

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
          <div class="import-field-title">
            <h3>{{ field.label }}</h3>
            <span class="import-field-alias-label">别名</span>
            <span
              v-if="fieldNotices[field.key]"
              class="import-field-notice"
              :class="{ danger: fieldNotices[field.key].tone === 'danger' }"
              role="status"
            >
              {{ fieldNotices[field.key].message }}
            </span>
          </div>
          <span class="field-requirement-badge" :class="{ required: field.required }">
            {{ field.required ? '必填' : '选填' }}
          </span>
        </div>

        <div class="import-alias-group">
          <div
            class="recipient-tag-input import-alias-tag-input"
            :class="{ invalid: fieldErrors[field.key] }"
            @click="focusAliasInput"
          >
            <span
              v-for="alias in field.builtInAliases"
              :key="`built-in-${alias}`"
              class="recipient-tag import-alias-tag built-in"
              title="预设别名，不可删除"
            >
              <span>{{ alias }}</span>
            </span>
            <span
              v-for="(alias, index) in field.customAliases"
              :key="`custom-${alias}-${index}`"
              class="recipient-tag import-alias-tag custom"
            >
              <span>{{ alias }}</span>
              <button
                type="button"
                :aria-label="`删除 ${field.label} 的别名 ${alias}`"
                :disabled="busy"
                @click.stop="removeAlias(field, index)"
              >
                <X :size="13" aria-hidden="true" />
              </button>
            </span>
            <label class="visually-hidden" :for="fieldInputId(field.key)">
              新增 {{ field.label }} 的相似字段名
            </label>
            <input
              :id="fieldInputId(field.key)"
              v-model="aliasInputs[field.key]"
              class="recipient-tag-search"
              type="text"
              maxlength="120"
              :placeholder="field.builtInAliases.length || field.customAliases.length ? '继续添加' : '输入相似字段名'"
              :aria-describedby="fieldErrors[field.key] ? fieldErrorId(field.key) : undefined"
              :aria-invalid="Boolean(fieldErrors[field.key])"
              :disabled="busy"
              @keydown="handleAliasKeydown($event, field)"
            />
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

        <div v-if="isRemarkTagField(field)" class="remark-tag-settings">
          <div class="import-alias-group">
            <h4>备注标签选项</h4>
            <p>匹配备注标签字段后，工单外框会使用第一项标签颜色；鼠标悬停时会依序显示全部标签名称。</p>
            <div
              class="recipient-tag-input remark-tag-input"
              :class="{ invalid: remarkTagOptionsError }"
              @click="$event.currentTarget.querySelector('input')?.focus()"
            >
              <span
                v-for="(tag, index) in draftRemarkTags"
                :key="remarkTagIdentity(tag)"
                class="recipient-tag remark-tag-option-tag"
                :class="{ active: selectedRemarkTagIndex === index }"
                :style="{ '--remark-tag-color': pickerColor(tag.color) }"
              >
                <button
                  type="button"
                  class="remark-tag-select-button"
                  :aria-label="`编辑备注标签 ${tag.name}`"
                  :disabled="busy"
                  @click.stop="selectRemarkTag(index)"
                >
                  <span>{{ tag.name }}</span>
                </button>
                <button
                  v-if="!isProtectedRemarkTag(tag)"
                  type="button"
                  :aria-label="`删除备注标签 ${tag.name}`"
                  :disabled="busy"
                  @click.stop="removeRemarkTag(tag, index)"
                >
                  <X :size="13" aria-hidden="true" />
                </button>
              </span>
              <label class="visually-hidden" for="remark-tag-name-input">新增备注标签</label>
              <input
                id="remark-tag-name-input"
                v-model="remarkTagInput"
                class="recipient-tag-search"
                type="text"
                maxlength="80"
                :placeholder="draftRemarkTags.length ? '继续添加' : '输入标签名称'"
                :aria-describedby="remarkTagOptionsError ? 'remark-tag-options-error' : undefined"
                :aria-invalid="Boolean(remarkTagOptionsError)"
                :disabled="busy"
                @click="handleRemarkTagInputFocus"
                @focus="handleRemarkTagInputFocus"
                @keydown="handleRemarkTagInputKeydown"
              />
            </div>
            <small
              v-if="remarkTagOptionsError"
              id="remark-tag-options-error"
              class="form-field-error"
              role="alert"
            >
              {{ remarkTagOptionsError }}
            </small>
          </div>

          <div v-if="selectedRemarkTag" class="order-source-option-editor remark-tag-option-editor">
            <label>
              <span class="form-field-label">
                标签名称
                <span class="required-marker" aria-hidden="true">*</span>
                <small v-if="remarkTagEditorErrors.name" class="form-field-error" role="alert">
                  {{ remarkTagEditorErrors.name }}
                </small>
              </span>
              <input
                v-model="selectedRemarkTag.name"
                type="text"
                maxlength="80"
                placeholder="例如 延后"
                required
                :aria-invalid="Boolean(remarkTagEditorErrors.name)"
                :disabled="busy"
              />
            </label>

            <label>
              <span class="form-field-label">
                标签颜色
                <span class="required-marker" aria-hidden="true">*</span>
                <small v-if="remarkTagEditorErrors.color" class="form-field-error" role="alert">
                  {{ remarkTagEditorErrors.color }}
                </small>
              </span>
              <span class="order-source-color-field">
                <input
                  :value="pickerColor(selectedRemarkTag.color)"
                  type="color"
                  :disabled="busy"
                  aria-label="选择备注标签颜色"
                  @input="selectedRemarkTag.color = $event.target.value.toUpperCase()"
                />
                <span class="hex-color-input">
                  <span aria-hidden="true">#</span>
                  <input
                    :value="hexColorDigits(selectedRemarkTag.color)"
                    type="text"
                    maxlength="6"
                    placeholder="FF6F61"
                    aria-label="备注标签颜色十六进制值"
                    required
                    :aria-invalid="Boolean(remarkTagEditorErrors.color)"
                    :disabled="busy"
                    @input="updateRemarkTagColor"
                    @blur="normalizeSelectedRemarkTagColor"
                  />
                </span>
              </span>
            </label>

            <div class="remark-rule-editor">
              <h4>包含文字</h4>
              <div class="remark-rule-input-row">
                <div
                  class="recipient-tag-input remark-rule-tag-input"
                  :class="{ invalid: remarkTagEditorErrors.containsText }"
                  @click="$event.currentTarget.querySelector('input')?.focus()"
                >
                  <span
                    v-for="(text, index) in selectedRemarkTag.containsTexts"
                    :key="`${text}-${index}`"
                    class="recipient-tag import-alias-tag custom"
                  >
                    <span>{{ text }}</span>
                    <button
                      type="button"
                      :aria-label="`删除包含文字 ${text}`"
                      :disabled="busy"
                      @click.stop="removeRemarkContainsText(index)"
                    >
                      <X :size="13" aria-hidden="true" />
                    </button>
                  </span>
                  <label class="visually-hidden" for="remark-contains-text">新增备注标签包含文字</label>
                  <input
                    id="remark-contains-text"
                    v-model="remarkContainsTextInput"
                    class="recipient-tag-search"
                    type="text"
                    maxlength="120"
                    placeholder="输入包含文字"
                    :aria-describedby="remarkTagEditorErrors.containsText ? 'remark-contains-text-error' : undefined"
                    :aria-invalid="Boolean(remarkTagEditorErrors.containsText)"
                    :disabled="busy"
                    @keydown="handleRemarkContainsTextKeydown"
                  />
                </div>
                <div class="order-source-option-editor-actions">
                  <button
                    class="icon-button primary-action"
                    type="button"
                    :disabled="busy || !selectedRemarkTagChanged"
                    @click="saveRemarkTag"
                  >
                    <span v-if="settingsStore.importFieldSettingsSaving" class="loading-spinner" aria-hidden="true"></span>
                    <Save v-else :size="18" aria-hidden="true" />
                    {{ settingsStore.importFieldSettingsSaving ? '保存中' : '保存' }}
                  </button>
                </div>
              </div>
              <small
                v-if="remarkTagEditorErrors.containsText"
                id="remark-contains-text-error"
                class="form-field-error"
                role="alert"
              >
                {{ remarkTagEditorErrors.containsText }}
              </small>
            </div>
          </div>
        </div>
      </article>
    </div>
  </div>
</template>
