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
    long left(Map<UUID, Long> m, UUID u) { Long t = m.get(u); return t == null ? 0 : Math.max(0, t - System.currentTimeMillis()); }
    long sec(long ms) { return (ms + 999) / 1000; }
    void msg(Player p, String s) { p.sendActionBar(Component.text(s, NamedTextColor.RED)); }
    boolean fr(Entity e) { return e instanceof Player p && held.containsKey(p.getUniqueId()); }

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
            else if (air && p.getFallDistance() < 1.0f) voidRift(p);
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
    @EventHandler public void fBreak(BlockBreakEvent e) { if (fr(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void fPlace(BlockPlaceEvent e) { if (fr(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void fDrop(PlayerDropItemEvent e) { if (fr(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void fSwap(PlayerSwapHandItemsEvent e) { if (fr(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void fEnt(PlayerInteractEntityEvent e) { if (fr(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void fCons(PlayerItemConsumeEvent e) { if (fr(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void fHeld(PlayerItemHeldEvent e) { if (fr(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void fInv(InventoryClickEvent e) { if (fr(e.getWhoClicked())) e.setCancelled(true); }
    @EventHandler public void fOpen(InventoryOpenEvent e) { if (fr(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void fBow(EntityShootBowEvent e) { if (fr(e.getEntity())) e.setCancelled(true); }
    @EventHandler public void fProj(ProjectileLaunchEvent e) { if (e.getEntity().getShooter() instanceof Player p && fr(p)) e.setCancelled(true); }

    // ---------------------------------------------------------------- attack 3: crash

    void attack3(Player p) {
        UUID u = p.getUniqueId();
        if (left(lock, u) > 0) { msg(p, "Void powers locked: " + sec(left(lock, u)) + "s"); return; }
        for (Flight f : flights) if (f.owner.equals(p)) return;
        stopTK(u);
        lock.put(u, System.currentTimeMillis() + (long) (cfg("judgement.cooldown") * 1000));
        World w = p.getWorld();
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        double range = cfg("judgement.range");
        Player victim = lookTarget(p, range);
        Location dest = null;
        if (victim == null) {
            RayTraceResult br = w.rayTraceBlocks(eye, dir, range, FluidCollisionMode.NEVER, true);
            dest = br != null ? br.getHitPosition().toLocation(w) : eye.clone().add(dir.clone().multiply(range));
        }
        Flight f = new Flight();
        f.owner = p; f.victim = victim; f.dest = dest;
        f.slot = p.getInventory().getHeldItemSlot();
        f.item = p.getInventory().getItem(f.slot);
        p.getInventory().setItem(f.slot, null);
        f.pos = eye.clone().add(dir.clone().multiply(1.2));
        f.disp = w.spawn(f.pos, ItemDisplay.class, d -> {
            d.setItemStack(f.item.clone());
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setPersistent(false);
            d.setInterpolationDuration(1);
            d.setTeleportDuration(1);
            d.setBrightness(new Display.Brightness(15, 15));
        });
        flights.add(f);
        snd(p.getLocation(), "throw");
        dark(hand(p), 80, 0.4);
    }

    void tickFlights() {
        for (Iterator<Flight> it = flights.iterator(); it.hasNext(); ) {
            Flight f = it.next();
            if (f.returned) { it.remove(); continue; }
            f.ticks++;
            if (f.landedAt >= 0) { if (f.ticks - f.landedAt >= 20) { giveBack(f); it.remove(); } continue; }
            World w = f.pos.getWorld();
            boolean homing = f.victim != null && f.victim.isOnline() && !f.victim.isDead() && f.victim.getWorld() == w;
            Location target = homing ? f.victim.getLocation().add(0, 1, 0) : (f.dest != null ? f.dest : f.pos);
            Vector to = target.toVector().subtract(f.pos.toVector());
            double dist = to.length(), step = cfg("judgement.speed");
            Vector dir = dist > 0.0001 ? to.clone().normalize() : new Vector(0, 1, 0);
            boolean hit = dist <= step + 0.6;
            Location next = hit ? target.clone() : f.pos.clone().add(dir.clone().multiply(step));
            if (!homing && !hit) {
                RayTraceResult r = w.rayTraceBlocks(f.pos, dir, step + 0.3, FluidCollisionMode.NEVER, true);
                if (r != null) { next = r.getHitPosition().toLocation(w); hit = true; }
            }
            // dense dark trail along the path
            Vector seg = next.toVector().subtract(f.pos.toVector());
            double len = seg.length();
            if (len > 0.01) {
                seg.normalize();
                for (double s = 0; s < len; s += 0.5) {
                    Location pt = f.pos.clone().add(seg.clone().multiply(s));
                    dark(pt, 12, 0.3);
                    w.spawnParticle(Particle.SCULK_SOUL, pt, 1, 0.15, 0.15, 0.15, 0.01);
                }
            }
            f.pos = next;
            // spinning, Mjolnir style
            Vector3f axis = new Vector3f((float) -dir.getZ(), 0, (float) dir.getX());
            if (axis.lengthSquared() < 1e-4f) axis.set(1, 0, 0);
            axis.normalize();
            f.angle += 0.8f;
            f.disp.setTransformation(new Transformation(new Vector3f(), new Quaternionf(new AxisAngle4f(f.angle, axis.x, axis.y, axis.z)),
                    new Vector3f(2.2f, 2.2f, 2.2f), new Quaternionf()));
            f.disp.teleport(next);
            if (f.ticks % 4 == 1) snd(next, "flight");
            if (hit || f.ticks > 120) impact(f);
        }
    }

    void impact(Flight f) {
        f.landedAt = f.ticks;
        Location l = f.pos;
        f.disp.remove();
        snd(l, "impact");
        bigBurst(l);
        if (f.victim != null && f.victim.isOnline() && !f.victim.isDead() && f.victim.getWorld() == l.getWorld()) {
            f.victim.damage(cfg("judgement.damage"), f.owner);
            if (f.victim.isDead()) snd(f.victim.getLocation(), "kill");
            else spawnSkeletons(f.victim);
        }
        zone(l, f.owner);
    }

    void giveBack(Flight f) {
        if (f.returned) return;
        f.returned = true;
        if (f.disp != null) f.disp.remove();
        Player o = f.owner;
        if (o.isOnline() && !o.isDead()) {
            ItemStack cur = o.getInventory().getItem(f.slot);
            if (cur == null || cur.getType().isAir()) o.getInventory().setItem(f.slot, f.item);
            else o.getInventory().addItem(f.item).values().forEach(i -> o.getWorld().dropItemNaturally(o.getLocation(), i));
            snd(o.getLocation(), "tk-stop");
        } else {
            f.pos.getWorld().dropItemNaturally(f.pos, f.item);
        }
    }

    void zone(Location c, Player owner) {
        double r = cfg("judgement.zone-radius");
        int dur = (int) (cfg("judgement.zone-seconds") * 20), bl = (int) (cfg("judgement.blindness-seconds") * 20);
        new BukkitRunnable() {
            int t = 0;
            public void run() {
                t++;
                World w = c.getWorld();
                if (t % 2 == 0) {
                    for (int i = 0; i < 26; i++) {
                        double a = R.nextDouble() * 2 * Math.PI, rr = Math.sqrt(R.nextDouble()) * r;
                        Location pl = c.clone().add(Math.cos(a) * rr, R.nextDouble() * 2.5, Math.sin(a) * rr);
                        w.spawnParticle(Particle.DUST, pl, 2, 0.3, 0.3, 0.3, 0, BIG);
                        if (i % 3 == 0) w.spawnParticle(Particle.LARGE_SMOKE, pl, 1, 0.2, 0.2, 0.2, 0.01);
                    }
                    ring(c.clone().add(0, 0.2, 0), r, 40, t * 0.1, Particle.SQUID_INK, null);
                }
                if (t % 10 == 0) for (Player p : w.getPlayers()) {
                    if (p.equals(owner) || p.getGameMode() == GameMode.SPECTATOR) continue;
                    if (p.getLocation().distanceSquared(c) <= r * r && !p.hasPotionEffect(PotionEffectType.BLINDNESS))
                        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, bl, 0, false, true, true));
                }
                if (t >= dur) cancel();
            }
        }.runTaskTimer(this, 0, 1);
    }

    void spawnSkeletons(Player v) {
        World w = v.getWorld();
        Location base = v.getLocation();
        Vector back = base.getDirection().setY(0);
        if (back.lengthSquared() < 1e-4) back = new Vector(0, 0, 1);
        back.normalize().multiply(-2.5);
        Vector side = new Vector(-back.getZ(), 0, back.getX()).normalize();
        List<UUID> ids = new ArrayList<>();
        int n = getConfig().getInt("judgement.skeletons");
        for (int i = 0; i < n; i++) {
            Location sl = base.clone().add(back).add(side.clone().multiply((i - (n - 1) / 2.0) * 1.6));
            WitherSkeleton ws = w.spawn(sl, WitherSkeleton.class, s -> {
                s.setPersistent(false);
                s.setCanPickupItems(false);
                s.getEquipment().setItemInMainHandDropChance(0f);
                s.getEquipment().setItemInOffHandDropChance(0f);
                s.setTarget(v);
                s.getPersistentDataContainer().set(SKEL, B, (byte) 1);
            });
            skel.put(ws.getUniqueId(), v.getUniqueId());
            ids.add(ws.getUniqueId());
            dark(sl.clone().add(0, 1, 0), 60, 0.5);
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (UUID id : ids) {
                skel.remove(id);
                Entity e = Bukkit.getEntity(id);
                if (e != null) { dark(e.getLocation().add(0, 1, 0), 40, 0.4); e.remove(); }
            }
        }, (long) (cfg("judgement.skeleton-seconds") * 20));
    }

    void tickSkeletons() {
        for (Iterator<Map.Entry<UUID, UUID>> it = skel.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, UUID> en = it.next();
            Entity e = Bukkit.getEntity(en.getKey());
            Player v = Bukkit.getPlayer(en.getValue());
            if (!(e instanceof WitherSkeleton ws) || !ws.isValid()) { it.remove(); continue; }
            if (v == null || v.isDead() || v.getWorld() != ws.getWorld()) { ws.remove(); it.remove(); continue; }
            if (ws.getTarget() == null || !ws.getTarget().equals(v)) ws.setTarget(v);
        }
    }

    @EventHandler
    public void onTarget(EntityTargetEvent e) {
        UUID v = skel.get(e.getEntity().getUniqueId());
        if (v != null && e.getTarget() != null && !e.getTarget().getUniqueId().equals(v)) e.setCancelled(true);
    }

    @EventHandler
    public void onSkelDeath(EntityDeathEvent e) {
        if (skel.remove(e.getEntity().getUniqueId()) != null) { e.getDrops().clear(); e.setDroppedExp(0); }
    }

    // ---------------------------------------------------------------- phantom step

    void phantomStep(Player p) {
        UUID u = p.getUniqueId();
        if (left(phantomCd, u) > 0) { msg(p, "Phantom Step: " + sec(left(phantomCd, u)) + "s"); return; }
        World w = p.getWorld();
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        double dist = cfg("phantom.distance");
        RayTraceResult rt = w.rayTraceBlocks(eye, dir, dist, FluidCollisionMode.NEVER, true);
        double d = rt != null ? Math.max(0, eye.toVector().distance(rt.getHitPosition()) - 0.8) : dist;
        Location dest = null;
        for (double t = d; t >= 1.0; t -= 0.5) {
            Location c = eye.clone().add(dir.clone().multiply(t)).subtract(0, 1.62, 0);
            if (c.getBlock().isPassable() && c.clone().add(0, 1, 0).getBlock().isPassable()) { dest = c; break; }
        }
        if (dest == null) { msg(p, "No space to blink."); return; }
        Location from = p.getLocation();
        phantomCd.put(u, System.currentTimeMillis() + (long) (cfg("phantom.cooldown") * 1000));
        // purple ender trail between start and end
        Vector line = dest.toVector().subtract(from.toVector());
        double len = line.length();
        if (len > 0.1) {
            line.normalize();
            for (double s = 0; s < len; s += 0.4) {
                Location pt = from.clone().add(line.clone().multiply(s)).add(0, 1, 0);
                w.spawnParticle(Particle.PORTAL, pt, 4, 0.2, 0.3, 0.2, 0.2);
                w.spawnParticle(Particle.DUST_COLOR_TRANSITION, pt, 2, 0.15, 0.2, 0.15, 0, PURPLE);
            }
        }
        w.spawnParticle(Particle.PORTAL, from.clone().add(0, 1, 0), 80, 0.4, 0.8, 0.4, 0.8);
        snd(from, "phantom");
        dest.setYaw(from.getYaw());
        dest.setPitch(from.getPitch());
        p.teleport(dest);
        p.setFallDistance(0);
        p.setVelocity(dir.clone().multiply(0.4));
        w.spawnParticle(Particle.PORTAL, dest.clone().add(0, 1, 0), 80, 0.4, 0.8, 0.4, 0.8);
        snd(dest, "phantom");
        slowRift(from, p);
    }

    void slowRift(Location c, Player owner) {
        double r = cfg("phantom.rift-radius");
        int dur = (int) (cfg("phantom.rift-seconds") * 20), amp = getConfig().getInt("phantom.slow-amplifier");
        new BukkitRunnable() {
            int t = 0;
            public void run() {
                t++;
                World w = c.getWorld();
                if (t % 2 == 0) {
                    ring(c.clone().add(0, 0.1, 0), r, 24, t * 0.2, Particle.DUST_COLOR_TRANSITION, PURPLE);
                    w.spawnParticle(Particle.PORTAL, c.clone().add(0, 1, 0), 8, r * 0.4, 0.8, r * 0.4, 0.3);
                }
                if (t % 5 == 0) for (Player p : w.getPlayers()) {
                    if (p.equals(owner) || p.getGameMode() == GameMode.SPECTATOR) continue;
                    if (p.getLocation().distanceSquared(c) <= r * r)
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, amp, false, true, true));
                }
                if (t >= dur) cancel();
            }
        }.runTaskTimer(this, 0, 1);
    }

    // ---------------------------------------------------------------- void rift

    void quietDamage(LivingEntity v, double dmg, Player src) {
        noFx.add(v.getUniqueId());
        try { v.damage(dmg, src); } finally { noFx.remove(v.getUniqueId()); }
    }

    void voidRift(Player p) {
        UUID u = p.getUniqueId();
        if (left(lock, u) > 0) { msg(p, "Void powers locked: " + sec(left(lock, u)) + "s"); return; }
        stopTK(u);
        lock.put(u, System.currentTimeMillis() + (long) (cfg("rift.cooldown") * 1000));
        World w = p.getWorld();
        Location eye = p.getEyeLocation();
        double range = cfg("rift.range");
        RayTraceResult br = w.rayTraceBlocks(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true);
        Location start;
        if (br != null) {
            start = br.getHitPosition().toLocation(w);
            if (br.getHitBlockFace() != null) start.add(br.getHitBlockFace().getDirection().multiply(0.3));
        } else start = eye.clone().add(eye.getDirection().multiply(range));
        RayTraceResult g = w.rayTraceBlocks(start.clone().add(0, 0.5, 0), new Vector(0, -1, 0), 40, FluidCollisionMode.NEVER, true);
        final Location c = g != null ? g.getHitPosition().toLocation(w).add(0, 0.05, 0) : p.getLocation();
        final double R2 = cfg("rift.radius"), dmg = cfg("rift.damage");
        final int dur = (int) (cfg("rift.seconds") * 20);
        snd(c, "rift");
        dark(c.clone().add(0, 0.5, 0), 120, 0.8);
        new BukkitRunnable() {
            int t = 0;
            public void run() {
                t++;
                if (t % 2 == 0) {
                    for (int arm = 0; arm < 3; arm++) for (double rr = 0.6; rr <= R2; rr += 0.7) {
                        double a = t * 0.18 + rr * 0.55 + arm * 2.094;
                        Location l = c.clone().add(Math.cos(a) * rr, 0.1, Math.sin(a) * rr);
                        w.spawnParticle(Particle.DUST_COLOR_TRANSITION, l, 1, 0, 0, 0, 0, PURPLE);
                        w.spawnParticle(Particle.DUST, l, 1, 0.05, 0.05, 0.05, 0, BLACK);
                    }
                    ring(c.clone().add(0, 0.1, 0), R2, 48, t * 0.05, Particle.DUST, BIG);
                    w.spawnParticle(Particle.PORTAL, c.clone().add(0, 1, 0), 20, 0.3, 1.2, 0.3, 0.5);
                    w.spawnParticle(Particle.SQUID_INK, c.clone().add(0, 0.3, 0), 3, R2 * 0.3, 0.2, R2 * 0.3, 0.01);
                }
                for (Player v : w.getPlayers()) {
                    if (v.equals(p) || v.getGameMode() == GameMode.SPECTATOR || held.containsKey(v.getUniqueId())) continue;
                    double dx = c.getX() - v.getX(), dz = c.getZ() - v.getZ(), d2 = dx * dx + dz * dz;
                    if (d2 > R2 * R2 || Math.abs(v.getY() - c.getY()) > 6) continue;
                    double dd = Math.sqrt(d2);
                    Vector vel = v.getVelocity();
                    if (dd < 0.8) v.setVelocity(new Vector(vel.getX() * 0.3, vel.getY(), vel.getZ() * 0.3));
                    else {
                        double str = Math.min(0.35, 0.08 + 0.04 * dd);
                        v.setVelocity(new Vector(vel.getX() * 0.6 + dx / dd * str, vel.getY(), vel.getZ() * 0.6 + dz / dd * str));
                    }
                    if (t % 20 == 0) quietDamage(v, dmg, p);
                }
                if (t >= dur) cancel();
            }
        }.runTaskTimer(this, 0, 1);
    }

    // ---------------------------------------------------------------- powers menu

    static class Menu implements InventoryHolder {
        Inventory inv;
        public Inventory getInventory() { return inv; }
    }

    Component legacy(String s) { return LegacyComponentSerializer.legacySection().deserialize(s).decoration(TextDecoration.ITALIC, false); }

    ItemStack icon(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta im = it.getItemMeta();
        im.displayName(legacy(name));
        List<Component> l = new ArrayList<>();
        for (String x : lore) l.add(legacy(x));
        im.lore(l);
        it.setItemMeta(im);
        return it;
    }

    void openMenu(Player p) {
        Menu h = new Menu();
        Inventory inv = Bukkit.createInventory(h, 27, legacy("§5§lVoidbreaker Powers"));
        h.inv = inv;
        ItemStack fill = icon(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) inv.setItem(i, fill);
        inv.setItem(4, icon(Material.MACE, "§5§l" + name(), "§7Hold it in your main hand:",
                "§d+ Regeneration II, Strength II", "§d+ Jump Boost IV", "§d+ 4 extra hearts"));
        inv.setItem(11, icon(Material.WITHER_SKELETON_SKULL, "§5§lSoul Barrage",
                "§7Control: §fRight click", "§7Fires 3 wither skulls.", "§7Damage only, no block damage.",
                "§7Cooldown: §f" + (int) cfg("skulls.cooldown") + "s"));
        inv.setItem(12, icon(Material.ENDER_EYE, "§5§lTelekinesis",
                "§7Control: §fRight click on a player", "§7Lifts the target and holds them", "§7in front of you. They cannot act.",
                "§7Right click again to release.", "§7Max §f" + (int) cfg("telekinesis.max-seconds") + "s§7, no cooldown."));
        inv.setItem(13, icon(Material.ENDER_PEARL, "§5§lPhantom Step",
                "§7Control: §fSprint, jump, then", "§fright click in the air", "§7Blink forward. A rift stays behind", "§7and slows enemies.",
                "§7Cooldown: §f" + (int) cfg("phantom.cooldown") + "s"));
        inv.setItem(14, icon(Material.NETHERITE_INGOT, "§5§lVoid Judgement",
                "§7Control: §fSneak + left click", "§7Throws the mace. Dark zone blinds", "§7everyone but you. 3 wither skeletons", "§7hunt the target.",
                "§7Cooldown: §f" + (int) cfg("judgement.cooldown") + "s §7(locks all powers)"));
        inv.setItem(15, icon(Material.CRYING_OBSIDIAN, "§5§lVoid Rift",
                "§7Control: §fJump, then left click", "§7Opens a dark portal where you aim.", "§7Pulls players to the center and", "§7damages them slowly.",
                "§7Cooldown: §f" + (int) cfg("rift.cooldown") + "s §7(locks all powers)"));
        inv.setItem(22, icon(Material.BARRIER, "§cClose"));
        p.openInventory(inv);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu) {
            e.setCancelled(true);
            if (e.getRawSlot() == 22) e.getWhoClicked().closeInventory();
            return;
        }
        if (e.getClick() == ClickType.RIGHT && isVB(e.getCurrentItem()) && e.getWhoClicked() instanceof Player p) {
            e.setCancelled(true);
            Bukkit.getScheduler().runTask(this, () -> openMenu(p));
        }
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    // ---------------------------------------------------------------- hits, kills, particles

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        Entity d = e.getDamager(), v = e.getEntity();
        if (noFx.contains(v.getUniqueId())) return;
        if (fr(d)) { e.setCancelled(true); return; }
        UUID sv = skel.get(d.getUniqueId());
        if (sv != null && !v.getUniqueId().equals(sv)) { e.setCancelled(true); return; }
        if (d instanceof WitherSkull ws && ws.getPersistentDataContainer().has(SKULL, B)) {
            if (ws.getShooter() instanceof Player o && o.equals(v)) { e.setCancelled(true); return; }
            e.setDamage(cfg("skulls.damage"));
            skullHit.add(v.getUniqueId());
            Bukkit.getScheduler().runTaskLater(this, () -> skullHit.remove(v.getUniqueId()), 3);
            hitFx(v, false, e);
            return;
        }
        if (d instanceof Player p && e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK && isVB(p.getInventory().getItemInMainHand())) {
            if (p.isSneaking() && left(lock, p.getUniqueId()) == 0) { e.setCancelled(true); attack3(p); return; }
            hitFx(v, p.getFallDistance() > 1.5f, e);
        }
    }

    void hitFx(Entity v, boolean smash, EntityDamageByEntityEvent e) {
        Location l = v.getLocation().add(0, 1, 0);
        dark(l, 60, 0.4);
        snd(l, "hit");
        if (smash) bigBurst(v.getLocation());
        if (v instanceof LivingEntity le && le.getHealth() - e.getFinalDamage() <= 0) snd(l, "kill");
    }

    // ---------------------------------------------------------------- crafting

    @EventHandler
    public void onPrepare(PrepareItemCraftEvent e) {
        if (e.getRecipe() instanceof ShapedRecipe sr && sr.getKey().equals(RECIPE) && !data.getBoolean("recipe", false))
            e.getInventory().setResult(null);
    }

    @EventHandler
    public void onCraft(CraftItemEvent e) {
        if (!(e.getRecipe() instanceof ShapedRecipe sr) || !sr.getKey().equals(RECIPE)) return;
        if (!data.getBoolean("recipe", false)) { e.setCancelled(true); return; }
        if (data.getBoolean("announced", false)) return;
        data.set("announced", true);
        saveData();
        Title t = Title.title(Component.text(name() + " Crafted", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD),
                Component.text("by " + e.getWhoClicked().getName(), NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(4), Duration.ofSeconds(1)));
        for (Player o : Bukkit.getOnlinePlayers()) {
            o.showTitle(t);
            o.playSound(o.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1f, 0.7f);
        }
    }

    // ---------------------------------------------------------------- cleanup

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        stopTK(u);
        if (held.containsKey(u)) stopTK(held.get(u));
        for (Flight f : new ArrayList<>(flights)) if (f.owner.equals(e.getPlayer())) giveBack(f);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        UUID u = e.getEntity().getUniqueId();
        stopTK(u);
        if (held.containsKey(u)) stopTK(held.get(u));
    }

    // ---------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (a.length > 0 && a[0].equalsIgnoreCase("powers")) { if (s instanceof Player pl) openMenu(pl); return true; }
        if (!s.hasPermission("voidbreaker.admin")) { s.sendMessage("/mace powers"); return true; }
        if (a.length == 0) { s.sendMessage("/mace give [player] | recipe on|off | reload | powers"); return true; }
        switch (a[0].toLowerCase()) {
            case "give" -> {
                final Player t = a.length > 1 ? Bukkit.getPlayer(a[1]) : (s instanceof Player pp ? pp : null);
                if (t == null) { s.sendMessage("Player not found."); return true; }
                t.getInventory().addItem(makeItem()).values().forEach(i -> t.getWorld().dropItemNaturally(t.getLocation(), i));
                s.sendMessage(name() + " given to " + t.getName() + ".");
            }
            case "recipe" -> {
                if (a.length < 2 || !(a[1].equalsIgnoreCase("on") || a[1].equalsIgnoreCase("off"))) { s.sendMessage("/mace recipe on|off"); return true; }
                boolean on = a[1].equalsIgnoreCase("on");
                data.set("recipe", on);
                saveData();
                s.sendMessage("Recipe is now " + (on ? "ON" : "OFF") + ".");
            }
            case "reload" -> { reloadConfig(); s.sendMessage("Config reloaded."); }
            default -> s.sendMessage("/mace give [player] | recipe on|off | reload");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1) return List.of("give", "recipe", "reload", "powers");
        if (a.length == 2 && a[0].equalsIgnoreCase("recipe")) return List.of("on", "off");
        return null;
    }
}
