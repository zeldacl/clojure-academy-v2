(ns cn.li.ac.block.developer.panel
  "Classic AcademyCraft `page_developer.xml` bindings + right-panel mode dispatch.

  Right panel (`parent_right/area`) dynamically switches between:
  - :skill-tree   — player has category → render skill nodes + click-to-learn
  - :console      — player has no category → text console with 'learn' command
  - :reset-console — player holds magnetic coil → reset console

  Left panel bindings remain as before. btn_upgrade → overlay (not separate screen).
  Wireless button → overlay popup (not tab switch).
  Sync rate bar uses fixed device property from developer-specs."
  (:require [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [clojure.string :as str]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.gui.xml-parser :as cgui-doc]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.i18n :as i18n]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.gui.tech-ui-common :as tech-ui]
            [cn.li.ac.ability.registry.category :as acat]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.ac.ability.registry.skill :as skill]
            [cn.li.ac.ability.domain.developer :as developer]
            [cn.li.ac.ability.util.balance :as bal]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.ability.rules.learning-rules :as learning-rules]
            [cn.li.ac.ability.config :as cfg]
            [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.client.screens.skill-tree :as skill-tree]
            [cn.li.ac.block.developer.console :as dev-console]
            [cn.li.ac.item.special-items :as special-items]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.mcmod.gui.container.action-payload :as action-payload]
            [cn.li.mcmod.gui.container-state :as container-state]
            [cn.li.mcmod.platform.be :as platform-be]))

;; ============================================================================
;; Helpers — widget manipulation
;; ============================================================================

(defn- textbox-of [widget]
  (comp/get-widget-component widget :textbox))

(defn- set-text-path! [root path text]
  (when-let [w (cgui-core/find-widget root path)]
    (when-let [tb (textbox-of w)]
      (comp/set-text! tb (str text)))))

(defn- set-progress-path! [root path ^double v]
  (when-let [w (cgui-core/find-widget root path)]
    (when-let [pb (comp/get-widget-component w :progressbar)]
      (comp/set-progress! pb v))))

(def ^:private max-console-lines 50)

(defn- clamp-lines
  "Keep at most `max-console-lines` entries, dropping oldest first."
  [lines]
  (let [cnt (count lines)]
    (if (> cnt max-console-lines)
      (subvec (vec lines) (- cnt max-console-lines))
      (vec lines))))

(defn- set-drawtexture-path! [root path texture-path]
  (when (string? texture-path)
    (when-let [w (cgui-core/find-widget root path)]
      (when-let [dt (comp/get-drawtexture-component w)]
        (comp/set-texture! dt texture-path)))))

(defn- set-visible-path! [root path visible?]
  (when-let [w (cgui-core/find-widget root path)]
    (cgui-core/set-visible! w visible?)))

;; ============================================================================
;; Left panel — ability info and machine status (mostly unchanged)
;; ============================================================================

(defn- dev-msg [action]
  (msg-registry/msg :developer action))

(defn- req-start-development!
  "Send a development request. If container has :on-dev-start callback,
  delegates to it (for portable/instant dev). Otherwise sends network
  message for timed block-based development."
  [container action & [extra callback]]
  (if-let [handler (:on-dev-start container)]
    ;; Portable/instant dev path — delegate to container's handler
    (handler action extra callback)
    ;; Block dev path — send network message for timed session
    (let [owner (try (container-state/owner-from-container container)
                     (catch Exception e
                       (log/error "[req-start-development!] owner error:" (ex-message e))
                       nil))
          msg-id (dev-msg :start-development)
          payload (action-payload/action-payload container (merge {:action action} extra))]
      (log/info "[req-start-development!] sending" msg-id "action=" action
                "owner=" (pr-str owner) "payload=" (pr-str payload))
      (net-client/send-to-server
        owner
        msg-id
        payload
        (fn [resp]
          (log/info "[req-start-development!] response:" (pr-str resp))
          (when callback (callback resp)))))))

