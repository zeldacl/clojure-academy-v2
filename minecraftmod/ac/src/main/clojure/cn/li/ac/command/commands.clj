(ns cn.li.ac.command.commands
  "Command definitions using the command DSL.

  This namespace defines all commands for the AcademyCraft mod using
  declarative DSL macros. Commands are automatically registered when
  this namespace is loaded."
  (:require [cn.li.ac.command.dsl :refer [defcommand defcommand-tree]]
            [cn.li.ac.command.handlers :as handlers]))

;; ============================================================================
;; /acach - Grant Advancement Command
;; ============================================================================

(defcommand acach
  :permission-level 2  ; OP only
  :arguments [{:name "advancement"
               :type :string
               :description "Advancement ID to grant"}
              {:name "player"
               :type :player
               :optional? true
               :description "Target player (defaults to self)"}]
  :executor-fn handlers/handle-grant-advancement
  :description "Grant an advancement to a player")

;; ============================================================================
;; /aim - Ability Management Command (Player)
;; ============================================================================

(defcommand-tree aim
  :permission-level 0  ; All players
  :description "Manage your abilities"
  :subcommands
  {:cat {:arguments [{:name "category"
                      :type :string
                      :description "Category ID to switch to"}]
         :executor-fn handlers/handle-aim-cat
         :description "Switch ability category"}

   :catlist {:arguments []
             :executor-fn handlers/handle-aim-catlist
             :description "List all ability categories"}

   :reset {:arguments []
           :executor-fn handlers/handle-aim-reset
           :description "Reset all abilities"}

   :learn {:arguments [{:name "skill"
                        :type :string
                        :description "Skill ID to learn"}]
           :executor-fn handlers/handle-aim-learn
           :description "Learn a skill"}

   :unlearn {:arguments [{:name "skill"
                          :type :string
                          :description "Skill ID to unlearn"}]
             :executor-fn handlers/handle-aim-unlearn
             :description "Unlearn a skill"}

   :learn_all {:arguments []
               :executor-fn handlers/handle-aim-learn-all
               :description "Learn all skills in current category"}

   :learned {:arguments []
             :executor-fn handlers/handle-aim-learned
             :description "List learned skills"}

   :skills {:arguments []
            :executor-fn handlers/handle-aim-skills
            :description "List available skills"}

   :level {:arguments [{:name "level"
                        :type :integer
                        :description "Level to set (1-5)"}]
           :executor-fn handlers/handle-aim-level
           :description "Set ability level"}

   :exp {:arguments [{:name "skill"
                      :type :string
                      :description "Skill ID"}
                     {:name "exp"
                      :type :float
                      :description "Experience value (0.0-1.0)"}]
         :executor-fn handlers/handle-aim-exp
         :description "Set skill experience"}

   :fullcp {:arguments []
            :executor-fn handlers/handle-aim-fullcp
            :description "Restore CP to full"}

   :cd_clear {:arguments []
              :executor-fn handlers/handle-aim-cd-clear
              :description "Clear all cooldowns"}

   :maxout {:arguments []
            :executor-fn handlers/handle-aim-maxout
            :description "Max out level progression"}

   :help {:arguments []
          :executor-fn handlers/handle-aim-help
          :description "Show help message"}

   :cheats_on {:arguments []
               :executor-fn handlers/handle-aim-cheats-on
               :description "Enable cheat mode"}

   :cheats_off {:arguments []
                :executor-fn handlers/handle-aim-cheats-off
                :description "Disable cheat mode"}})

;; ============================================================================
;; /aimp - Ability Management Command (Admin)
;; ============================================================================

(defcommand-tree aimp
  :permission-level 2  ; OP only
  :description "Manage abilities for a player"
  :arguments [{:name "player"
               :type :player
               :description "Target player"}]
  :subcommands
  {:cat {:arguments [{:name "category"
                      :type :string
                      :description "Category ID to switch to"}]
         :executor-fn handlers/handle-aim-cat
         :description "Switch ability category"}

   :catlist {:arguments []
             :executor-fn handlers/handle-aim-catlist
             :description "List all ability categories"}

   :reset {:arguments []
           :executor-fn handlers/handle-aim-reset
           :description "Reset all abilities"}

   :learn {:arguments [{:name "skill"
                        :type :string
                        :description "Skill ID to learn"}]
           :executor-fn handlers/handle-aim-learn
           :description "Learn a skill"}

   :unlearn {:arguments [{:name "skill"
                          :type :string
                          :description "Skill ID to unlearn"}]
             :executor-fn handlers/handle-aim-unlearn
             :description "Unlearn a skill"}

   :learn_all {:arguments []
               :executor-fn handlers/handle-aim-learn-all
               :description "Learn all skills in current category"}

   :learned {:arguments []
             :executor-fn handlers/handle-aim-learned
             :description "List learned skills"}

   :skills {:arguments []
            :executor-fn handlers/handle-aim-skills
            :description "List available skills"}

   :level {:arguments [{:name "level"
                        :type :integer
                        :description "Level to set (1-5)"}]
           :executor-fn handlers/handle-aim-level
           :description "Set ability level"}

   :exp {:arguments [{:name "skill"
                      :type :string
                      :description "Skill ID"}
                     {:name "exp"
                      :type :float
                      :description "Experience value (0.0-1.0)"}]
         :executor-fn handlers/handle-aim-exp
         :description "Set skill experience"}

   :fullcp {:arguments []
            :executor-fn handlers/handle-aim-fullcp
            :description "Restore CP to full"}

   :cd_clear {:arguments []
              :executor-fn handlers/handle-aim-cd-clear
              :description "Clear all cooldowns"}

   :maxout {:arguments []
            :executor-fn handlers/handle-aim-maxout
            :description "Max out level progression"}

   :help {:arguments []
          :executor-fn handlers/handle-aim-help
          :description "Show help message"}})
