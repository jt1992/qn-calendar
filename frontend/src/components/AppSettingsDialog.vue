<script setup>
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { Check, Eye, EyeOff, Pencil, Plus, Save, Trash2, X } from '@lucide/vue'
import HelpTooltip from './HelpTooltip.vue'
import ImportFieldSettingsPanel from './ImportFieldSettingsPanel.vue'
import { useAppSettingsStore } from '../stores/appSettingsStore'
import { useWorkOrderStore } from '../stores/workOrderStore'

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
const workOrderStore = useWorkOrderStore()
const activeTab = ref(normalizeTab(props.initialTab))
const amountInput = ref('')
const amountError = ref('')
const weekStartTimeInput = ref('')
const weekStartTimeError = ref('')
const orderSourceOptions = ref([])
const orderSourceOptionInput = ref('')
const orderSourceOptionsError = ref('')
const orderSourceOptionInputElement = ref(null)
const selectedOrderSourceIndex = ref(-1)
const settingsTabsScrolling = ref(false)
const orderSourceDeletingIdentifier = ref('')
const orderSourceEditorErrors = reactive({
  name: '',
  badgeColor: '',
  badgeText: ''
})
const basicFieldMessages = reactive({
  amount: '',
  weekStartTime: '',
  orderSources: ''
})
const basicFieldMessageTones = reactive({
  amount: 'info',
  weekStartTime: 'info',
  orderSources: 'info'
})
const fieldError = ref('')
const savedMessage = ref('')
const savedMessageTone = ref('info')
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
let settingsTabsScrollTimer = null
const basicFieldMessageTimers = {}

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
const selectedOrderSourceOption = computed(() => orderSourceOptions.value[selectedOrderSourceIndex.value] || null)
const settingsBusy = computed(() =>
  settingsStore.loading ||
  settingsStore.saving ||
  settingsStore.sourceDeleting ||
  Boolean(orderSourceDeletingIdentifier.value) ||
  settingsStore.importFieldSettingsLoading ||
  settingsStore.importFieldSettingsSaving ||
  settingsStore.recipientsLoading ||
  settingsStore.recipientSaving
)
const showEmailFields = computed(() =>
  activeTab.value === 'email' && (!emailSender.value.configured || emailEditing.value)
)
const canSubmitActiveTab = computed(() => showEmailFields.value)
const selectedOrderSourceChanged = computed(() => {
  const option = selectedOrderSourceOption.value

  if (!option) {
    return false
  }

  const savedOption = (settingsStore.settings.orderSourceOptions || []).find(
    (current) => current.identifier === option._persistedIdentifier
  )

  return !savedOption || JSON.stringify(comparableOrderSourceOption(option)) !== JSON.stringify(savedOption)
})
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
    clearBasicFieldMessages()
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
  clearBasicFieldMessages()
  clearFormValidation()
  if (settingsTabsScrollTimer) {
    window.clearTimeout(settingsTabsScrollTimer)
  }
})

async function submit() {
  if (showEmailFields.value) {
    await submitEmailSender()
  }
}

async function autoSaveAmount() {
  if (settingsBusy.value || amountMatchesSavedValue()) {
    return
  }

  clearFieldError()
  amountError.value = ''
  const amount = validateAmount()

  if (amount === null) {
    return
  }

  try {
    await settingsStore.saveSettings({
      estimatedHourlyBaseAmount: amount,
      weekViewDefaultStartTime: settingsStore.settings.weekViewDefaultStartTime,
      orderSourceOptions: settingsStore.settings.orderSourceOptions
    })
    amountInput.value = formatAmount(settingsStore.settings.estimatedHourlyBaseAmount)
    showBasicFieldMessage('amount', '已保存')
  } catch (error) {
    showFieldError(error.message)
  }
}