(defn- texture-path-from-category-icon [icon-str]
  (when (string? icon-str)
    (if (str/starts-with? icon-str "textures/")
      (modid/asset-path "textures" (subs icon-str (count "textures/")))
      (modid/asset-path "textures" icon-str))))

(defn- default-ability-icon-path []
  (modid/asset-path "textures" "guis/icons/icon_nocategory.png"))

(defn- normalize-tier [tier]
  (let [k (keyword (or tier :normal))]
    (if (developer/developer-type? k) k :normal)))

(defn- current-developer-type [container]
  (let [tile (:tile-entity container)
        block-tier (when tile
                     (some-> (platform-be/get-block-id tile)
                             developer/developer-type-for-block-id))
        state-tier (some-> (:tier container) deref normalize-tier)]
    (or block-tier state-tier :normal)))

(defn- category-ui-model
  [{:keys [ad cat dev? developer-type energy max-energy bandwidth]}]
  (let [cat-id (:category-id ad)
        has-category? (boolean cat)
        lvl (if has-category? (long (or (:level ad) 1)) 0)
        level-prog (double (:level-progress ad 0.0))
        skills-at-level (when cat-id
                          (skill-query/get-controllable-skills-at-level cat-id lvl))
        cat-rate (when cat-id (acat/get-prog-incr-rate cat-id))
        thresh (when (and cat-id (not (>= lvl 5)))
                 (learning-rules/level-up-threshold ad
                   skills-at-level cat-rate (cfg/prog-incr-rate)))
        cat-prog01 (if has-category?
                     (if (and thresh (pos? thresh))
                       (max 0.02 (bal/clamp01 (/ level-prog thresh)))
                       (if (>= lvl 5) 1.0 0.02))
                     0.0)
        can-upgrade? (and has-category? (< lvl 5)
                          (developer/gte? developer-type (developer/min-for-level (inc lvl)))
                          (if thresh (>= level-prog thresh) true))
        ability-name (if has-category? (i18n/translate (:name-key cat)) "N/A")
        icon-path (if has-category?
                    (or (some-> cat :icon texture-path-from-category-icon)
                        (default-ability-icon-path))
                    (default-ability-icon-path))
        exp-label (if has-category?
                    (if (>= lvl 5) "MAX"
                        (if thresh (format "EXP %.0f%%" (* 100.0 cat-prog01)) "EXP 0%"))
                    "EXP 0%")
        level-label (cond dev? "Learning"
                          :else (format "Lv.%d" lvl))]  ;; matching original levelDesc
    {:has-category? has-category?
     :can-upgrade? can-upgrade?
     :ability-name ability-name
     :icon-path icon-path
     :exp-label exp-label
     :level-label level-label
     :cat-prog01 cat-prog01
     :sync-rate (:bandwidth (developer/developer-spec developer-type) 0.7)  ;; fixed device property
     :power01 (bal/clamp01 (/ energy max-energy))}))

(declare current-ui-model-in-session)
(declare render-skill-tree-area!)

(defn- current-ui-model [container player]
  (current-ui-model-in-session
    (runtime-hooks/require-player-state-session-id "developer.panel")
    container player))

(defn- current-ui-model-in-session
  [session-id container player]
  (let [energy (double (or @(:energy container) 0.0))
        max-energy (max 1.0 (double (or @(:max-energy container) 1.0)))
        dev? (boolean (or @(:is-developing container) false))
        uuid-str (when player (uuid/player-uuid player))
        pstate (when uuid-str (store/get-player-state* session-id uuid-str))
        ad (:ability-data pstate)
        cat-id (:category-id ad)
        cat (when cat-id (acat/get-category cat-id))
        developer-type (current-developer-type container)]
    (category-ui-model {:ad ad :cat cat :dev? dev? :developer-type developer-type
                        :energy energy :max-energy max-energy
                        :bandwidth (:bandwidth (developer/developer-spec developer-type) 0.7)})))

