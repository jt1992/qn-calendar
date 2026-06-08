<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import FullCalendar from '@fullcalendar/vue3'
import dayGridPlugin from '@fullcalendar/daygrid'
import timeGridPlugin from '@fullcalendar/timegrid'
import interactionPlugin from '@fullcalendar/interaction'
import { ChevronLeft, ChevronRight, Mail } from '@lucide/vue'
import { useWorkOrderStore } from '../stores/workOrderStore'

const props = defineProps({
  events: {
    type: Array,
    required: true
  },
  focusedWorkOrder: {
    type: Object,
    default: null
  }
})

const emit = defineEmits(['send-email', 'range-change', 'focus-order'])
const store = useWorkOrderStore()
const calendarRef = ref(null)
const visibleTitle = ref('')
const currentView = ref('timeGridWeek')
const pointerPosition = ref({ x: 0, y: 0 })
const tooltipPosition = ref({ x: 0, y: 0 })
const interactionPreview = ref(null)
const interactionAction = ref('調整排程')
const eventTooltip = ref(null)

const calendarEvents = computed(() => {
  const marker = deadlineMarkerEvent.value
  return marker ? [...props.events, marker] : props.events
})

const deadlineMarkerEvent = computed(() => {
  if (currentView.value !== 'timeGridWeek' || !props.focusedWorkOrder?.latestShipTime) {
    return null
  }

  const start = new Date(props.focusedWorkOrder.latestShipTime)

  if (Number.isNaN(start.getTime())) {
    return null
  }

  return {
    id: `deadline-${props.focusedWorkOrder.id || props.focusedWorkOrder.workOrderId || 'focused'}`,
    start,
    end: addMinutes(start, 5),
    display: 'background',
    classNames: ['deadline-marker'],
    extendedProps: {
      isDeadlineMarker: true
    }
  }
})

const interactionPreviewStyle = computed(() => {
  const width = 300
  const x = Math.min(pointerPosition.value.x + 14, Math.max(14, window.innerWidth - width - 14))
  const y = Math.min(pointerPosition.value.y + 14, Math.max(14, window.innerHeight - 112))

  return {
    left: `${x}px`,
    top: `${y}px`
  }
})

const eventTooltipStyle = computed(() => {
  const width = 340
  const height = 188
  const x = Math.min(tooltipPosition.value.x + 14, Math.max(14, window.innerWidth - width - 14))
  const y = Math.min(tooltipPosition.value.y + 14, Math.max(14, window.innerHeight - height - 14))

  return {
    left: `${x}px`,
    top: `${y}px`
  }
})

const calendarOptions = computed(() => ({
  plugins: [dayGridPlugin, timeGridPlugin, interactionPlugin],
  initialView: 'timeGridWeek',
  headerToolbar: false,
  allDaySlot: false,
  slotDuration: '00:30:00',
  slotLabelInterval: '01:00:00',
  snapDuration: '00:05:00',
  slotLabelFormat: {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  },
  eventTimeFormat: {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false
  },
  editable: true,
  eventDurationEditable: currentView.value === 'timeGridWeek',
  eventStartEditable: true,
  droppable: true,
  eventOverlap: canOverlapEvents,
  slotEventOverlap: false,
  eventResizableFromStart: currentView.value === 'timeGridWeek',
  height: '100%',
  events: calendarEvents.value,
  dayHeaderContent,
  eventContent,
  datesSet: handleDatesSet,
  eventAllow,
  eventDragStart: (info) => handleInteractionStart(info, '拖曳排程'),
  eventDragStop: clearInteractionPreview,
  eventReceive: handleEventReceive,
  eventDrop: handleEventMove,
  eventResizeStart: (info) => handleInteractionStart(info, '調整工時'),
  eventResizeStop: clearInteractionPreview,
  eventResize: handleEventMove,
  eventClick: handleEventClick,
  eventDidMount: bindEventTooltip,
  eventClassNames
}))

onMounted(() => {
  window.addEventListener('pointermove', handlePointerMove, { passive: true })
  window.addEventListener('pointerup', clearInteractionPreview)
})

onBeforeUnmount(() => {
  window.removeEventListener('pointermove', handlePointerMove)
  window.removeEventListener('pointerup', clearInteractionPreview)
})

