(ns cn.li.forge1201.client.ability-gui
  "Provides screen-facing request functions for skill learn/level-up/preset edits."
  (:require [cn.li.mcmod.platform.ability-lifecycle :as ability-runtime]
            [cn.li.mcmod.util.log :as log]))

(defn learn-skill! [skill-id cb]
  (ability-runtime/client-req-learn-skill! skill-id nil cb))

(defn level-up! [cb]
  (ability-runtime/client-req-level-up! cb))

(defn set-activated! [v cb]
  (ability-runtime/client-req-set-activated! v cb))

(defn set-preset-slot! [preset-idx key-idx cat-id ctrl-id cb]
  (ability-runtime/client-req-set-preset-slot! preset-idx key-idx cat-id ctrl-id cb))

(defn switch-preset! [preset-idx cb]
  (ability-runtime/client-req-switch-preset! preset-idx cb))

(defn init! []
  (log/info "Ability GUI bridge initialized"))
