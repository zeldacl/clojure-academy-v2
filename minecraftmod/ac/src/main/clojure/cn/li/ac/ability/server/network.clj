(ns cn.li.ac.ability.server.network
  "Server-side message handler registrations for the ability system.

  All handlers registered here correspond to MSG-* constants in catalog.clj.
  Incoming messages carry a payload map and a player-uuid string.

  All mutating calls go through player-state ns; no atom touched directly.
  No net.minecraft.* imports allowed."
  (:require [clojure.string :as str]
[cn.li.mcmod.network.server         :as net-srv]
            [cn.li.mcmod.runtime.fixed-channel :as fixed-channel]
            [cn.li.ac.ability.messages          :as catalog]
            [cn.li.mcmod.platform.entity        :as entity]            [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.rules.learning-rules :as learning-rules]
            [cn.li.ac.ability.service.command-runtime :as command-rt]
            [cn.li.ac.ability.service.combat-runtime :as combat-runtime]
            [cn.li.ac.ability.service.combat-catalog :as combat-catalog]
            [cn.li.ac.ability.registry.skill             :as skill]
            [cn.li.ac.ability.rules.progression          :as progression]
            [cn.li.ac.ability.server.handlers.level-handler :as level-handler]
            [cn.li.ac.ability.server.handlers.portable-dev-handler :as portable-dev-handler]
            [cn.li.ac.ability.server.handlers.common :as handler-common]
            [cn.li.ac.ability.server.handlers.preset-handler :as preset-handler]
            [cn.li.ac.ability.server.handlers.activation-handler :as activation-handler]
            [cn.li.ac.ability.service.platform-hooks :as platform-hooks]
            [cn.li.ac.ability.server.util.developer-validation :as dev-validate]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.mcmod.platform.world         :as world]
            [cn.li.mcmod.platform.be            :as platform-be]
            [cn.li.mcmod.server.platform-bridge :as server-bridge]
            [cn.li.mcmod.util.log               :as log]))

(defn- wire-payload? [value]
  (bytes? value))

(defn- decode-player-spell-payload [payload]
  (when (wire-payload? (:wire payload))
    (try
      (fixed-channel/decode-player-spell-submit (:wire payload))
      (catch Throwable _ nil))))