;; ============================================================================
;; Overlay helpers
;; ============================================================================

(def ^:private cover-fade-duration 0.2)
(def ^:private cover-max-alpha 0.7)

(defn- time-secs [] (/ (double (client-bridge/game-time-ms)) 1000.0))

(defn- create-fading-cover
  "Create a fading black overlay matching original Cover component.
  Returns {:cover <widget> :end-cover! <fn>}."
  [parent root]
  (let [[pw ph] (cgui-core/get-size parent)
        cover (cgui-core/create-widget :pos [0 0] :size [pw ph])
        _ (comp/add-component! cover (comp/draw-texture nil (comp/mono-blend 0.0 0.0)))
        dt-comp (first (filter #(= (or (:kind %) (::kind %)) :drawtexture) @(:components cover)))
        state (atom {:start-time (time-secs) :ended? false :end-start-time 0.0})]
    (events/on-frame cover
      (fn [_]
        (let [{:keys [start-time ended? end-start-time]} @state
              t (time-secs) elapsed (- t (if ended? end-start-time start-time))
              src (min 1.0 (/ (max 0.0 elapsed) cover-fade-duration))
              alpha (if ended? (* cover-max-alpha (- 1.0 src)) (* cover-max-alpha src))]
          (when dt-comp
            (swap! (:state dt-comp) assoc :color (comp/mono-blend 0.0 (max 0.0 alpha))))
          (let [[rw rh] (cgui-core/get-size root)] (cgui-core/set-size! cover rw rh))
          (when (and ended? (<= alpha 0.0)) (cgui-core/remove-widget! root cover)))))
    (let [end-fn (fn [] (when-not (:ended? @state) (swap! state assoc :ended? true :end-start-time (time-secs))))]
      {:cover cover :end-cover! end-fn})))

(defn- register-overlay! [root end-fn]
  (swap! (:metadata root) update :cover-end-fns (fn [s] (conj (or s []) end-fn))))

(defn- unregister-overlay! [root end-fn]
  (swap! (:metadata root) update :cover-end-fns (fn [s] (vec (remove #{end-fn} (or s []))))))

(defn- create-centered-panel
  "Create a centered panel widget inside cover for overlay content."
  [cover panel-width panel-height]
  (let [[cw ch] (cgui-core/get-size cover)
        cx (int (/ (- cw panel-width) 2))
        cy (int (/ (- ch panel-height) 2))
        panel (cgui-core/create-widget :pos [cx cy] :size [panel-width panel-height])]
    (cgui-core/add-widget! cover panel)
    panel))

;; ============================================================================
;; Skill detail overlay
;; ============================================================================

(defn- create-skill-detail-overlay!
  "Skill detail overlay — delegates visual rendering to skill-tree/detail-popup-ops.
  CGUI layer handles only: fading cover, click-to-close, LEARN click dispatch."
  [root container skill-id _developer-type]
  (let [{:keys [cover end-cover!]} (create-fading-cover root root)
        close-fn (fn [] (unregister-overlay! root end-cover!) (end-cover!))
        _ (register-overlay! root end-cover!)
        dev-type (or _developer-type :normal)
        dev-spec (developer/developer-spec dev-type)
        skill-spec (skill/get-skill skill-id)
        skill-name (or (:name skill-spec) (name skill-id) "Unknown")
        skill-level (int (or (:level skill-spec) 1))
        est-consumption (long (* (:cps dev-spec 700.0)
                                 (+ 3 (* skill-level skill-level 0.5))))
        session-id (runtime-hooks/require-player-state-session-id "developer.panel")
        uuid-str (when (:player container) (uuid/player-uuid (:player container)))
        get-ad #(-> (when uuid-str (store/get-player-state* session-id uuid-str)) :ability-data)
        ad0 (get-ad)
        skill-icon (skill-query/get-skill-icon-path skill-id)
        skill-description (when-let [dk (:description-key skill-spec)] (i18n/translate dk))
        prerequisites (mapv (fn [{:keys [skill-id min-exp]}]
                              {:icon-path (skill-query/get-skill-icon-path skill-id)
                               :accepted? (>= (double (or (adata/get-skill-exp ad0 skill-id) 0.0))
                                              (double min-exp))})
                            (or (:prerequisites skill-spec) []))
        open-time-ms (client-bridge/game-time-ms)
        ;; Developer page layout (page_developer.xml 400×187), center = 200, 93
        cx 200 cy 93
        ta-y (+ cy 20)
        btn-x (- cx 16) btn-y (+ ta-y 52)
        ;; Per-overlay dev-state atom updated from container each frame
        state-a (atom {:is-developing? false :progress 0.0 :result nil :error nil})
        prev-dev-a (atom false)]

    ;; Sync dev-state from container atoms each frame
    (events/on-frame cover
      (fn [_]
        (let [is-dev (boolean @(:is-developing container))
              dev-prog (double (or @(:development-progress container) 0.0))
              dev-complete (boolean @(:development-complete? container))
              prev @prev-dev-a]
          (cond
            is-dev (swap! state-a assoc :is-developing? true :progress dev-prog :error nil)
            (and (not is-dev) prev dev-complete)
            (swap! state-a assoc :is-developing? false :progress 1.0 :result :success)
            (and (not is-dev) prev (not dev-complete))
            (swap! state-a assoc :is-developing? false :result :failed)
            :else nil)
          (reset! prev-dev-a is-dev))))

    ;; Cover click: detect LEARN button area or close
    (events/on-left-click cover
      (fn [evt]
        (let [s @state-a
              mx (int (:x evt 0)) my (int (:y evt 0))
              ad (get-ad)
              learned? (adata/is-learned? ad skill-id)
              developing? (:is-developing? s)
              has-result? (some? (:result s))
              on-btn? (and (not learned?) (not developing?) (not has-result?)
                           (>= mx btn-x) (<= mx (+ btn-x 32))
                           (>= my btn-y) (<= my (+ btn-y 16)))]
          (cond
            on-btn?
            (let [energy (double (or @(:energy container) 0.0))
                  player-level (int (or (:level ad) 1))]
              (cond
                (< energy est-consumption)
                (swap! state-a assoc :error :low-energy)
                (> skill-level player-level)
                (swap! state-a assoc :error :low-level)
                (not (learning-rules/can-learn? skill-spec ad player-level dev-type))
                (swap! state-a assoc :error :cond-fail)
                :else
                (do (req-start-development! container :learn-skill {:skill-id (name skill-id)})
                    (swap! state-a assoc :error nil))))
            (and (not developing?) (not on-btn?))
            (close-fn)))))

    ;; Draw-ops host — delegates ALL visual rendering to skill-tree/detail-popup-ops
    (client-bridge/draw-ops-host! cover
      (fn []
        (let [anim-time (/ (- (client-bridge/game-time-ms) open-time-ms) 1000.0)
              ad (get-ad)
              learned? (adata/is-learned? ad skill-id)
              skill-exp (double (if learned? (or (adata/get-skill-exp ad skill-id) 0.0) 0.0))]
          (skill-tree/detail-popup-ops
            {:skill-id skill-id :skill-name skill-name :skill-level skill-level
             :skill-icon skill-icon :skill-description skill-description
             :learned learned? :exp skill-exp}
            anim-time
            {:dev-state @state-a :est-consumption est-consumption
             :cx cx :cy cy :screen-w 400 :screen-h 187
             :prerequisites prerequisites}))))

    (cgui-core/add-widget! root cover)))

;; ============================================================================
;; Level-up overlay
;; ============================================================================

(defn- create-level-up-overlay!
  "Level-up overlay — delegates visual rendering to skill-tree/level-up-popup-ops.
  CGUI layer handles only: fading cover, click-to-close, LEARN click dispatch."
  [root container developer-type]
  (let [{:keys [cover end-cover!]} (create-fading-cover root root)
        close-fn (fn [] (unregister-overlay! root end-cover!) (end-cover!))
        _ (register-overlay! root end-cover!)
        dev-type (or (normalize-tier developer-type) :normal)
        dev-spec (developer/developer-spec dev-type)
        session-id (runtime-hooks/require-player-state-session-id "developer.panel")
        player (:player container)
        pstate (when player (store/get-player-state* session-id (uuid/player-uuid player)))
        ad (:ability-data pstate)
        current-level (int (or (:level ad) 1))
        target-level (inc current-level)
        est-consumption (long (* (:cps dev-spec 700.0) (+ 3 (* target-level target-level 0.5))))
        condition-icon-path (modid/asset-path "textures" (str "abilities/condition/any" target-level ".png"))
        open-time-ms (client-bridge/game-time-ms)
        ;; Developer page layout (page_developer.xml 400×187), center = 200, 93
        cx 200 cy 93
        text-base-y (+ cy 25)
        btn-x (- cx 16) btn-y (+ text-base-y 40)
        ;; Per-overlay dev-state atom updated from container each frame
        state-a (atom {:is-developing? false :progress 0.0 :result nil :error nil})
        prev-dev-a (atom false)]

    ;; Sync dev-state from container atoms each frame
    (events/on-frame cover
      (fn [_]
        (let [is-dev (boolean @(:is-developing container))
              dev-prog (double (or @(:development-progress container) 0.0))
              dev-complete (boolean @(:development-complete? container))
              prev @prev-dev-a]
          (cond
            is-dev (swap! state-a assoc :is-developing? true :progress dev-prog :error nil)
            (and (not is-dev) prev dev-complete)
            (swap! state-a assoc :is-developing? false :progress 1.0 :result :success)
            (and (not is-dev) prev (not dev-complete))
            (swap! state-a assoc :is-developing? false :result :failed)
            :else nil)
          (reset! prev-dev-a is-dev))))

    ;; Cover click: detect LEARN button area or close
    (events/on-left-click cover
      (fn [evt]
        (let [s @state-a
              mx (int (:x evt 0)) my (int (:y evt 0))
              developing? (:is-developing? s)
              has-result? (some? (:result s))
              on-btn? (and (not developing?) (not has-result?)
                           (>= mx btn-x) (<= mx (+ btn-x 32))
                           (>= my btn-y) (<= my (+ btn-y 16)))]
          (cond
            on-btn?
            (let [energy (double (or @(:energy container) 0.0))]
              (if (< energy est-consumption)
                (swap! state-a assoc :error :low-energy)
                (do (req-start-development! container :level-up)
                    (swap! state-a assoc :error nil))))
            (and (not developing?) (not on-btn?))
            (close-fn)))))

    ;; Draw-ops host — delegates ALL visual rendering to skill-tree/level-up-popup-ops
    (client-bridge/draw-ops-host! cover
      (fn []
        (let [anim-time (/ (- (client-bridge/game-time-ms) open-time-ms) 1000.0)]
          (skill-tree/level-up-popup-ops
            target-level condition-icon-path anim-time
            {:dev-state @state-a :est-consumption est-consumption
             :cx cx :cy cy :screen-w 400 :screen-h 187}))))

    (cgui-core/add-widget! root cover)))

;; ============================================================================
;; Right panel — mode dispatch
;; ============================================================================

(defn- player-holding-magnetic-coil? [player]
  (and player
       (entity/entity-ops-available?)
       (= special-items/magnetic-coil-item-id (entity/player-get-main-hand-item-id player))))

(defn- right-panel-mode
  "Pure: determine what to render in parent_right/area."
  [_player-state container player]
  (let [uuid-str (when player (uuid/player-uuid player))
        pstate (when uuid-str (store/get-player-state*
                                (runtime-hooks/require-player-state-session-id "developer.panel")
                                uuid-str))
        ad (:ability-data pstate)
        has-cat? (boolean (:category-id ad))
        holding-coil? (player-holding-magnetic-coil? player)]
    (cond
      (and has-cat? holding-coil?) :reset-console
      (not has-cat?) :console
      :else :skill-tree)))

;; ============================================================================
;; Skill tree rendering — upstream SkillTree.scala matching
;; ============================================================================
;; Shared node-size constants live in skill-tree namespace (skill-tree/widget-size etc.).

(defn- make-tree-state []
  (atom {:creation-time nil
         :hover {:hover-idx nil}
         :hover-transitions {}}))

(defn- update-hover-transitions!
  "Update per-node hover transitions in tree-state atom.
  Per-session atom (not global) to avoid state bleed between concurrent GUIs."
  [tree-state nodes mx my parallax-x parallax-y]
  (let [now (client-bridge/game-time-ms)
        hw2 (/ skill-tree/widget-size 2)
        closest-node (when (seq nodes)
                       (apply min-key
                         (fn [n]
                           (let [cx (+ (:x n) hw2 (- parallax-x))
                                 cy (+ (:y n) hw2 (- parallax-y))]
                             (+ (* (- mx cx) (- mx cx)) (* (- my cy) (- my cy)))))
                         nodes))
        hover-dist (when closest-node
                     (let [cx (+ (:x closest-node) hw2 (- parallax-x))
                           cy (+ (:y closest-node) hw2 (- parallax-y))]
                       (Math/sqrt (+ (* (- mx cx) (- mx cx)) (* (- my cy) (- my cy))))))
        hover-idx (:idx closest-node)
        hover-now? (and closest-node (< (or hover-dist 999) 20))
        prev-idx (get-in @tree-state [:hover :hover-idx])
        prev-trans (get-in @tree-state [:hover-transitions prev-idx])
        gate-open? (or (nil? prev-trans)
                      (>= (/ (- now (:start prev-trans)) 100.0) 1.0))]
    (swap! tree-state assoc-in [:hover :hover-idx] hover-idx)
    (when (and gate-open? (not= hover-idx prev-idx))
      (swap! tree-state update :hover-transitions
        (fn [m]
          (-> m
              (cond-> prev-idx (assoc prev-idx {:start now :dir :out}))
              (cond-> (and hover-now? hover-idx) (assoc hover-idx {:start now :dir :in}))))))))

(defn- build-panel-hover-args [tree-state]
  {:hid    (get-in @tree-state [:hover :hover-idx])
   :htrans (:hover-transitions @tree-state)})

(defn- render-skill-tree-area!
  "Render skill tree nodes in parent_right/area.
  Upstream: SkillTree.scala Common.initialize → area FrameEvent + per-skill widgets.
  tree-state: per-session atom holding creation-time and hover-transition state."
  [root area-widget container player tree-state]
  (cgui-core/clear-widgets! area-widget)
  (try
    (let [now (client-bridge/game-time-ms)
          uuid-str (when player (uuid/player-uuid player))
          session-id (runtime-hooks/require-player-state-session-id "developer.panel")
          pstate (when uuid-str (store/get-player-state* session-id uuid-str))
          dev-type (current-developer-type container)
          render-data (skill-tree/build-render-data-for-player-state pstate dev-type)
          nodes (:skill-nodes render-data)
          _ (when (nil? (:creation-time @tree-state))
              (swap! tree-state assoc :creation-time now))
          anim (/ (- now (long (:creation-time @tree-state now))) 1000.0)
          [mx my] (cn.li.mcmod.client.platform-bridge/get-mouse-pos)
          [window-w window-h] (cn.li.mcmod.client.platform-bridge/get-window-size)
          mx01 (bal/clamp01 (/ mx (max 1.0 (double window-w))))
          my01 (bal/clamp01 (/ my (max 1.0 (double window-h))))
          parallax-x (* (- mx01 0.5) 10.0)
          parallax-y (* (- my01 0.5) 10.0)]
      (client-bridge/draw-ops-host! area-widget
        (fn []
          (let [{:keys [hid htrans]} (build-panel-hover-args tree-state)]
            (skill-tree/build-tree-ops render-data anim mx01 my01 hid htrans nil))))
      (let [hover-w (cgui-core/create-widget :pos [0 0] :size [257 139])]
        (events/on-frame hover-w
          (fn [_] (update-hover-transitions! tree-state nodes mx my parallax-x parallax-y)))
        (cgui-core/add-widget! area-widget hover-w))
      (doseq [node nodes]
        (when (and (or (:can-learn node) (:learned node)) (not (:locked? node)))
          (let [click-x (int (- (:x node) parallax-x))
                click-y (int (- (:y node) parallax-y))
                click-sz (int skill-tree/widget-size)
                click-w (cgui-core/create-widget :pos [click-x click-y] :size [click-sz click-sz])]
            (events/on-left-click click-w
              (fn [_] (create-skill-detail-overlay! root container (:skill-id node) dev-type)))
            (cgui-core/add-widget! area-widget click-w)))))
    (catch Exception e
      (log/error "Skill tree render failed:" (ex-message e))
      (log/stacktrace "Skill tree render failed" e))))

(defn- make-dev-start-callback
  "Build a callback that writes server rejection reason to console state."
  [state-ref]
  (fn [resp]
    (when-not (:success resp)
      (when-let [state-a @state-ref]
        (let [err-msg (str "ERROR: " (:reason resp "unknown"))
              update-fn (fn [st]
                          (-> st
                              (update :lines #(-> % (conj err-msg) (clamp-lines)))
                              (assoc :phase :idle :dev-grace 0 :exec-cmd nil)))]
          (swap! state-a update-fn))))))

(defn- render-console-area!
  "Render text console in parent_right/area."
  [root area-widget container player mode]
  (cgui-core/clear-widgets! area-widget)
  (let [start-action (if (= :reset mode) :reset :level-up)
        state-ref (atom nil)
        on-start-dev (fn []
                       (req-start-development! container start-action nil
                         (make-dev-start-callback state-ref)))
        [state-a _panel] (dev-console/create-console area-widget
                           {:mode mode
                            :container container
                            :player-name (or @(:user-name container) "Player")
                            :focus-root root
                            :on-start-development on-start-dev
                            :has-developer (boolean (:tile-entity container))})]
    (reset! state-ref state-a)))

;; ============================================================================
;; Wireless node label refresh
;; ============================================================================

(defn refresh-linked-node-label!
  "Send :list-nodes to server and update the wireless button label.
  Uses container owner for response routing (matching wireless overlay pattern)."
  [root container]
  (when (:tile-entity container)
    (let [payload (action-payload/action-payload container {})
          owner (try (container-state/owner-from-container container)
                     (catch Exception e
                       (log/error "[refresh-linked-node-label!] owner resolution error:" (ex-message e))
                       nil))
          _ (log/info "[refresh-linked-node-label!] sending list-nodes, owner=" (pr-str owner)
                      "payload=" (pr-str payload))]
      (net-client/send-to-server
        owner
        (dev-msg :list-nodes)
        payload
        (fn [resp]
          (log/info "[refresh-linked-node-label!] response received:" (pr-str resp))
          (when (map? resp)
            (let [text (if-let [n (:linked resp)]
                         (or (:node-name n) "N/A")
                         "N/A")]
              (log/info "[refresh-linked-node-label!] setting node label to:" text)
              (set-text-path! root "parent_left/panel_machine/button_wireless/text_nodename" text))))))))

;; ============================================================================
;; Main attach function — binds left and right panels
;; ============================================================================

(defn load-classic-developer-page
  "Return root `main` from `guis/rework/page_developer.xml`."
  []
  (try
    (let [doc (cgui-doc/read-xml (modid/namespaced-path "guis/rework/page_developer.xml"))
          root (cgui-doc/get-widget doc "main")]
      (tech-ui/apply-breathe-to-ui-descendants! root)
      root)
    (catch Exception e
      (log/error "developer classic XML:" (ex-message e))
      (cgui-core/create-widget :name "main" :pos [0 0] :size [400 187]))))

(defn attach-classic-developer-bindings!
  "Attach handlers to all widgets in the classic developer page.

  Callbacks:
  - :on-wireless-click — called when wireless button clicked (for overlay)
  - :on-develop-start — (optional) overrides req-start-development!
    for portable/instant dev. Receives [container action extra]."
  [root container {:keys [on-wireless-click on-develop-start]}]
  (let [pl (:player container)]
    ;; Wireless node label
    (refresh-linked-node-label! root container)

    ;; btn_upgrade → level-up overlay (replaces separate screen navigation)
    (when-let [learn (cgui-core/find-widget root "parent_left/panel_ability/btn_upgrade")]
      (events/on-left-click learn
        (fn [_]
          (create-level-up-overlay! root container (current-developer-type container)))))

    ;; Wireless button → overlay callback.
    ;; deepest-only dispatch: clicks on text/icon children must forward to parent.
    (when-let [wbtn (cgui-core/find-widget root "parent_left/panel_machine/button_wireless")]
      (when on-wireless-click
        (let [forward (fn [_] (on-wireless-click))]
          (events/on-left-click wbtn forward)
          (doseq [child (cgui-core/get-widgets wbtn)]
            (events/on-left-click child forward)))))

    ;; Frame handler — updates left panel + right panel mode dispatch
    ;; + periodic wireless label refresh (every 5s for multiplayer correctness)
    (let [right-area (cgui-core/find-widget root "parent_right/area")
          last-mode (atom nil)
          refresh-tick (atom 0)
          tree-state (make-tree-state)]
      (events/on-frame root
        (fn [_]
          (swap! refresh-tick inc)
          (when (zero? (mod @refresh-tick 100))
            (refresh-linked-node-label! root container))
          (let [{:keys [ability-name icon-path exp-label level-label
                        cat-prog01 power01 sync-rate can-upgrade?]}
                (current-ui-model container pl)]
            (set-text-path! root "parent_left/panel_ability/text_abilityname" ability-name)
            (set-drawtexture-path! root "parent_left/panel_ability/logo_ability" icon-path)
            (set-text-path! root "parent_left/panel_ability/text_exp" exp-label)
            (set-text-path! root "parent_left/panel_ability/text_level" level-label)
            (set-progress-path! root "parent_left/panel_ability/logo_progress" cat-prog01)
            (set-progress-path! root "parent_left/panel_machine/progress_power" power01)
            (set-progress-path! root "parent_left/panel_machine/progress_syncrate"
              (double (or sync-rate 0.7)))
            (set-visible-path! root "parent_left/panel_ability/btn_upgrade" can-upgrade?)
            (set-visible-path! root "parent_left/panel_ability/text_level" (not can-upgrade?)))
          (when right-area
            (let [mode (right-panel-mode nil container pl)]
              (when (not= mode @last-mode)
                (reset! last-mode mode)
                (swap! tree-state assoc :creation-time nil)
                (when (not= mode :skill-tree) (cgui-core/clear-widgets! right-area))
                (case mode
                  :console (render-console-area! root right-area container pl :learn)
                  :reset-console (render-console-area! root right-area container pl :reset)
                  :skill-tree nil
                  nil))
              (when (= mode :skill-tree)
                (render-skill-tree-area! root right-area container pl tree-state)))))))

    root))


