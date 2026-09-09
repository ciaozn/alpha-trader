# Tasks 001: Alpha Trader 细粒度任务清单（P0 + P1 + P2 + P3）

| 字段 | 值 |
|---|---|
| 状态 | P0+P1+P2 已完成（T112 留用户验收）；P3 已细化并开工中 |
| 创建日期 | 2026-09-09 |
| 上游文档 | [plan.md](./plan.md)（Approved）· [spec.md](./spec.md)（Approved） |
| 生成策略 | 滚动式：本文件含 P0+P1+P2+P3 细任务；P4 及以后临近开工再细化 |

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
| T220 | CI 回测冒烟：固定数据集 + 绩效指标阈值断言 + 同输入 3 次运行逐位一致 | SC-02、plan §5 | `mvn clean verify` 全绿（含冒烟测试） | ☑ |
| T221 | SC-01 性能验收：BTCUSDT 1h 近三年（≈26k 根）完整回测 + 报告 < 5 分钟 | SC-01 | 实测计时记录在执行日志 | ☑ |
| T222 | 策略工厂注册机制：`StrategyFactory` SPI + yml 按 id 启停传参，新增策略零改引擎 | ST-01、场景 6 | 新增一个 dummy 策略仅靠配置生效的单测 | ☑ |
| T223 | **（补）** 历史数据下载入口：`download` profile + `AlphaProperties.Download` + 抽出 `DataPlan`（「数据落在哪」这条规则由下载与回测共用） | BT-05、OP-01、SC-01 前置 | `--spring.profiles.active=download` 把区间内 K 线写进 `alpha.backtest.data`、打印 fetched/stored 后进程正常退出 | ☑ |

**P2 出口**：T201-T223 全部通过；`mvn clean verify` 全绿；backtest profile 端到端出 HTML 报告；SC-01/SC-02 达标。
**✅ 已达成（2026-09-10）**：T201-T223 全部 ☑；`mvn -B -o clean verify` **331 个测试全绿**（2 个按需联网的默认 skip）；
SC-02 见 T220/T221（同输入三次逐位一致，且 26k 根上三个独立 JVM 进程的 HTML sha256 相同）；SC-01 见 T221（**26281 根 / 回放 425ms / 整个 JVM 1.379s**，预算 5 分钟）。

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
> 8. **T220 的冒烟数据集提交进仓库、窗口写成绝对时间戳，且年化收益永不设阈值**。任务只说「固定数据集 + 阈值断言」，
>    但「固定」有两种做法：滚动窗口（最近 N 天）会让夹具**自己重新测量自己**，阈值随行情漂移；绝对窗口
>    （2024-01-01T00:00:00Z 至 2024-04-09T23:00:00Z，2400 根）则让每个被钉住的数字都是可复核的历史事实，代价是 171KB 进仓库。
>    年化只报不断（取舍 7 的同一逻辑再上一层）：100 天的结果几何外推到一年，T219 那条 60 根的回放算出过 **530622%**，
>    对它设阈值要么宽到什么都没断言，要么每次合法改窗口都失败。**冒烟测试走 `BacktestWiring` 而不是 `BacktestRunner`** ——
>    打包产物跑的正是这条 yml→wiring→runner 的路，直接调 runner 的冒烟会在装配坏掉时继续绿。
>    重新生成夹具的那个测试**需要网络，因此默认跳过**（`-Dalpha.it.testnet=true` 才跑），`mvn verify` 保持离线可跑；
>    它同时也是「夹具可复现」的正面证据（二次下载 stored=0）。
> 9. **T223 是补的，不在原 P2 清单里；下载没有自己的「目的地」旋钮，默认序列也不从策略派生。**
>    补它的原因是产品缺口而不是任务缺口：FR-BT-05 要的是一个*工具*，而 T212 的 `BinanceKlineDownloader` 只有 JUnit 能调到 ——
>    `application-backtest.yml` 把 `csv-dir` 写成「下载器写进来的目录」，却没有任何东西往里写，SC-01 的三年验收也只有写过一次性
>    harness 的人能复现。做法沿用 T219 的退出机制（`ApplicationRunner` + 该 profile 下没有 `EventEngine` bean）。
>    **目的地刻意不设第二个旋钮**：下载写入 `alpha.backtest.data`，与回测走同一个 `DataPlan.store` ——
>    一个目录两处定义正是「下载成功了但回测没有数据」的成因，而它的现象是一份没有交易的报告，读起来像策略问题。
>    这是把取舍 6 的同构原则从「权益与策略」再推进一层到「K 线存储」。
>    **默认序列取全局 `alpha.symbols` × `alpha.interval`，而不像回测那样从启用的策略派生**：拉历史是数据操作，
>    不该要求存在一份合法的策略配置；两条规则允许不同，因为缺数据的序列是响亮的（feeder 报成覆盖整段区间的缺口，边界 3），
>    而多下载一条没人交易的序列只花网络与磁盘。显式 `series` 一旦给出就**替换**而非追加全局那一份。
>    **区间不回落到 `alpha.backtest.from/to`**：下载是累积且幂等的（同区间重跑 stored=0），回测是一次声明的实验，
>    共用区间会让「再下载一次」悄悄改变数据集边界。缺 `from`/`to` 一律拒绝运行（取舍 7 的同一逻辑）。
>    **`alpha.backtest.data` 因此声明在共享的 `application.yml` 而不是 `application-backtest.yml`**：Spring 只为激活的 profile
>    加载 `application-<profile>.yml`，download profile 从不读回测那个文件；把它写在 profile 专属文件里，两个模式一致就只靠
>    record 默认值恰好等于出厂值。`ShippedConfigurationTest` 钉的是「哪个文件声明了它」而不是绑定后的值 —— 后者在
>    「文件里写了」与「回落默认」两种情况下不可区分。
> 10. **SC-01 的三年数据集不提交，CI 里也没有计时断言。** 与取舍 8 里那份 171KB 冒烟夹具不同：冒烟夹具的价值在于
>    每次 `mvn verify` 都跑同一批 bar 并逐位比对结果，必须随代码一起进仓库；SC-01 的价值在于**一个数字**（跑完要多久），
>    而 1.9MB 换不来任何断言 —— 复现它需要的是 T223 的两条命令加数据集 sha256（`b4484e26…`），不是仓库里的副本。
>    不加「< 5 分钟」的 CI 断言同理：实测 **1.379s**，余量两个数量级，这样一条断言在慢 CI 上只会变成抖动源，
>    而它的验收标准本来就写着「实测计时记录在执行日志」。SC-02 反而在 26k 根上顺手加强了一次：三个**独立 JVM 进程**
>    （默认 / 换 report-dir / `journal=true`）产出的 HTML sha256 相同 —— 比 T220 的同进程三次比对更硬。

