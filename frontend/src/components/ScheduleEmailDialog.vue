<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { Mail, X } from '@lucide/vue'
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

const emit = defineEmits(['close'])
const store = useWorkOrderStore()
const sending = ref(false)
const sentMessage = ref('')
let sentMessageTimer = null

const form = reactive({
  recipients: '',
  subject: '',
  viewType: 'WEEK',
  dateFrom: '',
  dateTo: '',
  month: '',
  completedStatsMonth: ''
})

const recipients = computed(() =>
  form.recipients
    .split(',')
    .map((email) => email.trim())
    .filter(Boolean)
)

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

    await refreshCompletedStatsOptions()
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
  if (sending.value) {
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
    showSentMessage('Email 已发送')
  } catch (error) {
    store.setError(error.message)
  } finally {
    sending.value = false
  }
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
    return `月排程表 - ${form.month || currentMonth()}`
  }

  return `周排程表 - ${form.dateFrom || ''} - ${form.dateTo || ''}`
}

async function refreshCompletedStatsOptions() {
  try {
    await store.fetchCompletedStats()
  } catch (error) {
    store.setError(error.message)
  }
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
})
</script>

<template>
  <div v-if="open" class="dialog-backdrop" role="presentation" @click="emit('close')">
    <form class="dialog" aria-label="发送排程 Email" @click.stop @submit.prevent="submit">
      <div class="dialog-heading">
        <h2>发送排程 Email</h2>
        <button class="icon-only-button" type="button" aria-label="关闭" @click="emit('close')">
          <X :size="18" />
        </button>
      </div>

      <label>
        收件人 Email
        <input v-model="form.recipients" type="text" placeholder="someone@example.com" required />
      </label>

      <label>
        主题
        <input v-model="form.subject" type="text" required readonly />
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
          开始日期
          <input v-model="form.dateFrom" type="date" required />
        </label>
        <label>
          结束日期
          <input v-model="form.dateTo" type="date" :min="form.dateFrom" required />
        </label>
      </div>

      <label v-else-if="form.viewType === 'MONTH'">
        月份
        <MonthPicker v-model="form.month" required aria-label="月份" />
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

      <div class="dialog-actions">
        <span v-if="sentMessage" class="dialog-status" role="status">
          {{ sentMessage }}
        </span>
        <button class="icon-button primary-action" type="submit" :disabled="sending">
          <span v-if="sending" class="loading-spinner" aria-hidden="true"></span>
          <Mail v-else :size="18" />
          {{ sending ? '发送中' : '发送' }}
        </button>
      </div>
    </form>
  </div>
</template>
