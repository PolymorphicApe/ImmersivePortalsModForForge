package com.qouteall.immersive_portals.teleportation;

import com.qouteall.hiding_in_the_bushes.MyNetwork;
import com.qouteall.hiding_in_the_bushes.O_O;
import com.qouteall.immersive_portals.Global;
import com.qouteall.immersive_portals.Helper;
import com.qouteall.immersive_portals.McHelper;
import com.qouteall.immersive_portals.ModMain;
import com.qouteall.immersive_portals.PehkuiInterface;
import com.qouteall.immersive_portals.chunk_loading.NewChunkTrackingGraph;
import com.qouteall.immersive_portals.ducks.IEServerPlayNetworkHandler;
import com.qouteall.immersive_portals.ducks.IEServerPlayerEntity;
import com.qouteall.immersive_portals.my_util.LimitedLogger;
import com.qouteall.immersive_portals.my_util.MyTaskList;
import com.qouteall.immersive_portals.portal.Portal;
import com.qouteall.immersive_portals.portal.global_portals.GlobalPortalStorage;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.network.IPacket;
import net.minecraft.network.play.client.CPlayerPacket;
import net.minecraft.pathfinding.Path;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.Tuple;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import org.apache.commons.lang3.Validate;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ServerTeleportationManager {
    private Set<ServerPlayerEntity> teleportingEntities = new HashSet<>();
    private WeakHashMap<Entity, Long> lastTeleportGameTime = new WeakHashMap<>();
    public boolean isFiringMyChangeDimensionEvent = false;
    public final WeakHashMap<ServerPlayerEntity, Tuple<RegistryKey<World>, Vector3d>> lastPosition =
        new WeakHashMap<>();
    
    // The old teleport way does not recreate the entity
    // It's problematic because some AI-related fields contain world reference
    private static final boolean useOldTeleport = false;
    
    public ServerTeleportationManager() {
        ModMain.postServerTickSignal.connectWithWeakRef(this, ServerTeleportationManager::tick);
        Portal.serverPortalTickSignal.connectWithWeakRef(
            this, (this_, portal) -> {
                getEntitiesToTeleport(portal).forEach(entity -> {
                    this_.tryToTeleportRegularEntity(portal, entity);
                });
            }
        );
    }
    
    public static boolean shouldEntityTeleport(Portal portal, Entity entity) {
        return entity.world == portal.world &&
            portal.canTeleportEntity(entity) &&
            portal.isMovedThroughPortal(
                entity.getEyePosition(0),
                entity.getEyePosition(1).add(entity.getMotion())
            );
    }
    
    
    public void tryToTeleportRegularEntity(Portal portal, Entity entity) {
        if (entity instanceof ServerPlayerEntity) {
            return;
        }
        if (entity instanceof Portal) {
            return;
        }
        if (entity.getRidingEntity() != null || doesEntityClutterContainPlayer(entity)) {
            return;
        }
        if (entity.removed) {
            return;
        }
        if (!entity.isNonBoss()) {
            return;
        }
        if (isJustTeleported(entity, 1)) {
            return;
        }
        //a new born entity may have last tick pos 0 0 0
        double motion = McHelper.lastTickPosOf(entity).squareDistanceTo(entity.getPositionVec());
        if (motion > 20) {
            return;
        }
        ModMain.serverTaskList.addTask(() -> {
            try {
                teleportRegularEntity(entity, portal);
            }
            catch (Throwable e) {
                e.printStackTrace();
            }
            return true;
        });
    }
    
    private static Stream<Entity> getEntitiesToTeleport(Portal portal) {
        return portal.world.getEntitiesWithinAABB(
            Entity.class,
            portal.getBoundingBox().grow(2),
            e -> true
        ).stream().filter(
            e -> !(e instanceof Portal)
        ).filter(
            entity -> shouldEntityTeleport(portal, entity)
        );
    }
    
    public void onPlayerTeleportedInClient(
        ServerPlayerEntity player,
        RegistryKey<World> dimensionBefore,
        Vector3d oldEyePos,
        UUID portalId
    ) {
        recordLastPosition(player);
        
        Portal portal = findPortal(dimensionBefore, portalId);
        lastTeleportGameTime.put(player, McHelper.getServerGameTime());
        
        if (canPlayerTeleport(player, dimensionBefore, oldEyePos, portal)) {
            if (isTeleporting(player)) {
                Helper.log(player.toString() + "is teleporting frequently");
            }
            
            notifyChasersForPlayer(player, portal);
            
            RegistryKey<World> dimensionTo = portal.dimensionTo;
            Vector3d newEyePos = portal.transformPoint(oldEyePos);
            
            teleportPlayer(player, dimensionTo, newEyePos);
            
            portal.onEntityTeleportedOnServer(player);
            
            PehkuiInterface.onServerEntityTeleported.accept(player, portal);
        }
        else {
            Helper.err(String.format(
                "Player cannot teleport through portal %s %s %s %s",
                player.getName().getUnformattedComponentText(),
                player.world.func_234923_W_(),
                player.getPositionVec(),
                portal
            ));
        }
    }
    
    private Portal findPortal(RegistryKey<World> dimensionBefore, UUID portalId) {
        ServerWorld originalWorld = McHelper.getServer().getWorld(dimensionBefore);
        Entity portalEntity = originalWorld.getEntityByUuid(portalId);
        if (portalEntity == null) {
            portalEntity = GlobalPortalStorage.get(originalWorld).data
                .stream().filter(
                    p -> p.getUniqueID().equals(portalId)
                ).findFirst().orElse(null);
        }
        if (portalEntity == null) {
            return null;
        }
        if (portalEntity instanceof Portal) {
            return ((Portal) portalEntity);
        }
        return null;
    }
    
    public void recordLastPosition(ServerPlayerEntity player) {
        lastPosition.put(
            player,
            new Tuple<>(player.world.func_234923_W_(), player.getPositionVec())
        );
    }
    
    private boolean canPlayerTeleport(
        ServerPlayerEntity player,
        RegistryKey<World> dimensionBefore,
        Vector3d posBefore,
        Entity portalEntity
    ) {
        if (player.getRidingEntity() != null) {
            return true;
        }
        return canPlayerReachPos(player, dimensionBefore, posBefore) &&
            portalEntity instanceof Portal &&
            ((Portal) portalEntity).getDistanceToPlane(posBefore) < 20;
    }
    
    public static boolean canPlayerReachPos(
        ServerPlayerEntity player,
        RegistryKey<World> dimension,
        Vector3d pos
    ) {
        Vector3d playerPos = player.getPositionVec();
        if (player.world.func_234923_W_() == dimension) {
            if (playerPos.squareDistanceTo(pos) < 256) {
                return true;
            }
        }
        return McHelper.getNearbyPortals(player, 20)
            .filter(portal -> portal.dimensionTo == dimension)
            .map(portal -> portal.transformPoint(playerPos))
            .anyMatch(mappedPos -> mappedPos.squareDistanceTo(pos) < 256);
    }
    
    public void teleportPlayer(
        ServerPlayerEntity player,
        RegistryKey<World> dimensionTo,
        Vector3d newEyePos
    ) {
        McHelper.getServer().getProfiler().startSection("portal_teleport");
        
        ServerWorld fromWorld = (ServerWorld) player.world;
        ServerWorld toWorld = McHelper.getServer().getWorld(dimensionTo);
        
        if (player.world.func_234923_W_() == dimensionTo) {
            McHelper.setEyePos(player, newEyePos, newEyePos);
            McHelper.updateBoundingBox(player);
        }
        else {
            changePlayerDimension(player, fromWorld, toWorld, newEyePos);
            ((IEServerPlayNetworkHandler) player.connection).cancelTeleportRequest();
        }
        
        McHelper.adjustVehicle(player);
        player.connection.captureCurrentPosition();
        
        McHelper.getServer().getProfiler().endSection();
    }
    
    public void invokeTpmeCommand(
        ServerPlayerEntity player,
        RegistryKey<World> dimensionTo,
        Vector3d newPos
    ) {
        ServerWorld fromWorld = (ServerWorld) player.world;
        ServerWorld toWorld = McHelper.getServer().getWorld(dimensionTo);
        
        if (player.world.func_234923_W_() == dimensionTo) {
            player.setPosition(newPos.x, newPos.y, newPos.z);
        }
        else {
            changePlayerDimension(player, fromWorld, toWorld, newPos);
            sendPositionConfirmMessage(player);
        }
        
        player.connection.setPlayerLocation(
            newPos.x,
            newPos.y,
            newPos.z,
            player.rotationYaw,
            player.rotationPitch
        );
        player.connection.captureCurrentPosition();
        ((IEServerPlayNetworkHandler) player.connection).cancelTeleportRequest();
        
    }
    
    /**
     * {@link ServerPlayerEntity#moveToWorld(ServerWorld)}
     */
    private void changePlayerDimension(
        ServerPlayerEntity player,
        ServerWorld fromWorld,
        ServerWorld toWorld,
        Vector3d newEyePos
    ) {
        NewChunkTrackingGraph.addAdditionalDirectLoadingTickets(player);
        
        teleportingEntities.add(player);
        
        Entity vehicle = player.getRidingEntity();
        if (vehicle != null) {
            ((IEServerPlayerEntity) player).stopRidingWithoutTeleportRequest();
        }
        
        Vector3d oldPos = player.getPositionVec();
        
        O_O.segregateServerPlayer(fromWorld, player);
        
        McHelper.setEyePos(player, newEyePos, newEyePos);
        McHelper.updateBoundingBox(player);
        
        player.world = toWorld;
        toWorld.addDuringPortalTeleport(player);
        
        toWorld.chunkCheck(player);
        
        player.interactionManager.setWorld(toWorld);
        
        if (vehicle != null) {
            Vector3d vehiclePos = new Vector3d(
                newEyePos.x,
                McHelper.getVehicleY(vehicle, player),
                newEyePos.z
            );
            changeEntityDimension(
                vehicle,
                toWorld.func_234923_W_(),
                vehiclePos.add(0, vehicle.getEyeHeight(), 0),
                false
            );
            ((IEServerPlayerEntity) player).startRidingWithoutTeleportRequest(vehicle);
            McHelper.adjustVehicle(player);
        }
        
        Helper.log(String.format(
            "%s :: (%s %s %s %s)->(%s %s %s %s)",
            player.getName().getUnformattedComponentText(),
            fromWorld.func_234923_W_().func_240901_a_(),
            oldPos.getX(), oldPos.getY(), oldPos.getZ(),
            toWorld.func_234923_W_().func_240901_a_(),
            (int) player.getPosX(), (int) player.getPosY(), (int) player.getPosZ()
        ));
        
        O_O.onPlayerTravelOnServer(
            player,
            fromWorld.func_234923_W_(),
            toWorld.func_234923_W_()
        );
        
        //update advancements
        if (toWorld.func_234923_W_() == World.field_234919_h_) {
            ((IEServerPlayerEntity) player).setEnteredNetherPos(player.getPositionVec());
        }
        ((IEServerPlayerEntity) player).updateDimensionTravelAdvancements(fromWorld);
        
        
    }
    
    public static void sendPositionConfirmMessage(ServerPlayerEntity player) {
        IPacket packet = MyNetwork.createStcDimensionConfirm(
            player.world.func_234923_W_(),
            player.getPositionVec()
        );
        
        player.connection.sendPacket(packet);
    }
    
    private void tick() {
        teleportingEntities = new HashSet<>();
        long tickTimeNow = McHelper.getServerGameTime();
        ArrayList<ServerPlayerEntity> copiedPlayerList =
            McHelper.getCopiedPlayerList();
        if (tickTimeNow % 30 == 7) {
            for (ServerPlayerEntity player : copiedPlayerList) {
                updateForPlayer(tickTimeNow, player);
            }
        }
        copiedPlayerList.forEach(player -> {
            McHelper.getServerEntitiesNearbyWithoutLoadingChunk(
                player.world,
                player.getPositionVec(),
                Entity.class,
                32
            ).filter(
                entity -> !(entity instanceof ServerPlayerEntity)
            ).forEach(entity -> {
                McHelper.getGlobalPortals(entity.world).stream()
                    .filter(
                        globalPortal -> shouldEntityTeleport(globalPortal, entity)
                    )
                    .findFirst()
                    .ifPresent(
                        globalPortal -> tryToTeleportRegularEntity(globalPortal, entity)
                    );
            });
        });
    }
    
    private void updateForPlayer(long tickTimeNow, ServerPlayerEntity player) {
        // teleporting means dimension change
        // inTeleportationState means syncing position to client
        if (player.queuedEndExit || player.forceSpawn) {
            lastTeleportGameTime.put(player, tickTimeNow);
            return;
        }
        Long lastTeleportGameTime =
            this.lastTeleportGameTime.getOrDefault(player, 0L);
        if (tickTimeNow - lastTeleportGameTime > 60) {
            sendPositionConfirmMessage(player);
            
            //for vanilla nether portal cooldown to work normally
            player.clearInvulnerableDimensionChange();
        }
        else {
            ((IEServerPlayNetworkHandler) player.connection).cancelTeleportRequest();
        }
    }
    
    public boolean isTeleporting(ServerPlayerEntity entity) {
        return teleportingEntities.contains(entity);
    }
    
    private void teleportRegularEntity(Entity entity, Portal portal) {
        Validate.isTrue(!(entity instanceof ServerPlayerEntity));
        if (entity.world != portal.world) {
            Helper.err(String.format("Cannot teleport %s from %s through %s", entity, entity.world.func_234923_W_(), portal));
            return;
        }
        
        long currGameTime = McHelper.getServerGameTime();
        Long lastTeleportGameTime = this.lastTeleportGameTime.getOrDefault(entity, 0L);
        if (currGameTime - lastTeleportGameTime <= 1) {
            return;
        }
        this.lastTeleportGameTime.put(entity, currGameTime);
        
        if (entity.isPassenger() || doesEntityClutterContainPlayer(entity)) {
            return;
        }
        
        Vector3d velocity = entity.getMotion();
        
        List<Entity> passengerList = entity.getPassengers();
        
        Vector3d newEyePos = getRegularEntityTeleportedEyePos(entity, portal);
        
        if (portal.dimensionTo != entity.world.func_234923_W_()) {
            entity = changeEntityDimension(entity, portal.dimensionTo, newEyePos, true);
            
            Entity newEntity = entity;
            
            passengerList.stream().map(
                e -> changeEntityDimension(e, portal.dimensionTo, newEyePos, true)
            ).collect(Collectors.toList()).forEach(e -> {
                e.startRiding(newEntity, true);
            });
        }
        
        McHelper.setEyePos(entity, newEyePos, newEyePos);
        McHelper.updateBoundingBox(entity);
        
        ((ServerWorld) entity.world).chunkCheck(entity);
        
        portal.transformVelocity(entity);
        
        portal.onEntityTeleportedOnServer(entity);
        
        PehkuiInterface.onServerEntityTeleported.accept(entity, portal);
        
        // a new entity may be created
        this.lastTeleportGameTime.put(entity, currGameTime);
    }
    
    private static Vector3d getRegularEntityTeleportedEyePos(Entity entity, Portal portal) {
		Vector3d eyePosNextTick = McHelper.getEyePos(entity).add(entity.getMotion());
        if (entity instanceof ProjectileEntity) {
            Vector3d collidingPoint = portal.rayTrace(
                eyePosNextTick.subtract(entity.getMotion().normalize().scale(5)), eyePosNextTick);
            
            if (collidingPoint == null) {
                collidingPoint = eyePosNextTick;
            }
            
            return portal.transformPoint(collidingPoint).add(portal.getContentDirection().scale(0.01));
        }
        else {
            return portal.transformPoint(eyePosNextTick);
        }
    }
    
    /**
     * {@link Entity#moveToWorld(ServerWorld)}
     * Sometimes resuing the same entity object is problematic
     * because entity's AI related things may have world reference inside
     */
    public Entity changeEntityDimension(
        Entity entity,
        RegistryKey<World> toDimension,
        Vector3d newEyePos,
        boolean recreateEntity
    ) {
        ServerWorld fromWorld = (ServerWorld) entity.world;
        ServerWorld toWorld = McHelper.getServer().getWorld(toDimension);
        entity.detach();
        
        if (recreateEntity) {
            Entity oldEntity = entity;
            Entity newEntity;
            newEntity = entity.getType().create(toWorld);
            if (newEntity == null) {
                return oldEntity;
            }
            
            newEntity.copyDataFromOld(oldEntity);
            McHelper.setEyePos(newEntity, newEyePos, newEyePos);
            McHelper.updateBoundingBox(newEntity);
            newEntity.setRotationYawHead(oldEntity.getRotationYawHead());
            
            // calling remove() will make chest minecart item duplicate
            oldEntity.removed = true;
            
            toWorld.addFromAnotherDimension(newEntity);
            
            return newEntity;
        }
        else {
            O_O.segregateServerEntity(fromWorld, entity);
            
            McHelper.setEyePos(entity, newEyePos, newEyePos);
            McHelper.updateBoundingBox(entity);
            
            entity.world = toWorld;
            
            toWorld.addFromAnotherDimension(entity);
            
            return entity;
        }
        
        
    }
    
    private boolean doesEntityClutterContainPlayer(Entity entity) {
        if (entity instanceof PlayerEntity) {
            return true;
        }
        List<Entity> passengerList = entity.getPassengers();
        if (passengerList.isEmpty()) {
            return false;
        }
        return passengerList.stream().anyMatch(this::doesEntityClutterContainPlayer);
    }
    
    public boolean isJustTeleported(Entity entity, long valveTickTime) {
        long currGameTime = McHelper.getServerGameTime();
        Long lastTeleportGameTime = this.lastTeleportGameTime.getOrDefault(entity, 0L);
        return currGameTime - lastTeleportGameTime < valveTickTime;
    }
    
    private static final LimitedLogger limitedLogger = new LimitedLogger(20);
    
    public void acceptDubiousMovePacket(
        ServerPlayerEntity player,
        CPlayerPacket packet,
        RegistryKey<World> dimension
    ) {
        if (player.world.func_234923_W_() == dimension) {
            return;
        }
        double x = packet.getX(player.getPosX());
        double y = packet.getY(player.getPosY());
        double z = packet.getZ(player.getPosZ());
        Vector3d newPos = new Vector3d(x, y, z);
        if (canPlayerReachPos(player, dimension, newPos)) {
            recordLastPosition(player);
            teleportPlayer(player, dimension, newPos);
            limitedLogger.log(String.format("accepted dubious move packet %s %s %s %s %s %s %s",
                player.world.func_234923_W_(), x, y, z, player.getPosX(), player.getPosY(), player.getPosZ()
            ));
        }
        else {
            limitedLogger.log(String.format("ignored dubious move packet %s %s %s %s %s %s %s",
                player.world.func_234923_W_().func_240901_a_(), x, y, z, player.getPosX(), player.getPosY(), player.getPosZ()
            ));
        }
    }
    
    public static void teleportEntityGeneral(Entity entity, Vector3d targetPos, ServerWorld targetWorld) {
        if (entity instanceof ServerPlayerEntity) {
            Global.serverTeleportationManager.invokeTpmeCommand(
                (ServerPlayerEntity) entity, targetWorld.func_234923_W_(), targetPos
            );
        }
        else {
            if (targetWorld == entity.world) {
                entity.setLocationAndAngles(
                    targetPos.x,
                    targetPos.y,
                    targetPos.z,
                    entity.rotationYaw,
                    entity.rotationPitch
                );
                entity.setRotationYawHead(entity.rotationYaw);
            }
            else {
                Global.serverTeleportationManager.changeEntityDimension(
                    entity,
                    targetWorld.func_234923_W_(),
                    targetPos.add(0, entity.getEyeHeight(), 0),
                    true
                );
            }
        }
    }
    
    // make the mobs chase the player through portal
    // (only works in simple cases)
    private static void notifyChasersForPlayer(
        ServerPlayerEntity player,
        Portal portal
    ) {
        List<MobEntity> chasers = McHelper.findEntitiesRough(
            MobEntity.class,
            player.world,
            player.getPositionVec(),
            1,
            e -> e.getAttackTarget() == player
        );
        
        for (MobEntity chaser : chasers) {
            chaser.setAttackTarget(null);
            notifyChaser(player, portal, chaser);
        }
    }
    
    private static void notifyChaser(
        ServerPlayerEntity player,
        Portal portal,
        MobEntity chaser
    ) {
        Vector3d targetPos = player.getPositionVec().add(portal.getNormal().scale(-0.1));
        
        UUID chaserId = chaser.getUniqueID();
        ServerWorld destWorld = ((ServerWorld) portal.getDestinationWorld());
        
        ModMain.serverTaskList.addTask(MyTaskList.withRetryNumberLimit(
            140,
            () -> {
                if (chaser.removed) {
                    // the chaser teleported
                    Entity newChaser = destWorld.getEntityByUuid(chaserId);
                    if (newChaser instanceof MobEntity) {
                        ((MobEntity) newChaser).setAttackTarget(player);
                        return true;
                    }
                    else {
                        return false;
                    }
                }
                
                if (chaser.getPositionVec().distanceTo(targetPos) < 2) {
                    chaser.getMoveHelper().setMoveTo(
                        targetPos.x, targetPos.y, targetPos.z, 1
                    );
                }
                else {
                    @Nullable
                    Path path = chaser.getNavigator().getPathToPos(
                        new BlockPos(targetPos), 0
                    );
                    chaser.getNavigator().setPath(path, 1);
                }
                return false;
            },
            () -> {}
        ));
    }
}
