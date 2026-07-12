(ns cn.li.ac.block.developer.panel-reactive
  "Complete reactive replacement for developer/panel.clj + gui.clj's classic
   page_developer.xml layout (both deleted). All pure business logic
   (category/level model, right-panel mode derivation, skill-tree render-data,
   req-start-development!) was ported verbatim from the old panel.clj. Only
   CGUI widget construction/lookup and the cover-overlay mechanics were ever
   rewritten natively.

   Layout note: page_developer.xml has no new-format XML equivalent, so the
   classic 400x187 layout is reconstructed here as absolute-position native
   nodes (positions hand-derived from the old CGUI align/pivot math applied
   to the original XML's fixed widget tree)."
  (:require [clojure.string :as str]
            [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.runtime.owner :as runtime-owner]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.i18n :as i18n]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.wireless.gui.tab-reactive :as wireless-tab]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.core :as ui]
            [cn.li.mcmod.ui.node :as node]
            [cn.li.mcmod.ui.slot-write :as slot-write]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.ui.anim :as ranim]
            [cn.li.mcmod.ui.events :as events]
            [cn.li.mcmod.ui.xml :as ui-xml]
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
            [cn.li.ac.ability.client.screens.skill-tree-reactive :as skill-tree-reactive]
            [cn.li.ac.ability.client.screens.skill-tree-view :as skill-tree-view]
            [cn.li.ac.block.developer.console-reactive :as console-reactive]
            [cn.li.ac.item.special-items :as special-items]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.mcmod.gui.container.action-payload :as action-payload]
            [cn.li.mcmod.gui.container-state :as container-state]
            [cn.li.mcmod.network.client :as net-client])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]
           [cn.li.mcmod.ui.node INode]
           [cn.li.mcmod.uipojo.signal ISigD]))

;; ============================================================================
;; Layout constants (absolute positions derived from page_developer.xml's
;; LEFT/RIGHT/CENTER/TOP align+pivot math, resolved by hand against the fixed
;; 400x187 canvas — see panel.clj's widget-gui-pos for the original formula)
;; ============================================================================

(def ^:private classic-w 400.0)
(def ^:private classic-h 187.0)
(def ^:private root-w classic-w)  ;; upstream developer has no info-area sidebar
(def ^:private root-h classic-h)

(def ^:private area-x 128.0) (def ^:private area-y 18.0)
(def ^:private area-w 257.0) (def ^:private area-h 139.0)

;; ============================================================================
;; Pure business logic (ported verbatim from the deleted panel.clj) — category/
;; level model, right-panel mode derivation, skill-tree render-data, dev-start
;; requests. Only CGUI widget construction/lookup was ever rewritten natively;
;; none of this touches CGUI.
;; ============================================================================

(defn dev-msg [action]
  (msg-registry/msg :developer action))

(defn req-start-development!
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

(defn- tex-path
  "Namespaced texture :src from a relative texture path (sans 'textures/')."
  [p]
  (modid/asset-path "textures" p))

(defn- box [id x y w h fill]
  {:kind :box :props {:id id :x x :y y :w w :h h :fill fill}})

(defn normalize-tier [tier]
  (let [k (keyword (or tier :normal))]
    (if (developer/developer-type? k) k :normal)))

(defn current-developer-type [container]
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
        ;; Upstream (SkillTree.scala): "EXP " + (levelProgress*100).toInt + "%" —
        ;; always shown (no "MAX"), truncated, using the raw 0..1 fraction. The
        ;; 0.02 floor applies only to the progress BAR (cat-prog01), not the text.
        exp-frac (if has-category?
                   (cond (and thresh (pos? thresh)) (bal/clamp01 (/ level-prog thresh))
                         (>= lvl 5) 1.0
                         :else 0.0)
                   0.0)
        exp-label (format "EXP %d%%" (int (* 100.0 exp-frac)))
        level-label (format "Level %d" lvl)]  ;; matches upstream AbilityLocalization.levelDesc → "Level N"
    {:has-category? has-category?
     :can-upgrade? can-upgrade?
     :ability-name ability-name
     :icon-path icon-path
     :exp-label exp-label
     :level-label level-label
     :cat-prog01 cat-prog01
     :sync-rate (:bandwidth (developer/developer-spec developer-type) 0.7)  ;; fixed device property
     :power01 (bal/clamp01 (/ energy max-energy))}))

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

