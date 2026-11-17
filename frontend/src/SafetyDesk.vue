<script setup>
import { computed, ref, watch } from 'vue'
import { ElDialog } from 'element-plus'
import 'element-plus/es/components/dialog/style/css'
import { Plus, RefreshCw, ShieldCheck } from 'lucide-vue-next'
import { params, post, staff } from './api.js'
import { alerts, localInput, stateNames, time, toInstant } from './format.js'

const props = defineProps({ site: Object, global: Boolean })
const tab = ref('alerts')
const rows = ref([])
const modal = ref('')
const picked = ref()
const form = ref({})
const busy = ref(false)
const error = ref('')
const zone = computed(() => props.site.zone)
const location = computed(() => params({ site: props.site.id }))
const paths = { alerts: '/alerts', blacklist: '/blacklist', corrections: '/corrections', areas: '/admin/areas', notices: '/admin/notices' }
const labels = { alerts: '异常预警', blacklist: '黑名单', corrections: '补录审核', areas: '访问区域', notices: '安全须知' }
const columns = {
  alerts: [['kind', '异常类型'], ['name', '访客'], ['state', '状态'], ['lastAt', '最近触发'], ['assignee', '处理人']],
  blacklist: [['documentTail', '证件尾号'], ['reason', '原因'], ['siteId', '范围'], ['startsAt', '生效时间'], ['endsAt', '失效时间'], ['disabled', '状态']],
  corrections: [['name', '访客'], ['action', '更正项目'], ['occurredAt', '实际时间'], ['reason', '申请原因'], ['requestedBy', '申请人'], ['state', '状态']],
  areas: [['name', '区域名称'], ['enabled', '状态']],
  notices: [['kind', '类型'], ['id', '版本'], ['text', '正文']],
}

async function run(action) {
  busy.value = true
  error.value = ''
  try { await action() }
  catch (failure) { error.value = failure.message }
  finally { busy.value = false }
}

async function refresh() {
  const current = tab.value
  const data = await staff(paths[current] + location.value)
  if (current === tab.value) rows.value = data
}

function display(row, key) {
  if (key.endsWith('At')) return time(row[key], zone.value)
  if (key === 'kind') return { SAFETY: '入厂安全须知', PRIVACY: '个人信息处理说明', ...alerts }[row[key]] || row[key]
  if (key === 'state') return tab.value === 'corrections' && row[key] === 'APPROVED' ? '已核准' : stateNames[row[key]] || row[key]
  if (key === 'disabled') return row[key] ? '已停用' : '有效'
  if (key === 'siteId') return row[key] ? props.site.name : '全厂区'
  if (key === 'enabled') return row[key] ? '启用' : '停用'
  if (key === 'documentTail') return '•••• ' + row[key]
  return row[key] || '—'
}

function open(name, row) {
  error.value = ''
  picked.value = row
  form.value = {
    documentType: 'ID', documentNumber: '', reason: '', startsAt: localInput(Date.now(), zone.value),
    endsAt: '', global: false, name: row?.name || '', enabled: row?.enabled ?? true,
    kind: row?.kind || 'SAFETY', text: row?.text || '', resolution: '',
  }
  modal.value = name
}

function save() {
  return run(async () => {
    const data = form.value
    if (modal.value === 'blacklist') {
      await staff('/blacklist', post({ documentType: data.documentType, documentNumber: data.documentNumber, reason: data.reason,
        siteId: data.global ? null : props.site.id, startsAt: toInstant(data.startsAt, zone.value), endsAt: toInstant(data.endsAt, zone.value) }))
    } else if (modal.value === 'areas') {
      await staff('/admin/areas' + params({ id: picked.value?.id }), post({ siteId: props.site.id, name: data.name, enabled: data.enabled }))
    } else if (modal.value === 'notices') {
      await staff('/admin/notices' + location.value, post({ kind: data.kind, text: data.text }))
    } else if (modal.value === 'alerts') {
      await staff('/alerts/' + picked.value.id, post({ state: 'CLOSED', resolution: data.resolution }))
    }
    modal.value = ''
    await refresh()
  })
}

function update(path, options, message) {
  if (!message || window.confirm(message)) run(async () => { await staff(path, options); await refresh() })
}

watch(() => [tab.value, props.site.id], () => { rows.value = []; run(refresh) }, { immediate: true })
</script>

