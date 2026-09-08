# Alpha Trader 技术方案

> 基于事件驱动架构的量化交易系统 · Java 21 + Spring Boot 3 · Binance/OKX U 本位永续合约
> 版本 v1.0 · 2026-09-08

---

## 1. 设计总纲（结论先行）

| 决策点 | 结论 | 理由 |
|---|---|---|
| 语言/框架 | Java 21 + Spring Boot 3，Maven 多模块 | 工程化强、生态全，监控面板方便 |
| 事件总线 | **单线程事件循环**（BlockingQueue + 优先级调度） | 保证全局事件顺序确定，天然支持回放与同构回测；性能足够（非 HFT），预留 Disruptor 升级口 |
| 交易标的 | U 本位永续合约（Binance USDⓈ-M / OKX SWAP） | 可双向、流动性好；杠杆风险由风控层硬控 |
| 核心同构 | 同一事件引擎 + 同一策略/风控/OMS 代码，仅按 mode 装配不同网关与执行器 | 回测赚钱 ≈ 实盘赚钱的前提 |
| 运行模式 | `BACKTEST` / `PAPER`（testnet）/ `LIVE` 三种 profile | 逐级验证，先模拟后真金 |
| 部署 | Mac 本地开发回测；实盘部署海外 VPS（东京/新加坡） | 大陆网络无法直连交易所 API，且靠近机房延迟低 |

一句话架构：**所有组件只通过事件通信，事件流单向推进**：
`MarketDataEvent → SignalEvent → （风控闸门）→ OrderEvent → FillEvent → （更新仓位/净值）`

---

## 2. 总体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        alpha-app (Spring Boot)                  │
│   配置装配 │ REST 监控 │ Actuator │ 告警机器人(Telegram/钉钉)     │
└──────────────────────────────┬──────────────────────────────────┘
                               │
┌──────────────┐   ┌───────────▼────────────┐   ┌────────────────┐
│ 行情网关      │──►│   EventEngine           │──►│ 策略引擎        │
│ Gateway      │   │ 单线程事件循环           │   │ MA交叉 / RSI   │
│ Binance/OKX  │   │ (事件优先级 + 定时器)     │   │ 滑动窗口K线序列 │
│ WebSocket    │   └───────────┬────────────┘   └───────┬────────┘
└──────────────┘               │                        │ SignalEvent
                               │               ┌────────▼────────┐
                               │               │ 风控引擎         │
                               │               │ 规则管道(硬闸门)  │
                               │               └────────┬────────┘
                               │                        │ OrderEvent
                               │               ┌────────▼────────┐
                               └──────────────►│ 执行引擎 OMS     │
                                     FillEvent │ 订单状态机+对账   │
                                               └────────┬────────┘
                                                        │ REST/WS 私有流
                                               ┌────────▼────────┐
                                               │ 交易所 (永续合约) │
                                               └─────────────────┘
横向支撑：事件日志（append-only 落盘，崩溃恢复/回放） │ SQLite/PostgreSQL（订单、成交、净值）
```

---

## 3. Maven 多模块结构

```
alpha-trader/
├── pom.xml                    # 父 POM，统一依赖版本
├── alpha-common/              # 事件模型、DTO、枚举、Symbol 精度模型、工具类
├── alpha-engine/              # EventEngine：单线程事件循环、事件优先级、虚拟时钟
├── alpha-gateway/             # ExchangeGateway 接口 + Binance/OKX/模拟 实现
├── alpha-strategy/            # 策略框架（Strategy SPI）+ MaCross / RsiReversal
├── alpha-risk/                # 风控规则引擎（规则管道，可配置热更新）
├── alpha-execution/           # OMS：订单状态机、clientOrderId 幂等、对账
├── alpha-backtest/            # 历史数据回放器 + 撮合模拟器 + 绩效统计
├── alpha-app/                 # Spring Boot 主应用：装配、REST 监控、告警
└── docs/
```

模块依赖单向：`app → backtest → {strategy, risk, execution, gateway} → engine → common`。
**common 和 engine 不允许依赖任何交易所 SDK** —— 这是同构设计的纪律红线。

---

## 4. 事件模型（alpha-common）

Java 21 sealed interface，编译期穷举检查：

```java
public sealed interface Event {
    long eventId();        // 全局单调递增
    long timestamp();      // 事件发生的业务时间（回测时为历史时间）
}

