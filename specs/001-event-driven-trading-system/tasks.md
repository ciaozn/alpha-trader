# Tasks 001: Alpha Trader 细粒度任务清单（进行中）

| 字段 | 值 |
|---|---|
| 状态 | **P0-P3 已完成**；**P4 编码完成**（T409 与两处联调需用户凭据）；**P5：T501-T504 已完成**（实盘守卫/前置校验/告警演练/净值日报），T505 待办（缺回测信号导出），T506-T508 需用户执行 |
| 创建日期 | 2026-09-09 |
| 最近更新 | 2026-09-12（瘦身：已完成阶段移入归档） |
| 上游文档 | [plan.md](./plan.md)（Approved）· [spec.md](./spec.md)（Approved） |
| 历史归档 | [tasks-archive-p0-p3.md](./tasks-archive-p0-p3.md)（P0-P3 全部细任务 + 逐条验收记录） |

> 本文件**只保留进行中与待办**任务：已完成阶段的细任务与执行日志已归档（见上表），
> 因此文件长度不随项目推进增长。每条任务 ≤ 2 小时工作量，含可执行的验收方式。

---

## 阻塞与待办总表（唯一权威清单）

> 这里是全项目**唯一**的未完成事项清单：`tasks.md` 的表格记录任务详情，本表回答「现在卡在哪、卡在谁手上」。
> 每次状态变化都必须同步这张表——分散在两处的清单迟早会互相矛盾。

### A. 阻塞项（需外部条件，非代码问题）

| 项 | 需要什么 | 出处 |
|---|---|---|
| T112 断网 60 秒恢复联调 | 可人为断网并恢复的网络环境 | P1 遗留 |
| T321 场景 2 模拟盘全链路 | Binance testnet API Key（`BINANCE_API_KEY/SECRET`） | P3 出口 |
| T409 SC-03 七天连续运行 | testnet Key + 连续 7 天不中断 | P4 出口 |
| T406 OKX demo 全链路联调 | OKX 凭据（`OKX_API_KEY/SECRET/PASSPHRASE`） | P4 |
| T407 Docker 镜像构建验证 | 一台装了 Docker 的机器（本机没有） | P4 |
| T408 MySQL 真实连接验证 | 可连的 MySQL 8 实例（或同一台 Docker 机器） | P4 |
| T506 实盘启动（SEC-02 逐项签字 + 首笔链路） | 真实资金 + 实盘 Key | P5 出口 |
| T507 SC-06 两周观察 | 真实时间（两周） | P5 出口 |

### B. 待办项（不阻塞，可继续做）

| 项 | 前置条件 | 备注 |
|---|---|---|
| T505 SC-05 同构一致性抽查 | **需先补一座桥**：`BacktestReport` 目前只导出成交/挂单，不导出信号；比对的两端必须都是信号序列 | 桥补完再加比对逻辑，估计半小时；不做半成品 |

### 已修复但值得记住的漂移（2026-09-12）

| 漂移 | 真相 |
|---|---|
| `application-live.yml` 未配 `alpha.alert.drill-on-startup`，而 `AlertDrillRunner` 的注释声称 live 默认开启 | 已补配置：注释与配置必须一起改，否则「说过的话」和「做的事」不一致 |
| `Dockerfile` 注释引用了不存在的 `StopWiring` 类 | 实际停机机制是各 bean 的 destroy 方法（引擎 stop、sender close、网关 close），注释已改为真实机制 |
| `docs/DESIGN.md` 写「四张表」「告警用 Telegram/钉钉」 | 实现是六张表 + QQ 邮箱 SMTP；设计文档已升到 v1.1 并记录本次修订 |

---

## 阶段进度

