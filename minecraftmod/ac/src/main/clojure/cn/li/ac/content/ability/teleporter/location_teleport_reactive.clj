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
            [cn.li.ac.ability.client.managed-screens :as managed-screens]
            [cn.li.ac.ability.client.read-model :as read-model]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.platform.ui :as platform-ui]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.core :as ui]
            [cn.li.mcmod.ui.node :as node]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.ui.events :as events]
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

(defn- net-owner [player-uuid] {:logical-side :client :player-uuid player-uuid})

;; ============================================================================
;; Layout constants
;; ============================================================================

(def ^:private panel-w 280.0)
(def ^:private entry-h 24.0)
(def ^:private max-visible 7)
(def ^:private list-y 30.0)
(def ^:private panel-h (+ list-y (* (inc max-visible) entry-h) 12.0))

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
      (when (and resp (:success? resp))
        (update-screen! owner-key
          (fn [_] {:locations (vec (or (:locations resp) []))
                   :exp (double (or (:exp resp) 0.0))
                   :current-pos (:current-pos resp)
                   :limits (or (:limits resp) {})}))
        (rebuild-list! rt player-uuid owner-key)))))

(defn- send-action! [^UiRt rt player-uuid msg-id payload owner-key]
  (net-client/send-to-server (net-owner player-uuid) msg-id payload
    (fn [_resp] (send-query! rt player-uuid owner-key))))

;; ============================================================================
;; Row / add-row builders
;; ============================================================================

(defn- row-spec [id loc can? cross?]
  (let [name-str (str (or (:name loc) "?") (when cross? " [D]"))
        dist (int (or (:distance loc) 0)) cp (int (or (:cp-cost loc) 0))
        name-color (if can? 0xFFC1CFD5 0xFFA2A2A2)]
    {:kind :box
     :props {:id id :x 0.0 :y 0.0 :w 274.0 :h entry-h
             :fill (if can? 0x19000000 0x10000000) :hover-tint 0.15}
     :children
     [{:kind :text :props {:id (keyword (str (name id) "-name")) :x 4.0 :y 4.0 :w 120.0 :h 16.0
                            :text name-str :font-size 9.0 :color name-color}}
      {:kind :text :props {:id (keyword (str (name id) "-dist")) :x 126.0 :y 4.0 :w 80.0 :h 16.0
                            :text (str dist "m  " cp "IF") :font-size 8.0
                            :color (if can? 0xFF888888 0xFF666666)}}
      {:kind :box :props {:id (keyword (str (name id) "-tp")) :x 210.0 :y 4.0 :w 30.0 :h 16.0
                           :fill 0xFF224466 :hover-tint 0.25 :visible? can?}
       :children [{:kind :text :props {:x 0.0 :y 0.0 :w 30.0 :h 16.0 :text "TP" :font-size 8.0
                                        :color 0xFFFFFFFF :align "center"}}]}
      {:kind :box :props {:id (keyword (str (name id) "-del")) :x 242.0 :y 4.0 :w 28.0 :h 16.0
                           :fill 0xFF442222 :hover-tint 0.25}
       :children [{:kind :text :props {:x 0.0 :y 0.0 :w 28.0 :h 16.0 :text "Del" :font-size 9.0
                                        :color 0xFFFFFFFF :align "center"}}]}]}))

(defn- add-row-spec [id]
  {:kind :box
   :props {:id id :x 0.0 :y 0.0 :w 274.0 :h entry-h :fill 0x15000000}
   :children
   [{:kind :text :props {:id (keyword (str (name id) "-input")) :x 8.0 :y 4.0 :w 152.0 :h 16.0
                          :text "" :font-size 9.0 :color 0xFFC1CFD5 :editable? true}}
    {:kind :text :props {:id (keyword (str (name id) "-ph")) :x 8.0 :y 4.0 :w 152.0 :h 16.0
                          :text "Click to save location..." :font-size 8.0 :color 0xFF666666}}
    {:kind :box :props {:id (keyword (str (name id) "-ok")) :x 168.0 :y 4.0 :w 50.0 :h 16.0
                         :fill 0xFF224422 :hover-tint 0.25}
     :children [{:kind :text :props {:x 0.0 :y 0.0 :w 50.0 :h 16.0 :text "Save" :font-size 8.0
                                      :color 0xFFFFFFFF :align "center"}}]}
    {:kind :box :props {:id (keyword (str (name id) "-cancel")) :x 220.0 :y 4.0 :w 50.0 :h 16.0
                         :fill 0xFF333333 :hover-tint 0.25}
     :children [{:kind :text :props {:x 0.0 :y 0.0 :w 50.0 :h 16.0 :text "Clear" :font-size 8.0
                                      :color 0xFF888888 :align "center"}}]}]})

