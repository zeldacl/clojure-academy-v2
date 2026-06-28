(ns cn.li.ac.content.ability.teleporter.location-teleport-screen
  "CLIENT-ONLY: Location Teleport CGUI screen."
  (:require [cn.li.ac.ability.messages :as catalog]
            [cn.li.ac.ability.client.managed-screens :as managed-screens]
            [cn.li.ac.ability.client.read-model :as read-model]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.platform.ui :as platform-ui]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.mcmod.util.log :as log]
            [clojure.string :as str]))

;; ============================================================================
;; Blend animation (matching original Blend class)
;; ============================================================================

(def ^:private elem-time-step 0.06)

(defn- blend-alpha
  "Compute blend alpha matching original Blend(timeOffset, length).alpha.
   elapsed-sec — seconds since screen opened
   time-offset — delay before this element starts blending
   length      — blend duration in seconds
   Returns alpha in [0.0, 1.0]."
  [elapsed-sec time-offset length]
  (let [dt (- elapsed-sec time-offset)]
    (max 0.0 (min 1.0 (/ (max 0.0 dt) (double length))))))

(defn- now-secs []
  (/ (double (System/currentTimeMillis)) 1000.0))

;; ============================================================================
;; Screen state
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

;; ============================================================================
;; Network (forward decls needed for circular refs)
;; ============================================================================

(declare rebuild-list!)

(defn- net-owner [player-uuid] {:logical-side :client :player-uuid player-uuid})

(defn- send-query! [player-uuid owner-key root hovered-atom scroll-idx]
  (net-client/send-to-server (net-owner player-uuid)
    catalog/MSG-REQ-SAVED-POS-QUERY {}
    (fn [resp]
      (when (and resp (:success? resp))
        (update-screen! owner-key
          (fn [_] {:locations (vec (or (:locations resp) []))
                   :exp (double (or (:exp resp) 0.0))
                   :current-pos (:current-pos resp)
                   :limits (or (:limits resp) {})}))
        (when root (rebuild-list! root player-uuid owner-key hovered-atom scroll-idx))))))

(defn- send-action! [player-uuid msg-id payload owner-key root hovered-atom scroll-idx]
  (net-client/send-to-server (net-owner player-uuid) msg-id payload
    (fn [_resp] (send-query! player-uuid owner-key root hovered-atom scroll-idx))))

;; ============================================================================
;; Layout
;; ============================================================================

(def ^:private panel-w 280)
(def ^:private panel-h 230)
(def ^:private entry-h 24)
(def ^:private max-visible 7)
(def ^:private list-y 30)

(defn- make-text [x y w h text font-size color & {:keys [align] :or {align :left}}]
  (doto (cgui-core/create-widget :pos [x y] :size [w h])
    (comp/add-component! (comp/text-box :text text :font :ac-normal :font-size font-size :align align :color color))))

;; ============================================================================
;; Location entry row
;; ============================================================================

