(ns cn.li.mcmod.platform.be
  "Platform-neutral utilities for interacting with ScriptedBlockEntity."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.platform.runtime :as prt]
            [cn.li.mcmod.platform.world :as world]))

(defn- call-log [level & xs]
  (case level
    :error (apply log/error xs)
    :warn (apply log/warn xs)
    :info (apply log/info xs)
    nil))

(defprotocol IBlockEntity
  (be-get-level [this])
  (be-get-world [this])
  (be-get-custom-state [this])
  (be-set-custom-state! [this state])
  (be-get-block-id [this])
  (be-set-changed! [this]))

(def ^:private ^:dynamic *be-ops* nil)

(defn install-be-ops!
  "Install block-entity operation fns map. Keys match install-be-fns-only! in mc1201."
  [ops-map label]
  (prt/install-impl! #'*be-ops* ops-map (or label "be-ops")))

(defn be-ops-available? [] (prt/impl-available? #'*be-ops*))
(defn call-with-be-ops [ops f] (binding [*be-ops* ops] (f)))

(defn- be-op [k & args]
  (when-let [ops *be-ops*]
    (when-let [f (get ops k)]
      (apply f args))))

(defn be-get-world-safe [be]
  (or (try (if-let [f (get *be-ops* :be-get-level)]
            (f be)
            (be-get-level be))
           (catch Exception _ nil))
      (try (if-let [f (get *be-ops* :be-get-world)]
             (f be)
             (be-get-world be))
           (catch Exception _ nil))))

(defn get-block-entity [w block-pos]
  (try
    (world/world-get-tile-entity* w block-pos)
    (catch Exception e
      (call-log :warn "get-block-entity failed:" (ex-message e))
      nil)))

(defn get-custom-state [be]
  (when be
    (try
      (or (be-op :be-get-custom-state be)
          (be-get-custom-state be))
      (catch Exception e
        (call-log :warn "get-custom-state failed:" (ex-message e))
        nil))))

(defn set-custom-state! [be state]
  (when be
    (try
      (if-let [f (get *be-ops* :be-set-custom-state!)]
        (f be state)
        (be-set-custom-state! be state))
      (if-let [f (get *be-ops* :be-set-changed!)]
        (f be)
        (be-set-changed! be))
      (catch Exception e
        (call-log :error "set-custom-state! failed:" (ex-message e))))))

(defn get-block-id [be]
  (when be
    (try
      (or (be-op :be-get-block-id be)
          (be-get-block-id be))
      (catch Exception e
        (call-log :warn "get-block-id failed:" (ex-message e))
        nil))))

(defn set-changed! [be]
  (when be
    (try
      (or (be-op :be-set-changed! be)
          (be-set-changed! be))
      (catch Exception e
        (call-log :warn "set-changed! failed:" (ex-message e))
        nil))))
