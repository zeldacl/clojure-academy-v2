(ns cn.li.ac.content.ability.electromaster.railgun-fx
  (:require [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

;; Plain registry id, not a namespaced resource path: the platform entity
;; registries (ModEntities) are keyed by the bare registry name, and the
;; spawner resolves the EntityType with that exact string.
(defn- presentation-owner [ctx-id owner-uuid]
  (str "railgun-charge/" (or owner-uuid "unknown") "/" ctx-id))

;; Tracks the enhanced world-anchored charge glow so it can be removed as soon
;; as charging ends. The separate hand animation remains a full 1.6-second
;; one-shot, matching the original RailgunHandEffect.
;;
;; Entries are keyed by ctx-id and normally dropped by on-charge-end!, but that
;; message does not always arrive — a caster who disconnects mid-charge never
;; sends one. The entity itself is fine either way (life-ticks 32 disposes it),
;; so a stranded entry is only bookkeeping; prune it by age so the map cannot
;; grow for a whole session.
(defonce ^:private active-glows* (atom {}))

;; The effect's own life (railgun_charge, entities/all.clj) plus a tick of slack.
(def ^:private glow-life-ms 1650)

(defn reset-charge-glows-for-test! []
  (reset! active-glows* {}))

(defn active-charge-glows
  "Live glow bookkeeping, for tests."
  []
  @active-glows*)

(defn now-ms
  "Wall clock for glow bookkeeping; a var so tests can drive it."
  []
  (System/currentTimeMillis))

(defn- prune-stale [glows now-ms]
  (into {}
        (remove (fn [[_ {:keys [spawned-ms]}]]
                  (>= (- (long now-ms) (long (or spawned-ms 0))) glow-life-ms)))
        glows))

(defn- on-charge-start! [ctx-id _channel payload]
  (when-let [owner-uuid (:source-player-id payload)]
    (when-let [spawn! (client-bridge/call-adapter :presentation-spawn-effect!)]
      (let [owner (presentation-owner ctx-id owner-uuid)
            instance-id (spawn! :academy/railgun-charge owner
                                {:material-id 0 :texture-id 0 :count 1
                                 :source-player-id (str owner-uuid)}
                                (System/currentTimeMillis))
            now (now-ms)]
        (swap! active-glows*
               (fn [glows]
                 (assoc (prune-stale glows now)
                        ctx-id {:instance-id instance-id :owner owner :spawned-ms now})))))))

(defn- on-charge-end! [ctx-id _channel _payload]
  (when-let [{:keys [owner]} (get @active-glows* ctx-id)]
    (when-let [clear-owner! (client-bridge/call-adapter :presentation-clear-effect-owner!)]
      (clear-owner! owner))
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
                ;; despawns the world-anchored charge effect (railgun_charge,
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
