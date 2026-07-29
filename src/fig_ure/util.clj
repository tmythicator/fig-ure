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
    (let [^double d (double n)]
      (Double/parseDouble (format "%.2f" d)) )
    n))

(defn parse-hex
  "Safely parses a hex string (with or without '0x' prefix) into an integer."
  ^Long [^String s]
  (try
    (Integer/parseInt (strip-0x s) 16)
    (catch Exception _ nil)))