async function autoSaveWeekStartTime() {
  if (settingsBusy.value || weekStartTimeMatchesSavedValue()) {
    return
  }

  clearFieldError()
  weekStartTimeError.value = ''
  const weekStartTime = validateWeekStartTime()

  if (weekStartTime === null) {
    return
  }

  try {
    await settingsStore.saveSettings({
      estimatedHourlyBaseAmount: settingsStore.settings.estimatedHourlyBaseAmount,
      weekViewDefaultStartTime: weekStartTime,
      orderSourceOptions: settingsStore.settings.orderSourceOptions
    })
    weekStartTimeInput.value = formatWeekStartTime(settingsStore.settings.weekViewDefaultStartTime)
    showBasicFieldMessage('weekStartTime', '已保存')
  } catch (error) {
    showFieldError(error.message)
  }
}

async function saveOrderSourceOptions() {
  if (settingsBusy.value || !selectedOrderSourceChanged.value) {
    return
  }

  clearFieldError()
  const validatedOrderSourceOptions = validateOrderSourceOptions()

  if (validatedOrderSourceOptions === null) {
    return
  }

  try {
    await settingsStore.saveSettings({
      estimatedHourlyBaseAmount: settingsStore.settings.estimatedHourlyBaseAmount,
      weekViewDefaultStartTime: settingsStore.settings.weekViewDefaultStartTime,
      orderSourceOptions: validatedOrderSourceOptions
    })
    await Promise.all([
      workOrderStore.fetchPendingWorkOrders(),
      workOrderStore.refreshCalendarEvents(),
      workOrderStore.fetchCompletedStats()
    ])
    resetOrderSourceOptionsForm()
    showBasicFieldMessage('orderSources', '已保存')
  } catch (error) {
    showFieldError(error.message)
  }
}

