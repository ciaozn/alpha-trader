# Tasks 001: Alpha Trader 细粒度任务清单（进行中）

| 字段 | 值 |
|---|---|
| 状态 | **P0-P3 已完成**；**P4 编码完成**（出口验收 T409 与两处联调需用户凭据）；**P5 进行中**：T501-T504 由我实现，T505 待办，T506-T508 需用户执行 |
| 创建日期 | 2026-09-09 |
| 最近更新 | 2026-09-12（瘦身：已完成阶段移入归档） |
| 上游文档 | [plan.md](./plan.md)（Approved）· [spec.md](./spec.md)（Approved） |
| 历史归档 | [tasks-archive-p0-p3.md](./tasks-archive-p0-p3.md)（P0-P3 全部细任务 + 逐条验收记录） |

> 本文件**只保留进行中与待办**任务：已完成阶段的细任务与执行日志已归档（见上表），
> 因此文件长度不随项目推进增长。每条任务 ≤ 2 小时工作量，含可执行的验收方式。

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
| T501 | 实盘资金上限守卫：`alpha.online.max-equity`（默认 1000 USDT）在 live 模式作为一条风控规则接入管道，权益超过上限即拒绝开仓（`RK-00-live-cap`）——防的是「账户里多了钱，仓位跟着变大」这件没人盯着的事 | SC-06、plan P5-1 | 单测：超限拒绝 / 限内放行 / 减仓单不被拦；缺省值 1000 | ☐ |
| T502 | 启动前置校验 `PreflightChecks`：密钥存在性、testnet 与 profile 是否自相矛盾、时钟偏移、交易规则是否已缓存、告警通道是否可用，加 SEC-02 手工项提醒；输出 PASS/FAIL/SKIP 清单 | SEC-02、边界 8 | 单测：每项失败的独立信息；清单可读 | ☐ |
| T503 | 告警演练：`POST /api/alert/test` 发一封测试邮件（返回 sent/skipped/detail），live 启动时自动演练一次 | OP-03、plan P5-1 | 容器测试：未启用→skipped；启用→走传输层；失败不影响引擎 | ☐ |
| T504 | 每日净值日报：从 `equity_snapshot` 汇总（当日 P&L、累计收益、最大回撤、快照数）→ 邮件，按日定时发送 | plan P5-4、SC-06 | 单测：给定快照序列数字正确；空数据不抛异常 | ☐ |
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
