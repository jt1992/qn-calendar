<script setup>
import { computed, reactive, watch } from 'vue'
import { Mail, X } from '@lucide/vue'
import { useWorkOrderStore } from '../stores/workOrderStore'

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

const form = reactive({
  recipients: '',
  subject: '工單排程表',
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

watch(
  () => [
    props.open,
    props.dateFrom,
    props.dateTo,
    props.calendarViewType,
    props.defaultEmailType,
    props.completedStatsMonth
  ],
  ([open]) => {
    if (!open) {
      return
    }

    form.viewType = resolveInitialViewType()
    form.subject = defaultSubject(form.viewType)
    form.dateFrom = props.dateFrom || ''
    form.dateTo = props.dateTo || ''
    form.month = monthFromDate(props.dateFrom) || currentMonth()
    form.completedStatsMonth = props.completedStatsMonth || currentMonth()
  },
  { immediate: true }
)

async function submit() {
  const dateRange = resolveDateRange()

  await store.sendScheduleEmail({
    to: recipients.value,
    subject: form.subject,
    ...dateRange,
    viewType: form.viewType
  })

  emit('close')
}

function resolveDateRange() {
  if (form.viewType === 'COMPLETED_STATS') {
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

function setViewType(viewType) {
  const previousSubject = form.subject
  form.viewType = viewType

  if (isDefaultSubject(previousSubject)) {
    form.subject = defaultSubject(viewType)
  }
}

function resolveInitialViewType() {
  if (props.defaultEmailType) {
    return props.defaultEmailType
  }

  return props.calendarViewType === 'dayGridMonth' ? 'MONTH' : 'WEEK'
}

function defaultSubject(viewType) {
  return viewType === 'COMPLETED_STATS' ? '完工統計表' : '工單排程表'
}

function isDefaultSubject(subject) {
  return ['工單排程表', '完工統計表'].includes(subject)
}
</script>

<template>
  <div v-if="open" class="dialog-backdrop" role="presentation">
    <form class="dialog" aria-label="發送排程 Email" @submit.prevent="submit">
      <div class="dialog-heading">
        <h2>發送排程 Email</h2>
        <button class="icon-only-button" type="button" aria-label="關閉" @click="emit('close')">
          <X :size="18" />
        </button>
      </div>

      <label>
        收件者 Email
        <input v-model="form.recipients" type="text" placeholder="someone@example.com" required />
      </label>

      <label>
        主旨
        <input v-model="form.subject" type="text" required />
      </label>

      <div class="email-type-switch" aria-label="Email 類型">
        <button
          type="button"
          :class="{ active: form.viewType === 'WEEK' }"
          @click="setViewType('WEEK')"
        >
          週表
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
          完工統計
        </button>
      </div>

      <div v-if="form.viewType === 'WEEK'" class="date-fields">
        <label>
          開始日期
          <input v-model="form.dateFrom" type="date" required />
        </label>
        <label>
          結束日期
          <input v-model="form.dateTo" type="date" :min="form.dateFrom" required />
        </label>
      </div>

      <label v-else-if="form.viewType === 'MONTH'">
        月份
        <input v-model="form.month" type="month" required />
      </label>

      <label v-else-if="form.viewType === 'COMPLETED_STATS'">
        完工月份
        <input v-model="form.completedStatsMonth" type="month" required />
      </label>

      <div class="dialog-actions">
        <button class="text-button" type="button" @click="emit('close')">取消</button>
        <button class="icon-button primary-action" type="submit">
          <Mail :size="18" />
          發送
        </button>
      </div>
    </form>
  </div>
</template>
