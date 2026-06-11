<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({
  modelValue: {
    type: String,
    default: ''
  },
  required: {
    type: Boolean,
    default: false
  },
  availableMonths: {
    type: Array,
    default: null
  },
  ariaLabel: {
    type: String,
    default: '月份'
  }
})

const emit = defineEmits(['update:modelValue'])

const currentYear = new Date().getFullYear()
const draftYear = ref('')
const draftMonth = ref('')

const allMonthOptions = Array.from({ length: 12 }, (_, index) => {
  const value = String(index + 1).padStart(2, '0')
  return {
    value,
    label: `${index + 1}月`
  }
})

const normalizedAvailableMonths = computed(() => {
  if (!Array.isArray(props.availableMonths)) {
    return []
  }

  return [...new Set(props.availableMonths)]
    .filter((month) => /^\d{4}-\d{2}$/.test(month))
    .sort((left, right) => right.localeCompare(left))
})

const usesAvailableMonths = computed(() => Array.isArray(props.availableMonths))

const yearOptions = computed(() => {
  if (usesAvailableMonths.value) {
    return [...new Set(normalizedAvailableMonths.value.map((month) => Number(month.slice(0, 4))))]
  }

  const years = new Set()

  for (let year = currentYear - 10; year <= currentYear + 2; year++) {
    years.add(year)
  }

  if (draftYear.value) {
    years.add(Number(draftYear.value))
  }

  return [...years].sort((left, right) => right - left)
})

const monthOptions = computed(() => {
  if (!usesAvailableMonths.value) {
    return allMonthOptions
  }

  if (!draftYear.value) {
    return []
  }

  const availableMonthValues = new Set(
    normalizedAvailableMonths.value
      .filter((month) => month.startsWith(`${draftYear.value}-`))
      .map((month) => month.slice(5, 7))
  )

  return allMonthOptions
    .filter((month) => availableMonthValues.has(month.value))
    .sort((left, right) => right.value.localeCompare(left.value))
})

watch(
  () => props.modelValue,
  (value) => {
    const match = /^(\d{4})-(\d{2})$/.exec(value || '')
    draftYear.value = match ? match[1] : ''
    draftMonth.value = match ? match[2] : ''
  },
  { immediate: true }
)

function updateYear(value) {
  draftYear.value = value
  emitValue()
}

function updateMonth(value) {
  draftMonth.value = value
  emitValue()
}

function emitValue() {
  emit('update:modelValue', draftYear.value && draftMonth.value ? `${draftYear.value}-${draftMonth.value}` : '')
}
</script>

<template>
  <span class="month-picker" role="group" :aria-label="ariaLabel">
    <select
      class="month-picker-select year-select"
      :required="required"
      :value="draftYear"
      aria-label="年份"
      @change="updateYear($event.target.value)"
    >
      <option value="">年</option>
      <option v-for="year in yearOptions" :key="year" :value="String(year)">
        {{ year }}年
      </option>
    </select>
    <select
      class="month-picker-select month-select"
      :required="required"
      :value="draftMonth"
      aria-label="月份"
      @change="updateMonth($event.target.value)"
    >
      <option value="">月</option>
      <option v-for="month in monthOptions" :key="month.value" :value="month.value">
        {{ month.label }}
      </option>
    </select>
  </span>
</template>
