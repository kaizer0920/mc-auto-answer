package com.autoanswer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public class AutoAnswer implements ClientModInitializer {

    private static final String PROMPT =
        "This is a chat game question. Reply with ONLY the shortest correct answer: "
        + "no explanation, no punctuation, no full sentence. For math, reply with just the number. Question: ";

    // "keyword,keyword=answer" - every keyword must appear in the question
    private static final String[] ANSWERS = {
        "plants,absorb,photosynthesis=carbon dioxide",
        "black,fuel,torch=coal",
        "red planet=mars",
        "largest planet=jupiter",
        "closest,sun=mercury",
        "hardest,natural=diamond",
        "capital,france=paris",
        "capital,japan=tokyo",
        "capital,philippines=manila",
        "chemical symbol,water=h2o",
        "chemical symbol,gold=au",
        "chemical symbol,oxygen=o",
        "legs,spider=8",
        "planets,solar system=8",
        "gas,humans,breathe=oxygen",
        "gas,plants,release=oxygen",
        "creeper,drop=gunpowder",
        "ender dragon,drop=dragon egg",
        "blocks,nether portal,minimum=10",
        "ore,netherite=ancient debris",
        "stick,craft,torch=coal"
    };

    private String lastQuestion = "";

    @Override
    public void onInitializeClient() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) onChat(message.getString());
        });
    }

    private void onChat(String text) {
        String raw = ChatFormatting.stripFormatting(text);
        if (raw == null) return;
        int idx = raw.indexOf("Question:");
        if (idx < 0) return;

        String question = raw.substring(idx + 9).trim();
        if (question.isEmpty() || question.equals(lastQuestion)) return;
        lastQuestion = question;
        debug("question: " + question);

        String answer = solve(question);
        if (answer != null) {
            debug("local answer: " + answer);
            send(answer);
            return;
        }

        String key = readKey();
        if (key == null) {
            debug("no key file found");
            return;
        }
        final String q = question;
        new Thread(() -> {
            String a = askAI(key, q);
            if (a != null && !a.isEmpty()) {
                Minecraft.getInstance().execute(() -> send(a));
            }
        }).start();
    }

    private void debug(String text) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.player.sendSystemMessage(Component.literal("[AA] " + text));
        });
    }

    private void send(String answer) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.connection != null) {
            mc.player.connection.sendChat(answer);
        }
    }

    private String readKey() {
        try {
            Path p = FabricLoader.getInstance().getConfigDir().resolve("autoanswer-key.txt");
            if (!Files.exists(p)) return null;
            String k = Files.readString(p).trim();
            return k.isEmpty() ? null : k;
        } catch (Exception e) {
            return null;
        }
    }

    private String askAI(String key, String question) {
        try {
            JsonObject part = new JsonObject();
            part.addProperty("text", PROMPT + question);
            JsonArray parts = new JsonArray();
            parts.add(part);
            JsonObject content = new JsonObject();
            content.add("parts", parts);
            JsonArray contents = new JsonArray();
            contents.add(content);
            JsonObject think = new JsonObject();
            think.addProperty("thinkingBudget", 0);
            JsonObject gen = new JsonObject();
            gen.addProperty("maxOutputTokens", 60);
            gen.add("thinkingConfig", think);
            JsonObject body = new JsonObject();
            body.add("contents", contents);
            body.add("generationConfig", gen);

            HttpRequest req = HttpRequest.newBuilder(URI.create(
                    "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"))
                .timeout(Duration.ofSeconds(15))
                .header("x-goog-api-key", key)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
            HttpResponse<String> res = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                String b = res.body();
                debug("AI error " + res.statusCode() + ": " + b.substring(0, Math.min(150, b.length())));
                return null;
            }

            String text = JsonParser.parseString(res.body()).getAsJsonObject()
                .getAsJsonArray("candidates").get(0).getAsJsonObject()
                .getAsJsonObject("content").getAsJsonArray("parts").get(0).getAsJsonObject()
                .get("text").getAsString();
            text = text.trim().replaceAll("[.!\"]+$", "").replaceAll("^\"+", "");
            debug("AI answer: " + text.toLowerCase());
            return text.toLowerCase();
        } catch (Exception e) {
            debug("AI exception: " + e);
            return null;
        }
    }

    private String solve(String question) {
        String q = question;
        if (q.toUpperCase().startsWith("MATH:")) q = q.substring(5).trim();

        int eq = q.indexOf('=');
        String expr = eq >= 0 ? q.substring(0, eq).trim() : q;
        if (expr.matches("[0-9+\\-*/x\\u00d7\\u00f7().\\s]+")) {
            try {
                P p = new P(expr);
                double v = p.expr();
                if (v == Math.floor(v)) return String.valueOf((long) v);
                return String.valueOf(Math.round(v * 100.0) / 100.0);
            } catch (Exception ignored) {
            }
        }

        String lower = question.toLowerCase();
        for (String entry : ANSWERS) {
            String[] kv = entry.split("=", 2);
            boolean all = true;
            for (String k : kv[0].split(",")) {
                if (!lower.contains(k.trim())) { all = false; break; }
            }
            if (all) return kv[1];
        }
        return null;
    }

    private static class P {
        final String s;
        int i = 0;

        P(String s) { this.s = s.replace(" ", ""); }

        double expr() {
            double v = term();
            while (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                char c = s.charAt(i++);
                double t = term();
                v = c == '+' ? v + t : v - t;
            }
            return v;
        }

        double term() {
            double v = factor();
            while (i < s.length() && "*/x\u00d7\u00f7".indexOf(s.charAt(i)) >= 0) {
                char c = s.charAt(i++);
                double f = factor();
                v = (c == '/' || c == '\u00f7') ? v / f : v * f;
            }
            return v;
        }

        double factor() {
            if (s.charAt(i) == '-') { i++; return -factor(); }
            if (s.charAt(i) == '(') { i++; double v = expr(); i++; return v; }
            int st = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            return Double.parseDouble(s.substring(st, i));
        }
    }
}
