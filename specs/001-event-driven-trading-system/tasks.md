# Tasks 001: Alpha Trader 细粒度任务清单（P0 + P1 + P2）

| 字段 | 值 |
|---|---|
| 状态 | P0+P1 已完成（T112 留用户验收）；P2 细化并开工中 |
| 创建日期 | 2026-09-09 |
| 上游文档 | [plan.md](./plan.md)（Approved）· [spec.md](./spec.md)（Approved） |
| 生成策略 | 滚动式：本文件含 P0+P1+P2 细任务；P3 及以后临近开工再细化 |

> 每条任务 ≤ 2 小时工作量，含验收命令。完成后勾选并记录验收结果。

---

## P0 工程骨架

| # | 任务 | 验收 | 状态 |
|---|---|---|---|
| T001 | 父 POM：groupId `com.ciaozn.alphatrader`，Java 21，dependencyManagement 锁定 Spring Boot 3.x / OkHttp / Java-WebSocket / Jackson / JUnit5 版本 | `mvn -q validate` 通过 | ☑ |
| T002 | alpha-common 模块：POM（零外部依赖，仅 jackson-annotations）+ 包结构 | 模块可独立编译 | ☑ |
| T003 | alpha-engine 模块：POM 仅依赖 common | 红线：无交易所/网络库依赖 | ☑ |
| T004 | alpha-gateway / strategy / risk / execution 模块：POM 依赖 engine | 依赖方向正确 | ☑ |
| T005 | alpha-backtest 模块：POM 依赖 strategy/risk/execution/gateway | 依赖方向正确 | ☑ |
| T006 | alpha-app 模块：Spring Boot 主类 + 依赖全部模块 | 应用可启动 | ☑ |
| T007 | application.yml 三 profile：backtest / paper / live，模式装配占位 | 三 profile 均可启动 | ☑ |
| T008 | 密钥注入：`.env.example` + `.gitignore` + 启动时 EnvValidator（缺失即 fail-fast） | 仓库 grep 零密钥 | ☑ |
| T009 | Logback：应用日志与事件日志分离，按天滚动 | 启动产生两类日志文件 | ☑ |
| T010 | GitHub Actions CI：编译 + 单测 | workflow 文件就绪 | ☑ |

**P0 出口**：`mvn clean verify` 全绿；`--spring.profiles.active=backtest|paper|live` 均可启动。

## P1 事件引擎 + 行情接入

| # | 任务 | 对应 spec | 验收 | 状态 |
|---|---|---|---|---|
| T101 | Event sealed interface + 全局序号分配器（AtomicLong） | EN-02 | 编译期穷举单测 | ☑ |
| T102 | 行情模型：Symbol（`BTCUSDT.PERP` 统一表示）/ Interval / Kline + KlineEvent / TickerEvent | GW-04 | 模型单测 | ☑ |
| T103 | 交易事件：SignalEvent / OrderRequest / OrderUpdateEvent / FillEvent / TimerEvent / RiskAlertEvent | EN-01 | 模型单测 | ☑ |
| T104 | Clock 抽象：SystemClock / VirtualClock（可推进、可定点触发定时器） | EN-03 | 单测 + grep 无业务侧 `System.currentTimeMillis()` | ☑ |
| T105 | EventEngine：单线程循环 + FIFO 队列 + 定时器优先队列 + handler 链（连锁事件单轮闭环） | EN-01/04 | 顺序性 + 连锁闭环 + 定时器触发单测 | ☑ |
| T106 | EventJournal：append-only JSONL 落盘 + 读取 | EN-02 | round-trip 单测 | ☑ |
| T107 | ExchangeGateway 接口 + GatewayConfig / OrderAck / AccountSnapshot / Position DTO | GW 前置 | 接口编译 | ☑ |
| T108 | TimeSync：启动校准交易所时间偏移，超阈值（默认 1000ms）告警 | 边界 8 | 偏移计算单测 | ☑ |
| T109 | BinanceFuturesGateway（行情部分）：Java-WebSocket 订阅 K 线 → 标准化 KlineEvent，testnet/实盘地址可配 | GW-01/05 | 本地 mock WS 单测 | ☑ |
| T110 | 指数退避重连（1s→60s 上限）+ 重连后重订阅 | GW-02 | 退避序列单测 | ☑ |
| T111 | EchoStrategy 占位 + app 装配：paper profile 打印 K 线事件 | P1 出口 | 应用启动可见事件打印 | ☑ |
| T112 | testnet 真实联调：60s 断网恢复验证 | P1 出口 | ⚠️ 需用户网络环境，标记为用户验收项 | ⊘ 跳过 |

