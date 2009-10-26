(ns congomongo-test
  (:use clojure.test
        clojure.walk
        somnium.congomongo
        somnium.congomongo.coerce
        somnium.congomongo.config)
  (:import com.mongodb.util.JSON))

(def test-db "congomongotestdb")

(defn setup! [] (mongo! :db test-db))
(defn teardown! [] (drop-database! test-db))

(defmacro with-mongo [& body]
  `(do
     (setup!)
     ~@body
     (teardown!)))

(defmacro with-partial [partial-alias [func coll & args] & body]
  `(let [~partial-alias (partial ~func ~coll ~@args)]
     ~@body))

(deftest databases-test
  (with-mongo
    (let [test-db2 "congomongotestdb-part-deux"]
    
      (is (= test-db (.getName (@*mongo-config* :db)))
          "default DB exists")
      (set-database! test-db2)

      (is (= test-db2 (.getName (@*mongo-config* :db)))
          "changed DB exists")
      (drop-database! test-db2))))

(deftest simple-map-coercion
  (with-mongo
    (let [to   (:coerce-to-function   @*mongo-config*)
          from (:coerce-from-function @*mongo-config*)
          orig {"a" "A" "b" "B" "c" [{"d" "D" "e" "E"} {"f" "F" "g" "G"}]}
          tmap (com.mongodb.BasicDBObject. (to orig))
          fmap (from tmap)]
      (is (= orig fmap))
      (is (instance? com.mongodb.DBObject tmap)))))

(deftest insert-return-values-test
  (with-mongo
    (with-partial cat (insert! :cats {:name :felix})
      (let [test-cat (cat)]

        (is (= nil test-cat)
            "default return value from insert is nil"))

      (let [test-cat (cat :return :clojure)]

        (is (map? test-cat)
            "returns a persistent clojure map")

        (is (= #{"name" "_id" "_ns"}
               (set (keys test-cat)))
            "returned map has all the keys it is supposed to"))

      (let [test-cat (cat :return :json)]

        (is (string? test-cat)
            "returned a string for json")

        (is (instance? com.mongodb.DBObject
                       (JSON/parse test-cat))
            "json was parseable by JSON/parse"))

      (let [test-cat (cat :return :db)]
        (is (instance? com.mongodb.DBObject test-cat))))))

(deftest basic-crud-workout
  (with-mongo
    (let [time (with-out-str
                 (time
                  (doseq [x    (range 1 101)
                          y    (range 1 101)
                          :let [prod (* x y)
                                sum  (+ x y)
                                quot (/ x y)
                                diff (- x y)]]
                    (insert! :points {:x x :y y :ops {:prod prod
                                                      :sum sum
                                                      :quot quot
                                                      :diff diff}}))))]
      (println "slow insert time for 100000 objects was" time))

    (is (= (* 100 100) (fetch-count :points))
        "slow insert was ok")

    (let [test-point (fetch-one :points)
          test-ops   (test-point "ops")]

      (is (= #{"x" "y" "ops" "_id" "_ns"}
             (set (keys test-point)))
          "retrieved document as expected")

      (is (= #{"prod" "sum" "quot" "diff"}
             (set (keys test-ops)))
          "keys of nested maps are identical")

      (destroy! test-point)
      (is (= (fetch-count :points) (- (* 100 100) 1))
          "count is one less after destroy command")

      (is (= nil
             (fetch-one :points :where {"_id" (test-point "_id")}))
          "destroyed object id now returns nil on fetch"))
    
    (let [test-point   (fetch-one :points)
          update-point (update! (merge test-point
                                       {"x" "999 red balloons"}))]

      (is (= (-> "_id" test-point)
             (-> "_id" update-point))
          "object ids unaltered after update")

      (is (= "999 red balloons"
             ((fetch-one :points
                         :where {"_id" (test-point "_id")})
              "x"))
          "fetched object reflects update"))
    
    (let [test-point (fetch-one :points
                                :only [:x :y])]
      (is (= #{"_ns" "_id" "x" "y"}
             (set (keys test-point)))
          ":only correctly fetches specified fields")
      
      (is (every? #(= ((% "ops") "sum") 100)
                  (fetch :points
                         :where {"ops.sum" 100}))
          "nested :where query okay")

      (is (every? #(> ((% "ops") "prod") 500)
                  (fetch :points
                         :where {"ops.prod" {'> 500}}))
          "query operator coercion okay"))))

;; mass insert chokes on excessively large inserts
;; will need to implement some sort of chunking algorithm

(deftest mass-insert
  (with-mongo
    (let [time (with-out-str
                 (time
                  (mass-insert! :points
                                (for [x (range 100) y (range 100)]
                                  {:x x
                                   :y y
                                   :z (* x y)}))))]
      (println "mass insert time for 100000 objects was " time)
      (is (= (* 100 100)
             (fetch-count :points))
          "mass-insert okay"))))

(deftest basic-indexing
  (with-mongo
      (mass-insert! :points
                    (for [x (range 10) y (range 10)]
                      {:x x
                       :y y
                       :z (* x y)}))

      (add-index! :points [:x])
      
      (is (some #(= (% "key") {"x" 1})
                (get-indexes :points))
          "add of non unique index ok")

      (add-index! :points
                  [:x :y]
                  :unique true)
      
      (is (some #(let [i {"key"    {"y" 1
                                    "x" 1}
                          "unique" true}
                       r {"key"    (% "key")
                          "unique" (% "unique")}]
                   (= i r))
                (get-indexes :points))
          "add of unique compound index ok")))