| 阶段 | 状态 | 出口验证 | 证据 |
|---|---|---|---|
| P0 工程骨架 | ✅ 完成 | `mvn clean verify` 全绿；三 profile 可启动 | 归档 §P0 |
| P1 事件引擎 + 行情接入 | ✅ 完成 | paper 实测连 testnet 接收实时 K 线 | 归档 §P1（T112 断网恢复留用户） |
| P2 策略 + 回测 | ✅ 完成 | SC-01/SC-02：近三年回测 <5min、三次逐位一致 | 归档 §P2、`reports/` |
| P3 风控 + OMS 全链路 | ✅ 完成 | 场景 3 离线端到端通过；在线链路已通电 | 归档 §P3、`ScenarioThreeHardGateTest` |
| **P4 加固 + OKX + 告警** | ⏳ **进行中** | SC-03：testnet 连续 7 天零差异（待用户） | 本文件 |
| P5 小资金实盘 | ⬜ 未开始 | SC-05/SC-06 | — |

---

## P4 任务（进行中）

| # | 任务 | 对应 spec / plan | 验收 | 状态 |
|---|---|---|---|---|
| T401 | 邮件告警：JavaMailSender + QQ SMTP（授权码走环境变量），异步发送 + 同类 5 分钟聚合节流；五类消息：成交/风控拦截/熔断/断线重连/对账异常 | OP-03、plan P4-1 | 离线单测：窗口内同类只发一封、CRITICAL 立即发、失败降级不影响引擎 | ☑ 完成：QQ SMTP 异步出站 + 同类五分钟聚合，CRITICAL 立即发（`app/alert`，24 例单测） |
| T402 | REST 状态接口 `/api/status`：持仓、权益、引擎心跳、网关状态与延迟、运行模式 | OP-02、plan P4-2 | 容器测试断言字段齐全；backtest/download 不得被拉起 Web 容器 | ☑ 完成：`/api/status`（持仓/权益/心跳/网关健康/当日已实现盈亏），离线 profile 不启 Web 容器 |
| T403 | 崩溃恢复：启动先全量同步交易所 → 回放事件日志校验 → 恢复策略；不一致只告警不静默改账 | EN-05、边界 7 | kill -9 后重启状态正确；日志回放与库内状态比对单测 | ☑ 完成：`JournalReplay`（日志回放期望状态）+ `StartupRecovery`（启动同步→回放校验→差异只告警） |
| T404 | 风控参数热更新（含二次确认），改动写审计记录 | RK-09、SEC-03 | 热更新不重启生效；越界参数被拒 | ☑ 完成：`RiskGate.reload` 原子换入管道 + `app/risk` 二次确认接口，非法参数整单拒绝 |
| T405 | 幽灵仓位处置：补「配置选择自动平仓」分支（默认仍告警） | EX-05、plan P4-5 | 两种配置各自行为的单测 | ☑ 完成：Reconciler 幽灵仓位可选择自动平仓（默认仅告警），幂等 id 前缀 `ghost-close-` 防重复平仓 |
| T406 | OkxSwapGateway：v5 行情 WS + 签名 REST（`OK-ACCESS-*` + HmacSHA256 base64）+ demo 头 | GW-06、plan P4-6 | 解析器与签名离线单测；demo 联调留用户 | ☑ 完成：`alpha.online.gateway=binance|okx` 接线（订阅循环对接口编程，加交易所未改装配逻辑）；签名/解析/客户端均有离线单测（9 例）+ 选择开关 2 例。**OKX demo 全链路联调留用户**（需 OKX 凭据） |
| T407 | Docker：多阶段 Dockerfile + docker-compose（app + mysql）+ 部署脚本 | 部署、plan P4-7 | 镜像可构建；compose 与环境变量清单完整 | ◑ Dockerfile/.dockerignore/docker-compose 就绪；本机无 Docker，镜像构建需用户在 VPS 或本地验证 |
| T408 | MySQL 8 数据源切换（本地仍 SQLite） | OP-04、plan P4-8 | 双 URL 配置解析单测；真实 MySQL 连接留用户 | ☑ 完成：`StoreProperties.dialect()` + `StoreDataSource` 工厂，SQLite/MySQL 按 URL 分发（5 例单测） |
| T409 | **SC-03 出口**：testnet 连续 7 天零差异、告警邮件按预期到达 | SC-03 | ⚠️ 需用户运行 | ⊘ 阻塞 |

