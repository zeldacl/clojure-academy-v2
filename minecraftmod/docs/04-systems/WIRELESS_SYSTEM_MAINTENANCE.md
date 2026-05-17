# 无线系统维护手册

## 系统职责

无线系统负责矩阵网络、无线节点、节点连接、能量转发、GUI 查询和世界持久化。当前实现以 block/capability runtime 为唯一事实来源，不再维护旧的 domain/API split 兼容层。

## 模块边界

- `cn.li.ac.wireless.api`：对外查询和命令门面。
- `cn.li.ac.wireless.core.capability-resolver`：从 tile/VBlock 解析 `IWireless*` capability。
- `cn.li.ac.wireless.core.vblock`：无线 runtime 的 Minecraft/NBT 位置边界，纯位置逻辑复用 `cn.li.ac.foundation.vblock`。
- `cn.li.ac.wireless.data.*`：世界状态、网络状态、拓扑索引、NodeConn、持久化和 tick 逻辑。
- `cn.li.ac.wireless.config`：所有无线配置描述符和 getter。
- `ac/block/wireless_*` 与 GUI sync：只做 block/GUI 接入，通过 API、resolver 和 snapshot 边界访问无线状态。

## 运行时流程

1. Matrix 创建网络，`WiWorldData` 注册 matrix/SSID/spatial indexes。
2. Node 通过 API 或 topology helper 加入网络；旧网络绑定会立即解除。
3. Generator/receiver 通过 NodeConn 绑定到节点连接；旧连接绑定会立即解除。
4. Tick 阶段验证矩阵/节点 capability，执行网络能量平衡和 NodeConn 传输。
5. Save 阶段将当前 networks/connections 写入 schema v1 NBT；load 阶段重建新的 `WiWorldData` 和索引。

## 状态一致性规则

- 多索引更新必须走 `world-registry/transact!` 或已经封装事务的 helper。
- 不允许新增延迟删除队列；network node、generator、receiver unlink 必须同步更新 lookup/index。
- GUI 不读取内部 atom 字段；新增 UI 数据应先扩展 snapshot/presenter。
- 直接调用 `vblock-get` 后不要强转成无线接口；使用 capability resolver。

## 扩展点

- 新无线配置：添加到 `cn.li.ac.wireless.config/descriptors` 并提供 typed getter。
- 新无线设备能力：扩展 resolver 和 API/topology 命令，保持 block 层轻量。
- 新持久化字段：提升 `schema-version`，在 `wireless.data.persistence` 中集中处理读写。

## 排障手册

- 节点不入网：检查密码、capacity、矩阵 range、resolver 是否能拿到 matrix/node capability。
- 设备不连接：检查 NodeConn range/capacity、旧连接是否同步解除、`node-lookup` 是否重建。
- 存档状态丢失：检查 `world-data-to-nbt` 输出和 `world-data-from-nbt` 是否重建 network/connection indexes。
- GUI 状态错乱：检查 presenter/snapshot 是否过期，避免读取内部 atom。

## 校验命令

- `.\gradlew.bat :ac:compileClojure --no-daemon --console=plain`
- `.\gradlew.bat runAcUnitTests --no-daemon --console=plain`
- `.\gradlew.bat verifyArchitectureBoundaries --no-daemon --console=plain`
