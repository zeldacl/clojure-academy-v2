(ns cn.li.ac.item.components
  "Component items - building blocks for Wireless Matrix system
  
  These items are used to construct matrix cores, nodes, and other wireless components."
  (:require [cn.li.mcmod.item.dsl :as idsl]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Wafer - Basic computational component
;; ============================================================================

(idsl/defitem wafer
  :id "wafer"
  :max-stack-size 64
  :creative-tab :misc
  :properties {:tooltip ["基础计算晶圆"
                         "用于构建矩阵核心"]
               :model-texture "wafer"})

;; ============================================================================
;; Tutorial item
;; ============================================================================

(idsl/defitem tutorial
  :id "tutorial"
  :max-stack-size 64
  :creative-tab :misc
  :properties {:tooltip ["教程物品"]
               :model-texture "tutorial"})

;; ============================================================================
;; Terminal Installer
;; ============================================================================

(idsl/defitem terminal-installer
  :id "terminal_installer"
  :max-stack-size 1
  :creative-tab :tools
  :properties {:tooltip ["终端安装工具"
                         "用于在方块上安装无线通信终端"]
               :model-texture "terminal_installer"}
  :on-right-click (fn [event-data]
                    (let [{:keys [player]} event-data]
                      ;; Open terminal GUI (client-side)
                      (when-let [open-fn (requiring-resolve
                                          'cn.li.ac.terminal.terminal-gui/open-terminal)]
                        (open-fn player)))))

;; ============================================================================
;; Silbarn - Resonance material
;; ============================================================================

(idsl/defitem silbarn
  :id "silbarn"
  :max-stack-size 64
  :creative-tab :misc
  :properties {:tooltip ["银质材料"
                         "具有共鸣特性的稀有材料"]
               :model-texture "silbarn"})

;; ============================================================================
;; Resonance Crystal
;; ============================================================================

(idsl/defitem reso-crystal
  :id "reso_crystal"
  :max-stack-size 64
  :creative-tab :misc
  :properties {:tooltip ["共鸣晶体"
                         "用于放大无线信号的晶体"]
               :model-texture "reso_crystal"})

;; ============================================================================
;; Resonance Component
;; ============================================================================

(idsl/defitem resonance-component
  :id "resonance_component"
  :max-stack-size 64
  :creative-tab :misc
  :properties {:tooltip ["共鸣组件"
                         "集成在矩阵中以增强功能"]
               :model-texture "resonance_component"})

;; ============================================================================
;; Reinforced Iron Plate
;; ============================================================================

(idsl/defitem reinforced-iron-plate
  :id "reinforced_iron_plate"
  :max-stack-size 64
  :creative-tab :misc
  :properties {:tooltip ["强化铁板"
                         "用于构建装甲结构"]
               :model-texture "reinforced_iron_plate"})

;; ============================================================================
;; Needle - Precision component
;; ============================================================================

(idsl/defitem needle
  :id "needle"
  :max-stack-size 64
  :creative-tab :misc
  :properties {:tooltip ["针状组件"
                         "精密制造的微小部件"]
               :model-texture "needle"})

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init-components! []
  (log/info "Component items initialized: wafer, tutorial, terminal-installer, silbarn,"
            "reso-crystal, resonance-component, reinforced-iron-plate, needle"))
