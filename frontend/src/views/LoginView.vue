<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { login } from '../services/auth'
import { apiBase, useSession } from '../session'

const router = useRouter()
const session = useSession()
const loginName = ref('finance01')
const password = ref('')
const loading = ref(false)
const error = ref('')

async function submit() {
  loading.value = true
  error.value = ''
  try {
    session.setUser(await login(apiBase(), loginName.value.trim(), password.value))
    await router.push('/dashboard')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '登录失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="auth-shell">
    <section class="auth-panel">
      <p class="eyebrow">银行资金智能连接器</p>
      <h1>登录资金工作台</h1>
      <p class="lead">进入驾驶舱、列表和业务详情。</p>
      <form class="login-form" @submit.prevent="submit">
        <label>登录名<input v-model="loginName" autocomplete="username" required /></label
        ><label
          >密码<input v-model="password" type="password" autocomplete="current-password" required
        /></label>
        <p v-if="error" class="error-text">{{ error }}</p>
        <button class="primary-button" :disabled="loading" type="submit">
          {{ loading ? '登录中...' : '登录' }}
        </button>
      </form>
    </section>
  </main>
</template>
