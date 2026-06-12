<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import FullCalendar from '@fullcalendar/vue3'
import dayGridPlugin from '@fullcalendar/daygrid'
import timeGridPlugin from '@fullcalendar/timegrid'
import interactionPlugin from '@fullcalendar/interaction'
import { ChevronLeft, ChevronRight } from '@lucide/vue'
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

const emit = defineEmits(['range-change', 'focus-order'])
const store = useWorkOrderStore()
const calendarViewStorageKey = 'qn-calendar-view'
const calendarDateStorageKey = 'qn-calendar-date'
const allowPastSchedulingStorageKey = 'qn-calendar-allow-past-scheduling'
const validCalendarViews = new Set(['timeGridWeek', 'dayGridMonth'])
const initialCalendarView = getInitialCalendarView()
const initialCalendarDate = getInitialCalendarDate()
const calendarRef = ref(null)
const visibleTitle = ref('')
const currentView = ref(initialCalendarView)
const pointerPosition = ref({ x: 0, y: 0 })
const tooltipPosition = ref({ x: 0, y: 0 })
const interactionPreview = ref(null)
const interactionAction = ref('调整排程')
const activeInteraction = ref(null)
const eventTooltip = ref(null)
const ignoreNextCalendarClick = ref(false)
const localFocusedWorkOrder = ref(null)
const scheduleGranularityMinutes = 15
const allowPastScheduling = ref(getInitialAllowPastScheduling())

const effectiveFocusedWorkOrder = computed(() => props.focusedWorkOrder || localFocusedWorkOrder.value)
const focusedWorkOrderId = computed(() => {
  const focusedWorkOrder = effectiveFocusedWorkOrder.value
  return focusedWorkOrder?.id || focusedWorkOrder?.workOrderId || null
})

const calendarEvents = computed(() => {
  const focusedId = focusedWorkOrderId.value
  const events = props.events.map((event) => ({
    ...event,
    classNames: calendarEventClassNames(event, focusedId)
  }))
  const marker = deadlineMarkerEvent.value
  return marker ? [...events, marker] : events
})

