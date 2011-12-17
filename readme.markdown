CongoMongo
===========

What?
------
A toolkit for using MongoDB with Clojure.

News
--------------
Version 0.1.8 (SNAPSHOT):
* adds fetch-by-ids (#44)
* improves erorr handling when connection not set up (#42)
* updates clojure.data.json to 0.1.2 (for performance fixes)
* numerous documentation fixes (#38, #39, #40, #41)

Version 0.1.7 adds the ability to create MongoOptions and pass them
into make-connection as the last argument, so that you can control
autoConnectRetry and timeouts and so on. This release also fixes a
number of small bugs around type hints introduced in 0.1.6; corrects
the upsert(?) parameter in fetch-and-modify; upgrades the Java driver
to 2.6.5. The :only parameter can now be a map of field names and
true / false values to allow fields to be included or excluded. The
original vector of field names is still supported to include only
the named fields.

Version 0.1.6 removes (almost) all of the reflection warnings.

Version 0.1.5 adds compatibility with both Clojure 1.3, in addition
to 1.2.

Congomongo 0.1.4 introduces support for the MongoDB 1.8's modified
map-reduce functionality, wherein the 'out' parameter is
required. With this and future Congomongo releases, it will no longer
be possible to access the map-reduce features of older MongoDB
instances.

As of congomongo 0.1.3, Clojure 1.2 and Clojure-contrib 1.2 are required.    
If you need compatibility with Clojure 1.1,     
please stick with congomongo 0.1.2.

There is now a [Google Group](http://groups.google.com/group/congomongo-dev)    
Come help us make ponies for great good.

Clojars group is congomongo.

=======

CongoMongo is essentially a Clojure api for the mongo-java-driver,
transparently handling coercions between Clojure and Java data types.

Basics
--------

### Setup

#### import
```clojure
(ns my-mongo-app
  (:use somnium.congomongo))
```
#### make a connection
```clojure
(def conn mongo/make-connection "mydb"
                                :host "127.0.0.1"
                                :port 27017) => #'user/conn

conn => {:mongo #<Mongo Mongo: 127.0.0.1:20717>, :db #<DBApiLayer mydb>}
```
#### set the connection globally
```clojure
(set-connection! conn)
```
#### or locally
```clojure
(with-mongo conn
    (insert! :robots {:name "robby"}))
```
### Simple Tasks
------------------

#### create
```clojure
(insert! :robots
         {:name "robby"}
```
#### read
```clojure
(def my-robot (fetch-one :robots)) => #'user/my-robot

my-robot => { :name "robby",
              :_id  #<ObjectId> "0c23396f7e53e34a4c8cf400">,
              :_ns  "robots"}
```
#### update
```clojure
(update! :robots my-robot (merge my-robot { :name "asimo" }))

=>  { :name "asimo" ,
      :_id  #<ObjectId> "0c23396f7e53e34a4c8cf400"> ,
      :_ns : "robots" }
```
#### destroy
```clojure
(destroy! :robots my-robot) => nil
(fetch :robots) => ()
```
### More Sophisticated Tasks
----------------------------

#### mass inserts
```clojure
(mass-insert!
  :points
  (for [x (range 100) y (range 100)]
    {:x x
     :y y
     :z (* x y)}))

 =>  nil

(fetch-count :points)
=> 10000
```
#### ad-hoc queries
```clojure
(fetch-one
  :points
  :where {:x {:$gt 10
              :$lt 20}
          :y 42
          :z {:$gt 500}})

=> {:x 12, :y 42, :z 504,  :_ns "points", :_id ... }
```
#### authentication
```clojure
(authenticate conn "myusername" "my password")

=> true
```
#### advanced initialization using mongo-options
```clojure
((make-connection :mydb :host "127.0.0.1" (mongo-options :auto-connect-retry true)"
```
#### easy json
------------------------------------------------------------------------
```clojure
(fetch-one :points
           :as :json)

=> "{ \"_id\" : \"0c23396ffe79e34a508cf400\" ,
      \"x\" : 0 , \"y\" : 0 , \"z\" : 0 , \"_ns\" : \"points\"}"
```

Install
-------

Leiningen is the recommended way to use congomongo.
Just add

    [congomongo "0.1.7"]

to your project.clj and do

    $lein deps

to get congomongo and all of its dependencies.

### Feedback

CongoMongo is a work in progress. If you've used, improved, 
or abused it tell us about it at our [Google Group](http://groups.google.com/group/congomongo-dev).
