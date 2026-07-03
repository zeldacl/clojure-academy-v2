(ns cn.li.mcmod.platform.config-persist
  "Config persistence via Framework function map.

   Persist fn stored at [:platform :config-persist :persist!]."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.config.registry :as config-reg]))

(defn install-config-persist-op!
  [persist-fn _label]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :config-persist :persist!] persist-fn)) nil)

(defn config-persist-available?
  []
  (boolean (get-in @(fw/fw-atom) [:platform :config-persist :persist!])))

(defn persist-config-value!
  "Persist one config entry through the platform adapter when installed.
  Always updates the in-memory registry for immediate getter visibility."
  [domain key value]
  (config-reg/set-config-values! domain {key value})
  (when-let [persist-fn (get-in @(fw/fw-atom) [:platform :config-persist :persist!])]
    (persist-fn domain key value)))

(defn reset-config-persist-for-test!
  []
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :config-persist :persist!] nil)) nil)
