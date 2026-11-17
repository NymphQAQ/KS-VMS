# KS-VMS 项目说明与开发指南

> 本文根据当前仓库源码、配置、数据库迁移和测试代码整理，描述的是当前实现（as-built），不是对未来功能的承诺。需求范围和目标设计分别见 [需求文档](visitor-management-requirements.md) 与 [开发设计](visitor-management-design.md)。

## 1. 项目简介

KS-VMS（厂区访客管理系统）用于企业厂区访客预约、员工邀请、审批、门岗身份核验、车辆进出登记和安全追溯。系统支持一个企业下配置多个厂区、门岗、访问区域、部门和员工授权。

系统由一个 Spring Boot 后端、一个 Vue 3 前端代码库和一个 MySQL 数据库组成。前端根据访问路径加载访客页面或员工工作台；生产环境通过 Nginx 使用两个 HTTPS 域名隔离访客入口和内部工作台入口。

### 1.1 主要业务能力

| 业务域 | 当前实现 |
| --- | --- |
| 访客预约 | 企业登记二维码、自助预约、预约记录查询、修改和撤销 |
| 员工邀请 | 员工创建邀请，访客通过手机号验证码领取邀请并补充资料 |
| 分类审批 | 根据来访类型生成固定审批节点；现场登记额外增加接访部门主管节点 |
| 门岗管理 | 扫码或人工核验、确认入厂、确认离厂、现场登记、车辆登记、在厂查询 |
| 通行凭证 | 分别生成入厂码和离厂码；凭证短时有效，使用后失效 |
| 安全管理 | 黑名单、黑名单及核验异常预警、超时滞留扫描、异常补录和更正审核 |
| 统计审计 | 首页指标、来访及车辆统计、CSV 导出、员工操作审计 |
| 系统配置 | 厂区、门岗、访问区域、部门、员工、角色授权、须知和登记二维码 |

### 1.2 主要参与角色

- 访客：自助预约、领取邀请、查看本人申请、出示通行码。
- 员工：创建和跟进本人发出的访客邀请。
- 接访主管及业务审批人：按岗位审批申请。
- 门卫：现场登记、核对身份和车辆、办理入厂及离厂。
- 行政人员：维护预约与接待信息、厂区和人员配置。
- 安全人员：管理黑名单、处理预警、审核补录和更正。
- 管理人员及导出人员：查看统计，按授权导出历史记录。
- 系统管理员：管理账号、角色授权和系统基础配置。

## 2. 技术架构

### 2.1 技术栈

| 层次 | 技术与版本（以配置文件为准） | 用途 |
| --- | --- | --- |
| 运行环境 | Java 21 | 后端运行 |
| 后端框架 | Spring Boot 3.5.16、Spring MVC、Spring Security | REST API、会话认证、权限控制 |
| 数据访问 | Spring JDBC / JdbcTemplate、MyBatis-Plus 3.5.16 | SQL 访问；Visit 实体使用 MyBatis-Plus Mapper |
| 数据库迁移 | Flyway Core、Flyway MySQL | 版本化管理表结构 |
| 数据库 | MySQL 8.4 | 业务数据持久化 |
| 参数校验 | Jakarta Validation | 请求 DTO 校验 |
| 二维码 | ZXing 3.5.4、@zxing/browser | 服务端生成二维码、浏览器扫码 |
| API 文档 | springdoc-openapi 2.8.15、Knife4j 4.5.0 | 开发环境接口文档 |
| 前端框架 | Vue 3.5、Vite 7.3.8 | 单页应用与开发服务器 |
| 管理端 UI | Element Plus、Lucide Vue Next | 员工工作台组件和图标 |
| 访客端 UI | Vant、Lucide Vue Next | 移动端表单和交互 |
| 图表 | ECharts 6 | 统计图表 |
| Web 网关 | Nginx stable-alpine | 静态资源、HTTPS 和 API 反向代理 |
| 容器 | Docker、Docker Compose | 应用和数据库部署 |

