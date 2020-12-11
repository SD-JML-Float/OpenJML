//@ pure
public class JMLStringTest {

    //@ public normal_behavior
    //@   ensures \invariant_for(s);
    //@   ensures s.length >= 0;
    //@ model public static void stringInvariant(string s) {}
    
    //@ public normal_behavior
    //@   ensures s[i] == s.get(i);
    //@ model public static void stringBrackets(string s, \bigint i) {}
    
    //@ public normal_behavior
    //@   ensures s.size() == s.length;
    //@ model public static void sizeLength(string s) {}
    
    //@ public normal_behavior
    //@ ensures string.empty().isEmpty();
    //@ ensures string.empty().length == 0;
    //@ ensures string.string("").isEmpty();
    //@ model public static void newStringIsEmpty() {}
    
    //@ public normal_behavior
    //@ ensures string.string("abc").length == 3;
    //@ model public static void newStringFromString() {}
    
    //@ public normal_behavior
    //@ ensures s.add('c').length == 1 + s.length;
    //@ model public static void addBumpsSize(string s) {}
    
    //@ public normal_behavior
    //@ requires 0 <= i <= s.length;
    //@ ensures s.add(i,'c').length == 1 + s.length;
    //@ model public static void addBumpsSize(string s, \bigint i) {}
    
    //@ public normal_behavior
    //@ requires 0 <= k < s.length;
    //@ ensures s.remove(k).length == s.length - 1;
    //@ model public static <T> void removeLowersSize(string s, int k) {}
    
    //@ public normal_behavior
    //@   requires 0 <= i <= s.length;
    //@   ensures string.equals(s.add(i,'c').remove(i), s);
    //@ model public static void addRemove(string s, \bigint i) { show i, s.length; }
    
    //@ public normal_behavior
    //@   requires 0 <= k < i <= s.length;
    //@   ensures s.add(i,'c')[k] == s[k];
    //@   ensures s.add(i,'c').get(k) == s.get(k);
    //@ model public static void addGet1(string s, \bigint i, \bigint k) { }
    
    //@ public normal_behavior
    //@   requires 0 <= i < k <= s.length;
    //@   ensures s.add(i,'c').get(k) == s.get(k-1);
    //@ model public static void addGet2(string s, \bigint i, \bigint k) { show i, k, s.length;  }
    
    //@ public normal_behavior
    //@   requires 0 <= i <= s.length;
    //@   ensures s.add(i,c).get(i) == c;
    //@ model public static void addGet(string s, \bigint i, char c) {}
    
    //@ public normal_behavior
    //@   ensures string.string("abc").get(1) == 'b';
    //@ model public static void character() {}

    //@ public normal_behavior
    //@   ensures string.equals(string.empty(),string.empty());
    //@   ensures string.equals(string.string("abc"),string.string("abc"));
    //@   ensures !string.equals(string.string("abc"),string.string(""));
    //@ model public static void equals() {}

    //@ public normal_behavior
    //@   ensures string.empty() == string.empty();
    //@   ensures string.string("abc") == string.string("abc");
    //@   ensures string.string("abc") != string.string("");
    //@   ensures string.string("abc") != string.string("def");
    //@ model public static void equalsOp() {}

    
}
