<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import ModalPanel from '../components/ModalPanel.vue'
import { loadProjects, type Project } from '../services/projects'
import {
  createSystemUser,
  loadPermissionPoints,
  loadSystemRoles,
  loadSystemUsers,
  loadUserProjectScope,
  resetUserPassword,
  updateRolePermissions,
  updateSystemUser,
  updateUserProjectScope,
  type PermissionPoint,
  type SystemRole,
  type SystemUser,
} from '../services/system'
import { apiBase, useSession } from '../session'
import { permissionModuleLabel, userStatusLabel } from '../utils/labels'

const session = useSession()
const base = apiBase()
const users = ref<SystemUser[]>([])
const roles = ref<SystemRole[]>([])
const permissions = ref<PermissionPoint[]>([])
const projects = ref<Project[]>([])
const selectedRole = ref<SystemRole | null>(null)
const roleChecked = ref<string[]>([])
const roleDone = ref(false)
const scopeUser = ref<SystemUser | null>(null)
const scopeChecked = ref<number[]>([])
const scopeDone = ref(false)
const newUser = ref({ login_name: '', display_name: '', password: '', role_codes: [] as string[] })
const userModalOpen = ref(false)
const userDone = ref(false)
const resetTarget = ref<SystemUser | null>(null)
const resetPwd = ref('')
const resetDone = ref(false)
const disableTarget = ref<SystemUser | null>(null)
const loading = ref(false)
const error = ref('')
const message = ref('')

const permissionsByModule = computed(() => {
  const grouped: Record<string, PermissionPoint[]> = {}
  for (const point of permissions.value) {
    if (!grouped[point.module]) grouped[point.module] = []
    grouped[point.module].push(point)
  }
  return grouped
})

const anyModalOpen = computed(
  () =>
    userModalOpen.value ||
    resetTarget.value !== null ||
    disableTarget.value !== null ||
    scopeUser.value !== null ||
    selectedRole.value !== null,
)

function openUserModal() {
  newUser.value = { login_name: '', display_name: '', password: '', role_codes: [] }
  message.value = ''
  userDone.value = false
  userModalOpen.value = true
}

function closeUserModal() {
  userModalOpen.value = false
}

function openReset(user: SystemUser) {
  resetTarget.value = user
  resetPwd.value = ''
  resetDone.value = false
  message.value = ''
}

function closeReset() {
  resetTarget.value = null
}

function askDisable(user: SystemUser) {
  if (user.status === 'active') {
    disableTarget.value = user
    return
  }
  toggleStatus(user)
}

function cancelDisable() {
  disableTarget.value = null
}

async function confirmDisable() {
  const target = disableTarget.value
  if (!target) return
  disableTarget.value = null
  await toggleStatus(target)
}

function closeScope() {
  scopeUser.value = null
  scopeDone.value = false
}

function closeRole() {
  selectedRole.value = null
  roleDone.value = false
}

async function load() {
  if (!session.user.value || !session.token.value) return
  loading.value = true
  error.value = ''
  try {
    const tenant = session.user.value.tenant_id
    const [userPage, roleList, pointList, projectPage] = await Promise.all([
      loadSystemUsers(base, session.token.value, tenant),
      loadSystemRoles(base, session.token.value, tenant),
      loadPermissionPoints(base, session.token.value, tenant),
      loadProjects(base, session.token.value, tenant, 1, 100),
    ])
    users.value = userPage.data.items
    roles.value = roleList.data
    permissions.value = pointList.data
    projects.value = projectPage.data.items
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '系统管理数据加载失败'
  } finally {
    loading.value = false
  }
}

async function createUser() {
  if (!session.user.value || !session.token.value) return
  if (!newUser.value.login_name || !newUser.value.display_name || !newUser.value.password) {
    message.value = '请填写登录名、姓名和密码'
    return
  }
  try {
    await createSystemUser(base, session.token.value, session.user.value.tenant_id, newUser.value)
    message.value = `用户 ${newUser.value.login_name} 已创建。`
    newUser.value = { login_name: '', display_name: '', password: '', role_codes: [] }
    userDone.value = true
    await load()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '用户创建失败'
  }
}