(defn- panel-session-id
  "Resolve the runtime-store session-id for this developer screen from the
   container's canonical owner (set at menu-open). Unlike
   require-player-state-session-id, this works on the client render thread:
   the container screen pushes no per-frame session/owner context, so the
   ThreadLocal *player-state-owner* is nil during flush!. The container map
   carries the owner, so reading it here is stable and context-free. Falls back
   to the bound owner for any caller without an enriched container."
  [container]
  (or (some-> (container-state/owner-from-container container)
              runtime-owner/store-session-id)
      (runtime-hooks/require-player-state-session-id "developer.panel")))

(defn current-ui-model [container player]
  (current-ui-model-in-session
    (panel-session-id container)
    container player))

(defn skill-tree-render-context
  [session-id player container]
  (let [uuid-str (when player (uuid/player-uuid player))
        pstate (when uuid-str (store/get-player-state* session-id uuid-str))
        dev-type (current-developer-type container)]
    {:pstate pstate
     :dev-type dev-type
     :render-data (skill-tree/build-render-data-for-player-state pstate dev-type)}))

(defn- player-holding-magnetic-coil? [player]
  (and player
       (entity/entity-ops-available?)
       (= special-items/magnetic-coil-item-id (entity/player-get-main-hand-item-id player))))

(defn right-panel-mode
  "Pure: determine what to render in parent_right/area."
  [_player-state container player]
  (let [uuid-str (when player (uuid/player-uuid player))
        pstate (when uuid-str (store/get-player-state*
                                (panel-session-id container)
                                uuid-str))
        ad (:ability-data pstate)
        has-cat? (boolean (:category-id ad))
        holding-coil? (player-holding-magnetic-coil? player)]
    (cond
      (and has-cat? holding-coil?) :reset-console
      (not has-cat?) :console
      :else :skill-tree)))

;; ============================================================================
;; Node builders
;; ============================================================================
;; set-tick! — per-frame side-effecting computed-o, force-pulled every frame
;; ============================================================================
;; ComputedO/ComputedD are lazy-pull: depMarkDirty only flags dirty, it never
;; invokes the wrapped fn. A computed stored via put-user-signal! alone with
;; no reader NEVER executes. Binding is the only thing that gets eagerly
;; enqueued on dirty and pulled by rt/flush! each frame, so "per-frame side
;; effect" signals must be wired as a Binding (anchored to any already-built
;; node — :root always exists) whose apply-fn simply forces the pull.

(defn- pull-o! [_node source] (.sGet ^cn.li.mcmod.uipojo.signal.ISigO source) nil)

(defn- set-tick!
  "Replace the per-frame ticker stored under `key`: unbind the previous
   Binding (if any), then bind `computed-sig` (or just clear if nil)."
  [^UiRt rt key computed-sig]
  (when-let [old (rt/user-signal rt key)] (sig/unbind! old))
  (if computed-sig
    (let [^INode anchor (rt/node-by-id rt :root)
          b (sig/bind! computed-sig anchor pull-o! (rt/get-dirty-bindings-q rt))]
      (rt/register-binding! rt (.getIdx anchor) b)
      (rt/put-user-signal! rt key b))
    (rt/put-user-signal! rt key nil)))

(defn- root-spec []
  (let [page-spec (ui-xml/load-spec (modid/namespaced-path "guis/rework/new/page_developer.xml"))]
    {:kind :group
     :props {:id :root :x 0.0 :y 0.0 :w root-w :h root-h}
     :children [page-spec
                {:kind :box
                 :props {:id :dev-cover :x 0.0 :y 0.0 :w classic-w :h classic-h :fill 0x00000000}}]}))

