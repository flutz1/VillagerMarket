package net.bestemor.villagermarket.listener;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import net.bestemor.villagermarket.ConfigManager;
import net.bestemor.villagermarket.VMPlugin;
import net.bestemor.villagermarket.menu.Shopfront;
import net.bestemor.villagermarket.shop.AdminShop;
import net.bestemor.villagermarket.shop.PlayerShop;
import net.bestemor.villagermarket.shop.ShopMenu;
import net.bestemor.villagermarket.shop.VillagerShop;
import net.bestemor.villagermarket.utils.UpdateChecker;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PlayerListener implements Listener {

    private final VMPlugin plugin;
    private final List<UUID> cancelledPlayers = new ArrayList<>();

    public PlayerListener(VMPlugin plugin) {
        this.plugin = plugin;
    }

    public void addCancelledPlayer(UUID uuid) {
        cancelledPlayers.add(uuid);
    }

    public void removeCancelledPlayer(UUID uuid) {
        cancelledPlayers.remove(uuid);
    }

    @EventHandler (ignoreCancelled = false, priority = EventPriority.LOWEST)
    public void playerRightClick(PlayerInteractEntityEvent event) {
        Player p = event.getPlayer();

        if (event.getHand() == EquipmentSlot.OFF_HAND) { return; }

        if (cancelledPlayers.contains(p.getUniqueId())) {
            return;
        }
        
        VillagerShop shop = plugin.getShopManager().getShop(event.getRightClicked().getUniqueId());
        
        if (shop != null) {
            event.setCancelled(true);

            if (shop instanceof AdminShop) {
                if (p.hasPermission("villagermarket.adminshops")) {
                    shop.openInventory(p, ShopMenu.EDIT_SHOP);
                } else {
                    if (ConfigManager.getBoolean("per_adminshop_permissions") && !p.hasPermission("villagermarket.adminshop." + shop.getEntityUUID())) {
                        p.sendMessage(ConfigManager.getMessage("messages.no_permission_adminshop"));
                        return;
                    }
                    shop.getShopfrontHolder().open(p, Shopfront.Type.CUSTOMER);
                }
            } else {
                PlayerShop playerShop = (PlayerShop) shop;
                if (!playerShop.hasOwner()) {
                    shop.openInventory(p, ShopMenu.BUY_SHOP);
                } else if (playerShop.getOwnerUUID().equals(p.getUniqueId()) || playerShop.isTrusted(p) || (p.isSneaking() && p.hasPermission("villagermarket.spy"))) {
                    shop.updateMenu(ShopMenu.EDIT_SHOP);
                    shop.openInventory(p, ShopMenu.EDIT_SHOP);
                } else {
                    shop.getShopfrontHolder().open(p, Shopfront.Type.CUSTOMER);
                }
            }

            p.playSound(p.getLocation(), ConfigManager.getSound("sounds.open_shop"), 0.5f, 1);
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        for (Entity entity : plugin.getShopManager().getEntities()) {
            if (entity.getNearbyEntities(5, 5, 5).contains(player)) {
                entity.teleport(entity.getLocation().setDirection(player.getLocation().subtract(entity.getLocation()).toVector()));
            }
        }
    }

    @EventHandler
    public void onItemClick(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack itemStack = player.getInventory().getItemInHand();
        if (itemStack.getItemMeta() == null || event.getClickedBlock() == null || event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_AIR) {
            return;
        }

        ItemMeta itemMeta = itemStack.getItemMeta();
        PersistentDataContainer dataContainer = itemMeta.getPersistentDataContainer();

        if (dataContainer.has(new NamespacedKey(plugin, "vm-item"), PersistentDataType.STRING)) {
            event.setCancelled(true);
            //Check if player does not have access to the region

            int max = plugin.getShopManager().getMaxShops(player);
            int owned = plugin.getShopManager().getOwnedShops(player).size();
            if (max != -1 && owned >= max) {
                player.sendMessage(ConfigManager.getMessage("messages.max_shops")
                        .replace("%current%", String.valueOf(owned))
                        .replace("%max%", String.valueOf(max)));
                
                player.playSound(player.getLocation(), ConfigManager.getSound("sounds.max_shops"), 1, 1);
                return;
            }

            if (Bukkit.getPluginManager().isPluginEnabled("WorldGuard") && ConfigManager.getBoolean("world_guard")) {
                RegionManager rm = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(player.getWorld()));
                ApplicableRegionSet set = rm.getApplicableRegions(BukkitAdapter.asBlockVector(event.getClickedBlock().getLocation()));
                UUID uuid = player.getUniqueId();
                for (ProtectedRegion region : set) {
                    if (!region.getOwners().getUniqueIds().contains(uuid) && !region.getMembers().getUniqueIds().contains(uuid)) {
                        player.sendMessage(ConfigManager.getMessage("messages.region_no_access"));
                        return;
                    }
                }
            }
            if (!player.hasPermission("villagermarket.use_spawn_item")) {
                player.sendMessage(ConfigManager.getMessage("messages.no_permission_use_item"));
                return;
            }

            String data = dataContainer.get(new NamespacedKey(plugin, "vm-item"), PersistentDataType.STRING);
            int shopSize = Integer.parseInt(data.split("-")[0]);
            int storageSize = Integer.parseInt(data.split("-")[1]);

            Entity entity = plugin.getShopManager().spawnShop(event.getClickedBlock().getLocation().clone().add(0.5, 1, 0.5), "player");
            if (Bukkit.getEntity(entity.getUniqueId()) != null) {
                plugin.getShopManager().createShopConfig(plugin, entity.getUniqueId(), storageSize, shopSize, -1, "player", "infinite");
                PlayerShop playerShop = (PlayerShop) plugin.getShopManager().getShop(entity.getUniqueId());
                playerShop.setOwner(player);
            } else {
                Bukkit.getLogger().severe(ChatColor.RED + "[VillagerMarket] Unable to spawn Villager! Does WorldGuard deny mobs pawn?");
            }

            player.playSound(event.getClickedBlock().getLocation(), ConfigManager.getSound("sounds.create_shop"), 1, 1);
            if (itemStack.getAmount() > 1) {
                itemStack.setAmount(itemStack.getAmount() - 1);
            } else {
                player.setItemInHand(null);
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {

        Player player = event.getPlayer();
        if (player.hasPermission("villagermarket.admin")) {
            new UpdateChecker(plugin, 82965).getVersion(version -> {
                String currentVersion = plugin.getDescription().getVersion();
                if (!currentVersion.equalsIgnoreCase(version)) {
                    String foundVersion = ChatColor.translateAlternateColorCodes('&', "&bA new version of VillagerMarket was found!");
                    String downloadVersion = ChatColor.translateAlternateColorCodes('&', "&bGet it here for the latest features and bug fixes: &ehttps://www.spigotmc.org/resources/82965/");

                    player.sendMessage(ConfigManager.getString("plugin_prefix") + " " + foundVersion);
                    player.sendMessage(ConfigManager.getString("plugin_prefix") + " " + downloadVersion);
                }
            });
        }
        if (plugin.getShopManager().getExpiredStorages().containsKey(player.getUniqueId())) {
            player.sendMessage(ConfigManager.getMessage("messages.expired"));
        }
    }
}