// 行情事件
record TickerEvent(String symbol, BigDecimal price, long ts) implements Event {...}
record KlineEvent(String symbol, Interval interval, Kline bar, boolean closed) implements Event {...}

// 信号事件（策略 → 风控）
record SignalEvent(String strategyId, String symbol, Direction dir,  // LONG/SHORT/FLAT
                   double strength, String reason) implements Event {...}

// 订单事件（风控 → 执行）
record OrderRequest(String clientOrderId, String symbol, Side side,
                    OrderType type, BigDecimal qty, BigDecimal price) implements Event {...}

// 回报事件（执行 → 全局）
record OrderUpdateEvent(Order order, OrderStatus status) implements Event {...}
record FillEvent(String orderId, BigDecimal price, BigDecimal qty, BigDecimal fee) implements Event {...}

// 系统事件
record TimerEvent(String name, long ts) implements Event {...}   // 定时器（对账、心跳）
record RiskAlertEvent(Rule rule, String detail) implements Event {...}
```

关键点：
- **业务时间与墙钟分离**。回测时 timestamp 来自历史数据；EventEngine 内嵌 `Clock` 接口（`SystemClock` / `VirtualClock`），策略里的"现在"永远从 Clock 取 —— 这是同构的第二个支柱。
- 所有事件进入引擎后立即 append 到**事件日志**（JSON Lines 落盘），崩溃后可从最后一个快照 + 日志回放恢复状态。

---

## 5. EventEngine：单线程事件循环（alpha-engine）

```java
public class EventEngine {
    private final BlockingQueue<Envelope> queue;       // 普通事件 FIFO
    private final PriorityBlockingQueue<Envelope> timerQueue; // 定时事件按触发时间
    private final List<EventHandler> handlers;         // 策略/风控/OMS 按序注册
    private final EventJournal journal;                // append-only 事件日志
    private final Clock clock;

