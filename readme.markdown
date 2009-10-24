congomongo
===========

What?
------
A toolkit for using mongodb with clojure.

Summary
---------
Provides a convenience wrapper around most of the standard methods on 
collections from the java api and introduces some coercion utilities 
for convenient serialization of Clojure data-structures.

Basics
--------

### Setup

    (ns my-mongo-app  
      (:use somnium.congomongo))  
    (mongo!  
      :db "mydb")  

### Create

    (mass-insert!  
      :my-collection
      (for [x (range 100) y (range 100)] {:x x :y y}))

### Read

    (fetch
      :my-collection
      :where {:x {'> 7  
                  '< 42}
              :y {3}})

    (fetch-one
      :my-collection
      :as :json)
    (fetch-count
      :my-collection)

### Update

    (update!
      :my-collection
      {:x {'> 5 '< 10}}
      {:x "you've been updated!"})

### Destroy

    (destroy! :my-collection
      {:x 2})

    (drop! :my-collection)

Coercions
---------

  The mongodb-java-driver will serialize any collections that
implement java.util.Map or java.util.List. That covers most
of clojure already, for convenience congomongo coerces all keywords
to strings on insert, and coerces map keys back to keywords
on fetch (unless you're fetching json).

  It also coerces query shortcuts (like `'>`) to their Mongo form
("$gt"). A full list is located in congo.coerce.
  Strings mapped to keys that begin with an underscore and end with id
are coerced to com.mongodb.ObjectId instances. This comes in handy for
querying by object-id if you happen to want some relational database action while you're getting your key-value storage on.

  If all this coercion disturbs you, it's easy to turn it off:

    (mongo!
      :db "my-db"
      :coerce-to []
      :coerce-from [])

  You can also write your own coercions using the defcoercion macro in
congomongo.coerce. See the source for details.
   
Dependencies
------------

Congomongo requires the mongodb-java-driver and clojure on your classpath.
A working jar of the mongodb-java-api is included in deps, but you can
also get the latest one [here](http://www.github.com/mongodb/mongo-java-driver).   
Just in case you don't have clojure try looking [here](http://www.github.com/richhickey/clojure).

Building congomongo
-------

You will need a recent version of the mongo-java-driver due to a bug interfering  
with serialization of nested maps in earlier versions. 

A working jar is included in deps.

In the congomongo root directory type:

    ant -Dclojure.jar=<path/to/my/clojure.jar>

and voila, you should find a shiny new congomongo jar that's ready   
and raring to get on the classpath.

TODO
----

* tests
* MapReduce
* concurrency
* validations
* collection specific attributes
* refactor coercion interface
* definline everything

### Feedback

CongoMongo is a work in progress. If you've used, improved, 
or abused it I'd love to hear about it. Contact me at somnium@gmx.us
