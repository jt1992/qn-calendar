<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
import { Save, X } from '@lucide/vue'
import { useAppSettingsStore } from '../stores/appSettingsStore'

const props = defineProps({
  open: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['close'])
const settingsStore = useAppSettingsStore()
const amountInput = ref('')
const fieldError = ref('')
const savedMessage = ref('')
let fieldErrorTimer = null
let savedMessageTimer = null

watch(
  () => props.open,
  async (open) => {
    if (!open) {
      return
    }

    clearFieldError()
    clearSavedMessage()
    amountInput.value = formatAmount(settingsStore.settings.estimatedHourlyBaseAmount)

    try {
      await settingsStore.fetchSettings()
      amountInput.value = formatAmount(settingsStore.settings.estimatedHourlyBaseAmount)
    } catch (error) {
      showFieldError(error.message)
    }
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  clearFieldError()
  clearSavedMessage()
})

async function submit() {
  clearFieldError()
  clearSavedMessage()

  const amount = validateAmount()

  if (amount === null) {
    return
  }

  try {
    await settingsStore.saveSettings({
      estimatedHourlyBaseAmount: amount
    })
    amountInput.value = formatAmount(settingsStore.settings.estimatedHourlyBaseAmount)
    showSavedMessage('设置已保存')
  } catch (error) {
    showFieldError(error.message)
  }
}

function validateAmount() {
  const value = String(amountInput.value).trim()

  if (!value) {
    showFieldError('预估工时基础金额不可为空')
    return null
  }

  if (!/^\d+(\.\d{1,2})?$/.test(value)) {
    showFieldError('预估工时基础金额最多保留 2 位小数')
    return null
  }

  const amount = Number(value)

  if (!Number.isFinite(amount) || amount <= 0) {
    showFieldError('预估工时基础金额必须大于 0')
    return null
  }

  return amount
}

function showFieldError(message) {
  fieldError.value = message

  if (fieldErrorTimer) {
    window.clearTimeout(fieldErrorTimer)
  }

  fieldErrorTimer = window.setTimeout(() => {
    fieldError.value = ''
    fieldErrorTimer = null
  }, 5000)
}

function clearFieldError() {
  fieldError.value = ''

  if (fieldErrorTimer) {
    window.clearTimeout(fieldErrorTimer)
    fieldErrorTimer = null
  }
}

function showSavedMessage(message) {
  savedMessage.value = message

  if (savedMessageTimer) {
    window.clearTimeout(savedMessageTimer)
  }

  savedMessageTimer = window.setTimeout(() => {
    savedMessage.value = ''
    savedMessageTimer = null
  }, 5000)
}

function clearSavedMessage() {
  savedMessage.value = ''

  if (savedMessageTimer) {
    window.clearTimeout(savedMessageTimer)
    savedMessageTimer = null
  }
}

function formatAmount(value) {
  if (value === null || value === undefined || value === '') {
    return '100'
  }

  return String(value)
}
</script>

<template>
  <div v-if="open" class="dialog-backdrop" role="presentation" @click="emit('close')">
    <form class="dialog" aria-label="全局设置" novalidate @click.stop @submit.prevent="submit">
      <div class="dialog-heading">
        <h2>全局设置</h2>
        <button class="icon-only-button" type="button" aria-label="关闭" @click="emit('close')">
          <X :size="18" />
        </button>
      </div>

      <label>
        预估工时基础金额（元/小时）
        <input
          v-model="amountInput"
          inputmode="decimal"
          type="number"
          min="0.01"
          step="0.01"
          :disabled="settingsStore.loading || settingsStore.saving"
        />
      </label>

      <p v-if="fieldError" class="dialog-error" role="alert">{{ fieldError }}</p>

      <div class="dialog-actions">
        <span v-if="savedMessage" class="dialog-status" role="status">
          {{ savedMessage }}
        </span>
        <button
          class="icon-button primary-action"
          type="submit"
          :disabled="settingsStore.loading || settingsStore.saving"
        >
          <span v-if="settingsStore.saving" class="loading-spinner" aria-hidden="true"></span>
          <Save v-else :size="18" />
          {{ settingsStore.saving ? '保存中' : '保存' }}
        </button>
      </div>
    </form>
  </div>
</template>
