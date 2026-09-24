import type { Directive } from 'vue'
import { useSession } from '../session'
import { hasPermission } from '../utils/permission'

export const vPermission: Directive<HTMLElement, string> = {
  mounted(el, binding) {
    if (!binding.value) return
    const session = useSession()
    if (!hasPermission(session.user.value, binding.value)) {
      el.parentNode?.removeChild(el)
    }
  },
}
