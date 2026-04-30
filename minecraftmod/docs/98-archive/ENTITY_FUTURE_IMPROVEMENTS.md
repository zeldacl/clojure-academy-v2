# Entity 系统后续改进计划

**基准日期**：2026-04-28  
**优先级排序**：按技术复杂度 & 功能重要性  
**目标**：逐步对齐原版实现，提升玩家视觉体验和系统完整性

---

## 📋 改进路线图

```
Phase A: 粒子与视觉对齐（2-3 天）
  ├─ A1: MdRay 粒子系统集成
  ├─ A2: BloodSplash 纹理序列
  ├─ A3: SurroundArc/RippleMark 视觉参数微调
  └─ A4: Shield 纹理与旋转动画

Phase B: Marker 行为完整化（1-2 天）
  ├─ B1: ignoreDepth 深度禁用渲染
  ├─ B2: available flag 状态切换
  └─ B3: TPMarking 粒子 40% 概率生成

Phase C: BlockBody 网络同步（3-4 天）
  ├─ C1: EntityBlock NBT 序列化
  ├─ C2: MagManipEntityBlock 位置/旋转同步
  └─ C3: Silbarn 特殊行为（待定）

Phase D: 完整网络框架（4-5 天，可选）
  ├─ D1: Capability 系统集成
  ├─ D2: Payload/Codec 迁移
  └─ D3: 服务端行为验证
```

---

## 🎯 Phase A：粒子与视觉对齐

### A1：MdRay 粒子系统集成

**原版实现**：`MdParticleFactory`  
**调用点**：EntityMDRay, EntityMineRayBasic/Expert/Luck 的 `onUpdate()`

**原版参数**：
```java
public class MdParticleFactory {
    // 粒子生命周期：25-55 tick（随机）
    particle.maxAge = RandUtils.rangei(25, 55);
    
    // 粒子大小：0.05-0.07
    particle.setSize(RandUtils.rangef(0.05f, 0.07f));
    
    // Alpha 通道：76-152
    particle.setColor(1, 1, 1);
    particle.setAlpha(RandUtils.rangef(76, 152) / 255f);
    
    // 纹理：effects/md_particle
    particle.setTexture(Resources.getTexture("effects/md_particle"));
    
    // 重力：0（漂浮）
    // 运动：随机偏移 ±0.03
}
```

**当前状态**：
- ✅ Ray entities 声明完整（hook 系统就绪）
- ❌ 粒子生成未实现（Hook 中空）
- ❌ 纹理资源未导入

**改进步骤**：

1. **导入粒子纹理**
   ```
   forge-1.20.1/src/main/resources/assets/my_mod/textures/particle/
   └─ md_particle.png (32×32, RGBA)
   ```

2. **创建粒子工厂** (Forge 1.20.1 适配)
   ```java
   // forge-1.20.1/src/main/java/cn/li/forge1201/client/particle/MdParticleProvider.java
   public class MdParticleProvider implements ParticleProvider<SimpleParticleType> {
       @Override
       public Particle createParticle(SimpleParticleType type, ClientLevel level,
           double x, double y, double z, double vx, double vy, double vz) {
           // 创建粒子实例
           MdParticle particle = new MdParticle(level, x, y, z);
           particle.setLifetime(RandUtils.rangei(25, 55)); // 25-55 tick
           particle.setSize(RandUtils.rangef(0.05f, 0.07f));
           particle.setAlpha(RandUtils.rangef(76, 152) / 255f);
           particle.setVelocity(vx, vy, vz);
           return particle;
       }
   }
   ```

3. **在 Hook 中调用粒子**
   ```clojure
   ;; forge-1.20.1/src/main/clojure/cn/li/forge1201/entity/ray/ray_hooks.clj
   (defmethod ray-hook/tick-ray :md-ray [ray]
     (when (< (rand) 0.8)  ; 80% 概率生成粒子
       (let [pos (... ray lookingPos ...)
             vel (... rand-offset ...))]
         (particle/spawn-md-particle pos vel))))
   ```

4. **注册粒子类型**
   ```java
   // 在 ModClientRenderSetup 中
   public static void registerParticles(RegisterParticleProvidersEvent event) {
       event.register(PARTICLES.MD_PARTICLE.get(), MdParticleProvider::new);
   }
   ```

**工作量**：⭐⭐☆☆☆ (2 天)  
**优先级**：🔴 HIGH (直观改进玩家体验)

