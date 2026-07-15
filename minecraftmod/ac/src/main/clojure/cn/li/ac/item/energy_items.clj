(ns cn.li.ac.item.energy-items
  "Energy-backed item declarations migrated from original AcademyCraft."
  (:require [cn.li.mcmod.item.dsl :as idsl]
            [cn.li.ac.item.developer-portable-reactive :as developer-portable-reactive]
            [cn.li.mcmod.runtime.install :as install]
            [cn.li.ac.item.item-energy-base :as energy-base]
            [cn.li.mcmod.util.log :as log]))

(defn- open-portable-developer!
  "Right-click handler for developer_portable item.
  Opens the reactive developer screen (classic layout, machine panel,
  skill tree, overlays, console) — the same rich UI used by block-based
  developers, standalone-hosted with no wireless link."
  [{:keys [player side]}]
  (when (= side :client)
    (developer-portable-reactive/open! player))
  {:consume? true})

(defn init-energy-items!
  []
  (install/framework-once! ::energy-items-installed?
  (fn []
    (energy-base/init-energy-items!)
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
                                "容量: 10000 IF"]
                      :item-model-energy-levels {:texture-empty "developer_portable_empty"
                                                 :texture-half "developer_portable_half"
                                                 :texture-full "developer_portable_full"}
                      :energy-item? true
                      :energy-capacity 10000.0
                      :energy-bandwidth 50.0
                      :battery-type "developer_portable"
                      :item-model-3d-obj {:obj-model "models/developer_portable.obj"
                                          :texture "models/developer_portable"
                                          :display {:firstperson_righthand {:rotation [0 180 0] :scale [0.3 0.3 0.3] :translation [0.34 -0.1 -0.1]}
                                                    :firstperson_lefthand {:rotation [0 180 0] :scale [0.3 0.3 0.3] :translation [0.34 -0.1 -0.1]}
                                                    :thirdperson_righthand {:rotation [0 180 0] :scale [0.2 0.2 0.2]}
                                                    :thirdperson_lefthand {:rotation [0 180 0] :scale [0.2 0.2 0.2]}
                                                    :ground {:scale [-0.15 -0.15 0.15] :translation [0 0.1 0]}}}}
         :on-right-click open-portable-developer!}))
    (log/info "Energy items initialized: energy_unit, developer_portable"))))
