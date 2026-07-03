(ns cn.li.mcmod.platform.position
  "Position operations via Framework function map — pure relay layer, no MC dependencies."
  (:require [cn.li.mcmod.framework :as fw]))

(def position-keys #{:pos-x :pos-y :pos-z :create-block-pos :pos-above})

(defn install-position-ops!
  [ops-map _label]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :position-ops] ops-map)) nil)

(defn position-ops-available? [] (boolean (get-in @(fw/fw-atom) [:platform :position-ops])))
(defn current-ops            [] (get-in @(fw/fw-atom) [:platform :position-ops]))

(defn- call [k & args] (when-let [f (get (current-ops) k)] (apply f args)))

(defn pos-x       [p]    (call :pos-x p))
(defn pos-y       [p]    (call :pos-y p))
(defn pos-z       [p]    (call :pos-z p))
(defn pos-above   [p]    (call :pos-above p))

(defn create-block-pos [x y z]
  (if-let [f (get (current-ops) :create-block-pos)]
    (f x y z)
    (throw (ex-info "Position ops not installed" {:key :create-block-pos}))))

(defn factory-initialized? []
  (boolean (get (current-ops) :create-block-pos)))

;; Backward-compatible aliases (used by mcmod/ac layers that expect old protocol names)
(defn position-get-x [this] (pos-x this))
(defn position-get-y [this] (pos-y this))
(defn position-get-z [this] (pos-z this))
(defn position-get-block-pos [this] (call :position-get-block-pos this))
(defn position-get-pos [this] (call :position-get-pos this))