;; ============================================================================
;; Flat-color box "progress bar" — :box kind has no bindable width prop-writer
;; in the kinds table, so foreground fill width is written directly via a
;; custom Binding apply-fn (bypassing the generic prop-writer lookup).
;; ============================================================================

(defn- write-box-width! [^double full-w ^INode node source]
  (let [pct (max 0.0 (min 1.0 (double (.dGet ^ISigD source))))
        w (* full-w pct)]
    (when-not (== w (.getW node))
      (.setW node w)
      (.setFlag node node/FLAG-LAYOUT-DIRTY))))

(defn- bind-box-width! [^UiRt rt id ^double full-w value-sig]
  (let [^INode n (rt/node-by-id rt id)]
    (let [b (sig/bind! value-sig n (partial write-box-width! full-w) (rt/get-dirty-bindings-q rt))]
      (rt/register-binding! rt (.getIdx n) b))))

;; ============================================================================
;; Hover-alpha for image "buttons" (idle/hover alpha, replacing CGUI tint)
;; ============================================================================

(defn- hover-alpha-step [idle-a hover-a ^UiRt rt idx _ms]
  (if (= (long idx) (rt/hovered-idx rt)) (double hover-a) (double idle-a)))

(defn- bind-hover-alpha! [^UiRt rt id ^double idle-a ^double hover-a]
  (let [^INode n (rt/node-by-id rt id)
        idx (.getIdx n)
        clock (rt/clock-ms-sig rt)
        sig-d (sig/computed-d [clock] (partial hover-alpha-step idle-a hover-a rt idx))]
    (ui/bind! rt id :alpha sig-d)))

;; ============================================================================
;; Left/right panel dynamic bindings — reuses current-ui-model verbatim
;; ============================================================================

(defn- attach-model-bind! [^UiRt rt container player]
  (let [clock (rt/clock-ms-sig rt)
        cat-prog (sig/signal-d 0.0)
        power (sig/signal-d 0.0)
        sync-rate-sig (sig/signal-d 0.7)]
    (bind-box-width! rt :logo-progress 70.0 cat-prog)
    (bind-box-width! rt :progress-power 97.0 power)
    (bind-box-width! rt :progress-syncrate 97.0 sync-rate-sig)
    (set-tick! rt :model-tick
      (sig/computed-o [clock]
        (fn [_]
          (let [{:keys [ability-name icon-path exp-label level-label
                        cat-prog01 power01 sync-rate can-upgrade?]}
                (current-ui-model container player)]
            (ui/set-prop! rt :text-abilityname :text ability-name)
            (ui/set-prop! rt :logo-ability :src icon-path)
            (ui/set-prop! rt :text-exp :text exp-label)
            (ui/set-prop! rt :text-level :text level-label)
            (sig/sset-d! cat-prog cat-prog01)
            (sig/sset-d! power power01)
            (sig/sset-d! sync-rate-sig (double (or sync-rate 0.7)))
            (let [^INode upg (rt/node-by-id rt :btn-upgrade)
                  ^INode lvl (rt/node-by-id rt :text-level)]
              (when upg (.setVisible upg (boolean can-upgrade?)) (.setFlag upg node/FLAG-LAYOUT-DIRTY))
              (when lvl (.setVisible lvl (not can-upgrade?)) (.setFlag lvl node/FLAG-LAYOUT-DIRTY))))
          nil)))
    (bind-hover-alpha! rt :btn-upgrade 0.698 1.0)
    (bind-hover-alpha! rt :button-wireless 0.698 1.0)))

;; ============================================================================
;; Wireless node-name label refresh (native replacement for
;; refresh-linked-node-label! which writes via old find-widget path)
;; ============================================================================

