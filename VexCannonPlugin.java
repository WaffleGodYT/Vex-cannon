package com.wafflegod.vexcannon;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vex;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.stream.Stream;

public final class VexCannonPlugin extends JavaPlugin implements TabExecutor, Listener {

    /** One summoned vex: who it must attack and when it should vanish. */
    private record Summon(UUID targetId, long expireAtMs) {}

    private NamespacedKey cannonKey;
    private NamespacedKey recipeKey;

    private final Map<UUID, Long> lastUse = new HashMap<>();
    private final Map<UUID, Summon> summons = new HashMap<>();

    // Defaults, so everything still works even if the config can't be read
    private int vexCount = 8;
    private double lifetimeSeconds = 20.0;
    private long cooldownMs = 30_000L;
    private double range = 40.0;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        // 1. Command + listeners FIRST, so /vexcannon works even if something below fails
        PluginCommand cmd = getCommand("vexcannon");
        if (cmd == null) {
            getLogger().severe("Command 'vexcannon' is missing from plugin.yml. The plugin.yml inside the jar is wrong.");
        } else {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        getServer().getPluginManager().registerEvents(this, this);

        cannonKey = new NamespacedKey(this, "vex_cannon");
        recipeKey = new NamespacedKey(this, "vex_cannon_recipe");

        // 2. Config
        try {
            saveDefaultConfig();
            vexCount = Math.max(1, Math.min(32, getConfig().getInt("vex-count", 8)));
            lifetimeSeconds = Math.max(1.0, getConfig().getDouble("vex-lifetime-seconds", 20.0));
            cooldownMs = (long) (Math.max(0.0, getConfig().getDouble("cooldown-seconds", 30.0)) * 1000);
            range = Math.max(5.0, getConfig().getDouble("range", 40.0));
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "Problem reading config.yml, using default settings:", ex);
        }

        // 3. Recipe
        boolean registered = registerRecipe();
        if (registered) {
            getLogger().info("Vex Cannon recipe registered OK.");
            for (Player p : Bukkit.getOnlinePlayers()) p.discoverRecipe(recipeKey);
        } else {
            getLogger().severe("Vex Cannon recipe did NOT register. See any error above this line.");
        }

        // 4. Keeps vexes locked on their target and removes them when time is up
        Bukkit.getScheduler().runTaskTimer(this, this::tickSummons, 10L, 10L);

