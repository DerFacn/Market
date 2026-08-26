package com.derfacn.market;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.sql.*;
import java.util.UUID;
import java.util.logging.Level;

public class TelegramAPI {
    private final JavaPlugin plugin;
    private final Connection marketDb;
    private Connection tgDb;
    private HttpServer server;
    private final String apiKey;
    private final int port;

    public TelegramAPI(JavaPlugin plugin, Connection marketDb, String apiKey, int port) {
        this.plugin = plugin;
        this.marketDb = marketDb;
        this.apiKey = apiKey;
        this.port = port;
    }

    public void init() {
        try {
            tgDb = DriverManager.getConnection("jdbc:sqlite:" + plugin.getDataFolder() + "/telegram_connections.db");
            try (Statement stmt = tgDb.createStatement()) {
                stmt.executeUpdate("CREATE TABLE IF NOT EXISTS linked_accounts (telegram_id BIGINT PRIMARY KEY, uuid VARCHAR(36))");
                stmt.executeUpdate("CREATE TABLE IF NOT EXISTS link_codes (code VARCHAR(6) PRIMARY KEY, uuid VARCHAR(36), expires BIGINT)");
            }

            server = HttpServer.create(new InetSocketAddress(port), 0);

            server.createContext("/api/link", this::handleLink);
            server.createContext("/api/balance", this::handleBalance);
            server.createContext("/api/market", this::handleMarket);
            server.createContext("/api/orders", this::handleOrders);
            server.createContext("/api/myorders", this::handleMyOrders);
            server.createContext("/api/order", this::handlePlaceOrder);
            server.createContext("/api/cancel", this::handleCancelOrder);
            server.createContext("/api/send", this::handleSend);

            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.start();
            plugin.getLogger().info("Telegram API запущено на порту " + port);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Помилка запуску Telegram API", e);
        }
    }

    public void stop() {
        if (server != null) server.stop(0);
        try { if (tgDb != null) tgDb.close(); } catch (SQLException ignored) {}
    }

    public String generateLinkCode(String uuid) {
        String code = String.format("%06d", new java.util.Random().nextInt(999999));
        long expires = System.currentTimeMillis() + 300000; // 5 хвилин
        try (PreparedStatement stmt = tgDb.prepareStatement("INSERT INTO link_codes (code, uuid, expires) VALUES (?, ?, ?)")) {
            stmt.setString(1, code);
            stmt.setString(2, uuid);
            stmt.setLong(3, expires);
            stmt.executeUpdate();
            return code;
        } catch (SQLException e) {
            return null;
        }
    }

    private String getUuidByTgId(long tgId) throws SQLException {
        try (PreparedStatement stmt = tgDb.prepareStatement("SELECT uuid FROM linked_accounts WHERE telegram_id = ?")) {
            stmt.setLong(1, tgId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) return rs.getString("uuid");
        }
        return null;
    }

    // --- HTTP HANDLERS ---

