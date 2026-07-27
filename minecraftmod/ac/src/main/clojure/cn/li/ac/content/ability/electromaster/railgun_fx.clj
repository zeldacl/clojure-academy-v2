(ns cn.li.ac.content.ability.electromaster.railgun-fx
  (:require [cn.li.ac.ability.client.effects.beam-ops :as beam-ops]
            [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(def ^:private charge-glow-effect-id (modid/namespaced-path "railgun_charge"))

;; Tracks one-shot charge entities for tests/owner cleanup. The original
;; RailgunHandEffect runs its full 1.6 seconds even if charging ends early.
(defonce ^:private active-glows* (atom {}))

(defn reset-charge-glows-for-test! []
  (reset! active-glows* {}))

(defn- on-charge-start! [ctx-id _channel payload]
  (when-let [owner-uuid (:source-player-id payload)]
    (when-let [entity-uuid (client-bridge/run-client-effect!
                             :mcmod/spawn-scripted-effect-at-player
                             {:effect-id charge-glow-effect-id
                              :owner-uuid (str owner-uuid)})]
      (swap! active-glows* assoc ctx-id entity-uuid))))

(defn- on-charge-end! [ctx-id _channel _payload]
  ;; Do not despawn: RailgunHandEffect is a one-shot animation, not a live
  ;; charge-state indicator. The 32-tick entity lifetime removes it naturally.
  (swap! active-glows* dissoc ctx-id))

(def ^:private spec
  (arc-beam/build-spec
    {:effect-id :railgun-shot
     :initial-state (fn [] {:beam-effects {} :charging {}})
     :channels {:shot {:topic :railgun/fx-shot}
                :reflect {:topic :railgun/fx-reflect}
                ;; :level target is the idle-gating marker — see
                ;; impl/railgun_shot.clj and content/railgun.clj's
                ;; send-charge-start!/-update!/-end!. :immediate spawns the
                ;; self-expiring world-anchored charge effect (railgun_charge,
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