(defn- refresh-node-name! [^UiRt rt container]
  (when (:tile-entity container)
    (let [payload (action-payload/action-payload container {})
          owner (try (container-state/owner-from-container container)
                     (catch Exception e (log/error "[dev-panel] owner error:" (ex-message e)) nil))]
      (net-client/send-to-server
        owner (dev-msg :list-nodes) payload
        (fn [resp]
          (when (map? resp)
            (let [text (if-let [n (:linked resp)] (or (:node-name n) "N/A") "N/A")]
              (ui/set-prop! rt :text-nodename :text text))))))))

;; ============================================================================
;; Cover-overlay mechanics
;; ============================================================================

(defn- set-cover-visible! [^UiRt rt visible?]
  (let [^INode n (rt/node-by-id rt :dev-cover)]
    (.setVisible n visible?)
    (.setFlag n node/FLAG-LAYOUT-DIRTY)))

(defn- cover-fill-signal [alpha-target clock]
  (let [smoothed-a (ranim/smoothed alpha-target clock 3.5)]
    (sig/computed-d [smoothed-a]
      (fn [a]
        (double (unchecked-int (bit-shift-left (long (* 255.0 (max 0.0 (min 1.0 (double a))))) 24)))))))

(defn- clear-embedded-runtimes! [^UiRt rt]
  (when-let [entries (rt/user-signal rt :embedded-runtimes)]
    (doseq [{:keys [child-rt]} @entries] (rt/dispose! child-rt))
    (rt/put-user-signal! rt :embedded-runtimes (atom []))))

(defn- remove-embedded-runtimes!
  "Dispose + drop only the embedded runtimes matching `pred`, keeping the rest.
   The skill-tree area embed and the cover overlays share :embedded-runtimes, so
   a blanket clear on overlay-close would also wipe the skill tree. Overlays are
   tagged :overlay? — close-cover! removes those, a mode switch removes the rest."
  [^UiRt rt pred]
  (when-let [a (rt/user-signal rt :embedded-runtimes)]
    (let [entries @a]
      (doseq [{:keys [child-rt] :as e} entries]
        (when (pred e) (rt/dispose! child-rt)))
      (reset! a (vec (remove pred entries))))))

(defn- add-embedded-runtime! [^UiRt rt entry]
  (let [a (or (rt/user-signal rt :embedded-runtimes) (atom []))]
    (rt/put-user-signal! rt :embedded-runtimes a)
    (swap! a conj entry)))

(defn- clear-modal! [^UiRt rt]
  (rt/put-user-signal! rt :active-modal (atom nil)))

(defn- bind-cover-fill! [^UiRt rt fill-sig]
  (when-let [old (rt/user-signal rt :cover-fill-binding)] (sig/unbind! old))
  (let [^INode n (rt/node-by-id rt :dev-cover)
        writer (slot-write/resolve-sig-writer (get node/kinds :box) :fill)
        b (sig/bind! fill-sig n writer (rt/get-dirty-bindings-q rt))]
    (rt/register-binding! rt (.getIdx n) b)
    (rt/put-user-signal! rt :cover-fill-binding b)))

(defn- close-cover!
  "Close whatever overlay is currently open: dispose the overlay's embedded
   runtime (leaving the persistent skill-tree area embed intact), clear
   active-modal, hide cover, clear the ticker user-signal."
  [^UiRt rt]
  (remove-embedded-runtimes! rt :overlay?)
  (clear-modal! rt)
  (set-tick! rt :cover-tick nil)
  (set-cover-visible! rt false)
  (events/gain-focus! rt -1))

;; ============================================================================
;; Wireless overlay — reuses wireless-tab-reactive verbatim (full native
;; interactivity via :active-modal input forwarding).
;; ============================================================================

