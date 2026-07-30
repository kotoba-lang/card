(ns kotoba.card.lifecycle
  "Card lifecycle as a pure state machine.

  This namespace exists so that -- is this lifecycle transition even reachable
  from the state we have on record? -- is answered by a total, deterministic
  function with no I/O, no model and no policy. It is the intended callee of a
  consent surface's pre-check, which must reject an unreachable transition
  before any human approval is requested. Same posture and shape as
  kotoba.esim.lifecycle.

  The four events are exactly the vocabulary the issuer side already uses
  (cloud-itonami/cloud-itonami-card-issuing's `:card/lifecycle` accepts
  #{:activate :block :reissue :close}); this namespace adds the reachability
  the issuer side had no answer for, and invents no new event names.

  What this namespace decides: structural reachability and terminality. What it
  deliberately does NOT decide: whether an issuer is licensed, whether a
  cardholder consented, whether a block is warranted. Those belong to the
  governor and the consent surface and are not expressible as a state
  transition.

  Portable (.cljc) across JVM / ClojureScript / SCI / GraalVM."
  (:require [clojure.string :as str]))

(def states
  "Card states. :issued is a card whose record exists but which cannot yet be
  used; :reissued is terminal for THIS card reference, superseded by a
  successor that is a separate issuance."
  #{:issued :active :blocked :reissued :closed})

(def terminal-states
  "States no event leads out of. :reissued is terminal because the reference has
  been superseded -- the successor card carries the lifecycle from there."
  #{:reissued :closed})

(def events
  "Lifecycle events, each declaring the states it is reachable from and the
  state it lands in.

  Two readings are recorded here as decisions, not transcriptions:

  :activate is admitted from :blocked as well as :issued -- that is, unblocking
  IS activation. The issuer-side vocabulary has no :unblock event, so modeling
  one here would invent a name the issuer does not use and would then have to be
  translated back at the boundary.

  :close is NOT admitted from :reissued. The reference is already terminal
  there, and closing it again would put two terminal records against one card
  reference; the successor card is what gets closed."
  {:activate {:from #{:issued :blocked}          :to :active}
   :block    {:from #{:active}                   :to :blocked}
   :reissue  {:from #{:active :blocked}          :to :reissued}
   :close    {:from #{:issued :active :blocked}  :to :closed}})

(defn terminal?
  "True when state admits no further event."
  [state]
  (contains? terminal-states state))

(defn reachable?
  "True when event is admissible from state. False for any unknown state or
  event -- an unrecognized input is never treated as permissive."
  [state event]
  (boolean
   (when-let [{:keys [from]} (get events event)]
     (and (contains? states state)
          (contains? from state)))))

(defn next-state
  "The state event lands in when applied to state, or nil when the event is not
  reachable from it."
  [state event]
  (when (reachable? state event)
    (get-in events [event :to])))

(defn transition-issues
  "Return a seq of reasons event cannot be applied to state, empty when it can.
  Reasons are data so a governor can cite one rather than re-derive it."
  [state event]
  (cond
    (not (contains? events event))
    [{:card/issue :event/unknown :card/event event}]

    (not (contains? states state))
    [{:card/issue :state/unknown :card/state state}]

    (terminal? state)
    [{:card/issue :state/terminal :card/state state :card/event event}]

    (not (reachable? state event))
    [{:card/issue :transition/unreachable
      :card/state state
      :card/event event
      :card/from  (get-in events [event :from])}]

    :else []))

(defn apply-event
  "Apply event to state, returning either

    {:card/ok? true  :card/from s :card/to s'}

  or

    {:card/ok? false :card/issues [...]}

  and never throwing.

  A :reissue reports :card/supersedes-reference so the caller can see that the
  successor card is NOT created here: issuing the replacement is a separate
  :card/issue decision. A reissue that silently minted a new card would hide an
  issuance behind a lifecycle event."
  [state event & {:keys [card-reference]}]
  (let [issues (transition-issues state event)]
    (if (seq issues)
      {:card/ok? false :card/issues (vec issues)}
      (cond-> {:card/ok? true
               :card/from state
               :card/to   (next-state state event)}
        (= :reissue event)
        (assoc :card/supersedes-reference card-reference
               :card/successor :not-created-here)))))

(defn describe
  "A one-line human-readable rendering of a transition, for an operator log.
  Pure string building; no formatting library."
  [state event]
  (if-let [to (next-state state event)]
    (str (name event) ": " (name state) " -> " (name to))
    (str (name event) ": refused from " (name state)
         " (reachable from "
         (str/join ", " (sort (map name (get-in events [event :from] #{}))))
         ")")))
