(ns cn.li.ac.content.loot
  "Data-driven loot injection declarations."
  (:require [cn.li.mcmod.loot.dsl :as ldsl]
            [cn.li.mcmod.util.log :as log]))

(defonce loot-initialized? (atom false))

(defn init-loot!
  []
  (when (compare-and-set! loot-initialized? false true)
    ;; Example: inject custom item into common dungeon chests.
    ;; Missing target item IDs are skipped safely at runtime in Forge helper.
    (ldsl/defloot {:id "dungeon_skill_book"
                   :target-table "minecraft:chests/simple_dungeon"
                   :item-id "my_mod:electromaster_brain_course"
                   :weight 3
                   :quality 0
                   :min-count 1.0
                   :max-count 1.0})
    (log/info "Loot content initialized")))
