package com.derfacn.market;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
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
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
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
    private TelegramAPI telegramAPI;
    private Connection connection;
    private static final int ITEMS_PER_PAGE = 45;

    // GUI Titles
    private static final String MARKET_TITLE = "Маркет";
    private static final String SALES_TITLE = "Мої товари";
    private static final String ORDERS_TITLE = "Замовлення";
    private static final String MY_ORDERS_TITLE = "Мої замовлення";
    private static final String DEPOT_TITLE = "Депо (Склад)";
    private static final String DEPOSIT_TITLE = "Внесіть предмети";

    // NBT Keys
    private NamespacedKey pageKey, refreshKey, playerNameKey, materialKey;
    private NamespacedKey marketIdKey, salesIdKey, orderIdKey, myOrderIdKey, depotIdKey;

    @Override
    public void onEnable() {
        initKeys();
        getServer().getPluginManager().registerEvents(this, this);
        initDatabase();

        saveDefaultConfig();
        String apiKey = getConfig().getString("api_key", "SuperSecretKey123");
        int apiPort = getConfig().getInt("api_port", 8500);

        telegramAPI = new TelegramAPI(this, connection, apiKey, apiPort);
        telegramAPI.init();

        registerCommands();
        Objects.requireNonNull(getCommand("sell")).setExecutor(this);
        // Запускаємо фонову перевірку: старт через 1 хвилину (1200 тіків), повтор кожну годину (72000 тіків)
        new OrderExpiryTask(this, connection).runTaskTimerAsynchronously(this, 1200L, 72000L);
    }

    private void initKeys() {
        pageKey = new NamespacedKey(this, "pageKey");
        refreshKey = new NamespacedKey(this, "refreshKey");
        playerNameKey = new NamespacedKey(this, "playerNameKey");
        materialKey = new NamespacedKey(this, "materialKey");
        marketIdKey = new NamespacedKey(this, "market_id");
        salesIdKey = new NamespacedKey(this, "sales_id");
        orderIdKey = new NamespacedKey(this, "order_id");
        myOrderIdKey = new NamespacedKey(this, "myorder_id");
        depotIdKey = new NamespacedKey(this, "depot_id");
    }

    @Override
    public void onDisable() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Помилка при закритті БД", e);
        }
        if (telegramAPI != null) telegramAPI.stop();
    }

    private void initDatabase() {
        try {
            File dbFile = new File(getDataFolder(), "market.db");
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("CREATE TABLE IF NOT EXISTS items (id INTEGER PRIMARY KEY AUTOINCREMENT, item BLOB, seller TEXT, price INTEGER)");
                stmt.executeUpdate("CREATE TABLE IF NOT EXISTS balance (player VARCHAR(32) PRIMARY KEY, amount INT NOT NULL)");

                stmt.executeUpdate("CREATE TABLE IF NOT EXISTS orders (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid VARCHAR(36), username TEXT, material TEXT, count INT, price INT, expire_date BIGINT, timestamp BIGINT)");
                stmt.executeUpdate("CREATE TABLE IF NOT EXISTS completed_orders (id INTEGER PRIMARY KEY AUTOINCREMENT, owner_uuid VARCHAR(36), fulfiller_name TEXT, item BLOB, is_refund BOOLEAN, emerald_amount INT, timestamp BIGINT)");

                try { stmt.executeUpdate("ALTER TABLE items ADD COLUMN uuid VARCHAR(36)"); } catch (SQLException ignored) {}
                try { stmt.executeUpdate("ALTER TABLE items ADD COLUMN timestamp BIGINT"); } catch (SQLException ignored) {}
                try { stmt.executeUpdate("ALTER TABLE balance ADD COLUMN uuid VARCHAR(36)"); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Не вдалося підключитись до бази даних", e);
        }
    }

    private void registerCommands() {
        // [Всі команди залишаються як були в минулій версії. Для економії тут той самий блок]
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
                        sellers.stream().filter(entry -> entry.toLowerCase().startsWith(builder.getRemainingLowerCase())).forEach(builder::suggest);
                    } catch (SQLException e) {
                        e.printStackTrace();
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

        LiteralArgumentBuilder<CommandSourceStack> balanceCommandTree = Commands.literal("balance")
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) return 0;
                    player.sendMessage(ChatColor.YELLOW + "Твій баланс " + getBalance(player) + " ізумрудів");
                    return 1;
                });

        LiteralArgumentBuilder<CommandSourceStack> addCommand = Commands.literal("add").executes(ctx -> {
            if (!(ctx.getSource().getSender() instanceof Player player)) return 0;
            int amount = countEmeralds(player);
            if (amount > 0) {
                modifyBalance(player.getUniqueId().toString(), player.getName(), amount);
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
                    modifyBalance(player.getUniqueId().toString(), player.getName(), amount);
                    player.sendMessage(plain("Баланс поповнено на " + amount + " ізумрудів. Баланс: " + getBalance(player), NamedTextColor.GREEN));
                    return 1;
                });

        LiteralArgumentBuilder<CommandSourceStack> takeCommand = Commands.literal("take").executes(ctx -> {
            if (!(ctx.getSource().getSender() instanceof Player player)) return 0;
            int balance = getBalance(player);
            if (balance > 0) {
                setBalance(player.getUniqueId().toString(), player.getName(), 0);
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
                    modifyBalance(player.getUniqueId().toString(), player.getName(), -amount);
                    giveCarefully(player, new ItemStack(Material.EMERALD, amount));
                    player.sendMessage(plain("Ти забрав " + amount + " ізумрудів з балансу. Баланс: " + getBalance(player), NamedTextColor.GREEN));
                    return 1;
                });

        RequiredArgumentBuilder<CommandSourceStack, String> sendPlayerArgument = Commands.argument("playerName", StringArgumentType.string())
                .suggests((ctx, builder) -> {
                    try (PreparedStatement stmt = connection.prepareStatement("SELECT DISTINCT player FROM balance");
                         ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) builder.suggest(rs.getString("player"));
                    } catch (SQLException e) {
                        e.printStackTrace();
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
                                updateBalanceByName(targetName, amount);
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

        LiteralArgumentBuilder<CommandSourceStack> ordersCommand = Commands.literal("orders")
                .executes(ctx -> {
                    if (ctx.getSource().getSender() instanceof Player player) openOrdersGUI(player, 0);
                    return 1;
                });

        LiteralArgumentBuilder<CommandSourceStack> myOrdersCommand = Commands.literal("myorders")
                .executes(ctx -> {
                    if (ctx.getSource().getSender() instanceof Player player) openMyOrdersGUI(player, 0);
                    return 1;
                });

        LiteralArgumentBuilder<CommandSourceStack> depotCommand = Commands.literal("depot")
                .executes(ctx -> {
                    if (ctx.getSource().getSender() instanceof Player player) {
                        openDepotGUI(player, 0);
                    }
                    return 1;
                });

        LiteralArgumentBuilder<CommandSourceStack> orderCommand = Commands.literal("order")
                .then(Commands.argument("material", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            String input = builder.getRemainingLowerCase();
                            for (Material mat : Material.values()) {
                                if (mat.isItem() && mat.name().toLowerCase().startsWith(input)) {
                                    builder.suggest(mat.name().toLowerCase());
                                }
                            }
                            return builder.buildFuture();
                        })
                        .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                .then(Commands.argument("price", IntegerArgumentType.integer(1))
                                        .then(Commands.argument("days", IntegerArgumentType.integer(1, 30))
                                                .executes(ctx -> {
                                                    if (!(ctx.getSource().getSender() instanceof Player player)) return 0;
                                                    String matName = ctx.getArgument("material", String.class).toUpperCase();
                                                    int count = ctx.getArgument("count", Integer.class);
                                                    int price = ctx.getArgument("price", Integer.class);
                                                    int days = ctx.getArgument("days", Integer.class);

                                                    Material material = Material.matchMaterial(matName);
                                                    if (material == null || !material.isItem()) {
                                                        player.sendMessage(plain("Такого предмета не існує!", NamedTextColor.RED));
                                                        return 1;
                                                    }

                                                    if (count > material.getMaxStackSize()) {
                                                        player.sendMessage(plain("Помилка! Максимальна кількість для " + material.name() + " в одному слоті: " + material.getMaxStackSize(), NamedTextColor.RED));
                                                        return 1;
                                                    }

                                                    if (!withdrawBalance(player, price)) {
                                                        if (countEmeralds(player) >= price) {
                                                            removeEmeralds(player, price);
                                                        } else {
                                                            player.sendMessage(plain("Недостатньо ізумрудів (на балансі чи в інвентарі)!", NamedTextColor.RED));
                                                            return 1;
                                                        }
                                                    }

                                                    long now = System.currentTimeMillis();
                                                    long expire = now + (days * 86400000L);

                                                    try (PreparedStatement stmt = connection.prepareStatement("INSERT INTO orders (uuid, username, material, count, price, expire_date, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                                                        stmt.setString(1, player.getUniqueId().toString());
                                                        stmt.setString(2, player.getName());
                                                        stmt.setString(3, material.name());
                                                        stmt.setInt(4, count);
                                                        stmt.setInt(5, price);
                                                        stmt.setLong(6, expire);
                                                        stmt.setLong(7, now);
                                                        stmt.executeUpdate();

                                                        Component msg = Component.text(player.getName() + " створив замовлення на ", NamedTextColor.GRAY)
                                                                .append(Component.translatable(material.translationKey(), NamedTextColor.YELLOW))
                                                                .append(Component.text(" x" + count + " за " + price + " ізумрудів!", NamedTextColor.GRAY));
                                                        Bukkit.broadcast(msg);

                                                    } catch (SQLException e) {
                                                        getLogger().log(Level.SEVERE, "Помилка створення замовлення", e);
                                                        player.sendMessage(plain("Виникла помилка.", NamedTextColor.RED));
                                                    }
                                                    return 1;
                                                })))));

        LiteralArgumentBuilder<CommandSourceStack> tgCommand = Commands.literal("tg")
                .then(Commands.literal("link")
                        .executes(ctx -> {
                            if (!(ctx.getSource().getSender() instanceof Player player)) return 0;
                            String code = telegramAPI.generateLinkCode(player.getUniqueId().toString());
                            if (code != null) {
                                player.sendMessage(Component.text("Ваш код для Telegram: ", NamedTextColor.YELLOW)
                                        .append(Component.text(code, NamedTextColor.GREEN, TextDecoration.BOLD))
                                        .append(Component.text(" (Діє 5 хвилин). Надішліть боту команду /link " + code, NamedTextColor.YELLOW)));
                            } else {
                                player.sendMessage(Component.text("Помилка генерації коду.", NamedTextColor.RED));
                            }
                            return 1;
                        }));

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
            commands.registrar().register(ordersCommand.build());
            commands.registrar().register(myOrdersCommand.build());
            commands.registrar().register(depotCommand.build());
            commands.registrar().register(orderCommand.build());
            commands.registrar().register(tgCommand.build());
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

            try (PreparedStatement stmt = connection.prepareStatement("INSERT INTO items (item, seller, price, uuid, timestamp) VALUES (?, ?, ?, ?, ?)")) {
                stmt.setBytes(1, BukkitObjectSerializer.itemStackToBytes(item));
                stmt.setString(2, player.getName().toLowerCase());
                stmt.setInt(3, price);
                stmt.setString(4, player.getUniqueId().toString());
                stmt.setLong(5, System.currentTimeMillis());
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
                e.printStackTrace();
            }
        }
        return true;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String uuid = player.getUniqueId().toString();
        String name = player.getName().toLowerCase();

        try (PreparedStatement stmt = connection.prepareStatement("SELECT player FROM balance WHERE uuid = ? OR player = ?")) {
            stmt.setString(1, uuid);
            stmt.setString(2, name);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    try (PreparedStatement update = connection.prepareStatement("UPDATE balance SET uuid = ?, player = ? WHERE uuid = ? OR player = ?")) {
                        update.setString(1, uuid);
                        update.setString(2, name);
                        update.setString(3, uuid);
                        update.setString(4, name);
                        update.executeUpdate();
                    }
                } else {
                    try (PreparedStatement insert = connection.prepareStatement("INSERT INTO balance (player, uuid, amount) VALUES (?, ?, 0)")) {
                        insert.setString(1, name);
                        insert.setString(2, uuid);
                        insert.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        HumanEntity human = event.getWhoClicked();
        if (!(human instanceof Player player)) return;

        String title = event.getView().getTitle();

        // Вікно депозиту ігнорується, щоб гравці могли перетягувати туди предмети.
        if (title.contains(DEPOSIT_TITLE)) return;

        if (!title.contains(MARKET_TITLE) && !title.contains(SALES_TITLE) &&
                !title.contains(ORDERS_TITLE) && !title.contains(MY_ORDERS_TITLE) && !title.contains(DEPOT_TITLE)) return;

        event.setCancelled(true); // Заборона рухати предмети у звичайних меню

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.isEmpty() || clicked.getItemMeta() == null) return;
        ItemMeta meta = clicked.getItemMeta();

        if (meta.getPersistentDataContainer().has(pageKey, PersistentDataType.STRING)) {
            String pageAction = meta.getPersistentDataContainer().get(pageKey, PersistentDataType.STRING);
            int currentPage = getPageMeta(player, title);
            int newPage = "nextPage".equals(pageAction) ? currentPage + 1 : currentPage - 1;

            if (title.contains(ORDERS_TITLE)) openOrdersGUI(player, newPage);
            else if (title.contains(MY_ORDERS_TITLE)) openMyOrdersGUI(player, newPage);
            else if (title.contains(DEPOT_TITLE)) openDepotGUI(player, newPage);
            else if (title.contains(SALES_TITLE)) openSalesGUI(player, newPage);
            else {
                String playerName = meta.getPersistentDataContainer().get(playerNameKey, PersistentDataType.STRING);
                ItemStack material = null;
                if (meta.getPersistentDataContainer().has(materialKey, PersistentDataType.STRING)) {
                    material = new ItemStack(Objects.requireNonNull(Material.matchMaterial(meta.getPersistentDataContainer().get(materialKey, PersistentDataType.STRING))));
                }
                openMarketGUI(player, newPage, playerName, material);
            }
            return;
        }

        if (meta.getPersistentDataContainer().has(refreshKey, PersistentDataType.STRING)) {
            int page = getPageMeta(player, title);
            if (title.contains(ORDERS_TITLE)) openOrdersGUI(player, page);
            else if (title.contains(MY_ORDERS_TITLE)) openMyOrdersGUI(player, page);
            else if (title.contains(DEPOT_TITLE)) openDepotGUI(player, page);
            else {
                String playerName = meta.getPersistentDataContainer().get(playerNameKey, PersistentDataType.STRING);
                ItemStack material = null;
                if (meta.getPersistentDataContainer().has(materialKey, PersistentDataType.STRING)) {
                    material = new ItemStack(Objects.requireNonNull(Material.matchMaterial(meta.getPersistentDataContainer().get(materialKey, PersistentDataType.STRING))));
                }
                openMarketGUI(player, page, playerName, material);
            }
            return;
        }

        if (title.contains(MARKET_TITLE) && meta.getPersistentDataContainer().has(marketIdKey, PersistentDataType.INTEGER)) {
            handleMarketPurchase(player, meta, meta.getPersistentDataContainer().get(marketIdKey, PersistentDataType.INTEGER));
        } else if (title.contains(SALES_TITLE) && meta.getPersistentDataContainer().has(salesIdKey, PersistentDataType.INTEGER)) {
            handleSalesWithdraw(player, meta.getPersistentDataContainer().get(salesIdKey, PersistentDataType.INTEGER));
        } else if (title.contains(ORDERS_TITLE) && meta.getPersistentDataContainer().has(orderIdKey, PersistentDataType.INTEGER)) {
            initiateOrderFulfillment(player, meta.getPersistentDataContainer().get(orderIdKey, PersistentDataType.INTEGER));
        } else if (title.contains(MY_ORDERS_TITLE) && meta.getPersistentDataContainer().has(myOrderIdKey, PersistentDataType.INTEGER)) {
            handleOrderCancellation(player, meta.getPersistentDataContainer().get(myOrderIdKey, PersistentDataType.INTEGER));
        } else if (title.contains(DEPOT_TITLE) && meta.getPersistentDataContainer().has(depotIdKey, PersistentDataType.INTEGER)) {
            handleDepotClaim(player, meta.getPersistentDataContainer().get(depotIdKey, PersistentDataType.INTEGER));
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        // FIX: Жорстко перевіряємо, що закривається САМЕ вікно депозиту
        if (event.getView().getTitle().contains(DEPOSIT_TITLE) && player.hasMetadata("active_deposit_order")) {
            int orderId = player.getMetadata("active_deposit_order").get(0).asInt();
            player.removeMetadata("active_deposit_order", this);

            processOrderDeposit(player, orderId, event.getInventory());

            // Очищуємо вікно після обробки, щоб Bukkit випадково не викинув ці предмети на землю
            event.getInventory().clear();
        }
    }

    private int getPageMeta(Player player, String title) {
        String metaKey = title.contains(ORDERS_TITLE) ? "orders_page" :
                title.contains(MY_ORDERS_TITLE) ? "myorders_page" :
                        title.contains(DEPOT_TITLE) ? "depot_page" :
                                title.contains(SALES_TITLE) ? "sales_page" : "market_page";
        return player.hasMetadata(metaKey) ? player.getMetadata(metaKey).get(0).asInt() : 0;
    }

    private void initiateOrderFulfillment(Player player, int orderId) {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT uuid, material, count, price FROM orders WHERE id = ?")) {
            stmt.setInt(1, orderId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String ownerUuid = rs.getString("uuid");
                    if (ownerUuid.equals(player.getUniqueId().toString())) {
                        player.sendMessage(plain("Ви не можете виконати власне замовлення!", NamedTextColor.RED));
                        return;
                    }

                    Material reqMat = Material.matchMaterial(rs.getString("material"));
                    int reqCount = rs.getInt("count");

                    // Попередньо перевіряємо, чи взагалі є в інвентарі достатньо предметів
                    if (countMaterial(player, reqMat) < reqCount) {
                        player.sendMessage(plain("Недостатньо матеріалів! Вам потрібно мати мінімум " + reqCount + " " + reqMat.name(), NamedTextColor.RED));
                        return;
                    }

                    // FIX: Відкриваємо вікно з затримкою в 1 тік.
                    // Це дозволяє поточному івенту кліку безпечно завершитись і не створює багів-дюпів з інвентарем.
                    Bukkit.getScheduler().runTask(this, () -> {
                        Inventory depositGui = Bukkit.createInventory(null, 27, Component.text(DEPOSIT_TITLE));
                        player.setMetadata("active_deposit_order", new FixedMetadataValue(this, orderId));
                        player.openInventory(depositGui);
                    });
                } else {
                    player.sendMessage(plain("Це замовлення вже виконано або скасовано.", NamedTextColor.RED));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void processOrderDeposit(Player player, int orderId, Inventory depositGui) {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT uuid, material, count, price FROM orders WHERE id = ?")) {
            stmt.setInt(1, orderId);
            try (ResultSet rs = stmt.executeQuery()) {
                List<ItemStack> toRefund = new ArrayList<>();
                List<ItemStack> validItems = new ArrayList<>();

                // Якщо замовлення вже виконано/скасовано поки гравець кидав предмети - просто повертаємо все
                if (!rs.next()) {
                    for (ItemStack item : depositGui.getContents()) {
                        if (item != null && !item.isEmpty()) toRefund.add(item);
                    }
                    giveCarefully(player, toRefund.toArray(new ItemStack[0]));
                    player.sendMessage(plain("Замовлення було скасовано або виконано іншим гравцем!", NamedTextColor.RED));
                    return;
                }

                String ownerUuid = rs.getString("uuid");
                Material reqMat = Material.matchMaterial(rs.getString("material"));
                int reqCount = rs.getInt("count");
                int price = rs.getInt("price");

                int validCount = 0;

                // Перебираємо що гравець поклав у GUI
                for (ItemStack item : depositGui.getContents()) {
                    if (item == null || item.isEmpty()) continue;
                    if (item.getType() == reqMat) {
                        validItems.add(item);
                        validCount += item.getAmount();
                    } else {
                        toRefund.add(item); // Не той предмет - на повернення
                    }
                }

                if (validCount >= reqCount) {
                    // Зберігаємо оригінальний предмет зі збереженням NBT
                    ItemStack submittedItem = validItems.get(0).clone();
                    submittedItem.setAmount(reqCount);

                    // Розраховуємо залишок, який треба повернути гравцю (у випадку, якщо він поклав більше, ніж треба)
                    int remainingToDeduct = reqCount;
                    for (ItemStack item : validItems) {
                        if (remainingToDeduct > 0) {
                            if (item.getAmount() <= remainingToDeduct) {
                                remainingToDeduct -= item.getAmount();
                            } else {
                                item.setAmount(item.getAmount() - remainingToDeduct);
                                remainingToDeduct = 0;
                                toRefund.add(item);
                            }
                        } else {
                            toRefund.add(item);
                        }
                    }

                    // Переносимо замовлення у completed
                    try (PreparedStatement insert = connection.prepareStatement("INSERT INTO completed_orders (owner_uuid, fulfiller_name, item, is_refund, emerald_amount, timestamp) VALUES (?, ?, ?, ?, ?, ?)")) {
                        insert.setString(1, ownerUuid);
                        insert.setString(2, player.getName());
                        insert.setBytes(3, BukkitObjectSerializer.itemStackToBytes(submittedItem));
                        insert.setBoolean(4, false);
                        insert.setInt(5, 0);
                        insert.setLong(6, System.currentTimeMillis());
                        insert.executeUpdate();
                    }

                    // Видаляємо з orders
                    try (PreparedStatement del = connection.prepareStatement("DELETE FROM orders WHERE id = ?")) {
                        del.setInt(1, orderId);
                        del.executeUpdate();
                    }

                    modifyBalance(player.getUniqueId().toString(), player.getName(), price);
                    player.sendMessage(plain("Ви успішно виконали замовлення та отримали " + price + " ізумрудів!", NamedTextColor.GREEN));

                    Player owner = Bukkit.getPlayer(UUID.fromString(ownerUuid));
                    if (owner != null && owner.isOnline()) {
                        owner.sendMessage(plain("Ваше замовлення виконано! Заберіть предмет через /depot", NamedTextColor.GREEN));
                    }
                } else {
                    // Недостатньо предметів - скасовуємо та повертаємо все
                    toRefund.addAll(validItems);
                    player.sendMessage(plain("Ви поклали недостатньо предметів у вікно!", NamedTextColor.RED));
                }

                // Повертаємо всі зайві (або невірні) предмети в інвентар
                if (!toRefund.isEmpty()) {
                    giveCarefully(player, toRefund.toArray(new ItemStack[0]));
                }

            }
        } catch (Exception e) {
            e.printStackTrace();
            player.sendMessage(plain("Виникла помилка під час обробки вікна.", NamedTextColor.RED));
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
                        if (seller != null) updateBalanceByName(seller, price);

                        try (PreparedStatement del = connection.prepareStatement("DELETE FROM items WHERE id = ?")) {
                            del.setInt(1, clickedId);
                            del.executeUpdate();
                        }

                        player.sendMessage(Component.text("Ви купили предмет за " + price + " ізумрудів").color(NamedTextColor.GREEN));
                        sendMessageToPlayerByLowercaseName(seller, Component.translatable(dbItem.getType().translationKey())
                                .append(Component.text(" було продано!")).color(NamedTextColor.GREEN).decorate(TextDecoration.ITALIC));

                        openMarketGUI(player, getPageMeta(player, MARKET_TITLE), meta.getPersistentDataContainer().get(playerNameKey, PersistentDataType.STRING));
                    } else {
                        player.sendMessage(plain("Недостатньо ізумрудів", NamedTextColor.RED));
                    }
                } else {
                    player.sendMessage(Component.text("Цей предмет вже куплено або знято").color(NamedTextColor.RED));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
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
                    openSalesGUI(player, getPageMeta(player, SALES_TITLE));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleOrderCancellation(Player player, int orderId) {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT price FROM orders WHERE id = ?")) {
            stmt.setInt(1, orderId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    int price = rs.getInt("price");
                    try (PreparedStatement insert = connection.prepareStatement("INSERT INTO completed_orders (owner_uuid, fulfiller_name, is_refund, emerald_amount, timestamp) VALUES (?, 'Скасовано', 1, ?, ?)")) {
                        insert.setString(1, player.getUniqueId().toString());
                        insert.setInt(2, price);
                        insert.setLong(3, System.currentTimeMillis());
                        insert.executeUpdate();
                    }

                    try (PreparedStatement del = connection.prepareStatement("DELETE FROM orders WHERE id = ?")) {
                        del.setInt(1, orderId);
                        del.executeUpdate();
                    }

                    player.sendMessage(plain("Замовлення скасовано. Заберіть ізумруди в /depot", NamedTextColor.YELLOW));
                    openMyOrdersGUI(player, getPageMeta(player, MY_ORDERS_TITLE));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void handleDepotClaim(Player player, int depotId) {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT item, is_refund, emerald_amount FROM completed_orders WHERE id = ? AND owner_uuid = ?")) {
            stmt.setInt(1, depotId);
            stmt.setString(2, player.getUniqueId().toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    if (rs.getBoolean("is_refund")) {
                        int amount = rs.getInt("emerald_amount");
                        modifyBalance(player.getUniqueId().toString(), player.getName(), amount);
                        player.sendMessage(plain("Повернено " + amount + " ізумрудів на баланс.", NamedTextColor.GREEN));
                    } else {
                        giveCarefully(player, BukkitObjectSerializer.bytesToItemStack(rs.getBytes("item")));
                        player.sendMessage(plain("Ви забрали предмет зі складу.", NamedTextColor.GREEN));
                    }

                    try (PreparedStatement del = connection.prepareStatement("DELETE FROM completed_orders WHERE id = ?")) {
                        del.setInt(1, depotId);
                        del.executeUpdate();
                    }
                    openDepotGUI(player, getPageMeta(player, DEPOT_TITLE));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void openMarketGUI(Player player, int page) { openMarketGUI(player, page, null, null); }
    public void openMarketGUI(Player player, int page, String playerName) { openMarketGUI(player, page, playerName, null); }
    public void openMarketGUI(Player player, int page, ItemStack material) { openMarketGUI(player, page, null, material); }
    public void openMarketGUI(Player player, int page, String playerName, ItemStack material) {
        try {
            List<MarketItem> marketItems = new ArrayList<>();
            StringBuilder sql = new StringBuilder("SELECT id, item, seller, price FROM items WHERE 1=1");
            List<Object> params = new ArrayList<>();

            if (playerName != null) { sql.append(" AND seller = ?"); params.add(playerName); }
            if (material != null) { sql.append(" AND item LIKE ?"); params.add("%" + material.getType().getKey() + "%"); }

            sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
            params.add(ITEMS_PER_PAGE);
            params.add(page * ITEMS_PER_PAGE);

            try (PreparedStatement stmt = connection.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) stmt.setObject(i + 1, params.get(i));
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        int id = rs.getInt("id");
                        String seller = rs.getString("seller");
                        int price = rs.getInt("price");
                        ItemStack item = BukkitObjectSerializer.bytesToItemStack(rs.getBytes("item"));
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
            if (playerName != null) { countSql.append(" AND seller = ?"); countParams.add(playerName); }
            if (material != null) { countSql.append(" AND item LIKE ?"); countParams.add("%" + material.getType().getKey() + "%"); }

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
            e.printStackTrace();
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
            e.printStackTrace();
        }
    }

    public void openOrdersGUI(Player player, int page) {
        try {
            Inventory gui = Bukkit.createInventory(null, 54, Component.text(ORDERS_TITLE));
            long now = System.currentTimeMillis();

            try (PreparedStatement stmt = connection.prepareStatement("SELECT id, username, material, count, price, expire_date FROM orders WHERE expire_date > ? ORDER BY timestamp DESC LIMIT ? OFFSET ?")) {
                stmt.setLong(1, now);
                stmt.setInt(2, ITEMS_PER_PAGE);
                stmt.setInt(3, page * ITEMS_PER_PAGE);

                try (ResultSet rs = stmt.executeQuery()) {
                    int slot = 0;
                    while (rs.next()) {
                        int id = rs.getInt("id");
                        Material mat = Material.matchMaterial(rs.getString("material"));
                        if (mat == null) mat = Material.STONE;
                        int count = rs.getInt("count");
                        int daysLeft = (int) Math.max(1, (rs.getLong("expire_date") - now) / 86400000L);

                        ItemStack item = new ItemStack(mat, count);
                        ItemMeta meta = item.getItemMeta();
                        meta.getPersistentDataContainer().set(orderIdKey, PersistentDataType.INTEGER, id);

                        meta.lore(List.of(
                                Component.translatable(mat.translationKey()).append(Component.text(" x" + count, NamedTextColor.WHITE)),
                                plain("Замовник: " + rs.getString("username"), NamedTextColor.GRAY),
                                plain("Нагорода: " + rs.getInt("price") + " ізумрудів", NamedTextColor.YELLOW),
                                plain("Залишилось днів: " + daysLeft, NamedTextColor.RED),
                                plain("ЛКМ, щоб виконати замовлення", NamedTextColor.GREEN)
                        ));
                        item.setItemMeta(meta);
                        gui.setItem(slot++, item);
                    }
                }
            }
            if (page > 0) gui.setItem(46, createNavButton("Попередня сторінка", "previousPage", null, null));
            gui.setItem(49, createNavButton("Оновити", "refreshKey", null, null));
            gui.setItem(52, createNavButton("Наступна сторінка", "nextPage", null, null));
            player.openInventory(gui);
            player.setMetadata("orders_page", new org.bukkit.metadata.FixedMetadataValue(this, page));
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void openMyOrdersGUI(Player player, int page) {
        try {
            Inventory gui = Bukkit.createInventory(null, 54, Component.text(MY_ORDERS_TITLE));
            try (PreparedStatement stmt = connection.prepareStatement("SELECT id, material, count, price FROM orders WHERE uuid = ? ORDER BY timestamp DESC LIMIT ? OFFSET ?")) {
                stmt.setString(1, player.getUniqueId().toString());
                stmt.setInt(2, ITEMS_PER_PAGE);
                stmt.setInt(3, page * ITEMS_PER_PAGE);

                try (ResultSet rs = stmt.executeQuery()) {
                    int slot = 0;
                    while (rs.next()) {
                        int id = rs.getInt("id");
                        Material mat = Material.matchMaterial(rs.getString("material"));
                        if (mat == null) mat = Material.STONE;

                        ItemStack item = new ItemStack(mat, rs.getInt("count"));
                        ItemMeta meta = item.getItemMeta();
                        meta.getPersistentDataContainer().set(myOrderIdKey, PersistentDataType.INTEGER, id);
                        meta.lore(List.of(
                                plain("Ціна: " + rs.getInt("price") + " ізумрудів", NamedTextColor.YELLOW),
                                plain("ЛКМ щоб скасувати (гроші в депо)", NamedTextColor.RED)
                        ));
                        item.setItemMeta(meta);
                        gui.setItem(slot++, item);
                    }
                }
            }
            if (page > 0) gui.setItem(46, createNavButton("Попередня сторінка", "previousPage", null, null));
            gui.setItem(52, createNavButton("Наступна сторінка", "nextPage", null, null));
            player.openInventory(gui);
            player.setMetadata("myorders_page", new org.bukkit.metadata.FixedMetadataValue(this, page));
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void openDepotGUI(Player player, int page) {
        try {
            Inventory gui = Bukkit.createInventory(null, 54, Component.text(DEPOT_TITLE));
            try (PreparedStatement stmt = connection.prepareStatement("SELECT id, fulfiller_name, item, is_refund, emerald_amount, timestamp FROM completed_orders WHERE owner_uuid = ? ORDER BY timestamp DESC LIMIT ? OFFSET ?")) {
                stmt.setString(1, player.getUniqueId().toString());
                stmt.setInt(2, ITEMS_PER_PAGE);
                stmt.setInt(3, page * ITEMS_PER_PAGE);

                try (ResultSet rs = stmt.executeQuery()) {
                    int slot = 0;
                    while (rs.next()) {
                        int id = rs.getInt("id");
                        boolean isRefund = rs.getBoolean("is_refund");

                        ItemStack item;
                        if (isRefund) {
                            item = new ItemStack(Material.EMERALD, 1);
                            ItemMeta meta = item.getItemMeta();
                            meta.displayName(plain("Повернення: " + rs.getInt("emerald_amount") + " ізумрудів", NamedTextColor.GREEN));
                            meta.lore(List.of(plain("Причина: " + rs.getString("fulfiller_name"), NamedTextColor.GRAY)));
                            item.setItemMeta(meta);
                        } else {
                            item = BukkitObjectSerializer.bytesToItemStack(rs.getBytes("item"));
                            ItemMeta meta = item.getItemMeta();
                            List<Component> lore = meta.hasLore() ? Objects.requireNonNull(meta.lore()) : new ArrayList<>();
                            lore.add(plain("Виконав: " + rs.getString("fulfiller_name"), NamedTextColor.AQUA));
                            lore.add(plain("ЛКМ щоб забрати", NamedTextColor.GREEN));
                            meta.lore(lore);
                            item.setItemMeta(meta);
                        }

                        ItemMeta finalMeta = item.getItemMeta();
                        finalMeta.getPersistentDataContainer().set(depotIdKey, PersistentDataType.INTEGER, id);
                        item.setItemMeta(finalMeta);
                        gui.setItem(slot++, item);
                    }
                }
            }
            if (page > 0) gui.setItem(46, createNavButton("Попередня сторінка", "previousPage", null, null));
            gui.setItem(52, createNavButton("Наступна сторінка", "nextPage", null, null));
            player.openInventory(gui);
            player.setMetadata("depot_page", new org.bukkit.metadata.FixedMetadataValue(this, page));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private ItemStack createNavButton(String name, String action, @Nullable String playerName, @Nullable ItemStack material) {
        ItemStack item = new ItemStack("refreshKey".equals(action) ? Material.CLOCK : Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain(name, NamedTextColor.YELLOW));
        meta.getPersistentDataContainer().set("refreshKey".equals(action) ? refreshKey : pageKey, PersistentDataType.STRING, action);
        if (playerName != null) meta.getPersistentDataContainer().set(playerNameKey, PersistentDataType.STRING, playerName);
        if (material != null) meta.getPersistentDataContainer().set(materialKey, PersistentDataType.STRING, material.getType().getKey().toString());
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
        return item;
    }

    // --- Управління Балансом ---

    public int getBalance(Player player) {
        return getBalance(player.getUniqueId().toString(), player.getName().toLowerCase());
    }

    private int getBalance(String uuid, String name) {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT amount FROM balance WHERE uuid = ? OR player = ? LIMIT 1")) {
            stmt.setString(1, uuid);
            stmt.setString(2, name);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getInt("amount");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    private void modifyBalance(String uuid, String name, int amount) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO balance (player, uuid, amount) VALUES (?, ?, ?) ON CONFLICT(player) DO UPDATE SET amount = amount + excluded.amount, uuid = excluded.uuid")) {
            stmt.setString(1, name.toLowerCase());
            stmt.setString(2, uuid);
            stmt.setInt(3, amount);
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void setBalance(String uuid, String name, int amount) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO balance (player, uuid, amount) VALUES (?, ?, ?) ON CONFLICT(player) DO UPDATE SET amount = excluded.amount, uuid = excluded.uuid")) {
            stmt.setString(1, name.toLowerCase());
            stmt.setString(2, uuid);
            stmt.setInt(3, amount);
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void updateBalanceByName(String name, int amount) {
        try (PreparedStatement stmt = connection.prepareStatement("UPDATE balance SET amount = amount + ? WHERE player = ?")) {
            stmt.setInt(1, amount);
            stmt.setString(2, name.toLowerCase());
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public boolean withdrawBalance(Player player, int amount) {
        if (getBalance(player) >= amount) {
            modifyBalance(player.getUniqueId().toString(), player.getName(), -amount);
            return true;
        }
        return false;
    }

    private boolean playerExistsInDatabase(String playerName) {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT player FROM balance WHERE player = ? LIMIT 1")) {
            stmt.setString(1, playerName.toLowerCase());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    // --- Утиліти для інвентарю ---

    public int countMaterial(Player player, Material mat) {
        int total = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == mat) {
                total += item.getAmount();
            }
        }
        return total;
    }

    public void giveCarefully(Player player, ItemStack... items) {
        HashMap<Integer, ItemStack> leftovers = player.getInventory().addItem(items);
        if (!leftovers.isEmpty()) {
            for (ItemStack item : leftovers.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), item);
            }
            player.sendMessage(plain("Інвентар був повний, частину предметів скинуто під ноги.", NamedTextColor.YELLOW));
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