package dev.anchorregister;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Sound;
import org.bukkit.scheduler.BukkitTask;

final class RegistrationLobby implements Listener {
    private static final String PREFIX = ChatColor.DARK_AQUA + "[ᴀɴᴄʜᴏʀ] ";
    private static final int[] ORE_X = {-2, 0, 2};
    private static final List<Choice> CHOICES = List.of(
        new Choice(Material.DIAMOND_ORE, "алмазную руду"),
        new Choice(Material.IRON_ORE, "железную руду"),
        new Choice(Material.GOLD_ORE, "золотую руду")
    );
    private final AnchorRegister plugin;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, Challenge> challenges = new HashMap<>();
    private final List<TextDisplay> labels = new ArrayList<>();
    private final int maximumMistakes;
    private final Location entrance;
    private final Location hub;
    private final BukkitTask reminders;
    private final RegistrationEditor editor;

    RegistrationLobby(AnchorRegister plugin) throws java.io.IOException {
        this.plugin = plugin;
        maximumMistakes = Math.clamp(plugin.getConfig().getInt("lobby.maximum-mistakes", 3), 1, 10);
        World world = Bukkit.getWorlds().getFirst();
        entrance = new Location(world, 0.5, 65, 0.5, 180, 0);
        hub = createHub();
        buildWorld(world);
        editor = new RegistrationEditor(plugin, this);
        reminders = Bukkit.getScheduler().runTaskTimer(plugin, this::remind, 20, 20);
    }

    private void buildWorld(World world) {
        world.setDifficulty(Difficulty.PEACEFUL);
        world.setTime(6000);
        world.setStorm(false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_FIRE_TICK, false);
        world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        world.setGameRule(GameRule.SHOW_DEATH_MESSAGES, false);
        world.setSpawnLocation(entrance);
        world.getWorldBorder().setCenter(24.5, 0.5);
        world.getWorldBorder().setSize(160);
        for (TextDisplay display : world.getEntitiesByClass(TextDisplay.class)) {
            if (display.getScoreboardTags().contains("anchor_registration_label")) display.remove();
        }
        // Preserve an existing room on upgrade; create a default only in a fresh void world.
        if (!plugin.getConfig().getBoolean("lobby.room-created", false)) {
            boolean existingRoom = world.getBlockAt(0, 64, 0).getType().isSolid()
                || world.getBlockAt(-2, 66, -3).getType() == Material.DIAMOND_ORE;
            if (!existingRoom) buildPlatform(world, 64);
            plugin.getConfig().set("lobby.room-created", true);
            plugin.saveConfig();
        }
        repairServiceArea();
        label(world, 0.5, 69, -8.5, "§bᴀɴᴄʜᴏʀ");
        label(world, 0.5, 67.6, -8.5, "§fРегистрация и проверка");
    }

