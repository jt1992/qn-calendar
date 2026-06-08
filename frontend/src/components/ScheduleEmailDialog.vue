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
  dateFrom: '',
  dateTo: ''
})

const recipients = computed(() =>
  form.recipients
    .split(',')
    .map((email) => email.trim())
    .filter(Boolean)
)

watch(
  () => [props.open, props.dateFrom, props.dateTo],
  ([open]) => {
    if (!open) {
      return
    }

    form.dateFrom = props.dateFrom || ''
    form.dateTo = props.dateTo || ''
  },
  { immediate: true }
)

async function submit() {
  await store.sendScheduleEmail({
    to: recipients.value,
    subject: form.subject,
    dateFrom: form.dateFrom,
    dateTo: form.dateTo,
    viewType: 'WEEK'
  })

  emit('close')
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

      <div class="date-fields">
        <label>
          開始日期
          <input v-model="form.dateFrom" type="date" required />
        </label>
        <label>
          結束日期
          <input v-model="form.dateTo" type="date" required />
        </label>
      </div>

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
