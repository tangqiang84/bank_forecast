<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ bankCode: string; bankName: string }>()

// 各银行主色调（近似行徽色）
const BANK_COLORS: Record<string, string> = {
  BOC: '#a6192e',
  ICBC: '#c7000b',
  CGB: '#d21034',
  PAB: '#f26f21',
  BOSC: '#005bac',
  SUZB: '#006b54',
  BEA: '#003c7e',
  CMB: '#e60012',
  NBCB: '#d7000f',
}

const color = computed(() => BANK_COLORS[props.bankCode] ?? '#16324f')
const initial = computed(() => props.bankName.trim().charAt(0) || props.bankCode.charAt(0))
</script>

<template>
  <span
    class="bank-icon"
    :style="{ backgroundColor: color }"
    role="img"
    :aria-label="`${bankName}图标`"
    >{{ initial }}</span
  >
</template>
