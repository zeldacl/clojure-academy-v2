(ns cn.li.forge1201.client.ability-gui
  "Provides screen-facing request functions for skill learn/level-up/preset edits."
  (:require [cn.li.ac.ability.client-api :as api]
            [cn.li.mcmod.util.log :as log]))

(defn learn-skill! [skill-id cb]
  (api/req-learn-skill! skill-id cb))

(defn level-up! [cb]
  (api/req-level-up! cb))

(defn set-activated! [v cb]
  (api/req-set-activated! v cb))

(defn set-preset-slot! [preset-idx key-idx cat-id ctrl-id cb]
  (api/req-set-preset-slot! preset-idx key-idx cat-id ctrl-id cb))

(defn switch-preset! [preset-idx cb]
  (api/req-switch-preset! preset-idx cb))

(defn init! []
  (log/info "Ability GUI bridge initialized"))
