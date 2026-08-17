<script setup>
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { Check, Eye, EyeOff, Pencil, Plus, Save, Trash2, X } from '@lucide/vue'
import HelpTooltip from './HelpTooltip.vue'
import ImportFieldSettingsPanel from './ImportFieldSettingsPanel.vue'
import { useAppSettingsStore } from '../stores/appSettingsStore'

const STORED_SMTP_AUTH_CODE = '••••••••'

const props = defineProps({
  open: {
    type: Boolean,
    default: false
  },
  initialTab: {
    type: String,
    default: 'basic'
  }
})

const emit = defineEmits(['close', 'update-tab'])
const settingsStore = useAppSettingsStore()
const activeTab = ref(normalizeTab(props.initialTab))
const amountInput = ref('')
const amountError = ref('')
const weekStartTimeInput = ref('')
const weekStartTimeError = ref('')
const orderSourceOptions = ref([])
const orderSourceOptionInput = ref('')
const orderSourceOptionsError = ref('')
const orderSourceOptionInputElement = ref(null)
const fieldError = ref('')
const savedMessage = ref('')
const emailEditing = ref(false)
const smtpAuthCodeVisible = ref(false)
const recipientDeletingId = ref(null)
const recipientCreating = ref(false)
const recipientEditingId = ref(null)
const recipientNameDraft = ref('')
const recipientEmailDraft = ref('')
const recipientEditActionError = ref('')
const recipientCreateNameInput = ref(null)
const recipientNameInput = ref(null)
let fieldErrorTimer = null
let savedMessageTimer = null

const emailForm = reactive({
  senderEmail: '',
  smtpAuthCode: '',
  smtpHost: '',
  smtpPort: '465',
  smtpSecurity: 'SSL'
})

const recipientForm = reactive({
  name: '',
  email: ''
})

const emailSenderErrors = reactive({
  senderEmail: '',
  smtpAuthCode: '',
  smtpHost: '',
  smtpPort: '',
  smtpSecurity: ''
})

const recipientErrors = reactive({
  name: '',
  email: ''
})

const recipientEditErrors = reactive({
  name: '',
  email: ''
})

const emailSender = computed(() => settingsStore.settings.emailSender || {})
const settingsBusy = computed(() =>
  settingsStore.loading ||
  settingsStore.saving ||
  settingsStore.importFieldSettingsLoading ||
  settingsStore.importFieldSettingsSaving ||
  settingsStore.recipientsLoading ||
  settingsStore.recipientSaving
)
const showEmailFields = computed(() =>
  activeTab.value === 'email' && (!emailSender.value.configured || emailEditing.value)
)
const canSubmitActiveTab = computed(() => showEmailFields.value)
const basicSettingsChanged = computed(() =>
  !amountMatchesSavedValue() ||
  !weekStartTimeMatchesSavedValue() ||
  !orderSourceOptionsMatchSavedValue() ||
  Boolean(normalizedText(orderSourceOptionInput.value))
)
const emailSenderChanged = computed(() => {
  const original = emailSenderFormDefaults()

  return normalizedText(emailForm.senderEmail) !== normalizedText(original.senderEmail) ||
    normalizedText(emailForm.smtpAuthCode) !== normalizedText(original.smtpAuthCode) ||
    normalizedText(emailForm.smtpHost) !== normalizedText(original.smtpHost) ||
    normalizedText(emailForm.smtpPort) !== normalizedText(original.smtpPort) ||
    normalizedText(emailForm.smtpSecurity) !== normalizedText(original.smtpSecurity)
})
const recipientCreateChanged = computed(() => Boolean(
  normalizedText(recipientForm.name) || normalizedText(recipientForm.email)
))
const activeSaving = computed(() => settingsStore.saving)
const submitButtonText = computed(() => {
  if (activeSaving.value) {
    return '保存中'
  }

  return '保存'
})

watch(
  () => props.initialTab,
  (tab) => {
    const nextTab = normalizeTab(tab)

    if (nextTab !== activeTab.value) {
      clearFormValidation()
    }

    activeTab.value = nextTab
  }
)

watch(
  () => props.open,
  async (open) => {
    if (!open) {
      return
    }

    activeTab.value = normalizeTab(props.initialTab)
    clearFieldError()
    clearSavedMessage()
    clearFormValidation()
    resetBasicSettingsForm()
    resetEmailForm()
    cancelRecipientCreate()
    cancelRecipientEdit()

    const [settingsResult, importFieldsResult, recipientsResult] = await Promise.allSettled([
      settingsStore.fetchSettings(),
      settingsStore.fetchImportFieldSettings(),
      settingsStore.fetchEmailRecipients()
    ])

    if (settingsResult.status === 'fulfilled') {
      resetBasicSettingsForm()
      resetEmailForm()
      emailEditing.value = !emailSender.value.configured
    }

    const failedResult = [settingsResult, importFieldsResult, recipientsResult]
      .find((result) => result.status === 'rejected')

    if (failedResult) {
      showFieldError(failedResult.reason?.message || '读取设置失败')
    }
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  clearFieldError()
  clearSavedMessage()
  clearFormValidation()
})

