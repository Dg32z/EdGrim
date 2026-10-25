# cloud-service

WatchNeko 云端服务。本目录当前只有**契约骨架**：三个端点的形状、以及把形状钉住的测试。真实推理、模型加载、存储、认证分别属于 ticket #11–#16。

契约本身在 `../contract/README.md`，语料在 `../contract/fixtures/`。**两侧测试读同一批文件**，这是整个方案的防漂移机制。

## 搭环境

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
```

`.venv/` 已被 git 忽略。

## 跑测试

```bash
.venv/bin/python -m pytest
```

## 目录

```
app/
  main.py       三个端点 + create_app(registry, scorer) 工厂
  schemas.py    线上形状（pydantic），未知字段一律拒绝
  model.py      模型版本、桩分类器、分类阈值的映射
tests/
  contract_fixtures.py    读 ../contract/fixtures
  contract_assertions.py  严格结构比对（字段名集合必须完全一致）
  test_*_contract.py      三个端点各自的契约测试
  test_contract_drift.py  防漂移机制本身的自测
```

## 本阶段是桩的部分

| 桩 | 现在的行为 | 谁把它换成真的 |
| --- | --- | --- |
| 分类器 | 恒定返回 `score = 0.0`，因此裁决恒为 `legit_like` | #13 |
| 存储 | 不上存任何样本，只回执 `accepted = len(samples)` | #14 |
| 认证 | 声明了 `Authorization` / `X-Server-Id` 两个头，但不校验 | #15 |
| 配额 | 未实现，不会返回 429 | #15 |

桩的取值（`m-0001`、`0.0`）被语料钉住。换成真实现时语料会跟着改，两侧测试会同时变红——这正是预期行为，不是回归。

## 一处契约外行为

`POST /v1/inference` 在没有已发布模型时同样返回 `404 {"contract_version": 1, "error": "no_model"}`，复用已固定的错误形状。插件不会走到这条路径（冷启动时检查根本没启用），所以没有为它单独立 fixture。
