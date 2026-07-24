(ns cn.li.ac.content.ability.electromaster.railgun-fx
  (:require [cn.li.ac.ability.client.effects.beam-ops :as beam-ops]
            [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(def ^:private charge-glow-effect-id (modid/namespaced-path "railgun_charge"))

;; ctx-id -> spawned railgun_charge entity uuid, so fx-charge-end can despawn
;; the exact entity fx-charge-start spawned on THIS client. Deliberately
;; outside the arc-beam :level state store (that one is pure/replayable;
;; spawning a real client entity is a side effect that must happen exactly
;; once per start/end pair, not be replayed on state recompute).
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
  (when-let [entity-uuid (get @active-glows* ctx-id)]
    (client-bridge/run-client-effect!
      :mcmod/remove-local-scripted-effect
      {:entity-uuid entity-uuid})
    (swap! active-glows* dissoc ctx-id)))

(def ^:private spec
  (arc-beam/build-spec
    {:effect-id :railgun-shot
     :initial-state (fn [] {:beam-effects {} :charging {}})
     :channels {:shot {:topic :railgun/fx-shot}
                :reflect {:topic :railgun/fx-reflect}
                ;; :level target is the idle-gating marker — see
                ;; impl/railgun_shot.clj and content/railgun.clj's
                ;; send-charge-start!/-update!/-end!. :immediate spawns/
                ;; despawns the world-anchored charge glow (railgun_charge,
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