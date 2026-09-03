(ns cn.li.ac.terminal.client.apps.tutorial-reactive
  "Tutorial catalogue and markdown content on Presentation Runtime.

   Tutorial navigation, recipe expansion, tag paging, preview projection and
   first-open logo animation remain AC-owned; the artifact and input lifecycle
   are owned by Presentation Runtime."
  (:require [cn.li.ac.ability.util.uuid :as uuid]
            [clojure.string :as str]
            [cn.li.ac.gui.presentation-application :as application]
            [cn.li.ac.gui.presentation :as presentation]
            [cn.li.ac.tutorial.client.preview-reactive :as preview]
            [cn.li.ac.tutorial.client.state :as client-state]
            [cn.li.ac.tutorial.content :as tut-content]
            [cn.li.ac.tutorial.markdown-renderer :as markdown]
            [cn.li.ac.tutorial.messages :as tut-msg]
            [cn.li.ac.tutorial.registry :as tut-registry]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.network.client :as net-client]))

(def ^:private brief-width 130)
(def ^:private logo3-layout-y 55.625)
(def ^:private logo3-start-y 63.0)   ;; unscaled XML offset during blendy
(def ^:private logo3-final-y -36.0)

;; logo1-anchor scale 0.25 — glow dslots are screen-pixel offsets from logo1 center.
(def ^:private glow-s 0.25)
(def ^:private glow-ln 500.0)
(def ^:private glow-ln2 300.0)
(def ^:private glow-cl 50.0)

(def ^:private logo-timings
  [[:logo3 100.0 300.0] [:logo2 650.0 300.0] [:logo1 1300.0 300.0] [:logo0 1750.0 300.0]])

(defonce ^:private screen-tick* (atom nil))

(defn- now-ms []
  (System/currentTimeMillis))

(defn- logo-fade-alpha [elapsed-ms start-delay-ms duration-ms]
  (let [t (max 0.0 (min 1.0 (/ (- elapsed-ms start-delay-ms) duration-ms)))]
    (float t)))

(defn- alpha->rgba [a]
  [1.0 1.0 1.0 (float (max 0.0 (min 1.0 (double a))))])

(defn- static-glow-geom
  "Final/static glow endpoints — matches main setup-static-glow!."
  []
  (let [s glow-s ln glow-ln ln2 glow-ln2]
    {:glow-visible? true
     :glow-right-x0 (* s (- ln ln2))
     :glow-right-x1 (* s ln)
     :glow-left-x0 (* s (- ln))
     :glow-left-x1 (* s (- ln2 ln))
     :glow-y (* s 15.0)
     :glow-line-w (max 1.0 (* s 5.0))
     :glow-sz (max 1.0 (* s 5.0))}))

(defn- glow-geom-at
  "Staged glow growth starting at elapsed 400ms (main attach-first-open-animation!)."
  [elapsed]
  (let [s glow-s ln glow-ln ln2 glow-ln2 cl glow-cl
        dt (- (double elapsed) 400.0)
        b1 300.0 b2 200.0
        base {:glow-y (* s 15.0)
              :glow-line-w (max 1.0 (* s 5.0))
              :glow-sz (max 1.0 (* s 5.0))}]
    (cond
      (< dt 0.0)
      (assoc base :glow-visible? false
             :glow-right-x0 0.0 :glow-right-x1 0.0
             :glow-left-x0 0.0 :glow-left-x1 0.0)

      (< dt b1)
      (let [len (* ln (/ dt b1))]
        (if (> len cl)
          (assoc base :glow-visible? true
                 :glow-right-x0 (* s cl) :glow-right-x1 (* s len)
                 :glow-left-x0 (* s (- len)) :glow-left-x1 (* s (- cl)))
          (assoc base :glow-visible? false
                 :glow-right-x0 0.0 :glow-right-x1 0.0
                 :glow-left-x0 0.0 :glow-left-x1 0.0)))

      :else
      (let [ldt (min (- dt b1) b2)
            len2 (+ (- ln cl cl) (* (- ln2 (- ln cl cl)) (/ ldt b2)))]
        (assoc base :glow-visible? true
               :glow-right-x0 (* s (- ln len2)) :glow-right-x1 (* s ln)
               :glow-left-x0 (* s (- ln)) :glow-left-x1 (* s (- len2 ln)))))))

