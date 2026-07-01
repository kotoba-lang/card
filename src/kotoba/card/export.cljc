(ns kotoba.card.export
  "Operator-facing export for a card-payment-processing actor.

  Renders PAN validation, ISO 8583 messages and authorization decisions to
  CSV and JSON for settlement audit and downstream reporting. PANs are masked
  to the last 4 digits in exports — raw PANs are never persisted. Pure data
  → text: no network."
  (:require [clojure.string :as str]
            [kotoba.card :as card]))

(defn- csv-cell [v]
  (let [s (str (if (nil? v) "" v))]
    (if (re-find #"[\",\n]" s)
      (str "\"" (str/replace s "\"" "\"\"") "\"")
      s)))

(defn- csv-row [vals] (str/join "," (map csv-cell vals)))

(defn- json-str [v]
  (-> (str (if (nil? v) "" v))
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")))

(defn- mask [pan]
  (let [d (str (or pan "")) n (count d)]
    (if (>= n 4) (str "•••• " (subs d (- n 4))) d)))

(defn pans->csv [pans]
  (str/join "\n"
    (cons (csv-row ["pan_masked" "valid" "network" "error"])
          (for [p pans]
            (let [r (card/validate-pan p) parsed (:card/parsed r)]
              (csv-row [(mask p)
                        (if (:card/valid? r) "yes" "no")
                        (or (some-> (:card/network parsed) name) "")
                        (name (or (:card/error r) :ok))]))))))

(defn messages->csv [messages]
  (str/join "\n"
    (cons (csv-row ["mti" "valid" "class" "data_elements" "bitmap"])
          (for [m messages]
            (let [r (card/validate-message m)]
              (csv-row [(or (:card/mti m) "")
                        (if (:card/valid? r) "yes" "no")
                        (or (:card/class m) "")
                        (count (keys (:card/de m)))
                        (count (:card/bitmap m))]))))))

(defn authorizations->csv [authorizations]
  (str/join "\n"
    (cons (csv-row ["pan_masked" "amount" "action" "approved" "rrn" "reason"])
          (for [a authorizations]
            (csv-row [(mask (:card/pan a))
                      (:card/amount a)
                      (name (:card/action a))
                      (card/authorized-amount a)
                      (or (:card/rrn a) "")
                      (or (:card/reason a) "")])))))

(defn pans->json [pans]
  (str "["
       (str/join ","
                 (for [p pans]
                   (let [r (card/validate-pan p) parsed (:card/parsed r)]
                     (str "{\"pan_masked\":\"" (json-str (mask p)) "\","
                          "\"valid\":" (if (:card/valid? r) "true" "false") ","
                          "\"network\":\"" (json-str (:card/network parsed)) "\"}"))))
       "]"))

(defn authorizations->json [authorizations]
  (str "["
       (str/join ","
                 (for [a authorizations]
                   (str "{\"pan_masked\":\"" (json-str (mask (:card/pan a))) "\","
                        "\"amount\":" (or (:card/amount a) 0) ","
                        "\"action\":\"" (name (:card/action a)) "\","
                        "\"approved\":" (card/authorized-amount a) ","
                        "\"rrn\":\"" (json-str (:card/rrn a)) "\"}")))
       "]"))
