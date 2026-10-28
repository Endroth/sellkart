package com.servidor.sell;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.text.NumberFormat;
import java.util.*;

public class SellPlugin extends JavaPlugin implements Listener {

    private static final int BUTTON = 53;

    private final Map<Material, Double> prices = new EnumMap<>(Material.class);
    private final Set<Material> blockedExact = EnumSet.noneOf(Material.class);
    private final List<String> blockedSuffix = new ArrayList<>();
    private double defaultPrice, bow, bowFlame, crossbow, crossbowQuick;
    private boolean sellOnClose;
    private String fallbackCommand;
    private Economy economy;
    private final NumberFormat fmt = NumberFormat.getNumberInstance(new Locale("pt", "BR"));

    /** Marca o inventário como sendo o menu de venda. */
    private static class SellHolder implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        fmt.setMaximumFractionDigits(2);
        fmt.setMinimumFractionDigits(0);
        loadSettings();
        setupVault();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("SellPlugin ligado. Economia: " + (economy != null ? "Vault" : "comando fallback"));
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof SellHolder) p.closeInventory();
        }
    }

    private void setupVault() {
        economy = null;
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return;
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp != null) economy = rsp.getProvider();
    }

    private void loadSettings() {
        reloadConfig();
        var c = getConfig();
        prices.clear();
        blockedExact.clear();
        blockedSuffix.clear();

        defaultPrice = c.getDouble("default-price", 1);
        sellOnClose = c.getBoolean("sell-on-close", true);
        fallbackCommand = c.getString("fallback-command", "eco give {player} {amount}");
        bow = c.getDouble("special.bow", 100);
        bowFlame = c.getDouble("special.bow-flame", 12000);
        crossbow = c.getDouble("special.crossbow", 100);
        crossbowQuick = c.getDouble("special.crossbow-quick-charge", 10000);

        for (String s : c.getStringList("blocked")) {
            if (s.startsWith("*")) {
                blockedSuffix.add(s.substring(1).toUpperCase());
            } else {
                Material m = Material.matchMaterial(s);
                if (m != null) blockedExact.add(m);
            }
        }
        var sec = c.getConfigurationSection("prices");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                Material m = Material.matchMaterial(key);
                if (m == null) { getLogger().warning("Item inválido em prices: " + key); continue; }
                prices.put(m, sec.getDouble(key));
            }
        }
    }

    private boolean isBlocked(Material m) {
        if (blockedExact.contains(m)) return true;
        String n = m.name();
        for (String suf : blockedSuffix) if (n.endsWith(suf)) return true;
        return false;
    }

    /** Preço de UMA unidade do item (0 = não vende). */
    private double unitPrice(ItemStack it) {
        if (it == null) return 0;
        Material m = it.getType();
        if (m.isAir() || !m.isItem() || isBlocked(m)) return 0;

        if (m == Material.BOW)
            return it.containsEnchantment(Enchantment.FLAME) ? bowFlame : bow;
        if (m == Material.CROSSBOW)
            return it.containsEnchantment(Enchantment.QUICK_CHARGE) ? crossbowQuick : crossbow;

        return prices.getOrDefault(m, defaultPrice);
    }

    private double totalValue(Inventory inv) {
        double total = 0;
        for (int i = 0; i < BUTTON; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null) total += unitPrice(it) * it.getAmount();
        }
        return total;
    }

    private String money(double v) { return "$" + fmt.format(v); }

    private ItemStack buildButton(double total) {
        ItemStack b = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta meta = b.getItemMeta();
        meta.displayName(Component.text("Vender itens", NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Você vai ganhar: ", NamedTextColor.GRAY)
                        .append(Component.text(money(total), NamedTextColor.GREEN))
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Clique para vender", NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)));
        b.setItemMeta(meta);
        return b;
    }

    private void open(Player p) {
        SellHolder holder = new SellHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, Component.text("Sell"));
        holder.inv = inv;
        inv.setItem(BUTTON, buildButton(0));
        p.openInventory(inv);
    }

    private void refreshButton(Inventory inv) {
        inv.setItem(BUTTON, buildButton(totalValue(inv)));
    }

    /** Vende tudo que for vendável; devolve o resto. */
    private void sell(Player p, Inventory inv) {
        double total = 0;
        long count = 0;
        List<ItemStack> giveBack = new ArrayList<>();

        for (int i = 0; i < BUTTON; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            double unit = unitPrice(it);
            if (unit <= 0) {
                giveBack.add(it);
            } else {
                total += unit * it.getAmount();
                count += it.getAmount();
            }
            inv.setItem(i, null);
        }

        for (ItemStack it : giveBack) {
            Map<Integer, ItemStack> left = p.getInventory().addItem(it);
            for (ItemStack rest : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }

        if (!giveBack.isEmpty()) {
            p.sendMessage(Component.text("Alguns itens não podem ser vendidos e foram devolvidos.", NamedTextColor.RED));
        }

        if (total > 0) {
            deposit(p, total);
            p.sendMessage(Component.text("Você vendeu " + fmt.format(count) + " itens por ", NamedTextColor.GREEN)
                    .append(Component.text(money(total), NamedTextColor.DARK_GREEN)
                            .decoration(TextDecoration.BOLD, true)));
        }
    }

    private void deposit(Player p, double amount) {
        if (economy != null) {
            economy.depositPlayer(p, amount);
        } else {
            String cmd = fallbackCommand
                    .replace("{player}", p.getName())
                    .replace("{amount}", String.valueOf(Math.round(amount * 100.0) / 100.0));
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        }
    }

    // ---------------- comando ----------------

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!s.hasPermission("sell.admin")) {
                s.sendMessage(Component.text("Sem permissão.", NamedTextColor.RED));
                return true;
            }
            loadSettings();
            setupVault();
            s.sendMessage(Component.text("Configuração recarregada.", NamedTextColor.GREEN));
            return true;
        }
        if (!(s instanceof Player p)) {
            s.sendMessage("Só jogadores podem usar esse comando.");
            return true;
        }
        if (!p.hasPermission("sell.use")) {
            p.sendMessage(Component.text("Sem permissão.", NamedTextColor.RED));
            return true;
        }
        open(p);
        return true;
    }

    // ---------------- eventos do menu ----------------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof SellHolder)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;

        if (e.getClick() == ClickType.DOUBLE_CLICK || e.getAction() == org.bukkit.event.inventory.InventoryAction.COLLECT_TO_CURSOR) {
            e.setCancelled(true);
            return;
        }

        if (e.getRawSlot() == BUTTON) {
            e.setCancelled(true);
            sell(p, top);
            Bukkit.getScheduler().runTask(this, () -> p.closeInventory());
            return;
        }

        // atualiza o valor do botão depois que o clique for processado
        Bukkit.getScheduler().runTask(this, () -> refreshButton(top));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof SellHolder)) return;
        if (e.getRawSlots().contains(BUTTON)) {
            e.setCancelled(true);
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> refreshButton(top));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Inventory inv = e.getInventory();
        if (!(inv.getHolder() instanceof SellHolder)) return;
        if (!(e.getPlayer() instanceof Player p)) return;

        if (sellOnClose) {
            sell(p, inv);
        } else {
            // sem venda ao fechar: devolve tudo
            for (int i = 0; i < BUTTON; i++) {
                ItemStack it = inv.getItem(i);
                if (it == null) continue;
                for (ItemStack rest : p.getInventory().addItem(it).values())
                    p.getWorld().dropItemNaturally(p.getLocation(), rest);
                inv.setItem(i, null);
            }
        }
    }
}
