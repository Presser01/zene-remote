package hu.peresztegi.zeneremote;

import org.json.JSONArray;

public class SearchSmokeTest {
    public static void main(String[] args) throws Exception {
        String[] queries = {"azahriah", "Rick Astley never gonna give you up", "Quimby"};
        int failures = 0;
        for (String q : queries) {
            JSONArray r = YtmSearch.search(q);
            System.out.println("== " + q + " -> " + r.length() + " találat");
            for (int i = 0; i < Math.min(5, r.length()); i++) System.out.println("   " + r.getJSONObject(i));
            if (r.length() == 0) failures++;
        }
        if (failures > 0) { System.out.println("HIBA: üres eredmény"); System.exit(1); }
    }
}