> **T321（P3 遗留）**：场景 2 模拟盘全链路联调，需 testnet API Key，同样标记为用户验收项。

---

## P5 任务（进行中）

> P5 的内容是「小额真金白银 + 两周观察」，其中**大部分只有运营者能执行**（真实资金、真实时间、
> 交易账户凭据）。因此这里把 P5 拆成两类：**可写成代码并离线验收的实盘守卫与观察工具**（T501-T505，
> 由我实现并自测），以及**必须由人执行的验收动作**（T506-T508，标注阻塞原因）。

| # | 任务 | 对应 spec / plan | 验收 | 状态 |
|---|---|---|---|---|
| T501 | 实盘资金上限守卫：`alpha.online.max-equity`（默认 1000 USDT）在 live 模式作为一条风控规则接入管道，权益超过上限即拒绝开仓（`RK-00-live-cap`）——防的是「账户里多了钱，仓位跟着变大」这件没人盯着的事 | SC-06、plan P5-1 | 单测：超限拒绝 / 限内放行 / 减仓单不被拦；缺省值 1000 | ☑ 完成：`LiveEquityCapRule`（`RK-00-live-cap`）仅在 live profile 接入管道，作为 leading rule 先于其他规则拒绝；加仓拦、减仓/平仓放行（6 例单测）；`alpha.online.max-equity` 缺省 1000 USDT |
| T502 | 启动前置校验 `PreflightChecks`：密钥存在性、testnet 与 profile 是否自相矛盾、时钟偏移、交易规则是否已缓存、告警通道是否可用，加 SEC-02 手工项提醒；输出 PASS/FAIL/SKIP 清单 | SEC-02、边界 8 | 单测：每项失败的独立信息；清单可读 | ☑ 完成：`PreflightChecks` + `PreflightRunner`（@Order 3）六项清单；两个「说出来才有意义」的一致性检查：live 指向 testnet、paper 指向实盘（8 例单测） |
| T503 | 告警演练：`POST /api/alert/test` 发一封测试邮件（返回 sent/skipped/detail），live 启动时自动演练一次 | OP-03、plan P5-1 | 容器测试：未启用→skipped；启用→走传输层；失败不影响引擎 | ☑ 完成：`AlertDrill`（SENT/SKIPPED/FAILED，失败带原因不抛）+ `POST /api/alert/test` + live 启动按 `alpha.alert.drill-on-startup` 自动演练（3 例单测） |
| T504 | 每日净值日报：从 `equity_snapshot` 汇总（当日 P&L、累计收益、最大回撤、快照数）→ 邮件，按日定时发送 | plan P5-4、SC-06 | 单测：给定快照序列数字正确；空数据不抛异常 | ☑ 完成：`DailyEquityReport`（当日 P&L、百分比、日内最大回撤、快照数；半开 UTC 窗口；空日不产出，6 例单测）+ `DailyReportScheduler`（05:00 UTC，无样本时发「NO SAMPLES」而不是 0 收益） |
| T505 | SC-05 同构一致性抽查：同一时间窗内比较「库内信号序列」与参考序列（策略/标的/方向/bar 时间），输出一致/缺失/多出 | SC-05、plan P5-3 | ☐ **待办**：回测侧目前不导出信号（`BacktestReport` 只有成交与挂单），要先让回测把信号写到可比对的地方，否则这个比对只能做一半——不做半成品 | ☐ |
| T506 | 实盘启动：SEC-02 逐项手工签字 + 首笔实盘信号链路正确 | SC-06、plan P5-2 | ⚠️ 需真实资金与实盘凭据 | ⊘ 阻塞 |
| T507 | SC-06 两周观察：每日对账记录 + 净值跟踪，无异常方可加仓 | SC-06、plan P5-4 | ⚠️ 需真实时间 | ⊘ 阻塞 |
| T508 | SC-03 七天模拟盘 + T321 场景 2 联调 + T406 OKX demo 联调（P4 遗留） | SC-03、SC-02 | ⚠️ 需 testnet / OKX 凭据 | ⊘ 阻塞 |

## 执行日志（P4 / P5）