async function refreshWorkOrdersAfterRemarkTagSave() {
  try {
    await Promise.all([
      workOrderStore.fetchPendingWorkOrders(),
      workOrderStore.refreshCalendarEvents()
    ])
  } catch (error) {
    showFieldError(`备注标签已保存，但工单列表刷新失败：${error.message}`)
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
  const options = orderSourceOptions.value.map((option) => ({
    name: normalizedText(option.name),
    identifier: normalizedText(option.identifier) || null,
    badgeColor: normalizeHexColor(option.badgeColor),
    badgeText: normalizedText(option.badgeText)
  }))
  let error = ''
  let invalidIndex = -1

  if (options.length === 0) {
    error = '请至少保留一个选项。'
  } else if (options.length > 20) {
    error = '最多添加 20 个选项。'
  } else if ((invalidIndex = options.findIndex((option) => !option.name)) >= 0) {
    error = '选项不可为空。'
  } else if ((invalidIndex = options.findIndex((option) => option.name.length > 80)) >= 0) {
    error = '每个选项最长为 80 个字符。'
  } else if (new Set(options.map((option) => option.name.toLocaleLowerCase('zh-CN'))).size !== options.length) {
    error = '来源名称不可重复。'
    invalidIndex = options.findIndex((option, index) =>
      options.findIndex(
        (current) => current.name.toLocaleLowerCase('zh-CN') === option.name.toLocaleLowerCase('zh-CN')
      ) !== index
    )
  } else {
    invalidIndex = options.findIndex((option) => !/^#[0-9A-F]{6}$/.test(option.badgeColor))
    if (invalidIndex >= 0) {
      error = '标签颜色须填写六位十六进制色码。'
    } else {
      invalidIndex = options.findIndex((option) => !/^(?:\p{Script=Han}|[A-Za-z])$/u.test(option.badgeText))
      if (invalidIndex >= 0) {
        error = '标签单一文字只能输入一个中文字符或英文字母。'
      }
    }
  }

  clearOrderSourceEditorErrors()
  if (invalidIndex >= 0) {
    selectedOrderSourceIndex.value = invalidIndex
    if (!options[invalidIndex].name
        || options[invalidIndex].name.length > 80
        || options.filter(
          (option) => option.name.toLocaleLowerCase('zh-CN') ===
            options[invalidIndex].name.toLocaleLowerCase('zh-CN')
        ).length > 1) {
      orderSourceEditorErrors.name = error
    } else if (!/^#[0-9A-F]{6}$/.test(options[invalidIndex].badgeColor)) {
      orderSourceEditorErrors.badgeColor = error
    } else {
      orderSourceEditorErrors.badgeText = error
    }
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
  if (orderSourceOptions.value.some((current) => current.name.toLocaleLowerCase('zh-CN') === option.toLocaleLowerCase('zh-CN'))) {
    orderSourceOptionsError.value = '来源名称不可重复。'
    return false
  }

  orderSourceOptions.value.push({
    name: option,
    identifier: null,
    badgeColor: '#3B82F6',
    badgeText: Array.from(option).find((character) => /^(?:\p{Script=Han}|[A-Za-z])$/u.test(character)) || ''
  })
  selectedOrderSourceIndex.value = orderSourceOptions.value.length - 1
  orderSourceOptionInput.value = ''
  orderSourceOptionsError.value = ''
  clearOrderSourceEditorErrors()
  return true
}

async function removeOrderSourceOption(option, index) {
  if (settingsBusy.value) {
    return
  }

  if (orderSourceOptions.value.length <= 1) {
    orderSourceOptionsError.value = '请至少保留一个选项。'
    return
  }

  clearFieldError()
  clearSavedMessage()
  const persistedIdentifier = option._persistedIdentifier

  if (!persistedIdentifier) {
    if (window.confirm(`是否删除尚未保存的订单来源「${option.name}」？`)) {
      removeLocalOrderSourceOption(index)
    }
    return
  }

  orderSourceDeletingIdentifier.value = persistedIdentifier

  try {
    const impact = await settingsStore.getOrderSourceDeletionImpact(persistedIdentifier)
    const confirmation = impact.workOrderCount > 0
      ? `订单来源「${impact.name}」目前已有 ${impact.workOrderCount} 笔工单。删除后，这些工单及其排程记录会永久删除。是否继续删除？`
      : `是否删除订单来源「${impact.name}」？`

    if (!window.confirm(confirmation)) {
      return
    }

    const result = await settingsStore.deleteOrderSource(persistedIdentifier)
    const currentIndex = orderSourceOptions.value.findIndex(
      (current) => current._persistedIdentifier === persistedIdentifier
    )

    if (currentIndex >= 0) {
      removeLocalOrderSourceOption(currentIndex)
    }
    showBasicFieldMessage(
      'orderSources',
      result.deletedWorkOrderCount > 0
        ? `订单来源「${impact.name}」及 ${result.deletedWorkOrderCount} 笔工单已删除`
        : `订单来源「${impact.name}」已删除`,
      'danger'
    )

    try {
      await Promise.all([
        workOrderStore.fetchPendingWorkOrders(),
        workOrderStore.refreshCalendarEvents(),
        workOrderStore.fetchCompletedStats()
      ])
    } catch (error) {
      showFieldError(`订单来源已删除，但工单列表刷新失败：${error.message}`)
    }
  } catch (error) {
    showFieldError(error.message)
  } finally {
    orderSourceDeletingIdentifier.value = ''
  }
}

function removeLocalOrderSourceOption(index) {
  orderSourceOptions.value.splice(index, 1)
  if (selectedOrderSourceIndex.value === index) {
    selectedOrderSourceIndex.value = -1
  } else if (selectedOrderSourceIndex.value > index) {
    selectedOrderSourceIndex.value -= 1
  }
  orderSourceOptionsError.value = orderSourceOptions.value.length ? '' : '请至少保留一个选项。'
  clearOrderSourceEditorErrors()
}

function selectOrderSourceOption(index) {
  selectedOrderSourceIndex.value = index
  clearOrderSourceEditorErrors()
}

function handleOrderSourceOptionInputFocus() {
  selectedOrderSourceIndex.value = -1
  clearOrderSourceEditorErrors()
}

function handleSettingsTabsScroll() {
  settingsTabsScrolling.value = true
  if (settingsTabsScrollTimer) {
    window.clearTimeout(settingsTabsScrollTimer)
  }
  settingsTabsScrollTimer = window.setTimeout(() => {
    settingsTabsScrolling.value = false
    settingsTabsScrollTimer = null
  }, 700)
}

function normalizeSelectedSourceColor() {
  if (selectedOrderSourceOption.value) {
    selectedOrderSourceOption.value.badgeColor = normalizeHexColor(
      selectedOrderSourceOption.value.badgeColor
    )
  }
}

function updateSelectedSourceColor(event) {
  if (selectedOrderSourceOption.value) {
    const digits = sanitizeHexDigits(event.target.value)
    event.target.value = digits
    selectedOrderSourceOption.value.badgeColor = `#${digits}`
  }
}

function updateSelectedSourceColorFromPicker(event) {
  if (selectedOrderSourceOption.value) {
    selectedOrderSourceOption.value.badgeColor = event.target.value.toUpperCase()
  }
}

function updateSelectedSourceBadgeText(event) {
  if (!selectedOrderSourceOption.value) {
    return
  }

  const badgeText = Array.from(event.target.value)
    .find((character) => /^(?:\p{Script=Han}|[A-Za-z])$/u.test(character)) || ''
  event.target.value = badgeText
  selectedOrderSourceOption.value.badgeText = badgeText
}

function clearOrderSourceEditorErrors() {
  orderSourceEditorErrors.name = ''
  orderSourceEditorErrors.badgeColor = ''
  orderSourceEditorErrors.badgeText = ''
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

    showSavedMessage('收件者已删除', 'danger')
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

function comparableOrderSourceOption(option) {
  return {
    name: option.name,
    identifier: option.identifier,
    badgeColor: option.badgeColor,
    badgeText: option.badgeText
  }
}

function resetBasicSettingsForm() {
  amountInput.value = formatAmount(settingsStore.settings.estimatedHourlyBaseAmount)
  weekStartTimeInput.value = formatWeekStartTime(settingsStore.settings.weekViewDefaultStartTime)
  resetOrderSourceOptionsForm()
}

function resetOrderSourceOptionsForm() {
  orderSourceOptions.value = (settingsStore.settings.orderSourceOptions || [
    { name: '千牛', identifier: 'QIANNIU', badgeColor: '#218BFF', badgeText: '千' },
    { name: '小红书', identifier: 'XIAOHONGSHU', badgeColor: '#FF5C5C', badgeText: '书' }
  ]).map((option) => ({
    ...option,
    _persistedIdentifier: option.identifier
  }))
  orderSourceOptionInput.value = ''
  selectedOrderSourceIndex.value = -1
  clearOrderSourceEditorErrors()
}

function recipientHasChanges(recipient) {
  return normalizedText(recipientNameDraft.value) !== normalizedText(recipient.name) ||
    normalizedText(recipientEmailDraft.value) !== normalizedText(recipient.email)
}

function normalizedText(value) {
  return String(value ?? '').trim()
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
  clearOrderSourceEditorErrors()
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

function showSavedMessage(message, tone = 'info') {
  savedMessage.value = message
  savedMessageTone.value = tone

  if (savedMessageTimer) {
    window.clearTimeout(savedMessageTimer)
  }

  savedMessageTimer = window.setTimeout(() => {
    savedMessage.value = ''
    savedMessageTone.value = 'info'
    savedMessageTimer = null
  }, 5000)
}

function clearSavedMessage() {
  savedMessage.value = ''
  savedMessageTone.value = 'info'

  if (savedMessageTimer) {
    window.clearTimeout(savedMessageTimer)
    savedMessageTimer = null
  }
}

function showBasicFieldMessage(field, message, tone = 'info') {
  basicFieldMessages[field] = message
  basicFieldMessageTones[field] = tone

  if (basicFieldMessageTimers[field]) {
    window.clearTimeout(basicFieldMessageTimers[field])
  }

  basicFieldMessageTimers[field] = window.setTimeout(() => {
    basicFieldMessages[field] = ''
    basicFieldMessageTones[field] = 'info'
    delete basicFieldMessageTimers[field]
  }, 5000)
}

function clearBasicFieldMessages() {
  Object.keys(basicFieldMessages).forEach((field) => {
    basicFieldMessages[field] = ''
    basicFieldMessageTones[field] = 'info'

    if (basicFieldMessageTimers[field]) {
      window.clearTimeout(basicFieldMessageTimers[field])
      delete basicFieldMessageTimers[field]
    }
  })
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
      <div class="dialog-heading settings-dialog-heading">
        <h2>全局设置</h2>
        <div
          class="settings-tabs"
          :class="{ scrolling: settingsTabsScrolling }"
          role="tablist"
          aria-label="全局设置分类"
          @scroll.passive="handleSettingsTabsScroll"
        >
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
        <button class="icon-only-button" type="button" aria-label="关闭" @click="emit('close')">
          <X :size="18" />
        </button>
      </div>

      <section v-show="activeTab === 'basic'" class="settings-panel" role="tabpanel">
        <div class="basic-settings-form">
          <div class="basic-settings-grid">
            <label>
              <span class="form-field-label">
                预估工时基础金额（元/小时）
                <small
                  v-if="basicFieldMessages.amount"
                  class="field-save-status"
                  role="status"
                >
                  {{ basicFieldMessages.amount }}
                </small>
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
                @blur="autoSaveAmount"
                @change="autoSaveAmount"
                @keydown.enter.prevent="$event.currentTarget.blur()"
              />
            </label>

            <label>
              <span class="form-field-label">
                周表默认开始时间
                <HelpTooltip aria-label="查看周表默认开始时间说明">
                  <p>当前周没有已排工单时，从此时间开始显示；有工单时会自动显示该周最早的工单时间。</p>
                </HelpTooltip>
                <small
                  v-if="basicFieldMessages.weekStartTime"
                  class="field-save-status"
                  role="status"
                >
                  {{ basicFieldMessages.weekStartTime }}
                </small>
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
                @blur="autoSaveWeekStartTime"
                @change="autoSaveWeekStartTime"
                @keydown.enter.prevent="$event.currentTarget.blur()"
              />
            </label>

            <div class="basic-settings-wide-field">
              <label class="form-field-label" for="order-source-option-input">
                订单来源选项
                <HelpTooltip aria-label="查看订单来源选项说明">
                  <p>用于手动新增待排工单时选择订单来源；输入后按 Enter 或逗号添加。</p>
                </HelpTooltip>
                <small
                  v-if="basicFieldMessages.orderSources"
                  class="field-save-status"
                  :class="{ danger: basicFieldMessageTones.orderSources === 'danger' }"
                  role="status"
                >
                  {{ basicFieldMessages.orderSources }}
                </small>
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
                <span
                  v-for="(option, index) in orderSourceOptions"
                  :key="option._persistedIdentifier || option.identifier || `${option.name}-${index}`"
                  class="recipient-tag order-source-option-tag"
                  :class="{ active: selectedOrderSourceIndex === index }"
                  :style="{ '--order-source-color': pickerColor(option.badgeColor) }"
                  role="button"
                  tabindex="0"
                  :aria-label="`编辑订单来源 ${option.name}`"
                  @click.stop="selectOrderSourceOption(index)"
                  @keydown.enter.prevent="selectOrderSourceOption(index)"
                  @keydown.space.prevent="selectOrderSourceOption(index)"
                >
                  <span>{{ option.name }}</span>
                  <button
                    type="button"
                    :aria-label="`删除订单来源选项 ${option.name}`"
                    :disabled="settingsBusy"
                    @click.stop="removeOrderSourceOption(option, index)"
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
                  @focus="handleOrderSourceOptionInputFocus"
                  @keydown="handleOrderSourceOptionKeydown"
                />
              </div>

              <div v-if="selectedOrderSourceOption" class="order-source-option-editor">
                <label>
                  <span class="form-field-label">
                    来源名称
                    <span class="required-marker" aria-hidden="true">*</span>
                    <small
                      v-if="orderSourceEditorErrors.name"
                      class="form-field-error"
                      role="alert"
                    >
                      {{ orderSourceEditorErrors.name }}
                    </small>
                  </span>
                  <input
                    v-model="selectedOrderSourceOption.name"
                    type="text"
                    maxlength="80"
                    placeholder="例如 小红书"
                    autocomplete="off"
                    required
                    :aria-invalid="Boolean(orderSourceEditorErrors.name)"
                    :disabled="settingsBusy"
                  />
                </label>
                <label>
                  <span class="form-field-label">
                    标签单一文字
                    <span class="required-marker" aria-hidden="true">*</span>
                    <small
                      v-if="orderSourceEditorErrors.badgeText"
                      class="form-field-error"
                      role="alert"
                    >
                      {{ orderSourceEditorErrors.badgeText }}
                    </small>
                  </span>
                  <input
                    :value="selectedOrderSourceOption.badgeText"
                    type="text"
                    maxlength="2"
                    placeholder="例如 抖"
                    required
                    :aria-invalid="Boolean(orderSourceEditorErrors.badgeText)"
                    :disabled="settingsBusy"
                    @input="updateSelectedSourceBadgeText"
                  />
                </label>
                <label class="order-source-color-editor-field">
                  <span class="form-field-label">
                    标签颜色
                    <span class="required-marker" aria-hidden="true">*</span>
                    <small
                      v-if="orderSourceEditorErrors.badgeColor"
                      class="form-field-error"
                      role="alert"
                    >
                      {{ orderSourceEditorErrors.badgeColor }}
                    </small>
                  </span>
                  <span class="order-source-color-field">
                    <input
                      :value="pickerColor(selectedOrderSourceOption.badgeColor)"
                      type="color"
                      :disabled="settingsBusy"
                      aria-label="选择订单来源标签颜色"
                      @input="updateSelectedSourceColorFromPicker"
                    />
                    <span class="hex-color-input">
                      <span aria-hidden="true">#</span>
                      <input
                        :value="hexColorDigits(selectedOrderSourceOption.badgeColor)"
                        type="text"
                        inputmode="text"
                        maxlength="6"
                        placeholder="3B82F6"
                        aria-label="订单来源标签颜色十六进制值"
                        :aria-invalid="Boolean(orderSourceEditorErrors.badgeColor)"
                        :disabled="settingsBusy"
                        required
                        @input="updateSelectedSourceColor"
                        @blur="normalizeSelectedSourceColor"
                      />
                    </span>
                  </span>
                </label>
                <div class="order-source-option-editor-actions">
                  <button
                    class="icon-button primary-action"
                    type="button"
                    :disabled="settingsBusy || !selectedOrderSourceChanged"
                    @click="saveOrderSourceOptions"
                  >
                    <span v-if="settingsStore.saving" class="loading-spinner" aria-hidden="true"></span>
                    <Save v-else :size="18" />
                    {{ settingsStore.saving ? '保存中' : '保存' }}
                  </button>
                </div>
              </div>
            </div>
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
        <ImportFieldSettingsPanel
          :active="activeTab === 'fields'"
          @saved="refreshWorkOrdersAfterRemarkTagSave"
        />
      </section>

      <section v-show="activeTab === 'recipients'" class="settings-panel recipient-settings-panel" role="tabpanel">
        <div class="recipient-list-heading">
          <div>
            <h3>常用与寄送过的收件者</h3>
            <p>成功寄送的新 Email 会自动加入此列表。</p>
          </div>
          <div class="dialog-actions">
            <span
              v-if="savedMessage"
              class="dialog-status"
              :class="{ danger: savedMessageTone === 'danger' }"
              role="status"
            >
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
        <span
          v-if="savedMessage && activeTab === 'email'"
          class="dialog-status"
          :class="{ danger: savedMessageTone === 'danger' }"
          role="status"
        >
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
