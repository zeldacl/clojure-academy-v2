(ns cn.li.mcmod.platform.player-motion
  "Minecraft-free relay for neutral player motion operations."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.framework.platform :as platform]))

(defn- framework [] (fw/fw-atom))
(defn current [] (when-let [a (framework)] (platform/get-adapter a :player-motion)))
(defn available? [] (boolean (current)))

(defn- call [op & args]
  (let [a (framework)]
    (when-not a
      (throw (ex-info "Framework is not installed" {:operation op})))
    (apply platform/call-adapter a :player-motion op args)))

(defn dismount-riding! [owner]
  (boolean (call :dismount-riding! (str owner))))

(defn set-velocity! [owner x y z]
  (boolean (call :set-velocity! (str owner) (double x) (double y) (double z))))

(defn get-velocity [owner]
  (call :get-velocity (str owner)))

(defn set-on-ground! [owner on-ground?]
  (boolean (call :set-on-ground! (str owner) (boolean on-ground?))))

(defn on-ground? [owner]
  (boolean (call :is-on-ground? (str owner))))

(defn can-fly? [owner]
  (boolean (call :can-fly? (str owner))))

(defn set-can-fly! [owner can-fly?]
  (boolean (call :set-can-fly! (str owner) (boolean can-fly?))))