    public void run() {
        while (running) {
            Envelope e = nextEvent();                  // 定时事件到点优先，其余 FIFO
            journal.append(e);
            for (EventHandler h : handlers) h.onEvent(e);  // 同一线程内顺序分发
        }
    }
}
```

为什么不用 Disruptor/Kafka：
1. **确定性**：单线程消费保证"同一时刻全系统看到的世界一致"，回测结果可精确复现；
2. **量级够**：K 线级策略每秒事件 < 100，BlockingQueue 吞吐绰绰有余；
3. **简单**：没有并发 bug 重灾区。将来若做 tick 级高频，再换 LMAX Disruptor，handler 接口不变。

事件处理顺序（每一轮）：`行情 → 策略(可能发信号) → 风控(可能发订单) → OMS(发单)` —— 同一根 K 线触发的连锁事件在本轮内闭环，不会出现"信号等下一轮"。

---

## 6. 交易所网关（alpha-gateway）

### 6.1 统一抽象

```java
public interface ExchangeGateway {
    void connect(GatewayConfig cfg);
    void subscribeKline(String symbol, Interval interval);   // WS 订阅
    OrderAck placeOrder(OrderRequest req);                   // REST
    void cancelOrder(String clientOrderId);
    AccountSnapshot queryAccount();                          // 权益/保证金
    List<Position> queryPositions();
    List<Order> queryOpenOrders();                           // 对账用
    void close();
}
```

四个实现：

| 实现 | 用途 | 说明 |
|---|---|---|
| `BinanceFuturesGateway` | 实盘/testnet | fapi REST + WS；user data stream 用 listenKey 收回报，30 分钟 keepalive |
| `OkxSwapGateway` | 实盘/demo | v5 REST + 公共/私有 WS；demo trading 用 `x-simulated-trading: 1` 头 |
| `SimulatedGateway` | 回测 | 不发网络请求，行情来自回放器，撮合在本地完成 |
| `PaperGateway` | 模拟盘 | 包装真实网关，连 testnet/demo 环境 |

### 6.2 工程要点

- **Symbol 精度模型**：启动时拉 `exchangeInfo`（Binance）/ `instruments`（OKX），缓存 tickSize/stepSize/minNotional，下单前在网关内做精度对齐（拒绝脏单）。
- **统一 Symbol**：内部统一 `BTCUSDT.PERP`，网关层映射到 `BTCUSDT`（Binance）/ `BTC-USDT-SWAP`（OKX）。
- **断线重连**：指数退避（1s→2s→…→60s 上限），重连后重订阅 + 触发一次全量对账。
- **签名**：REST 自写轻客户端，OkHttp + HmacSHA256；不引第三方交易所 SDK，可控可审计。
- **时间同步**：启动时校准服务器时间偏移（`/fapi/v1/time`），避免签名 timestamp 拒单。

---

## 7. 策略框架（alpha-strategy）

### 7.1 SPI

```java
public interface Strategy {
    String id();
    Set<String> symbols();
    default void onKline(KlineEvent e, StrategyContext ctx) {}
    default void onFill(FillEvent e, StrategyContext ctx) {}
    default void onTimer(TimerEvent e, StrategyContext ctx) {}
}
```

`StrategyContext` 提供：K 线滑动窗口（BarSeries，定长环形缓冲）、当前持仓、发信号的出口（`ctx.emit(SignalEvent)`）。**策略不允许直接下单、不允许读时钟以外的系统状态** —— 信号是它的唯一输出。

### 7.2 内置策略

| 策略 | 逻辑 | 参数 |
|---|---|---|
| `MaCrossStrategy` | 快/慢 SMA 金叉做多、死叉平多翻空（可配是否允许做空） | fast=10, slow=30, allowShort=true |
| `RsiReversalStrategy` | RSI(14) < 30 超卖做多，> 70 超买平多；带 ATR 过滤可选 | period=14, os=30, ob=70 |

指标（SMA/EMA/RSI/ATR）自实现于 `alpha-strategy/indicator/`，不引 ta4j —— 几十个方法的数学，自己的实现保证回测/实盘计算完全一致，且少一个依赖。

策略注册：Spring 扫描 `@StrategyComponent`，`application.yml` 里按 id 启停和传参，**加策略不改引擎**。

---

## 8. 风控引擎（alpha-risk）—— 独立硬闸门

信号到订单之间唯一的通道，规则以**管道（pipeline）**串联，任何一条拒绝则拦截并发 `RiskAlertEvent` + 告警推送：

| 层级 | 规则 | 默认参数 |
|---|---|---|
| 账户级 | 总杠杆上限；保证金率低于阈值禁止开新仓 | maxLeverage=3x；marginRatio≥150% |
| 订单级 | 单笔名义价值上限；价格偏离最新价上限（防乌龙指） | 单笔≤20%权益；偏离≤2% |
| 组合级 | 总持仓名义上限；单 symbol 持仓上限 | 总仓≤60%权益；单 symbol≤30% |
| 熔断级 | 单日亏损达 X% 全天禁止开仓；连续 N 笔亏损暂停 M 小时 | -5% 熔断；连亏 3 笔停 2h |
| 频率级 | 单位时间最大订单数 | ≤10 单/分钟 |

另外风控负责**仓位换算**：`SignalEvent(strength)` → 目标仓位张数（按权益 × 目标风险敞口 ÷ 价格 ÷ 合约乘数，再对 stepSize 取整）。策略说"我要做多"，风控决定"做多少、能不能做"。

规则定义在 `risk-rules.yml`，支持 REST 热更新（改参数不重启）。

---

## 9. 执行引擎 OMS（alpha-execution）

### 9.1 订单状态机

```
NEW → SUBMITTED → PARTIALLY_FILLED → FILLED
              │            │
              ▼            ▼
          CANCELED     CANCELED(剩余)
              │
              ▼
          REJECTED
