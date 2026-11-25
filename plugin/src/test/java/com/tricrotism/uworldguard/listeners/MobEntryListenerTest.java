package com.tricrotism.uworldguard.listeners;

import com.destroystokyo.paper.event.entity.EntityPathfindEvent;
import com.tricrotism.uworldguard.config.Settings;
import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.region.ProtectedCuboidRegion;
import com.tricrotism.uworldguard.region.RegionContainerImpl;
import com.tricrotism.uworldguard.region.RegionManager;
import com.tricrotism.uworldguard.region.RegionQuery;
import com.tricrotism.uworldguard.storage.RegionStore;
import com.tricrotism.uworldguard.util.BlockVector3;
import io.papermc.paper.event.entity.EntityMoveEvent;
import org.bukkit.Location;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.ArmorStandMock;
import org.mockbukkit.mockbukkit.entity.PigMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.entity.WolfMock;
import org.mockbukkit.mockbukkit.entity.ZombieMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.EnumSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code mob-entry deny} refuses a crossing in from outside and leaves a mob that is already inside
 * where it is, unless {@code remove-inside} is on.
 */
class MobEntryListenerTest {

    private static final RegionStore NO_STORE = new RegionStore() {
        @Override
        public void load(final String worldName, final RegionManager manager) {}

        @Override
        public void save(final String worldName, final RegionManager manager) {}
    };

    private static final double INSIDE = 8.5;
    private static final double ALSO_INSIDE = 12.5;
    private static final double OUTSIDE = 40.5;

    private ServerMock server;
    private Plugin plugin;
    private WorldMock world;
    private RegionContainerImpl container;
    private RegionQuery query;
    private RegionManager manager;
    private ProtectedCuboidRegion spawn;
    private Settings settings;
    private MobEntryListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("uWorldGuard");
        world = server.addSimpleWorld("world");
        container = new RegionContainerImpl(plugin, NO_STORE);
        container.loadAll();
        query = container.createQuery();
        manager = container.get(world);
        assertNotNull(manager);
        spawn = new ProtectedCuboidRegion("spawn", BlockVector3.at(0, 0, 0), BlockVector3.at(31, 255, 31));
        spawn.setFlag(Flags.MOB_ENTRY, State.DENY);
        manager.addRegion(spawn);
        settings = new Settings();
        listener = new MobEntryListener(plugin, container, query, settings);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private Location at(final double x) {
        return new Location(world, x, 64, 8.5);
    }

    private <T extends Entity> T place(final T entity, final double x) {
        entity.teleport(at(x));
        return entity;
    }

    private ZombieMock zombieAt(final double x) {
        return place(new ZombieMock(server, UUID.randomUUID()), x);
    }

    private EntityMoveEvent move(final LivingEntity entity, final double from, final double to) {
        final EntityMoveEvent event = new EntityMoveEvent(entity, at(from), at(to));
        entity.setVelocity(new Vector(0.4, 0.1, 0.2));
        listener.onMobMove(event);
        return event;
    }

    private boolean hearsMoves() {
        for (final RegisteredListener registration : EntityMoveEvent.getHandlerList().getRegisteredListeners()) {
            if (registration.getPlugin() == plugin) {
                return true;
            }
        }
        return false;
    }

    @Test
    void aZombieWalkingInIsPushedBack() {
        final ZombieMock zombie = zombieAt(OUTSIDE);
        final EntityMoveEvent event = move(zombie, OUTSIDE, INSIDE);

        assertTrue(event.isCancelled());
        assertEquals(0.0, zombie.getVelocity().getX(), "knockback must not carry it back in");
        assertEquals(0.0, zombie.getVelocity().getZ());
        assertEquals(0.1, zombie.getVelocity().getY(), 0.0);
    }

    @Test
    void aZombieAlreadyInsideMayMove() {
        final EntityMoveEvent event = move(zombieAt(INSIDE), INSIDE, ALSO_INSIDE);

        assertFalse(event.isCancelled());
        assertEquals(0.4, zombieVelocity(event), "an allowed step keeps its velocity");
    }

    @Test
    void aZombieMayLeave() {
        final EntityMoveEvent event = move(zombieAt(INSIDE), INSIDE, OUTSIDE);

        assertFalse(event.isCancelled());
    }

