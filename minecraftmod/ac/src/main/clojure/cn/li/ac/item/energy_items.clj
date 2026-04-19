(ns cn.li.ac.item.energy-items
  "Energy-backed item declarations migrated from original AcademyCraft."
  (:require [cn.li.mcmod.item.dsl :as idsl]
            [cn.li.ac.item.test-battery :as test-battery]
            [cn.li.mcmod.util.log :as log]))

(defonce ^:private energy-items-installed? (atom false))

(defn- open-portable-developer!
  [{:keys [player side]}]
  (when (= side :client)
    ;; Keep placeholder behavior minimal while preserving the original right-click intent.
    (when-let [open-fn (requiring-resolve 'cn.li.ac.terminal.terminal-gui/open-terminal)]
      (open-fn player)))
  {:consume? true})

(defn init-energy-items!
  []
  (when (compare-and-set! energy-items-installed? false true)
    (test-battery/init-test-batteries!)
    (idsl/register-item!
      (idsl/create-item-spec
        "energy_unit"
        {:max-stack-size 1
         :creative-tab :misc
         :properties {:tooltip ["储能单元"
                                "容量: 10000 IF"
                                "带宽: 20 IF/t"]
                      :item-model-energy-levels {:texture-empty "energy_unit_empty"
                                                 :texture-half "energy_unit_half"
                                                 :texture-full "energy_unit_full"}
                      :energy-item? true
                      :energy-capacity 10000.0
                      :energy-bandwidth 20.0
                      :battery-type "energy_unit"}}))
    (idsl/register-item!
      (idsl/create-item-spec
        "developer_portable"
        {:max-stack-size 1
         :creative-tab :tools
         :properties {:tooltip ["便携式能力开发仪"
                                "容量: 100000 IF"
                                "迁移阶段占位实现"]
                      ;; Same layout as AcademyCraft item models: empty base + overrides
                      ;; on <modid>:energy for half/full (see forge datagen + client register!).
                      :item-model-energy-levels {:texture-empty "developer_portable_empty"
                                                 :texture-half "developer_portable_half"
                                                 :texture-full "developer_portable_full"}
                      :energy-item? true
                      :energy-capacity 100000.0
                      :energy-bandwidth 100.0
                      :battery-type "developer_portable"}
         :on-right-click open-portable-developer!}))
    (log/info "Energy items initialized: energy_unit, developer_portable")))
