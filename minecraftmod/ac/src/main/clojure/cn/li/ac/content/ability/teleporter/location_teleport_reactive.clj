(ns cn.li.ac.content.ability.teleporter.location-teleport-reactive
  "Complete reactive replacement for location-teleport-screen.clj.
   All network/state logic (query/add/remove/perform, owner-key resolution,
   screen-state cache) is reused verbatim. Only CGUI widget construction is
   rewritten native.

   Simplification versus the original (cosmetic-only, no functional loss):
   row reveal / panel grow blend-in animations omitted — content appears
   instantly. Hover highlighting is native (:box hover-tint via the
   framework's own hoveredIdx tracking) instead of manually-tracked
   :hovering? metadata."
  (:require [cn.li.ac.ability.messages :as catalog]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.ability.client.managed-screens :as managed-screens]
            [cn.li.ac.ability.client.read-model :as read-model]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.client.ui.registry :as widget-registry]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.core :as ui]
            [cn.li.mcmod.ui.node :as node]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.ui.events :as events]
            [cn.li.mcmod.ui.xml :as ui-xml]
            [clojure.string :as str])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]
           [cn.li.mcmod.ui.node INode]
           [cn.li.mcmod.uipojo.signal ISigO]))

;; ============================================================================
;; Screen state — reused verbatim from location-teleport-screen.clj
;; ============================================================================

(def screen-id :ac/location-teleport)
(def ^:private default-state {:locations [] :exp 0.0 :current-pos nil :limits {}})

(defn- owner-key-from-player [player]
  (read-model/owner-key
    (read-model/canonical-client-owner
      {:client-session-id (runtime-hooks/require-player-state-session-id "teleporter.ui")
       :player-uuid (uuid/player-uuid player)}
      :location-teleport)
    :location-teleport))

(defn- screen-st [owner-key]
  (managed-screens/screen-state screen-id owner-key default-state))

(defn- update-screen! [owner-key f & args]
  (apply managed-screens/update-screen-state! screen-id owner-key default-state f args))

(defn apply-server-payload! [owner {:keys [locations exp current-pos limits]}]
  (let [ok (read-model/owner-key owner :location-teleport)]
    (update-screen! ok (fn [_] {:locations (vec (or locations []))
                                 :exp (double (or exp 0.0))
                                 :current-pos current-pos
                                 :limits (or limits {})}))
    nil))

(defn- net-owner [player-uuid]
  ;; The RPC owner contract requires :client-session-id; default-client-owner
  ;; derives it from the live connection (a bare :player-uuid map throws
  ;; ":client-owner contract violation" in send-to-server).
  (assoc (runtime-hooks/default-client-owner) :player-uuid player-uuid))

;; ============================================================================
;; Layout constants
;; ============================================================================

;; Layout lives in guis/new/loctele_new.xml (upstream loctele_new.xml: root
;; centered at scale 0.24, menu 442x530, rows 442x80 spacing 2, info panel
;; right-aligned). These constants drive the row placement math and scroll
;; window only.
(def ^:private list-y 18.0)
(def ^:private entry-h 80.0)
(def ^:private entry-spacing 2.0)
(def ^:private max-visible 6)

;; Upstream DefColors — the row text toggles between them at runtime.
(def ^:private color-text-normal 0xFFC1CFD5)
(def ^:private color-text-disabled 0xFFA2A2A2)

;; ============================================================================
;; set-tick! — force a per-frame side-effecting computed-o to actually run
;; (see developer panel-reactive.clj for the fuller writeup).
;; ============================================================================

(defn- pull-o! [_node source] (.sGet ^ISigO source) nil)

(defn- set-tick! [^UiRt rt key computed-sig]
  (when-let [old (rt/user-signal rt key)] (sig/unbind! old))
  (if computed-sig
    (let [^INode anchor (rt/node-by-id rt :root)
          b (sig/bind! computed-sig anchor pull-o! (rt/get-dirty-bindings-q rt))]
      (rt/register-binding! rt (.getIdx anchor) b)
      (rt/put-user-signal! rt key b))
    (rt/put-user-signal! rt key nil)))

;; ============================================================================
;; Network — mirrors old send-query!/send-action!, adapted to rebuild the
;; native list instead of a CGUI subtree.
;; ============================================================================

(declare rebuild-list!)

