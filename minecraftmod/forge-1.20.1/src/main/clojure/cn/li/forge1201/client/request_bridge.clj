(ns cn.li.forge1201.client.request-bridge
  "Provides screen-facing request functions for runtime operations."
  (:require [cn.li.mcmod.platform.power-runtime :as power-runtime]
            [cn.li.mcmod.util.log :as log]))

(defn learn-skill! [skill-id cb]
  (power-runtime/client-req-learn-skill! skill-id nil cb))

(defn learn-node! [node-id cb]
  (power-runtime/client-req-learn-skill! node-id nil cb))

(defn level-up! [cb]
  (power-runtime/client-req-level-up! cb))

(defn set-activated! [v cb]
  (power-runtime/client-req-set-activated! v cb))

(defn set-preset-slot! [preset-idx key-idx cat-id ctrl-id cb]
  (power-runtime/client-req-set-preset-slot! preset-idx key-idx cat-id ctrl-id cb))

(defn switch-preset! [preset-idx cb]
  (power-runtime/client-req-switch-preset! preset-idx cb))

(defn init! []
  (log/info "Client request bridge initialized"))