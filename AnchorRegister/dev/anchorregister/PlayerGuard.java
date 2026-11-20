package dev.anchorregister;

import com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;

final class PlayerGuard implements Listener {
   private final AnchorRegister plugin;

   PlayerGuard(AnchorRegister plugin) {
      this.plugin = plugin;
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onCommand(PlayerCommandPreprocessEvent event) {
      if (this.plugin.locked(event.getPlayer())) {
         event.setCancelled(true);
         this.plugin.prompt(event.getPlayer());
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST
   )
   public void onCommandList(PlayerCommandSendEvent event) {
      if (this.plugin.locked(event.getPlayer())) {
         event.getCommands().removeIf(label -> !this.plugin.authenticationCommandAllowed(event.getPlayer(), label));
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onItemPickup(EntityPickupItemEvent event) {
      if (event.getEntity() instanceof Player player && this.plugin.locked(player)) event.setCancelled(true);
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onExperiencePickup(PlayerPickupExperienceEvent event) {
      if (this.plugin.locked(event.getPlayer())) event.setCancelled(true);
   }

   @EventHandler(
      priority = EventPriority.HIGHEST
   )
   public void onDamage(EntityDamageEvent event) {
      if (event.getEntity() instanceof Player player && this.plugin.locked(player)) {
         event.setCancelled(true);
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST
   )
   public void onFood(FoodLevelChangeEvent event) {
      if (event.getEntity() instanceof Player player && this.plugin.locked(player)) {
         event.setCancelled(true);
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST
   )
   public void onTarget(EntityTargetEvent event) {
      if (event.getTarget() instanceof Player player && this.plugin.locked(player)) {
         event.setCancelled(true);
      }
   }
}