(defn- open-wireless-overlay! [^UiRt rt container]
  (let [alpha-target (sig/signal-d 0.7)
        fill-sig (cover-fill-signal alpha-target (rt/clock-ms-sig rt))
        wr (rt/create-runtime)
        spec (ui-xml/load-spec (modid/namespaced-path "guis/rework/new/page_wireless.xml"))
        _ (rt/build! wr spec)
        px (/ (- classic-w 176.0) 2.0) py 0.0]
    (bind-cover-fill! rt fill-sig)
    (set-cover-visible! rt true)
    (wireless-tab/attach-panel! wr {:role :receiver :container container
                                    :tab-logo-path (tex-path "guis/icons/icon_node.png")
                                    :connected-row-logo-path (tex-path "guis/icons/icon_node.png")})
    (add-embedded-runtime! rt {:child-rt wr :x px :y py :w 176.0 :h 187.0 :visible?-fn nil :overlay? true})
    (rt/put-user-signal! rt :active-modal
      (atom {:child-rt wr :x px :y py :w 176.0 :h 187.0
             :on-close-outside
             (fn []
               (close-cover! rt)
               (refresh-node-name! rt container))}))))

;; ============================================================================
;; Skill-detail / level-up overlays — render-only embedded popup (via
;; skill-tree-view) + a single manual click-region handler on the cover
;; (matching the old code's manual mx/my bounds check; no input forwarding
;; into the popup runtime is needed since it registers no handlers itself).
;; ============================================================================

(defn- popup-click-region! [^UiRt rt btn-x btn-y btn-w btn-h eligible?-fn on-click! on-outside-close!]
  (let [^INode cover (rt/node-by-id rt :dev-cover)]
    (events/on! rt :dev-cover :left-click
      (fn [_ _ evt]
        (let [mx (double (:x evt 0)) my (double (:y evt 0))
              on-btn? (and (eligible?-fn)
                           (>= mx btn-x) (<= mx (+ btn-x btn-w))
                           (>= my btn-y) (<= my (+ btn-y btn-h)))]
          (if on-btn? (on-click!) (on-outside-close!)))))
    (events/on! rt :dev-cover :key
      (fn [_ _ evt]
        (when (= (long (:key-code evt 0)) 256)
          (on-outside-close!))))
    (events/gain-focus! rt (.getIdx cover))))

