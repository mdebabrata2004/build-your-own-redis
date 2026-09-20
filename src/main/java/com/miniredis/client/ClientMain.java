package com.miniredis.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simple interactive CLI for Mini Redis.
 * Supports both plain commands and quoted values.
 */
public final class ClientMain {

    private ClientMain() {
        // Utility class
    }

    public static void main(String[] args) {
        String host = "localhost";
        int port = 6379;

        // Optional: allow host/port from command line
        if (args.length >= 1) host = args[0];
        if (args.length >= 2) {
            try {
                port = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port: " + args[1]);
                return;
            }
        }

        RedisClient client = new RedisClient(host, port);

        try (BufferedReader console = new BufferedReader(new InputStreamReader(System.in))) {

            client.connect();

            System.out.println("╔══════════════════════════════════════╗");
            System.out.println("║          Mini Redis CLI              ║");
            System.out.println("╚══════════════════════════════════════╝");
            System.out.println("Connected to " + host + ":" + port);
            System.out.println();
            System.out.println("Examples:");
            System.out.println("  PING");
            System.out.println("  SET name \"Grok AI\"");
            System.out.println("  SET session token EX 60");
            System.out.println("  GET name");
            System.out.println("  INCR counter");
            System.out.println("  KEYS *");
            System.out.println("  TTL session");
            System.out.println("  DEL name");
            System.out.println("  EXIT / QUIT");
            System.out.println();

            while (true) {
                System.out.print(host + ":" + port + "> ");

                String line = console.readLine();
                if (line == null) break;

                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.equalsIgnoreCase("EXIT") || line.equalsIgnoreCase("QUIT")) {
                    try {
                        client.execute("QUIT");
                    } catch (Exception ignored) {}
                    break;
                }

                try {
                    String[] parts = tokenize(line);
                    if (parts.length == 0) continue;

                    Object result = client.executeTyped(parts);
                    printResult(result);

                } catch (RedisClient.RedisException e) {
                    System.out.println("(error) " + e.getMessage());
                } catch (IOException e) {
                    System.out.println("ERROR: " + e.getMessage());
                    break;
                } catch (Exception e) {
                    System.out.println("ERROR: " + e.getMessage());
                }
            }

        } catch (IOException e) {
            System.out.println("Could not connect: " + e.getMessage());
        } finally {
            client.disconnect();
            System.out.println("Bye.");
        }
    }

    /**
     * Simple tokenizer that supports double-quoted strings.
     * Example: SET name "Grok AI" EX 30  →  ["SET", "name", "Grok AI", "EX", "30"]
     */
    private static String[] tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        Matcher m = Pattern.compile("\"([^\"]*)\"|(\\S+)").matcher(line);

        while (m.find()) {
            if (m.group(1) != null) {
                tokens.add(m.group(1));          // quoted
            } else {
                tokens.add(m.group(2));          // unquoted
            }
        }
        return tokens.toArray(new String[0]);
    }

    private static void printResult(Object result) {
        if (result == null) {
            System.out.println("(nil)");
        } else if (result instanceof Long l) {
            System.out.println("(integer) " + l);
        } else if (result instanceof List<?> list) {
            if (list.isEmpty()) {
                System.out.println("(empty array)");
            } else {
                for (int i = 0; i < list.size(); i++) {
                    Object item = list.get(i);
                    System.out.println((i + 1) + ") " + (item == null ? "(nil)" : item));
                }
            }
        } else {
            System.out.println(result);
        }
    }
}