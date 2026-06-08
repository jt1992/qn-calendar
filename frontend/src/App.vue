<script setup>
import { onMounted, ref } from 'vue'
import { Moon, Sun } from '@lucide/vue'
import WorkOrderImportButton from './components/WorkOrderImportButton.vue'
import PendingWorkOrderList from './components/PendingWorkOrderList.vue'
import WorkOrderCalendar from './components/WorkOrderCalendar.vue'
import ScheduleEmailDialog from './components/ScheduleEmailDialog.vue'
import { useWorkOrderStore } from './stores/workOrderStore'

const store = useWorkOrderStore()
const emailDialogOpen = ref(false)
const themeMode = ref(window.localStorage.getItem('qn-calendar-theme') || 'dark')
const focusedWorkOrder = ref(null)
const emailDateRange = ref({
  dateFrom: '',
  dateTo: ''
})

onMounted(async () => {
  try {
    await store.fetchPendingWorkOrders()
  } catch (error) {
    store.error = error.message
  }
})

function toggleTheme() {
  themeMode.value = themeMode.value === 'dark' ? 'light' : 'dark'
  window.localStorage.setItem('qn-calendar-theme', themeMode.value)
}

function updateEmailDateRange(range) {
  emailDateRange.value = {
    dateFrom: range.dateFrom,
    dateTo: range.dateTo
  }
}

function focusWorkOrder(workOrder) {
  focusedWorkOrder.value = workOrder
}
</script>

<template>
  <main class="app-shell" :data-theme="themeMode">
    <aside class="side-panel" aria-label="待排工單">
      <div class="side-heading">
        <div>
          <h1>待排工單</h1>
          <p>上傳 XLSX 後拖曳排程</p>
        </div>
        <button class="icon-only-button" type="button" aria-label="切換深淺色模式" @click="toggleTheme">
          <Sun v-if="themeMode === 'dark'" :size="18" />
          <Moon v-else :size="18" />
        </button>
      </div>

      <WorkOrderImportButton />

      <div v-if="store.error" class="message error-message">
        {{ store.error }}
      </div>
      <div v-else-if="store.notice" class="message success-message">
        {{ store.notice }}
      </div>

      <section v-if="store.importResult?.errors?.length" class="import-errors">
        <h2>匯入錯誤</h2>
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
      @range-change="updateEmailDateRange"
      @send-email="emailDialogOpen = true"
    />

    <ScheduleEmailDialog
      :date-from="emailDateRange.dateFrom"
      :date-to="emailDateRange.dateTo"
      :open="emailDialogOpen"
      @close="emailDialogOpen = false"
    />
  </main>
</template>
