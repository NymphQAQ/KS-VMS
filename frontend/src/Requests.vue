<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElDialog } from 'element-plus'
import 'element-plus/es/components/dialog/style/css'
import { CalendarPlus, Check, ClipboardList, Copy, RefreshCw } from 'lucide-vue-next'
import { params, post, staff } from './api.js'
import { roles, sources, stateNames, time, toInstant, types } from './format.js'
import VisitForm from './VisitForm.vue'

const props = defineProps({ mode: String, site: Object })
const approval = computed(() => props.mode === 'approvals')
const history = ref(false)
const rows = ref([])
const config = ref()
const showInvite = ref(false)
const selected = ref()
const reject = ref(false)
const comment = ref('')
const permit = ref('')
const training = ref('')
const until = ref('')
const combined = ref(true)
const link = ref('')
const error = ref('')
const busy = ref(false)
const zone = computed(() => props.site?.zone || 'Asia/Shanghai')

async function run(action) {
  busy.value = true
  error.value = ''
  try { await action() }
  catch (failure) { error.value = failure.message }
  finally { busy.value = false }
}

async function refresh() {
  rows.value = await staff(approval.value ? '/approvals' + params({ history: history.value }) : '/invitations')
}

function openInvite() {
  return run(async () => {
    config.value = await staff('/visit-form' + params({ site: props.site.id }))
    showInvite.value = true
    link.value = ''
  })
}

function submitInvite(body) {
  return run(async () => {
    link.value = (await staff('/invitations', post(body))).url
    await refresh()
  })
}

function openApproval(row, denied) {
  selected.value = row
  reject.value = denied
  comment.value = ''
  permit.value = ''
  training.value = ''
  until.value = ''
  combined.value = true
  error.value = ''
}

function submitDecision() {
  return run(async () => {
    const body = { approve: !reject.value, comment: comment.value, completeNext: combined.value }
    if (!reject.value && selected.value.role === 'SAFETY') {
      body.permitNo = permit.value
      body.trainingUntil = toInstant(training.value, zone.value)
      body.permitUntil = toInstant(until.value, zone.value)
    }
    await staff('/approvals/' + selected.value.stepId + '/decision', post(body))
    selected.value = null
    await refresh()
  })
}

async function copy() {
  try { await navigator.clipboard.writeText(link.value) }
  catch { error.value = '请选中链接后复制' }
}

function cancel(row) {
  if (window.confirm('确定撤销这条邀请？')) {
    run(async () => { await staff('/invitations/' + row.id + '/cancel', post()); await refresh() })
  }
}

onMounted(() => run(refresh))
watch(history, () => run(refresh))
</script>