function eventAllow(dropInfo, draggedEvent) {
  const latestShipTime = draggedEvent.extendedProps.latestShipTime
  const { start, end } = resolveInteractionWindow(dropInfo, draggedEvent)
  const overlapAllowed = !overlapsActiveWorkOrder(draggedEvent, start, end)

  if (!latestShipTime) {
    updateInteractionPreview(interactionAction.value, start, end, null, overlapAllowed, overlapAllowed ? '' : '與未完成工單重疊')
    return overlapAllowed
  }

  const latest = new Date(latestShipTime)
  const deadlineAllowed = end <= latest
  const allowed = deadlineAllowed && overlapAllowed
  updateInteractionPreview(
    interactionAction.value,
    start,
    end,
    latest,
    allowed,
    deadlineAllowed ? '與未完成工單重疊' : `超過最晚發貨時間 ${formatDateTime(latest)}`
  )
  return allowed
}

function canOverlapEvents(stillEvent, movingEvent) {
  return stillEvent.extendedProps.status === 'DONE'
    || movingEvent.extendedProps.status === 'DONE'
    || stillEvent.extendedProps.isDeadlineMarker
    || movingEvent.extendedProps.isDeadlineMarker
}

async function handleDatesSet(info) {
  const dateFrom = toDateOnly(info.start)
  const dateTo = toDateOnly(addDays(info.end, -1))
  const focusWeekStart = startOfWeek(info.view.calendar.getDate())
  const focusWeekEnd = addDays(focusWeekStart, 6)
  visibleTitle.value = formatVisibleTitle(info)
  currentView.value = info.view.type
  emit('range-change', {
    dateFrom: toDateOnly(focusWeekStart),
    dateTo: toDateOnly(focusWeekEnd),
    visibleDateFrom: dateFrom,
    visibleDateTo: dateTo,
    viewType: info.view.type
  })

  try {
    await store.fetchCalendarEvents(dateFrom, dateTo)
  } catch (error) {
    store.error = error.message
  }
}

function dayHeaderContent(info) {
  if (info.view.type === 'dayGridMonth') {
    return weekdayLabel(info.date)
  }

  return `${String(info.date.getDate()).padStart(2, '0')} ${weekdayLabel(info.date)}`
}

function eventContent(info) {
  if (info.event.extendedProps.isDeadlineMarker) {
    return { domNodes: [] }
  }

  const root = document.createElement('div')
  const title = document.createElement('strong')
  const timeRange = document.createElement('span')
  const duration = document.createElement('span')
  const deadlineLabel = document.createElement('span')
  const deadlineDate = document.createElement('span')
  const deadlineTime = document.createElement('span')
  const actions = document.createElement('div')
  const doneButton = document.createElement('button')
  const removeButton = document.createElement('button')
  const latestShipTime = info.event.extendedProps.latestShipTime
  const actualMinutes = info.event.extendedProps.actualMinutes || diffMinutes(info.event.start, info.event.end)

  root.className = 'calendar-event-card'
  root.dataset.tooltipTitle = `${info.event.extendedProps.urgent ? '[加急] ' : ''}${info.event.extendedProps.orderNo || info.event.title}`
  root.dataset.tooltipTimeRange = `${formatDateTime(info.event.start)} - ${formatDateTime(info.event.end)}`
  root.dataset.tooltipDuration = formatDurationText(actualMinutes)
  root.dataset.tooltipStatus = info.event.extendedProps.status === 'DONE' ? '完成' : '未完成'
  root.dataset.tooltipLatest = formatDateTime(latestShipTime)
  root.dataset.tooltipPrice = info.event.extendedProps.price ? `$${info.event.extendedProps.price}` : ''
  title.className = 'calendar-event-title'
  title.textContent = info.event.extendedProps.orderNo || info.event.title
  actions.className = 'calendar-event-actions'

  doneButton.className = 'event-complete-button'
  doneButton.type = 'button'
  doneButton.textContent = info.event.extendedProps.status === 'DONE' ? '↻' : '✓'
  doneButton.setAttribute(
    'aria-label',
    info.event.extendedProps.status === 'DONE' ? '取消完成' : '標記完成'
  )
  doneButton.addEventListener('click', async (event) => {
    event.preventDefault()
    event.stopPropagation()
    await toggleEventDone(info.event)
  })

  removeButton.className = 'event-remove-button'
  removeButton.type = 'button'
  removeButton.textContent = 'X'
  removeButton.setAttribute('aria-label', '移出日曆回到待排工單')
  removeButton.addEventListener('click', async (event) => {
    event.preventDefault()
    event.stopPropagation()
    await unscheduleEvent(info.event)
  })

  timeRange.className = 'calendar-event-time'
  timeRange.textContent = `${formatTime(info.event.start)}~${formatTime(info.event.end)}`
  duration.className = 'calendar-event-duration'
  duration.textContent = formatDurationText(info.event.extendedProps.actualMinutes)
  deadlineLabel.className = 'calendar-event-deadline-label'
  deadlineLabel.textContent = '最晚發貨：'
  deadlineDate.className = 'calendar-event-deadline-date'
  deadlineDate.textContent = formatDatePart(latestShipTime)
  deadlineTime.className = 'calendar-event-deadline-time'
  deadlineTime.textContent = formatTimePart(latestShipTime)

  actions.append(doneButton, removeButton)
  root.append(actions, title, timeRange, duration, deadlineLabel, deadlineDate, deadlineTime)
  return { domNodes: [root] }
}