**P1 出口**：T101-T111 全部通过且 `mvn clean verify` 全绿；T112 由用户在可连 testnet 的环境执行。

---

## P2 策略 + 回测

> 本阶段是"同构设计"的第一个兑现点：回测链路完全脱离网络运行，且策略/风控/OMS 代码与实盘共用（FR-BT-06）。
> 环境事实（2026-09-09 实测）：本机 **无法直连 `fapi.binance.com`（实盘 REST）与 `repo.maven.apache.org`**，
> 但 **可直连 `testnet.binancefuture.com`（REST+WS，含 2019 年至今 BTCUSDT 1h 历史）**。
> 因此 T211 下载工具以 testnet 为验收环境，base url 可配置（FR-GW-05）；Maven 走 `~/.m2/settings.xml` 镜像到 `repo1.maven.org`。

| # | 任务 | 对应 spec | 验收 | 状态 |
|---|---|---|---|---|
| T201 | `TradingRules`（tickSize/stepSize/minNotional）落 alpha-common，作为 Symbol 精度模型 | GW-03 前置、RK-07 | 取整/对齐工具单测 | ☑ |
| T202 | `Portfolio` + `Position` 领域模型（common）：成交应用（加仓均价/减仓实现盈亏/翻仓）、手续费、资金费、标记价与权益 | EX-06 前置、ST-01 | 加/减/翻仓 + 权益单测 | ☑ |
| T203 | Strategy SPI：`Strategy`（id/symbols/onKline/onFill/onTimer）+ `StrategyContext`（K 线窗口、持仓视图、Clock、emit 信号） | ST-01/05 | 接口编译 + emit 进 cascade 单测 | ☑ |
| T204 | `BarSeries` 定长环形缓冲：只收 closed bar，按 openTime 去重与丢弃乱序 | 边界 1 | 重复/乱序/容量单测 | ☑ |
| T205 | 指标库：SMA / EMA / RSI / ATR（流式纯计算，回测实盘同一实现） | ST-04 | 与 Python 参考实现逐值对比（EMA 以 SMA 播种、RSI/ATR 用 Wilder 平滑） | ☑ |
| T206 | `StrategyEngine`（EventHandler）：按 symbol 分发 closed K 线、喂 BarSeries、信号经 cascade 发出 | ST-01 | 分发 + 过滤非订阅 symbol 单测 | ☑ |
| T207 | `MaCrossStrategy`：金叉做多、死叉平多/翻空，fast/slow/allowShort 可配 | ST-02 | 构造 K 线序列触发预期信号单测 | ☑ |
| T208 | `RsiReversalStrategy`：超卖做多、超买平多，period/os/ob（+可选 ATR 波动过滤）可配 | ST-03 | 同上 | ☑ |
| T209 | `PositionSizer` + 风控直通闸门（alpha-risk）：信号 → 目标仓位增量 → stepSize 取整 → OrderRequestEvent；小于最小数量/名义则放弃并告警 | RK-07、边界 4 | 换算 + 取整 + 放弃告警单测 | ☑ |
| T210 | `KlineRepository` 契约（common）+ `CsvKlineRepository`（backtest，DESIGN §10 的 CSV 回放源） | BT-01 | CSV round-trip 单测 | ☑ |
| T211 | `JdbcKlineRepository`（alpha-app，SQLite/MySQL 同 schema）+ 建表 | BT-05、OP-04 前置 | 临时 SQLite 文件 round-trip 单测 | ☑ |
| T212 | `BinanceKlineDownloader`（gateway REST，1500 根分页、base url testnet/实盘可配、限速退避） | BT-05、GW-05 | 解析单测（mock JSON）+ testnet 真实拉取入库 | ☑ |
| T213 | `EventEngine.awaitQuiescence(eventId)`：回放方等本轮连锁闭环后再推进虚拟时钟 | BT-01、NFR-04 | 单测（等待返回时 cascade 已完成） | ☐ |
| T214 | `BacktestDataFeeder`：按时间序回放 + 推进 VirtualClock + 数据缺口检测记录 | BT-01、边界 3 | 顺序性 + 缺根记录单测 | ☐ |
| T215 | `SimulatedExecutor`：次根开盘价成交 + 滑点（固定 bp + 振幅比例）+ taker 手续费 + 资金费（8h 计提） | BT-02/03、边界 5 | 撮合规则单测 + **反未来函数断言**（信号根不成交、成交根不用未来价） | ☐ |
| T216 | `TradeTracker` + `PerformanceMetrics` + `PerformanceAnalyzer`：净值曲线、年化、夏普、最大回撤、胜率、盈亏比 | BT-04 | 手工构造净值/成交序列的公式单测 | ☐ |
| T217 | 报告输出：自包含 HTML（内联 SVG 净值曲线 + 逐笔明细 + 缺口）+ 控制台摘要 | BT-04 | 生成报告含全部指标，无外部 CDN 依赖 | ☐ |
| T218 | `BacktestRunner`：纯 Java 装配全链路（引擎/虚拟时钟/feeder/撮合/策略/风控直通/报告），不依赖 Spring | BT-06 | 固定数据集端到端跑通并出报告 | ☐ |
| T219 | alpha-app backtest 装配：`alpha.backtest.*` 配置（数据源 csv/db、策略启停与参数、初始资金、滑点费率）+ 跑完自动退出 | OP-01、ST-01 | `--spring.profiles.active=backtest` 出报告后进程正常退出 | ☐ |
| T220 | CI 回测冒烟：固定数据集 + 绩效指标阈值断言 + 同输入 3 次运行逐位一致 | SC-02、plan §5 | `mvn clean verify` 全绿（含冒烟测试） | ☐ |
| T221 | SC-01 性能验收：BTCUSDT 1h 近三年（≈26k 根）完整回测 + 报告 < 5 分钟 | SC-01 | 实测计时记录在执行日志 | ☐ |
| T222 | 策略工厂注册机制：`StrategyFactory` SPI + yml 按 id 启停传参，新增策略零改引擎 | ST-01、场景 6 | 新增一个 dummy 策略仅靠配置生效的单测 | ☑ |

