package saki4.skantibot;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class SKAntiBot extends JavaPlugin implements Listener {

    private final Map<UUID, CaptchaSession> activeCaptchas = new HashMap<>();
    private final List<Material> captchaMaterials = Arrays.asList(
            Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE,
            Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS,
            Material.DIAMOND_SWORD, Material.DIAMOND_PICKAXE,
            Material.DIAMOND_AXE, Material.DIAMOND_SHOVEL,
            Material.DIAMOND_BLOCK, Material.DIAMOND
    );

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("§8========================================");
        getLogger().info("§aSKAntiBot §fактивирован!");
        getLogger().info("§fПлагин разработан студией: §bSkyKnock dev");
        getLogger().info("§fСсылка: §dhttps://t.me/skyknockdev");
        getLogger().info("§8========================================");
    }

    @Override
    public void onDisable() {
        activeCaptchas.values().forEach(CaptchaSession::cancel);
        activeCaptchas.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        startCaptcha(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        CaptchaSession session = activeCaptchas.remove(e.getPlayer().getUniqueId());
        if (session != null) session.cancel();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player player = (Player) e.getWhoClicked();

        CaptchaSession session = activeCaptchas.get(player.getUniqueId());
        if (session == null) return;

        e.setCancelled(true);

        if (e.getClickedInventory() == null || e.getClickedInventory().equals(player.getInventory())) return;

        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        if (clicked.getType() == session.getTargetMaterial()) {
            session.decreaseClicks();
            if (session.getClicksLeft() <= 0) {
                session.cancel();
                activeCaptchas.remove(player.getUniqueId());
                player.closeInventory();
                player.sendMessage(ChatColor.GREEN + "Проверка пройдена!");
            } else {
                session.setSwitching(true);
                Bukkit.getScheduler().runTask(this, () -> {
                    openCaptchaGUI(player, session);
                    session.setSwitching(false);
                });
            }
        } else {
            kickPlayer(player, color(getConfig().getString("messages.kick_wrong")));
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        Player player = (Player) e.getPlayer();
        CaptchaSession session = activeCaptchas.get(player.getUniqueId());

        if (session != null && !session.isSwitching()) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (player.isOnline() && activeCaptchas.containsKey(player.getUniqueId())) {
                    player.openInventory(session.getCurrentInventory());
                }
            }, 1L);
        }
    }

    private void startCaptcha(Player player) {
        int time = getConfig().getInt("time", 90);
        int clicks = getConfig().getInt("click", 3);

        // Получаем цвет из конфига
        String colorStr = getConfig().getString("bosscolor", "RED").toUpperCase();
        BarColor barColor;
        try {
            barColor = BarColor.valueOf(colorStr);
        } catch (Exception ex) {
            barColor = BarColor.RED; // Дефолт если ввели ерунду
        }

        CaptchaSession session = new CaptchaSession(player, time, clicks, barColor);
        activeCaptchas.put(player.getUniqueId(), session);
        openCaptchaGUI(player, session);
    }

    private void openCaptchaGUI(Player player, CaptchaSession session) {
        Material target = captchaMaterials.get(new Random().nextInt(captchaMaterials.size()));
        session.setTargetMaterial(target);

        String itemName = getConfig().getString("items." + target.name(), target.name());
        String guiTitle = color(getConfig().getString("messages.gui_title").replace("%item%", itemName));

        Inventory gui = Bukkit.createInventory(null, 54, guiTitle);
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < 54; i++) slots.add(i);
        Collections.shuffle(slots);

        Random rand = new Random();
        for (int i = 0; i < 54; i++) {
            Material mat = (i == 0) ? target : captchaMaterials.get(rand.nextInt(captchaMaterials.size()));
            gui.setItem(slots.get(i), createItem(mat));
        }

        session.setCurrentInventory(gui);
        player.openInventory(gui);
    }

    private ItemStack createItem(Material mat) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color("&f" + getConfig().getString("items." + mat.name(), mat.name())));
            item.setItemMeta(meta);
        }
        return item;
    }

    private void kickPlayer(Player p, String reason) {
        CaptchaSession session = activeCaptchas.remove(p.getUniqueId());
        if (session != null) session.cancel();
        Bukkit.getScheduler().runTask(this, () -> p.kickPlayer(reason));
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private class CaptchaSession {
        private final Player player;
        private int timeLeft, clicksLeft;
        private Material targetMaterial;
        private Inventory currentInventory;
        private final BossBar bossBar;
        private BukkitTask task;
        private boolean isSwitching = false;

        public CaptchaSession(Player player, int time, int clicks, BarColor color) {
            this.player = player;
            this.timeLeft = time;
            this.clicksLeft = clicks;
            this.bossBar = Bukkit.createBossBar(updateTitle(), color, BarStyle.SOLID);
            this.bossBar.addPlayer(player);
            start();
        }

        private String updateTitle() {
            return color(getConfig().getString("bossbar").replace("%times%", String.valueOf(timeLeft)));
        }

        private void start() {
            task = new BukkitRunnable() {
                @Override
                public void run() {
                    timeLeft--;
                    if (timeLeft <= 0) {
                        kickPlayer(player, color(getConfig().getString("messages.kick_timeout")));
                        cancel();
                        return;
                    }
                    bossBar.setTitle(updateTitle());
                    bossBar.setProgress(Math.max(0.0, (double) timeLeft / getConfig().getInt("time", 90)));
                }
            }.runTaskTimer(SKAntiBot.this, 20L, 20L);
        }

        public void cancel() {
            if (task != null) task.cancel();
            bossBar.removeAll();
        }

        public Material getTargetMaterial() { return targetMaterial; }
        public void setTargetMaterial(Material m) { this.targetMaterial = m; }
        public int getClicksLeft() { return clicksLeft; }
        public void decreaseClicks() { this.clicksLeft--; }
        public Inventory getCurrentInventory() { return currentInventory; }
        public void setCurrentInventory(Inventory inv) { this.currentInventory = inv; }
        public boolean isSwitching() { return isSwitching; }
        public void setSwitching(boolean switching) { isSwitching = switching; }
    }
}