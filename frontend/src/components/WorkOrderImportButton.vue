<script setup>
import { ref } from 'vue'
import { Upload } from '@lucide/vue'
import { useWorkOrderStore } from '../stores/workOrderStore'

const store = useWorkOrderStore()
const fileInput = ref(null)
const isDragging = ref(false)

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
    store.error = '请上传 XLSX 文件'
    return
  }

  await store.importXlsx(file)
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
  <button
    type="button"
    class="upload-dropzone"
    :class="{ dragging: isDragging }"
    aria-label="上传 XLSX"
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
    @change="handleFileChange"
  />
</template>
