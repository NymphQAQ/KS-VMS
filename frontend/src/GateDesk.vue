<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElDialog } from 'element-plus'
import 'element-plus/es/components/dialog/style/css'
import { ArrowDownToLine, ArrowUpFromLine, CalendarDays, ChevronRight, CircleAlert, ClipboardPenLine, DoorOpen, LogIn, LogOut, RefreshCw, ScanLine, Search, Truck, UsersRound, Video } from 'lucide-vue-next'
import { params, post, staff } from './api.js'
import { stateNames, time, types } from './format.js'
import VisitForm from './VisitForm.vue'

const props = defineProps({ site: Object, gate: Object, readOnly: Boolean, recordsOnly: Boolean, canReceive: Boolean })
const summary = ref({})
const rows = ref([])
const search = ref('')
const state = ref('')
const error = ref('')
const busy = ref(false)
const mode = ref('')
const direction = ref('ENTRY')
const token = ref('')
const qrInput = ref()
const video = ref()
const camera = ref(false)
const selected = ref()
const picked = ref()
const correction = ref(false)
const correctionForm = ref({ action: 'EXIT', occurredAt: '', reason: '', documentType: 'ID', documentNumber: '' })
const identityChecked = ref(false)
const documentType = ref('ID')
const documentNumber = ref('')
const plate = ref('')
const form = ref()
const notice = ref('')
const modalError = ref('')
const timeline = ref()
const receptionOptions = ref()
const reception = ref({ departmentId: null, hostId: null, areaId: null })
const receptionHosts = computed(() => receptionOptions.value?.hosts.filter(person => person.departmentId === reception.value.departmentId) || [])
const needsArea = computed(() => receptionOptions.value?.types.find(type => type.code === timeline.value?.visit.type)?.area)
const canAssign = computed(() => props.canReceive && timeline.value?.visit.state === 'PENDING'
  && timeline.value.approvals.every(step => step.state === 'PENDING'))
const retry = ref()
let scanner, cameraRun = 0, timer, pageRequest = 0
const zone = computed(() => props.site?.zone || 'Asia/Shanghai')
const isScan = computed(() => mode.value === 'scan')
const isRegister = computed(() => ['walk', 'vehicle'].includes(mode.value))
const title = computed(() => ({
  scan: direction.value === 'ENTRY' ? '扫码入厂' : '扫码离厂',
  walk: '现场登记', vehicle: '车辆登记', records: '访问详情',
})[mode.value] || '')

const metrics = [
  { key: 'booked', label: '今日预约', icon: CalendarDays, tone: 'blue' },
  { key: 'entered', label: '今日入厂', icon: LogIn, tone: 'green' },
  { key: 'departed', label: '今日离厂', icon: LogOut, tone: 'violet' },
  { key: 'present', label: '当前在厂', icon: UsersRound, tone: 'amber' },
]
const actions = [
  { key: 'ENTRY', label: '扫码入厂', icon: ArrowDownToLine, description: '核验通行码并登记入厂', tone: 'blue' },
  { key: 'EXIT', label: '扫码离厂', icon: ArrowUpFromLine, description: '核验离厂码或查找在厂人员', tone: 'green' },
  { key: 'walk', label: '现场登记', icon: ClipboardPenLine, description: '登记未预约来访人员', tone: 'violet' },
  { key: 'vehicle', label: '车辆登记', icon: Truck, description: '快速登记送货和提货车辆', tone: 'amber' },
]
const filters = [
  ['', '全部记录'], ['PENDING', '待审批'], ['APPROVED', '待入厂'], ['PRESENT', '在厂'], ['DEPARTED', '已离厂'],
]

async function refresh() {
  if (!props.site) return
  const request = ++pageRequest
  const query = { site: props.site.id, gate: props.gate?.id }
  error.value = ''
  try {
    const [stats, records] = await Promise.all([
      staff('/dashboard' + params(query)),
      props.readOnly && !props.recordsOnly ? [] : staff('/visits' + params({ ...query, state: state.value, search: search.value.trim() })),
    ])
    if (request === pageRequest) {
      summary.value = stats
      rows.value = records
    }
  } catch (failure) {
    if (request === pageRequest) error.value = failure.message
  }
}

function reset() {
  stopCamera()
  modalError.value = ''
  notice.value = ''
  selected.value = null
  picked.value = null
  timeline.value = null
  receptionOptions.value = null
  correction.value = false
  token.value = ''
  identityChecked.value = false
  documentNumber.value = ''
  plate.value = ''
  retry.value = null
}

