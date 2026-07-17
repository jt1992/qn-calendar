<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Draggable } from '@fullcalendar/interaction'
import { Clock, Minus, Plus, Trash2 } from '@lucide/vue'
import { useWorkOrderStore } from '../stores/workOrderStore'

const props = defineProps({
  workOrders: {
    type: Array,
    required: true
  }
})

const emit = defineEmits(['focus-order', 'work-order-deleted'])
const store = useWorkOrderStore()
const listRef = ref(null)
const updatingDurations = ref(new Set())
const deletingWorkOrders = ref(new Set())
const orderTooltip = ref(null)
const tooltipPosition = ref({ x: 0, y: 0 })
const scheduleGranularityMinutes = 15
const cardDragThresholdPixels = 6
let draggable = null
let cardPointerInteraction = null

const hasWorkOrders = computed(() => props.workOrders.length > 0)

const orderTooltipStyle = computed(() => {
  const width = 340
  const height = 244
  const x = Math.min(tooltipPosition.value.x + 14, Math.max(14, window.innerWidth - width - 14))
  const y = Math.min(tooltipPosition.value.y + 14, Math.max(14, window.innerHeight - height - 14))

  return {
    left: `${x}px`,
    top: `${y}px`
  }
})

onMounted(() => {
  draggable = new Draggable(listRef.value, {
    itemSelector: '.pending-order-card:not(.deleting)',
    eventData(eventElement) {
      return JSON.parse(eventElement.dataset.event)
    }
  })
  window.addEventListener('pointermove', trackCardPointerMovement, { passive: true })
  window.addEventListener('pointercancel', cancelCardPointerInteraction, { passive: true })
})

