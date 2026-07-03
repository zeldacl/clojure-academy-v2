(ns cn.li.mcmod.platform.be
  "Block entity operations via Framework function map.

   BE ops stored at [:platform :be-ops] as {:be-get-level fn, :be-get-world fn, ...}."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.platform.world :as world]))

(def be-ops-keys
  #{:be-get-level :be-get-world :be-get-custom-state :be-set-custom-state!
    :be-get-block-id :be-get-tile-id :be-set-changed! :be-get-fluid-height})

(defn install-be-ops!
  [ops-map _label]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :be-ops] ops-map)) nil)

(defn be-ops-available? [] (boolean (get-in @(fw/fw-atom) [:platform :be-ops])))
(defn call-with-be-ops [ops f] (f ops))

(defn- call-log [level & xs]
  (case level
    :error (apply log/error xs)
    :warn  (apply log/warn xs)
    :info  (apply log/info xs)
    nil))

(defn- be-op [k & args]
  (when-let [f (get-in @(fw/fw-atom) [:platform :be-ops k])]
    (apply f args)))

(defn be-get-world-safe [be]
  (or (try (be-op :be-get-level be) (catch Exception _ nil))
      (try (be-op :be-get-world be) (catch Exception _ nil))))

(defn get-block-entity [w block-pos]
  (try
    (world/world-get-tile-entity* w block-pos)
    (catch Exception e
      (call-log :warn "get-block-entity failed:" (ex-message e))
      nil)))

(defn get-custom-state [be]
  (when be
    (try (be-op :be-get-custom-state be)
         (catch Exception e
           (call-log :warn "get-custom-state failed:" (ex-message e)) nil))))

(defn set-custom-state! [be state]
  (when be
    (try
      (be-op :be-set-custom-state! be state)
      (be-op :be-set-changed! be)
      (catch Exception e
        (call-log :error "set-custom-state! failed:" (ex-message e))))))

(defn be-get-block-id [be]
  (when be
    (try (be-op :be-get-block-id be)
         (catch Exception e
           (call-log :warn "be-get-block-id failed:" (ex-message e)) nil))))

(defn get-block-id [be]
  (when be
    (try (be-op :be-get-block-id be)
         (catch Exception e
           (call-log :warn "get-block-id failed:" (ex-message e)) nil))))

(defn get-tile-id [be]
  (when be
    (try (be-op :be-get-tile-id be)
         (catch Exception e
           (call-log :warn "get-tile-id failed:" (ex-message e)) nil))))

(defn set-changed! [be]
  (when be
    (try (be-op :be-set-changed! be)
         (catch Exception e
           (call-log :warn "set-changed! failed:" (ex-message e)) nil))))

(defn get-fluid-height [be]
  (when be
    (try (or (be-op :be-get-fluid-height be) 0.0)
         (catch Exception _ 0.0))))