<template>
  <div class="dashboard">
    <header class="page-heading">
      <div><h1>{{ approval ? '审批管理' : '员工邀请' }}</h1>
        <p>{{ approval ? '处理入厂申请并查询审批结果' : '为来访人员生成专属登记链接' }}</p></div>
      <button v-if="!approval && site" class="button primary" type="button" @click="openInvite"><CalendarPlus :size="17" /> 邀请访客</button>
      <button v-else class="button ghost" type="button" @click="run(refresh)"><RefreshCw :size="16" /> 刷新</button>
    </header>
    <p v-if="error && !showInvite && !selected" class="inline-error" role="alert">{{ error }}</p>
    <section class="table-card">
      <div v-if="approval" class="tabs"><button class="tab" :aria-pressed="!history" type="button" @click="history = false">待审批申请</button>
        <button class="tab" :aria-pressed="history" type="button" @click="history = true">已审批申请</button></div>
      <div v-else class="table-top"><h2>我的邀请</h2></div>
      <div class="table-scroll">
        <table>
          <thead><tr><th>访客</th><th v-if="approval">来访与接待</th><th>计划到厂</th><th>{{ approval ? '审批岗位' : '状态' }}</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="row in rows" :key="row.stepId || row.id">
              <td><div class="person"><span class="avatar">{{ row.name.slice(0, 1) }}</span><span><strong>{{ row.name }}</strong><small v-if="approval">{{ row.company }}</small></span></div></td>
              <td v-if="approval">{{ types[row.type] }} · {{ sources[row.source] }}<small class="table-sub">{{ row.hostName }} · {{ row.siteName }}</small></td>
              <td>{{ time(row.plannedIn, zone) }}</td>
              <td v-if="approval">{{ roles[row.role] }}<small v-if="history" class="table-sub">{{ row.decision === 'APPROVED' ? '已通过' : '已驳回' }}</small></td>
              <td v-else>{{ row.cancelledAt ? '已撤回' : row.state ? stateNames[row.state] : row.claimedAt ? '已领取' : Date.parse(row.expiresAt) < Date.now() ? '已过期' : '待领取' }}</td>
              <td v-if="approval && !history"><button type="button" class="button link" @click="openApproval(row, false)">处理申请</button></td>
              <td v-else-if="approval">{{ time(row.actedAt, zone) }}</td>
              <td v-else><button v-if="!row.cancelledAt && !['CANCELLED','REJECTED','PRESENT','DEPARTED'].includes(row.state)" type="button" class="button link" @click="cancel(row)">撤销</button></td>
            </tr>
            <tr v-if="!rows.length"><td :colspan="approval ? 5 : 4"><div class="empty"><ClipboardList :size="28" />{{ approval ? '暂无审批记录' : '暂无邀请' }}</div></td></tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
  <ElDialog v-model="showInvite" title="邀请访客" width="min(600px, calc(100vw - 30px))" align-center>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <div v-if="link" class="form-stack">
      <p class="success-message"><Check :size="18" /> 邀请链接已生成</p>
      <label>发送给指定访客<input :value="link" readonly @focus="event => event.target.select()"></label>
      <button class="button primary" type="button" @click="copy"><Copy :size="17" /> 复制链接</button>
    </div>
    <VisitForm v-else-if="config" :config="config" mode="invite" :busy="busy" @submit="submitInvite" />
  </ElDialog>
  <ElDialog :model-value="Boolean(selected)" title="审批入厂申请" width="min(600px, calc(100vw - 30px))" align-center @close="selected = null">
    <template v-if="selected">
      <h3>{{ selected.name }} · {{ types[selected.type] }}</h3>
      <dl class="details-grid">
        <dt>审批岗位</dt><dd>{{ roles[selected.role] }}</dd><dt>厂区</dt><dd>{{ selected.siteName }}</dd>
        <dt>接待人员</dt><dd>{{ selected.hostName }} · {{ selected.departmentName }}</dd>
        <dt>来访事由</dt><dd>{{ selected.reason }}</dd><dt>计划时段</dt><dd>{{ time(selected.plannedIn, zone) }} 至 {{ time(selected.plannedOut, zone) }}</dd>
        <dt v-if="selected.plate">车牌</dt><dd v-if="selected.plate">{{ selected.plate }}</dd>
        <dt v-if="selected.referenceNo">业务单号</dt><dd v-if="selected.referenceNo">{{ selected.referenceNo }}</dd>
        <dt v-if="selected.areaName">访问区域</dt><dd v-if="selected.areaName">{{ selected.areaName }}</dd>
      </dl>
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <form class="form-stack" @submit.prevent="submitDecision">
        <label v-if="!reject && selected.role === 'SAFETY'">施工许可编号<input v-model="permit" required maxlength="100"></label>
        <div v-if="!reject && selected.role === 'SAFETY'" class="form-grid">
          <label>培训有效至<input v-model="training" type="datetime-local" required></label>
          <label>施工许可有效至<input v-model="until" type="datetime-local" required></label>
        </div>
        <label v-if="!reject && selected.role === 'HOST_SUPERVISOR'" class="checkline"><input v-model="combined" type="checkbox"> 同时确认我有权处理的下一审批</label>
        <label>{{ reject ? '驳回理由' : '审批意见（选填）' }}<textarea v-model="comment" :required="reject" maxlength="300" rows="2"></textarea></label>
        <div class="dialog-actions"><button type="submit" class="button" :class="reject ? 'danger' : 'primary'" :disabled="busy">{{ reject ? '确认驳回' : '批准' }}</button>
          <button type="button" class="button ghost" @click="reject = !reject">{{ reject ? '返回批准' : '驳回申请' }}</button></div>
      </form>
    </template>
  </ElDialog>
</template>