const deadlineMarkerEvent = computed(() => {
  const focusedWorkOrder = effectiveFocusedWorkOrder.value

  if (currentView.value !== 'timeGridWeek' || !focusedWorkOrder?.latestShipTime) {
    return null
  }

  const start = new Date(focusedWorkOrder.latestShipTime)

  if (Number.isNaN(start.getTime())) {
    return null
  }

  return {
    id: `deadline-${focusedWorkOrder.id || focusedWorkOrder.workOrderId || 'focused'}`,
    start,
    end: addMinutes(start, scheduleGranularityMinutes),
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
  const height = 244
  const x = Math.min(tooltipPosition.value.x + 14, Math.max(14, window.innerWidth - width - 14))
  const y = Math.min(tooltipPosition.value.y + 14, Math.max(14, window.innerHeight - height - 14))

  return {
    left: `${x}px`,
    top: `${y}px`
  }
})

const calendarOptions = computed(() => ({
  plugins: [dayGridPlugin, timeGridPlugin, interactionPlugin],
  initialView: initialCalendarView,
  initialDate: initialCalendarDate,
  headerToolbar: false,
  allDaySlot: false,
  views: {
    timeGridWeek: {
      duration: { days: 7 },
      dateAlignment: 'day'
    }
  },
  slotDuration: '00:30:00',
  slotLabelInterval: '01:00:00',
  snapDuration: '00:15:00',
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
  dragRevertDuration: 0,
  eventOverlap: true,
  slotEventOverlap: false,
  eventResizableFromStart: currentView.value === 'timeGridWeek',
  height: '100%',
  events: calendarEvents.value,
  dayHeaderContent,
  eventContent,
  datesSet: handleDatesSet,
  eventAllow,
  eventDragStart: (info) => handleInteractionStart(info, '拖拽排程'),
  eventDragStop: handleEventDragStop,
  eventReceive: handleEventReceive,
  eventDrop: handleEventMove,
  eventResizeStart: (info) => handleInteractionStart(info, '调整工时'),
  eventResizeStop: handleInteractionStop,
  eventResize: handleEventMove,
  eventClick: handleEventClick,
  eventDidMount: bindEventTooltip,
  eventClassNames: (info) => eventClassNames(info, focusedWorkOrderId.value)
}))

onMounted(() => {
  window.addEventListener('pointermove', handlePointerMove, { passive: true })
  window.addEventListener('pointerup', clearInteractionPreview)
  window.addEventListener('pointerdown', handleGlobalFocusPointerDown)
  window.addEventListener('click', handleGlobalFocusClick)
})

onBeforeUnmount(() => {
  window.removeEventListener('pointermove', handlePointerMove)
  window.removeEventListener('pointerup', clearInteractionPreview)
  window.removeEventListener('pointerdown', handleGlobalFocusPointerDown)
  window.removeEventListener('click', handleGlobalFocusClick)
})

watch(
  () => props.focusedWorkOrder,
  (focusedWorkOrder) => {
    localFocusedWorkOrder.value = focusedWorkOrder
    refreshCalendarDecorations()
  }
)

function eventAllow(dropInfo, draggedEvent) {
  const latestShipTime = draggedEvent.extendedProps.latestShipTime
  const { start, end } = resolveInteractionWindow(dropInfo, draggedEvent)
  const latest = latestShipTime ? new Date(latestShipTime) : null
  const scheduleWindow = resolveNonOverlappingWindow(
    draggedEvent,
    start,
    end,
    latest,
    minScheduleStart(),
    interactionOriginalStart(draggedEvent)
  )

  updateInteractionPreview(
    interactionAction.value,
    scheduleWindow.start,
    scheduleWindow.end,
    latest,
    scheduleWindow.valid,
    scheduleWindow.invalidReason
  )
  return scheduleWindow.valid
}

async function handleDatesSet(info) {
  const dateFrom = toDateOnly(info.start)
  const dateTo = toDateOnly(addDays(info.end, -1))
  const focusWeekStart = startOfWeek(info.view.calendar.getDate())
  const focusWeekEnd = addDays(focusWeekStart, 6)
  visibleTitle.value = formatVisibleTitle(info)
  currentView.value = info.view.type
  window.localStorage.setItem(calendarViewStorageKey, info.view.type)
  window.localStorage.setItem(calendarDateStorageKey, toDateOnly(resolveCalendarStorageDate(info)))
  emit('range-change', {
    dateFrom: info.view.type === 'timeGridWeek' ? dateFrom : toDateOnly(focusWeekStart),
    dateTo: info.view.type === 'timeGridWeek' ? dateTo : toDateOnly(focusWeekEnd),
    visibleDateFrom: dateFrom,
    visibleDateTo: dateTo,
    viewType: info.view.type
  })

  try {
    await store.fetchCalendarEvents(dateFrom, dateTo)
  } catch (error) {
    store.setError(error.message)
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
  const splitButton = document.createElement('button')
  const pauseButton = document.createElement('button')
  const latestShipTime = info.event.extendedProps.latestShipTime
  const isDone = info.event.extendedProps.status === 'DONE'
  const isPaused = Boolean(info.event.extendedProps.paused)
  const segmentMinutes = info.event.extendedProps.actualMinutes || diffMinutes(info.event.start, info.event.end)
  const totalMinutes = info.event.extendedProps.totalMinutes || segmentMinutes
  const pausedMinutes = info.event.extendedProps.pausedMinutes || 0

  root.className = 'calendar-event-card'
  root.dataset.workOrderId = String(info.event.extendedProps.workOrderId || '')
  root.dataset.orderNo = info.event.extendedProps.orderNo || info.event.title || ''
  root.dataset.urgent = String(Boolean(info.event.extendedProps.urgent))
  root.dataset.status = info.event.extendedProps.status || ''
  root.dataset.paused = String(isPaused)
  root.dataset.overdue = String(Boolean(info.event.extendedProps.overdue))
  root.dataset.latestShipTime = info.event.extendedProps.latestShipTime || ''
  root.dataset.price = String(info.event.extendedProps.price || '')
  root.dataset.remark = info.event.extendedProps.remark || ''
  root.dataset.actualMinutes = String(info.event.extendedProps.totalMinutes || info.event.extendedProps.actualMinutes || '')
  root.dataset.totalMinutes = String(info.event.extendedProps.totalMinutes || '')
  root.dataset.tooltipTitle = `${info.event.extendedProps.urgent ? '[加急] ' : ''}${info.event.extendedProps.orderNo || info.event.title}`
  root.dataset.tooltipTimeRange = `${formatDateTime(info.event.start)} - ${formatDateTime(info.event.end)}`
  root.dataset.tooltipDuration = pausedMinutes > 0
    ? `总排程 ${formatDurationText(totalMinutes)}，暂停 ${formatDurationText(pausedMinutes)}`
    : `总排程 ${formatDurationText(totalMinutes)}`
  root.dataset.tooltipStatus = isDone ? '完成' : isPaused ? '暂停中' : '未完成'
  root.dataset.tooltipLatest = formatDateTime(latestShipTime)
  root.dataset.tooltipPrice = info.event.extendedProps.price ? formatMoney(info.event.extendedProps.price) : ''
  root.dataset.tooltipRemark = formatRemarkText(info.event.extendedProps.remark)
  title.className = 'calendar-event-title'
  title.textContent = info.event.extendedProps.orderNo || info.event.title
  actions.className = 'calendar-event-actions'

  if (!isDone) {
    doneButton.className = 'event-complete-button'
    doneButton.type = 'button'
    doneButton.textContent = '✓'
    doneButton.setAttribute('aria-label', '标记完成')
    doneButton.addEventListener('click', async (event) => {
      event.preventDefault()
      event.stopPropagation()
      await toggleEventDone(info.event)
    })

    splitButton.className = 'event-split-button'
    splitButton.type = 'button'
    splitButton.textContent = '拆'
    splitButton.setAttribute('aria-label', '拆分片段')
    splitButton.addEventListener('click', async (event) => {
      event.preventDefault()
      event.stopPropagation()
      await splitEvent(info.event)
    })

    actions.append(doneButton, splitButton)
  }

  if (canShowPauseButton(info.event)) {
    pauseButton.className = `event-pause-toggle${isPaused ? ' paused' : ''}`
    pauseButton.type = 'button'
    pauseButton.textContent = isPaused ? '▶' : '⏸'
    pauseButton.setAttribute('aria-label', isPaused ? '继续计时' : '暂停计时')
    pauseButton.addEventListener('click', async (event) => {
      event.preventDefault()
      event.stopPropagation()
      await toggleEventPause(info.event)
    })
  }

  timeRange.className = 'calendar-event-time'
  timeRange.textContent = `${formatTime(info.event.start)}~${formatTime(info.event.end)}`
  duration.className = 'calendar-event-duration'
  duration.textContent = `总 ${formatDurationText(totalMinutes)}`
  deadlineLabel.className = 'calendar-event-deadline-label'
  deadlineLabel.textContent = '最晚发货：'
  deadlineDate.className = 'calendar-event-deadline-date'
  deadlineDate.textContent = formatDatePart(latestShipTime)
  deadlineTime.className = 'calendar-event-deadline-time'
  deadlineTime.textContent = formatTimePart(latestShipTime)

  if (actions.childElementCount > 0) {
    root.append(actions)
  }

  if (pauseButton.parentElement === null && pauseButton.textContent) {
    root.append(pauseButton)
  }

  root.append(title, timeRange, duration, deadlineLabel, deadlineDate, deadlineTime)
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
    const start = normalizeScheduleBoundary(normalizeScheduleStart(info.event.start))
    const end = normalizeScheduleBoundary(addMinutes(start, info.event.extendedProps.actualMinutes))
    const latest = info.event.extendedProps.latestShipTime
      ? new Date(info.event.extendedProps.latestShipTime)
      : null
    const scheduleWindow = resolveNonOverlappingWindow(info.event, start, end, latest, minScheduleStart())

    if (!scheduleWindow.valid) {
      throw new Error(scheduleWindow.invalidReason || '排程时间不可用')
    }

    await store.scheduleWorkOrder(info.event.extendedProps.workOrderId, scheduleWindow.start, scheduleWindow.end)
    clearInteractionPreview()
  } catch (error) {
    store.setError(error.message)
    info.revert?.()
    info.event.remove()
    clearInteractionPreview()
  }
}

async function handleEventMove(info) {
  try {
    const start = normalizeScheduleBoundary(normalizeScheduleStart(info.event.start))
    const rawEnd = currentView.value === 'timeGridWeek'
      ? info.event.end || addMinutes(start, info.event.extendedProps.actualMinutes)
      : addMinutes(start, info.event.extendedProps.actualMinutes)
    const end = normalizeScheduleBoundary(rawEnd)
    const latest = info.event.extendedProps.latestShipTime
      ? new Date(info.event.extendedProps.latestShipTime)
      : null
    const scheduleWindow = resolveNonOverlappingWindow(
      info.event,
      start,
      end,
      latest,
      minScheduleStart(),
      info.oldEvent?.start || interactionOriginalStart(info.event)
    )

    if (!scheduleWindow.valid) {
      throw new Error(scheduleWindow.invalidReason || '排程时间不可用')
    }

    await store.updateWorkOrderSegment(
      info.event.extendedProps.segmentId,
      scheduleWindow.start,
      scheduleWindow.end
    )
    clearInteractionPreview()
  } catch (error) {
    store.setError(error.message)
    info.revert()
    clearInteractionPreview()
  }
}

function handleEventClick(info) {
  if (info.event.extendedProps.isDeadlineMarker) {
    return
  }

  focusEvent(info.event)
}

function focusEvent(event) {
  if (event.extendedProps.isDeadlineMarker) {
    return
  }

  setFocusedWorkOrder({
    id: event.extendedProps.workOrderId,
    orderNo: event.extendedProps.orderNo,
    urgent: event.extendedProps.urgent,
    status: event.extendedProps.status,
    latestShipTime: event.extendedProps.latestShipTime,
    price: event.extendedProps.price,
    remark: event.extendedProps.remark,
    actualMinutes: event.extendedProps.totalMinutes || event.extendedProps.actualMinutes,
    totalMinutes: event.extendedProps.totalMinutes,
    paused: event.extendedProps.paused,
    overdue: event.extendedProps.overdue
  })
}

function focusInteractionEvent(event) {
  focusEvent(event)
}

function handleCalendarBackgroundClick(event) {
  if (ignoreNextCalendarClick.value) {
    ignoreNextCalendarClick.value = false
    return
  }

  if (event.target?.closest?.('.fc-event, .fc-event-mirror, .fc-event-resizer')) {
    return
  }

  clearFocusedWorkOrder()
}

function handleGlobalFocusPointerDown(event) {
  const target = event.target

  if (!(target instanceof Element)) {
    return
  }

  if (target.closest('.fc-event, .fc-event-mirror, .fc-event-resizer, .pending-order-card')) {
    return
  }

  clearFocusedWorkOrder()
}

function handleGlobalFocusClick(event) {
  const target = event.target

  if (!(target instanceof Element)) {
    return
  }

  const eventElement = target.closest('.fc-event')

  if (eventElement && !eventElement.classList.contains('deadline-marker')) {
    focusEventElement(eventElement)
  }
}

function focusEventElement(eventElement) {
  const eventCard = eventElement.querySelector('.calendar-event-card')

  if (!eventCard?.dataset.workOrderId) {
    return
  }

  setFocusedWorkOrder({
    id: eventCard.dataset.workOrderId,
    orderNo: eventCard.dataset.orderNo,
    urgent: eventCard.dataset.urgent === 'true',
    status: eventCard.dataset.status,
    latestShipTime: eventCard.dataset.latestShipTime,
    price: eventCard.dataset.price,
    remark: eventCard.dataset.remark,
    actualMinutes: numberFromDataset(eventCard.dataset.actualMinutes),
    totalMinutes: numberFromDataset(eventCard.dataset.totalMinutes)
  })
}

function setFocusedWorkOrder(workOrder) {
  localFocusedWorkOrder.value = workOrder
  emit('focus-order', workOrder)
  refreshCalendarDecorations()
}

function clearFocusedWorkOrder() {
  localFocusedWorkOrder.value = null
  emit('focus-order', null)
  refreshCalendarDecorations()
}

function refreshCalendarDecorations() {
  nextTick(() => {
    const calendarApi = calendarRef.value?.getApi()

    if (!calendarApi) {
      return
    }

    for (const calendarEvent of calendarApi.getEvents()) {
      if (calendarEvent.extendedProps.isDeadlineMarker) {
        calendarEvent.remove()
      } else {
        calendarEvent.setProp(
          'classNames',
          calendarEventClassNamesFromProps(calendarEvent.extendedProps, calendarEvent.classNames, focusedWorkOrderId.value)
        )
      }
    }

    const marker = deadlineMarkerEvent.value

    if (marker) {
      calendarApi.addEvent(marker)
    }
  })
}

function calendarEventClassNames(event, focusedId) {
  return calendarEventClassNamesFromProps(event.extendedProps || {}, event.classNames, focusedId)
}

function calendarEventClassNamesFromProps(extendedProps, existingClassNames, focusedId) {
  const managedClassNames = new Set([
    'work-order-selected',
    'work-order-done',
    'work-order-urgent',
    'work-order-overdue',
    'work-order-paused'
  ])
  const classNames = new Set(
    (Array.isArray(existingClassNames) ? existingClassNames : []).filter(
      (className) => !managedClassNames.has(className)
    )
  )

  if (focusedId && String(focusedId) === String(extendedProps.workOrderId)) {
    classNames.add('work-order-selected')
  }

  if (extendedProps.status === 'DONE') {
    classNames.add('work-order-done')
  }

  if (extendedProps.urgent) {
    classNames.add('work-order-urgent')
  }

  if (extendedProps.overdue) {
    classNames.add('work-order-overdue')
  }

  if (extendedProps.paused) {
    classNames.add('work-order-paused')
  }

  return [...classNames]
}

function numberFromDataset(value) {
  const number = Number(value)
  return Number.isFinite(number) ? number : undefined
}

function eventClassNames(info, focusedId) {
  if (info.event.extendedProps.isDeadlineMarker) {
    return ['deadline-marker']
  }

  const classNames = []

  if (focusedId && String(focusedId) === String(info.event.extendedProps.workOrderId)) {
    classNames.push('work-order-selected')
  }

  if (info.event.extendedProps.status === 'DONE') {
    classNames.push('work-order-done')
  }

  if (info.event.extendedProps.urgent) {
    classNames.push('work-order-urgent')
  }

  if (info.event.extendedProps.overdue) {
    classNames.push('work-order-overdue')
  }

  if (info.event.extendedProps.paused) {
    classNames.push('work-order-paused')
  }

  return classNames
}

function previousRange() {
  const calendarApi = calendarRef.value?.getApi()

  if (!calendarApi) {
    return
  }

  if (!allowPastScheduling.value && currentView.value === 'timeGridWeek') {
    const previousStart = addDays(calendarApi.view.currentStart, -7)

    if (previousStart < todayStart()) {
      calendarApi.gotoDate(todayStart())
      return
    }
  }

  calendarApi.prev()
}

function nextRange() {
  calendarRef.value?.getApi().next()
}

function today() {
  calendarRef.value?.getApi().today()
}

function changeView(viewName) {
  if (validCalendarViews.has(viewName)) {
    window.localStorage.setItem(calendarViewStorageKey, viewName)
  }

  calendarRef.value?.getApi().changeView(viewName)
}

function getInitialCalendarView() {
  const storedView = window.localStorage.getItem(calendarViewStorageKey)
  return validCalendarViews.has(storedView) ? storedView : 'timeGridWeek'
}

function getInitialCalendarDate() {
  const storedDate = parseDateOnly(window.localStorage.getItem(calendarDateStorageKey))
  return storedDate || todayStart()
}

function getInitialAllowPastScheduling() {
  return window.localStorage.getItem(allowPastSchedulingStorageKey) === 'true'
}

function toggleAllowPastScheduling(event) {
  allowPastScheduling.value = event.target.checked
  window.localStorage.setItem(allowPastSchedulingStorageKey, String(allowPastScheduling.value))
}

async function unscheduleEvent(event, options = {}) {
  const { removeImmediately = false } = options

  try {
    if (removeImmediately) {
      event.remove()
      hideEventTooltip()
    }

    const response = await store.deleteWorkOrderSegment(event.extendedProps.segmentId)

    if (response.segments.length === 0) {
      emit('focus-order', null)
    }
  } catch (error) {
    store.setError(error.message)
    await store.refreshCalendarEvents()
  }
}

async function splitEvent(event) {
  const splitAt = resolveSplitAt(event)

  if (!splitAt) {
    store.setError('片段至少 30 分钟才能拆分')
    return
  }

  try {
    await store.splitWorkOrderSegment(event.extendedProps.segmentId, splitAt)
  } catch (error) {
    store.setError(error.message)
  }
}

function handleEventDragStop(info) {
  if (
    !info.event.extendedProps.isDeadlineMarker
    && info.event.extendedProps.segmentId
    && isPointerOutsideCalendar(info.jsEvent)
  ) {
    unscheduleEvent(info.event, { removeImmediately: true })
  }

  handleInteractionStop()
}

function handleInteractionStop() {
  ignoreNextCalendarClick.value = true
  activeInteraction.value = null
  window.setTimeout(() => {
    ignoreNextCalendarClick.value = false
  }, 150)
  clearInteractionPreview()
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
    if (event.extendedProps.status !== 'DONE') {
      await store.markSegmentAsDone(event.extendedProps.segmentId)
    }
  } catch (error) {
    store.setError(error.message)
  }
}

