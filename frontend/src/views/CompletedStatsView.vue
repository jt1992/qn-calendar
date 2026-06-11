<script setup>
import { computed, onMounted } from 'vue'
import CompletedStatsTable from '../components/CompletedStatsTable.vue'
import { useWorkOrderStore } from '../stores/workOrderStore'

const props = defineProps({
  monthFilter: {
    type: String,
    default: ''
  }
})

const emit = defineEmits(['update-month-filter'])
const store = useWorkOrderStore()

const availableOrderMonths = computed(() => {
  return [...new Set(
    store.completedStats
      .map((row) => row.orderTime?.slice?.(0, 7))
      .filter(Boolean)
  )]
})

const filteredCompletedStats = computed(() => {
  if (!props.monthFilter) {
    return store.completedStats
  }

  return store.completedStats.filter((row) => row.orderTime?.slice?.(0, 7) === props.monthFilter)
})

onMounted(async () => {
  try {
    await store.fetchCompletedStats()
  } catch (error) {
    store.setError(error.message)
  }
})
</script>

<template>
  <section class="stats-layout" aria-label="完工统计表">
    <div v-if="store.error" class="message error-message">
      {{ store.error }}
    </div>
    <CompletedStatsTable
      :available-months="availableOrderMonths"
      :month-filter="monthFilter"
      :stats="filteredCompletedStats"
      @update-month-filter="emit('update-month-filter', $event)"
    />
  </section>
</template>
