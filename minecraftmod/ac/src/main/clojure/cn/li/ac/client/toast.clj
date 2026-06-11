(ns cn.li.ac.client.toast
  "Client-side toast notification overlay.

  Renders transient on-screen message boxes matching the original
  AcademyCraft FreqTransmitterUI StateNotify visual style:
  semi-transparent dark background (0x77272727) with glow border
  (0xaaffffff), centered text, auto-dismiss after TTL.

  Usage from any client-side code:
    (toast/show-toast! {:message-key \"app.my_mod.freq_transmitter.e1\"})"
  (:require [cn.li.mcmod.i18n :as i18n]))


;; ============================================================================
;; State
;; ============================================================================

(def ^:private default-duration-ms 2000)
(def ^:private fade-duration-ms 400)

(defonce ^:private toasts* (atom []))

(defn- now-ms []
  (System/currentTimeMillis))


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
       (swap! toasts* conj entry)))))


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
      (reset! toasts* active))
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
    (if-let [toast (last active)]
      (let [a (alpha toast now)
            msg (i18n/translate (:message-key toast))
            ;; Layout: centered horizontally, ~35% from bottom
            box-w 260
            box-h 32
            x (int (- (/ screen-width 2) (/ box-w 2)))
            y (int (- screen-height (* screen-height 0.35)))
            bg-alpha (int (* 119 a))   ;; 0x77 = 119
            glow-alpha (int (* 170 a)) ;; 0xaa = 170
            text-alpha (int (* 255 a))]
        [{:kind :fill
          :x x :y y :w box-w :h box-h
          :color {:r 39 :g 39 :b 39 :a bg-alpha}}
         ;; Glow border top
         {:kind :fill
          :x (dec x) :y (dec y) :w (+ box-w 2) :h 1
          :color {:r 255 :g 255 :b 255 :a glow-alpha}}
         ;; Glow border bottom
         {:kind :fill
          :x (dec x) :y (+ y box-h) :w (+ box-w 2) :h 1
          :color {:r 255 :g 255 :b 255 :a glow-alpha}}
         ;; Glow border left
         {:kind :fill
          :x (dec x) :y y :w 1 :h box-h
          :color {:r 255 :g 255 :b 255 :a glow-alpha}}
         ;; Glow border right
         {:kind :fill
          :x (+ x box-w) :y y :w 1 :h box-h
          :color {:r 255 :g 255 :b 255 :a glow-alpha}}
         ;; Text
         {:kind :text
          :x (+ x 12) :y (+ y 9)
          :text msg
          :color {:r 255 :g 255 :b 255 :a text-alpha}}])
      [])))


;; ============================================================================
;; Test support
;; ============================================================================

(defn reset-toasts-for-test!
  []
  (reset! toasts* [])
  nil)

(defn toasts-snapshot
  []
  @toasts*)