async function toggleEventPause(event) {
  try {
    if (event.extendedProps.paused) {
      await store.resumeSegment(event.extendedProps.segmentId)
    } else {
      await store.pauseSegment(event.extendedProps.segmentId)
    }
  } catch (error) {
    store.setError(error.message)
  }
}

function canShowPauseButton(event) {
  if (event.extendedProps.status === 'DONE' || !event.start || !event.extendedProps.segmentId) {
    return false
  }

  const now = new Date()
  return isSameDate(event.start, now) && now >= event.start
}

function handleInteractionStart(info, action) {
  if (info.event.extendedProps.isDeadlineMarker) {
    return
  }

  focusInteractionEvent(info.event)
  interactionAction.value = action
  activeInteraction.value = {
    segmentId: String(info.event.extendedProps.segmentId || ''),
    start: info.event.start ? new Date(info.event.start) : null
  }
  const start = normalizeScheduleBoundary(info.event.start)
  const end = normalizeScheduleBoundary(info.event.end || addMinutes(start, info.event.extendedProps.actualMinutes))
  const latest = info.event.extendedProps.latestShipTime
    ? new Date(info.event.extendedProps.latestShipTime)
    : null
  const scheduleWindow = resolveNonOverlappingWindow(info.event, start, end, latest, minScheduleStart(), info.event.start)

  updateInteractionPreview(
    action,
    scheduleWindow.start,
    scheduleWindow.end,
    latest,
    scheduleWindow.valid,
    scheduleWindow.invalidReason
  )
}