---

### A2：BloodSplash 纹理序列

**原版实现**：`EntityBloodSplash` + 10 帧纹理序列

**原版参数**：
```java
static ResourceLocation[] SPLASH = Resources.getEffectSeq("blood_splash", 10);
// 结果：[blood_splash_0.png, ..., blood_splash_9.png]

// 渲染：
frame = 0-9
texture = SPLASH[frame % SPLASH.length]
alpha = 200 (固定)
size = 0.8-1.3f (随机)
```

**当前状态**：
- ✅ 实体声明完整（生命周期 10 tick）
- ✅ Renderer 骨架就绪
- ❌ 纹理序列未导入
- ❌ 帧动画逻辑未实现

**改进步骤**：

1. **导入纹理序列**
   ```
   forge-1.20.1/src/main/resources/assets/my_mod/textures/effect/
   └─ blood_splash/
      ├─ blood_splash_0.png  (32×32, RGBA, 深红+透明)
      ├─ blood_splash_1.png
      └─ ... (9-10 帧)
   ```
   颜色：RGB(213, 29, 29) = #D51D1D (渐变透明)

2. **完善 Renderer**
   ```java
   // forge-1.20.1/src/main/java/cn/li/forge1201/client/effect/BloodSplashRenderer.java
   @Override
   public void render(BloodSplashEntity entity, float entityYaw, float partialTicks,
       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
       
       // 计算当前帧
       int frame = (int) entity.tickCount % 10;
       
       // 获取纹理
       ResourceLocation tex = new ResourceLocation("my_mod", 
           "textures/effect/blood_splash/blood_splash_" + frame + ".png");
       
       // 渲染十字 billboard
       renderCross(poseStack, buffer, tex, entity.getSize(), 
           entity.getX(), entity.getY(), entity.getZ(), 200);
   }
   ```

3. **注册纹理加载**
   ```java
   // TextureStitchEvent 中预加载纹理
   for (int i = 0; i < 10; i++) {
       String loc = "my_mod:effect/blood_splash_" + i;
       // 确保纹理被加载到 texture atlas
   }
   ```

**工作量**：⭐⭐☆☆☆ (1.5 天)  
**优先级**：🟡 MEDIUM (视觉完整性)

---

### A3：SurroundArc & RippleMark 视觉参数微调

**原版实现分析**：

**SurroundArc**（EntityIntensifyEffect）：
```java
// 生成时刻
genAtHt(2, 0);    // 立即：高度 2.0
genAtHt(1.8, 1);  // 1 tick后：1.8
genAtHt(1.5, 3);  // 3 tick后：1.5
// ... 共 7 个高度分层

// 每层生成 3-4 条子电弧
int gen = RandUtils.rangei(3, 4);
double phi = RandUtils.ranged(0.5, 0.6);  // 方向半径
double theta = RandUtils.ranged(0, 2π);    // 圆周角
```

**RippleMark**（EntityRippleMark）：
```java
// 固定参数
size = 2.0 × 2.0
color = white()
creationTime = GameTimer.getTime()  // 用于动画计算

// 渲染中：
cycleTime = (current - creationTime) % 3.6s
radius = 0.4 + cycleTime/3.6 * 0.5  // 脉动：0.4→0.9→0.4
```

**当前状态**：
- ✅ Renderer 绘制逻辑完整
- ⚠️ 参数化程度低（固定值）

**改进步骤**：

1. **SurroundArc 参数深化**（可选）
   ```clojure
   ;; 添加到 surround-arc hook 中
   (defmethod arc-hook/tick-arc :surround-arc [arc]
     ;; 当前简化版：3 层环形
     ;; 改进版：分层生成 + 渐变消退
     (let [age (arc-age arc)
           progress (/ age (:life-ticks arc))]
       ;; 分层显示：0→33% layer1, 33%→66% layer2, 66%→100% layer3
       (when (< progress 0.33)
         (render-layer-1 arc))
       (when (and (>= progress 0.2) (< progress 0.66))
         (render-layer-2 arc))
       (when (>= progress 0.5)
         (render-layer-3 arc))))
   ```