(defn- tutorial-title [lang tutorial]
  (or (when-let [id (:id tutorial)]
        (:title (tut-content/load-tutorial-content lang id)))
      (some-> tutorial :id name)
      ""))

(defn- markdown-lines
  ([text misaka-id] (markdown-lines text misaka-id markdown/max-content-width))
  ([text misaka-id max-width]
   (vec
     (mapcat (fn [segment]
               (if (= :image (:type segment))
                 [{:label (str "[image] " (:texture-path segment))
                   :font-size (or (:font-size segment) markdown/default-font-size)}]
                 (map (fn [line]
                        {:label line
                         :font-size (or (:font-size segment) markdown/default-font-size)})
                      (str/split-lines (str (:text segment))))))
             (markdown/render-segments (or text "") misaka-id max-width)))))

(defn- active? [player-uuid tut]
  (or (:default-installed? tut)
      (and (client-state/ready?)
           (client-state/is-activated? player-uuid (:id tut)))))

(defn- request-sync! []
  (when-let [owner (runtime-hooks/default-client-owner)]
    (net-client/send-to-server owner (tut-msg/msg-id :tutorial/request-sync) {}
      (fn [response]
        (when response (client-state/apply-sync! response))))))

(defn- tutorial-items [entries player-uuid lang]
  (let [ordered (sort-by (fn [t] (if (active? player-uuid t) 0 1)) entries)]
    (mapv (fn [t]
            {:label (str (when-not (active? player-uuid t) "[locked] ")
                          (tutorial-title lang t))
             :action-label "Open"
             :tutorial-id (:id t)})
          ordered)))

(defn- current-content [ctx]
  (let [{:keys [lang player-uuid current-tut-id]} @ctx
        ;; Open with no selection until the player picks an entry (matches main).
        cd (if current-tut-id
             (or (tut-content/load-tutorial-content lang current-tut-id) {})
             {})
        misaka (client-state/get-misaka-id player-uuid)]
    {:brief-lines (markdown-lines (:brief cd) misaka brief-width)
     :content-lines (markdown-lines (:content cd) misaka)}))

(defn- current-preview [ctx]
  (let [pvs (:pvs @ctx)
        vg (preview/current-view-group pvs)
        view (preview/current-sub-view pvs)
        groups (or (:view-groups @pvs) [])]
    {:tag-items (mapv (fn [[idx group]]
                        (let [tag (or (:tag group) :view)
                              src (or (get preview/tag-textures tag)
                                      (get preview/tag-textures :view))]
                          {:kind :image
                           :src src
                           :x 1.0 :y 1.0 :w 16.0 :h 16.0
                           :tag-index idx
                           :label (or (:display-text group) "")}))
                      (map-indexed vector groups))
     :preview-items (if view (preview/preview-items view) [])
     :tag-tooltip (or (when-let [idx (:hovered-tag @ctx)]
                        (when (number? idx)
                          (:display-text (nth groups idx nil))))
                      "")
     :button-left {:label "Previous" :visible? (> (count (or (:sub-views vg) [])) 1)}
     :button-right {:label "Next" :visible? (> (count (or (:sub-views vg) [])) 1)}}))