function resolveInteractionWindow(dropInfo, draggedEvent) {
  const start = normalizeScheduleBoundary(currentView.value === 'dayGridMonth' || dropInfo.allDay
    ? dateAtWorkdayStart(dropInfo.start)
    : dropInfo.start)
  const duration = draggedEvent.extendedProps.actualMinutes || diffMinutes(draggedEvent.start, draggedEvent.end)
  const end = normalizeScheduleBoundary(currentView.value === 'timeGridWeek' && dropInfo.end
    ? dropInfo.end
    : addMinutes(start, duration))

  return { start, end }
}

function resolveSplitAt(event) {
  const minutes = diffMinutes(event.start, event.end)

  if (minutes < scheduleGranularityMinutes * 2) {
    return null
  }

  const roundedHalf = Math.round((minutes / 2) / scheduleGranularityMinutes) * scheduleGranularityMinutes
  const offsetMinutes = Math.min(
    minutes - scheduleGranularityMinutes,
    Math.max(scheduleGranularityMinutes, roundedHalf)
  )
  return addMinutes(event.start, offsetMinutes)
}

function resolveNonOverlappingWindow(draggedEvent, start, end, latest, minStart, originalStart = null) {
  const duration = diffMinutes(start, end)
  const directWindow = { start, end }

  if (duration <= 0) {
    return {
      ...directWindow,
      valid: false,
      invalidReason: '排程结束时间必须晚于开始时间'
    }
  }

  if (getDifferentWorkOrderOverlaps(draggedEvent, start, end).length === 0) {
    return withScheduleWindowValidation(draggedEvent, directWindow, latest, minStart, originalStart)
  }

  const directValidation = withScheduleWindowValidation(draggedEvent, directWindow, latest, minStart, originalStart)

  if (!directValidation.valid) {
    return directValidation
  }

  const candidates = [
    buildAdjacentWindow(draggedEvent, start, duration, 'before'),
    buildAdjacentWindow(draggedEvent, start, duration, 'after')
  ]
    .filter(Boolean)
    .map((candidate) => withScheduleWindowValidation(draggedEvent, candidate, latest, minStart, originalStart))
    .filter((candidate) => candidate.valid)
    .sort((left, right) => {
      const leftShift = Math.abs(left.start.getTime() - start.getTime())
      const rightShift = Math.abs(right.start.getTime() - start.getTime())
      return leftShift - rightShift
    })

  if (candidates.length > 0) {
    return {
      ...candidates[0],
      invalidReason: '已贴齐其他工单'
    }
  }

  return {
    ...directWindow,
    valid: false,
    invalidReason: '找不到可贴齐的空档'
  }
}

