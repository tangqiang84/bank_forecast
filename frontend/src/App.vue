<script setup lang="ts">
import { onMounted } from 'vue'
import { RouterView } from 'vue-router'
import { useSession } from './session'
const session = useSession()
onMounted(() => session.restore())
</script>

<template><RouterView /></template>
