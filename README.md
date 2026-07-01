# kotoba-card

[![CI](https://github.com/kotoba-lang/card/actions/workflows/ci.yml/badge.svg)](https://github.com/kotoba-lang/card/actions/workflows/ci.yml)

**Payment cards, ISO 8583 messages and authorization in pure Clojure.** A
[kotoba-lang](https://github.com/kotoba-lang) capability library that gives
the [`cloud-itonami-6619`](https://github.com/gftdcojp/cloud-itonami-6619)
card-transaction-processing open business the records a card-processing
operator keeps: PAN (ISO/IEC 7812) account numbers with Luhn (ISO/IEC
7812-1) checksum and network classification, ISO 8583 message headers and
data elements, and an authorization/decision contract.

The library models **records, not the wire format**. ISO 8583 on the wire
uses a 4-byte message indicator, a 16-bit primary bitmap and BCD-packed
fields; here messages are EDN so a `PolicyGovernor` or test harness can
reason structurally without a codec. No network, no I/O. Amounts are plain
numbers in the smallest unit of the transaction currency (e.g. cents) — no
BigDecimal assumption, keeping the library portable `.cljc` across JVM /
ClojureScript / SCI / GraalVM.

> PANs are kept whole only for the structural model. A separate
> tokenization layer must replace them in any real system; this library
> never persists raw PANs.

## Contract

```clojure
(require '[kotoba.card :as card])

(card/pan-valid? "4111111111111111")          ; => true (Visa test PAN)
(card/parse-pan "4111111111111111")           ; => {:card/iin "411111" :card/network :visa ...}
(card/message "0100" {2 "4111111111111111" 4 "300620261000"})
(card/authorization "4111111111111111" 1999 :approve)
(card/authorization "4111111111111111" 1999 :partial-approve :approved 1000)
(card/validate-pan "4111111111111112")        ; => {:card/valid? false :card/error :bad-checksum}
```

## Operator console (UI/UX)

A read-only HTML dashboard renders PAN validation (masked), ISO 8583 messages and authorization decisions for an operator. Built on
[`kotoba-lang/html`](https://github.com/kotoba-lang/html) (Hiccup→HTML) +
[`kotoba-lang/css`](https://github.com/kotoba-lang/css) (EDN→CSS). Pure data
→ markup; the console never exposes a write surface (no `<form>`/`<button>`)
— writes stay behind the governor.

```clojure
(require '[kotoba.card.ui :as ui])

(ui/dashboard
  {:pans ["4111111111111111"]
   :messages [(card/message "0100" {2 "4111111111111111"})]
   :authorizations [(card/authorization "4111111111111111" 1999 :approve)]})
;; => "<html>...read-only · governor-gated...</html>"
```

## Export (CSV / JSON)

Audit-grade CSV (RFC-4180 quoting) and JSON (quote/backslash/newline
escaped) for PAN validation (masked), messages and authorizations.

```clojure
(require '[kotoba.card.export :as ex])

(ex/pans->csv pans)                ; PANs masked to last 4
(ex/authorizations->csv auths)    ; action/approved/reason
(ex/pans->json pans)
```

## Why

A card-processing operator must never settle a transaction whose PAN fails
the Luhn checksum or whose authorization was declined, and must never
over-charge a partially-approved amount. `kotoba-card` is the pure-data layer
a `PolicyGovernor` checks against; the actor (`cloud-itonami-6619`) decides
permission, the audit ledger records proof.

## License

Apache License 2.0.
