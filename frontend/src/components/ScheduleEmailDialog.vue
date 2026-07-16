<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { Mail, Settings, X } from '@lucide/vue'
import { useAppSettingsStore } from '../stores/appSettingsStore'
import { useWorkOrderStore } from '../stores/workOrderStore'
import MonthPicker from './MonthPicker.vue'

const props = defineProps({
  dateFrom: {
    type: String,
    default: ''
  },
  dateTo: {
    type: String,
    default: ''
  },
  calendarViewType: {
    type: String,
    default: 'timeGridWeek'
  },
  defaultEmailType: {
    type: String,
    default: ''
  },
  completedStatsMonth: {
    type: String,
    default: ''
  },
  open: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['close', 'open-settings'])
const store = useWorkOrderStore()
const settingsStore = useAppSettingsStore()
const sending = ref(false)
const sentMessage = ref('')
const selectedRecipients = ref([])
const recipientQuery = ref('')
const recipientInput = ref(null)
const recipientMenuOpen = ref(false)
const activeSuggestionIndex = ref(0)
let sentMessageTimer = null

const form = reactive({
  subject: '',
  viewType: 'WEEK',
  dateFrom: '',
  dateTo: '',
  month: '',
  completedStatsMonth: ''
})

const formErrors = reactive({
  recipients: '',
  subject: '',
  dateFrom: '',
  dateTo: '',
  month: ''
})

const recipients = computed(() => selectedRecipients.value.map((recipient) => recipient.email))
const selectedRecipientEmails = computed(() => new Set(
  recipients.value.map((email) => email.toLowerCase())
))

const recipientSuggestions = computed(() => {
  const query = recipientQuery.value.trim().toLowerCase()

  return settingsStore.emailRecipients
    .filter((recipient) => !selectedRecipientEmails.value.has(recipient.email.toLowerCase()))
    .filter((recipient) => {
      if (!query) {
        return true
      }

      return recipient.email.toLowerCase().includes(query)
        || (recipient.name || '').toLowerCase().includes(query)
    })
})

const showRecipientMenu = computed(() => recipientMenuOpen.value)
const hasRecipientSuggestions = computed(() => recipientSuggestions.value.length > 0)

const activeSuggestionId = computed(() => {
  const recipient = recipientSuggestions.value[activeSuggestionIndex.value]
  return recipient ? `email-recipient-option-${recipient.id}` : undefined
})

const emailSenderConfigured = computed(() => Boolean(settingsStore.settings.emailSender?.configured))

const availableCompletedStatsMonths = computed(() => {
  return [...new Set(
    store.completedStats
      .map((row) => row.orderTime?.slice?.(0, 7))
      .filter(Boolean)
  )]
})

watch(
  () => [
    props.open,
    props.dateFrom,
    props.dateTo,
    props.calendarViewType,
    props.defaultEmailType,
    props.completedStatsMonth
  ],
  async ([open]) => {
    if (!open) {
      return
    }

    sending.value = false
    clearSentMessage()
    form.viewType = resolveInitialViewType()
    form.dateFrom = props.dateFrom || ''
    form.dateTo = props.dateTo || ''
    form.month = monthFromDate(props.dateFrom) || currentMonth()
    form.completedStatsMonth = props.completedStatsMonth || ''
    form.subject = defaultSubject()
    recipientQuery.value = ''
    recipientMenuOpen.value = false
    activeSuggestionIndex.value = 0
    clearFormValidation()

    await Promise.all([
      refreshEmailSenderSettings(),
      refreshEmailRecipients(),
      refreshCompletedStatsOptions()
    ])
  },
  { immediate: true }
)

watch(
  () => [
    form.viewType,
    form.dateFrom,
    form.dateTo,
    form.month,
    form.completedStatsMonth
  ],
  () => {
    form.subject = defaultSubject()
  }
)

async function submit() {
  if (sending.value || !emailSenderConfigured.value) {
    if (!emailSenderConfigured.value) {
      store.setError('请先在全局设置中配置寄件者 SMTP')
    }

    return
  }

  if (!validateForm()) {
    if (formErrors.recipients) {
      recipientInput.value?.focus()
    }

    return
  }

  sending.value = true

  try {
    const dateRange = resolveDateRange()

    await store.sendScheduleEmail({
      to: recipients.value,
      subject: defaultSubject(),
      ...dateRange,
      viewType: form.viewType
    })

    store.clearError()
    clearFormValidation()
    showSentMessage('Email 已发送')
  } catch (error) {
    store.setError(error.message)
  } finally {
    sending.value = false
  }
}

function validateForm() {
  const errors = {
    recipients: '',
    subject: '',
    dateFrom: '',
    dateTo: '',
    month: ''
  }

  if (recipientQuery.value.trim() && !commitRecipientQuery()) {
    errors.recipients = '请输入完整 Email，或从搜索结果中选取收件者。'
  } else if (selectedRecipients.value.length === 0) {
    errors.recipients = '不能为空。'
  }

  if (!form.subject.trim()) {
    errors.subject = '不能为空。'
  }

  if (form.viewType === 'WEEK') {
    if (!form.dateFrom) {
      errors.dateFrom = '不能为空。'
    }

    if (!form.dateTo) {
      errors.dateTo = '不能为空。'
    } else if (form.dateFrom && form.dateTo < form.dateFrom) {
      errors.dateTo = '不能早于开始日期。'
    }
  }

  if (form.viewType === 'MONTH' && !form.month) {
    errors.month = '不能为空。'
  }

  Object.entries(errors).forEach(([key, message]) => {
    if (message) {
      formErrors[key] = message
    }
  })

  return !Object.values(errors).some(Boolean)
}

function resolveDateRange() {
  if (form.viewType === 'COMPLETED_STATS') {
    if (!form.completedStatsMonth) {
      return {
        dateFrom: null,
        dateTo: null
      }
    }

    return monthRange(form.completedStatsMonth)
  }

  if (form.viewType === 'MONTH') {
    return monthRange(form.month)
  }

  return {
    dateFrom: form.dateFrom,
    dateTo: form.dateTo
  }
}

function monthRange(monthValue) {
  const safeMonthValue = monthValue || currentMonth()
  const [year, month] = safeMonthValue.split('-').map(Number)
  const lastDay = new Date(year, month, 0).getDate()

  return {
    dateFrom: `${safeMonthValue}-01`,
    dateTo: `${safeMonthValue}-${String(lastDay).padStart(2, '0')}`
  }
}

function monthFromDate(value) {
  return value?.slice?.(0, 7) || ''
}

function currentMonth() {
  const today = new Date()
  return `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}`
}

async function setViewType(viewType) {
  if (viewType !== form.viewType) {
    clearScheduleFieldValidation()
  }

  form.viewType = viewType

  if (viewType === 'COMPLETED_STATS') {
    await refreshCompletedStatsOptions()
  }
}

function resolveInitialViewType() {
  if (props.defaultEmailType) {
    return props.defaultEmailType
  }

  return props.calendarViewType === 'dayGridMonth' ? 'MONTH' : 'WEEK'
}

function defaultSubject() {
  if (form.viewType === 'COMPLETED_STATS') {
    return `完工统计表 - ${form.completedStatsMonth || '全部'}`
  }

  if (form.viewType === 'MONTH') {
    return `月表 - ${form.month || currentMonth()}`
  }

  return `周表 - ${form.dateFrom || ''} - ${form.dateTo || ''}`
}

async function refreshCompletedStatsOptions() {
  try {
    await store.fetchCompletedStats()
  } catch (error) {
    store.setError(error.message)
  }
}

async function refreshEmailSenderSettings() {
  try {
    await settingsStore.fetchSettings()
  } catch (error) {
    store.setError(error.message)
  }
}

async function refreshEmailRecipients() {
  try {
    await settingsStore.fetchEmailRecipients()
  } catch (error) {
    store.setError(error.message)
  }
}

function openRecipientMenu() {
  recipientMenuOpen.value = true
  activeSuggestionIndex.value = 0
}

function handleRecipientInput() {
  openRecipientMenu()
}

function closeRecipientMenu() {
  recipientMenuOpen.value = false
}

function selectRecipient(recipient) {
  addRecipient(recipient)
  recipientQuery.value = ''
  recipientMenuOpen.value = true
  activeSuggestionIndex.value = 0
  recipientInput.value?.focus()
}

function addRecipient(recipient) {
  const normalizedEmail = recipient.email.trim().toLowerCase()

  if (selectedRecipientEmails.value.has(normalizedEmail)) {
    return
  }

  selectedRecipients.value.push({
    id: recipient.id || null,
    name: recipient.name?.trim() || '',
    email: normalizedEmail
  })
}

function removeRecipient(email) {
  selectedRecipients.value = selectedRecipients.value.filter(
    (recipient) => recipient.email !== email
  )
  recipientInput.value?.focus()
}

function commitRecipientQuery() {
  const value = recipientQuery.value.trim()

  if (!value) {
    return true
  }

  const normalizedValue = value.toLowerCase()
  const exactRecipient = settingsStore.emailRecipients.find((recipient) =>
    recipient.email.toLowerCase() === normalizedValue
      || recipient.name?.trim().toLowerCase() === normalizedValue
  )

  if (exactRecipient) {
    selectRecipient(exactRecipient)
    return true
  }

  if (!isValidEmail(value)) {
    return false
  }

  addRecipient({ email: value })
  recipientQuery.value = ''
  recipientMenuOpen.value = true
  activeSuggestionIndex.value = 0
  return true
}

function handleRecipientKeydown(event) {
  if (event.key === 'Escape') {
    closeRecipientMenu()
    return
  }

  if (event.key === 'Backspace' && !recipientQuery.value && selectedRecipients.value.length > 0) {
    selectedRecipients.value.pop()
    return
  }

  if (event.key === ',') {
    event.preventDefault()

    if (!commitRecipientQuery()) {
      showRecipientFieldError('请输入完整 Email，或从搜索结果中选取收件者。')
    }

    return
  }

  if (!hasRecipientSuggestions.value) {
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      openRecipientMenu()
    } else if (event.key === 'Enter') {
      event.preventDefault()

      if (!commitRecipientQuery()) {
        showRecipientFieldError('请输入完整 Email，或从搜索结果中选取收件者。')
      }
    }

    return
  }

  if (event.key === 'ArrowDown') {
    event.preventDefault()
    activeSuggestionIndex.value = (
      activeSuggestionIndex.value + 1
    ) % recipientSuggestions.value.length
    return
  }

  if (event.key === 'ArrowUp') {
    event.preventDefault()
    activeSuggestionIndex.value = (
      activeSuggestionIndex.value - 1 + recipientSuggestions.value.length
    ) % recipientSuggestions.value.length
    return
  }

  if (event.key === 'Enter') {
    event.preventDefault()
    selectRecipient(recipientSuggestions.value[activeSuggestionIndex.value])
  }
}

