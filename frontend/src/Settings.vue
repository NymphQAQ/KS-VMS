<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElDialog } from 'element-plus'
import 'element-plus/es/components/dialog/style/css'
import { Plus, Settings2 } from 'lucide-vue-next'
import { params, post, staff } from './api.js'
import { roles, time, toInstant, types } from './format.js'

const emit = defineEmits(['changed'])
const config = ref({ sites: [], gates: [], areas: [], departments: [], users: [], roles: [], grants: [] })
const audit = ref([])
const unassigned = ref([])
const policySite = ref('')
const tab = ref('sites')
const modal = ref('')
const form = ref({})
const picked = ref()
const error = ref('')
const busy = ref(false)
const labels = { sites: '厂区及预约规则', gates: '门岗', areas: '访问区域', departments: '部门', users: '用户管理',
  grants: '角色与权限', policy: '审批规则', audit: '操作日志' }
const headings = {
  sites: [['name','厂区'],['zone','时区'],['leadMinutes','提前预约（分钟）'],['advanceDays','预约跨度（天）'],['waitMinutes','现场等待（分钟）'],['enabled','状态']],
  gates: [['name','门岗'],['siteId','厂区'],['enabled','状态']], areas: [['name','区域'],['siteId','厂区'],['enabled','状态']],
  departments: [['name','名称']], users: [['username','账号'],['name','姓名'],['departmentId','部门'],['enabled','状态']],
  grants: [['staffId','用户'],['role','角色'],['siteId','厂区'],['departmentId','部门'],['gateId','门岗'],['endAt','授权截止']],
  audit: [['createdAt','时间'],['staffName','操作者'],['action','操作'],['referenceId','对象编号'],['detail','详情']],
}
const flow = {
  CUSTOMER: '老板确认', SUPPLIER: '采购部门', DELIVERY: '仓库或采购', LOGISTICS: '物流部门',
  CONTRACTOR: '负责部门 → 安全部门', MAINTENANCE: '设备部门', INTERVIEW: '人事部门', SOFTWARE: '信息化部门',
}
const rows = computed(() => tab.value === 'audit' ? audit.value : config.value[tab.value] || [])
const canEdit = computed(() => ['sites','gates','areas','users'].includes(tab.value))
const gateOptions = computed(() => config.value.gates.filter(item => item.siteId === Number(form.value.siteId)))

async function run(action) {
  busy.value = true
  error.value = ''
  try { await action() }
  catch (failure) { error.value = failure.message }
  finally { busy.value = false }
}

async function refresh() {
  config.value = await staff('/admin/configuration')
  if (tab.value === 'audit') audit.value = await staff('/admin/audit')
  if (!policySite.value) policySite.value = config.value.sites[0]?.id || ''
  if (tab.value === 'policy') await refreshUnassigned()
}

async function refreshUnassigned() {
  unassigned.value = policySite.value ? await staff('/approval-rules/unassigned' + params({ site: policySite.value })) : []
}

function display(row, field) {
  const value = row[field]
  const labelLists = { siteId: config.value.sites, gateId: config.value.gates, departmentId: config.value.departments, staffId: config.value.users }
  if (field in labelLists) return labelLists[field].find(item => item.id === value)?.name || (tab.value === 'grants' ? '全部' : '—')
  if (field === 'role') return roles[value] || value
  if (field === 'enabled') return value ? '启用' : '停用'
  if (field === 'hostPublic') return value ? '是' : '否'
  if (field.endsWith('At')) return time(value)
  return value ?? '—'
}

function open(row) {
  picked.value = row
  form.value = { enabled: true, zone: 'Asia/Shanghai', leadMinutes: 120, advanceDays: 30, waitMinutes: 60,
    maxMinutes: 720, openTime: '00:00', closeTime: '00:00', ...row }
  if (row) {
    form.value.openTime = row.openTime?.slice(0, 5)
    form.value.closeTime = row.closeTime?.slice(0, 5)
  }
  error.value = ''
  modal.value = tab.value
}

