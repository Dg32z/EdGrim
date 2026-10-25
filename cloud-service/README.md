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

全部走环境变量，每一项都有默认值。

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `WATCHNEKO_TENANTS` | `[]` | 租户 JSON 数组，见下 |
| `WATCHNEKO_QUOTA_PER_MINUTE` | `6000` | 每租户每分钟可用的样本数 |
| `WATCHNEKO_RETENTION_DAYS` | `90` | 样本保留期；由重训周期顺带清理 |
| `WATCHNEKO_RETRAIN_INTERVAL_SECONDS` | `3600` | 重训周期 |
| `WATCHNEKO_DATABASE_PATH` | `watchneko.db` | SQLite 路径；容器内是 `/data/watchneko.db` |

```json
[{"tenant_id": "t-survival", "api_key": "…", "servers": ["survival-1", "survival-2"]}]
```

**`WATCHNEKO_TENANTS` 为空等于没有认证**：每个调用者都被当作同一个本地租户。这在 localhost 上没问题，
一旦端口对外可达就是错的——`run.py` 会在启动时为此打一条 warning。

## 管理控制台

启动后访问 `http://<host>:8000/admin/`。界面用 Material Design 3 的 token 层手写（Web 端的 MD3
Expressive 组件集并不存在，Material Web 处于维护模式），无构建步骤。

**每个视图是一个独立文档，不是一个 SPA。**

| URL | 模板 | 页面脚本 |
| --- | --- | --- |
| `/admin/login` | `templates/login.html` | `page-login.js` |
| `/admin/dashboard` | `templates/dashboard.html` | `page-dashboard.js` |
| `/admin/tenants` | `templates/tenants.html` | `page-tenants.js` |
| `/admin/datasets` | `templates/datasets.html` | `page-datasets.js` |
| `/admin/models` | `templates/models.html` | `page-models.js` |

外壳（导航栏、顶栏）只在 `templates/base.html` 里写一次，四个视图 `{% extends %}` 它——把导航复制
到五份文档里，就是五个会忘记改的地方。

**会话守卫在服务端**：未登录请求 `/admin/tenants` 拿到的是 303 重定向，页面标记根本不会发到浏览器。
放在前端做守卫的话，不该拿到的人照样能拿到 HTML。

共用逻辑在 `static/console.js`（API 客户端、对话框、snackbar、DOM 辅助），每个页面只 import 它并渲染
自己的内容——它不认识「租户」是什么，那是页面脚本的事。

**两种凭据是分开的**，这是刻意的：

| 凭据 | 谁用 | 能做什么 |
| --- | --- | --- |
| 租户 API key | 插件 | 上传样本、请求裁决 |
| 管理员登录 | 人 | 改租户、轮换密钥、回滚模型 |

插件手里的 key 不能开控制台——否则一个被攻破的服务器就能吊销所有租户。

管理员凭据走环境变量：

| 变量 | 说明 |
| --- | --- |
| `WATCHNEKO_ADMIN_USERNAME` | 默认 `admin` |
| `WATCHNEKO_ADMIN_PASSWORD` | **不设则整个 `/admin` 不挂载** |
| `WATCHNEKO_ADMIN_SECRET` | 会话签名密钥；不设则每次重启生成随机值，所有会话失效 |

### 密钥的生命周期

租户与密钥存在 SQLite 的 `tenants` 表里，密钥**只存 SHA-256 摘要**，明文仅在创建或轮换时显示一次。
所以界面上有「复制」按钮，关掉对话框就再也拿不到——这是设计，不是缺陷。

轮换会**立即**让旧 key 失效，没有重叠期。正在用旧 key 的服务器会立刻开始 401，这一点在轮换确认框里
写明了。

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
.venv/bin/python -m pytest        # 187 个用例
```

## 有意保留的限制

- **`/metrics` 不做认证**：它是给运维自己的 scraper 用的，不含玩家数据，给 tenant key 加保护只会
  让密钥多存在一个地方。
- **一次训练只服务一个租户的数据集**：`ops.py` 按租户遍历，第一个可训练的租户胜出。跨租户汇总训练
  是 ADR-0002 的方向，但需要先有租户级样本权重，否则样本量大的租户会主导模型。
- **回滚不会删除任何东西**：历史只前进指针，被回滚的模型仍可原样服务——这正是回滚安全的原因。