async function submit() {
  if (activeTab.value === 'basic') {
    await submitBasicSettings()
    return
  }

  if (showEmailFields.value) {
    await submitEmailSender()
    return
  }
}

async function submitBasicSettings() {
  if (settingsBusy.value || !basicSettingsChanged.value) {
    return
  }

  clearFieldError()
  clearSavedMessage()

  if (normalizedText(orderSourceOptionInput.value) && !addOrderSourceOption()) {
    return
  }

  const amount = validateAmount()
  const weekStartTime = validateWeekStartTime()
  const validatedOrderSourceOptions = validateOrderSourceOptions()

  if (amount === null || weekStartTime === null || validatedOrderSourceOptions === null) {
    return
  }

  try {
    await settingsStore.saveSettings({
      estimatedHourlyBaseAmount: amount,
      weekViewDefaultStartTime: weekStartTime,
      orderSourceOptions: validatedOrderSourceOptions
    })
    clearBasicSettingsValidation()
    resetBasicSettingsForm()
    showSavedMessage('设置已保存')
  } catch (error) {
    showFieldError(error.message)
  }
}

async function submitEmailSender() {
  if (settingsBusy.value || !emailSenderChanged.value) {
    return
  }

  clearFieldError()
  clearSavedMessage()

  const emailSenderSettings = validateEmailSender()

  if (!emailSenderSettings) {
    return
  }

  try {
    await settingsStore.saveEmailSenderSettings(emailSenderSettings)
    clearEmailSenderValidation()
    resetEmailForm()
    emailEditing.value = false
    showSavedMessage('设置已保存')
  } catch (error) {
    showFieldError(error.message)
  }
}

async function submitEmailRecipient() {
  if (!recipientCreating.value || settingsStore.recipientSaving || !recipientCreateChanged.value) {
    return
  }

  clearFieldError()
  clearSavedMessage()

  const recipient = validateEmailRecipient()

  if (!recipient) {
    return
  }

  try {
    await settingsStore.createEmailRecipient(recipient)
    cancelRecipientCreate()
    showSavedMessage('收件者已新增')
  } catch (error) {
    showFieldError(error.message)
  }
}

function validateAmount() {
  const value = String(amountInput.value).trim()
  let error = ''

  if (!value) {
    error = '不能为空。'
  } else if (!/^\d+(\.\d{1,2})?$/.test(value)) {
    error = '最多保留 2 位小数。'
  } else if (!Number.isFinite(Number(value)) || Number(value) <= 0) {
    error = '必须大于 0。'
  }

  if (error) {
    amountError.value = error
  }

  return error ? null : Number(value)
}

function validateWeekStartTime() {
  const value = String(weekStartTimeInput.value).trim()
  let error = ''

  if (!value) {
    error = '不能为空。'
  } else if (!/^([01]\d|2[0-3]):(00|30)$/.test(value)) {
    error = '请选择 30 分钟间隔的有效时间。'
  }

  if (error) {
    weekStartTimeError.value = error
  }

  return error ? null : value
}

function validateOrderSourceOptions() {
  const options = orderSourceOptions.value.map((option) => option.trim())
  let error = ''

  if (options.length === 0) {
    error = '请至少保留一个选项。'
  } else if (options.length > 20) {
    error = '最多添加 20 个选项。'
  } else if (options.some((option) => !option)) {
    error = '选项不可为空。'
  } else if (options.some((option) => option.length > 80)) {
    error = '每个选项最长为 80 个字符。'
  } else if (new Set(options.map((option) => option.toLocaleLowerCase('zh-CN'))).size !== options.length) {
    error = '选项不可重复。'
  }

  orderSourceOptionsError.value = error
  return error ? null : options
}

function addOrderSourceOption() {
  const option = normalizedText(orderSourceOptionInput.value)

  if (!option) {
    return true
  }
  if (option.length > 80) {
    orderSourceOptionsError.value = '每个选项最长为 80 个字符。'
    return false
  }
  if (orderSourceOptions.value.length >= 20) {
    orderSourceOptionsError.value = '最多添加 20 个选项。'
    return false
  }
  if (orderSourceOptions.value.some((current) => current.toLocaleLowerCase('zh-CN') === option.toLocaleLowerCase('zh-CN'))) {
    orderSourceOptionsError.value = '选项不可重复。'
    return false
  }

  orderSourceOptions.value.push(option)
  orderSourceOptionInput.value = ''
  orderSourceOptionsError.value = ''
  return true
}

function removeOrderSourceOption(option) {
  if (!window.confirm(`是否删除订单来源选项：${option}？`)) {
    return
  }

  orderSourceOptions.value = orderSourceOptions.value.filter((current) => current !== option)
  orderSourceOptionsError.value = orderSourceOptions.value.length ? '' : '请至少保留一个选项。'
}