    private void handleLink(HttpExchange exchange) {
        if (!checkAuth(exchange)) return;
        try {
            JsonObject req = JsonParser.parseReader(new InputStreamReader(exchange.getRequestBody())).getAsJsonObject();
            long tgId = req.get("telegram_id").getAsLong();
            String code = req.get("code").getAsString();

            try (PreparedStatement stmt = tgDb.prepareStatement("SELECT uuid FROM link_codes WHERE code = ? AND expires > ?")) {
                stmt.setString(1, code);
                stmt.setLong(2, System.currentTimeMillis());
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    String uuid = rs.getString("uuid");
                    try (PreparedStatement insert = tgDb.prepareStatement("INSERT INTO linked_accounts (telegram_id, uuid) VALUES (?, ?) ON CONFLICT(telegram_id) DO UPDATE SET uuid = excluded.uuid")) {
                        insert.setLong(1, tgId);
                        insert.setString(2, uuid);
                        insert.executeUpdate();
                    }
                    try (PreparedStatement del = tgDb.prepareStatement("DELETE FROM link_codes WHERE code = ?")) {
                        del.setString(1, code);
                        del.executeUpdate();
                    }
                    sendResponse(exchange, 200, "{\"success\":true, \"message\":\"Акаунт успішно прив'язано!\"}");
                } else {
                    sendResponse(exchange, 400, "{\"success\":false, \"message\":\"Код недійсний або прострочений.\"}");
                }
            }
        } catch (Exception e) { sendResponse(exchange, 500, "{\"error\":\"server error\"}"); }
    }

    private void handleBalance(HttpExchange exchange) {
        if (!checkAuth(exchange)) return;
        try {
            long tgId = Long.parseLong(getQueryParam(exchange.getRequestURI().getQuery(), "tg_id"));
            String uuid = getUuidByTgId(tgId);
            if (uuid == null) { sendResponse(exchange, 403, "{\"error\":\"not_linked\"}"); return; }

            try (PreparedStatement stmt = marketDb.prepareStatement("SELECT amount FROM balance WHERE uuid = ?")) {
                stmt.setString(1, uuid);
                ResultSet rs = stmt.executeQuery();
                int balance = rs.next() ? rs.getInt("amount") : 0;
                sendResponse(exchange, 200, "{\"success\":true, \"balance\":" + balance + "}");
            }
        } catch (Exception e) { sendResponse(exchange, 500, "{\"error\":\"server error\"}"); }
    }

    private void handleMarket(HttpExchange exchange) {
        if (!checkAuth(exchange)) return;
        try {
            int page = 0;
            String pageStr = getQueryParam(exchange.getRequestURI().getQuery(), "page");
            if (pageStr != null) page = Integer.parseInt(pageStr);

            try (PreparedStatement stmt = marketDb.prepareStatement("SELECT id, item, seller, price FROM items ORDER BY id DESC LIMIT 20 OFFSET ?")) {
                stmt.setInt(1, page * 20);
                try (ResultSet rs = stmt.executeQuery()) {
                    JsonArray arr = new JsonArray();
                    while (rs.next()) {
                        JsonObject obj = new JsonObject();
                        obj.addProperty("id", rs.getInt("id"));
                        obj.addProperty("seller", rs.getString("seller"));
                        obj.addProperty("price", rs.getInt("price"));
                        ItemStack item = BukkitObjectSerializer.bytesToItemStack(rs.getBytes("item"));
                        obj.addProperty("item", item.getType().name() + " x" + item.getAmount());
                        arr.add(obj);
                    }
                    sendResponse(exchange, 200, arr.toString());
                }
            }
        } catch (Exception e) { sendResponse(exchange, 500, "{\"error\":\"server error\"}"); }
    }

    private void handleOrders(HttpExchange exchange) {
        if (!checkAuth(exchange)) return;
        try {
            int page = 0;
            String pageStr = getQueryParam(exchange.getRequestURI().getQuery(), "page");
            if (pageStr != null) page = Integer.parseInt(pageStr);

            try (PreparedStatement stmt = marketDb.prepareStatement("SELECT id, username, material, count, price FROM orders WHERE expire_date > ? ORDER BY timestamp DESC LIMIT 20 OFFSET ?")) {
                stmt.setLong(1, System.currentTimeMillis());
                stmt.setInt(2, page * 20);
                try (ResultSet rs = stmt.executeQuery()) {
                    JsonArray arr = new JsonArray();
                    while (rs.next()) {
                        JsonObject obj = new JsonObject();
                        obj.addProperty("id", rs.getInt("id"));
                        obj.addProperty("customer", rs.getString("username"));
                        obj.addProperty("material", rs.getString("material") + " x" + rs.getInt("count"));
                        obj.addProperty("price", rs.getInt("price"));
                        arr.add(obj);
                    }
                    sendResponse(exchange, 200, arr.toString());
                }
            }
        } catch (Exception e) { sendResponse(exchange, 500, "{\"error\":\"server error\"}"); }
    }

    private void handleMyOrders(HttpExchange exchange) {
        if (!checkAuth(exchange)) return;
        try {
            long tgId = Long.parseLong(getQueryParam(exchange.getRequestURI().getQuery(), "tg_id"));
            String uuid = getUuidByTgId(tgId);
            if (uuid == null) { sendResponse(exchange, 403, "{\"error\":\"not_linked\"}"); return; }

            int page = 0;
            String pageStr = getQueryParam(exchange.getRequestURI().getQuery(), "page");
            if (pageStr != null) page = Integer.parseInt(pageStr);

            try (PreparedStatement stmt = marketDb.prepareStatement("SELECT id, material, count, price FROM orders WHERE uuid = ? ORDER BY timestamp DESC LIMIT 20 OFFSET ?")) {
                stmt.setString(1, uuid);
                stmt.setInt(2, page * 20);
                try (ResultSet rs = stmt.executeQuery()) {
                    JsonArray arr = new JsonArray();
                    while (rs.next()) {
                        JsonObject obj = new JsonObject();
                        obj.addProperty("id", rs.getInt("id"));
                        obj.addProperty("material", rs.getString("material") + " x" + rs.getInt("count"));
                        obj.addProperty("price", rs.getInt("price"));
                        arr.add(obj);
                    }
                    sendResponse(exchange, 200, arr.toString());
                }
            }
        } catch (Exception e) { sendResponse(exchange, 500, "{\"error\":\"server error\"}"); }
    }

    private void handlePlaceOrder(HttpExchange exchange) {
        if (!checkAuth(exchange)) return;
        try {
            JsonObject req = JsonParser.parseReader(new InputStreamReader(exchange.getRequestBody())).getAsJsonObject();
            String uuid = getUuidByTgId(req.get("telegram_id").getAsLong());
            if (uuid == null) { sendResponse(exchange, 403, "{\"error\":\"not_linked\"}"); return; }

            String matName = req.get("material").getAsString().toUpperCase();
            int count = req.get("count").getAsInt();
            int price = req.get("price").getAsInt();
            int days = req.get("days").getAsInt();

            Material material = Material.matchMaterial(matName);
            if (material == null || !material.isItem()) {
                sendResponse(exchange, 400, "{\"success\":false, \"message\":\"Невідомий предмет!\"}"); return;
            }
            if (count > material.getMaxStackSize()) {
                sendResponse(exchange, 400, "{\"success\":false, \"message\":\"Максимальний стак для цього предмета: " + material.getMaxStackSize() + "\"}"); return;
            }

            // Перевіряємо та знімаємо баланс (ТІЛЬКИ віртуальний, інвентар офлайн перевірити неможливо)
            try (PreparedStatement stmt = marketDb.prepareStatement("SELECT amount, player FROM balance WHERE uuid = ?")) {
                stmt.setString(1, uuid);
                ResultSet rs = stmt.executeQuery();
                if (rs.next() && rs.getInt("amount") >= price) {
                    String username = rs.getString("player");
                    // Віднімаємо гроші
                    try (PreparedStatement update = marketDb.prepareStatement("UPDATE balance SET amount = amount - ? WHERE uuid = ?")) {
                        update.setInt(1, price); update.setString(2, uuid); update.executeUpdate();
                    }
                    // Створюємо замовлення
                    try (PreparedStatement insert = marketDb.prepareStatement("INSERT INTO orders (uuid, username, material, count, price, expire_date, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                        insert.setString(1, uuid); insert.setString(2, username); insert.setString(3, material.name());
                        insert.setInt(4, count); insert.setInt(5, price);
                        insert.setLong(6, System.currentTimeMillis() + (days * 86400000L));
                        insert.setLong(7, System.currentTimeMillis());
                        insert.executeUpdate();
                    }
                    sendResponse(exchange, 200, "{\"success\":true, \"message\":\"Замовлення успішно створено!\"}");
                } else {
                    sendResponse(exchange, 400, "{\"success\":false, \"message\":\"Недостатньо віртуальних ізумрудів! (Поповніть баланс у грі)\"}");
                }
            }
        } catch (Exception e) { sendResponse(exchange, 500, "{\"error\":\"server error\"}"); }
    }

    private void handleCancelOrder(HttpExchange exchange) {
        if (!checkAuth(exchange)) return;
        try {
            JsonObject req = JsonParser.parseReader(new InputStreamReader(exchange.getRequestBody())).getAsJsonObject();
            String uuid = getUuidByTgId(req.get("telegram_id").getAsLong());
            int orderId = req.get("order_id").getAsInt();

            try (PreparedStatement stmt = marketDb.prepareStatement("SELECT price FROM orders WHERE id = ? AND uuid = ?")) {
                stmt.setInt(1, orderId); stmt.setString(2, uuid);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    int price = rs.getInt("price");
                    try (PreparedStatement insert = marketDb.prepareStatement("INSERT INTO completed_orders (owner_uuid, fulfiller_name, is_refund, emerald_amount, timestamp) VALUES (?, 'Скасовано (через ТГ)', 1, ?, ?)")) {
                        insert.setString(1, uuid); insert.setInt(2, price); insert.setLong(3, System.currentTimeMillis());
                        insert.executeUpdate();
                    }
                    try (PreparedStatement del = marketDb.prepareStatement("DELETE FROM orders WHERE id = ?")) {
                        del.setInt(1, orderId); del.executeUpdate();
                    }
                    sendResponse(exchange, 200, "{\"success\":true, \"message\":\"Замовлення скасовано, кошти повернуто в Депо.\"}");
                } else {
                    sendResponse(exchange, 400, "{\"success\":false, \"message\":\"Замовлення не знайдено або воно не ваше.\"}");
                }
            }
        } catch (Exception e) { sendResponse(exchange, 500, "{\"error\":\"server error\"}"); }
    }

    private void handleSend(HttpExchange exchange) {
        if (!checkAuth(exchange)) return;
        try {
            JsonObject req = JsonParser.parseReader(new InputStreamReader(exchange.getRequestBody())).getAsJsonObject();
            String uuid = getUuidByTgId(req.get("telegram_id").getAsLong());
            String target = req.get("target_name").getAsString().toLowerCase();
            int amount = req.get("amount").getAsInt();

            try (PreparedStatement check = marketDb.prepareStatement("SELECT amount FROM balance WHERE uuid = ?")) {
                check.setString(1, uuid);
                ResultSet rs = check.executeQuery();
                if (rs.next() && rs.getInt("amount") >= amount) {
                    // Перевіряємо чи існує ціль
                    try (PreparedStatement checkTgt = marketDb.prepareStatement("SELECT uuid FROM balance WHERE player = ?")) {
                        checkTgt.setString(1, target);
                        if (!checkTgt.executeQuery().next()) {
                            sendResponse(exchange, 400, "{\"success\":false, \"message\":\"Гравця не знайдено в базі!\"}"); return;
                        }
                    }
                    // Віднімаємо і додаємо
                    try (PreparedStatement sub = marketDb.prepareStatement("UPDATE balance SET amount = amount - ? WHERE uuid = ?")) {
                        sub.setInt(1, amount); sub.setString(2, uuid); sub.executeUpdate();
                    }
                    try (PreparedStatement add = marketDb.prepareStatement("UPDATE balance SET amount = amount + ? WHERE player = ?")) {
                        add.setInt(1, amount); add.setString(2, target); add.executeUpdate();
                    }
                    sendResponse(exchange, 200, "{\"success\":true, \"message\":\"Успішно надіслано!\"}");
                } else {
                    sendResponse(exchange, 400, "{\"success\":false, \"message\":\"Недостатньо коштів на балансі!\"}");
                }
            }
        } catch (Exception e) { sendResponse(exchange, 500, "{\"error\":\"server error\"}"); }
    }

    private boolean checkAuth(HttpExchange exchange) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.equals("Bearer " + apiKey)) {
            sendResponse(exchange, 401, "{\"error\":\"Unauthorized\"}");
            return false;
        }
        return true;
    }

    private void sendResponse(HttpExchange exchange, int code, String response) {
        try {
            byte[] bytes = response.getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Помилка відправки відповіді API: " + e.getMessage());
        }
    }

    private String getQueryParam(String query, String param) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=");
            if (kv.length > 1 && kv[0].equals(param)) return kv[1];
        }
        return null;
    }
}