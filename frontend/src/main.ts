import { createApp } from 'vue'
import App from './App.vue'
import './styles.css'
import router from './router'
import { vPermission } from './directives/permission'
import { useSession } from './session'

const session = useSession()
window.addEventListener('auth:unauthorized', () => {
  const target = router.currentRoute.value.fullPath
  session.signOut()
  if (router.currentRoute.value.name !== 'login') {
    void router.replace({ name: 'login', query: { redirect: target } })
  }
})

createApp(App).use(router).directive('permission', vPermission).mount('#app')
