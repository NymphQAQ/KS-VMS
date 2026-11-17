<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { localInput, toInstant } from './format.js'

const props = defineProps({
  config: { type: Object, required: true },
  mode: { type: String, required: true },
  initial: { type: Object, default: () => ({}) },
  busy: Boolean,
  onsite: Boolean,
  confirmOnsite: Boolean,
  publicBooking: Boolean,
})
const emit = defineEmits(['submit'])
const invite = computed(() => props.mode === 'invite')
const locked = computed(() => props.mode === 'visitor' && Boolean(props.initial.invited))
const zone = computed(() => props.config.site.zone)
const start = props.onsite ? Date.now() : Date.now() + Math.max(props.config.site.leadMinutes + 15, 60) * 60000
const today = localInput(Date.now(), zone.value).slice(0, 10)
const form = reactive({
  name: props.initial.name || '', company: props.initial.company || '', reason: props.initial.reason || '',
  hostId: props.initial.hostId || '', type: props.initial.type || (props.mode === 'vehicle' ? 'DELIVERY' : props.publicBooking ? 'SUPPLIER' : ''),
  requestedHost: props.initial.hostName || '',
  phone: props.initial.phone || '', plate: props.initial.plate || '', referenceNo: props.initial.referenceNo || '',
  areaId: props.initial.areaId || '', drives: Boolean(props.initial.plate) || props.mode === 'vehicle',
  plannedIn: localInput(props.initial.plannedIn || start, zone.value),
  plannedOut: localInput(props.initial.plannedOut || start + 90 * 60000, zone.value),
  visitDate: props.onsite ? today : localInput(props.initial.plannedIn || start, zone.value).slice(0, 10),
  safety: false, privacy: false, onsiteConfirmed: false,
})
const localError = ref('')
const openNotice = ref('')
const selected = computed(() => props.config.types.find(item => item.code === form.type))
const availableTypes = computed(() => props.mode === 'vehicle' ? props.config.types.filter(type => type.driver) : props.config.types)
const referenceLabel = computed(() => ({
  DELIVERY: '送货单号', LOGISTICS: '提货单号', MAINTENANCE: '维修工单号', SOFTWARE: '维护工单或变更单号',
})[form.type] || '业务单号')
const notices = computed(() => Object.fromEntries(props.config.notices.map(item => [item.kind, item])))
const soon = computed(() => {
  if (props.mode !== 'visitor' || props.publicBooking || !form.plannedIn) return false
  try {
    return Date.parse(toInstant(form.plannedIn, zone.value)) - Date.now() < props.config.site.leadMinutes * 60000
  } catch {
    return false
  }
})

watch(selected, value => { if (value?.driver) form.drives = true })

function submit() {
  localError.value = ''
  try {
    if (props.mode !== 'visitor' && !form.hostId) throw new Error('请选择接待人员')
    const common = {
      name: form.name, type: form.type, reason: form.reason,
      ...(props.mode === 'visitor' ? { requestedHost: form.requestedHost.trim() } : { hostId: Number(form.hostId) }),
      ...(props.publicBooking ? { phone: form.phone, visitDate: form.visitDate }
        : { plannedIn: toInstant(form.plannedIn, zone.value), plannedOut: toInstant(form.plannedOut, zone.value) }),
    }
    if (invite.value) {
      emit('submit', { ...common, siteId: props.config.site.id, phone: form.phone })
      return
    }
    if (!form.safety || !form.privacy || !notices.value.SAFETY || !notices.value.PRIVACY) {
      throw new Error('请阅读并确认两份须知')
    }
    emit('submit', {
      ...common, company: form.company, plate: form.drives ? form.plate : null,
      referenceNo: selected.value?.reference ? form.referenceNo : null,
      areaId: props.mode !== 'visitor' && form.areaId ? Number(form.areaId) : null, safetyNoticeId: notices.value.SAFETY.id,
      privacyNoticeId: notices.value.PRIVACY.id, onsiteConfirmed: form.onsiteConfirmed,
    })
  } catch (error) {
    localError.value = error.message
  }
}
</script>

