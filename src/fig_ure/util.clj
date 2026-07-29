(ns fig-ure.util
  "Common pure utility functions for string formatting, math rounding, and byte manipulation."
  (:require [clojure.string :as string]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(defn strip-0x
  "Strips leading '0x' or '0X' prefix from a hex string."
  ^String [^String s]
  (if (and (string? s) (string/starts-with? (string/lower-case s) "0x"))
    (subs s 2)
    s))

(defn round-2
  "Rounds a number to 2 decimal places."
  [n]
  (if (number? n)
    (Double/parseDouble (format "%.2f" (double n)))
    n))

(defn parse-hex
  "Safely parses a hex string (with or without '0x' prefix) into an integer."
  ^Long [^String s]
  (try
    (Long/parseLong (strip-0x s) 16)
    (catch Exception _ nil)))

(defn format-log-message
  "Formats a standardized aligned log message string for telemetry events."
  [tag message]
  (let [clean-msg (-> (str message)
                      (string/replace #"\r?\n" " ")
                      (string/trim))]
    (format "[%-24s] %s" tag clean-msg)))