(defn- open-skill-detail-overlay! [^UiRt rt container player skill-id dev-type]
  (let [alpha-target (sig/signal-d 0.7)
        fill-sig (cover-fill-signal alpha-target (rt/clock-ms-sig rt))
        dev-spec (developer/developer-spec (or dev-type :normal))
        skill-spec (skill/get-skill skill-id)
        skill-name (or (:name skill-spec) (name skill-id) "Unknown")
        skill-level (int (or (:level skill-spec) 1))
        est-consumption (long (* (:cps dev-spec 700.0) (+ 3 (* skill-level skill-level 0.5))))
        session-id (panel-session-id container)
        uuid-str (when player (uuid/player-uuid player))
        get-ad #(-> (when uuid-str (store/get-player-state* session-id uuid-str)) :ability-data)
        ad0 (get-ad)
        skill-icon (skill-query/get-skill-icon-path skill-id)
        skill-description (when-let [dk (:description-key skill-spec)] (i18n/translate dk))
        cx 200.0 cy 93.0
        ta-y (+ cy 20.0) btn-x (- cx 16.0) btn-y (+ ta-y 52.0)
        state-a (atom {:is-developing? false :progress 0.0 :result nil :error nil})
        prev-dev-a (atom false)
        node-data {:skill-id skill-id :skill-name skill-name :skill-level skill-level
                   :skill-icon skill-icon :skill-description skill-description
                   :learned (adata/is-learned? ad0 skill-id)
                   :exp (double (or (adata/get-skill-exp ad0 skill-id) 0.0))}
        popup-rt (skill-tree-reactive/create-detail-overlay-runtime node-data)]
    (bind-cover-fill! rt fill-sig)
    (set-cover-visible! rt true)
    (add-embedded-runtime! rt {:child-rt popup-rt :x 0.0 :y 0.0 :w classic-w :h classic-h :visible?-fn nil :overlay? true})
    (popup-click-region! rt btn-x btn-y 32.0 16.0
      (fn [] (let [s @state-a]
               (and (not (:learned (get-ad))) (not (:is-developing? s)) (nil? (:result s)))))
      (fn []
        (let [energy (double (or @(:energy container) 0.0))
              ad (get-ad) player-level (int (or (:level ad) 1))]
          (cond
            (< energy est-consumption) (swap! state-a assoc :error :low-energy)
            (> skill-level player-level) (swap! state-a assoc :error :low-level)
            (not (learning-rules/can-learn? skill-spec ad player-level dev-type))
            (swap! state-a assoc :error :cond-fail)
            :else (do (req-start-development! container :learn-skill {:skill-id (name skill-id)})
                      (swap! state-a assoc :error nil)))))
      (fn [] (close-cover! rt)))
    (set-tick! rt :cover-tick
      (sig/computed-o [(rt/clock-ms-sig rt)]
        (fn [_]
          (let [is-dev (boolean @(:is-developing container))
                dev-prog (double (or @(:development-progress container) 0.0))
                dev-complete (boolean (some-> (:development-complete? container) deref))
                prev @prev-dev-a]
            (cond
              is-dev (swap! state-a assoc :is-developing? true :progress dev-prog :error nil)
              (and (not is-dev) prev dev-complete)
              (swap! state-a assoc :is-developing? false :progress 1.0 :result :success)
              (and (not is-dev) prev (not dev-complete))
              (swap! state-a assoc :is-developing? false :result :failed)
              :else nil)
            (reset! prev-dev-a is-dev)
            (let [ad (get-ad) learned? (adata/is-learned? ad skill-id)
                  updated (assoc node-data :learned learned?
                                 :exp (double (if learned? (or (adata/get-skill-exp ad skill-id) 0.0) 0.0))
                                 :dev-state @state-a)]
              (skill-tree-view/refresh-detail-overlay! popup-rt updated)))
          nil)))))

(defn- open-levelup-overlay! [^UiRt rt container player developer-type]
  (let [alpha-target (sig/signal-d 0.7)
        fill-sig (cover-fill-signal alpha-target (rt/clock-ms-sig rt))
        dev-type (or (normalize-tier developer-type) :normal)
        dev-spec (developer/developer-spec dev-type)
        session-id (panel-session-id container)
        uuid-str (when player (uuid/player-uuid player))
        pstate (when uuid-str (store/get-player-state* session-id uuid-str))
        ad (:ability-data pstate)
        current-level (int (or (:level ad) 1))
        target-level (inc current-level)
        est-consumption (long (* (:cps dev-spec 700.0) (+ 3 (* target-level target-level 0.5))))
        cx 200.0 cy 93.0
        text-base-y (+ cy 25.0) btn-x (- cx 16.0) btn-y (+ text-base-y 40.0)
        state-a (atom {:is-developing? false :progress 0.0 :result nil :error nil})
        prev-dev-a (atom false)
        popup-rt (skill-tree-reactive/create-levelup-overlay-runtime target-level @state-a)]
    (bind-cover-fill! rt fill-sig)
    (set-cover-visible! rt true)
    (add-embedded-runtime! rt {:child-rt popup-rt :x 0.0 :y 0.0 :w classic-w :h classic-h :visible?-fn nil :overlay? true})
    (popup-click-region! rt btn-x btn-y 32.0 16.0
      (fn [] (let [s @state-a] (and (not (:is-developing? s)) (nil? (:result s)))))
      (fn []
        (let [energy (double (or @(:energy container) 0.0))]
          (if (< energy est-consumption)
            (swap! state-a assoc :error :low-energy)
            (do (req-start-development! container :level-up)
                (swap! state-a assoc :error nil)))))
      (fn [] (close-cover! rt)))
    (set-tick! rt :cover-tick
      (sig/computed-o [(rt/clock-ms-sig rt)]
        (fn [_]
          (let [is-dev (boolean @(:is-developing container))
                dev-prog (double (or @(:development-progress container) 0.0))
                dev-complete (boolean (some-> (:development-complete? container) deref))
                prev @prev-dev-a]
            (cond
              is-dev (swap! state-a assoc :is-developing? true :progress dev-prog :error nil)
              (and (not is-dev) prev dev-complete)
              (swap! state-a assoc :is-developing? false :progress 1.0 :result :success)
              (and (not is-dev) prev (not dev-complete))
              (swap! state-a assoc :is-developing? false :result :failed)
              :else nil)
            (reset! prev-dev-a is-dev)
            (skill-tree-view/refresh-levelup-overlay! popup-rt target-level @state-a))
          nil)))))

