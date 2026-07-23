(ns cn.li.ac.content.ability.electromaster.railgun-fx
  (:require [cn.li.ac.ability.client.effects.beam-ops :as beam-ops]
            [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]))

(def ^:private spec
  (arc-beam/build-spec
    {:effect-id :railgun-shot
     :initial-state (fn [] {:beam-effects {} :charging {}})
     :channels {:shot {:topic :railgun/fx-shot}
                :reflect {:topic :railgun/fx-reflect}
                ;; Idle-gating markers only — see impl/railgun_shot.clj and
                ;; content/railgun.clj's send-charge-start!/-update!/-end!.
                ;; The actual charge-hand visual keeps coming from
                ;; client-runtime/railgun-charge-visual-state.
                :charge-start {:topic :railgun/fx-charge-start}
                :charge-update {:topic :railgun/fx-charge-update}
                :charge-end {:topic :railgun/fx-charge-end}}}))

(arc-beam/def-arc-beam-fx :railgun-shot)