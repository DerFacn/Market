package com.derfacn.market;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.logging.Level;

public class OrderExpiryTask extends BukkitRunnable {
    private final JavaPlugin plugin;
    private final Connection connection;

    public OrderExpiryTask(JavaPlugin plugin, Connection connection) {
        this.plugin = plugin;
        this.connection = connection;
    }

    @Override
    public void run() {
        long now = System.currentTimeMillis();
        // Перевіряємо всі замовлення, термін яких минув
        try (PreparedStatement stmt = connection.prepareStatement("SELECT id, uuid, price FROM orders WHERE expire_date <= ?")) {
            stmt.setLong(1, now);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    String uuid = rs.getString("uuid");
                    int price = rs.getInt("price");

                    // 1. Додаємо запис про повернення ізумрудів у депо гравця
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO completed_orders (owner_uuid, fulfiller_name, is_refund, emerald_amount, timestamp) VALUES (?, 'Прострочено', 1, ?, ?)")) {
                        insert.setString(1, uuid);
                        insert.setInt(2, price);
                        insert.setLong(3, now);
                        insert.executeUpdate();
                    }

                    // 2. Видаляємо прострочене замовлення з активних
                    try (PreparedStatement del = connection.prepareStatement("DELETE FROM orders WHERE id = ?")) {
                        del.setInt(1, id);
                        del.executeUpdate();
                    }

                    // Тут у майбутньому ти зможеш додати виклик API/Discord Webhook
                    // plugin.getLogger().info("Замовлення #" + id + " було прострочено та закрито.");
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Помилка при фоновій перевірці прострочених замовлень", e);
        }
    }
}