async function open(action) {
  reset()
  if (['ENTRY', 'EXIT'].includes(action)) {
    direction.value = action
    mode.value = 'scan'
    await nextTick()
    qrInput.value?.focus()
  } else {
    mode.value = action
    try {
      form.value = await staff('/visit-form' + params({ site: props.site.id }))
    } catch (failure) { modalError.value = failure.message }
  }
}

function close() {
  mode.value = ''
  reset()
}

async function withModal(action) {
  busy.value = true
  modalError.value = ''
  try {
    await action()
  } catch (failure) {
    modalError.value = failure.message
  } finally {
    busy.value = false
  }
}

async function inspect(value) {
  if (!props.gate) return
  stopCamera()
  selected.value = null
  await withModal(async () => {
    const input = typeof value === 'number' ? { visitId: value } : { token: value.trim() }
    const preview = await staff('/gate/verifications', post({ ...input, gateId: props.gate.id, direction: direction.value }))
    picked.value = input
    selected.value = preview
    identityChecked.value = false
    documentNumber.value = ''
    plate.value = ''
    retry.value = null
  })
}

async function confirm() {
  await withModal(async () => {
    const payload = {
      ...picked.value, gateId: props.gate.id, identityChecked: identityChecked.value,
      documentType: documentType.value, documentNumber: documentNumber.value, plate: plate.value,
    }
    const serialized = JSON.stringify(payload)
    if (retry.value?.payload !== serialized) retry.value = { payload: serialized, key: crypto.randomUUID() }
    const response = await staff('/gate/' + (direction.value === 'ENTRY' ? 'entries' : 'exits'),
      post(payload, { 'Idempotency-Key': retry.value.key }))
    if (!response.ok) {
      modalError.value = response.message
      retry.value = null
      return
    }
    selected.value = null
    picked.value = null
    token.value = ''
    notice.value = response.message
    await refresh()
  })
}

async function toggleCamera() {
  if (camera.value) return stopCamera()
  const run = ++cameraRun
  camera.value = true
  modalError.value = ''
  await nextTick()
  try {
    const { BrowserQRCodeReader } = await import('@zxing/browser')
    if (run !== cameraRun) return
    const reader = new BrowserQRCodeReader()
    const controls = await reader.decodeFromConstraints({ video: { facingMode: { ideal: 'environment' } } }, video.value, result => {
      if (result && run === cameraRun) {
        token.value = result.getText()
        inspect(token.value)
      }
    })
    if (run === cameraRun) scanner = controls
    else controls.stop()
  } catch {
    if (run !== cameraRun) return
    modalError.value = '无法使用摄像头，请检查权限，或使用扫码枪与人工查询'
    stopCamera()
  }
}

function stopCamera() {
  cameraRun += 1
  scanner?.stop()
  scanner = undefined
  camera.value = false
}

async function submit(data) {
  await withModal(async () => {
    selected.value = await staff('/walk-ins', post({ ...data, gateId: props.gate.id }))
    await refresh()
  })
}

async function openRecord(row) {
  if (props.gate && !props.readOnly && ['APPROVED', 'PRESENT'].includes(row.state)) {
    reset()
    direction.value = row.state === 'PRESENT' ? 'EXIT' : 'ENTRY'
    mode.value = 'scan'
    await inspect(row.id)
    return
  }
  await openTimeline(row)
}

async function openTimeline(row) {
  reset()
  mode.value = 'records'
  await withModal(async () => { timeline.value = await staff('/visits/' + row.id) })
}

async function editReception() {
  await withModal(async () => {
    const visit = timeline.value.visit
    reception.value = { departmentId: visit.departmentId || null, hostId: visit.hostId || null, areaId: visit.areaId || null }
    receptionOptions.value = await staff('/visit-form' + params({ site: props.site.id }))
  })
}

async function saveReception() {
  await withModal(async () => {
    timeline.value = await staff('/visits/' + timeline.value.visit.id + '/reception', post(reception.value))
    receptionOptions.value = null
    notice.value = '接待信息已确认，申请进入部门审批'
    await refresh()
  })
}

async function correct() {
  await withModal(async () => {
    const { toInstant } = await import('./format.js')
    const { occurredAt, ...rest } = correctionForm.value
    await staff('/corrections', post({ ...rest, gateId: props.gate.id, visitId: timeline.value.visit.id,
      occurredAt: toInstant(occurredAt, zone.value) }))
    notice.value = '已提交安全部门审核'
    correction.value = false
  })
}

