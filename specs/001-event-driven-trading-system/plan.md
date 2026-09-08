# Plan 001: Alpha Trader 实现计划

| 字段 | 值 |
|---|---|
| 状态 | Approved v1.0 |
| 创建日期 | 2026-09-08 |
| 上游文档 | [spec.md](./spec.md)（Approved）· [docs/DESIGN.md](../../docs/DESIGN.md) |
| 开发方法 | SDD：本 plan 把 spec 的需求映射为有序的技术实施阶段 |

> 本文档定义 **实施顺序与任务映射**：先做什么、后做什么、每个阶段交付什么、怎么验证。
> 阶段设计原则：**每个阶段结束系统都处于可运行、可验证状态**，不留"烂尾中间态"。

---

## 1. 技术上下文（既定决策汇总）

| 项 | 决策 |
|---|---|
| 语言 / 框架 | Java 21 LTS + Spring Boot 3.x |
| 构建 | Maven 多模块（父 POM 统一依赖版本） |
| 事件引擎 | 自研单线程事件循环（BlockingQueue + 优先级定时器），预留 Disruptor 升级口 |
| 交易所接入 | 自写轻客户端：OkHttp（REST + HmacSHA256 签名）+ Java-WebSocket |
| 指标库 | 自实现 SMA / EMA / RSI / ATR（不引 ta4j，保证回测实盘一致） |
| 数据库 | SQLite（本地开发）/ MySQL 8（服务器），schema 一致 |
| 告警 | QQ 邮箱 SMTP → ciaozn@qq.com（JavaMailSender，异步 + 聚合节流） |
| 监控 | Spring Actuator + 自建 `/api/status` |
| 部署 | 海外 VPS（AWS 东京优先），Docker + docker-compose（app + mysql） |
| 测试 | JUnit 5 + 固定数据集回测冒烟（CI） |

---

## 2. 阶段总览

| 阶段 | 名称 | 交付物 | 覆盖的 spec 需求 | 出口验证 |
|---|---|---|---|---|
| P0 | 工程骨架 | 可启动的空系统 | FR-SEC-01 | `mvn verify` 全绿，app 启动打印 banner |
| P1 | 事件引擎 + 行情接入 | testnet 实时 K 线驱动事件流 | GW-01/02/04/05、EN-01~04 | 控制台实时打印 BTCUSDT K 线事件，断线自动重连 |
| P2 | 策略 + 回测 | 可跑历史回测并出报告 | ST-01~05、BT-01~06 | SC-01、SC-02（性能 + 确定性） |
| P3 | 风控 + OMS 全链路 | testnet 信号→风控→下单→成交 | RK-01~08、EX-01~06、OP-01/04 | 场景 2、场景 3 验收通过 |
| P4 | 加固 + OKX + 告警 | 可 7×24 无人值守的模拟盘 | GW-03/06、OP-02/03、EN-05、RK-06/09、EX-05 | SC-03（7 天连续运行） |
| P5 | 小资金实盘 | 实盘运行 | SEC-02、全部 P0 | SC-05、SC-06 |

阶段依赖：P0 → P1 → P2 → P3 → P4 → P5，严格串行（每个阶段是下一个的地基）。

---

## 3. P0 工程骨架（预计 0.5 天）

**目标**：搭好 Maven 多模块骨架与纪律红线，后续所有工作在骨架内生长。

| # | 任务 | 验收 |
|---|---|---|
| P0-1 | 父 POM + 8 个子模块（common/engine/gateway/strategy/risk/execution/backtest/app），依赖单向 | `mvn dependency:analyze` 无反向依赖 |
| P0-2 | 红线检查：common/engine 不依赖任何交易所相关库 | 在 POM 层面就没有可依赖项 |
| P0-3 | Spring Boot 主应用空壳 + application.yml 三 profile（backtest/paper/live） | 三种 profile 均可启动 |
| P0-4 | 密钥注入机制：环境变量 + `.env`（gitignore）+ 启动时缺失即报错 | 仓库 grep 零密钥 |
| P0-5 | GitHub Actions CI：编译 + 单测 | push 触发全绿 |
| P0-6 | 日志规范：Logback，按天滚动，事件日志与应用日志分离 | — |

**关键设计**：P0-1 中模块依赖方向必须在 POM 里写死 `app → backtest → {strategy, risk, execution, gateway} → engine → common`，用构建工具强制纪律，而不是靠自觉。

---

## 4. P1 事件引擎 + 行情接入（预计 2 天）

**目标**：事件引擎跑起来，接入 Binance testnet 行情，事件流肉眼可见。

