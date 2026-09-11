import { computed, ref } from 'vue'
import { getToken, loadCurrentUser, logout, type User } from './services/auth'

const token = ref(getToken())
const user = ref<User | null>(null)
const ready = ref(false)

export function useSession() {
  const loggedIn = computed(() => Boolean(token.value && user.value))

  async function restore() {
    if (token.value && !user.value) {
      try { user.value = await loadCurrentUser(apiBase(), token.value) } catch { signOut() }
    }
    ready.value = true
  }

  function setUser(next: User) {
    user.value = next
    token.value = getToken()
  }

  function signOut() {
    logout()
    user.value = null
    token.value = ''
  }

  return { token, user, ready, loggedIn, restore, setUser, signOut }
}

export function apiBase() {
  return import.meta.env.VITE_BACKEND_BASE_URL ?? 'http://localhost:8080'
}
