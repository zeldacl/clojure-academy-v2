(ns cn.li.ac.block.developer.presentation
  "Presentation Runtime controller shared by the block and portable developer.
   AC owns developer semantics and RPCs; the artifact owns layout and painting."
  (:require [clojure.string :as str]
            [cn.li.ac.ability.client.api :as client-api]
            [cn.li.ac.ability.client.read-model :as read-model]
            [cn.li.ac.ability.client.screens.skill-tree :as skill-tree]
            [cn.li.ac.ability.config :as ability-config]
            [cn.li.ac.ability.registry.category :as category]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.ac.ability.rules.learning-rules :as learning-rules]
            [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.ability.domain.developer :as developer]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.gui.presentation :as presentation]
            [cn.li.ac.gui.presentation-container :as presentation-container]
            [cn.li.ac.item.special-items :as special-items]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.gui.container.action-payload :as action-payload]
            [cn.li.mcmod.gui.container-state :as container-state]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.i18n :as i18n]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.block.developer.console :as console]))

(def ^:private view-id :academy.app/developer)
(def ^:private area-w 257.0)
(def ^:private area-h 139.0)
(def ^:private node-size 16.0)
(def ^:private default-ability-icon
  "academy:textures/guis/icons/icon_nocategory.png")

(defn- player-id [player]
  (some-> (uuid/player-uuid player) str))

(defn- owner-of [container]
  (or (try (container-state/owner-from-container container)
           (catch Throwable _ nil))
      (:owner container)
      (runtime-hooks/default-client-owner)))

(defn- session-id [container]
  (or (:client-session-id (owner-of container))
      (runtime-hooks/client-session-id)))

(defn- player-state [container player]
  (when-let [sid (player-id player)]
    (store/get-player-state (session-id container) sid)))

(defn- value-of [v]
  (if (instance? clojure.lang.IDeref v) @v v))

(defn- developer-type [container]
  (let [tier (keyword (or (value-of (:tier container)) :normal))]
    (if (developer/developer-type? tier) tier :normal)))

(defn- has-coil? [player]
  (= special-items/magnetic-coil-item-id
     (some-> player entity/player-get-main-hand-item-id)))

(defn- panel-mode [container player pstate]
  (let [has-category? (some? (get-in pstate [:ability-data :category-id]))]
    (cond
      (and has-category? (has-coil? player)) :reset-console
      (not has-category?) :console
      :else :skill-tree)))

(defn- display-name [skill-id]
  (or (some (fn [spec]
              (when (= (:id spec) skill-id)
                (or (:name spec) (:name-key spec))))
            (skill-query/list-skills))
      (name skill-id)))

(defn- skill-icon-src [icon]
  (cond
    (and (string? icon) (seq icon) (str/includes? icon ":")) icon
    (and (string? icon) (seq icon)) (modid/namespaced-path icon)
    :else nil))

(defn- texture-path-from-category-icon [icon-str]
  (when (string? icon-str)
    (cond
      (str/includes? icon-str ":") icon-str
      (str/starts-with? icon-str "textures/")
      (modid/asset-path "textures" (subs icon-str (count "textures/")))
      :else (modid/asset-path "textures" icon-str))))