async function persist() {
  await run(async () => {
    const input = form.value
    const id = picked.value?.id
    const fields = (...keys) => Object.fromEntries(keys.map(key => [key, input[key]]))
    const selected = modal.value
    let body
    if (selected === 'sites') body = fields('name','zone','leadMinutes','advanceDays','waitMinutes','maxMinutes','openTime','closeTime','enabled')
    else if (selected === 'users') body = { ...fields('name','username','departmentId'), enabled: input.enabled, hostPublic: Boolean(input.hostPublic),
      password: input.password || null, mfaSecret: input.mfaSecret || null }
    else if (selected === 'departments') body = fields('name')
    else if (selected === 'grants') {
      body = { ...fields('role','siteId','departmentId','gateId'), startAt: toInstant(input.startAt), endAt: toInstant(input.endAt) }
      await staff('/admin/users/' + input.staffId + '/grants', post(body))
    } else body = fields('siteId','name','enabled')
    if (selected !== 'grants') await staff('/admin/' + selected + params({ id }), post(body))
    modal.value = ''
    await refresh()
    emit('changed')
  })
}

function revoke(row) {
  if (window.confirm('确定撤销所选权限？')) {
    run(async () => { await staff('/admin/grants/' + row.id, { method: 'DELETE' }); await refresh(); emit('changed') })
  }
}

onMounted(() => run(refresh))
watch(tab, () => { if (['audit','policy'].includes(tab.value)) run(refresh) })
</script>

