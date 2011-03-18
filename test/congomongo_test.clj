(ns congomongo-test
  (:use clojure.test
        somnium.congomongo
        somnium.congomongo.config
        somnium.congomongo.util
        somnium.congomongo.coerce
        clojure.contrib.pprint)
  (:use [clojure.contrib.json :only (read-json json-str)])
  (:use [clojure.contrib.duck-streams :only (slurp*)])
  (:import [com.mongodb BasicDBObject]))

(deftest coercions
  (let [forms   [:clojure :mongo :json]
        input   {:a {:b "c" :d "e" :f ["a" "b" "c"] :g {:h ["i" "j"]}}}
        results (for [from forms
                      to   forms
                      :let [start (condp = from
                                        :clojure input
                                        :json    (json-str input)
                                        :mongo   (coerce input [:clojure :mongo]))
                            x (coerce start [from to])
                            y (coerce x [to from])]
                      :when (not= from to)]
                  (if (= from :json)
                    [(read-json start) (read-json y) from to]
                    [start y from to]))]
    (doseq [t results]
      (is (= (t 0) (t 1)) (str (t 2) " " (t 3))))))

(def test-db-host "127.0.0.1")
(def test-db "congomongotestdb")
(defn setup! [] (mongo! :db test-db :host test-db-host))
(defn teardown! []
  (drop-database! test-db))