(defn- send-query! [^UiRt rt player-uuid owner-key]
  (net-client/send-to-server (net-owner player-uuid)
    catalog/MSG-REQ-SAVED-POS-QUERY {}
    (fn [resp]
      ;; The server wraps the query result in {:action ... :snapshot ...} —
      ;; read the snapshot, not the top level.
      (let [snapshot (or (:snapshot resp) resp)]
        (log/info "Loctele query resp" {:success? (:success? snapshot)
                                        :count (count (:locations snapshot))})
        (when (and snapshot (:success? snapshot))
          (update-screen! owner-key
            (fn [_] {:locations (vec (or (:locations snapshot) []))
                     :exp (double (or (:exp snapshot) 0.0))
                     :current-pos (:current-pos snapshot)
                     :limits (or (:limits snapshot) {})}))
          (rebuild-list! rt player-uuid owner-key))))))

(defn- send-action! [^UiRt rt player-uuid msg-id payload owner-key]
  (net-client/send-to-server (net-owner player-uuid) msg-id payload
    (fn [resp]
      (log/info "Loctele action resp" {:msg-id msg-id
                                       :top-level (select-keys resp [:success? :error])
                                       :action (select-keys (:action resp) [:success? :error :op])
                                       :snapshot-success? (:success? (:snapshot resp))})
      (send-query! rt player-uuid owner-key))))

;; ============================================================================
;; Row / add-row builders
;; ============================================================================

(defn- hide-row-sections! [^UiRt rt ^INode item]
  (doseq [id [:elem-row :add-row]]
    (when-let [^INode n (ui/item-node item id)]
      (.setVisible n false)
      ;; setVisible alone does not dirty the render tape — without this the
      ;; hidden section stays hittable and swallows the row's clicks.
      (.setFlag n node/FLAG-LAYOUT-DIRTY))))

(defn- show-row-section! [^UiRt rt ^INode item section-id]
  (hide-row-sections! rt item)
  (when-let [^INode n (ui/item-node item section-id)]
    (.setVisible n true)
    (.setFlag n node/FLAG-LAYOUT-DIRTY))
  (rt/mark-tree-dirty! rt))

;; ============================================================================
;; List rebuild — the visible scroll window + optional add-row
;; ============================================================================

(defn- hovered-location [^UiRt rt hit-map]
  ;; hit-test returns the DEEPEST node (name text, icon) — walk up to the
  ;; row entry so hovering anywhere on a row shows its info panel.
  (let [idx (rt/hovered-idx rt)]
    (loop [n (when (>= idx 0) (rt/node-by-idx rt idx))]
      (cond
        (nil? n) nil
        (contains? hit-map (.getIdx ^INode n)) (get hit-map (.getIdx ^INode n))
        :else (recur (.getParentNode ^INode n))))))