### 2.2 系统结构

    访客手机浏览器 ── HTTPS ──┐
                              ├── Nginx 网关 ── Vue 静态资源
    员工/门卫浏览器 ─ HTTPS ──┘       │
                                     └── /api/* ── Spring Boot
                                                       │
                                     ┌─────────────────┼─────────────┐
                                     │                 │             │
                                  MySQL 8.4        短信交付服务    Spring Task

- 前端构建产物是一套静态资源，由访客域名和员工域名共同使用。
- 员工域名提供工作台页面，只代理员工 API；访客域名提供 /visitor 页面，只代理访客 API。
- Nginx 将 API 请求转发给后端，数据库不直接暴露到公网。
- 后端以模块化单体方式组织，不是微服务。业务事务、审批、核验和数据访问都在同一个 Spring Boot 进程内完成。
- 短信服务通过后端配置的 HTTPS 地址发送访客验证码。当前代码未配置消息队列或独立通知中心。
- 当前 Compose 文件没有 Redis 服务或 Redis 会话配置；后端会话使用 Spring Security / Servlet 默认会话存储方式。

### 2.3 域名隔离

生产环境需要配置两个域名：

| 域名 | 页面入口 | 允许代理的业务 API |
| --- | --- | --- |
| 员工域名（VMS_STAFF_HOST） | /，员工工作台 | /api/staff/*、/api/csrf |
| 访客域名（VMS_VISITOR_HOST） | /visitor，访客服务；根路径重定向到 /visitor | /api/visitor/*、/api/csrf |

Nginx 对不属于当前域名的 API 路径返回 404，并限制员工域名访问 /visitor 路径。两个站点都启用 HTTPS，HTTP 请求重定向到 HTTPS。

## 3. 仓库目录

    KS-VMS/
    ├── backend/
    │   ├── src/main/java/cn/ks/vms/       后端业务代码
    │   ├── src/main/resources/
    │   │   ├── application.yml            Spring Boot 配置
    │   │   └── db/migration/              Flyway SQL 迁移
    │   ├── src/test/java/cn/ks/vms/       单元测试和在线流程测试
    │   ├── pom.xml                       Maven 依赖与构建配置
    │   └── Dockerfile                    后端运行镜像
    ├── frontend/
    │   ├── src/                          Vue 页面、组件、API 封装
    │   ├── dist/                         Vite 构建产物
    │   ├── package.json                  前端依赖和脚本
    │   └── vite.config.js                Vite 与本地 API 代理
    ├── deploy/
    │   ├── Dockerfile                    Nginx 静态站点镜像
    │   ├── vms.conf.template             双域名虚拟主机配置
    │   └── proxy.conf                    API 反向代理公共配置
    ├── docs/
    │   ├── project-guide.md              本文：项目实现与开发指南
    │   ├── visitor-management-requirements.md  产品需求与验收场景
    │   └── visitor-management-design.md   目标架构与业务设计
    ├── compose.yaml                      MySQL、后端和网关编排
    ├── .env.example                      生产环境变量模板
    └── README.md                         项目入口和快速开始

## 4. 后端模块说明

后端 Java 包名为 cn.ks.vms。当前代码按业务职责拆分为以下类，属于同一应用中的协作模块。

| 文件 | 职责 |
| --- | --- |
| VmsApplication.java | Spring Boot 启动入口，启用 Spring Task，提供 UTC Clock |
| Access.java | Spring Security 配置、员工账号密码登录、访客短信验证码、会话创建和注销、限流 |
| Catalog.java | 厂区、门岗、部门、接待人、须知、登记链接读取；员工授权范围判断；审计写入 |
| Policy.java | 来访类型、审批岗位映射、预约时间窗口和厂区营业时间计算 |
| Visit.java | Visit 持久化实体和 MyBatis-Plus Mapper，包含按 ID 加锁查询 |
| Visits.java | 预约、邀请、申请版本、审批、接待信息确认、访客通行码和二维码生成 |
| Gates.java | 门岗核验预览、身份和车牌校验、入厂离厂事务、幂等请求、门岗事件和告警 |
| AdminController.java | 厂区、部门、门岗、区域、员工、授权、须知、登记二维码和审计管理 API |
| SafetyController.java | 黑名单、预警处置、补录申请与审核；每分钟扫描超时滞留 |
| Reports.java | 首页指标、统计查询和 CSV 导出 |
| VisitorBookingAccess.java | 访客浏览器预约凭据 Cookie 与手机号会话归属校验 |
| Crypto.java | HMAC 摘要、随机令牌、AES-GCM 加解密 |
| Totp.java | TOTP 验证器密钥处理和一次性验证码校验 |
| Db.java | JdbcTemplate 查询、写入、结果映射和时间类型转换 |
| Problem.java | 统一业务异常和 REST 错误响应 |

### 4.1 后端代码组织特点

- 业务控制器通过构造器注入 Service、Catalog 和 Db 等依赖。
- 业务写操作主要由 Spring JDBC 执行 SQL；Visit 实体和锁定查询由 MyBatis-Plus Mapper 承担。
- 需要保证多步写入一致性的业务方法使用 Spring 的 @Transactional。
- Visits 负责申请与审批领域；Gates 负责真实进出厂记录。不要在控制器中复制这两处的业务校验。
- Catalog.Actor 封装员工授权，并支持角色、厂区、部门、门岗和授权有效期匹配。
- Policy 是预约窗口和审批链的集中规则入口。新增来访类型时，应同步检查前端类型展示、后端校验、审批岗位和测试。

## 5. 前端模块说明

前端没有使用 Vue Router。入口由路径判断：访问路径以 /visitor 开头时加载 Visitor.vue，否则加载 Workspace.vue。工作台内部通过组件状态和 URL hash 切换模块。

| 文件 | 职责 |
| --- | --- |
| main.js | 根据 pathname 选择访客端或员工工作台入口 |
| Workspace.vue | 员工登录、会话恢复、厂区和门岗选择、角色菜单、工作台布局 |
| Visitor.vue | 访客预约、手机号验证、邀请领取、申请列表和详情、通行码展示 |
| VisitForm.vue | 访客、邀请和现场登记共用的动态表单 |
| GateDesk.vue | 门岗首页、扫码、摄像头解码、人工核验、现场登记、车辆登记、访问时间线 |
| Requests.vue | 员工邀请和审批列表、审批处理 |
| RegistrationCodes.vue | 登记二维码生成、预览、复制、下载和启停 |
| SafetyDesk.vue | 黑名单、异常预警、补录审核、访问区域和安全须知 |
| Reports.vue | 来访、部门、车辆统计及导出 |
| Settings.vue | 厂区、门岗、部门、账号、角色授权和未分派审批查询 |
| api.js | fetch 请求封装、CSRF Token、错误处理、访客和员工 API 前缀 |
| format.js | 状态、类型、角色、时间格式化和时区转换 |
| styles.css | 全局视觉样式 |

前端 API 统一通过 api.js 访问。非 GET 请求先获取 CSRF Token，再发送 X-CSRF-TOKEN 请求头。员工接口使用 /api/staff 前缀，访客接口使用 /api/visitor 前缀。Vite 开发服务器将 /api 代理到后端，默认目标为 http://127.0.0.1:8080，可通过 VMS_API_TARGET 修改。

## 6. 核心业务流程

### 6.1 预约来源

系统区分业务来源和提交渠道：

| 来源 | 说明 |
| --- | --- |
| SELF | 访客通过企业预约二维码自助预约 |
| INVITED | 员工邀请访客，受邀人领取邀请后提交 |
| ONSITE | 门岗现场登记，或当日预约时间不足厂区提前量而转为现场流程 |

提交渠道还会记录企业二维码、门岗二维码、员工邀请或门卫代登记，以供追溯和统计。

公开企业预约码通过 registration_link 的 slug 关联厂区；现场二维码还关联具体门岗。二维码停用后，公开登记入口无法继续使用。

### 6.2 来访类型与审批链

审批映射由 Policy.Type 在代码中定义，当前实现如下：

| 来访类型 | 类型审批岗位 | 额外要求 |
| --- | --- | --- |
| 客户 CUSTOMER | OWNER | 需要访问区域 |
| 供应商 SUPPLIER | PROCUREMENT | — |
| 送货司机 DELIVERY | WAREHOUSE_OR_PROCUREMENT | 需要车牌和业务单号 |
| 物流司机 LOGISTICS | LOGISTICS | 需要车牌和业务单号 |
| 外协施工 CONTRACTOR | PROJECT → SAFETY | 需要访问区域；安全审批填写施工许可和培训有效期 |
| 设备维修 MAINTENANCE | EQUIPMENT | 需要访问区域和维修业务单号 |
| 面试人员 INTERVIEW | HR | — |
| 软件维护 SOFTWARE | IT | 需要访问区域和维护业务单号 |

现场来源在类型审批前增加 HOST_SUPERVISOR（接访部门主管）节点。主管同时拥有下一节点权限时，可在一次操作中完成两个节点；外协施工的 PROJECT 与 SAFETY 必须由不同账号审批。

**实现边界：** 当前审批岗位映射由代码中的 Policy.Type 和 Policy.steps 决定，不是数据库驱动的动态审批规则引擎。员工的角色、厂区、部门、门岗和授权起止时间由 staff_grant 配置。/api/staff/approval-rules/unassigned 用于查询当前缺少可用审批授权的待办，不提供审批规则编辑功能。

### 6.3 预约时间窗口

厂区配置包括时区、预约提前量、可提前预约天数、现场等待时限、最大停留时长和每日开放时间。

- 默认预约提前量为 120 分钟，默认可预约未来 30 天。
- 默认现场审批等待时限为 60 分钟，默认最大停留时间为 720 分钟。
- 预约窗口按厂区时区和开放时间计算；跨午夜开放时段也由 Policy 统一处理。
- 当日申请未满足提前预约时间时，后端要求确认转为现场流程；现场登记仅限当日。
- 入厂时还会结合计划离厂时间、厂区最大停留时间，以及施工许可有效期计算应离厂时间。

以上为数据库默认值，实际值以厂区配置为准。

### 6.4 员工邀请

1. 员工选择厂区、接待人、来访类型、访客姓名、手机号、事由和计划时间创建邀请。
2. 系统生成随机邀请令牌，数据库只保存令牌摘要和受邀手机号摘要。
3. 访客使用受邀手机号获取验证码并登录后，领取邀请。
4. 访客补齐资料和须知回执后提交申请，申请进入对应审批链。
5. 员工可查看和撤销本人邀请；未入厂的申请仍需满足当前状态限制才可撤销。

邀请有效期不超过创建后 48 小时，并且不晚于计划离厂时间。

### 6.5 门岗核验与进出厂

1. 门卫选择授权厂区和门岗，扫描访客二维码或按申请记录人工检索。
2. 后端先执行核验预览，返回申请状态、方向、证件是否已绑定及车牌信息。
3. 首次核验时录入证件类型和号码；系统对规范化证件号计算带密钥摘要，仅保存摘要与尾号。
4. 确认入厂时，后端在事务中锁定申请和身份，复核审批状态、厂区、门岗、入厂时段、访问区域、黑名单、证件、车牌、培训及施工许可，并检查是否重复在厂。
5. 确认离厂时，校验真实在厂记录和身份；车辆尚未离厂时同步登记车辆离厂。
6. 入厂和离厂分别写入 presence 与 gate_event，形成可追溯记录。

通行码分 ENTRY 和 EXIT 两种方向，默认有效期 5 分钟；入厂码还受批准入厂窗口约束。刷新新码会停用旧码，成功核验后凭证失效。门岗确认接口要求 Idempotency-Key，相同操作者、方向、请求编号和请求内容的重试返回已保存结果；请求编号被用于不同内容时返回冲突。

### 6.6 申请与访问状态

| 状态 | 含义 |
| --- | --- |
| PENDING | 等待当前审批节点处理 |
| APPROVED | 审批全部通过，等待门岗办理入厂 |
| REJECTED | 审批驳回 |
| CANCELLED | 申请已撤销 |
| SUPERSEDED | 申请已被新版本替代 |
| EXPIRED | 已超过准入窗口，未入厂申请失效（查询时根据时间计算） |
| PRESENT | 已登记入厂，尚未登记离厂 |
| DEPARTED | 已登记离厂 |

其中 EXPIRED、PRESENT 和 DEPARTED 由查询视图结合申请状态及 presence 时间计算，不是简单地由访客端直接写入的申请状态。审批节点另有 PENDING、APPROVED、REJECTED、CANCELLED 等状态。

### 6.7 安全预警和更正

- 黑名单按证件身份关联，可配置全局或指定厂区、生效和失效时间。
- 入厂时命中黑名单会阻止入厂并产生告警；在厂人员新增命中黑名单也会产生告警。
- Spring Task 每 60 秒扫描超时滞留，并按厂区、访问和告警类型避免重复创建同类告警。
- 门岗或授权人员可提交异常补录申请；安全人员审核后写入更正结果。
- 访问详情时间线整合审批动作和门岗事件；审计日志记录关键管理和业务操作。

### 6.8 统计和导出

- 首页提供预约、入厂、离厂、当前在厂及滞留相关指标。
- 统计支持按厂区日期区间、来访类型、来源、部门和车牌等维度查询。
- 导出支持 visits、vehicles、alerts 三类 CSV，单次最多 10,000 条。
- 导出姓名和车牌默认脱敏，并记录导出操作者和筛选范围。

## 7. 数据库设计

数据库由 Flyway 管理，迁移文件位于 backend/src/main/resources/db/migration。当前迁移版本为 V1 至 V6。应用启动时由 Flyway 自动执行尚未应用的迁移。

### 7.1 主要数据表

| 分组 | 表 | 说明 |
| --- | --- | --- |
| 组织与配置 | site、gate、area、department | 厂区、门岗、访问区域和部门 |
| 员工与授权 | staff、staff_grant | 员工账号及带作用域、有效期的角色授权 |
| 登记入口 | registration_link、notice | 预约二维码入口和安全/隐私须知 |
| 验证与限流 | otp_challenge、request_limit | 手机验证码状态和请求限流计数 |
| 访客身份 | identity_record | 证件类型、带密钥摘要和号码尾号 |
| 预约与邀请 | invitation、visit | 邀请记录、访客申请及申请版本 |
| 审批 | approval_step、approval_action | 审批节点和审批动作 |
| 通行与在厂 | gate_pass、presence、gate_event、gate_request | 临时通行码、人员/车辆在厂状态、门岗事件和幂等请求 |
| 安全 | blacklist、alert、correction | 黑名单、预警和补录更正审核 |
| 审计 | audit_log | 员工关键操作记录 |

### 7.2 关键关系和约束

- 一个厂区可以配置多个门岗、访问区域和登记入口。
- 一条 visit 对应一次来访申请；申请修改时创建新记录，通过 previous_id 和 version 保留版本关系。
- 一条申请包含多个 approval_step，每个审批节点最多有一条最终 approval_action。
- 一条申请最多关联一条 presence；presence 记录人员和车辆的入厂、离厂时间。
- presence 使用生成列与唯一索引限制同一厂区同一身份只能有一条未离厂记录，同一车牌也只能有一条未离厂记录。
- gate_request 以员工、操作方向和请求编号为联合主键，保存请求指纹及结果，用于门岗写操作幂等。
- 关键业务表通过外键关联厂区、员工、申请和身份记录。

### 7.3 数据时间与敏感数据

- 后端 Clock 使用 UTC；数据库连接配置使用 UTC；前端按厂区 zone 展示和计算本地时间。
- 员工密码使用 BCrypt 哈希存储。
- TOTP 验证器密钥使用 AES-GCM 加密存储。
- 手机号和证件号通过 Crypto 进行带密钥摘要；访客自助预约联系方式使用 AES-GCM 加密。
- 邀请令牌、通行码只以摘要形式保存，原始随机令牌只在需要时返回给前端。
- 导出和访客公开接口只返回业务所需字段，避免暴露内部员工、部门和访问区域配置。

**数据库模型说明：** 本节列出当前 Flyway SQL 中真实存在的表。开发设计文档中的 visitor_account、visit_application 等属于逻辑模型命名，不应直接当作当前数据库表名使用。

## 8. API 接口概览

所有业务接口均为 JSON REST API，除 CSV 导出外。以下为当前控制器中实现的主要路由，完整参数以 Controller DTO 和代码校验为准。

### 8.1 通用与认证

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /api/health | 健康检查 |
| GET | /api/csrf | 获取 CSRF Token |
| POST | /api/staff/sessions | 员工账号密码登录，必要时附 TOTP 验证码 |
| GET | /api/staff/me | 获取当前员工身份和授权 |
| DELETE | /api/staff/sessions/current | 员工退出 |
| POST | /api/visitor/otp | 发送访客手机验证码 |
| POST | /api/visitor/sessions | 手机验证码登录 |
| DELETE | /api/visitor/sessions/current | 访客退出 |

### 8.2 访客 API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /api/visitor/registration/{slug} | 读取二维码对应的登记配置 |
| GET | /api/visitor/me | 查询当前访客会话状态 |
| GET | /api/visitor/visit-form | 读取本人可用的表单配置 |
| POST | /api/visitor/invitations/claim | 领取员工邀请 |
| POST | /api/visitor/applications | 创建访客申请 |
| GET | /api/visitor/applications | 查询本人申请列表 |
| GET | /api/visitor/applications/{id} | 查询本人申请详情 |
| POST | /api/visitor/applications/{id}/revisions | 修改申请并创建新版本 |
| POST | /api/visitor/applications/{id}/cancel | 撤销申请 |
| POST | /api/visitor/applications/{id}/passes?direction=ENTRY/EXIT | 领取入厂码或离厂码 |

自助预约可以通过提交时的浏览器专属 Cookie 关联申请，因此首次提交公开预约不强制短信登录；手机号验证码会话用于邀请领取和已验证手机号的访问。访客接口会校验申请归属，并过滤内部接待人 ID、部门和区域等字段。

### 8.3 员工工作台 API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /api/staff/workspace | 当前账号可访问的厂区和门岗 |
| GET | /api/staff/visit-form | 员工侧表单配置、接待人和部门 |
| POST | /api/staff/visits/{id}/reception | 确认接访部门、接待人和区域 |
| POST / GET | /api/staff/invitations | 创建或查询本人邀请 |
| POST | /api/staff/invitations/{id}/cancel | 撤销邀请 |
| GET | /api/staff/approvals | 查询待审批或历史审批 |
| POST | /api/staff/approvals/{id}/decision | 审批通过或驳回 |
| GET | /api/staff/approval-rules/unassigned | 查询未分派审批任务 |
| POST | /api/staff/walk-ins | 创建现场登记 |
| GET | /api/staff/walk-ins/{id} | 查询现场登记详情 |
| GET | /api/staff/visits | 查询厂区访问记录 |
| GET | /api/staff/visits/{id} | 查看访问详情及时间线 |
| GET | /api/staff/gate/presences | 查询当前在厂人员 |
| POST | /api/staff/gate/verifications | 门岗核验预览 |
| POST | /api/staff/gate/entries | 确认入厂 |
| POST | /api/staff/gate/exits | 确认离厂 |

### 8.4 管理、安全和统计 API

| 前缀/路径 | 主要功能 |
| --- | --- |
| /api/staff/admin/configuration | 读取系统配置 |
| /api/staff/admin/sites、departments、gates、areas | 厂区、部门、门岗和区域维护 |
| /api/staff/admin/users、users/{id}/grants、grants/{id} | 员工账号和角色授权 |
| /api/staff/admin/notices | 发布安全须知和隐私说明 |
| /api/staff/admin/registration-links | 创建、查询、启停预约二维码 |
| /api/staff/admin/audit | 查询管理审计记录 |
| /api/staff/blacklist | 黑名单查询、添加和停用 |
| /api/staff/alerts | 查询和处置异常预警 |
| /api/staff/corrections | 提交补录及安全审核 |
| /api/staff/dashboard | 首页统计卡片 |
| /api/staff/statistics | 来访、部门和车辆统计 |
| /api/staff/exports | visits、vehicles、alerts CSV 导出 |

### 8.5 错误响应

业务异常由 ProblemAdvice 统一转换为 JSON，基本结构如下：

    {
      "code": "INVALID_INPUT",
      "message": "请求参数或业务状态提示"
    }

常见 HTTP 状态包括 400（参数或业务校验失败）、401（未登录或会话失效）、403（权限不足）、404（记录不存在或不可访问）、409（业务冲突）、429（请求过于频繁）和 503（短信服务不可用）。

## 9. 权限与安全机制

### 9.1 员工权限

员工登录后由 Catalog.actor 读取有效 staff_grant。授权可限制：

- role：业务角色；
- site_id：指定厂区，NULL 表示不限制厂区；
- department_id：指定部门；
- gate_id：指定门岗；
- start_at / end_at：授权生效和失效时间。

后端对每个敏感 API 再次检查权限，不以“前端隐藏菜单”作为安全边界。员工账号停用、密码或 MFA 更新会递增 auth_version，使旧会话失效。

### 9.2 登录、会话与 CSRF

- 员工密码采用 BCrypt 校验。
- ADMIN、ADMINISTRATION、SAFETY、EXPORT 等特权角色在非演示模式下要求配置 TOTP。
- TOTP 为 6 位动态验证码，服务端防止同一计数窗口重复使用。
- Spring Security 使用 HTTP Session 保存认证状态；登录时轮换 Session ID。
- Cookie 使用 HttpOnly、SameSite=Strict；Secure 属性由 SECURE_COOKIES 控制，生产应保持开启。
- 非 GET 请求通过 CSRF Token 保护。
- 员工登录、访客验证码、验证码校验、邀请领取和门岗预览均有请求频率限制。

### 9.3 数据保护

- VMS_SECRET 是摘要和加密派生密钥的基础，生产环境至少配置 32 个随机字符。
- 手机号、证件号、通行码和邀请令牌不应写入普通日志。
- 证件完整号码不作为明文长期保存，身份匹配使用带密钥摘要，界面只展示证件尾号。
- CSV 导出对姓名和车牌脱敏，并处理表格公式注入。
- 生产数据库备份必须与对应的 VMS_SECRET 一并安全保管；丢失密钥会导致依赖该密钥的数据无法正常解密或匹配。

## 10. 本地开发与运行

### 10.1 环境要求

- JDK 21
- Maven 3.6.3 或更高版本
- Node.js 22.12 或更高版本
- Docker（用于本地 MySQL 或完整 Compose 部署）

以下命令均在仓库根目录执行。

### 10.2 启动开发数据库

    docker run -d --name ks-vms-dev-db \
      -p 127.0.0.1:33317:3306 \
      -e MYSQL_RANDOM_ROOT_PASSWORD=yes \
      -e MYSQL_DATABASE=ks_vms \
      -e MYSQL_USER=vms \
      -e MYSQL_PASSWORD=ks-vms-local-db \
      mysql:8.4

等待 MySQL 健康后再启动后端。开发数据库没有挂载持久化卷，删除容器会丢失其中数据。

### 10.3 启动后端

    VMS_DEMO=true \
    SECURE_COOKIES=false \
    API_DOCS=true \
    DB_PASSWORD=ks-vms-local-db \
    VMS_VISITOR_URL=http://127.0.0.1:5173 \
      mvn -f backend/pom.xml spring-boot:run

默认后端地址为 http://127.0.0.1:8080。Flyway 会自动执行数据库迁移。演示模式要求后端绑定回环地址，不允许以非回环地址启动。

### 10.4 启动前端

    npm --prefix frontend ci
    npm --prefix frontend run dev

默认前端地址为 http://127.0.0.1:5173。Vite 会把 /api 请求代理到 http://127.0.0.1:8080；如后端地址不同，可设置 VMS_API_TARGET。

常用入口：

| URL | 页面 |
| --- | --- |
| http://127.0.0.1:5173/ | 员工工作台 |
| http://127.0.0.1:5173/#gate | 门岗工作台 |
| http://127.0.0.1:5173/#qr | 二维码管理 |
| http://127.0.0.1:5173/visitor?register=company | 企业预约登记 |
| http://127.0.0.1:5173/visitor?register=east-gate | 东门现场登记 |

### 10.5 演示账号

首次启动空数据库时，Bootstrap 会创建演示厂区、部门、门岗、区域、二维码、须知和体验账号。演示账号统一密码为 KS-VMS-demo-2026：

| 账号 | 权限用途 |
| --- | --- |
| admin | 系统管理及全局业务体验 |
| guard | 门岗核验、现场登记和车辆登记 |
| approver | 接访主管及各类型审批 |
| safety | 安全管理、黑名单和更正审核 |
| employee | 员工邀请 |

演示数据仅在 staff 表为空时初始化。演示模式仅用于本机开发和虚构数据测试，不可暴露到公网。演示模式下验证码会在响应中返回 demoCode。

## 11. 生产部署

### 11.1 配置环境变量

    cp .env.example .env

至少配置以下变量：

| 变量 | 用途 |
| --- | --- |
| VMS_STAFF_HOST | 员工工作台域名 |
| VMS_VISITOR_HOST | 访客入口域名，生成访客链接时使用 |
| DB_PASSWORD | 应用数据库账号密码 |
| MYSQL_ROOT_PASSWORD | MySQL root 密码 |
| VMS_SECRET | 至少 32 个随机字符的摘要/加密密钥 |
| VMS_ADMIN_PASSWORD | 首次初始化管理员密码，要求 12 至 72 字节 |
| VMS_ADMIN_TOTP | 首次初始化管理员的 Base32 验证器密钥，32 至 64 个字符 |
| VMS_SMS_URL | 短信验证码交付服务 HTTPS 地址 |
| VMS_SMS_TOKEN | 短信服务 Bearer Token |

首次启动生产数据库且 staff 表为空时，系统使用 VMS_ADMIN_PASSWORD 和 VMS_ADMIN_TOTP 创建 admin 账号。管理员账号和其他特权账号需要 MFA。已经存在员工数据时，Bootstrap 不会重置管理员密码或重复创建初始账号。

短信服务通过 HTTPS POST 接收 JSON：phone、code、expiresInSeconds，并使用 Authorization: Bearer 令牌认证。服务需返回 2xx 表示受理成功。

### 11.2 配置证书并构建

将证书和私钥放入 deploy/certs/：

- staff.crt、staff.key：员工域名证书和私钥；
- visitor.crt、visitor.key：访客域名证书和私钥。

然后执行：

    mvn -f backend/pom.xml package
    npm --prefix frontend ci
    npm --prefix frontend run build
    docker compose up -d --build

Compose 会启动 MySQL、Spring Boot 后端和 Nginx 网关。MySQL 数据保存在 mysql-data 命名卷中，网关暴露 80 和 443 端口。

### 11.3 上线检查

- 域名 DNS 已指向部署主机，员工和访客证书及私钥路径正确。
- VMS_DEMO 保持 false；SECURE_COOKIES 保持 true。
- VMS_SECRET、数据库密码、管理员密码和短信令牌使用独立强随机值。
- 已配置真实厂区时区、营业时间、预约提前量、最大停留时间和门岗。
- 已创建部门、员工、审批岗位授权和安全岗位授权，并确认授权有效期。
- 已发布安全须知和隐私说明，已验证企业二维码和门岗二维码。
- 使用真实手机、门岗终端和摄像头验证预约、邀请、审批、扫码入厂、跨门岗离厂及异常更正。
- 已建立数据库备份、VMS_SECRET 保管和恢复演练流程。

## 12. 测试与构建

### 12.1 后端测试

    mvn -f backend/pom.xml test

PolicyTest 覆盖来访类型审批链、预约提前量、现场时间窗口、厂区开放时间和 TOTP 测试向量。

LiveWorkflowTest 是在线流程测试，只有设置 VMS_TEST_URL 后才启用：

    VMS_TEST_URL=http://127.0.0.1:8080 mvn -f backend/pom.xml test

在线测试会向已启动的演示服务写入虚构数据，覆盖预约归属隔离、邀请领取、二维码启停、现场审批、扫码进出厂、幂等重试、黑名单、统计和导出。不要将其指向生产环境。

### 12.2 前端构建

    npm --prefix frontend run build

构建产物写入 frontend/dist，部署镜像会将该目录复制到 Nginx 静态目录。

## 13. 开发约定与维护建议

1. **先确认业务边界。** 访客公开接口不能返回员工 ID、部门、区域等内部数据；新增字段时同时检查 publicView 白名单和在线测试。
2. **业务规则集中维护。** 来访类型、审批岗位和预约窗口放在 Policy；门岗最终校验放在 Gates；申请状态和审批事务放在 Visits。
3. **写操作必须考虑并发。** 涉及审批、申请修改、身份绑定、入厂离厂和黑名单的写入，应检查事务边界、行锁、唯一约束和幂等行为。
4. **数据库变更只追加迁移。** 新建 V7、V8 等 Flyway 迁移，不直接修改已经应用到环境中的旧迁移文件。
5. **授权由后端校验。** 新增员工 API 时检查 Catalog.Actor 的角色与厂区/部门/门岗范围，不能只依赖前端菜单。
6. **时间统一用 UTC 存储。** 业务展示和自然日统计使用厂区时区，不要直接依赖服务器本地时区。
7. **同步维护测试和文档。** 修改角色、接口、状态、数据库字段或部署变量时，同步更新本指南、README 和对应测试。
8. **敏感信息不得提交。** .env、生产证书、密钥、真实访客信息和短信令牌不得进入 Git。

## 14. 当前实现边界与待核对事项

以下内容用于避免把设计目标误认为已经交付的功能：

- 审批链目前由代码枚举固定映射；staff_grant 可配置审批人员和授权范围，但尚不是数据库驱动的可视化审批规则编辑器。
- 当前未发现 Redis、消息队列或独立通知中心配置。短信交付集成用于发送验证码，不代表已经实现审批通知。
- 当前没有独立访客账号表；自助预约通过浏览器专属 Cookie 关联记录，受邀人通过手机号验证码会话领取邀请。
- 当前数据库真实表结构以 Flyway V1 至 V6 为准。开发设计文档中的部分表名和组件是逻辑设计描述，不等于现有实现。
- 当前后端为单体应用，Servlet Session 默认存储在应用实例内。若未来部署多个后端实例，需要先设计共享会话或粘性会话方案，并验证幂等和调度任务的多实例行为。
- OpenAPI / Knife4j 默认关闭（API_DOCS=false）；本地可开启用于开发调试。生产环境是否开放接口文档应单独评估访问路径和权限。

---

## 15. 相关文档

- [项目入口与快速开始](../README.md)
- [产品需求与验收场景](visitor-management-requirements.md)
- [开发设计与目标架构](visitor-management-design.md)
