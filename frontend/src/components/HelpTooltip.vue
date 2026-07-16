<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, useId } from 'vue'
import { CircleHelp } from '@lucide/vue'

defineProps({
  ariaLabel: {
    type: String,
    required: true
  }
})

const triggerRef = ref(null)
const tooltipRef = ref(null)
const open = ref(false)
const focused = ref(false)
const hovered = ref(false)
const tooltipStyle = ref({})
const tooltipId = `help-tooltip-${useId()}`

onMounted(() => {
  window.addEventListener('resize', updatePosition)
  window.addEventListener('scroll', updatePosition, true)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', updatePosition)
  window.removeEventListener('scroll', updatePosition, true)
})

function showTooltip() {
  open.value = true
  nextTick(updatePosition)
}

function handleFocus() {
  focused.value = true
  showTooltip()
}

function handleBlur() {
  focused.value = false

  if (!hovered.value) {
    open.value = false
  }
}

function handleMouseEnter() {
  hovered.value = true
  showTooltip()
}

function handleMouseLeave() {
  hovered.value = false

  if (!focused.value) {
    open.value = false
  }
}

function closeTooltip() {
  open.value = false
  triggerRef.value?.blur()
}

function updatePosition() {
  if (!open.value || !triggerRef.value || !tooltipRef.value) {
    return
  }

  const viewportPadding = 12
  const gap = 8
  const triggerRect = triggerRef.value.getBoundingClientRect()
  const tooltipRect = tooltipRef.value.getBoundingClientRect()
  const left = Math.min(
    Math.max(viewportPadding, triggerRect.right - tooltipRect.width),
    window.innerWidth - tooltipRect.width - viewportPadding
  )
  const spaceBelow = window.innerHeight - triggerRect.bottom - viewportPadding
  const top = spaceBelow >= tooltipRect.height + gap
    ? triggerRect.bottom + gap
    : Math.max(viewportPadding, triggerRect.top - tooltipRect.height - gap)

  tooltipStyle.value = {
    left: `${left}px`,
    top: `${top}px`
  }
}
</script>

<template>
  <span
    class="help-tooltip"
    @mouseenter="handleMouseEnter"
    @mouseleave="handleMouseLeave"
  >
    <span
      ref="triggerRef"
      class="help-tooltip-trigger"
      tabindex="0"
      :aria-label="ariaLabel"
      :aria-describedby="tooltipId"
      @focus="handleFocus"
      @blur="handleBlur"
      @keydown.esc.prevent="closeTooltip"
    >
      <CircleHelp :size="17" aria-hidden="true" />
    </span>

    <span
      v-show="open"
      :id="tooltipId"
      ref="tooltipRef"
      class="help-tooltip-content"
      :style="tooltipStyle"
      role="tooltip"
    >
      <slot />
    </span>
  </span>
</template>

<style scoped>
.help-tooltip {
  display: inline-flex;
  flex: 0 0 auto;
}

.help-tooltip-trigger {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 999px;
  color: var(--muted);
  cursor: help;
}

.help-tooltip-trigger:hover {
  color: var(--primary);
}

.help-tooltip-trigger:focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: 2px;
  color: var(--primary);
}

.help-tooltip-content {
  position: fixed;
  z-index: 2147483647;
  display: block;
  width: min(360px, calc(100vw - 24px));
  border: 1px solid var(--line-strong);
  border-radius: 10px;
  padding: 10px 12px;
  background: var(--surface);
  color: var(--text);
  box-shadow: var(--shadow);
  font-size: 0.8rem;
  font-weight: 600;
  line-height: 1.45;
  pointer-events: none;
}

.help-tooltip-content :deep(p) {
  margin: 0;
}

.help-tooltip-content :deep(p + p) {
  margin-top: 7px;
}

.help-tooltip-content :deep(strong) {
  color: var(--primary);
}
</style>
