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
        ;; Recipes yield up to 4; 1.21+ ItemStack codec rejects count > maxStackSize.
        {:max-stack-size 64
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
                      ;; Upstream ItemDeveloper#onModelBake (BakedModelForTEISR):
         ;; FP rotate(0,180,0) + scale .3 + translate(.34,-.1,-.1);
         ;; TP rotate(0,180,0) + scale .2;
         ;; ground scale(-.15,-.15,.15) + translate(0,.1,0).
         ;; TransformChain composes T*S*R, so the translation is in whole
         ;; blocks; JSON `display` translation is in 1/16-block units (*16).
         ;; The engine clamps translation to ±5, so FP x lands on 5.0 (0.3125)
         ;; instead of 5.44 — a ~0.03-block offset from upstream.
         ;; The mesh texture has to sit under `textures/item/`: it is sampled from
         ;; an atlas, and every MC version here only scans `textures/block` and
         ;; `textures/item` for one. Upstream kept it beside the OBJ under
         ;; `textures/models/` because its TEISR bound the PNG directly, and the
         ;; other `models/*.png` still do that from block-entity renderers.
         :item-model-3d-obj {:obj-model "models/developer_portable.obj"
                                          :texture "item/developer_portable_3d"
                                          :display {:firstperson_righthand {:rotation [0 180 0] :scale [0.3 0.3 0.3] :translation [5.44 -1.6 -1.6]}
                                                    :firstperson_lefthand {:rotation [0 180 0] :scale [0.3 0.3 0.3] :translation [5.44 -1.6 -1.6]}
                                                    :thirdperson_righthand {:rotation [0 180 0] :scale [0.2 0.2 0.2]}
                                                    :thirdperson_lefthand {:rotation [0 180 0] :scale [0.2 0.2 0.2]}
                                                    :ground {:scale [-0.15 -0.15 0.15] :translation [0 1.6 0]}}}}
         :on-right-click open-portable-developer!}))
    (log/info "Energy items initialized: energy_unit, developer_portable"))))