| # | 任务 | 对应 spec | 验收 |
|---|---|---|---|
| P1-1 | 事件模型：sealed interface Event + 全部 record 子类型（alpha-common） | EN-02 前置 | 编译期穷举检查的单元测试 |
| P1-2 | Clock 抽象：SystemClock / VirtualClock | EN-03 | 组件注入 Clock，grep 无 `System.currentTimeMillis()` 业务调用 |
| P1-3 | EventEngine：单线程循环 + FIFO 队列 + 定时器优先队列 + handler 链 | EN-01/04 | 连锁事件单轮闭环的顺序性单测 |
| P1-4 | EventJournal：append-only JSONL 落盘 | EN-02 | 写入/读取 round-trip 测试 |
| P1-5 | ExchangeGateway 接口 + Symbol 统一模型（`BTCUSDT.PERP`） | GW-04 | 接口评审 |
| P1-6 | BinanceFuturesGateway：WS K 线订阅 + 标准化为 KlineEvent | GW-01/05 | testnet 实时打印 K 线 |
| P1-7 | 断线重连：指数退避 + 重订阅 | GW-02 | 手动断网 60s 自动恢复 |
| P1-8 | 交易所时间偏移校准 | 边界情况 8 | 偏差超阈值告警 |

**出口验证**：`--spring.profiles.active=paper` 启动，控制台持续输出 `KlineEvent[symbol=BTCUSDT.PERP, interval=1h, ...]`；拔网线 60 秒后插回，自动重连且 K 线恢复。

**不做的事**：本阶段策略只写一个打印事件的 EchoStrategy 占位，不写真实策略逻辑。

---

## 5. P2 策略 + 回测（预计 4 天）

**目标**：策略框架 + 两个内置策略 + 完整回测链路 + 绩效报告。**这是同构设计的第一个兑现点**：回测跑通即证明引擎与策略可以脱离网络独立运行。

| # | 任务 | 对应 spec | 验收 |
|---|---|---|---|
| P2-1 | Strategy SPI + StrategyContext（K 线滑动窗口 + emit 信号出口） | ST-01/05 | 接口评审 |
| P2-2 | 指标库：SMA / EMA / RSI / ATR（纯函数，可单测） | ST-04 | 与已知参考数据对比的正确性测试 |
| P2-3 | MaCrossStrategy：金叉做多、死叉平多/翻空，参数可配 | ST-02 | 单测：构造 K 线序列触发预期信号 |
| P2-4 | RsiReversalStrategy：超卖做多、超买平多 | ST-03 | 同上 |
| P2-5 | 历史数据下载工具：REST 批量拉 K 线落 SQLite | BT-05 | BTCUSDT 1h 近三年入库 |
| P2-6 | BacktestDataFeeder：按时间序回放，驱动 VirtualClock | BT-01 | 回放顺序性测试 |
| P2-7 | SimulatedExecutor：次根开盘价成交 + 滑点 + 手续费 + 资金费 | BT-02/03 | 撮合规则单测（含反未来函数断言） |
| P2-8 | 简易风控直通（本阶段只做仓位换算，规则管道 P3 接入） | RK-07 前置 | — |
| P2-9 | 绩效统计 + HTML 报告（净值曲线、年化、夏普、回撤、胜率、盈亏比、逐笔明细） | BT-04 | 报告生成，指标公式经人工抽验 |
| P2-10 | 数据缺口检测：缺根记录并计入报告 | 边界情况 3 | 构造缺根数据验证 |

**出口验证（SC-01 + SC-02）**：BTCUSDT 1h 近三年回测 + 报告生成 < 5 分钟；同一输入跑 3 次，绩效指标逐位一致。

**关键设计**：P2-7 的撮合器是"反未来函数"纪律的物理载体——信号在收盘产生、次根开盘成交的规则写死在撮合器里，策略作者无法绕过。CI 加入回测冒烟：固定数据集 + 绩效指标阈值断言，防止后续改动悄悄改变策略行为。

---

## 6. P3 风控 + OMS 全链路（预计 4 天）

**目标**：testnet 上跑通「信号 → 风控闸门 → 下单 → 成交 → 持仓更新」全链路。**这是系统第一次真正"碰"交易所的写接口。**

| # | 任务 | 对应 spec | 验收 |
|---|---|---|---|
| P3-1 | 风控规则框架：RiskRule 接口 + 管道串联 + 拒绝即拦截 | RK-01 | 管道顺序与短路行为单测 |
| P3-2 | 五级规则实现：账户（杠杆/保证金率）、订单（单笔限额/价格偏离）、组合（总仓/单标的）、熔断（日亏损/连亏暂停） | RK-02~05 | SC-04：拦截测试用例 100% 拦截 |
| P3-3 | 仓位换算：信号强度 → 目标数量 → stepSize 取整；小于最小数量放弃下单并告警 | RK-07、边界 4 | 换算正确性 + 边界单测 |
| P3-4 | 风控告警事件 + 拦截记录落库可查 | RK-08 | 拦截记录含命中规则与账户快照 |
| P3-5 | OMS 订单状态机：NEW→SUBMITTED→…→FILLED/CANCELED/REJECTED，迁移落库 | EX-01 | 状态机全路径单测 |
| P3-6 | clientOrderId 幂等：策略 ID + 时间戳 + 序号 | EX-02 | 重复提交不产生重复订单的测试 |
| P3-7 | 精度对齐：启动拉取交易规则缓存，下单前 tickSize/stepSize/minNotional 对齐 | GW-03 | 脏单被拒测试 |
| P3-8 | Binance 下单 REST（HmacSHA256 签名）+ user data stream（listenKey + 30min keepalive） | EX-03 | testnet 下单成交回报链路 |
| P3-9 | 成交回报处理：FillEvent 回事件引擎 → 持仓/净值更新 → 落库 | EX-06 | 场景 2 验收 |
| P3-10 | 定时对账：60s 拉挂单 + 持仓，以交易所为准修正，差异告警 | EX-04、边界 2 | 人为制造状态不一致验证对账修正 |
| P3-11 | 三模式装配收口：BACKTEST/PAPER/LIVE 由 profile 切换可替换件 | OP-01 | 三种模式均可启动 |

