<script setup>
import { computed, ref } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'
import { CalendarDays, Mail, Moon, Sun, Table2 } from '@lucide/vue'
import ScheduleEmailDialog from './components/ScheduleEmailDialog.vue'

const route = useRoute()
const emailDialogOpen = ref(false)
const themeMode = ref(window.localStorage.getItem('qn-calendar-theme') || 'dark')
const completedStatsMonth = ref('')
const emailDateRange = ref({
  dateFrom: '',
  dateTo: '',
  viewType: 'timeGridWeek'
})

const defaultEmailType = computed(() => route.name === 'completed-stats' ? 'COMPLETED_STATS' : '')

function toggleTheme() {
  themeMode.value = themeMode.value === 'dark' ? 'light' : 'dark'
  window.localStorage.setItem('qn-calendar-theme', themeMode.value)
}

function updateEmailDateRange(range) {
  emailDateRange.value = {
    dateFrom: range.dateFrom,
    dateTo: range.dateTo,
    viewType: range.viewType
  }
}
</script>

<template>
  <main class="app-shell" :data-theme="themeMode">
    <header class="top-nav">
      <nav class="main-tabs" aria-label="主要功能">
        <RouterLink v-slot="{ isActive, navigate }" custom to="/schedule">
          <button type="button" :class="{ active: isActive }" @click="navigate">
            <CalendarDays :size="18" />
            待排工单
          </button>
        </RouterLink>
        <RouterLink v-slot="{ isActive, navigate }" custom to="/completed-stats">
          <button type="button" :class="{ active: isActive }" @click="navigate">
            <Table2 :size="18" />
            完工统计表
          </button>
        </RouterLink>
      </nav>

      <div class="top-nav-actions">
        <button class="icon-button" type="button" @click="emailDialogOpen = true">
          <Mail :size="18" />
          发送 Email
        </button>
        <button class="icon-only-button" type="button" aria-label="切换深浅色模式" @click="toggleTheme">
          <Sun v-if="themeMode === 'dark'" :size="18" />
          <Moon v-else :size="18" />
        </button>
      </div>
    </header>

    <RouterView v-slot="{ Component, route: viewRoute }">
      <component
        :is="Component"
        v-bind="viewRoute.name === 'completed-stats' ? { monthFilter: completedStatsMonth } : {}"
        @range-change="updateEmailDateRange"
        @update-month-filter="completedStatsMonth = $event"
      />
    </RouterView>

    <ScheduleEmailDialog
      :date-from="emailDateRange.dateFrom"
      :date-to="emailDateRange.dateTo"
      :calendar-view-type="emailDateRange.viewType"
      :completed-stats-month="completedStatsMonth"
      :default-email-type="defaultEmailType"
      :open="emailDialogOpen"
      @close="emailDialogOpen = false"
    />
  </main>
</template>
