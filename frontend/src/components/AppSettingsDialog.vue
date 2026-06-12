<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { Save, X } from '@lucide/vue'
import { useAppSettingsStore } from '../stores/appSettingsStore'

const props = defineProps({
  open: {
    type: Boolean,
    default: false
  },
  initialTab: {
    type: String,
    default: 'baseAmount'
  }
})

const emit = defineEmits(['close', 'update-tab'])
const settingsStore = useAppSettingsStore()
const activeTab = ref(normalizeTab(props.initialTab))
const amountInput = ref('')
const fieldError = ref('')
const savedMessage = ref('')
const emailEditing = ref(false)
let fieldErrorTimer = null
let savedMessageTimer = null

const emailForm = reactive({
  senderEmail: '',
  smtpAuthCode: '',
  smtpHost: '',
  smtpPort: '465',
  smtpSecurity: 'SSL'
})

const emailSender = computed(() => settingsStore.settings.emailSender || {})
const settingsBusy = computed(() => settingsStore.loading || settingsStore.saving)
const showEmailFields = computed(() =>
  activeTab.value === 'email' && (!emailSender.value.configured || emailEditing.value)
)
const canSubmitActiveTab = computed(() => activeTab.value === 'baseAmount' || showEmailFields.value)

watch(
  () => props.initialTab,
  (tab) => {
    activeTab.value = normalizeTab(tab)
  }
)

