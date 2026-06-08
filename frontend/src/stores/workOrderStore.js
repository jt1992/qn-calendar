import { defineStore } from 'pinia'
import {
  getCalendarWorkOrders,
  getPendingWorkOrders,
  importWorkOrders,
  markWorkOrderAsDone,
  reopenWorkOrder,
  scheduleWorkOrder,
  sendScheduleEmail,
  unscheduleWorkOrder,
  updateWorkOrderDuration
} from '../api/workOrders'

export const useWorkOrderStore = defineStore('workOrders', {
  state: () => ({
    pendingWorkOrders: [],
    calendarEvents: [],
    importResult: null,
    activeRange: null,
    loading: false,
    error: '',
    notice: ''
  }),

  actions: {
    async importXlsx(file) {
      this.loading = true
      this.error = ''

      try {
        this.importResult = await importWorkOrders(file)
        this.notice = `新增 ${this.importResult.createdCount} 筆，跳過 ${this.importResult.skippedCount} 筆`
        await this.fetchPendingWorkOrders()
      } catch (error) {
        this.error = error.message
        throw error
      } finally {
        this.loading = false
      }
    },

    async fetchPendingWorkOrders() {
      this.pendingWorkOrders = sortPendingWorkOrders(await getPendingWorkOrders())
    },

    async fetchCalendarEvents(dateFrom, dateTo) {
      this.activeRange = { dateFrom, dateTo }
      const workOrders = await getCalendarWorkOrders(dateFrom, dateTo)
      this.calendarEvents = workOrders.map(toCalendarEvent)
    },

    async refreshCalendarEvents() {
      if (!this.activeRange) {
        return
      }

      await this.fetchCalendarEvents(this.activeRange.dateFrom, this.activeRange.dateTo)
    },

    async scheduleWorkOrder(id, start, end) {
      const workOrder = await scheduleWorkOrder(id, toLocalDateTime(start), toLocalDateTime(end))
      await this.fetchPendingWorkOrders()
      await this.refreshCalendarEvents()
      return workOrder
    },

    async updateWorkOrderDuration(id, actualMinutes) {
      const workOrder = await updateWorkOrderDuration(id, actualMinutes)
      this.pendingWorkOrders = sortPendingWorkOrders(
        this.pendingWorkOrders.map((pendingWorkOrder) =>
          pendingWorkOrder.id === workOrder.id ? workOrder : pendingWorkOrder
        )
      )
      return workOrder
    },

    async unscheduleWorkOrder(id) {
      const workOrder = await unscheduleWorkOrder(id)
      await this.fetchPendingWorkOrders()
      await this.refreshCalendarEvents()
      return workOrder
    },

    async markAsDone(id) {
      await markWorkOrderAsDone(id)
      await this.refreshCalendarEvents()
    },

    async reopen(id) {
      await reopenWorkOrder(id)
      await this.refreshCalendarEvents()
    },

    async sendScheduleEmail(payload) {
      await sendScheduleEmail(payload)
      this.notice = '排程 Email 已送出'
    },

    clearMessages() {
      this.error = ''
      this.notice = ''
    }
  }
})

function toCalendarEvent(workOrder) {
  return {
    id: String(workOrder.id),
    title: `${workOrder.urgent ? '加急 ' : ''}${workOrder.orderNo}`,
    start: workOrder.scheduledStart,
    end: workOrder.scheduledEnd,
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

function sortPendingWorkOrders(workOrders) {
  return [...workOrders].sort((left, right) => {
    const deadlineComparison = compareDateTime(left.latestShipTime, right.latestShipTime)

    if (deadlineComparison !== 0) {
      return deadlineComparison
    }

    if (left.urgent !== right.urgent) {
      return left.urgent ? -1 : 1
    }

    return compareDateTime(left.createdAt, right.createdAt)
  })
}

function compareDateTime(left, right) {
  if (!left && !right) {
    return 0
  }

  const leftTime = left ? new Date(left).getTime() : Number.POSITIVE_INFINITY
  const rightTime = right ? new Date(right).getTime() : Number.POSITIVE_INFINITY
  return leftTime - rightTime
}

function toLocalDateTime(value) {
  const date = value instanceof Date ? value : new Date(value)
  const pad = (number) => String(number).padStart(2, '0')

  return [
    date.getFullYear(),
    pad(date.getMonth() + 1),
    pad(date.getDate())
  ].join('-') + `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}
