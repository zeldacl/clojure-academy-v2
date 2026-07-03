(ns cn.li.mcmod.platform.world
  "World operations via Framework function map — pure relay layer, no MC dependencies."
  (:require [cn.li.mcmod.framework :as fw]))

(def world-ops-keys
  #{:world-get-tile-entity :world-get-block-state :world-set-block :world-remove-block
    :world-break-block :world-place-block-by-id :world-is-chunk-loaded?
    :world-get-day-time :world-get-dimension-id :world-server-session-id
    :world-get-players :world-is-raining :world-is-client-side :world-can-see-sky})

(def block-state-keys
  #{:block-state-is-air :block-state-get-block :block-state-get-state-definition
    :block-state-get-property :block-state-set-property})

(defn install-world-ops!
  [ops-map _label]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :world-ops] ops-map)) nil)

(defn world-ops-available? [] (boolean (get-in @(fw/fw-atom) [:platform :world-ops])))
(defn current-ops          [] (get-in @(fw/fw-atom) [:platform :world-ops]))

(defn- call [k & args] (when-let [f (get (current-ops) k)] (apply f args)))

;; World wrappers — callers use *-suffixed names
(defn world-get-tile-entity*    [w pos]           (call :world-get-tile-entity w pos))
(defn world-get-block-state*    [w pos]           (call :world-get-block-state w pos))
(defn world-set-block*          [w pos s fl]      (call :world-set-block w pos s fl))
(defn world-remove-block*       [w pos]           (call :world-remove-block w pos))
(defn world-break-block*        [w pos drop?]     (call :world-break-block w pos drop?))
(defn world-place-block-by-id*  [w id pos fl]     (call :world-place-block-by-id w id pos fl))
(defn world-is-chunk-loaded?*   [w cx cz]         (call :world-is-chunk-loaded? w cx cz))
(defn world-get-day-time*       [w]               (call :world-get-day-time w))
(defn world-get-dimension-id*   [w]               (call :world-get-dimension-id w))
(defn world-server-session-id*  [w]               (call :world-server-session-id w))
(defn world-get-players*        [w]               (call :world-get-players w))
(defn world-is-raining*         [w]               (call :world-is-raining w))
(defn world-is-client-side*     [w]               (call :world-is-client-side w))
(defn world-can-see-sky*        [w pos]           (call :world-can-see-sky w pos))

;; BlockState wrappers
(defn block-state-is-air               [bs]       (call :block-state-is-air bs))
(defn block-state-get-block            [bs]       (call :block-state-get-block bs))
(defn block-state-get-state-definition [bs]       (call :block-state-get-state-definition bs))
(defn block-state-get-property         [bs sd pn] (call :block-state-get-property bs sd pn))
(defn block-state-set-property         [bs p v]   (call :block-state-set-property bs p v))

;; Backward-compatible alias
(defn block-state-is-air? [bs] (block-state-is-air bs))

(defn block-to-chunk-coord [block-coord] (bit-shift-right block-coord 4))
(defn is-chunk-loaded-at-block? [world x z]
  (let [cx (block-to-chunk-coord x) cz (block-to-chunk-coord z)]
    (call :world-is-chunk-loaded? world cx cz)))
