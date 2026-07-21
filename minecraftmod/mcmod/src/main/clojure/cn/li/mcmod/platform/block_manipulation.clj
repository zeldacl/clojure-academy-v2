(ns cn.li.mcmod.platform.block-manipulation
  "Block manipulation operations via Framework function map.

   Impl stored at [:platform :block-manipulation]."
  (:require [cn.li.mcmod.framework :as fw]))

(def block-manipulation-keys
  #{:break-block! :set-block! :get-block :get-block-hardness
    :can-break-block? :find-blocks-in-line :liquid-block? :farmland-block?})

(defn install-block-manipulation!
  [impl _label]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :block-manipulation] impl)) nil)

(defn available? [] (boolean (get-in @(fw/fw-atom) [:platform :block-manipulation])))
(defn current   [] (get-in @(fw/fw-atom) [:platform :block-manipulation]))
(defn call-with-runtime [rt f] (f rt))

(defn- call [k & args]
  (when-let [f (get (current) k)]
    (apply f args)))

(defn install-destroy-gate!
  "Install a 0-arg predicate (fn [] boolean) consulted before any ability
   break-block!* call — content modules (e.g. AC's global 'Destroy blocks'
   setting) register this without the platform adapter needing to know about
   content-layer config. Absent = destruction allowed (matches prior
   ungated behavior)."
  [pred]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :block-destroy-gate] pred))
  nil)

(defn destroy-allowed?
  []
  (if-let [pred (get-in @(fw/fw-atom) [:platform :block-destroy-gate])]
    (boolean (pred))
    true))

(defn break-block!*
  ([player-id world-id x y z drop?]
   (when (destroy-allowed?) (call :break-block! player-id world-id x y z drop?)))
  ([player-id world-id x y z drop? fortune-level]
   (when (destroy-allowed?) (call :break-block! player-id world-id x y z drop? fortune-level))))
(defn set-block!*            [world-id x y z block-id]                    (call :set-block! world-id x y z block-id))
(defn get-block*             [world-id x y z]                             (call :get-block world-id x y z))
(defn get-block-hardness*    [world-id x y z]                             (call :get-block-hardness world-id x y z))
(defn can-break-block?*      [player-id world-id x y z]                   (call :can-break-block? player-id world-id x y z))
(defn find-blocks-in-line*   [world-id x1 y1 z1 dx dy dz max-distance]    (call :find-blocks-in-line world-id x1 y1 z1 dx dy dz max-distance))
(defn liquid-block?*         [world-id x y z]                             (call :liquid-block? world-id x y z))
(defn farmland-block?*       [world-id x y z]                             (call :farmland-block? world-id x y z))
(defn break-block-with-fortune!* [player-id world-id x y z drop? fortune-level]
  (when (destroy-allowed?) (call :break-block! player-id world-id x y z drop? fortune-level)))
