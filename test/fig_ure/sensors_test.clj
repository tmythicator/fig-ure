(ns fig-ure.sensors-test
  (:require [clojure.test :refer [deftest is testing]]
            [fig-ure.sensors :as sensors]
            [matcher-combinators.test :refer [match?]]))

(deftest format-reading-test
  (testing "formats sensor reading into telemetry map structure"
    (is (match? {:sensor/id    :soil-moisture
                 :sensor/value 42.5
                 :sensor/unit  :percent}
                (sensors/format-reading :soil-moisture 42.5 :percent)))))