(defn- decode-combat-payload [payload]
  (when (wire-payload? (:wire payload))
    (try
      (let [{:keys [seq control-id edge choice client-tick] :as decoded}
            (fixed-channel/decode-intent (:wire payload))]
        (cond
          (and (string? choice) (str/starts-with? choice "wheel:"))
          (let [delta (Double/parseDouble (subs choice 6))]
            (when (and (Double/isFinite delta) (<= -8.0 delta 8.0) (not (zero? delta)))
              {:schema-version 1 :intent-id seq :op :event :event :slot-wheel
               :slot control-id :context {:delta delta} :client-tick client-tick}))

          (string? choice)
          (let [[movement-key movement-transition] (str/split choice #":" 2)]
            {:schema-version 1 :intent-id seq :op :movement
             :slot control-id :movement-key (keyword movement-key)
             :movement-transition (keyword movement-transition)
             :client-tick client-tick})

          :else
          {:schema-version 1 :intent-id seq
           :op (case edge :press :start :release :release :abort :abort)
           :slot control-id :client-tick client-tick}))
      (catch Throwable _ nil))))

(def ^:private fn-try-pull-developer-energy :ability/try-pull-developer-energy!)

(defonce ^:private catalog-handshakes* (atom {}))
(defonce ^:private input-admission* (atom {}))

(defn clear-input-admission! [player-uuid]
  (swap! input-admission* dissoc (str player-uuid))
  nil)

(defn clear-all-input-admission! []
  (reset! input-admission* {})
  nil)

(defn- admit-input! [owner intent-id]
  (let [decision (atom nil)
        now (long (combat-runtime/current-tick))]
    (swap! input-admission*
           (fn [state]
             (let [{:keys [last-seq window-start window-count]} (get state owner)
                   reset-window? (or (nil? window-start)
                                     (>= (- now (long window-start)) 20))
                   count* (if reset-window? 0 (long (or window-count 0)))
                   accepted? (and (number? intent-id)
                                  (or (nil? last-seq) (> (long intent-id) (long last-seq)))
                                  (< count* 40))
                   result (if accepted?
                            {:accepted? true}
                            {:accepted? false
                             :reason (cond
                                       (not (number? intent-id)) :invalid-sequence
                                       (and last-seq (<= (long intent-id) (long last-seq))) :duplicate-or-out-of-order
                                       :else :rate-limit)})]
               (reset! decision result)
               (if accepted?
                 (assoc state owner {:last-seq (long intent-id)
                                     :window-start (if reset-window? now window-start)
                                     :window-count (inc count*)})
                 state))))
    @decision))

(defn- catalog-identity []
  (select-keys (combat-catalog/catalog) [:schema-version :content-hash]))

(defn send-catalog-hello!
  "Send the compact final-catalog identity after a player joins."
  [player-uuid]
  (server-bridge/send-to-client!
   (str player-uuid)
   catalog/MSG-CATALOG-HELLO
   {:wire (fixed-channel/encode-catalog-hello (catalog-identity))}))

(defn clear-catalog-handshake! [player-uuid]
  (swap! catalog-handshakes* dissoc (str player-uuid))
  nil)

(defn clear-all-catalog-handshakes! []
  (reset! catalog-handshakes* {})
  nil)

(defn- catalog-handshake-accepted? [player-uuid]
  (= :accepted (get @catalog-handshakes* (str player-uuid))))

(defn- handle-catalog-ack-request [payload player]
  (let [player-uuid (uuid/player-uuid player)
        wire (:wire payload)
        ack (when (wire-payload? wire)
              (fixed-channel/decode-catalog-ack wire))
        identity (catalog-identity)
        accepted? (boolean (and ack
                                (:accepted? ack)
                                (= (:schema-version identity) (:schema-version ack))
                                (= (:content-hash identity) (:content-hash ack))))]
    (if accepted?
      (swap! catalog-handshakes* assoc (str player-uuid) :accepted)
      (clear-catalog-handshake! player-uuid))
    {:status (if accepted? :accepted :rejected)
     :accepted? accepted?
     :schema-version (:schema-version identity)
     :content-hash (:content-hash identity)}))

  ;; ============================================================================
  ;; Helpers
  ;; ============================================================================

  (defn- get-state [uuid]
    (handler-common/get-state uuid))

  (defn- try-pull-developer-energy!
    [tile ^double amount]
    (if (platform-hooks/platform-fn-registered? fn-try-pull-developer-energy)
      (boolean ((platform-hooks/get-platform-fn fn-try-pull-developer-energy) tile amount))
      false))

  ;; ============================================================================
  ;; Skill learning
  ;; ============================================================================

  (defn- handle-learn-skill-request
    [payload player]
    (let [{:keys [skill-id pos-x pos-y pos-z]} payload
          uuid (uuid/player-uuid player)
          session-id (handler-common/current-server-session-id)
          state (get-state uuid)
          ad (:ability-data state)
          player-level (:level ad)
          world (entity/player-get-level player)
          all-coords? (and (number? pos-x) (number? pos-y) (number? pos-z))
          tile (when (and all-coords? world)
                 (net-helpers/get-tile-at world
                   {:pos-x (long pos-x) :pos-y (long pos-y) :pos-z (long pos-z)}))
          st (when tile (or (platform-be/get-custom-state tile) {}))
          session-ok? (= (str (:user-uuid st "")) uuid)
          server-world? (and world (not (world/client-side? world)))
          station
          (when all-coords?
            (cond (not server-world?) {:ok? false :reason :not-server}
                  (not tile) {:ok? false :reason :no-tile}
                  (not (dev-validate/developer-controller-tile? tile)) {:ok? false :reason :wrong-block}
                  (not (dev-validate/dist-sq-ok-for-station? player tile)) {:ok? false :reason :distance}
                  (not session-ok?) {:ok? false :reason :session}
                  (not (:structure-valid st)) {:ok? false :reason :structure}
                  :else {:ok? true :tile tile :developer-type (dev-validate/developer-type-for-tile tile)}))
                  skill-spec (skill/get-skill skill-id)
          do-learn! #(command-rt/run-command-in-session! session-id uuid {:command :learn-skill
                                                                          :skill-id skill-id
                                                                          :check-conditions? false})]
      (when-not (adata/is-learned? ad skill-id)
        (cond
          (and all-coords? (not (:ok? station)))
          (log/debug "learn-skill rejected (station)" uuid skill-id (:reason station))

          all-coords?
          (let [dev-t (:developer-type station)
                {:keys [pass? failures]} (if skill-spec
                                           (learning-rules/check-all-conditions skill-spec ad player-level dev-t)
                                           {:pass? false
                                            :failures [{:type :unknown-skill :skill-id skill-id}]})]
            (if pass?
              (let [cost (double (progression/learning-cost (long (:level skill-spec))))]
                (if (try-pull-developer-energy! (:tile station) cost)
                  (do-learn!)
                  (log/debug "learn-skill rejected (IF)" uuid skill-id cost)))
              (log/debug "learn-skill rejected" uuid skill-id failures)))

          :else
          (let [{:keys [pass? failures]} (if skill-spec
                                           (learning-rules/check-all-conditions skill-spec ad player-level :normal)
                                           {:pass? false
                                            :failures [{:type :unknown-skill :skill-id skill-id}]})]
            (if pass?
              (do-learn!)
              (log/debug "learn-skill rejected" uuid skill-id failures)))))))

  ;; ============================================================================
  ;; Registration
  ;; ============================================================================

  ;; Ability handlers do not operate on open GUI containers; they carry
  ;; self-contained payloads (activated, skill-id, category-id, etc.) and do
  ;; not need sync-routing validation.
(def ^:private ability-handler-contract
    {:owner-spec :server :payload-routing :none})

