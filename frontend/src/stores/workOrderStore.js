import { defineStore } from 'pinia'
import {
  deleteWorkOrderSegment,
  getCalendarWorkOrders,
  getCompletedWorkOrderStats,
  getPendingWorkOrders,
  importWorkOrders,
  markWorkOrderAsDone,
  markWorkOrderSegmentAsDone,
  reopenWorkOrder,
  scheduleWorkOrder,
  sendScheduleEmail,
  splitWorkOrderSegment,
  unscheduleWorkOrder,
  updateWorkOrderSegment,
  updateWorkOrderDuration
} from '../api/workOrders'

let noticeTimer = null

export const useWorkOrderStore = defineStore('workOrders', {
  state: () => ({
    pendingWorkOrders: [],
    calendarEvents: [],
    completedStats: [],
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
        this.setNotice(`新增 ${this.importResult.createdCount} 笔，跳过 ${this.importResult.skippedCount} 笔`)
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

    async fetchCompletedStats() {
      this.completedStats = await getCompletedWorkOrderStats()
    },

    async refreshCalendarEvents() {
      if (!this.activeRange) {
        return
      }

      await this.fetchCalendarEvents(this.activeRange.dateFrom, this.activeRange.dateTo)
    },

    async scheduleWorkOrder(id, start, end) {
      const response = await scheduleWorkOrder(id, toLocalDateTime(start), toLocalDateTime(end))
      await this.fetchPendingWorkOrders()
      await this.refreshCalendarEvents()
      return response
    },

    async updateWorkOrderSegment(segmentId, start, end) {
      const response = await updateWorkOrderSegment(segmentId, toLocalDateTime(start), toLocalDateTime(end))
      await this.refreshCalendarEvents()
      return response
    },

    async deleteWorkOrderSegment(segmentId) {
      const response = await deleteWorkOrderSegment(segmentId)
      await this.fetchPendingWorkOrders()
      await this.refreshCalendarEvents()
      return response
    },

    async splitWorkOrderSegment(segmentId, splitAt) {
      const response = await splitWorkOrderSegment(segmentId, toLocalDateTime(splitAt))
      await this.refreshCalendarEvents()
      return response
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

    async markSegmentAsDone(segmentId) {
      await markWorkOrderSegmentAsDone(segmentId)
      await this.refreshCalendarEvents()
    },

    async reopen(id) {
      await reopenWorkOrder(id)
      await this.refreshCalendarEvents()
    },

    async sendScheduleEmail(payload) {
      await sendScheduleEmail(payload)
    },

    setNotice(message) {
      this.notice = message

      if (noticeTimer) {
        window.clearTimeout(noticeTimer)
      }

      noticeTimer = window.setTimeout(() => {
        this.notice = ''
        noticeTimer = null
      }, 5000)
    },

    clearMessages() {
      this.error = ''
      this.notice = ''

      if (noticeTimer) {
        window.clearTimeout(noticeTimer)
        noticeTimer = null
      }
    }
  }
})

function toCalendarEvent(segment) {
  return {
    id: String(segment.segmentId || segment.id),
    title: `${segment.urgent ? '加急 ' : ''}${segment.orderNo}`,
    start: segment.scheduledStart,
    end: segment.scheduledEnd,
    extendedProps: {
      segmentId: segment.segmentId || segment.id,
      workOrderId: segment.workOrderId,
      orderNo: segment.orderNo,
      urgent: segment.urgent,
      status: segment.status,
      latestShipTime: segment.latestShipTime,
      price: segment.price,
      buyerNickname: segment.buyerNickname,
      remark: segment.remark,
      estimatedMinutes: segment.estimatedMinutes,
      actualMinutes: segment.actualMinutes,
      totalMinutes: segment.totalMinutes
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