2. **RippleMark 脉动动画**
   ```java
   // BloodSplashRenderer 改进
   @Override
   public void render(RippleMarkEntity entity, float entityYaw, float partialTicks,
       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
       
       long ageTicks = entity.tickCount;
       double cyclePos = (ageTicks * 20 % 3600) / 3600.0;  // 3.6s 周期
       
       // 脉动半径：0.4→0.9→0.4
       double radius = 0.4 + 0.5 * Math.sin(cyclePos * Math.PI * 2);
       
       // 绘制多层环
       for (int layer = 0; layer < 3; layer++) {
           float alpha = 1.0f - (layer * 0.3f);  // 逐层衰减
           renderRing(poseStack, buffer, radius + layer * 0.1f, alpha);
       }
   }
   ```

**工作量**：⭐⭐☆☆☆ (1 天)  
**优先级**：🟢 LOW (已可视，优化性改进)

---

### A4：Shield 纹理与旋转动画

**原版实现**：
- DiamondShield：静态金字塔网格（固定方向）
- MdShield：旋转 billboard（自旋 9°/tick）

**当前状态**：
- ✅ DiamondShieldRenderer 线框完整
- ✅ MdShieldRenderer 旋转逻辑完整
- ❌ 可选：添加原版纹理贴图

**改进步骤**（可选）：

1. **MdShield 纹理叠加**
   ```java
   // 在 MdShieldRenderer 中添加纹理支持
   ResourceLocation tex = new ResourceLocation("my_mod", 
       "textures/effect/mdshield.png");
   
   // 半透明纹理 + 旋转 billboard
   // alpha = 0.7f
   // rotation += 9° per tick
   ```

2. **DiamondShield 网格增强**（可选）
   ```java
   // 当前：简单金字塔线框（绿色）
   // 可选：添加渐变色 (蓝→绿→紫)
   // 或：网格纹理贴图
   ```

**工作量**：⭐⭐⭐☆☆ (2 天，可选)  
**优先级**：🟢 LOW (已有基础实现)

---

## 🎯 Phase B：Marker 行为完整化

### B1：ignoreDepth 深度禁用渲染

**原版实现**：
```java
if (marker.ignoreDepth) {
    GL11.glDisable(GL11.GL_DEPTH_TEST);
}
// ... render ...
GL11.glEnable(GL11.GL_DEPTH_TEST);
```

**当前状态**：
- ✅ 实体声明中有 `ignore-depth` 字段
- ❌ Renderer 中未实现

**改进步骤**：

1. **在 Renderer 中读取 flag**
   ```java
   // forge-1.20.1/src/main/java/cn/li/forge1201/client/effect/WireMarkerRenderer.java
   @Override
   public void render(ScriptedMarkerEntity marker, float entityYaw, float partialTicks,
       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
       
       // 读取 ignoreDepth flag
       ScriptedMarkerSpec spec = ModEntities.getMarkerSpec(marker.getRegistryName());
       boolean ignoreDepth = spec.ignoreDepth();
       
       if (ignoreDepth) {
           // 禁用深度测试
           RenderSystem.disableDepthTest();
       }
       
       // 渲染
       renderWireBox(poseStack, buffer, ...);
       
       if (ignoreDepth) {
           // 恢复深度测试
           RenderSystem.enableDepthTest();
       }
   }
   ```

2. **验证 spec 参数**
   ```clojure
   ;; ac/src/main/clojure/cn/li/ac/content/entities/all.clj
   ;; 确认 marker 的 ignore-depth? 参数正确
   (edsl/register-entity!
     (edsl/create-entity-spec
       "entity_marker"
       {:entity-kind :scripted-marker
        ...
        :properties {:marker {:life-ticks 120
                              :follow-target? true
                              :ignore-depth? true  ; ✅ 正确配置
                              ...}}}))
   ```

**工作量**：⭐☆☆☆☆ (0.5 天)  
**优先级**：🔴 HIGH (功能完整性)

---

### B2：available flag 状态切换

**原版实现**：
```java
public boolean available = true;  // 可在运行时切换

// ShiftTeleport skill 中：
if (marking.available) {
    // 渲染可用状态
} else {
    // 渲染不可用状态（灰显）
}
```

**当前状态**：
- ✅ 实体中有 available 字段
- ⚠️ 需要通过 skill 系统注入该逻辑

**改进步骤**：

1. **在 skill 中设置 available**
   ```clojure
   ;; 当技能可用时设置 marking.available = true
   ;; 当技能冷却时设置 marking.available = false
   
   ;; 通过 entity 方法（需 Java bridge）：
   ;; entity.setAvailable(boolean)  // 待实现
   ```

