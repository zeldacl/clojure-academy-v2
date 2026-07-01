(ns cn.li.ac.client.toast
  "Client-side toast notification overlay.

  Renders transient on-screen message boxes matching the original
  AcademyCraft FreqTransmitterUI StateNotify visual style:
  semi-transparent dark background (0x77272727) with glow border
  (0xaaffffff), centered text, auto-dismiss after TTL.

  Usage from any client-side code:
    (toast/show-toast! {:message-key \"app.my_mod.freq_transmitter.e1\"})"
  (:require [cn.li.mcmod.i18n :as i18n]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))


;; ============================================================================
;; State
;; ============================================================================

(def ^:private default-duration-ms 2000)
(def ^:private fade-duration-ms 400)

(defn- now-ms []
  ;; Use game-time so toast animations pause when the game pauses,
  ;; consistent with the overlay renderer that reads `now-ms` as game time.
  (client-bridge/game-time-ms))

(let [toasts* (volatile! [])]
  ;; ============================================================================
  ;; Public API
  ;; ============================================================================

  (defn show-toast!
    "Queue a toast notification for rendering.

    Required: :message-key — i18n translation key
    Optional: :args — vector of format args
              :duration-ms — override default TTL (ms)"
    ([{:keys [message-key args duration-ms]}]
     (when message-key
       (let [entry {:message-key message-key
                    :args (vec (or args []))
                    :start-ms (now-ms)
                    :end-ms (+ (now-ms) (long (or duration-ms default-duration-ms)))}]
         (vreset! toasts* (conj @toasts* entry))))))

  ;; ============================================================================
  ;; Rendering helpers
  ;; ============================================================================

  (defn- expired?
    [entry now]
    (>= now (:end-ms entry)))

  (defn- alpha
    [entry now]
    (let [remaining (- (:end-ms entry) now)
          fade (long fade-duration-ms)]
      (if (pos? remaining)
        (if (< remaining fade)
          (max 0.0 (/ (double remaining) (double fade)))
          1.0)
        0.0)))

  (defn- renderable-toasts
    "Return active toast entries and cleanup expired ones."
    [now]
    (let [active (remove #(expired? % now) @toasts*)]
      (when-not (= (count active) (count @toasts*))
        (vreset! toasts* active))
      active))


;; ============================================================================
;; Overlay element builder
;; ============================================================================

(defn build-toast-elements
  "Return overlay elements for the currently active toast.
  Called each frame from the overlay plan builder.

  Elements use the same :kind values as the overlay renderer
  (:fill for backgrounds/borders, :text for strings).

  Visual style matches original AcademyCraft drawTextBox:
  - background: 0x77272727 (dark grey, ~47% alpha)
  - glow border: 0xaaffffff (white, ~67% alpha)"
  [screen-width screen-height now-ms]
  (let [now (long (or now-ms (now-ms)))
        active (renderable-toasts now)]
    (if (seq active)
      (let [box-h 32
            pad-x 16  ;; horizontal text padding inside box
            gap 4     ;; spacing between stacked toasts
            ;; Compute max text width across all active toasts for uniform box sizing
            messages (mapv #(i18n/translate (:message-key %)) active)
            max-text-w (long (apply max 0 (map #(client-bridge/font-width %) messages)))
            box-w (+ max-text-w (* 2 pad-x))  ;; dynamic width: text + padding
            x (int (- (/ screen-width 2) (/ box-w 2)))]
        (mapcat (fn [toast idx]
                  (let [a (alpha toast now)
                        msg (nth messages idx)
                        ;; Stack toasts upward from the bottom
                        y (- (int (- screen-height (* screen-height 0.35)))
                             (* idx (+ box-h gap)))
                        bg-alpha (int (* 119 a))   ;; 0x77 = 119
                        glow-alpha (int (* 170 a)) ;; 0xaa = 170
                        text-alpha (int (* 255 a))
                        ;; Center text within box
                        text-w (long (client-bridge/font-width msg))
                        text-x (+ x (quot (- box-w text-w) 2))]
                    [{:kind :fill
                      :x x :y y :w box-w :h box-h
                      :color {:r 39 :g 39 :b 39 :a bg-alpha}}
                     {:kind :fill  ;; glow border top
                      :x (dec x) :y (dec y) :w (+ box-w 2) :h 1
                      :color {:r 255 :g 255 :b 255 :a glow-alpha}}
                     {:kind :fill  ;; glow border bottom
                      :x (dec x) :y (+ y box-h) :w (+ box-w 2) :h 1
                      :color {:r 255 :g 255 :b 255 :a glow-alpha}}
                     {:kind :fill  ;; glow border left
                      :x (dec x) :y y :w 1 :h box-h
                      :color {:r 255 :g 255 :b 255 :a glow-alpha}}
                     {:kind :fill  ;; glow border right
                      :x (+ x box-w) :y y :w 1 :h box-h
                      :color {:r 255 :g 255 :b 255 :a glow-alpha}}
                     {:kind :text
                      :x text-x :y (+ y 9)
                      :text msg
                      :color {:r 255 :g 255 :b 255 :a text-alpha}}]))
                active
                (range (count active))))
      [])))


;; ============================================================================
;; Test support
;; ============================================================================

(defn reset-toasts-for-test!
  []
  (vreset! toasts* [])
  nil)

(defn toasts-snapshot
  []
  @toasts*))
