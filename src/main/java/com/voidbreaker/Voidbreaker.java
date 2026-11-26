package com.voidbreaker;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.*;

public final class Voidbreaker extends JavaPlugin implements Listener, TabExecutor {

    static final Particle.DustOptions BLACK = new Particle.DustOptions(Color.fromRGB(6, 0, 10), 2.0f);
    static final Particle.DustOptions BIG = new Particle.DustOptions(Color.fromRGB(4, 0, 8), 3.0f);
    static final Particle.DustTransition PURPLE = new Particle.DustTransition(Color.fromRGB(70, 20, 130), Color.fromRGB(5, 0, 10), 1.8f);
    static final PersistentDataType<Byte, Byte> B = PersistentDataType.BYTE;

    record TK(UUID owner, UUID victim, long start) {}

    static class Flight {
        Player owner, victim;
        Location pos, dest;
        ItemDisplay disp;
        ItemStack item;
        int slot, ticks, landedAt = -1;
        float angle;
        boolean returned;
    }

    NamespacedKey KEY, HP, SKULL, SKEL, RECIPE;
    YamlConfiguration data;
    File dataFile;
    final Map<UUID, Long> skullCd = new HashMap<>(), lock = new HashMap<>(), phantomCd = new HashMap<>(), lastSprint = new HashMap<>();
    final Set<UUID> noFx = new HashSet<>();
    final Set<UUID> holed = new HashSet<>();
    final Map<UUID, TK> tks = new HashMap<>();
    final Map<UUID, UUID> held = new HashMap<>(), skel = new HashMap<>();
    final List<Flight> flights = new ArrayList<>();
    final List<WitherSkull> skulls = new ArrayList<>();
    final Set<UUID> skullHit = new HashSet<>();
    final Random R = new Random();
    int tc = 0;

    // ---------------------------------------------------------------- setup

    @Override
    public void onEnable() {
        saveDefaultConfig();
        KEY = new NamespacedKey(this, "voidbreaker");
        HP = new NamespacedKey(this, "vb_hearts");
        SKULL = new NamespacedKey(this, "vb_skull");
        SKEL = new NamespacedKey(this, "vb_skel");
        RECIPE = new NamespacedKey(this, "voidbreaker_recipe");
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        try { Bukkit.removeRecipe(RECIPE); } catch (Exception ignored) {}
        ShapedRecipe r = new ShapedRecipe(RECIPE, makeItem());
        r.shape("SNS", "NEN", "SNS");
        r.setIngredient('S', Material.WITHER_SKELETON_SKULL);
        r.setIngredient('N', Material.NETHERITE_INGOT);
        r.setIngredient('E', Material.DRAGON_EGG);
        Bukkit.addRecipe(r);
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("mace").setExecutor(this);
        new BukkitRunnable() { public void run() { tick(); } }.runTaskTimer(this, 1, 1);
    }

    @Override
    public void onDisable() {
        for (UUID o : new ArrayList<>(tks.keySet())) stopTK(o);
        for (Flight f : new ArrayList<>(flights)) giveBack(f);
        for (UUID id : skel.keySet()) { Entity e = Bukkit.getEntity(id); if (e != null) e.remove(); }
        for (Player p : Bukkit.getOnlinePlayers()) {
            release(p);
            AttributeInstance ai = p.getAttribute(Attribute.MAX_HEALTH);
            if (ai != null && ai.getModifier(HP) != null) ai.removeModifier(HP);
        }
    }

    void saveData() { try { data.save(dataFile); } catch (IOException ex) { getLogger().warning("data.yml save failed"); } }

    String name() { return getConfig().getString("name", "Voidbreaker"); }