2. **在 Renderer 中响应**
   ```java
   // TpMarkingRenderer 中
   private void renderRing(PoseStack poseStack, MultiBufferSource buffer,
       ScriptedMarkerEntity entity, float alpha) {
       
       // 获取 available 状态
       boolean available = entity.isAvailable();  // 待实现数据方法
       
       float color = available ? 1.0f : 0.5f;  // 可用→白，不可用→灰
       float effectAlpha = available ? alpha : alpha * 0.5f;
       
       // 绘制
       renderRingLine(poseStack, buffer, ..., color, effectAlpha);
   }
   ```

**工作量**：⭐⭐☆☆☆ (1 天)  
**优先级**：🟡 MEDIUM (玩家反馈视觉)

---

### B3：TPMarking 粒子 40% 概率生成

**原版实现**：
```java
if (available && rand.nextDouble() < 0.4) {  // 40% 概率
    Particle p = TPParticleFactory.instance.next(world);
    p.setPosition(posX + rand(-1, 1), posY + rand(0.2, 1.6) - 1.6, posZ + rand(-1, 1));
    p.setVelocity(rand(-0.03, 0.03), rand(0, 0.05), rand(-0.03, 0.03));
    world.spawnEntity(p);
}
```

**当前状态**：
- ✅ 粒子 registry 骨架就绪
- ❌ 粒子生成未实现
- ❌ TP 粒子纹理未导入

**改进步骤**：

1. **导入 TP 粒子纹理**
   ```
   forge-1.20.1/src/main/resources/assets/my_mod/textures/particle/
   └─ tp_particle.png (32×32, RGBA, 紫白色)
   ```

2. **创建 TP 粒子工厂**
   ```java
   // forge-1.20.1/src/main/java/cn/li/forge1201/client/particle/TpParticleProvider.java
   public class TpParticleProvider implements ParticleProvider<SimpleParticleType> {
       @Override
       public Particle createParticle(SimpleParticleType type, ClientLevel level,
           double x, double y, double z, double vx, double vy, double vz) {
           TpParticle p = new TpParticle(level, x, y, z);
           p.setLifetime(20);  // 20 tick
           p.setSize(RandUtils.rangef(0.1f, 0.2f));
           p.setVelocity(vx, vy, vz);
           p.setAlpha(RandUtils.rangef(0.6f, 0.8f));
           return p;
       }
   }
   ```

3. **在 Marker hook 中调用**
   ```clojure
   ;; hook 层在每 tick 触发粒子生成
   (defmethod marker-hook/tick-marker :tp-marking [marker]
     (when (and marker.available (< (rand) 0.4))
       ;; 计算随机位置和速度
       (let [x (+ marker.x (rand-between -1 1))
             y (+ marker.y (rand-between 0.2 1.6) -1.6)
             z (+ marker.z (rand-between -1 1))
             vx (rand-between -0.03 0.03)
             vy (rand-between 0 0.05)
             vz (rand-between -0.03 0.03)]
         (particle/spawn-tp-particle x y z vx vy vz))))
   ```

**工作量**：⭐⭐⭐☆☆ (1.5 天)  
**优先级**：🟡 MEDIUM (视觉对齐)

---

## 🎯 Phase C：BlockBody 网络同步

### C1：EntityBlock NBT 序列化

**原版实现**：
```java
// EntityBlock 中（临时实体，实际同步通过 DataManager）
@Override
protected void readEntityFromNBT(NBTTagCompound tag) {}  // 空实现
@Override
protected void writeEntityToNBT(NBTTagCompound tag) {}   // 空实现

// 真实同步通过 DataParameter
@Modifiable
static class EntityBlockData {
    int blockId;   // 方块 ID
    int meta;      // 元数据
}
```

**当前状态**：
- ✅ 实体壳完整
- ⚠️ NBT 序列化为空
- ❌ DataParameter 网络同步未实现

**改进步骤**：

