export const stateNames = {
  PENDING: '待审批', APPROVED: '待入厂', REJECTED: '已驳回', PRESENT: '在厂',
  DEPARTED: '已离厂', EXPIRED: '已失效', CANCELLED: '已撤销', SUPERSEDED: '已变更',
  OPEN: '待处理', PROCESSING: '处理中', CLOSED: '已结案',
}
export const types = {
  CUSTOMER: '客户', SUPPLIER: '供应商', DELIVERY: '送货司机', LOGISTICS: '物流司机',
  CONTRACTOR: '外协施工人员', MAINTENANCE: '设备维修人员', INTERVIEW: '面试人员', SOFTWARE: '软件维护人员',
}
export const roles = {
  ADMIN: '系统管理员', ADMINISTRATION: '行政', EMPLOYEE: '员工', GUARD: '门卫',
  MANAGER: '管理层', SAFETY: '安全部门', HOST_SUPERVISOR: '接访部门主管',
  OWNER: '老板确认', PROCUREMENT: '采购部门', WAREHOUSE: '仓库',
  WAREHOUSE_OR_PROCUREMENT: '仓库或采购', LOGISTICS: '物流部门', PROJECT: '施工负责部门',
  EQUIPMENT: '设备部门', HR: '人事部门', IT: '信息化部门', EXPORT: '历史导出',
}
export const alerts = {
  OVERDUE: '超时滞留', BLACKLIST: '黑名单拦截', BLACKLIST_PRESENT: '黑名单人员在厂',
  DUPLICATE_ENTRY: '重复入厂', IDENTITY_MISMATCH: '证件不符',
  PLATE_MISMATCH: '车牌不符', NO_ENTRY: '缺少入厂记录',
}
export const sources = { SELF: '自主预约', INVITED: '员工邀请', ONSITE: '现场登记' }

export function time(value, zone = 'Asia/Shanghai', short = false) {
  if (value == null) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: zone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
    ...(!short && { year: 'numeric', month: '2-digit', day: '2-digit' }),
  }).format(new Date(value))
}

export function localInput(value, zone = 'Asia/Shanghai') {
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', {
    timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).formatToParts(new Date(value)).map(part => [part.type, part.value]))
  return parts.year + '-' + parts.month + '-' + parts.day + 'T' + parts.hour + ':' + parts.minute
}

export function toInstant(value, zone = 'Asia/Shanghai') {
  if (!value) return null
  const target = Date.parse(value + ':00Z')
  let candidate = target
  for (let index = 0; index < 3; index += 1) {
    const observed = Date.parse(localInput(candidate, zone) + ':00Z')
    if (observed === target) return new Date(candidate).toISOString()
    candidate += target - observed
  }
  throw new Error('该时间在所选厂区的时区内无效')
}
