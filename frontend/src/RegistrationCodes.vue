<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElDialog } from 'element-plus'
import 'element-plus/es/components/dialog/style/css'
import { Copy, Download, ExternalLink, Plus, QrCode } from 'lucide-vue-next'
import { params, post, staff } from './api.js'

const { site } = defineProps({ site: { type: Object, required: true } })
const endpoint = '/admin/registration-links'
const links = ref([])
const gates = ref([])
const dialog = ref('')
const name = ref('')
const gateId = ref(null)
const result = ref()
const selected = ref()
const busy = ref(false)
const error = ref('')
const copied = ref('')
const urlField = ref()
const linkName = row => row.displayName || (row.gateId ? '现场登记码' : '访客预约码')
const localAddress = computed(() => result.value
  && ['localhost', '127.0.0.1', '[::1]'].includes(new URL(result.value.url).hostname))

async function run(action) {
  busy.value = true
  error.value = ''
  try { await action() }
  catch (failure) { error.value = failure.message }
  finally { busy.value = false }
}

async function refresh() {
  const response = await staff(endpoint + params({ site: site.id }))
  links.value = response.links
  gates.value = response.gates
}

function start() {
  name.value = ''
  gateId.value = null
  error.value = ''
  dialog.value = 'create'
}

function create() {
  return run(async () => {
    result.value = await staff(endpoint, post({ siteId: site.id, gateId: gateId.value, displayName: name.value }))
    selected.value = { gateId: gateId.value, displayName: name.value.trim(), enabled: true }
    copied.value = ''
    dialog.value = 'preview'
    await refresh()
  })
}

function preview(row) {
  return run(async () => {
    result.value = await staff(endpoint + '/' + row.slug)
    selected.value = row
    copied.value = ''
    dialog.value = 'preview'
  })
}

function toggle(row) {
  if (row.enabled && !window.confirm('停用后，扫此码的访客将无法提交登记。确定停用？')) return
  return run(async () => {
    await staff(endpoint + '/' + row.slug, { method: 'PATCH', body: { enabled: !row.enabled } })
    await refresh()
  })
}

async function copy() {
  try {
    await navigator.clipboard.writeText(result.value.url)
    copied.value = '已复制链接'
  } catch {
    urlField.value?.select()
    copied.value = '已选中链接，可手动复制'
  }
}

onMounted(() => run(refresh))
</script>

<template>
  <div class="dashboard">
    <header class="page-heading">
      <div><h1>二维码管理</h1><p>{{ site.name }}</p></div>
      <button type="button" class="button primary" :disabled="busy" @click="start"><Plus :size="17" /> 生成二维码</button>
    </header>
    <p v-if="error && !dialog" class="inline-error" role="alert">{{ error }}</p>
    <section class="table-card">
      <div class="table-top"><h2>访客登记入口</h2></div>
      <div class="table-scroll">
        <table>
          <thead><tr><th>名称</th><th>扫码用途</th><th>门岗</th><th>状态</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="row in links" :key="row.slug">
              <td><div class="person"><span class="icon-tile"><QrCode :size="18" /></span><strong>{{ linkName(row) }}</strong></div></td>
              <td>{{ row.gateId ? '现场登记' : '访客预约' }}</td>
              <td>{{ row.gateName || '—' }}</td>
              <td><span class="badge" :data-state="row.enabled ? 'PRESENT' : 'CANCELLED'">{{ row.enabled ? '已启用' : '已停用' }}</span></td>
              <td>
                <button class="button link" type="button" :disabled="busy" @click="preview(row)">查看二维码</button>
                <button class="button link" type="button" :disabled="busy" @click="toggle(row)">{{ row.enabled ? '停用' : '启用' }}</button>
              </td>
            </tr>
            <tr v-if="!links.length"><td colspan="5"><div class="empty"><QrCode :size="27" />{{ busy ? '正在加载' : '暂无登记二维码' }}</div></td></tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
  <ElDialog :model-value="Boolean(dialog)" :title="dialog === 'create' ? '生成二维码' : linkName(selected || {})"
    width="min(540px, calc(100vw - 30px))" align-center @close="dialog = ''">
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <form v-if="dialog === 'create'" class="form-stack" @submit.prevent="create">
      <label>二维码名称（选填）<input v-model="name" maxlength="80" placeholder="例如：访客预约海报"></label>
      <label>扫码入口
        <select v-model="gateId">
          <option :value="null">访客预约</option>
          <option v-for="gate in gates" :key="gate.id" :value="gate.id">现场登记 · {{ gate.name }}</option>
        </select>
      </label>
      <button class="button primary" :disabled="busy" type="submit">生成二维码</button>
    </form>
    <div v-else-if="dialog === 'preview' && result" class="form-stack">
      <div class="pass-display"><img :src="result.qr" :alt="linkName(selected)" width="250" height="250"></div>
      <p v-if="!selected.enabled" class="hint">此二维码已停用。启用后可用同一张码登记。</p>
      <template v-else>
        <label>访客链接<input ref="urlField" :value="result.url" readonly @focus="event => event.target.select()"></label>
        <p v-if="localAddress" class="hint">当前链接用于本机预览。手机扫码需配置手机可访问的访客站点地址。</p>
        <div class="dialog-actions qr-actions">
          <a class="button primary" :href="result.url" target="_blank" rel="noreferrer"><ExternalLink :size="16" />{{ selected.gateId ? '打开登记页' : '打开预约页' }}</a>
          <button class="button ghost" type="button" @click="copy"><Copy :size="16" /> 复制链接</button>
          <a class="button ghost" :href="result.qr" :download="'访客二维码-' + result.slug + '.png'"><Download :size="16" /> 下载二维码</a>
        </div>
        <p v-if="copied" class="muted" role="status">{{ copied }}</p>
      </template>
    </div>
  </ElDialog>
</template>

<style scoped>
.qr-actions a { text-decoration: none; }
</style>