function buildAdjacentWindow(draggedEvent, desiredStart, duration, direction) {
  let candidateStart = new Date(desiredStart)
  let candidateEnd = addMinutes(candidateStart, duration)

  for (let attempt = 0; attempt < props.events.length + 1; attempt++) {
    const overlaps = getDifferentWorkOrderOverlaps(draggedEvent, candidateStart, candidateEnd)

    if (overlaps.length === 0) {
      return { start: candidateStart, end: candidateEnd }
    }

    if (direction === 'after') {
      candidateStart = new Date(Math.max(...overlaps.map((event) => new Date(event.end).getTime())))
      candidateEnd = addMinutes(candidateStart, duration)
    } else {
      candidateEnd = new Date(Math.min(...overlaps.map((event) => new Date(event.start).getTime())))
      candidateStart = addMinutes(candidateEnd, -duration)
    }
  }

  return null
}

function withScheduleWindowValidation(draggedEvent, scheduleWindow, latest, minStart, originalStart) {
  const lockedValidation = validateLockedScheduleWindow(draggedEvent, scheduleWindow, originalStart)

  if (!lockedValidation.valid) {
    return {
      ...scheduleWindow,
      valid: false,
      invalidReason: lockedValidation.invalidReason
    }
  }

  const minStartAllowed = !minStart || scheduleWindow.start >= minStart
  const deadlineAllowed = !latest || scheduleWindow.end <= latest

  return {
    ...scheduleWindow,
    valid: minStartAllowed && deadlineAllowed,
    invalidReason: !minStartAllowed
      ? '不可排到今天以前'
      : deadlineAllowed ? '' : `超过最晚发货时间 ${formatDateTime(latest)}`
  }
}

