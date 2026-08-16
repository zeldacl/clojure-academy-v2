(ns cn.li.ac.ability.registry.catalog-merge
  "Pure merge rules for player-facing skill metadata.

   Combat Core metadata wins on duplicate ids; legacy metadata remains only
   for non-combat generic skills."
  (:require [clojure.set :as set]))

(defn merge-skill-specs
  [combat-specs legacy-specs]
  (let [combat (vec combat-specs)
        ids (set (map :id combat))]
    (into combat (remove #(contains? ids (:id %)) legacy-specs))))
