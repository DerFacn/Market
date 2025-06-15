package com.derfacn.market;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;

public class Market extends JavaPlugin implements Listener, TabExecutor {
    private Connection connection;
    private final int ITEMS_PER_PAGE = 45;
    private final String MARKET_TITLE = "Маркет";
    private final String SALES_TITLE = "Мої товари";

    @Override
    public void onEnable() {
//        getServer().getPluginManager().registerEvents(new PlayerJoinListener(connection), this);
        getServer().getPluginManager().registerEvents(this, this);

//        LiteralArgumentBuilder newSellCommand = SellCommand.createCommand(connection);  // Creating new_sell command
//
//        LiteralCommandNode<CommandSourceStack> buildCommand = newSellCommand.build();
//        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commands -> {
//            commands.registrar().register(buildCommand);
//        });

//      Building and registering commands
        LiteralArgumentBuilder<CommandSourceStack> salesCommand = Commands.literal("sales").executes(ctx -> {
            CommandSender sender = ctx.getSource().getSender();
            if (!(sender instanceof Player player)) return 0;
            openSalesGUI(player, 0);
            return 1;
        });

        LiteralArgumentBuilder<CommandSourceStack> addCommand = Commands.literal("add").executes(ctx -> {
            CommandSender sender = ctx.getSource().getSender();

            if (!(sender instanceof Player player)) return 0;

            String playerName = player.getName().toLowerCase();
            int amount = countEmeralds(player);

            try {
                PreparedStatement stmt = connection.prepareStatement("INSERT INTO balance (player, amount) VALUES (?, ?) " +
                        "ON CONFLICT(player) DO UPDATE SET amount = amount + excluded.amount");
                stmt.setString(1, playerName);
                stmt.setInt(2, amount);
                stmt.executeUpdate();
                stmt.close();

                removeEmeralds(player, amount);

                PreparedStatement stmt1 = connection.prepareStatement("SELECT amount FROM balance WHERE player = ?");
                stmt1.setString(1, playerName);
                ResultSet rs = stmt1.executeQuery();

                int balance = 0;

                if (rs.next()) {
                    balance = rs.getInt("amount");
                }

                rs.close();
                stmt1.close();

                player.sendMessage(plain("Баланс поповнено на " + amount + " ізумрудів. Баланс: " + balance, NamedTextColor.GREEN));

            } catch (SQLException e) {
                player.sendMessage(plain("Помилка при обробці запиту", NamedTextColor.RED));
                e.printStackTrace();
            }
            return 1;
        });

        RequiredArgumentBuilder<CommandSourceStack, Integer> addWithAmountArgument = Commands.argument("amount", IntegerArgumentType.integer(1, 2034))
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();

                    if (!(sender instanceof Player player)) return 0;

                    String playerName = player.getName().toLowerCase();
                    int amount = ctx.getArgument("amount", int.class);
                    int summa = countEmeralds(player);

                    if (summa < amount) {
                        player.sendMessage(plain("Недостатньо ізумрудів в інвентарі!", NamedTextColor.RED));
                        return 1;
                    }

                    try {
                        PreparedStatement stmt = connection.prepareStatement("INSERT INTO balance (player, amount) VALUES (?, ?) " +
                                "ON CONFLICT(player) DO UPDATE SET amount = amount + excluded.amount");
                        stmt.setString(1, playerName);
                        stmt.setInt(2, amount);
                        stmt.executeUpdate();
                        stmt.close();

                        removeEmeralds(player, amount);

                        PreparedStatement stmt1 = connection.prepareStatement("SELECT amount FROM balance WHERE player = ?");
                        stmt1.setString(1, playerName);
                        ResultSet rs = stmt1.executeQuery();

                        int balance = 0;

                        if (rs.next()) {
                            balance = rs.getInt("amount");
                        }

                        player.sendMessage(plain("Баланс поповнено на " + amount + " ізумрудів. Баланс: " + balance, NamedTextColor.GREEN));

                    } catch (SQLException e) {
                        player.sendMessage(plain("Помилка при обробці запиту", NamedTextColor.RED));
                        e.printStackTrace();
                    }

                    return 1;
                }
        );

        LiteralArgumentBuilder<CommandSourceStack> takeCommand = Commands.literal("take").executes(ctx -> {
            CommandSender sender = ctx.getSource().getSender();

            if (!(sender instanceof Player player)) return 0;

            String senderName = player.getName().toLowerCase();
            try {
                PreparedStatement stmt = connection.prepareStatement("SELECT amount FROM balance WHERE player = ?");
                stmt.setString(1, senderName);
                ResultSet rs = stmt.executeQuery();

                int balance = 0;
                if (rs.next()) balance = rs.getInt("amount");
                if (balance > 0) {
                    giveCarefully(player, new ItemStack(Material.EMERALD, balance));
                    PreparedStatement update = connection.prepareStatement("UPDATE balance SET amount = 0 WHERE player = ?");
                    update.setString(1, player.getName().toLowerCase());
                    update.executeUpdate();
                    player.sendMessage(ChatColor.GREEN + "Ти забрав " + balance + " ізумрудів з балансу. Баланс: 0");
                } else {
                    player.sendMessage(ChatColor.RED + "У тебе немає коштів");
                }
            } catch (SQLException e) {
                player.sendMessage(ChatColor.RED + "Помилка при обробці запиту");
                e.printStackTrace();
            }
            return 1;
        });

        RequiredArgumentBuilder<CommandSourceStack, Integer> takeWithAmountArgument = Commands.argument("amount", IntegerArgumentType.integer(1))
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();

                    if (!(sender instanceof Player player)) return 0;

                    String playerName = player.getName().toLowerCase();
                    int amount = ctx.getArgument("amount", int.class);

                    try {
                        PreparedStatement stmt = connection.prepareStatement("SELECT amount FROM balance WHERE player = ?");
                        stmt.setString(1, playerName);
                        ResultSet rs = stmt.executeQuery();

                        int balance = 0;
                        boolean playerHasBalance = false;

                        if (rs.next()) {
                            balance = rs.getInt("amount");
                            playerHasBalance = true;
                        }

                        rs.close();
                        stmt.close();

                        if (!playerHasBalance || balance < amount) {
                            player.sendMessage(plain("Недостатньо коштів на балансі!", NamedTextColor.RED));
                            return 1;
                        }

                        PreparedStatement stmt1 = connection.prepareStatement(
                                "INSERT INTO balance (player, amount) VALUES (?, ?) " +
                                        "ON CONFLICT(player) DO UPDATE SET amount = amount - excluded.amount"
                        );

                        stmt1.setString(1, playerName);
                        stmt1.setInt(2, amount);
                        stmt1.executeUpdate();
                        stmt1.close();

                        PreparedStatement stmt2 = connection.prepareStatement("SELECT amount FROM balance WHERE player = ?");
                        stmt2.setString(1, playerName);
                        ResultSet rs2 = stmt2.executeQuery();

                        int currentBalance = 0;

                        if (rs2.next()) {
                            currentBalance = rs2.getInt("amount");
                        }

                        stmt2.close();
                        rs2.close();

                        giveCarefully(player, new ItemStack(Material.EMERALD, amount));
                        player.sendMessage(plain("Ти забрав " + amount + " ізумрудів з балансу. Баланс: " + currentBalance, NamedTextColor.GREEN));
                    } catch (SQLException e) {
                        e.printStackTrace();
                        player.sendMessage(plain("Сталася помилка при обробці запиту", NamedTextColor.RED));
                    }
                    return 1;
                }
        );

        LiteralArgumentBuilder<CommandSourceStack> balanceCommandTree = Commands.literal("balance").executes(ctx -> {
            CommandSender sender = ctx.getSource().getSender();

            if (!(sender instanceof Player player)) return 0;

            String senderName = player.getName().toLowerCase();
            try {
                PreparedStatement stmt = connection.prepareStatement("SELECT amount FROM balance WHERE player = ?");
                stmt.setString(1, senderName);
                ResultSet rs = stmt.executeQuery();

                int profit = 0;
                if (rs.next()) profit = rs.getInt("amount");

                player.sendMessage(ChatColor.YELLOW + "Твій баланс " + profit + " ізумрудів");
            } catch (SQLException e) {
                player.sendMessage(ChatColor.RED + "Помилка при обробці запиту.");
                e.printStackTrace();
            }
            return 1;
        });

        takeCommand.then(takeWithAmountArgument);
        addCommand.then(addWithAmountArgument);

        balanceCommandTree.then(addCommand);
        balanceCommandTree.then(takeCommand);

        LiteralCommandNode<CommandSourceStack> builtCommand = balanceCommandTree.build();
        LiteralCommandNode<CommandSourceStack> builtSalesCommand = salesCommand.build();

        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commands -> {
            commands.registrar().register(builtCommand);
            commands.registrar().register(builtSalesCommand);
        });

        getCommand("sell").setExecutor(this);
        getCommand("market").setExecutor(this);

        try {
            File dbFile = new File(getDataFolder(), "market.db");
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("CREATE TABLE IF NOT EXISTS items (id INTEGER PRIMARY KEY AUTOINCREMENT, item BLOB, seller TEXT, price INTEGER)");
                stmt.executeUpdate("CREATE TABLE IF NOT EXISTS balance (player VARCHAR(32) PRIMARY KEY, amount INT NOT NULL)");
            }
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Не вдалося підключитись до бази даних", e);
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) return true;

        // Обробляємо команди
        switch (command.getName().toLowerCase()) {
            case "sell" -> {
                if (args.length != 1 || !args[0].matches("\\d+")) {
                    player.sendMessage(Component.text("Використання: /sell <ціна>").color(NamedTextColor.RED));
                    return true;
                }

                ItemStack item = player.getInventory().getItemInMainHand();
                if (item.getType() == Material.AIR) {
                    player.sendMessage(Component.text("Треба тримати предмет у головній руці!").color(NamedTextColor.RED));
                    return true;
                }

                int price = Integer.parseInt(args[0]);
                try (PreparedStatement stmt = connection.prepareStatement(
                        "INSERT INTO items (item, seller, price) VALUES (?, ?, ?)")) {
                    stmt.setBytes(1, BukkitObjectSerializer.itemStackToBytes(item));
                    stmt.setString(2, player.getName().toLowerCase());
                    stmt.setInt(3, price);
                    stmt.executeUpdate();

                    player.getInventory().setItemInMainHand(null);

                    String translationKey = item.getType().translationKey();
                    Component translatedItemName = Component.translatable(translationKey);

                    player.sendMessage(translatedItemName.append(
                            Component.text(" виставлено на продаж за " + price + " ізумрудів")
                    ).color(NamedTextColor.GREEN));

                    String playerName = player.getName();
                    Bukkit.broadcast(
                            Component.text(playerName + " виставив ", NamedTextColor.GRAY)
                                    .append(translatedItemName)
                                    .append(Component.text(" за " + price + " ізумрудів", NamedTextColor.GRAY))
                                    .decorate(TextDecoration.ITALIC)
                    );
                } catch (SQLException | IOException e) {
                    player.sendMessage(Component.text("Помилка при додаванні предмета до бази").color(NamedTextColor.RED));
                    e.printStackTrace();
                }
            }

            case "market" -> openMarketGUI(player, 0);
        }

        return true;
    }

    public void openMarketGUI(Player player, int page) {
        try {
            List<MarketItem> marketItems = new ArrayList<>();
            PreparedStatement stmt = connection.prepareStatement("SELECT id, item, seller, price FROM items LIMIT ? OFFSET ?");
            stmt.setInt(1, ITEMS_PER_PAGE);
            stmt.setInt(2, page * ITEMS_PER_PAGE);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                int id = rs.getInt("id");
                byte[] data = rs.getBytes("item");
                String seller = rs.getString("seller");
                int price = rs.getInt("price");
                ItemStack item = BukkitObjectSerializer.bytesToItemStack(data);

                ItemMeta meta = item.getItemMeta();

                // Додай ID предмета в PersistentDataContainer
                NamespacedKey key = new NamespacedKey(this, "market_id");
                meta.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, id);

                meta.lore(List.of(
                        plain("Ціна: " + price + " ізумрудів", NamedTextColor.YELLOW),
                        plain("Продавець: " + seller, NamedTextColor.GRAY),
                        plain("ЛКМ щоб купити", NamedTextColor.GREEN)
                ));
                item.setItemMeta(meta);

                marketItems.add(new MarketItem(id, item, seller, price));
            }

            int currentPage = page + 1;

            PreparedStatement countStmt = connection.prepareStatement(
                    "SELECT CEIL(COUNT(*) / ?) AS total_pages FROM items"
            );
            countStmt.setDouble(1, ITEMS_PER_PAGE);
            ResultSet countRs = countStmt.executeQuery();

            int totalPages = 1;

            if (countRs.next()) {
                totalPages = countRs.getInt("total_pages");
            }

            Component guiName = Component.text(MARKET_TITLE, NamedTextColor.DARK_PURPLE)
                    .append(Component.text(" (" + currentPage + "/" + totalPages + ")"));

            Inventory gui = Bukkit.createInventory(null, 54, guiName);
            for (int i = 0; i < marketItems.size(); i++) {
                gui.setItem(i, marketItems.get(i).item());
            }

            // Кнопки пагінації
            NamespacedKey pageKey = new NamespacedKey(this, "pageKey");
            NamespacedKey refreshKey = new NamespacedKey(this, "refreshKey");

            if (page > 0) {
                ItemStack back = new ItemStack(Material.PAPER);

                ItemMeta meta = back.getItemMeta();
                meta.displayName(plain("Попередня сторінка", NamedTextColor.YELLOW));
                meta.getPersistentDataContainer().set(pageKey, PersistentDataType.STRING, "previousPage");
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                back.setItemMeta(meta);
                gui.setItem(46, back);
            }

            if (marketItems.size() == ITEMS_PER_PAGE) {
                ItemStack next = new ItemStack(Material.PAPER);
                ItemMeta meta = next.getItemMeta();
                meta.displayName(plain("Наступна сторінка", NamedTextColor.YELLOW));
                meta.getPersistentDataContainer().set(pageKey, PersistentDataType.STRING, "nextPage");
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                next.setItemMeta(meta);
                gui.setItem(52, next);
            }

            ItemStack refresh = new ItemStack(Material.CLOCK);
            ItemMeta refreshMeta = refresh.getItemMeta();
            refreshMeta.displayName(plain("Оновити сторінку", NamedTextColor.YELLOW));
            refreshMeta.getPersistentDataContainer().set(refreshKey, PersistentDataType.STRING, "refreshKey");
            refreshMeta.addEnchant(Enchantment.UNBREAKING, 1, true);
            refreshMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            refresh.setItemMeta(refreshMeta);
            gui.setItem(49, refresh);

            player.openInventory(gui);
            player.setMetadata("market_page", new org.bukkit.metadata.FixedMetadataValue(this, page));

            stmt.close();
            countStmt.close();
        } catch (Exception e) {
            player.sendMessage(Component.text("Не вдалося відкрити маркет").color(NamedTextColor.RED));
            e.printStackTrace();
        }
    }

    public void openSalesGUI(Player player, int page) {
        try {
            List<SalesItem> salesItems = new ArrayList<>();
            String seller = player.getName().toLowerCase();
            PreparedStatement stmt = connection.prepareStatement("SELECT id, item, price FROM items WHERE seller = ? LIMIT ? OFFSET ?");
            stmt.setString(1, seller);
            stmt.setInt(2, ITEMS_PER_PAGE);
            stmt.setInt(3, page * ITEMS_PER_PAGE);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                int id = rs.getInt("id");
                byte[] data = rs.getBytes("item");
                int price = rs.getInt("price");
                ItemStack item = BukkitObjectSerializer.bytesToItemStack(data);

                ItemMeta meta = item.getItemMeta();

                NamespacedKey key = new NamespacedKey(this, "sales_id");
                meta.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, id);

                meta.lore(List.of(
                        plain("Ціна: " + price + " ізумрудів", NamedTextColor.YELLOW),
                        plain("ЛКМ - зняти з продажу", NamedTextColor.RED)
                ));
                item.setItemMeta(meta);

                salesItems.add(new SalesItem(id, item, price));
            }

            Inventory gui = Bukkit.createInventory(null, 54, Component.text(SALES_TITLE, NamedTextColor.DARK_PURPLE));
            for (int i = 0; i < salesItems.size(); i++) {
                gui.setItem(i, salesItems.get(i).item());
            }

            NamespacedKey pageKey = new NamespacedKey(this, "pageKey");

            if (page > 0) {
                ItemStack back = new ItemStack(Material.PAPER);
                ItemMeta meta = back.getItemMeta();
                meta.displayName(plain("Попередня сторінка", NamedTextColor.YELLOW));
                meta.getPersistentDataContainer().set(pageKey, PersistentDataType.STRING, "previousPage");
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                back.setItemMeta(meta);
                gui.setItem(46, back);
            }

            if (salesItems.size() == ITEMS_PER_PAGE) {
                ItemStack next = new ItemStack(Material.PAPER);
                ItemMeta meta = next.getItemMeta();
                meta.displayName(plain("Наступна сторінка", NamedTextColor.YELLOW));
                meta.getPersistentDataContainer().set(pageKey, PersistentDataType.STRING, "nextPage");
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                next.setItemMeta(meta);
                gui.setItem(52, next);
            }

            stmt.close();

            player.openInventory(gui);
            player.setMetadata("sales_page", new org.bukkit.metadata.FixedMetadataValue(this, page));
        } catch (Exception e) {
            player.sendMessage(Component.text("Не вдалося відкрити продажі").color(NamedTextColor.RED));
            e.printStackTrace();
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        HumanEntity human = event.getWhoClicked();
        if (!(human instanceof Player player)) return;

        String title = event.getView().getTitle();
        boolean isMarket = title.contains(MARKET_TITLE);
        boolean isSales = title.contains(SALES_TITLE);

        if (!isMarket && !isSales) return;

        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        NamespacedKey pageKey = new NamespacedKey(this, "pageKey");
        NamespacedKey refreshKey = new NamespacedKey(this, "refreshKey");

        // Pages buttons
        if (clicked.getItemMeta().getPersistentDataContainer().has(pageKey, PersistentDataType.STRING)) {
            if (Objects.equals(clicked.getItemMeta().getPersistentDataContainer().get(pageKey, PersistentDataType.STRING), "previousPage")) {
                if (isMarket) {
                    int currentPage = player.hasMetadata("market_page") ? player.getMetadata("market_page").get(0).asInt() : 0;
                    openMarketGUI(player, currentPage - 1);
                }
                else {
                    int currentPage = player.hasMetadata("sales_page") ? player.getMetadata("sales_page").get(0).asInt() : 0;
                    openSalesGUI(player, currentPage - 1);
                }
            } else if (Objects.equals(clicked.getItemMeta().getPersistentDataContainer().get(pageKey, PersistentDataType.STRING), "nextPage")) {
                if (isMarket) {
                    int currentPage = player.hasMetadata("market_page") ? player.getMetadata("market_page").get(0).asInt() : 0;
                    openMarketGUI(player, currentPage + 1);
                }
                else {
                    int currentPage = player.hasMetadata("sales_page") ? player.getMetadata("sales_page").get(0).asInt() : 0;
                    openSalesGUI(player, currentPage + 1);
                }
            }
            return;
        }

        // Refresh button
        if (clicked.getItemMeta().getPersistentDataContainer().has(refreshKey, PersistentDataType.STRING)) {
            openMarketGUI(player, player.hasMetadata("market_page") ? player.getMetadata("market_page").get(0).asInt() : 0);
            return;
        }

        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) return;

        if (isMarket) {
            NamespacedKey key = new NamespacedKey(this, "market_id");
            if (!meta.getPersistentDataContainer().has(key, PersistentDataType.INTEGER)) return;

            int clickedId = meta.getPersistentDataContainer().get(key, PersistentDataType.INTEGER);

            try {
                PreparedStatement stmt = connection.prepareStatement("SELECT item, price, seller FROM items WHERE id = ?");
                stmt.setInt(1, clickedId);
                ResultSet rs = stmt.executeQuery();

                if (rs.next()) {
                    int price = rs.getInt("price");
                    String seller = rs.getString("seller");
                    ItemStack dbItem = BukkitObjectSerializer.bytesToItemStack(rs.getBytes("item"));

                    // Market: покупка
                    boolean purchaseSuccessful = false;

                    // Firstly, trying to withdraw from balance
                    if (getBalance(player) >= price) {
                        if (withdrawBalance(player, price)) {
                            purchaseSuccessful = true;
                        }
                    }

                    // If not withdrew, then take from inventory
                    if (!purchaseSuccessful && countEmeralds(player) >= price) {
                        removeEmeralds(player, price);
                        purchaseSuccessful = true;
                    }

                    if (purchaseSuccessful) {
                        giveCarefully(player, dbItem);

                        if (seller != null) {
                            PreparedStatement balanceStmt = connection.prepareStatement(
                                    "INSERT INTO balance (player, amount) VALUES (?, ?) " +
                                            "ON CONFLICT(player) DO UPDATE SET amount = amount + excluded.amount"
                            );
                            balanceStmt.setString(1, seller);
                            balanceStmt.setInt(2, price);
                            balanceStmt.executeUpdate();
                            balanceStmt.close();

                            PreparedStatement del = connection.prepareStatement("DELETE FROM items WHERE id = ?");
                            del.setInt(1, clickedId);
                            del.executeUpdate();
                            del.close();

                            player.sendMessage(Component.text("Ви купили предмет за " + price + " ізумрудів").color(NamedTextColor.GREEN));

                            // Повідомлення продавцю
                            String translationKey = dbItem.getType().translationKey();
                            Component translatedItemName = Component.translatable(translationKey);

                            sendMessageToPlayerByLowercaseName(seller, translatedItemName
                                    .append(Component.text(" було продано!"))
                                    .color(NamedTextColor.GREEN)
                                    .decorate(TextDecoration.ITALIC)
                            );

                            player.closeInventory();
                            openMarketGUI(player, player.hasMetadata("market_page") ? player.getMetadata("market_page").get(0).asInt() : 0);
                        }
                    } else {
                       player.sendMessage(plain("Недостатньо ізумрудів", NamedTextColor.RED));
                    }
                } else {
                    player.sendMessage(Component.text("Цей предмет вже куплено або знято").color(NamedTextColor.RED));
                }

                stmt.close();
                rs.close();
            } catch (Exception e) {
                player.sendMessage(Component.text("Помилка при обробці дії").color(NamedTextColor.RED));
                e.printStackTrace();
            }
        } else if (isSales) {
            NamespacedKey key = new NamespacedKey(this, "sales_id");
            if (!meta.getPersistentDataContainer().has(key, PersistentDataType.INTEGER)) return;

            int clickedId = meta.getPersistentDataContainer().get(key, PersistentDataType.INTEGER);

            try {
                PreparedStatement stmt = connection.prepareStatement("SELECT item FROM items WHERE id = ?");
                stmt.setInt(1, clickedId);
                ResultSet rs = stmt.executeQuery();

                if (rs.next()) {
                    ItemStack dbItem = BukkitObjectSerializer.bytesToItemStack(rs.getBytes("item"));
                    giveCarefully(player, dbItem);

                    PreparedStatement del = connection.prepareStatement("DELETE FROM items WHERE id = ?");
                    del.setInt(1, clickedId);
                    del.executeUpdate();
                    del.close();

                    player.sendMessage(Component.text("Ви зняли предмет з продажу").color(NamedTextColor.YELLOW));
                    player.closeInventory();
                    openSalesGUI(player, player.hasMetadata("sales_page") ? player.getMetadata("sales_page").get(0).asInt() : 0);
                } else {
                    player.sendMessage(Component.text("Цей предмет вже куплено або знято").color(NamedTextColor.RED));
                }
                stmt.close();
            } catch (Exception e) {
                player.sendMessage(Component.text("Помилка при обробці дії").color(NamedTextColor.RED));
                e.printStackTrace();
            }
        }
    }

    public void giveCarefully(Player player, ItemStack items) {
        HashMap<Integer, ItemStack> leftovers = player.getInventory().addItem(items);

        if (!leftovers.isEmpty()) {
            for (ItemStack item : leftovers.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), item);
            }
            player.sendMessage(plain("Інвентар був повний, предмет(и) скинуто під ноги.", NamedTextColor.YELLOW));
        }
    }

    public int countEmeralds(Player player) {
        int total = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == Material.EMERALD) total += item.getAmount();
        }
        return total;
    }

    public int getBalance(Player player) {
        int balance = 0;
        String playerName = player.getName().toLowerCase();
        try {
            PreparedStatement stmt = connection.prepareStatement("SELECT amount FROM balance WHERE player = ?");
            stmt.setString(1, playerName);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                balance = rs.getInt("amount");
            }
            return balance;
        } catch (SQLException e) {
            e.printStackTrace();
            return 0;
        }
    }

    public boolean withdrawBalance(Player player, @Nullable Integer amount) {
        String playerName = player.getName().toLowerCase();

        if (amount != null) {
            try {
                PreparedStatement stmt = connection.prepareStatement(
                        "INSERT INTO balance (player, amount) VALUES (?, ?) " +
                                "ON CONFLICT(player) DO UPDATE SET amount = amount - excluded.amount");
                stmt.setString(1, playerName);
                stmt.setInt(2, amount);
                stmt.executeUpdate();
                stmt.close();

                return true;
            } catch (SQLException e) {
                e.printStackTrace();
                return false;
            }
        } else {
            try {
                PreparedStatement stmt = connection.prepareStatement("UPDATE balance SET amount = 0 WHERE player = ?");
                stmt.setString(1, playerName);
                stmt.executeUpdate();
                stmt.close();

                return true;
            } catch (SQLException e) {
                e.printStackTrace();
                return false;
            }
        }
    }

    public void removeEmeralds(Player player, int amount) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && amount > 0; i++) {
            ItemStack item = contents[i];
            if (item != null && item.getType() == Material.EMERALD) {
                int remove = Math.min(item.getAmount(), amount);
                item.setAmount(item.getAmount() - remove);
                amount -= remove;
                if (item.getAmount() <= 0) contents[i] = null;
            }
        }
        player.getInventory().setContents(contents);
    }

    public static Component plain(@NotNull String text,
                                  @Nullable NamedTextColor namedTextColor) {
        Component result = Component.text(text).decoration(TextDecoration.ITALIC, false);
        if (namedTextColor != null) {
            return result.color(namedTextColor);
        }
        return result;
    }

    public void sendMessageToPlayerByLowercaseName(String lowercaseName, Component message) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().equalsIgnoreCase(lowercaseName)) {
                player.sendMessage(message);
                break;
            }
        }
    }

    public record MarketItem(int id, ItemStack item, String seller, int price) {}

    public record SalesItem(int id, ItemStack item, int price) {}
}
