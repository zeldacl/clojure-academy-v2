(ns cn.li.mcmod.block.events
  "Runtime event helpers for block specs.

  Kept separate from `cn.li.mcmod.block.dsl` so the public DSL surface remains
  focused on declarations, templates, validation, and query helpers."
  (:require [cn.li.mcmod.block.dsl-multiblock :as mb]
            [cn.li.mcmod.util.log :as log]))

(defn handle-right-click
  [block-spec event-data]
  (when-let [handler (or (:on-right-click block-spec)
                         (:on-right-click (:events block-spec)))]
    (handler event-data)))

(defn handle-break
  [block-spec event-data]
  (when-let [handler (or (:on-break block-spec)
                         (:on-break (:events block-spec)))]
    (handler event-data)))

(defn handle-place
  [block-spec event-data]
  (when-let [handler (or (:on-place block-spec)
                         (:on-place (:events block-spec)))]
    (handler event-data)))

(defn handle-multi-block-break
  [block-spec event-data]
  (let [multi-block (:multi-block block-spec)
        events (:events block-spec)]
    (when (:multi-block? multi-block)
      (log/info "Breaking multi-block structure:" (:id block-spec))
      (when-let [handler (:on-multi-block-break events)]
        (handler event-data))
      (let [origin (or (:multi-block-origin multi-block) {:x 0 :y 0 :z 0})
            positions (if-let [custom-pos (:multi-block-positions multi-block)]
                        (mb/calculate-multi-block-positions custom-pos origin)
                        (mb/calculate-multi-block-positions (:multi-block-size multi-block)
                                                            origin))]
        {:should-break-all true
         :positions positions}))))