;; ============================================================================
;; Skill-tree area — embedded render + native click hit-targets (hover via
;; the framework's own hovered-idx tracking; parallax camera-shift from the
;; original is a cosmetic-only omission, not a functional one)
;; ============================================================================

(defn- clear-area! [^UiRt rt]
  (rt/clear-children! rt (rt/node-by-id rt :area))
  (remove-embedded-runtimes! rt (complement :overlay?)))

(defn- skill-under-pointer
  "Skill-id whose widget contains the area-local point (cmx,cmy), applying the
   same fit + parallax transform as the visible nodes. Computed on demand
   (hover/click) — no shadow hit-boxes, no per-frame work (upstream-style: the
   widget under the pointer is the target)."
  [nodes cmx cmy fit-s fit-ox fit-oy]
  (let [[pdx pdy] (skill-tree-view/parallax-offset cmx cmy area-w area-h)
        sz (* skill-tree/widget-size fit-s)]
    (some (fn [nd]
            (let [vx (+ fit-ox (* (- (double (:x nd)) (double pdx)) fit-s))
                  vy (+ fit-oy (* (- (double (:y nd)) (double pdy)) fit-s))]
              (when (and (>= cmx vx) (< cmx (+ vx sz))
                         (>= cmy vy) (< cmy (+ vy sz)))
                (:skill-id nd))))
          nodes)))

