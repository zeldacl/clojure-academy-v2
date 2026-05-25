# Platform Onboarding Checklist

用于新增平台模块（或新增平台版本）时的最小可交付校验清单。

## 1. 编译与边界

- [ ] `verifyArchitectureBoundaries` 通过
- [ ] `:<platform>:compileJava` 通过
- [ ] `:<platform>:compileClojure` 通过
- [ ] `:<platform>:checkClojure` 已纳入基线（默认 warn 或阶段性 fail 策略已声明）

## 2. 运行最小烟雾

- [ ] 存在 smoke 任务（建议 runData 或等价最小入口）
- [ ] 日志校验任务存在，能阻断已知 fatal 模式
- [ ] smoke 任务纳入聚合验证入口

## 3. 平台层去业务化

- [ ] 平台层无业务内容 ID 到平台类的硬编码映射
- [ ] 业务 hook-id -> impl-key 映射位于业务内容层（如 ac）
- [ ] 共享层仅通过 mcmod 通用 resolver/provider 消费业务 hook 映射
- [ ] 平台层仅维护 impl-key -> 平台实现映射

## 4. Hook 覆盖契约

- [ ] `verifyForgeHookCoverage` / 对应平台覆盖任务通过
- [ ] 若平台暂不支持，缺口在 manifest 中显式声明
- [ ] `verifyPlatformHookCoverage` 通过

## 5. 文档与治理

- [ ] 更新 `docs/dev/AGENT_AND_TOOLING.md` 的命令矩阵
- [ ] 更新 `docs/dev/BUILD_AND_VERIFY_PLAYBOOK.md` 的验证步骤
- [ ] 更新平台支持状态说明（正式支持 / minimal maintenance / experimental）

## 建议执行顺序

1) 基线编译与边界
2) smoke + 日志校验
3) 去业务化检查
4) hook 覆盖契约
5) 文档更新
