import { defineStore } from 'pinia'
import {
  deleteWorkOrder as deleteWorkOrderRequest,
  deleteWorkOrderSegment,
  getCalendarWorkOrders,
  getCompletedWorkOrderStats,
  getPendingWorkOrders,
  importWorkOrders,
  markWorkOrderAsDone,
  markWorkOrderSegmentAsDone,
  pauseWorkOrderSegment,
  reopenWorkOrder,
  resumeWorkOrderSegment,
  scheduleWorkOrder,
  sendScheduleEmail,
  splitWorkOrderSegment,
  updateWorkOrderSegment,
  updateWorkOrderDuration
} from '../api/workOrders'

let errorTimer = null
let noticeTimer = null
export const ORDER_NUMBER_COPY_NOTICE = '复制订单编号成功'
const ORDER_NUMBER_COPY_NOTICE_DURATION_MS = 3000

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
    async copyOrderNumber(orderNo) {
      const value = String(orderNo)

      try {
        await navigator.clipboard.writeText(value)
      } catch {
        if (!copyTextWithFallback(value)) {
          this.setError('复制订单编号失败，请手动复制')
          return
        }
      }

      this.setNotice(ORDER_NUMBER_COPY_NOTICE, ORDER_NUMBER_COPY_NOTICE_DURATION_MS)
    },

    async importXlsx(file) {
      this.loading = true
      this.clearError()

      try {
        this.importResult = await importWorkOrders(file)
        this.setNotice(`新增 ${this.importResult.createdCount} 笔，更新 ${this.importResult.updatedCount} 笔`)
        await Promise.all([
          this.fetchPendingWorkOrders(),
          this.refreshCalendarEvents()
        ])
      } catch (error) {
        this.setError(error.message)
        throw error
      } finally {
        this.loading = false
      }
    },

    async fetchPendingWorkOrders() {
      this.pendingWorkOrders = sortPendingWorkOrders(await getPendingWorkOrders())
    },

    async deleteWorkOrder(id) {
      await deleteWorkOrderRequest(id)
      await this.fetchPendingWorkOrders()
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

    async markAsDone(id) {
      await markWorkOrderAsDone(id)
      await this.refreshCalendarEvents()
    },

    async markSegmentAsDone(segmentId) {
      await markWorkOrderSegmentAsDone(segmentId)
      await this.refreshCalendarEvents()
    },

    async pauseSegment(segmentId) {
      await pauseWorkOrderSegment(segmentId)
      await this.refreshCalendarEvents()
    },

    async resumeSegment(segmentId) {
      await resumeWorkOrderSegment(segmentId)
      await this.refreshCalendarEvents()
    },

    async reopen(id) {
      await reopenWorkOrder(id)
      await this.refreshCalendarEvents()
    },

    async sendScheduleEmail(payload) {
      await sendScheduleEmail(payload)
    },

    setError(message) {
      this.error = message
      this.notice = ''

      if (errorTimer) {
        window.clearTimeout(errorTimer)
      }

      errorTimer = window.setTimeout(() => {
        this.error = ''
        errorTimer = null
      }, 5000)
    },

    setNotice(message, durationMs = 5000) {
      this.notice = message
      this.error = ''

      if (noticeTimer) {
        window.clearTimeout(noticeTimer)
      }

      noticeTimer = window.setTimeout(() => {
        this.notice = ''
        noticeTimer = null
      }, durationMs)
    },

    clearError() {
      this.error = ''

      if (errorTimer) {
        window.clearTimeout(errorTimer)
        errorTimer = null
      }
    },

    clearMessages() {
      this.error = ''
      this.notice = ''

      if (errorTimer) {
        window.clearTimeout(errorTimer)
        errorTimer = null
      }

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
    startEditable: true,
    durationEditable: true,
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
      totalMinutes: segment.totalMinutes,
      paused: segment.paused,
      pausedMinutes: segment.pausedMinutes,
      overdue: segment.overdue,
      scheduleStartLocked: Boolean(segment.scheduleStartLocked),
      latestPausedAt: segment.latestPausedAt
    }
  }
}

function copyTextWithFallback(value) {
  const textarea = document.createElement('textarea')
  textarea.value = value
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.opacity = '0'
  document.body.append(textarea)
  textarea.select()

  try {
    return document.execCommand('copy')
  } catch {
    return false
  } finally {
    textarea.remove()
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