function bindEventTooltip(info) {
  if (info.event.extendedProps.isDeadlineMarker) {
    return
  }

  info.el.__workOrderCalendarEvent = info.event
  info.el.addEventListener('mouseenter', (event) => showEventTooltip(info.event, event))
  info.el.addEventListener('mousemove', moveEventTooltip)
  info.el.addEventListener('mouseleave', hideEventTooltip)
  info.el.addEventListener('pointerenter', (event) => showEventTooltip(info.event, event))
  info.el.addEventListener('pointermove', moveEventTooltip)
  info.el.addEventListener('pointerleave', hideEventTooltip)
  info.el.addEventListener('focusin', (event) => showEventTooltip(info.event, event))
  info.el.addEventListener('focusout', hideEventTooltip)
}

async function handleEventReceive(info) {
  try {
    const start = normalizeScheduleStart(info.event.start)
    const end = addMinutes(start, info.event.extendedProps.actualMinutes)
    await store.scheduleWorkOrder(info.event.extendedProps.workOrderId, start, end)
    clearInteractionPreview()
  } catch (error) {
    store.error = error.message
    info.revert?.()
    info.event.remove()
    clearInteractionPreview()
  }
}

async function handleEventMove(info) {
  try {
    const start = normalizeScheduleStart(info.event.start)
    const end = currentView.value === 'timeGridWeek'
      ? info.event.end || addMinutes(start, info.event.extendedProps.actualMinutes)
      : addMinutes(start, info.event.extendedProps.actualMinutes)
    await store.scheduleWorkOrder(
      info.event.extendedProps.workOrderId,
      start,
      end
    )
    clearInteractionPreview()
  } catch (error) {
    store.error = error.message
    info.revert()
    clearInteractionPreview()
  }
}

function handleEventClick(info) {
  if (info.event.extendedProps.isDeadlineMarker) {
    return
  }

  emit('focus-order', {
    id: info.event.extendedProps.workOrderId,
    orderNo: info.event.extendedProps.orderNo,
    urgent: info.event.extendedProps.urgent,
    status: info.event.extendedProps.status,
    latestShipTime: info.event.extendedProps.latestShipTime,
    price: info.event.extendedProps.price,
    actualMinutes: info.event.extendedProps.actualMinutes
  })
}

function eventClassNames(info) {
  if (info.event.extendedProps.isDeadlineMarker) {
    return ['deadline-marker']
  }

  if (info.event.extendedProps.status === 'DONE') {
    return ['work-order-done']
  }

  if (info.event.extendedProps.urgent) {
    return ['work-order-urgent']
  }

  return []
}

function previousRange() {
  calendarRef.value?.getApi().prev()
}

function nextRange() {
  calendarRef.value?.getApi().next()
}

function today() {
  calendarRef.value?.getApi().today()
}

function changeView(viewName) {
  calendarRef.value?.getApi().changeView(viewName)
}

async function unscheduleEvent(event) {
  try {
    await store.unscheduleWorkOrder(event.extendedProps.workOrderId)
    emit('focus-order', null)
  } catch (error) {
    store.error = error.message
  }
}

function handlePointerMove(event) {
  pointerPosition.value = {
    x: event.clientX,
    y: event.clientY
  }

  updateEventTooltipFromPointer(event)
}

async function toggleEventDone(event) {
  try {
    if (event.extendedProps.status === 'DONE') {
      await store.reopen(event.extendedProps.workOrderId)
    } else {
      await store.markAsDone(event.extendedProps.workOrderId)
    }
  } catch (error) {
    store.error = error.message
  }
}

function handleInteractionStart(info, action) {
  if (info.event.extendedProps.isDeadlineMarker) {
    return
  }

  interactionAction.value = action
  const start = info.event.start
  const end = info.event.end || addMinutes(start, info.event.extendedProps.actualMinutes)
  const latest = info.event.extendedProps.latestShipTime
    ? new Date(info.event.extendedProps.latestShipTime)
    : null
  const overlapAllowed = !overlapsActiveWorkOrder(info.event, start, end)
  const deadlineAllowed = !latest || end <= latest

  updateInteractionPreview(
    action,
    start,
    end,
    latest,
    deadlineAllowed && overlapAllowed,
    deadlineAllowed ? '與未完成工單重疊' : `超過最晚發貨時間 ${formatDateTime(latest)}`
  )
}

