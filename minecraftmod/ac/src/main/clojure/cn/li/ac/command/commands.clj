(ns cn.li.ac.command.commands)

(require '[cn.li.ac.command.dsl :as cmd])

(defn- resolve-handler [handler-name]
  (or (requiring-resolve (symbol (str "cn.li.ac.command.handlers/" handler-name)))
      (throw (ex-info "Command handler not found" {:handler handler-name}))))

(defn- build-common-aim-subcommands []
  {:cat {:arguments [{:name "category"
                      :type :string
                      :description "Category ID to switch to"}]
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
                        :description "Skill ID to learn"}]
           :executor-fn (resolve-handler "handle-aim-learn")
           :description "Learn a skill"}
   :unlearn {:arguments [{:name "skill"
                          :type :string
                          :description "Skill ID to unlearn"}]
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
                      :description "Skill ID"}
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

(defonce ^:private commands-installed? (atom false))

(defn init-commands!
  []
  (when (compare-and-set! commands-installed? false true)
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
         :subcommands (build-aimp-subcommands)}))))
