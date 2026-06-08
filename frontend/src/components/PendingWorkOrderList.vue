<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Draggable } from '@fullcalendar/interaction'
import { Clock, Minus, Plus } from '@lucide/vue'
import { useWorkOrderStore } from '../stores/workOrderStore'

const props = defineProps({
  workOrders: {
    type: Array,
    required: true
  }
})

const emit = defineEmits(['focus-order'])
const store = useWorkOrderStore()
const listRef = ref(null)
const updatingDurations = ref(new Set())
let draggable = null

const hasWorkOrders = computed(() => props.workOrders.length > 0)

onMounted(() => {
  draggable = new Draggable(listRef.value, {
    itemSelector: '.pending-order-card',
    eventData(eventElement) {
      return JSON.parse(eventElement.dataset.event)
    }
  })
})

onBeforeUnmount(() => {
  draggable?.destroy()
})

function toExternalEvent(workOrder) {
  return {
    title: `${workOrder.urgent ? '加急 ' : ''}${workOrder.orderNo}`,
    duration: minutesToDuration(workOrder.actualMinutes || workOrder.estimatedMinutes),
    extendedProps: {
      workOrderId: workOrder.id,
      orderNo: workOrder.orderNo,
      urgent: workOrder.urgent,
      status: workOrder.status,
      latestShipTime: workOrder.latestShipTime,
      price: workOrder.price,
      estimatedMinutes: workOrder.estimatedMinutes,
      actualMinutes: workOrder.actualMinutes
    }
  }
}

function minutesToDuration(minutes) {
  const hours = Math.floor(minutes / 60)
  const remainingMinutes = minutes % 60
  return `${String(hours).padStart(2, '0')}:${String(remainingMinutes).padStart(2, '0')}:00`
}

function durationMinutes(workOrder) {
  return workOrder.actualMinutes || workOrder.estimatedMinutes || 5
}

function normalizeMinutes(minutes) {
  if (!Number.isFinite(minutes)) {
    return 5
  }

  return Math.max(5, Math.round(minutes / 5) * 5)
}

function setDurationUpdating(id, updating) {
  const next = new Set(updatingDurations.value)

  if (updating) {
    next.add(id)
  } else {
    next.delete(id)
  }

  updatingDurations.value = next
}

function isDurationUpdating(id) {
  return updatingDurations.value.has(id)
}

async function adjustDuration(workOrder, deltaMinutes) {
  await updateDuration(workOrder, durationMinutes(workOrder) + deltaMinutes)
}

async function updateDuration(workOrder, minutes) {
  const actualMinutes = normalizeMinutes(minutes)

  if (actualMinutes === durationMinutes(workOrder)) {
    return
  }

  setDurationUpdating(workOrder.id, true)

  try {
    await store.updateWorkOrderDuration(workOrder.id, actualMinutes)
  } catch (error) {
    store.error = error.message
  } finally {
    setDurationUpdating(workOrder.id, false)
  }
}

function formatDurationText(minutes) {
  const normalizedMinutes = normalizeMinutes(minutes)
  const hours = Math.floor(normalizedMinutes / 60)
  const remainingMinutes = normalizedMinutes % 60
  return `${hours}小時${remainingMinutes}分鐘`
}

function formatShipTime(value) {
  return value ? value.replace('T', ' ').slice(0, 19) : '-'
}

function formatMoney(value) {
  return new Intl.NumberFormat('zh-TW', {
    style: 'currency',
    currency: 'TWD',
    maximumFractionDigits: 0
  }).format(Number(value || 0))
}

function statusLabel(status) {
  return status === 'DONE' ? '已完成' : status === 'SCHEDULED' ? '已排入' : '待排'
}
</script>

<template>
  <section class="pending-panel" aria-label="待排工單清單">
    <div class="panel-heading">
      <span>共 {{ workOrders.length }} 筆</span>
      <span class="count-badge">{{ workOrders.length }}</span>
    </div>

    <div ref="listRef" class="pending-list">
      <article
        v-for="workOrder in workOrders"
        :key="workOrder.id"
        class="pending-order-card"
        :class="{ urgent: workOrder.urgent }"
        :data-event="JSON.stringify(toExternalEvent(workOrder))"
        tabindex="0"
        @click="emit('focus-order', workOrder)"
        @focus="emit('focus-order', workOrder)"
      >
        <div class="order-line">
          <div class="order-summary">
            <strong>#{{ workOrder.orderNo }}</strong>
          </div>
          <div class="order-badges">
            <span class="status-badge" :class="`status-${workOrder.status.toLowerCase()}`">
              {{ statusLabel(workOrder.status) }}
            </span>
            <span v-if="workOrder.urgent" class="urgent-badge">加急</span>
          </div>
        </div>
        <div class="order-detail-line">
          <span class="order-price">訂單價格 {{ formatMoney(workOrder.price) }}</span>
          <div
            class="duration-control"
            title="每次按鈕調整 5 分鐘"
            @mousedown.stop
            @pointerdown.stop
            @dragstart.stop
          >
            <button
              type="button"
              aria-label="減少工時"
              :disabled="isDurationUpdating(workOrder.id) || durationMinutes(workOrder) <= 5"
              @click.stop="adjustDuration(workOrder, -5)"
            >
              <Minus :size="14" />
            </button>
            <span class="duration-value">{{ formatDurationText(durationMinutes(workOrder)) }}</span>
            <button
              type="button"
              aria-label="增加工時"
              :disabled="isDurationUpdating(workOrder.id)"
              @click.stop="adjustDuration(workOrder, 5)"
            >
              <Plus :size="14" />
            </button>
          </div>
        </div>
        <div class="order-meta">
          <span
            class="ship-deadline"
            :aria-label="`最晚發貨時間 ${formatShipTime(workOrder.latestShipTime)}`"
            :title="`最晚發貨時間 ${formatShipTime(workOrder.latestShipTime)}`"
          >
            <Clock :size="14" aria-hidden="true" />
            <span>{{ formatShipTime(workOrder.latestShipTime) }}</span>
          </span>
        </div>
      </article>

      <p v-if="!hasWorkOrders" class="empty-state">目前沒有待排工單</p>
    </div>
  </section>
</template>
