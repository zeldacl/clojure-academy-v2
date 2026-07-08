(ns cn.li.ac.item.developer-portable-reactive
  "Complete reactive replacement for developer_portable.clj.
   Reuses cn.li.ac.block.developer.panel-reactive's full classic layout
   (left/right panel, console, skill-tree area, overlays) via its shared
   build-runtime! entry point — the same one the block developer screen
   uses — since portable dev needs the identical UI, just standalone-hosted
   with no wireless link and energy synced from the held item each frame
   instead of a tile-entity's server-synced schema."
  (:require [cn.li.ac.ability.client.api :as api]
            [cn.li.ac.ability.client.read-model :as read-model]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.block.developer.panel-reactive :as panel-reactive]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.node :as node]
            [cn.li.mcmod.ui.signal :as sig])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]
           [cn.li.mcmod.ui.node INode]
           [cn.li.mcmod.uipojo.signal ISigO]))

;; ============================================================================
;; Portable container (reused verbatim from old developer_portable.clj)
;; ============================================================================

(def ^:private portable-max-energy 10000.0)
(def ^:private session-ns-prefix "developer.portable")

(defn- get-player-held-stack [player]
  (when player (entity/player-get-main-hand-item-stack player)))

(defn- current-energy-from-held-item [player]
  (let [stack (get-player-held-stack player)]
    (if (and stack (energy/is-energy-item-supported? stack))
      (double (energy/get-item-energy stack)) 0.0)))

(defn- make-portable-on-dev-start [owner]
  (fn [action extra callback]
    (case action
      :learn-skill (api/req-learn-skill! owner (-> extra :skill-id keyword) callback)
      :level-up    (api/req-level-up! owner callback)
      (when callback (callback {:success false :reason "not-available-on-portable"})))))

(defn make-portable-container [player owner]
  (let [player-uuid-str (or (uuid/player-uuid player) "")
        player-name-str (or (entity/player-get-name player) "")]
    {:energy                (atom (current-energy-from-held-item player))
     :max-energy            (atom portable-max-energy)
     :tier                  (atom :portable)
     :is-developing         (atom false)
     :development-progress  (atom 0.0)
     :development-complete? (atom false)
     :structure-valid       (atom true)
     :user-uuid             (atom player-uuid-str)
     :user-name             (atom player-name-str)
     :player                player
     :tile-entity           nil
     :container-type        :portable-developer
     :metadata              (atom {})
     :on-dev-start          (make-portable-on-dev-start owner)}))

;; ============================================================================
;; set-tick! — force a per-frame side-effecting computed-o to actually run
;; (see developer panel-reactive.clj for the fuller writeup).
;; ============================================================================

(defn- pull-o! [_node source] (.sGet ^ISigO source) nil)

(defn- set-tick! [^UiRt rt key computed-sig]
  (let [^INode anchor (rt/node-by-id rt :root)
        b (sig/bind! computed-sig anchor pull-o! (rt/get-dirty-bindings-q rt))]
    (rt/register-binding! rt (.getIdx anchor) b)
    (rt/put-user-signal! rt key b)))

;; ============================================================================
;; Entry point
;; ============================================================================

(defn create-runtime [player]
  (let [session-id (runtime-hooks/require-player-state-session-id session-ns-prefix)
        owner (read-model/canonical-client-owner
                {:client-session-id session-id :player-uuid (uuid/player-uuid player)}
                :skill-tree)
        container (make-portable-container player owner)
        _ (read-model/ensure-player-state! (read-model/owner-key owner :skill-tree))
        r (panel-reactive/build-runtime! container player)]
    ;; No wireless link on portable — hide the block-only wireless button
    (let [^INode wb (rt/node-by-id r :button-wireless) ^INode wt (rt/node-by-id r :text-wireless)]
      (when wb (.setVisible wb false) (.setFlag wb node/FLAG-LAYOUT-DIRTY))
      (when wt (.setVisible wt false) (.setFlag wt node/FLAG-LAYOUT-DIRTY)))
    ;; Energy synced from the held item each frame (instead of tile-entity sync)
    (set-tick! r :portable-energy-tick
      (sig/computed-o [(rt/clock-ms-sig r)]
        (fn [_] (reset! (:energy container) (current-energy-from-held-item player)) nil)))
    r))

(defn open! [player]
  (bridge/open-reactive-screen! (create-runtime player) "Portable Developer"))