function resolveInteractionWindow(dropInfo, draggedEvent) {
  const start = currentView.value === 'dayGridMonth' || dropInfo.allDay
    ? dateAtWorkdayStart(dropInfo.start)
    : dropInfo.start
  const duration = draggedEvent.extendedProps.actualMinutes || diffMinutes(draggedEvent.start, draggedEvent.end)
  const end = currentView.value === 'timeGridWeek' && dropInfo.end
    ? dropInfo.end
    : addMinutes(start, duration)

  return { start, end }
}

function updateInteractionPreview(action, start, end, latest, valid, invalidReason = '') {
  if (!start || !end) {
    return
  }

  interactionPreview.value = {
    action,
    valid,
    startText: formatDateTime(start),
    endText: formatDateTime(end),
    durationText: formatDurationText(diffMinutes(start, end)),
    latestText: latest ? formatDateTime(latest) : '',
    invalidReason
  }
}

function overlapsActiveWorkOrder(draggedEvent, start, end) {
  if (draggedEvent.extendedProps.status === 'DONE') {
    return false
  }

  const draggedId = String(draggedEvent.extendedProps.workOrderId || draggedEvent.id)

  return props.events.some((event) => {
    if (event.extendedProps?.status === 'DONE') {
      return false
    }

    const eventId = String(event.extendedProps?.workOrderId || event.id)

    if (eventId === draggedId) {
      return false
    }

    const eventStart = new Date(event.start)
    const eventEnd = new Date(event.end)

    return eventStart < end && eventEnd > start
  })
}

function clearInteractionPreview() {
  interactionPreview.value = null
  interactionAction.value = '調整排程'
}

function showEventTooltip(event, pointerEvent) {
  moveEventTooltip(pointerEvent)
  const latestShipTime = event.extendedProps.latestShipTime
  const actualMinutes = event.extendedProps.actualMinutes || diffMinutes(event.start, event.end)

  eventTooltip.value = {
    title: `${event.extendedProps.urgent ? '[加急] ' : ''}${event.extendedProps.orderNo || event.title}`,
    timeRange: `${formatDateTime(event.start)} - ${formatDateTime(event.end)}`,
    durationText: formatDurationText(actualMinutes),
    statusText: event.extendedProps.status === 'DONE' ? '完成' : '未完成',
    latestText: formatDateTime(latestShipTime),
    priceText: event.extendedProps.price ? `$${event.extendedProps.price}` : ''
  }
}

function moveEventTooltip(event) {
  if (!event) {
    return
  }

  const source = event.clientX === undefined ? event.currentTarget?.getBoundingClientRect() : null
  tooltipPosition.value = source
    ? { x: source.right, y: source.top }
    : { x: event.clientX, y: event.clientY }
}

function hideEventTooltip() {
  eventTooltip.value = null
}

function updateEventTooltipFromPointer(event) {
  const eventCard = event.target?.closest?.('.calendar-event-card')

  if (!eventCard) {
    hideEventTooltip()
    return
  }

  moveEventTooltip(event)
  eventTooltip.value = {
    title: eventCard.dataset.tooltipTitle,
    timeRange: eventCard.dataset.tooltipTimeRange,
    durationText: eventCard.dataset.tooltipDuration,
    statusText: eventCard.dataset.tooltipStatus,
    latestText: eventCard.dataset.tooltipLatest,
    priceText: eventCard.dataset.tooltipPrice
  }
}

function toDateOnly(date) {
  return [
    date.getFullYear(),
    String(date.getMonth() + 1).padStart(2, '0'),
    String(date.getDate()).padStart(2, '0')
  ].join('-')
}

function addDays(date, days) {
  const next = new Date(date)
  next.setDate(next.getDate() + days)
  return next
}

function startOfWeek(date) {
  const weekStart = new Date(date)
  weekStart.setDate(weekStart.getDate() - weekStart.getDay())
  return weekStart
}

function addMinutes(date, minutes) {
  const next = new Date(date)
  next.setMinutes(next.getMinutes() + minutes)
  return next
}

function diffMinutes(start, end) {
  if (!start || !end) {
    return 0
  }

  return Math.max(0, Math.round((end.getTime() - start.getTime()) / 60000))
}