(defn- build-skill-tree-area! [^UiRt rt container player]
  (let [session-id (panel-session-id container)
        {:keys [render-data dev-type]} (skill-tree-render-context session-id player container)
        nodes (:skill-nodes render-data)
        ;; Only learnable/learned nodes are interactive (upstream filter).
        clickable (filterv #(and (or (:can-learn %) (:learned %)) (not (:locked? %))) nodes)
        [fit-s fit-ox fit-oy] (skill-tree-view/area-fit-transform nodes area-w area-h)
        open-ms (atom nil)
        mouse-a (atom nil)   ;; area-local pointer for parallax
        hover-a (atom nil)   ;; skill-id under the pointer (for highlight)
        ^INode area-node (rt/node-by-id rt :area)
        embed-rt (skill-tree-reactive/create-embedded-runtime render-data (/ area-w 2.0) (/ area-h 2.0)
                   area-w area-h nil 0.0)]
    ;; On move: track area-local pointer + which skill is hovered (both on demand).
    (rt/put-user-signal! rt :on-pointer-move
      (fn [mx my]
        (let [cmx (- (double mx) (.getAbsX area-node))
              cmy (- (double my) (.getAbsY area-node))]
          (reset! mouse-a [cmx cmy])
          (reset! hover-a (skill-under-pointer clickable cmx cmy fit-s fit-ox fit-oy)))))
    ;; Single transparent input catcher over the whole area — resolves the clicked
    ;; skill on click only. Replaces the per-node shadow hit-boxes entirely.
    (rt/build-child! rt (box :skill-input 0.0 0.0 area-w area-h 0x00000000) area-node)
    (events/on! rt :skill-input :left-click
      (fn [_ _ evt]
        (let [cmx (- (double (:x evt)) (.getAbsX area-node))
              cmy (- (double (:y evt)) (.getAbsY area-node))]
          (when-let [sid (skill-under-pointer clickable cmx cmy fit-s fit-ox fit-oy)]
            (open-skill-detail-overlay! rt container player sid dev-type)))))
    (add-embedded-runtime! rt {:child-rt embed-rt :anchor-node area-node
                               :x area-x :y area-y :w area-w :h area-h :visible?-fn nil})
    (set-tick! rt :skill-tree-tick
      (sig/computed-o [(rt/clock-ms-sig rt)]
        (fn [now-ms]
          (when (nil? @open-ms) (reset! open-ms (double now-ms)))
          (let [{:keys [render-data]} (skill-tree-render-context session-id player container)
                anim-s (/ (- (double now-ms) (double @open-ms)) 1000.0)
                [amx amy] (or @mouse-a [(/ area-w 2.0) (/ area-h 2.0)])]
            (skill-tree-reactive/refresh-embedded-runtime! embed-rt render-data
              amx amy area-w area-h @hover-a anim-s))
          nil)))))

;; ============================================================================
;; Right-panel mode dispatch — reuses right-panel-mode verbatim
;; ============================================================================

(defn- attach-right-panel-dispatch! [^UiRt rt container player]
  (let [last-mode (atom nil)]
    (set-tick! rt :right-panel-tick
      (sig/computed-o [(rt/clock-ms-sig rt)]
        (fn [_]
          (let [mode (right-panel-mode nil container player)]
            (when (not= mode @last-mode)
              (reset! last-mode mode)
              (set-tick! rt :skill-tree-tick nil)
              (clear-area! rt)
              (case mode
                :console (console-reactive/attach! rt :area area-w area-h
                           {:mode :learn :container container
                            :player-name (or @(:user-name container) "Player")
                            :has-developer (boolean (:tile-entity container))
                            :on-start-development
                            (fn [] (req-start-development! container :level-up))})
                :reset-console (console-reactive/attach! rt :area area-w area-h
                                  {:mode :reset :container container
                                   :player-name (or @(:user-name container) "Player")
                                   :has-developer (boolean (:tile-entity container))
                                   :on-start-development
                                   (fn [] (req-start-development! container :reset))})
                :skill-tree (build-skill-tree-area! rt container player)
                nil)))
          nil)))))

;; ============================================================================
;; Wireless button + upgrade button wiring
;; ============================================================================

(defn- attach-buttons! [^UiRt rt container player]
  (events/on! rt :btn-upgrade :left-click
    (fn [_ _ _] (open-levelup-overlay! rt container player (current-developer-type container))))
  (events/on! rt :button-wireless :left-click
    (fn [_ _ _] (open-wireless-overlay! rt container)))
  (events/on! rt :logo-node :left-click
    (fn [_ _ _] (open-wireless-overlay! rt container)))
  (events/on! rt :text-nodename :left-click
    (fn [_ _ _] (open-wireless-overlay! rt container))))

;; ============================================================================
;; Entry point
;; ============================================================================

(defn build-runtime!
  "Build + wire the full classic developer layout onto a fresh UiRt. Shared
   entry point for both the block (menu-backed) screen and the portable item
   (standalone) screen — the only difference between them is how the caller
   wraps/hosts the returned runtime, and whether the wireless button applies."
  [container player]
  (let [r (rt/create-runtime)]
    (rt/build! r (root-spec))
    (set-cover-visible! r false)
    (attach-model-bind! r container player)
    (attach-buttons! r container player)
    (attach-right-panel-dispatch! r container player)
    (refresh-node-name! r container)
    r))

(defn create-screen
  [container menu player]
  (let [container (assoc container :menu menu)
        r (build-runtime! container player)]
    {:type :reactive-container-screen
     :runtime r
     :container container
     :menu menu
     :no-slots? true  ;; developer is a full-screen UI; hide the menu's inventory slots
     :size-dx (- root-w 176.0)
     :size-dy (- root-h 166.0)}))
