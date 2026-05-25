(ns cn.li.ac.ability.service.snapshot
  "Pure map snapshots for AC ability runtime data."
  (:require [cn.li.ac.ability.state.store-contract :as store-contract]))

(defn ability-data->map
  "Serialize ability data for a player to a pure EDN map."
  [uuid]
  (let [s (store-contract/player-ability-store)]
    {:category-id    (store-contract/ability-get-category s uuid)
     :level          (store-contract/ability-get-level s uuid)
     :level-progress (store-contract/ability-get-level-progress s uuid)}))

(defn resource-data->map
  "Serialize resource data for a player to a pure EDN map."
  [uuid]
  (let [s (store-contract/player-ability-store)]
    {:cur-cp           (store-contract/res-get-cur-cp s uuid)
     :max-cp           (store-contract/res-get-max-cp s uuid)
     :cur-overload     (store-contract/res-get-cur-overload s uuid)
     :max-overload     (store-contract/res-get-max-overload s uuid)
     :activated        (store-contract/res-is-activated? s uuid)
     :until-recover    (store-contract/res-get-until-recover s uuid)}))