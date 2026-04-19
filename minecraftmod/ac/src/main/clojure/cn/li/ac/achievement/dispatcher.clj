(ns cn.li.ac.achievement.dispatcher
  "AC-side achievement dispatcher (game-concept aware)."
  (:require [cn.li.ac.achievement.registry :as ach-reg]
            [cn.li.ac.achievement.trigger :as ach-trigger]
            [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.state.player :as ps]
            [cn.li.mcmod.util.log :as log]))

(defonce ^:private installed? (atom false))

(defn- player-category
  [uuid]
  (get-in (ps/get-player-state uuid) [:ability-data :category-id]))

(defn- fire-by-trigger!
  [kind payload uuid]
  (doseq [achievement-id (ach-reg/find-by-trigger kind payload)]
    (ach-trigger/trigger-achievement! uuid achievement-id)))

(defn trigger-custom-event!
  "Manually map a custom game event key to one or more achievements."
  [uuid event-id]
  (fire-by-trigger! :custom {:event-id event-id} uuid))

(defn init-dispatcher!
  []
  (when (compare-and-set! installed? false true)
    (evt/subscribe-ability-event!
      evt/EVT-LEVEL-CHANGE
      (fn [{:keys [uuid new-level]}]
        (when-let [category (player-category uuid)]
          (fire-by-trigger! :level-change {:category category :level new-level} uuid))))

    (evt/subscribe-ability-event!
      evt/EVT-SKILL-LEARN
      (fn [{:keys [uuid skill-id]}]
        (fire-by-trigger! :skill-learn {:skill-id skill-id} uuid)))

    (evt/subscribe-ability-event!
      evt/EVT-SKILL-PERFORM
      (fn [{:keys [uuid skill-id]}]
        (fire-by-trigger! :skill-perform {:skill-id skill-id} uuid)))

    (log/info "Achievement dispatcher initialized")))

