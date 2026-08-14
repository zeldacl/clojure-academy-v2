(ns cn.li.ac.content.ability.electromaster.railgun-fx
  (:require [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

;; Tracks the enhanced world-anchored charge glow so it can be removed as soon
;; as charging ends. The separate hand animation remains a full 1.6-second
;; one-shot, matching the original RailgunHandEffect.
;;
;; Entries are keyed by ctx-id and normally dropped by on-charge-end!, but that
;; message does not always arrive — a caster who disconnects mid-charge never
;; sends one. The entity itself is fine either way (life-ticks 32 disposes it),
;; so a stranded entry is only bookkeeping; prune it by age so the map cannot
;; grow for a whole session.
(defn- on-charge-start! [ctx-id _channel payload]
  (client-bridge/presentation-spawn-effect!
    :railgun-charge [:ctx ctx-id] payload (client-bridge/game-time-ms)))

(defn- on-charge-end! [ctx-id _channel _payload]
  (client-bridge/presentation-clear-effect-owner! [:ctx ctx-id]))

(def ^:private spec
  (arc-beam/build-spec
    {:effect-id :railgun-shot
     :initial-state (fn [] {:beam-effects {} :charging {}})
     :channels {:shot {:topic :railgun/fx-shot}
                :reflect {:topic :railgun/fx-reflect}
                ;; :level target is the idle-gating marker — see
                ;; impl/railgun_shot.clj and content/railgun.clj's
                ;; send-charge-start!/-update!/-end!. :immediate spawns/
                ;; entities/all.clj), keyed by :source-player-id so every
                ;; recipient's client anchors it to the CASTER, not
                ;; themselves — this is what makes it visible to bystanders,
                ;; unlike the old hand-runtime-only charge-hand-ops path
                ;; (still driven by client-runtime/railgun-charge-visual-state
                ;; for the caster's own first-person view).
                :charge-start {:topic :railgun/fx-charge-start
                               :targets [:level :immediate]
                               :immediate-fn on-charge-start!}
                :charge-update {:topic :railgun/fx-charge-update}
                :charge-end {:topic :railgun/fx-charge-end
                             :targets [:level :immediate]
                             :immediate-fn on-charge-end!}}}))

(arc-beam/def-arc-beam-fx :railgun-shot)