**P2 出口**：T201-T222 全部通过；`mvn clean verify` 全绿；backtest profile 端到端出 HTML 报告；SC-01/SC-02 达标。

> 设计取舍记录（与 DESIGN 的非冲突细化）：
> 1. DESIGN §7.2 提到 Spring 扫描 `@StrategyComponent`。实现改为 **`StrategyFactory` SPI（`META-INF/services` 发现）+ app 侧按 yml 装配**，
>    以保持 alpha-strategy 模块零 Spring 依赖（策略可脱离容器单测）。新增策略只需：策略类 + 工厂类 + services 一行 + yml 一条，
>    引擎/风控/OMS/app 装配代码零改动（场景 6、NFR-06）；分发顺序取 yml 顺序而非 ServiceLoader 发现顺序，保证可复现。
> 2. K 线仓储契约放 alpha-common（纯接口，零依赖），SQLite/MySQL 的 JDBC 实现放 alpha-app（唯一有 Spring/驱动的模块），
>    CSV 实现放 alpha-backtest —— 严格守住 `app → backtest → {strategy,risk,execution,gateway} → engine → common` 单向依赖。
> 3. `Portfolio`（持仓/权益/已实现盈亏）放 alpha-common：它是 spec §5 的领域实体，回测撮合与实盘 OMS 必须共用同一份实现（FR-BT-06）。
> 4. **P2 的风控范围是刻意收窄的，不是遗漏**：`RiskGate` 目前只做仓位换算（FR-RK-07）+ 交易所最小值校验（边界 4），
>    DESIGN §8 的账户/单笔/组合/熔断/频率规则在 P3 以有序管道接在它前面，下游（OMS/撮合）无需改动。
>    同理 `PositionSizer.Policy.targetExposure` 被限制在 (0, 1]：在账户级杠杆规则（FR-RK-02/04）就位前，
>    sizer 不允许单靠自己把敞口放大到超过权益，上限放开必须与那两条规则同一个 PR 落地。
> 5. **`JdbcKlineRepository` 的 MySQL 兼容目前是「构造上成立」，不是「实测通过」**：同一份方言中立 DDL（不用保留字、
>    `VARCHAR`/`BIGINT`/主键三元组）、价格一律存**文本**（SQLite 的 NUMERIC 亲和性会把 `'36498.60'` 存成 double，
>    MySQL 的 `DECIMAL(24,8)` 会补零成 `36498.60000000`，两者都会破坏逐位复现；这些列上没有任何 SQL 运算），
>    `save` 的「新增/变更条数」由内存 diff 得出（MySQL 报*已修改*行数、SQLite 报*匹配*行数，驱动的返回值都答不了这个问题）。
>    但测试只跑了 SQLite，`mysql-connector-j` 也刻意尚未加入依赖 —— 等 P3/P4 真正需要 MySQL 持久化时再补集成测试。

