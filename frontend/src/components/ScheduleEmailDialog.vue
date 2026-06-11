<script setup>
import { computed, reactive, ref, watch } from 'vue'
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

const form = reactive({
  recipients: '',
  subject: '工单排程表',
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
    form.viewType = resolveInitialViewType()
    form.subject = defaultSubject(form.viewType)
    form.dateFrom = props.dateFrom || ''
    form.dateTo = props.dateTo || ''
    form.month = monthFromDate(props.dateFrom) || currentMonth()
    form.completedStatsMonth = props.completedStatsMonth || ''

    await refreshCompletedStatsOptions()
  },
  { immediate: true }
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
      subject: form.subject,
      ...dateRange,
      viewType: form.viewType
    })

    emit('close')
  } catch (error) {
    store.error = error.message
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
  const previousSubject = form.subject
  form.viewType = viewType

  if (isDefaultSubject(previousSubject)) {
    form.subject = defaultSubject(viewType)
  }

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

function defaultSubject(viewType) {
  return viewType === 'COMPLETED_STATS' ? '完工统计表' : '工单排程表'
}

function isDefaultSubject(subject) {
  return ['工单排程表', '完工统计表'].includes(subject)
}

async function refreshCompletedStatsOptions() {
  try {
    await store.fetchCompletedStats()
  } catch (error) {
    store.error = error.message
  }
}
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
        <input v-model="form.subject" type="text" required />
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
        <button class="icon-button primary-action" type="submit" :disabled="sending">
          <span v-if="sending" class="loading-spinner" aria-hidden="true"></span>
          <Mail v-else :size="18" />
          {{ sending ? '发送中' : '发送' }}
        </button>
      </div>
    </form>
  </div>
</template>
