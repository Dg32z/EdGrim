# cloud-service

WatchNeko 的云端服务：接收插件上传的特征向量并给出裁决，接收带标注的录制样本并入库，
按数据集训练模型、自动发布并在指标恶化时自动回滚。

契约在 [`../contract/README.md`](../contract/README.md)，语料在 `../contract/fixtures/`。
**两侧测试读同一批文件**，这是整个方案的防漂移机制。

## 跑起来

本地：

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python run.py          # 监听 0.0.0.0:8000
```

Docker（单机，Q22 的部署形态）：

```bash
cd ..            # 仓库根，docker-compose.yml 在那里
docker compose up -d
```

## 配置

**没有环境变量。** 服务读取一个 YAML 文件，**首次启动时自动生成**，之后不再覆盖——改完重启生效。

```bash
.venv/bin/python run.py                      # 默认读 cloud-service/config.yaml
.venv/bin/python run.py --config /etc/wn.yaml
```

首次启动会打印：

```
WARNING 已生成配置文件 .../cloud-service/config.yaml
WARNING 管理员账号 admin，密码 7gLR7JAJZC0D2rSOfFOzEJam
WARNING 请修改密码后重启；该密码只在这里和配置文件里出现。
```

生成的文件长这样，每一项都带注释：

```yaml
server:
  host: 0.0.0.0
  port: 8000

admin:
  username: admin
  password: "首次生成时随机产生"      # 请改成你自己的
  secret: "会话签名密钥"              # 改动会让所有登录会话失效
  session_hours: 8

database:
  path: watchneko.db                 # 相对路径以本文件所在目录为准
  retention_days: 90

quota:
  samples_per_minute: 6000           # 新租户的默认配额；0 表示不限

training:
  trainer: logistic                  # logistic | lightgbm
  interval_seconds: 3600             # 设为 0 关闭自动重训
```

删掉任意一项会退回默认值；`port: 0` 这类非法值会在启动时报错并指出是哪一项。

**`config.yaml` 与数据库都不进版本库**（已在 `.gitignore` 里）——前者含明文密码。

Docker 下整份配置和数据都在一个卷里：

```bash
docker compose up -d          # 首次启动在 /data/config.yaml 生成配置
docker compose logs cloud     # 这里能看到生成的管理员密码
```

## 模块

```
app/
  main.py       三个契约端点 + /metrics；create_app 只接受注入，不自己造依赖
  console.py    控制台页面路由（服务端会话守卫）
  templates/    每视图一个文档 + 共用外壳 base.html
  static/       md3.css、console.js、page-*.js
  schemas.py    线上形状（pydantic），未知字段一律拒绝
  auth.py       租户 API key + server-id；401（不知道你是谁）与 403（知道但无权）分开
  quota.py      按样本数计的滑动窗口，全有或全无
  store.py      SQLite 双表（录制会话 + 样本），按租户隔离，保留期与按哈希删除
  dataset.py    按内容寻址的数据集快照
  train.py      可插拔训练后端；阈值随模型推导
  publish.py    发布历史与回滚；健康判据
  ops.py        一个周期：清理 → 判断 → 重训 → 发布
  scheduler.py  周期驱动 + 让服务跟随发布切换模型
  metrics.py    五类指标的 Prometheus 文本输出
  config.py     环境变量
  serve.py      组合根
```

## 训练后端

`available_trainers()` 探测可用后端：

| 后端 | 依赖 | 说明 |
| --- | --- | --- |
| `logistic` | 无 | 纯 Python 批量梯度下降，确定性，永远可用 |
| `lightgbm` | `lightgbm` + `scikit-learn` | 规格要求的 GBDT；`deterministic=True`、`force_row_wise=True` |

用 `WATCHNEKO_TRAINER` 选择，默认 `logistic`。两者产出的模型**都可服务**：
`predictor_from_artifact` 能从各自产物重建打分器（logistic 从权重，LightGBM 从 booster 模型字符串）。
这一点很关键——模型若无法加载，发布与回滚就只是摆设。

### macOS 上的 libomp

LightGBM 的 macOS 轮子不带 OpenMP 运行时，`import lightgbm` 会报
`Library not loaded: @rpath/libomp.dylib`。**Linux 轮子自带**，容器里不需要任何额外步骤。

macOS 上有两条路：

1. `brew install libomp` —— 需要能写 `/usr/local`。
2. 写不了 `/usr/local` 时，把 conda-forge 的 `llvm-openmp` 取进 venv 并给 LightGBM 的 dylib 加 rpath：

```bash
curl -sL https://conda.anaconda.org/conda-forge/osx-64/llvm-openmp-23.1.4-h8c1e6b9_0.conda -o /tmp/lo.conda
mkdir -p /tmp/lo && cd /tmp/lo && unzip -q ../lo.conda && tar --zstd -xf pkg-*.tar.zst

LGB=.venv/lib/python3.14/site-packages/lightgbm/lib/lib_lightgbm.dylib
mkdir -p .venv/lib/libomp-runtime
cp /tmp/lo/lib/libomp.dylib .venv/lib/libomp-runtime/
install_name_tool -add_rpath "$PWD/.venv/lib/libomp-runtime" "$LGB"
codesign --force --sign - "$LGB"          # install_name_tool 会破坏签名
```

第 2 条是开发机上实际走的路径（沙箱不允许写 `/usr/local`），`import`、训练、booster 序列化与重建均已验证。

## 跑测试

```bash
.venv/bin/python -m pytest        # 197 个用例
```

## 有意保留的限制

- **`/metrics` 不做认证**：它是给运维自己的 scraper 用的，不含玩家数据，给 tenant key 加保护只会
  让密钥多存在一个地方。
- **一次训练只服务一个租户的数据集**：`ops.py` 按租户遍历，第一个可训练的租户胜出。跨租户汇总训练
  是 ADR-0002 的方向，但需要先有租户级样本权重，否则样本量大的租户会主导模型。
- **回滚不会删除任何东西**：历史只前进指针，被回滚的模型仍可原样服务——这正是回滚安全的原因。
