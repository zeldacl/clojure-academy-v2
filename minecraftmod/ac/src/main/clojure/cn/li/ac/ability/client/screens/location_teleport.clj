(ns cn.li.ac.ability.client.screens.location-teleport
  "LocationTeleport screen state and draw-ops (AC layer - no Minecraft imports).

  Layout (screen coords assume 320x240 virtual canvas):
  - Left panel (x=10, y=10, w=200, h=220): scrollable location list
    - Each row (h=28): location name, cp cost, [Teleport] [Remove] buttons
    - Last row: [Add current location] with text input
  - Right info panel (x=220, y=10, w=90, h=220): hover info (dim, coords, cp)"
  (:require [cn.li.ac.ability.client.api :as api]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [clojure.string :as str]))

;; ============================================================================
;; Screen state
;; ============================================================================

(defonce ^:private screen-state
  (atom {:open? false
         :player-uuid nil
         :locations []
         :selected nil    ;; index of hovered location (int or nil)
         :add-mode? false
         :add-text ""
         :pending-op nil
         :last-error nil
         :exp 0.0
         :limits {:cross-dimension-exp-threshold 0.8
                  :max-location-name-length 16}
         :current-pos nil}))

;; ============================================================================
;; Layout constants
;; ============================================================================

(def ^:private panel-x 10)
(def ^:private panel-y 10)
(def ^:private panel-w 200)
(def ^:private panel-h 220)
(def ^:private row-h 28)
(def ^:private info-x 220)
(def ^:private info-y 10)
(def ^:private info-w 90)
(def ^:private info-h 220)

(def ^:private color-bg       0xcc1a2226)
(def ^:private color-row      0x19c1cfd5)
(def ^:private color-row-hover 0x66c1cfd5)
(def ^:private color-btn      0x99c1cfd5)
(def ^:private color-text-normal 0xffc1cfd5)
(def ^:private color-text-dim    0xffa2a2a2)
(def ^:private color-err         0xffff8888)
(def ^:private color-info-bg  0xaa111820)

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- row-y [idx]
  (+ panel-y (* idx row-h)))

(defn- point-in?
  [mx my x y w h]
  (and (>= mx x) (< mx (+ x w))
       (>= my y) (< my (+ y h))))

(defn- action-of [resp]
  (or (:action resp) {:success? (boolean (:success? resp))
                      :error (:error resp)}))

(defn- snapshot-of [resp]
  (or (:snapshot resp) resp))

;; ============================================================================
;; Public API: lifecycle
;; ============================================================================

(defn open-screen!
  "Called by platform bridge when server push opens the screen.
  `player-uuid` is the local player. `payload` is the initial query result."
  [player-uuid payload]
  (let [snapshot (snapshot-of payload)]
    (reset! screen-state
            {:open? true
             :player-uuid player-uuid
             :locations (vec (:locations snapshot []))
             :selected nil
             :add-mode? false
             :add-text ""
             :pending-op nil
             :last-error nil
             :exp (double (or (:exp snapshot) 0.0))
             :limits (merge {:cross-dimension-exp-threshold 0.8
                             :max-location-name-length 16}
                            (:limits snapshot))
             :current-pos (:current-pos snapshot)})
    {:command :open-screen}))

(defn close-screen! []
  (swap! screen-state assoc :open? false))

(defn apply-server-payload!
  "Apply server query payload to existing screen state (used before screen opens)."
  [payload]
  (when payload
    (let [snapshot (snapshot-of payload)]
      (swap! screen-state assoc
             :locations (vec (:locations snapshot []))
             :exp (double (or (:exp snapshot) 0.0))
             :limits (merge {:cross-dimension-exp-threshold 0.8
                             :max-location-name-length 16}
                            (:limits snapshot))
             :current-pos (:current-pos snapshot)))))

(declare apply-query-response!)

(defn- refresh-locations! []
  (api/req-location-teleport-query!
    (fn [resp]
      (let [action (action-of resp)
            snapshot (snapshot-of resp)]
        (when (:success? action)
          (apply-query-response! snapshot))))))

(defn- apply-query-response! [resp]
  (let [snapshot (snapshot-of resp)]
    (swap! screen-state assoc
           :locations (vec (:locations snapshot []))
           :exp (double (or (:exp snapshot) 0.0))
           :limits (merge {:cross-dimension-exp-threshold 0.8
                           :max-location-name-length 16}
                          (:limits snapshot))
           :current-pos (:current-pos snapshot))))

;; ============================================================================
;; Draw ops builder
;; ============================================================================

