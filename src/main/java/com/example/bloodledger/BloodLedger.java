package com.example.bloodledger;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Lectern;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public final class BloodLedger extends JavaPlugin implements Listener, CommandExecutor {

    private final Map<UUID, PlayerData> playerDataMap = new HashMap<>();
    private Location lecternLocation = null;
    private boolean isDayChanged = false;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        
        if (this.getCommand("bloodledger") != null) {
            this.getCommand("bloodledger").setExecutor(this);
        } else {
            getLogger().severe("Blad krytyczny: Nie udalo sie zarejestrowac komendy /bloodledger w systemie!");
        }

        // Tracker lokalizacji baz (co 5 sekund)
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    PlayerData data = getPlayerData(player);
                    data.trackLocation(player.getLocation().getChunk());
                }
            }
        }.runTaskTimer(this, 100L, 100L);

        // Sprawdzanie czasu gry (aktualizacja o świcie)
        new BukkitRunnable() {
            @Override
            public void run() {
                if (Bukkit.getWorlds().isEmpty()) return;
                long time = Bukkit.getWorlds().get(0).getTime();
                
                if (time >= 0 && time < 100) {
                    if (!isDayChanged) {
                        updateBloodLedger();
                        isDayChanged = true;
                    }
                } else {
                    isDayChanged = false;
                }
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    private PlayerData getPlayerData(Player player) {
        return playerDataMap.computeIfAbsent(player.getUniqueId(), k -> new PlayerData(player.getName()));
    }

    private void updateBloodLedger() {
        if (lecternLocation == null || !(lecternLocation.getBlock().getState() instanceof Lectern)) {
            return;
        }

        Lectern lectern = (Lectern) lecternLocation.getBlock().getState();
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();

        if (meta == null) return;

        meta.setTitle(ChatColor.DARK_RED + "Ksiega Krwi");
        meta.setAuthor("Serwer");

        // Strona 1: Top 5 Killi
        StringBuilder page1 = new StringBuilder();
        page1.append(ChatColor.RED + "=== KSIĘGA KRWI ===\n\n");
        page1.append(ChatColor.DARK_GRAY + "Najwiecej zabojstw:\n");

        playerDataMap.values().stream()
                .sorted((p1, p2) -> Integer.compare(p2.kills, p1.kills))
                .limit(5)
                .forEach(p -> page1.append(ChatColor.BLACK + "- " + p.name + ": " + ChatColor.RED + p.kills + "\n"));

        meta.addPage(page1.toString());

        // Strona 2: Kordy poszukiwanych graczy (KS > 3)
        StringBuilder page2 = new StringBuilder();
        page2.append(ChatColor.DARK_RED + "Poszukiwani (>3 KS):\n\n");
        boolean anyBounty = false;

        for (PlayerData p : playerDataMap.values()) {
            if (p.killstreak > 3) {
                anyBounty = true;
                Chunk homeChunk = p.getMostVisitedChunk();
                if (homeChunk != null) {
                    int approxX = (homeChunk.getX() << 4) + 8;
                    int approxZ = (homeChunk.getZ() << 4) + 8;
                    approxX = (approxX / 50) * 50;
                    approxZ = (approxZ / 50) * 50;

                    page2.append(ChatColor.DARK_BLUE + p.name + ChatColor.BLACK + " (KS: " + p.killstreak + ")\n")
                            .append("Okolice: X: ~" + approxX + ", Z: ~" + approxZ + "\n\n");
                }
            }
        }

        if (!anyBounty) {
            page2.append(ChatColor.GRAY + "Obecnie brak krwawych potworów na świecie...");
        }
        meta.addPage(page2.toString());

        book.setItemMeta(meta);
        lectern.getInventory().setItem(0, book);
        lectern.update();
        
        Bukkit.broadcastMessage(ChatColor.DARK_RED + "[Księga Krwi] " + ChatColor.RED + "Księga Krwi na spawnie została zaktualizowana!");
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        PlayerData victimData = getPlayerData(victim);
        victimData.killstreak = 0;

        if (killer != null && !killer.equals(victim)) {
            PlayerData killerData = getPlayerData(killer);
            killerData.kills++;
            killerData.killstreak++;
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (lecternLocation != null && event.getBlock().getLocation().equals(lecternLocation)) {
            if (!event.getPlayer().isOp()) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(ChatColor.RED + "Nie mozesz zniszczyc Ksiegi Krwi!");
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Ta komenda moze byc uzyta tylko przez gracza na serwerze.");
            return true;
        }
        
        Player player = (Player) sender;

        if (!player.isOp()) {
            player.sendMessage(ChatColor.RED + "Brak uprawnien. Musisz miec status Operatora (OP).");
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("set")) {
            // Pobieramy blok dokładnie tam, gdzie stoi gracz (na poziomie jego nóg)
            Location spawnLoc = player.getLocation().getBlock().getLocation();
            Block targetBlock = spawnLoc.getBlock();

            // Automatycznie zmieniamy ten blok w Pulpit
            targetBlock.setType(Material.LECTERN);
            
            // Zapisujemy lokalizację w pamięci pluginu
            lecternLocation = spawnLoc;

            player.sendMessage(ChatColor.GREEN + "Pomyślnie postawiono i skonfigurowano pulpit Księgi Krwi w Twojej pozycji!");
            
            // Wywołujemy natychmiastową aktualizację i włożenie książki
            updateBloodLedger();
            return true;
        }

        player.sendMessage(ChatColor.YELLOW + "Użyj: /bloodledger set - aby postawić pulpit w miejscu, w którym stoisz.");
        return true;
    }

    private static class PlayerData {
        String name;
        int kills = 0;
        int killstreak = 0;
        Map<String, Integer> chunkActivity = new HashMap<>();

        PlayerData(String name) {
            this.name = name;
        }

        void trackLocation(Chunk chunk) {
            String chunkKey = chunk.getWorld().getName() + "," + chunk.getX() + "," + chunk.getZ();
            chunkActivity.put(chunkKey, chunkActivity.getOrDefault(chunkKey, 0) + 1);
        }

        Chunk getMostVisitedChunk() {
            if (chunkActivity.isEmpty()) return null;
            String bestKey = Collections.max(chunkActivity.entrySet(), Map.Entry.comparingByValue()).getKey();
            String[] parts = bestKey.split(",");
            return Bukkit.getWorld(parts[0]).getChunkAt(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        }
    }
}