---

## 执行日志

| 时间 | 任务 | 结果 |
|---|---|---|
| 2026-09-09 01:00 | T001-T010（P0 全部） | 通过：mvn clean verify 全绿；三 profile 可启动；live 缺密钥 fail-fast 正确；双日志文件生成；CI workflow 就绪 |
| 2026-09-09 01:02 | T101-T106（事件模型+引擎+日志） | 通过：9 个单测全绿（顺序性/连锁闭环/定时器/异常隔离/round-trip）。修复 1 个真实 bug：定时器注册无法唤醒 parked 引擎 |
| 2026-09-09 01:03 | T107-T110（网关+Binance 行情） | 通过：parser/backoff/timesync 单测全绿；交易接口 P3 占位 fail-fast |
| 2026-09-09 01:04 | T111（装配验收） | 通过：paper 模式实测连接 stream.binancefuture.com 并接收 BTCUSDT 实时 K 线事件 |
| 2026-09-09 01:04 | T112（断网 60s 恢复） | ⊘ 跳过：需用户网络环境执行，标记为用户验收项 |
| 2026-09-09 01:05 | 提交推送 | commit dc8a55c 已推送 origin/main，触发 GitHub Actions CI |
| 2026-09-09 01:40 | P2 细化 | 生成 T201-T222（22 条），并实测环境：fapi.binance.com/repo.maven.apache.org 不可达，testnet REST 可直连（含 2019 至今 BTCUSDT 1h）；已在 `~/.m2/settings.xml` 镜像 central → repo1.maven.org（仅本机，CI 不受影响） |
| 2026-09-09 01:43 | T201-T202（精度模型 + 组合领域模型） | 通过：alpha-common 18 个单测全绿（取整/对齐/minNotional 拒单、加减翻仓均价与实现盈亏、资金费多空方向、确定性 scale 一致） |
| 2026-09-09 01:52 | T203-T205（Strategy SPI + BarSeries + 指标库） | 通过：alpha-strategy 21 个单测全绿。指标值以独立 Python 参考实现（教科书公式：EMA 以 SMA 播种、RSI/ATR 用 Wilder 平滑）逐值钉死；BarSeries 覆盖重复/乱序丢弃与容量淘汰（边界 1）。修正 1 处测试自身错误（EMA 播种窗口取前 5 根而非后 5 根） |
| 2026-09-09 02:00 | T207-T208（MA 双均线 + RSI 反转策略） | 通过：新增 12 个单测全绿（alpha-strategy 累计 33）。信号只在状态跃迁的那根 K 线发出，超买/超卖持续多根不重复下单；RSI 平仓以「已请求平仓的持仓数量」去重，既避免逐根重复发信号，也保证迟到成交重新建立多头后仍会被平掉；Strategy SPI 补 `interval()`（单周期，多周期不在 v1 范围）。修正 1 处测试自身错误（每根 K 线新建策略实例导致指标永不预热） |
| 2026-09-09 02:06 | T206（StrategyEngine 分发器） | 通过：新增 8 个单测全绿（alpha-strategy 累计 41）。分发规则：只投 closed bar（未收盘永不进策略，FR-BT-02）、按 symbol+interval 双维度过滤、同 symbol/interval 的多策略共用一份 BarSeries 且每根只追加一次（重复/乱序直接丢弃且不回调策略，边界 1）、按配置顺序分发保证可复现、单策略抛异常被隔离不影响其他策略 |
| 2026-09-09 02:15 | T222（策略工厂注册 + app 装配） | 通过：新增 19 个单测（alpha-strategy 累计 60，全仓 84 全绿）。`StrategyFactory` 走 `META-INF/services` 发现，`StrategyRegistry` 按 yml 顺序构建；配置错误一律启动即失败（未知 type/重复 id/参数拼错/参数值非法/工厂忽略配置 id/两个工厂抢同一 type）。app 侧：删除 EchoStrategy 占位，新增 Portfolio 与 StrategyEngine bean，网关订阅改为「alpha.symbols ∪ 已启用策略声明的 symbol+interval」，避免新策略因无人订阅而静默空跑。实测：backtest profile 启动日志正确显示发现/启用/停用；paper profile 连上 stream.binancefuture.com 并订阅 BTCUSDT.PERP/1h |
| 2026-09-09 02:28 | T209（PositionSizer + 风控直通闸门） | 通过：新增 27 个单测（alpha-risk 26 + alpha-common ClientOrderIds 5，另 StrategyEngineTest 补 1 条标记价断言；全仓 126 全绿）。四处理解决策：①金额精度抽到 `common.model.Money`（MC(24,HALF_UP)/scale 8）唯一定义，Portfolio 与 sizer 共用，避免两处各自取整导致回测与实盘漂移（数量断言按 scale 精确比对而非 compareTo）；②P2 的 `targetExposure` 上限钉死 1.0，杠杆/账户级敞口是 P3 的 FR-RK-02/04，规则未就位前不允许 sizer 自行放大账户；③闸门只发 MARKET 且 `price=null`——回测撮合按次根开盘、实盘按盘口，此处带限价要么是未来函数要么是过期报价；④`StrategyEngine` 在分发前用收盘 `portfolio.mark()`，否则 paper/live 每个信号都会因无标记价被 `RK-07-no-price` 拦死（回测因撮合先跑不易暴露）。边界 4 落实：不足 minNotional / stepSize 取整为 0 一律拒单 + WARNING 告警，绝不发脏单；无交易规则视为 CRITICAL 硬停 |
| 2026-09-09 23:51 | T210-T212（历史数据层：契约 + CSV + JDBC + Binance 下载器） | 通过：新增 39 个单测（backtest 12、app 10、gateway 22 含 1 条按需 testnet IT；全仓 165 全绿）。契约决策：`KlineRepository.load` 必须返回**按 openTime 升序、每个 openTime 一根**，`save` 必须**幂等**（可重复覆盖同一区间），价格**原样存原样还**（绝不二次取整，NFR-04）——三条不变式让 CSV/SQLite/MySQL 对 feeder 与策略完全等价，T214 的缺口检测可以直接信任「一根一根」。`CsvKlineRepository` 走 `.tmp` + `ATOMIC_MOVE`（不支持则退化为普通 move），`changed==0` 时不落盘；损坏行报 `文件:行号`。`JdbcKlineRepository` 见取舍 5（建表自动执行，价格存文本，内存 diff 决定「新增/变更」计数）。`BinanceKlineDownloader`：1500 根分页、base url testnet/实盘可配（FR-GW-05）、429/418/5xx 退避重试并优先听 `Retry-After`、4xx 立即失败不重试；**绝不入库未收盘的那一根**（以 Clock 判定 `closeTime < now`），否则一个「其实从未存在的收盘价」会在之后每次回测里静默违反 FR-BT-02。实测 testnet 真实拉取 BTCUSDT.PERP 1h 近 100 天：`pages=2 fetched=2400 stored=2399 forming=1`，无缺口，全部满足 OHLC 与 `closeTime=openTime+1h-1` 不变式；公开端点无需密钥，FR-SEC-01 不受影响 |
