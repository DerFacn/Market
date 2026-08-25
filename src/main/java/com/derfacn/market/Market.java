package com.derfacn.market;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
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
import org.bukkit.event.player.PlayerJoinEvent;
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
    private static final int ITEMS_PER_PAGE = 45;
    private static final String MARKET_TITLE = "Маркет";
    private static final String SALES_TITLE = "Мої товари";

    // Оптимізація: ініціалізуємо ключі один раз при запуску
    private NamespacedKey pageKey;
    private NamespacedKey refreshKey;
    private NamespacedKey playerNameKey;
    private NamespacedKey materialKey;
    private NamespacedKey marketIdKey;
    private NamespacedKey salesIdKey;

    @Override
    public void onEnable() {
        // Ініціалізація ключів NBT
        pageKey = new NamespacedKey(this, "pageKey");
        refreshKey = new NamespacedKey(this, "refreshKey");
        playerNameKey = new NamespacedKey(this, "playerNameKey");
        materialKey = new NamespacedKey(this, "materialKey");
        marketIdKey = new NamespacedKey(this, "market_id");
        salesIdKey = new NamespacedKey(this, "sales_id");

        getServer().getPluginManager().registerEvents(this, this);

        initDatabase();
        registerCommands();

        Objects.requireNonNull(getCommand("sell")).setExecutor(this);
    }

    @Override
    public void onDisable() {
        // Запобігання витокам ресурсів при рестартах
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                getLogger().info("З'єднання з базою даних успішно закрито.");
            }
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Помилка при закритті бази даних", e);
        }
    }

    private void initDatabase() {
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

    private void registerCommands() {
        LiteralArgumentBuilder<CommandSourceStack> salesCommand = Commands.literal("sales")
                .executes(ctx -> {
                    if (ctx.getSource().getSender() instanceof Player player) openSalesGUI(player, 0);
                    return 1;
                });

        LiteralArgumentBuilder<CommandSourceStack> marketCommand = Commands.literal("market")
                .executes(ctx -> {
                    if (ctx.getSource().getSender() instanceof Player player) openMarketGUI(player, 0);
                    return 1;
                });

        RequiredArgumentBuilder<CommandSourceStack, String> marketPlayerArgument = Commands.argument("playerName", StringArgumentType.string())
                .suggests((ctx, builder) -> {
                    try (PreparedStatement stmt = connection.prepareStatement("SELECT DISTINCT seller FROM items");
                         ResultSet rs = stmt.executeQuery()) {
                        Set<String> sellers = new HashSet<>();
                        while (rs.next()) sellers.add(rs.getString("seller"));
                        sellers.add("all");

                        sellers.stream()
                                .filter(entry -> entry.toLowerCase().startsWith(builder.getRemainingLowerCase()))
                                .forEach(builder::suggest);
                    } catch (SQLException e) {
                        getLogger().log(Level.WARNING, "Помилка автодоповнення", e);
                    }
                    return builder.buildFuture();
                })
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) return 0;
                    String playerName = ctx.getArgument("playerName", String.class);
                    openMarketGUI(player, 0, "all".equalsIgnoreCase(playerName) ? null : playerName);
                    return 1;
                });

        RequiredArgumentBuilder<CommandSourceStack, ItemStack> marketItemArgument = Commands.argument("item", ArgumentTypes.itemStack())
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) return 0;
                    String playerName = ctx.getArgument("playerName", String.class);
                    ItemStack item = ctx.getArgument("item", ItemStack.class);
                    openMarketGUI(player, 0, "all".equalsIgnoreCase(playerName) ? null : playerName, item);
                    return 1;
                });

        LiteralArgumentBuilder<CommandSourceStack> addCommand = Commands.literal("add").executes(ctx -> {
            if (!(ctx.getSource().getSender() instanceof Player player)) return 0;
            int amount = countEmeralds(player);
            if (amount > 0) {
                modifyBalance(player.getName().toLowerCase(), amount);
                removeEmeralds(player, amount);
                player.sendMessage(plain("Баланс поповнено на " + amount + " ізумрудів. Ваш баланс: " + getBalance(player), NamedTextColor.GREEN));
            } else {
                player.sendMessage(plain("У вас немає ізумрудів у інвентарі!", NamedTextColor.RED));
            }
            return 1;
        });

        RequiredArgumentBuilder<CommandSourceStack, Integer> addWithAmountArgument = Commands.argument("amount", IntegerArgumentType.integer(1, 2034))
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) return 0;
                    int amount = ctx.getArgument("amount", Integer.class);

                    if (countEmeralds(player) < amount) {
                        player.sendMessage(plain("Недостатньо ізумрудів в інвентарі!", NamedTextColor.RED));
                        return 1;
                    }

                    removeEmeralds(player, amount);
                    modifyBalance(player.getName().toLowerCase(), amount);
                    player.sendMessage(plain("Баланс поповнено на " + amount + " ізумрудів. Баланс: " + getBalance(player), NamedTextColor.GREEN));
                    return 1;
                });

        LiteralArgumentBuilder<CommandSourceStack> takeCommand = Commands.literal("take").executes(ctx -> {
            if (!(ctx.getSource().getSender() instanceof Player player)) return 0;
            int balance = getBalance(player);
            if (balance > 0) {
                setBalance(player.getName().toLowerCase(), 0);
                giveCarefully(player, new ItemStack(Material.EMERALD, balance));
                player.sendMessage(ChatColor.GREEN + "Ти забрав " + balance + " ізумрудів з балансу. Баланс: 0");
            } else {
                player.sendMessage(ChatColor.RED + "У тебе немає коштів на балансі");
            }
            return 1;
        });

        RequiredArgumentBuilder<CommandSourceStack, Integer> takeWithAmountArgument = Commands.argument("amount", IntegerArgumentType.integer(1))
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) return 0;
                    int amount = ctx.getArgument("amount", Integer.class);
                    int balance = getBalance(player);

                    if (balance < amount) {
                        player.sendMessage(plain("Недостатньо коштів на балансі!", NamedTextColor.RED));
                        return 1;
                    }

                    modifyBalance(player.getName().toLowerCase(), -amount);
                    giveCarefully(player, new ItemStack(Material.EMERALD, amount));
                    player.sendMessage(plain("Ти забрав " + amount + " ізумрудів з балансу. Баланс: " + getBalance(player), NamedTextColor.GREEN));
                    return 1;
                });

        LiteralArgumentBuilder<CommandSourceStack> balanceCommandTree = Commands.literal("balance").executes(ctx -> {
            if (!(ctx.getSource().getSender() instanceof Player player)) return 0;
            player.sendMessage(ChatColor.YELLOW + "Твій баланс " + getBalance(player) + " ізумрудів");
            return 1;
        });

        RequiredArgumentBuilder<CommandSourceStack, String> sendPlayerArgument = Commands.argument("playerName", StringArgumentType.string())
                .suggests((ctx, builder) -> {
                    try (PreparedStatement stmt = connection.prepareStatement("SELECT DISTINCT player FROM balance");
                         ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) builder.suggest(rs.getString("player"));
                    } catch (SQLException e) {
                        getLogger().log(Level.WARNING, "Помилка автодоповнення", e);
                    }
                    return builder.buildFuture();
                })
                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            if (!(ctx.getSource().getSender() instanceof Player player)) return 0;
                            int amount = ctx.getArgument("amount", Integer.class);
                            String targetName = ctx.getArgument("playerName", String.class).toLowerCase();

                            if (!playerExistsInDatabase(targetName)) {
                                player.sendMessage(Component.text("Такого гравця не існує в базі даних!", NamedTextColor.RED));
                                return 1;
                            }

                            boolean sendSuccessful = withdrawBalance(player, amount);
                            if (!sendSuccessful && countEmeralds(player) >= amount) {
                                removeEmeralds(player, amount);
                                sendSuccessful = true;
                            }

                            if (sendSuccessful) {
                                modifyBalance(targetName, amount);
                                player.sendMessage(Component.text("Успішно надіслано " + amount + " ізумрудів гравцю " + targetName, NamedTextColor.GREEN));
                                sendMessageToPlayerByLowercaseName(targetName,
                                        Component.text(player.getName() + " надіслав вам ", NamedTextColor.GREEN)
                                                .append(Component.text(amount + " ізумрудів", NamedTextColor.GOLD))
                                                .append(Component.text("!", NamedTextColor.GREEN))
                                );
                            } else {
                                player.sendMessage(Component.text("Недостатньо ізумрудів на балансі чи в інвентарі!", NamedTextColor.RED));
                            }
                            return 1;
                        }));

        LiteralArgumentBuilder<CommandSourceStack> sendCommand = Commands.literal("send").then(sendPlayerArgument);

        takeCommand.then(takeWithAmountArgument);
        addCommand.then(addWithAmountArgument);
        balanceCommandTree.then(addCommand);
        balanceCommandTree.then(takeCommand);
        marketPlayerArgument.then(marketItemArgument);
        marketCommand.then(marketPlayerArgument);

        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commands -> {
            commands.registrar().register(balanceCommandTree.build());
            commands.registrar().register(salesCommand.build());
            commands.registrar().register(marketCommand.build());
            commands.registrar().register(sendCommand.build());
        });
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) return true;

        if (command.getName().equalsIgnoreCase("sell")) {
            if (args.length != 1 || !args[0].matches("\\d+")) {
                player.sendMessage(Component.text("Використання: /sell <ціна>").color(NamedTextColor.RED));
                return true;
            }

            int price = Integer.parseInt(args[0]);
            if (price <= 0) {
                player.sendMessage(Component.text("Ціна має бути більшою за 0!").color(NamedTextColor.RED));
                return true;
            }

            ItemStack item = player.getInventory().getItemInMainHand();
            if (item.isEmpty()) {
                player.sendMessage(Component.text("Треба тримати предмет у головній руці!").color(NamedTextColor.RED));
                return true;
            }

            try (PreparedStatement stmt = connection.prepareStatement("INSERT INTO items (item, seller, price) VALUES (?, ?, ?)")) {
                stmt.setBytes(1, BukkitObjectSerializer.itemStackToBytes(item));
                stmt.setString(2, player.getName().toLowerCase());
                stmt.setInt(3, price);
                stmt.executeUpdate();

                player.getInventory().setItemInMainHand(null);

                Component translatedItemName = Component.translatable(item.getType().translationKey());
                player.sendMessage(translatedItemName.append(Component.text(" виставлено на продаж за " + price + " ізумрудів")).color(NamedTextColor.GREEN));

                Bukkit.broadcast(
                        Component.text(player.getName() + " виставив ", NamedTextColor.GRAY)
                                .append(translatedItemName)
                                .append(Component.text(" за " + price + " ізумрудів", NamedTextColor.GRAY))
                                .decorate(TextDecoration.ITALIC)
                );
            } catch (SQLException | IOException e) {
                player.sendMessage(Component.text("Помилка при додаванні предмета до бази").color(NamedTextColor.RED));
                getLogger().log(Level.SEVERE, "SQL Error", e);
            }
        }
        return true;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        String playerName = event.getPlayer().getName().toLowerCase();
        if (!playerExistsInDatabase(playerName)) {
            setBalance(playerName, 0);
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
        if (clicked == null || clicked.isEmpty() || clicked.getItemMeta() == null) return;
        ItemMeta meta = clicked.getItemMeta();

        // Кнопки сторінок
        if (meta.getPersistentDataContainer().has(pageKey, PersistentDataType.STRING)) {
            String pageAction = meta.getPersistentDataContainer().get(pageKey, PersistentDataType.STRING);
            int currentPage = player.hasMetadata(isMarket ? "market_page" : "sales_page")
                    ? player.getMetadata(isMarket ? "market_page" : "sales_page").get(0).asInt() : 0;

            int newPage = "nextPage".equals(pageAction) ? currentPage + 1 : currentPage - 1;

            if (isMarket) {
                String playerName = meta.getPersistentDataContainer().get(playerNameKey, PersistentDataType.STRING);
                ItemStack material = null;
                if (meta.getPersistentDataContainer().has(materialKey, PersistentDataType.STRING)) {
                    material = new ItemStack(Objects.requireNonNull(Material.matchMaterial(meta.getPersistentDataContainer().get(materialKey, PersistentDataType.STRING))));
                }
                openMarketGUI(player, newPage, playerName, material);
            } else {
                openSalesGUI(player, newPage);
            }
            return;
        }

        // Кнопка оновлення
        if (meta.getPersistentDataContainer().has(refreshKey, PersistentDataType.STRING)) {
            int page = player.hasMetadata("market_page") ? player.getMetadata("market_page").get(0).asInt() : 0;
            String playerName = meta.getPersistentDataContainer().get(playerNameKey, PersistentDataType.STRING);
            ItemStack material = null;
            if (meta.getPersistentDataContainer().has(materialKey, PersistentDataType.STRING)) {
                material = new ItemStack(Objects.requireNonNull(Material.matchMaterial(meta.getPersistentDataContainer().get(materialKey, PersistentDataType.STRING))));
            }
            openMarketGUI(player, page, playerName, material);
            return;
        }

        // Обробка покупки (Market)
        if (isMarket && meta.getPersistentDataContainer().has(marketIdKey, PersistentDataType.INTEGER)) {
            int clickedId = meta.getPersistentDataContainer().get(marketIdKey, PersistentDataType.INTEGER);
            handleMarketPurchase(player, meta, clickedId);
        }

        // Обробка зняття (Sales)
        else if (isSales && meta.getPersistentDataContainer().has(salesIdKey, PersistentDataType.INTEGER)) {
            int clickedId = meta.getPersistentDataContainer().get(salesIdKey, PersistentDataType.INTEGER);
            handleSalesWithdraw(player, clickedId);
        }
    }

    private void handleMarketPurchase(Player player, ItemMeta meta, int clickedId) {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT item, price, seller FROM items WHERE id = ?")) {
            stmt.setInt(1, clickedId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    int price = rs.getInt("price");
                    String seller = rs.getString("seller");
                    ItemStack dbItem = BukkitObjectSerializer.bytesToItemStack(rs.getBytes("item"));

                    boolean purchaseSuccessful = withdrawBalance(player, price);
                    if (!purchaseSuccessful && countEmeralds(player) >= price) {
                        removeEmeralds(player, price);
                        purchaseSuccessful = true;
                    }

                    if (purchaseSuccessful) {
                        giveCarefully(player, dbItem);
                        if (seller != null) modifyBalance(seller, price);

                        try (PreparedStatement del = connection.prepareStatement("DELETE FROM items WHERE id = ?")) {
                            del.setInt(1, clickedId);
                            del.executeUpdate();
                        }

                        player.sendMessage(Component.text("Ви купили предмет за " + price + " ізумрудів").color(NamedTextColor.GREEN));
                        sendMessageToPlayerByLowercaseName(seller, Component.translatable(dbItem.getType().translationKey())
                                .append(Component.text(" було продано!")).color(NamedTextColor.GREEN).decorate(TextDecoration.ITALIC));

                        int pageNumber = player.hasMetadata("market_page") ? player.getMetadata("market_page").get(0).asInt() : 0;
                        openMarketGUI(player, pageNumber, meta.getPersistentDataContainer().get(playerNameKey, PersistentDataType.STRING));
                    } else {
                        player.sendMessage(plain("Недостатньо ізумрудів", NamedTextColor.RED));
                    }
                } else {
                    player.sendMessage(Component.text("Цей предмет вже куплено або знято").color(NamedTextColor.RED));
                }
            }
        } catch (Exception e) {
            player.sendMessage(Component.text("Помилка при обробці дії").color(NamedTextColor.RED));
            getLogger().log(Level.SEVERE, "Purchase Error", e);
        }
    }

    private void handleSalesWithdraw(Player player, int clickedId) {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT item FROM items WHERE id = ?")) {
            stmt.setInt(1, clickedId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    giveCarefully(player, BukkitObjectSerializer.bytesToItemStack(rs.getBytes("item")));
                    try (PreparedStatement del = connection.prepareStatement("DELETE FROM items WHERE id = ?")) {
                        del.setInt(1, clickedId);
                        del.executeUpdate();
                    }
                    player.sendMessage(Component.text("Ви зняли предмет з продажу").color(NamedTextColor.YELLOW));
                    int page = player.hasMetadata("sales_page") ? player.getMetadata("sales_page").get(0).asInt() : 0;
                    openSalesGUI(player, page);
                } else {
                    player.sendMessage(Component.text("Цей предмет вже куплено або знято").color(NamedTextColor.RED));
                }
            }
        } catch (Exception e) {
            player.sendMessage(Component.text("Помилка при обробці дії").color(NamedTextColor.RED));
            getLogger().log(Level.SEVERE, "Withdraw Error", e);
        }
    }

    public void openMarketGUI(Player player, int page) {
        openMarketGUI(player, page, null, null);
    }
    public void openMarketGUI(Player player, int page, String playerName) {
        openMarketGUI(player, page, playerName, null);
    }
    public void openMarketGUI(Player player, int page, String playerName, ItemStack material) {
        try {
            List<MarketItem> marketItems = new ArrayList<>();
            StringBuilder sql = new StringBuilder("SELECT id, item, seller, price FROM items WHERE 1=1");
            List<Object> params = new ArrayList<>();

            if (playerName != null) {
                sql.append(" AND seller = ?");
                params.add(playerName);
            }
            if (material != null) {
                sql.append(" AND item LIKE ?");
                params.add("%" + material.getType().getKey() + "%");
            }

            sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
            params.add(ITEMS_PER_PAGE);
            params.add(page * ITEMS_PER_PAGE);

            try (PreparedStatement stmt = connection.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) stmt.setObject(i + 1, params.get(i));

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        int id = rs.getInt("id");
                        byte[] data = rs.getBytes("item");
                        String seller = rs.getString("seller");
                        int price = rs.getInt("price");
                        ItemStack item = BukkitObjectSerializer.bytesToItemStack(data);
                        ItemMeta meta = item.getItemMeta();

                        if (meta != null) {
                            meta.getPersistentDataContainer().set(marketIdKey, PersistentDataType.INTEGER, id);
                            if (playerName != null) meta.getPersistentDataContainer().set(playerNameKey, PersistentDataType.STRING, playerName);

                            meta.lore(List.of(
                                    plain("Ціна: " + price + " ізумрудів", NamedTextColor.YELLOW),
                                    plain("Продавець: " + seller, NamedTextColor.GRAY),
                                    plain("ЛКМ щоб купити", NamedTextColor.GREEN)
                            ));
                            item.setItemMeta(meta);
                        }
                        marketItems.add(new MarketItem(id, item, seller, price));
                    }
                }
            }

            int totalPages = 1;
            StringBuilder countSql = new StringBuilder("SELECT CEIL(COUNT(*) * 1.0 / ?) AS total_pages FROM items WHERE 1=1");
            List<Object> countParams = new ArrayList<>();
            countParams.add(ITEMS_PER_PAGE);

            if (playerName != null) {
                countSql.append(" AND seller = ?");
                countParams.add(playerName);
            }
            if (material != null) {
                countSql.append(" AND item LIKE ?");
                countParams.add("%" + material.getType().getKey() + "%");
            }

            try (PreparedStatement countStmt = connection.prepareStatement(countSql.toString())) {
                for (int i = 0; i < countParams.size(); i++) countStmt.setObject(i + 1, countParams.get(i));
                try (ResultSet countRs = countStmt.executeQuery()) {
                    if (countRs.next()) totalPages = Math.max(1, countRs.getInt("total_pages"));
                }
            }

            Component guiName = Component.text(MARKET_TITLE, NamedTextColor.DARK_PURPLE);
            if (playerName != null) guiName = guiName.append(Component.text(" (" + playerName + ")", NamedTextColor.DARK_PURPLE));
            guiName = guiName.append(Component.text(" (" + (page + 1) + "/" + totalPages + ")"));

            Inventory gui = Bukkit.createInventory(null, 54, guiName);
            for (int i = 0; i < marketItems.size(); i++) gui.setItem(i, marketItems.get(i).item());

            if (page > 0) gui.setItem(46, createNavButton("Попередня сторінка", "previousPage", playerName, material));
            if (marketItems.size() == ITEMS_PER_PAGE) gui.setItem(52, createNavButton("Наступна сторінка", "nextPage", playerName, material));
            gui.setItem(49, createNavButton("Оновити сторінку", "refreshKey", playerName, material));

            player.openInventory(gui);
            player.setMetadata("market_page", new org.bukkit.metadata.FixedMetadataValue(this, page));

        } catch (Exception e) {
            player.sendMessage(Component.text("Не вдалося відкрити маркет").color(NamedTextColor.RED));
            getLogger().log(Level.SEVERE, "GUI Error", e);
        }
    }

    public void openSalesGUI(Player player, int page) {
        try {
            List<SalesItem> salesItems = new ArrayList<>();
            try (PreparedStatement stmt = connection.prepareStatement("SELECT id, item, price FROM items WHERE seller = ? LIMIT ? OFFSET ?")) {
                stmt.setString(1, player.getName().toLowerCase());
                stmt.setInt(2, ITEMS_PER_PAGE);
                stmt.setInt(3, page * ITEMS_PER_PAGE);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        int id = rs.getInt("id");
                        int price = rs.getInt("price");
                        ItemStack item = BukkitObjectSerializer.bytesToItemStack(rs.getBytes("item"));
                        ItemMeta meta = item.getItemMeta();

                        if (meta != null) {
                            meta.getPersistentDataContainer().set(salesIdKey, PersistentDataType.INTEGER, id);
                            meta.lore(List.of(
                                    plain("Ціна: " + price + " ізумрудів", NamedTextColor.YELLOW),
                                    plain("ЛКМ - зняти з продажу", NamedTextColor.RED)
                            ));
                            item.setItemMeta(meta);
                        }
                        salesItems.add(new SalesItem(id, item, price));
                    }
                }
            }

            Inventory gui = Bukkit.createInventory(null, 54, Component.text(SALES_TITLE, NamedTextColor.DARK_PURPLE));
            for (int i = 0; i < salesItems.size(); i++) gui.setItem(i, salesItems.get(i).item());

            if (page > 0) gui.setItem(46, createNavButton("Попередня сторінка", "previousPage", null, null));
            if (salesItems.size() == ITEMS_PER_PAGE) gui.setItem(52, createNavButton("Наступна сторінка", "nextPage", null, null));

            player.openInventory(gui);
            player.setMetadata("sales_page", new org.bukkit.metadata.FixedMetadataValue(this, page));
        } catch (Exception e) {
            player.sendMessage(Component.text("Не вдалося відкрити продажі").color(NamedTextColor.RED));
            getLogger().log(Level.SEVERE, "GUI Error", e);
        }
    }

    private ItemStack createNavButton(String name, String action, @Nullable String playerName, @Nullable ItemStack material) {
        ItemStack item = new ItemStack(Material.PAPER);
        if ("refreshKey".equals(action)) item.setType(Material.CLOCK);

        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain(name, NamedTextColor.YELLOW));

        if ("refreshKey".equals(action)) {
            meta.getPersistentDataContainer().set(refreshKey, PersistentDataType.STRING, action);
        } else {
            meta.getPersistentDataContainer().set(pageKey, PersistentDataType.STRING, action);
        }

        if (playerName != null) meta.getPersistentDataContainer().set(playerNameKey, PersistentDataType.STRING, playerName);
        if (material != null) meta.getPersistentDataContainer().set(materialKey, PersistentDataType.STRING, material.getType().getKey().toString());

        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
        return item;
    }

    // --- Управління Балансом ---

    public int getBalance(Player player) {
        return getBalance(player.getName().toLowerCase());
    }

    private int getBalance(String playerName) {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT amount FROM balance WHERE player = ?")) {
            stmt.setString(1, playerName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getInt("amount");
            }
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Помилка отримання балансу", e);
        }
        return 0;
    }

    private void modifyBalance(String playerName, int amount) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO balance (player, amount) VALUES (?, ?) ON CONFLICT(player) DO UPDATE SET amount = amount + excluded.amount")) {
            stmt.setString(1, playerName);
            stmt.setInt(2, amount);
            stmt.executeUpdate();
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Помилка модифікації балансу", e);
        }
    }

    private void setBalance(String playerName, int amount) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO balance (player, amount) VALUES (?, ?) ON CONFLICT(player) DO UPDATE SET amount = excluded.amount")) {
            stmt.setString(1, playerName);
            stmt.setInt(2, amount);
            stmt.executeUpdate();
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Помилка встановлення балансу", e);
        }
    }

    public boolean withdrawBalance(Player player, int amount) {
        String playerName = player.getName().toLowerCase();
        if (getBalance(playerName) >= amount) {
            modifyBalance(playerName, -amount);
            return true;
        }
        return false;
    }

    private boolean playerExistsInDatabase(String playerName) {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT player FROM balance WHERE player = ? LIMIT 1")) {
            stmt.setString(1, playerName);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Помилка перевірки гравця", e);
            return false;
        }
    }

    // --- Утиліти для інвентарю ---

    public void giveCarefully(Player player, ItemStack items) {
        HashMap<Integer, ItemStack> leftovers = player.getInventory().addItem(items);
        if (!leftovers.isEmpty()) {
            for (ItemStack item : leftovers.values()) player.getWorld().dropItemNaturally(player.getLocation(), item);
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

    public static Component plain(@NotNull String text, @Nullable NamedTextColor namedTextColor) {
        Component result = Component.text(text).decoration(TextDecoration.ITALIC, false);
        return namedTextColor != null ? result.color(namedTextColor) : result;
    }

    public void sendMessageToPlayerByLowercaseName(String lowercaseName, Component message) {
        Player player = Bukkit.getPlayerExact(lowercaseName);
        if (player != null && player.isOnline()) {
            player.sendMessage(message);
        }
    }

    public record MarketItem(int id, ItemStack item, String seller, int price) {}
    public record SalesItem(int id, ItemStack item, int price) {}
}