function validateLockedScheduleWindow(draggedEvent, scheduleWindow, originalStart) {
  if (!draggedEvent.extendedProps.scheduleStartLocked) {
    return { valid: true, invalidReason: '' }
  }

  const lockedStart = originalStart ? normalizeScheduleBoundary(originalStart) : null

  if (lockedStart && scheduleWindow.start.getTime() !== lockedStart.getTime()) {
    return {
      valid: false,
      invalidReason: '已开始计时的工单不可调整开始时间'
    }
  }

  const minEnd = minLockedScheduleEnd(draggedEvent)
  if (minEnd && scheduleWindow.end < minEnd) {
    return {
      valid: false,
      invalidReason: '排程结束时间不可早于最后暂停时间'
    }
  }

  return { valid: true, invalidReason: '' }
}

function minLockedScheduleEnd(draggedEvent) {
  if (!draggedEvent.extendedProps.latestPausedAt) {
    return null
  }

  const latestPausedAt = new Date(draggedEvent.extendedProps.latestPausedAt)
  return Number.isNaN(latestPausedAt.getTime()) ? null : normalizeScheduleBoundary(latestPausedAt)
}

function interactionOriginalStart(event) {
  const segmentId = String(event.extendedProps.segmentId || '')

  if (!activeInteraction.value?.start || !segmentId || activeInteraction.value.segmentId !== segmentId) {
    return event.start || null
  }

  return activeInteraction.value.start
}

