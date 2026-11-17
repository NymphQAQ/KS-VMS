<script setup>
import { computed, defineAsyncComponent, onMounted, onUnmounted, ref } from 'vue'
import { Building2, CalendarDays, ChartNoAxesCombined, ChevronDown, ClipboardCheck, DoorOpen, LayoutDashboard, LogOut, MapPin, Menu, QrCode, ScanLine, Settings2, ShieldCheck, UsersRound, X } from 'lucide-vue-next'
import { post, staff } from './api.js'
import { roles } from './format.js'
import GateDesk from './GateDesk.vue'
import Requests from './Requests.vue'
import RegistrationCodes from './RegistrationCodes.vue'
import SafetyDesk from './SafetyDesk.vue'
import Settings from './Settings.vue'
const Reports = defineAsyncComponent(() => import('./Reports.vue'))

const user = ref()
const workspace = ref({ sites: [], gates: [] })
const siteId = ref('')
const gateId = ref('')
const section = ref('')
const landing = ref(location.hash.slice(1))
const mobileMenu = ref(false)
const username = ref('')
const password = ref('')
const otp = ref('')
const mfa = ref(false)
const error = ref('')
const busy = ref(false)
const loading = ref(true)
const grants = computed(() => user.value?.grants || [])
const site = computed(() => workspace.value.sites.find(item => item.id === siteId.value))
const gates = computed(() => workspace.value.gates.filter(item => item.siteId === siteId.value))
const gate = computed(() => gates.value.find(item => item.id === gateId.value))
const guards = computed(() => grants.value.some(item => item.role === 'GUARD'))
const has = (...values) => grants.value.some(item => values.includes(item.role) && (!site.value || !item.siteId || item.siteId === site.value.id))
const administrator = computed(() => grants.value.some(item => item.role === 'ADMIN'))
const groups = computed(() => [
  { name: '工作台', items: [
    ...(has('MANAGER', 'ADMINISTRATION', 'SAFETY') ? [{ id: 'home', name: '首页工作台', icon: LayoutDashboard }] : []),
    ...(guards.value ? [{ id: 'gate', name: '智慧门岗', icon: ScanLine }] : []),
    ...(has('EMPLOYEE') ? [{ id: 'invitations', name: '员工邀请', icon: CalendarDays }] : []),
    ...(has('ADMINISTRATION', 'SAFETY') ? [{ id: 'records', name: '预约记录', icon: UsersRound }] : []),
    ...(grants.value.some(item => ['OWNER','PROCUREMENT','WAREHOUSE','LOGISTICS','PROJECT','SAFETY','EQUIPMENT','HR','IT','HOST_SUPERVISOR'].includes(item.role))
      ? [{ id: 'approvals', name: '审批管理', icon: ClipboardCheck }] : []),
  ] },
  { name: '管理', items: [
    ...(has('ADMIN', 'ADMINISTRATION') ? [{ id: 'qr', name: '二维码管理', icon: QrCode }] : []),
    ...(has('SAFETY') ? [{ id: 'safety', name: '安全管理', icon: ShieldCheck }] : []),
    ...(has('MANAGER', 'ADMINISTRATION', 'SAFETY', 'EXPORT') ? [{ id: 'reports', name: '数据统计', icon: ChartNoAxesCombined }] : []),
    ...(administrator.value ? [{ id: 'settings', name: '系统管理', icon: Settings2 }] : []),
  ] },
].filter(group => group.items.length))
const current = computed(() => groups.value.flatMap(group => group.items).find(item => item.id === section.value))

async function initialize() {
  workspace.value = await staff('/workspace')
  const storedSite = Number(sessionStorage.getItem('vms-site'))
  const storedGate = Number(sessionStorage.getItem('vms-gate'))
  siteId.value = workspace.value.sites.some(item => item.id === storedSite) ? storedSite : workspace.value.sites[0]?.id || ''
  gateId.value = gates.value.some(item => item.id === storedGate) ? storedGate : gates.value[0]?.id || ''
  const preferred = landing.value
  section.value = groups.value.flatMap(group => group.items).some(item => item.id === preferred) ? preferred
    : guards.value && gate.value ? 'gate' : groups.value[0]?.items[0]?.id || ''
  alignGate()
}

async function login() {
  busy.value = true
  error.value = ''
  try {
    user.value = await staff('/sessions', post({ username: username.value.trim(), password: password.value, code: otp.value || null }))
    password.value = ''
    otp.value = ''
    mfa.value = false
    await initialize()
  } catch (failure) {
    error.value = failure.message
    if (failure.code === 'MFA_REQUIRED') mfa.value = true
  } finally {
    busy.value = false
  }
}

async function logout() {
  try {
    await staff('/sessions/current', { method: 'DELETE' })
    user.value = null
    section.value = ''
  } catch (failure) { error.value = failure.message }
}

function navigate(id) {
  landing.value = id
  section.value = id
  alignGate()
  history.replaceState(null, '', '#' + id)
  mobileMenu.value = false
}

function hashChanged() {
  landing.value = location.hash.slice(1)
  if (groups.value.some(group => group.items.some(item => item.id === landing.value))) {
    section.value = landing.value
    alignGate()
    mobileMenu.value = false
  }
}

function alignGate() {
  const first = workspace.value.gates[0]
  if (section.value === 'gate' && !gate.value && first) {
    siteId.value = first.siteId
    gateId.value = first.id
  }
}

function selectSite() {
  sessionStorage.setItem('vms-site', siteId.value)
  gateId.value = gates.value[0]?.id || ''
  if (!current.value && groups.value.length) navigate(groups.value[0].items[0].id)
}

