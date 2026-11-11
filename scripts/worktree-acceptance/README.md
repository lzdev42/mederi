# Worktree 验收资产

本目录保存三份机器验收清单和人工对照模板。Worktree 当前结构与行为见 [Core 架构](../../docs/architecture/01-core.md)、[应用架构](../../docs/architecture/02-app-shared.md) 和 [运行流程](../../docs/architecture/04-flows.md) 的工作区章节。

检查清单及回归测试资产：

```bash
python3 scripts/verify-worktree-round4.py --manifest-only
```

`--docs-only` 仍可作为同一命令的别名。此检查只验证清单和测试资产，行为验证仍须运行测试。

完整验收使用 `temp/worktree-evidence/delivery/`，或通过 `--evidence-dir` 选择该证据根内的其他目录。保留真实的实施前 baseline 收据，以 `verify-worktree-round4.py --run` 收集常规证据，以 `verify-worktree-delivery.py --native macos` 和 `--native linux` 分别采集对应平台证据。将 [manual-template.json](manual-template.json) 复制为证据目录的 `manual.json`，完成其中的实机项目，并把交付记录写入证据目录的 `delivery.md`。最后无参数运行 `verify-worktree-round4.py`。常规、原生和人工证据必须对应当前源码指纹。旧收据可以留作历史记录。
