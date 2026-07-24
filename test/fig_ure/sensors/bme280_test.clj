(ns fig-ure.sensors.bme280-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [fig-ure.sensors :as sensors]
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
      (is (match? {:status :ok
                   :reading {:sensor/id    :bme280-temperature
                             :sensor/value 524288
                             :sensor/unit  :raw-adc}}
                  (bme280/parse-temperature hardware-fixture sensors/format-reading)))))

  (testing "returns error status when f0 line is missing or corrupted"
    (is (match? {:status       :error
                 :error/reason :parse-failed}
                (bme280/parse-temperature "corrupted text without f0 line" sensors/format-reading)))))