async function toggleStatus(user: SystemUser) {
  if (!session.user.value || !session.token.value) return
  const next = user.status === 'active' ? 'disabled' : 'active'
  try {
    await updateSystemUser(base, session.token.value, session.user.value.tenant_id, user.id, {
      status: next,
    })
    message.value = `用户 ${user.login_name} 已${next === 'active' ? '启用' : '停用'}。`
    await load()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '用户状态更新失败'
  }
}

async function resetPassword() {
  if (!session.user.value || !session.token.value || !resetTarget.value) return
  if (!resetPwd.value.trim()) {
    message.value = '请输入新密码'
    return
  }
  try {
    await resetUserPassword(
      base,
      session.token.value,
      session.user.value.tenant_id,
      resetTarget.value.id,
      resetPwd.value.trim(),
    )
    message.value = `用户 ${resetTarget.value.login_name} 密码已重置。`
    resetDone.value = true
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '密码重置失败'
  }
}

async function editScope(user: SystemUser) {
  if (!session.user.value || !session.token.value) return
  try {
    scopeChecked.value = (
      await loadUserProjectScope(base, session.token.value, session.user.value.tenant_id, user.id)
    ).data
    scopeDone.value = false
    message.value = ''
    scopeUser.value = user
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '项目范围加载失败'
  }
}

async function saveScope() {
  if (!session.user.value || !session.token.value || !scopeUser.value) return
  try {
    await updateUserProjectScope(
      base,
      session.token.value,
      session.user.value.tenant_id,
      scopeUser.value.id,
      scopeChecked.value,
    )
    message.value = `用户 ${scopeUser.value.login_name} 的项目数据范围已更新。`
    scopeDone.value = true
    await load()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '项目范围保存失败'
  }
}

function editRole(role: SystemRole) {
  selectedRole.value = role
  roleChecked.value = [...(role.permission_codes ?? [])]
  roleDone.value = false
  message.value = ''
}

async function saveRolePermissions() {
  if (!session.user.value || !session.token.value || !selectedRole.value) return
  try {
    await updateRolePermissions(
      base,
      session.token.value,
      session.user.value.tenant_id,
      selectedRole.value.id,
      roleChecked.value,
    )
    message.value = `角色 ${selectedRole.value.role_name} 的权限已更新。`
    roleDone.value = true
    await load()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '角色权限保存失败'
  }
}

onMounted(load)
</script>

