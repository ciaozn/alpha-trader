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
| T213 | `EventEngine.awaitQuiescence(eventId)`：回放方等本轮连锁闭环后再推进虚拟时钟 | BT-01、NFR-04 | 单测（等待返回时 cascade 已完成） | ☑ |
| T214 | `BacktestDataFeeder`：按时间序回放 + 推进 VirtualClock + 数据缺口检测记录 | BT-01、边界 3 | 顺序性 + 缺根记录单测 | ☑ |
| T215 | `SimulatedExecutor`：次根开盘价成交 + 滑点（固定 bp + 振幅比例）+ taker 手续费 + 资金费（8h 计提） | BT-02/03、边界 5 | 撮合规则单测 + **反未来函数断言**（信号根不成交、成交根不用未来价） | ☑ |
| T216 | `TradeTracker` + `PerformanceMetrics` + `PerformanceAnalyzer`：净值曲线、年化、夏普、最大回撤、胜率、盈亏比 | BT-04 | 手工构造净值/成交序列的公式单测 | ☑ |
| T217 | 报告输出：自包含 HTML（内联 SVG 净值曲线 + 逐笔明细 + 缺口）+ 控制台摘要 | BT-04 | 生成报告含全部指标，无外部 CDN 依赖 | ☑ |
| T218 | `BacktestRunner`：纯 Java 装配全链路（引擎/虚拟时钟/feeder/撮合/策略/风控直通/报告），不依赖 Spring | BT-06 | 固定数据集端到端跑通并出报告 | ☑ |
| T219 | alpha-app backtest 装配：`alpha.backtest.*` 配置（数据源 csv/db、策略启停与参数、初始资金、滑点费率）+ 跑完自动退出 | OP-01、ST-01 | `--spring.profiles.active=backtest` 出报告后进程正常退出 | ☑ |
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
> 6. **T219 的配置面按「是否回测专属」划分，而不是照抄任务文字**：任务写的是「`alpha.backtest.*` 配置（数据源、策略启停与参数、
>    初始资金、滑点费率）」，但**初始资金与策略启停没有搬进 `alpha.backtest`**，仍读共用的 `alpha.initial-cash` / `alpha.strategies`。
>    理由：FR-BT-06 的同构若只覆盖类而不覆盖配置，就能配出一个「回测 10 万、实盘 1 万」或「回测开三个策略、实盘开一个」的系统，
>    而回测结果照旧被当成实盘的证据。`alpha.backtest.*` 只放真正回测专属的东西（数据源、区间、序列、敞口、成本、精度规则、
>    报告目录、journal）；`exposure`/`cost`/`quiescenceTimeout` 保持可空，为空时直接把 `Policy.DEFAULT` /
>    `CostModel.DEFAULT` / `DEFAULT_QUIESCENCE_TIMEOUT` 交给 runner，让那三个数字始终只有一处定义。
> 7. **`alpha.backtest.data.source: db` 目前只接受 SQLite，且缺 `from`/`to` 是拒绝运行而不是猜默认值**：前者是取舍 5 的延续
>    （`mysql-connector-j` 尚未进依赖，给一个别的 URL 只会得到一条 `ClassNotFoundException` 而不是一句人话，所以在装配处就拒并点名属性）；
>    后者是因为区间猜宽了会被 feeder 如实报成数据缺口 —— 一次配置笔误会变成一份声称数据集有洞的报告，比直接失败更贵。

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
| 2026-09-10 00:11 | T213（`EventEngine.awaitQuiescence` 回放屏障） | 通过：新增 5 个单测（alpha-engine 7→12，全仓 170 全绿）。屏障语义：`completedThrough >= eventId`（事件 id 全局单调，故用一个 long 水位即可精确表达「该轮及其之前的轮都已闭环」，无需记录 id 集合）**且**引擎空闲（无轮在飞 + 外部队列空 + 无到期定时器）。定时器那一条是关键：资金费这类周期性结算靠定时器驱动，而定时器恰好在 feeder 拨钟时到期，屏障若提前返回就会让 feeder 带着未结算的资金费推进。`false`（超时/引擎已停）必须中止回放，绝不可忽略。**顺带修掉两个真实缺陷**：①`pollNext` 原先按「距下个定时器的毫秒数」park，而该期限取自 Clock——`VirtualClock` 被回放方一次性拨快后，循环会按*真实*时间 park 数小时，已到期的定时器迟迟不触发；改为 park 上限 20ms（有事件时 poll 立即返回，无额外开销）。②`emitDueTimers` 原先写在 try 内，被 `wakeLoop()` 的中断打断时会整轮跳过定时器评估；移出 catch 之外，任何一次唤醒都重新评估。**用变异测试验证断言不是空转**：逐条删掉 `!dispatching`、`millisUntilNextTimer() > 0` 后对应用例必须失败——第一版用例曾在此处假绿（cascade 微秒级排空，靠运气通过），改为在 cascade 中途卡住 handler 后才真正钉死；同时删掉因此冗余的 `cascade.isEmpty()`（`dispatching` 已覆盖整轮）与一条被更强用例包含的弱用例 |
| 2026-09-10 00:28 | T214（`BacktestDataFeeder` 回放 + 缺口检测） | 通过：新增 14 个单测（alpha-backtest 12→26，全仓 184 全绿）。回放语义：每根 K 线严格「拨钟 → publish → `awaitQuiescence`」，一根 == 一轮；不等闭环就会让下一根的事件与本根 signal→risk→order→fill 连锁交错，同一份数据不再可复现（NFR-04）。handler 收到的就是实盘 WS 会给的那个 `KlineEvent`（`closed=true`、businessTs=该根收盘时刻），下游无法分辨回测与实盘（FR-BT-01/06）。多序列**按业务时间归并**（稳定排序），同一 closeTime 并列时按**配置顺序**而非字母/哈希序，多标的交错与实盘一致。缺口三形态统一为一条扫描：起始晚于区间、中间空洞、**数据早于区间结束（尾部缺口）**——三者都计入报告（边界 3）；`Gap` 去掉 `actualOpenTime`（恒等于 `expected + missing*step`，冗余且令尾部缺口无法表达）。**网格校验锚定 `fromOpenTime` 而非只比相邻差值**：只比差值会放过「自身等距但整体错位」的序列，随后前导缺口被 floor 截断，少报缺根数——这条是写完初版后自查发现的真实计数缺陷。空区间**拒绝回放**而不是回放 0 根：0 根回测产出平直净值曲线，读起来像「策略没赚钱」而不是「没有数据」。仓库若返回非严格升序或重复根直接抛（契约已声明），否则重复根会被回放两次并被误报为缺口。**变异测试：8 个变异体杀掉 7 个**（尾部缺口、锚定网格、升序校验、缺口记录、空区间拒绝、引擎未启动拒绝、时间排序）；唯一存活的是「把拨钟挪到 publish 之后」——它确实引入竞态（handler 可能读到上一根的钟，令恰好在该收盘到期的资金费定时器被屏障漏判、错配到下一根），但两条语句相邻，错误顺序几乎每次都读对，**任何黑盒时序测试都抓不住**；因此不假装有测试钉住，改为在 `advanceAndPublish` 现场用 JMM happens-before（advanceTo → queue offer → loop poll synchronizes-with）把「为什么这个顺序不能反、反了会怎样」写死在代码里。另修掉测试自身两处缺陷：`feeder()` 覆盖 `engine` 字段导致先建引擎的非守护线程泄漏（用例全绿后仍可能挂死 surefire fork）；一条 `assertThat(seen).isEmpty()` 是空断言（该引擎根本没注册记录 handler，永不可能失败），换成 `handlerEntries == 1`，真正证明 feeder 停在第一根、没有把其余 4 根堆在打不开的轮后面 |
| 2026-09-10 00:48 | T215（`SimulatedExecutor` 模拟撮合 + 滑点/手续费/资金费） | 通过：新增 22 个单测（alpha-backtest 26→48，全仓 206 全绿）。撮合语义：订单在**下一根 closed bar 的 open** 成交，未收盘根永不成交（FR-BT-02）；成交根自己的 high/low/close 完全不进价格，用例专门喂了一根 open=210、high=999、low=0.5、close=500 的极端根来钉死这一点。**滑点取自「下单那根」的振幅，而不是「成交那根」**：成交根的 high/low 在它自己 open 那一刻是未来信息，用它算滑点是最容易写错、错了以后回测还会系统性偏乐观的一处未来函数；实现上把下单根的滑点算好存进 `Pending`，成交时直接用。偏置一律对交易者不利（买 CEILING、卖 FLOOR、手续费按滑后价计），回测宁可信差不可信好。资金费按 **UTC 对齐的 8h 边界**数据驱动计提，而非 `scheduleRepeating` 定时器：边界因此与「何时开跑」无关，且一段数据空洞会把交易所真的结算过的每个边界全部补上（用例：24h 空洞 → 3 次计提），同时第一根之前绝不追溯计提（否则从 epoch 起算会凭空吞掉权益）。边界 4 的成交侧：滑点后名义再查一次 minNotional，不够就拒，绝不发脏单。**谁把成交写进账本**这一处架构选择记在此：executor 自己写，且在 publish `FillEvent` **之前**——与实盘 OMS 同构（账本算术回测实盘同一套，FR-BT-06），顺带消掉「账本 handler 必须排在策略之前」的隐式注册顺序约束，并保证策略 `onFill` 看到的是这笔成交真实产生的持仓（变异体「先 publish 再 applyFill」被用例抓住）。成交时刻缺交易规则按 CRITICAL 硬停而非普通拒单：撮合下去等于凭空发明一个 tickSize。`pendingOrders()` 在收尾非空即进报告——最后一根的信号没等到成交，这是事实，不能静默丢弃。**变异测试：16 个变异体全部杀掉**，含两条未来函数（次根收盘成交、滑点取成交根）、两条偏置方向反转（取整/滑点偏向交易者）、手续费按原始 open 计、minNotional 复查移除、非 MARKET 接受、非正数量接受、缺规则忽略、资金费锚点移除与「隔一个边界计提一次」、标记价不更新、LIFO、跨标的成交。 |
| 2026-09-10 01:19 | T216（`EquityCurve`/`EquityRecorder` + `Trade`/`TradeTracker` + `PerformanceMetrics`/`PerformanceAnalyzer`） | 通过：新增 38 个单测（alpha-backtest 48→86，全仓 244 全绿）。**净值采样点**：给 feeder 加了一个 `RoundListener` 缝隙（旧三参构造委托给新四参，行为不变），回调严格放在 `awaitRound` **之后**——「本根 signal→risk→order→fill 连锁已闭环」这件事**无法用 handler 注册顺序表达**，靠「注册在最后」实现的采样器会在有人多注册一个 handler 的那天静默变错，而在这里它是构造上精确的、且零成本。变异体「把回调挪到 `awaitRound` 之前」被两条用例杀掉：卡死的轮不得被报为已闭环（否则采样器会把半轮记账当成那根的结果），以及 `kline→signal→round-closed` 的逐元素交错序。`EquityRecorder` 对**同一业务时刻的第二次采样是替换而非追加**：两个序列同刻收盘会产生两轮同一个时间戳，而「该时刻的权益」意思是「该时刻所有事情都处理完之后」；追加会往收益序列里插一个零长度周期，把所有波动率指标压小。**交易归集**：一次 flat→flat 的往返是一笔交易而不是一笔成交一次（否则每个剥头皮腿都被叫做「交易」，40% 胜率看起来像 100%）；`Portfolio` 每标的只有一个净头寸，故每标的至多一笔未平交易，归集无歧义；翻仓的那一笔成交拆成平仓腿+开仓腿，**手续费按数量比例分摊且开仓腿的手续费用减法导出**（不是再分摊一次），两半严格加起来等于实收。交易盈亏**由现金流算出**（开仓付出、平仓收到、未平部分按标记价），而不是去读账本的已实现数：往返归零时两者构造上相等，这样均价算术只留在 `Portfolio` 一处；`theTradesAccountForExactlyWhatTheBookShows` 用 7 笔成交（含加仓/部分平仓/翻仓、双标的）钉死三条恒等式——`Σgross == realized+unrealized`、`Σfees == feeTotal`、`Σnet == equity-startingEquity`。**未平交易照报但排除在胜率/盈亏比之外**：把未实现结果计入会让这些数字取决于数据停在哪里。**「未定义」与「零」严格分开**：金额类结果是 Money scale 的精确 BigDecimal（重跑逐位一致，CI 阈值可用 `isEqualByComparingTo`，SC-02）；统计类是 `OptionalDouble`/`Optional<BigDecimal>` 而**不是 NaN 也不是 0**——分母为空时那个值是未定义，报 0 会被读成「没有风险」「没有亏损」，且能静默通过阈值断言。**年化只是照报，绝不用作阈值**：短窗口年化会剧烈外推（6 小时 +4% 复合成 7.39e24），有用例专门钉住这个量级，类注释指明 CI 该断言 `totalReturn` 与 `maxDrawdown`；年化按日历时间（含空洞）而夏普按采样频率年化，两者刻意用不同的时间观念。**写测试时查出两处真实缺陷**：①`best`/`worst` 原先播种在 0，全亏损的回测会报 bestTrade=0.00，读起来像「有一笔保本」——改为播种于第一笔已平交易，仅在从未平仓时回落到 0；②`payoffRatio` 原先只在 `averageLoss.signum()==0` 时短路，于是会去除 `averageWin` 那个**约定俗成的 0**，报出 payoff=0.00（读作「赢利单尺寸为零」而不是「没有赢利单」）——改为按 `wins==0 || losses==0` 的**计数**守卫。**删掉两处不可达守卫**而不是留着当杀不死的变异体：`worstDrawdown` 里的 `peak.signum()<=0`（`EquityCurve` 已校验 startingEquity>0，peak 只会被更大的样本替换，故恒正；爆仓是 ≥100% 回撤而非除零）与 `annualizedReturn` 里的 `yearsElapsed<=0`（周期已校验为正、曲线时间严格升序，跨度恒正）。公式一律用**独立参考实现**取值，不回读自己的输出：`/tmp/metrics_decimal.py`（Python `decimal`，MathContext(24,HALF_UP) 后 quantize 到 1e-8）出精确金额，`/tmp/metrics_ref.py`（IEEE double）出统计量；`Math.pow` 与 Python `**` 的差用 1e-9 容差吸收，其余 1e-12。**变异测试：18 个变异体全部杀掉**（空曲线拒绝、非正周期拒绝、爆仓仍出夏普、全亏仍年化、平直曲线 0/0、单点夏普、payoff 除以回落零、profitFactor 除以零 grossLoss、best 播种零、曲线接受非升序/零基线、同刻采样追加而非替换、翻仓判定反转、整笔手续费记在平仓腿、非正数量接受、开平腿只看 side 不看方向、平仓后仍留在 open 表、轮未闭环就报已闭环）。**另记一处 Maven 陷阱**：变异脚本用 `shutil.copy2` 还原源文件会把**原 mtime 一起还原**，比 `target/classes` 旧，Maven 的新旧判定因此跳过重编译，下一次构建跑的还是变异体的 class——还原必须用 `write_text`（盖新 mtime）；这个坑先让「还原后基线」假红了一次。 |
| 2026-09-10 01:41 | T217（`BacktestReport` + `ReportFormat` + `EquityCurveSvg` + `HtmlReportRenderer` + `ConsoleSummary`） | 通过：新增 28 个单测（alpha-backtest 86→114，全仓 244→272 全绿）。口径与 spec 澄清一致（spec.md:268「HTML 静态报告 + 控制台摘要」），FR-BT-04 七项指标 + 逐笔成交明细齐备，边界 3 的缺口取自 `ReplaySummary.gaps()`。**自包含**：用例禁掉 11 种引用形式（`<script`/`<link`/`@import`/`src=`/`href=`/`url(`/`http:`/`https:`/`<iframe`/`<img`/`<object`/`<embed`）——CDN 依赖今天照样渲染得好好的，等网络或那个库没了才炸，是典型的静默失败；内联 SVG **刻意不写 `xmlns`**（HTML5 内合法，且这样整份文档里连一个 `http:` 字符串都不存在），变异体 H2 把 xmlns 加回去立刻被这条抓住。**确定性（SC-02）**：`BacktestReport` 里唯一带墙上时钟的值是 `ReplaySummary.elapsed()`，**刻意不进 HTML**——三次逐位比对里它每次都不同，而它看起来完全正常，正是最难发现的那类破绽；它只进控制台摘要，因为 SC-01 是计时验收、那个数字必须能从这次运行本身读出来，而控制台文本没有任何东西逐位比对，所以安全（两个类的注释各写了一遍理由）。`Locale.ROOT` + `ZoneOffset.UTC` 全覆盖：用例把 JVM 默认 locale/时区翻成 de-DE、ar-SA、fr-FR 与 Pacific/Kiritimati vs Pacific/Pago_Pago，再与**原始默认下捕获的基线**比对，HTML 与控制台各比一次（H8/H9 证明会咬人）。**转义是「单点发射」规则**：只有 `fact`/`metricRow`/`table` 三处让文本进 markup、各转义恰好一次，所以所有调用方一律传原始文本。初版的行 lambda 里也调了 escape，于是 `&` 渲染成 `&amp;amp;`；清洗到恰好 10 个发射点，并用 `doesNotContain("&amp;lt;")` 钉住「不多转一次」。`metricRow` 的转义**当前没有任何测试能区分**（走它的全是格式化数字或字面标签），仍然保留：删掉它会把一条统一规则变成一个例外，而第一个把 symbol 名字塞进指标行的调用方会静默继承这个洞——它就是**唯一存活的变异体 H10**，作为「构造上等价」写进类注释，不假装有测试钉住。**降采样**：三年小时线 ~26k 点，`MAX_POINTS=2000` 按 stride 抽，但**最后一根永不跳过**（整条曲线就是拿它来读的）。证明方式刻意不复制几何：构造一条 5000 点曲线与一条 2 点曲线，两者 low/high/起始权益/首末时间戳全同，于是共用一个 scale 与一条 x 轴，断言**末顶点逐位相同**（H4「末根被 stride 跳过」与 H12「从不降采样」被同一条用例的不同断言分别杀掉）。单点曲线画 `<circle class="dot">` 而不是 polyline——一个顶点的 polyline 什么都不渲染，读起来像「没有数据」。**y 轴刻度自适应**（写完初版自查发现的真实弱点）：原先固定 0 位小数，小资金或短而安静的区间会让五个刻度全部塌到同一个整数，坐标轴等于什么都没说；`labelScale(span)` 按量级给 6/4/2/0 位，并**在方法注释里写明这是全项目唯一允许取整的地方**——它们是视觉辅助线而不是报告值，故与 `ReportFormat` 的「绝不为显示取整」不矛盾，这句话是防止后来者把它当成可推广的例外。**金额显示绝不取整**：`stripTrailingZeros().toPlainString()`，scale 8 的零打成 `0` 而不是 `0E-8`。写的时候先加了个 `signum()==0` 特例，随后**用一次性 Java 程序实测**确认 JDK 21 上 `new BigDecimal("0.00000000").stripTrailingZeros().toPlainString()` 本来就是 `"0"`（JDK-6480539，Java 8 已修），于是**把这段死代码删掉**而不是留着当杀不死的变异体，并把用例注释改成指向那个 JDK 号。**未决事项一律浮到表面**：`hasUnresolved()` = 未成交挂单 / 拒单 / 未平仓 / 未平交易 / 数据缺口，任一非空就在报告顶部出横幅，控制台摘要列**同一份** `warnings(report)`（一处定义，两边不可能漂移）；空段落显式写「无」而不是只留一个光秃秃的标题——T215 记下的「最后一根的信号没等到成交」这条事实，就是靠这里才不会被埋掉。**表格列数装了绊线**：`headers.size() != values.size()` 直接抛 `IllegalStateException`，因为列错位会安静地把「手续费」印在「数量」底下，而 HTML 照样打得开、照样好看。**变异测试：21 个变异体杀掉 20 个**（表格不转义、xmlns 外链、耗时进文件、末根被跳过、未定义渲染成零、空段落无声、单点画隐形 polyline、格式化跟随机器 locale、时间戳跟随机器时区、回撤不标在曲线上、从不降采样、charset 不声明、控制台藏起未决项、金额按两位取整、双引号不转义、百分比走二进制浮点、刻度恒为整数、刻度恒为 6 位、亚单位区间不多给小数、整数刻度阈值放宽）。 |
| 2026-09-10 01:57 | T218（`BacktestRunner`：纯 Java 装配全链路，占位类整体替换） | 通过：新增 10 个单测（alpha-backtest 114→124，全仓 272→282 全绿），零 Spring。**装配的三条不可见规则写进类注释**（代码本身看不出来）：①`SimulatedExecutor` 注册在 `StrategyEngine` 之前——两者都吃 `KlineEvent`，注册序即分发序；反了**不是**未来函数泄漏，而是策略会在交易所已经改过的持仓上做决策（同样错，只是错法不同）。②`EquityRecorder` 走 feeder 的 `RoundListener` 而**不是** handler，且它根本不实现 `EventHandler`，所以「误注册成 handler」这个错**类型系统直接不允许**——比靠注释守更硬。③除 executor 与 `StrategyEngine` 的 mark 之外**没有任何组件写 Portfolio**，再加一个记账 handler 就会把每笔成交记两次。**风控拦截刻意不进报告**：`RiskGate` 已按 WARN 逐条打了 ruleId，runner 只把 `ordersPassed/signalsBlocked` 汇总进运行日志；塞进 `BacktestReport` 等于给读者同一份信息的第二个、过滤口径还不一样的视图。**夹具是照着「一次装配错误必须被发现」设计的**：bar0 open=100 / close=101、bar1 open=102——**开盘价刻意不等于上一根收盘价**，否则「次根开盘成交」与「信号根收盘成交」在数字上无法区分。于是把注册顺序反过来会**同时**改变三个可观测量：成交根变成 bar0、滑点从 20bp 掉回固定的 5bp（订单在撮合器还没见到 bar0 时就挂了，`slipOfLastBar` 里查不到它，落回 `getOrDefault`）、成交价从 102.21 变成 100.05；变异体 R1 被这三条一起杀掉。**手算黄金值直接钉住整条链**：qty `29.702`（0.30 × 10000 权益 / 101 标记价，按 stepSize 0.001 向下取整）、滑点 `20bp`（5 固定 + bar0 的 300bp 振幅 × 0.05）、成交价 `102.21`（102 × 1.0020 按 tickSize 0.01 对交易者不利地 CEILING）——这三个数一次跑通即证明 sizer 拿到了正确的权益/强度/标记价、撮合器拿到了正确的下单根与成交根。**`samplingPeriod` 取「最细周期」并把代价写明**：一轮一根、一根一采样，故最细周期是「近似恒定间距」里最诚实的那个，单序列（P2 出货的每一种）就是它的 bar 周期；混合周期确实不等距、夏普年化处理因此是近似——**明说而不是藏起来**，因为另一种做法（让配置里的周期可以与真实回放的数据不一致）更糟：它会让数字看起来精确其实是错的。测试把 ETH H4 放在序列列表**第一位**，使「第一个序列的周期」与「最细周期」不同，R8/R9 两个变异体都被杀；这条测试顺带跑到了 T216 的「同刻采样替换」路径（13 轮 → 11 个点）。**两处「拒绝运行」而不是「跑出个没意义的结果」**：零策略（会产出平直净值曲线与「0 笔交易」的报告，和「策略刚好保本」无法区分，通常成因是把策略全关掉的配置笔误）与非正初始资金。后者特意**在 `Config` 里就拦**：0 权益会让 sizer 一单不发、白跑 10 根、最后在 `EquityCurve` 那里报一条**关于曲线**的错；用例连消息文本一起钉住（`startingEquity must be positive`），否则 `EquityCurve` 那条同样含 "startingEquity" 的错会让变异体 R13 蒙混过关。**`engine.stop()` 放 finally**：引擎循环线程不是守护线程，一次抛异常却没停引擎的运行会把 JVM（或 surefire fork）挂在失败**之后**；用例数 `event-engine` 线程数前后一致，变异体 R3（去掉 finally）被它杀掉，且并没有把 surefire 挂死（3.x 的 `forkedProcessExitTimeoutInSeconds` 会强杀 fork 并报错）。**一个 `Config` 只能跑一次**（写在 `run()` 注释里）：`Strategy` 跨 bar 带状态（指标窗口、金叉是否已发过），复用同一份 Config 跑第二次等于往已预热的策略里再灌一遍数据；SC-02 的三次复现必须**重建策略**而不是重跑同一个 runner——**T220 照此写**。**未决事项贯通**：最后一根的信号永远等不到成交，`pendingOrders()` 必须进报告（R4 被杀），funding（R5）、openPositions（R6）、series（R7）同理。**数据源可替换是实测的而不是声称的**：同一份 10 根 bar 分别经内存仓储与 `CsvKlineRepository`（`@TempDir`）跑，两份 HTML **逐字节相同**——FR-BT-06 的「换存储不得触及 feeder/策略/撮合」由此有了正面证据。**变异测试：13 个变异体全部杀掉**（注册顺序反转、净值采样器不接、失败时不停引擎、挂单/资金费/持仓/序列各自从报告里丢掉、采样周期取第一个与取最粗、TradeTracker 未注册、RiskGate 未注册、两处守卫删除）。 |
| 2026-09-10 02:24 | T219（alpha-app backtest 装配：`AlphaProperties.Backtest` + 新增 `BacktestWiring` + 三处 profile 收窄 + `application-backtest.yml`） | 通过：新增 22 个单测（app 10→31、backtest 124→125，全仓 282→304 全绿），并**手工跑通打包产物**：`java -jar target/alpha-app-0.1.0-SNAPSHOT.jar --spring.profiles.active=backtest …` → **EXIT CODE 0**、墙上时间 ~1.0s、`backtest-report.html`（7839 字节）落盘、控制台摘要 60 根 / 1 笔成交 qty=36.14450 / 权益 10000→10605.0517011 / 最大回撤 0% / 夏普 58.236 / **年化 530622.3150%** / 手续费 1.5036112 / 资金费 0.6722877 / elapsed 12ms / 1 项未决。qty 的 5 位小数顺带证明 yml 里嵌套的 `rules` 列表真的绑定上了（stepSize 0.0001）；那个荒谬的年化数字则是 T216「年化只照报、绝不当阈值」的实物证据 —— **T220 的阈值断言只用 `totalReturn` 与 `maxDrawdown`**。**「跑完自动退出」不是配置出来的，是把在线装配从 backtest profile 里拿掉之后才成立的**：`StartupWiring` 原先是无条件 `@Component @Order(1) ApplicationRunner`、`EngineConfig` 的三个 bean 也无条件，于是 backtest 下照样 new 出 `EventEngine` 并 start，而**它的循环线程刻意不是守护线程**；Spring Boot 非 web 应用在 `main` 返回后只在「只剩守护线程」时退出，所以进程永远不终止（这也是验收时最先撞上的现象：报告已经写完、进程还挂着）。修法是把 `EngineConfig`/`StrategyConfig.strategyEngine`/`StartupWiring` 收窄到 `@Profile({"paper","live"})`、`BacktestWiring` 反向收窄到 `@Profile("backtest")`；顺手**删掉**两处因此变成死代码的东西 —— `EngineConfig` 里没人用的 `virtualClock` bean，以及 `StartupWiring` 里恒不成立的「本 profile 没有 gateway」分支连同它那条过期的「T214 会有 feeder」日志。**`StartupWiring` 同时改为直接注入 `ExchangeGateway`**：`GatewayWiringConfig` 本来就是 `@Profile({"paper","live"})`，在这两个 profile 里那个 bean 必然存在，`ObjectProvider` 的兜底分支不可达 —— 按纪律删掉而不是留着当杀不死的变异体。**变异体 E1/S1/U1（拆掉三处 profile 限制）只有能启动容器的测试看得见**，这正是 `BacktestProfileApplicationTest` 存在的理由：`@SpringBootTest` + `@ActiveProfiles("backtest")` 断言容器里**没有** `EventEngine`/`StrategyEngine`/`ExchangeGateway` 三个 bean、JVM 里**没有**名为 `event-engine` 的存活非守护线程、报告存在且含标的与初始资金、yml 那一块（含只有 yml 里才有的 `rules[0]`）真的绑定成功。**数据集在 `static {}` 里、容器加载之前写好** —— runner 是在启动过程中执行的。**配置面按原则划而不是按任务文字划**（取舍 6）：`alpha.backtest.*` 只放真正回测专属的旋钮，**初始资金与策略启停留在共用的 `alpha.initial-cash` / `alpha.strategies`**，于是「回测配的账本或策略集与实盘不同」在配置层面根本表达不出来 —— FR-BT-06 的同构由「同一批类」延伸到「同一份配置」。`exposure`/`cost`/`quiescenceTimeout` 三个旋钮**刻意保持可空**，为空时把领域里的那三个常量原样交给 runner，让数字只有一处定义。**缺区间是拒绝运行而不是猜默认值**（取舍 7），且**全部校验发生在碰文件系统之前**：一次因缺区间被拒的运行不该在退出路上留下数据库文件或 journal。**`Config` 新增第三条拒绝**（排在非正权益与零策略之后）：**被交易的标的必须有精度规则**。没有 tickSize/stepSize 时 `RiskGate` 会把每个信号都拦成 RK-07，于是这次运行回放完所有 bar、一单不发、报出一条平直曲线，读起来正是「这个策略保本」。**按策略交易的标的查而不是按回放的序列查** —— 多回放一条没人下单的上下文序列是合法的（T218 的采样周期用例就回放了 ETH 却从不交易它，所以那条用例必须继续通过）。守卫放在 `Config` 而不是 app 侧，于是每个调用方（包括 T220 的 CI 冒烟）都被保护到。**yml 里的精度规则是实测来的不是编的**：`tick-size 0.10 / step-size 0.0001 / min-notional 50` 取自 2026-09-09 对 `testnet.binancefuture.com/fapi/v1/exchangeInfo` 的真实抓取，注释里写了快照日期与「testnet 过滤器可能与生产不同，信 minNotional 行为前先复核」。**`source: db` 只接受 SQLite**，非 `jdbc:sqlite:` 的 URL 被拒并说明「本模块不带别的 JDBC 驱动」。**journal 打开时先 `deleteIfExists` 再建**：`JsonlEventJournal` 是**追加**写的，不删就把上一次的事件接在后面，而这份文件是拿来逐行比对的。**刻意不断言的东西写在测试注释里而不是悄悄跳过**：`quiescence-timeout` 没有用例 —— 它只在「某一轮没能闭环」时才起作用，1ns 超时是否咬人取决于哪个线程先赢，在这里测它就是一条假装成覆盖率的抖动测试，交给实现它的那个组件去测。**变异测试：26 个变异体杀掉 25 个**（缺区间被放行、未知 source 回落 csv、两处 URL 校验失效、db 静默走 csv、序列派生方向两向反转、敞口/成本/`orDefault` 三个旋钮被忽略、规则列表被清空、成本模型换成默认、from/to 互换、权益写死 10000、报告不写与写错文件名、journal 恒为 noop 与不删旧文件、三处配置默认值改动、三处 profile 限制拆除、`Config` 新守卫删除）。**唯一存活的 W19（`quiescence-timeout` 恒取默认值）与上面那条「刻意不断言」是同一个事实的两面**：不是漏测出来的侥幸，而是这个旋钮在单元层面不可观测，已在 `BacktestWiringTest` 的类注释里写明理由，不假装有测试钉住它。 |
