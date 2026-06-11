import { http } from './http'

export async function importWorkOrders(file) {
  const formData = new FormData()
  formData.append('file', file)

  const { data } = await http.post('/api/work-orders/import', formData, {
    headers: {
      'Content-Type': 'multipart/form-data'
    }
  })

  return data
}

export async function getPendingWorkOrders() {
  const { data } = await http.get('/api/work-orders/pending')
  return data
}

export async function getCalendarWorkOrders(dateFrom, dateTo) {
  const { data } = await http.get('/api/work-orders/calendar', {
    params: {
      dateFrom,
      dateTo
    }
  })

  return data
}

export async function getCompletedWorkOrderStats() {
  const { data } = await http.get('/api/work-orders/statistics/completed')
  return data
}

export async function scheduleWorkOrder(id, scheduledStart, scheduledEnd) {
  const { data } = await http.patch(`/api/work-orders/${id}/schedule`, {
    scheduledStart,
    scheduledEnd
  })

  return data
}

export async function updateWorkOrderSegment(segmentId, scheduledStart, scheduledEnd) {
  const { data } = await http.patch(`/api/work-orders/segments/${segmentId}`, {
    scheduledStart,
    scheduledEnd
  })

  return data
}

export async function deleteWorkOrderSegment(segmentId) {
  const { data } = await http.delete(`/api/work-orders/segments/${segmentId}`)
  return data
}

export async function splitWorkOrderSegment(segmentId, splitAt) {
  const { data } = await http.post(`/api/work-orders/segments/${segmentId}/split`, {
    splitAt
  })

  return data
}

export async function updateWorkOrderDuration(id, actualMinutes) {
  const { data } = await http.patch(`/api/work-orders/${id}/duration`, {
    actualMinutes
  })

  return data
}

export async function unscheduleWorkOrder(id) {
  const { data } = await http.patch(`/api/work-orders/${id}/unschedule`)
  return data
}

export async function markWorkOrderAsDone(id) {
  const { data } = await http.patch(`/api/work-orders/${id}/done`)
  return data
}

export async function markWorkOrderSegmentAsDone(segmentId) {
  const { data } = await http.patch(`/api/work-orders/segments/${segmentId}/done`)
  return data
}

export async function pauseWorkOrderSegment(segmentId) {
  const { data } = await http.patch(`/api/work-orders/segments/${segmentId}/pause`)
  return data
}

export async function resumeWorkOrderSegment(segmentId) {
  const { data } = await http.patch(`/api/work-orders/segments/${segmentId}/resume`)
  return data
}

export async function reopenWorkOrder(id) {
  const { data } = await http.patch(`/api/work-orders/${id}/reopen`)
  return data
}

export async function sendScheduleEmail(payload) {
  await http.post('/api/work-orders/schedule-email', payload)
}
