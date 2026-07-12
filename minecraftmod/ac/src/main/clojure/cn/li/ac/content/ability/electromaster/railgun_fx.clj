(ns cn.li.ac.content.ability.electromaster.railgun-fx
  (:require [cn.li.ac.ability.client.effects.beam-ops :as beam-ops]
            [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]))

(def ^:private spec
  (arc-beam/build-spec
    {:effect-id :railgun-shot
     :initial-state (fn [] {:beam-effects {}})
     :channels {:shot {:topic :railgun/fx-shot}
                :reflect {:topic :railgun/fx-reflect}}}))

(arc-beam/def-arc-beam-fx :railgun-shot)