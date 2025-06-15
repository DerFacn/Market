package com.derfacn.market;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.sql.*;

public class PlayerJoinListener implements Listener {
    private Connection connection;

    public PlayerJoinListener(Connection connection) {
        this.connection = connection;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName().toLowerCase();

        try {
            PreparedStatement stmt = connection.prepareStatement("SELECT profit_notifies FROM settings WHERE player = ?");
            stmt.setString(1, playerName);
            ResultSet rs = stmt.executeQuery();
            stmt.close();

            if (rs.next()) {

            } else {
                PreparedStatement stmt1 = connection.prepareStatement("INSERT INTO settings (player) VALUES (?)");
                stmt1.setString(1, playerName);
                stmt1.executeUpdate();


            }

        } catch (SQLException e) {

        }
    }

}