(defn- location-row-ops
  "Build draw ops for a single location entry row."
  [idx loc mx my]
  (let [ry (row-y idx)
        hovering? (point-in? mx my panel-x ry panel-w row-h)
        row-color (if hovering? color-row-hover color-row)
        can? (:can-perform? loc true)
        text-color (if can? color-text-normal color-text-dim)
        cp-text (when-let [cp (:cp-cost loc)]
                  (format "%.0f CP" (double cp)))
        ;; Button positions (relative to panel)
        btn-remove-x (+ panel-x panel-w -30)
        btn-tele-x   (+ panel-x panel-w -62)
        btn-y        (+ ry 6)
        btn-h        16]
    (cond-> []
      true (conj {:kind :fill :x panel-x :y ry :w panel-w :h (dec row-h) :color row-color})
      true (conj {:kind :text :text (or (:name loc) "?") :x (+ panel-x 4) :y (+ ry 6) :color text-color})
      cp-text (conj {:kind :text :text cp-text :x (+ panel-x 4) :y (+ ry 16) :color (if can? color-text-dim color-err)})
      can? (conj {:kind :fill :x btn-tele-x :y btn-y :w 30 :h btn-h :color color-btn})
      can? (conj {:kind :text :text "TP" :x (+ btn-tele-x 5) :y (+ btn-y 3) :color 0xff202a30})
      true (conj {:kind :fill :x btn-remove-x :y btn-y :w 28 :h btn-h :color color-btn})
      true (conj {:kind :text :text "Del" :x (+ btn-remove-x 4) :y (+ btn-y 3) :color 0xff202a30}))))

(defn- add-row-ops
  "Build draw ops for the 'Add current location' row."
  [idx add-text mx my]
  (let [ry (row-y idx)
        hovering? (point-in? mx my panel-x ry panel-w row-h)
        row-color (if hovering? color-row-hover color-row)
        has-text? (not (str/blank? add-text))
        btn-x (+ panel-x panel-w -60)
        btn-y (+ ry 6)]
    (cond-> []
      true (conj {:kind :fill :x panel-x :y ry :w panel-w :h (dec row-h) :color row-color})
      true (conj {:kind :text
                  :text (if (str/blank? add-text) "[click to add current location]" (str "> " add-text "|"))
                  :x (+ panel-x 4) :y (+ ry 9)
                  :color (if hovering? color-text-normal color-text-dim)})
      has-text? (conj {:kind :fill :x btn-x :y btn-y :w 56 :h 16 :color color-btn})
      has-text? (conj {:kind :text :text "Save" :x (+ btn-x 14) :y (+ btn-y 3) :color 0xff202a30}))))

(defn- info-panel-ops
  "Build draw ops for the right info panel based on hovered row."
  [hovered-loc current-pos threshold]
  (let [lines (if hovered-loc
                (cond-> [(str "Dim: " (or (:world-id hovered-loc) "?"))
                         (format "X: %.0f" (double (or (:x hovered-loc) 0)))
                         (format "Y: %.0f" (double (or (:y hovered-loc) 0)))
                         (format "Z: %.0f" (double (or (:z hovered-loc) 0)))]
                  (:cp-cost hovered-loc) (conj (format "CP: %.0f" (double (:cp-cost hovered-loc))))
                  (and (:cross-dimension? hovered-loc) (not (:can-perform? hovered-loc true)))
                  (conj (format "Need exp > %.2f" (double threshold)))
                  (:distance hovered-loc) (conj (format "Dist: %.0f" (double (:distance hovered-loc)))))
                (if current-pos
                  [(str "Dim: " (or (:world-id current-pos) "?"))
                   (format "X: %.0f" (double (or (:x current-pos) 0)))
                   (format "Y: %.0f" (double (or (:y current-pos) 0)))
                   (format "Z: %.0f" (double (or (:z current-pos) 0)))]
                  ["Hover a location" "to see info"]))]
    (into [{:kind :fill :x info-x :y info-y :w info-w :h info-h :color color-info-bg}]
          (map-indexed
            (fn [i line]
              {:kind :text :text line
               :x (+ info-x 4) :y (+ info-y 6 (* i 20))
               :color color-text-normal})
            lines))))