function previousStep() {
  if (selected.value) {
    selected.value = null
    picked.value = null
    token.value = ''
  } else close()
}

watch(() => [props.site?.id, props.gate?.id, state.value], refresh)
onMounted(() => {
  refresh()
  timer = setInterval(async () => {
    if (document.hidden) return
    if (isRegister.value && selected.value?.state === 'PENDING') {
      try { selected.value = (await staff('/walk-ins/' + selected.value.id)).visit }
      catch (failure) { modalError.value = failure.message }
    } else if (!mode.value) refresh()
  }, 5000)
})
onUnmounted(() => { clearInterval(timer); stopCamera() })
</script>

<template>
  <div v-if="site" class="dashboard">
    <header class="page-heading">
      <div><h1>{{ recordsOnly ? '预约记录' : readOnly ? '首页工作台' : '智慧门岗' }}</h1><p>{{ site.name }}<template v-if="gate && !readOnly"> <span class="dot-divider">·</span> {{ gate.name }}</template></p></div>
      <button type="button" class="button ghost" @click="refresh"><RefreshCw :size="16" /> 刷新数据</button>
    </header>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <div v-if="!recordsOnly" class="metric-grid">
      <article v-for="metric in metrics" :key="metric.key" class="metric">
        <div><span>{{ readOnly && metric.key === 'booked' ? '异常滞留' : metric.label }}</span>
          <strong>{{ summary[readOnly && metric.key === 'booked' ? 'overdue' : metric.key] ?? '—' }}</strong></div>
        <div class="icon-tile" :data-tone="metric.tone"><component :is="readOnly && metric.key === 'booked' ? CircleAlert : metric.icon" :size="22" /></div>
      </article>
    </div>
    <template v-if="recordsOnly || !readOnly && gate">
      <section v-if="!recordsOnly" class="operations" aria-label="快捷操作">
        <div class="section-heading"><h2>快捷操作</h2><span v-if="Number(summary.overdue)" class="alert-count"><CircleAlert :size="15" /> {{ summary.overdue }} 人超时滞留</span></div>
        <div class="action-grid">
          <button v-for="action in actions" :key="action.key" class="operation" type="button" :data-tone="action.tone" @click="open(action.key)">
            <span class="icon-tile" :data-tone="action.tone"><component :is="action.icon" :size="24" /></span>
            <span class="operation-label"><strong>{{ action.label }}</strong><small>{{ action.description }}</small></span>
            <ChevronRight class="operation-arrow" :size="17" />
          </button>
        </div>
      </section>
      <section class="table-card">
        <div class="table-top">
          <h2>来访记录</h2>
          <form class="search-box" @submit.prevent="refresh"><Search :size="17" /><input v-model="search" maxlength="80" aria-label="搜索来访记录" placeholder="姓名、车牌或单号" @input="search === '' && refresh()"><button type="submit" class="button link">搜索</button></form>
        </div>
        <div class="tabs" aria-label="筛选来访状态">
          <button v-for="[value, label] in filters" :key="value" class="tab" type="button" :aria-pressed="state === value" @click="state = value">{{ label }}</button>
        </div>
        <div class="table-scroll">
          <table>
            <thead><tr><th>姓名</th><th>来访类型</th><th>来访公司</th><th>被访人员</th><th>时间</th><th>状态</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="row in rows" :key="row.id">
                <td><div class="person"><span class="avatar">{{ row.name.slice(0, 1) }}</span><strong>{{ row.name }}</strong></div></td>
                <td>{{ types[row.type] }}</td>
                <td>{{ row.company }}<small v-if="row.plate" class="table-sub">{{ row.plate }}</small></td><td>{{ row.hostName || '未指定' }}<small class="table-sub">{{ row.departmentName || '待确认接访部门' }}</small></td>
                <td class="number">{{ time(row.departedAt || row.enteredAt || row.plannedIn, zone, true) }}</td>
                <td><span class="badge" :data-state="row.state">{{ stateNames[row.state] || row.state }}</span></td>
                <td><button type="button" class="button link" @click="openRecord(row)">{{ !readOnly && row.state === 'APPROVED' ? '核验入厂' : !readOnly && row.state === 'PRESENT' ? '办理离厂' : '查看' }}</button>
                  <button v-if="!readOnly && ['APPROVED','PRESENT'].includes(row.state)" type="button" class="button link" @click="openTimeline(row)">详情</button></td>
              </tr>
              <tr v-if="!rows.length"><td colspan="7"><div class="empty"><DoorOpen :size="28" /><span>{{ search ? '没有找到匹配的记录' : '暂无来访记录' }}</span></div></td></tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>
  </div>
  <ElDialog :model-value="Boolean(mode)" :title="title" width="min(640px, calc(100vw - 30px))" align-center @close="close">
    <p v-if="modalError" class="inline-error" role="alert">{{ modalError }}</p>
    <p v-if="notice" class="success-message" role="status">{{ notice }}</p>
    <template v-if="isScan && !selected">
      <form class="field-row" @submit.prevent="inspect(token)">
        <input ref="qrInput" v-model="token" aria-label="通行码" maxlength="100" placeholder="对准扫码枪，或输入通行码">
        <button class="button primary" :disabled="busy || !token.trim()" type="submit"><ScanLine :size="17" /> 核验</button>
      </form>
      <button class="button ghost camera-button" type="button" @click="toggleCamera"><Video :size="17" /> {{ camera ? '关闭摄像头' : '打开摄像头' }}</button>
      <video v-if="camera" ref="video" class="camera-preview" muted playsinline autoplay />
      <p class="muted">手动办理可在来访记录中搜索姓名或车牌。</p>
      <div class="dialog-actions"><button type="button" class="button ghost" @click="close">返回来访记录</button></div>
    </template>
    <template v-else-if="isScan && selected">
      <div class="selected-visit">
        <div><h3>{{ selected.name }}</h3><p>{{ selected.company }} · {{ types[selected.type] }}</p></div>
        <span class="badge" :data-state="selected.state">{{ stateNames[selected.state] }}</span>
      </div>
      <dl class="details-grid">
        <dt>接待人员</dt><dd>{{ selected.hostName }} · {{ selected.departmentName }}</dd>
        <dt v-if="selected.plate">预约车牌</dt><dd v-if="selected.plate">{{ selected.plate }}</dd>
        <dt v-if="selected.referenceNo">业务单号</dt><dd v-if="selected.referenceNo">{{ selected.referenceNo }}</dd>
        <dt v-if="selected.areaName">获准区域</dt><dd v-if="selected.areaName">{{ selected.areaName }}</dd>
        <dt v-if="selected.identityKnown">证件尾号</dt><dd v-if="selected.identityKnown">{{ selected.documentTail }}</dd>
      </dl>
      <form class="form-stack" @submit.prevent="confirm">
        <div v-if="!selected.identityKnown" class="form-grid">
          <label>证件类型<select v-model="documentType"><option value="ID">身份证</option><option value="PASSPORT">护照</option><option value="OTHER">其他证件</option></select></label>
          <label>证件号码<input v-model="documentNumber" required maxlength="40" autocomplete="off"></label>
        </div>
        <details v-else><summary>输入完整证件号码复核</summary><div class="form-grid">
          <label>证件类型<select v-model="documentType"><option value="ID">身份证</option><option value="PASSPORT">护照</option><option value="OTHER">其他证件</option></select></label>
          <label>证件号码<input v-model="documentNumber" maxlength="40" autocomplete="off"></label>
        </div></details>
        <label v-if="selected.plate && (direction === 'ENTRY' || !selected.vehicleDepartedAt)">现场车牌<input v-model="plate" required maxlength="20" placeholder="核对并输入现场车牌"></label>
        <label class="checkline"><input v-model="identityChecked" type="checkbox" required> 已核对来访人身份</label>
        <div class="dialog-actions"><button class="button primary" :disabled="busy || (direction === 'ENTRY' ? selected.state !== 'APPROVED' : selected.state !== 'PRESENT')" type="submit">
          {{ direction === 'ENTRY' ? '确认入厂' : '确认离厂' }}</button><button class="button ghost" type="button" @click="previousStep">返回</button></div>
      </form>
    </template>
    <template v-else-if="isRegister">
      <VisitForm v-if="!selected && form" :key="mode" :config="form" :mode="mode" onsite :busy="busy" @submit="submit" />
      <div v-else-if="selected" class="form-stack">
        <div class="selected-visit"><div><h3>{{ selected.name }}</h3><p>{{ types[selected.type] }} · {{ selected.plate || selected.company }}</p></div>
          <span class="badge" :data-state="selected.state">{{ stateNames[selected.state] }}</span>
        </div>
        <p v-if="selected.state === 'PENDING'" class="hint">正在等待接访部门及对应岗位审批，页面会自动更新。</p>
        <p v-if="selected.rejection" class="inline-error">{{ selected.rejection }}</p>
        <div class="dialog-actions"><button v-if="selected.state === 'APPROVED'" type="button" class="button primary" @click="openRecord(selected)">继续核验入厂</button>
          <button type="button" class="button ghost" @click="close">返回来访记录</button></div>
      </div>
    </template>
    <template v-else-if="timeline">
      <h3>{{ timeline.visit.name }} · {{ types[timeline.visit.type] }}</h3>
      <p class="muted">{{ timeline.visit.reason }}</p>
      <p v-if="timeline.visit.phone" class="muted">联系电话 <a :href="'tel:' + timeline.visit.phone">{{ timeline.visit.phone }}</a></p>
      <dl class="details-grid">
        <dt>访客填写的被访人</dt><dd>{{ timeline.visit.requestedHost || '未填写' }}</dd>
        <dt>接访部门</dt><dd>{{ timeline.visit.departmentName || '待确认' }}</dd>
        <dt>内部接待人员</dt><dd>{{ timeline.visit.hostId ? timeline.visit.hostName : '未指定' }}</dd>
        <dt v-if="timeline.visit.areaName">访问区域</dt><dd v-if="timeline.visit.areaName">{{ timeline.visit.areaName }}</dd>
      </dl>
      <p v-if="!timeline.visit.departmentId" class="hint">请由门岗或行政确认接访部门后再审批。</p>
      <button v-if="canAssign && !receptionOptions" class="button ghost" type="button" :disabled="busy" @click="editReception">确认接待信息</button>
      <form v-if="receptionOptions" class="form-stack" @submit.prevent="saveReception">
        <div class="form-grid">
          <label>接访部门
            <select v-model="reception.departmentId" required aria-label="接访部门" @change="reception.hostId = null">
              <option :value="null" disabled>请选择部门</option>
              <option v-for="department in receptionOptions.departments" :key="department.id" :value="department.id">{{ department.name }}</option>
            </select>
          </label>
          <label>接待人员（选填）
            <select v-model="reception.hostId" aria-label="接待人员（选填）">
              <option :value="null">未指定</option>
              <option v-for="person in receptionHosts" :key="person.id" :value="person.id">{{ person.name }}</option>
            </select>
          </label>
          <label>访问区域{{ needsArea ? '' : '（选填）' }}
            <select v-model="reception.areaId" :required="needsArea" aria-label="访问区域">
              <option :value="null">{{ needsArea ? '请选择区域' : '未指定' }}</option>
              <option v-for="area in receptionOptions.areas" :key="area.id" :value="area.id">{{ area.name }}</option>
            </select>
          </label>
        </div>
        <button class="button primary" :disabled="busy" type="submit">保存接待信息</button>
      </form>
      <div class="event-list">
        <div v-for="(event, index) in [...timeline.approvals, ...timeline.events]" :key="index">
          <span>{{ event.role || event.action }} · {{ stateNames[event.state] || event.outcome || event.state }}</span>
          <small>{{ event.approver || event.staffName }} {{ time(event.actedAt || event.occurredAt, zone) }}</small>
        </div>
      </div>
      <button v-if="gate && !correction" class="button ghost" type="button" @click="correction = true">申请补录或更正</button>
      <form v-if="correction" class="form-stack" @submit.prevent="correct">
        <div class="form-grid">
          <label>更正事件<select v-model="correctionForm.action"><option value="EXIT">人员离厂</option><option value="ENTRY">人员入厂</option><option value="VEHICLE_EXIT">车辆离厂</option></select></label>
          <label>实际发生时间<input v-model="correctionForm.occurredAt" type="datetime-local" required></label>
          <label v-if="correctionForm.action === 'ENTRY'">证件类型<select v-model="correctionForm.documentType"><option value="ID">身份证</option><option value="PASSPORT">护照</option><option value="OTHER">其他证件</option></select></label>
          <label v-if="correctionForm.action === 'ENTRY'">待核验的证件号码<input v-model="correctionForm.documentNumber" maxlength="40" autocomplete="off"></label>
          <label class="wide">原因<input v-model="correctionForm.reason" maxlength="300" required></label>
        </div>
        <button class="button primary" :disabled="busy" type="submit">提交安全审核</button>
      </form>
    </template>
  </ElDialog>
</template>