<template>
  <div class="dashboard">
    <header class="page-heading">
      <div><h1>安全管理</h1><p>{{ site.name }}</p></div>
      <button v-if="['blacklist','areas','notices'].includes(tab)" type="button" class="button primary" @click="open(tab)">
        <Plus :size="17" /> {{ tab === 'notices' ? '发布须知' : tab === 'areas' ? '新增区域' : '新增名单' }}</button>
      <button v-else type="button" class="button ghost" @click="run(refresh)"><RefreshCw :size="16" /> 刷新</button>
    </header>
    <p v-if="error && !modal" role="alert" class="inline-error">{{ error }}</p>
    <section class="table-card">
      <div class="tabs"><button v-for="(label, key) in labels" :key="key" type="button" class="tab" :aria-pressed="tab === key" @click="tab = key">{{ label }}</button></div>
      <div class="table-scroll">
        <table>
          <thead><tr><th v-for="[key, label] in columns[tab]" :key="key">{{ label }}</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td v-for="[key] in columns[tab]" :key="key" :class="{ 'notice-cell': key === 'text' }">{{ display(row, key) }}</td>
              <td>
                <template v-if="tab === 'alerts' && row.state !== 'CLOSED'">
                  <button v-if="row.state === 'OPEN'" class="button link" type="button" @click="update('/alerts/' + row.id, post({state:'PROCESSING'}))">认领</button>
                  <button class="button link" type="button" @click="open(tab, row)">结案</button>
                </template>
                <button v-if="tab === 'blacklist' && !row.disabled && (row.siteId || global)" class="button link" type="button" @click="update('/blacklist/' + row.id, { method: 'DELETE' }, '确认停用这条名单？')">停用</button>
                <template v-if="tab === 'corrections' && row.state === 'PENDING'">
                  <button class="button link" type="button" @click="update('/corrections/' + row.id + '/review', post({approve:true}), '确认更正出入厂记录？')">核准</button>
                  <button class="button link" type="button" @click="update('/corrections/' + row.id + '/review', post({approve:false}), '确认驳回申请？')">驳回</button>
                </template>
                <button v-if="tab === 'areas' || tab === 'notices'" class="button link" type="button" @click="open(tab, row)">{{ tab === 'areas' ? '编辑' : '发布新版本' }}</button>
              </td>
            </tr>
            <tr v-if="!rows.length"><td :colspan="columns[tab].length + 1"><div class="empty"><ShieldCheck :size="28" />暂无{{ labels[tab] }}记录</div></td></tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
  <ElDialog :model-value="Boolean(modal)" :title="modal === 'alerts' ? '结案处置' : labels[modal]" width="min(580px, calc(100vw - 30px))" align-center @close="modal = ''">
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <form v-if="modal" class="form-stack" @submit.prevent="save">
      <template v-if="modal === 'blacklist'">
        <div class="form-grid">
          <label>证件类型<select v-model="form.documentType"><option value="ID">身份证</option><option value="PASSPORT">护照</option><option value="OTHER">其他证件</option></select></label>
          <label>证件号码<input v-model="form.documentNumber" autocomplete="off" required maxlength="40"></label>
          <label>生效时间<input v-model="form.startsAt" type="datetime-local" required></label>
          <label>失效时间（选填）<input v-model="form.endsAt" type="datetime-local"></label>
        </div>
        <label>列入原因<textarea v-model="form.reason" required maxlength="300" rows="2"></textarea></label>
        <label v-if="global" class="checkline"><input v-model="form.global" type="checkbox"> 适用所有厂区</label>
      </template>
      <template v-else-if="modal === 'areas'">
        <label>区域名称<input v-model="form.name" required maxlength="100"></label>
        <label class="checkline"><input v-model="form.enabled" type="checkbox"> 启用区域</label>
      </template>
      <template v-else-if="modal === 'notices'">
        <label>类型<select v-model="form.kind"><option value="SAFETY">入厂安全须知</option><option value="PRIVACY">个人信息处理说明</option></select></label>
        <label>发布内容<textarea v-model="form.text" required rows="7" maxlength="16000"></textarea></label>
      </template>
      <label v-else>处置结论<textarea v-model="form.resolution" required maxlength="300" rows="3"></textarea></label>
      <button class="button primary" :disabled="busy" type="submit">确认提交</button>
    </form>
  </ElDialog>
</template>