**出口验证**：spec 场景 2（模拟盘全链路）与场景 3（风控硬闸门）逐条验收通过。

**关键设计**：P3-10 对账是实盘生命线，优先级高于一切新功能——"本地状态可以错，但必须在 60s 内被发现并修正"。

---

## 7. P4 加固 + OKX + 告警（预计 3 天）

**目标**：从"能跑"到"敢放着跑 7 天"。

| # | 任务 | 对应 spec | 验收 |
|---|---|---|---|
| P4-1 | 邮件告警：JavaMailSender + QQ SMTP，异步发送 + 同类 5min 聚合 | OP-03 | 五类告警全部可达 ciaozn@qq.com |
| P4-2 | REST 状态接口：持仓 / 权益 / 引擎心跳 / 网关延迟 | OP-02 | `/api/status` 返回完整状态 |
| P4-3 | 崩溃恢复：重启 → 交易所全量同步持仓 → 事件日志回放校验 → 恢复策略 | EN-05、边界 7 | kill -9 后重启状态正确 |
| P4-4 | 频率规则 + 风控参数热更新（含二次确认） | RK-06/09、SEC-03 | 热更新不重启生效 |
| P4-5 | 幽灵仓位处理：告警或自动平仓（配置选择） | EX-05 | 人为在交易所开仓验证发现与处置 |
| P4-6 | OkxSwapGateway：v5 行情 + 交易 + demo 环境 | GW-06 | OKX demo 全链路 |
| P4-7 | Dockerfile + docker-compose（app + mysql）+ VPS 部署脚本 | 部署 | VPS 一键起服务 |
| P4-8 | MySQL schema + 服务器数据源切换 | OP-04 | SQLite/MySQL 双数据源测试 |

**出口验证（SC-03）**：testnet 连续运行 7 天，无未处理异常，对账零差异，告警邮件按预期到达。

---

## 8. P5 小资金实盘（预计 1 天切换 + 2 周观察）

| # | 任务 | 验收 |
|---|---|---|
| P5-1 | 实盘前检查清单：API Key 禁提币 + IP 白名单 + 风控参数复核 + 告警演练 | SEC-02 逐项签字 |
| P5-2 | 充值 ≤ 1000 USDT，LIVE 模式启动 MA 交叉策略 | 首笔实盘信号链路正确 |
| P5-3 | 同构一致性抽查：实盘信号序列 vs 同期回测信号序列 | SC-05 |
| P5-4 | 观察 2 周：每日对账记录 + 净值跟踪，无异常方可加仓 | SC-06 |

---

## 9. 风险登记册（实施期重点盯防）

| 风险 | 影响阶段 | 对策 | 触发后预案 |
|---|---|---|---|
| 大陆网络无法直连 testnet 调试 | P1/P3/P4 | 开发机配代理；尽早把联调挪到 VPS | P1 延期则在 VPS 上远程开发 |
| 回测/实盘行为漂移 | P2 起全程 | 同构三可替换件纪律 + CI 回测冒烟 + SC-05 抽查 | 定位漂移源（时钟/精度/撮合假设），修一致性而非修数字 |
| WS 私有流 listenKey 过期丢回报 | P3/P4 | 30min keepalive + REST 对账兜底 | 对账 60s 内自愈，告警复盘 |
| 邮件告警延迟/进垃圾箱 | P4 | 异步 + 聚合；QQ 邮箱加白名单 | 备用渠道：Server 酱/企业微信 webhook（接口已抽象） |
| 交易所 API 变更 | 全程 | 网关层集中适配，契约测试守护 | 版本锁定 + 变更告警监控官方 changelog |
| 资金费计提偏差 | P2/P3 | 回测用历史资金费数据，实盘按实际结算 | 绩效报告单独列示资金费项便于核对 |

---

## 10. 下一步

1. 本 plan 评审通过 → 生成 `tasks.md`：把 P0–P1 拆成可逐条执行、逐条勾选的细粒度任务（每条 ≤ 2 小时工作量，含验收命令）
2. tasks.md 确认后从 P0-1 开始实现

> tasks 采用滚动式生成：先出 P0+P1 的细任务，P2 及以后在接近开工时再细化，避免远期计划过早锁死。
