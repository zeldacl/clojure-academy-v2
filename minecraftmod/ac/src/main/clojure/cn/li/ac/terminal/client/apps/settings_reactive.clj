(ns cn.li.ac.terminal.client.apps.settings-reactive
  "Complete reactive replacement for settings.clj."
  (:require [cn.li.ac.ability.config :as ability-config]
            [cn.li.ac.config.common :as config-common]
            [cn.li.ac.tutorial.config :as tutorial-config]
            [cn.li.ac.config.gameplay :as gameplay-config]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.platform.config-persist :as config-persist]
            [cn.li.mcmod.spi.keybinding-registry :as kb-registry]
            [cn.li.mcmod.i18n :as i18n]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.core :as ui]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.ui.events :as events]
            [cn.li.mcmod.ui.node :as node]
            [cn.li.mcmod.ui.slot-write :as slot-write]
            [cn.li.mcmod.ui.xml :as ui-xml])
  (:import [cn.li.mcmod.ui.node INode]))

(def ^:private check-tex-true
  (modid/asset-path "textures/guis" "check_true.png"))

(def ^:private check-tex-false
  (modid/asset-path "textures/guis" "check_false.png"))

(def ^:private row-h 70.0)
(def ^:private visible-h 720.0)

;; Scrollbar thumb travel range — matches upstream DragBar(lower=119, upper=760)
;; in AcademyCraft's settings.xml.
(def ^:private thumb-min-y 119.0)
(def ^:private thumb-max-y 760.0)
(def ^:private thumb-travel (- thumb-max-y thumb-min-y))

;; :prop-id matches upstream's literal PropertyElements ids (used verbatim as
;; "settings.<modid>.prop.<id>" translation keys) — NOT the Clojure :key.
(def ^:private props
  [{:key :attack-player  :prop-id "attackPlayer"  :category "generic" :get ability-config/attack-player-enabled?    :domain config-common/ability-domain  :sp-only? true}
   {:key :destroy-blocks :prop-id "destroyBlocks" :category "generic" :get ability-config/destroy-blocks-enabled?   :domain config-common/ability-domain  :sp-only? true}
   {:key :heads-or-tails :prop-id "headsOrTails"  :category "generic" :get tutorial-config/heads-or-tails-enabled?  :domain config-common/tutorial-domain :sp-only? false}
   {:key :use-mouse-wheel :prop-id "useMouseWheel" :category "generic" :get gameplay-config/use-mouse-wheel-enabled? :domain config-common/gameplay-domain :sp-only? false}])

;; input-id -> upstream registered keybind name (ClientHandler.KEY_SWITCH_PRESET
;; / KEY_ACTIVATE_ABILITY, DebugConsole "debug_console", TerminalUI
;; "open_data_terminal") — same "settings.<modid>.prop.<id>" translation ids.
(def ^:private keybind-prop-id
  {:content/cycle-selection      "switch_preset"
   :content/toggle-primary-state "ability_activation"
   :content/toggle-debug-overlay "debug_console"
   :content/toggle-terminal      "open_data_terminal"})

;; Fallback display names for the 4 registered GLFW key codes when the
;; platform bridge can't supply a live localized name (Fabric — see
;; :keybind-get-key-name below).
(def ^:private glfw-key-names
  {67 "C" 86 "V" 293 "F4" 342 "Left Alt"})

(defn- persist! [domain key value] (config-persist/persist-config-value! domain key value))
(defn- toggle-config! [p] (let [v (not ((:get p)))] (persist! (:domain p) (:key p) v) v))

(defn- settings-i18n [suffix]
  (str "settings." modid/MOD-ID "." suffix))

(defn- checkbox-text [p]
  (or (i18n/translate (settings-i18n (str "prop." (:prop-id p))))
      (:prop-id p)))

(defn- cathead-text [cat]
  (or (i18n/translate (settings-i18n (str "cat." cat)))
      cat))

(defn- keybind-label [input-id]
  (let [prop-id (get keybind-prop-id input-id (name input-id))]
    (or (i18n/translate (settings-i18n (str "prop." prop-id))) prop-id)))

(defn- hide-row-sections! [^INode item]
  (doseq [id ["cathead-line" "cathead-text" "checkbox-row" "key-row"]]
    (when-let [^INode n (ui/item-node item id)]
      (.setVisible n false))))

(defn- show-only! [^INode item section-id]
  (hide-row-sections! item)
  (when-let [^INode n (ui/item-node item section-id)]
    (.setVisible n true)))

(defn- update-checkbox-item! [r item p checked?]
  (show-only! item :checkbox-row)
  (let [text-n (ui/item-node item :text)
        box-n (ui/item-node item :box)]
    (ui/set-node-prop! r text-n :text (checkbox-text p))
    (ui/set-node-prop! r box-n :src (if checked? check-tex-true check-tex-false))))

(defn- wire-checkbox-click! [r item p]
  (let [^INode box (ui/item-node item :box)]
    (rt/register-event! r (.getIdx box) :left-click
      (fn [_ _ _]
        (let [new-val (toggle-config! p)]
          (update-checkbox-item! r item p new-val))))))