onBeforeUnmount(() => {
  draggable?.destroy()
  window.removeEventListener('pointermove', trackCardPointerMovement)
  window.removeEventListener('pointercancel', cancelCardPointerInteraction)
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
      remark: workOrder.remark,
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
  return workOrder.actualMinutes || workOrder.estimatedMinutes || scheduleGranularityMinutes
}

function normalizeMinutes(minutes) {
  if (!Number.isFinite(minutes)) {
    return scheduleGranularityMinutes
  }

  return Math.max(
    scheduleGranularityMinutes,
    Math.round(minutes / scheduleGranularityMinutes) * scheduleGranularityMinutes
  )
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

function setWorkOrderDeleting(id, deleting) {
  const next = new Set(deletingWorkOrders.value)

  if (deleting) {
    next.add(id)
  } else {
    next.delete(id)
  }

  deletingWorkOrders.value = next
}

function isWorkOrderDeleting(id) {
  return deletingWorkOrders.value.has(id)
}

async function removeWorkOrder(workOrder) {
  if (!window.confirm(`确定删除工单 ${workOrder.orderNo}？此操作无法撤销。`)) {
    return
  }

  setWorkOrderDeleting(workOrder.id, true)
  hideOrderTooltip()

  try {
    await store.deleteWorkOrder(workOrder.id)
    store.setNotice(`工单 ${workOrder.orderNo} 已删除`)
    emit('work-order-deleted', workOrder.id)
  } catch (error) {
    store.setError(error.message)
  } finally {
    setWorkOrderDeleting(workOrder.id, false)
  }
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
    store.setError(error.message)
  } finally {
    setDurationUpdating(workOrder.id, false)
  }
}

function formatDurationText(minutes) {
  const normalizedMinutes = normalizeMinutes(minutes)
  const hours = Math.floor(normalizedMinutes / 60)
  const remainingMinutes = normalizedMinutes % 60
  return `${hours}小时${remainingMinutes}分钟`
}

function formatShipTime(value) {
  return value ? value.replace('T', ' ').slice(0, 19) : '-'
}

function formatMoney(value) {
  return `¥${Number(value || 0).toLocaleString('zh-CN', {
    minimumFractionDigits: 0,
    maximumFractionDigits: 2
  })}`
}

function statusLabel(status) {
  return status === 'DONE' ? '已完成' : status === 'SCHEDULED' ? '已排入' : '待排'
}

function formatRemarkText(value) {
  return value && value.trim() ? value : '无任何备注'
}

function showOrderTooltip(workOrder, event) {
  moveOrderTooltip(event)
  orderTooltip.value = {
    title: `${workOrder.urgent ? '[加急] ' : ''}${workOrder.orderNo}`,
    timeRange: '未排程',
    durationText: `总排程 ${formatDurationText(durationMinutes(workOrder))}`,
    statusText: statusLabel(workOrder.status),
    latestText: formatShipTime(workOrder.latestShipTime),
    priceText: formatMoney(workOrder.price),
    remarkText: formatRemarkText(workOrder.remark)
  }
}

function focusOrder(workOrder, event) {
  emit('focus-order', workOrder)
  showOrderTooltip(workOrder, event)
}

function startCardPointerInteraction(workOrder, event) {
  if (event.button !== 0) {
    cardPointerInteraction = null
    return
  }

  cardPointerInteraction = {
    pointerId: event.pointerId,
    workOrderId: workOrder.id,
    startX: event.clientX,
    startY: event.clientY,
    dragged: false
  }
}

function trackCardPointerMovement(event) {
  if (!cardPointerInteraction || cardPointerInteraction.pointerId !== event.pointerId) {
    return
  }

  const movedX = event.clientX - cardPointerInteraction.startX
  const movedY = event.clientY - cardPointerInteraction.startY

  if (Math.hypot(movedX, movedY) >= cardDragThresholdPixels) {
    cardPointerInteraction.dragged = true
  }
}

function cancelCardPointerInteraction(event) {
  if (cardPointerInteraction?.pointerId === event.pointerId) {
    cardPointerInteraction.dragged = true
  }
}

async function handleCardClick(workOrder, event) {
  const wasDragged = cardPointerInteraction?.workOrderId === workOrder.id
    && cardPointerInteraction.dragged
  cardPointerInteraction = null
  focusOrder(workOrder, event)

  if (wasDragged) {
    return
  }

  hideOrderTooltip()
  await store.copyOrderNumber(workOrder.orderNo)
}

function moveOrderTooltip(event) {
  if (!event) {
    return
  }

  const source = event.clientX === undefined ? event.currentTarget?.getBoundingClientRect() : null
  tooltipPosition.value = source
    ? { x: source.right, y: source.top }
    : { x: event.clientX, y: event.clientY }
}

function hideOrderTooltip() {
  orderTooltip.value = null
}
</script>

<template>
  <section class="pending-panel" aria-label="待排工单清单">
    <div class="panel-heading">
      <span class="pending-count">
        还有
        <span class="count-badge">{{ workOrders.length }}</span>
        笔
      </span>
    </div>

    <div ref="listRef" class="pending-list">
      <article
        v-for="workOrder in workOrders"
        :key="workOrder.id"
        class="pending-order-card"
        :class="{
          urgent: workOrder.urgent,
          deleting: isWorkOrderDeleting(workOrder.id)
        }"
        :data-event="isWorkOrderDeleting(workOrder.id)
          ? undefined
          : JSON.stringify(toExternalEvent(workOrder))"
        :aria-busy="isWorkOrderDeleting(workOrder.id)"
        tabindex="0"
        @pointerdown="(event) => startCardPointerInteraction(workOrder, event)"
        @click="(event) => handleCardClick(workOrder, event)"
        @focus="(event) => focusOrder(workOrder, event)"
        @blur="hideOrderTooltip"
        @mouseenter="(event) => showOrderTooltip(workOrder, event)"
        @mouseover="(event) => showOrderTooltip(workOrder, event)"
        @mousemove="moveOrderTooltip"
        @mouseleave="hideOrderTooltip"
      >
        <div class="pending-order-content">
          <div class="order-line">
            <div class="order-summary">
              <span class="order-number">#{{ workOrder.orderNo }}</span>
            </div>
            <span v-if="workOrder.urgent" class="urgent-badge">急</span>
          </div>
          <div class="order-detail-line">
            <span class="order-price">{{ formatMoney(workOrder.price) }}</span>
            <div
              class="duration-control"
              aria-label="每次按钮调整 15 分钟"
              @mousedown.stop
              @pointerdown.stop
              @dragstart.stop
            >
              <button
                type="button"
                aria-label="减少工时"
                :disabled="isWorkOrderDeleting(workOrder.id)
                  || isDurationUpdating(workOrder.id)
                  || durationMinutes(workOrder) <= scheduleGranularityMinutes"
                @click.stop="adjustDuration(workOrder, -scheduleGranularityMinutes)"
              >
                <Minus :size="14" />
              </button>
              <span class="duration-value">{{ formatDurationText(durationMinutes(workOrder)) }}</span>
              <button
                type="button"
                aria-label="增加工时"
                :disabled="isWorkOrderDeleting(workOrder.id) || isDurationUpdating(workOrder.id)"
                @click.stop="adjustDuration(workOrder, scheduleGranularityMinutes)"
              >
                <Plus :size="14" />
              </button>
            </div>
          </div>
          <div class="order-meta">
            <span
              class="ship-deadline"
              :aria-label="`最晚发货时间 ${formatShipTime(workOrder.latestShipTime)}`"
            >
              <Clock :size="14" aria-hidden="true" />
              <span>{{ formatShipTime(workOrder.latestShipTime) }}</span>
            </span>

            <div
              class="pending-order-actions"
              @mousedown.stop
              @pointerdown.stop
              @dragstart.stop
              @mouseover.stop
              @mousemove.stop
              @mouseenter="hideOrderTooltip"
            >
              <button
                type="button"
                class="icon-only-button pending-delete-button"
                :aria-label="`删除工单 ${workOrder.orderNo}`"
                :aria-busy="isWorkOrderDeleting(workOrder.id)"
                :disabled="isWorkOrderDeleting(workOrder.id)"
                @click.stop="removeWorkOrder(workOrder)"
              >
                <span
                  v-if="isWorkOrderDeleting(workOrder.id)"
                  class="loading-spinner"
                  aria-hidden="true"
                ></span>
                <Trash2 v-else :size="16" aria-hidden="true" />
              </button>
            </div>
          </div>
        </div>
      </article>

      <p v-if="!hasWorkOrders" class="empty-state">目前没有待排工单</p>
    </div>

    <div
      v-if="orderTooltip"
      class="event-tooltip"
      :style="orderTooltipStyle"
      role="tooltip"
    >
      <strong>{{ orderTooltip.title }}</strong>
      <span>{{ orderTooltip.timeRange }}</span>
      <span>{{ orderTooltip.durationText }}</span>
      <span>{{ orderTooltip.statusText }} · 最晚 {{ orderTooltip.latestText }}</span>
      <span v-if="orderTooltip.priceText">订单价格 {{ orderTooltip.priceText }}</span>
      <span class="event-tooltip-remark">订单备注：{{ orderTooltip.remarkText }}</span>
    </div>
  </section>
</template>