---

## P3 风控 + OMS 全链路

> 本阶段是系统第一次"碰"交易所的写接口（plan §6）：信号 → 风控闸门 → 下单 → 成交 → 持仓更新。
> **环境事实（沿用 P2，2026-09-10 复核）**：本机可直连 testnet REST/WS，但**没有任何 API Key**
> （用户未提供；FR-SEC-01 要求密钥只走环境变量，仓库零密钥）。因此凡验收需要「真实下单 / 真实成交回报 /
> 真实对账」的部分（T321 全部，T316-T318 的 testnet 实跑部分）**阻塞**：实现照做，验收改为**离线桩**
> （JDK `com.sun.net.httpserver.HttpServer`，因为 `mockwebserver` 不在本地 m2）+ 真实报文样本单测，
> 实跑标记为用户验收项（同 T112 的处理）。**这不影响 SC-04 与场景 3**：风控拦截是纯本地行为。

| # | 任务 | 对应 spec | 验收 | 状态 |
|---|---|---|---|---|
| T301 | `RiskRule` SPI + `SignalFacts`/`OrderFacts` + `RiskPipeline`（有序、任一拒绝即短路、两阶段，见取舍 11）；`RiskGate` 改为管道宿主，回测行为不变。原计划的单一 `RiskContext` 拆成两个事实记录：一个上下文类型会让「数量尚不存在」这个状态可表达 | RK-01 | 管道顺序/短路/两阶段单测；`mvn -B -o verify` 全绿且 `BacktestSmokeTest` 钉住的数字不变 | ☑ |
| T302 | `AlphaProperties.Risk`：五层参数 + 每层 `enabled` 开关，声明在**共享** `application.yml`（取舍 13） | RK-09 前置、OP-01 | 绑定单测（缺省值逐条等于 DESIGN §8 表）+ `ShippedConfigurationTest` 扩展「哪个文件声明了它」 | ☐ |
| T303 | 账户级规则：总杠杆上限（默认 3x）、保证金率下限（默认 150%，定义见取舍 12） | RK-02 | 阈值两侧（恰好等于 / 超出一分）单测 + 变异测试 | ☐ |
| T304 | 订单级规则：单笔名义价值上限（默认 ≤20% 权益）、价格偏离最新价上限（默认 ≤2%，防乌龙指） | RK-03 | 限价单偏离拦截 + MARKET 单无价格时**跳过偏离检查**的语义单测 | ☐ |
| T305 | 组合级规则：总持仓名义上限（默认 ≤60% 权益）、单 symbol 上限（默认 ≤30%） | RK-04 | 「本单成交之后」口径的拦截单测（含减仓单永不被这两条拦） | ☐ |
| T306 | 熔断级：单日亏损达阈值（默认 -5%）当日禁止开仓 + 连亏 N 笔（默认 3）暂停 M 小时（默认 2h）；`CircuitBreaker` 观察 `FillEvent` 与 UTC 日界 | RK-05 | 日界翻转 / 连亏计数 / 暂停到期单测（VirtualClock 定点推进） | ☐ |
| T307 | 频率级：单位时间最大订单数（默认 ≤10 单/分钟，滑动窗口） | RK-06、SC-04（取舍 14） | 窗口滑动 + 突发拦截 + 窗口过期后恢复单测 | ☐ |
| T308 | **SC-04 拦截矩阵**：五级规则各 ≥1 条「必被拦截」用例，拦截率 100%，并断言**短路点**（命中的是哪一条 ruleId） | SC-04 | 矩阵测试逐条列 ruleId；逐条删掉每个 guard 必须有用例失败（变异测试） | ☐ |
| T309 | 持久化契约：`OrderStore` / `RecordStore` 接口（execution / risk 各自定义，零 Spring 零驱动）+ 回测用内存实现 | OP-04、RK-08、EX-01 | 接口编译 + 内存实现 round-trip 单测；红线 grep：alpha-risk/execution 无 jdbc、无 spring | ☐ |
| T310 | 业务库 DDL（`orders`/`fills`/`signals`/`equity_snapshot`/`positions`/`risk_interceptions`，方言中立、价格与数量存文本，见取舍 15）+ `JdbcOrderStore`（alpha-app） | OP-04、EX-01 | 临时 SQLite 文件 round-trip 单测；建表幂等（重复启动不报错） | ☐ |
| T311 | `JdbcRecordStore`：signals / risk_interceptions（含命中规则 + 当时账户快照）/ equity_snapshot / positions | RK-08、OP-04 | round-trip 单测 + 「按规则/时间查询拦截记录」单测 | ☐ |
| T312 | OMS 订单状态机：NEW→SUBMITTED→PARTIALLY_FILLED→FILLED/CANCELED/REJECTED，每次迁移写库 + 发 `OrderUpdateEvent` | EX-01 | 全路径单测（含**非法迁移一律拒绝**：终态不可再迁移） | ☐ |
| T313 | clientOrderId 幂等 + **边界 6**：区分「交易所拒单」与「交易所不可用」——后者退避重试或留给对账，**绝不判失败** | EX-02、边界 6 | 重复提交不产生重复订单；传输异常不推进状态机的单测 | ☐ |
| T314 | 成交回报处理：OMS **先写 `Portfolio` 再 publish `FillEvent`**（取舍 16）+ 落库 | EX-06、场景 2 | 部分成交 / 多次成交 / 翻仓的账本与事件序单测 | ☐ |
| T315 | 精度对齐：启动拉 `exchangeInfo` → 缓存 tickSize/stepSize/minNotional 的 `TradingRulesProvider` 实现 | GW-03 | 脏单被拒测试；T209 的「规则缺失 = CRITICAL 硬停」不被新实现绕过 | ☐ |
| T316 | Binance 签名 REST：HmacSHA256 + `TimeSync` 偏移 + 下单/撤单/账户/持仓/挂单；**边界 8** 时钟漂移超阈值 → 告警并暂停下单 | EX-03 前置、GW-05、边界 8 | 离线桩：签名与测试内**独立算出**的 HMAC 逐字节相同；漂移守卫单测；密钥只从环境读 | ☐ |
| T317 | user data stream：`listenKey` 创建 + **30min keepalive** + `ORDER_TRADE_UPDATE` 解析 → `FillEvent`/`OrderUpdateEvent`；REST 查询兜底 | EX-03 | 报文解析单测（真实 testnet 报文样本）+ keepalive 定时单测（VirtualClock）；实跑 ⊘ 需 Key | ☐ |
| T318 | 定时对账（默认 60s）：挂单 + 持仓 + 账户，以交易所为准修正本地，差异发 `RiskAlertEvent`；启动先全量同步；幽灵仓位**仅告警**（自动平仓属 P4，取舍 18） | EX-04/05、边界 2/7 | 人为制造三类不一致（挂单丢失 / 持仓不符 / 幽灵仓位）均被修正并告警的单测（假网关） | ☐ |
| T319 | paper/live 装配收口：OMS / 对账定时器 / 持久化 / 网关凭据按 profile 装配，**每个在线 bean 都带 `@Profile({"paper","live"})`**；backtest 与 download 的自动退出不得被破坏 | OP-01、SEC-01 | `PaperProfileApplicationTest`（假网关，容器可启动可关闭）+ 既有两个 profile 容器测试仍绿 | ☐ |
| T320 | **场景 3 风控硬闸门**端到端（离线）：超限信号被拦、不发往交易所、产生告警事件、拦截记录可查（含命中规则与当时账户状态） | 场景 3、RK-01/08 | 端到端测试用假网关断言 `placeOrder` **从未被调用** + 从库里查回拦截记录 | ☐ |
| T321 | **场景 2 模拟盘全链路**：testnet 实时行情 → 信号 → 风控 → 下单 → 成交回报 → 持仓/净值更新，且每笔信号/订单/成交可在库中查询 | 场景 2、EX-03/06 | ⚠️ 需 testnet API Key，标记为用户验收项 | ⊘ 阻塞 |

