<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { login } from '../services/auth'
import { apiBase, useSession } from '../session'
import { landingPath } from '../utils/navigation'

const router = useRouter()
const session = useSession()
// 版本号来自构建期注入的 package.json version（见 vite.config.ts define），
// 不用原型稿里的 v2.0——那是编造值，真实版本为 package.json 所载
const appVersion = __APP_VERSION__
// Q-04（2026-10-21 修正）：登录名不再预填 finance01，避免私有化部署下页面泄露默认账号
const loginName = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')

async function submit() {
  loading.value = true
  error.value = ''
  try {
    session.setUser(await login(apiBase(), loginName.value.trim(), password.value))
    await router.push(landingPath(session.user.value))
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
        <label
          >登录名<input
            v-model="loginName"
            autocomplete="username"
            placeholder="请输入登录名"
            required /></label
        ><label
          >密码<input
            v-model="password"
            type="password"
            autocomplete="current-password"
            placeholder="请输入密码"
            required
        /></label>
        <p v-if="error" class="error-text">{{ error }}</p>
        <button class="primary-button" :disabled="loading" type="submit">
          {{ loading ? '登录中...' : '登录' }}
        </button>
      </form>
      <!-- 页脚：版本号取 package.json 真实值（原型的 v2.0 为编造值，不采用）；
           「私有化部署」依据 PRD §0 首期部署模式。未登录态不显示用户名。 -->
      <p class="login-footer">v{{ appVersion }} · 私有化部署</p>
    </section>
  </main>
</template>
