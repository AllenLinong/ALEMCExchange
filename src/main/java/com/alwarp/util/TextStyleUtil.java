package com.alwarp.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class TextStyleUtil {

    private static final Pattern AMP_HEX = Pattern.compile("(?i)&#[0-9a-f]{6}");
    private static final Pattern AMP_LEGACY_HEX = Pattern.compile("(?i)&x(&[0-9a-f]){6}");
    private static final Pattern MINI_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern LEGACY_CODE = Pattern.compile("(?i)[&§][0-9a-fk-or]");
    private static final Pattern LEGACY_HEX = Pattern.compile("(?i)§x(§[0-9a-f]){6}");

    private TextStyleUtil() {
    }

    public static String stripUserFormatting(String text) {
        if (text == null || text.isEmpty()) return "";
        String cleaned = LEGACY_HEX.matcher(text).replaceAll("");
        cleaned = AMP_LEGACY_HEX.matcher(cleaned).replaceAll("");
        cleaned = AMP_HEX.matcher(cleaned).replaceAll("");
        cleaned = LEGACY_CODE.matcher(cleaned).replaceAll("");
        cleaned = MINI_TAG.matcher(cleaned).replaceAll("");
        return cleaned.trim();
    }

    public static String render(String text, String style, boolean bold) {
        if (text == null || text.isEmpty()) return "";
        String normalized = normalizeStyle(style);
        if (normalized == null || normalized.isBlank()) {
            return bold ? applyBoldOnly(text) : text;
        }
        if (normalized.toLowerCase(Locale.ROOT).startsWith("gradient:")) {
            return renderGradient(text, normalized.substring("gradient:".length()), bold);
        }
        return renderSolid(text, normalized, bold);
    }

    public static String normalizeStyle(String style) {
        if (style == null) return null;
        String trimmed = style.trim();
        if (trimmed.isEmpty()
                || trimmed.equalsIgnoreCase("default")
                || trimmed.equalsIgnoreCase("none")) {
            return null;
        }
        if (trimmed.startsWith("<#") && trimmed.endsWith(">") && trimmed.length() == 9) {
            return trimmed.substring(1, 8);
        }
        if (trimmed.matches("(?i)^#[0-9a-f]{6}$")) {
            return trimmed;
        }
        if (trimmed.matches("(?i)^&#[0-9a-f]{6}$")) {
            return trimmed.substring(1);
        }
        if (trimmed.matches("(?i)^&[0-9a-f]$") || trimmed.matches("(?i)^§[0-9a-f]$")) {
            return trimmed;
        }
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("gradient:")) {
            return "gradient:" + normalizeGradientStops(trimmed.substring("gradient:".length()));
        }
        return trimmed;
    }

    public static String gradientStyle(List<String> stops) {
        if (stops == null || stops.size() < 2) return null;
        String joined = normalizeGradientStops(String.join(":", stops));
        return joined != null ? "gradient:" + joined : null;
    }

    private static String normalizeGradientStops(String raw) {
        if (raw == null) return null;
        String[] parts = raw.trim().split("[:;,\\s]+");
        List<String> stops = new ArrayList<>();
        for (String part : parts) {
            String hex = normalizeHex(part);
            if (hex != null) stops.add(hex);
        }
        return stops.size() >= 2 ? String.join(":", stops) : null;
    }

    private static String renderSolid(String text, String style, boolean bold) {
        String color = toLegacyColor(style);
        if (color == null) color = MessageUtil.colorize(style);
        String prefix = color + (bold ? "§l" : "");
        return prefix + text.replace("\n", "\n" + prefix);
    }

    private static String renderGradient(String text, String stopsText, boolean bold) {
        String normalizedStops = normalizeGradientStops(stopsText);
        if (normalizedStops == null) return applyBoldOnly(text);
        String[] stopParts = normalizedStops.split(":");
        int[][] stops = new int[stopParts.length][3];
        for (int i = 0; i < stopParts.length; i++) {
            stops[i] = parseHex(stopParts[i]);
        }

        int visible = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '\n' && text.charAt(i) != '\r') visible++;
        }
        if (visible <= 0) return "";

        StringBuilder result = new StringBuilder();
        int position = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                result.append(c);
                continue;
            }
            double progress = visible == 1 ? 0.0 : (double) position / (visible - 1);
            int[] rgb = interpolate(stops, progress);
            result.append(toLegacyHex(rgb[0], rgb[1], rgb[2]));
            if (bold) result.append("§l");
            result.append(c);
            position++;
        }
        return result.toString();
    }

    private static String applyBoldOnly(String text) {
        return "§l" + text.replace("\n", "\n§l");
    }

    private static int[] interpolate(int[][] stops, double progress) {
        if (stops.length == 1) return stops[0];
        double scaled = progress * (stops.length - 1);
        int index = Math.min((int) Math.floor(scaled), stops.length - 2);
        double local = scaled - index;
        int[] a = stops[index];
        int[] b = stops[index + 1];
        return new int[] {
                (int) Math.round(a[0] + (b[0] - a[0]) * local),
                (int) Math.round(a[1] + (b[1] - a[1]) * local),
                (int) Math.round(a[2] + (b[2] - a[2]) * local)
        };
    }

    private static String toLegacyColor(String style) {
        String hex = normalizeHex(style);
        if (hex != null) {
            int[] rgb = parseHex(hex);
            return toLegacyHex(rgb[0], rgb[1], rgb[2]);
        }
        if (style != null && style.matches("(?i)^&[0-9a-f]$")) {
            return "§" + style.charAt(1);
        }
        if (style != null && style.matches("(?i)^§[0-9a-f]$")) {
            return style;
        }
        return null;
    }

    private static String normalizeHex(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.matches("(?i)^&#[0-9a-f]{6}$")) trimmed = trimmed.substring(1);
        if (trimmed.matches("(?i)^#[0-9a-f]{6}$")) return trimmed.toLowerCase(Locale.ROOT);
        if (trimmed.matches("(?i)^<#[0-9a-f]{6}>$")) return trimmed.substring(1, 8).toLowerCase(Locale.ROOT);
        return null;
    }

    private static int[] parseHex(String hex) {
        String value = hex.startsWith("#") ? hex.substring(1) : hex;
        return new int[] {
                Integer.parseInt(value.substring(0, 2), 16),
                Integer.parseInt(value.substring(2, 4), 16),
                Integer.parseInt(value.substring(4, 6), 16)
        };
    }

    private static String toLegacyHex(int red, int green, int blue) {
        String hex = String.format(Locale.ROOT, "%02x%02x%02x", red, green, blue);
        StringBuilder result = new StringBuilder("§x");
        for (int i = 0; i < hex.length(); i++) {
            result.append('§').append(hex.charAt(i));
        }
        return result.toString();
    }
}
