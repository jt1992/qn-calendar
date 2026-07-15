<script setup>
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { Check, Pencil, Plus, Save, Trash2, X } from '@lucide/vue'
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
const recipientDeletingId = ref(null)
const recipientEditingId = ref(null)
const recipientNameDraft = ref('')
const recipientEmailDraft = ref('')
const recipientEditError = ref('')
const recipientNameInput = ref(null)
let fieldErrorTimer = null
let savedMessageTimer = null

const emailForm = reactive({
  senderEmail: '',
  smtpAuthCode: '',
  smtpHost: '',
  smtpPort: '465',
  smtpSecurity: 'SSL'
})

const recipientForm = reactive({
  name: '',
  email: ''
})

const emailSender = computed(() => settingsStore.settings.emailSender || {})
const settingsBusy = computed(() =>
  settingsStore.loading ||
  settingsStore.saving ||
  settingsStore.recipientsLoading ||
  settingsStore.recipientSaving
)
const showEmailFields = computed(() =>
  activeTab.value === 'email' && (!emailSender.value.configured || emailEditing.value)
)
const canSubmitActiveTab = computed(() =>
  activeTab.value === 'baseAmount' || activeTab.value === 'recipients' || showEmailFields.value
)
const activeSaving = computed(() =>
  activeTab.value === 'recipients' ? settingsStore.recipientSaving : settingsStore.saving
)
const submitButtonText = computed(() => {
  if (activeSaving.value) {
    return '保存中'
  }

  if (activeTab.value === 'recipients') {
    return '新增'
  }

  return '保存'
})

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
    resetRecipientForm()
    cancelRecipientEdit()

    try {
      await Promise.all([
        settingsStore.fetchSettings(),
        settingsStore.fetchEmailRecipients()
      ])
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
    return
  }

  if (activeTab.value === 'recipients') {
    await submitEmailRecipient()
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

async function submitEmailRecipient() {
  clearFieldError()
  clearSavedMessage()

  const recipient = validateEmailRecipient()

  if (!recipient) {
    return
  }

  try {
    await settingsStore.createEmailRecipient(recipient)
    showSavedMessage('收件者已新增')
    resetRecipientForm()
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

function validateEmailRecipient() {
  const name = recipientForm.name.trim()
  const email = recipientForm.email.trim()

  if (!name) {
    showFieldError('收件人姓名不可为空')
    return null
  }

  if (name.length > 120) {
    showFieldError('收件人姓名最多 120 个字符')
    return null
  }

  if (!email) {
    showFieldError('收件 Email 不可为空')
    return null
  }

  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    showFieldError('收件 Email 格式无效')
    return null
  }

  return { name, email }
}

function activateTab(tab) {
  const nextTab = normalizeTab(tab)
  activeTab.value = nextTab
  clearFieldError()
  clearSavedMessage()
  cancelRecipientEdit()
  emit('update-tab', nextTab)
}

async function startRecipientEdit(recipient) {
  recipientEditingId.value = recipient.id
  recipientNameDraft.value = recipient.name || ''
  recipientEmailDraft.value = recipient.email
  recipientEditError.value = ''
  clearFieldError()
  clearSavedMessage()
  await nextTick()
  recipientNameInput.value?.focus()
}

function setRecipientNameInput(element) {
  recipientNameInput.value = element
}

async function saveRecipient(recipient) {
  const name = recipientNameDraft.value.trim()
  const email = recipientEmailDraft.value.trim()

  if (!name) {
    recipientEditError.value = '收件人姓名不可为空'
    return
  }

  if (name.length > 120) {
    recipientEditError.value = '收件人姓名最多 120 个字符'
    return
  }

  if (!email) {
    recipientEditError.value = '收件 Email 不可为空'
    return
  }

  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    recipientEditError.value = '收件 Email 格式无效'
    return
  }

  recipientEditError.value = ''
  clearFieldError()
  clearSavedMessage()

  try {
    await settingsStore.updateEmailRecipient(recipient.id, {
      name,
      email
    })
    cancelRecipientEdit()
    showSavedMessage('收件者已更新')
  } catch (error) {
    recipientEditError.value = error.message
  }
}

function cancelRecipientEdit() {
  recipientEditingId.value = null
  recipientNameDraft.value = ''
  recipientEmailDraft.value = ''
  recipientEditError.value = ''
}

async function removeRecipient(recipient) {
  if (!window.confirm(`确定删除 ${recipient.name || recipient.email}？`)) {
    return
  }

  recipientDeletingId.value = recipient.id
  clearFieldError()
  clearSavedMessage()

  try {
    await settingsStore.deleteEmailRecipient(recipient.id)

    if (recipientEditingId.value === recipient.id) {
      cancelRecipientEdit()
    }

    showSavedMessage('收件者已删除')
  } catch (error) {
    showFieldError(error.message)
  } finally {
    recipientDeletingId.value = null
  }
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

function resetRecipientForm() {
  recipientForm.name = ''
  recipientForm.email = ''
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
  return ['email', 'recipients'].includes(tab) ? tab : 'baseAmount'
}

function recipientMeta(recipient) {
  if (!recipient.lastUsedAt) {
    return '尚未发送'
  }

  return `已发送 ${recipient.usageCount} 次 · 最近 ${formatDateTime(recipient.lastUsedAt)}`
}

function formatDateTime(value) {
  return value ? value.replace('T', ' ').slice(0, 16) : ''
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
  <div
    v-if="open"
    class="dialog-backdrop settings-dialog-backdrop"
    role="presentation"
    @click="emit('close')"
  >
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
          :aria-selected="activeTab === 'recipients'"
          :class="{ active: activeTab === 'recipients' }"
          @click="activateTab('recipients')"
        >
          Email 收件者
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
        <button
          type="button"
          role="tab"
          :aria-selected="activeTab === 'baseAmount'"
          :class="{ active: activeTab === 'baseAmount' }"
          @click="activateTab('baseAmount')"
        >
          基础金额
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

      <section v-else-if="activeTab === 'email'" class="settings-panel" role="tabpanel">
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

      <section v-else class="settings-panel recipient-settings-panel" role="tabpanel">
        <div class="email-recipient-form">
          <label>
            收件人姓名
            <input
              v-model="recipientForm.name"
              type="text"
              maxlength="120"
              autocomplete="name"
              placeholder="例如：张小姐"
              required
              :disabled="settingsBusy"
            />
          </label>
          <label>
            收件 Email
            <input
              v-model="recipientForm.email"
              type="email"
              maxlength="320"
              autocomplete="email"
              placeholder="receiver@example.com"
              required
              :disabled="settingsBusy"
            />
          </label>
        </div>

        <div class="recipient-list-heading">
          <div>
            <h3>常用与寄送过的收件者</h3>
            <p>成功寄送的新 Email 会自动加入此列表。</p>
          </div>
          <span class="count-badge">{{ settingsStore.emailRecipients.length }}</span>
        </div>

        <p v-if="settingsStore.recipientsLoading" class="recipient-empty">载入中...</p>
        <p v-else-if="settingsStore.emailRecipients.length === 0" class="recipient-empty">
          尚无收件者
        </p>
        <div v-else class="email-recipient-list">
          <article
            v-for="recipient in settingsStore.emailRecipients"
            :key="recipient.id"
            class="email-recipient-item"
          >
            <div class="email-recipient-details">
              <div class="email-recipient-fields">
                <input
                  v-if="recipientEditingId === recipient.id"
                  :ref="setRecipientNameInput"
                  v-model="recipientNameDraft"
                  class="email-recipient-name-input"
                  type="text"
                  maxlength="120"
                  autocomplete="name"
                  aria-label="编辑收件人姓名"
                  placeholder="收件人姓名"
                  required
                  autofocus
                  :disabled="settingsBusy"
                  @keydown.enter.prevent="saveRecipient(recipient)"
                  @keydown.esc.prevent="cancelRecipientEdit"
                />
                <strong v-else class="email-recipient-field-display">
                  {{ recipient.name || '未设置姓名' }}
                </strong>
                <input
                  v-if="recipientEditingId === recipient.id"
                  v-model="recipientEmailDraft"
                  type="email"
                  maxlength="320"
                  autocomplete="email"
                  aria-label="编辑收件 Email"
                  placeholder="收件 Email"
                  required
                  :disabled="settingsBusy"
                  @keydown.enter.prevent="saveRecipient(recipient)"
                  @keydown.esc.prevent="cancelRecipientEdit"
                />
                <span v-else class="email-recipient-field-display">{{ recipient.email }}</span>
              </div>
              <small>{{ recipientMeta(recipient) }}</small>
              <small
                v-if="recipientEditingId === recipient.id && recipientEditError"
                class="recipient-edit-error"
                role="alert"
              >
                {{ recipientEditError }}
              </small>
            </div>
            <div class="email-recipient-actions">
              <button
                v-if="recipientEditingId === recipient.id"
                class="icon-only-button"
                type="button"
                :aria-label="`取消编辑 ${recipient.email}`"
                :disabled="settingsBusy"
                @click="cancelRecipientEdit"
              >
                <X :size="16" />
              </button>
              <button
                v-if="recipientEditingId === recipient.id"
                class="icon-only-button confirm-action"
                type="button"
                :aria-label="`确认编辑 ${recipient.email}`"
                :disabled="settingsBusy"
                @click="saveRecipient(recipient)"
              >
                <span
                  v-if="settingsStore.recipientSaving"
                  class="loading-spinner"
                  aria-hidden="true"
                ></span>
                <Check v-else :size="16" />
              </button>
              <button
                v-if="recipientEditingId !== recipient.id"
                class="icon-only-button"
                type="button"
                :aria-label="`编辑 ${recipient.name || recipient.email}`"
                :disabled="settingsBusy"
                @click="startRecipientEdit(recipient)"
              >
                <Pencil :size="16" />
              </button>
              <button
                v-if="recipientEditingId !== recipient.id"
                class="icon-only-button danger-action"
                type="button"
                :aria-label="`删除 ${recipient.name || recipient.email}`"
                :disabled="settingsBusy"
                @click="removeRecipient(recipient)"
              >
                <span
                  v-if="recipientDeletingId === recipient.id"
                  class="loading-spinner"
                  aria-hidden="true"
                ></span>
                <Trash2 v-else :size="16" />
              </button>
            </div>
          </article>
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
          <span v-if="activeSaving" class="loading-spinner" aria-hidden="true"></span>
          <Plus v-else-if="activeTab === 'recipients'" :size="18" />
          <Save v-else :size="18" />
          {{ submitButtonText }}
        </button>
      </div>
    </form>
  </div>
</template>