(defn- wire-cathead-item! [r item cat]
  (show-only! item :cathead-text)
  (ui/set-node-prop! r (ui/item-node item :cathead-text) :text (cathead-text cat))
  (when-let [^INode line (ui/item-node item :cathead-line)]
    (.setVisible line true)))

(defn- rebind-supported? []
  (boolean (bridge/call-adapter :keybind-rebind-supported?)))

(defn- default-key-name [key-code]
  (get glfw-key-names key-code (str "KEY_" key-code)))

(defn- current-key-name [input-id default-code]
  (or (bridge/call-adapter :keybind-get-key-name input-id)
      (default-key-name default-code)))

(defn- wire-key-binding-item! [r item {:keys [input-id config]}]
  (show-only! item :key-row)
  (let [default-code (get-in config [:key-mapping :key])
        editable? (rebind-supported?)
        key-text (sig/signal-o (current-key-name input-id default-code))
        recording? (atom false)
        ^INode key-val (ui/item-node item :key-value)
        writer (slot-write/resolve-sig-writer (get node/kinds :text) :text)
        b (sig/bind! key-text key-val writer (rt/get-dirty-bindings-q r))]
    (ui/set-node-prop! r (ui/item-node item :key-label) :text (keybind-label input-id))
    (rt/register-binding! r (.getIdx key-val) b)
    (when editable?
      (rt/register-event! r (.getIdx key-val) :left-click
        (fn [_ _ _]
          (reset! recording? true)
          (sig/sset-o! key-text "PRESS...")))
      (rt/register-event! r (.getIdx key-val) :key
        (fn [_ _ evt]
          (when @recording?
            (reset! recording? false)
            (let [key-code (:key-code evt)]
              (bridge/call-adapter :keybind-set-key! input-id key-code)
              (sig/sset-o! key-text (current-key-name input-id default-code)))))))))

(defn- all-settings-rows []
  (into []
    (concat
      (mapcat (fn [[cat cat-props]]
                (cons {:type :cathead :label cat}
                      (map #(assoc % :type :checkbox) cat-props)))
              (group-by :category props))
      (let [keybinds (kb-registry/get-all-keybinding-configs)]
        (when (seq keybinds)
          (cons {:type :cathead :label "keys"}
                (map (fn [[input-id config]] {:type :key-binding :input-id input-id :config config})
                     keybinds)))))))

(defn- populate-settings-list! [r rows]
  (ui/list-set! r "settings-list" rows
    (fn [rt item row]
      (case (:type row)
        :cathead (wire-cathead-item! rt item (:label row))
        :checkbox (do (update-checkbox-item! rt item row ((:get row)))
                      (wire-checkbox-click! rt item row))
        :key-binding (wire-key-binding-item! rt item row)
        nil))))

(defn- sync-scrollbar-thumb! [r progress]
  (let [^INode thumb (rt/node-by-id r :scrollbar)]
    (.setY thumb (double (+ thumb-min-y (* (double progress) thumb-travel))))
    (.setFlag thumb node/FLAG-LAYOUT-DIRTY)))

(defn create-runtime []
  (let [r (rt/create-runtime)
        rows (all-settings-rows)
        _ (rt/build! r (ui-xml/load-spec (modid/namespaced-path "guis/new/settings.xml")))
        scroll (sig/signal-d 0.0)
        max-scroll (max 0.0 (- (* (count rows) row-h) visible-h))
        scroll-px (sig/computed-d [scroll]
                     (fn [_] (* (sig/sget-d scroll) max-scroll)))
        set-scroll! (fn [progress]
                      (let [p (max 0.0 (min 1.0 (double progress)))]
                        (sig/sset-d! scroll p)
                        (sync-scrollbar-thumb! r p)))
        drag-start-y (atom thumb-min-y)]
    (rt/put-user-signal! r :scroll scroll)
    (ui/bind! r :settings-list :scroll-offset scroll-px)
    (populate-settings-list! r rows)
    ;; Scrollbar interaction: upstream (DragBar) only supports drag-the-thumb;
    ;; mouse-wheel is kept here as an added convenience on top of that.
    (events/on! r :scrollbar :mouse-scroll
      (fn [_ _ evt]
        (set-scroll! (+ (sig/sget-d scroll) (* (:delta evt) 0.01)))))
    (events/on! r :scrollbar :drag-start
      (fn [_ _ _]
        (reset! drag-start-y (.getY ^INode (rt/node-by-id r :scrollbar)))))
    (events/on! r :scrollbar :drag
      (fn [_ _ evt]
        (let [new-y (max thumb-min-y (min thumb-max-y (+ @drag-start-y (double (:dy evt)))))]
          (set-scroll! (/ (- new-y thumb-min-y) thumb-travel)))))
    r))

(defn open! []
  (let [r (create-runtime)]
    (bridge/open-reactive-screen! r "Settings")))