(defn- tree-bbox [nodes]
  (when (seq nodes)
    (let [xs (map #(double (or (:x %) 0.0)) nodes)
          ys (map #(double (or (:y %) 0.0)) nodes)]
      {:minx (apply min xs)
       :maxx (+ (apply max xs) node-size)
       :miny (apply min ys)
       :maxy (+ (apply max ys) node-size)})))

(defn- area-fit-transform
  "Scale+offset so node bbox fits in area-w×area-h (main skill-tree-view)."
  [nodes w h]
  (if-let [bb (tree-bbox nodes)]
    (let [bw (max 1.0 (- (:maxx bb) (:minx bb)))
          bh (max 1.0 (- (:maxy bb) (:miny bb)))
          pad 14.0
          s (min 1.0 (/ (- (double w) pad) bw) (/ (- (double h) pad) bh))
          bcx (/ (+ (:minx bb) (:maxx bb)) 2.0)
          bcy (/ (+ (:miny bb) (:maxy bb)) 2.0)]
      [s
       (double (Math/round (- (/ (double w) 2.0) (* bcx s))))
       (double (Math/round (- (/ (double h) 2.0) (* bcy s))))])
    [1.0 0.0 0.0]))

(defn- area-skill-composite
  "Composite items for the developer right-panel area (257×139)."
  [nodes connections selected]
  (let [[s ox oy] (area-fit-transform nodes area-w area-h)
        map-pt (fn [x y] [(+ ox (* s (double (or x 0.0))))
                          (+ oy (* s (double (or y 0.0))))])
        bg [{:kind :quad :layout-x 0.0 :layout-y 0.0 :x 0.0 :y 0.0
             :w area-w :h area-h :rgba 0x6610151F}]
        wires
        (mapcat (fn [{:keys [from-x from-y to-x to-y]}]
                  (let [[x1 y1] (map-pt from-x from-y)
                        [x2 y2] (map-pt to-x to-y)
                        ;; Centers of 16px nodes
                        x1 (+ x1 (* 0.5 node-size s))
                        y1 (+ y1 (* 0.5 node-size s))
                        x2 (+ x2 (* 0.5 node-size s))
                        y2 (+ y2 (* 0.5 node-size s))
                        x (min x1 x2) y (min y1 y2)
                        w (max 1.0 (Math/abs (- x2 x1)))
                        h (max 1.0 (Math/abs (- y2 y1)))]
                    [{:kind :quad :layout-x x :layout-y y :x 0.0 :y 0.0
                      :w w :h h :rgba 0x88FFFFFF}]))
                connections)
        node-items
        (mapcat (fn [{:keys [skill-id skill-icon x y learned]}]
                  (let [[px py] (map-pt x y)
                        sz (* node-size s)
                        selected? (= skill-id selected)
                        back-tint (cond selected? 0xFF68B5FF
                                        learned 0xFF65D58A
                                        :else 0xFFB0B8C4)
                        icon (skill-icon-src skill-icon)]
                    (vec (remove nil?
                           [{:kind :image
                             :src "academy:textures/guis/developer/skill_back.png"
                             :layout-x px :layout-y py :x 0.0 :y 0.0
                             :w sz :h sz :rgba back-tint :skill-id skill-id}
                            (when icon
                              {:kind :image :src icon
                               :layout-x (+ px (* sz 0.12)) :layout-y (+ py (* sz 0.12))
                               :x 0.0 :y 0.0 :w (* sz 0.76) :h (* sz 0.76)
                               :skill-id skill-id})
                            (when selected?
                              {:kind :image
                               :src "academy:textures/guis/developer/skill_outline.png"
                               :layout-x (- px (* sz 0.15)) :layout-y (- py (* sz 0.15))
                               :x 0.0 :y 0.0 :w (* sz 1.3) :h (* sz 1.3)
                               :rgba 0xFF68B5FF :skill-id skill-id})]))))
                nodes)]
    (vec (concat bg wires node-items))))

(defn- find-skill-node [nodes sid]
  (some #(when (= sid (:skill-id %)) %) nodes))

(defn- level-up-ready? [pstate]
  (let [ad (:ability-data pstate)
        cat-id (:category-id ad)
        level (long (or (:level ad) 1))]
    (and cat-id (< level 5)
         (let [skills (skill-query/get-controllable-skills-at-level cat-id level)
               rate (category/get-prog-incr-rate cat-id)
               threshold (learning-rules/level-up-threshold ad skills rate
                                                            (ability-config/prog-incr-rate))]
           (or (nil? threshold) (>= (double (or (:level-progress ad) 0.0))
                                    (double threshold)))))))

(defn- append-console! [state text]
  "Legacy helper — prefer console machine for typed output."
  (let [line (cond
               (string? text) text
               (keyword? text) (name text)
               :else nil)]
    (when (and line (seq line))
      (swap! state update :console
             (fn [cs]
               (if cs
                 (-> cs
                     (update :lines into (vec (str/split-lines line)))
                     (update :lines #(if (> (count %) console/max-lines)
                                       (subvec (vec %) (- (count %) console/max-lines))
                                       (vec %)))
                     (assoc :dirty? true))
                 cs))))))

(defn- refresh! [container]
  (when-let [f (:presentation-refresh! container)]
    (f)))

(defn- live-container
  "Prefer the menu-bound container (has :minecraft-container). Dispatch
   closures from prepare-container capture a pre-bind map otherwise."
  [container]
  (or (when-let [live* (:presentation-live-container container)]
        @live*)
      container))

(defn- wireless-state
  "Prefer the shared Presentation wireless atom (drives wireless_page); fall
   back to the developer UI atom for portable / pre-bind snapshots."
  ([state] (wireless-state state nil))
  ([state container]
   (or (some-> container live-container :presentation-wireless-state deref)
       (:wireless @state)
       {:linked nil :avail [] :password ""})))

(defn- send-wireless! [container action payload callback]
  (let [c (live-container container)
        owner (owner-of c)
        message-id (msg-registry/msg :developer action)]
    (net-client/send-to-server owner message-id
      (action-payload/action-payload c payload)
      callback)))

(defn- apply-wireless-response! [container state response]
  (let [c (live-container container)
        patch (cond-> {}
                (contains? response :linked) (assoc :linked (:linked response))
                (contains? response :avail) (assoc :avail (vec (or (:avail response) [])))
                (contains? response :password) (assoc :password (str (:password response))))]
    (when-let [ws (:presentation-wireless-state c)]
      (swap! ws #(merge (or % {:linked nil :avail [] :password ""}) patch)))
    (swap! state update :wireless #(merge (or % {}) patch))
    (refresh! c)))

(defn- open-wireless-overlay! [container state]
  (let [c (live-container container)]
    (swap! state assoc :wireless-page-visible? true :status "Wireless")
    (refresh! c)
    (send-wireless! c :list-nodes {}
      (fn [response]
        (apply-wireless-response! c state response)))))

(defn- close-wireless-overlay! [container state]
  (let [c (live-container container)]
    (swap! state assoc :wireless-page-visible? false)
    (refresh! c)))

(defn- wireless-items [state container]
  (mapv (fn [item]
          {:label (str (or (:node-name item) "Node"))
           :action-label "Link"
           :node-x (:pos-x item) :node-y (:pos-y item) :node-z (:pos-z item)})
        (:avail (wireless-state state container))))

(defn- start-development! [container player state action skill-id]
  (let [c (live-container container)
        extra (cond-> {} skill-id (assoc :skill-id (name skill-id)))
        callback (fn [response]
                   (let [ok? (or (true? (:success? response))
                                 (true? (:success response)))
                         detail (let [e (or (:error response) (:reason response))]
                                  (cond
                                    (string? e) e
                                    (keyword? e) (name e)
                                    :else nil))]
                     (append-console! state (if ok?
                                              "Development started."
                                              (str "Development rejected"
                                                   (when detail (str ": " detail)))))
                     (swap! state assoc :status (if ok? "Development in progress" "Request rejected"))
                     (refresh! c)))]
    (if-let [handler (:on-dev-start c)]
      (handler action extra callback)
      (let [owner (owner-of c)
            msg-id (msg-registry/msg :developer :start-development)
            payload (action-payload/action-payload c (merge {:action action} extra))]
        (net-client/send-to-server owner msg-id payload callback)))))

(defn- reset-allowed? [container player pstate]
  (let [ad (:ability-data pstate)
        factor (special-items/find-induction-factor player)
        c (live-container container)]
    (and (developer/gte? (developer-type c) :advanced)
         (>= (long (or (:level ad) 1)) 3)
         (has-coil? player)
         factor
         (not= (:category factor) (:category-id ad)))))

(defn- console-editing?
  [state]
  (when-let [cs (:console @state)]
    (console/editing? cs)))

(defn- apply-console-input!
  "Whole-area key capture: glyphs / backspace into console. Ignores map
   payloads (e.g. mistaken submit :value = entire view-state)."
  [container state payload]
  (when (console-editing? state)
    (cond
      (or (true? (:backspace payload))
          (= 259 (int (or (:key-code payload) -1))))
      (do (swap! state update :console #(some-> % console/backspace-input))
          (refresh! container))

      (and (string? (:text payload))
           (not (contains? payload :value))
           (<= (count (:text payload)) 8))
      (do (swap! state update :console #(some-> % (console/type-char (:text payload))))
          (refresh! container))

      (string? (:value payload))
      (do (swap! state update :console #(some-> % (console/set-input (:value payload))))
          (refresh! container)))))

(defn- submit-console! [container player state raw]
  (let [c (live-container container)
        text (cond
               (string? raw) raw
               (string? (get-in @state [:console :input])) (get-in @state [:console :input])
               :else "")]
    (swap! state update :console
           (fn [cs]
             (if cs
               (-> cs
                   (console/set-input text)
                   console/submit-input)
               cs)))
    (refresh! c)))

(defn- ensure-console!
  "Create/reset the typewriter console when the right-panel mode changes."
  [state container player mode]
  (when (or (= mode :console) (= mode :reset-console))
    (let [c (live-container container)
          console-mode (if (= mode :reset-console) :reset :learn)
          cur (:console @state)]
      (when (or (nil? cur) (not= (:panel-mode cur) mode))
        (let [player-name (or (try (entity/player-get-name player) (catch Throwable _ nil))
                              "Player")
              has-dev? (not= :skill-tree-viewer (:container-type c))
              cs (console/init-state console-mode player-name has-dev?)
              cs (assoc cs
                        :panel-mode mode
                        :on-start-development
                        (when has-dev?
                          (fn []
                            (start-development!
                              c player state
                              (if (= console-mode :reset) :reset :level-up)
                              nil)))
                        :reset-precheck
                        (fn []
                          (when-not (reset-allowed? c player
                                                     (player-state c player))
                            :reset_fail_other)))]
          (swap! state assoc :console cs))))))

(defn- snapshot [container player state]
  (let [pstate (or (player-state container player) {})
        ad (:ability-data pstate)
        cat (some-> (:category-id ad) category/get-category)
        mode (panel-mode container player pstate)
        selected (:selected-skill @state)
        skill-tree? (= mode :skill-tree)
        console? (or (= mode :console) (= mode :reset-console))
        render (when skill-tree?
                 (skill-tree/build-render-data-for-player-state pstate (developer-type container)))
        nodes (vec (or (:skill-nodes render) []))
        connections (vec (or (:connections render) []))
        selected-node (when selected (find-skill-node nodes selected))
        development-progress (max 0.0 (min 1.0 (double (or (value-of (:development-progress container)) 0.0))))
        development-active? (boolean (value-of (:is-developing container)))
        selected-conditions (skill-tree/condition-items (:conditions selected-node))
        hover-index (:condition-hover @state)
        hover-condition (when (integer? hover-index)
                          (nth selected-conditions hover-index nil))
        can-upgrade? (boolean (level-up-ready? pstate))
        level (long (or (:level ad) 0))
        level-prog (double (or (:level-progress ad) 0.0))
        selected-detail (if selected-node
                          {:title (str (or (:skill-name selected-node) (display-name selected)))
                           :level (str "Required level: " (:skill-level selected-node))
                           :description (str (or (:skill-description selected-node) ""))
                           :condition-label "Req."
                           :condition-items selected-conditions
                           :condition-hint (if hover-condition
                                             (str "(" (:hint-text hover-condition) ")") "")
                           :learn-label (if (:learned selected-node) "Learned" "Learn")
                           :can-learn? (and (not development-active?)
                                            (boolean (:can-learn selected-node)))
                           :development-progress development-progress
                           :development-label (if development-active?
                                               (str "Progress " (format "%.0f%%" (* 100.0 development-progress)))
                                               "")}
                          {:title "Select a skill" :level "" :description ""
                           :condition-label "" :condition-items []
                           :condition-hint "" :learn-label "Learn" :can-learn? false
                           :development-progress 0.0 :development-label ""})
        energy (double (or (value-of (:energy container)) 0.0))
        max-energy (max 1.0 (double (or (value-of (:max-energy container)) 1.0)))
        dtype (developer-type container)
        dspec (developer/developer-spec dtype)
        wireless (wireless-state state container)
        linked (:linked wireless)
        icon-path (or (some-> cat :icon texture-path-from-category-icon)
                      default-ability-icon)
        portable? (= :portable (value-of (:tier container)))]
    (merge @state
           {:title "Ability Developer"
            :mode (name mode)
            :ability-name (if cat (or (some-> cat :name-key i18n/translate) "N/A") "N/A")
            :level-label (let [k (str "ability.academy.level" level)
                               t (i18n/translate k)]
                           (if (or (nil? t) (= t k)) (str "Lv." level) (str t)))
            :exp-label (let [k "skill_tree.academy.exp"
                             t (i18n/translate k)
                             prefix (if (or (nil? t) (= t k)) "EXP" (str t))]
                         (str prefix " " (int (* 100.0 level-prog)) "%"))
            :level-prog (max 0.0 (min 1.0 level-prog))
            :can-upgrade? can-upgrade?
            :level-label-visible? (not can-upgrade?)
            :ability-icon-items [{:kind :image :src icon-path
                                  :layout-x 0.0 :layout-y 0.0 :x 0.0 :y 0.0
                                  :w 32.0 :h 32.0 :rgba 0xFFFFFFFF}]
            :energy-ratio (max 0.0 (min 1.0 (/ energy max-energy)))
            :sync-rate (double (or (:sync-rate dspec) 0.7))
            :skill-tree-visible? skill-tree?
            :console-visible? console?
            :composite-list (if skill-tree?
                              (area-skill-composite nodes connections selected)
                              [])
            :console-lines (if console?
                             (console/body-rows (or (:console @state)
                                                    (console/init-state :learn "Player" true)))
                             [])
            :console-prompt (if console?
                              (console/prompt-line (or (:console @state)
                                                       (console/init-state :learn "Player" true)))
                              "")
            :detail-visible? (boolean (and skill-tree? selected-node))
            :wireless-visible (not portable?)
            :wireless-page-visible? (boolean (and (not portable?)
                                                  (:wireless-page-visible? @state)))
            :wireless-state (if linked "Connected" "Not connected")
            :wireless-node-name (or (:node-name linked) "N/A")
            :wireless-owner (str "Node: " (or (:node-name linked) "-"))
            :wireless-range (str "Range: " (or (:range linked) "-"))
            :wireless-bandwidth (str "Bandwidth: " (or (:bandwidth linked) "-"))
            :wireless-load 0.0
            :wireless-nodes (wireless-items state container)
            :wireless-password (str (or (:password wireless) ""))
            :wireless-disconnect {:label "Disconnect"}
            :wireless-available-label {:label "Available"}
            :selected-detail selected-detail
            :selected-skill (if selected-node
                              (str "Selected: " (:skill-name selected-node))
                              "No skill selected")
            :button-upgrade {:label (if can-upgrade? "Level Up" "Level Up (locked)")}
            :button-reset {:label "Reset"}
            :button-wireless {:label (if portable? "Unavailable" "Wireless")}
            :status (or (:status @state)
                        (case mode
                          :console "Type help, levelup, or reset"
                          :reset-console "Type reset or use the button"
                          "Select a skill"))})))

(defn- ensure-state [container]
  (or (:presentation-developer-state container)
      (atom {:selected-skill nil :condition-hover nil
             :console nil :console-last-ms nil
             :wireless-page-visible? false
             :status nil :wireless {:linked nil :avail [] :password ""}})))

(defn prepare-container [container player]
  (let [state (ensure-state container)
        live* (or (:presentation-live-container container) (atom nil))
        ;; Closures must read @live* so C2S sees :minecraft-container after
        ;; presentation-screen-data binds the open menu.
        c (fn [] (or @live* container))]
    (let [prepared
          (assoc container
                 :presentation-developer-state state
                 :presentation-live-container live*
                 :presentation-snapshot-fn
                 (fn [cont p]
                   (let [mode (panel-mode cont p (player-state cont p))]
                     (ensure-console! state cont p mode)
                     (snapshot cont p state)))
                 :presentation-frame!
                 (fn [cont]
                   (let [p (or (:player cont) player)
                         pstate (player-state cont p)
                         mode (panel-mode cont p pstate)
                         now (System/nanoTime)
                         last (or (:console-last-ms @state) now)
                         dt (min 0.05 (max 0.0 (/ (double (- now last)) 1.0e9)))]
                     (swap! state assoc :console-last-ms now)
                     (ensure-console! state cont p mode)
                     (when (and (:console @state)
                                (or (= mode :console) (= mode :reset-console)))
                       (let [cs (:console @state)
                             cs' (console/tick cs dt
                                   :development-progress (value-of (:development-progress cont))
                                   :is-developing? (value-of (:is-developing cont))
                                   :development-complete? (value-of (:development-complete? cont)))]
                         (when (not= cs cs')
                           (swap! state assoc :console cs')
                           (when (:dirty? cs')
                             (refresh! cont)))))))
                 :presentation-dispatch-action!
                 (fn [action payload]
                   (let [cont (c)
                         item (:item payload)
                         sid (or (some-> (:skill-id item) keyword)
                                 (some-> (:skill-id payload) keyword)
                                 (:selected-skill @state))]
                     (case action
                       :developer/select-skill
                       (when sid
                         (swap! state assoc :selected-skill sid :condition-hover nil
                                :status (str "Selected " (display-name sid)))
                         (refresh! cont))
                       :developer/close-detail
                       (do (swap! state assoc :selected-skill nil :condition-hover nil)
                           (refresh! cont))
                       :developer/learn-skill
                       (when (and sid (:can-learn? (:selected-detail (snapshot cont player state))))
                         (start-development! cont player state :learn-skill sid))
                       :developer/condition-hover
                       (do (swap! state assoc :condition-hover
                                  (when (:hover? payload)
                                    (:condition-index (:item payload))))
                           (refresh! cont))
                       (:developer/console-input :input/character :input/backspace)
                       (apply-console-input! cont state payload)
                       :developer/console-submit
                       (submit-console! cont player state
                                        (or (:value payload)
                                            (get-in @state [:console :input])))
                       :input/key
                       (when (and (= 257 (int (or (:key-code payload) -1)))
                                  (console-editing? state))
                         (submit-console! cont player state
                                          (get-in @state [:console :input])))
                       :developer/level-up
                       (start-development! cont player state :level-up nil)
                       :developer/reset
                       (if (reset-allowed? cont player (player-state cont player))
                         (start-development! cont player state :reset nil)
                         (append-console! state "Reset requirements are not met."))
                       :developer/wireless
                       (when (not= :portable (value-of (:tier cont)))
                         (open-wireless-overlay! cont state))
                       :developer/wireless-close
                       (close-wireless-overlay! cont state)
                       :developer/wireless-password
                       (when-let [ws (:presentation-wireless-state cont)]
                         (swap! ws assoc :password (str (or (:value payload) ""))))
                       :developer/wireless-connect
                       (let [item (:item payload)
                             password (str (or (:password (wireless-state state cont)) ""))
                             payload* (assoc item :password password :need-auth? true)]
                         (send-wireless! cont :connect payload*
                           (fn [response] (apply-wireless-response! cont state response))))
                       :developer/wireless-disconnect
                       (send-wireless! cont :disconnect {}
                         (fn [response] (apply-wireless-response! cont state response)))
                       nil)
                     nil))
                 :presentation-on-mount!
                 (fn [cont]
                   (let [p (or (:player cont) player)]
                     (ensure-console! state cont p
                                      (panel-mode cont p (player-state cont p)))
                     (when (not= :portable (value-of (:tier cont)))
                       (send-wireless! cont :list-nodes {}
                         (fn [response] (apply-wireless-response! cont state response)))))))]
      (reset! live* prepared)
      prepared)))

(defn create-screen [container menu player]
  (let [container (-> (prepare-container container player)
                      ;; Classic page_developer.xml canvas is 400×187, not TechUI 290.
                      (assoc :presentation-image-width 400
                             :presentation-image-height 187
                             :default-player-inventory-mode :none
                             ;; Same receiver role as main page_wireless overlay.
                             :presentation-wireless {:domain :developer :role :receiver}))
        _ (when-let [live* (:presentation-live-container container)]
            (reset! live* container))]
    (presentation-container/presentation-screen-data
      container menu player :developer "academy:developer")))

(defn open-portable! [player]
  (let [owner (read-model/local-client-owner (player-id player) "developer.portable")
        refresh* (atom nil)
        base-container {:player player :owner owner :tier (atom :portable)
                        :energy (atom 0.0) :max-energy (atom 10000.0)
                        :on-dev-start (fn [action extra callback]
                                        (client-api/req-portable-dev-start!
                                          owner action (some-> extra :skill-id keyword) callback))}
        container (prepare-container
                    (assoc base-container
                           :presentation-refresh!
                           (fn []
                             (when-let [refresh @refresh*] (refresh))))
                    player)
        state-fn (fn [] (snapshot container player (:presentation-developer-state container)))
        vm (presentation/mount-view!
             {:view-id view-id :host-kind :screen :state (state-fn)
              :dispatch-action! (fn [action payload _current]
                                  (when-let [dispatch (:presentation-dispatch-action! container)]
                                    (dispatch action payload))
                                  (state-fn))
              :on-close (fn [] nil)})]
    (reset! refresh* #(presentation/present! vm (state-fn)))
    (bridge/call-adapter :presentation-open-screen! (:mount vm) "Portable Developer" nil)
    vm))