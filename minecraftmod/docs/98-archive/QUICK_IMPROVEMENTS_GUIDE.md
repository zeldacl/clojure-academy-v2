# Entity 改进快速执行指南

**文档**：ENTITY_FUTURE_IMPROVEMENTS.md（详细计划）

---

## 🚀 快速总览

| Phase | 目标 | 工作量 | 优先级 | 预计耗时 |
|-------|------|--------|--------|---------|
| **A1** | MdRay 粒子系统 | ⭐⭐ | 🔴 HIGH | 2 天 |
| **B1** | ignoreDepth 渲染 | ⭐ | 🔴 HIGH | 0.5 天 |
| **B3** | TP 粒子生成 | ⭐⭐⭐ | 🟡 MEDIUM | 1.5 天 |
| **C1** | 方块 NBT 序列化 | ⭐⭐ | 🟡 MEDIUM | 1 天 |
| **A2** | BloodSplash 纹理 | ⭐⭐ | 🟡 MEDIUM | 1.5 天 |
| **C2** | 旋转同步消息 | ⭐⭐⭐ | 🟡 MEDIUM | 2 天 |

---

## 📋 本周任务清单

### ✅ 立即可做

**B1: ignoreDepth 深度禁用**（0.5 天）
```java
// 位置：forge-1.20.1/src/main/java/cn/li/forge1201/client/effect/WireMarkerRenderer.java
// 修改：render() 方法中添加
if (ignoreDepth) {
    RenderSystem.disableDepthTest();
    // render...
    RenderSystem.enableDepthTest();
}
```

**A1: MdRay 粒子**（2 天）
```
1. 导入纹理：effects/md_particle.png
2. 创建 MdParticleProvider.java + MdParticle.java
3. 在 ray hook 中调用粒子生成（80% 概率）
4. 注册粒子到 RegisterParticleProvidersEvent
```

**B3: TP 粒子**（1.5 天）
```
1. 导入纹理：effects/tp_particle.png
2. 创建 TpParticleProvider.java + TpParticle.java
3. 在 marker hook 中调用（40% 概率，需 available flag）
4. 注册粒子
```

---

## 🔗 关键代码位置

### 粒子工厂模板
```java
// 来源：.tmp/AcademyCraft/src/main/java/cn/academy/client/render/particle/MdParticleFactory.java
public Particle next(World world, Vec3d pos, Vec3d motion) {
    Particle p = new Particle(world, pos.x, pos.y, pos.z);
    p.setMaxAge(RandUtils.rangei(25, 55));     // 生命周期
    p.setSize(RandUtils.rangef(0.05f, 0.07f)); // 大小
    p.setAlpha(RandUtils.rangef(76, 152) / 255f);
    p.setMotion(motion.x, motion.y, motion.z);
    return p;
}
```

### NBT 序列化模板
```java
// 来源：.tmp/AcademyCraft/src/main/java/cn/academy/entity/EntityBlock.java
@Override
protected void readEntityFromNBT(NBTTagCompound tag) {
    // 读取字段
}

@Override
protected void writeEntityToNBT(NBTTagCompound tag) {
    // 写入字段
}
```

### 网络消息模板（Forge 1.20.1）
```java
public class S2CBlockBodySyncMessage {
    // 使用 Payload + CustomPayloadEvent
    public void handle(NetworkEvent.Context ctx) {
        ctx.enqueueWork(() -> {
            // 处理消息
        });
    }
}
```

---

## 📦 资源清单（需导入）

### 纹理文件
```
forge-1.20.1/src/main/resources/assets/my_mod/textures/
├─ particle/
│  ├─ md_particle.png      (32×32, 淡绿粒子)
│  └─ tp_particle.png      (32×32, 紫白粒子)
├─ effect/
│  ├─ blood_splash_0.png   (10 帧序列，深红)
│  ├─ blood_splash_1.png
│  └─ ...
└─ shield/
   ├─ mdshield.png        (可选，旋转纹理)
   └─ diamond_shield.png  (可选，金字塔网格)
```

### 原版参考
- MdRay 粒子：80% 概率，life 25-55 tick，size 0.05-0.07
- TP 粒子：40% 概率，life 20 tick，size 0.1-0.2
- BloodSplash：10 frame 序列，size 0.8-1.3，alpha 固定 200

---

## ⚠️ 注意事项

1. **粒子生命周期**
   - 单位：tick（1 tick = 50ms）
   - 导入时确保纹理尺寸 32×32（POT）

2. **网络同步频率**
   - MagManipEntityBlock：每 5 tick 同步（4 Hz）
   - 避免过度同步导致网络拥塞

3. **渲染优化**
   - ignoreDepth 仅在需要时使用（会降低性能）
   - 粒子生成频率受 CPU 和 GPU 限制

4. **向后兼容**
   - 所有改进都是可选的（不影响现有功能）
   - 可逐步集成而不破坏编译

---

## 📞 支持资源

- 详细计划：[ENTITY_FUTURE_IMPROVEMENTS.md](ENTITY_FUTURE_IMPROVEMENTS.md)
- 原版分析：`/memories/session/ac-original-implementation-analysis.md`
- 参数对标：[ENTITY_PARAMETER_ALIGNMENT.md](ENTITY_PARAMETER_ALIGNMENT.md)
- 迁移实现总结：[ENTITY_MIGRATION_COMPLETE.md](ENTITY_MIGRATION_COMPLETE.md)

---

**状态**：📋 计划完成，随时可启动  
**下一步**：选择优先级，分配开发  
**联系**：需要代码审查或技术支持
