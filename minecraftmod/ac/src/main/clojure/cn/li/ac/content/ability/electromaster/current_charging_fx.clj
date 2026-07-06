(ns cn.li.ac.content.ability.electromaster.current-charging-fx
  (:require [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(def ^:private spec
  (arc-beam/build-spec
    {:effect-id :current-charging
     :runtime :hand
     :initial-state (fn [] {:states {}})
     :channels {:start {:topic :current-charging/fx-start :mode :start :targets [:hand]}
                :update {:topic :current-charging/fx-update :mode :update :targets [:hand]}
                :end {:topic :current-charging/fx-end :mode :end :targets [:hand]}}}))

(defn init! [] (fx-spec/register! spec) nil)

(defn current-charging-fx-snapshot [] (arc-beam/snapshot :current-charging))

(defn reset-current-charging-fx-for-test! [] (arc-beam/reset-for-test! :current-charging) nil)

(defn clear-current-charging-owner! [owner-key] (arc-beam/clear-owner! :current-charging owner-key) nil)

(defn- visual-max-ticks []
  (max 1 (int (or (skill-config/tunable-int :current-charging :charge.visual-max-ticks) 40))))

(def ^:private default-state
  {:active? false :blending? false :is-item false :good? false
   :charge-ticks 0 :charge-ratio 0.0 :target nil :block-pos nil
   :charged 0.0 :started-at-ms 0 :ending-at-ms 0})

(defn- current-store []
  (let [store (current-charging-fx-snapshot)]
    (if (contains? store :states) store (arc-beam/initial-state :current-charging))))

(defn- state-for-selector [store selector]
  (let [states (:states store)]
    (or (cond
          (vector? selector) (get states selector)
          (some? selector)
          (some (fn [[_ st]]
                  (when (and (:source-player-id st)
                             (= (str selector) (str (:source-player-id st))))
                    st))
                states)
          :else
          (or (some (fn [[_ st]] (when (:active? st) st)) states)
              (some (fn [[_ st]] (when (:blending? st) st)) states)))
        default-state)))

(defn current-state [selector]
  (state-for-selector (current-store) selector))
