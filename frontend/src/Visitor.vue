<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { Button as VanButton, Field as VanField } from 'vant'
import 'vant/es/button/style'
import 'vant/es/field/style'
import { ArrowLeft, Building2, CalendarDays, ShieldCheck } from 'lucide-vue-next'
import VisitForm from './VisitForm.vue'
import { visitor, params, post } from './api.js'
import { stateNames, time, types } from './format.js'

const registration = new URLSearchParams(location.search).get('register')
const invitation = new URLSearchParams(location.hash.slice(1)).get('invite')
const authenticated = ref(false)
const verified = ref(false)
const config = ref()
const onsite = ref(false)
const item = ref()
const rows = ref([])
const selected = ref()
const editing = ref(false)
const active = ref('form')
const phone = ref('')
const code = ref('')
const demoCode = ref('')
const busy = ref(false)
const error = ref('')
const pass = ref()
const confirmOnsite = ref(false)
const registrationUnavailable = ref(false)
const zone = computed(() => config.value?.site.zone || 'Asia/Shanghai')
const publicBooking = computed(() => !invitation && (!editing.value || Boolean(item.value?.phone)))
const bookingForm = computed(() => active.value === 'form' && config.value && publicBooking.value)

async function run(action) {
  error.value = ''
  busy.value = true
  try {
    await action()
  } catch (failure) {
    error.value = failure.message
    if (failure.code === 'ONSITE_CONFIRMATION') confirmOnsite.value = true
    return false
  } finally {
    busy.value = false
  }
  return true
}

async function init() {
  registrationUnavailable.value = false
  error.value = ''
  if (registration) {
    try {
      const data = await visitor('/registration/' + encodeURIComponent(registration))
      config.value = data.form
      onsite.value = data.onsite
    } catch (failure) {
      registrationUnavailable.value = true
      error.value = failure.status === 404 ? '此登记二维码已停用或失效，请联系接待人员获取新二维码' : failure.message
      return
    }
    if (!invitation) {
      authenticated.value = true
      return
    }
  }
  try {
    const session = await visitor('/me')
    authenticated.value = true
    verified.value = session.verified
  } catch (failure) {
    if (failure.status !== 401) error.value = failure.message
  }
  if (authenticated.value && (!invitation || verified.value)) await prepare()
}

async function prepare() {
  if (invitation) {
    await run(async () => {
      const data = await visitor('/invitations/claim', post({ token: invitation }))
      item.value = { ...data, invited: true }
      config.value = await visitor('/visit-form' + params({ site: data.siteId }))
      if (data.visitId) await details(data.visitId)
    })
  } else if (!registration) {
    await list()
  }
}

function sendCode() {
  return run(async () => { demoCode.value = (await visitor('/otp', post({ phone: phone.value }))).demoCode })
}

async function login() {
  if (await run(async () => { await visitor('/sessions', post({ phone: phone.value, code: code.value })) })) {
    authenticated.value = true
    verified.value = true
    await prepare()
  }
}

async function list() {
  active.value = 'list'
  pass.value = null
  await run(async () => { rows.value = await visitor('/applications') })
}

async function details(id) {
  active.value = 'details'
  pass.value = null
  selected.value = await visitor('/applications/' + id)
}

async function edit() {
  await run(async () => {
    if (config.value?.site.id !== selected.value.siteId) {
      config.value = await visitor('/visit-form' + params({ site: selected.value.siteId, visitId: selected.value.id }))
    }
    item.value = selected.value
    onsite.value = selected.value.source === 'ONSITE'
    confirmOnsite.value = false
    editing.value = true
    active.value = 'form'
  })
}

async function submit(data) {
  await run(async () => {
    const path = editing.value ? '/applications/' + item.value.id + '/revisions' : '/applications'
    const result = await visitor(path, post({ ...data, slug: registration, inviteToken: invitation }))
    editing.value = false
    await details(result.id)
  })
}

async function makePass() {
  await run(async () => {
    const direction = selected.value.state === 'PRESENT' ? 'EXIT' : 'ENTRY'
    pass.value = await visitor('/applications/' + selected.value.id + '/passes' + params({ direction }), post())
  })
}

async function cancel() {
  if (window.confirm('确定撤销这条申请？')) {
    await run(async () => {
      await visitor('/applications/' + selected.value.id + '/cancel', post())
      await details(selected.value.id)
    })
  }
}

function expired() {
  authenticated.value = false
  verified.value = false
  code.value = ''
  pass.value = null
  if (registration && !invitation) {
    active.value = 'form'
    editing.value = false
    item.value = null
    error.value = '请使用提交预约的浏览器查看记录'
  } else {
    error.value = '登录已过期，请重新验证手机号'
  }
}

onMounted(() => { window.addEventListener('vms-session-expired', expired); init() })
onUnmounted(() => window.removeEventListener('vms-session-expired', expired))
</script>

