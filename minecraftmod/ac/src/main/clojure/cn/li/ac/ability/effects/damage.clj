(ns cn.li.ac.ability.effects.damage
  (:require [cn.li.ac.ability.util.balance :as bal]
            [cn.li.ac.ability.effects.world :as world-effects]
            [cn.li.mcmod.framework :as fw]))

(defn available?
  []
  (boolean (get-in @(fw/fw-atom) [:platform :entity-damage])))

(defn current
  []
  (get-in @(fw/fw-atom) [:platform :entity-damage]))

(defn install-pvp-gate!
  [pred]
  (when-let [fw-atom (fw/fw-atom)]
    (swap! fw-atom assoc-in [:platform :entity-damage-pvp-gate] pred))
  nil)

(defn pvp-allowed?
  []
  (if-let [pred (get-in @(fw/fw-atom) [:platform :entity-damage-pvp-gate])]
    (boolean (pred))
    true))

(defn apply-direct-damage!
  [world-id entity-uuid damage source-type]
  (when-let [f (get-in @(fw/fw-atom) [:platform :entity-damage :apply-direct-damage!])]
    (f world-id entity-uuid damage source-type)))

(defn apply-aoe-damage!
  [world-id x y z radius damage source-type falloff?]
  (when-let [f (get-in @(fw/fw-atom) [:platform :entity-damage :apply-aoe-damage!])]
    (f world-id x y z radius damage source-type falloff?)))

(defn apply-reflection-damage!
  [world-id entity-uuid damage source-type reflection-count max-reflections]
  (when-let [f (get-in @(fw/fw-atom) [:platform :entity-damage :apply-reflection-damage!])]
    (f world-id entity-uuid damage source-type reflection-count max-reflections)))

(defn execute-damage-direct!
  [evt {:keys [target amount damage-type]}]
  (when-let [uuid (or (when (map? target) (:uuid target))
                      (when (keyword? target) (get evt target))
                      (when (string? target) target))]
    (when (available?)
      (apply-direct-damage!
        (:world-id evt)
        uuid
        (double (or amount 0.0))
        (or damage-type :generic))))
  evt)

(defn execute-damage-aoe!
  [evt {:keys [center radius amount damage-type exclude]}]
  (when (and (world-effects/available?) (available?))
    (let [center* (or (when (map? center) center) (get evt center))
          world-id (:world-id evt)
          victims (world-effects/find-entities-in-radius
                                                         world-id
                                                         (double (:x center*))
                                                         (double (:y center*))
                                                         (double (:z center*))
                                                         (double radius))
          excluded (set (concat [(:player-id evt)] (or exclude [])))]
      (doseq [{:keys [uuid x y z]} victims]
        (when-not (contains? excluded uuid)
          (let [dx (- (double x) (double (:x center*)))
                dy (- (double y) (double (:y center*)))
                dz (- (double z) (double (:z center*)))
                dist (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))
                final (* (double amount) (bal/falloff-linear dist radius))]
            (when (> final 0.0)
              (apply-direct-damage!
                world-id uuid final
                (or damage-type :generic))))))))
  evt)

