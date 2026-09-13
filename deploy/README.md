# 部署说明

三种部署方式，按场景选：

| 方式 | 场景 | 命令 |
|---|---|---|
| 首次上机 | 一台新 VPS | `sudo bash deploy/install.sh` |
| **发布流水线** | 之后每次更新 | 打 tag 或用 GitHub Actions 手动触发 |
| 本地跑一套 | 开发调试 | `docker compose up -d --build`（本机已有 Docker） |

流水线做的是「验证 → 构建多架构镜像 → 推送到 ghcr.io → SSH 让 VPS 拉取切换」，见
[`.github/workflows/release.yml`](../.github/workflows/release.yml)。

> **为什么不用 K8s**：本项目是**单实例有状态**应用（账本在内存、只允许一个写者），
> 横向扩副本会重复下单。一台 VPS + 镜像仓库 + 一个更新脚本已经覆盖了「无人值守更新」这个需求，
> 引入集群只会增加要维护的东西。等真要分片或多标的并行时再谈。

---

## 一次性配置

### 1. VPS 上（只需一次）

```bash
sudo bash deploy/install.sh git@github.com:ciaozn/alpha-trader.git

# 让部署账号能用 docker（避免为流水线开免密 sudo），并确保它能写部署目录
sudo usermod -aG docker "$USER"      # 重新登录后生效
sudo chown -R "$USER" /opt/alpha-trader
```

### 2. 让 VPS 能拉私有镜像（二选一）

镜像默认是**私有**的，VPS 拉取需要凭据：

- **方案 A（推荐）**：在 VPS 上一次性登录，凭据存在 `~/.docker/config.json`，后续拉取自动带
  ```bash
  # PAT 需要 read:packages 权限
  echo "$GHCR_PAT" | docker login ghcr.io -u ciaozn --password-stdin
  ```
- **方案 B**：在 GitHub 的 Packages 页面把 `alpha-trader` 包设为 public。镜像里只有代码和 JRE，
  没有密钥（密钥全部由环境变量注入），所以公开并不泄露凭据——但会公开代码，按你的仓库可见性决定。

### 3. GitHub 仓库配置

`Settings → Secrets and variables → Actions`：

| 类型 | 名称 | 说明 |
|---|---|---|
| Secret | `VPS_HOST` | VPS 地址 |
| Secret | `VPS_USER` | 部署账号（docker 组成员） |
| Secret | `VPS_SSH_KEY` | 该账号的**私钥**（对应公钥已写进 VPS 的 `~/.ssh/authorized_keys`） |
| Variable | `VPS_PORT` | 可选，默认 22 |
| Variable | `VPS_DEPLOY_PATH` | 可选，默认 `/opt/alpha-trader` |

> 建议在 `Settings → Environments` 建一个 `production` 环境并加上**必需审批人**：
> 工作流里已引用它，开启后每次部署都会等你点一下「Approve」——对实盘尤其值得。

### 4. 触发发布

```bash
git tag v1.0.0 && git push origin v1.0.0     # 正式发布：构建 + 部署
```

或在 Actions 页面手动触发 `Release`（勾选 `deploy_to_vps` 才会部署，适合先只验证构建）。

---

## 回滚

镜像带提交哈希、不可变，所以回滚就是「把 app 指向旧标签」：

```bash
# 在 VPS 上
bash deploy/update.sh ghcr.io/ciaozn/alpha-trader:v0.9.0 /opt/alpha-trader
```

流水线里的更新脚本**在健康检查失败时会自动回滚**到上一个镜像，并把失败版本的日志打出来。

## 更新脚本做了什么

`deploy/update.sh` 的四步：拉镜像 → 换 app 容器（**不动 MySQL**）→ 轮询健康检查 → 清理旧镜像。

两个刻意的选择：

- **等健康检查**而不是只看 `docker compose up -d` 的退出码——容器起来了不等于应用可用；
- **单实例的秒级中断是可接受的**：账本在内存、且只允许一个写者，所以不能像无状态服务那样新旧并存做滚动更新。
  中断安全的前提是 T403 的崩溃恢复：重启后先同步交易所、再回放事件日志校验。

## 日常运维

```bash
docker compose logs -f app          # 跟日志
docker compose ps                   # 看健康状态
curl -s localhost:8080/api/status   # 状态接口（只绑 127.0.0.1，可从本机或 SSH 隧道访问）
docker compose down                 # 停服务（数据卷保留；不要加 -v）
```
