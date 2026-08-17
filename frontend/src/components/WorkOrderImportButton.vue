<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { Upload } from '@lucide/vue'
import { useWorkOrderStore } from '../stores/workOrderStore'

const emit = defineEmits(['imported'])
const store = useWorkOrderStore()
const fileInput = ref(null)
const uploadDropzone = ref(null)
const uploadHelpTooltip = ref(null)
const isDragging = ref(false)
const fileError = ref('')
const uploadHelpOpen = ref(false)
const uploadHelpStyle = ref({})
let uploadDropzoneFocused = false
let uploadDropzoneHovered = false

onMounted(() => {
  window.addEventListener('resize', updateUploadHelpPosition)
  window.addEventListener('scroll', updateUploadHelpPosition, true)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', updateUploadHelpPosition)
  window.removeEventListener('scroll', updateUploadHelpPosition, true)
})

function openFilePicker() {
  fileInput.value?.click()
}

function showUploadHelp() {
  uploadHelpOpen.value = true
  nextTick(updateUploadHelpPosition)
}

function handleUploadMouseEnter() {
  uploadDropzoneHovered = true
  showUploadHelp()
}

function handleUploadMouseLeave() {
  uploadDropzoneHovered = false

  if (!uploadDropzoneFocused) {
    uploadHelpOpen.value = false
  }
}

function handleUploadFocus() {
  uploadDropzoneFocused = true
  showUploadHelp()
}

function handleUploadBlur() {
  uploadDropzoneFocused = false

  if (!uploadDropzoneHovered) {
    uploadHelpOpen.value = false
  }
}

function closeUploadHelp() {
  uploadHelpOpen.value = false
}

function updateUploadHelpPosition() {
  if (!uploadHelpOpen.value || !uploadDropzone.value || !uploadHelpTooltip.value) {
    return
  }

  const viewportPadding = 12
  const gap = 8
  const triggerRect = uploadDropzone.value.getBoundingClientRect()
  const tooltipRect = uploadHelpTooltip.value.getBoundingClientRect()
  const left = Math.min(
    Math.max(viewportPadding, triggerRect.left),
    window.innerWidth - tooltipRect.width - viewportPadding
  )
  const spaceBelow = window.innerHeight - triggerRect.bottom - viewportPadding
  const top = spaceBelow >= tooltipRect.height + gap
    ? triggerRect.bottom + gap
    : Math.max(viewportPadding, triggerRect.top - tooltipRect.height - gap)

  uploadHelpStyle.value = {
    left: `${left}px`,
    top: `${top}px`
  }
}

async function handleFileChange(event) {
  const [file] = event.target.files

  if (!file) {
    return
  }

  try {
    await uploadFile(file)
  } finally {
    event.target.value = ''
  }
}

async function uploadFile(file) {
  if (!file.name.toLowerCase().endsWith('.xlsx')) {
    fileError.value = '仅支持 .xlsx 文件。'
    return
  }

  await store.importXlsx(file)
  fileError.value = ''
  emit('imported')
}

async function handleDrop(event) {
  isDragging.value = false
  const [file] = event.dataTransfer.files

  if (!file) {
    return
  }

  await uploadFile(file)
}
</script>

<template>
  <div class="upload-field">
    <div class="upload-dropzone-container">
      <button
        ref="uploadDropzone"
        type="button"
        class="upload-dropzone"
        :class="{ dragging: isDragging, invalid: fileError }"
        aria-label="上传 XLSX"
        :aria-describedby="fileError ? 'xlsx-upload-help xlsx-file-error' : 'xlsx-upload-help'"
        :aria-invalid="Boolean(fileError)"
        @click="openFilePicker"
        @dragenter.prevent="isDragging = true"
        @dragover.prevent="isDragging = true"
        @dragleave.prevent="isDragging = false"
        @drop.prevent="handleDrop"
        @mouseenter="handleUploadMouseEnter"
        @mouseleave="handleUploadMouseLeave"
        @focus="handleUploadFocus"
        @blur="handleUploadBlur"
        @keydown.esc.prevent="closeUploadHelp"
      >
        <Upload :size="16" />
        <span>点击上传或拖拽 XLSX 到这里</span>
      </button>
    </div>

    <Teleport to=".app-shell">
      <div
        v-show="uploadHelpOpen"
        id="xlsx-upload-help"
        ref="uploadHelpTooltip"
        class="upload-dropzone-tooltip"
        :style="uploadHelpStyle"
        role="tooltip"
      >
        <p>档名包含来源名称或标签文字时以档名的来源为主</p>
        <p>
          <strong>必填：</strong>
          订单编号、买家实付金额、应发货时间
        </p>
        <p>
          <strong>选填：</strong>
          备注标签、买家留言、商家备注、订单付款时间
        </p>
      </div>
    </Teleport>

    <small v-if="fileError" id="xlsx-file-error" class="form-field-error" role="alert">
      {{ fileError }}
    </small>

    <input
      ref="fileInput"
      class="visually-hidden"
      type="file"
      accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
      :aria-describedby="fileError ? 'xlsx-upload-help xlsx-file-error' : 'xlsx-upload-help'"
      :aria-invalid="Boolean(fileError)"
      @change="handleFileChange"
    />
  </div>
</template>