function handleOrderSourceOptionKeydown(event) {
  if (event.key === 'Enter' || event.key === ',') {
    event.preventDefault()
    addOrderSourceOption()
  }
}

function validateEmailSender() {
  const senderEmail = emailForm.senderEmail.trim()
  const smtpAuthCode = emailForm.smtpAuthCode.trim()
  const retainStoredAuthCode = emailSender.value.configured && smtpAuthCode === STORED_SMTP_AUTH_CODE
  const smtpHost = emailForm.smtpHost.trim()
  const smtpPort = Number(emailForm.smtpPort)
  const smtpSecurity = emailForm.smtpSecurity
  const errors = {
    senderEmail: '',
    smtpAuthCode: '',
    smtpHost: '',
    smtpPort: '',
    smtpSecurity: ''
  }

  if (!senderEmail) {
    errors.senderEmail = '不能为空。'
  } else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(senderEmail)) {
    errors.senderEmail = '格式无效。'
  }

  if (!smtpAuthCode) {
    errors.smtpAuthCode = '不能为空。'
  }

  if (!smtpHost) {
    errors.smtpHost = '不能为空。'
  }

  if (![465, 587].includes(smtpPort)) {
    errors.smtpPort = '必须为 465 或 587。'
  }

  if (!['NONE', 'SSL', 'STARTTLS'].includes(smtpSecurity)) {
    errors.smtpSecurity = '不能为空。'
  }

  applyValidationErrors(emailSenderErrors, errors)

  if (Object.values(errors).some(Boolean)) {
    return null
  }

  return {
    senderEmail,
    smtpAuthCode: retainStoredAuthCode ? null : smtpAuthCode,
    smtpHost,
    smtpPort,
    smtpSecurity
  }
}

function validateEmailRecipient() {
  const name = recipientForm.name.trim()
  const email = recipientForm.email.trim()
  const errors = {
    name: '',
    email: ''
  }

  if (!name) {
    errors.name = '不能为空。'
  } else if (name.length > 120) {
    errors.name = '最多 120 个字符。'
  }

  if (!email) {
    errors.email = '不能为空。'
  } else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    errors.email = '格式无效。'
  }

  applyValidationErrors(recipientErrors, errors)

  if (Object.values(errors).some(Boolean)) {
    return null
  }

  return { name, email }
}

function activateTab(tab) {
  const nextTab = normalizeTab(tab)

  if (nextTab !== activeTab.value) {
    clearFormValidation()
  }

  activeTab.value = nextTab
  clearFieldError()
  clearSavedMessage()
  cancelRecipientCreate()
  cancelRecipientEdit()
  emit('update-tab', nextTab)
}

async function startRecipientCreate() {
  if (recipientCreating.value || recipientEditingId.value !== null) {
    return
  }

  recipientCreating.value = true
  resetRecipientForm()
  clearRecipientValidation()
  clearFieldError()
  clearSavedMessage()
  await nextTick()
  recipientCreateNameInput.value?.focus()
}

function cancelRecipientCreate() {
  recipientCreating.value = false
  resetRecipientForm()
  clearRecipientValidation()
  clearFieldError()
  clearSavedMessage()
}

async function startRecipientEdit(recipient) {
  recipientEditingId.value = recipient.id
  recipientNameDraft.value = recipient.name || ''
  recipientEmailDraft.value = recipient.email
  recipientEditActionError.value = ''
  clearRecipientEditValidation()
  clearFieldError()
  clearSavedMessage()
  await nextTick()
  recipientNameInput.value?.focus()
}

function setRecipientNameInput(element) {
  recipientNameInput.value = element
}

async function saveRecipient(recipient) {
  if (settingsBusy.value || !recipientHasChanges(recipient)) {
    return
  }

  const name = recipientNameDraft.value.trim()
  const email = recipientEmailDraft.value.trim()
  const errors = {
    name: '',
    email: ''
  }

  if (!name) {
    errors.name = '不能为空。'
  } else if (name.length > 120) {
    errors.name = '最多 120 个字符。'
  }

  if (!email) {
    errors.email = '不能为空。'
  } else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    errors.email = '格式无效。'
  }

  applyValidationErrors(recipientEditErrors, errors)

  if (Object.values(errors).some(Boolean)) {
    return
  }

  recipientEditActionError.value = ''
  clearFieldError()
  clearSavedMessage()

  try {
    await settingsStore.updateEmailRecipient(recipient.id, {
      name,
      email
    })
    cancelRecipientEdit()
    showSavedMessage('收件者已更新')
  } catch (error) {
    recipientEditActionError.value = error.message
  }
}

function cancelRecipientEdit() {
  recipientEditingId.value = null
  recipientNameDraft.value = ''
  recipientEmailDraft.value = ''
  recipientEditActionError.value = ''
  clearRecipientEditValidation()
}

