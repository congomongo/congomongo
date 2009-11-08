/*
 * Subclass of com.mongodb.BasicDBObject for
 * rapid creation of clojure persistent data structures.
 */

package somnium.congomongo;
import com.mongodb.BasicDBObject;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.ArrayList;
import clojure.lang.Keyword;
import clojure.lang.Symbol;
import clojure.lang.MapEntry;
import clojure.lang.IPersistentMap;
import clojure.lang.PersistentHashMap;
import clojure.lang.PersistentVector;

/**
 *
 * @author somnium
 */

public class ClojureDBObject extends BasicDBObject {

  public ClojureDBObject() {
  }

  public ClojureDBObject(IPersistentMap m) {
    this.putClojure(m);
  }

  public void putClojure(IPersistentMap m) {
    Iterator<MapEntry> iter = m.iterator();

    while (iter.hasNext()) {
      MapEntry e = iter.next();
      Object cKey = e.getKey();
      String mKey;

      // check key for keyword type
      // and assign result to var mKey

      if (cKey instanceof Keyword) {
        mKey = ((Keyword)cKey).getName();}
      else {mKey = cKey.toString();}

      // delegate to readClojureObject

      this.put(mKey, readClojureObject(e.getValue()));
      }
    }

    private Object readClojureObject(Object o) {

      if (o instanceof Keyword) {
        return ((Keyword)o).getName();
      }

      else if (o instanceof IPersistentMap) {
        ClojureDBObject clj = new ClojureDBObject();
        clj.putClojure((IPersistentMap) o);
        return clj;
      }

      else if (o instanceof List) {
        ArrayList<Object>alist = new ArrayList<Object>();
        Iterator<Object> i = ((java.util.List)o).iterator();
        while (i.hasNext()) {
          Object obj = i.next();
          alist.add(readClojureObject(obj));
        }
        return alist;
      }

      else return o;

    }

    // options:
    // use struct-map?
    // keywordize keys?
    // call .toString on ObjectIds?

    public IPersistentMap toClojure() {
      return toClojure(true);
    }

    public IPersistentMap toClojure(boolean keywordize) {
      return toClojureMap(this, keywordize);
    }

    private static IPersistentMap toClojureMap(Map m, boolean keywordize){

      Set keys = m.keySet();
      Iterator<String> iter = keys.iterator();
      ArrayList<Object> alist = new ArrayList<Object>();
      while (iter.hasNext()) {
        String s = iter.next();
        Object v = m.get(s);

        if (keywordize) {
          Symbol sym = Symbol.intern(s);
          Keyword k = Keyword.intern(sym);
          alist.add(k);
        } else {
          alist.add(s);
        }

        alist.add(toClojureVal(v, keywordize));
      }

      return PersistentHashMap.create(alist);
    }

    private static Object toClojureVal(Object o, boolean keywordize) {

      if (o instanceof Map) {
        return toClojureMap((Map)o, keywordize);
      }

      else if (o instanceof List) {
        ArrayList<Object>alist = new ArrayList<Object>();
        Iterator<Object> i = ((java.util.List)o).iterator();
        while (i.hasNext()) {
          Object obj = i.next();
          alist.add(toClojureVal(obj, keywordize));
        }
        return PersistentVector.create(alist);
      }
      
      else return o;
    }
  }