;; ============================================================================
;; List rebuild — the visible scroll window + optional add-row
;; ============================================================================

(defn- hovered-location [^UiRt rt hit-map]
  (get hit-map (rt/hovered-idx rt)))

(defn- rebuild-list!
  [^UiRt rt player-uuid owner-key]
  (let [list-grp ^INode (rt/node-by-id rt :list-ctr)
        _ (rt/clear-children! rt list-grp)
        {:keys [locations limits]} (screen-st owner-key)
        total (count locations)
        max-locs (int (or (:max-saved-locations limits) 10))
        scroll-a (rt/user-signal rt :scroll-idx)
        start (max 0 (min @scroll-a (max 0 (- total max-visible))))
        _ (reset! scroll-a start)
        visible-locs (vec (drop start (take (+ start max-visible) locations)))
        hit-map (atom {})]
    (doseq [[idx loc] (map-indexed vector visible-locs)]
      (let [can? (boolean (:can-perform? loc))
            cross? (boolean (:cross-dimension? loc))
            id (keyword (str "loc-row-" idx))
            spec (assoc-in (row-spec id loc can? cross?) [:props :y] (double (* idx entry-h)))
            ^INode row (rt/build-child! rt spec list-grp)]
        (swap! hit-map assoc (.getIdx row) loc)
        (when can?
          (events/on! rt (keyword (str (name id) "-tp")) :left-click
            (fn [_ _ _]
              (client-sounds/queue-current-sound-effect!
                {:type :sound :sound-id "my_mod:tp.tp" :volume 0.5 :pitch 1.0})
              (send-action! rt player-uuid catalog/MSG-REQ-SAVED-POS-PERFORM {:name (:name loc)} owner-key)
              (bridge/close-screen!))))
        (events/on! rt (keyword (str (name id) "-del")) :left-click
          (fn [_ _ _]
            (send-action! rt player-uuid catalog/MSG-REQ-SAVED-POS-REMOVE {:name (:name loc)} owner-key)))))
    (let [add-y (double (* (count visible-locs) entry-h))
          spec (assoc-in (add-row-spec :add-row) [:props :y] add-y)
          ^INode add-row (rt/build-child! rt spec list-grp)]
      (events/on! rt :add-row-ok :left-click
        (fn [_ _ _]
          (let [^INode input-n (ui/item-node add-row :add-row-input)
                name (str/trim (str (.getOSlot input-n 0)))
                name-len (int (or (:max-location-name-length limits) 16))]
            (when (and (not (str/blank? name)) (<= (count name) name-len) (< total max-locs))
              (send-action! rt player-uuid catalog/MSG-REQ-SAVED-POS-ADD {:name name} owner-key)
              (ui/set-node-prop! rt input-n :text "")))))
      (events/on! rt :add-row-cancel :left-click
        (fn [_ _ _]
          (let [^INode input-n (ui/item-node add-row :add-row-input)]
            (ui/set-node-prop! rt input-n :text "")))))
    (rt/put-user-signal! rt :hit-map hit-map)
    (let [scroll-max (max 0 (- total max-visible))]
      (let [^INode up (rt/node-by-id rt :scroll-up) ^INode dn (rt/node-by-id rt :scroll-down)]
        (when up (.setVisible up (pos? start)) (.setFlag up node/FLAG-LAYOUT-DIRTY))
        (when dn (.setVisible dn (< start scroll-max)) (.setFlag dn node/FLAG-LAYOUT-DIRTY))))
    (ui/set-prop! rt :status-line :text (str total " / " max-locs " locations"))))

