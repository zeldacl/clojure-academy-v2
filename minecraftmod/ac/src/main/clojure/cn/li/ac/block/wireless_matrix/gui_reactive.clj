(ns cn.li.ac.block.wireless-matrix.gui-reactive
  "Reactive GUI registration for the Wireless Matrix.
   Owns container wiring, slots, network actions, capability proxy creation,
   and ownership-policy checks."
  (:require [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.gui.spec :as gui-reg]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.gui.manifest :as gui-manifest]
            [cn.li.ac.gui.info-area :as info-area]
            [cn.li.ac.gui.presentation-container :as presentation-container]
            [cn.li.ac.block.wireless-matrix.matrix-info-reactive :as matrix-info]
            [cn.li.ac.block.wireless-matrix.capability :as matrix-capability]
            [cn.li.ac.block.wireless-matrix.logic :as matrix-logic]
            [cn.li.ac.block.wireless-matrix.stats :as matrix-stats]
            [cn.li.ac.block.wireless-matrix.schema :as matrix-schema]
            [cn.li.ac.block.gui.sync :as gui-sync]
            [cn.li.ac.item.constraint-plate :as plate]
            [cn.li.ac.item.mat-core :as core]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.ac.wireless.gui.container.move :as move-common]))

(def ^:private wireless-matrix-id :wireless-matrix)

(defn- ensure-wireless-matrix-slot-schema!
  []
  (matrix-logic/ensure-matrix-slot-schema!))

(def ^:private inventory-pred
  (fn [slot-index player-inventory-start]
    (>= slot-index player-inventory-start)))

(def ^:private matrix-quick-move-config-lock
  (Object.))

(def ^:private ^:dynamic *wireless-matrix-quick-move-config*
  nil)

