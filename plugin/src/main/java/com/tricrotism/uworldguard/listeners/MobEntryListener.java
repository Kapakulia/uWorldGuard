package com.tricrotism.uworldguard.listeners;

import com.destroystokyo.paper.event.entity.EntityPathfindEvent;
import com.destroystokyo.paper.event.entity.EntityTeleportEndGatewayEvent;
import com.tricrotism.uworldguard.config.EventGate;
import com.tricrotism.uworldguard.config.Settings;
import com.tricrotism.uworldguard.event.RegionFlagChangeEvent;
import com.tricrotism.uworldguard.event.RegionRemoveEvent;
import com.tricrotism.uworldguard.event.RegionsLoadedEvent;
import com.tricrotism.uworldguard.event.RegionsUnloadedEvent;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.region.ApplicableRegionSet;
import com.tricrotism.uworldguard.region.ProtectedRegion;
import com.tricrotism.uworldguard.region.RegionContainerImpl;
import com.tricrotism.uworldguard.region.RegionQuery;
import io.papermc.paper.event.entity.EntityMoveEvent;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityPortalExitEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * Enforces {@code mob-entry}. A deny stops a mob crossing into the region: the move or teleport is
 * cancelled (Paper snaps a cancelled {@link EntityMoveEvent} back to where the tick started) and
 * leftover horizontal velocity is cleared, so knockback does not slide it straight back in. A path
 * or attack target inside is cancelled. A boat or minecart carrying such a mob is teleported back;
 * one a player is riding is left alone.
 *
 * <p>Mobs that spawn inside, or that were already inside, stay and may move within the denied area,
 * including into a child region that only inherits the flag. Crossing into a neighbour that sets its
 * own deny is refused. {@code regions.mob-entry.remove-inside} instead removes a mob the next time it
 * moves while already inside, and cancels a spawn there. Nothing scans the world. The global region
 * has no outside, so a deny on it does not push; the toggle still clears mobs that move or spawn.
 *
 * <p>Players, armor stands, mannequins, item frames and projectiles are not mobs and are never
 * checked. Tamed pets and mobs leashed by a player are exempt unless the config says otherwise.
 *
 * <p>The move, vehicle, path and target handlers are registered only while some region sets the
 * flag. Paper builds an {@link EntityMoveEvent} for every living entity whose position <em>or</em>
 * rotation changed, in every world, for as long as any listener is registered — the same cost
 * {@link MovementListener} avoids for mount tracking — so an unused flag must not subscribe.
 * Handlers that do run return on a same-block move, a non-mob, or a world where the flag is unset
 * before any region lookup.
 */
@NullMarked
public final class MobEntryListener implements Listener {

    private enum Outcome {
        ALLOW, BLOCK, REMOVE
    }

    private final Plugin plugin;
    private final RegionContainerImpl container;
    private final RegionQuery query;
    private final Settings settings;
    private final Moves moves = new Moves();
    /**
     * Touched on the global region thread, and from {@link #shutdown} once no further sync can run.
     */
    private boolean movesRegistered;

    public MobEntryListener(
        final Plugin plugin, final RegionContainerImpl container, final RegionQuery query, final Settings settings
    ) {
        this.plugin = plugin;
        this.container = container;
        this.query = query;
        this.settings = settings;
    }

    /**
     * Subscribes the move handlers if a loaded world already denies entry. Regions are loaded before
     * this listener, so the startup check is synchronous; later edits schedule {@link #syncMoves()}.
     */
    public void start() {
        if (!movesRegistered && container.anyRegionUses(Flags.MOB_ENTRY)) {
            plugin.getServer().getPluginManager().registerEvents(moves, plugin);
            movesRegistered = true;
        }
    }

    /**
     * Drops the move handlers. Inline, like {@link MovementListener#stop()}: {@code onDisable} runs
     * with the plugin already disabled, and scheduling from there throws.
     */
    public void shutdown() {
        HandlerList.unregisterAll(moves);
        movesRegistered = false;
    }