```

- `clientOrderId = strategyId + timestamp + seq`，全局幂等键，重发不重复下单；
- 每笔订单状态迁移写库 + 发 `OrderUpdateEvent`；
- 回报走 WS 私有流（低延迟），REST 查询兜底。

### 9.2 对账（Reconciliation）——实盘生命线

定时器每 60s 触发：拉交易所 `openOrders + positions`，与本地状态比对，不一致则：
1. 以交易所为准修正本地（交易所是 source of truth）；
2. 发 `RiskAlertEvent` + 推送告警；
3. 发现"幽灵仓位"（本地无记录但实际持仓）时按配置选择自动平仓或仅告警。

崩溃恢复：重启后先拉账户全量状态重建，再从事件日志回放校验。

---

## 10. 回测/实盘同构（alpha-backtest）

同构 = **同一个 main 装配逻辑，三个可替换件**：

| 组件 | BACKTEST | PAPER | LIVE |
|---|---|---|---|
| 行情来源 | `BacktestDataFeeder`（CSV/DB 回放） | Binance testnet WS | Binance 实盘 WS |
| 执行器 | `SimulatedExecutor`（本地撮合） | testnet 真实撮合 | 实盘真实撮合 |
| 时钟 | `VirtualClock`（随数据推进） | SystemClock | SystemClock |

撮合模拟规则（保守假设，宁可回测吃亏）：
- 信号在 K 线**收盘**产生，下一根 K 线**开盘价**成交（避免未来函数）；
- 滑点：固定 bp + 按 K 线振幅比例，默认 5bp；
- 手续费：taker 0.05%（可配）；资金费率按历史数据或固定 0.01%/8h 计提；
- 绩效报告：净值曲线、年化、夏普、最大回撤、胜率、盈亏比、逐笔成交明细（HTML 报告）。

历史数据：启动任务从交易所 REST 批量拉 K 线落 SQLite，回测离线可用。

---

## 11. 持久化与监控

- **事件日志**：`logs/events-YYYYMMDD.jsonl`，append-only，回放/审计/排障三用；
- **业务库**：SQLite（本地开发）/ MySQL 8（服务器）四张表 —— `orders`、`fills`、`signals`、`equity_snapshot`；
- **监控**：Spring Actuator + 自建 `/api/status`（持仓/权益/引擎心跳/各网关延迟）；
- **告警**：邮件推送（QQ 邮箱 SMTP → ciaozn@qq.com，异步发送不阻塞事件循环 + 同类告警聚合节流）：成交通知、风控拦截、熔断、断线重连、对账异常。

---

## 12. 安全与部署

**安全纪律**：
- API Key 只开「合约交易」权限，**禁用提币**；绑定 VPS 固定 IP 白名单；
- 密钥走环境变量 + `.env`（gitignore），仓库里零密钥；
- 风控参数改动需要二次确认（防手滑）。

**部署**：
- 开发：Mac 本地，`./mvnw spring-boot:run -pl alpha-app`；
- 实盘：海外 VPS（AWS 东京 / 阿里云新加坡，2C2G 足够），Docker 镜像 + docker-compose（app + mysql），systemd 守护，日志卷挂载；
- CI：GitHub Actions —— 单测 + 回测冒烟（固定数据集，绩效指标阈值断言，防止改代码改坏策略行为）。

---

## 13. 里程碑路线图

| 阶段 | 内容 | 验收标准 |
|---|---|---|
| M1 骨架（第 1 周） | Maven 多模块 + EventEngine + 事件模型 + Binance testnet 行情接入 | 能跑起来，控制台实时打印 BTCUSDT K 线事件 |
| M2 策略+回测（第 2-3 周） | 指标库 + MA 交叉 + RSI + 数据回放 + 撮合模拟 + 绩效报告 | BTCUSDT 1h 近三年回测出完整绩效报告 |
| M3 风控+OMS（第 4-5 周） | 风控规则管道 + 订单状态机 + testnet 下单/回报/对账 | testnet 全链路：信号→风控→下单→成交→持仓正确 |
| M4 加固（第 6 周） | OKX 网关 + 断线重连 + 告警 + Docker 部署 | testnet 连续运行 7 天无人工干预 |
| M5 实盘（第 7 周+） | 小资金实盘（建议 ≤1000 USDT 起步），观察 2 周再逐步加仓 | 实盘净值与回测/模拟行为一致，无对账异常 |

---

## 14. 已知风险与对策（速查表）

| 风险 | 对策 |
|---|---|
| 大陆网络无法直连交易所 | 实盘跑海外 VPS；本地开发走 testnet + 代理 |
| 回测/实盘行为漂移 | 同构设计 + 撮合保守假设 + testnet 长时验证 |
| 断网/断线丢单 | WS 重连 + REST 对账 + 事件日志恢复 |
| 杠杆爆仓 | 风控硬闸门：杠杆上限 + 熔断 + 保证金率监控 |
| API Key 泄漏 | 禁提币 + IP 白名单 + 环境变量注入 |
| 未来函数（回测虚高） | 收盘出信号、次根开盘成交的纪律写死在撮合器里 |
