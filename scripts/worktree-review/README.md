# Worktree review 回归资产

2026-11-02在真实生产方法上复现的6项缺陷，最初运行6项全部失败。样本均位于repo temp，不使用真实用户目录/密钥。它们是需要修到通过的安全行为断言。

运行：

```bash
./gradlew -I scripts/worktree-review/init.gradle :core:jvmTest --tests xyz.mederi.workspace.WorkspaceReviewRegressionTest --console=plain
```

修复者应将此类纳入core/src/jvmTest/kotlin/xyz/mederi/workspace，使常规全量也运行。init.gradle检测该正式文件存在时不会重复注入；不要长期只跑带-I的特殊测试而让正式回归漏掉这6项。源接口变化可适配fixture，断言安全语义保持。

正式验收清单见 [Worktree 验收资产](../worktree-acceptance/README.md)。