(defn- rebuild-list!
  [^UiRt rt player-uuid owner-key]
  (let [{:keys [locations limits current-pos]} (screen-st owner-key)
        total (count locations)
        scroll-a (rt/user-signal rt :scroll-idx)
        start (max 0 (min @scroll-a (max 0 (- total max-visible))))
        _ (reset! scroll-a start)
        visible-locs (vec (drop start (take (+ start max-visible) locations)))
        items (into (mapv (fn [idx loc] {:type :elem :loc loc :idx idx})
                          (range) visible-locs)
                    [{:type :add}])
        hit-map (atom {})]
    (ui/list-set! rt :list-ctr items
      (fn [rt ^INode item row]
        (case (:type row)
          :elem
          (let [loc (:loc row)
                idx (:idx row)
                can? (boolean (:can-perform? loc))
                ^INode name-n (ui/item-node item :name)
                ^INode tp-n (ui/item-node item :btn-tp)
                ^INode del-n (ui/item-node item :btn-del)]
            (show-row-section! rt item :elem-row)
            (ui/set-node-prop! rt name-n :text (str (or (:name loc) "?")))
            (ui/set-node-prop! rt name-n :color
                              (if can? color-text-normal color-text-disabled))
            (when tp-n
              (.setVisible tp-n can?)
              (.setFlag tp-n node/FLAG-LAYOUT-DIRTY)
              (rt/mark-tree-dirty! rt))
            ;; Hit-map value carries the row's design y so the info panel can
            ;; follow the hovered row (upstream setMessage moves info to ypos).
            (swap! hit-map assoc (.getIdx item)
                   {:loc loc :y (double (+ list-y (* idx (+ entry-h entry-spacing))))})
            (when can?
              (rt/register-event! rt (.getIdx tp-n) :left-click
                (fn [_ _ _]
                  ;; UI events carry no session binding — pass the screen's
                  ;; session explicitly (owner-key's first element).
                  (client-sounds/queue-sound-effect!
                    (first owner-key)
                    {:type :sound :sound-id (modid/namespaced-path "tp.tp") :volume 0.5 :pitch 1.0})
                  (send-action! rt player-uuid catalog/MSG-REQ-SAVED-POS-PERFORM {:name (:name loc)} owner-key)
                  (bridge/close-screen!))))
            (rt/register-event! rt (.getIdx del-n) :left-click
              (fn [_ _ _]
                (send-action! rt player-uuid catalog/MSG-REQ-SAVED-POS-REMOVE {:name (:name loc)} owner-key))))
          :add
          (let [^INode input-n (ui/item-node item :input)
                ^INode ok-n (ui/item-node item :ok)]
            (show-row-section! rt item :add-row)
            ;; Upstream newAdd: hovering the add row shows the CURRENT
            ;; position (dimension + feet coords, no CP).
            (swap! hit-map assoc (.getIdx item)
                   {:loc (or current-pos {}) :add? true
                    :y (double (+ list-y (* (count visible-locs)
                                            (+ entry-h entry-spacing))))})
            ;; The "Add..." placeholder hides as soon as the input is
            ;; clicked (focus) and while it has text; it returns when the
            ;; input is cleared and focus leaves.
            (let [set-ph! (fn [visible?]
                            (when-let [^INode ph-n (ui/item-node item :ph)]
                              (.setVisible ph-n visible?)
                              (.setFlag ph-n node/FLAG-LAYOUT-DIRTY)
                              (rt/mark-tree-dirty! rt)))
                  has-text? (fn [] (pos? (count (str (.getOSlot input-n 0)))))]
              (rt/register-event! rt (.getIdx input-n) :left-click
                (fn [_ _ _] (set-ph! false)))
              (rt/register-event! rt (.getIdx input-n) :change-content
                (fn [_ _ _] (set-ph! (not (has-text?)))))
              (rt/register-event! rt (.getIdx input-n) :lost-focus
                (fn [_ _ _] (set-ph! (not (has-text?))))))
            (rt/register-event! rt (.getIdx ok-n) :left-click
              (fn [_ _ _]
                (let [name (str/trim (str (.getOSlot input-n 0)))
                      name-len (int (or (:max-location-name-length limits) 16))]
                  (log/info "Loctele add click" {:name name :len (count name)
                                                 :max-len name-len})
                  (when (and (not (str/blank? name)) (<= (count name) name-len))
                    (send-action! rt player-uuid catalog/MSG-REQ-SAVED-POS-ADD {:name name} owner-key)
                    (ui/set-node-prop! rt input-n :text ""))))))
          nil)))
    (rt/put-user-signal! rt :hit-map hit-map)))

;; ============================================================================
;; Info panel — hover detail or EXP/cross-dim status, refreshed each frame
;; ============================================================================

(defn- attach-info-panel-tick! [^UiRt rt _owner-key]
  ;; Upstream MessageTab: the info panel shows the hovered row's message
  ;; (dimension, coords, CP cost) and moves to the row's y; hidden otherwise.
  (set-tick! rt :info-tick
    (sig/computed-o [(rt/clock-ms-sig rt)]
      (fn [_]
        (let [hit-map @(or (rt/user-signal rt :hit-map) (atom {}))
              entry (hovered-location rt hit-map)
              ^INode info (rt/node-by-id rt :info)]
          (log/info "Loctele hover tick" {:hovered-idx (rt/hovered-idx rt)
                                          :entry (boolean entry)})
          (if entry
            (let [;; The add row shows the CURRENT position — the local
                  ;; player's live feet coords (upstream player.posX/Y/Z), no
                  ;; server round-trip; the world id falls back to the
                  ;; snapshot's current-pos.
                  loc (if (:add? entry)
                        (merge (:loc entry)
                               (or (bridge/call-adapter :local-player-pos) {}))
                        (:loc entry))
                  coords (format "(%.0f, %.0f, %.0f)"
                                 (double (or (:x loc) 0.0))
                                 (double (or (:y loc) 0.0))
                                 (double (or (:z loc) 0.0)))
                  ;; Upstream: the add row shows the CURRENT position
                  ;; (dimension + coords, no CP); a saved row shows its
                  ;; stored location + CP cost.
                  lines (if (:add? entry)
                          [(str (or (:world-id loc) "?") " (#" (int (or (:dim-id loc) 0)) ")")
                           coords]
                          [(str (or (:world-id loc) "?") " (#" (int (or (:dim-id loc) 0)) ")")
                           coords
                           (str (int (or (:cp-cost loc) 0)) " CP")])]
              (when info
                (when-not (.isVisible info)
                  (.setVisible info true)
                  (.setFlag info node/FLAG-LAYOUT-DIRTY)
                  (rt/mark-tree-dirty! rt))
                (.setY info (double (:y entry)))
                (.setFlag info node/FLAG-LAYOUT-DIRTY))
              (doseq [[i line] (map-indexed vector lines)]
                (ui/set-prop! rt (keyword (str "info-line-" i)) :text line))
              ;; Clear the unused trailing message lines.
              (doseq [i (range (count lines) 4)]
                (ui/set-prop! rt (keyword (str "info-line-" i)) :text "")))
            (when info
              (when (.isVisible info)
                (.setVisible info false)
                (.setFlag info node/FLAG-LAYOUT-DIRTY)
                (rt/mark-tree-dirty! rt))))
          nil)))))

;; ============================================================================
;; Scroll handling
;; ============================================================================

(defn- attach-scroll! [^UiRt rt player-uuid owner-key]
  (events/on! rt :list-bg :mouse-scroll
    (fn [_ _ evt]
      (let [{:keys [locations]} (screen-st owner-key)
            scroll-max (max 0 (- (count locations) max-visible))
            delta (double (or (:delta evt) 0.0))]
        (when (pos? scroll-max)
          (swap! (rt/user-signal rt :scroll-idx) #(max 0 (min scroll-max (+ % (if (neg? delta) 1 -1)))))
          (rebuild-list! rt player-uuid owner-key))))))

;; ============================================================================
;; Root spec + entry point
;; ============================================================================

(defn- root-spec []
  ;; The whole layout (geometry + templates) lives in
  ;; guis/new/loctele_new.xml — upstream loctele_new.xml port.
  (ui-xml/load-spec (modid/namespaced-path "guis/new/loctele_new.xml")))

(defn create-runtime [player]
  (let [r (rt/create-runtime)
        owner-key (owner-key-from-player player)
        player-uuid (uuid/player-uuid player)]
    (rt/build! r (root-spec))
    (rt/put-user-signal! r :scroll-idx (atom 0))
    (attach-scroll! r player-uuid owner-key)
    (attach-info-panel-tick! r owner-key)
    (rebuild-list! r player-uuid owner-key)
    (send-query! r player-uuid owner-key)
    (set-tick! r :refresh-tick
      (let [counter (long-array 1)]
        (sig/computed-o [(rt/clock-ms-sig r)]
          (fn [_]
            (aset counter 0 (unchecked-inc (aget counter 0)))
            (when (zero? (rem (aget counter 0) 100))
              (send-query! r player-uuid owner-key))
            nil))))
    r))

(defn open-screen! [player _payload]
  (let [r (create-runtime player)]
    {:type :reactive-screen :runtime r :title "Location Teleport"}))

(defn close-screen! [owner]
  (managed-screens/clear-screen-state! screen-id (read-model/owner-key owner :location-teleport)))

(defn open! [player]
  (bridge/open-reactive-screen! (create-runtime player) "Location Teleport"))

(defn init!
  "Register location-teleport widget factory. Idempotent."
  []
  (install/framework-once! ::init
    (fn []
      (widget-registry/register-widget-factory! :ac/saved-position
        ;; The :location-teleport/ui-open channel payload carries only the
        ;; query data — resolve the client player here like the skill-tree
        ;; widget factory does (a payload :player would be nil).
        (fn [payload]
          (open-screen! (bridge/get-client-player) (or payload {}))))
      (log/info "Location Teleport reactive screen registered")))
  nil)