<template>
  <form class="form-stack" @submit.prevent="submit">
    <div v-if="mode === 'vehicle'" class="segmented driver-type">
      <button type="button" :class="{ chosen: form.type === 'DELIVERY' }" @click="form.type = 'DELIVERY'">送货</button>
      <button type="button" :class="{ chosen: form.type === 'LOGISTICS' }" @click="form.type = 'LOGISTICS'">提货</button>
    </div>
    <div class="form-grid" :class="{ 'single-column': mode === 'visitor', 'booking-fields': publicBooking }">
      <label>姓名<input v-model="form.name" required maxlength="100" :readonly="locked" placeholder="输入访客姓名"></label>
      <label v-if="invite">受邀人手机号<input v-model="form.phone" required inputmode="tel" pattern="1[3-9][0-9]{9}" maxlength="11"></label>
      <label v-if="publicBooking">手机号码<input v-model="form.phone" type="tel" required autocomplete="tel-national" pattern="1[3-9][0-9]{9}" maxlength="11" placeholder="输入手机号码"></label>
      <label v-if="!invite">所属公司<input v-model="form.company" required maxlength="100" placeholder="个人来访可填写「个人」"></label>
      <label v-if="mode !== 'vehicle'">来访类型
        <select v-model="form.type" required :disabled="locked">
          <option disabled value="">请选择</option>
          <option v-for="item in availableTypes" :key="item.code" :value="item.code">{{ item.label }}</option>
        </select>
      </label>
      <label v-if="mode === 'visitor'">被访人员
        <input v-model="form.requestedHost" maxlength="100" autocomplete="off" placeholder="选填，填写您已知的姓名">
      </label>
      <label v-else>接待人员
        <select v-model="form.hostId" required>
          <option disabled value="">请选择接待人员</option>
          <option v-for="person in config.hosts" :key="person.id" :value="person.id">{{ person.name }} · {{ person.departmentName }}</option>
        </select>
      </label>
      <label v-if="publicBooking">来访日期<input v-model="form.visitDate" type="date" required :min="today" :readonly="onsite"></label>
      <template v-else>
        <label v-if="!onsite || locked">计划到厂<input v-model="form.plannedIn" type="datetime-local" required :readonly="locked"></label>
        <label>计划离厂<input v-model="form.plannedOut" type="datetime-local" required :readonly="locked"></label>
      </template>
      <label class="wide" v-if="mode === 'vehicle' || !locked">来访事由
        <input v-model="form.reason" required maxlength="500" :placeholder="mode === 'vehicle' ? '如：送货至仓储收发区' : '简述本次来访事项'">
      </label>
      <template v-if="!invite">
        <fieldset v-if="publicBooking" class="booking-driving">
          <legend>是否驾车</legend>
          <div class="booking-radio">
            <label class="checkline"><input v-model="form.drives" type="radio" name="drives" :value="true"> 是</label>
            <label class="checkline"><input v-model="form.drives" type="radio" name="drives" :value="false" :disabled="selected?.driver"> 否</label>
          </div>
        </fieldset>
        <label v-else-if="!selected?.driver" class="checkline wide"><input v-model="form.drives" type="checkbox"> 驾车入厂</label>
        <label v-if="publicBooking || form.drives">车牌号码<input v-model="form.plate" :required="form.drives" :disabled="!form.drives" maxlength="20" placeholder="例如：粤B12345"></label>
        <label v-if="selected?.reference">{{ referenceLabel }}<input v-model="form.referenceNo" required maxlength="100"></label>
        <label v-if="mode !== 'visitor' && selected?.area">访问或作业区域
          <select v-model="form.areaId" required>
            <option disabled value="">请选择</option>
            <option v-for="area in config.areas" :key="area.id" :value="area.id">{{ area.name }}</option>
          </select>
        </label>
      </template>
    </div>
    <div v-if="!invite" class="form-notices">
      <template v-if="notices.SAFETY && notices.PRIVACY">
        <template v-for="[kind, label, field] in [['SAFETY', '入厂安全须知', 'safety'], ['PRIVACY', '个人信息处理说明', 'privacy']]" :key="kind">
          <details v-if="!publicBooking"><summary>{{ label }}</summary><p>{{ notices[kind].text }}</p></details>
          <div class="notice-row">
            <label class="checkline"><input v-model="form[field]" type="checkbox" required> {{ onsite && mode !== 'visitor' ? '访客' : publicBooking ? '' : '我' }}已阅读{{ label }}</label>
            <button v-if="publicBooking" class="button link" type="button" :aria-label="'查看' + label" :aria-expanded="openNotice === kind"
              @click="openNotice = openNotice === kind ? '' : kind">{{ openNotice === kind ? '收起' : '查看' }}</button>
          </div>
          <p v-if="publicBooking && openNotice === kind" class="notice-text muted">{{ notices[kind].text }}</p>
        </template>
        <label v-if="!onsite && (soon || confirmOnsite)" class="checkline">
          <input v-model="form.onsiteConfirmed" type="checkbox" required> 确认按现场登记提交，等待接访主管及对应部门审批
        </label>
      </template>
      <p v-else class="inline-error">该厂区须知尚未发布，请联系行政人员。</p>
    </div>
    <p v-if="localError" class="inline-error" role="alert">{{ localError }}</p>
    <button class="button primary" type="submit" :disabled="busy || (!invite && (!notices.SAFETY || !notices.PRIVACY))">
      {{ busy ? '正在提交…' : invite ? '生成邀请链接' : onsite || soon || confirmOnsite ? '提交现场登记' : initial.id && !locked ? '提交修改并重新审批' : '提交预约' }}
    </button>
  </form>
</template>

<style scoped>
.booking-fields > label, .booking-fields > .field {
  display: grid;
  grid-template-columns: 76px minmax(0, 1fr);
  align-items: start;
  column-gap: 8px;
}
.booking-fields > label, .booking-fields > .field > label { line-height: 40px; }
.booking-fields .field .form-stack { gap: 6px; }
.booking-fields input:disabled { background: #f5f7fa; }
.booking-driving { margin: 0; padding: 0; border: 0; display: flex; align-items: center; min-width: 0; }
.booking-driving legend { float: left; width: 84px; padding: 0; font-size: 13px; font-weight: 620; line-height: 32px; }
.booking-radio { display: flex; gap: 26px; align-items: center; }
.notice-row { display: flex; justify-content: space-between; align-items: center; gap: 6px; }
.notice-text { white-space: pre-wrap; padding-left: 24px; }
</style>
