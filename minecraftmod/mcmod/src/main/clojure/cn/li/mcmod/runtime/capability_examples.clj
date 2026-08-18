(ns cn.li.mcmod.runtime.capability-examples
  "Neutral bottom-layer request examples; platform adapters fill batches/results.")

(def query-examples
  {:owner/snapshot {:capability :owner/snapshot :world-id "world-id" :owner "owner-id"}
   :item/held {:capability :item/held :world-id "world-id" :source :main-hand}
   :raycast {:capability :raycast :world-id "world-id"
             :origin [0.0 64.0 0.0] :direction [0.0 0.0 1.0] :distance 50.0}
   :entity/select {:capability :entity/select :world-id "world-id"
                   :shape {:type :sphere :center [0.0 64.0 0.0] :radius 4.0}
                   :projection [:id :type :position] :limit 8}
   :block/select {:capability :block/select :world-id "world-id"
                  :shape {:type :segment :start [0 64 0] :end [0 64 50]}
                  :projection [:position :hardness] :limit 4096}
   :state/read {:capability :state/read :world-id "world-id"
                :paths [[:resources :cp] [:resources :overload]]}})

(def action-examples
  {:inventory/consume {:capability :inventory/consume :world-id "world-id"
                        :source :main-hand :count 1}
   :entity/damage {:capability :entity/damage :world-id "world-id"
                   :entries [{:target "entity-id" :amount 60.0 :type :generic}]}
   :entity/impulse {:capability :entity/impulse :world-id "world-id"
                    :entries [{:target "entity-id" :vector [0.0 0.5 0.0]}]}
   :entity/spawn {:capability :entity/spawn :world-id "world-id"
                  :entity-type "generic" :position [0.0 64.0 0.0]}
   :world/sound {:capability :world/sound :world-id "world-id"
                 :sound-id "generic" :position [0.0 64.0 0.0]}})
