(ns cn.li.ac.ability.client.screens.spell-composer-reactive
  "Presentation Runtime controller for the player spell composer
   editor execution plan. Follows preset-editor-reactive's
   self-contained active-mounts pattern, same as the node editor screen.

   State is shaped so ordinary UI actions cannot create an INVALID glyph sequence
   while the combat layer still validates every packet at the server boundary: the combat player-spell desugar
   step requires the first glyph to be a :form/* and throws otherwise
   (an ex-info that would propagate uncaught through the compile-and-
   admit/dispatch path if it ever reached the server that way -- not
   verified here whether the network layer's handler dispatch catches
   an arbitrary handler exception, so this composer simply never
   constructs a sequence that could throw: :form is a single required
   slot picked before any :effects/:augments can be added, not a flat
   list a player could reorder into something illegal.

   The composed glyphs start with safe DEFAULT params (:amount 2.0, :range
   16.0, etc) and expose bounded numeric fields for the selected effect.
   Draft text is kept separate from committed params; submit validates
   finiteness and descriptor min/max before the spell can be cast.

   Physical glyph items / a spell-storage item's NBT (the plan's other
   P3 storage deliverable) is NOT part of this pass either -- both need a
   texture, a model json, and creative-tab placement this environment
   cannot create or visually verify, and neither is on the critical path
   for the compose-and-cast LOOP to work end to end (this screen can be
   opened directly, e.g. from a command, with no physical item
   involved). Deferred as a real, separate content-integration task."
  (:require [clojure.string :as str]
            [cn.li.ac.gui.presentation :as presentation]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.i18n :as i18n]
            [cn.li.ability.editor.chrome :as chrome]
            [cn.li.ability.editor.label :as label]
            [cn.li.ability.editor.render :as render]
            [cn.li.ac.ability.client.api :as api]
            [cn.li.ac.ability.client.read-model :as read-model]
            [cn.li.combat.api :as combat-api]))

(defonce ^:private active-mounts (atom {}))

(defn- glyph-str
  "A glyph keyword -> its full :ns/name text, e.g. :form/touch ->
   \"form/touch\". NOT (name glyph) -- name silently strips a keyword's
   namespace segment, and every real glyph here IS namespaced (:form/*,
   :effect/*, :augment/*) -- the same class of bug the node language's
   own sigil-classification code warns about for the identical reason."
  [kw]
  (subs (str kw) 1))

(defn- ui-label
  "Keep single-line composer labels inside their fixed layout box.

   Presentation V4 currently clips but does not implement generic text
   ellipsizing.  Only derived display labels are shortened here; glyph data,
   status state and parameter drafts remain lossless. Delegates to
   cn.li.ability.editor.label/ellipsize, the one shared implementation
   both editors and graph.clj's canvas labels now use (P6)."
  [value max-width]
  (label/ellipsize value max-width))

;; spell_composer.ui.edn's :host design box and this screen's shared-shell
;; panel widths. Unlike the node editor, the composer's palette/inspector
;; are never collapsed -- there are only 5 real glyphs and 1 selected
;; effect's params, so the always-open width is already the minimum
;; useful one (see cn.li.ability.editor.chrome for why width, not
;; :visible, is what the shell actually needs to reclaim panel space).
(def ^:private design-width 480.0)
(def ^:private design-height 320.0)
(def ^:private palette-open-w 110.0)
(def ^:private inspector-open-w 120.0)

(defn- shell-geometry []
  (chrome/panel-geometry
   {:design-width design-width :design-height design-height
    :palette-open? true :palette-open-w palette-open-w
    :inspector-open? true :inspector-open-w inspector-open-w
    :diagnostic-count 0}))

(defn- glyph-label
  "A glyph keyword -> its localized display name (\"Touch\", not
   \"form/touch\") via cn.li.ac.ability.datagen.spell-glyph-translations'
   :en_us entry for the spec's own :i18n key -- the spec has always had
   this key (combat-core's glyph-specs table), nothing ever wrote a real
   translation for it before this refactor (datagen's own registry.clj
   docstring point 3)."
  [state glyph]
  (if-let [i18n-key (get-in state [:glyph-specs glyph :i18n])]
    (i18n/translate i18n-key)
    (glyph-str glyph)))

;; NOT routed through the mod's i18n/datagen translation system (checked
;; before writing this: every existing reactive controller's dynamic
;; :status text -- skill_tree.clj, settings_reactive.clj, about_reactive.
;; clj, this namespace's own :status assignments elsewhere -- is a
;; hardcoded English literal; ability-translation-map's datagen path is
;; for STATIC content Minecraft's own resource system needs a pre-
;; registered lang key for at build time (item/skill/achievement names),
;; not for a runtime status string a Clojure atom composes on the fly.
;; Giving reject reasons a second, inconsistent mechanism here would be
;; the actual gap, not the fix -- if per-locale dynamic status text is
;; ever wanted, it needs a mechanism this whole reactive-controller
;; family adopts together, not a one-off in this file.
(def ^:private reject-labels
  {:over-complexity "Spell is too complex for your current mastery."
   :forbidden-effect "Spell uses an effect players are not allowed to cast."
    :over-budget "Spell exceeds the host-command/iteration budget."
    :invalid-glyph "Spell contains invalid glyph data."})
(defn- owner-for [player-uuid]
  (read-model/local-client-owner player-uuid "spell-composer"))

;; --- pure state ------------------------------------------------------------

(defn- initial-state []
  (let [catalog (combat-api/player-glyph-catalog)
        specs (combat-api/player-glyph-specs)]
    {:catalog catalog
     :glyph-specs specs
     :form nil
     :effect-groups []
     :selected-effect nil
     :palette-collapsed #{}
     :busy? false
     :status "Pick a form, then add effects and cast."}))

(defn- defaults-for [spec]
  (into {} (map (fn [[k descriptor]] [k (:default descriptor)])
                (:params spec))))

(defn- descriptor-for [state glyph]
  (get (:glyph-specs state) glyph))

(defn- glyph-entry [state glyph]
  (let [spec (descriptor-for state glyph)]
    {:glyph glyph :params (defaults-for spec)}))

(defn- pick-form [state glyph-kw]
  (if (= :form (:kind (descriptor-for state glyph-kw)))
    (assoc state :form (glyph-entry state glyph-kw)
           :status (str "Form: " (glyph-str glyph-kw)))
    (assoc state :status "Choose a form glyph.")))

(defn- add-effect [state glyph-kw]
  (cond
    (not (:form state)) (assoc state :status "Pick a form first.")
    (not= :effect (:kind (descriptor-for state glyph-kw)))
    (assoc state :status "Choose an effect glyph.")
    (>= (count (:effect-groups state)) 8)
    (assoc state :status "Maximum 8 effects per spell.")
    :else
    (let [group (assoc (glyph-entry state glyph-kw) :augments [])]
      (-> state
          (update :effect-groups conj group)
          (assoc :selected-effect (count (:effect-groups state))
                 :status (str "Added " (glyph-str glyph-kw) "."))))))

(defn- add-augment [state glyph-kw]
  (let [idx (:selected-effect state)
        groups (:effect-groups state)
        valid-index? (and (integer? idx) (<= 0 idx) (< idx (count groups)))]
    (cond
      (nil? (:form state)) (assoc state :status "Pick a form first.")
      (not valid-index?) (assoc state :status "Select an effect first.")
      (not= :augment (:kind (descriptor-for state glyph-kw)))
      (assoc state :status "Choose an augment glyph.")
      (>= (count (get-in groups [idx :augments])) 8)
      (assoc state :status "Maximum 8 augments on one effect.")
      :else
      (-> state
          (update-in [:effect-groups idx :augments] conj (glyph-entry state glyph-kw))
          (assoc :status (str "Added " (glyph-str glyph-kw) " to effect " (inc idx) "."))))))

(defn- remap-drafts-after-remove
  "Drop drafts for effect IDX and shift later effect indices left."
  [drafts idx]
  (into {}
        (keep (fn [[[effect-idx key] value]]
                (cond
                  (= effect-idx idx) nil
                  (> effect-idx idx) [[(dec effect-idx) key] value]
                  :else [[effect-idx key] value])))
        (or drafts {})))

(defn- remap-drafts-after-swap
  "Swap draft indices together with two reordered effect groups."
  [drafts idx target]
  (into {}
        (map (fn [[[effect-idx key] value]]
               [(cond
                  (= effect-idx idx) [target key]
                  (= effect-idx target) [idx key]
                  :else [effect-idx key])
                value]))
        (or drafts {})))
(defn- select-effect [state idx]
  (if (and (integer? idx)
           (<= 0 idx)
           (< idx (count (:effect-groups state))))
    (assoc state :selected-effect idx)
    (assoc state :status "Select a valid effect.")))

(defn- remove-effect [state idx]
  (if (and (integer? idx) (< -1 idx) (< idx (count (:effect-groups state))))
    (let [groups (vec (concat (subvec (:effect-groups state) 0 idx)
                               (subvec (:effect-groups state) (inc idx))))]
      (assoc state :effect-groups groups
             :param-drafts (remap-drafts-after-remove (:param-drafts state) idx)
             :selected-effect (when (seq groups) (min idx (dec (count groups))))
             :status "Effect removed."))
    state))

(defn- move-effect [state idx delta]
  (let [groups (:effect-groups state)]
    (if-not (and (integer? idx) (integer? delta))
      state
      (let [target (+ idx delta)]
        (if (and (<= 0 idx) (< idx (count groups))
                 (<= 0 target) (< target (count groups)))
          (let [item (nth groups idx)
                reordered (-> groups vec
                              (assoc idx (nth groups target))
                              (assoc target item))]
            (assoc state :effect-groups reordered
                   :param-drafts (remap-drafts-after-swap (:param-drafts state) idx target)
                   :selected-effect target))
          state)))))

(defn- remove-augment [state effect-idx augment-idx]
  (let [groups (:effect-groups state)
        valid-effect? (and (integer? effect-idx)
                           (<= 0 effect-idx)
                           (< effect-idx (count groups)))
        augments (when valid-effect? (get-in groups [effect-idx :augments]))]
    (if (and (integer? augment-idx)
             (<= 0 augment-idx)
             (< augment-idx (count augments)))
      (update-in state [:effect-groups effect-idx :augments]
                 #(vec (concat (subvec % 0 augment-idx) (subvec % (inc augment-idx)))))
      state)))

(defn- clear-composition [state]
  (assoc state :form nil :effect-groups [] :selected-effect nil :param-drafts {} :busy? false :status "Cleared."))

(defn- composed-glyphs [{:keys [form effect-groups]}]
  (when form
    (into [form]
          (mapcat (fn [group] (cons (dissoc group :augments) (:augments group)))
                  effect-groups))))

(declare payload-index)
(defn- selected-param-fields [{:keys [effect-groups selected-effect param-drafts glyph-specs]}]
  (if-let [group (and (integer? selected-effect) (get effect-groups selected-effect))]
    (let [params (:params (get glyph-specs (:glyph group)))]
      (mapv (fn [[key descriptor]]
              {:effect-index selected-effect
               :param-key key
               :draft-key (keyword (str "composer-param-" selected-effect "-" (name key)))
               :label (ui-label (name key) 96.0)
               :value (str (get param-drafts [selected-effect key]
                                (get-in group [:params key])))
               :decrement-label "-" :increment-label "+"})
            params))
    []))

(defn- parse-finite-number [value]
  (try
    (let [n (Double/parseDouble (str/trim (str value)))]
      (when (Double/isFinite n) n))
    (catch Exception _ nil)))

(defn- valid-param-drafts?
  "True when every in-progress parameter draft is finite and within its
   descriptor bounds. Invalid drafts never become part of the spell payload."
  [{:keys [param-drafts effect-groups glyph-specs]}]
  (every? (fn [[[idx key] raw]]
            (let [valid-index? (and (integer? idx)
                                   (<= 0 idx)
                                   (< idx (count effect-groups)))
                  glyph (when valid-index? (get-in effect-groups [idx :glyph]))
                  descriptor (when (and valid-index? (keyword? key))
                               (get-in glyph-specs [glyph :params key]))
                  value (parse-finite-number raw)]
              (and valid-index?
                   descriptor
                   (some? value)
                   (>= value (double (:min descriptor)))
                   (<= value (double (:max descriptor))))))
          (or param-drafts {})))
(defn- coerce-param-value [descriptor n]
  (if (= :int (:type descriptor)) (long (Math/round (double n))) (double n)))

(defn- param-change [state payload]
  (let [item (:item payload)
        idx (payload-index payload :effect-index)
        key (:param-key item)
        groups (:effect-groups state)
        glyph (when (and (integer? idx)
                         (<= 0 idx)
                         (< idx (count groups)))
                (get-in groups [idx :glyph]))
        descriptor (when (and glyph (keyword? key))
                     (get-in (:glyph-specs state) [glyph :params key]))
        value (or (:value payload) (:value item) (:text payload))]
    (if (and descriptor (some? value))
      (assoc-in state [:param-drafts [idx key]] (str value))
      state)))

(defn- param-submit [state payload]
  (let [item (:item payload)
        idx (payload-index payload :effect-index)
        key (:param-key item)
        effect-groups (:effect-groups state)
        valid-index? (and (integer? idx)
                           (<= 0 idx)
                           (< idx (count effect-groups)))
        glyph (when valid-index? (get-in effect-groups [idx :glyph]))
        descriptor (when (and valid-index? (keyword? key))
                     (get-in (:glyph-specs state) [glyph :params key]))
        value (parse-finite-number (or (:value payload) (:value item) (:text payload)))]
    (cond
      (not (and valid-index? (keyword? key) descriptor))
      (assoc state :status "Unknown parameter.")
      (nil? value)
      (assoc state :status (str "Enter a finite number for " (name key) "."))
      (< value (double (:min descriptor)))
      (assoc state :status (str (name key) " is below its minimum."))
      (> value (double (:max descriptor)))
      (assoc state :status (str (name key) " exceeds its maximum."))
      :else
      (-> state
          (assoc-in [:effect-groups idx :params key] (coerce-param-value descriptor value))
          (update :param-drafts dissoc [idx key])
          (assoc :status (str "Updated " (name key) "."))))))
(defn- toggle-palette-category [state kind]
  (update state :palette-collapsed
          (fn [collapsed]
            (if (contains? collapsed kind)
              (disj collapsed kind)
              (conj (or collapsed #{}) kind)))))

(defn- param-step
  "Nudge the selected effect's param `key` by one step in `direction`
   (+1/-1), clamped to the glyph spec's own [:min :max] -- the same bound
   param-submit already enforces on a typed Enter, just reachable with a
   click. Step size is the descriptor's own range / 20, so a wide range
   (:range 1..128) and a narrow one (:amount 0..20) both take a sane
   number of clicks to cross."
  [state idx key direction]
  (let [groups (:effect-groups state)
        valid-index? (and (integer? idx) (<= 0 idx) (< idx (count groups)))
        glyph (when valid-index? (get-in groups [idx :glyph]))
        descriptor (when (and valid-index? (keyword? key))
                     (get-in (:glyph-specs state) [glyph :params key]))]
    (if-not descriptor
      state
      (let [min-v (double (:min descriptor))
            max-v (double (:max descriptor))
            current (or (parse-finite-number (get (:param-drafts state) [idx key]))
                        (double (get-in groups [idx :params key] min-v)))
            step (max 0.01 (/ (- max-v min-v) 20.0))
            next-v (-> (+ current (* step (double direction)))
                       (max min-v) (min max-v)
                       (->> (coerce-param-value descriptor)))]
        (-> state
            (assoc-in [:effect-groups idx :params key] next-v)
            (update :param-drafts dissoc [idx key])
            (assoc :status (str "Updated " (name key) ".")))))))

;; --- render-state ------------------------------------------------------

(defn- glyph-palette-item [state {:keys [glyph kind cost admissible?]}]
  {:glyph glyph :header? false :entry? true
   :label (ui-label (glyph-label state glyph) 62.0)
   :cost-label (ui-label (str cost) 22.0)
   :category-color (render/argb->rgba-floats (render/category-color kind))
   ;; Both rendered, never filtered out (C3): an unlocked-but-inadmissible
   ;; glyph is a real "the game will not accept this yet" signal a player
   ;; needs to SEE to know what to work toward -- combat.player/glyph-
   ;; catalog's own docstring calls this the grey-out list by name.
   :admissible? (boolean admissible?)
   :not-admissible? (not (boolean admissible?))})

(defn- palette-rows
  "catalog (cn.li.combat.api/player-glyph-catalog's shape) -> the palette's
   display rows: one collapsible category header per glyph :kind (form/
   effect/augment), replacing the old three separately-titled panels (the
   duplicate \"Effects\" heading P1/C1 flagged) with the single list
   Ars Nouveau/Blueprint both use."
  [state catalog collapsed]
  (let [by-kind (group-by :kind catalog)]
    (vec (mapcat (fn [kind]
                   (let [entries (get by-kind kind [])
                         folded? (contains? collapsed kind)]
                     (when (seq entries)
                       (cons {:row-type :category :header? true :entry? false :kind kind
                              :header-label (ui-label (str (if folded? "> " "v ") (name kind)) 100.0)}
                             (when-not folded?
                               (map #(glyph-palette-item state %) entries))))))
                 [:form :effect :augment]))))

(defn- spell-complexity
  "Live complexity/cap reading for the header's persistent :progress bar
   (C4) -- previously this same analyze-player-spell call only ever ran
   once, at Cast, so a player learned they were over budget only after
   being rejected. :over-complexity is the one reject reason that still
   reports :complexity/:cap (combat-core's admit fn), so that case
   keeps the real number instead of collapsing to a blank/zero reading."
  [state]
  (let [cap (long combat-api/player-spell-complexity-cap)
        glyphs (composed-glyphs state)]
    (if-not glyphs
      {:complexity 0 :cap cap :over-cap? false}
      (let [analysis (combat-api/analyze-player-spell glyphs cap)]
        (cond
          (:ok analysis) {:complexity (long (:complexity analysis)) :cap cap :over-cap? false}
          (= :over-complexity (:reject analysis)) {:complexity (long (:complexity analysis)) :cap cap :over-cap? true}
          :else {:complexity 0 :cap cap :over-cap? false})))))

(defn- chain-card [state idx {:keys [glyph augments]} selected-effect group-count]
  {:form? false :effect? true :index idx
   :label (ui-label (glyph-label state glyph) 48.0)
   :category-color (render/argb->rgba-floats (render/category-color (get-in state [:glyph-specs glyph :kind])))
   :selected? (= idx selected-effect)
   :can-move-up? (pos? idx)
   :can-move-down? (< idx (dec group-count))
   :up-label "^" :down-label "v" :remove-label "x"
   :augments (mapv (fn [augment-index a]
                      {:effect-index idx
                       :augment-index augment-index
                       :label (ui-label (glyph-label state (:glyph a)) 44.0)
                       :remove-label "x"})
                    (range) augments)})

(defn- render-state
  "form + effect-groups render as one left-to-right :chain-items sequence
   the stage's :repeater :direction :row draws (P3: 'a spell is a chain
   that reads like a sentence, Form -> Effect -> Effect+Aug', matching
   Ars Nouveau -- player.clj:149's own comment already calls that
   reference out by name)."
  [state]
  (let [{:keys [catalog form effect-groups selected-effect palette-collapsed status busy?]} state
        selected-params (selected-param-fields state)
        draft-state (into {}
                          (keep (fn [{:keys [draft-key value]}]
                                  (when draft-key [draft-key (str value)])))
                          selected-params)
        shell (shell-geometry)
        {:keys [complexity cap over-cap?]} (spell-complexity state)
        chain (into (if form
                      [{:form? true :effect? false :index -1
                        :label (ui-label (glyph-label state (:glyph form)) 48.0)
                        :category-color (render/argb->rgba-floats (render/category-color :form))}]
                      [])
                    (map-indexed (fn [idx group] (chain-card state idx group selected-effect (count effect-groups))))
                    effect-groups)]
    (merge draft-state
           {:title "Spell Composer"
     :shell-header-h (:header-h shell) :shell-footer-h (:footer-h shell)
     :shell-body-h (:body-h shell) :shell-diagnostics-h (:diagnostics-h shell)
     :shell-palette-w (:palette-w shell) :shell-stage-w (:stage-w shell) :shell-inspector-w (:inspector-w shell)
     :palette-rows (palette-rows state catalog palette-collapsed)
     :complexity-label (str "Complexity " complexity " / " cap)
     :complexity-ratio (double (min 1.0 (/ (double complexity) (double (max 1 cap)))))
     :over-cap? (boolean over-cap?)
     :chain-items chain
     :selected-param-fields selected-params
     :can-cast? (boolean (and (not busy?) form (seq effect-groups)
                              (valid-param-drafts? state)))
     :busy? (boolean busy?)
     :status (ui-label (or status "") 420.0)
     :cast-label (if busy? "Casting..." "Cast")
     :clear-label "Clear"})))

;; --- input handling ------------------------------------------------------

(defn- payload-index [payload key]
  (let [value (or (get payload key) (get-in payload [:item key]))]
    (if (string? value) (try (Long/parseLong value) (catch Exception _ -1)) value)))

(defn- payload-keyword [value]
  (cond
    (keyword? value) value
    (string? value) (when (seq value) (keyword value))
    :else nil))

(defn- handle-action [state* owner action payload]
  (case action
    ;; The palette is now one list grouped by kind (C1/P1), not three
    ;; separately-titled panels each bound to their own add-X action --
    ;; this one handler routes by the clicked glyph's OWN :kind instead,
    ;; the same :kind palette-rows already grouped it under.
    :composer/glyph-activate
    (let [glyph (payload-keyword (:glyph (:item payload)))
          kind (:kind (descriptor-for @state* glyph))]
      (case kind
        :form (swap! state* pick-form glyph)
        :effect (swap! state* add-effect glyph)
        :augment (swap! state* add-augment glyph)
        nil))

    :composer/toggle-palette-category
    (let [kind (or (:kind payload) (get-in payload [:item :kind]))]
      (when kind (swap! state* toggle-palette-category (keyword kind))))

    :composer/param-increment
    (swap! state* param-step (payload-index payload :effect-index) (:param-key (:item payload)) 1)

    :composer/param-decrement
    (swap! state* param-step (payload-index payload :effect-index) (:param-key (:item payload)) -1)

    :composer/select-effect
    (swap! state* select-effect (payload-index payload :index))

    :composer/remove-effect
    (swap! state* remove-effect (payload-index payload :index))

    :composer/move-effect-up
    (swap! state* move-effect (payload-index payload :index) -1)

    :composer/move-effect-down
    (swap! state* move-effect (payload-index payload :index) 1)

    :composer/remove-augment
    (swap! state* remove-augment
           (payload-index payload :effect-index)
           (payload-index payload :augment-index))

     :composer/param-change
     (swap! state* param-change payload)

     :composer/param-submit
     (swap! state* param-submit payload)

    :composer/clear
    (swap! state* clear-composition)

    :composer/cast
    (let [snapshot @state*
          glyphs (composed-glyphs snapshot)]
      (cond
        (:busy? snapshot) nil
        (not (and (:form snapshot) (seq (:effect-groups snapshot))))
        (swap! state* assoc :status "Pick a form and at least one effect first.")
        (not (valid-param-drafts? snapshot))
        (swap! state* assoc :status "Finish valid parameter edits before casting.")
        :else
        (let [analysis (combat-api/analyze-player-spell glyphs combat-api/player-spell-complexity-cap)]
          (if-not (:ok analysis)
            (swap! state* assoc :status
                   (get reject-labels (:reject analysis)
                        (str "Spell rejected: " (:reject analysis))))
            (do
              (swap! state* assoc :busy? true :status "Casting...")
              (api/req-submit-spell!
               owner glyphs
               (fn [result]
                 (swap! state* assoc :busy? false :status
                        (case (:status result)
                          :accepted "Spell cast!"
                          :rejected (get reject-labels (:reason result)
                                         (str "Spell rejected: " (:reason result)))
                          (str "Unexpected result: " result))))))))))

    nil)
  (render-state @state*))

;; --- mount ---------------------------------------------------------------
;; --- mount ---------------------------------------------------------------

(defn open! [player-uuid]
  (let [owner (owner-for player-uuid)
        state* (atom (initial-state))
        on-close #(swap! active-mounts dissoc (str player-uuid))
        vm (presentation/mount-view!
            {:view-id :academy.app/spell-composer
             :host-kind :screen
             :state (render-state @state*)
             :dispatch-action! (fn [action payload _current] (handle-action state* owner action payload))
             :on-close on-close})]
    (swap! active-mounts assoc (str player-uuid) {:mount (:mount vm) :state* state*})
    (bridge/call-adapter :presentation-open-screen!
                         (:mount vm) "Spell Composer" on-close)
    vm))

