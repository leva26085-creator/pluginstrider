package dev.anchorreports;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;

public final class AnchorReports extends JavaPlugin implements Listener {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm z", Locale.ROOT)
        .withZone(ZoneId.of("Asia/Yekaterinburg"));
    private static final int PREVIOUS = 45;
    private static final int NEXT = 53;

    private ThreadPoolExecutor database;
    private ReportStore reports;

    @Override
    public void onEnable() {
        try {
            Files.createDirectories(getDataFolder().toPath());
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось открыть папку жалоб", exception);
        }
        java.nio.file.Path storage=java.nio.file.Path.of(getConfig().getString("storage-path",getDataFolder().toPath().resolve("reports.db").toString()));
        reports = new ReportStore(storage);
        database = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(256), Thread.ofPlatform().name("AnchorReports-db").daemon().factory());
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        if (database == null) return;
        database.shutdown();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu) player.closeInventory();
        }
        try {
            if (!database.awaitTermination(4, TimeUnit.SECONDS)) {
                getLogger().warning("Очередь жалоб ещё сохраняется при остановке сервера.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("list")) {
            if (!sender.isOp()) {
                message(sender, "Список жалоб доступен операторам.", NamedTextColor.RED);
                return true;
            }
            if (args.length != 1) {
                message(sender, "Используй /report list", NamedTextColor.YELLOW);
                return true;
            }
            if (sender instanceof Player player) {
                openList(player, 1, Long.MAX_VALUE, null);
            } else {
                message(sender, "Открой /report list в игре, чтобы посмотреть жалобы.", NamedTextColor.YELLOW);
            }
            return true;
        }
        if (!(sender instanceof Player player) || args.length != 1) return false;

        OfflinePlayer target = Bukkit.getPlayerExact(args[0]);
        if (target == null) target = Bukkit.getOfflinePlayerIfCached(args[0]);
        if (target == null || target.getName() == null) {
            message(player, "Игрок с таким ником не найден.", NamedTextColor.RED);
            return true;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            message(player, "Нельзя пожаловаться на себя.", NamedTextColor.RED);
            return true;
        }
        player.openInventory(new ReasonMenu(target.getUniqueId(), target.getName()).getInventory());
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        var matches = new java.util.TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        if (sender.isOp() && "list".startsWith(prefix)) matches.add("list");
        for (Player candidate : Bukkit.getOnlinePlayers()) {
            if (sender instanceof Player player && (player == candidate || !player.canSee(candidate))) continue;
            if (candidate.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) matches.add(candidate.getName());
        }
        return List.copyOf(matches);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof Menu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize()
            || (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT)) return;

        if (menu instanceof ReasonMenu reasonMenu) {
            ReportReason reason = ReportReason.atSlot(event.getRawSlot());
            if (reason != null && !reasonMenu.submitted) submit(player, reasonMenu, reason);
        } else if (menu instanceof ListMenu listMenu && player.isOp() && !listMenu.loading) {
            int slot = event.getRawSlot();
            ReportStore.Page page = listMenu.page;
            if (slot == PREVIOUS && page.number() > 1) openList(player, page.number() - 1, page.newestId(), listMenu);
            if (slot == NEXT && page.more()) openList(player, page.number() + 1, page.newestId(), listMenu);
            if(slot<page.entries().size())player.openInventory(new ReviewMenu(page.entries().get(slot)).getInventory());
        } else if(menu instanceof ReviewMenu review&&player.isOp()&&!review.pending){
            int slot=event.getRawSlot();if(slot==45){openList(player,1,Long.MAX_VALUE,null);return;}
            if((slot!=21&&slot!=23)||!review.entry.status().equals("OPEN"))return;
            review.pending=true;String status=slot==21?"ACCEPTED":"REJECTED";
            if(!runDatabase(()->reports.review(review.entry.id(),status,player.getName()),changed->{
                review.pending=false;messageIfOnline(player,changed?(status.equals("ACCEPTED")?"Жалоба принята.":"Жалоба отклонена."):"Эту жалобу уже рассмотрел другой оператор.",NamedTextColor.GREEN);
                if(player.isOnline()&&player.isOp()&&player.getOpenInventory().getTopInventory().getHolder(false)==review)openList(player,1,Long.MAX_VALUE,null);
            },()->{review.pending=false;messageIfOnline(player,"Не удалось сохранить решение. Попробуй ещё раз.");},player))review.pending=false;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Menu) event.setCancelled(true);
    }

    private void submit(Player player, ReasonMenu menu, ReportReason reason) {
        var report = new ReportStore.Draft(player.getUniqueId(), player.getName(), menu.targetId,
            menu.target, reason, System.currentTimeMillis());
        menu.submitted = runDatabase(() -> {
            reports.save(report);
            return report;
        }, saved -> {
            Player reporter = Bukkit.getPlayer(saved.reporterId());
            if (reporter != null) {
                message(reporter, "Жалоба на " + saved.target() + " отправлена. Причина — " + saved.reason().title + ".",
                    NamedTextColor.GREEN);
            }
        }, () -> messageIfOnline(player, "Не удалось сохранить жалобу. Попробуй ещё раз."), player);
        if (menu.submitted) Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder(false) == menu) {
                player.closeInventory();
            }
        });
    }

    private void openList(Player player, int number, long newestId, ListMenu from) {
        var oldView = player.getOpenInventory();
        boolean queued = runDatabase(() -> reports.read(number, newestId), page -> {
            if (from != null) from.loading = false;
            if (player.isOnline() && player.isOp() && player.getOpenInventory() == oldView) {
                player.openInventory(new ListMenu(page).getInventory());
            }
        }, () -> {
            if (from != null) from.loading = false;
            messageIfOnline(player, "Не удалось открыть жалобы. Попробуй ещё раз.");
        }, player);
        if (from != null) from.loading = queued;
    }

    private <T> boolean runDatabase(Callable<T> job, Consumer<T> success, Runnable failure, CommandSender sender) {
        try {
            database.execute(() -> {
                Runnable reply;
                try {
                    T result = job.call();
                    reply = () -> success.accept(result);
                } catch (Exception exception) {
                    getLogger().log(Level.SEVERE, "Ошибка базы жалоб", exception);
                    reply = failure;
                }
                try {
                    Bukkit.getScheduler().runTask(this, reply);
                } catch (IllegalPluginAccessException ignored) {
                    // The database can finish an already accepted write during shutdown.
                }
            });
            return true;
        } catch (RejectedExecutionException exception) {
            message(sender, "Список жалоб сейчас занят. Попробуй чуть позже.", NamedTextColor.RED);
            return false;
        }
    }

    private static final class ReasonMenu extends Menu {
        final UUID targetId;
        final String target;
        boolean submitted;

        ReasonMenu(UUID targetId, String target) {
            super(27, "Жалоба на " + target);
            this.targetId = targetId;
            this.target = target;
            for (ReportReason reason : ReportReason.values()) {
                getInventory().setItem(reason.slot, icon(reason.icon, reason.title, NamedTextColor.YELLOW, reason.details));
            }
        }
    }

    private static final class ListMenu extends Menu {
        final ReportStore.Page page;
        boolean loading;

        ListMenu(ReportStore.Page page) {
            super(54, "Жалобы игроков");
            this.page = page;
            Inventory inventory = getInventory();
            int slot = 0;
            for (ReportStore.Entry entry : page.entries()) {
                inventory.setItem(slot++, icon(entry.reason().icon, "#" + entry.id() + " · " + entry.target(), entry.status().equals("OPEN")?NamedTextColor.GOLD:entry.status().equals("ACCEPTED")?NamedTextColor.GREEN:NamedTextColor.GRAY,
                    "Причина — " + entry.reason().title, "Автор — " + entry.reporter(), timestamp(entry.time()),"Статус: "+status(entry.status()),"Нажми, чтобы рассмотреть"));
            }
            if (page.entries().isEmpty()) {
                inventory.setItem(22, icon(Material.BOOK, "Жалоб пока нет", NamedTextColor.GRAY));
            }
            if (page.number() > 1) inventory.setItem(PREVIOUS, icon(Material.ARROW, "Предыдущая страница", NamedTextColor.AQUA));
            if (page.more()) inventory.setItem(NEXT, icon(Material.ARROW, "Следующая страница", NamedTextColor.AQUA));
            inventory.setItem(49, icon(Material.COMPASS, "Страница " + page.number(), NamedTextColor.WHITE));
        }
    }

    private static String status(String value){return switch(value){case "ACCEPTED"->"Принята";case "REJECTED"->"Отклонена";default->"Ожидает решения";};}
    private static final class ReviewMenu extends Menu {
        final ReportStore.Entry entry;boolean pending;
        ReviewMenu(ReportStore.Entry entry){super(54,"Рассмотрение жалобы #"+entry.id());this.entry=entry;Inventory inv=getInventory();
            inv.setItem(13,icon(entry.reason().icon,entry.target(),NamedTextColor.GOLD,"Автор: "+entry.reporter(),"Причина: "+entry.reason().title,"Статус: "+status(entry.status()),entry.reviewer()==null?"Решение ещё не принято":"Оператор: "+entry.reviewer()));
            if(entry.status().equals("OPEN")){inv.setItem(21,icon(Material.LIME_CONCRETE,"Принять жалобу",NamedTextColor.GREEN,"Подтвердить жалобу и сохранить решение"));inv.setItem(23,icon(Material.RED_CONCRETE,"Отклонить жалобу",NamedTextColor.RED,"Закрыть жалобу без принятия"));}
            inv.setItem(45,icon(Material.ARROW,"Назад к списку",NamedTextColor.AQUA));
        }
    }

    private abstract static class Menu implements InventoryHolder {
        private final Inventory inventory;

        Menu(int size, String title) {
            inventory = Bukkit.createInventory(this, size, Component.text(title));
        }

        @Override
        public final Inventory getInventory() {
            return inventory;
        }
    }

    private static ItemStack icon(Material material, String title, NamedTextColor color, String... details) {
        ItemStack item = ItemStack.of(material);
        item.editMeta(meta -> {
            meta.itemName(Component.text(title, color));
            meta.lore(java.util.Arrays.stream(details)
                .map(line -> Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)).toList());
        });
        return item;
    }

    private static String timestamp(long millis) {
        return DATE.format(Instant.ofEpochMilli(millis));
    }

    private static void messageIfOnline(Player player, String text) {
        if (player.isOnline()) message(player, text, NamedTextColor.RED);
    }
    private static void messageIfOnline(Player player,String text,NamedTextColor color){if(player.isOnline())message(player,text,color);}

    private static void message(CommandSender sender, String text, NamedTextColor color) {
        sender.sendMessage(Component.text(text, color));
    }
}
