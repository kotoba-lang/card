(ns kotoba.card.lifecycle-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.card.lifecycle :as lc]))

(deftest event-vocabulary-matches-the-issuer-side
  (testing "exactly the four events cloud-itonami-card-issuing's :card/lifecycle
            accepts, and no invented fifth"
    (is (= #{:activate :block :reissue :close} (set (keys lc/events))))))

(deftest reachability
  (testing "the documented transitions are reachable"
    (is (lc/reachable? :issued :activate))
    (is (lc/reachable? :active :block))
    (is (lc/reachable? :active :reissue))
    (is (lc/reachable? :active :close))
    (is (lc/reachable? :issued :close))
    (is (lc/reachable? :blocked :close)))
  (testing "unblocking is activation -- no :unblock event is invented"
    (is (lc/reachable? :blocked :activate))
    (is (nil? (get lc/events :unblock))))
  (testing "a card cannot be activated or blocked twice"
    (is (not (lc/reachable? :active :activate)))
    (is (not (lc/reachable? :blocked :block))))
  (testing "an unissued card cannot be blocked or reissued"
    (is (not (lc/reachable? :issued :block)))
    (is (not (lc/reachable? :issued :reissue))))
  (testing "terminal states admit nothing"
    (doseq [state [:reissued :closed]
            event (keys lc/events)]
      (is (not (lc/reachable? state event))
          (str state " should not admit " event))))
  (testing "closing a superseded reference is refused -- one terminal record per
            reference, the successor is what gets closed"
    (is (not (lc/reachable? :reissued :close))))
  (testing "an unknown state or event is never permissive"
    (is (not (lc/reachable? :frozen :activate)))
    (is (not (lc/reachable? :active :unblock)))
    (is (not (lc/reachable? nil nil)))))

(deftest next-state-is-total
  (is (= :active (lc/next-state :issued :activate)))
  (is (= :active (lc/next-state :blocked :activate)))
  (is (= :blocked (lc/next-state :active :block)))
  (is (= :reissued (lc/next-state :active :reissue)))
  (is (= :closed (lc/next-state :blocked :close)))
  (testing "an unreachable event yields nil rather than throwing"
    (is (nil? (lc/next-state :reissued :close)))
    (is (nil? (lc/next-state :issued :reissue)))
    (is (nil? (lc/next-state :nonsense :activate)))))

(deftest transition-issues-cite-a-reason
  (testing "a reachable transition has no issues"
    (is (empty? (lc/transition-issues :active :block))))
  (testing "each refusal names its own kind"
    (is (= [:event/unknown] (mapv :card/issue (lc/transition-issues :active :unblock))))
    (is (= [:state/unknown] (mapv :card/issue (lc/transition-issues :frozen :block))))
    (is (= [:state/terminal] (mapv :card/issue (lc/transition-issues :closed :activate))))
    (is (= [:state/terminal] (mapv :card/issue (lc/transition-issues :reissued :close))))
    (is (= [:transition/unreachable] (mapv :card/issue (lc/transition-issues :issued :block)))))
  (testing "an unreachable transition reports the states it would be reachable from"
    (is (= #{:active} (:card/from (first (lc/transition-issues :issued :block)))))))

(deftest apply-event-returns-data
  (testing "a reachable event reports from/to"
    (let [r (lc/apply-event :issued :activate)]
      (is (:card/ok? r))
      (is (= :issued (:card/from r)))
      (is (= :active (:card/to r)))))
  (testing "an unreachable event is refused with its issue, not thrown"
    (let [r (lc/apply-event :issued :reissue)]
      (is (not (:card/ok? r)))
      (is (= [:transition/unreachable] (mapv :card/issue (:card/issues r))))))
  (testing "a reissue says outright that it did not create the successor"
    (let [r (lc/apply-event :active :reissue :card-reference "411111...1111")]
      (is (:card/ok? r))
      (is (= :reissued (:card/to r)))
      (is (= "411111...1111" (:card/supersedes-reference r)))
      (is (= :not-created-here (:card/successor r)))))
  (testing "a non-reissue carries no successor keys at all"
    (let [r (lc/apply-event :issued :activate)]
      (is (not (contains? r :card/successor)))
      (is (not (contains? r :card/supersedes-reference)))))
  (testing "apply-event never throws on nonsense input"
    (is (false? (:card/ok? (lc/apply-event nil nil))))
    (is (false? (:card/ok? (lc/apply-event :active :suspend))))))

(deftest lifecycles-run-through
  (testing "issue -> activate -> block -> activate -> close"
    (let [path [[:issued :activate :active]
                [:active :block :blocked]
                [:blocked :activate :active]
                [:active :close :closed]]]
      (doseq [[from event to] path]
        (let [r (lc/apply-event from event)]
          (is (:card/ok? r) (str event " from " from))
          (is (= to (:card/to r)))))))
  (testing "a reissued card is terminal, so the walk stops there"
    (let [r (lc/apply-event :active :reissue)]
      (is (lc/terminal? (:card/to r)))
      (is (not (:card/ok? (lc/apply-event (:card/to r) :activate)))))))

(deftest describe-renders-both-outcomes
  (is (= "activate: blocked -> active" (lc/describe :blocked :activate)))
  (is (= "block: refused from issued (reachable from active)"
         (lc/describe :issued :block)))
  (is (= "close: refused from reissued (reachable from active, blocked, issued)"
         (lc/describe :reissued :close))))