;; ============================================================================
;; Info panel — hover detail or EXP/cross-dim status, refreshed each frame
;; ============================================================================

(defn- attach-info-panel-tick! [^UiRt rt owner-key]
  (set-tick! rt :info-tick
    (sig/computed-o [(rt/clock-ms-sig rt)]
      (fn [_]
        (let [hit-map @(or (rt/user-signal rt :hit-map) (atom {}))
              loc (hovered-location rt hit-map)]
          (if loc
            (ui/set-prop! rt :status :text
              (str "Dim: " (or (:world-id loc) "?")
                   "  (" (int (or (:x loc) 0)) ", " (int (or (:y loc) 0)) ", " (int (or (:z loc) 0)) ")"
                   "  " (int (or (:cp-cost loc) 0)) "IF"))
            (let [st (screen-st owner-key)
                  exp (double (or (:exp st) 0.0))
                  limits (or (:limits st) {})
                  cross-exp (double (or (:cross-dimension-exp-threshold limits) 0.8))]
              (ui/set-prop! rt :status :text
                (str "EXP: " (int (* 100.0 exp)) "%  "
                     (if (>= exp cross-exp) "Cross-Dim OK" "Same Dim Only"))))))
        nil))))

;; ============================================================================
;; Scroll handling
;; ============================================================================

(defn- attach-scroll! [^UiRt rt player-uuid owner-key]
  (events/on! rt :scroll-up :left-click
    (fn [_ _ _]
      (swap! (rt/user-signal rt :scroll-idx) #(max 0 (dec %)))
      (rebuild-list! rt player-uuid owner-key)))
  (events/on! rt :scroll-down :left-click
    (fn [_ _ _]
      (swap! (rt/user-signal rt :scroll-idx) inc)
      (rebuild-list! rt player-uuid owner-key)))
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
  {:kind :box
   :props {:id :root :x 0.0 :y 0.0 :w panel-w :h panel-h :fill 0xCC1A2226}
   :children
   [{:kind :box :props {:id :info-panel :x 0.0 :y 0.0 :w panel-w :h 28.0 :fill 0xAA111820}
     :children
     [{:kind :text :props {:id :title :x 6.0 :y 2.0 :w 100.0 :h 12.0
                            :text "Location Teleport" :font-size 10.0 :color 0xFFC1CFD5}}
      {:kind :text :props {:id :status :x 6.0 :y 15.0 :w 268.0 :h 12.0
                            :text "" :font-size 8.0 :color 0xFF888888}}]}
    {:kind :group :props {:id :list-bg :x 0.0 :y list-y :w 260.0 :h (* max-visible entry-h) :clip? true}
     :children [{:kind :group :props {:id :list-ctr :x 3.0 :y 0.0
                                       :w 254.0 :h (* entry-h (inc max-visible))}}]}
    {:kind :box :props {:id :scroll-up :x 262.0 :y list-y :w 16.0 :h 14.0
                         :fill 0xFF334455 :hover-tint 0.3 :visible? false}
     :children [{:kind :text :props {:x 0.0 :y 0.0 :w 16.0 :h 14.0 :text "^" :font-size 8.0
                                      :color 0xFF888888 :align "center"}}]}
    {:kind :box :props {:id :scroll-down :x 262.0 :y (+ list-y (- (* max-visible entry-h) 14.0)) :w 16.0 :h 14.0
                         :fill 0xFF334455 :hover-tint 0.3 :visible? false}
     :children [{:kind :text :props {:x 0.0 :y 0.0 :w 16.0 :h 14.0 :text "v" :font-size 8.0
                                      :color 0xFF888888 :align "center"}}]}
    {:kind :text :props {:id :status-line :x 6.0 :y (- panel-h 12.0) :w 268.0 :h 10.0
                          :text "" :font-size 8.0 :color 0xFF666666 :align "center"}}]})

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

(let [registered? (atom false)]
  (defn init!
    "Register location-teleport widget factory. Idempotent."
    []
    (when (compare-and-set! registered? false true)
      (platform-ui/register-widget-factory! :ac/saved-position
        (fn [{:keys [player payload]}] (open-screen! player (or payload {}))))
      (log/info "Location Teleport reactive screen registered"))))
