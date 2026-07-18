(ns cn.li.ac.command.commands
  (:require [cn.li.ac.ability.registry.category :as cat]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.ac.command.dsl :as cmd]
            [cn.li.ac.command.handlers :as handlers]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.runtime.install :as install]))

(defn- resolve-handler [handler-name]
  (or (ns-resolve 'cn.li.ac.command.handlers (symbol handler-name))
      (throw (ex-info "Command handler not found" {:handler handler-name}))))

(defn- category-id-suggestions
  "Tab-completion values for category arguments. Reads the category registry
  at completion time — categories registered later appear automatically."
  [_ctx]
  (map #(name (:id %)) (cat/get-all-categories)))

(defn- skill-id-suggestions
  "Tab-completion values for skill arguments. Prefers the requesting player's
  current category (that is what learn/unlearn operate on); falls back to
  every registered category when there is no player (console) or no category
  yet. Registry + player state are read at completion time — nothing is
  hardcoded."
  [{:keys [player-uuid]}]
  (let [session-id (runtime-hooks/player-state-session-id)
        current-cat (when (and player-uuid session-id)
                      (get-in (store/get-player-state session-id player-uuid)
                              [:ability-data :category-id]))
        cat-ids (if current-cat
                  [current-cat]
                  (map :id (cat/get-all-categories)))]
    (->> cat-ids
         (mapcat skill-query/get-skills-for-category)
         (keep :id)
         (map name)
         sort)))

(defn- build-common-aim-subcommands []
  {:cat {:arguments [{:name "category"
                      :type :string
                      :description "Category ID to switch to"
                      :suggestions category-id-suggestions}]
         :executor-fn (resolve-handler "handle-aim-cat")
         :description "Switch ability category"}
   :catlist {:arguments []
             :executor-fn (resolve-handler "handle-aim-catlist")
             :description "List all ability categories"}
   :reset {:arguments []
           :executor-fn (resolve-handler "handle-aim-reset")
           :description "Reset all abilities"}
   :learn {:arguments [{:name "skill"
                        :type :string
                        :description "Skill ID to learn"
                        :suggestions skill-id-suggestions}]
           :executor-fn (resolve-handler "handle-aim-learn")
           :description "Learn a skill"}
   :unlearn {:arguments [{:name "skill"
                          :type :string
                          :description "Skill ID to unlearn"
                          :suggestions skill-id-suggestions}]
             :executor-fn (resolve-handler "handle-aim-unlearn")
             :description "Unlearn a skill"}
   :learn_all {:arguments []
               :executor-fn (resolve-handler "handle-aim-learn-all")
               :description "Learn all skills in current category"}
   :learned {:arguments []
             :executor-fn (resolve-handler "handle-aim-learned")
             :description "List learned skills"}
   :skills {:arguments []
            :executor-fn (resolve-handler "handle-aim-skills")
            :description "List available skills"}
   :level {:arguments [{:name "level"
                        :type :integer
                        :description "Level to set (1-5)"}]
           :executor-fn (resolve-handler "handle-aim-level")
           :description "Set ability level"}
   :exp {:arguments [{:name "skill"
                      :type :string
                      :description "Skill ID"
                      :suggestions skill-id-suggestions}
                     {:name "exp"
                      :type :float
                      :description "Experience value (0.0-1.0)"}]
         :executor-fn (resolve-handler "handle-aim-exp")
         :description "Set skill experience"}
   :fullcp {:arguments []
            :executor-fn (resolve-handler "handle-aim-fullcp")
            :description "Restore CP to full"}
   :cd_clear {:arguments []
              :executor-fn (resolve-handler "handle-aim-cd-clear")
              :description "Clear all cooldowns"}
   :maxout {:arguments []
            :executor-fn (resolve-handler "handle-aim-maxout")
            :description "Max out level progression"}
   :help {:arguments []
          :executor-fn (resolve-handler "handle-aim-help")
          :description "Show help message"}})

(defn- build-aim-subcommands []
  (assoc (build-common-aim-subcommands)
         :cheats_on {:arguments []
                     :executor-fn (resolve-handler "handle-aim-cheats-on")
                     :description "Enable cheat mode"}
         :cheats_off {:arguments []
                      :executor-fn (resolve-handler "handle-aim-cheats-off")
                      :description "Disable cheat mode"}))

(defn- build-aimp-subcommands []
  (build-common-aim-subcommands))

(defn init-commands!
  []
  (install/framework-once! ::commands-installed?
  (fn []
    (cmd/register-command!
      (cmd/create-command-spec
        "acach"
        {:permission-level 2
         :arguments [{:name "advancement"
                      :type :string
                      :description "Advancement ID to grant"}
                     {:name "player"
                      :type :player
                      :optional? true
                      :description "Target player (defaults to self)"}]
         :executor-fn (resolve-handler "handle-grant-advancement")
         :description "Grant an advancement to a player"}))

    (cmd/register-command!
      (cmd/create-command-spec
        "aim"
        {:permission-level 0
         :description "Manage your abilities"
         :subcommands (build-aim-subcommands)}))

    (cmd/register-command!
      (cmd/create-command-spec
        "aimp"
        {:permission-level 2
         :description "Manage abilities for a player"
         :arguments [{:name "player"
                      :type :player
                      :description "Target player"}]
         :subcommands (build-aimp-subcommands)})))))
