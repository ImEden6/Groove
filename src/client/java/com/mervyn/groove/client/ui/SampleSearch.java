package com.mervyn.groove.client.ui;

import groove.engine.samples.SampleCatalog;
import java.util.List;
import java.util.Locale;

/** Client-thread cache; catalog identity also invalidates results after a hot reload. */
public final class SampleSearch {
    private SampleCatalog catalog;
    private String query;
    private List<SampleCatalog.Entry> rows = List.of();
    public List<SampleCatalog.Entry> filter(SampleCatalog next, String text) {
        if (catalog == next && text.equals(query)) return rows;
        if (text.isBlank()) { rows = next.entries(); catalog = next; query = text; return rows; }
        String[] tokens = text.toLowerCase(Locale.ROOT).trim().split("\\s+");
        rows = next.entries().stream().filter(entry -> {
            String id = entry.ref().assetId();
            for (String token : tokens) {
                if (token.equals("@custom")) { if (!id.startsWith("custom:")) return false; }
                else if (token.equals("@factory")) { if (!id.startsWith("factory:")) return false; }
                else if (!id.contains(token)) return false;
            }
            return true;
        }).toList();
        catalog = next; query = text;
        return rows;
    }
}
