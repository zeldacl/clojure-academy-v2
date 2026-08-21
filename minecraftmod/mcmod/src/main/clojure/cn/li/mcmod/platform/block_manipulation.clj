(ns cn.li.mcmod.platform.block-manipulation
  (:require [cn.li.mcmod.framework :as fw]))

;; Ability-internal breaking exemption: while the ability runtime is activated
;; (V mode), the platform cancels player block-break events (the "ability
;; mode" interaction lock). Generic beam/terrain propagation permission checks post
;; synthetic BreakEvents of their own, which that lock then cancels — so
;; ability breaking must mark itself and have the lock skip it.
(def ^:private ^ThreadLocal internal-break-flag (ThreadLocal.))

(defn with-internal-break!
  "Run thunk with the ability-internal breaking flag set; the runtime-active
  interaction lock (event-handlers/runtime-active-event?) skips events raised
  while it is set."
  [thunk]
  (let [old (.get internal-break-flag)]
    (.set internal-break-flag Boolean/TRUE)
    (try
      (thunk)
      (finally
        (if (nil? old)
          (.remove internal-break-flag)
          (.set internal-break-flag old))))))

(defn internal-break?
  []
  (boolean (.get internal-break-flag)))

(defn available?
  []
  (boolean (get-in @(fw/fw-atom) [:platform :block-manipulation])))

(defn current
  []
  (get-in @(fw/fw-atom) [:platform :block-manipulation]))

(defn- call
  [k & args]
  (let [ops (current)
        f (get ops k)]
    (when-not f
      (throw (ex-info "Required block-manipulation operation is not installed"
                      {:operation k :installed (keys ops)})))
    (apply f args)))

(defn install-destroy-gate!
  [pred]
  (when-let [fw-atom (fw/fw-atom)]
    (swap! fw-atom assoc-in [:platform :block-destroy-gate] pred))
  nil)

(defn destroy-allowed?
  []
  (if-let [pred (get-in @(fw/fw-atom) [:platform :block-destroy-gate])]
    (boolean (pred))
    true))

(defn break-block!
  ([player-id world-id x y z drop?]
   (when (destroy-allowed?)
     (with-internal-break!
       (fn [] (call :break-block! player-id world-id x y z drop?)))))
  ([player-id world-id x y z drop? fortune-level]
   (when (destroy-allowed?)
     (with-internal-break!
       (fn [] (call :break-block! player-id world-id x y z drop? fortune-level))))))

(defn set-block!
  [world-id x y z block-id]
  (call :set-block! world-id x y z block-id))

(defn get-block
  [world-id x y z]
  (call :get-block world-id x y z))

(defn get-block-hardness
  [world-id x y z]
  (call :get-block-hardness world-id x y z))

(defn block-collidable?
  [world-id x y z]
  (boolean (call :block-collidable? world-id x y z)))

(defn can-break-block?
  [player-id world-id x y z]
  (with-internal-break!
    (fn [] (call :can-break-block? player-id world-id x y z))))

(defn requires-high-tier-tool?
  [world-id x y z]
  (boolean (call :requires-high-tier-tool? world-id x y z)))

(defn find-blocks-in-line
  [world-id x1 y1 z1 dx dy dz max-distance]
  (call :find-blocks-in-line world-id x1 y1 z1 dx dy dz max-distance))

(defn liquid-block?
  [world-id x y z]
  (call :liquid-block? world-id x y z))

(defn farmland-block?
  [world-id x y z]
  (call :farmland-block? world-id x y z))