(defn- wireless-matrix-quick-move-config
  []
  (or (var-get #'*wireless-matrix-quick-move-config*)
      (locking matrix-quick-move-config-lock
        (or (var-get #'*wireless-matrix-quick-move-config*)
            (let [cfg (do
                        (ensure-wireless-matrix-slot-schema!)
                        (slot-schema/build-quick-move-config
                          wireless-matrix-id
                          {:inventory-pred inventory-pred
                           :rules [{:accept? core/is-mat-core?
                                    :slot-ids [:core]}
                                   {:accept? plate/is-constraint-plate?
                                    :slot-type :plate}]}))]
              (alter-var-root #'*wireless-matrix-quick-move-config* (constantly cfg))
              cfg)))))

(defn- resolve-state
  "Resolve the state map from either a ScriptedBlockEntity or an existing map."
  [tile]
  (if (map? tile)
    [nil tile]
    (try
      (let [state (or (platform-be/get-custom-state tile) matrix-stats/matrix-default-state)]
        [tile state])
      (catch Exception e
        (log/warn "Could not resolve customState from BE:"(ex-message e))
        [tile {}]))))

(declare handle-button-click!)

(defn create-container
  "Create a Matrix GUI container instance.
  Uses container atoms for sync; platform layer may attach DataSlots if needed."
  [tile player]
  (let [[be state] (resolve-state tile)
        entity (or be tile)
        proxy (matrix-capability/->MatrixJavaProxy entity)
        base (gui-sync/create-schema-container
               matrix-schema/unified-matrix-schema
               state
               player
               :matrix
               {:gui-id (gui-manifest/gui-id :wireless-matrix)
                :base {:tile-entity entity
                       :tile-java proxy}})
        network* (atom {:initialized false :ssid "" :password ""
                        :owner "Unknown" :load 0 :max-capacity 16
                        :range 64 :bandwidth 100})
        ;; Empty map — do not pre-seed :ssid/:password as nil (contains? would
        ;; be true and gather-info merge could force password "").
        form* (atom {})
        ;; Self-ref so button/submit handlers can call matrix-info with the
        ;; fully assoc'd container after create-container returns.
        container* (atom nil)
        refresh-network!
        (fn [c data]
          (let [data (into {} data)
                ;; While still uninitialized, keep local INIT drafts across the
                ;; async gather-info response so typing is not wiped mid-edit.
                data (if (:initialized data)
                       data
                       (let [f @form*
                             ssid (info-area/draft-value f :node-name)
                             password (info-area/draft-value f :network-password)]
                         (cond-> data
                           (some? ssid) (assoc :ssid ssid)
                           (some? password) (assoc :password password))))]
            (reset! network* data)
            (when-let [refresh (:presentation-refresh! c)]
              (refresh))))
        try-init!
        (fn [c view-state]
          ;; INIT is a button click — read live drafts via the shared alias
          ;; contract (ssid↔node-name, password↔network-password).
          (let [view (or view-state {})
                form @form*
                net @network*
                ssid (str (or (info-area/draft-value view :node-name)
                              (info-area/draft-value form :node-name)
                              (info-area/draft-value net :node-name)
                              ""))
                password (str (or (info-area/draft-value view :network-password)
                                  (info-area/draft-value form :network-password)
                                  (info-area/draft-value net :network-password)
                                  ""))]
            (swap! form* merge (info-area/expand-drafts
                                 {:node-name ssid :network-password password}))
            (when (and (seq ssid) (seq password)
                       (matrix-logic/owner-authorized? state player))
              (matrix-info/send-init-network
                c ssid password
                (fn [success]
                  (when success
                    (matrix-info/send-gather-info
                      c #(refresh-network! c %))))))))
        container
        (assoc base
               :presentation-network network*
               :presentation-form-state form*
               :presentation-snapshot-fn
               (fn [c _]
                 (let [data @(:presentation-network c)
                       owner? (boolean (matrix-logic/owner-authorized? state player))
                       info (matrix-info/info-area-snapshot data owner?)]
                   ;; Left page matches main page_matrix.xml (art + slots only).
                   ;; INIT / noinit chrome lives in the info area.
                   {:info-area info
                    :matrix-init-visible? (boolean (:init-visible? info))
                    :matrix-init-button (or (:init-button info) {:label "INIT"})
                    :matrix-noinit-visible? (boolean (:noinit-visible? info))
                    :matrix-noinit-label (str (or (:noinit-label info)
                                                 "-- Network unavailable --"))}))
               :presentation-text-change!
               (fn [field value]
                 (let [field (case field :node-name :ssid field)
                       drafts (info-area/payload-drafts field value)]
                   (when drafts
                     (swap! form* merge drafts)
                     (when (and (not (:initialized @network*))
                                (or (contains? drafts :ssid)
                                    (contains? drafts :password)))
                       (swap! network* merge (select-keys drafts [:ssid :password]))))))
               :presentation-text-submit!
               (fn [field value container]
                 (let [field (case field :node-name :ssid field)
                       drafts (info-area/payload-drafts field value)
                       value (str value)]
                   (when drafts (swap! form* merge drafts))
                   (when (and container (:initialized @network*)
                              (matrix-logic/owner-authorized? state player))
                     (case field
                       :ssid (matrix-info/send-change-ssid container value)
                       :password (matrix-info/send-change-password container value)
                       nil))))
               :presentation-on-mount!
               (fn [c]
                 ;; presentation-screen-data assoc's :minecraft-container before
                 ;; mount; keep container* on that live map so C2S actions
                 ;; resolve menu container-id (wireless-node pattern).
                 (reset! container* c)
                 (matrix-info/send-gather-info c #(refresh-network! c %)))
               :presentation-dispatch-action!
               (fn [action payload current]
                 (when (and (= action :matrix/init) @container*)
                   (try-init! @container* (when (map? current) current)))))]
      (reset! container* container)
      container))

;; ============================================================================
;; Slot Management
;; ============================================================================

(def ^:private matrix-slot-schema-id wireless-matrix-id)

(defn get-slot-count [_container]
  (slot-schema/tile-slot-count matrix-slot-schema-id))

(defn can-place-item? [_container slot-index item-stack]
  (case (slot-schema/slot-type matrix-slot-schema-id slot-index)
    :plate (plate/is-constraint-plate? item-stack)
    :core (core/is-mat-core? item-stack)
    false))

(defn get-slot-item [container slot-index]
  (common/get-slot-item-be container slot-index))

(defn set-slot-item! [container slot-index item-stack]
  (let [tile (:tile-entity container)]
    (log/debug "set-slot-item! - tile=" tile " slot=" slot-index " item=" item-stack)
    (common/set-slot-item-be! container slot-index item-stack
                              matrix-stats/matrix-default-state
                              matrix-logic/recalculate-counts)
    (when tile
      (log/debug "set-slot-item! after-write - plate=" (matrix-logic/get-plate-count tile)
                " core=" (matrix-logic/get-core-level tile)))
    ;; DataSlot synchronization is handled by Menu.broadcastChanges(),
    ;; which reads plate-count and core-level from container atoms every tick.
    nil))

(defn slot-changed! [container slot-index]
  ;; Trigger BE update with recalculation
  (let [item (get-slot-item container slot-index)]
    (set-slot-item! container slot-index item)))

;; ============================================================================
;; Container Sync
;; ============================================================================

(def ^:private matrix-sync
  (gui-sync/schema-sync-fns matrix-schema/unified-matrix-schema))

(def server-menu-sync! (:server-menu-sync! matrix-sync))

(defn still-valid? [container player]
  (common/still-valid? container player))

(defn handle-button-click! [container button-id _data]
  (case (int button-id)
    0 (log/debug "Toggled matrix working state")
    1 (do
        (set-slot-item! container (slot-schema/slot-index matrix-slot-schema-id :core) nil)
        (log/debug "Ejected matrix core"))
    2 (do
        (doseq [slot-idx (slot-schema/slot-indexes-by-type matrix-slot-schema-id :plate)]
          (set-slot-item! container slot-idx nil))
        (log/debug "Ejected all plates"))
    (log/warn "Unknown button ID:" button-id)))

(defn quick-move-stack [container slot-index player-inventory-start]
  (move-common/quick-move-with-rules
    container
    slot-index
    player-inventory-start
    (wireless-matrix-quick-move-config)))

(defn on-close [container]
  (log/debug "Closing wireless matrix container")
  ((:on-close matrix-sync) container))

;; ============================================================================
;; Reactive rendering bindings
;; ============================================================================

(defn create-screen [container menu player]
  ;; gather-info runs from :presentation-on-mount! once :minecraft-container
  ;; is bound — calling it here fails action-payload (no menu container-id).
  (presentation-container/presentation-screen-data
    container menu player :wireless-matrix "academy:wireless_matrix"))


;; ============================================================================
;; Registration
;; ============================================================================

(defn- matrix-container?
  "Predicate used by the GUI dispatcher (get-config-by-container) to route
   validate/close/slot operations to this GUI. Every other block GUI registers
   one; without it the matrix container can't be matched and close/validate fall
   through to \"Unknown container type\"."
  [c]
  (and (map? c) (= (:container-type c) :matrix)))

(defn init-wireless-matrix-reactive! []
  (install/framework-once! ::matrix-reactive-installed?
  (fn []
    (gui-reg/register-block-gui!
      (gui-manifest/gui-name :wireless-matrix)
      (merge (gui-manifest/gui-registration :wireless-matrix)
        {:container-predicate matrix-container?
         :container-fn create-container
         :screen-fn create-screen
         :server-menu-sync-fn server-menu-sync!
         :validate-fn still-valid?
         :close-fn on-close
         :button-click-fn handle-button-click!
         :slot-count-fn get-slot-count
         :slot-get-fn get-slot-item
         :slot-set-fn set-slot-item!
         :slot-can-place-fn can-place-item?
         :slot-changed-fn slot-changed!
         :quick-move-fn quick-move-stack}))
    (log/debug "Wireless Matrix GUI initialized (reactive render + delegated container logic)"))))
