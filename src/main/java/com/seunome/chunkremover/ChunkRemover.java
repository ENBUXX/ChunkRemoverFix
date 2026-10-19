package com.seunome.chunkremover;

import net.milkbowl.vault.economy.Economy;
import me.gysta.economyshopgui.api.EconomyShopGUIHook;
import net.brcdev.shopgui.ShopGUIPlusApi;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import me.angeschossen.lands.api.LandsIntegration;
import me.angeschossen.lands.api.land.Area;
import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.TownyPermission;
import com.palmergames.bukkit.towny.utils.PlayerCacheUtil;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.sql.*;
import java.time.Duration;
import java.util.*;

public final class ChunkRemover extends JavaPlugin implements Listener, CommandExecutor {

    private final Map<UUID, Inventory> playerOreVaults = new HashMap<>();
    private final Map<UUID, Long> vaultExpirationTimes = new HashMap<>();

    private final Map<String, List<Location>> chunkHoppersMap = new HashMap<>();
    private final Map<Location, Location> hopperToChestMap = new HashMap<>();
    private final Map<Location, Inventory> hopperFilters = new HashMap<>();
    private final Map<Location, Boolean> hopperAutoSell = new HashMap<>();
    private final Map<Location, Double> hopperBalance = new HashMap<>();

    private final Set<UUID> pendingChestLink = new HashSet<>();
    private final Map<UUID, Location> playerPendingHopperLoc = new HashMap<>();

    private Connection dbConnection;
    private static final long EXPIRATION_TIME_MS = 24 * 60 * 60 * 1000L;