| 时间 | 任务 | 结果 |
|---|---|---|
| 2026-09-12 03:19 | T401 邮件告警（`app/alert`：AlertDispatcher/AlertThrottle/SmtpAlertTransport/EmailAlertHandler） | 通过：离线单测 24 个全绿；handler 由 `AlertWiring @Order(2)` 在 StartupWiring 之后注册，出站异步、同类五分钟聚合、CRITICAL 立即发 |
| 2026-09-12 09:35 | T402 REST 状态接口（`app/status`：StatusController/StatusSnapshotFactory/EngineHeartbeat/GatewayHealthProbe/DailyRealizedPnl） | 通过：`mvn clean verify` 全绿；`/api/status` 容器测试 4 例；离线 profile 显式关 Web 容器 |

> 更早的执行日志见 [归档](./tasks-archive-p0-p3.md)。
| 2026-09-12 10:30 | **P4 收尾（T401-T408）** | 全部走完，`mvn clean verify` 全绿、**710 用例 0 失败**。T401 邮件告警、T402 状态接口（夜里已完成并验证）、T403 崩溃恢复（`JournalReplay`+`StartupRecovery`）、T404 风控热更新（`RiskGate.reload`+二次确认接口）、T405 幽灵仓位可选自动平仓、T406 OKX 网关（签名/REST/行情/四个解析器）、T407 Docker 部署文件、T408 双数据库方言。**值得记住的一次错误**：OKX 签名我按记忆写成「不含 query string」，还配了测试把错误信念钉住——查官方文档后确认预映像必须包含 query（官方示例 `/api/v5/account/balance?ccy=BTC`），已一并修正实现、测试与 javadoc。**遗留**：T406 只差 app 级网关选择接线；T407 镜像未实际构建（本机无 Docker）；T409/T321 需 testnet 凭据。 |
| 2026-09-12 10:45 | T406 补齐 app 级网关接线（`alpha.online.gateway=binance\|okx`） | 通过：`GatewayWiringConfig` 的订阅循环改为面向 `ExchangeGateway` 接口，新增 `GatewayWiringTest` 2 例断言属性真的选中实现（此前 OKX 网关「有测试但无法启用」——与 T319 之前在线链路的情形同型）。app 模块 184 用例全绿 |
| 2026-09-12 10:40 | **P5 可编码部分完成（T501-T504）** | 通过：`mvn clean verify` 全绿、**736 用例 0 失败**。T501 实盘权益上限（live-only，作为 leading rule 先于其他规则；加仓拦、减仓放行）；T502 启动前置校验六项，其中「live 指向 testnet」与「paper 指向实盘」两个静默配置错误是本任务真正的价值；T503 告警演练（接口 + live 启动自动，失败给原因不抛异常）；T504 每日净值日报（半开 UTC 窗口、日内回撤、空日发「NO SAMPLES」而非 0 收益）。**T505 未做**：回测报告不导出信号（只有成交/挂单），比对只能做一半，不做半成品——已在任务里写明缺的桥是什么。**过程中修了自己一个错**：新增 `AlertDrill` 忘了声明 bean，容器启动即失败，被 paper 容器测试抓住（这正是那类测试存在的意义）。 |
| 2026-09-12 11:15 | 落盘与漂移修正（无代码逻辑变更） | ①`tasks.md` 新增**「阻塞与待办总表」**作为全项目唯一未完成清单（8 项阻塞 + 1 项待办 + 3 条已修复漂移），并写进项目 MEMORY.md；②修正三处「说的和做的不一致」：`application-live.yml` 补 `alpha.alert.drill-on-startup: true`（此前代码注释声称 live 默认开启但配置缺项）、`Dockerfile` 注释不再引用不存在的 `StopWiring` 类、`docs/DESIGN.md` 升 v1.1（四张表→六张表、告警渠道 Telegram/钉钉→QQ SMTP）；③把「读契约→小步实现→模块测试→全量验证→提交→落盘」这套闭环存成可复用技能 `java-sdd-task-loop`。`mvn clean verify` 仍全绿 |
