# 第四轮独立回归资产

七项测试从第三轮审查的真实失败复现原样保存。执行入口是
`docs/design/worktree-spec/ROUND4-REPAIR.md`，不是继续按历史 RF 表逐条打补丁。

```bash
./gradlew -I scripts/worktree-review-round4/init.gradle :core:jvmTest \
  --tests xyz.mederi.workspace.WorkspaceProductionThirdReviewTest \
  --tests xyz.mederi.workspace.WorkspaceLifecycleThirdReviewTest \
  --tests xyz.mederi.workspace.WorkspaceJournalThirdReviewTest \
  --tests xyz.mederi.workspace.WorkspaceRollbackThirdReviewTest --console=plain
```

审查时七项均失败于行为断言，0 errors/skip；不是预期应该通过的当前产品证据。
修复时把四个类一起复制进 core 常规 jvmTest 源集，保留类名、方法名和断言。
init 发现四个都已提升后不再注入源文件；只提升部分会显式报错。
允许随生产 API 重构调整 fixture，不允许把真实生产调用改成绕过被测入口的手动对象来过关。

所有 fixture 只在仓库 temp 自建假仓库与配置，不能改真实用户数据库/项目。