watch(
  () => props.open,
  async (open) => {
    if (!open) {
      return
    }

    activeTab.value = normalizeTab(props.initialTab)
    clearFieldError()
    clearSavedMessage()
    amountInput.value = formatAmount(settingsStore.settings.estimatedHourlyBaseAmount)
    resetEmailForm()

    try {
      await settingsStore.fetchSettings()
      amountInput.value = formatAmount(settingsStore.settings.estimatedHourlyBaseAmount)
      resetEmailForm()
      emailEditing.value = !emailSender.value.configured
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
  if (activeTab.value === 'baseAmount') {
    await submitBaseAmount()
    return
  }

  if (showEmailFields.value) {
    await submitEmailSender()
  }
}

async function submitBaseAmount() {
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

async function submitEmailSender() {
  clearFieldError()
  clearSavedMessage()

  const emailSenderSettings = validateEmailSender()

  if (!emailSenderSettings) {
    return
  }

  try {
    await settingsStore.saveEmailSenderSettings(emailSenderSettings)
    resetEmailForm()
    emailEditing.value = false
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

function validateEmailSender() {
  const senderEmail = emailForm.senderEmail.trim()
  const smtpAuthCode = emailForm.smtpAuthCode.trim()
  const smtpHost = emailForm.smtpHost.trim()
  const smtpPort = Number(emailForm.smtpPort)
  const smtpSecurity = emailForm.smtpSecurity

  if (!senderEmail) {
    showFieldError('寄件 Email 不可为空')
    return null
  }

  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(senderEmail)) {
    showFieldError('寄件 Email 格式无效')
    return null
  }

  if (!smtpAuthCode) {
    showFieldError('授权码不可为空')
    return null
  }

  if (!smtpHost) {
    showFieldError('SMTP 服务器不可为空')
    return null
  }

  if (![465, 587].includes(smtpPort)) {
    showFieldError('SMTP 端口必须为 465 或 587')
    return null
  }

  if (!['NONE', 'SSL', 'STARTTLS'].includes(smtpSecurity)) {
    showFieldError('加密方式不可为空')
    return null
  }

  return {
    senderEmail,
    smtpAuthCode,
    smtpHost,
    smtpPort,
    smtpSecurity
  }
}

function activateTab(tab) {
  const nextTab = normalizeTab(tab)
  activeTab.value = nextTab
  clearFieldError()
  clearSavedMessage()
  emit('update-tab', nextTab)
}

function startEmailEdit() {
  resetEmailForm()
  emailEditing.value = true
}

function resetEmailForm() {
  emailForm.senderEmail = ''
  emailForm.smtpAuthCode = ''
  emailForm.smtpHost = emailSender.value.smtpHost || ''
  emailForm.smtpPort = emailSender.value.smtpPort ? String(emailSender.value.smtpPort) : '465'
  emailForm.smtpSecurity = emailSender.value.smtpSecurity || 'SSL'
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

function normalizeTab(tab) {
  return tab === 'email' ? 'email' : 'baseAmount'
}

function securityLabel(value) {
  if (value === 'SSL') {
    return 'SSL / TLS'
  }

  if (value === 'NONE') {
    return '无'
  }

  return 'STARTTLS'
}
</script>

<template>
  <div v-if="open" class="dialog-backdrop" role="presentation" @click="emit('close')">
    <form class="dialog settings-dialog" aria-label="全局设置" novalidate @click.stop @submit.prevent="submit">
      <div class="dialog-heading">
        <h2>全局设置</h2>
        <button class="icon-only-button" type="button" aria-label="关闭" @click="emit('close')">
          <X :size="18" />
        </button>
      </div>

      <div class="settings-tabs" role="tablist" aria-label="全局设置分类">
        <button
          type="button"
          role="tab"
          :aria-selected="activeTab === 'baseAmount'"
          :class="{ active: activeTab === 'baseAmount' }"
          @click="activateTab('baseAmount')"
        >
          基础金额
        </button>
        <button
          type="button"
          role="tab"
          :aria-selected="activeTab === 'email'"
          :class="{ active: activeTab === 'email' }"
          @click="activateTab('email')"
        >
          Email 寄件者
        </button>
      </div>

      <section v-if="activeTab === 'baseAmount'" class="settings-panel" role="tabpanel">
        <label>
          预估工时基础金额（元/小时）
          <input
            v-model="amountInput"
            inputmode="decimal"
            type="number"
            min="0.01"
            step="0.01"
            :disabled="settingsBusy"
          />
        </label>
      </section>

      <section v-else class="settings-panel" role="tabpanel">
        <div v-if="emailSender.configured && !emailEditing" class="email-sender-summary">
          <span>已设置寄件者</span>
          <strong>{{ emailSender.senderEmailMasked || '已配置' }}</strong>
          <span v-if="emailSender.smtpHost" class="settings-meta">
            {{ emailSender.smtpHost }}:{{ emailSender.smtpPort }} · {{ securityLabel(emailSender.smtpSecurity) }}
          </span>
          <button class="text-button" type="button" @click="startEmailEdit">
            要更换寄件者
          </button>
        </div>

        <div v-else class="email-settings-grid">
          <label>
            寄件 Email
            <input
              v-model="emailForm.senderEmail"
              type="email"
              autocomplete="username"
              placeholder="sender@example.com"
              required
              :disabled="settingsBusy"
            />
          </label>

          <label>
            授权码
            <input
              v-model="emailForm.smtpAuthCode"
              type="password"
              autocomplete="current-password"
              required
              :disabled="settingsBusy"
            />
          </label>

          <label>
            SMTP 服务器
            <input
              v-model="emailForm.smtpHost"
              type="text"
              placeholder="smtp.example.com"
              required
              :disabled="settingsBusy"
            />
          </label>

          <div class="date-fields">
            <label>
              SMTP 端口
              <select
                v-model="emailForm.smtpPort"
                required
                :disabled="settingsBusy"
              >
                <option value="465">465</option>
                <option value="587">587</option>
              </select>
            </label>
            <label>
              加密方式
              <select v-model="emailForm.smtpSecurity" :disabled="settingsBusy">
                <option value="STARTTLS">STARTTLS</option>
                <option value="SSL">SSL / TLS</option>
                <option value="NONE">无</option>
              </select>
            </label>
          </div>
        </div>
      </section>

      <p v-if="fieldError" class="dialog-error" role="alert">{{ fieldError }}</p>

      <div class="dialog-actions">
        <span v-if="savedMessage" class="dialog-status" role="status">
          {{ savedMessage }}
        </span>
        <button
          v-if="canSubmitActiveTab"
          class="icon-button primary-action"
          type="submit"
          :disabled="settingsBusy"
        >
          <span v-if="settingsStore.saving" class="loading-spinner" aria-hidden="true"></span>
          <Save v-else :size="18" />
          {{ settingsStore.saving ? '保存中' : '保存' }}
        </button>
      </div>
    </form>
  </div>
</template>