<template>
  <div class="dashboard">
    <header class="page-heading">
      <div><h1>系统管理</h1><p>配置厂区、用户及通行权限</p></div>
      <button v-if="!['audit','policy'].includes(tab)" type="button" class="button primary" @click="open()"><Plus :size="17" />{{ tab === 'grants' ? '分配权限' : '新增' }}</button>
    </header>
    <p v-if="error && !modal" class="inline-error" role="alert">{{ error }}</p>
    <section class="table-card">
      <div class="tabs"><button v-for="(label, key) in labels" :key="key" type="button" class="tab" :aria-pressed="tab === key" @click="tab = key">{{ label }}</button></div>
      <div v-if="tab === 'policy'" class="table-scroll">
        <p class="panel muted">现场来访先经接访部门主管审批。审批岗位的人员、厂区和代班期限在「角色与权限」中配置。</p>
        <table><thead><tr><th>来访类型</th><th>岗位审批</th></tr></thead>
          <tbody><tr v-for="(label, kind) in flow" :key="kind"><td>{{ types[kind] }}</td><td>{{ label }}</td></tr></tbody></table>
        <div class="table-top"><h2>待分派申请</h2>
          <label>厂区<select v-model="policySite" @change="run(refreshUnassigned)">
            <option v-for="site in config.sites" :key="site.id" :value="site.id">{{ site.name }}</option>
          </select></label>
        </div>
        <table><thead><tr><th>申请编号</th><th>来访类型</th><th>接访部门</th><th>待配置岗位</th><th>截止时间</th></tr></thead>
          <tbody>
            <tr v-for="row in unassigned" :key="row.id"><td>{{ row.id }}</td><td>{{ types[row.type] }}</td>
              <td>{{ row.departmentName }}</td><td>{{ roles[row.role] }}</td><td>{{ time(row.windowEnd, config.sites.find(site => site.id === policySite)?.zone) }}</td></tr>
            <tr v-if="!unassigned.length"><td colspan="5"><div class="empty">暂无待分派申请</div></td></tr>
          </tbody></table>
      </div>
      <div v-else class="table-scroll">
        <table><thead><tr><th v-for="[key, label] in headings[tab]" :key="key">{{ label }}</th><th v-if="canEdit || tab === 'grants'">操作</th></tr></thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td v-for="[key] in headings[tab]" :key="key">{{ display(row, key) }}</td>
              <td v-if="canEdit"><button type="button" class="button link" @click="open(row)">编辑</button></td>
              <td v-else-if="tab === 'grants'"><button type="button" class="button link" @click="revoke(row)">撤销</button></td>
            </tr>
            <tr v-if="!rows.length"><td :colspan="headings[tab].length + 1"><div class="empty"><Settings2 :size="26" />暂无记录</div></td></tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
  <ElDialog :model-value="Boolean(modal)" :title="labels[modal]" width="min(620px, calc(100vw - 30px))" align-center @close="modal = ''">
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <form v-if="modal" class="form-stack" @submit.prevent="persist">
      <div class="form-grid">
        <label v-if="['sites','gates','areas','departments','users'].includes(modal)">名称<input v-model="form.name" required maxlength="100"></label>
        <label v-if="['gates','areas','grants'].includes(modal)">厂区<select v-model="form.siteId" :required="modal !== 'grants'" :disabled="Boolean(picked?.id && modal !== 'grants')">
          <option :value="null">{{ modal === 'grants' ? '所有厂区' : '请选择' }}</option>
          <option v-for="site in config.sites" :key="site.id" :value="site.id">{{ site.name }}</option>
        </select></label>
        <label v-if="modal === 'grants' && form.role === 'GUARD'">门岗<select v-model="form.gateId"><option :value="null">所有门岗</option>
          <option v-for="gate in gateOptions" :key="gate.id" :value="gate.id">{{ gate.name }}</option>
        </select></label>
        <template v-if="modal === 'sites'">
          <label>时区<input v-model="form.zone" required></label>
          <label>提前预约（分钟）<input v-model.number="form.leadMinutes" required type="number" min="0" max="1440"></label>
          <label>预约跨度（天）<input v-model.number="form.advanceDays" required type="number" min="1" max="180"></label>
          <label>现场等待（分钟）<input v-model.number="form.waitMinutes" required type="number" min="1" max="1440"></label>
          <label>最长停留（分钟）<input v-model.number="form.maxMinutes" required type="number" min="1" max="10080"></label>
          <label>开放时间<input v-model="form.openTime" required type="time"></label>
          <label>关闭时间<input v-model="form.closeTime" required type="time"></label>
          <p class="muted wide">开始和结束时间相同时全天开放。</p>
        </template>
        <template v-if="modal === 'users'">
          <label>登录账号<input v-model="form.username" :readonly="Boolean(picked?.id)" required pattern="[a-zA-Z0-9._-]{3,64}" maxlength="64"></label>
          <label>{{ picked ? '重置密码（选填）' : '初始密码' }}<input v-model="form.password" type="password" minlength="12" maxlength="64" :required="!picked" autocomplete="new-password"></label>
          <label>验证器密钥（选填）<input v-model="form.mfaSecret" maxlength="64" autocomplete="off"></label>
        </template>
        <label v-if="modal === 'users' || modal === 'grants' && ['PROJECT','HOST_SUPERVISOR'].includes(form.role)">部门
          <select v-model="form.departmentId"><option :value="null">请选择</option><option v-for="department in config.departments" :key="department.id" :value="department.id">{{ department.name }}</option></select>
        </label>
        <template v-if="modal === 'grants'">
          <label>角色<select v-model="form.role" required><option v-for="role in config.roles" :key="role" :value="role">{{ roles[role] || role }}</option></select></label>
          <label>用户<select v-model="form.staffId" required><option v-for="person in config.users" :key="person.id" :value="person.id">{{ person.name }} · {{ person.username }}</option></select></label>
          <label>授权开始（选填，上海时区）<input v-model="form.startAt" type="datetime-local"></label>
          <label>授权截止（选填，上海时区）<input v-model="form.endAt" type="datetime-local"></label>
        </template>
        <label v-if="canEdit" class="checkline wide"><input v-model="form.enabled" type="checkbox"> 启用</label>
      </div>
      <button class="button primary" :disabled="busy" type="submit">保存</button>
    </form>
  </ElDialog>
</template>
