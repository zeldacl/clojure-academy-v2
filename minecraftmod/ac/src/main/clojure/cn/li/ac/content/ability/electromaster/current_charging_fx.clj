(ns cn.li.ac.content.ability.electromaster.current-charging-fx
  (:require [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.client.effect-controller :as vfx-level]
            [cn.li.ac.ability.skill-config :as skill-config]))

(def ^:private spec
  (arc-beam/build-spec
    {:effect-id :current-charging
     :lifecycle :transient
     :runtime :level
     :level-initial-state (fn [] {})
     :channels {:start {:topic :current-charging/fx-start :mode :start :targets [:level]}
                :update {:topic :current-charging/fx-update :mode :update :targets [:level]}
                :end {:topic :current-charging/fx-end :mode :end :targets [:level]}}}))

(arc-beam/def-arc-beam-fx :current-charging)

(defn- visual-max-ticks []
  (max 1 (int (or (skill-config/tunable-int :current-charging :charge.visual-max-ticks) 40))))

(def ^:private default-state
  {:active? false :blending? false :is-item false :good? false
   :charge-ticks 0 :charge-ratio 0.0 :target nil :block-pos nil
   :charged 0.0 :started-at-ms 0 :ending-at-ms 0})

;; vfx-core :transient migration (docs/04-systems/COMBAT_VFX_PLATFORM_GAPS.md
;; E section): current-state has no production caller anywhere in the tree
;; (grepped) -- only its own test exercises it -- but it is genuinely
;; tested, so it is migrated for correctness rather than dropped (same
;; policy as plasma_cannon.clj's charge-visual-state).
;;
;; Its old `selector` shape doesn't survive this migration intact: a
;; [:ctx ctx-id] vector selector selected one entry out of the old
;; :states owner-map, but :transient's ctx-id is always nil (see
;; effect_controller.clj's apply-enqueue) and there is no owner-map left to
;; index into -- each instance already IS one owner's state directly. A
;; selector is now treated as an owner (typically a player-uuid, via
;; instance-for-owner, the same lookup plasma_cannon.clj's
;; charge-visual-state uses); no selector falls back to whatever the
;; current sample/first instance resolves to (arc-beam/snapshot), matching
;; the old no-selector branch's own "just give me a plausible one" intent.
(defn current-state [selector]
  (or (if selector
        (vfx-level/instance-for-owner :current-charging (str selector) :level)
        (cn.li.ac.ability.client.fx-templates.arc-beam/snapshot :current-charging))
      default-state))
