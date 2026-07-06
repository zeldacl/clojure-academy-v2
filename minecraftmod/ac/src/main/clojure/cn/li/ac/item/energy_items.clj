(ns cn.li.ac.item.energy-items
  "Energy-backed item declarations migrated from original AcademyCraft."
  (:require [cn.li.mcmod.item.dsl :as idsl]
            [cn.li.ac.item.developer-portable :as developer-portable]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.ac.item.item-energy-base :as energy-base]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(defonce-guard energy-items-installed?)

(defn- open-portable-developer!
  "Right-click handler for developer_portable item.
  Opens the classic AcademyCraft CGUI developer screen (page_developer.xml)
  with machine panel, skill tree, overlays, and console — the same rich UI
  used by block-based developers."
  [{:keys [player side]}]
  (when (= side :client)
    ;; Build the CGUI screen data (platform-agnostic)
    (let [screen-map (developer-portable/create-screen player)]
      ;; Open via platform CGUI screen host
      (client-bridge/open-screen! {:cgui-root (:cgui screen-map) :title "Portable Developer" :session-id (:session-id screen-map)})))
  {:consume? true})

(defn init-energy-items!
  []
  (with-init-guard energy-items-installed?
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
                      :energy-bandwidth 0.3
                      :battery-type "developer_portable"
                      :item-model-3d-obj {:obj-model "models/developer_portable.obj"
                                          :texture "models/developer_portable"
                                          :display {:firstperson_righthand {:rotation [0 180 0] :scale [0.3 0.3 0.3] :translation [0.34 -0.1 -0.1]}
                                                    :firstperson_lefthand {:rotation [0 180 0] :scale [0.3 0.3 0.3] :translation [0.34 -0.1 -0.1]}
                                                    :thirdperson_righthand {:rotation [0 180 0] :scale [0.2 0.2 0.2]}
                                                    :thirdperson_lefthand {:rotation [0 180 0] :scale [0.2 0.2 0.2]}
                                                    :ground {:scale [-0.15 -0.15 0.15] :translation [0 0.1 0]}}}}
         :on-right-click open-portable-developer!}))
    (log/info "Energy items initialized: energy_unit, developer_portable")))
