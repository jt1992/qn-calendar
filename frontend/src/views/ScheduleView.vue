<script setup>
import { onMounted, ref } from 'vue'
import WorkOrderImportButton from '../components/WorkOrderImportButton.vue'
import ManualWorkOrderDialog from '../components/ManualWorkOrderDialog.vue'
import PendingWorkOrderList from '../components/PendingWorkOrderList.vue'
import WorkOrderCalendar from '../components/WorkOrderCalendar.vue'
import { ORDER_NUMBER_COPY_NOTICE, useWorkOrderStore } from '../stores/workOrderStore'

const emit = defineEmits(['range-change'])
const store = useWorkOrderStore()
const focusedWorkOrder = ref(null)
const manualWorkOrderDialogOpen = ref(false)

onMounted(async () => {
  try {
    await store.fetchPendingWorkOrders()
  } catch (error) {
    store.setError(error.message)
  }
})

function focusWorkOrder(workOrder) {
  focusedWorkOrder.value = workOrder
}

function clearFocusedWorkOrder() {
  focusedWorkOrder.value = null
}

function clearDeletedWorkOrderFocus(workOrderId) {
  const focusedId = focusedWorkOrder.value?.id || focusedWorkOrder.value?.workOrderId

  if (focusedId && String(focusedId) === String(workOrderId)) {
    clearFocusedWorkOrder()
  }
}

function clearFocusOnBackground(event) {
  if (event.target?.closest?.('.pending-order-card, .fc-event, .fc-event-mirror, .fc-event-resizer')) {
    return
  }

  focusedWorkOrder.value = null
}
</script>

<template>
  <section class="schedule-layout" aria-label="待排工单" @pointerdown="clearFocusOnBackground">
    <aside class="side-panel">
      <div class="side-heading">
        <WorkOrderImportButton @imported="clearFocusedWorkOrder" />
      </div>

      <div v-if="store.error" class="message error-message" role="alert">
        {{ store.error }}
      </div>
      <div
        v-else-if="store.notice && store.notice !== ORDER_NUMBER_COPY_NOTICE"
        class="message"
        :class="store.noticeTone === 'danger' ? 'danger-message' : 'success-message'"
        role="status"
      >
        {{ store.notice }}
      </div>

      <section v-if="store.importResult?.errors?.length" class="import-errors">
        <h2>导入错误</h2>
        <ul>
          <li v-for="error in store.importResult.errors" :key="`${error.row}-${error.message}`">
            第 {{ error.row }} 列：{{ error.message }}
          </li>
        </ul>
      </section>

      <PendingWorkOrderList
        :work-orders="store.pendingWorkOrders"
        @add-work-order="manualWorkOrderDialogOpen = true"
        @focus-order="focusWorkOrder"
        @work-order-deleted="clearDeletedWorkOrderFocus"
      />
    </aside>

    <WorkOrderCalendar
      :events="store.calendarEvents"
      :focused-work-order="focusedWorkOrder"
      @focus-order="focusWorkOrder"
      @range-change="emit('range-change', $event)"
    />

    <ManualWorkOrderDialog
      :open="manualWorkOrderDialogOpen"
      @close="manualWorkOrderDialogOpen = false"
      @created="focusWorkOrder"
    />
  </section>
</template>
