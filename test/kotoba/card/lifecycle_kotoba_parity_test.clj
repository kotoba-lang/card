;; `kotoba/kotoba/card/lifecycle.{kotoba,cljk}` against `kotoba.card.lifecycle`.
;;
;; The oracle is itself a MIRROR of the deployed issuer side
;; (cloud-itonami-card-issuing's `cardissuing.governor/legal-predecessor` and
;; `cardissuing.store/lifecycle!`), and its own docstring says a mirror that
;; disagrees with its subject is worse than no mirror. The guest is now a third
;; copy of the same table, so this test compares EVERY state x event pair -- 6
;; states (the five plus one unknown) times 5 events (the four plus one unknown),
;; every one of them, not a sample.
;;
;; `.cljc` stays the oracle and is not required from the guest (require-graph).
;;
;; The negative control is `activate-must-not-be-legal-from-blocked`: the oracle's
;; own test is named after that guess, because unblocking is NOT an activate -- the
;; issuer side's recovery path from :blocked is :reissue. A guest that allowed it
;; would let a consent surface wave through a transition the governor refuses,
;; which is the wasted approval this namespace exists to prevent.

(ns kotoba.card.lifecycle-kotoba-parity-test
  (:require [clojure.java.io :as io]
            [kotoba.lang.text :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [kotoba.card.lifecycle :as lc]))

(def ^:private kotoba-file
  (io/file (System/getProperty "user.dir") "kotoba" "kotoba" "card" "lifecycle.kotoba"))

(def ^:private cljk-file
  (io/file (System/getProperty "user.dir") "kotoba" "kotoba" "card" "lifecycle.cljk"))

(defn- source-available? []
  (let [k? (.exists kotoba-file) c? (.exists cljk-file)]
    (is k? (str "kotoba object not found at " kotoba-file))
    (is c? (str "cljk object not found at " cljk-file))
    (and k? c?)))

(def ^:private kir
  (delay (:kir (compiler/compile-source (slurp kotoba-file) :wasm32-kotoba-v1 {}))))

(def ^:private cljk-kir
  (delay (:kir (compiler/compile-source (slurp cljk-file) :wasm32-kotoba-v1 {}))))

(defn- call [compiled f args] (ir/execute compiled f args))

(defn- doc-> [d]
  (let [[tag v] d]
    (case tag
      "map" (into {} (map (fn [[k val]] [(second k) (doc-> val)])) v)
      "vector" (mapv doc-> v)
      "string" v "keyword" v "i64" v "bool" v "null" nil
      (throw (ex-info "no document decoding" {:tag tag})))))

;; Every state and event the oracle knows, plus one of each it does not.
(def ^:private all-states (conj (vec (sort lc/states)) :not-a-state))
(def ^:private all-events (conj (vec (sort (keys lc/events))) :not-an-event))

;; How the oracle renders an event's `:from` set inside `describe`.
(defn- from-string [event]
  (str/join ", " (sort (map name (get-in lc/events [event :from] #{})))))

(deftest ^:kotoba-parity kotoba-lifecycle-objects-are-present
  (source-available?))

(deftest ^:kotoba-parity the-tables-agree-with-the-oracle
  (when (source-available?)
    (doseq [s all-states]
      (is (= (contains? lc/states s) (call @kir 'state? [s])) (str "state? " s))
      (is (= (lc/terminal? s) (call @kir 'terminal? [s])) (str "terminal? " s)))
    (doseq [e all-events]
      (is (= (contains? lc/events e) (call @kir 'event? [e])) (str "event? " e))
      (is (= (lc/mints-successor? e) (call @kir 'mints-successor? [e]))
          (str "mints-successor? " e)))))

(deftest ^:kotoba-parity every-state-event-pair-agrees
  (when (source-available?)
    (doseq [s all-states, e all-events]
      (is (= (lc/reachable? s e) (call @kir 'reachable? [s e]))
          (str "reachable? " s " " e))
      ;; nil on one side, :none on the other -- a keyword cannot be nil.
      (is (= (or (lc/next-state s e) :none) (call @kir 'next-state [s e]))
          (str "next-state " s " " e)))))

(deftest ^:kotoba-parity the-issue-reasons-agree-including-their-order
  (when (source-available?)
    (doseq [s all-states, e all-events]
      (let [want (vec (lc/transition-issues s e))
            got (doc-> (call @kir 'transition-issues [s e]))]
        (is (= (count want) (count got)) (str "issue count for " s " " e))
        (when (seq want)
          (let [w (first want) g (first got)]
            ;; The KIND is the ordering claim: a close from :closed is terminal,
            ;; not unreachable, and the oracle's `cond` decides that order.
            (is (= (:card/issue w) (:card/issue g)) (str "issue kind for " s " " e))
            (is (= (:card/state w) (:card/state g)) (str "issue state for " s " " e))
            (is (= (:card/event w) (:card/event g)) (str "issue event for " s " " e))
            ;; `:card/from` is a SET in the oracle and the rendered list in the
            ;; guest, because a set is not a Kotoba return type. Compared as the
            ;; rendering, which is what a governor citing the issue would print.
            (when (contains? w :card/from)
              (is (= (str/join ", " (sort (map name (:card/from w))))
                     (:card/from g))
                  (str "issue from for " s " " e)))))))))

(deftest ^:kotoba-parity apply-event-agrees-on-every-pair
  (when (source-available?)
    (doseq [s all-states, e all-events]
      (let [want (lc/apply-event s e)
            got (doc-> (call @kir 'apply-event [s e ""]))]
        (is (= (:card/ok? want) (:card/ok? got)) (str "ok? for " s " " e))
        (if (:card/ok? want)
          (do (is (= (:card/from want) (:card/from got)) (str "from for " s " " e))
              (is (= (:card/to want) (:card/to got)) (str "to for " s " " e))
              (is (= (boolean (:card/mints-successor? want))
                     (boolean (:card/mints-successor? got)))
                  (str "mints-successor? for " s " " e)))
          (is (= (count (:card/issues want)) (count (:card/issues got)))
              (str "issue count for " s " " e)))))
    ;; The reissue's reference, which the oracle echoes and the guest carries as a
    ;; string. Absent is nil there and "" here -- asserted, not assumed.
    (let [want (lc/apply-event :blocked :reissue :card-reference "card_1")
          got (doc-> (call @kir 'apply-event [:blocked :reissue "card_1"]))]
      (is (= "card_1" (:card/supersedes-reference want)))
      (is (= "card_1" (:card/supersedes-reference got))))
    (let [want (lc/apply-event :blocked :reissue)
          got (doc-> (call @kir 'apply-event [:blocked :reissue ""]))]
      (is (nil? (:card/supersedes-reference want)) "absent is nil in the oracle")
      (is (= "" (:card/supersedes-reference got)) "and the empty string in the guest"))))

(deftest ^:kotoba-parity describe-agrees-on-every-known-pair
  (when (source-available?)
    (doseq [s (sort lc/states), e (sort (keys lc/events))]
      (is (= (lc/describe s e) (call @kir 'describe [s e]))
          (str "describe " s " " e)))
    ;; And the rendering the guest builds the refusal line from.
    (doseq [e (sort (keys lc/events))]
      (is (= (from-string e) (call @kir 'reachable-from [e]))
          (str "reachable-from " e)))))

(deftest ^:kotoba-parity cljk-twin-agrees-with-the-kotoba-guest
  (when (source-available?)
    (doseq [s all-states, e all-events]
      (doseq [f '[reachable? next-state issue-kind describe]]
        (is (= (call @kir f [s e]) (call @cljk-kir f [s e]))
            (str "cljk drifted on " f " " s " " e))))
    (doseq [s all-states]
      (is (= (call @kir 'apply-event [s :close ""])
             (call @cljk-kir 'apply-event [s :close ""]))
          (str "cljk drifted on apply-event " s)))))

(deftest ^:kotoba-parity activate-must-not-be-legal-from-blocked
  (when (source-available?)
    (let [original (slurp kotoba-file)
          ;; Make :activate legal from :blocked, which is the guess the oracle's
          ;; own test is named after.
          mutated (str/replace original
                               "(= e :activate) (= s :issued)"
                               "(= e :activate) (or (= s :issued) (= s :blocked))")
          _ (is (not= mutated original)
                "the activate rule was not found -- the control mutated nothing")
          mutated-kir (:kir (compiler/compile-source mutated :wasm32-kotoba-v1 {}))
          allowed (call mutated-kir 'reachable? [:blocked :activate])]
      (is (true? allowed) "the mutation has to actually allow it")
      (is (false? (lc/reachable? :blocked :activate))
          "the oracle refuses it, because the recovery path from :blocked is :reissue")
      (is (not= (lc/reachable? :blocked :activate) allowed)
          "a blocked card must not simply resume"))))

(deftest ^:kotoba-parity what-the-guest-renders-differently-is-recorded
  "Two stated boundaries, both from keywords not being convertible to strings at
  runtime. `describe` answers \"\" for an unknown state or event where the oracle
  prints the unknown name, and `:card/from` is the rendered list rather than the
  set. Neither is reachable through a real transition -- every caller has a state
  from the record and an event from the table -- and both are asserted here so the
  difference is on the record."
  (when (source-available?)
    (is (= "" (call @kir 'describe [:not-a-state :activate])))
    (is (str/includes? (lc/describe :not-a-state :activate) "not-a-state")
        "the oracle names the unknown state")
    (is (= "" (call @kir 'describe [:active :not-an-event])))
    (is (set? (:card/from (first (lc/transition-issues :active :activate))))
        "the oracle carries a set")
    (is (string? (:card/from (first (doc-> (call @kir 'transition-issues [:active :activate])))))
        "the guest carries its rendering")))
