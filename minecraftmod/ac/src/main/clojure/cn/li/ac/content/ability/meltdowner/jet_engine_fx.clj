(ns cn.li.ac.content.ability.meltdowner.jet-engine-fx
  (:require [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.client.effect-controller :as vfx-level]))

(def ^:private spec
  (arc-beam/build-spec
    {:effect-id :jet-engine
     :lifecycle :transient
     :initial-state (fn [] {})
     :channels {:start {:topic :jet-engine/fx-start}
                :update {:topic :jet-engine/fx-update}
                :end {:topic :jet-engine/fx-end}
                :trigger-start {:topic :jet-engine/fx-trigger-start}
                :trigger-update {:topic :jet-engine/fx-trigger-update}
                :trigger-end {:topic :jet-engine/fx-trigger-end}}}))

(arc-beam/def-arc-beam-fx :jet-engine)

;; Mirrors impl/jet_engine.clj's private trigger-ttl — the trigger phase's
;; fixed lifetime in ticks, used to normalize :ttl into a fade-out ratio.
(def ^:private trigger-ttl 20)

(defn flash-alpha
  "Screen-flash intensity (0-255, capped at 85) for `player-uuid`'s
  currently-triggering jet-engine phase, if any; 0 when idle. This used to
  be computed inline in build-plan as an inert {:type :screen-flash} op —
  the world-space :ops renderer only understands :kind (:line/:quad/
  :plasma-body), so it was silently dropped every frame. The real consumer
  is the 2D screen overlay (reactive-hud/build-snapshot -> Presentation
  HUD's :skill-flash-screen), which has no notion of :ops at all.

  vfx-core :transient migration (docs/04-systems/COMBAT_VFX_PLATFORM_GAPS.md
  E section): this is called from outside any build-plan-fn/transform-fn
  sample callback (reactive_hud.clj builds the HUD snapshot on its own
  schedule), so effect_controller.clj's *sample-state* binding is never
  active here -- fx-snapshot's owner-map scan would silently fall back to
  vfx-core's core/instance-for-effect, an arbitrary \"first instance of
  :jet-engine\" pick, once more than one player can have a live instance at
  once. instance-for-owner replaces that with an exact lookup, since this
  function already receives the one player-uuid it actually cares about.
  A nil player-uuid used to match ANY triggering instance (a defensive
  fallback for a caller that didn't know its own uuid) -- there is no
  owner-scoped equivalent of that under :transient, so a nil player-uuid
  now just reports 0."
  [player-uuid]
  (let [state (when player-uuid
                (vfx-level/instance-for-owner :jet-engine (str player-uuid) :level))
        ttl (long (or (:ttl state) 0))]
    (if (and (= :triggering (:phase state)) (pos? ttl))
      (min 85 (int (* 220 (/ (double ttl) (double trigger-ttl)))))
      0)))