**P3 出口**：T301-T320 全部通过且 `mvn -B -o clean verify` 全绿；spec 场景 3 逐条验收通过；SC-04 拦截率 100%；
场景 2（T321）由用户在有 testnet Key 的环境执行。

> 设计取舍记录（续 P2 的 1-10）：
> 11. **风控管道分 PRE_SIZE / POST_SIZE 两阶段，不是一个平面列表。** DESIGN §8 只说「管道串联、任一拒绝即拦截」，
>    但订单级（单笔名义上限）与组合级（总仓 / 单 symbol 上限）**必须先有数量才能评估**，而数量正是 FR-RK-07 换算出来的。
>    于是：账户级 / 熔断级 / 频率级在换算**之前**跑（它们只看信号与账户状态，提前短路还省下换算），
>    订单级 / 组合级在换算**之后**跑，且按「这一单成交之后」的口径评估。把五条规则塞进一个平面管道，
>    得到的订单级检查会因为 `qty == null` 而恒真（或恒假），而且**没有任何测试能发现**——它会安静地放过每一单。
> 12. **`marginRatio` 用本系统自己的定义，绝不用交易所那个同名字段。** DESIGN §8 的「保证金率 ≥150%，低于阈值禁止开新仓」
>    是证券语义：权益 / 占用保证金，**越大越安全**；而 Binance 的 `AccountSnapshot.marginRatio` 是维持保证金 / 保证金余额，
>    **方向相反**（越大越接近强平）。把网关那个字段直接喂进这条规则，会让「快要爆仓」通过检查、「非常安全」被拦死，
>    而且两种情况都看起来像正常拒单。故规则自己算：`usedMargin = totalNotional / maxLeverage`，`marginRatio = equity / usedMargin`。
>    在 `maxLeverage=3x` 时它等价于 `totalNotional ≤ 2×equity`，**比杠杆上限（≤3×equity）更紧** —— 这正是它存在的意义：
>    把 maxLeverage 调大不会顺手把全部缓冲删掉。交易所的 marginRatio 只进对账告警（T318），永不进这条规则。
> 13. **风控参数进共享 `application.yml` 的 `alpha.risk.*`，不新建 DESIGN §8 说的 `risk-rules.yml`。**
>    这是取舍 9 那条理由的第三次应用：回测与实盘必须读到同一套规则（FR-BT-06），而 Spring 只为激活的 profile 加载
>    `application-<profile>.yml`；再多一个独立文件就多出「哪个文件赢了」的问题，而它的失败现象是
>    「回测里被拦住的信号，实盘照发」—— 回测因此不再是实盘的证据。热更新（RK-09，P4）不需要独立文件：
>    改的是内存里的规则集，REST 端点照样能做，且那样才谈得上「不重启」。**与 DESIGN 的文件名不一致，记在此处。**
> 14. **频率级（RK-06）在 P3 做，尽管 plan §2 把它划给 P4。** SC-04 写的是「覆盖**全部五级规则**的拦截测试用例，
>    拦截率 100%」，而 P3-2 的验收正是 SC-04；plan §6 的任务文字自己也写着「五级规则实现」，只是 spec 映射列写了 RK-02~05。
>    少一级就拿不到 SC-04，而这条规则是一个滑动窗口计数器（≤10 单/分钟），成本远低于它换来的验收完整性。
>    plan §2 与 §6 的这处不一致按「§6 + SC-04」解决。
> 15. **DESIGN §11 的四张表在 P3 扩成六张。** `orders`/`fills`/`signals`/`equity_snapshot` 之外加 `positions` 与
>    `risk_interceptions`，因为 spec 直接要求的两件事在四张表里无处可放：FR-RK-08「拦截记录可查询，包含命中的规则与
>    当时账户状态」（场景 3 的 And 子句）与 EX-04/EX-06 的持仓修正需要一份可查的持仓历史。把拦截塞进 `signals`
>    会让一张表同时表达「策略想做什么」和「风控不让做什么」两种事实，查询口径只能靠一列 flag 区分。
>    schema 保持方言中立、价格与数量**存文本**（取舍 5 的同一理由：SQLite 的 NUMERIC 亲和性与 MySQL 的 `DECIMAL`
>    补零都会破坏逐位复现）；MySQL 集成测试仍留到 P4-8。
> 16. **OMS 占的正是回测里 `SimulatedExecutor` 的那个位置，并且由它写账本。** paper/live 注册 `OrderManager`、
>    backtest 注册 `SimulatedExecutor`，两者吃同一个 `OrderRequestEvent`、发同一个 `FillEvent`，链路其余部分一行不改
>    （FR-BT-06 的第三次兑现：类同构 → 配置同构 → 链路位置同构）。写账本的规则沿用 T215 的结论 ——
>    **谁确知成交发生，谁就在 publish `FillEvent` 之前把成交写进 `Portfolio`**：这样「账本 handler 必须排在策略之前」
>    这条隐式注册顺序约束根本不存在，且策略 `onFill` 看到的持仓就是这笔成交造成的。
> 17. **出厂默认值必须自洽：`targetExposure` 与「单笔 ≤20% 权益」是耦合的，耦合式是 `单笔上限 ≥ 2 × targetExposure`。**
>    翻仓那一单的数量是目标仓位的两倍，所以任何 flip 的名义都是 `2 × targetExposure × equity`；
>    而 `PositionSizer.Policy.DEFAULT` 现在是 0.30 —— 配上 DESIGN 的 20% 上限，**所有翻仓、以及所有从空仓建到 30% 的单
>    都会被订单级规则拦掉**。规则没错，错的是两个默认值放在一起不成立（P2 没有订单级规则，所以这个矛盾一直不可见）。
>    P3 落地时把出厂 `targetExposure` 降到 **0.10**（上限 0.20 恰好容纳一次翻仓），并在 `application.yml` 注释里写明这条不等式。
>    **`BacktestSmokeTest` 必须显式声明一套不咬人的风控参数**：它钉的是数据集 + 策略 + 撮合的确定性，不是风控默认值，
>    让它继承出厂风控参数等于把两件事的变更绑在一起。若钉住的数字仍然变了，按 `PROVENANCE.md` 在同一个提交里重新测量。
> 18. **两处"P3 只做一半"是刻意的，不是遗漏。** ①RK-08 的「推送通知」在 P3 只到**事件 + 落库 + 日志**为止：
>    邮件通道是 P4-1（JavaMailSender + QQ SMTP，需要 SMTP 授权码，当前阻塞）。P4 接上推送时不需要改风控层一行。
>    ②EX-05 幽灵仓位在 P3 **只告警不平仓**（plan 把自动平仓划给 P4-5）：自动平仓是一个会自己下单的行为，
>    在没有 7 天浸泡证据之前，不该跟对账同一个 PR 落地 —— 对账判错一次，代价就是一笔真实的反向单。

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
| 2026-09-10 02:45 | T220（CI 回测冒烟：提交 2400 根真实数据集 + `BacktestSmokeTest` 4 个用例 + `BacktestSmokeFixtureTest` 按需再生成 + `PROVENANCE.md`） | 通过：新增 5 个测试，全仓 **304→309 全绿**（2 个跳过 = 两个按需联网测试：夹具再生成与 testnet 下载）。**SC-02 有了实物证据**：同一份输入连跑三次（每次 **重建** `BacktestWiring`，因为 `Strategy` 跨 bar 带状态，重跑同一个 runner 是往预热过的策略里再灌一遍数据），`metrics()`/`curve()`/`trades()`/`fills()`/`funding()` 五个记录全等 **且 HTML 逐字节相同**，surefire 计时 **0.423s**（三次 2400 根回放的总和）—— 这也是 SC-01 的早期信号：26k 根按此外推约 4.6s 回放。**数据集是真实抓来的、不是编的**：走真正的 `BinanceKlineDownloader → CsvKlineRepository` 路径从 `testnet.binancefuture.com` 拉 2024-01-01T00:00:00Z 至 2024-04-09T23:00:00Z 的 BTCUSDT 1h，**2 次请求（真的翻页了）**、`skippedForming=0`，落盘 **174917 字节**、sha256 `992672de066e55114cf8112681c88174a1b4264425cf2852f4dc020844ec61a5`；再生成测试顺带证明幂等（**第二次同区间下载 stored=0**）。一个 testnet 的细节记在 `PROVENANCE.md` 里：**它拒绝 `BTCUSDT.PERP` 这个符号**，抓取时按 `BTCUSDT` 请求、落盘时按统一符号命名，否则再生成会静默产出空目录。**钉住的黄金值（实测得来，第一稿里那些编的数全被换掉了）**：`totalReturn -0.13417588`、`maxDrawdown 0.15788502`、`finalEquity 8658.24122746`、`totalFees 338.75374834`、`fundingTotal 10.59291420`、`fills 123`、`closedTrades 122`、`openTrades 1`、`wins 32`、`losses 90`。**基线是亏损的，这件事写在类注释里而不是藏起来**：绿灯说的是「管道没变」，**完全不代表 MA 双均线值得跑**（100 天小时线、扣 taker 费+滑点+资金费后 26% 胜率，正是一个趋势跟踪策略在震荡窗口里的样子）——策略好坏是读报告的人的判断，不是 CI 的。**年化按取舍 8 只报不断**：实测 `-0.4089580146693411`；`winRate 0.26229508196721313` 与 `sharpe -1.7422310888491224` 打印但也不单独钉 —— 前者是 `wins/closedTrades`（两个都已钉），后者由净值曲线推出（曲线在确定性用例里已逐位比对），再钉一遍只是给同一批输入多两个必须同步修改的字符串。**数据集自检用一条网格断言**：`bars.get(i).openTime() == FROM + i*HOUR` 对 2400 个 i 成立，一句话同时蕴含了条数、顺序、端点与**无空洞**；另外单列 `gaps()` 为空，因为 committed 数据集里出现一个洞不会响亮地失败，只会让每个黄金值移动一个看起来像策略 bug 的量。**变异测试 14 个，杀掉 13 个**，且刻意瞄准**链路**而不是装配（装配已由 T219 的 26 个覆盖）：mark 价改用开盘、成交价改用收盘（未来函数）、CEILING/FLOOR 互换、手续费按开盘价算、taker 费率 0.0005→0.00045、回撤峰值不更新、总收益漏减 1、金叉守卫删除、信号强度 1.0→0.5、目标敞口 0.30→0.31、CSV 解析改走 double、CSV 排序反转、以及**唯一一个只为 SC-02 而设的变异体**（HTML 里多打一行 `Instant.now()`/`System.nanoTime()` —— 它被三次逐字节比对杀掉，证明那条断言真的在看守「墙上时间不得进被比对的产物」）。**唯一存活的 M3（把 `slipOfLastBar.put` 移到 `fillPending` 之前）经查是等价变异体，不是漏测**：该 map 的唯一读点在 `onOrderRequest`（存进 `Pending` 后 `fill` 只读 `Pending.slippageBps()`，`fillPending` 从不碰 map），而 `EventEngine.publish` 是 `externalQueue.offer(event)` —— **入队，不是重入分发**，所以 `onKline` 执行期间没有任何订单能被下。这个重入问题是值得查的（`Strategy.onFill` 确实存在且确实能发单），但从 `onFill` 发出的 `OrderRequestEvent` 同样排在 `onKline` 之后才被消费，因此 put 相对 `fillPending` 的位置**对任何策略都不可观测**。**推论是原注释说的是错的理由**（「Recorded after the fill: 后下的单落进这根 bar…」），已改写为真正的不变量：*在本 handler 返回之前记录，这才是「用这根 bar 的振幅」成立的原因*。**夹具解析走 classloader（`getResource().toURI()`）而不是 `src/test/resources/...`**：冒烟测试是唯一一个不许依赖工作目录的测试，在某种 IDE 运行配置下报「数据集找不到」会教会人去忽略冒烟测试。 |
| 2026-09-10 03:12 | T223（补：历史数据下载入口 `download` profile + `AlphaProperties.Download` + 抽出 `DataPlan` + `application-download.yml`） | 通过：新增 22 个单测（app 36→58，全仓 309→**331** 全绿，2 个跳过 = 两条按需联网测试），并**手工跑通打包产物**：`java -jar … --spring.profiles.active=download --alpha.download.from=2023-09-10T00:00:00Z --alpha.download.to=2026-09-09T00:00:00Z` → **EXIT 0**、墙上 8s、**18 次请求**（真的翻页了）、**26281 根 fetched/stored、0 根因未收盘被丢**，落盘 `data/klines/BTCUSDT.PERP-1h.csv`（1934662 字节，sha256 `b4484e26…b94584`，gitignored 不提交）；**紧接着同区间重跑 stored=0** —— 幂等，这也是「我的库是不是最新」这个问题唯一诚实的答法。**补这条任务的原因是产品缺口而不是任务缺口**：FR-BT-05 要的是一个*工具*，而 T212 的 `BinanceKlineDownloader` 只有 JUnit 能调到，`application-backtest.yml` 却把 `csv-dir` 注释成「下载器写进来的目录」—— 没有任何东西往里写，SC-01 也只有写过一次性 harness 的人能复现。**验收时查出一个真实隐患（已修）**：`alpha.backtest.data` 原先只声明在 `application-backtest.yml`，而 **Spring 只为激活的 profile 加载 `application-<profile>.yml`**，download profile 根本不读那个文件 —— 它落到 `AlphaProperties.Backtest.Data` 的 record 默认值 `data/klines`，**与回测一致纯属出厂值恰好相同**。操作员一旦把 `csv-dir` 改成别的路径，就会得到「下载成功、回测说没数据」，正是取舍 9 声称不可能发生的那个现象。修法是把 `data` 块搬进**共享的 `application.yml`**（取舍 6 的同一原则：它不是回测专属旋钮，是两个模式共用的存储），并加 `ShippedConfigurationTest`（3 个用例，**不起容器**，用 `YamlPropertySourceLoader` 直接问「哪个文件声明了什么」）。**断言的是「已声明」而不是「绑定后的值」**：record 默认值与出厂值同为 `data/klines`，绑定结果在「文件里写了」与「回落默认」两种情况下不可区分，所以必须问 property source 本身；另两个用例分别断言两个 profile 文件**各自单独加载时都查不到这个 key**（与共享文件一起加载时，重复声明同一个目录是不可见的），以及两个 profile 解析出同一个 store。变异体 D21（从 `application.yml` 删掉 data 块）与 D22（塞回 `application-backtest.yml` 且改成 `/mnt/klines`）都被杀掉。**离线验收用 JDK 自带的 `com.sun.net.httpserver.HttpServer` 做 stub**（`mockwebserver` 不在本地 m2 而构建是离线的）：它记录每个查询、**只服务落在请求窗口内的 bar**，于是「区间被换掉或截断」表现为 bar 变少而不是被静默接受；需要失败时用 `failWith(400)` 而不是关掉的 socket —— 连接错误会被下载器按 5 次指数退避重试，一条断言会变成 15 秒。**`settings()` 与 `release()` 都做成 package-private static**，理由写在 Javadoc：否则「testnet/live 映射」与「释放到底做了什么」都只有联网才测得到。**容器测试断言 download profile 下没有 `EventEngine`/`StrategyEngine`/`ExchangeGateway`/`BacktestWiring` 四个 bean、恰好一个 `DownloadWiring`、JVM 里没有名为 `event-engine` 的存活非守护线程** —— 与 T219 同一套结构性论证（承重墙是 profile 限制，拆掉它只有能启动容器的测试看得见）。**变异测试 22 个杀掉 21 个**：三处拒绝、testnet/live 两向、空 base-url 当成有值、override 顺手重置退避预算、序列方向反转 / 全局周期被忽略 / 显式列表变成追加、写到别的目录、区间互换、db→csv 回落、未知 source 回落、SQLite 校验删除、`@Profile("download")` 拆除、`symbol.binance()`→`unified()`、连接池不 evict、dispatcher 不 shutdown，加上面两个 yml 变异体。**唯一存活的 D11（`download()` 不调用 `release()`）经查是不可观测，不是漏测**，判定**靠实测而不是推断**：先加一条直接钉 `release()` 的用例（发一次真请求 → 断言连接池非空且 executor 未 shutdown → 调 `release` → 断言池空且 `isShutdown()`），D12/D13 因此被杀；再写一次性探针采样 10×100ms，发现 **`OkHttp TaskRunner` 是守护线程且是 JVM 级单例，释放之后照样 TIMED_WAITING 活着**，released 与 unreleased 的线程集合完全相同，唯一差别是池里那条连接（unreleased `pool=1`），而那个 client 在 `download()` 里创建并丢弃、外部拿不到。把 client 注入进来只为可测性，等于把 HTTP client 塞进装配的公开契约，不划算 —— 结论写进 `release()` 的 Javadoc（「删掉这个 finally 之前先知道它挡的是什么：工具已经打印 complete 之后还攥着一条 socket 和一个清理任务」），不假装有测试钉住。**变异脚本的新陷阱**：D11 第一版的替换文本调用了一个不存在的方法，而**编译失败的变异体不算被杀**（构建失败会被记成 KILLED，理由却是错的），已改成编译通过的 `if (series.isEmpty())` 守卫 —— 写变异体时先问一句「这个改动能编译吗」。 |
| 2026-09-10 03:18 | T221（SC-01 性能验收：BTCUSDT 1h 近三年完整回测 + 报告） | **达标，余量是两个数量级**。数据集 = 用 T223 的 download profile 从 testnet 拉的 **26281 根**（2023-09-10T00:00:00Z → 2026-09-09T00:00:00Z，1095 天 × 24 + 1，sha256 `b4484e26…b94584`，1.9MB，放 gitignored 的 `data/klines`、**不提交**）。跑法就是操作员的跑法（`mvn -B -o package -DskipTests` 之后两条 `java -jar`），**实测：回放 425ms（feeder 自报 `PT0.425222083S`）、整个 JVM 墙上 1.379s（含 Spring 启动 0.575s、CSV 解析、958962 字节 HTML 落盘）、EXIT 0**，而预算是 **5 分钟**（≈218 倍余量）。`Backtest done: bars=26281, orders passed=1154, filled=1154, rejected=0, still pending=0, equity samples=26281`；**0 处缺口** —— testnet 三年小时线是完整的，所以这份计时既不含缺口处理的开销，也不需要它。**顺手把 SC-02 在 26k 根上又验了一遍，而且比 T220 更强**：三个**独立 JVM 进程**（默认、`report-dir` 换到 /tmp、`journal=true`）产出的 HTML **sha256 完全相同**（`f6946261…1ec52d`）。T220 钉的是同一进程内三次运行逐位一致，这里证明的是**跨进程、跨 journal 开关**也一致，即墙上时间与 journal 都没进被比对的产物。**测出一条与注释不符的事实并改了注释**：`application-backtest.yml` 原先说 journal 默认关是因为「三年小时线每根数个事件，写它们会与 SC-01 的 5 分钟竞争」；实测 `journal=true` 是 **746ms 回放 / 1.758s 墙上 / `events.jsonl` 8274642 字节**，即只 **+0.3s**，对 5 分钟毫无威胁 —— 真正的代价是那次运行多出一个 8.3MB 的产物。注释已换成实测数字与真正的理由（按 T220 的 M3 先例：**测量与注释冲突时，按测量改注释**）。**刻意不加 CI 计时断言**（沿用 T220 的判断）：一条「< 5 分钟」的断言在慢 CI 上只会变成抖动源，而 SC-01 的验收标准本来就是「实测计时记录在执行日志」；**可复现性由 T223 保证**（两条命令 + 数据集 sha256），不靠把 1.9MB 塞进仓库。**结果本身照旧不是策略评价**：权益 10000 → 2007.81680474（**-79.92%**）、最大回撤 81.25%、夏普 -1.947、年化 -41.4424%、1153 笔已平 + 1 笔未平、胜率 26.71%、盈亏比 0.5636、手续费 1741.54443593、资金费 39.87562933 —— MA(10,30) 在三年小时线上扣掉 taker 费 + 滑点 + 资金费后是稳定亏损的，与 T220 那条 100 天亏损基线同方向、只是窗口更长。SC-01 说的是「跑得完」，不是「值得跑」。 |
| 2026-09-10 03:25 | P3 细化 | 生成 **T301-T321**（21 条，其中 T321 阻塞）。细化时查出并解决了 5 处上游文档之间/与既有实现之间的矛盾，全部记进取舍 11-18 而不是就地拍板：①DESIGN §8 的「平面管道」装不下订单级/组合级规则（它们要数量，数量是 RK-07 换算出来的）→ 两阶段管道；②DESIGN 的 `marginRatio≥150%` 与 Binance 同名指标**方向相反**，直接对接会让快爆仓的单通过检查 → 规则自算，交易所那个字段只进对账；③DESIGN 说规则放 `risk-rules.yml`，但取舍 9 已证明「profile 专属文件 + 回测实盘必须同值」是陷阱 → 放共享 `application.yml`；④plan §2 把频率级划给 P4，而 P3-2 的验收 SC-04 要求「全部五级」→ 按 §6+SC-04 在 P3 做；⑤DESIGN §11 的四张表放不下 FR-RK-08 的拦截记录与持仓历史 → 扩成六张。另查出**一处 P2 遗留的默认值矛盾**（取舍 17）：`targetExposure=0.30` 配上 DESIGN 的「单笔 ≤20% 权益」会拦掉所有翻仓与所有建仓到 30% 的单 —— P2 没有订单级规则所以从未暴露；耦合式 `单笔上限 ≥ 2 × targetExposure` 写进 yml 注释，出厂敞口降到 0.10。阻塞项：T321（场景 2 testnet 全链路）与 T316-T318 的实跑部分需要 API Key，验收改为离线桩 + 真实报文样本单测，实跑留作用户验收项（同 T112）。 |
| 2026-09-10 03:40 | T301（`RiskRule` SPI + `SignalFacts`/`OrderFacts` + `RiskPipeline` + `RiskGate` 改为管道宿主） | 通过：新增 20 个单测（`RiskPipelineTest` 15 + `RiskGateTest` 9→14，alpha-risk 26→46，全仓 331→**351** 全绿，2 个跳过 = 两条按需联网测试）；`BacktestSmokeTest` 四个用例照旧通过 —— **出厂装配仍然传 `RiskPipeline.empty()`，所以钉住的黄金值不是「碰巧没变」而是构造上不可能变**，真正换上五级规则是 T302/T308 的事，届时数字必然移动并按 `PROVENANCE.md` 重新实测。**原计划里的单一 `RiskContext` 拆成了两个记录**：一个上下文类型必须给「数量」留一个可空的槽位，于是「订单级规则读到了一个还没被换算出来的数量」这件事在类型上是可表达的，只能靠注释和纪律守；拆成 `SignalFacts`（信号 + 账户快照）与 `OrderFacts`（候选订单 + **成交后的账本投影**）之后，PRE_SIZE 阶段的规则**拿不到**数量，编译期就成立。`OrderFacts` 的投影是这一层的全部价值：查当前总名义会放过「一步一步把已超限的账本继续做大」的每一单，只查本单名义会漏掉「相对权益很小但把单一标的翻倍」的单。**投影按「换掉本标的在总名义里的那一片」算**（`total - symbolNotional + projectedSymbolNotional`）而不是重新对全账本 mark 一次：其他持仓没动，重读会让投影依赖于两次读之间到达的标记价 —— 与 `RiskGate` 的**单快照规则**同一条理由，整条决策链只 `SignalFacts.of` 一次，规则、sizer、拦截记录看到的是同一个对象（用例断 `isSameAs`，不是 `isEqualTo`）。**`RiskGate` 的四步顺序**：缺精度规则 → 信号级规则 → FR-RK-07 换算 → 订单级规则。缺规则排在最前，因为没有 tickSize/stepSize 时任何规则算出来的名义价值都是编的；信号级排在换算之前，用例用**强度 0**（换算结果是「无需交易」，本该什么都不发）来证明熔断规则确实先跑过 —— 换算先行会让这次尝试静默结束，规则永远不知道自己被问过。**两条构造期守卫**：①跨两阶段的 ruleId 去重（告警与拦截记录都只按 id 索引，同号两阶段会让「哪条真的响了」变成歧义）；②`attributed()` 校验规则返回的 rejection 必须写自己的 id **和** level —— 少了它，一条复制粘贴出来的规则会把单子正确拦下、告警读起来也合理，而拦截记录指向一条从未响过的规则，这类错只有凌晨三点试图给自己解释「这单为什么被拦」的人才会发现。**变异测试：14 个变异体全部杀掉**（去重守卫关闭、两阶段各自绕过归因校验、归因校验的 id 半条与 level 半条分别删除、信号级短路改成取最后一条、投影忘记减掉旧片、BUY 投影变成减、事实交出绝对数量而非带符号数量、投影名义忘记取绝对值、订单名义改用投影数量、`RiskGate` 跳过信号级、跳过订单级、订单级重读账本）。**跑变异查出测试自身的三处缺陷，都改了测试而不是放过变异体**：①`theTwoStagesAreIndependent` 里一条 `assertThat(signal.seen).isEmpty()` 永远不可能通过（前面那次 `checkSignal` 已经记了一条），改成「各记一条、谁也没伸进对面的列表」；②归因用例原先让 id 与 level **同时**不同，于是删掉校验的任意一半都照样抛 —— 拆成「同 level 错 id」与「同 id 错 level」两条，两个比较才各自承重（这正是 D5 第一版存活的原因）；③长仓用例里 `Position.qty()` 与 `signedQty()` 数值相同，**「事实交出绝对数量」这个变异体在只有多头的测试下不可检测**，补了一条空头账本用例（-10 的带符号数量、1000 的名义敞口、买 30 覆盖后是 +20 而不是 40）—— 空头是唯一让两者分叉的地方，也是符号 bug 唯一的藏身处。**脚本陷阱复现**：D7/D8 第一版替换文本的换行位置与源码不符（`PATTERN x0`），按纪律不算杀也不算存活，改正后重跑。 |
