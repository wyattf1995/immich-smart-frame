package com.wyattfleming.frametv;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** URL configuration accepts dashboards, never embedded credentials. */
public final class UrlPolicy {
    private UrlPolicy() {}

    public static String validate(String input) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) return "";
        if (value.length() > 2048) throw new IllegalArgumentException("The address is too long.");
        URI uri = parse(value);
        String scheme = lower(uri.getScheme());
        String host = lower(uri.getHost());
        if ((!scheme.equals("https") && !scheme.equals("http")) || host.isEmpty()) {
            throw new IllegalArgumentException("Use a complete http:// or https:// dashboard address.");
        }
        if (uri.getRawUserInfo() != null || uri.getPort() > 65535 || uri.getPort() == 0) {
            throw new IllegalArgumentException("Use an address without a username or password.");
        }
        if (scheme.equals("http") && !localHost(host)) {
            throw new IllegalArgumentException("Use HTTPS, or a local network address for HTTP.");
        }
        if (containsCredential(uri.getRawQuery()) || containsCredential(uri.getRawFragment())) {
            throw new IllegalArgumentException("Do not put passwords or access tokens in dashboard addresses.");
        }
        return uri.toASCIIString();
    }

    public static boolean navigationAllowed(String configured, String destination) {
        try {
            URI from = parse(validate(configured));
            URI to = parse(destination);
            if (to.getRawUserInfo() != null) return false;
            return !lower(from.getHost()).isEmpty()
                    && lower(from.getScheme()).equals(lower(to.getScheme()))
                    && lower(from.getHost()).equals(lower(to.getHost()))
                    && effectivePort(from) == effectivePort(to);
        } catch (IllegalArgumentException e) { return false; }
    }

    private static URI parse(String value) {
        try { return new URI(value); }
        catch (URISyntaxException | NullPointerException e) {
            throw new IllegalArgumentException("The dashboard address is not valid.");
        }
    }
    private static String lower(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }
    private static int effectivePort(URI uri) {
        return uri.getPort() == -1 ? ("https".equals(lower(uri.getScheme())) ? 443 : 80) : uri.getPort();
    }
    private static boolean containsCredential(String query) {
        if (query == null) return false;
        for (String pair : query.split("[&;]")) {
            String key;
            try { key = URLDecoder.decode(pair.split("=", 2)[0], StandardCharsets.UTF_8.name()); }
            catch (Exception e) { return true; }
            key = lower(key).replace("-", "").replace("_", "");
            if (key.contains("token") || key.contains("password") || key.contains("secret")
                    || key.equals("apikey") || key.equals("authorization") || key.equals("auth")) return true;
        }
        return false;
    }
    private static boolean localHost(String host) {
        if (host.equals("localhost") || host.equals("[::1]") || host.endsWith(".local")) return true;
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        int[] bytes = new int[4];
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("0|[1-9][0-9]{0,2}")) return false;
            bytes[i] = Integer.parseInt(parts[i]);
            if (bytes[i] > 255) return false;
        }
        return bytes[0] == 10 || bytes[0] == 127
                || (bytes[0] == 192 && bytes[1] == 168)
                || (bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31);
    }
}
