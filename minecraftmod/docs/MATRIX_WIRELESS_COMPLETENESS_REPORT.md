# Matrix & Wireless 系统完整性检查报告

**生成时间**: 2026-02-27  
**检查范围**: core 模块 matrix/wireless GUI 子系统与 resources 资源对接

---

## 一、关键发现

### ❌ 严重问题：XML布局文件位置不匹配

**问题描述**:  
`matrix_gui_xml.clj` 期望在标准加载路径查找 `page_wireless_matrix.xml`，但文件存在于错误的位置。

**详细信息**:

| 文件 | 代码位置 | 加载路径 | 实际位置 | 状态 |
|------|--------|--------|--------|------|
| page_wireless_matrix.xml | [matrix_gui_xml.clj:404](matrix_gui_xml.clj#L404) | core/src/main/resources/assets/my_mod/gui/layouts/ | **resources/assets/academy/gui/** | ❌ 位置错误 |
| page_wireless_node.xml | [node_gui_xml.clj:40](node_gui_xml.clj#L40) | core/src/main/resources/assets/my_mod/gui/layouts/ | core/src/main/resources/assets/my_mod/gui/layouts/ | ✅ 正确 |

---

## 二、资源缺失清单

### 2.1 XML布局文件 (1/2 缺失)

| XML文件 | 期望位置 | 实际位置 | 参考代码 | 状态 |
|---------|--------|--------|---------|------|
| page_wireless_node.xml | core/src/main/resources/assets/my_mod/gui/layouts/ | core/src/main/resources/assets/my_mod/gui/layouts/ | node_gui_xml.clj:40 | ✅ 存在 |
| page_wireless_matrix.xml | core/src/main/resources/assets/my_mod/gui/layouts/ | resources/assets/academy/gui/ | matrix_gui_xml.clj:404, 477 | ❌ 位置错误 |

### 2.2 纹理文件 - Node GUI (全部缺失)

| 纹理路径 | 引用位置 | 用途 | 状态 |
|---------|--------|------|------|
| my_mod:textures/gui/node_background.png | node_gui_xml.clj:374, page_wireless_node.xml:31 | Node UI 背景 | ❌ 缺失 |
| my_mod:textures/gui/ui_inventory.png | page_wireless_node.xml:52 | 库存UI图层 | ❌ 缺失 |
| my_mod:textures/gui/ui_wireless_node.png | page_wireless_node.xml:72 | Node特定UI | ❌ 缺失 |
| my_mod:textures/gui/effect_node.png | node_gui_xml.clj:98, page_wireless_node.xml:114 | 动画效果（10帧) | ❌ 缺失 |
| my_mod:textures/gui/icons/icon_tomatrix.png | page_wireless_node.xml:217 | 矩阵Logo图标 | ❌ 缺失 |

### 2.3 纹理文件 - Matrix GUI (全部缺失)

| 纹理路径 | 引用位置 | 用途 | 状态 |
|---------|--------|------|------|
| my_mod:textures/gui/wireless_matrix.png | matrix_gui.clj:25 | Matrix UI 背景 | ❌ 缺失 |

### 2.4 纹理文件 - 通用 (全部缺失)

| 纹理路径 | 引用位置 | 用途 | 状态 |
|---------|--------|------|------|
| my_mod:textures/gui/node.png | components.clj:45 | 通用node纹理 | ❌ 缺失 |

### 2.5 额外纹理 - academy资源 (存在但命名空间不同)

位置: `resources/assets/academy/gui/page_wireless_matrix.xml`  
命名空间: academy:textures/guis/ (不是 my_mod:textures/gui/)

| 纹理 | 状态 |
|------|------|
| academy:textures/guis/matrix_slots.png | ❌ 引用但不存在 |
| academy:textures/guis/matrix_triangle.png | ❌ 引用但不存在 |
| academy:textures/guis/gui_components.png | ❌ 引用但不存在 |

---

## 三、代码-资源映射完整性

### 3.1 Node系统

```
┌─ node_gui_xml.clj (Clojure实现)
│  └─ 加载 page_wireless_node.xml
│     └─ 引用 5 个纹理资源
│        └─ ❌ 0个存在
│
├─ node_gui.clj (非XML实现，备选方案)
│  └─ 引用 1 个纹理 (wireless_node.png)
│     └─ ❌ 不存在
│
└─ node_gui_xml.clj (出入: 新增create-screen支持)
   └─ ✅ 已支持与registry集成
```

### 3.2 Matrix系统

```
┌─ matrix_gui_xml.clj (Clojure实现)
│  └─ 加载 page_wireless_matrix.xml ???
│     └─ 实际位置: resources/assets/academy/gui/ ❌
│
├─ matrix_gui.clj (非XML实现，备选方案)
│  └─ 引用 1 个纹理 (wireless_matrix.png)
│     └─ ❌ 不存在
│
└─ 命名空间不一致:
   ├─ 代码期望: my_mod:textures/gui/
   ├─ XML存在地点: academy:textures/guis/
   └─ ❌ 模块隔离失败
```

---

## 四、问题分析

### 4.1 资源结构问题

| 问题 | 影响 | 严重性 |
|------|------|--------|
| 缺少纹理资源 | GUI渲染时加载失败 | **高** |
| page_wireless_matrix.xml 在错误位置 | matrix_gui_xml 无法实例化 | **高** |
| academy 和 my_mod 命名空间混淆 | 资源路径解析失败 | **中** |
| 无 textures/ 目录结构 | PNG加载失败 | **高** |

### 4.2 运行时影响

如果尝试在游戏中打开GUI，会遇到：

1. **Node GUI (XML)**:
   - ❌ XML加载成功但纹理加载失败
   - ❌ background/animation 不显示
   - ❌ 可能触发异常并回退到备选方案

2. **Matrix GUI (XML)**:
   - ❌ XML加载本身可能失败（文件位置错误）
   - ❌ 备选的非XML方案也会纹理缺失

---

## 五、修复方案

### 方案A：创建缺失的纹理文件（推荐）

1. **创建目录结构**:
   ```
   core/src/main/resources/assets/my_mod/textures/gui/
   ├── node_background.png (176x187)
   ├── ui_inventory.png (176x187)
   ├── ui_wireless_node.png (176x187)
   ├── wireless_node.png (176x187)
   ├── wireless_matrix.png (176x200)
   ├── effect_node.png (186x750, 10帧垂直条纹)
   └── icons/
       └── icon_tomatrix.png (32x32)
   ```

2. **修复 page_wireless_matrix.xml 位置**:
   ```
   复制 resources/assets/academy/gui/page_wireless_matrix.xml
   → core/src/main/resources/assets/my_mod/gui/layouts/page_wireless_matrix.xml
   ```

3. **更新XML内的命名空间** (如果复制academy文件):
   ```xml
   <!-- 替换 -->
   <texture>academy:textures/guis/matrix_slots.png</texture>
   <!-- 为 -->
   <texture>my_mod:textures/gui/matrix_slots.png</texture>
   ```

### 方案B：禁用XML GUI，使用非XML备选方案

1. **registry.clj**: 维持使用 `node-gui` 而非 `node-gui-xml`
2. **优点**: 不需要创建纹理或XML布局
3. **缺点**: 失去XML驱动架构的优势

---

## 六、依赖关系总结

### 代码依赖树

```
wireless/
├── block/wireless_node.clj (开启GUI的地方)
│   └── → gui/registry.clj (dispatch到GUI实现)
│       ├── → gui/node_gui_xml.clj (✅ 即将就绪，已修复create-screen)
│       │   ├── 加载 page_wireless_node.xml (✅ 文件存在)
│       │   │   └── 需要 5 个PNG纹理 (❌ 全部缺失)
│       │   └── 需要 player 参数 (✅ 已支持)
│       │
│       └── → gui/matrix_gui_xml.clj (未测试)
│           └── 加载 page_wireless_matrix.xml (❌ 位置错误)
│               └── 需要使用academy资源? (未知)
│
└── gui/node-container.clj
    └── 提供容器接口 (✅ 完整)
```

---

## 七、建议清单

| 优先级 | 任务 | 预计工作量 | 依赖 |
|--------|------|----------|------|
| 🔴 高 | 创建 textures/ 目录和PNG文件 | 中等 | 美工资源 |
| 🔴 高 | 修复 page_wireless_matrix.xml 位置 | 低 | 文件复制+引用更新 |
| 🟡 中 | 验证 matrix_gui_xml 与XML的集成 | 低 | 完成上述两项 |
| 🟢 低 | 添加 matrix_gui_xml 的 create-screen 包装器 | 很低 | node_gui_xml 参考 |

---

## 附录：文件检查清单

### A1. 需要创建的PNG文件

```
core/src/main/resources/assets/my_mod/textures/gui/
1. node_background.png - 176x187
2. ui_inventory.png - 176x187  
3. ui_wireless_node.png - 176x187
4. wireless_node.png - 176x187
5. wireless_matrix.png - 176x200
6. effect_node.png - 186x750 (10 vertical frames @ 75px height)
7. icons/icon_tomatrix.png - 32x32
```

### A2. 需要修复的文件位置

```
FROM: resources/assets/academy/gui/page_wireless_matrix.xml
TO:   core/src/main/resources/assets/my_mod/gui/layouts/page_wireless_matrix.xml
```

### A3. 需要更新的代码引用 (如复制XML)

如果复制 page_wireless_matrix.xml 到 my_mod 位置，需要更新所有 `academy:textures/guis/` → `my_mod:textures/gui/` 的引用。

---

## 总体评分

| 维度 | 完整性 | 备注 |
|------|--------|------|
| XML布局 | 50% | Node✅, Matrix❌位置错误 |
| 纹理资源 | 0% | 全部缺失 |
| 代码实现 | 85% | Node✅完成, Matrix需补充 |
| 集成就绪 | 50% | Node即将可用(纹理除外), Matrix需修复 |
| **总体** | **42%** | **立即可用: 否** |

