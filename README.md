# Alpha Trader

事件驱动架构的加密货币量化交易系统：Binance / OKX U 本位永续合约，MA 交叉、RSI 反转策略，
行情 → 信号 → 风控 → 执行全链路闭环。**回测、模拟盘、实盘三种模式同构**——同一条代码路径，
不同的数据源与执行器，这是本系统所有设计取舍的出发点。

## 当前状态（2026-09-12）

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 | Maven 多模块工程骨架 | ✅ |
| P1 | 事件引擎 + Binance 行情接入 | ✅ |
| P2 | 策略框架 + 回测全链路（SC-01/SC-02 达成） | ✅ |
| P3 | 风控闸门 + OMS + 对账（在线链路通电） | ✅ |
| P4 | 告警 / 状态接口 / 崩溃恢复 / 风控热更新 / OKX 网关 / Docker / 双数据库 | ✅ |
| P5 | 实盘守卫（权益上限、前置校验、告警演练、净值日报） | ✅ 代码完成 |

剩余未完成事项见 [tasks.md 的「阻塞与待办总表」](specs/001-event-driven-trading-system/tasks.md)——
全部是需要 testnet 凭据、真实资金或真实时间的验收动作，不是代码问题。

## 快速开始

```bash
# 构建 + 全部测试（约 3 分钟，747+ 用例）
mvn -B clean verify

# 跑一次回测（需要先有历史数据，见「数据」）
java -jar alpha-app/target/alpha-app-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=backtest \
  --alpha.backtest.from=2024-01-01T00:00:00Z \
  --alpha.backtest.to=2024-06-01T00:00:00Z
# 报告输出到 reports/backtest-report.html

# 模拟盘（连 Binance testnet，收实时 K 线）
java -jar alpha-app/target/alpha-app-0.1.0-SNAPSHOT.jar --spring.profiles.active=paper
```

所有配置项见 [`alpha-app/src/main/resources/application.yml`](alpha-app/src/main/resources/application.yml)
（内含逐项注释），环境变量清单见 [`.env.example`](.env.example)。**密钥只走环境变量，仓库零密钥。**

## 模块结构

依赖方向自上而下，`alpha-common` 与 `alpha-engine` 不依赖任何交易所 SDK 或 Spring：

```
alpha-app         Spring Boot 装配：三/四 profile、密钥校验、在线接线、告警、状态接口
alpha-backtest    历史数据回放、模拟撮合、绩效报告、SC-05 信号一致性比对
alpha-strategy    策略 SPI 与实现（ma-cross、rsi-reversal）
alpha-risk        风控管道（五级规则）、快照采样、记录仓储
alpha-execution   OMS：订单状态机、下单出口、对账、标记价维护
alpha-gateway     交易所适配：Binance / OKX（行情 WS + 签名 REST）
alpha-engine      EventEngine：单线程事件循环、JSONL 事件日志
alpha-common      事件模型、领域模型、Clock 抽象、Portfolio 账本
```

## 运行模式

| profile | 用途 | Web 容器 | 网络 |
|---|---|---|---|
| `backtest` | 历史回放 + 绩效报告 | 无（跑完即退出） | 无 |
| `download` | 拉取历史 K 线到本地 | 无（跑完即退出） | 交易所公共 REST |
| `paper` | 模拟盘（testnet） | 有（`/api/status`） | testnet |
| `live` | 实盘 | 有（`/api/status`） | 主网 |

切换交易所：`alpha.online.gateway=binance|okx`（OKX 需 `OKX_API_KEY/SECRET/PASSPHRASE`）。
风控参数热更新：`POST /api/risk/reload`（需二次确认）。告警演练：`POST /api/alert/test`。

## 数据

- **K 线**：`data/klines/<SYMBOL>-<interval>.csv`（`download` profile 产出，回测消费）
- **业务数据**：本地默认 SQLite（`data/trading.db`），服务器可切 MySQL 8
  （`ALPHA_STORE_JDBC_URL=jdbc:mysql://...`，六张表 DDL 对两个引擎都验证过）

## 部署

```bash
docker compose up -d      # app + MySQL 8.4，含健康检查与开机重启
```

VPS 一键脚本见 [`deploy/install.sh`](deploy/install.sh)（安装 Docker、生成 `.env`、起服务）。

## 文档地图

| 想了解什么 | 去哪 |
|---|---|
| 系统设计：架构、事件模型、风控设计、里程碑 | [docs/DESIGN.md](docs/DESIGN.md) |
| 需求：场景、功能需求、成功标准（WHAT/WHY） | [spec.md](specs/001-event-driven-trading-system/spec.md) |
| 实施计划：阶段划分、进度映射 | [plan.md](specs/001-event-driven-trading-system/plan.md) |
| 任务明细与执行日志（唯一状态权威） | [tasks.md](specs/001-event-driven-trading-system/tasks.md) · [归档](specs/001-event-driven-trading-system/tasks-archive-p0-p3.md) |
| 环境变量清单 | [.env.example](.env.example) |

开发流程是 SDD（spec → plan → tasks → 实现）；文档纪律：**状态只在 tasks.md 维护**，
plan 里放指针；已完成阶段移入归档。
