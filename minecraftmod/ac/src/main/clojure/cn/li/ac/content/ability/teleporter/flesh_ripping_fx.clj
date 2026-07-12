(ns cn.li.ac.content.ability.teleporter.flesh-ripping-fx
  (:require [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]))

(defn- flesh-target-payload [_ctx-id _channel p]
  {:target-x (:target-x p)
   :target-y (:target-y p)
   :target-z (:target-z p)
   :hit? (:hit? p)
   :target-uuid (:target-uuid p)})

(def ^:private spec
  (arc-beam/build-spec
    {:effect-id :flesh-ripping
     :initial-state (fn [] {:fx-state {}})
     :channels {:start {:topic :flesh-ripping/fx-start :mode :start}
                :update {:topic :flesh-ripping/fx-update :mode :update :level-payload flesh-target-payload}
                :perform {:topic :flesh-ripping/fx-perform :mode :perform :level-payload flesh-target-payload}
                :end {:topic :flesh-ripping/fx-end :mode :end}}}))

(arc-beam/def-arc-beam-fx :flesh-ripping)
