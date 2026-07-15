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

function formatCurrency(value, minimumFractionDigits = 0) {
  if (value === null || value === undefined || value === '') {
    return '-'
  }

  return `¥${Number(value).toLocaleString('zh-CN', {
    minimumFractionDigits,
    maximumFractionDigits: 2
  })}`
}

function formatDurationText(minutes) {
  const normalizedMinutes = Math.max(0, Math.round(minutes || 0))
  const hours = Math.floor(normalizedMinutes / 60)
  const remainingMinutes = normalizedMinutes % 60
  const parts = []

  if (hours > 0) {
    parts.push(`${hours} h`)
  }

  if (remainingMinutes > 0) {
    parts.push(`${remainingMinutes} m`)
  }

  return parts.length ? parts.join(' ') : '0 m'
}

function formatDeltaText(minutes) {
  if (!minutes) {
    return '符合预期'
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

  return formatCurrency(value, 2)
}
</script>

<template>
  <section class="stats-panel" aria-label="完工统计表">
    <header class="stats-heading">
      <div>
        <p>已完成工单的预估与实际工时对照</p>
      </div>
      <div class="stats-heading-actions">
        <label class="stats-month-filter">
          订单月份
          <MonthPicker
            :model-value="monthFilter"
            :available-months="availableMonths"
            aria-label="订单月份"
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
        <span class="count-badge">共 {{ stats.length }} 笔</span>
      </div>
    </header>

    <div class="stats-table-scroll">
      <table class="stats-table">
        <thead>
          <tr>
            <th>订单编号</th>
            <th>订单备注</th>
            <th class="stats-number">订单价格</th>
            <th class="stats-number">预估工时</th>
            <th class="stats-number">实际工时</th>
            <th class="stats-number">暂停时长</th>
            <th class="stats-number">工时差</th>
            <th class="stats-number">时薪</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in stats" :key="row.id">
            <td>
              <strong>{{ row.orderNo }}</strong>
            </td>
            <td class="stats-remark">{{ displayText(row.remark) }}</td>
            <td class="stats-number">{{ formatCurrency(row.price) }}</td>
            <td class="stats-number">{{ formatDurationText(row.estimatedMinutes) }}</td>
            <td class="stats-number">{{ formatDurationText(row.actualTotalMinutes) }}</td>
            <td class="stats-number">{{ formatDurationText(row.pausedMinutes) }}</td>
            <td class="stats-number">
              <span class="delta-badge" :class="deltaClass(row.deltaMinutes)">
                {{ formatDeltaText(row.deltaMinutes) }}
              </span>
            </td>
            <td class="stats-number">{{ formatHourlyRate(row.hourlyRate) }}</td>
          </tr>
          <tr v-if="stats.length === 0">
            <td class="empty-state" colspan="8">尚无已完成工单</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
