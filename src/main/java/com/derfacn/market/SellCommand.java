package com.derfacn.market;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class SellCommand {
    static Connection conn;

    public static LiteralArgumentBuilder<CommandSourceStack> createCommand(Connection connection) {
        conn = connection;
        return Commands.literal("new_sell")
                .executes(
                        ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            if (!(sender instanceof Player)) {
                                sender.sendMessage("Only players can use this command!");
                                return 1;
                            }

                            sender.sendMessage("§rCommand usage: /new_sell <price> [<amount>]");

                            return 1;
                        }
                ).then(
                Commands.argument("price", LongArgumentType.longArg(0, 2034))
                        .executes(SellCommand::sellWithPrice).then(
                                Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                                        .executes(SellCommand::SellWithPriceAndAmount)
                        )
        );
    }

    private static int sellWithPrice(CommandContext<CommandSourceStack> ctx) {
        long price = LongArgumentType.getLong(ctx, "price");

        CommandSender sender = ctx.getSource().getSender();
        Entity executor = ctx.getSource().getExecutor();

        if (!(executor instanceof Player player)) {
            sender.sendPlainMessage("Only players can use this command!");
            return 1;
        }

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType() == Material.AIR) {
            player.sendPlainMessage("Треба тримати предмет у головній руці!");
            return 1;
        }

        try {
            PreparedStatement stmt = conn.prepareStatement("INSERT INTO items (item, seller, price) VALUES (?, ?, ?");
            stmt.setBytes(1, BukkitObjectSerializer.itemStackToBytes(item));
            stmt.setString(2, player.getName().toLowerCase());
            stmt.setLong(3, price);
            stmt.executeUpdate();

            player.getInventory().setItemInMainHand(null);
            player.sendMessage(ChatColor.GREEN + "Предмет виставлено на продаж за " + price + " ізумрудів.");
        } catch (SQLException | IOException e) {
            sender.sendPlainMessage("§rError while adding item to database!");
        }

        return 1;
    }

    private static int SellWithPriceAndAmount(CommandContext<CommandSourceStack> ctx) {
        long price = LongArgumentType.getLong(ctx, "price");
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        ctx.getSource().getExecutor().sendMessage("Price: " + price + " , amount: " + amount);
        return 1;
    }
}