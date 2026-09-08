(ns cn.li.ac.ability.server.handlers.preset-handler
  "Preset request network handlers. Server store is authoritative."
  (:require
   [clojure.string :as str]
   [cn.li.ac.ability.server.handlers.common :as common]
   [cn.li.ac.ability.util.uuid :as uuid]
   [cn.li.ac.ability.model.preset :as preset-data]
   [cn.li.ac.ability.service.command-runtime :as command-rt]
   [cn.li.ac.ability.registry.skill-query :as skill-query]
   [cn.li.mcmod.util.log :as log]))

(defn- as-kw
  [x]
  (cond
    (keyword? x) x
    (string? x) (keyword x)
    (symbol? x) (keyword (name x))
    :else x))

(defn- skill-id-aliases
  [skill-id]
  (when-let [sid (as-kw skill-id)]
    (let [n (name sid)
          ns (namespace sid)
          flipped (str/replace n #"[_-]" (fn [ch] (if (= ch "_") "-" "_")))
          kw (fn [stem] (if ns (keyword ns stem) (keyword stem)))]
      (cond-> #{sid}
        (not= n flipped) (conj (kw flipped))))))

(defn- skill-learned?
  [ability-data skill-id]
  (let [learned (:learned-skills ability-data #{})
        aliases (skill-id-aliases skill-id)]
    (or (some (fn [id] (contains? learned id)) aliases)
        (some (fn [id] (contains? learned (some-> id name))) aliases)
        (boolean (some (fn [learned-id]
                         (some aliases (skill-id-aliases learned-id)))
                       learned)))))

(defn- current-preset-data
  [uuid]
  (preset-data/normalize-preset-data
   (:preset-data (common/get-state uuid))))

(defn- ok-with-preset
  [uuid]
  {:ok true
   :preset-data (current-preset-data uuid)})

(defn- reject
  [uuid reason message-key & {:keys [detail]}]
  (cond-> {:ok false
           :reason reason
           :message-key message-key
           :preset-data (when uuid (current-preset-data uuid))}
    detail (assoc :detail (str detail))))

(defn- resolve-assign-slot
  "Return {:slot [...]} or {:reason :message-key :detail}."
  [player-uuid cat-id ctrl-id]
  (let [cat-id (as-kw cat-id)
        ctrl-id (as-kw ctrl-id)
        skill-id (skill-query/get-skill-by-controllable cat-id ctrl-id)]
    (cond
      (nil? skill-id)
      {:reason :unknown-skill
       :message-key "ac.ability.preset.reject.unknown_skill"
       :detail (str (name cat-id) "/" (name ctrl-id))}

      (not (skill-learned? (:ability-data (common/get-state player-uuid)) skill-id))
      {:reason :not-learned
       :message-key "ac.ability.preset.reject.not_learned"
       :detail (name skill-id)}

      :else
      {:slot [cat-id ctrl-id]})))

(defn handle-set-preset-request
  [{:keys [preset-idx key-idx cat-id ctrl-id]} player]
  (try
    (let [uuid (uuid/player-uuid player)
          session-id (common/current-server-session-id)
          cat-id (as-kw cat-id)
          ctrl-id (as-kw ctrl-id)]
      (cond
        (nil? uuid)
        (reject nil :no-player "ac.ability.preset.reject.no_player")

        (and (nil? cat-id) (nil? ctrl-id))
        (do
          (command-rt/run-command-in-session! session-id uuid {:command :set-preset-slot
                                                               :preset-idx preset-idx
                                                               :key-idx key-idx
                                                               :controllable nil})
          (ok-with-preset uuid))

        (and cat-id ctrl-id)
        (let [resolved (resolve-assign-slot uuid cat-id ctrl-id)]
          (if-let [slot (:slot resolved)]
            (do
              (command-rt/run-command-in-session! session-id uuid {:command :set-preset-slot
                                                                   :preset-idx preset-idx
                                                                   :key-idx key-idx
                                                                   :controllable slot})
              (ok-with-preset uuid))
            (do
              (log/warn "set-preset-slot rejected"
                        {:uuid uuid
                         :preset-idx preset-idx
                         :key-idx key-idx
                         :cat-id cat-id
                         :ctrl-id ctrl-id
                         :reason (:reason resolved)
                         :detail (:detail resolved)
                         :registry-count (count (skill-query/list-skills))})
              (reject uuid (:reason resolved) (:message-key resolved)
                      :detail (:detail resolved)))))

        :else
        (reject uuid :invalid-payload "ac.ability.preset.reject.invalid_payload")))
    (catch Exception e
      (log/stacktrace "set-preset-slot handler failed" e)
      {:ok false
       :reason :handler-error
       :message-key "ac.ability.preset.reject.handler_error"
       :detail (ex-message e)})))

(defn handle-switch-preset-request
  [{:keys [preset-idx]} player]
  (try
    (let [uuid (uuid/player-uuid player)
          session-id (common/current-server-session-id)]
      (if-not uuid
        (reject nil :no-player "ac.ability.preset.reject.no_player")
        (do
          (command-rt/run-command-in-session! session-id uuid {:command :switch-preset
                                                               :preset-idx preset-idx})
          (assoc (ok-with-preset uuid) :preset-idx preset-idx))))
    (catch Exception e
      (log/stacktrace "switch-preset handler failed" e)
      {:ok false
       :reason :handler-error
       :message-key "ac.ability.preset.reject.handler_error"
       :detail (ex-message e)})))