async function removeRecipient(recipient) {
  if (!window.confirm(`确定删除 ${recipient.name || recipient.email}？`)) {
    return
  }

  recipientDeletingId.value = recipient.id
  clearFieldError()
  clearSavedMessage()

  try {
    await settingsStore.deleteEmailRecipient(recipient.id)

    if (recipientEditingId.value === recipient.id) {
      cancelRecipientEdit()
    }

    showSavedMessage('收件者已删除')
  } catch (error) {
    showFieldError(error.message)
  } finally {
    recipientDeletingId.value = null
  }
}

function startEmailEdit() {
  resetEmailForm()
  clearEmailSenderValidation()
  emailEditing.value = true
}

function cancelEmailEdit() {
  resetEmailForm()
  clearEmailSenderValidation()
  clearFieldError()
  clearSavedMessage()
  emailEditing.value = false
}

function resetEmailForm() {
  const defaults = emailSenderFormDefaults()

  smtpAuthCodeVisible.value = false
  Object.assign(emailForm, defaults)
}

function emailSenderFormDefaults() {
  return {
    senderEmail: emailSender.value.senderEmail || '',
    smtpAuthCode: emailSender.value.configured ? STORED_SMTP_AUTH_CODE : '',
    smtpHost: emailSender.value.smtpHost || '',
    smtpPort: emailSender.value.smtpPort ? String(emailSender.value.smtpPort) : '465',
    smtpSecurity: emailSender.value.smtpSecurity || 'SSL'
  }
}

function amountMatchesSavedValue() {
  const current = normalizedText(amountInput.value)
  const saved = normalizedText(formatAmount(settingsStore.settings.estimatedHourlyBaseAmount))

  if (current === saved) {
    return true
  }

  return /^\d+(\.\d{1,2})?$/.test(current) && Number(current) === Number(saved)
}

function weekStartTimeMatchesSavedValue() {
  return normalizedText(weekStartTimeInput.value) === normalizedText(
    formatWeekStartTime(settingsStore.settings.weekViewDefaultStartTime)
  )
}

function orderSourceOptionsMatchSavedValue() {
  const savedOptions = settingsStore.settings.orderSourceOptions || []

  return orderSourceOptions.value.length === savedOptions.length &&
    orderSourceOptions.value.every((option, index) => option === savedOptions[index])
}

function resetBasicSettingsForm() {
  amountInput.value = formatAmount(settingsStore.settings.estimatedHourlyBaseAmount)
  weekStartTimeInput.value = formatWeekStartTime(settingsStore.settings.weekViewDefaultStartTime)
  orderSourceOptions.value = [...(settingsStore.settings.orderSourceOptions || ['千牛', '小红书'])]
  orderSourceOptionInput.value = ''
}

function recipientHasChanges(recipient) {
  return normalizedText(recipientNameDraft.value) !== normalizedText(recipient.name) ||
    normalizedText(recipientEmailDraft.value) !== normalizedText(recipient.email)
}

function normalizedText(value) {
  return String(value ?? '').trim()
}

function resetRecipientForm() {
  recipientForm.name = ''
  recipientForm.email = ''
}

function clearRecipientValidation() {
  clearValidationErrors(recipientErrors)
}

function clearEmailSenderValidation() {
  clearValidationErrors(emailSenderErrors)
}

function clearRecipientEditValidation() {
  clearValidationErrors(recipientEditErrors)
}

function clearFormValidation() {
  clearBasicSettingsValidation()
  clearEmailSenderValidation()
  clearRecipientValidation()
  clearRecipientEditValidation()
}

function clearBasicSettingsValidation() {
  amountError.value = ''
  weekStartTimeError.value = ''
  orderSourceOptionsError.value = ''
}

function clearValidationErrors(errors) {
  Object.keys(errors).forEach((key) => {
    errors[key] = ''
  })
}

function applyValidationErrors(target, errors) {
  Object.entries(errors).forEach(([key, message]) => {
    if (message) {
      target[key] = message
    }
  })
}

function showFieldError(message) {
  fieldError.value = message

  if (fieldErrorTimer) {
    window.clearTimeout(fieldErrorTimer)
  }

  fieldErrorTimer = window.setTimeout(() => {
    fieldError.value = ''
    fieldErrorTimer = null
  }, 5000)
}

function clearFieldError() {
  fieldError.value = ''

  if (fieldErrorTimer) {
    window.clearTimeout(fieldErrorTimer)
    fieldErrorTimer = null
  }
}

function showSavedMessage(message) {
  savedMessage.value = message

  if (savedMessageTimer) {
    window.clearTimeout(savedMessageTimer)
  }

  savedMessageTimer = window.setTimeout(() => {
    savedMessage.value = ''
    savedMessageTimer = null
  }, 5000)
}

function clearSavedMessage() {
  savedMessage.value = ''

  if (savedMessageTimer) {
    window.clearTimeout(savedMessageTimer)
    savedMessageTimer = null
  }
}

function formatAmount(value) {
  if (value === null || value === undefined || value === '') {
    return '100'
  }

  return String(value)
}

function formatWeekStartTime(value) {
  return /^([01]\d|2[0-3]):[0-5]\d$/.test(String(value || '')) ? value : '06:00'
}