(defn- handle-combat-intent-request
  [raw-payload player]
  (let [payload (decode-combat-payload raw-payload)
        owner (uuid/player-uuid player)
        movement-keys #{:forward :back :left :right}
        movement-transitions #{:press :tick :release}
        raw-key (:movement-key payload)
        raw-transition (:movement-transition payload)
        movement? (= :movement (:op payload))
        valid-movement? (and movement?
                             (contains? movement-keys raw-key)
                             (contains? movement-transitions raw-transition))
        event (when valid-movement?
                (keyword "movement"
                         (str (name raw-key) "-" (name raw-transition))))
        ;; The client submits only a neutral movement fact.  The server owns
        ;; the event vocabulary and creative-mode truth.
        intent (cond-> (select-keys payload [:schema-version :intent-id :slot :client-tick :event :context])
                 (not movement?) (assoc :op (:op payload))
                 valid-movement? (assoc :op :event :event event)
                 true (assoc :creative? (boolean (entity/player-creative? player))))
        admission (when (and payload (catalog-handshake-accepted? owner))
                    (admit-input! owner (:intent-id payload)))
        result (if-not payload
                 {:status :rejected :feedback [{:type :invalid-combat-wire}]}
                 (if-not (catalog-handshake-accepted? owner)
                   {:status :rejected :feedback [{:type :catalog-handshake-required}]}
                   (if-not (:accepted? admission)
                     {:status :rejected
                      :feedback [{:type :combat-input-rejected
                                  :reason (:reason admission)}]}
                     (if (and movement? (not valid-movement?))
                       {:status :rejected :feedback [{:type :invalid-movement}]}
                       ;; S8 cutover: routes through the new engine -- see
                     ;; cn.li.ac.ability.service.combat-runtime/dispatch-
                     ;; trigger!'s own docstring for why dispatch-intent!
                     ;; itself is left untouched (old-engine-specific
                     ;; tests still exercise it directly).
                     (combat-runtime/dispatch-intent-v2! owner intent)))))
        result (if (= :accepted (:status result))
                 (combat-runtime/finalize-result! owner result)
                 result)]
    (when (= :rejected (:status result))
      (log/warn "Combat intent rejected" {:owner owner
                                          :reason (:reason result)
                                          :ability-id (:ability-id result)
                                          :feedback (:feedback result)})
      (when (seq (:feedback result))
        (try
          (server-bridge/send-to-client!
           owner catalog/MSG-COMBAT-RESULT
           {:wire (fixed-channel/encode-combat-feedback
                   {:status :rejected
                    :feedback (vec (:feedback result))})})
          (catch Throwable e
            (log/debug "Failed to push combat reject feedback" {:error (.getMessage e)})))))
    result))

(defn- handle-spell-submit-request
  "S7: a player-composed [form effect augment* ...] glyph vector, never a
  client-compiled program (see combat-runtime/dispatch-player-spell!'s
  own docstring for why) -- the server is the only place that ever
  desugars/compiles/admits it. Same catalog-handshake gate as handle-
  combat-intent-request above; a player casting a composed spell has
  necessarily already handshaken to get this far in the client UI."
  [raw-payload player]
  (let [glyphs (decode-player-spell-payload raw-payload)
        owner (uuid/player-uuid player)
        result (cond
                 (not glyphs)
                 {:status :rejected :feedback [{:type :invalid-spell-wire}]}

                 (not (catalog-handshake-accepted? owner))
                 {:status :rejected :feedback [{:type :catalog-handshake-required}]}

                 :else
                 (combat-runtime/dispatch-player-spell! owner glyphs))
        result (if (= :accepted (:status result))
                 (combat-runtime/finalize-result! owner result)
                 result)]
    (when (= :rejected (:status result))
      (log/debug "Player spell submit rejected" {:owner owner :reason (:reason result)}))
    result))

(defn register-handlers! []
  (net-srv/register-handler catalog/MSG-CATALOG-ACK
                            handle-catalog-ack-request
                            ability-handler-contract)
  (net-srv/register-handler catalog/MSG-REQ-LEARN-NODE     handle-learn-skill-request    ability-handler-contract)
  (net-srv/register-handler catalog/MSG-REQ-LEVEL-UP       level-handler/handle-level-up-request ability-handler-contract)
  (net-srv/register-handler catalog/MSG-REQ-PORTABLE-DEV-START portable-dev-handler/handle-portable-dev-start-request ability-handler-contract)
  (net-srv/register-handler catalog/MSG-REQ-SET-PRESET     preset-handler/handle-set-preset-request ability-handler-contract)
  (net-srv/register-handler catalog/MSG-REQ-SWITCH-PRESET  preset-handler/handle-switch-preset-request ability-handler-contract)
  (net-srv/register-handler catalog/MSG-REQ-SET-ACTIVATED  activation-handler/handle-set-activated-request ability-handler-contract)
  (net-srv/register-handler catalog/MSG-COMBAT-INTENT
                            handle-combat-intent-request
                            ability-handler-contract)
  (net-srv/register-handler catalog/MSG-REQ-SPELL-SUBMIT
                            handle-spell-submit-request
                            ability-handler-contract)
  (log/info "Ability network handlers registered"))
