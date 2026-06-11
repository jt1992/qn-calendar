<script setup>
import MonthPicker from './MonthPicker.vue'

defineProps({
  monthFilter: {
    type: String,
    default: ''
  },
  stats: {
    type: Array,
    required: true
  },
  availableMonths: {
    type: Array,
    default: () => []
  }
})

const emit = defineEmits(['update-month-filter'])

function displayText(value) {
  return value === null || value === undefined || value === '' ? '-' : value
}

function formatCurrency(value) {
  if (value === null || value === undefined || value === '') {
    return '-'
  }

  return `$${Number(value).toLocaleString('zh-TW', {
    minimumFractionDigits: 0,
    maximumFractionDigits: 2
  })}`
}

function formatDurationText(minutes) {
  const normalizedMinutes = Math.max(0, Math.round(minutes || 0))
  const hours = Math.floor(normalizedMinutes / 60)
  const remainingMinutes = normalizedMinutes % 60
  return `${hours}小時${remainingMinutes}分鐘`
}

function formatDeltaText(minutes) {
  if (!minutes) {
    return '符合預期'
  }

  const label = minutes > 0 ? '超出' : '提前'
  return `${label} ${formatDurationText(Math.abs(minutes))}`
}

function deltaClass(minutes) {
  return {
    'delta-late': minutes > 0,
    'delta-early': minutes < 0
  }
}

function formatHourlyRate(value) {
  if (value === null || value === undefined || value === '') {
    return '-'
  }

  return `${formatCurrency(value)} / 小時`
}
</script>

<template>
  <section class="stats-panel" aria-label="完工統計表">
    <header class="stats-heading">
      <div>
        <h1>完工統計表</h1>
        <p>已完成工單的預估與實際工時對照</p>
      </div>
      <div class="stats-heading-actions">
        <label class="stats-month-filter">
          訂單月份
          <MonthPicker
            :model-value="monthFilter"
            :available-months="availableMonths"
            aria-label="訂單月份"
            @update:model-value="emit('update-month-filter', $event)"
          />
        </label>
        <button
          class="text-button"
          type="button"
          :class="{ active: !monthFilter }"
          @click="emit('update-month-filter', '')"
        >
          全部
        </button>
        <span class="count-badge">{{ stats.length }}</span>
      </div>
    </header>

    <div class="stats-table-scroll">
      <table class="stats-table">
        <thead>
          <tr>
            <th>訂單編號</th>
            <th>訂單備注</th>
            <th>訂單價格</th>
            <th>原本預估時長</th>
            <th>實際總時長</th>
            <th>差異時間</th>
            <th>時薪</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in stats" :key="row.id">
            <td>
              <strong>{{ row.orderNo }}</strong>
            </td>
            <td class="stats-remark">{{ displayText(row.remark) }}</td>
            <td>{{ formatCurrency(row.price) }}</td>
            <td>{{ formatDurationText(row.estimatedMinutes) }}</td>
            <td>{{ formatDurationText(row.actualTotalMinutes) }}</td>
            <td>
              <span class="delta-badge" :class="deltaClass(row.deltaMinutes)">
                {{ formatDeltaText(row.deltaMinutes) }}
              </span>
            </td>
            <td>{{ formatHourlyRate(row.hourlyRate) }}</td>
          </tr>
          <tr v-if="stats.length === 0">
            <td class="empty-state" colspan="7">尚無已完成工單</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
