(ns fig-ure.sensors.bme280-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [fig-ure.sensors.bme280 :as bme280]
            [matcher-combinators.test :refer [match?]]))

(defn- load-fixture []
  (edn/read-string (slurp (io/file "test/fixtures/bme280_fixture.edn"))))

(defn- round-2 [n]
  (Double/parseDouble (format "%.2f" n)))

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

(deftest parse-raw-adc-test
  (testing "parses raw ADC values for pressure, temperature, and humidity matching snapshot"
    (let [{:keys [dump raw-adc]} (load-fixture)]
      (is (= raw-adc (bme280/parse-raw-adc dump))))))

(deftest parse-calibration-test
  (testing "parses T1..T3, P1..P9, H1..H6 calibration coefficients matching snapshot"
    (let [{:keys [dump calibration]} (load-fixture)]
      (is (match? {:status :ok :calibration calibration}
                  (bme280/parse-calibration dump)))))

  (testing "returns error when 80 line is corrupted"
    (is (match? {:status       :error
                 :error/reason :parse-calibration-failed}
                (bme280/parse-calibration "corrupted text without 80 line")))))

(deftest compensate-temperature-test
  (testing "calculates accurate temperature in Celsius matching snapshot"
    (let [{:keys [raw-adc calibration readings]} (load-fixture)
          res (round-2 (bme280/compensate-temperature (:raw-temp raw-adc) (:temp calibration)))]
      (is (= (:temp readings) res)))))

(deftest compensate-pressure-test
  (testing "calculates accurate pressure in hPa matching snapshot"
    (let [{:keys [raw-adc calibration readings]} (load-fixture)
          t-fine (bme280/calculate-t-fine (:raw-temp raw-adc) (:temp calibration))
          res    (round-2 (bme280/compensate-pressure (:raw-press raw-adc) (:press calibration) t-fine))]
      (is (= (:press readings) res)))))

(deftest compensate-humidity-test
  (testing "calculates accurate relative humidity in % matching snapshot"
    (let [{:keys [raw-adc calibration readings]} (load-fixture)
          t-fine (bme280/calculate-t-fine (:raw-temp raw-adc) (:temp calibration))
          res    (round-2 (bme280/compensate-humidity (:raw-hum raw-adc) (:hum calibration) t-fine))]
      (is (= (:hum readings) res)))))

(deftest parse-bme280-readings-test
  (testing "parses raw temperature, pressure, and humidity values atomically matching snapshot"
    (let [{:keys [dump calibration readings]} (load-fixture)
          parsed (bme280/parse-bme280-readings dump calibration)]
      (is (= :ok (:status parsed)))
      (is (= (:temp readings) (round-2 (:temp parsed))))
      (is (= (:press readings) (round-2 (:press parsed))))
      (is (= (:hum readings) (round-2 (:hum parsed))))))

  (testing "returns error status when f0 line is corrupted"
    (is (match? {:status       :error
                 :error/reason :parse-failed}
                (bme280/parse-bme280-readings "corrupted text without f0 line")))))