function expired() {
  user.value = null
  section.value = ''
  error.value = '登录已过期，请重新登录'
}

onMounted(async () => {
  window.addEventListener('vms-session-expired', expired)
  window.addEventListener('hashchange', hashChanged)
  try {
    user.value = await staff('/me')
    await initialize()
  } catch (failure) {
    if (failure.status !== 401) error.value = failure.message
  } finally { loading.value = false }
})
onUnmounted(() => {
  window.removeEventListener('vms-session-expired', expired)
  window.removeEventListener('hashchange', hashChanged)
})
</script>

<template>
  <div v-if="loading" class="screen-message">正在加载工作台…</div>
  <main v-else-if="!user" class="login-screen">
    <div class="login-context">
      <div class="login-art" aria-hidden="true"><Building2 :size="164" :stroke-width="0.55" /></div>
      <div class="brand-mark"><DoorOpen :size="24" /></div>
      <h1>厂区通行</h1><p>入厂登记管理系统</p>
    </div>
    <div class="login-content">
      <form class="login-form form-stack" @submit.prevent="login">
        <h2>{{ landing === 'gate' ? '门卫登录' : '工作台登录' }}</h2>
        <label>账号<input v-model="username" required maxlength="64" autocomplete="username" placeholder="请输入员工账号" @input="mfa = false; otp = ''"></label>
        <label>密码<input v-model="password" required type="password" maxlength="72" autocomplete="current-password" placeholder="请输入密码"></label>
        <label v-if="mfa">验证器验证码<input v-model="otp" required pattern="[0-9]{6}" maxlength="6" inputmode="numeric" autocomplete="one-time-code"></label>
        <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
        <button class="button primary" :disabled="busy" type="submit">{{ busy ? '正在登录…' : '进入工作台' }}</button>
        <a v-if="landing !== 'gate'" href="#gate">门卫入口</a>
      </form>
    </div>
  </main>
  <div v-else class="workspace">
    <button v-if="mobileMenu" class="mobile-overlay" aria-label="关闭菜单" @click="mobileMenu = false"></button>
    <aside class="sidebar" :class="{ 'mobile-open': mobileMenu }">
      <div class="sidebar-brand">
        <div class="brand-mark"><DoorOpen :size="23" /></div>
        <div><strong>厂区通行</strong><span>入厂登记管理系统</span></div>
        <button type="button" class="mobile-close" aria-label="关闭菜单" @click="mobileMenu = false"><X :size="20" /></button>
      </div>
      <nav aria-label="工作台导航">
        <section v-for="group in groups" :key="group.name" class="nav-group">
          <p>{{ group.name }}</p>
          <button v-for="item in group.items" :key="item.id" class="nav-item" type="button" :aria-current="section === item.id ? 'page' : undefined" @click="navigate(item.id)">
            <component :is="item.icon" :size="18" /><span>{{ item.name }}</span>
          </button>
        </section>
      </nav>
      <div class="sidebar-bottom"><span class="connection-dot"></span><span>厂区访问管理</span></div>
    </aside>
    <section class="workspace-main">
      <header class="workspace-top">
        <div class="header-context"><button type="button" class="button ghost menu-toggle" aria-label="打开菜单" @click="mobileMenu = true"><Menu :size="20" /></button>
          <span class="crumb">工作台</span><ChevronDown class="crumb-divider" :size="14" /><strong>{{ current?.name || '厂区通行' }}</strong>
        </div>
        <div class="header-controls">
          <label v-if="workspace.sites.length" class="header-select"><MapPin :size="16" /><select v-model="siteId" aria-label="当前厂区" @change="selectSite">
            <option v-for="location in workspace.sites" :key="location.id" :value="location.id">{{ location.name }}</option>
          </select></label>
          <label v-if="gates.length && section === 'gate'" class="header-select"><select v-model="gateId" aria-label="当前门岗" @change="event => sessionStorage.setItem('vms-gate', event.target.value)">
            <option v-for="location in gates" :key="location.id" :value="location.id">{{ location.name }}</option>
          </select></label>
          <div class="user-block"><span class="avatar">{{ user.name.slice(0, 1) }}</span><span><strong>{{ user.name }}</strong><small>{{ roles[user.grants[0]?.role] || '员工' }}</small></span></div>
          <button type="button" class="button icon-button" aria-label="退出登录" title="退出登录" @click="logout"><LogOut :size="18" /></button>
        </div>
      </header>
      <main class="workspace-content">
        <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
        <div v-if="section === 'gate' && !gate" class="screen-message">当前厂区暂无可用门岗，请切换厂区或联系系统管理员。</div>
        <GateDesk v-else-if="site && ['gate', 'home', 'records'].includes(section)" :key="section" :site="site" :gate="gate"
          :read-only="section !== 'gate'" :records-only="section === 'records'" :can-receive="has('GUARD','ADMINISTRATION')" />
        <Requests v-else-if="section === 'approvals' || section === 'invitations'" :key="section" :mode="section" :site="site" />
        <RegistrationCodes v-else-if="section === 'qr' && site" :key="site.id" :site="site" />
        <SafetyDesk v-else-if="section === 'safety' && site" :site="site" :global="has('SAFETY') && grants.some(item => item.role === 'SAFETY' && !item.siteId)" />
        <Reports v-else-if="section === 'reports' && site" :site="site" :can-export="has('EXPORT')" :can-view="has('MANAGER','ADMINISTRATION','SAFETY')" />
        <Settings v-else-if="section === 'settings'" @changed="initialize" />
        <div v-else class="screen-message">暂无可用厂区或工作权限，请联系系统管理员。</div>
      </main>
    </section>
  </div>
</template>
