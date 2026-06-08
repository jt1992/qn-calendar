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

export async function scheduleWorkOrder(id, scheduledStart, scheduledEnd) {
  const { data } = await http.patch(`/api/work-orders/${id}/schedule`, {
    scheduledStart,
    scheduledEnd
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

export async function reopenWorkOrder(id) {
  const { data } = await http.patch(`/api/work-orders/${id}/reopen`)
  return data
}

export async function sendScheduleEmail(payload) {
  await http.post('/api/work-orders/schedule-email', payload)
}