(defn- anim-overlay
  "Project logo/left-bg/glow animation channels into snapshot fields."
  [ctx]
  (let [{:keys [phase anim-start-ms logo-alphas logo3-y left-bg-alpha
                list-visible? panels-visible? logos-visible?
                glow-visible? glow-right-x0 glow-right-x1
                glow-left-x0 glow-left-x1 glow-y glow-line-w glow-sz]} @ctx
        alphas (or logo-alphas {})
        static (static-glow-geom)]
    {:phase phase
     :animating? (contains? #{:first-open :fade-out} phase)
     :list-visible? (boolean list-visible?)
     :panels-visible? (boolean panels-visible?)
     :logos-visible? (boolean logos-visible?)
     :glow-visible? (boolean (if (nil? glow-visible?)
                               (:glow-visible? static)
                               glow-visible?))
     :glow-right-x0 (float (or glow-right-x0 (:glow-right-x0 static)))
     :glow-right-x1 (float (or glow-right-x1 (:glow-right-x1 static)))
     :glow-left-x0 (float (or glow-left-x0 (:glow-left-x0 static)))
     :glow-left-x1 (float (or glow-left-x1 (:glow-left-x1 static)))
     :glow-y (float (or glow-y (:glow-y static)))
     :glow-line-w (float (or glow-line-w (:glow-line-w static)))
     :glow-sz (float (or glow-sz (:glow-sz static)))
     :left-bg-rgba (alpha->rgba (or left-bg-alpha 1.0))
     :logo0-rgba (alpha->rgba (get alphas :logo0 0.0))
     :logo1-rgba (alpha->rgba (get alphas :logo1 (if (= phase :idle-glow) 1.0 0.0)))
     :logo2-rgba (alpha->rgba (get alphas :logo2 0.0))
     :logo3-rgba (alpha->rgba (get alphas :logo3 0.0))
     :logo3-y (float (+ logo3-layout-y
                        (- (float (or logo3-y logo3-final-y)) logo3-final-y)))
     :anim-start-ms anim-start-ms}))

(defn- snapshot [ctx]
  (let [{:keys [entries player-uuid lang current-tut-id]} @ctx
        title (if current-tut-id
                (tutorial-title lang (tut-registry/tutorial-by-id current-tut-id))
                "Select a tutorial")]
    (merge {:title "MisakaCloud Terminal"
            :status title
            :tutorial-items (tutorial-items entries player-uuid lang)
            :selected 0}
           (anim-overlay ctx)
           (current-content ctx)
           (current-preview ctx))))

(defn- present-ctx! [ctx vm]
  (when vm
    (presentation/present! vm (snapshot ctx))))

(defn- mark-first-open-done! []
  (when-let [owner (runtime-hooks/default-client-owner)]
    (net-client/send-to-server owner (tut-msg/msg-id :tutorial/mark-first-open-done) {} nil))
  (client-state/apply-sync! {:first-open? false}))

(defn- tick-first-open! [ctx elapsed]
  (let [alphas (into {}
                     (map (fn [[id start dur]]
                            [id (logo-fade-alpha elapsed start dur)])
                          logo-timings))
        blendy-start 700.0
        blendy-dur 400.0
        blendy-t (max 0.0 (min 1.0 (/ (- elapsed blendy-start) blendy-dur)))
        logo3-y (+ logo3-start-y (* (- logo3-final-y logo3-start-y) blendy-t))
        left-start 1750.0
        left-dur 300.0
        left-a (if (>= elapsed left-start)
                 (max 0.0 (min 1.0 (/ (- elapsed left-start) left-dur)))
                 0.0)
        list-visible? (>= elapsed 2400.0)
        glow (glow-geom-at elapsed)]
    (swap! ctx merge glow
           {:logo-alphas alphas
            :logo3-y logo3-y
            :left-bg-alpha left-a
            :list-visible? list-visible?
            :logos-visible? true
            :panels-visible? false})
    (when (and list-visible? (not (:first-open-marked? @ctx)))
      (mark-first-open-done!)
      (swap! ctx merge (static-glow-geom)
             {:first-open-marked? true :phase :idle-glow}))))

(defn- tick-fade-out! [ctx elapsed]
  (let [a (float (max 0.0 (- 1.0 (/ elapsed 300.0))))]
    (swap! ctx assoc
           :logo-alphas {:logo0 a :logo1 a :logo2 a :logo3 a}
           :logos-visible? (pos? a)
           :glow-visible? (pos? a))
    (when (<= a 0.0)
      (swap! ctx assoc :phase :content :logos-visible? false :glow-visible? false))))

(defn- frame-tick! [ctx vm]
  (let [{:keys [phase anim-start-ms]} @ctx
        elapsed (double (- (now-ms) (or anim-start-ms (now-ms))))]
    (case phase
      :first-open (tick-first-open! ctx elapsed)
      :fade-out (tick-fade-out! ctx elapsed)
      nil)
    (when (contains? #{:first-open :fade-out} (:phase @ctx))
      (present-ctx! ctx vm))))

(defn- begin-fade-out! [ctx]
  (swap! ctx assoc :phase :fade-out :anim-start-ms (now-ms)
         :panels-visible? true :list-visible? true))

(defn- select-tutorial! [ctx tut-id]
  (when-let [tut (tut-registry/tutorial-by-id tut-id)]
    (let [first-selection? (nil? (:current-tut-id @ctx))]
      (when first-selection?
        (begin-fade-out! ctx))
      (swap! ctx assoc
             :current-tut-id (:id tut)
             :panels-visible? true
             :list-visible? true
             :pvs (atom (preview/create-preview-state (:id tut)))))))

(defn- dispatch! [ctx vm action current]
  (let [selected-item (:selected-item current)]
    (case action
      :tutorial/select
      (select-tutorial! ctx (:tutorial-id selected-item))

      :tutorial/tag
      (when-let [idx (:tag-index selected-item)]
        (preview/switch-view-group! (:pvs @ctx) (int idx)))

      :tutorial/tag-hover
      (if (= :enter (:hover-event current))
        (swap! ctx assoc :hovered-tag (:tag-index selected-item))
        (swap! ctx assoc :hovered-tag nil))

      :tutorial/prev
      (preview/cycle-sub-view! (:pvs @ctx) :prev)

      :tutorial/next
      (preview/cycle-sub-view! (:pvs @ctx) :next)

      :input/scroll nil
      nil)
    (let [snap (snapshot ctx)]
      (present-ctx! ctx vm)
      snap)))

(defn- initial-phase [player-uuid]
  (if (client-state/first-open? player-uuid)
    :first-open
    :idle-glow))

(defn open! [player]
  (let [player-uuid (uuid/player-uuid player)
        _ (client-state/ensure-client-state! player-uuid)
        _ (request-sync!)
        lang (tut-content/current-lang)
        entries (vec (tut-registry/all-tutorials))
        phase (initial-phase player-uuid)
        glow0 (if (= phase :idle-glow)
                (static-glow-geom)
                {:glow-visible? false
                 :glow-right-x0 0.0 :glow-right-x1 0.0
                 :glow-left-x0 0.0 :glow-left-x1 0.0
                 :glow-y (* glow-s 15.0)
                 :glow-line-w (max 1.0 (* glow-s 5.0))
                 :glow-sz (max 1.0 (* glow-s 5.0))})
        ctx (atom (merge {:player-uuid player-uuid
                          :lang lang
                          :entries entries
                          :current-tut-id nil
                          :pvs (atom (preview/create-preview-state :welcome))
                          :phase phase
                          :anim-start-ms (now-ms)
                          :logo-alphas (if (= phase :idle-glow)
                                         {:logo0 0.0 :logo1 1.0 :logo2 0.0 :logo3 0.0}
                                         {:logo0 0.0 :logo1 0.0 :logo2 0.0 :logo3 0.0})
                          :logo3-y logo3-final-y
                          :left-bg-alpha (if (= phase :idle-glow) 1.0 0.0)
                          :list-visible? (not= phase :first-open)
                          :panels-visible? false
                          :logos-visible? true}
                         glow0))
        vm-box (atom nil)
        vm (application/mount!
             (str "application/tutorial/" player-uuid)
             "MisakaCloud Terminal"
             (snapshot ctx)
             (fn [action current] (dispatch! ctx @vm-box action current))
             (fn []
               (reset! screen-tick* nil)
               (bridge/close-screen!))
             :screen
             :academy.app/tutorial)]
    (reset! vm-box vm)
    (reset! screen-tick* (fn [] (frame-tick! ctx vm)))
    vm))

(defn screen-tick!
  "Called once per screen frame from the Presentation host refresh path."
  []
  (when-let [f @screen-tick*]
    (f)))