    /**
     * Re-reads whether any world still sets the flag and registers or drops the move handlers to
     * match. Delayed a tick so a flag event, which fires before the write, is visible.
     */
    public void syncMoves() {
        plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, _ -> {
            if (!plugin.isEnabled()) {
                return;
            }
            final boolean wanted = container.anyRegionUses(Flags.MOB_ENTRY);
            if (wanted == movesRegistered) {
                return;
            }
            movesRegistered = wanted;
            if (wanted) {
                plugin.getServer().getPluginManager().registerEvents(moves, plugin);
            } else {
                HandlerList.unregisterAll(moves);
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlag(final RegionFlagChangeEvent event) {
        if (event.getFlag() == Flags.MOB_ENTRY) {
            syncMoves();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRemove(final RegionRemoveEvent event) {
        if (event.getRegion().getOwnFlag(Flags.MOB_ENTRY) != null) {
            syncMoves();
        }
    }

    @EventHandler
    public void onRegionsLoaded(final RegionsLoadedEvent event) {
        if (event.getManager().anyRegionUses(Flags.MOB_ENTRY)) {
            syncMoves();
        }
    }

    @EventHandler
    public void onRegionsUnloaded(final RegionsUnloadedEvent event) {
        if (movesRegistered) {
            syncMoves();
        }
    }

    /**
     * Block crossing for a mob the server is moving. Registered only while the flag is in use.
     * Rotation-only events return before any lookup: Paper fires those too.
     */
    public void onMobMove(final EntityMoveEvent event) {
        if (!event.hasChangedBlock()) {
            return;
        }
        final LivingEntity living = event.getEntity();
        if (!(living instanceof Mob mob)) {
            return;
        }
        final Location to = event.getTo();
        final World world = to.getWorld();
        if (world == null || !query.usesFlag(world, Flags.MOB_ENTRY) || EventGate.disabled(event) || exempt(mob)) {
            return;
        }
        switch (judgeAt(event.getFrom(), to, mob.getType())) {
            case BLOCK -> {
                event.setCancelled(true);
                stopHorizontal(mob);
            }
            case REMOVE -> {
                event.setCancelled(true);
                mob.remove();
            }
            case ALLOW -> {
            }
        }
    }

    /**
     * Boats and minecarts do not fire {@link EntityMoveEvent}. A living mount does, and is handled
     * there; skipping it here avoids pushing the same mob twice.
     */
    public void onVehicleMove(final VehicleMoveEvent event) {
        final Vehicle vehicle = event.getVehicle();
        if (vehicle instanceof Mob || vehicle.isEmpty()) {
            return;
        }
        final Location from = event.getFrom();
        final Location to = event.getTo();
        if (sameBlock(from, to) || carriesPlayer(vehicle) || EventGate.disabled(event)) {
            return;
        }
        final World world = to.getWorld();
        if (world == null || !query.usesFlag(world, Flags.MOB_ENTRY) || !hasRestrictedMob(vehicle)) {
            return;
        }
        final ApplicableRegionSet fromSet = regions(from);
        final ApplicableRegionSet toSet = query.getApplicableRegions(to);
        if (restrain(vehicle, fromSet, toSet)) {
            stopHorizontal(vehicle);
            vehicle.teleportAsync(from);
        }
    }

    public void onPathfind(final EntityPathfindEvent event) {
        final Entity entity = event.getEntity();
        if (!(entity instanceof Mob mob) || EventGate.disabled(event)) {
            return;
        }
        final World world = mob.getWorld();
        if (!query.usesFlag(world, Flags.MOB_ENTRY) || exempt(mob)) {
            return;
        }
        final Location from = mob.getLocation();
        if (judge(from, event.getLoc(), mob.getType()) == Outcome.BLOCK) {
            event.setCancelled(true);
            return;
        }
        final Entity target = event.getTargetEntity();
        if (target != null && judge(from, target.getLocation(), mob.getType()) == Outcome.BLOCK) {
            event.setCancelled(true);
        }
    }

    public void onTarget(final EntityTargetEvent event) {
        final Entity target = event.getTarget();
        if (target == null || event.getReason() == EntityTargetEvent.TargetReason.FORGOT_TARGET) {
            return;
        }
        final Entity entity = event.getEntity();
        if (!(entity instanceof Mob mob) || EventGate.disabled(event)) {
            return;
        }
        final World world = mob.getWorld();
        if (!query.usesFlag(world, Flags.MOB_ENTRY) || exempt(mob)) {
            return;
        }
        if (judge(mob.getLocation(), target.getLocation(), mob.getType()) == Outcome.BLOCK) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(final EntityTeleportEvent event) {
        rejectTeleport(event);
    }

    /**
     * Portal events keep their own handler list, so they never reach {@link #onTeleport}.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(final EntityPortalEvent event) {
        rejectTeleport(event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortalExit(final EntityPortalExitEvent event) {
        rejectTeleport(event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGateway(final EntityTeleportEndGatewayEvent event) {
        rejectTeleport(event);
    }

    /**
     * Spawns are left alone unless {@code remove-inside} is on, so a farm inside a no-entry region
     * still works. {@code mob-spawning} and {@code deny-spawn} remain the way to stop spawns outright.
     * One field read when the toggle is off: this handler stays registered because spawn events are
     * not the per-entity move tax.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(final CreatureSpawnEvent event) {
        if (!settings.mobEntryRemoveInside() || EventGate.disabled(event)) {
            return;
        }
        final LivingEntity living = event.getEntity();
        if (!(living instanceof Mob mob) || exempt(mob)) {
            return;
        }
        final Location at = event.getLocation();
        final World world = at.getWorld();
        if (world == null || !query.usesFlag(world, Flags.MOB_ENTRY)) {
            return;
        }
        if (denies(query.getApplicableRegions(at), mob.getType())) {
            event.setCancelled(true);
        }
    }

    /**
     * Reeling drags the hooked mob toward the player. Cancel when that would pull it into a deny.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(final PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_ENTITY || EventGate.disabled(event)) {
            return;
        }
        final Entity caught = event.getCaught();
        if (!(caught instanceof Mob mob) || exempt(mob)) {
            return;
        }
        if (judge(mob.getLocation(), event.getPlayer().getLocation(), mob.getType()) == Outcome.BLOCK) {
            event.setCancelled(true);
        }
    }

    private void rejectTeleport(final EntityTeleportEvent event) {
        final Location to = event.getTo();
        if (to == null || sameBlock(event.getFrom(), to) || EventGate.disabled(event)) {
            return;
        }
        final Entity entity = event.getEntity();
        if (!(entity instanceof Mob mob) || exempt(mob)) {
            return;
        }
        switch (judge(event.getFrom(), to, mob.getType())) {
            case BLOCK -> {
                event.setCancelled(true);
                stopHorizontal(mob);
            }
            case REMOVE -> {
                event.setCancelled(true);
                mob.remove();
            }
            case ALLOW -> {
            }
        }
    }

    private Outcome judge(final Location from, final Location to, final EntityType type) {
        final World world = to.getWorld();
        if (world == null || !query.usesFlag(world, Flags.MOB_ENTRY)) {
            return Outcome.ALLOW;
        }
        return judgeAt(from, to, type);
    }

    private Outcome judgeAt(final Location from, final Location to, final EntityType type) {
        return judge(regions(from), query.getApplicableRegions(to), type);
    }

    private Outcome judge(
        final @Nullable ApplicableRegionSet from, final ApplicableRegionSet to, final EntityType type
    ) {
        if (!denies(to, type)) {
            return Outcome.ALLOW;
        }
        if (from == null || !denies(from, type) || newOwnDeny(from, to, type)) {
            return Outcome.BLOCK;
        }
        return settings.mobEntryRemoveInside() ? Outcome.REMOVE : Outcome.ALLOW;
    }

    /**
     * Effective deny at {@code set}, narrowed by {@code mob-entry-mobs} when that list is set.
     */
    private static boolean denies(final ApplicableRegionSet set, final EntityType type) {
        if (set.queryState(Flags.MOB_ENTRY) != State.DENY) {
            return false;
        }
        if (!set.worldUses(Flags.MOB_ENTRY_MOBS)) {
            return true;
        }
        final Set<EntityType> filter = set.queryValue(Flags.MOB_ENTRY_MOBS);
        return filter == null || filter.isEmpty() || filter.contains(type);
    }

    /**
     * A region the mob was not already standing in that denies this type on its own flags, rather
     * than by inheriting a deny the mob is already inside.
     */
    private static boolean newOwnDeny(
        final ApplicableRegionSet from, final ApplicableRegionSet to, final EntityType type
    ) {
        for (int i = 0, n = to.size(); i < n; i++) {
            final ProtectedRegion region = to.get(i);
            if (contains(from, region)) {
                continue;
            }
            if (ownDenies(region, type)) {
                return true;
            }
        }
        return false;
    }

    private static boolean ownDenies(final ProtectedRegion region, final EntityType type) {
        if (region.getOwnFlag(Flags.MOB_ENTRY) != State.DENY) {
            return false;
        }
        final Set<EntityType> filter = region.getOwnFlag(Flags.MOB_ENTRY_MOBS);
        return filter == null || filter.isEmpty() || filter.contains(type);
    }

    private static boolean contains(final ApplicableRegionSet set, final ProtectedRegion region) {
        for (int i = 0, n = set.size(); i < n; i++) {
            if (set.get(i) == region) {
                return true;
            }
        }
        return false;
    }

    private boolean exempt(final Mob mob) {
        if (mob.getVehicle() != null && ridingWithPlayer(mob)) {
            return true;
        }
        if (!mob.isEmpty() && carriesPlayer(mob)) {
            return true;
        }
        if (settings.mobEntryExemptTamed() && mob instanceof Tameable tameable && tameable.isTamed()) {
            return true;
        }
        return settings.mobEntryExemptLeashed() && mob.isLeashed() && mob.getLeashHolder() instanceof Player;
    }

    private boolean ridingWithPlayer(final Entity entity) {
        Entity vehicle = entity.getVehicle();
        while (vehicle != null) {
            if (carriesPlayer(vehicle)) {
                return true;
            }
            vehicle = vehicle.getVehicle();
        }
        return false;
    }

    private boolean carriesPlayer(final Entity entity) {
        if (entity.isEmpty()) {
            return false;
        }
        final List<Entity> passengers = entity.getPassengers();
        for (int i = 0, n = passengers.size(); i < n; i++) {
            final Entity passenger = passengers.get(i);
            if (passenger instanceof Player || carriesPlayer(passenger)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasRestrictedMob(final Entity entity) {
        if (entity.isEmpty()) {
            return false;
        }
        final List<Entity> passengers = entity.getPassengers();
        for (int i = 0, n = passengers.size(); i < n; i++) {
            final Entity passenger = passengers.get(i);
            if ((passenger instanceof Mob mob && !exempt(mob)) || hasRestrictedMob(passenger)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return whether the vehicle itself has to be sent back. Passengers already inside are removed
     * in place when that toggle is on, which does not require moving the boat.
     */
    private boolean restrain(
        final Entity entity, final @Nullable ApplicableRegionSet from, final ApplicableRegionSet to,
        final boolean self
    ) {
        boolean push = false;
        if (self && entity instanceof Mob mob && !exempt(mob)) {
            final Outcome outcome = judge(from, to, mob.getType());
            if (outcome == Outcome.REMOVE) {
                mob.remove();
            } else if (outcome == Outcome.BLOCK) {
                push = true;
            }
        }
        if (entity.isEmpty()) {
            return push;
        }
        final List<Entity> passengers = entity.getPassengers();
        for (int i = 0, n = passengers.size(); i < n; i++) {
            push |= restrain(passengers.get(i), from, to, true);
        }
        return push;
    }

    private boolean restrain(
        final Entity vehicle, final @Nullable ApplicableRegionSet from, final ApplicableRegionSet to
    ) {
        return restrain(vehicle, from, to, false);
    }

    private @Nullable ApplicableRegionSet regions(final Location location) {
        final World world = location.getWorld();
        return world == null ? null : query.getApplicableRegions(location);
    }

    private static boolean sameBlock(final Location from, final Location to) {
        return from.getWorld() == to.getWorld()
            && from.getBlockX() == to.getBlockX()
            && from.getBlockY() == to.getBlockY()
            && from.getBlockZ() == to.getBlockZ();
    }

    private static void stopHorizontal(final Entity entity) {
        final Vector velocity = entity.getVelocity();
        if (velocity.getX() == 0.0 && velocity.getZ() == 0.0) {
            return;
        }
        velocity.setX(0);
        velocity.setZ(0);
        entity.setVelocity(velocity);
    }

    /**
     * The handlers Paper would otherwise run for every mob in every world. Registered only while
     * {@code mob-entry} is set somewhere.
     */
    private final class Moves implements Listener {

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onMove(final EntityMoveEvent event) {
            onMobMove(event);
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onVehicle(final VehicleMoveEvent event) {
            onVehicleMove(event);
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onPath(final EntityPathfindEvent event) {
            onPathfind(event);
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onTarget(final EntityTargetEvent event) {
            MobEntryListener.this.onTarget(event);
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onTargetLiving(final EntityTargetLivingEntityEvent event) {
            MobEntryListener.this.onTarget(event);
        }
    }
}
