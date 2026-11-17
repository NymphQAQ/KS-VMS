<script setup>
import { nextTick, onUnmounted, ref, watch } from 'vue'
import { BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { init, use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { Download } from 'lucide-vue-next'
import { params, staff } from './api.js'
import { localInput, sources, types } from './format.js'

use([BarChart, GridComponent, TooltipComponent, CanvasRenderer])
const props = defineProps({ site: Object, canExport: Boolean, canView: Boolean })
const tab = ref('visitors')
const from = ref(localInput(Date.now() - 6 * 86400000, props.site.zone).slice(0, 10))
const to = ref(localInput(Date.now(), props.site.zone).slice(0, 10))
const kind = ref('visits')
const type = ref('')
const source = ref('')
const department = ref('')
const plate = ref('')
const data = ref({})
const plot = ref()
const error = ref('')
let chart, observer
const labels = { visitors: '来访人员', departments: '来访部门', vehicles: '车辆统计' }
const filters = () => ({ site: props.site.id, from: from.value, to: to.value, type: type.value, source: source.value,
  department: department.value.trim(), plate: plate.value.trim() })

async function load() {
  if (!props.canView) return
  error.value = ''
  try {
    data.value = await staff('/statistics' + params(filters()))
    draw()
  } catch (failure) { error.value = failure.message }
}

async function draw() {
  await nextTick()
  if (!plot.value) return
  if (!chart) {
    chart = init(plot.value)
    observer = new ResizeObserver(() => chart?.resize())
    observer.observe(plot.value)
  }
  const rows = (data.value[tab.value] || []).slice(0, 12)
  chart.setOption({
    color: ['#2464e0', '#26a48b'],
    grid: { top: 28, right: 14, bottom: 54, left: 45 },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: rows.map(row => row.name || row.plate || types[row.type]), axisLabel: { rotate: rows.length > 5 ? 24 : 0 } },
    yAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: '#edf0f5' } } },
    series: ['entered', 'departed'].map((key, index) => ({
      name: index ? '离厂' : '入厂', type: 'bar', barMaxWidth: 34,
      data: rows.map(row => Number(row[key] || 0)), itemStyle: { borderRadius: [3, 3, 0, 0] },
    })),
  }, { notMerge: true })
}

async function download() {
  error.value = ''
  const response = await fetch('/api/staff/exports' + params({ ...filters(), kind: kind.value }))
  if (!response.ok) {
    error.value = (await response.json().catch(() => ({}))).message || '无法导出'
    return
  }
  const url = URL.createObjectURL(await response.blob())
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = kind.value + '.csv'
  anchor.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

watch(() => props.site.id, load, { immediate: true })
watch(tab, draw)
onUnmounted(() => { observer?.disconnect(); chart?.dispose(); chart = undefined })
</script>

<template>
  <div class="dashboard">
    <header class="page-heading"><div><h1>数据统计</h1><p>{{ site.name }}</p></div></header>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <section class="panel form-stack">
      <form class="form-grid report-range" @submit.prevent="load">
        <label>开始日期<input v-model="from" type="date" required></label>
        <label>结束日期<input v-model="to" type="date" required></label>
        <label>来访类型<select v-model="type"><option value="">全部</option><option v-for="(label, key) in types" :key="key" :value="key">{{ label }}</option></select></label>
        <label>申请来源<select v-model="source"><option value="">全部</option><option v-for="(label, key) in sources" :key="key" :value="key">{{ label }}</option></select></label>
        <label>接访部门<input v-model="department" placeholder="填写部门全称" maxlength="100"></label>
        <label>车牌<input v-model="plate" placeholder="填写完整车牌" maxlength="20"></label>
        <button v-if="canView" type="submit" class="button primary">查询</button>
        <template v-if="canExport">
          <label>历史记录导出<select v-model="kind"><option value="visits">访客记录</option><option value="vehicles">车辆记录</option><option value="alerts">异常记录</option></select></label>
          <button type="button" class="button ghost" @click="download"><Download :size="16" /> 导出 CSV</button>
        </template>
      </form>
    </section>
    <section v-if="canView" class="table-card">
      <div class="tabs"><button v-for="(label, key) in labels" :key="key" type="button" class="tab" :aria-pressed="tab === key" @click="tab = key">{{ label }}</button></div>
      <div ref="plot" class="chart" role="img" :aria-label="labels[tab] + '进出厂柱状图'"></div>
      <div class="table-scroll"><table>
        <thead><tr><th>{{ tab === 'vehicles' ? '车牌' : labels[tab] }}</th><th v-if="tab !== 'vehicles'">来访记录</th><th>入厂</th><th>离厂</th></tr></thead>
        <tbody><tr v-for="row in data[tab] || []" :key="(row.name || row.plate || '') + (row.type || '')">
          <td>{{ row.name || row.plate || types[row.type] }}</td><td v-if="tab !== 'vehicles'">{{ row.total }}</td><td>{{ row.entered || 0 }}</td><td>{{ row.departed || 0 }}</td>
        </tr><tr v-if="!(data[tab] || []).length"><td :colspan="tab === 'vehicles' ? 3 : 4"><div class="empty">所选时段暂无数据</div></td></tr></tbody>
      </table></div>
    </section>
  </div>
</template>