    ItemStack makeItem() {
        ItemStack it = new ItemStack(Material.MACE);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text(name(), NamedTextColor.DARK_PURPLE).decoration(TextDecoration.ITALIC, false).decorate(TextDecoration.BOLD));
        m.lore(List.of(Component.text("Darkness bends to its wielder.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        m.setUnbreakable(true);
        m.addEnchant(Enchantment.WIND_BURST, 3, true);
        m.addEnchant(Enchantment.DENSITY, 5, true);
        String mdl = getConfig().getString("model", "");
        if (!mdl.isEmpty()) { NamespacedKey mk = NamespacedKey.fromString(mdl); if (mk != null) m.setItemModel(mk); }
        m.getPersistentDataContainer().set(KEY, B, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean isVB(ItemStack it) {
        return it != null && it.getType() == Material.MACE && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(KEY, B);
    }

    // ---------------------------------------------------------------- helpers

    double cfg(String p) { return getConfig().getDouble(p); }
    double cfgd(String p, double def) { return getConfig().getDouble(p, def); }
    long left(Map<UUID, Long> m, UUID u) { Long t = m.get(u); return t == null ? 0 : Math.max(0, t - System.currentTimeMillis()); }
    long sec(long ms) { return (ms + 999) / 1000; }
    void msg(Player p, String s) { p.sendActionBar(Component.text(s, NamedTextColor.RED)); }
    boolean fr(Entity e) { return e instanceof Player p && held.containsKey(p.getUniqueId()); }
    boolean hl(Entity e) { return e instanceof Player p && holed.contains(p.getUniqueId()); }

    boolean hasVB(Player p) {
        if (isVB(p.getInventory().getItemInMainHand()) || isVB(p.getInventory().getItemInOffHand())) return true;
        for (Flight f : flights) if (f.owner.equals(p)) return true;
        return false;
    }

    void release(Player p) {
        if (holed.remove(p.getUniqueId())) { p.setGravity(true); p.setFallDistance(0f); }
    }

    void snd(Location l, String key) {
        for (String s : getConfig().getStringList("sounds." + key)) {
            String[] a = s.split(",");
            try { l.getWorld().playSound(l, a[0].trim(), SoundCategory.PLAYERS, Float.parseFloat(a[1].trim()), Float.parseFloat(a[2].trim())); }
            catch (Exception ignored) {}
        }
    }

    void dark(Location l, int n, double s) {
        World w = l.getWorld();
        w.spawnParticle(Particle.DUST, l, n, s, s, s, 0, BLACK);
        w.spawnParticle(Particle.SMOKE, l, Math.max(1, n / 3), s, s, s, 0.03);
        w.spawnParticle(Particle.LARGE_SMOKE, l, Math.max(1, n / 6), s, s, s, 0.02);
        w.spawnParticle(Particle.SQUID_INK, l, Math.max(1, n / 6), s, s, s, 0.05);
    }

    void ring(Location c, double r, int n, double phase, Particle p, Object d) {
        for (int i = 0; i < n; i++) {
            double a = phase + i * 2 * Math.PI / n;
            Location l = c.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r);
            if (d == null) c.getWorld().spawnParticle(p, l, 1, 0, 0, 0, 0);
            else c.getWorld().spawnParticle(p, l, 1, 0, 0, 0, 0, d);
        }
    }

    void bigBurst(Location l) {
        dark(l.clone().add(0, 0.5, 0), 400, 1.2);
        new BukkitRunnable() {
            int i = 0;
            public void run() {
                i++;
                double r = i * 0.8;
                Location c = l.clone().add(0, 0.3, 0);
                ring(c, r, Math.min(120, (int) (r * 14)), 0, Particle.DUST, BIG);
                ring(c, r, Math.min(60, (int) (r * 7)), 0.3, Particle.LARGE_SMOKE, null);
                if (i >= 8) cancel();
            }
        }.runTaskTimer(this, 0, 2);
    }

    Location hand(Player p) { return p.getLocation().add(0, 1.0, 0).add(p.getLocation().getDirection().multiply(0.6)); }

    Player lookTarget(Player p, double range) {
        RayTraceResult r = p.getWorld().rayTraceEntities(p.getEyeLocation(), p.getEyeLocation().getDirection(), range, 0.7,
                e -> e instanceof Player t && !t.equals(p) && t.getGameMode() != GameMode.SPECTATOR);
        return r == null ? null : (Player) r.getHitEntity();
    }

    // ---------------------------------------------------------------- tick

    void tick() {
        tc++;
        long nowMs = System.currentTimeMillis();
        for (Player sp : Bukkit.getOnlinePlayers()) if (sp.isSprinting()) lastSprint.put(sp.getUniqueId(), nowMs);
        if (tc % 10 == 0) for (Player p : Bukkit.getOnlinePlayers()) holder(p);
        if (tc % 5 == 0) for (Player p : Bukkit.getOnlinePlayers()) if (isVB(p.getInventory().getItemInMainHand())) hud(p);
        tickTK();
        tickFlights();
        skulls.removeIf(s -> {
            if (!s.isValid()) return true;
            dark(s.getLocation(), 8, 0.15);
            return false;
        });
        if (tc % 10 == 0) tickSkeletons();
    }

    void holder(Player p) {
        if (p.isDead()) return;
        boolean h = isVB(p.getInventory().getItemInMainHand());
        AttributeInstance ai = p.getAttribute(Attribute.MAX_HEALTH);
        boolean has = ai != null && ai.getModifier(HP) != null;
        if (h) {
            eff(p, PotionEffectType.REGENERATION, "effects.regeneration");
            eff(p, PotionEffectType.STRENGTH, "effects.strength");
            eff(p, PotionEffectType.JUMP_BOOST, "effects.jump-boost");
            if (ai != null && !has) {
                double extra = cfg("effects.extra-hearts") * 2;
                ai.addTransientModifier(new AttributeModifier(HP, extra, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
                p.setHealth(Math.min(ai.getValue(), p.getHealth() + extra));
            }
        } else {
            for (PotionEffectType t : List.of(PotionEffectType.REGENERATION, PotionEffectType.STRENGTH, PotionEffectType.JUMP_BOOST)) {
                PotionEffect e = p.getPotionEffect(t);
                if (e != null && e.isAmbient() && e.getDuration() <= 45) p.removePotionEffect(t);
            }
            if (has) ai.removeModifier(HP);
        }
    }

    void eff(Player p, PotionEffectType t, String path) {
        p.addPotionEffect(new PotionEffect(t, 40, getConfig().getInt(path), true, false, false));
    }

    void hud(Player p) {
        UUID u = p.getUniqueId();
        long lk = left(lock, u), sk = left(skullCd, u), ph = left(phantomCd, u);
        TK k = tks.get(u);
        String a = lk > 0 ? "§clocked" : sk > 0 ? "§f" + sec(sk) + "s" : "§aready";
        String b = k != null ? "§dactive " + Math.max(0, (long) cfg("telekinesis.max-seconds") - (System.currentTimeMillis() - k.start()) / 1000) + "s" : lk > 0 ? "§clocked" : "§aready";
        String c = lk > 0 ? "§clocked" : ph > 0 ? "§f" + sec(ph) + "s" : "§aready";
        String d = lk > 0 ? "§f" + sec(lk) + "s" : "§aready";
        p.sendActionBar(LegacyComponentSerializer.legacySection().deserialize("§5Soul Barrage: " + a + " §8| §5Telekinesis: " + b + " §8| §5Phantom Step: " + c + " §8| §5Void: " + d));
    }

    // ---------------------------------------------------------------- input

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        if (held.containsKey(u)) { e.setCancelled(true); return; }
        if (holed.contains(u)) {
            ItemStack food = e.getItem();
            boolean rc = e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK;
            if (rc && food != null && food.getType().isEdible()) e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            else e.setCancelled(true);
            return;
        }
        if (e.getHand() != EquipmentSlot.HAND || !isVB(p.getInventory().getItemInMainHand())) return;
        Action a = e.getAction();
        boolean air = !p.isOnGround() && !p.isFlying() && !p.isGliding() && !p.isInWater() && p.getVehicle() == null;
        if (a == Action.RIGHT_CLICK_AIR || (a == Action.RIGHT_CLICK_BLOCK && !e.getClickedBlock().getType().isInteractable())) {
            if (tks.containsKey(u)) { stopTK(u); return; }
            if (left(lock, u) > 0) { msg(p, "Void powers locked: " + sec(left(lock, u)) + "s"); return; }
            if (air && System.currentTimeMillis() - lastSprint.getOrDefault(u, 0L) < 1500) { phantomStep(p); return; }
            Player t = lookTarget(p, cfg("telekinesis.grab-range"));
            if (t != null && !held.containsKey(t.getUniqueId())) startTK(p, t); else castSkulls(p);
        } else if (a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK) {
            if (p.isSneaking()) attack3(p);
            else if (air) voidRift(p);
        }
    }

    // ---------------------------------------------------------------- attack 1: wither skulls

    void castSkulls(Player p) {
        UUID u = p.getUniqueId();
        if (left(skullCd, u) > 0) { msg(p, "Soul Barrage: " + sec(left(skullCd, u)) + "s"); return; }
        skullCd.put(u, System.currentTimeMillis() + (long) (cfg("skulls.cooldown") * 1000));
        Location eye = p.getEyeLocation();
        for (int i = -1; i <= 1; i++) {
            Vector d = eye.getDirection().rotateAroundY(Math.toRadians(i * 9));
            WitherSkull s = p.launchProjectile(WitherSkull.class, d.clone().multiply(1.2));
            s.setDirection(d);
            s.setIsIncendiary(false);
            s.setYield(0f);
            s.getPersistentDataContainer().set(SKULL, B, (byte) 1);
            skulls.add(s);
        }
        snd(p.getLocation(), "skulls");
        new BukkitRunnable() {
            int t = 0;
            public void run() {
                if (!p.isOnline() || ++t > 25) { cancel(); return; }
                Location h = hand(p);
                for (int k = 0; k < 3; k++) {
                    double a = t * 0.7 + k * 2.09;
                    Location l = h.clone().add(Math.cos(a) * 0.45, (t % 12) * 0.05, Math.sin(a) * 0.45);
                    p.getWorld().spawnParticle(Particle.DUST, l, 3, 0.05, 0.05, 0.05, 0, BLACK);
                    p.getWorld().spawnParticle(Particle.SMOKE, l, 1, 0, 0, 0, 0.01);
                    p.getWorld().spawnParticle(Particle.SOUL, l, 1, 0, 0, 0, 0.01);
                }
            }
        }.runTaskTimer(this, 0, 1);
    }

    @EventHandler
    public void onSkullPrime(ExplosionPrimeEvent e) {
        if (e.getEntity() instanceof WitherSkull w && w.getPersistentDataContainer().has(SKULL, B)) e.setCancelled(true);
    }

    @EventHandler
    public void onSkullHit(ProjectileHitEvent e) {
        if (e.getEntity() instanceof WitherSkull w && w.getPersistentDataContainer().has(SKULL, B)) {
            dark(w.getLocation(), 80, 0.5);
            snd(w.getLocation(), "hit");
            Bukkit.getScheduler().runTask(this, w::remove);
        }
    }

    @EventHandler
    public void onEffect(EntityPotionEffectEvent e) {
        if (e.getNewEffect() != null && e.getNewEffect().getType() == PotionEffectType.WITHER
                && e.getCause() == EntityPotionEffectEvent.Cause.ATTACK && skullHit.contains(e.getEntity().getUniqueId())
                && !getConfig().getBoolean("skulls.wither-effect")) e.setCancelled(true);
    }

    // ---------------------------------------------------------------- attack 2: telekinesis

    void startTK(Player o, Player v) {
        v.leaveVehicle();
        v.setGravity(false);
        tks.put(o.getUniqueId(), new TK(o.getUniqueId(), v.getUniqueId(), System.currentTimeMillis()));
        held.put(v.getUniqueId(), o.getUniqueId());
        snd(o.getLocation(), "tk-start");
    }

    void stopTK(UUID owner) {
        TK k = tks.remove(owner);
        if (k == null) return;
        held.remove(k.victim());
        Player v = Bukkit.getPlayer(k.victim());
        if (v != null) { v.setGravity(true); v.setFallDistance(0); }
        Player o = Bukkit.getPlayer(owner);
        if (o != null) snd(o.getLocation(), "tk-stop");
    }

    void tickTK() {
        for (UUID oid : new ArrayList<>(tks.keySet())) {
            TK k = tks.get(oid);
            Player o = Bukkit.getPlayer(oid), v = Bukkit.getPlayer(k.victim());
            if (o == null || v == null || o.isDead() || v.isDead() || v.getWorld() != o.getWorld()
                    || !isVB(o.getInventory().getItemInMainHand())
                    || System.currentTimeMillis() - k.start() > cfg("telekinesis.max-seconds") * 1000) { stopTK(oid); continue; }
            Location eye = o.getEyeLocation();
            Vector dir = eye.getDirection();
            double d = cfg("telekinesis.hold-distance");
            RayTraceResult rt = o.getWorld().rayTraceBlocks(eye, dir, d + 0.5, FluidCollisionMode.NEVER, true);
            if (rt != null) d = Math.max(1.0, eye.toVector().distance(rt.getHitPosition()) - 0.6);
            Location tgt = eye.clone().add(dir.clone().multiply(d)).subtract(0, 1.0, 0);
            tgt.setYaw(v.getLocation().getYaw());
            tgt.setPitch(v.getLocation().getPitch());
            v.setVelocity(new Vector(0, 0, 0));
            v.setFallDistance(0);
            v.teleport(tgt);
            if (tc % 2 == 0) {
                double ph = tc * 0.25;
                // victim: black dust ring + smoke under feet
                Location vf = v.getLocation().add(0, 0.1, 0);
                ring(vf, 0.9, 16, ph, Particle.DUST, BIG);
                v.getWorld().spawnParticle(Particle.SMOKE, vf, 4, 0.4, 0.05, 0.4, 0.01);
                // owner: purple-to-black transition ring + sculk souls (different darkness)
                Location of = o.getLocation().add(0, 0.1, 0);
                ring(of, 1.1, 16, -ph, Particle.DUST_COLOR_TRANSITION, PURPLE);
                o.getWorld().spawnParticle(Particle.SCULK_SOUL, of, 1, 0.5, 0.05, 0.5, 0.01);
                // dark line from hand to victim
                Location from = hand(o), to = v.getLocation().add(0, 1, 0);
                Vector line = to.toVector().subtract(from.toVector());
                double len = line.length();
                if (len > 0.1) {
                    line.normalize();
                    for (double s = 0; s < len; s += 0.5)
                        o.getWorld().spawnParticle(Particle.DUST, from.clone().add(line.clone().multiply(s)), 1, 0.05, 0.05, 0.05, 0, BLACK);
                }
            }
        }
    }

    // frozen victim restrictions
    @EventHandler public void fMove(PlayerMoveEvent e) { if (fr(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public v