function getDifferentWorkOrderOverlaps(draggedEvent, start, end) {
  const draggedWorkOrderId = draggedEvent.extendedProps.workOrderId

  if (!draggedWorkOrderId) {
    return []
  }

  const draggedSegmentId = draggedEvent.extendedProps.segmentId

  return props.events.filter((event) => {
    if (event.extendedProps?.isDeadlineMarker) {
      return false
    }

    if (String(event.extendedProps?.workOrderId) === String(draggedWorkOrderId)) {
      return false
    }

    if (draggedSegmentId && String(event.extendedProps?.segmentId) === String(draggedSegmentId)) {
      return false
    }

    const eventStart = new Date(event.start)
    const eventEnd = new Date(event.end)

    return eventStart < end && eventEnd > start
  })
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

function clearInteractionPreview() {
  interactionPreview.value = null
  interactionAction.value = '调整排程'
}

function showEventTooltip(event, pointerEvent) {
  moveEventTooltip(pointerEvent)
  const latestShipTime = event.extendedProps.latestShipTime
  const segmentMinutes = event.extendedProps.actualMinutes || diffMinutes(event.start, event.end)
  const totalMinutes = event.extendedProps.totalMinutes || segmentMinutes
  const pausedMinutes = event.extendedProps.pausedMinutes || 0

  eventTooltip.value = {
    title: `${event.extendedProps.urgent ? '[加急] ' : ''}${event.extendedProps.orderNo || event.title}`,
    timeRange: `${formatDateTime(event.start)} - ${formatDateTime(event.end)}`,
    durationText: pausedMinutes > 0
      ? `总排程 ${formatDurationText(totalMinutes)}，暂停 ${formatDurationText(pausedMinutes)}`
      : `总排程 ${formatDurationText(totalMinutes)}`,
    statusText: event.extendedProps.status === 'DONE' ? '完成' : event.extendedProps.paused ? '暂停中' : '未完成',
    latestText: formatDateTime(latestShipTime),
    priceText: event.extendedProps.price ? formatMoney(event.extendedProps.price) : '',
    remarkText: formatRemarkText(event.extendedProps.remark)
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
    priceText: eventCard.dataset.tooltipPrice,
    remarkText: eventCard.dataset.tooltipRemark
  }
}

function isPointerOutsideCalendar(event) {
  const element = calendarRef.value?.$el

  if (!event || !element) {
    return false
  }

  const rect = element.getBoundingClientRect()
  return event.clientX < rect.left
    || event.clientX > rect.right
    || event.clientY < rect.top
    || event.clientY > rect.bottom
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

function todayStart() {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  return today
}

function isSameDate(left, right) {
  return left.getFullYear() === right.getFullYear()
    && left.getMonth() === right.getMonth()
    && left.getDate() === right.getDate()
}

function parseDateOnly(value) {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    return null
  }

  const [year, month, day] = value.split('-').map(Number)
  const date = new Date(year, month - 1, day)

  return date.getFullYear() === year
    && date.getMonth() === month - 1
    && date.getDate() === day
    ? date
    : null
}

function resolveCalendarStorageDate(info) {
  return info.view.currentStart || info.start || info.view.calendar.getDate()
}

function minScheduleStart() {
  return allowPastScheduling.value ? null : todayStart()
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

function normalizeScheduleBoundary(date) {
  const next = new Date(date)
  next.setSeconds(0, 0)

  const remainder = next.getMinutes() % scheduleGranularityMinutes
  if (remainder !== 0) {
    next.setMinutes(next.getMinutes() + scheduleGranularityMinutes - remainder)
  }

  return next
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
  return `${hours}小时${remainingMinutes}分钟`
}

function formatMoney(value) {
  return `¥${Number(value || 0).toLocaleString('zh-CN', {
    minimumFractionDigits: 0,
    maximumFractionDigits: 2
  })}`
}

function formatRemarkText(value) {
  return value && value.trim() ? value : '无任何备注'
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
  <section class="calendar-panel" aria-label="工单日历">
    <header class="calendar-header">
      <div class="calendar-title-group">
        <h2>{{ visibleTitle }}</h2>
        <p>{{ currentView === 'dayGridMonth' ? '月视图拖到日期，周视图精准调整时间' : '半小时区间，15 分钟粒度' }}</p>
      </div>

      <div class="calendar-header-actions">
        <label class="schedule-toggle" title="测试用：允许排程到今天以前">
          <input
            type="checkbox"
            :checked="allowPastScheduling"
            @change="toggleAllowPastScheduling"
          />
          <span>允许过去</span>
        </label>
        <div class="view-switch" aria-label="日历视图">
          <button
            type="button"
            :class="{ active: currentView === 'timeGridWeek' }"
            @click="changeView('timeGridWeek')"
          >
            周
          </button>
          <button
            type="button"
            :class="{ active: currentView === 'dayGridMonth' }"
            @click="changeView('dayGridMonth')"
          >
            月
          </button>
        </div>
        <div class="calendar-nav" aria-label="日历导航">
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

    <div class="calendar-click-area" @click="handleCalendarBackgroundClick">
      <FullCalendar ref="calendarRef" class="calendar-shell" :options="calendarOptions" />
    </div>

    <div
      v-if="interactionPreview"
      class="interaction-preview"
      :class="{ invalid: !interactionPreview.valid }"
      :style="interactionPreviewStyle"
      aria-live="polite"
    >
      <strong>{{ interactionPreview.action }}</strong>
      <span>开始：{{ interactionPreview.startText }}</span>
      <span>结束：{{ interactionPreview.endText }}</span>
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
      <span v-if="eventTooltip.priceText">订单价格 {{ eventTooltip.priceText }}</span>
      <span class="event-tooltip-remark">订单备注：{{ eventTooltip.remarkText }}</span>
    </div>
  </section>
</template>