        getLogger().info("VexCannon enabled. Command registered: " + (cmd != null));
    }

    @Override
    public void onDisable() {
        for (UUID id : summons.keySet()) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        summons.clear();
        if (recipeKey != null) Bukkit.removeRecipe(recipeKey);
    }

    // ------------------------------------------------------------------ item + recipe

    private ItemStack createCannon() {
        ItemStack item = new ItemStack(Material.CROSSBOW);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.text("Vex Cannon", NamedTextColor.AQUA, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Right-click to unleash " + vexCount + " vexes", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("on the player you're aiming at.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.setUnbreakable(true);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(cannonKey, PersistentDataType.BYTE, (byte) 1);

        item.setItemMeta(meta);
        return item;
    }

    private boolean isCannon(ItemStack item) {
        return item != null
                && item.getType() == Material.CROSSBOW
                && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(cannonKey, PersistentDataType.BYTE);
    }

    /**
     * Registers the crafting recipe and reports whether it really exists afterwards.
     *
     *   S T S      S = Iron Sword     T = Totem of Undying
     *   D C D      D = Diamond        C = Crossbow
     *   S O S      O = Soul Sand
     */
    private boolean registerRecipe() {
        try {
            Bukkit.removeRecipe(recipeKey); // clear any stale copy first

            ShapedRecipe recipe = new ShapedRecipe(recipeKey, createCannon());
            recipe.shape("STS", "DCD", "SOS");
            recipe.setIngredient('S', Material.IRON_SWORD);
            recipe.setIngredient('T', Material.TOTEM_OF_UNDYING);
            recipe.setIngredient('D', Material.DIAMOND);
            recipe.setIngredient('C', Material.CROSSBOW);
            recipe.setIngredient('O', Material.SOUL_SAND);

            boolean added = Bukkit.addRecipe(recipe);
            boolean exists = Bukkit.getRecipe(recipeKey) != null;
            if (!added) getLogger().warning("Bukkit.addRecipe returned false.");
            return added && exists;
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "Exception while registering the recipe:", ex);
            return false;
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        e.getPlayer().discoverRecipe(recipeKey);
    }

    // ------------------------------------------------------------------ firing

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        if (!isCannon(e.getItem())) return;

        e.setCancelled(true); // don't start loading the crossbow
        Player p = e.getPlayer();
        if (!p.hasPermission("vexcannon.use")) return;

        long now = System.currentTimeMillis();
        Long last = lastUse.get(p.getUniqueId());
        if (last != null && now - last < cooldownMs) {
            long left = (cooldownMs - (now - last) + 999) / 1000;
            p.sendActionBar(Component.text("Vex Cannon recharging: " + left + "s", NamedTextColor.GRAY));
            return;
        }

        Player target = findTarget(p);
        if (target == null) {
            p.sendActionBar(Component.text("No player in range.", NamedTextColor.RED));
            return; // fizzle, cooldown not used
        }

        lastUse.put(p.getUniqueId(), now);
        summonVexes(p, target);
        p.sendActionBar(Component.text("Vexes released at " + target.getName() + "!", NamedTextColor.AQUA));
    }

    /** Safety net: if the crossbow ever gets loaded and shot some other way, don't fire an arrow. */
    @EventHandler
    public void onShoot(EntityShootBowEvent e) {
        if (isCannon(e.getBow())) e.setCancelled(true);
    }

    /** The player in the crosshair, or failing that the nearest other player within range. */
    private Player findTarget(Player user) {
        Location eye = user.getEyeLocation();
        RayTraceResult hit = user.getWorld().rayTrace(eye, eye.getDirection(), range,
                FluidCollisionMode.NEVER, true, 1.0,
                en -> en instanceof Player cand
                        && !cand.equals(user)
                        && cand.getGameMode() != GameMode.SPECTATOR);
        if (hit != null && hit.getHitEntity() instanceof Player found) return found;

        Player nearest = null;
        double best = range * range;
        for (Player other : user.getWorld().getPlayers()) {
            if (other.equals(user) || other.getGameMode() == GameMode.SPECTATOR) continue;
            double d = other.getLocation().distanceSquared(user.getLocation());
            if (d <= best) {
                best = d;
                nearest = other;
            }
        }
        return nearest;
    }

    private void summonVexes(Player user, Player target) {
        World world = user.getWorld();
        Location base = user.getLocation();
        long expireAt = System.currentTimeMillis() + (long) (lifetimeSeconds * 1000);

        for (int i = 0; i < vexCount; i++) {
            double angle = 2 * Math.PI * i / vexCount;
            Location spawn = base.clone().add(Math.cos(angle) * 2.5, 1.5 + (i % 2) * 0.8, Math.sin(angle) * 2.5);

            Vex vex = world.spawn(spawn, Vex.class, v -> {
                v.setPersistent(false);
                v.setTarget(target);
            });
            summons.put(vex.getUniqueId(), new Summon(target.getUniqueId(), expireAt));
            world.spawnParticle(Particle.SOUL, spawn, 10, 0.3, 0.3, 0.3, 0.02);
        }

        world.playSound(base, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.5f, 1.0f);
        world.playSound(base, Sound.ENTITY_VEX_CHARGE, 1.5f, 1.0f);
    }

    // ------------------------------------------------------------------ vex control

    /** Runs twice a second: re-aims every vex at its assigned player and removes expired ones. */
    private void tickSummons() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Summon>> it = summons.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Summon> entry = it.next();
            Summon s = entry.getValue();

            Entity ent = Bukkit.getEntity(entry.getKey());
            if (!(ent instanceof Vex vex) || !vex.isValid()) {
                it.remove(); // already dead or gone
                continue;
            }

            Player target = Bukkit.getPlayer(s.targetId());
            boolean done = now >= s.expireAtMs()
                    || target == null
                    || target.isDead()
                    || !target.getWorld().equals(vex.getWorld());
            if (done) {
                vex.getWorld().spawnParticle(Particle.SOUL, vex.getLocation(), 8, 0.2, 0.2, 0.2, 0.02);
                vex.remove();
                it.remove();
                continue;
            }

            vex.setTarget(target);
        }
    }

    /** Stops summoned vexes from turning on whoever fired the cannon. */
    @EventHandler
    public void onTarget(EntityTargetEvent e) {
        Summon s = summons.get(e.getEntity().getUniqueId());
        if (s == null) return;

        Player assigned = Bukkit.getPlayer(s.targetId());
        if (assigned == null) {
            e.setCancelled(true);
            return;
        }
        if (!assigned.equals(e.getTarget())) e.setTarget(assigned);
    }

    // ------------------------------------------------------------------ commands

    private boolean isAdmin(CommandSender sender) {
        return sender.isOp() || sender.hasPermission("vexcannon.admin");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!isAdmin(sender)) {
            sender.sendMessage("§cYou need operator permission (vexcannon.admin) to use this command.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§7VexCannon is running. Usage: /vexcannon give [player] | /vexcannon recipe");
            return true;
        }

        // /vexcannon recipe -> checks the recipe exists, and tries to register it if it doesn't
        if (args[0].equalsIgnoreCase("recipe")) {
            boolean exists = Bukkit.getRecipe(recipeKey) != null;
            if (!exists) {
                sender.sendMessage("§eRecipe was missing, trying to register it now...");
                exists = registerRecipe();
            }
            if (exists) {
                sender.sendMessage("§aVex Cannon recipe is registered.");
                sender.sendMessage("§7Grid: Iron Sword, Totem of Undying, Iron Sword / Diamond, Crossbow, Diamond / Iron Sword, Soul Sand, Iron Sword");
                if (sender instanceof Player p) p.discoverRecipe(recipeKey);
            } else {
                sender.sendMessage("§cRecipe could NOT be registered. Check the server console for the error.");
            }
            return true;
        }

        // /vexcannon give [player]
        if (args[0].equalsIgnoreCase("give")) {
            Player recipient;
            if (args.length >= 2) {
                recipient = Bukkit.getPlayerExact(args[1]);
                if (recipient == null) {
                    sender.sendMessage("§cPlayer not found: " + args[1]);
                    return true;
                }
            } else if (sender instanceof Player self) {
                recipient = self;
            } else {
                sender.sendMessage("§cConsole must specify a player: /vexcannon give <player>");
                return true;
            }
            recipient.getInventory().addItem(createCannon()).values()
                    .forEach(left -> recipient.getWorld().dropItemNaturally(recipient.getLocation(), left));
            sender.sendMessage("§bGave a Vex Cannon to " + recipient.getName() + ".");
            return true;
        }

        sender.sendMessage("§7Usage: /vexcannon give [player] | /vexcannon recipe");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!isAdmin(sender)) return List.of();
        if (args.length == 1) {
            return Stream.of("give", "recipe")
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}
