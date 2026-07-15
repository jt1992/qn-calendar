<script setup>
import { onMounted, ref } from 'vue'
import WorkOrderImportButton from '../components/WorkOrderImportButton.vue'
import PendingWorkOrderList from '../components/PendingWorkOrderList.vue'
import WorkOrderCalendar from '../components/WorkOrderCalendar.vue'
import { useWorkOrderStore } from '../stores/workOrderStore'

const emit = defineEmits(['range-change'])
const store = useWorkOrderStore()
const focusedWorkOrder = ref(null)

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
        <WorkOrderImportButton />
      </div>

      <div v-if="store.error" class="message error-message">
        {{ store.error }}
      </div>
      <div v-else-if="store.notice" class="message success-message">
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
        @focus-order="focusWorkOrder"
      />
    </aside>

    <WorkOrderCalendar
      :events="store.calendarEvents"
      :focused-work-order="focusedWorkOrder"
      @focus-order="focusWorkOrder"
      @range-change="emit('range-change', $event)"
    />
  </section>
</template>