    @Test
    void turningItsHeadIsIgnored() {
        final ZombieMock zombie = zombieAt(OUTSIDE);
        final Location from = at(OUTSIDE);
        final Location to = at(OUTSIDE);
        to.setYaw(90);
        final EntityMoveEvent event = new EntityMoveEvent(zombie, from, to);

        listener.onMobMove(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void allowDoesNotBlock() {
        spawn.setFlag(Flags.MOB_ENTRY, State.ALLOW);
        final EntityMoveEvent event = move(zombieAt(OUTSIDE), OUTSIDE, INSIDE);

        assertFalse(event.isCancelled());
    }

    @Test
    void anUnsetFlagDoesNothing() {
        spawn.setFlag(Flags.MOB_ENTRY, null);
        final EntityMoveEvent event = move(zombieAt(OUTSIDE), OUTSIDE, INSIDE);

        assertFalse(event.isCancelled());
    }

    @Test
    void anArmorStandIsNotAMob() {
        final ArmorStandMock stand = place(new ArmorStandMock(server, UUID.randomUUID()), OUTSIDE);
        final EntityMoveEvent event = new EntityMoveEvent(stand, at(OUTSIDE), at(INSIDE));

        listener.onMobMove(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void aTamedWolfMayFollowItsOwner() {
        final PlayerMock owner = server.addPlayer();
        final WolfMock wolf = place(new WolfMock(server, UUID.randomUUID()), OUTSIDE);
        wolf.setOwner(owner);
        wolf.setTamed(true);
        final EntityMoveEvent event = move(wolf, OUTSIDE, INSIDE);

        assertFalse(event.isCancelled());
    }

    @Test
    void aTamedWolfIsBlockedWhenTheExemptionIsOff() {
        settings.load(config("exempt-tamed: false\n"));
        final WolfMock wolf = place(new WolfMock(server, UUID.randomUUID()), OUTSIDE);
        wolf.setTamed(true);
        final EntityMoveEvent event = move(wolf, OUTSIDE, INSIDE);

        assertTrue(event.isCancelled());
    }

    @Test
    void aLeashedZombieMayFollowThePlayerHoldingTheLead() {
        final ZombieMock zombie = leashedTo(server.addPlayer(), OUTSIDE);
        final EntityMoveEvent event = move(zombie, OUTSIDE, INSIDE);

        assertFalse(event.isCancelled());
    }

    @Test
    void aLeashedZombieIsBlockedWhenTheExemptionIsOff() {
        settings.load(config("exempt-leashed: false\n"));
        final ZombieMock zombie = leashedTo(server.addPlayer(), OUTSIDE);
        final EntityMoveEvent event = move(zombie, OUTSIDE, INSIDE);

        assertTrue(event.isCancelled());
    }

    /**
     * MockBukkit reports a mob as leashed only when the holder is itself a mob, so a player lead
     * never shows up. This stands in for the real contract: {@code isLeashed} is true and
     * {@code getLeashHolder} is the player.
     */
    private ZombieMock leashedTo(final Player holder, final double x) {
        final ZombieMock zombie = new ZombieMock(server, UUID.randomUUID()) {
            private Entity lead;

            @Override
            public boolean setLeashHolder(final Entity entity) {
                lead = entity;
                return true;
            }

            @Override
            public boolean isLeashed() {
                return lead != null;
            }

            @Override
            public Entity getLeashHolder() {
                if (lead == null) {
                    throw new IllegalStateException("Entity is currently not leashed");
                }
                return lead;
            }
        };
        assertTrue(zombie.setLeashHolder(holder));
        return place(zombie, x);
    }

    @Test
    void theTypeListBlocksOnlyTheNamedMobs() {
        spawn.setFlag(Flags.MOB_ENTRY_MOBS, EnumSet.of(EntityType.ZOMBIE));
        final EntityMoveEvent zombie = move(zombieAt(OUTSIDE), OUTSIDE, INSIDE);
        final PigMock pig = place(new PigMock(server, UUID.randomUUID()), OUTSIDE);
        final EntityMoveEvent pigMove = move(pig, OUTSIDE, INSIDE);

        assertTrue(zombie.isCancelled());
        assertFalse(pigMove.isCancelled());
    }

    @Test
    void theTypeListDoesNothingWhileEntryIsAllowed() {
        spawn.setFlag(Flags.MOB_ENTRY, State.ALLOW);
        spawn.setFlag(Flags.MOB_ENTRY_MOBS, EnumSet.of(EntityType.ZOMBIE));
        final EntityMoveEvent event = move(zombieAt(OUTSIDE), OUTSIDE, INSIDE);

        assertFalse(event.isCancelled());
    }

    @Test
    void aChildThatOnlyInheritsDoesNotTrapMobsAlreadyInside() {
        final ProtectedCuboidRegion shop = new ProtectedCuboidRegion("shop",
            BlockVector3.at(0, 0, 0), BlockVector3.at(10, 255, 31));
        shop.setParent(spawn);
        manager.addRegion(shop);
        final EntityMoveEvent event = move(zombieAt(20.5), 20.5, 5.5);

        assertFalse(event.isCancelled(), "the shop does not set its own deny");
    }

    @Test
    void aNeighbourThatSetsItsOwnDenyIsRefused() {
        final ProtectedCuboidRegion next = new ProtectedCuboidRegion("next",
            BlockVector3.at(32, 0, 0), BlockVector3.at(63, 255, 31));
        next.setFlag(Flags.MOB_ENTRY, State.DENY);
        manager.addRegion(next);
        final EntityMoveEvent event = move(zombieAt(30.5), 30.5, 33.5);

        assertTrue(event.isCancelled());
    }

    @Test
    void teleportInIsCancelledAndTeleportOutIsNot() {
        final ZombieMock in = zombieAt(OUTSIDE);
        final EntityTeleportEvent entering = new EntityTeleportEvent(in, at(OUTSIDE), at(INSIDE));
        listener.onTeleport(entering);
        final ZombieMock out = zombieAt(INSIDE);
        final EntityTeleportEvent leaving = new EntityTeleportEvent(out, at(INSIDE), at(OUTSIDE));
        listener.onTeleport(leaving);

        assertTrue(entering.isCancelled());
        assertFalse(leaving.isCancelled());
    }

    @Test
    void targetingSomethingInsideIsCancelled() {
        final ZombieMock zombie = zombieAt(OUTSIDE);
        final PlayerMock player = server.addPlayer();
        player.teleport(at(INSIDE));
        final EntityTargetLivingEntityEvent event = new EntityTargetLivingEntityEvent(
            zombie, player, EntityTargetEvent.TargetReason.CLOSEST_PLAYER);

        listener.onTarget(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void targetingSomethingOutsideIsAllowed() {
        final ZombieMock zombie = zombieAt(INSIDE);
        final PlayerMock player = server.addPlayer();
        player.teleport(at(OUTSIDE));
        final EntityTargetLivingEntityEvent event = new EntityTargetLivingEntityEvent(
            zombie, player, EntityTargetEvent.TargetReason.CLOSEST_PLAYER);

        listener.onTarget(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void pathingIntoTheRegionIsCancelled() {
        final ZombieMock zombie = zombieAt(OUTSIDE);
        final EntityPathfindEvent event = new EntityPathfindEvent(zombie, at(INSIDE), null);

        listener.onPathfind(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void pathingAroundInsideIsAllowed() {
        final ZombieMock zombie = zombieAt(INSIDE);
        final EntityPathfindEvent event = new EntityPathfindEvent(zombie, at(ALSO_INSIDE), null);

        listener.onPathfind(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void reelingAMobInIsCancelled() {
        final PlayerMock player = server.addPlayer();
        player.teleport(at(INSIDE));
        final ZombieMock zombie = zombieAt(OUTSIDE);
        final PlayerFishEvent event = new PlayerFishEvent(player, zombie, null, PlayerFishEvent.State.CAUGHT_ENTITY);

        listener.onFish(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void aSpawnInsideIsLeftAloneByDefault() {
        final CreatureSpawnEvent event = new CreatureSpawnEvent(
            zombieAt(INSIDE), CreatureSpawnEvent.SpawnReason.NATURAL);

        listener.onSpawn(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void removeInsideDeletesAMobThatMovesAndCancelsASpawn() {
        settings.load(config("remove-inside: true\n"));
        final ZombieMock zombie = zombieAt(INSIDE);
        final EntityMoveEvent moving = move(zombie, INSIDE, ALSO_INSIDE);
        final CreatureSpawnEvent spawning = new CreatureSpawnEvent(
            zombieAt(INSIDE), CreatureSpawnEvent.SpawnReason.SPAWNER_EGG);

        listener.onSpawn(spawning);

        assertTrue(moving.isCancelled());
        assertTrue(zombie.isDead());
        assertTrue(spawning.isCancelled());
    }

    @Test
    void aBoatCarryingAZombieIsStoppedAndOneCarryingAPlayerIsNot() {
        final Boat blocked = world.spawn(at(INSIDE), Boat.class);
        final Zombie passenger = world.spawn(at(INSIDE), Zombie.class);
        assertTrue(blocked.addPassenger(passenger));
        blocked.setVelocity(new Vector(0.5, 0, 0.5));
        // MockBukkit does not implement teleportAsync. The velocity clear happens first; the
        // teleport is what sends the boat back on a real server.
        try {
            listener.onVehicleMove(new VehicleMoveEvent(blocked, at(OUTSIDE), at(INSIDE)));
        } catch (final org.opentest4j.TestAbortedException ignored) {
        }

        assertEquals(0.0, blocked.getVelocity().getX());
        assertEquals(0.0, blocked.getVelocity().getZ());

        final Boat ridden = world.spawn(at(INSIDE), Boat.class);
        final PlayerMock player = server.addPlayer();
        player.teleport(at(INSIDE));
        assertTrue(ridden.addPassenger(player));
        assertTrue(ridden.addPassenger(world.spawn(at(INSIDE), Zombie.class)));
        ridden.setVelocity(new Vector(0.5, 0, 0.5));
        listener.onVehicleMove(new VehicleMoveEvent(ridden, at(OUTSIDE), at(INSIDE)));

        assertEquals(0.5, ridden.getVelocity().getX(), "a player aboard is left alone");
    }

    @Test
    void theMoveHandlerIsRegisteredOnlyWhileTheFlagIsSet() {
        listener.start();
        assertTrue(hearsMoves());

        listener.shutdown();
        spawn.setFlag(Flags.MOB_ENTRY, null);
        listener.start();

        assertFalse(hearsMoves(), "an unused flag must not subscribe to EntityMoveEvent");
    }

    private static double zombieVelocity(final EntityMoveEvent event) {
        return event.getEntity().getVelocity().getX();
    }

    private static YamlConfiguration config(final String body) {
        final YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString("regions:\n  mob-entry:\n    " + body.replace("\n", "\n    "));
        } catch (final InvalidConfigurationException e) {
            throw new IllegalStateException(e);
        }
        return yaml;
    }
}
