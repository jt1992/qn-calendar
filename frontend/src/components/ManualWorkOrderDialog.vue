<script setup>
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { Plus, X } from '@lucide/vue'
import { useAppSettingsStore } from '../stores/appSettingsStore'
import { useWorkOrderStore } from '../stores/workOrderStore'

const props = defineProps({
  open: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['close', 'created'])
const store = useWorkOrderStore()
const settingsStore = useAppSettingsStore()
const orderNoInput = ref(null)
const submitting = ref(false)
const loadingSources = ref(false)
const submitError = ref('')
const form = reactive(emptyForm())
const errors = reactive(emptyErrors())
const orderSourceOptions = computed(() => settingsStore.settings.orderSourceOptions || [])

watch(
  () => props.open,
  async (open) => {
    if (!open) {
      return
    }

    resetForm()
    loadingSources.value = true

    try {
      await settingsStore.ensureSettingsLoaded()
      form.sourceName = orderSourceOptions.value[0]?.name || ''
    } catch (error) {
      submitError.value = error.message || '读取订单来源选项失败'
    } finally {
      loadingSources.value = false
    }

    await nextTick()
    orderNoInput.value?.focus()
  }
)

function emptyForm() {
  return {
    orderNo: '',
    sourceName: '',
    price: '',
    latestShipTime: '',
    urgentText: '',
    buyerMessage: '',
    merchantRemark: '',
    paidAt: ''
  }
}

function emptyErrors() {
  return {
    orderNo: '',
    sourceName: '',
    price: '',
    latestShipTime: '',
    urgentText: '',
    buyerMessage: '',
    merchantRemark: '',
    paidAt: ''
  }
}

function resetForm() {
  Object.assign(form, emptyForm())
  Object.assign(errors, emptyErrors())
  submitError.value = ''
  submitting.value = false
}

function close() {
  if (!submitting.value) {
    emit('close')
  }
}

async function submit() {
  if (submitting.value || loadingSources.value || !validate()) {
    return
  }

  submitting.value = true
  submitError.value = ''

  try {
    const workOrder = await store.createWorkOrder({
      orderNo: form.orderNo.trim(),
      sourceName: form.sourceName,
      price: Number(form.price),
      latestShipTime: form.latestShipTime,
      urgentText: form.urgentText.trim(),
      buyerMessage: form.buyerMessage.trim(),
      merchantRemark: form.merchantRemark.trim(),
      paidAt: form.paidAt || null
    })
    emit('created', workOrder)
    emit('close')
  } catch (error) {
    submitError.value = error.details?.length
      ? error.details.join('；')
      : error.message
  } finally {
    submitting.value = false
  }
}

function validate() {
  Object.assign(errors, emptyErrors())
  submitError.value = ''

  const orderNo = form.orderNo.trim()
  const price = String(form.price).trim()

  if (!orderNo) {
    errors.orderNo = '不能为空。'
  } else if (orderNo.length > 80) {
    errors.orderNo = '最长为 80 个字符。'
  }

  if (!form.sourceName) {
    errors.sourceName = '不能为空。'
  } else if (!orderSourceOptions.value.some((option) => option.name === form.sourceName)) {
    errors.sourceName = '请选择基础设置中的订单来源。'
  }

  if (!price) {
    errors.price = '不能为空。'
  } else if (!/^\d+(\.\d{1,2})?$/.test(price)) {
    errors.price = '请输入非负金额，最多保留 2 位小数。'
  } else if (price.split('.')[0].length > 12) {
    errors.price = '整数部分最多 12 位。'
  }

  if (!form.latestShipTime) {
    errors.latestShipTime = '不能为空。'
  }

  if (form.urgentText.trim().length > 120) {
    errors.urgentText = '最长为 120 个字符。'
  }

  if (form.buyerMessage.trim().length > 1000) {
    errors.buyerMessage = '最长为 1000 个字符。'
  }

  if (form.merchantRemark.trim().length > 1000) {
    errors.merchantRemark = '最长为 1000 个字符。'
  }

  const remarkLength = combinedRemarkLength()
  if (remarkLength > 1000 && !errors.merchantRemark) {
    errors.merchantRemark = '与买家留言合并后最长为 1000 个字符。'
  }

  return !Object.values(errors).some(Boolean)
}

function combinedRemarkLength() {
  const buyerMessage = form.buyerMessage.trim()
  const merchantRemark = form.merchantRemark.trim()
  const parts = []

  if (buyerMessage) {
    parts.push(`买家留言：${buyerMessage}`)
  }

  if (merchantRemark) {
    parts.push(`商家备注：${merchantRemark}`)
  }

  return parts.join('\n').length
}

function openDateTimePicker(event) {
  try {
    event.currentTarget.showPicker?.()
  } catch {
    // The native picker can only open from a direct user interaction.
  }
}

function handleDateTimeKeydown(event) {
  if (event.key === 'Tab') {
    return
  }

  event.preventDefault()

  if (['Enter', ' ', 'ArrowDown'].includes(event.key)) {
    openDateTimePicker(event)
  }
}
</script>

<template>
  <div
    v-if="open"
    class="dialog-backdrop manual-work-order-dialog-backdrop"
    role="presentation"
    @click="close"
    @keydown.esc="close"
  >
    <form
      class="dialog manual-work-order-dialog"
      aria-label="新增待排工单"
      novalidate
      @click.stop
      @submit.prevent="submit"
    >
      <div class="dialog-heading">
        <h2>新增待排工单</h2>
        <button class="icon-only-button" type="button" aria-label="关闭" :disabled="submitting" @click="close">
          <X :size="18" />
        </button>
      </div>

      <div class="manual-work-order-fields">
        <label>
          <span class="form-field-label">
            订单来源
            <span class="required-marker" aria-hidden="true">*</span>
            <small v-if="errors.sourceName" id="manual-source-name-error" class="form-field-error" role="alert">
              {{ errors.sourceName }}
            </small>
          </span>
          <select
            v-model="form.sourceName"
            required
            :aria-describedby="errors.sourceName ? 'manual-source-name-error' : undefined"
            :aria-invalid="Boolean(errors.sourceName)"
            :disabled="submitting || loadingSources"
          >
            <option disabled value="">{{ loadingSources ? '读取中...' : '请选择订单来源' }}</option>
            <option v-for="option in orderSourceOptions" :key="option.identifier" :value="option.name">
              {{ option.name }}
            </option>
          </select>
        </label>

        <label>
          <span class="form-field-label">
            订单编号
            <span class="required-marker" aria-hidden="true">*</span>
            <small v-if="errors.orderNo" id="manual-order-no-error" class="form-field-error" role="alert">
              {{ errors.orderNo }}
            </small>
          </span>
          <input
            ref="orderNoInput"
            v-model="form.orderNo"
            type="text"
            maxlength="80"
            required
            :aria-describedby="errors.orderNo ? 'manual-order-no-error' : undefined"
            :aria-invalid="Boolean(errors.orderNo)"
            :disabled="submitting"
          />
        </label>

        <label>
          <span class="form-field-label">
            买家实付金额
            <span class="required-marker" aria-hidden="true">*</span>
            <small v-if="errors.price" id="manual-price-error" class="form-field-error" role="alert">
              {{ errors.price }}
            </small>
          </span>
          <input
            v-model="form.price"
            type="number"
            inputmode="decimal"
            min="0"
            step="0.01"
            required
            :aria-describedby="errors.price ? 'manual-price-error' : undefined"
            :aria-invalid="Boolean(errors.price)"
            :disabled="submitting"
          />
        </label>

        <label>
          <span class="form-field-label">
            应发货时间
            <span class="required-marker" aria-hidden="true">*</span>
            <small
              v-if="errors.latestShipTime"
              id="manual-latest-ship-time-error"
              class="form-field-error"
              role="alert"
            >
              {{ errors.latestShipTime }}
            </small>
          </span>
          <input
            v-model="form.latestShipTime"
            type="datetime-local"
            inputmode="none"
            required
            :aria-describedby="errors.latestShipTime ? 'manual-latest-ship-time-error' : undefined"
            :aria-invalid="Boolean(errors.latestShipTime)"
            :disabled="submitting"
            @beforeinput.prevent
            @click="openDateTimePicker"
            @drop.prevent
            @keydown="handleDateTimeKeydown"
            @paste.prevent
          />
        </label>

        <label>
          <span class="form-field-label">
            订单付款时间
            <small v-if="errors.paidAt" id="manual-paid-at-error" class="form-field-error" role="alert">
              {{ errors.paidAt }}
            </small>
          </span>
          <input
            v-model="form.paidAt"
            type="datetime-local"
            inputmode="none"
            :aria-describedby="errors.paidAt ? 'manual-paid-at-error' : undefined"
            :aria-invalid="Boolean(errors.paidAt)"
            :disabled="submitting"
            @beforeinput.prevent
            @click="openDateTimePicker"
            @drop.prevent
            @keydown="handleDateTimeKeydown"
            @paste.prevent
          />
        </label>

        <label class="manual-work-order-wide-field">
          <span class="form-field-label">
            备注标签
            <small v-if="errors.urgentText" id="manual-urgent-text-error" class="form-field-error" role="alert">
              {{ errors.urgentText }}
            </small>
          </span>
          <input
            v-model="form.urgentText"
            type="text"
            maxlength="120"
            placeholder="按字段识别设置判断是否加急"
            :aria-describedby="errors.urgentText ? 'manual-urgent-text-error' : undefined"
            :aria-invalid="Boolean(errors.urgentText)"
            :disabled="submitting"
          />
        </label>

        <label class="manual-work-order-wide-field">
          <span class="form-field-label">
            买家留言
            <small v-if="errors.buyerMessage" id="manual-buyer-message-error" class="form-field-error" role="alert">
              {{ errors.buyerMessage }}
            </small>
          </span>
          <textarea
            v-model="form.buyerMessage"
            rows="3"
            maxlength="1000"
            :aria-describedby="errors.buyerMessage ? 'manual-buyer-message-error' : undefined"
            :aria-invalid="Boolean(errors.buyerMessage)"
            :disabled="submitting"
          ></textarea>
        </label>

        <label class="manual-work-order-wide-field">
          <span class="form-field-label">
            商家备注
            <small
              v-if="errors.merchantRemark"
              id="manual-merchant-remark-error"
              class="form-field-error"
              role="alert"
            >
              {{ errors.merchantRemark }}
            </small>
          </span>
          <textarea
            v-model="form.merchantRemark"
            rows="3"
            maxlength="1000"
            :aria-describedby="errors.merchantRemark ? 'manual-merchant-remark-error' : undefined"
            :aria-invalid="Boolean(errors.merchantRemark)"
            :disabled="submitting"
          ></textarea>
        </label>
      </div>

      <p v-if="submitError" class="form-submit-error" role="alert">{{ submitError }}</p>

      <div class="dialog-actions">
        <button class="text-button" type="button" :disabled="submitting" @click="close">取消</button>
        <button class="icon-button primary-action" type="submit" :disabled="submitting">
          <span v-if="submitting" class="loading-spinner" aria-hidden="true"></span>
          <Plus v-else :size="18" aria-hidden="true" />
          {{ submitting ? '新增中' : '新增' }}
        </button>
      </div>
    </form>
  </div>
</template>
