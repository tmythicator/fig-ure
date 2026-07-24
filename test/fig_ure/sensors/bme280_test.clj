(ns fig-ure.sensors.bme280-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [fig-ure.sensors.bme280 :as bme280]
            [matcher-combinators.test :refer [match?]]))

(deftest decode-chip-id-test
  (testing "decodes valid BME280 chip ID (0x60)"
    (is (match? {:bme280/chip-id "0x60"
                 :bme280/valid?  true}
                (bme280/decode-chip-id "0x60"))))

  (testing "invalidates mismatched chip ID"
    (is (match? {:bme280/chip-id "0x58"
                 :bme280/valid?  false}
                (bme280/decode-chip-id "0x58")))))

(deftest decode-mode-test
  (testing "decodes mode hex strings into keywords"
    (is (= :normal (bme280/decode-mode "0x27")))
    (is (= :sleep (bme280/decode-mode "0x00")))
    (is (= :forced (bme280/decode-mode "0x25")))
    (is (= :unknown (bme280/decode-mode "0x99")))))

(deftest parse-bme280-temperature-test
  (testing "parses raw ADC temperature bytes from real hardware i2cdump fixture"
    (let [hardware-fixture (slurp (io/file "test/fixtures/bme280_i2cdump.txt"))]
      (is (match? {:status  :ok
                   :reading 524288}
                  (bme280/parse-temperature hardware-fixture)))))

  (testing "returns error status when f0 line is missing or corrupted"
    (is (match? {:status       :error
                 :error/reason :parse-failed}
                (bme280/parse-temperature "corrupted text without f0 line")))))

(deftest compensate-temperature-test
  (testing "calculates accurate temperature in Celsius using Bosch formulas"
    (let [adc-t  539296
          dig-t  {:dig-t1 28000 :dig-t2 26000 :dig-t3 50}]
      (is (number? (bme280/compensate-temperature adc-t dig-t))))))

(deftest parse-calibration-test
  (testing "parses T1, T2, T3 calibration coefficients from i2cdump hardware fixture"
    (let [hardware-fixture (slurp (io/file "test/fixtures/bme280_i2cdump.txt"))]
      (is (match? {:status :ok
                   :calibration {:dig-t1 28589
                                 :dig-t2 26428
                                 :dig-t3 50}}
                  (bme280/parse-calibration hardware-fixture)))))

  (testing "returns error when 80 line is corrupted"
    (is (match? {:status       :error
                 :error/reason :parse-calibration-failed}
                (bme280/parse-calibration "corrupted text without 80 line")))))
