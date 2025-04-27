package com.omdmrotat.totemghostfix;

// Specific Bukkit imports
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
// Other necessary imports
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public final class TotemGhostFix extends JavaPlugin implements Listener {

    // Stores data about a potential totem save detected just before death
    private static class TotemSaveData {
        final Location deathLocation;
        // We no longer strictly need to store which hand it *was* in,
        // as we re-check both hands in PlayerDeathEvent.
        // Keeping them can be useful for debugging, though.
        final boolean wasInOffhandInitially;
        final boolean wasInMainHandInitially;

        TotemSaveData(Location loc, boolean offhand, boolean mainhand) {
            this.deathLocation = loc;
            this.wasInOffhandInitially = offhand;
            this.wasInMainHandInitially = mainhand;
        }
    }

    private final Map<UUID, TotemSaveData> pendingTotemSaves = new HashMap<>();
    private final Map<UUID, Long> totemReviveCooldowns = new HashMap<>();
    private final long REVIVE_COOLDOWN_MILLISECONDS = 500; // Half a second cooldown to prevent rapid re-revives

    @Override
    public void onEnable() {
        // Spigot check (optional but good practice)
        // Uses getServer() and getLogger() from JavaPlugin, Bukkit from org.bukkit
        if (!getServer().getVersion().contains("Spigot") && !getServer().getVersion().contains("Paper") && !getServer().getVersion().contains("Purpur")) {
             getLogger().severe("************************************************************");
             getLogger().severe("TotemGhostFix works best with Spigot or a fork (Paper, Purpur)");
             getLogger().severe("for certain functionalities (like player.spigot().respawn()).");
             getLogger().severe("Functionality might be limited on other platforms.");
             getLogger().severe("************************************************************");
        }
        Bukkit.getPluginManager().registerEvents(this, this); // Uses Bukkit
        getLogger().info("TotemGhostFix v2.2 (Flexible Consume) enabled!");
        getLogger().info("This plugin attempts to mitigate ghost totems by intercepting the death event.");
    }

    @Override
    public void onDisable() {
        pendingTotemSaves.clear();
        totemReviveCooldowns.clear();
        getLogger().info("TotemGhostFix disabled.");
    }

    // 1. Detect potential lethal damage and if a totem exists *without cancelling yet*
    // Priority MONITOR ensures this runs after damage calculations are mostly done.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPreDeathDamageCheck(EntityDamageEvent event) {
        // Uses Player, UUID, System, Map, EntityDamageEvent
        if (!(event.getEntity() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getEntity();
        UUID playerId = player.getUniqueId();

        // Check if player is currently on the short revive cooldown
        if (totemReviveCooldowns.containsKey(playerId) && System.currentTimeMillis() < totemReviveCooldowns.get(playerId)) {
            // getLogger().info("[Debug] Player " + player.getName() + " is on revive cooldown. Ignoring damage event.");
            return; // Don't process if on cooldown
        }

        // Calculate health after this damage event
        // Note: Absorption hearts are considered
        double healthAfterDamage = player.getHealth() + player.getAbsorptionAmount() - event.getFinalDamage();

        // Check if the damage is lethal
        if (healthAfterDamage <= 0) {
            // getLogger().info("[Debug] Potential lethal damage detected for " + player.getName() + ". Health: " + player.getHealth() + ", Absorption: " + player.getAbsorptionAmount() + ", Final Damage: " + event.getFinalDamage());

            // Uses PlayerInventory, ItemStack, Material
            PlayerInventory inventory = player.getInventory();
            ItemStack offhandItem = inventory.getItemInOffHand();
            ItemStack mainHandItem = inventory.getItemInMainHand();

            // Check if a totem exists in either hand
            boolean totemInOffhand = offhandItem != null && offhandItem.getType() == Material.TOTEM_OF_UNDYING && offhandItem.getAmount() > 0;
            boolean totemInMainHand = mainHandItem != null && mainHandItem.getType() == Material.TOTEM_OF_UNDYING && mainHandItem.getAmount() > 0;

            // If a totem is found and the player isn't already marked for a save
            if ((totemInOffhand || totemInMainHand) && !pendingTotemSaves.containsKey(playerId)) {
                // getLogger().info("[Debug] Totem condition PASSED for " + player.getName() + ". Offhand=" + totemInOffhand + ", Mainhand=" + totemInMainHand + ". Adding to pendingSaves.");
                // Uses Level (logging), Location
                getLogger().log(Level.INFO, "TotemGhostFix: Detected lethal damage for " + player.getName() + ". Totem found initially in " + (totemInOffhand ? "offhand" : "main hand") + ". Preparing for potential death interception.");
                // Store the location and which hand(s) had the totem initially
                pendingTotemSaves.put(playerId, new TotemSaveData(player.getLocation().clone(), totemInOffhand, totemInMainHand));
            }
            // else if (pendingTotemSaves.containsKey(playerId)) {
            //     getLogger().info("[Debug] Player " + player.getName() + " was already in pendingSaves. No action needed here.");
            // } else {
            //     getLogger().info("[Debug] Totem condition FAILED for " + player.getName() + " (or already pending). No totem found or player already marked.");
            // }
        }
    }

    // 2. Intercept the death event if marked - NOW WITH FLEXIBLE CONSUMPTION VALIDATION
    // Priority HIGHEST to run before most other plugins handle death (like gravestone plugins).
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        // Uses Player, UUID, PlayerDeathEvent, Map, TotemSaveData, PlayerInventory, ItemStack, Material
        Player player = event.getEntity();
        UUID playerId = player.getUniqueId();

        // getLogger().info("[Debug] onPlayerDeath triggered for " + player.getName());

        // Check if this player was marked for a potential save
        if (pendingTotemSaves.containsKey(playerId)) {
            // getLogger().info("[Debug] Player " + player.getName() + " IS in pendingTotemSaves. Attempting interception and validation.");

            TotemSaveData saveData = pendingTotemSaves.get(playerId); // Get the saved data (primarily for location)
            PlayerInventory inventory = player.getInventory();
            boolean consumed = false;
            String consumedHand = "N/A"; // Track which hand we actually consumed from

            // --- NEW FLEXIBLE CONSUMPTION VALIDATION ---
            // Priority to Offhand (Vanilla behavior)
            ItemStack offhandStack = inventory.getItemInOffHand();
            if (offhandStack != null && offhandStack.getType() == Material.TOTEM_OF_UNDYING && offhandStack.getAmount() > 0) {
                // getLogger().info("[Debug] Found totem in OFFHAND at moment of death for " + player.getName() + ". Consuming.");
                offhandStack.setAmount(offhandStack.getAmount() - 1); // Decrease amount
                inventory.setItemInOffHand(offhandStack.getAmount() <= 0 ? null : offhandStack); // Set to null if amount is 0
                consumed = true;
                consumedHand = "offhand";
            }
            // If not consumed from offhand, check Main Hand
            else {
                 ItemStack mainHandStack = inventory.getItemInMainHand();
                 if (mainHandStack != null && mainHandStack.getType() == Material.TOTEM_OF_UNDYING && mainHandStack.getAmount() > 0) {
                    // getLogger().info("[Debug] Found totem in MAIN HAND at moment of death for " + player.getName() + ". Consuming.");
                    mainHandStack.setAmount(mainHandStack.getAmount() - 1); // Decrease amount
                    inventory.setItemInMainHand(mainHandStack.getAmount() <= 0 ? null : mainHandStack); // Set to null if amount is 0
                    consumed = true;
                    consumedHand = "main hand";
                 }
            }
            // --- END FLEXIBLE CONSUMPTION VALIDATION ---


            // --- ABORT IF VALIDATION FAILED (No totem in EITHER hand now) ---
            if (!consumed) {
                // Uses Level (logging)
                getLogger().warning("TotemGhostFix: Aborting failsafe for " + player.getName() + ". No totem found in EITHER hand at the moment of death, despite one being detected earlier. Player will die normally.");
                pendingTotemSaves.remove(playerId); // Clean up the pending save mark
                // getLogger().info("[Debug] Removed " + player.getName() + " from pendingSaves due to failed consumption/validation (no totem found).");
                return; // <<<< LET THE PLAYER DIE NORMALLY
            }
            // --- END ABORT LOGIC ---


            // --- Proceed only if consumption was successful ---
            getLogger().info("TotemGhostFix: Successfully consumed totem from " + consumedHand + " for " + player.getName() + ". Proceeding with revive.");

            // Prevent drops and normal death consequences
            event.setKeepInventory(true); // Keep inventory
            event.setKeepLevel(true); // Keep XP levels
            event.setDroppedExp(0); // Drop no XP orbs
            event.getDrops().clear(); // Clear item drops list
            event.setDeathMessage(null); // Suppress the default death message

            // Schedule the immediate respawn (needs to be delayed by 1 tick)
            // We use a BukkitRunnable to run this slightly later.
            // Uses BukkitRunnable, Exception
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (player.isOnline()) { // Check if player is still online
                        // getLogger().info("[Debug] Scheduling immediate respawn for " + player.getName());
                        try {
                            // Use Spigot API to force immediate respawn without death screen
                            player.spigot().respawn();
                            // Note: The onPlayerRespawn event will handle effects and location
                        } catch (Exception e) {
                            getLogger().severe("TotemGhostFix: Failed to force respawn for " + player.getName() + ". Error: " + e.getMessage());
                            // If respawn fails, remove from pending saves to prevent issues
                            pendingTotemSaves.remove(playerId);
                            // getLogger().info("[Debug] Removed " + player.getName() + " from pendingSaves due to respawn exception.");
                        }
                    } else {
                        // Player logged off before respawn task ran
                        // getLogger().info("[Debug] Player " + player.getName() + " logged off before respawn task. Clearing from pendingSaves.");
                        pendingTotemSaves.remove(playerId); // Clean up
                    }
                }
            }.runTaskLater(this, 1L); // Run 1 tick later using 'this' plugin instance

        } else {
            // Player was not in pendingTotemSaves, likely died without a totem or was on cooldown
            // getLogger().info("[Debug] Player " + player.getName() + " is NOT in pendingTotemSaves. Allowing normal death.");
        }
    }


    // 3. Finalize the revival on respawn
    // Priority HIGHEST to ensure we set the location before other plugins might change it.
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        // Uses Player, UUID, PlayerRespawnEvent, Map, TotemSaveData
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        // getLogger().info("[Debug] onPlayerRespawn triggered for " + player.getName());

        // Check if this respawn was triggered by our plugin's save
        if (pendingTotemSaves.containsKey(playerId)) {
            TotemSaveData saveData = pendingTotemSaves.get(playerId); // Get the saved data
            getLogger().info("TotemGhostFix: Finalizing totem failsafe for " + player.getName() + " on respawn.");

            // Set the respawn location back to where they "died"
            // Uses Location
            event.setRespawnLocation(saveData.deathLocation);
            // getLogger().info("[Debug] Set respawn location for " + player.getName() + " to " + saveData.deathLocation.toString());

            // Apply totem effects AFTER respawn (delay 1 tick to ensure player is fully respawned)
            // Uses BukkitRunnable, PotionEffectType, PotionEffect, Sound, Particle, System, ChatColor
            new BukkitRunnable() {
                @Override
                public void run() {
                     if (!player.isOnline()) {
                        // getLogger().info("[Debug] Player " + player.getName() + " logged off before post-respawn effects task. Clearing from pendingSaves.");
                        pendingTotemSaves.remove(playerId); // Clean up if player logs off quickly
                        return;
                     }

                    // getLogger().info("[Debug] Applying post-respawn effects for " + player.getName());
                    // Apply standard Totem of Undying effects
                    player.setHealth(1.0); // Set health to 1 heart (vanilla totem behavior)
                    // Clear any existing effects of these types before applying new ones
                    player.removePotionEffect(PotionEffectType.REGENERATION);
                    player.removePotionEffect(PotionEffectType.ABSORPTION);
                    player.removePotionEffect(PotionEffectType.FIRE_RESISTANCE);
                    // Add new effects
                    player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 900, 1)); // Regen II for 45s
                    player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 100, 1)); // Absorption II for 5s
                    player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 800, 0)); // Fire Resistance for 40s

                    // Play sound and particle effects at the player's location
                    player.playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 1.0f, 1.0f);
                    player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, player.getLocation().add(0, 1, 0), 50, 0.5, 0.5, 0.5, 0.1); // Particle effect slightly above feet

                    // Optional: Broadcast a message
                    // Bukkit.broadcastMessage(ChatColor.GOLD + "[TotemGhostFix] " + ChatColor.YELLOW + player.getName() + " was saved by the failsafe totem mechanism!");

                    // Apply the short cooldown to prevent immediate re-revival if they die again instantly
                    totemReviveCooldowns.put(playerId, System.currentTimeMillis() + REVIVE_COOLDOWN_MILLISECONDS);
                    // getLogger().info("[Debug] Applied revive cooldown for " + player.getName());

                    // IMPORTANT: Remove the player from pending saves now that the process is complete
                    pendingTotemSaves.remove(playerId);
                    // getLogger().info("[Debug] Removed " + player.getName() + " from pendingSaves after successful revival effects.");

                }
            }.runTaskLater(this, 1L); // Run 1 tick later using 'this' plugin instance
        } else {
            // Player respawned normally (not via our plugin)
            // getLogger().info("[Debug] Player " + player.getName() + " respawned normally (not found in pendingTotemSaves).");
        }
    }

    // Clean up maps if player leaves to prevent memory leaks
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Uses UUID, PlayerQuitEvent, Map
        UUID playerId = event.getPlayer().getUniqueId();
        boolean removedPending = pendingTotemSaves.remove(playerId) != null;
        boolean removedCooldown = totemReviveCooldowns.remove(playerId) != null;
        // if (removedPending || removedCooldown) {
        //     getLogger().info("[Debug] Cleaned up data for " + event.getPlayer().getName() + " on quit. RemovedFromPending=" + removedPending + ", RemovedFromCooldown=" + removedCooldown);
        // }
    }
}