<template>
  <div class="visitor-page">
    <header class="public-top">
      <div class="brand-mark"><Building2 :size="23" /></div>
      <div><strong>厂区通行</strong><span>访客服务</span></div>
      <button v-if="authenticated" type="button" class="button top-link" @click="list"><CalendarDays :size="17" /> 我的预约</button>
    </header>
    <main class="visitor-main" :class="{ 'public-booking': bookingForm }">
      <section class="visitor-title">
        <p v-if="config">{{ config.site.name }}</p>
        <h1>{{ active === 'list' ? '我的预约' : active === 'details' ? '来访详情' : editing ? '修改预约' : onsite ? '入厂现场登记' : '入厂预约' }}</h1>
      </section>
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <section v-if="registrationUnavailable" class="visitor-card">
        <button class="button ghost" type="button" @click="init">重新加载</button>
      </section>
      <section v-else-if="invitation ? !verified : !registration && !authenticated" class="visitor-card">
        <h2><ShieldCheck :size="20" /> 手机号验证</h2>
        <form class="form-stack" @submit.prevent="login">
          <VanField v-model="phone" type="tel" maxlength="11" label="手机号码" placeholder="请输入手机号" autocomplete="tel" />
          <VanField v-model="code" type="digit" maxlength="6" label="验证码" placeholder="6 位验证码" autocomplete="one-time-code">
            <template #button><VanButton size="small" type="primary" plain native-type="button" :disabled="busy || !/^1[3-9][0-9]{9}$/.test(phone)" @click="sendCode">获取验证码</VanButton></template>
          </VanField>
          <p v-if="demoCode" class="hint">演示验证码 {{ demoCode }}</p>
          <VanButton type="primary" block native-type="submit" :loading="busy" :disabled="code.length !== 6">验证并继续</VanButton>
        </form>
      </section>
      <section v-else-if="active === 'form' && config" class="visitor-card">
        <VisitForm :key="item?.id || 'new'" :config="config" mode="visitor" :initial="item || undefined" :onsite="onsite"
          :public-booking="publicBooking" :confirm-onsite="confirmOnsite" :busy="busy"
          @submit="submit" />
      </section>
      <section v-else-if="active === 'list'" class="visitor-card">
        <button v-if="registration && config" class="button ghost" type="button" @click="item = undefined; editing = false; active = 'form'">填写新预约</button>
        <p v-if="!rows.length" class="muted">此浏览器暂无预约记录。可扫描厂区登记码或打开员工邀请。</p>
        <button v-for="row in rows" :key="row.id" type="button" class="visitor-row" @click="run(() => details(row.id))">
          <span><strong>{{ types[row.type] }} · {{ row.name }}</strong><small>{{ row.siteName }} · {{ time(row.plannedIn, zone) }}</small></span>
          <span class="badge" :data-state="row.state">{{ stateNames[row.state] }}</span>
        </button>
      </section>
      <section v-else-if="active === 'details' && selected" class="visitor-card">
        <button type="button" class="button link" @click="list"><ArrowLeft :size="16" /> 我的预约</button>
        <h2>{{ selected.name }} <span class="badge" :data-state="selected.state">{{ stateNames[selected.state] }}</span></h2>
        <dl class="details-grid">
          <dt>厂区</dt><dd>{{ selected.siteName }}</dd><dt>人员类型</dt><dd>{{ types[selected.type] }}</dd>
          <dt v-if="selected.hostName">被访人员</dt><dd v-if="selected.hostName">{{ selected.hostName }}</dd>
          <dt>计划到厂</dt><dd>{{ time(selected.plannedIn, zone) }}</dd>
          <dt v-if="selected.phone">手机号码</dt><dd v-if="selected.phone">{{ selected.phone }}</dd>
          <dt v-if="selected.dueAt">应离厂时间</dt><dd v-if="selected.dueAt">{{ time(selected.dueAt, zone) }}</dd>
        </dl>
        <p v-if="selected.rejection" class="inline-error">{{ selected.rejection }}</p>
        <p v-if="selected.state === 'APPROVED'" class="hint">允许入厂 {{ time(selected.windowStart, zone) }} 至 {{ time(selected.windowEnd, zone) }}</p>
        <div class="dialog-actions">
          <button v-if="['APPROVED', 'PRESENT'].includes(selected.state)" class="button primary" :disabled="busy" type="button" @click="makePass">
            {{ selected.state === 'PRESENT' ? '显示离厂码' : '显示入厂码' }}
          </button>
          <button v-if="['PENDING', 'APPROVED'].includes(selected.state)" class="button ghost" :disabled="busy" type="button" @click="edit">修改</button>
          <button v-if="['PENDING', 'APPROVED'].includes(selected.state)" class="button ghost" :disabled="busy" type="button" @click="cancel">撤销</button>
          <button class="button ghost" type="button" :disabled="busy" @click="run(() => details(selected.id))">刷新状态</button>
        </div>
        <div v-if="pass" class="pass-display">
          <img :src="pass.qr" :alt="pass.direction === 'ENTRY' ? '入厂二维码' : '离厂二维码'" width="236" height="236">
          <span>有效至 {{ time(pass.expiresAt, zone, true) }}，请向门卫出示</span>
        </div>
      </section>
      <p v-else-if="!busy" class="muted">请使用厂区登记码或员工邀请链接。</p>
    </main>
  </div>
</template>

<style scoped>
.public-booking { max-width: 490px; }
.public-booking .visitor-title { text-align: center; }
.public-booking .visitor-title p { color: var(--ink); font-size: 17px; font-weight: 700; }
</style>
