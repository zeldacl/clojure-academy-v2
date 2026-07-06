(ns cn.li.ac.tutorial.client.notification
  "CLIENT-ONLY: Tutorial activation HUD notification — slide-in overlay
  matching upstream AcademyCraft NotifyUI.

  Four-phase animation:
    1. blend-in  (0 → 0.5s): background + icon fade in, icon at start position
    2. scan/slide (0.5 → 1.0s): icon slides from (420,42) to (34,42) with sine ease
    3. hold       (1.0 → 5.7s): full display, all elements visible
    4. blend-out  (5.7 → 6.0s): all elements fade out

  Rendered via overlay element builder (same pipeline as toast.clj).
  Called from client-state/apply-sync! when new tutorial activations arrive."
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.ac.tutorial.content :as tutorial-content]
            [cn.li.mcmod.i18n :as i18n]
            [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Constants
;; ============================================================================

(def ^:private blend-in-time 0.5)
(def ^:private scan-time 0.5)
(def ^:private total-keep-time 6.0)
(def ^:private blend-out-time 0.3)

(def ^:private icon-start-x 420.0)
(def ^:private icon-start-y 42.0)
(def ^:private icon-end-x 34.0)
(def ^:private icon-end-y 42.0)
(def ^:private icon-size 83.0)

(def ^:private bg-w 517.0)
(def ^:private bg-h 170.0)
(def ^:private bg-scale 0.25)

(def ^:private title-x 137)
(def ^:private title-y 40)
(def ^:private content-x 137)
(def ^:private content-y 72)

(def ^:private bg-tex (modid/asset-path "textures/guis" "notification/back.png"))
(def ^:private icon-tex (modid/asset-path "textures" "tutorial/update_notify.png"))

;; ============================================================================
;; State
;; ============================================================================

(defn- notifications-atom
  []
  (if-let [fw-atom (fw/fw-atom)]
    (or (get-in @fw-atom [:service :client-ui :tutorial-notifications])
        (let [a (atom [])]
          (swap! fw-atom assoc-in [:service :client-ui :tutorial-notifications] a)
          a))
    (atom [])))

(defn- lerp [a b t]
  (+ a (* t (- b a))))

(defn- sine-ease [t]
  (Math/sin (* t (/ Math/PI 2.0))))

;; ============================================================================
;; Public API
;; ============================================================================

(defn show-activation-toasts!
  "Queue a HUD slide-in notification for each newly activated tutorial.
  Replaces the old simple toast with a rich animated overlay matching
  upstream NotifyUI behavior.

  Args:
    new-tut-ids — set of keyword tutorial ids that were just activated"
  [new-tut-ids]
  (when (seq new-tut-ids)
    (try
      (doseq [tut-id new-tut-ids]
        (let [title (try
                      (:title (tutorial-content/load-tutorial-content (name tut-id)))
                      (catch Throwable _ (name tut-id)))]
          (reset! (notifications-atom) (conj @(notifications-atom)
                 {:title title
                  :tut-id tut-id
                  :start-sec nil}))))
      (catch Throwable t
        (log/warn :tutorial-notification "Failed to queue activation toast:" (ex-message t))))))

;; ============================================================================
;; Overlay element builder
;; ============================================================================

(defn build-notification-elements!
  "Return overlay elements for the currently active notification.
  Called each frame from the overlay plan builder (client_ui_hooks.clj).

  Elements: {:kind :blit-texture ...} for textures, {:kind :text ...} for strings."
  [screen-width screen-height now-ms]
  (let [now-sec (/ (double now-ms) 1000.0)
        ;; Single atomic swap! — init start-sec for new notifications and
        ;; remove expired ones. Avoids read-then-reset! race with enqueue.
        active (let [new-val ((fn [notifs]
                                 (let [needs-init? (some #(nil? (:start-sec %)) notifs)
                                       initialized (if needs-init?
                                                     (mapv (fn [n] (if (:start-sec n) n (assoc n :start-sec now-sec)))
                                                           notifs)
                                                     notifs)]
                                   (filterv #(<= (- now-sec (:start-sec %)) total-keep-time) initialized)))
                               @(notifications-atom))]
                 (reset! (notifications-atom) new-val)
                 new-val)]
    (if-let [notif (last active)]
      (let [dt (- now-sec (:start-sec notif))
            ;; Compute per-element alpha based on animation phase
            bg-alpha (cond
                       (< dt blend-in-time)     (min 1.0 (/ dt 0.3))          ;; background fade-in over 0.3s
                       (> dt (- total-keep-time blend-out-time))
                       (max 0.0 (/ (- total-keep-time dt) blend-out-time))   ;; fade-out
                       (< dt (+ blend-in-time scan-time)) 1.0               ;; full during slide
                       :else 1.0)                                            ;; full during hold
            icon-alpha (cond
                         (< dt 0.2)             0.0                           ;; icon delayed start
                         (< dt blend-in-time)   (max 0.0 (min 1.0 (/ (- dt 0.2) 0.3)))
                         (> dt (- total-keep-time blend-out-time))
                         (max 0.0 (/ (- total-keep-time dt) blend-out-time))
                         :else 1.0)
            text-alpha (cond
                         (< dt blend-in-time)   0.0                           ;; text appears during slide
                         (< dt (+ blend-in-time scan-time))
                         (let [sp (/ (- dt blend-in-time) scan-time)]
                           (sine-ease sp))
                         (> dt (- total-keep-time blend-out-time))
                         (max 0.0 (/ (- total-keep-time dt) blend-out-time))
                         :else 1.0)
            ;; Icon slide position (sine ease matching upstream MathHelper.sin)
            icon-progress (if (< dt blend-in-time)
                           0.0
                           (if (< dt (+ blend-in-time scan-time))
                             (sine-ease (/ (- dt blend-in-time) scan-time))
                             1.0))
            icon-x (lerp icon-start-x icon-end-x icon-progress)
            icon-y (lerp icon-start-y icon-end-y icon-progress)
            ;; Scaled background position
            bg-scaled-w (* bg-w bg-scale)
            bg-scaled-h (* bg-h bg-scale)
            ;; Title text
            title (i18n/translate "tutorial.my_mod.update")
            ;; Content text
            content (:title notif)]
        [{:kind :blit-texture
          :texture bg-tex
          :x 0 :y 15
          :w bg-scaled-w :h bg-scaled-h
          :alpha bg-alpha}
         {:kind :blit-texture
          :texture icon-tex
          :x icon-x :y icon-y
          :w icon-size :h icon-size
          :alpha icon-alpha}
         {:kind :text
          :x title-x :y title-y
          :text title
          :color {:r 255 :g 255 :b 255 :a (int (* 255 text-alpha))}}
         {:kind :text
          :x content-x :y content-y
          :text content
          :color {:r 255 :g 255 :b 255 :a (int (* 255 text-alpha))}}])
      [])))
