<script setup>
import { computed, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { CalendarDays, Mail, Moon, Settings, Sun, Table2 } from '@lucide/vue'
import AppSettingsDialog from './components/AppSettingsDialog.vue'
import ScheduleEmailDialog from './components/ScheduleEmailDialog.vue'
import { ORDER_NUMBER_COPY_NOTICE, useWorkOrderStore } from './stores/workOrderStore'

const route = useRoute()
const router = useRouter()
const workOrderStore = useWorkOrderStore()
const emailDialogOpen = ref(false)
const settingsDialogOpen = ref(route.query.settingsModal === '1')
const settingsTab = ref(normalizeSettingsTab(route.query.tab))
const themeMode = ref(window.localStorage.getItem('qn-calendar-theme') || 'dark')
const completedStatsMonth = ref('')
const pointerActivatedControlSelector = [
  'button',
  'input[type="button"]',
  'input[type="checkbox"]',
  'input[type="color"]',
  'input[type="file"]',
  'input[type="image"]',
  'input[type="radio"]',
  'input[type="range"]',
  'input[type="reset"]',
  'input[type="submit"]',
  '[role="button"]',
  '[role="checkbox"]',
  '[role="radio"]',
  '[role="switch"]'
].join(', ')
const emailDateRange = ref({
  dateFrom: '',
  dateTo: '',
  viewType: 'timeGridWeek'
})

const defaultEmailType = computed(() => route.name === 'completed-stats' ? 'COMPLETED_STATS' : '')

watch(
  () => route.query,
  (query) => {
    settingsDialogOpen.value = query.settingsModal === '1'
    settingsTab.value = normalizeSettingsTab(query.tab)
  },
  { immediate: true }
)

function toggleTheme() {
  themeMode.value = themeMode.value === 'dark' ? 'light' : 'dark'
  window.localStorage.setItem('qn-calendar-theme', themeMode.value)
}

function releasePointerActivatedControlFocus(event) {
  if (event.detail === 0 || !(event.target instanceof Element)) {
    return
  }

  const label = event.target.closest('label')
  const control = event.target.closest(pointerActivatedControlSelector) || label?.control

  if (!control?.matches(pointerActivatedControlSelector)) {
    return
  }

  window.requestAnimationFrame(() => {
    if (document.activeElement === control || control.contains(document.activeElement)) {
      document.activeElement.blur()
    }
  })
}

function updateEmailDateRange(range) {
  emailDateRange.value = {
    dateFrom: range.dateFrom,
    dateTo: range.dateTo,
    viewType: range.viewType
  }
}

function openSettingsDialog(tab = 'recipients') {
  const nextTab = normalizeSettingsTab(tab)
  settingsDialogOpen.value = true
  settingsTab.value = nextTab
  router.replace({
    query: {
      ...route.query,
      settingsModal: '1',
      tab: nextTab
    }
  })
}

function closeSettingsDialog() {
  settingsDialogOpen.value = false
  const nextQuery = { ...route.query }
  delete nextQuery.settingsModal
  delete nextQuery.tab
  router.replace({ query: nextQuery })
}

function updateSettingsTab(tab) {
  const nextTab = normalizeSettingsTab(tab)
  settingsTab.value = nextTab

  if (!settingsDialogOpen.value) {
    return
  }

  router.replace({
    query: {
      ...route.query,
      settingsModal: '1',
      tab: nextTab
    }
  })
}

function openSettingsFromEmail(tab) {
  emailDialogOpen.value = false
  openSettingsDialog(tab)
}

function normalizeSettingsTab(tab) {
  if (tab === 'baseAmount') {
    return 'basic'
  }

  return ['basic', 'email', 'fields', 'recipients'].includes(tab) ? tab : 'recipients'
}
</script>

<template>
  <main
    class="app-shell"
    :data-theme="themeMode"
    @click.capture="releasePointerActivatedControlFocus"
  >
    <header class="top-nav">
      <div
        v-if="workOrderStore.notice === ORDER_NUMBER_COPY_NOTICE"
        class="copy-success-message"
        role="status"
        aria-live="polite"
      >
        {{ workOrderStore.notice }}
      </div>

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
        <button class="icon-button" type="button" @click="openSettingsDialog()">
          <Settings :size="18" />
          设置
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
      @open-settings="openSettingsFromEmail"
    />

    <AppSettingsDialog
      :initial-tab="settingsTab"
      :open="settingsDialogOpen"
      @close="closeSettingsDialog"
      @update-tab="updateSettingsTab"
    />
  </main>
</template>
