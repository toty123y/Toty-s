package com.findeye.app;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ClipTokenizer {
    public static final int CONTEXT_LENGTH = 16;

    private final Map<String, Integer> encoder = new HashMap<>();
    private final Map<String, Integer> bpeRanks = new HashMap<>();
    private final Map<Integer, String> byteEncoder = new HashMap<>();
    private final Map<String, String> cache = new HashMap<>();

    private final Pattern tokenPattern = Pattern.compile(
            "<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
    );

    private final int startId;
    private final int endId;
    private final int padId;

    public ClipTokenizer(Context context) throws Exception {
        loadVocab(context);
        loadMerges(context);
        buildByteEncoder();

        startId = requireId("<|startoftext|>");
        endId = requireId("<|endoftext|>");
        Integer pad = encoder.get("!");
        padId = pad == null ? 0 : pad;
    }

    public TokenResult tokenize(String input) {
        String text = clean(input).toLowerCase(Locale.ROOT);
        List<Integer> ids = new ArrayList<>();

        Matcher matcher = tokenPattern.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
            StringBuilder encodedBytes = new StringBuilder();
            for (byte b : bytes) {
                encodedBytes.append(byteEncoder.get(b & 0xff));
            }

            String bpeText = bpe(encodedBytes.toString());
            if (!bpeText.isEmpty()) {
                String[] pieces = bpeText.split(" ");
                for (String piece : pieces) {
                    Integer id = encoder.get(piece);
                    if (id != null) ids.add(id);
                }
            }
        }

        int[] tokenIds = new int[CONTEXT_LENGTH];
        int[] attention = new int[CONTEXT_LENGTH];

        for (int i = 0; i < CONTEXT_LENGTH; i++) tokenIds[i] = padId;

        tokenIds[0] = startId;
        attention[0] = 1;

        int maxPayload = CONTEXT_LENGTH - 2;
        int payload = Math.min(ids.size(), maxPayload);
        for (int i = 0; i < payload; i++) {
            tokenIds[i + 1] = ids.get(i);
            attention[i + 1] = 1;
        }

        int endPos = payload + 1;
        tokenIds[endPos] = endId;
        attention[endPos] = 1;

        return new TokenResult(tokenIds, attention);
    }

    private void loadVocab(Context context) throws Exception {
        String json = readAll(context.getAssets().open("vocab.json"));
        JSONObject obj = new JSONObject(json);
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            encoder.put(key, obj.getInt(key));
        }
    }

    private void loadMerges(Context context) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("merges.txt"), StandardCharsets.UTF_8
        ));
        String line;
        int rank = 0;
        boolean first = true;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (first && line.startsWith("#version")) {
                first = false;
                continue;
            }
            first = false;

            String[] parts = line.split("\\s+");
            if (parts.length >= 2) {
                bpeRanks.put(pairKey(parts[0], parts[1]), rank++);
            }
        }
        reader.close();
    }

    private void buildByteEncoder() {
        List<Integer> bs = new ArrayList<>();
        for (int i = 33; i <= 126; i++) bs.add(i);
        for (int i = 161; i <= 172; i++) bs.add(i);
        for (int i = 174; i <= 255; i++) bs.add(i);

        List<Integer> cs = new ArrayList<>(bs);
        int n = 0;
        for (int b = 0; b < 256; b++) {
            if (!bs.contains(b)) {
                bs.add(b);
                cs.add(256 + n);
                n++;
            }
        }

        for (int i = 0; i < bs.size(); i++) {
            byteEncoder.put(bs.get(i), String.valueOf((char) cs.get(i).intValue()));
        }
    }

    private String bpe(String token) {
        String cached = cache.get(token);
        if (cached != null) return cached;
        if (token.isEmpty()) return "";

        List<String> word = new ArrayList<>();
        for (int i = 0; i < token.length(); i++) {
            String c = token.substring(i, i + 1);
            if (i == token.length() - 1) c += "</w>";
            word.add(c);
        }

        Set<String> pairs = getPairs(word);
        if (pairs.isEmpty()) {
            String result = token + "</w>";
            cache.put(token, result);
            return result;
        }

        while (true) {
            String bestPair = null;
            int bestRank = Integer.MAX_VALUE;
            for (String pair : pairs) {
                Integer rank = bpeRanks.get(pair);
                if (rank != null && rank < bestRank) {
                    bestRank = rank;
                    bestPair = pair;
                }
            }

            if (bestPair == null) break;

            String[] pairParts = bestPair.split("\\t", 2);
            String first = pairParts[0];
            String second = pairParts[1];

            List<String> merged = new ArrayList<>();
            int i = 0;
            while (i < word.size()) {
                int j = indexOf(word, first, i);
                if (j < 0) {
                    merged.addAll(word.subList(i, word.size()));
                    break;
                }

                merged.addAll(word.subList(i, j));
                if (j < word.size() - 1
                        && word.get(j).equals(first)
                        && word.get(j + 1).equals(second)) {
                    merged.add(first + second);
                    i = j + 2;
                } else {
                    merged.add(word.get(j));
                    i = j + 1;
                }
            }

            word = merged;
            if (word.size() == 1) break;
            pairs = getPairs(word);
        }

        String result = join(word, " ");
        cache.put(token, result);
        return result;
    }

    private static Set<String> getPairs(List<String> word) {
        if (word.size() < 2) return Collections.emptySet();
        Set<String> pairs = new HashSet<>();
        for (int i = 0; i < word.size() - 1; i++) {
            pairs.add(pairKey(word.get(i), word.get(i + 1)));
        }
        return pairs;
    }

    private static String pairKey(String a, String b) {
        return a + "\t" + b;
    }

    private static int indexOf(List<String> list, String value, int start) {
        for (int i = start; i < list.size(); i++) {
            if (list.get(i).equals(value)) return i;
        }
        return -1;
    }

    private static String join(List<String> values, String delimiter) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append(delimiter);
            out.append(values.get(i));
        }
        return out.toString();
    }

    private static String clean(String text) {
        if (text == null) return "";
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        return normalized.replaceAll("\\s+", " ").trim();
    }

    private int requireId(String token) {
        Integer id = encoder.get(token);
        if (id == null) throw new IllegalStateException("Missing tokenizer token: " + token);
        return id;
    }

    private static String readAll(InputStream input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = input.read(buffer)) >= 0) out.write(buffer, 0, n);
        input.close();
        return out.toString(StandardCharsets.UTF_8.name());
    }

    public static final class TokenResult {
        public final int[] ids;
        public final int[] attentionMask;

        TokenResult(int[] ids, int[] attentionMask) {
            this.ids = ids;
            this.attentionMask = attentionMask;
        }
    }
}
