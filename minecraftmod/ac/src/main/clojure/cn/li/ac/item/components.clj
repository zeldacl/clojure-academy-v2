(ns cn.li.ac.item.components
  "Component items - building blocks for Wireless Matrix system
  
  These items are used to construct matrix cores, nodes, and other wireless components."
  (:require [cn.li.mcmod.item.dsl :as idsl]
            [cn.li.mcmod.util.log :as log]))

(defonce ^:private components-installed? (atom false))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init-components! []
  (when (compare-and-set! components-installed? false true)
    (idsl/register-item!
      (idsl/create-item-spec
        "wafer"
        {:max-stack-size 64
         :creative-tab :misc
         :properties {:tooltip ["基础计算晶圆"
                                "用于构建矩阵核心"]
                      :model-texture "wafer"}}))
    (idsl/register-item!
      (idsl/create-item-spec
        "tutorial"
        {:max-stack-size 64
         :creative-tab :misc
         :properties {:tooltip ["教程物品"]
                      :model-texture "tutorial"}
         :on-right-click (fn [event-data]
                           (let [{:keys [player side]} event-data]
                             (when (= side :client)
                               (when-let [open-fn (requiring-resolve
                                                   'cn.li.ac.terminal.apps.tutorial/open-tutorial-gui)]
                                 (open-fn player)))
                             {:consume? true}))}))
    (idsl/register-item!
      (idsl/create-item-spec
        "terminal_installer"
        {:max-stack-size 1
         :creative-tab :tools
         :properties {:tooltip ["终端安装工具"
                                "用于在方块上安装无线通信终端"]
                      :model-texture "terminal_installer"}
         :on-right-click (fn [event-data]
                           (let [{:keys [player side]} event-data]
                             (when (= side :client)
                               (when-let [open-fn (requiring-resolve
                                                   'cn.li.ac.terminal.terminal-gui/open-terminal)]
                                 (open-fn player)))
                             {:consume? true}))}))
    (idsl/register-item!
      (idsl/create-item-spec
        "silbarn"
        {:max-stack-size 64
         :creative-tab :misc
         :properties {:tooltip ["银质材料"
                                "具有共鸣特性的稀有材料"]
                      :model-texture "silbarn"}}))
    (idsl/register-item!
      (idsl/create-item-spec
        "reso_crystal"
        {:max-stack-size 64
         :creative-tab :misc
         :properties {:tooltip ["共鸣晶体"
                                "用于放大无线信号的晶体"]
                      :model-texture "reso_crystal"}}))
    (idsl/register-item!
      (idsl/create-item-spec
        "resonance_component"
        {:max-stack-size 64
         :creative-tab :misc
         :properties {:tooltip ["共鸣组件"
                                "集成在矩阵中以增强功能"]
                      :model-texture "resonance_component"}}))
    (idsl/register-item!
      (idsl/create-item-spec
        "reinforced_iron_plate"
        {:max-stack-size 64
         :creative-tab :misc
         :properties {:tooltip ["强化铁板"
                                "用于构建装甲结构"]
                      :model-texture "reinforced_iron_plate"}}))
    (idsl/register-item!
      (idsl/create-item-spec
        "needle"
        {:max-stack-size 64
         :creative-tab :misc
         :properties {:tooltip ["针状组件"
                                "精密制造的微小部件"]
                      :model-texture "needle"}}))
    (idsl/register-item!
      (idsl/create-item-spec
        "coin"
        {:max-stack-size 64
         :creative-tab :misc
         :properties {:tooltip ["能力硬币"
                                "电磁炮 QTE 投掷触发物"]
                      :model-texture "needle"}}))
    (idsl/register-item!
      (idsl/create-item-spec
        "brain_component"
        {:max-stack-size 64
         :creative-tab :misc
         :properties {:tooltip ["脑波组件"
                                "用于高级能力设备"]
                      :model-texture "brain_component"}}))
    (idsl/register-item!
      (idsl/create-item-spec
        "energy_convert_component"
        {:max-stack-size 64
         :creative-tab :misc
         :properties {:tooltip ["能量转换组件"
                                "用于能量转换设备"]
                      :model-texture "energy_convert_component"}}))
    (idsl/register-item!
      (idsl/create-item-spec
        "info_component"
        {:max-stack-size 64
         :creative-tab :misc
         :properties {:tooltip ["信息组件"
                                "用于终端和信息处理设备"]
                      :model-texture "info_component"}}))
    (idsl/register-item!
      (idsl/create-item-spec
        "magnetic_coil"
        {:max-stack-size 64
         :creative-tab :misc
         :properties {:tooltip ["磁力线圈"
                                "用于超电磁炮技能"]
                      :model-texture "magnetic_coil"}}))
    (idsl/register-item!
      (idsl/create-item-spec
        "logo"
        {:max-stack-size 64
         :creative-tab nil
         :properties {:tooltip ["AcademyCraft"]
                      :model-texture "logo"}}))
    (log/info "Component items initialized: wafer, tutorial, terminal-installer, silbarn,"
              "reso-crystal, resonance-component, reinforced-iron-plate, needle, coin,"
              "brain-component, energy-convert-component, info-component, magnetic-coil, logo")))