function isValidEmail(value) {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)
}

function showRecipientFieldError(message) {
  formErrors.recipients = message
}

function clearScheduleFieldValidation() {
  formErrors.dateFrom = ''
  formErrors.dateTo = ''
  formErrors.month = ''
}

function clearFormValidation() {
  Object.keys(formErrors).forEach((key) => {
    formErrors[key] = ''
  })
}

function showSentMessage(message) {
  sentMessage.value = message

  if (sentMessageTimer) {
    window.clearTimeout(sentMessageTimer)
  }

  sentMessageTimer = window.setTimeout(() => {
    sentMessage.value = ''
    sentMessageTimer = null
  }, 5000)
}

function clearSentMessage() {
  sentMessage.value = ''

  if (sentMessageTimer) {
    window.clearTimeout(sentMessageTimer)
    sentMessageTimer = null
  }
}

onBeforeUnmount(() => {
  clearSentMessage()
  clearFormValidation()
})
</script>

<template>
  <div
    v-if="open"
    class="dialog-backdrop email-dialog-backdrop"
    role="presentation"
    @click="emit('close')"
  >
    <form class="dialog" aria-label="发送排程 Email" novalidate @click.stop @submit.prevent="submit">
      <div class="dialog-heading">
        <h2>发送排程 Email</h2>
        <button class="icon-only-button" type="button" aria-label="关闭" @click="emit('close')">
          <X :size="18" />
        </button>
      </div>

      <div class="dialog-field">
        <label class="form-field-label" for="schedule-email-recipients">
          收件人
          <small
            v-if="formErrors.recipients"
            id="schedule-email-recipients-error"
            class="form-field-error"
            role="alert"
          >
            {{ formErrors.recipients }}
          </small>
        </label>
        <div class="recipient-combobox">
          <div
            class="recipient-tag-input"
            :class="{ invalid: formErrors.recipients }"
            @click="recipientInput?.focus()"
          >
            <span
              v-for="recipient in selectedRecipients"
              :key="recipient.email"
              class="recipient-tag"
              :title="recipient.email"
            >
              <span>{{ recipient.name || recipient.email }}</span>
              <button
                type="button"
                :aria-label="`移除 ${recipient.name || recipient.email}`"
                @click.stop="removeRecipient(recipient.email)"
              >
                <X :size="13" />
              </button>
            </span>
            <input
              id="schedule-email-recipients"
              ref="recipientInput"
              v-model="recipientQuery"
              class="recipient-tag-search"
              type="text"
              role="combobox"
              autocomplete="off"
              aria-autocomplete="list"
              aria-controls="email-recipient-options"
              :aria-expanded="showRecipientMenu"
              :aria-activedescendant="activeSuggestionId"
              :aria-describedby="formErrors.recipients ? 'schedule-email-recipients-error' : undefined"
              :aria-invalid="Boolean(formErrors.recipients)"
              :placeholder="selectedRecipients.length ? '继续添加' : '输入姓名或 Email 搜索'"
              @focus="openRecipientMenu"
              @input="handleRecipientInput"
              @blur="closeRecipientMenu"
              @keydown="handleRecipientKeydown"
            />
          </div>
          <div
            v-if="showRecipientMenu"
            id="email-recipient-options"
            class="recipient-suggestions"
            role="listbox"
          >
            <button
              v-for="(recipient, index) in recipientSuggestions"
              :id="`email-recipient-option-${recipient.id}`"
              :key="recipient.id"
              class="recipient-suggestion"
              :class="{ active: index === activeSuggestionIndex }"
              type="button"
              role="option"
              :aria-selected="index === activeSuggestionIndex"
              @mousedown.prevent
              @click="selectRecipient(recipient)"
            >
              <strong>{{ recipient.name || recipient.email }}</strong>
              <span v-if="recipient.name">{{ recipient.email }}</span>
            </button>
            <p v-if="!hasRecipientSuggestions" class="recipient-suggestion-empty">
              {{ recipientQuery.trim() ? '没有匹配的收件人' : '尚无可选收件人' }}
            </p>
          </div>
        </div>
      </div>

      <label>
        <span class="form-field-label">
          主题
          <small
            v-if="formErrors.subject"
            id="schedule-email-subject-error"
            class="form-field-error"
            role="alert"
          >
            {{ formErrors.subject }}
          </small>
        </span>
        <input
          v-model="form.subject"
          type="text"
          required
          readonly
          :aria-describedby="formErrors.subject ? 'schedule-email-subject-error' : undefined"
          :aria-invalid="Boolean(formErrors.subject)"
        />
      </label>

      <div class="email-type-switch" aria-label="Email 类型">
        <button
          type="button"
          :class="{ active: form.viewType === 'WEEK' }"
          @click="setViewType('WEEK')"
        >
          周表
        </button>
        <button
          type="button"
          :class="{ active: form.viewType === 'MONTH' }"
          @click="setViewType('MONTH')"
        >
          月表
        </button>
        <button
          type="button"
          :class="{ active: form.viewType === 'COMPLETED_STATS' }"
          @click="setViewType('COMPLETED_STATS')"
        >
          完工统计
        </button>
      </div>

      <div v-if="form.viewType === 'WEEK'" class="date-fields">
        <label>
          <span class="form-field-label">
            开始日期
            <small
              v-if="formErrors.dateFrom"
              id="schedule-email-date-from-error"
              class="form-field-error"
              role="alert"
            >
              {{ formErrors.dateFrom }}
            </small>
          </span>
          <input
            v-model="form.dateFrom"
            type="date"
            required
            :aria-describedby="formErrors.dateFrom ? 'schedule-email-date-from-error' : undefined"
            :aria-invalid="Boolean(formErrors.dateFrom)"
          />
        </label>
        <label>
          <span class="form-field-label">
            结束日期
            <small
              v-if="formErrors.dateTo"
              id="schedule-email-date-to-error"
              class="form-field-error"
              role="alert"
            >
              {{ formErrors.dateTo }}
            </small>
          </span>
          <input
            v-model="form.dateTo"
            type="date"
            :min="form.dateFrom"
            required
            :aria-describedby="formErrors.dateTo ? 'schedule-email-date-to-error' : undefined"
            :aria-invalid="Boolean(formErrors.dateTo)"
          />
        </label>
      </div>

      <label v-else-if="form.viewType === 'MONTH'">
        <span class="form-field-label">
          月份
          <small
            v-if="formErrors.month"
            id="schedule-email-month-error"
            class="form-field-error"
            role="alert"
          >
            {{ formErrors.month }}
          </small>
        </span>
        <MonthPicker
          v-model="form.month"
          required
          aria-label="月份"
          :aria-describedby="formErrors.month ? 'schedule-email-month-error' : undefined"
          :aria-invalid="Boolean(formErrors.month)"
        />
      </label>

      <label v-else-if="form.viewType === 'COMPLETED_STATS'">
        订单月份
        <div class="month-filter-row">
          <MonthPicker
            v-model="form.completedStatsMonth"
            :available-months="availableCompletedStatsMonths"
            aria-label="订单月份"
          />
          <button
            class="text-button"
            type="button"
            :class="{ active: !form.completedStatsMonth }"
            @click="form.completedStatsMonth = ''"
          >
            全部
          </button>
        </div>
      </label>

      <div v-if="!emailSenderConfigured" class="dialog-warning">
        <span>请先配置寄件者 SMTP</span>
        <button class="icon-button" type="button" @click="emit('open-settings', 'email')">
          <Settings :size="18" />
          设置寄件者
        </button>
      </div>

      <div class="dialog-actions">
        <span v-if="sentMessage" class="dialog-status" role="status">
          {{ sentMessage }}
        </span>
        <button
          class="icon-button primary-action"
          type="submit"
          :disabled="sending || settingsStore.loading || !emailSenderConfigured"
        >
          <span v-if="sending" class="loading-spinner" aria-hidden="true"></span>
          <Mail v-else :size="18" />
          {{ sending ? '发送中' : '发送' }}
        </button>
      </div>
    </form>
  </div>
</template>