1. **添加 NBT 序列化**
   ```java
   // forge-1.20.1/src/main/java/cn/li/forge1201/entity/ScriptedBlockBodyEntity.java
   
   private static final String TAG_BLOCK_ID = "block_id";
   private static final String TAG_BLOCK_STATE = "block_state";
   private static final String TAG_VELOCITY = "velocity";
   
   @Override
   protected void readAdditionalSaveData(CompoundTag tag) {
       super.readAdditionalSaveData(tag);
       
       String blockId = tag.getString(TAG_BLOCK_ID);
       this.blockRegistry = ForgeRegistries.BLOCKS.getValue(
           new ResourceLocation(blockId));
       
       // 读取其他数据
       int vel_x = tag.getInt("vel_x");
       int vel_y = tag.getInt("vel_y");
       int vel_z = tag.getInt("vel_z");
       this.setDeltaMovement(vel_x / 1000.0, vel_y / 1000.0, vel_z / 1000.0);
   }
   
   @Override
   protected void addAdditionalSaveData(CompoundTag tag) {
       super.addAdditionalSaveData(tag);
       
       if (this.blockRegistry != null) {
           tag.putString(TAG_BLOCK_ID, 
               ForgeRegistries.BLOCKS.getKey(this.blockRegistry).toString());
       }
       
       // 保存其他数据
       tag.putInt("vel_x", (int) (this.getDeltaMovement().x * 1000));
       tag.putInt("vel_y", (int) (this.getDeltaMovement().y * 1000));
       tag.putInt("vel_z", (int) (this.getDeltaMovement().z * 1000));
   }
   ```

2. **配置网络同步**
   ```java
   // 使用 EntityDataAccessor (Forge 1.20.1)
   private static final EntityDataAccessor<Integer> DATA_BLOCK_ID =
       SynchedEntityData.defineId(ScriptedBlockBodyEntity.class, 
           EntityDataSerializers.INT);
   
   @Override
   protected void defineSynchedData() {
       super.defineSynchedData();
       this.entityData.define(DATA_BLOCK_ID, 0);
   }
   
   public void setBlockId(int id) {
       this.entityData.set(DATA_BLOCK_ID, id);
   }
   
   public int getBlockId() {
       return this.entityData.get(DATA_BLOCK_ID);
   }
   ```

**工作量**：⭐⭐☆☆☆ (1 天)  
**优先级**：🟡 MEDIUM (持久化完整性)

---

### C2：MagManipEntityBlock 位置/旋转同步

**原版实现**：
```java
public class MagManipEntityBlock extends EntityBlock {
    public float rotation = 0;  // 旋转角度
    
    public void tick() {
        super.tick();
        
        // 每 5 tick 同步一次（4 Hz）
        if (this.tickCount % 5 == 0) {
            syncToServer();
        }
    }
    
    private void syncToServer() {
        // 发送位置 + 旋转
        // 频道: "MSG_ENT_SYNC"
    }
}
```

**当前状态**：
- ✅ 实体壳完整
- ❌ 旋转角度字段缺失
- ❌ 网络同步消息未实现

**改进步骤**：

1. **添加旋转字段**
   ```java
   // forge-1.20.1/src/main/java/cn/li/forge1201/entity/ScriptedBlockBodyEntity.java
   
   private static final EntityDataAccessor<Float> DATA_ROTATION =
       SynchedEntityData.defineId(ScriptedBlockBodyEntity.class, 
           EntityDataSerializers.FLOAT);
   
   @Override
   protected void defineSynchedData() {
       super.defineSynchedData();
       this.entityData.define(DATA_ROTATION, 0.0f);
   }
   
   public void setRotation(float rotation) {
       this.entityData.set(DATA_ROTATION, rotation);
   }
   
   public float getRotation() {
       return this.entityData.get(DATA_ROTATION);
   }
   ```

2. **创建网络消息**
   ```java
   // forge-1.20.1/src/main/java/cn/li/forge1201/network/S2CBlockBodySyncMessage.java
   
   public class S2CBlockBodySyncMessage {
       public int entityId;
       public double x, y, z;
       public double velX, velY, velZ;
       public float rotation;
       public int blockId;
       
       public static void handle(S2CBlockBodySyncMessage msg, 
           Supplier<NetworkEvent.Context> ctx) {
           ctx.get().enqueueWork(() -> {
               // 客户端处理同步
               Entity entity = Minecraft.getInstance().level
                   .getEntity(msg.entityId);
               if (entity instanceof ScriptedBlockBodyEntity) {
                   ScriptedBlockBodyEntity be = 
                       (ScriptedBlockBodyEntity) entity;
                   be.setPos(msg.x, msg.y, msg.z);
                   be.setDeltaMovement(msg.velX, msg.velY, msg.velZ);
                   be.setRotation(msg.rotation);
               }
           });
           return true;
       }
   }
   ```

