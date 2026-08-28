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
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]))

(def ^:private view-id :academy.app/developer)
(def ^:private max-console-lines 10)

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

(defn- skill-rows [container player pstate selected]
  (let [dev-type (developer-type container)
        nodes (vec (or (:skill-nodes
                         (skill-tree/build-render-data-for-player-state pstate dev-type)) []))]
    (mapv (fn [node]
            (let [sid (:skill-id node)
                  selected? (= sid selected)]
              {:skill-id sid
               :label (str (when selected? "> ")
                           (if (:learned node) "[learned] " "[ ] ")
                           (or (:skill-name node) (display-name sid))
                           "  " (format "%.0f%%" (* 100.0 (double (or (:exp node) 0.0)))))
               :select-label (if selected? "Selected" "Select")
               :learned? (boolean (:learned node))
               :can-learn? (boolean (:can-learn node))}))
          nodes)))

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
  (let [lines (vec (concat (:console-lines @state) (str/split-lines (str text))))
        lines (if (> (count lines) max-console-lines)
                (subvec lines (- (count lines) max-console-lines))
                lines)]
    (swap! state assoc :console-lines (mapv (fn [line] {:label line}) lines))))

(defn- refresh! [container]
  (when-let [f (:presentation-refresh! container)]
    (f)))

(defn- start-development! [container player state action skill-id]
  (let [extra (cond-> {} skill-id (assoc :skill-id (name skill-id)))
        callback (fn [response]
                   (append-console! state (if (:success? response)
                                             "Development started."
                                             (str "Development rejected: "
                                                  (or (:error response) (:reason response) "unknown"))))
                   (swap! state assoc :status (if (:success? response) "Development in progress" "Request rejected"))
                   (refresh! container))]
    (if-let [handler (:on-dev-start container)]
      (handler action extra callback)
      (let [owner (owner-of container)
            msg-id (msg-registry/msg :developer :start-development)
            payload (action-payload/action-payload container (merge {:action action} extra))]
        (net-client/send-to-server owner msg-id payload callback)))))

(defn- reset-allowed? [container player pstate]
  (let [ad (:ability-data pstate)
        factor (special-items/find-induction-factor player)]
    (and (developer/gte? (developer-type container) :advanced)
         (>= (long (or (:level ad) 1)) 3)
         (has-coil? player)
         factor
         (not= (:category factor) (:category-id ad)))))

(defn- submit-console! [container player state raw]
  (let [parts (->> (str/split (str/trim (or raw "")) #"\s+") (remove str/blank?) vec)
        command (some-> (first parts) str/lower-case)
        pstate (player-state container player)
        sid (some-> (second parts) keyword)]
    (swap! state assoc :console-input "")
    (case command
      "help" (append-console! state "Commands: learn <skill>, levelup, reset")
      "learn" (if sid
                 (do (swap! state assoc :selected-skill sid)
                     (start-development! container player state :learn-skill sid))
                 (append-console! state "Usage: learn <skill>"))
      "levelup" (if (level-up-ready? pstate)
                   (start-development! container player state :level-up nil)
                   (append-console! state "Level-up requirements are not met."))
      "reset" (if (reset-allowed? container player pstate)
                 (start-development! container player state :reset nil)
                 (append-console! state "Reset requirements are not met."))
      (when (seq command)
        (append-console! state "Unknown command. Type help.")))))

(defn- snapshot [container player state]
  (let [pstate (or (player-state container player) {})
        ad (:ability-data pstate)
        cat (some-> (:category-id ad) category/get-category)
        mode (panel-mode container player pstate)
        selected (:selected-skill @state)
        rows (if (= mode :skill-tree) (skill-rows container player pstate selected) [])
        selected-row (some #(when (= selected (:skill-id %)) %) rows)
        energy (double (or (value-of (:energy container)) 0.0))
        max-energy (max 1.0 (double (or (value-of (:max-energy container)) 1.0)))
        dtype (developer-type container)
        dspec (developer/developer-spec dtype)]
    (merge @state
           {:title "Ability Developer"
            :mode (name mode)
            :ability-name (or (some-> cat :name-key i18n/translate) "No ability selected")
            :level-label (str "Level " (or (:level ad) 0))
            :exp-label (str "EXP " (format "%.0f%%" (* 100.0 (double (or (:level-progress ad) 0.0)))))
            :energy-ratio (max 0.0 (min 1.0 (/ energy max-energy)))
            :sync-rate (double (or (:sync-rate dspec) 0.7))
            :skills rows
            :selected-skill (if selected-row
                              (str "Selected: " (:label selected-row))
                              "No skill selected")
            :button-upgrade {:label (if (level-up-ready? pstate) "Level Up" "Level Up (locked)")}
            :button-reset {:label (if (= mode :reset-console) "Reset" "Reset")}
            :button-wireless {:label (if (= :portable (:tier container)) "Unavailable" "Wireless")}
            :status (or (:status @state)
                        (case mode
                          :console "Type help, levelup, or reset"
                          :reset-console "Type reset or use the button"
                          "Select a skill and use the command input"))})))

(defn- ensure-state [container]
  (or (:presentation-developer-state container)
      (let [state (atom {:selected-skill nil :console-lines [] :console-input "" :status nil})]
        state)))

(defn prepare-container [container player]
  (let [state (ensure-state container)]
    (assoc container
           :presentation-developer-state state
           :presentation-snapshot-fn (fn [c p] (snapshot c p state))
           :presentation-dispatch-action!
           (fn [action payload]
             (let [item (:item payload)
                   sid (some-> (:skill-id item) keyword)]
               (case action
                 :developer/select-skill
                 (do (swap! state assoc :selected-skill sid :status (str "Selected " (display-name sid)))
                     (refresh! container))
                 :developer/level-up
                 (start-development! container player state :level-up nil)
                 :developer/reset
                 (if (reset-allowed? container player (player-state container player))
                   (start-development! container player state :reset nil)
                   (append-console! state "Reset requirements are not met."))
                 :developer/wireless
                 (swap! state assoc :status "Wireless configuration is available from the node controls")
                 nil)
               nil))
           :presentation-text-change!
           (fn [_ value] (swap! state assoc :console-input (str value)))
           :presentation-text-submit!
           (fn [_ value] (submit-console! container player state value)))))

(defn create-screen [container menu player]
  (let [container (prepare-container container player)]
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