package com.supportmind.organization;

import java.text.Normalizer;
import java.util.Locale;

/** Slugs de organización: "Acme Store S.A.S." -> "acme-store-s-a-s". */
public final class Slugs {

    private Slugs() {
    }

    public static String from(String name) {
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (slug.length() > 50) {
            slug = slug.substring(0, 50).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? "org" : slug;
    }
}