<template>
  <section class="page-shell">
    <header class="hero-band">
      <div>
        <p class="eyebrow">系统管理</p>
        <h2>用户、角色与数据范围</h2>
        <p class="lead">管理租户内用户账号、角色权限点和业务用户的项目数据范围。</p>
      </div>
      <span class="meta">共 {{ users.length }} 个用户</span>
    </header>
    <p v-if="error" class="error-banner">{{ error }}</p>
    <p v-if="message && !anyModalOpen" class="feedback-text">{{ message }}</p>

    <article class="panel table-panel">
      <div class="section-heading">
        <div>
          <h3>用户管理</h3>
          <span class="meta">新建用户、分配角色、启停和重置密码</span>
        </div>
        <div class="action-group">
          <span v-if="loading" class="meta">加载中...</span>
          <button class="small-primary-button" type="button" @click="openUserModal">
            新建用户
          </button>
        </div>
      </div>
      <div v-if="users.length" class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>登录名</th>
              <th>姓名</th>
              <th>角色</th>
              <th>项目范围</th>
              <th>状态</th>
              <th>最近登录</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="user in users" :key="user.id">
              <td>{{ user.login_name }}</td>
              <td>{{ user.display_name }}</td>
              <td>{{ (user.roles ?? []).join('、') || '-' }}</td>
              <td>{{ user.scoped_project_count ?? 0 }} 个项目</td>
              <td>
                <span class="pill">{{ userStatusLabel(user.status) }}</span>
              </td>
              <td>{{ user.last_login_at || '-' }}</td>
              <td>
                <div class="action-group">
                  <button class="text-button" type="button" @click="askDisable(user)">
                    {{ user.status === 'active' ? '停用' : '启用' }}</button
                  ><button class="text-button" type="button" @click="openReset(user)">
                    重置密码</button
                  ><button class="text-button" type="button" @click="editScope(user)">
                    项目范围
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="empty-state">暂无用户。</p>
    </article>

    <article class="panel table-panel">
      <div class="section-heading">
        <div>
          <h3>角色权限</h3>
          <span class="meta">管理员角色的权限不可修改</span>
        </div>
      </div>
      <div v-if="roles.length" class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>角色</th>
              <th>名称</th>
              <th>权限点数</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="role in roles" :key="role.id">
              <td>{{ role.role_code }}</td>
              <td>{{ role.role_name }}</td>
              <td>{{ (role.permission_codes ?? []).length }}</td>
              <td>
                <button class="text-button" type="button" @click="editRole(role)">编辑权限</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </article>

    <ModalPanel v-if="userModalOpen" title="新建用户" @close="closeUserModal">
      <label>登录名<input v-model.trim="newUser.login_name" placeholder="如 cashier02" /></label>
      <label>姓名<input v-model.trim="newUser.display_name" placeholder="如 出纳乙" /></label>
      <label
        >密码<input v-model="newUser.password" type="password" placeholder="至少 8 位"
      /></label>
      <label
        >角色<select v-model="newUser.role_codes" multiple>
          <option v-for="role in roles" :key="role.role_code" :value="role.role_code">
            {{ role.role_name }}
          </option>
        </select></label
      >
      <div class="action-group">
        <button class="primary-button" type="button" @click="createUser">确认新建</button>
      </div>
      <p v-if="message" class="feedback-text">{{ message }}</p>
      <template #footer>
        <button v-if="userDone" class="primary-button" type="button" @click="closeUserModal">
          关闭
        </button>
      </template>
    </ModalPanel>

    <ModalPanel
      v-if="resetTarget"
      :title="`重置密码：${resetTarget.login_name}`"
      @close="closeReset"
    >
      <label>新密码<input v-model="resetPwd" type="password" placeholder="至少 8 位" /></label>
      <div class="action-group">
        <button class="primary-button" type="button" @click="resetPassword">确认重置</button>
      </div>
      <p v-if="message" class="feedback-text">{{ message }}</p>
      <template #footer>
        <button v-if="resetDone" class="primary-button" type="button" @click="closeReset">
          关闭
        </button>
      </template>
    </ModalPanel>

    <ModalPanel
      v-if="disableTarget"
      :title="`停用用户：${disableTarget.login_name}`"
      @close="cancelDisable"
    >
      <p>停用后该用户将无法登录系统，是否继续？</p>
      <template #footer>
        <button class="ghost-button" type="button" @click="cancelDisable">取消</button>
        <button class="small-danger-button" type="button" @click="confirmDisable">确认</button>
      </template>
    </ModalPanel>

    <ModalPanel
      v-if="scopeUser"
      :title="`项目数据范围：${scopeUser.display_name}`"
      @close="closeScope"
    >
      <p class="meta">仅对 BUSINESS 角色用户生效；勾选该用户可见的项目。</p>
      <div class="mini-list">
        <label v-for="project in projects" :key="project.id" class="checkbox-label">
          <input v-model="scopeChecked" type="checkbox" :value="project.id" />
          {{ project.project_no }} · {{ project.project_name }}
        </label>
      </div>
      <div class="action-group">
        <button class="primary-button" type="button" @click="saveScope">保存范围</button>
      </div>
      <p v-if="message" class="feedback-text">{{ message }}</p>
      <template #footer>
        <button v-if="scopeDone" class="primary-button" type="button" @click="closeScope">
          关闭
        </button>
      </template>
    </ModalPanel>

    <ModalPanel
      v-if="selectedRole"
      :title="`编辑角色权限：${selectedRole.role_name}`"
      @close="closeRole"
    >
      <div v-for="(points, module) in permissionsByModule" :key="module" class="detail-section">
        <h3>{{ permissionModuleLabel(module) }}</h3>
        <label v-for="point in points" :key="point.permission_code" class="checkbox-label">
          <input v-model="roleChecked" type="checkbox" :value="point.permission_code" />
          {{ point.permission_name }}（{{ point.permission_code }}）
        </label>
      </div>
      <div class="action-group">
        <button class="primary-button" type="button" @click="saveRolePermissions">保存权限</button>
      </div>
      <p v-if="message" class="feedback-text">{{ message }}</p>
      <template #footer>
        <button v-if="roleDone" class="primary-button" type="button" @click="closeRole">
          关闭
        </button>
      </template>
    </ModalPanel>
  </section>
</template>
