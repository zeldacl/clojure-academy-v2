(ns cn.li.ac.ability.preset-available-skills-test
  "Regression: preset selector must list other learned skills when only one
   is assigned (assigned filter hides that one only)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ac.ability.client.managed-screens :as managed]
            [cn.li.ac.ability.client.screens.preset-editor :as pe]
            [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.model.preset :as pdata]
            [cn.li.ac.ability.registry.skill :as sk]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.combat-catalog :as cc]
            [cn.li.ac.ability.service.runtime-store :as rs]
            [cn.li.ac.test.support.player-state :as ps]
            [cn.li.mcmod.hooks.core :as hooks]))

(def ^:private uuid "preset-avail-1")
(def ^:private session ps/test-session-id)
(def ^:private server-session [:server "preset-avail-peer"])

(defn- owner []
  {:logical-side :client
   :client-session-id session
   :player-uuid uuid})

(use-fixtures :each
  (fn [f]
    (cc/initialize!)
    (ps/clean-player-states-fixture
     (fn []
       (sk/reset-skill-registry-for-test!)
       (doseq [s (cc/skill-specs)]
         (sk/register-skill! s))
       (rs/create-session! session)
       (rs/create-session! server-session)
       (managed/reset-managed-screen-state-for-test!)
       (hooks/with-client-ctx-fn {:session-id session}
         (fn []
           (hooks/with-player-state-owner-fn
             {:logical-side :client
              :client-session-id session
              :player-uuid uuid}
             (fn [] (f)))))))))

(defn- seed!
  [learned slot-entries]
  (let [ad (-> (adata/new-ability-data)
               (adata/set-category :electromaster)
               (as-> d (reduce adata/learn-skill d learned)))
        pd (reduce (fn [d [[pi ki] pair]]
                     (pdata/set-slot d pi ki pair))
                   (pdata/new-preset-data)
                   slot-entries)]
    (rs/set-player-state! session uuid
                          {:ability-data ad :preset-data pd})))

(deftest available-skills-hides-only-assigned-test
  (testing "other learned skills remain after assigning arc-gen to LMB"
    (seed! [:arc-gen :railgun :thunder-bolt]
           [[[0 0] [:electromaster :arc-gen]]])
    (pe/open-screen! (owner))
    (let [data (pe/build-preset-editor-render-data (owner))
          ids (set (map :skill-id (:available-skills data)))]
      (is (= :arc-gen (get-in data [:slots 0 :skill-id])))
      (is (contains? ids :railgun))
      (is (contains? ids :thunder-bolt))
      (is (not (contains? ids :arc-gen)))))

  (testing "only arc-gen learned+assigned → picker empty (expected)"
    (seed! [:arc-gen]
           [[[0 0] [:electromaster :arc-gen]]])
    (pe/open-screen! (owner))
    (let [data (pe/build-preset-editor-render-data (owner))]
      (is (= :arc-gen (get-in data [:slots 0 :skill-id])))
      (is (empty? (:available-skills data)))))

  (testing "underscore learned id still matches kebab registry id"
    (seed! [:arc_gen :railgun]
           [[[0 0] [:electromaster :arc-gen]]])
    (pe/open-screen! (owner))
    (let [ids (set (map :skill-id
                        (:available-skills
                         (pe/build-preset-editor-render-data (owner)))))]
      (is (contains? ids :railgun))
      (is (not (contains? ids :arc-gen)))))

  (testing "learn_all-sized learned set fills the picker"
    (seed! [:arc-gen :railgun :thunder-bolt :body-intensify]
           [])
    (pe/open-screen! (owner))
    (let [ids (set (map :skill-id
                        (:available-skills
                         (pe/build-preset-editor-render-data (owner)))))]
      (is (contains? ids :railgun))
      (is (contains? ids :body-intensify))
      (is (>= (count ids) 4))))

  (testing "empty client projection heals from peer server session (SP learn_all)"
    (let [server-ad (-> (adata/new-ability-data)
                        (adata/set-category :electromaster)
                        (adata/learn-skill :railgun)
                        (adata/learn-skill :thunder-bolt)
                        (adata/learn-skill :body-intensify))]
      (rs/set-player-state! server-session uuid
                            {:ability-data server-ad
                             :preset-data (pdata/new-preset-data)})
      (rs/set-player-state! session uuid
                            {:ability-data (adata/new-ability-data)
                             :preset-data (pdata/new-preset-data)})
      (pe/open-screen! (owner))
      (let [ids (set (map :skill-id
                          (:available-skills
                           (pe/build-preset-editor-render-data (owner)))))]
        (is (contains? ids :railgun))
        (is (contains? ids :thunder-bolt))
        (is (contains? ids :body-intensify))
        (is (>= (count ids) 3)))))

  (testing "Forge config controllable/enabled=false must not empty the picker"
    ;; In-game tip empty L=29 A=0: learn_all wrote skills, but get-skill's
    ;; apply-skill-overrides can blanket-disable canControl from TOML.
    (seed! [:arc-gen :railgun :thunder-bolt :body-intensify] [])
    (pe/open-screen! (owner))
    (with-redefs [skill-config/apply-skill-overrides
                  (fn [spec]
                    (assoc spec :controllable? false :enabled false))]
      (let [data (pe/build-preset-editor-render-data (owner))
            ids (set (map :skill-id (:available-skills data)))
            dbg (:debug data)]
        (is (contains? ids :railgun))
        (is (contains? ids :body-intensify))
        (is (pos? (:available-count dbg)))
        (is (pos? (:bindable-count dbg))))))

  (testing "assigned slot still paints when config disables controllable"
    (seed! [:arc-gen :railgun]
           [[[0 0] [:electromaster :arc-gen]]])
    (pe/open-screen! (owner))
    (with-redefs [skill-config/apply-skill-overrides
                  (fn [spec]
                    (assoc spec :controllable? false :enabled false))]
      (let [data (pe/build-preset-editor-render-data (owner))]
        (is (= :arc-gen (get-in data [:slots 0 :skill-id])))
        (is (seq (get-in data [:slots 0 :skill-name])))
        (is (= :arc-gen (get-in data [:all-preset-slots 0 0 :skill-id]))))))

  (testing "list pair (not vector) still paints the bound slot"
    (seed! [:railgun] [])
    (let [ps (rs/get-player-state session uuid)
          pd (assoc-in (:preset-data ps) [:slots [0 1]] (list :electromaster :railgun))]
      (rs/set-player-state! session uuid (assoc ps :preset-data pd)))
    (pe/open-screen! (owner))
    (let [data (pe/build-preset-editor-render-data (owner))]
      (is (= :railgun (get-in data [:slots 1 :skill-id])))
      (is (seq (get-in data [:slots 1 :skill-name]))))))