(defn- build-entry [player-uuid owner-key loc idx total-count hovered-atom root scroll-idx]
  (let [name (or (:name loc) "?")
        dist (int (or (:distance loc) 0))
        cp (int (or (:cp-cost loc) 0))
        cross? (boolean (:cross-dimension? loc))
        can? (boolean (:can-perform? loc))
        y (* idx entry-h)
        row (doto (cgui-core/create-widget :pos [0 y] :size [274 entry-h])
              (comp/add-component! (comp/tint 0x00000000))
              (comp/add-component! (comp/draw-texture nil (if can? 0x19000000 0x10000000))))
        tint-comp (first (filter #(= (or (:kind %) (::kind %)) :tint) @(:components row)))
        name-color (if can? 0xFFc1cfd5 0xFFa2a2a2)
        name-str (str name (when cross? " [D]"))]
    ;; Name text
    (doto row
      (cgui-core/add-widget!
        (doto (cgui-core/create-widget :pos [4 4] :size [120 16])
          (comp/add-component!
            (comp/text-box :text name-str :font :ac-normal :font-size 9 :align :left :color name-color)))))
    ;; Distance + CP
    (doto row
      (cgui-core/add-widget!
        (doto (cgui-core/create-widget :pos [126 4] :size [80 16])
          (comp/add-component!
            (comp/text-box :text (str dist "m  " cp "IF") :font :ac-normal :font-size 8
                           :align :right :color (if can? 0xFF888888 0xFF666666))))))
    ;; TP button
    (when can?
      (let [tp-btn (doto (cgui-core/create-widget :pos [210 4] :size [30 16])
                     (comp/add-component! (comp/draw-texture nil 0xFF224466))
                     (comp/add-component!
                       (comp/text-box :text "TP" :font :ac-bold :font-size 8 :align :center :color 0xFFFFFFFF)))]
        (events/on-left-click tp-btn
          (fn [_]
            (client-sounds/queue-current-sound-effect!
              {:type :sound :sound-id "my_mod:tp.tp" :volume 0.5 :pitch 1.0})
            (send-action! player-uuid catalog/MSG-REQ-SAVED-POS-PERFORM {:name name}
                          owner-key root hovered-atom scroll-idx)
            (client-bridge/close-screen!)))
        (cgui-core/add-widget! row tp-btn)))
    ;; Remove button
    (let [del-btn (doto (cgui-core/create-widget :pos [242 4] :size [28 16])
                    (comp/add-component! (comp/draw-texture nil 0xFF442222))
                    (comp/add-component!
                      (comp/text-box :text "Del" :font :ac-bold :font-size 9 :align :center :color 0xFFFFFFFF)))]
      (events/on-left-click del-btn
        (fn [_]
          (send-action! player-uuid catalog/MSG-REQ-SAVED-POS-REMOVE {:name name}
                        owner-key root hovered-atom scroll-idx)))
      (cgui-core/add-widget! row del-btn))
    ;; Blend-in reveal overlay + hover effect
    (let [reveal-dt (comp/draw-texture nil 0xCC1a2226)]
      (comp/add-component! row reveal-dt)
      (events/on-frame row
        (fn [_]
          ;; Blend-in reveal animation (matching original sequential row blend)
          (let [creation-sec (or (get @(:metadata root) :creation-sec) (now-secs))
                elapsed (- (now-secs) creation-sec)
                bg-alpha (blend-alpha elapsed (* idx elem-time-step) 0.2)
                ;; Reveal overlay: fade from opaque to transparent
                reveal-a (int (* 255.0 (- 1.0 bg-alpha)))
                _ (swap! (:state reveal-dt) assoc :color
                     (unchecked-int (bit-or (bit-shift-left reveal-a 24) 0x001a2226)))
                ;; Hover tint
                hovering? (boolean (:hovering? @(:metadata row)))
                hover-alpha (if hovering? 0x40FFFFFF
                                    (let [a (int (* 255.0 0.1 bg-alpha))]
                                      (unchecked-int (bit-or (bit-shift-left a 24) 0x00FFFFFF))))]
            (when tint-comp
              (swap! (:state tint-comp) assoc :color hover-alpha))
            (reset! hovered-atom (when hovering? {:loc loc :row-y (+ list-y y)}))))))
    row))

;; ============================================================================
;; Add row
;; ============================================================================

(defn- build-add-row [player-uuid owner-key idx total-count root hovered-atom scroll-idx]
  (let [y (* idx entry-h)
        input-tb (comp/text-box :text "" :font :ac-normal :font-size 9 :align :left :color 0xFFc1cfd5)
        input-w (doto (cgui-core/create-widget :pos [4 4] :size [160 16])
                  (comp/add-component! input-tb))
        submit (fn []
                 (let [name (str/trim (or (:text @(:state input-tb)) ""))
                       limits (:limits (screen-st owner-key))
                       max-locs (int (or (:max-saved-locations limits) 10))
                       name-len (int (or (:max-location-name-length limits) 16))]
                   (when (and (not (str/blank? name)) (<= (count name) name-len) (< total-count max-locs))
                     (send-action! player-uuid catalog/MSG-REQ-SAVED-POS-ADD {:name name}
                                   owner-key root hovered-atom scroll-idx)
                     (comp/set-text! input-tb ""))))
        ph-w (doto (cgui-core/create-widget :pos [8 4] :size [152 16])
               (comp/add-component!
                 (comp/text-box :text "Click to save location..." :font :ac-normal :font-size 8
                                :align :left :color 0xFF666666)))
        ok-btn (doto (cgui-core/create-widget :pos [168 4] :size [50 16])
                 (comp/add-component! (comp/draw-texture nil 0xFF224422))
                 (comp/add-component!
                   (comp/text-box :text "Save" :font :ac-bold :font-size 8 :align :center :color 0xFFFFFFFF)))
        cancel-btn (doto (cgui-core/create-widget :pos [220 4] :size [50 16])
                     (comp/add-component! (comp/draw-texture nil 0xFF333333))
                     (comp/add-component!
                       (comp/text-box :text "Clear" :font :ac-normal :font-size 8 :align :center :color 0xFF888888)))
        row (doto (cgui-core/create-widget :pos [0 y] :size [274 entry-h])
              (comp/add-component! (comp/tint 0x00000000))
              (comp/add-component! (comp/draw-texture nil 0x15000000)))]
    (comp/set-editable! input-tb true)
    (events/on-confirm-input input-tb (fn [_] (submit)))
    (cgui-core/add-widget! row input-w)
    (events/on-frame ph-w (fn [_] (cgui-core/set-visible! ph-w (str/blank? (or (:text @(:state input-tb)) "")))))
    (cgui-core/add-widget! row ph-w)
    (events/on-left-click ok-btn (fn [_] (submit)))
    (cgui-core/add-widget! row ok-btn)
    (events/on-left-click cancel-btn (fn [_] (comp/set-text! input-tb "")))
    (cgui-core/add-widget! row cancel-btn)
    (let [tint-comp (first (filter #(= (or (:kind %) (::kind %)) :tint) @(:components row)))]
      (events/on-frame row
        (fn [_]
          (when tint-comp
            (swap! (:state tint-comp) assoc :color
                   (if (boolean (:hovering? @(:metadata row))) 0x40FFFFFF 0x00000000)))
          ;; Clear hovered-atom when hovering add row (no location data)
          (when (boolean (:hovering? @(:metadata row)))
            (reset! hovered-atom nil)))))
    (reset! scroll-idx (min @scroll-idx (max 0 (- total-count max-visible))))
    row))

;; ============================================================================
;; Info panel
;; ============================================================================

(defn- build-info-panel [hovered-atom]
  (let [info (doto (cgui-core/create-widget :pos [0 0] :size [panel-w 28])
               (comp/add-component! (comp/draw-texture nil 0xAA111820)))
        title-w (doto (cgui-core/create-widget :pos [6 2] :size [100 12])
                  (comp/add-component!
                    (comp/text-box :text "Location Teleport" :font :ac-bold :font-size 10 :align :left :color 0xFFc1cfd5)))
        status-w (doto (cgui-core/create-widget :pos [6 15] :size [268 12])
                   (comp/add-component!
                     (comp/text-box :text "" :font :ac-normal :font-size 8 :align :left :color 0xFF888888)))
        status-tb (comp/get-textbox-component status-w)]
    (cgui-core/add-widget! info title-w)
    (cgui-core/add-widget! info status-w)
    (events/on-frame info
      (fn [_]
        (if-let [{:keys [loc row-y]} @hovered-atom]
          (do
            ;; Reposition info panel to follow row y (matching original moveWidgetToAbsPos)
            (cgui-core/set-position! info 0 (max list-y (- row-y list-y)))
            (comp/set-text! status-tb
              (str "Dim: " (or (:world-id loc) "?")
                   "  (" (int (or (:x loc) 0)) ", " (int (or (:y loc) 0)) ", " (int (or (:z loc) 0)) ")"
                   "  " (int (or (:cp-cost loc) 0)) "IF")))
          (do
            ;; No hover — reset to top, show status
            (cgui-core/set-position! info 0 0)
            (let [st (screen-st nil)
                  exp (double (or (:exp st) 0.0))
                  limits (or (:limits st) {})
                  cross-exp (double (or (:cross-dimension-exp-threshold limits) 0.8))]
              (comp/set-text! status-tb
                (str "EXP: " (int (* 100.0 exp)) "%"
                     "  " (if (>= exp cross-exp) "Cross-Dim OK" "Same Dim Only"))))))))
    info))

;; ============================================================================
;; List builder
;; ============================================================================

(defn- rebuild-list! [root player-uuid owner-key hovered-atom scroll-idx]
  (when-let [list-ctr (cgui-core/find-widget root "loc-list-ctr")]
    (cgui-core/clear-widgets! list-ctr)
    (let [{:keys [locations]} (screen-st owner-key)
          total (count locations)
          start @scroll-idx
          scroll-max (max 0 (- total max-visible))
          visible-locs (vec (drop start (take (+ start max-visible) locations)))]
      (reset! scroll-idx (min @scroll-idx scroll-max))
      (doseq [[idx loc] (map-indexed vector visible-locs)]
        (cgui-core/add-widget! list-ctr
          (build-entry player-uuid owner-key loc idx total hovered-atom root scroll-idx)))
      (when (and (pos? total) (< total max-visible))
        (cgui-core/add-widget! list-ctr
          (build-add-row player-uuid owner-key total total root hovered-atom scroll-idx)))
      (when (>= total max-visible)
        (cgui-core/add-widget! list-ctr
          (build-add-row player-uuid owner-key max-visible total root hovered-atom scroll-idx)))
      (when-let [up-btn (cgui-core/find-widget root "scroll-up")]
        (cgui-core/set-visible! up-btn (pos? @scroll-idx)))
      (when-let [dn-btn (cgui-core/find-widget root "scroll-down")]
        (cgui-core/set-visible! dn-btn (< @scroll-idx scroll-max))))))

;; ============================================================================
;; Root builder
;; ============================================================================

(defn build-screen [player]
  (let [player-uuid (uuid/player-uuid player)
        owner-key (owner-key-from-player player)
        {:keys [locations limits]} (screen-st owner-key)
        max-locs (int (or (:max-saved-locations limits) 10))
        total (count locations)
        hovered-atom (atom nil)
        scroll-idx (atom 0)
        creation-sec (now-secs)
        root (doto (cgui-core/create-widget :pos [0 0] :size [panel-w 0])  ;; start at 0 height for blend-in
               (comp/add-component! (comp/draw-texture nil 0xCC1a2226)))
        info-panel (build-info-panel hovered-atom)
        up-btn (doto (cgui-core/create-widget :pos [262 list-y] :size [16 14])
                 (comp/add-component! (comp/draw-texture nil 0xFF334455))
                 (comp/add-component! (comp/text-box :text "^" :font :ac-normal :font-size 8 :align :center :color 0xFF888888))
                 (cgui-core/set-name! "scroll-up")
                 (cgui-core/set-visible! false))
        dn-btn (doto (cgui-core/create-widget :pos [262 (+ list-y (- (* max-visible entry-h) 14))] :size [16 14])
                 (comp/add-component! (comp/draw-texture nil 0xFF334455))
                 (comp/add-component! (comp/text-box :text "v" :font :ac-normal :font-size 8 :align :center :color 0xFF888888))
                 (cgui-core/set-name! "scroll-down")
                 (cgui-core/set-visible! false))
        list-bg (doto (cgui-core/create-widget :pos [0 list-y] :size [260 (* max-visible entry-h)])
                  (comp/add-component! (comp/draw-texture nil 0x00000000)))
        list-ctr (doto (cgui-core/create-widget :pos [3 0] :size [254 (* entry-h (inc (min total max-visible)))])
                   (cgui-core/set-name! "loc-list-ctr"))
        status-line (doto (cgui-core/create-widget :pos [6 (- panel-h 12)] :size [268 10])
                      (comp/add-component!
                        (comp/text-box :text (str total " / " max-locs " locations")
                                       :font :ac-normal :font-size 8 :align :center :color 0xFF666666)))]
    (cgui-core/add-widget! root info-panel)
    (events/on-left-click up-btn
      (fn [_] (swap! scroll-idx #(max 0 (dec %)))
              (rebuild-list! root player-uuid owner-key hovered-atom scroll-idx)))
    (cgui-core/add-widget! root up-btn)
    (events/on-left-click dn-btn
      (fn [_] (swap! scroll-idx inc)
              (rebuild-list! root player-uuid owner-key hovered-atom scroll-idx)))
    (cgui-core/add-widget! root dn-btn)
    (events/on-mouse-scroll list-bg
      (fn [evt]
        (let [scroll-max (max 0 (- total max-visible))
              delta (int (or (:delta-y evt) 0))]
          (when (pos? scroll-max)
            (swap! scroll-idx #(max 0 (min scroll-max (+ % (if (neg? delta) 1 -1)))))
            (rebuild-list! root player-uuid owner-key hovered-atom scroll-idx)))))
    (cgui-core/add-widget! root list-bg)
    (doseq [[idx loc] (map-indexed vector (take max-visible locations))]
      (cgui-core/add-widget! list-ctr (build-entry player-uuid owner-key loc idx total hovered-atom root scroll-idx)))
    (cgui-core/add-widget! list-ctr (build-add-row player-uuid owner-key (min max-visible total) total root hovered-atom scroll-idx))
    (cgui-core/add-widget! list-bg list-ctr)
    (cgui-core/add-widget! root status-line)
    (swap! (:metadata root) assoc :creation-sec creation-sec)
    (let [counter (atom 0)
          menu-blend-done? (atom false)]
      (events/on-frame root
        (fn [_]
          ;; Menu blend-in animation (matching original Blend(0, 0.4))
          (when-not @menu-blend-done?
            (let [elapsed (- (now-secs) creation-sec)
                  menu-alpha (blend-alpha elapsed 0.0 0.4)]
              (cgui-core/set-size! root panel-w (* panel-h menu-alpha))
              (when (>= menu-alpha 1.0)
                (reset! menu-blend-done? true))))
          ;; Periodic refresh
          (swap! counter inc)
          (when (zero? (mod @counter 100))
            (send-query! player-uuid owner-key root hovered-atom scroll-idx)))))
    (send-query! player-uuid owner-key root hovered-atom scroll-idx)
    root))

;; ============================================================================
;; Public API
;; ============================================================================

(defn open-screen! [player _payload]
  (let [root (build-screen player)]
    {:type :cgui-screen :cgui root}))

(defn close-screen! [owner]
  (managed-screens/clear-screen-state! screen-id (read-model/owner-key owner :location-teleport)))

(defn init! []
  (platform-ui/register-widget-factory! :ac/saved-position
    (fn [{:keys [player payload]}] (open-screen! player (or payload {}))))
  (log/info "Location Teleport CGUI screen registered"))