(defmacro with-test-mongo [& body]
  `(do
     (setup!)
     ~@body
     (teardown!)))

(deftest with-mongo-interactions
  (with-test-mongo
    (let [a (make-connection "congomongotest-db-a" :host test-db-host)
          b (make-connection "congomongotest-db-b" :host test-db-host)]
      (with-mongo a
        (testing "with-mongo sets the mongo-config"
          (is (= "congomongotest-db-a" (.getName (*mongo-config* :db)))))
        (testing "mongo! inside with-mongo stomps on current config"
          (mongo! :db "congomongotest-db-b" :host test-db-host)
          (is (= "congomongotest-db-b" (.getName (*mongo-config* :db))))))
      (testing "and previous mongo! inside with-mongo is visible afterwards"
        (is (= "congomongotest-db-b" (.getName (*mongo-config* :db))))))))

(deftest closing-with-mongo
  (with-test-mongo
    (let [a (make-connection "congomongotest-db-a" :host test-db-host)]
      (with-mongo a
        (testing "close-connection inside with-mongo sets mongo-config to nil"
          (close-connection a)
          (is (= nil *mongo-config*)))))))

(deftest fetch-sort
  (with-test-mongo
    (let [unsorted [3 10 7 0 2]]
      (mass-insert! :points
                  (for [i unsorted]
                    {:x i}))
      (is (= (map :x (fetch :points :sort {:x 1})) (sort unsorted)))
      (is (= (map :x (fetch :points :sort {:x -1})) (reverse (sort unsorted)))))))

(deftest fetch-by-id-of-any-type
  (with-test-mongo
    (insert! :by-id {:_id "Blarney" :val "Stone"})
    (insert! :by-id {:_id 300 :val "warriors"})
    (is (= "Stone" (:val (fetch-by-id :by-id "Blarney"))))
    (is (= "warriors" (:val (fetch-by-id :by-id 300))))))

`(deftest databases-test
  (with-test-mongo
    (let [test-db2 "congomongotestdb-part-deux"]
    
      (is (= test-db (.getName (*mongo-config* :db)))
          "default DB exists")
      (set-database! test-db2)

      (is (= test-db2 (.getName (*mongo-config* :db)))
          "changed DB exists")
      (drop-database! test-db2))))

(defn make-points! []
  (println "slow insert of 10000 points:")
  (time
   (doseq [x (range 100)
           y (range 100)]
     (insert! :points {:x x :y y}))))

(deftest slow-insert-and-fetch
  (with-test-mongo
    (make-points!)
    (is (= (* 100 100)) (fetch-count :points))
    (is (= (fetch-count :points
                        :where {:x 42}) 100))))

(deftest destroy
  (with-test-mongo
    (make-points!)
    (let [point-id (:_id (fetch-one :points))]
      (destroy! :points
                {:_id point-id})
      (is (= (fetch-count :points) (dec (* 100 100))))
      (is (= nil (fetch-one :points
                            :where {:_id point-id}))))))

(deftest update
  (with-test-mongo
    (make-points!)
    (let [point-id (:_id (fetch-one :points))]
      (update! :points
               {:_id point-id}
               {:x "suffusion of yellow"})
      (is (= (:x (fetch-one :points
                            :where {:_id point-id}))
             "suffusion of yellow")))))


(deftest test-distinct-values
  (with-test-mongo
    (insert! :distinct {:genus "Pan" :species "troglodytes" :common-name "chimpanzee"})
    (insert! :distinct {:genus "Pan" :species "pansicus" :common-name "bonobo"})
    (insert! :distinct {:genus "Homo" :species "sapiens" :common-name "human"})
    (insert! :distinct {:genus "Homo" :species "floresiensis" :common-name "hobbit"})

    (is (= (set (distinct-values :distinct "genus"))
           #{"Pan" "Homo"}))
    (is (= (set (distinct-values :distinct "common-name"))
           #{"chimpanzee" "bonobo" "human" "hobbit"}))
    (is (= (set (distinct-values :distinct "species" :where {:genus "Pan"}))
           #{"troglodytes" "pansicus"}))
    (is (= (set (distinct-values :distinct "species" :where "{\"genus\": \"Pan\"}" :from :json))
           #{"troglodytes" "pansicus"}))
    (let [json (distinct-values :distinct "genus" :as :json)]
      ;; I don't think you can influence the order in which distinct results are returned,
      ;; so just check both possibilities
      (is (or (= (read-json json) ["Pan", "Homo"])
              (= (read-json json) ["Homo", "Pan"]))))))


;; ;; mass insert chokes on excessively large inserts
;; ;; will need to implement some sort of chunking algorithm

 (deftest mass-insert
   (with-test-mongo
     (println "mass insert of 10000 points")
     (time
      (mass-insert! :points
                    (for [x (range 100) y (range 100)]
                      {:x x
                       :y y
                       :z (* x y)})))
     (is (= (* 100 100)
            (fetch-count :points))
         "mass-insert okay")))

 (deftest basic-indexing
   (with-test-mongo
     (make-points!)
     (add-index! :points [:x])
     (is (some #(= (into {} (% "key")) {"x" 1})
               (get-indexes :points)))))

(defn- index-keys-in-same-order
  [a b]
  (every? identity (map #(= %1 %2) (.keySet a) (.keySet b))))


(deftest test-coerce-index-fields
  (let [objects-have-same-data (fn [a b] (= a b))]
    (let [bdo (doto (BasicDBObject.)
                   (.put "a" 1)
                   (.put "b" 1)
                   (.put "c" 1))
          index (coerce-index-fields [:a :b :c])]
      (is (objects-have-same-data bdo index))
      (is (index-keys-in-same-order bdo index)))
    (let [bdo (doto (BasicDBObject.)
                (.put "a" 1)
                (.put "b" -1)
                (.put "c" 1))
          index (coerce-index-fields [:a [:b -1] :c])]
      (is (objects-have-same-data bdo index))
      (is (index-keys-in-same-order bdo index)))))

(defn- get-named-index [coll name]
  (first (filter #(= (get % "name") name)
                 (get-indexes coll))))

(deftest complex-indexing
  (with-test-mongo
    (add-index! :testing-indexes [:a :b :c])
    (let [auto-generated-index-name "a_1_b_1_c_1"
          actual-index (get (get-named-index :testing-indexes auto-generated-index-name)
                            "key")
          expected-index (doto (BasicDBObject.)
                           (.put "a" 1)
                           (.put "b" 1)
                           (.put "c" 1))]
      (is (= actual-index expected-index))
      (is (index-keys-in-same-order actual-index expected-index)))

    (add-index! :testing-indexes [:a [:b -1] :c])
    (let [auto-generated-index-name "a_1_b_-1_c_1"
          actual-index (get (get-named-index :testing-indexes auto-generated-index-name)
                            "key")
          expected-index (doto (BasicDBObject.)
                           (.put "a" 1)
                           (.put "b" -1)
                           (.put "c" 1))]
      (is (= actual-index expected-index))
      (is (index-keys-in-same-order actual-index expected-index)))))

(deftest index-name
  (with-test-mongo
    (let [coll :test-index-name
          index "customIndexName"]
      (add-index! coll [:foo :bar :baz] :name index)
      (is (= (get (get-named-index coll index)
                  "key"))))))

(defrecord Foo [a b])

(deftest can-insert-records-as-maps
  (with-test-mongo
    (insert! :foos (Foo. 1 2))
    (let [found (fetch-one :foos)]
      (are (= 1 (:a found))
           (= 2 (:b found))))))

(deftest insert-returns-id
  (with-test-mongo
    (let [ret (insert! :foos {:a 1 :b 2})]
      (is (map? ret))
      (is (= (:a ret) 1))
      (is (= (:b ret) 2))
      (is (:_id ret)))))

(deftest gridfs-insert-and-fetch
  (with-test-mongo
    (is (empty? (fetch-files :testfs)))
    (let [f (insert-file! :testfs (.getBytes "toasted")
                          :filename "muffin" :contentType "food/breakfast")]
      (is (= "muffin" (:filename f)))
      (is (= "food/breakfast" (:contentType f)))
      (is (= 7 (:length f)))
      (is (= nil (fetch-one-file :testfs :where {:filename "monkey"})))
      (is (= f (fetch-one-file :testfs :where {:filename "muffin"})))
      (is (= f (fetch-one-file :testfs :where {:contentType "food/breakfast"})))
      (is (= (list f) (fetch-files :testfs))))))
 
(deftest gridfs-destroy
  (with-test-mongo
    (insert-file! :testfs (.getBytes "banana") :filename "lunch")
    (destroy-file! :testfs {:filename "lunch"})
    (is (empty? (fetch-files :testfs)))))
 
(deftest gridfs-insert-with-metadata
  (with-test-mongo
    (let [f (insert-file! :testfs (.getBytes "nuts")
                          :metadata {:calories 50, :opinion "tasty"})]
      (is (= "tasty" (get-in f [:metadata :opinion])))
      (is (= f (fetch-one-file :testfs :where { :metadata.opinion "tasty" }))))))

(deftest gridfs-write-file-to
  (with-test-mongo
    (let [f (insert-file! :testfs (.getBytes "banana"))]
      (let [o (java.io.ByteArrayOutputStream.)]
        (write-file-to :testfs f o)
        (is (= "banana" (str o)))))))

(deftest gridfs-stream-from
  (with-test-mongo
    (let [f (insert-file! :testfs (.getBytes "plantain"))
          stream (stream-from :testfs f)
          data (slurp* stream)]
      (is (= "plantain" data)))))

(deftest test-roundtrip-vector
  (with-test-mongo
    (insert! :stuff {:name "name" :vector [ "foo" "bar"]})
    (let [return (fetch-one :stuff :where {:name "name"})]
      (is (vector? (:vector return))))))

(deftest test-map-reduce
  (with-test-mongo
    (insert! :mr {:fruit "bananas" :count 1})
    (insert! :mr {:fruit "bananas" :count 2})
    (insert! :mr {:fruit "plantains" :count 3})
    (insert! :mr {:fruit "plantains" :count 2})
    (insert! :mr {:fruit "pineapples" :count 4})
    (insert! :mr {:fruit "pineapples" :count 2})
    (let [mapfn
          "function(){
              emit(this.fruit, {count: this.count});
          }"
          mapfn-with-scope
          "function(){
              emit((adj + ' ' + this.fruit), {count: this.count});
          }"
          reducefn
          "function(key, values){
              var total = 0;
              for ( var i=0; i<values.length; i++ ){
                  total += values[i].count;
              }
              return { count : total };
          }"
          target-collection :monkey-shopping-list]
      ;; See that the base case works
      (is (= (map-reduce :mr mapfn reducefn target-collection)
             (seq [{:_id "bananas" :value {:count 3}}
                   {:_id "pineapples" :value {:count 6}}
                   {:_id "plantains" :value {:count 5}}])))
      ;; Make sure we get the collection name back, too
      (is (= (map-reduce :mr mapfn reducefn target-collection :output :collection)
             target-collection))

      ;; Test the new (>= MongoDB 1.8) MapReduce output options
      ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
      ;; Replace existing data in target collection
      (drop-coll! target-collection)
      (insert! target-collection {:dummy-data true}) ;; we should not find this!
      (is (= (map-reduce :mr mapfn reducefn {:replace target-collection})
             (seq [{:_id "bananas" :value {:count 3}}
                   {:_id "pineapples" :value {:count 6}}
                   {:_id "plantains" :value {:count 5}}])))

      ;; Merge data in the target collection
      (drop-coll! target-collection)
      (insert! target-collection {:_id "macadamia nuts" :value {:count 1000000}})
      (is (= (map-reduce :mr mapfn reducefn {:merge target-collection})
             (seq [{:_id "macadamia nuts" :value {:count 1000000}}
                   {:_id "bananas" :value {:count 3}}
                   {:_id "pineapples" :value {:count 6}}
                   {:_id "plantains" :value {:count 5}}])))

      ;; Reduce with existing data
      (drop-coll! target-collection)
      (insert! target-collection {:_id "bananas" :value {:count 10}})
      (is (= (map-reduce :mr mapfn reducefn {:reduce target-collection})
             (seq [{:_id "bananas" :value {:count 13}}
                   {:_id "pineapples" :value {:count 6}}
                   {:_id "plantains" :value {:count 5}}])))

      ;; inline data (no output collection)
      (is (= (map-reduce :mr mapfn reducefn {:inline 1})
             (seq [{:_id "bananas" :value {:count 3}}
                   {:_id "pineapples" :value {:count 6}}
                   {:_id "plantains" :value {:count 5}}])))

      ;; inline output ignores if you ask for an output collection name instead
      (is (= (map-reduce :mr mapfn reducefn {:inline 1} :output :collection)
             (seq [{:_id "bananas" :value {:count 3}}
                   {:_id "pineapples" :value {:count 6}}
                   {:_id "plantains" :value {:count 5}}])))

      ;; Check limit
      (is (= (map-reduce :mr mapfn reducefn target-collection :limit 2)
             (seq [{:_id "bananas" :value {:count 3}}])))
      ;; Check sort
      ;; sort requires an index to work?
      (add-index! :mr [:fruit])
      (is (= (map-reduce :mr mapfn reducefn target-collection :sort {:fruit -1} :limit 2)
             (seq [{:_id "plantains" :value {:count 5}}])))
      ;; check query
      (is (= (map-reduce :mr mapfn reducefn target-collection :query {:fruit "pineapples"})
             (seq [{:_id "pineapples" :value {:count 6}}])))
      ;; check finalize
      (is (= (map-reduce :mr mapfn reducefn target-collection
                         :finalize "function(key, value){return 'There are ' + value.count + ' ' + key}")
             (seq [{:_id "bananas" :value "There are 3 bananas"}
                   {:_id "pineapples" :value "There are 6 pineapples"}
                   {:_id "plantains" :value "There are 5 plantains"}])))
      ;; check scope
      (is (= (map-reduce :mr mapfn-with-scope reducefn target-collection
                         :scope {:adj "tasty"})
             (seq [{:_id "tasty bananas" :value {:count 3}}
                   {:_id "tasty pineapples" :value {:count 6}}
                   {:_id "tasty plantains" :value {:count 5}}])))
      )))

(deftest test-server-eval
  (with-test-mongo
    (is (= (server-eval
            "
function ()
{
 function square (n)
 {
  return n*n;                           ;
  }
 return square (25);
 }
") 625))))