(defn build-draw-ops
  "Build all draw ops for the current frame. Called from render loop."
  [mx my]
  (let [{:keys [locations selected add-text current-pos limits last-error pending-op]} @screen-state
        hovered-loc (when (and selected (< selected (count locations)))
                      (nth locations selected))
        loc-ops (mapcat #(location-row-ops %1 %2 mx my) (range (count locations)) locations)
        add-idx (count locations)
        add-ops (add-row-ops add-idx add-text mx my)
        info-ops (info-panel-ops hovered-loc current-pos (:cross-dimension-exp-threshold limits))
        status-op (when pending-op
                    {:kind :text :text (str "Pending: " (name pending-op))
                     :x (+ panel-x 120) :y (- panel-y 12) :color color-text-dim})
        error-op (when last-error
                   {:kind :text :text (str "Error: " (name last-error))
                    :x (+ panel-x 4) :y (+ panel-y panel-h -12) :color color-err})
        title-op {:kind :text :text "Location Teleport" :x (+ panel-x 4) :y (- panel-y 12) :color color-text-normal}]
      (concat (keep identity [title-op status-op error-op
                  {:kind :fill :x panel-x :y panel-y :w panel-w :h panel-h :color color-bg}])
            loc-ops
            add-ops
          info-ops)))

;; ============================================================================
;; Interaction
;; ============================================================================

(defn on-mouse-move [mx my]
  (let [{:keys [locations]} @screen-state
        n (count locations)
        hovered (first (filter (fn [i]
                                 (point-in? mx my panel-x (row-y i) panel-w row-h))
                               (range n)))]
    (swap! screen-state assoc :selected hovered)))

(defn handle-screen-click!
  "Handle mouse click. Returns truthy if click was consumed."
  [mx my]
  (let [{:keys [locations add-text pending-op]} @screen-state
        n (count locations)]
    (if pending-op
      true
      (let [loc-result
            (loop [i 0]
              (when (< i n)
                (let [loc (nth locations i)
                      ry (row-y i)
                      can? (:can-perform? loc true)
                      btn-remove-x (+ panel-x panel-w -30)
                      btn-tele-x (+ panel-x panel-w -62)
                      btn-y (+ ry 6)
                      btn-h 16]
                  (cond
                    (and can? (point-in? mx my btn-tele-x btn-y 30 btn-h))
                    (do
                      (swap! screen-state assoc :pending-op :perform :last-error nil)
                      (api/req-location-teleport-perform!
                        (:name loc)
                        (fn [resp]
                          (let [action (action-of resp)
                                snapshot (snapshot-of resp)]
                            (apply-query-response! snapshot)
                            (if (:success? action)
                              (do
                                (fx-registry/dispatch-fx-channel!
                                  nil
                                  :location-teleport/fx-perform-success
                                  action)
                                (swap! screen-state assoc :pending-op nil :last-error nil)
                                (close-screen!))
                              (swap! screen-state assoc :pending-op nil :last-error (:error action))))))
                      true)

                    (point-in? mx my btn-remove-x btn-y 28 btn-h)
                    (do
                      (swap! screen-state assoc :pending-op :remove :last-error nil)
                      (api/req-location-teleport-remove!
                        (:name loc)
                        (fn [resp]
                          (let [action (action-of resp)
                                snapshot (snapshot-of resp)]
                            (apply-query-response! snapshot)
                            (swap! screen-state assoc :pending-op nil :last-error (when-not (:success? action)
                                                                                    (:error action))))))
                      true)

                    :else
                    (recur (inc i))))))]
        (or loc-result
            (let [add-idx n
                  ry (row-y add-idx)
                  btn-x (+ panel-x panel-w -60)
                  btn-y (+ ry 6)]
              (cond
                (and (not (str/blank? add-text))
                     (point-in? mx my btn-x btn-y 56 16))
                (do
                  (swap! screen-state assoc :pending-op :add :last-error nil)
                  (api/req-location-teleport-add!
                    add-text
                    (fn [resp]
                      (let [action (action-of resp)
                            snapshot (snapshot-of resp)]
                        (swap! screen-state assoc :add-text "")
                        (apply-query-response! snapshot)
                        (swap! screen-state assoc :pending-op nil :last-error (when-not (:success? action)
                                                                                (:error action))))))
                  true)

                (point-in? mx my panel-x ry panel-w row-h)
                (do
                  (swap! screen-state assoc :add-mode? true)
                  true)

                :else nil)))))))


(defn handle-char-typed!
  "Handle a character typed while screen is open. Called by platform if supported."
  [ch]
  (let [{:keys [add-mode? add-text pending-op limits]} @screen-state
        max-len (long (or (:max-location-name-length limits) 16))]
    (when (and add-mode? (nil? pending-op))
      (cond
        (= (int ch) 8) ;; backspace
        (when (seq add-text)
          (swap! screen-state update :add-text #(subs % 0 (dec (count %)))))
        ;; Enter key: confirm add if there is text
        (= ch \newline)
        (when (not (str/blank? add-text))
          (let [name add-text]
            (swap! screen-state assoc :add-text "" :pending-op :add :last-error nil)
            (api/req-location-teleport-add!
              name
              (fn [resp]
                (let [action (action-of resp)
                      snapshot (snapshot-of resp)]
                  (apply-query-response! snapshot)
                  (swap! screen-state assoc :pending-op nil :last-error (when-not (:success? action)
                                                                          (:error action))))))))
        (and (>= (int ch) 32) (< (count add-text) max-len))
        (swap! screen-state update :add-text #(str % ch))))))
