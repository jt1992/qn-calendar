<script setup>
import { ref } from 'vue'
import { Upload } from '@lucide/vue'
import { useWorkOrderStore } from '../stores/workOrderStore'

const store = useWorkOrderStore()
const fileInput = ref(null)
const isDragging = ref(false)
const fileError = ref('')

function openFilePicker() {
  fileInput.value?.click()
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
    <span class="form-field-label">
      XLSX 文件
      <small v-if="fileError" id="xlsx-file-error" class="form-field-error" role="alert">
        {{ fileError }}
      </small>
    </span>
    <button
      type="button"
      class="upload-dropzone"
      :class="{ dragging: isDragging, invalid: fileError }"
      aria-label="上传 XLSX"
      :aria-describedby="fileError ? 'xlsx-file-error' : undefined"
      :aria-invalid="Boolean(fileError)"
      @click="openFilePicker"
      @dragenter.prevent="isDragging = true"
      @dragover.prevent="isDragging = true"
      @dragleave.prevent="isDragging = false"
      @drop.prevent="handleDrop"
    >
      <Upload :size="16" />
      <span>点击上传或拖拽 XLSX 到这里</span>
    </button>
    <input
      ref="fileInput"
      class="visually-hidden"
      type="file"
      accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
      :aria-describedby="fileError ? 'xlsx-file-error' : undefined"
      :aria-invalid="Boolean(fileError)"
      @change="handleFileChange"
    />
  </div>
</template>