function normalizeTab(tab) {
  if (tab === 'baseAmount') {
    return 'basic'
  }

  return ['email', 'fields', 'recipients'].includes(tab) ? tab : 'basic'
}

function recipientMeta(recipient) {
  if (!recipient.lastUsedAt) {
    return '尚未发送'
  }

  return `已发送 ${recipient.usageCount} 次 · 最近 ${formatDateTime(recipient.lastUsedAt)}`
}

function formatDateTime(value) {
  return value ? value.replace('T', ' ').slice(0, 16) : ''
}

function securityLabel(value) {
  if (value === 'SSL') {
    return 'SSL / TLS'
  }

  if (value === 'NONE') {
    return '无'
  }

  return 'STARTTLS'
}
</script>

<template>
  <div
    v-if="open"
    class="dialog-backdrop settings-dialog-backdrop"
    role="presentation"
    @click="emit('close')"
  >
    <form class="dialog settings-dialog" aria-label="全局设置" novalidate @click.stop @submit.prevent="submit">
      <div class="dialog-heading">
        <h2>全局设置</h2>
        <button class="icon-only-button" type="button" aria-label="关闭" @click="emit('close')">
          <X :size="18" />
        </button>
      </div>

      <div class="settings-tabs" role="tablist" aria-label="全局设置分类">
        <button
          type="button"
          role="tab"
          :aria-selected="activeTab === 'recipients'"
          :class="{ active: activeTab === 'recipients' }"
          @click="activateTab('recipients')"
        >
          Email 收件者
        </button>
        <button
          type="button"
          role="tab"
          :aria-selected="activeTab === 'email'"
          :class="{ active: activeTab === 'email' }"
          @click="activateTab('email')"
        >
          Email 寄件者
        </button>
        <button
          type="button"
          role="tab"
          :aria-selected="activeTab === 'basic'"
          :class="{ active: activeTab === 'basic' }"
          @click="activateTab('basic')"
        >
          基础设置
        </button>
        <button
          type="button"
          role="tab"
          :aria-selected="activeTab === 'fields'"
          :class="{ active: activeTab === 'fields' }"
          @click="activateTab('fields')"
        >
          字段识别设置
        </button>
      </div>

      <section v-show="activeTab === 'basic'" class="settings-panel" role="tabpanel">
        <div class="basic-settings-form">
          <div class="basic-settings-grid">
            <label>
              <span class="form-field-label">
                预估工时基础金额（元/小时）
                <small v-if="amountError" id="base-amount-error" class="form-field-error" role="alert">
                  {{ amountError }}
                </small>
              </span>
              <input
                v-model="amountInput"
                inputmode="decimal"
                type="number"
                min="0.01"
                step="0.01"
                required
                :aria-describedby="amountError ? 'base-amount-error' : undefined"
                :aria-invalid="Boolean(amountError)"
                :disabled="settingsBusy"
                @keydown.enter.prevent="submitBasicSettings"
              />
            </label>

            <label>
              <span class="form-field-label">
                周表默认开始时间
                <HelpTooltip aria-label="查看周表默认开始时间说明">
                  <p>当前周没有已排工单时，从此时间开始显示；有工单时会自动显示该周最早的工单时间。</p>
                </HelpTooltip>
                <small
                  v-if="weekStartTimeError"
                  id="week-start-time-error"
                  class="form-field-error"
                  role="alert"
                >
                  {{ weekStartTimeError }}
                </small>
              </span>
              <input
                v-model="weekStartTimeInput"
                type="time"
                step="1800"
                required
                :aria-describedby="weekStartTimeError ? 'week-start-time-error' : undefined"
                :aria-invalid="Boolean(weekStartTimeError)"
                :disabled="settingsBusy"
                @keydown.enter.prevent="submitBasicSettings"
              />
            </label>

            <div class="basic-settings-wide-field">
              <label class="form-field-label" for="order-source-option-input">
                订单来源选项
                <HelpTooltip aria-label="查看订单来源选项说明">
                  <p>用于手动新增待排工单时选择订单来源；输入后按 Enter 或逗号添加。</p>
                </HelpTooltip>
                <small
                  v-if="orderSourceOptionsError"
                  id="order-source-options-error"
                  class="form-field-error"
                  role="alert"
                >
                  {{ orderSourceOptionsError }}
                </small>
              </label>
              <div
                class="recipient-tag-input order-source-tag-input"
                :class="{ invalid: orderSourceOptionsError }"
                @click="orderSourceOptionInputElement?.focus()"
              >
                <span v-for="option in orderSourceOptions" :key="option" class="recipient-tag">
                  <span>{{ option }}</span>
                  <button
                    type="button"
                    :aria-label="`删除订单来源选项 ${option}`"
                    :disabled="settingsBusy"
                    @click.stop="removeOrderSourceOption(option)"
                  >
                    <X :size="13" />
                  </button>
                </span>
                <input
                  id="order-source-option-input"
                  ref="orderSourceOptionInputElement"
                  v-model="orderSourceOptionInput"
                  class="recipient-tag-search"
                  type="text"
                  maxlength="80"
                  :aria-describedby="orderSourceOptionsError ? 'order-source-options-error' : undefined"
                  :aria-invalid="Boolean(orderSourceOptionsError)"
                  :disabled="settingsBusy"
                  :placeholder="orderSourceOptions.length ? '继续添加' : '输入订单来源'"
                  @keydown="handleOrderSourceOptionKeydown"
                />
              </div>
            </div>
          </div>

          <div class="dialog-actions">
            <span v-if="savedMessage" class="dialog-status" role="status">
              {{ savedMessage }}
            </span>
            <button
              class="icon-button primary-action"
              type="submit"
              :disabled="settingsBusy || !basicSettingsChanged"
            >
              <span v-if="activeSaving" class="loading-spinner" aria-hidden="true"></span>
              <Save v-else :size="18" />
              {{ submitButtonText }}
            </button>
          </div>
        </div>
      </section>

      <section v-show="activeTab === 'email'" class="settings-panel" role="tabpanel">
        <div v-if="emailSender.configured && !emailEditing" class="email-sender-summary">
          <div class="email-sender-details">
            <strong>{{ emailSender.senderEmailMasked || '已配置' }}</strong>
            <span v-if="emailSender.smtpHost" class="settings-meta">
              {{ emailSender.smtpHost }}:{{ emailSender.smtpPort }} · {{ securityLabel(emailSender.smtpSecurity) }}
            </span>
          </div>
          <button class="text-button" type="button" @click="startEmailEdit">
            更换寄件者
          </button>
        </div>

        <div v-else class="email-settings-grid">
          <label>
            <span class="form-field-label">
              寄件 Email
              <small
                v-if="emailSenderErrors.senderEmail"
                id="sender-email-error"
                class="form-field-error"
                role="alert"
              >
                {{ emailSenderErrors.senderEmail }}
              </small>
            </span>
            <input
              v-model="emailForm.senderEmail"
              type="email"
              autocomplete="username"
              placeholder="sender@example.com"
              required
              :aria-describedby="emailSenderErrors.senderEmail ? 'sender-email-error' : undefined"
              :aria-invalid="Boolean(emailSenderErrors.senderEmail)"
              :disabled="settingsBusy"
              @keydown.enter.prevent="submitEmailSender"
            />
          </label>

          <div class="dialog-field">
            <label for="smtp-auth-code" class="form-field-label">
              授权码
              <small
                v-if="emailSenderErrors.smtpAuthCode"
                id="smtp-auth-code-error"
                class="form-field-error"
                role="alert"
              >
                {{ emailSenderErrors.smtpAuthCode }}
              </small>
            </label>
            <div class="password-input">
              <input
                id="smtp-auth-code"
                v-model="emailForm.smtpAuthCode"
                :type="smtpAuthCodeVisible ? 'text' : 'password'"
                autocomplete="current-password"
                required
                :aria-describedby="emailSenderErrors.smtpAuthCode ? 'smtp-auth-code-error' : undefined"
                :aria-invalid="Boolean(emailSenderErrors.smtpAuthCode)"
                :disabled="settingsBusy"
                @keydown.enter.prevent="submitEmailSender"
              />
              <button
                class="icon-only-button password-visibility-button"
                type="button"
                :aria-label="smtpAuthCodeVisible ? '隐藏授权码' : '显示授权码'"
                :aria-pressed="smtpAuthCodeVisible"
                :disabled="settingsBusy"
                @click="smtpAuthCodeVisible = !smtpAuthCodeVisible"
              >
                <EyeOff v-if="smtpAuthCodeVisible" :size="18" />
                <Eye v-else :size="18" />
              </button>
            </div>
          </div>

          <label>
            <span class="form-field-label">
              SMTP 服务器
              <small
                v-if="emailSenderErrors.smtpHost"
                id="smtp-host-error"
                class="form-field-error"
                role="alert"
              >
                {{ emailSenderErrors.smtpHost }}
              </small>
            </span>
            <input
              v-model="emailForm.smtpHost"
              type="text"
              placeholder="smtp.example.com"
              required
              :aria-describedby="emailSenderErrors.smtpHost ? 'smtp-host-error' : undefined"
              :aria-invalid="Boolean(emailSenderErrors.smtpHost)"
              :disabled="settingsBusy"
              @keydown.enter.prevent="submitEmailSender"
            />
          </label>

          <div class="date-fields">
            <label>
              <span class="form-field-label">
                SMTP 端口
                <small
                  v-if="emailSenderErrors.smtpPort"
                  id="smtp-port-error"
                  class="form-field-error"
                  role="alert"
                >
                  {{ emailSenderErrors.smtpPort }}
                </small>
              </span>
              <select
                v-model="emailForm.smtpPort"
                required
                :aria-describedby="emailSenderErrors.smtpPort ? 'smtp-port-error' : undefined"
                :aria-invalid="Boolean(emailSenderErrors.smtpPort)"
                :disabled="settingsBusy"
              >
                <option value="465">465</option>
                <option value="587">587</option>
              </select>
            </label>
            <label>
              <span class="form-field-label">
                加密方式
                <small
                  v-if="emailSenderErrors.smtpSecurity"
                  id="smtp-security-error"
                  class="form-field-error"
                  role="alert"
                >
                  {{ emailSenderErrors.smtpSecurity }}
                </small>
              </span>
              <select
                v-model="emailForm.smtpSecurity"
                required
                :aria-describedby="emailSenderErrors.smtpSecurity ? 'smtp-security-error' : undefined"
                :aria-invalid="Boolean(emailSenderErrors.smtpSecurity)"
                :disabled="settingsBusy"
              >
                <option value="STARTTLS">STARTTLS</option>
                <option value="SSL">SSL / TLS</option>
                <option value="NONE">无</option>
              </select>
            </label>
          </div>
        </div>
      </section>

      <section v-show="activeTab === 'fields'" class="settings-panel field-settings-panel" role="tabpanel">
        <ImportFieldSettingsPanel :active="activeTab === 'fields'" />
      </section>

      <section v-show="activeTab === 'recipients'" class="settings-panel recipient-settings-panel" role="tabpanel">
        <div class="recipient-list-heading">
          <div>
            <h3>常用与寄送过的收件者</h3>
            <p>成功寄送的新 Email 会自动加入此列表。</p>
          </div>
          <div class="dialog-actions">
            <span v-if="savedMessage" class="dialog-status" role="status">
              {{ savedMessage }}
            </span>
            <button
              class="icon-button primary-action"
              type="button"
              :disabled="settingsBusy || recipientCreating || recipientEditingId !== null"
              @click="startRecipientCreate"
            >
              <Plus :size="18" />
              新增
            </button>
          </div>
        </div>

        <p v-if="settingsStore.recipientsLoading" class="recipient-empty">载入中...</p>
        <p
          v-else-if="!recipientCreating && settingsStore.emailRecipients.length === 0"
          class="recipient-empty"
        >
          尚无收件者
        </p>
        <div v-else class="email-recipient-list">
          <article v-if="recipientCreating" class="email-recipient-item">
            <div class="email-recipient-details">
              <div class="email-recipient-fields">
                <label>
                  <span class="form-field-label">
                    收件人姓名
                    <small
                      v-if="recipientErrors.name"
                      id="recipient-name-error"
                      class="form-field-error"
                      role="alert"
                    >
                      {{ recipientErrors.name }}
                    </small>
                  </span>
                  <input
                    ref="recipientCreateNameInput"
                    v-model="recipientForm.name"
                    class="email-recipient-name-input"
                    type="text"
                    maxlength="120"
                    autocomplete="name"
                    placeholder="例如：咩咩"
                    required
                    :aria-describedby="recipientErrors.name ? 'recipient-name-error' : undefined"
                    :aria-invalid="Boolean(recipientErrors.name)"
                    :disabled="settingsBusy"
                    @keydown.enter.prevent="submitEmailRecipient"
                    @keydown.esc.prevent="cancelRecipientCreate"
                  />
                </label>
                <label>
                  <span class="form-field-label">
                    收件 Email
                    <small
                      v-if="recipientErrors.email"
                      id="recipient-email-error"
                      class="form-field-error"
                      role="alert"
                    >
                      {{ recipientErrors.email }}
                    </small>
                  </span>
                  <input
                    v-model="recipientForm.email"
                    type="email"
                    maxlength="320"
                    autocomplete="email"
                    placeholder="miemie@example.com"
                    required
                    :aria-describedby="recipientErrors.email ? 'recipient-email-error' : undefined"
                    :aria-invalid="Boolean(recipientErrors.email)"
                    :disabled="settingsBusy"
                    @keydown.enter.prevent="submitEmailRecipient"
                    @keydown.esc.prevent="cancelRecipientCreate"
                  />
                </label>
              </div>
              <small>尚未保存</small>
            </div>
            <div class="email-recipient-actions">
              <button
                class="icon-only-button"
                type="button"
                aria-label="取消新增收件者"
                :disabled="settingsBusy"
                @click="cancelRecipientCreate"
              >
                <X :size="16" />
              </button>
              <button
                class="icon-only-button confirm-action"
                type="button"
                aria-label="保存新增收件者"
                :disabled="settingsBusy || !recipientCreateChanged"
                @click="submitEmailRecipient"
              >
                <span
                  v-if="settingsStore.recipientSaving"
                  class="loading-spinner"
                  aria-hidden="true"
                ></span>
                <Check v-else :size="16" />
              </button>
            </div>
          </article>
          <article
            v-for="recipient in settingsStore.emailRecipients"
            :key="recipient.id"
            class="email-recipient-item"
          >
            <div class="email-recipient-details">
              <div class="email-recipient-fields">
                <label v-if="recipientEditingId === recipient.id">
                  <span class="form-field-label">
                    收件人姓名
                    <small
                      v-if="recipientEditErrors.name"
                      :id="`recipient-edit-name-error-${recipient.id}`"
                      class="form-field-error"
                      role="alert"
                    >
                      {{ recipientEditErrors.name }}
                    </small>
                  </span>
                  <input
                    :ref="setRecipientNameInput"
                    v-model="recipientNameDraft"
                    class="email-recipient-name-input"
                    type="text"
                    maxlength="120"
                    autocomplete="name"
                    aria-label="编辑收件人姓名"
                    placeholder="收件人姓名"
                    required
                    autofocus
                    :aria-describedby="recipientEditErrors.name ? `recipient-edit-name-error-${recipient.id}` : undefined"
                    :aria-invalid="Boolean(recipientEditErrors.name)"
                    :disabled="settingsBusy"
                    @keydown.enter.prevent="saveRecipient(recipient)"
                    @keydown.esc.prevent="cancelRecipientEdit"
                  />
                </label>
                <strong v-else class="email-recipient-field-display">
                  {{ recipient.name || '未设置姓名' }}
                </strong>
                <label v-if="recipientEditingId === recipient.id">
                  <span class="form-field-label">
                    收件 Email
                    <small
                      v-if="recipientEditErrors.email"
                      :id="`recipient-edit-email-error-${recipient.id}`"
                      class="form-field-error"
                      role="alert"
                    >
                      {{ recipientEditErrors.email }}
                    </small>
                  </span>
                  <input
                    v-model="recipientEmailDraft"
                    type="email"
                    maxlength="320"
                    autocomplete="email"
                    aria-label="编辑收件 Email"
                    placeholder="收件 Email"
                    required
                    :aria-describedby="recipientEditErrors.email ? `recipient-edit-email-error-${recipient.id}` : undefined"
                    :aria-invalid="Boolean(recipientEditErrors.email)"
                    :disabled="settingsBusy"
                    @keydown.enter.prevent="saveRecipient(recipient)"
                    @keydown.esc.prevent="cancelRecipientEdit"
                  />
                </label>
                <span v-else class="email-recipient-field-display">{{ recipient.email }}</span>
              </div>
              <small>{{ recipientMeta(recipient) }}</small>
              <small
                v-if="recipientEditingId === recipient.id && recipientEditActionError"
                class="recipient-edit-error"
                role="alert"
              >
                {{ recipientEditActionError }}
              </small>
            </div>
            <div class="email-recipient-actions">
              <button
                v-if="recipientEditingId === recipient.id"
                class="icon-only-button"
                type="button"
                :aria-label="`取消编辑 ${recipient.email}`"
                :disabled="settingsBusy"
                @click="cancelRecipientEdit"
              >
                <X :size="16" />
              </button>
              <button
                v-if="recipientEditingId === recipient.id"
                class="icon-only-button confirm-action"
                type="button"
                :aria-label="`确认编辑 ${recipient.email}`"
                :disabled="settingsBusy || !recipientHasChanges(recipient)"
                @click="saveRecipient(recipient)"
              >
                <span
                  v-if="settingsStore.recipientSaving"
                  class="loading-spinner"
                  aria-hidden="true"
                ></span>
                <Check v-else :size="16" />
              </button>
              <button
                v-if="recipientEditingId !== recipient.id"
                class="icon-only-button"
                type="button"
                :aria-label="`编辑 ${recipient.name || recipient.email}`"
                :disabled="settingsBusy || recipientCreating"
                @click="startRecipientEdit(recipient)"
              >
                <Pencil :size="16" />
              </button>
              <button
                v-if="recipientEditingId !== recipient.id"
                class="icon-only-button danger-action"
                type="button"
                :aria-label="`删除 ${recipient.name || recipient.email}`"
                :disabled="settingsBusy || recipientCreating"
                @click="removeRecipient(recipient)"
              >
                <span
                  v-if="recipientDeletingId === recipient.id"
                  class="loading-spinner"
                  aria-hidden="true"
                ></span>
                <Trash2 v-else :size="16" />
              </button>
            </div>
          </article>
        </div>
      </section>

      <p v-if="fieldError" class="dialog-error" role="alert">{{ fieldError }}</p>

      <div
        v-if="(savedMessage && activeTab === 'email') || canSubmitActiveTab"
        class="dialog-actions"
      >
        <span v-if="savedMessage && activeTab === 'email'" class="dialog-status" role="status">
          {{ savedMessage }}
        </span>
        <button
          v-if="activeTab === 'email' && emailSender.configured && emailEditing"
          class="text-button"
          type="button"
          :disabled="settingsBusy"
          @click="cancelEmailEdit"
        >
          取消
        </button>
        <button
          v-if="canSubmitActiveTab"
          class="icon-button primary-action"
          type="submit"
          :disabled="settingsBusy || !emailSenderChanged"
        >
          <span v-if="activeSaving" class="loading-spinner" aria-hidden="true"></span>
          <Save v-else :size="18" />
          {{ submitButtonText }}
        </button>
      </div>
    </form>
  </div>
</template>