3. **在 Entity 中发送消息**
   ```java
   @Override
   public void tick() {
       super.tick();
       
       // 服务端：每 5 tick 同步一次
       if (!this.level.isClientSide && this.tickCount % 5 == 0) {
           S2CBlockBodySyncMessage msg = new S2CBlockBodySyncMessage();
           msg.entityId = this.getId();
           msg.x = this.getX();
           msg.y = this.getY();
           msg.z = this.getZ();
           msg.velX = this.getDeltaMovement().x;
           msg.velY = this.getDeltaMovement().y;
           msg.velZ = this.getDeltaMovement().z;
           msg.rotation = this.getRotation();
           msg.blockId = this.getBlockId();
           
           NetworkHandler.INSTANCE.send(
               PacketDistributor.TRACKING_ENTITY.with(() -> this),
               msg);
       }
   }
   ```

**工作量**：⭐⭐⭐☆☆ (2 天)  
**优先级**：🟡 MEDIUM (多人游戏完整性)

---

### C3：Silbarn 特殊行为（待定）

**原版特性**：
- 物理碰撞箱特殊形状（非立方体）
- OBJ 模型渲染（可选）

**当前状态**：
- ✅ 实体声明完整
- ⚠️ 占位渲染实现

**改进建议**（低优先级）：
- 如无特殊需求，保持当前实现
- 若需要，可后续添加：OBJ 模型加载、碰撞箱自定义

**工作量**：⭐⭐⭐⭐☆ (3+ 天，可选)  
**优先级**：🟢 LOW (非关键)

---

## 🎯 Phase D：完整网络框架（可选）

### D1-D3：Capability/Payload 系统集成

**说明**：仅在需要服务端行为复杂同步时（如玩家数据、技能状态等）才需要。

当前实体同步已通过以下完成：
- ✅ DataParameter（自动同步）
- ✅ 自定义消息（关键数据）
- ✅ NBT 存储（持久化）

**未来需求触发条件**：
- 需要同步玩家 Ability 数据状态
- MagManip 需要与技能系统交互
- BlockBody 需要 TileEntity 级别的数据存储

**工作量**：⭐⭐⭐⭐⭐ (5+ 天)  
**优先级**：🟢 LOW (可选，非立即必需)

---

## 📅 时间表与优先级

### 立即可做（本周）
```
A1: MdRay 粒子系统      [███░░░░░░] 2 天  🔴 HIGH
B1: ignoreDepth 渲染    [█░░░░░░░░] 0.5 天 🔴 HIGH
B3: TP 粒子生成         [██░░░░░░░] 1.5 天 🟡 MEDIUM
C1: 方块 NBT 同步       [██░░░░░░░] 1 天   🟡 MEDIUM
```

### 可选深化（下周）
```
A2: BloodSplash 纹理    [█░░░░░░░░] 1.5 天 🟡 MEDIUM
A3: 弧线脉动参数        [█░░░░░░░░] 1 天   🟢 LOW
A4: Shield 纹理         [██░░░░░░░] 2 天   🟢 LOW
B2: available flag      [██░░░░░░░] 1 天   🟡 MEDIUM
C2: 旋转同步            [███░░░░░░] 2 天   🟡 MEDIUM
```

### 不急（后续）
```
C3: Silbarn 特殊行为    [████░░░░░] 3+ 天  🟢 LOW
D1-D3: Capability 框架  [█████░░░░] 5+ 天  🟢 LOW
```

---

## 📝 技术参考

### 资源位置
- 原版粒子代码：`.tmp/AcademyCraft/src/main/java/cn/academy/client/render/particle/`
- 原版纹理资源：`.tmp/AcademyCraft/src/main/resources/assets/academycraft/textures/`
- Forge 1.20.1 网络：`Payload` + `CustomPayloadEvent`（Networking MDK）

### 关键文件清单
- 粒子纹理导入：`forge-1.20.1/src/main/resources/assets/my_mod/textures/particle/`
- 粒子工厂实现：`forge-1.20.1/src/main/java/cn/li/forge1201/client/particle/`
- 网络消息定义：`forge-1.20.1/src/main/java/cn/li/forge1201/network/`
- Hook 实现：`forge-1.20.1/src/main/clojure/cn/li/forge1201/entity/*/`

---

**最后更新**：2026-04-28 16:58 UTC  
**下次 Review**：完成 Phase A 后重新评估
