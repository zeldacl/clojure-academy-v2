(ns cn.li.ac.block.metal-former.logic-test
  "Metal Former tick-logic tests: recipe cadence (4 idle + 60 work ticks,
  13.3 IF/tick), battery-slot drain, and the working-sound cooldown cycle
  (machine.machine_work every 20 work ticks, silence when idle — upstream
  TileMetalFormer loops the sound at volume 0.6 while isWorkInProgress)."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.block.machine.runtime :as machine-runtime]
            [cn.li.ac.block.metal-former.config :as cfg]
            [cn.li.ac.block.metal-former.logic :as logic]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.mcmod.platform.item :as pitem]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.platform.world-effects :as world-effects]))

(defn- fake-stack [item-id count]
  {:item-id item-id :count (int count)})

(defn- install-item-mocks!
  "Platform item ops over plain map stacks."
  []
  (with-redefs [pitem/empty? (fn [s] (or (nil? s) (<= (get s :count 0) 0)))
                pitem/stack-count (fn [s] (long (get s :count 0)))
                pitem/object (fn [s] (get s :item-id))
                pitem/registry-name (fn [o] o)
                pitem/description-id (fn [_] nil)
                pitem/in-item-tag? (fn [_ _] false)
                pitem/stack-by-id (fn [id count] (fake-stack id (int count)))
                pitem/stack-from-item-tag (fn [id count] (fake-stack id (int count)))
                pitem/same? (fn [a b] (= (get a :item-id) (get b :item-id)))
                pitem/max-stack-size (fn [_] 64)
                pitem/set-damage! (fn [s _d] s)
                pitem/damage (fn [s] (get s :damage 0))]))

(defn- run-ticks! [state n]
  (nth (iterate (fn [s] (logic/former-tick-state s nil nil nil nil)) state) n))

(defn- former-state [inventory energy]
  (machine-runtime/ensure-machine-state
    {:inventory inventory
     :mode "plate"
     :energy (double energy)}
    logic/former-default-state))

;; ============================================================================
;; Recipe cycle: 4 idle ticks (recipe scan every 5) + 60 work ticks
;; ============================================================================

(deftest former-completes-plate-recipe-in-64-ticks-test
  (install-item-mocks!)
  (let [result (run-ticks! (former-state [(fake-stack "minecraft:iron_ingot" 1) nil nil] 3000.0)
                           65)]
    (is (nil? (get-in result [:inventory 0]))
        "input stack consumed")
    (is (= "my_mod:reinforced_iron_plate" (get-in result [:inventory 1 :item-id]))
        "iron_ingot -> reinforced_iron_plate")
    (is (= 1 (get-in result [:inventory 1 :count])))
    (is (false? (:working result)))
    (is (< (Math/abs (- (:energy result) (- 3000.0 (* 60 cfg/energy-per-tick)))) 1e-9)
        "60 work ticks consume 13.3 IF each")))

(deftest former-without-energy-never-forms-test
  (install-item-mocks!)
  (let [result (run-ticks! (former-state [(fake-stack "minecraft:iron_ingot" 1) nil nil] 0.0)
                           100)]
    (is (false? (:working result)))
    (is (= "minecraft:iron_ingot" (get-in result [:inventory 0 :item-id]))
        "input untouched without energy")))

;; ============================================================================
;; Battery slot: energy pulled into machine buffer before working
;; ============================================================================

(deftest former-drains-battery-item-into-buffer-test
  (install-item-mocks!)
  (with-redefs [energy/is-energy-item-supported? (fn [s] (= :battery (get s :kind)))
                energy/pull-energy-from-item (fn [s amt _]
                                               (min (double amt) (double (get s :stored 0.0))))]
    (let [battery (assoc (fake-stack "my_mod:energy_unit" 1) :kind :battery :stored 500.0)
          result (run-ticks! (former-state [nil nil battery] 0.0) 1)]
      (is (= 500.0 (double (:energy result)))
          "battery drain refills machine buffer up to stored amount"))))

;; ============================================================================
;; Working sound: fires every 20 work ticks (work ticks 1/21/41), silent when
;; idle — mirrors upstream TileMetalFormer.updateSounds
;; ============================================================================

(deftest former-plays-work-sound-only-while-working-test
  (install-item-mocks!)
  (let [plays (atom 0)]
    (with-redefs [world-effects/available? (constantly true)
                  world/dimension-id (constantly :test)
                  world-effects/play-sound! (fn [& _] (swap! plays inc))]
      (let [idle (run-ticks! (former-state [nil nil nil] 3000.0) 10)]
        (is (zero? @plays) "no sound while idle")
        (is (false? (:working idle)))
        (is (= 0 (int (:sound-cooldown idle)))))
      (let [done (run-ticks! (former-state [(fake-stack "minecraft:iron_ingot" 1) nil nil] 3000.0)
                             65)]
        (is (= 3 @plays) "one sound burst per 20 work ticks (ticks 1/21/41)")
        (is (false? (:working done)))))))