    private Location createHub() {
        boolean missingWorld = !new java.io.File(Bukkit.getWorldContainer(), "hub/level.dat").isFile();
        World world = new WorldCreator("hub").type(WorldType.FLAT).generateStructures(false)
            .generatorSettings("{\"layers\":[{\"block\":\"minecraft:air\",\"height\":1}],\"biome\":\"minecraft:the_void\"}")
            .createWorld();
        if (world == null) throw new IllegalStateException("Не удалось загрузить мир хаба");
        Location spawn = new Location(world, -0.5, 64, 0.5, 90, 0);
        // Build only once so subsequent starts preserve the owner's spawn construction.
        if (missingWorld) {
            for (int x = -16; x <= 16; x++) {
                for (int z = -16; z <= 16; z++) {
                    Material material = Math.abs(x) == 16 || Math.abs(z) == 16
                        ? Material.POLISHED_DEEPSLATE : Material.SMOOTH_QUARTZ;
                    if (Math.abs(x) == 12 && Math.abs(z) == 12) material = Material.SEA_LANTERN;
                    world.getBlockAt(x, 62, z).setType(Material.POLISHED_DEEPSLATE, false);
                    world.getBlockAt(x, 63, z).setType(material, false);
                }
            }
            world.save();
            plugin.getConfig().set("hub.platform-created", true);
            plugin.saveConfig();
        }
        world.setSpawnLocation(spawn);
        world.setDifficulty(Difficulty.PEACEFUL);
        world.setTime(6000);
        world.setStorm(false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        return spawn;
    }

    void sendToHub(Player player) {
        if (editor != null && editor.exitToHub(player)) return;
        if (plugin.locked(player)) {
            plugin.prompt(player);
            return;
        }
        player.closeInventory();
        player.setFallDistance(0);
        if (!player.teleport(hub)) {
            player.sendMessage(PREFIX + ChatColor.RED + "Не удалось перейти в хаб. Повтори /hub.");
            return;
        }
        refreshVisibility(player);
        player.sendActionBar("");
        player.sendMessage(PREFIX + ChatColor.AQUA + "Ты в хабе.");
    }

    private boolean canBuild(Player player) {
        return !plugin.locked(player) && player.isOp() && player.getGameMode() == GameMode.CREATIVE
            && (player.getWorld().equals(hub.getWorld()) || editor.canBuild(player));
    }

    private void buildPlatform(World world, int floorY) {
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                boolean edge = Math.abs(x) == 12 || Math.abs(z) == 12;
                Material floor = edge ? Material.POLISHED_DEEPSLATE : Material.SMOOTH_QUARTZ;
                if (Math.abs(x) <= 2 && Math.abs(z) <= 2) floor = Material.AMETHYST_BLOCK;
                if (Math.abs(x) == 9 && Math.abs(z) == 9) floor = Material.SEA_LANTERN;
                world.getBlockAt(x, floorY - 1, z).setType(Material.POLISHED_DEEPSLATE, false);
                world.getBlockAt(x, floorY, z).setType(floor, false);
                for (int y = floorY + 1; y <= floorY + 8; y++) {
                    world.getBlockAt(x, y, z).setType(
                        edge ? Material.POLISHED_DEEPSLATE : Material.AIR, false);
                }
                world.getBlockAt(x, floorY + 9, z).setType(
                    x % 6 == 0 && z % 6 == 0 ? Material.SEA_LANTERN : Material.SMOOTH_QUARTZ, false);
            }
        }
    }

    private void label(World world, double x, double y, double z, String text) {
        TextDisplay display = world.spawn(new Location(world, x, y, z), TextDisplay.class);
        display.setText(text);
        display.setBillboard(Display.Billboard.CENTER);
        display.setAlignment(TextDisplay.TextAlignment.CENTER);
        display.setSeeThrough(false);
        display.setShadowed(true);
        display.setLineWidth(350);
        display.addScoreboardTag("anchor_registration_label");
        labels.add(display);
    }

    void prepare(Player player) {
        player.closeInventory();
        player.getInventory().clear();
        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setFoodLevel(20);
        player.setHealth(player.getMaxHealth());
        player.setFireTicks(0);
        player.setFallDistance(0);
        player.teleport(entrance);
    }

    void begin(Player player) {
        Challenge challenge = new Challenge(CHOICES.get(random.nextInt(CHOICES.size())));
        challenges.put(player.getUniqueId(), challenge);
        player.setGameMode(GameMode.SURVIVAL);
        ItemStack pickaxe = new ItemStack(Material.DIAMOND_PICKAXE);
        ItemMeta meta = pickaxe.getItemMeta();
        meta.setDisplayName("§bКирка для проверки");
        meta.setUnbreakable(true);
        pickaxe.setItemMeta(meta);
        player.getInventory().setItem(0, pickaxe);
        player.getInventory().setHeldItemSlot(0);
    }

    void prompt(Player player) {
        Challenge challenge = challenges.get(player.getUniqueId());
        if (challenge == null) return;
        player.sendTitle("§bСломай " + challenge.target.name,
            "§fЗажми левую кнопку мыши на нужной руде", 5, 100, 10);
        player.sendMessage(PREFIX + ChatColor.AQUA + "Сломай " + challenge.target.name
            + ". Руды перед тобой. Зажми левую кнопку мыши.");
    }

    private boolean isVerificationOre(org.bukkit.block.Block block) {
        if (block == null || !block.getWorld().equals(entrance.getWorld())
            || block.getY() != 66 || block.getZ() != -3) return false;
        for (int x : ORE_X) if (block.getX() == x) return true;
        return false;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && canBuild(player)) return;
        event.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && canBuild(player)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String label = event.getMessage().substring(1).split("\\s+", 2)[0];
        if (label.equalsIgnoreCase("captcha") || label.equalsIgnoreCase("anchorregister:captcha")) {
            event.setCancelled(true);
            if (plugin.verified(event.getPlayer())) {
                event.getPlayer().sendMessage(PREFIX + ChatColor.GREEN + "Проверка уже пройдена.");
                if (plugin.locked(event.getPlayer())) plugin.prompt(event.getPlayer());
            } else {
                prompt(event.getPlayer());
            }
        }
    }

    void complete(Player player) {
        if (editor.recover(player)) return;
        sendToHub(player);
        player.sendTitle("§aВход выполнен", "§fРегистрация и проверка завершены", 10, 60, 20);
    }

    private void remind() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.locked(player) && (plugin.verified(player) || challenges.containsKey(player.getUniqueId()))) {
                String text = plugin.verified(player)
                    ? "§eВведи " + plugin.authenticationUsage(player)
                    : "§bСломай " + challenges.get(player.getUniqueId()).target.name;
                player.sendActionBar(text);
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        event.setJoinMessage(null);
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other != event.getPlayer()) {
                other.hidePlayer(plugin, event.getPlayer());
                event.getPlayer().hidePlayer(plugin, other);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        editor.leave(event.getPlayer());
        event.setQuitMessage(null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (plugin.locked(event.getPlayer())) {
            if (event.getTo().getY() < entrance.getY() - 3 || event.getTo().getY() > entrance.getY() + 8) {
                event.setTo(entrance);
            }
        } else if (event.getTo().getWorld().equals(hub.getWorld()) && event.getTo().getY() < hub.getY() - 8) {
            event.setTo(hub);
        }
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) {
        if (canBuild(event.getPlayer())) {
            if (editor.canBuild(event.getPlayer()) && isVerificationOre(event.getBlock())) {event.setCancelled(true);event.getPlayer().sendMessage(PREFIX + ChatColor.YELLOW + "Эти три руды нужны для проверки. Оформляй пространство вокруг них.");}
            return;
        }
        boolean alreadyCancelled = event.isCancelled();
        event.setCancelled(true);
        Player player = event.getPlayer();
        Challenge challenge = challenges.get(player.getUniqueId());
        if (alreadyCancelled || challenge == null || !isVerificationOre(event.getBlock())) return;
        if (player.getEyeLocation().distanceSquared(event.getBlock().getLocation().add(0.5, 0.5, 0.5)) > 25
            || player.getInventory().getItemInMainHand().getType() != Material.DIAMOND_PICKAXE) return;
        if (event.getBlock().getType() != challenge.target.material) {
            challenge.mistakes++;
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.6f);
            if (challenge.mistakes >= maximumMistakes) {
                challenges.remove(player.getUniqueId());
                player.kickPlayer("Проверка не пройдена. Подключись и попробуй снова.");
            } else {
                player.sendMessage(PREFIX + ChatColor.YELLOW + "Это другая руда. Сломай "
                    + challenge.target.name + ". Осталось ошибок: " + (maximumMistakes - challenge.mistakes));
            }
            return;
        }
        challenges.remove(player.getUniqueId());
        player.getInventory().clear();
        player.setGameMode(GameMode.ADVENTURE);
        player.resetTitle();
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.5f);
        plugin.verificationPassed(player);
        // Keep the shared ore intact, and show its destruction only to this player.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && plugin.locked(player) && plugin.verified(player)) {
                for (int x : ORE_X) {
                    player.sendBlockChange(new Location(entrance.getWorld(), x, 66, -3), Material.AIR.createBlockData());
                }
            }
        });
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        if (canBuild(event.getPlayer())) {
            if (editor.canBuild(event.getPlayer()) && serviceSpace(event.getBlock())) {event.setCancelled(true);event.getPlayer().sendMessage(PREFIX + ChatColor.YELLOW + "Не перекрывай точку появления и обзор трёх руд.");}
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (canBuild(event.getPlayer())) return;
        if (event.getAction() == Action.LEFT_CLICK_BLOCK
            && challenges.containsKey(event.getPlayer().getUniqueId()) && isVerificationOre(event.getClickedBlock())) return;
        event.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (canBuild(event.getPlayer())) return;
        event.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        event.setCancelled(true);
    }

    @EventHandler
    public void onFood(FoodLevelChangeEvent event) {
        event.setCancelled(true);
    }

    void forget(Player player) {
        challenges.remove(player.getUniqueId());
    }

    void close() {
        editor.close();
        reminders.cancel();
        challenges.clear();
        labels.forEach(TextDisplay::remove);
    }

    World registrationWorld() { return entrance.getWorld(); }
    Location entranceLocation() { return entrance.clone(); }
    Location hubLocation() { return hub.clone(); }
    void editorCommand(Player player, String[] args) { editor.command(player, args); }
    void refreshVisibility(Player player) {
        for (Player other : Bukkit.getOnlinePlayers()) if (other != player) {
            boolean visible = !plugin.locked(player) && !plugin.locked(other)
                && player.getWorld() == hub.getWorld() && other.getWorld() == hub.getWorld();
            if (visible) {player.showPlayer(plugin, other);other.showPlayer(plugin, player);}
            else {player.hidePlayer(plugin, other);other.hidePlayer(plugin, player);}
        }
    }
    private boolean serviceSpace(org.bukkit.block.Block block) {
        if (block.getWorld() != entrance.getWorld() || block.getX() < -3 || block.getX() > 3
            || block.getZ() < -3 || block.getZ() > 0 || block.getY() < 65 || block.getY() > 66) return false;
        if (isVerificationOre(block) || block.getX()==0 && block.getZ()==0) return true;
        var eyes=entrance.clone().add(0,1.62,0).toVector();
        for(int x:ORE_X){var ray=new org.bukkit.util.Vector(x+.5,66.5,-2.5).subtract(eyes);if(block.getBoundingBox().rayTrace(eyes,ray.clone().normalize(),ray.length())!=null)return true;}
        return false;
    }
    String roomProblem() {
        World world = entrance.getWorld();
        if (!world.getBlockAt(0, 64, 0).getType().isSolid() || !world.getBlockAt(0, 65, 0).isPassable()
            || !world.getBlockAt(0, 66, 0).isPassable()) return "Оставь твёрдый пол и свободное место появления: 0 65 0.";
        Location eyes = entrance.clone().add(0, 1.62, 0);
        for (int i = 0; i < ORE_X.length; i++) {
            var ore = world.getBlockAt(ORE_X[i], 66, -3);
            if (ore.getType() != CHOICES.get(i).material) return "Верни три руды проверки на прежние места.";
            var direction = ore.getLocation().add(.5, .5, .5).toVector().subtract(eyes.toVector());
            var hit = world.rayTraceBlocks(eyes, direction.clone().normalize(), direction.length()+.1, org.bukkit.FluidCollisionMode.NEVER, true);
            if (hit == null || !ore.equals(hit.getHitBlock())) return "Из точки появления должны быть видны все три руды. Убери препятствия перед ними.";
        }
        return null;
    }
    void repairServiceArea() {
        World world = entrance.getWorld();
        if (!world.getBlockAt(0, 64, 0).getType().isSolid()) world.getBlockAt(0, 64, 0).setType(Material.SMOOTH_QUARTZ, false);
        for (int y=65; y<=66; y++) if (!world.getBlockAt(0,y,0).isPassable()) world.getBlockAt(0,y,0).setType(Material.AIR,false);
        Location eyes = entrance.clone().add(0,1.62,0);
        for (int i=0; i<ORE_X.length; i++) {
            var ore=world.getBlockAt(ORE_X[i],66,-3);if(ore.getType()!=CHOICES.get(i).material)ore.setType(CHOICES.get(i).material,false);
            var direction=ore.getLocation().add(.5,.5,.5).toVector().subtract(eyes.toVector());
            for (int attempt=0; attempt<16; attempt++) {
                var hit=world.rayTraceBlocks(eyes,direction.clone().normalize(),direction.length()+.1,org.bukkit.FluidCollisionMode.NEVER,true);
                if(hit==null||hit.getHitBlock()==null||ore.equals(hit.getHitBlock())) break;
                hit.getHitBlock().setType(Material.AIR,false);
            }
        }
    }

    private record Choice(Material material, String name) {}

    private static final class Challenge {
        private final Choice target;
        private int mistakes;

        private Challenge(Choice target) {
            this.target = target;
        }
    }
}
