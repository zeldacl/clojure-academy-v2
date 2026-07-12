(ns cn.li.ac.content.ability.teleporter.flashing-fx
  (:require [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]))

(defn- preview-to-payload [_ctx-id _channel p]
  {:to-x (:to-x p) :to-y (:to-y p) :to-z (:to-z p)})

(def ^:private spec
  (arc-beam/build-spec
    {:effect-id :flashing
     :initial-state (fn [] {:fx-state {}})
     :channels {:state-start {:topic :flashing/fx-state-start :mode :state-start}
                :preview-start {:topic :flashing/fx-preview-start :mode :preview-start :level-payload preview-to-payload}
                :preview-update {:topic :flashing/fx-preview-update :mode :preview-update :level-payload preview-to-payload}
                :preview-end {:topic :flashing/fx-preview-end :mode :preview-end}
                :perform {:topic :flashing/fx-perform :mode :perform
                          :level-payload (fn [_c _ch p]
                                           (merge (preview-to-payload _c _ch p)
                                                  {:from-x (:from-x p) :from-y (:from-y p) :from-z (:from-z p)}))}
                :state-end {:topic :flashing/fx-state-end :mode :state-end}}}))

(arc-beam/def-arc-beam-fx :flashing)