    private static Economy econ = null;
    private boolean economyShopGUIEnabled = false;
    private boolean shopGUIPlusEnabled = false;
    private boolean griefPreventionEnabled = false;
    private boolean worldGuardEnabled = false;
    private boolean townyEnabled = false;
    private LandsIntegration landsIntegration;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);

        if (getCommand("removedor") != null) getCommand("removedor").setExecutor(this);
        if (getCommand("minerios") != null) getCommand("minerios").setExecutor(this);

        setupVaultEconomy();
        setupProtectionAndShopHooks();
        initDatabase();
        loadDatabaseData();
        startReminderTask();

        getLogger().info("ChunkRemover 1.21+ Habilitado com Sucesso!");
    }

    @Override
    public void onDisable() {
        saveDatabaseData();
        closeDatabase();
    }

    private void initDatabase() {
        try {
            Class.forName("org.sqlite.JDBC");
            File dbFile = new File(getDataFolder(), "database.db");
            dbConnection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

            Statement stmt = dbConnection.createStatement();
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS vaults (uuid VARCHAR(36) PRIMARY KEY, expire BIGINT);");
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS hoppers (id INTEGER PRIMARY KEY AUTOINCREMENT, world VARCHAR(64), x INT, y INT, z INT, chest_x INT, chest_y INT, chest_z INT, autosell BOOLEAN, balance DOUBLE);");
            stmt.close();
        } catch (Exception e) {
            getLogger().severe("Erro ao inicializar o banco SQLite: " + e.getMessage());
        }
    }

    private void loadDatabaseData() {
        try {
            Statement stmt = dbConnection.createStatement();
            ResultSet rsVaults = stmt.executeQuery("SELECT * FROM vaults;");
            while (rsVaults.next()) {
                UUID uuid = UUID.fromString(rsVaults.getString("uuid"));
                long expire = rsVaults.getLong("expire");
                vaultExpirationTimes.put(uuid, expire);

                String title = getConfig().getString("messages.ores-gui-title", "&8Seus Minérios Coletados");
                Inventory inv = Bukkit.createInventory(null, 54, formatMsg(title));
                playerOreVaults.put(uuid, inv);
            }
            rsVaults.close();

            ResultSet rsHoppers = stmt.executeQuery("SELECT * FROM hoppers;");
            while (rsHoppers.next()) {
                String worldName = rsHoppers.getString("world");
                if (Bukkit.getWorld(worldName) == null) continue;

                Location hLoc = new Location(Bukkit.getWorld(worldName), rsHoppers.getInt("x"), rsHoppers.getInt("y"), rsHoppers.getInt("z"));
                Chunk chunk = hLoc.getChunk();
                String chunkKey = chunk.getWorld().getName() + ";" + chunk.getX() + ";" + chunk.getZ();

                chunkHoppersMap.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(hLoc);

                int cx = rsHoppers.getInt("chest_x");
                if (!rsHoppers.wasNull()) {
                    Location cLoc = new Location(Bukkit.getWorld(worldName), cx, rsHoppers.getInt("chest_y"), rsHoppers.getInt("chest_z"));
                    hopperToChestMap.put(hLoc, cLoc);
                }

                hopperAutoSell.put(hLoc, rsHoppers.getBoolean("autosell"));
                hopperBalance.put(hLoc, rsHoppers.getDouble("balance"));

                Inventory filterInv = Bukkit.createInventory(null, 9, formatMsg(getConfig().getString("hopper.filter-gui-title")));
                hopperFilters.put(hLoc, filterInv);
            }
            rsHoppers.close();
            stmt.close();
        } catch (Exception e) {
            getLogger().severe("Erro ao carregar dados do SQLite: " + e.getMessage());
        }
    }

    private void saveDatabaseData() {
        try {
            PreparedStatement psVault = dbConnection.prepareStatement("REPLACE INTO vaults(uuid, expire) VALUES(?, ?);");
            for (UUID uuid : vaultExpirationTimes.keySet()) {
                psVault.setString(1, uuid.toString());
                psVault.setLong(2, vaultExpirationTimes.get(uuid));
                psVault.addBatch();
            }
            psVault.executeBatch();
            psVault.close();

            Statement clearStmt = dbConnection.createStatement();
            clearStmt.executeUpdate("DELETE FROM hoppers;");
            clearStmt.close();

            PreparedStatement psHopper = dbConnection.prepareStatement("INSERT INTO hoppers(world, x, y, z, chest_x, chest_y, chest_z, autosell, balance) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?);");
            for (Map.Entry<Location, Boolean> entry : hopperAutoSell.entrySet()) {
                Location hLoc = entry.getKey();
                Location cLoc = hopperToChestMap.get(hLoc);

                psHopper.setString(1, hLoc.getWorld().getName());
                psHopper.setInt(2, hLoc.getBlockX());
                psHopper.setInt(3, hLoc.getBlockY());
                psHopper.setInt(4, hLoc.getBlockZ());

                if (cLoc != null) {
                    psHopper.setInt(5, cLoc.getBlockX());
                    psHopper.setInt(6, cLoc.getBlockY());
                    psHopper.setInt(7, cLoc.getBlockZ());
                } else {
                    psHopper.setNull(5, Types.INTEGER);
                    psHopper.setNull(6, Types.INTEGER);
                    psHopper.setNull(7, Types.INTEGER);
                }

                psHopper.setBoolean(8, entry.getValue());
                psHopper.setDouble(9, hopperBalance.getOrDefault(hLoc, 0.0));
                psHopper.addBatch();
            }
            psHopper.executeBatch();
            psHopper.close();
        } catch (Exception e) {
            getLogger().severe("Erro ao salvar dados no SQLite: " + e.getMessage());
        }
    }

    private void closeDatabase() {
        try {
            if (dbConnection != null && !dbConnection.isClosed()) {
                dbConnection.close();
            }
        } catch (SQLException ignored) {}
    }

    private boolean setupVaultEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        econ = rsp.getProvider();
        return econ != null;
    }

    private void setupProtectionAndShopHooks() {
        if (Bukkit.getPluginManager().getPlugin("EconomyShopGUI") != null) economyShopGUIEnabled = true;
        if (Bukkit.getPluginManager().getPlugin("ShopGUIPlus") != null) shopGUIPlusEnabled = true;
        if (Bukkit.getPluginManager().getPlugin("GriefPrevention") != null) griefPreventionEnabled = true;
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") != null) worldGuardEnabled = true;
        if (Bukkit.getPluginManager().getPlugin("Towny") != null) townyEnabled = true;
        if (Bukkit.getPluginManager().getPlugin("Lands") != null) landsIntegration = LandsIntegration.of(this);
    }

    private void startReminderTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                Iterator<Map.Entry<UUID, Long>> iterator = vaultExpirationTimes.entrySet().iterator();

                while (iterator.hasNext()) {
                    Map.Entry<UUID, Long> entry = iterator.next();
                    UUID uuid = entry.getKey();
                    long expireTime = entry.getValue();

                    if (now >= expireTime) {
                        playerOreVaults.remove(uuid);
                        iterator.remove();

                        Player onlinePlayer = Bukkit.getPlayer(uuid);
                        if (onlinePlayer != null) {
                            onlinePlayer.sendMessage(formatMsg(getConfig().getString("messages.vault-expired-alert")));
                            onlinePlayer.playSound(onlinePlayer.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 0.5f);
                        }
                    } else {
                        Player onlinePlayer = Bukkit.getPlayer(uuid);
                        if (onlinePlayer != null && hasItemsInVault(uuid)) {
                            long remainingMillis = expireTime - now;
                            String msg = getConfig().getString("messages.vault-reminder-periodic")
                                    .replace("%time%", formatTime(remainingMillis));
                            onlinePlayer.sendMessage(formatMsg(msg));
                            onlinePlayer.playSound(onlinePlayer.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5f, 1.2f);
                        }
                    }
                }
            }
        }.runTaskTimer(this, 1200L, 6000L);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (vaultExpirationTimes.containsKey(uuid) && hasItemsInVault(uuid)) {
            long now = System.currentTimeMillis();
            long expireTime = vaultExpirationTimes.get(uuid);

            if (now < expireTime) {
                long remainingMillis = expireTime - now;

                new BukkitRunnable() {
                    @Override
                    public void run() {
                        player.sendMessage(formatMsg(getConfig().getString("messages.vault-reminder-join")));
                        player.sendMessage(formatMsg("&eTempo restante antes da exclusão automática: &c" + formatTime(remainingMillis)));
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1f);
                    }
                }.runTaskLater(this, 40L);
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("removedor")) {
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission(getConfig().getString("settings.admin-permission", "chunkremover.admin"))) {
                    sender.sendMessage(formatMsg(getConfig().getString("messages.no-permission")));
                    return true;
                }
                reloadConfig();
                saveDatabaseData();
                loadDatabaseData();
                sender.sendMessage(formatMsg(getConfig().getString("messages.reload-success")));
                return true;
            }

            if (args.length >= 3 && args[0].equalsIgnoreCase("give")) {
                if (!sender.hasPermission(getConfig().getString("settings.admin-permission", "chunkremover.admin"))) {
                    sender.sendMessage(formatMsg(getConfig().getString("messages.no-permission")));
                    return true;
                }

                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(formatMsg(getConfig().getString("messages.invalid-player")));
                    return true;
                }

                String type = args[2].toLowerCase();
                int amount = 1;
                if (args.length >= 4) {
                    try { amount = Integer.parseInt(args[3]); } catch (NumberFormatException ignored) {}
                }

                ItemStack itemToGive = null;
                if (type.equals("1")) itemToGive = createGuiItem("items.chunk_1");
                else if (type.equals("4")) itemToGive = createGuiItem("items.chunk_4");
                else if (type.equals("8")) itemToGive = createGuiItem("items.chunk_8");
                else if (type.equals("funil") || type.equals("hopper")) itemToGive = createChunkHopperItem();

                if (itemToGive == null) {
                    sender.sendMessage(formatMsg(getConfig().getString("messages.invalid-type")));
                    return true;
                }

                itemToGive.setAmount(amount);
                target.getInventory().addItem(itemToGive);

                String msgAdmin = getConfig().getString("messages.give-success")
                        .replace("%amount%", String.valueOf(amount))
                        .replace("%type%", type)
                        .replace("%player%", target.getName());
                sender.sendMessage(formatMsg(msgAdmin));

                String msgTarget = getConfig().getString("messages.give-received")
                        .replace("%amount%", String.valueOf(amount))
                        .replace("%type%", type);
                target.sendMessage(formatMsg(msgTarget));
                return true;
            }

            if (!(sender instanceof Player player)) {
                sender.sendMessage("Apenas jogadores podem abrir a GUI.");
                return true;
            }

            if (!player.hasPermission(getConfig().getString("settings.admin-permission", "chunkremover.admin"))) {
                player.sendMessage(formatMsg(getConfig().getString("messages.no-permission")));
                return true;
            }

            openAdminGUI(player);
            return true;
        }

        if (command.getName().equalsIgnoreCase("minerios")) {
            if (!(sender instanceof Player player)) return true;

            if (!vaultExpirationTimes.containsKey(player.getUniqueId()) || !hasItemsInVault(player.getUniqueId())) {
                player.sendMessage(formatMsg(getConfig().getString("messages.no-ores-to-claim")));
                return true;
            }

            openOreVault(player);
            return true;
        }

        return false;
    }

    private boolean hasItemsInVault(UUID uuid) {
        Inventory vault = playerOreVaults.get(uuid);
        if (vault == null) return false;

        for (ItemStack item : vault.getContents()) {
            if (item != null && item.getType() != Material.AIR) return true;
        }
        return false;
    }

    private void openAdminGUI(Player player) {
        Component titleComponent = LegacyComponentSerializer.legacyAmpersand().deserialize("&8Pega-Itens Especiais (Admin)");
        Inventory inv = Bukkit.createInventory(null, 27, titleComponent);

        ItemStack glassFiller = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta glassMeta = glassFiller.getItemMeta();
        if (glassMeta != null) {
            glassMeta.displayName(Component.text(" "));
            glassFiller.setItemMeta(glassMeta);
        }

        for (int i = 0; i < 27; i++) inv.setItem(i, glassFiller);

        inv.setItem(10, createGuiItem("items.chunk_1"));
        inv.setItem(12, createGuiItem("items.chunk_4"));
        inv.setItem(14, createGuiItem("items.chunk_8"));
        inv.setItem(16, createChunkHopperItem());

        player.openInventory(inv);
    }

    private ItemStack createChunkHopperItem() {
        ItemStack hopper = new ItemStack(Material.HOPPER);
        ItemMeta meta = hopper.getItemMeta();
        if (meta != null) {
            meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize("&6&lFunil de Chunk"));
            List<Component> lore = new ArrayList<>();
            lore.add(LegacyComponentSerializer.legacyAmpersand().deserialize("&7Coleta todos os itens dropados na chunk."));
            lore.add(LegacyComponentSerializer.legacyAmpersand().deserialize("&8&m------------------"));
            lore.add(LegacyComponentSerializer.legacyAmpersand().deserialize("&e[!] Mantenha-se &cAGACHADO (SHIFT) &epara colocar!"));
            meta.lore(lore);

            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            hopper.setItemMeta(meta);
        }
        return hopper;
    }

    private ItemStack createGuiItem(String path) {
        Material mat = Material.valueOf(getConfig().getString(path + ".material", "TNT"));
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(getConfig().getString(path + ".name", "")));
            List<Component> lore = new ArrayList<>();
            for (String s : getConfig().getStringList(path + ".lore")) {
                lore.add(LegacyComponentSerializer.legacyAmpersand().deserialize(s));
            }
            lore.add(LegacyComponentSerializer.legacyAmpersand().deserialize("&8&m------------------"));
            lore.add(LegacyComponentSerializer.legacyAmpersand().deserialize("&e[!] Mantenha-se &cAGACHADO (SHIFT) &epara ativar!"));

            meta.lore(lore);

            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

            item.setItemMeta(meta);
        }
        return item;
    }

    private Inventory getOrCreateOreVault(Player player) {
        return playerOreVaults.computeIfAbsent(player.getUniqueId(), uuid -> {
            String titleStr = getConfig().getString("messages.ores-gui-title", "&8Seus Minérios Coletados");
            Component title = LegacyComponentSerializer.legacyAmpersand().deserialize(titleStr);
            return Bukkit.createInventory(null, 54, title);
        });
    }

    private void openOreVault(Player player) {
        Inventory vault = getOrCreateOreVault(player);
        player.openInventory(vault);
    }

    public void openHopperControlGUI(Player player, Location hopperLoc) {
        Component title = LegacyComponentSerializer.legacyAmpersand().deserialize("&8Gerenciador do Funil");
        Inventory inv = Bukkit.createInventory(null, 9, title);

        ItemStack filterItem = new ItemStack(Material.HOPPER);
        ItemMeta fMeta = filterItem.getItemMeta();
        fMeta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize("&eFiltro de Itens"));
        fMeta.lore(List.of(LegacyComponentSerializer.legacyAmpersand().deserialize("&7Clique para configurar os itens coletados.")));
        filterItem.setItemMeta(fMeta);
        inv.setItem(1, filterItem);

        ItemStack chestItem = new ItemStack(Material.CHEST);
        ItemMeta cMeta = chestItem.getItemMeta();
        cMeta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize("&6Vincular Baú Remoto"));
        Location linkedChest = hopperToChestMap.get(hopperLoc);
        String chestStatus = (linkedChest != null) ? "&aVinculado em: " + linkedChest.getBlockX() + ", " + linkedChest.getBlockY() + ", " + linkedChest.getBlockZ() : "&cSem baú vinculado";
        cMeta.lore(List.of(
                LegacyComponentSerializer.legacyAmpersand().deserialize("&7Clique para selecionar um baú no mapa."),
                LegacyComponentSerializer.legacyAmpersand().deserialize(chestStatus)
        ));
        chestItem.setItemMeta(cMeta);
        inv.setItem(3, chestItem);

        boolean isAutoSell = hopperAutoSell.getOrDefault(hopperLoc, false);
        double balance = hopperBalance.getOrDefault(hopperLoc, 0.0);
        ItemStack sellItem = new ItemStack(isAutoSell ? Material.GOLD_INGOT : Material.IRON_INGOT);
        ItemMeta sMeta = sellItem.getItemMeta();
        sMeta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize("&aVenda Automática: " + (isAutoSell ? "&a[ATIVADA]" : "&c[DESATIVADA]")));
        sMeta.lore(List.of(
                LegacyComponentSerializer.legacyAmpersand().deserialize("&7Clique Esquerdo: Ativar/Desativar"),
                LegacyComponentSerializer.legacyAmpersand().deserialize("&7Shift + Clique: Resgatar Dinheiro Farmado"),
                LegacyComponentSerializer.legacyAmpersand().deserialize("&eSaldo Acumulado: &a$" + String.format("%.2f", balance))
        ));
        sellItem.setItemMeta(sMeta);
        inv.setItem(5, sellItem);

        boolean isLoaded = hopperLoc.getChunk().isLoaded();
        ItemStack statusItem = new ItemStack(isLoaded ? Material.LIME_DYE : Material.RED_DYE);
        ItemMeta stMeta = statusItem.getItemMeta();
        stMeta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize("&eStatus da Chunk"));
        stMeta.lore(List.of(
                LegacyComponentSerializer.legacyAmpersand().deserialize(isLoaded ? "&aChunk Carregada (Ativa)" : "&cChunk Descarregada (Pausada)")
        ));
        statusItem.setItemMeta(stMeta);
        inv.setItem(7, statusItem);

        player.openInventory(inv);
        playerPendingHopperLoc.put(player.getUniqueId(), hopperLoc);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getClickedInventory() == null) return;
        Player player = (Player) event.getWhoClicked();

        if (event.getView().title().equals(LegacyComponentSerializer.legacyAmpersand().deserialize("&8Pega-Itens Especiais (Admin)"))) {
            event.setCancelled(true);
            ItemStack current = event.getCurrentItem();
            if (current != null && current.hasItemMeta()) {
                player.getInventory().addItem(current.clone());
                player.sendMessage(formatMsg("&aVocê pegou o item!"));
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
            }
            return;
        }

        if (event.getView().title().equals(LegacyComponentSerializer.legacyAmpersand().deserialize("&8Gerenciador do Funil"))) {
            event.setCancelled(true);
            Location hLoc = playerPendingHopperLoc.get(player.getUniqueId());
            if (hLoc == null) return;

            int slot = event.getSlot();

            if (slot == 1) {
                Inventory filterInv = hopperFilters.computeIfAbsent(hLoc, loc -> Bukkit.createInventory(null, 9, formatMsg(getConfig().getString("hopper.filter-gui-title"))));
                player.openInventory(filterInv);
            }

            if (slot == 3) {
                player.closeInventory();
                pendingChestLink.add(player.getUniqueId());
                player.sendMessage(formatMsg("&a[!] Clique com o botão direito no baú que deseja vincular a este funil!"));
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
            }

            if (slot == 5) {
                if (event.isShiftClick()) {
                    double balance = hopperBalance.getOrDefault(hLoc, 0.0);
                    if (balance > 0) {
                        hopperBalance.put(hLoc, 0.0);
                        if (econ != null) {
                            econ.depositPlayer(player, balance);
                        } else {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "eco give " + player.getName() + " " + balance);
                        }
                        player.sendMessage(formatMsg("&a[!] Você resgatou &e$" + String.format("%.2f", balance) + " &asaldos do funil!"));
                        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                        saveDatabaseData();
                    } else {
                        player.sendMessage(formatMsg("&cNão há saldo acumulado para resgatar."));
                    }
                } else {
                    boolean currentStatus = hopperAutoSell.getOrDefault(hLoc, false);
                    hopperAutoSell.put(hLoc, !currentStatus);
                    player.sendMessage(formatMsg("&eAuto-Sell alterado para: " + (!currentStatus ? "&a[ATIVADA]" : "&c[DESATIVADA]")));
                    openHopperControlGUI(player, hLoc);
                    saveDatabaseData();
                }
            }
        }
    }

    @EventHandler
    public void onFilterClick(InventoryClickEvent event) {
        if (event.getClickedInventory() == null) return;
        
        if (event.getView().title().equals(LegacyComponentSerializer.legacyAmpersand().deserialize(getConfig().getString("hopper.filter-gui-title")))) {
            Player player = (Player) event.getWhoClicked();
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.5f);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItemInHand();

        if (!item.hasItemMeta() || item.getItemMeta().displayName() == null) return;

        String displayName = LegacyComponentSerializer.legacyAmpersand().serialize(item.getItemMeta().displayName());

        if (displayName.equals(formatMsgRaw("&6&lFunil de Chunk")) && item.getType() == Material.HOPPER) {
            if (!player.isSneaking()) {
                event.setCancelled(true);
                player.sendMessage(formatMsg(getConfig().getString("messages.must-be-sneaking")));
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                return;
            }

            Chunk chunk = event.getBlockPlaced().getChunk();
            String chunkKey = chunk.getWorld().getName() + ";" + chunk.getX() + ";" + chunk.getZ();

            List<Location> hoppersInChunk = chunkHoppersMap.computeIfAbsent(chunkKey, k -> new ArrayList<>());
            int maxHoppers = getConfig().getInt("gameplay.max-hoppers-per-chunk", 1);

            if (hoppersInChunk.size() >= maxHoppers) {
                event.setCancelled(true);
                String msg = getConfig().getString("hopper.max-reached", "&cLimite atingido!").replace("%max%", String.valueOf(maxHoppers));
                player.sendMessage(formatMsg(msg));
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                return;
            }

            Location hopperLoc = event.getBlockPlaced().getLocation();
            hoppersInChunk.add(hopperLoc);

            Inventory filterInv = Bukkit.createInventory(null, 9, formatMsg(getConfig().getString("hopper.filter-gui-title")));
            hopperFilters.put(hopperLoc, filterInv);

            player.sendMessage(formatMsg(getConfig().getString("hopper.placed-success")));
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 1f, 1.2f);
            saveDatabaseData();
            return;
        }

        int radius = 0;
        if (displayName.equals(formatMsgRaw(getConfig().getString("items.chunk_1.name")))) {
            radius = getConfig().getInt("items.chunk_1.radius-chunks", 1);
        } else if (displayName.equals(formatMsgRaw(getConfig().getString("items.chunk_4.name")))) {
            radius = getConfig().getInt("items.chunk_4.radius-chunks", 2);
        } else if (displayName.equals(formatMsgRaw(getConfig().getString("items.chunk_8.name")))) {
            radius = getConfig().getInt("items.chunk_8.radius-chunks", 3);
        }

        if (radius > 0) {
            if (!player.isSneaking()) {
                event.setCancelled(true);
                player.sendMessage(formatMsg(getConfig().getString("messages.must-be-sneaking")));
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                return;
            }

            event.setCancelled(true);
            Location targetLoc = event.getBlockPlaced().getLocation();

            if (hasProtectedAreaInRadius(player, targetLoc, radius)) {
                player.sendMessage(formatMsg(getConfig().getString("messages.claim-protected")));
                return;
            }

            if (item.getAmount() > 1) {
                item.setAmount(item.getAmount() - 1);
            } else {
                player.getInventory().setItemInMainHand(null);
            }

            int delaySeconds = getConfig().getInt("settings.delay-seconds", 10);
            final int finalRadius = radius;

            new BukkitRunnable() {
                int count = delaySeconds;

                @Override
                public void run() {
                    if (count > 0) {
                        String titleMsg = getConfig().getString("messages.countdown-title", "&c&lREMOVEDOR ATIVADO");
                        String subMsg = getConfig().getString("messages.countdown-subtitle", "&eDetonando em %seconds%s...")
                                .replace("%seconds%", String.valueOf(count));

                        Title title = Title.title(
                                formatMsg(titleMsg),
                                formatMsg(subMsg),
                                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(800), Duration.ofMillis(100))
                        );
                        player.showTitle(title);

                        spawnChunkParticles(targetLoc, finalRadius);
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.2f);
                        count--;
                    } else {
                        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f);
                        startLayeredChunkRemoval(player, targetLoc, finalRadius);
                        cancel();
                    }
                }
            }.runTaskTimer(this, 0L, 20L);
        }
    }

    private void startLayeredChunkRemoval(Player player, Location centerLoc, int radius) {
        Chunk centerChunk = centerLoc.getChunk();
        Inventory oreVault = getOrCreateOreVault(player);

        int cX = centerChunk.getX();
        int cZ = centerChunk.getZ();

        List<Chunk> chunksToClear = new ArrayList<>();
        for (int x = -radius + 1; x <= radius - 1; x++) {
            for (int z = -radius + 1; z <= radius - 1; z++) {
                Chunk targetChunk = centerChunk.getWorld().getChunkAt(cX + x, cZ + z);
                Location sampleLoc = targetChunk.getBlock(8, 64, 8).getLocation();
                if (!isLocationProtectedFromPlayer(player, sampleLoc)) {
                    chunksToClear.add(targetChunk);
                }
            }
        }

        int maxY = centerLoc.getWorld().getMaxHeight() - 1;
        int minY = centerLoc.getWorld().getMinHeight();
        int ticksPerLayer = getConfig().getInt("performance.ticks-per-layer", 2);
        List<String> protectedList = getConfig().getStringList("protected-blocks");

        new BukkitRunnable() {
            int currentY = maxY;

            @Override
            public void run() {
                if (currentY < minY) {
                    vaultExpirationTimes.put(player.getUniqueId(), System.currentTimeMillis() + EXPIRATION_TIME_MS);
                    saveDatabaseData();

                    player.sendMessage(formatMsg(getConfig().getString("messages.success")));
                    player.sendMessage(formatMsg("&e[!] Seus minérios foram salvos! Você tem &c24 horas&e para resgatá-los via &c/minerios&e antes de serem apagados."));
                    cancel();
                    return;
                }

                for (Chunk chunk : chunksToClear) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            Block block = chunk.getBlock(x, currentY, z);
                            Material type = block.getType();

                            if (type == Material.AIR || protectedList.contains(type.name())) {
                                continue;
                            }

                            if (isOre(type)) {
                                Collection<ItemStack> drops = block.getDrops();
                                for (ItemStack drop : drops) {
                                    oreVault.addItem(drop);
                                }
                            }

                            block.setType(Material.AIR, false);
                        }
                    }
                }

                currentY--;
            }
        }.runTaskTimer(this, 0L, (long) ticksPerLayer);
    }

    private void spawnChunkParticles(Location centerLoc, int radius) {
        Chunk centerChunk = centerLoc.getChunk();
        int cX = centerChunk.getX();
        int cZ = centerChunk.getZ();

        for (int x = -radius + 1; x <= radius - 1; x++) {
            for (int z = -radius + 1; z <= radius - 1; z++) {
                Chunk chunk = centerLoc.getWorld().getChunkAt(cX + x, cZ + z);
                int minX = chunk.getX() * 16;
                int minZ = chunk.getZ() * 16;
                int playerY = centerLoc.getBlockY();

                centerLoc.getWorld().spawnParticle(Particle.FLAME, minX, playerY + 1, minZ, 5);
                centerLoc.getWorld().spawnParticle(Particle.FLAME, minX + 16, playerY + 1, minZ, 5);
                centerLoc.getWorld().spawnParticle(Particle.FLAME, minX, playerY + 1, minZ + 16, 5);
                centerLoc.getWorld().spawnParticle(Particle.FLAME, minX + 16, playerY + 1, minZ + 16, 5);
            }
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return;

        Block clicked = event.getClickedBlock();
        Player player = event.getPlayer();

        if (pendingChestLink.contains(player.getUniqueId()) && clicked.getState() instanceof Container) {
            event.setCancelled(true);
            Location hLoc = playerPendingHopperLoc.get(player.getUniqueId());
            if (hLoc != null) {
                hopperToChestMap.put(hLoc, clicked.getLocation());
                player.sendMessage(formatMsg(getConfig().getString("hopper.linked-success")));
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
                saveDatabaseData();
            }
            pendingChestLink.remove(player.getUniqueId());
            return;
        }

        if (clicked.getType() == Material.HOPPER) {
            Chunk chunk = clicked.getChunk();
            String chunkKey = chunk.getWorld().getName() + ";" + chunk.getX() + ";" + chunk.getZ();

            List<Location> hoppers = chunkHoppersMap.get(chunkKey);
            if (hoppers != null) {
                for (Location hLoc : hoppers) {
                    if (hLoc.equals(clicked.getLocation())) {
                        event.setCancelled(true);
                        openHopperControlGUI(player, clicked.getLocation());
                        return;
                    }
                }
            }
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();

        if (block.getType() == Material.HOPPER) {
            Chunk chunk = block.getChunk();
            String chunkKey = chunk.getWorld().getName() + ";" + chunk.getX() + ";" + chunk.getZ();

            List<Location> hoppers = chunkHoppersMap.get(chunkKey);
            if (hoppers != null) {
                Iterator<Location> iterator = hoppers.iterator();
                while (iterator.hasNext()) {
                    Location hLoc = iterator.next();
                    if (hLoc.equals(block.getLocation())) {

                        if (isLocationProtectedFromPlayer(player, block.getLocation())) {
                            event.setCancelled(true);
                            player.sendMessage(formatMsg(getConfig().getString("hopper.not-owner")));
                            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                            return;
                        }

                        iterator.remove();
                        hopperToChestMap.remove(hLoc);
                        hopperFilters.remove(hLoc);
                        hopperAutoSell.remove(hLoc);
                        hopperBalance.remove(hLoc);

                        player.sendMessage(formatMsg(getConfig().getString("hopper.removed")));
                        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_BREAK, 1f, 1f);
                        saveDatabaseData();
                        break;
                    }
                }
            }
        }
    }

    @EventHandler
    public void onItemSpawn(ItemSpawnEvent event) {
        Item itemEntity = event.getEntity();
        Chunk chunk = itemEntity.getLocation().getChunk();

        if (!chunk.isLoaded()) return;

        String chunkKey = chunk.getWorld().getName() + ";" + chunk.getX() + ";" + chunk.getZ();
        List<Location> hoppers = chunkHoppersMap.get(chunkKey);

        if (hoppers != null && !hoppers.isEmpty()) {
            Location hopperLoc = hoppers.get(0);
            ItemStack itemStack = itemEntity.getItemStack();

            Inventory filterInv = hopperFilters.get(hopperLoc);
            if (filterInv != null && !isFilterEmpty(filterInv)) {
                if (!matchesFilter(filterInv, itemStack.getType())) return;
            }

            boolean autoSellActive = hopperAutoSell.getOrDefault(hopperLoc, false);
            if (autoSellActive) {
                double unitPrice = getItemSellPrice(itemStack);
                double totalPrice = unitPrice * itemStack.getAmount();

                double currentBalance = hopperBalance.getOrDefault(hopperLoc, 0.0);
                hopperBalance.put(hopperLoc, currentBalance + totalPrice);

                event.setCancelled(true);
                return;
            }

            Location chestLoc = hopperToChestMap.get(hopperLoc);
            if (chestLoc != null && chestLoc.getBlock().getState() instanceof Container container) {
                Inventory chestInv = container.getInventory();
                HashMap<Integer, ItemStack> leftover = chestInv.addItem(itemStack);

                if (leftover.isEmpty()) {
                    event.setCancelled(true);
                } else {
                    itemEntity.setItemStack(leftover.get(0));
                }
            }
        }
    }

    private double getItemSellPrice(ItemStack item) {
        boolean useCustom = getConfig().getBoolean("economy.use-custom-prices", true);

        if (!useCustom) {
            if (economyShopGUIEnabled) {
                try {
                    double price = EconomyShopGUIHook.getItemSellPrice(item);
                    if (price > 0) return price;
                } catch (Exception ignored) {}
            }

            if (shopGUIPlusEnabled) {
                try {
                    double price = ShopGUIPlusApi.getItemSellPrice(item);
                    if (price > 0) return price;
                } catch (Exception ignored) {}
            }
        }

        String matName = item.getType().name();
        if (getConfig().contains("economy.prices." + matName)) {
            return getConfig().getDouble("economy.prices." + matName);
        }

        return getConfig().getDouble("economy.prices.DEFAULT_PRICE", 1.0);
    }

    private boolean isFilterEmpty(Inventory filterInv) {
        for (ItemStack item : filterInv.getContents()) {
            if (item != null && item.getType() != Material.AIR) return false;
        }
        return true;
    }

    private boolean matchesFilter(Inventory filterInv, Material mat) {
        for (ItemStack item : filterInv.getContents()) {
            if (item != null && item.getType() == mat) return true;
        }
        return false;
    }

    private boolean hasProtectedAreaInRadius(Player player, Location centerLoc, int radius) {
        Chunk centerChunk = centerLoc.getChunk();
        int cX = centerChunk.getX();
        int cZ = centerChunk.getZ();

        for (int x = -radius + 1; x <= radius - 1; x++) {
            for (int z = -radius + 1; z <= radius - 1; z++) {
                Chunk chunk = centerLoc.getWorld().getChunkAt(cX + x, cZ + z);

                Location[] checkLocations = new Location[]{
                        chunk.getBlock(0, 64, 0).getLocation(),
                        chunk.getBlock(15, 64, 15).getLocation(),
                        chunk.getBlock(8, 64, 8).getLocation()
                };

                for (Location loc : checkLocations) {
                    if (isLocationProtectedFromPlayer(player, loc)) return true;
                }
            }
        }
        return false;
    }

    private boolean isLocationProtectedFromPlayer(Player player, Location loc) {
        if (griefPreventionEnabled) {
            Claim claim = GriefPrevention.instance.dataStore.getClaimAt(loc, true, null);
            if (claim != null && !claim.ownerID.equals(player.getUniqueId())) {
                if (claim.allowBuild(player, Material.TNT) != null) return true;
            }
        }

        if (worldGuardEnabled) {
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            RegionQuery query = container.createQuery();
            com.sk89q.worldedit.util.Location wgLoc = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(loc);
            if (!query.testState(wgLoc, WorldGuardPlugin.inst().wrapPlayer(player), Flags.BUILD)) return true;
        }

        if (landsIntegration != null) {
            Area area = landsIntegration.getArea(loc);
            if (area != null && !area.getLand().getOwnerUID().equals(player.getUniqueId())) {
                if (!area.hasRoleFlag(player, me.angeschossen.lands.api.flags.type.Flags.BLOCK_BREAK)) return true;
            }
        }

        if (townyEnabled) {
            boolean canBuild = PlayerCacheUtil.hasAccessInTown(player, loc, Material.TNT, TownyPermission.ActionType.DESTROY);
            if (!canBuild && !TownyAPI.getInstance().isWilderness(loc)) return true;
        }

        return false;
    }

    private boolean isOre(Material material) {
        String name = material.name();
        return name.contains("ORE") || name.contains("RAW_") || name.contains("ANCIENT_DEBRIS");
    }

    private String formatTime(long millis) {
        long seconds = millis / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        return String.format("%02dh %02dm %02ds", hours, minutes, secs);
    }

    private Component formatMsg(String message) {
        if (message == null) return Component.empty();
        return LegacyComponentSerializer.legacyAmpersand().deserialize(message);
    }

    private String formatMsgRaw(String message) {
        if (message == null) return "";
        return ChatColor.translateAlternateColorCodes('&', message);
    }
}
