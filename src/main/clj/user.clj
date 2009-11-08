(ns user
  (:use [clojure.contrib pprint repl-utils]
         clojure.test
         somnium.congomongo
         somnium.congomongo.util
         somnium.congomongo.coerce
         [clojure.contrib.json read write])
  (:require [somnium.congomongo util]))

(defn go! []
  (alter-var-root #'*print-length* (constantly 100))
  (alter-var-root #'*print-level* (constantly 25))
  (alter-var-root #'*print-meta* (constantly true))
  (mongo! :db "test"))

(defn wtf []
  (let [forms [:clojure :mongo :json]
        input {:a {:b "c" :d "e" :f ["a" "b" "c"] :g {:h ["i" "j"]}}}
        res   (doseq [from forms
                      to   forms
                      :let [start (condp = from
                                    :clojure input
                                    :json    (coerce input [:clojure :json])
                                    :mongo   (coerce input [:clojure :mongo]))
                            x    (coerce start [from to])
                            y    (coerce x [to from])]
                      :when (not= from to)]
                (let [a (if (= :json from) (read-json start) start)
                      b (if (= :json from) (read-json y) y)]
                  (println from to)
                  (println a)
                  (println x)
                  (println b)
                  (println "equal?" (= a b))))]
    (pprint res)))