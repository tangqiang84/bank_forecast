<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useSession } from '../session'

const session = useSession()
const router = useRouter()

function signOut() {
  session.signOut()
  router.push('/login')
}
</script>

<template>
  <section class="page-shell">
    <article class="panel">
      <h2>没有访问权限</h2>
      <p class="lead">当前账号没有访问该页面的权限，请联系管理员调整角色或权限。</p>
      <RouterLink class="ghost-button" to="/">返回工作区</RouterLink>
      <button class="ghost-button" type="button" @click="signOut">退出登录</button>
    </article>
  </section>
</template>
