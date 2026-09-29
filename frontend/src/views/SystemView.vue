<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
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

const session = useSession()
const base = apiBase()
const users = ref<SystemUser[]>([])
const roles = ref<SystemRole[]>([])
const permissions = ref<PermissionPoint[]>([])
const projects = ref<Project[]>([])
const selectedRole = ref<SystemRole | null>(null)
const roleChecked = ref<string[]>([])
const scopeUser = ref<SystemUser | null>(null)
const scopeChecked = ref<number[]>([])
const newUser = ref({ login_name: '', display_name: '', password: '', role_codes: [] as string[] })
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

async function resetPassword(user: SystemUser) {
  if (!session.user.value || !session.token.value) return
  const password = window.prompt(`为用户 ${user.login_name} 设置新密码（至少 8 位）`)
  if (!password?.trim()) return
  try {
    await resetUserPassword(
      base,
      session.token.value,
      session.user.value.tenant_id,
      user.id,
      password.trim(),
    )
    message.value = `用户 ${user.login_name} 密码已重置。`
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
    scopeUser.value = null
    await load()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '项目范围保存失败'
  }
}

function editRole(role: SystemRole) {
  selectedRole.value = role
  roleChecked.value = [...(role.permission_codes ?? [])]
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
    selectedRole.value = null
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
    <p v-if="message" class="feedback-text">{{ message }}</p>

    <article class="panel table-panel">
      <div class="section-heading">
        <div>
          <h3>用户管理</h3>
          <span class="meta">新建用户、分配角色、启停和重置密码</span>
        </div>
        <span v-if="loading" class="meta">加载中...</span>
      </div>
      <div class="filter-bar">
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
        <button class="small-primary-button" type="button" @click="createUser">新建用户</button>
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
                <span class="pill">{{ user.status }}</span>
              </td>
              <td>{{ user.last_login_at || '-' }}</td>
              <td>
                <div class="action-group">
                  <button class="text-button" type="button" @click="toggleStatus(user)">
                    {{ user.status === 'active' ? '停用' : '启用' }}</button
                  ><button class="text-button" type="button" @click="resetPassword(user)">
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

    <article v-if="scopeUser" class="panel inline-edit-form">
      <h3>项目数据范围：{{ scopeUser.display_name }}</h3>
      <p class="meta">仅对 BUSINESS 角色用户生效；勾选该用户可见的项目。</p>
      <div class="mini-list">
        <label v-for="project in projects" :key="project.id" class="checkbox-label">
          <input v-model="scopeChecked" type="checkbox" :value="project.id" />
          {{ project.project_no }} · {{ project.project_name }}
        </label>
      </div>
      <div class="action-group">
        <button class="primary-button" type="button" @click="saveScope">保存范围</button
        ><button class="ghost-button" type="button" @click="scopeUser = null">取消</button>
      </div>
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

    <article v-if="selectedRole" class="panel inline-edit-form">
      <h3>编辑角色权限：{{ selectedRole.role_name }}</h3>
      <div v-for="(points, module) in permissionsByModule" :key="module" class="detail-section">
        <h3>{{ module }}</h3>
        <label v-for="point in points" :key="point.permission_code" class="checkbox-label">
          <input v-model="roleChecked" type="checkbox" :value="point.permission_code" />
          {{ point.permission_name }}（{{ point.permission_code }}）
        </label>
      </div>
      <div class="action-group">
        <button class="primary-button" type="button" @click="saveRolePermissions">保存权限</button
        ><button class="ghost-button" type="button" @click="selectedRole = null">取消</button>
      </div>
    </article>
  </section>
</template>