function dateAtWorkdayStart(date) {
  const start = new Date(date)
  start.setHours(9, 0, 0, 0)
  return start
}

function normalizeScheduleStart(date) {
  if (currentView.value === 'dayGridMonth') {
    return dateAtWorkdayStart(date)
  }

  return date
}

function formatDateTime(value) {
  if (!value) {
    return '-'
  }

  return value instanceof Date
    ? `${toDateOnly(value)} ${String(value.getHours()).padStart(2, '0')}:${String(value.getMinutes()).padStart(2, '0')}:${String(value.getSeconds()).padStart(2, '0')}`
    : value.replace('T', ' ').slice(0, 19)
}

function formatTime(value) {
  if (!value) {
    return '-'
  }

  return `${String(value.getHours()).padStart(2, '0')}:${String(value.getMinutes()).padStart(2, '0')}`
}

function formatDatePart(value) {
  return formatDateTime(value).split(' ')[0]
}

function formatTimePart(value) {
  return formatDateTime(value).split(' ')[1] || '-'
}

function formatDurationText(minutes) {
  const normalizedMinutes = Math.max(0, Math.round(minutes))
  const hours = Math.floor(normalizedMinutes / 60)
  const remainingMinutes = normalizedMinutes % 60
  return `${hours}小時${remainingMinutes}分鐘`
}

function formatRangeTitle(start, end) {
  const startText = toDateOnly(start)
  const endText = toDateOnly(end)
  return startText === endText ? startText : `${startText} - ${endText}`
}

function formatVisibleTitle(info) {
  if (info.view.type === 'dayGridMonth') {
    return `${info.view.currentStart.getFullYear()}-${String(info.view.currentStart.getMonth() + 1).padStart(2, '0')}`
  }

  return formatRangeTitle(info.start, addDays(info.end, -1))
}

function weekdayLabel(date) {
  return ['日', '一', '二', '三', '四', '五', '六'][date.getDay()]
}
</script>

<template>
  <section class="calendar-panel" aria-label="工單日曆">
    <header class="calendar-header">
      <div>
        <h2>{{ visibleTitle }}</h2>
        <p>{{ currentView === 'dayGridMonth' ? '月檢視拖到日期，週檢視精準調整時間' : '半小時區間，5 分鐘粒度' }}</p>
      </div>

      <div class="calendar-header-actions">
        <button class="icon-button" type="button" @click="emit('send-email')">
          <Mail :size="17" />
          發送 Email
        </button>
        <div class="view-switch" aria-label="日曆檢視">
          <button
            type="button"
            :class="{ active: currentView === 'timeGridWeek' }"
            @click="changeView('timeGridWeek')"
          >
            週
          </button>
          <button
            type="button"
            :class="{ active: currentView === 'dayGridMonth' }"
            @click="changeView('dayGridMonth')"
          >
            月
          </button>
        </div>
        <div class="calendar-nav" aria-label="日曆導覽">
          <button class="icon-only-button" type="button" aria-label="上一段" @click="previousRange">
            <ChevronLeft :size="20" />
          </button>
          <button class="today-button" type="button" @click="today">今天</button>
          <button class="icon-only-button" type="button" aria-label="下一段" @click="nextRange">
            <ChevronRight :size="20" />
          </button>
        </div>
      </div>
    </header>

    <FullCalendar ref="calendarRef" class="calendar-shell" :options="calendarOptions" />

    <div
      v-if="interactionPreview"
      class="interaction-preview"
      :class="{ invalid: !interactionPreview.valid }"
      :style="interactionPreviewStyle"
      aria-live="polite"
    >
      <strong>{{ interactionPreview.action }}</strong>
      <span>開始：{{ interactionPreview.startText }}</span>
      <span>結束：{{ interactionPreview.endText }}</span>
      <span>{{ interactionPreview.durationText }}</span>
      <span v-if="!interactionPreview.valid" class="interaction-warning">
        {{ interactionPreview.invalidReason }}
      </span>
    </div>

    <div
      v-if="eventTooltip"
      class="event-tooltip"
      :style="eventTooltipStyle"
      role="tooltip"
    >
      <strong>{{ eventTooltip.title }}</strong>
      <span>{{ eventTooltip.timeRange }}</span>
      <span>{{ eventTooltip.durationText }}</span>
      <span>{{ eventTooltip.statusText }} · 最晚 {{ eventTooltip.latestText }}</span>
      <span v-if="eventTooltip.priceText">訂單價格 {{ eventTooltip.priceText }}</span>
    </div>
  </section>
</template>
