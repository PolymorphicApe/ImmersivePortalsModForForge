package com.qouteall.immersive_portals.portal;

import com.qouteall.hiding_in_the_bushes.MyNetwork;
import com.qouteall.immersive_portals.CHelper;
import com.qouteall.immersive_portals.Global;
import com.qouteall.immersive_portals.Helper;
import com.qouteall.immersive_portals.McHelper;
import com.qouteall.immersive_portals.OFInterface;
import com.qouteall.immersive_portals.PehkuiInterface;
import com.qouteall.immersive_portals.dimension_sync.DimId;
import com.qouteall.immersive_portals.my_util.BoxPredicate;
import com.qouteall.immersive_portals.my_util.Plane;
import com.qouteall.immersive_portals.my_util.RotationHelper;
import com.qouteall.immersive_portals.my_util.SignalArged;
import com.qouteall.immersive_portals.my_util.SignalBiArged;
import com.qouteall.immersive_portals.render.FrustumCuller;
import com.qouteall.immersive_portals.render.PortalRenderInfo;
import com.qouteall.immersive_portals.render.PortalRenderer;
import com.qouteall.immersive_portals.render.PortalRenderingGroup;
import com.qouteall.immersive_portals.render.ViewAreaRenderer;
import com.qouteall.immersive_portals.teleportation.CollisionHelper;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.minecart.AbstractMinecartEntity;
import net.minecraft.entity.EntitySize;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.Pose;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.nbt.StringNBT;
import net.minecraft.network.IPacket;
import net.minecraft.util.Direction;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.Util;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Matrix3f;
import net.minecraft.util.math.vector.Matrix4f;
import net.minecraft.util.math.vector.Quaternion;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.math.vector.Vector3f;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.apache.commons.lang3.Validate;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Portal entity. Global portals are also entities but not added into world.
 */
public class Portal extends Entity implements PortalLike {
    public static EntityType<Portal> entityType;
    
    public static final UUID nullUUID = Util.field_240973_b_;
    
    /**
     * The portal area length along axisW
     */
    public double width = 0;
    public double height = 0;
    
    /**
     * axisW and axisH define the orientation of the portal
     * They should be normalized and should be perpendicular to each other
     */
    public Vector3d axisW;
    public Vector3d axisH;
    
    /**
     * The destination dimension
     */
    public RegistryKey<World> dimensionTo;
    
    
    /**
     * The destination position
     */
    public Vector3d destination;
    
    /**
     * If false, cannot teleport entities
     */
    public boolean teleportable = true;
    
    /**
     * If not null, this portal can only be accessed by one player
     * If it's {@link Portal#nullUUID} the portal can only be accessed by entities
     */
    @Nullable
    public UUID specificPlayerId;
    
    /**
     * If not null, defines the special shape of the portal
     * The shape should not exceed the area defined by width and height
     */
    @Nullable
    public GeometryPortalShape specialShape;
    
    /**
     * The bounding box. Expanded very little.
     */
    private AxisAlignedBB exactBoundingBoxCache;
    
    /**
     * The bounding box. Expanded a little.
     */
    private AxisAlignedBB boundingBoxCache;
    private Vector3d normal;
    private Vector3d contentDirection;
    
    /**
     * For outer frustum culling
     */
    public double cullableXStart = 0;
    public double cullableXEnd = 0;
    public double cullableYStart = 0;
    public double cullableYEnd = 0;
    
    /**
     * The rotating transformation of the portal
     */
    @Nullable
    public Quaternion rotation;
    
    /**
     * The scaling transformation of the portal
     */
    public double scaling = 1.0;
    
    /**
     * Whether the entity scale changes after crossing the portal
     */
    public boolean teleportChangesScale = true;
    
    /**
     * Whether the player can place and break blocks across the portal
     */
    private boolean interactable = true;
    
    PortalExtension extension;
    
    @Nullable
    public String portalTag;
    
    /**
     * Non-global portals are normal entities,
     * global portals are in GlobalPortalStorage and always loaded
     */
    public boolean isGlobalPortal = false;
    
    /**
     * If true, the portal rendering will not render the sky and maintain the outer world depth.
     * So that the things inside portal will look fused with the things outside portal
     */
    public boolean fuseView = false;
    
    /**
     * If true, if the portal touches another portal that have the same spacial transformation,
     * these two portals' rendering will be merged.
     * It can improve rendering performance.
     * However, the merged rendering's front clipping won't work as if they are separately rendered.
     * So this is by default disabled.
     */
    public boolean renderingMergable = false;
    
    /**
     * If false, the cross portal collision will be ignored
     */
    public boolean hasCrossPortalCollision = true;
    
    @Nullable
    public List<String> commandsOnTeleported;
    
    public static final SignalArged<Portal> clientPortalTickSignal = new SignalArged<>();
    public static final SignalArged<Portal> serverPortalTickSignal = new SignalArged<>();
    public static final SignalArged<Portal> portalCacheUpdateSignal = new SignalArged<>();
    public static final SignalArged<Portal> portalDisposeSignal = new SignalArged<>();
    public static final SignalBiArged<Portal, CompoundNBT> readPortalDataSignal = new SignalBiArged<>();
    public static final SignalBiArged<Portal, CompoundNBT> writePortalDataSignal = new SignalBiArged<>();
    
    public Portal(
        EntityType<?> entityType, World world
    ) {
        super(entityType, world);
    }
    
    /**
     * @param pos
     * @return use the portal's transformation to transform a point
     */
    @Override
    public Vector3d transformPoint(Vector3d pos) {
        Vector3d localPos = pos.subtract(getOriginPos());
        
        return transformLocalVec(localPos).add(getDestPos());
        
    }
    
    /**
     * Transform a vector in portal-centered coordinate (without translation transformation)
     */
    @Override
    public Vector3d transformLocalVec(Vector3d localVec) {
        return transformLocalVecNonScale(localVec).scale(scaling);
    }
    
    /**
     * @return The normal vector of the portal plane
     */
    public Vector3d getNormal() {
        if (normal == null) {
            normal = axisW.crossProduct(axisH).normalize();
        }
        return normal;
    }
    
    /**
     * @return The direction of "portal content", the "direction" of the "inner world view"
     */
    public Vector3d getContentDirection() {
        if (contentDirection == null) {
            contentDirection = transformLocalVecNonScale(getNormal().scale(-1));
        }
        return contentDirection;
    }
    
    /**
     * Will be invoked when an entity teleports through this portal on server side.
     * This method can be overridden.
     */
    public void onEntityTeleportedOnServer(Entity entity) {
        if (commandsOnTeleported != null) {
            CommandSource commandSource =
                entity.getCommandSource().withPermissionLevel(2).withFeedbackDisabled();
            
            Commands commandManager = McHelper.getServer().getCommandManager();
            for (String command : commandsOnTeleported) {
                commandManager.handleCommand(commandSource, command);
            }
        }
    }
    
    /**
     * Update the portal's cache and send the entity spawn packet to client again
     */
    public void reloadAndSyncToClient() {
        Validate.isTrue(!isGlobalPortal);
        Validate.isTrue(!world.isRemote());
        updateCache();
        McHelper.getIEStorage(this.world.func_234923_W_()).resendSpawnPacketToTrackers(this);
    }
    
    /**
     * The bounding box, normal vector, content direction vector is cached
     * If the portal attributes get changed, these cache should be updated
     */
    // TODO rename
    public void updateCache() {
        boolean updates =
            boundingBoxCache != null || exactBoundingBoxCache != null ||
                normal != null || contentDirection != null;
        
        boundingBoxCache = null;
        exactBoundingBoxCache = null;
        normal = null;
        contentDirection = null;
        
        if (updates) {
            portalCacheUpdateSignal.emit(this);
        }
    }
    
    /**
     * @return The portal center
     */
    @Override
    public Vector3d getOriginPos() {
        return getPositionVec();
    }
    
    /**
     * @return The destination position
     */
    @Override
    public Vector3d getDestPos() {
        return destination;
    }
    
    /**
     * Set the portal's center position
     */
    public void setOriginPos(Vector3d pos) {
        setPosition(pos.x, pos.y, pos.z);
        // it will call setPos and update the cache
    }
    
    /**
     * Set the destination dimension
     * If the dimension does not exist, the portal is invalid and will be automatically removed
     */
    public void setDestinationDimension(RegistryKey<World> dim) {
        dimensionTo = dim;
    }
    
    /**
     * Set the portal's destination
     */
    public void setDestination(Vector3d destination) {
        this.destination = destination;
        updateCache();
    }
    
    /**
     * @param portalPosRelativeToCamera The portal position relative to camera
     * @param vertexOutput              Output the triangles that constitute the portal view area.
     *                                  Every 3 vertices correspond to a triangle.
     *                                  In camera-centered coordinate.
     */
    @OnlyIn(Dist.CLIENT)
    @Override
    public void renderViewAreaMesh(Vector3d portalPosRelativeToCamera, Consumer<Vector3d> vertexOutput) {
        if (this instanceof Mirror) {
            //rendering portal behind translucent objects with shader is broken
            double mirrorOffset =
                (OFInterface.isShaders.getAsBoolean() || Global.pureMirror) ? 0.01 : -0.01;
            portalPosRelativeToCamera = portalPosRelativeToCamera.add(
                ((Mirror) this).getNormal().scale(mirrorOffset));
        }
        
        ViewAreaRenderer.generateViewAreaTriangles(this, portalPosRelativeToCamera, vertexOutput);
    }
    
    @Override
    public boolean isFuseView() {
        return fuseView;
    }
    
    public boolean isRenderingMergable() {
        return renderingMergable;
    }
    
    /**
     * Determines whether the player should be able to reach through the portal or not.
     * Can be overridden by a sub class.
     *
     * @return the interactability of the portal
     */
    public boolean isInteractable() {
        return interactable;
    }
    
    /**
     * Changes the reach-through behavior of the portal.
     *
     * @param interactable the interactability of the portal
     */
    public void setInteractable(boolean interactable) {
        this.interactable = interactable;
    }
    
    @Override
    public World getOriginWorld() {
        return world;
    }
    
    @Override
    public World getDestWorld() {
        return getDestinationWorld();
    }
    
    @Override
    public RegistryKey<World> getDestDim() {
        return dimensionTo;
    }
    
    @Override
    public double getScale() {
        return scaling;
    }
    
    @Override
    public boolean getIsGlobal() {
        return isGlobalPortal;
    }
    
    /**
     * @param entity
     * @return Can the portal teleport this entity.
     */
    public boolean canTeleportEntity(Entity entity) {
        if (!teleportable) {
            return false;
        }
        if (entity instanceof ServerPlayerEntity) {
            if (specificPlayerId != null) {
                if (!entity.getUniqueID().equals(specificPlayerId)) {
                    return false;
                }
            }
        }
        else {
            if (specificPlayerId != null) {
                if (!specificPlayerId.equals(nullUUID)) {
                    // it can only be used by the player
                    return false;
                }
            }
        }
        
        return entity.isNonBoss();
    }
    
    @Nullable
    @Override
    public Quaternion getRotation() {
        return rotation;
    }
    
    public void setOrientationAndSize(
        Vector3d newAxisW, Vector3d newAxisH,
        double newWidth, double newHeight
    ) {
        axisW = newAxisW;
        axisH = newAxisH;
        width = newWidth;
        height = newHeight;
        
        updateCache();
    }
    
    public void setRotationTransformation(Quaternion quaternion) {
        rotation = quaternion;
        updateCache();
    }
    
    public void setScaleTransformation(double newScale) {
        scaling = newScale;
    }
    
    @Override
    protected void readAdditional(CompoundNBT compoundTag) {
        width = compoundTag.getDouble("width");
        height = compoundTag.getDouble("height");
        axisW = Helper.getVec3d(compoundTag, "axisW").normalize();
        axisH = Helper.getVec3d(compoundTag, "axisH").normalize();
        dimensionTo = DimId.getWorldId(compoundTag, "dimensionTo", world.isRemote);
        setDestination(Helper.getVec3d(compoundTag, "destination"));
        if (compoundTag.contains("specificPlayer")) {
            specificPlayerId = Helper.getUuid(compoundTag, "specificPlayer");
        }
        if (compoundTag.contains("specialShape")) {
            specialShape = new GeometryPortalShape(
                compoundTag.getList("specialShape", 6)
            );
            
            if (specialShape.triangles.isEmpty()) {
                specialShape = null;
            }
            else {
                if (!specialShape.isValid()) {
                    Helper.err("Portal shape invalid ");
                    specialShape = null;
                }
            }
        }
        if (compoundTag.contains("teleportable")) {
            teleportable = compoundTag.getBoolean("teleportable");
        }
        if (compoundTag.contains("cullableXStart")) {
            cullableXStart = compoundTag.getDouble("cullableXStart");
            cullableXEnd = compoundTag.getDouble("cullableXEnd");
            cullableYStart = compoundTag.getDouble("cullableYStart");
            cullableYEnd = compoundTag.getDouble("cullableYEnd");
            
            cullableXEnd = Math.min(cullableXEnd, width / 2);
            cullableXStart = Math.max(cullableXStart, -width / 2);
            cullableYEnd = Math.min(cullableYEnd, height / 2);
            cullableYStart = Math.max(cullableYStart, -height / 2);
        }
        else {
            if (specialShape != null) {
                cullableXStart = 0;
                cullableXEnd = 0;
                cullableYStart = 0;
                cullableYEnd = 0;
            }
            else {
                initDefaultCullableRange();
            }
        }
        if (compoundTag.contains("rotationA")) {
            rotation = new Quaternion(
                compoundTag.getFloat("rotationB"),
                compoundTag.getFloat("rotationC"),
                compoundTag.getFloat("rotationD"),
                compoundTag.getFloat("rotationA")
            );
        }
        
        if (compoundTag.contains("interactable")) {
            interactable = compoundTag.getBoolean("interactable");
        }
        
        if (compoundTag.contains("scale")) {
            scaling = compoundTag.getDouble("scale");
        }
        if (compoundTag.contains("teleportChangesScale")) {
            teleportChangesScale = compoundTag.getBoolean("teleportChangesScale");
        }
        
        if (compoundTag.contains("portalTag")) {
            portalTag = compoundTag.getString("portalTag");
        }
        
        if (compoundTag.contains("fuseView")) {
            fuseView = compoundTag.getBoolean("fuseView");
        }
        
        if (compoundTag.contains("renderingMergable")) {
            renderingMergable = compoundTag.getBoolean("renderingMergable");
        }
        
        if (compoundTag.contains("hasCrossPortalCollision")) {
            hasCrossPortalCollision = compoundTag.getBoolean("hasCrossPortalCollision");
        }
        
        if (compoundTag.contains("commandsOnTeleported")) {
            ListNBT list = compoundTag.getList("commandsOnTeleported", 8);
            commandsOnTeleported = list.stream()
                .map(t -> ((StringNBT) t).getString()).collect(Collectors.toList());
        }
        else {
            commandsOnTeleported = null;
        }
        
        readPortalDataSignal.emit(this, compoundTag);
        
        updateCache();
    }
    
    @Override
    protected void writeAdditional(CompoundNBT compoundTag) {
        compoundTag.putDouble("width", width);
        compoundTag.putDouble("height", height);
        Helper.putVec3d(compoundTag, "axisW", axisW);
        Helper.putVec3d(compoundTag, "axisH", axisH);
        DimId.putWorldId(compoundTag, "dimensionTo", dimensionTo);
        Helper.putVec3d(compoundTag, "destination", getDestPos());
        
        if (specificPlayerId != null) {
            Helper.putUuid(compoundTag, "specificPlayer", specificPlayerId);
        }
        
        if (specialShape != null) {
            compoundTag.put("specialShape", specialShape.writeToTag());
        }
        
        compoundTag.putBoolean("teleportable", teleportable);
        
        if (specialShape == null) {
            initDefaultCullableRange();
        }
        compoundTag.putDouble("cullableXStart", cullableXStart);
        compoundTag.putDouble("cullableXEnd", cullableXEnd);
        compoundTag.putDouble("cullableYStart", cullableYStart);
        compoundTag.putDouble("cullableYEnd", cullableYEnd);
        if (rotation != null) {
            compoundTag.putDouble("rotationA", rotation.getW());
            compoundTag.putDouble("rotationB", rotation.getX());
            compoundTag.putDouble("rotationC", rotation.getY());
            compoundTag.putDouble("rotationD", rotation.getZ());
        }
        
        compoundTag.putBoolean("interactable", interactable);
        
        compoundTag.putDouble("scale", scaling);
        compoundTag.putBoolean("teleportChangesScale", teleportChangesScale);
        
        if (portalTag != null) {
            compoundTag.putString("portalTag", portalTag);
        }
        
        compoundTag.putBoolean("fuseView", fuseView);
        
        compoundTag.putBoolean("renderingMergable", renderingMergable);
        
        compoundTag.putBoolean("hasCrossPortalCollision", hasCrossPortalCollision);
        
        if (commandsOnTeleported != null) {
            ListNBT list = new ListNBT();
            for (String command : commandsOnTeleported) {
                list.add(StringNBT.valueOf(command));
            }
            compoundTag.put(
                "commandsOnTeleported",
                list
            );
        }
        
        writePortalDataSignal.emit(this, compoundTag);
        
    }
    
    @Override
    public void setRawPosition(double x, double y, double z) {
        boolean shouldUpdate = getPosX() != x || getPosY() != y || getPosZ() != z;
        
        super.setRawPosition(x, y, z);
        
        if (shouldUpdate) {
            updateCache();
        }
    }
    
    
    private void initDefaultCullableRange() {
        cullableXStart = -(width / 2);
        cullableXEnd = (width / 2);
        cullableYStart = -(height / 2);
        cullableYEnd = (height / 2);
    }
    
    public void initCullableRange(
        double cullableXStart,
        double cullableXEnd,
        double cullableYStart,
        double cullableYEnd
    ) {
        this.cullableXStart = Math.min(cullableXStart, cullableXEnd);
        this.cullableXEnd = Math.max(cullableXStart, cullableXEnd);
        this.cullableYStart = Math.min(cullableYStart, cullableYEnd);
        this.cullableYEnd = Math.max(cullableYStart, cullableYEnd);
    }
    
    @Override
    public IPacket<?> createSpawnPacket() {
        return MyNetwork.createStcSpawnEntity(this);
    }
    
    @Override
    public boolean isSpectatedByPlayer(ServerPlayerEntity spectator) {
        if (specificPlayerId == null) {
            return true;
        }
        return spectator.getUniqueID().equals(specificPlayerId);
    }
    
    @Override
    public void tick() {
        if (world.isRemote) {
            clientPortalTickSignal.emit(this);
        }
        else {
            if (!isPortalValid()) {
                Helper.log("removed invalid portal" + this);
                remove();
                return;
            }
            serverPortalTickSignal.emit(this);
        }
        
        CollisionHelper.notifyCollidingPortals(this);
    }
    
    @Override
    public AxisAlignedBB getBoundingBox() {
        if (axisW == null) {
            //avoid npe with pehkui
            //pehkui will invoke this when axisW is not initialized
            boundingBoxCache = null;
            return new AxisAlignedBB(0, 0, 0, 0, 0, 0);
        }
        if (boundingBoxCache == null) {
            double w = width;
            double h = height;
            if (shouldLimitBoundingBox()) {
                // avoid bounding box too big after converting global portal to normal portal
                w = Math.min(this.width, 64.0);
                h = Math.min(this.height, 64.0);
            }
            
            boundingBoxCache = new AxisAlignedBB(
                getPointInPlane(w / 2, h / 2)
                    .add(getNormal().scale(0.2)),
                getPointInPlane(-w / 2, -h / 2)
                    .add(getNormal().scale(-0.2))
            ).union(new AxisAlignedBB(
                getPointInPlane(-w / 2, h / 2)
                    .add(getNormal().scale(0.2)),
                getPointInPlane(w / 2, -h / 2)
                    .add(getNormal().scale(-0.2))
            ));
        }
        return boundingBoxCache;
    }
    
    protected boolean shouldLimitBoundingBox() {
        return !getIsGlobal();
    }
    
    // TODO rename
    public AxisAlignedBB getExactBoundingBox() {
        if (exactBoundingBoxCache == null) {
            exactBoundingBoxCache = new AxisAlignedBB(
                getPointInPlane(width / 2, height / 2)
                    .add(getNormal().scale(0.02)),
                getPointInPlane(-width / 2, -height / 2)
                    .add(getNormal().scale(-0.02))
            ).union(new AxisAlignedBB(
                getPointInPlane(-width / 2, height / 2)
                    .add(getNormal().scale(0.02)),
                getPointInPlane(width / 2, -height / 2)
                    .add(getNormal().scale(-0.02))
            ));
        }
        
        return exactBoundingBoxCache;
    }
    
    @Override
    public void setBoundingBox(AxisAlignedBB boundingBox) {
        boundingBoxCache = null;
    }
    
    @Override
    public void move(MoverType type, Vector3d movement) {
        //portal cannot be moved
    }
    
    /**
     * Invalid portals will be automatically removed
     */
    public boolean isPortalValid() {
        boolean valid = dimensionTo != null &&
            width != 0 &&
            height != 0 &&
            axisW != null &&
            axisH != null &&
            getDestPos() != null &&
            axisW.lengthSquared() > 0.9 &&
            axisH.lengthSquared() > 0.9 &&
            getPosY() > -1000;
        if (valid) {
            if (world instanceof ServerWorld) {
                ServerWorld destWorld = McHelper.getServer().getWorld(dimensionTo);
                if (destWorld == null) {
                    Helper.err("Missing Dimension " + dimensionTo.func_240901_a_());
                    return false;
                }
            }
        }
        return valid;
    }
    
    /**
     * @return A UUID for discriminating portal rendering units.
     */
    @Nullable
    @Override
    public UUID getDiscriminator() {
        return getUniqueID();
    }
    
    @Override
    public String toString() {
        return String.format(
            "%s{%s,%s,(%s %s %s %s)->(%s %s %s %s)%s%s%s}",
            getClass().getSimpleName(),
            getEntityId(),
            Direction.getFacingFromVector(
                getNormal().x, getNormal().y, getNormal().z
            ),
            world.func_234923_W_().func_240901_a_(), (int) getPosX(), (int) getPosY(), (int) getPosZ(),
            dimensionTo.func_240901_a_(), (int) getDestPos().x, (int) getDestPos().y, (int) getDestPos().z,
            specificPlayerId != null ? (",specificAccessor:" + specificPlayerId.toString()) : "",
            hasScaling() ? (",scale:" + scaling) : "",
            portalTag != null ? "," + portalTag : ""
        );
    }
    
    public void transformVelocity(Entity entity) {
        if (PehkuiInterface.isPehkuiPresent) {
            if (teleportChangesScale) {
                entity.setMotion(transformLocalVecNonScale(entity.getMotion()));
            }
            else {
                entity.setMotion(transformLocalVec(entity.getMotion()));
            }
        }
        else {
            entity.setMotion(transformLocalVec(entity.getMotion()));
        }
        
        final int maxVelocity = 15;
        if (entity.getMotion().length() > maxVelocity) 
		{
            // cannot be too fast
            entity.setMotion(entity.getMotion().normalize().scale(maxVelocity));
        }
		
		if (entity instanceof AbstractMinecartEntity && entity.getMotion().lengthSquared() < 0.5) 
		{
            entity.setMotion(entity.getMotion().normalize().scale(2));
        }
		
    }
    
    /**
     * @param pos
     * @return the distance to the portal plane without regarding the shape
     */
    public double getDistanceToPlane(Vector3d pos) {
        return pos.subtract(getOriginPos()).dotProduct(getNormal());
    }
    
    /**
     * @param pos
     * @return is the point in front of the portal plane without regarding the shape
     */
    public boolean isInFrontOfPortal(Vector3d pos) {
        return getDistanceToPlane(pos) > 0;
    }
    
    /**
     * @param xInPlane
     * @param yInPlane
     * @return Convert the 2D coordinate in portal plane into the world coordinate
     */
    public Vector3d getPointInPlane(double xInPlane, double yInPlane) {
        return getOriginPos().add(getPointInPlaneLocal(xInPlane, yInPlane));
    }
    
    /**
     * @param xInPlane
     * @param yInPlane
     * @return Convert the 2D coordinate in portal plane into the portal-centered coordinate
     */
    public Vector3d getPointInPlaneLocal(double xInPlane, double yInPlane) {
        return axisW.scale(xInPlane).add(axisH.scale(yInPlane));
    }
    
    public Vector3d getPointInPlaneLocalClamped(double xInPlane, double yInPlane) {
        return getPointInPlaneLocal(
            MathHelper.clamp(xInPlane, -width / 2, width / 2),
            MathHelper.clamp(yInPlane, -height / 2, height / 2)
        );
    }
    
    //3  2
    //1  0
    public Vector3d[] getFourVerticesLocal(double shrinkFactor) {
        Vector3d[] vertices = new Vector3d[4];
        vertices[0] = getPointInPlaneLocal(
            width / 2 - shrinkFactor,
            -height / 2 + shrinkFactor
        );
        vertices[1] = getPointInPlaneLocal(
            -width / 2 + shrinkFactor,
            -height / 2 + shrinkFactor
        );
        vertices[2] = getPointInPlaneLocal(
            width / 2 - shrinkFactor,
            height / 2 - shrinkFactor
        );
        vertices[3] = getPointInPlaneLocal(
            -width / 2 + shrinkFactor,
            height / 2 - shrinkFactor
        );
        
        return vertices;
    }
    
    //3  2
    //1  0
    private Vector3d[] getFourVerticesLocalRotated(double shrinkFactor) {
        Vector3d[] fourVerticesLocal = getFourVerticesLocal(shrinkFactor);
        fourVerticesLocal[0] = transformLocalVec(fourVerticesLocal[0]);
        fourVerticesLocal[1] = transformLocalVec(fourVerticesLocal[1]);
        fourVerticesLocal[2] = transformLocalVec(fourVerticesLocal[2]);
        fourVerticesLocal[3] = transformLocalVec(fourVerticesLocal[3]);
        return fourVerticesLocal;
    }
    
    //3  2
    //1  0
    private Vector3d[] getFourVerticesLocalCullable(double shrinkFactor) {
        Vector3d[] vertices = new Vector3d[4];
        vertices[0] = getPointInPlaneLocal(
            cullableXEnd - shrinkFactor,
            cullableYStart + shrinkFactor
        );
        vertices[1] = getPointInPlaneLocal(
            cullableXStart + shrinkFactor,
            cullableYStart + shrinkFactor
        );
        vertices[2] = getPointInPlaneLocal(
            cullableXEnd - shrinkFactor,
            cullableYEnd - shrinkFactor
        );
        vertices[3] = getPointInPlaneLocal(
            cullableXStart + shrinkFactor,
            cullableYEnd - shrinkFactor
        );
        
        return vertices;
    }
    
    /**
     * Transform a point regardless of rotation transformation and scale transformation
     */
    public final Vector3d transformPointRough(Vector3d pos) {
        Vector3d offset = getDestPos().subtract(getOriginPos());
        return pos.add(offset);
    }
    
    public Vector3d transformLocalVecNonScale(Vector3d localVec) {
        if (rotation == null) {
            return localVec;
        }
        
        Vector3f temp = new Vector3f(localVec);
        temp.transform(rotation);
        
        return new Vector3d(temp);
    }
    
    public Vector3d inverseTransformLocalVecNonScale(Vector3d localVec) {
        if (rotation == null) {
            return localVec;
        }
        
        Vector3f temp = new Vector3f(localVec);
        Quaternion r = new Quaternion(rotation);//copy() is client only
        r.conjugate();
        temp.transform(r);
        return new Vector3d(temp);
    }
    
    public Vector3d inverseTransformLocalVec(Vector3d localVec) {
        return inverseTransformLocalVecNonScale(localVec).scale(1.0 / scaling);
    }
    
    public Vector3d inverseTransformPoint(Vector3d point) {
        return getOriginPos().add(inverseTransformLocalVec(point.subtract(getDestPos())));
    }
    
    public AxisAlignedBB getThinAreaBox() {
        return new AxisAlignedBB(
            getPointInPlane(width / 2, height / 2),
            getPointInPlane(-width / 2, -height / 2)
        );
    }
    
    /**
     * Project the point into the portal plane, is it in the portal area
     */
    public boolean isPointInPortalProjection(Vector3d pos) {
        Vector3d offset = pos.subtract(getOriginPos());
        
        double yInPlane = offset.dotProduct(axisH);
        double xInPlane = offset.dotProduct(axisW);
        
        boolean roughResult = Math.abs(xInPlane) < (width / 2 + 0.001) &&
            Math.abs(yInPlane) < (height / 2 + 0.001);
        
        if (roughResult && specialShape != null) {
            return specialShape.triangles.stream()
                .anyMatch(triangle ->
                    triangle.isPointInTriangle(xInPlane, yInPlane)
                );
        }
        
        return roughResult;
    }
    
    public boolean isMovedThroughPortal(
        Vector3d lastTickPos,
        Vector3d pos
    ) {
        return rayTrace(lastTickPos, pos) != null;
    }
    
    @Nullable
    public Vector3d rayTrace(
        Vector3d from,
        Vector3d to
    ) {
        double lastDistance = getDistanceToPlane(from);
        double nowDistance = getDistanceToPlane(to);
        
        if (!(lastDistance > 0 && nowDistance < 0)) {
            return null;
        }
        
        Vector3d lineOrigin = from;
        Vector3d lineDirection = to.subtract(from).normalize();
        
        double collidingT = Helper.getCollidingT(getOriginPos(), normal, lineOrigin, lineDirection);
        Vector3d collidingPoint = lineOrigin.add(lineDirection.scale(collidingT));
        
        if (isPointInPortalProjection(collidingPoint)) {
            return collidingPoint;
        }
        else {
            return null;
        }
    }
    
    @Override
    public double getDistanceToNearestPointInPortal(
        Vector3d point
    ) {
        double distanceToPlane = getDistanceToPlane(point);
        Vector3d localPos = point.subtract(getOriginPos());
        double localX = localPos.dotProduct(axisW);
        double localY = localPos.dotProduct(axisH);
        double distanceToRect = Helper.getDistanceToRectangle(
            localX, localY,
            -(width / 2), -(height / 2),
            (width / 2), (height / 2)
        );
        return Math.sqrt(distanceToPlane * distanceToPlane + distanceToRect * distanceToRect);
    }
    
    public Vector3d getPointProjectedToPlane(Vector3d pos) {
        Vector3d originPos = getOriginPos();
        Vector3d offset = pos.subtract(originPos);
        
        double yInPlane = offset.dotProduct(axisH);
        double xInPlane = offset.dotProduct(axisW);
        
        return originPos.add(
            axisW.scale(xInPlane)
        ).add(
            axisH.scale(yInPlane)
        );
    }
    
    public Vector3d getNearestPointInPortal(Vector3d pos) {
        Vector3d originPos = getOriginPos();
        Vector3d offset = pos.subtract(originPos);
        
        double yInPlane = offset.dotProduct(axisH);
        double xInPlane = offset.dotProduct(axisW);
        
        xInPlane = MathHelper.clamp(xInPlane, -width / 2, width / 2);
        yInPlane = MathHelper.clamp(yInPlane, -height / 2, height / 2);
        
        return originPos.add(
            axisW.scale(xInPlane)
        ).add(
            axisH.scale(yInPlane)
        );
    }
    
    public World getDestinationWorld() {
        return getDestinationWorld(world.isRemote());
    }
    
    private World getDestinationWorld(boolean isClient) {
        if (isClient) {
            return CHelper.getClientWorld(dimensionTo);
        }
        else {
            return McHelper.getServer().getWorld(dimensionTo);
        }
    }
    
    public static boolean isParallelPortal(Portal currPortal, Portal outerPortal) {
        return currPortal.world.func_234923_W_() == outerPortal.dimensionTo &&
            currPortal.dimensionTo == outerPortal.world.func_234923_W_() &&
            !(currPortal.getNormal().dotProduct(outerPortal.getContentDirection()) > -0.9) &&
            !outerPortal.isInside(currPortal.getOriginPos(), 0.1);
    }
    
    public static boolean isParallelOrientedPortal(Portal currPortal, Portal outerPortal) {
        return currPortal.world.func_234923_W_() == outerPortal.dimensionTo &&
            currPortal.getNormal().dotProduct(outerPortal.getContentDirection()) <= -0.9 &&
            !outerPortal.isInside(currPortal.getOriginPos(), 0.1);
    }
    
    public static boolean isReversePortal(Portal a, Portal b) {
        return a.dimensionTo == b.world.func_234923_W_() &&
            a.world.func_234923_W_() == b.dimensionTo &&
            a.getOriginPos().distanceTo(b.getDestPos()) < 1 &&
            a.getDestPos().distanceTo(b.getOriginPos()) < 1 &&
            a.getNormal().dotProduct(b.getContentDirection()) > 0.5;
    }
    
    public static boolean isFlippedPortal(Portal a, Portal b) {
        if (a == b) {
            return false;
        }
        return a.world == b.world &&
            a.dimensionTo == b.dimensionTo &&
            a.getOriginPos().distanceTo(b.getOriginPos()) < 1 &&
            a.getDestPos().distanceTo(b.getDestPos()) < 1 &&
            a.getNormal().dotProduct(b.getNormal()) < -0.5;
    }
    
    @Override
    public double getDestAreaRadiusEstimation() {
        return Math.max(this.width, this.height) * this.scaling;
    }
    
    public Matrix3f getOuterOrientationMatrix() {
        //transformation: x*axisW+y*axisH+z*normal
        final Matrix3f matrix3f = new Matrix3f();
        matrix3f.func_232605_a_(0, 0, (float) axisW.getX());
        matrix3f.func_232605_a_(0, 1, (float) axisW.getZ());
        matrix3f.func_232605_a_(0, 2, (float) axisW.getY());
        matrix3f.func_232605_a_(1, 0, (float) axisH.getX());
        matrix3f.func_232605_a_(1, 1, (float) axisH.getY());
        matrix3f.func_232605_a_(1, 2, (float) axisH.getZ());
        matrix3f.func_232605_a_(2, 0, (float) getNormal().getX());
        matrix3f.func_232605_a_(2, 1, (float) getNormal().getY());
        matrix3f.func_232605_a_(2, 2, (float) getNormal().getZ());
        return matrix3f;
    }
    
    public Matrix3f getInnerOrientationMatrix() {
        Matrix3f matrix3f;
        if (rotation != null) {
            matrix3f = new Matrix3f(rotation);
        }
        else {
            matrix3f = new Matrix3f();
            matrix3f.setIdentity();
        }
        matrix3f.mul((float) scaling);
        return matrix3f;
    }
    
    @Override
    public boolean isConventionalPortal() {
        return true;
    }
    
    @Override
    public AxisAlignedBB getExactAreaBox() {
        return getExactBoundingBox();
    }
    
    @Override
    public boolean isRoughlyVisibleTo(Vector3d cameraPos) {
        return isInFrontOfPortal(cameraPos);
    }
    
    @Nullable
    @Override
    public Plane getInnerClipping() {
        return new Plane(getDestPos(), getContentDirection());
    }
    
    //3  2
    //1  0
    @Nullable
    @Override
    public Vector3d[] getOuterFrustumCullingVertices() {
        return getFourVerticesLocalCullable(0);
    }
    
    
    // function return true for culled
    @OnlyIn(Dist.CLIENT)
    @Override
    public BoxPredicate getInnerFrustumCullingFunc(
        double cameraX, double cameraY, double cameraZ
    ) {
        
        Vector3d portalOriginInLocalCoordinate = getDestPos().add(
            -cameraX, -cameraY, -cameraZ
        );
        Vector3d[] innerFrustumCullingVertices = getFourVerticesLocalRotated(0);
        if (innerFrustumCullingVertices == null) {
            return BoxPredicate.nonePredicate;
        }
        Vector3d[] downLeftUpRightPlaneNormals = FrustumCuller.getDownLeftUpRightPlaneNormals(
            portalOriginInLocalCoordinate,
            innerFrustumCullingVertices
        );
        
        Vector3d downPlane = downLeftUpRightPlaneNormals[0];
        Vector3d leftPlane = downLeftUpRightPlaneNormals[1];
        Vector3d upPlane = downLeftUpRightPlaneNormals[2];
        Vector3d rightPlane = downLeftUpRightPlaneNormals[3];
        
        return
            (double minX, double minY, double minZ, double maxX, double maxY, double maxZ) ->
                FrustumCuller.isFullyOutsideFrustum(
                    minX, minY, minZ, maxX, maxY, maxZ,
                    leftPlane, rightPlane, upPlane, downPlane
                );
    }
    
    public static class TransformationDesc {
        public final RegistryKey<World> dimensionTo;
        @Nullable
        public final Quaternion rotation;
        public final double scaling;
        public final Vector3d offset;
        public final boolean isMirror;
        
        public TransformationDesc(
            RegistryKey<World> dimensionTo,
            @Nullable Quaternion rotation, double scaling,
            Vector3d offset, boolean isMirror
        ) {
            this.dimensionTo = dimensionTo;
            this.rotation = rotation;
            this.scaling = scaling;
            this.offset = offset;
            this.isMirror = isMirror;
        }
        
        private static boolean rotationRoughlyEquals(Quaternion a, Quaternion b) {
            if (a == null && b == null) {
                return true;
            }
            if (a == null || b == null) {
                return false;
            }
            
            return RotationHelper.isClose(a, b, 0.01f);
        }
        
        //roughly equals
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TransformationDesc that = (TransformationDesc) o;
            
            if (isMirror || that.isMirror) {
                return false;
            }
            
            return Double.compare(that.scaling, scaling) == 0 &&
                dimensionTo == that.dimensionTo &&
                rotationRoughlyEquals(rotation, that.rotation) &&//approximately
                offset.squareDistanceTo(that.offset) < 0.01;//approximately
        }
        
        @Override
        public int hashCode() {
            throw new RuntimeException("This cannot be put into a container");
        }
    }
    
    public TransformationDesc getTransformationDesc() {
        return new TransformationDesc(
            getDestDim(),
            getRotation(),
            getScale(),
            getDestPos().scale(1.0 / getScale()).subtract(getOriginPos()),
            this instanceof Mirror
        );
    }
    
    @Override
    public boolean cannotRenderInMe(Portal portal) {
        return isParallelOrientedPortal(portal, this);
    }
    
    @Override
    public void remove() {
        super.remove();
        portalDisposeSignal.emit(this);
    }
    
    @OnlyIn(Dist.CLIENT)
    public PortalLike getRenderingDelegate() {
        if (Global.enablePortalRenderingMerge) {
            PortalRenderingGroup group = PortalRenderInfo.getGroupOf(this);
            if (group != null) {
                return group;
            }
            else {
                return this;
            }
        }
        else {
            return this;
        }
    }
    
    
    @Override
    public void recalculateSize() {
        boundingBoxCache = null;
    }
    
    @Override
    protected float getEyeHeight(Pose pose, EntitySize dimensions) {
        return 0;
    }
    
    
    // Scaling does not interfere camera transformation
    @Override
    @Nullable
    public Matrix4f getAdditionalCameraTransformation() {
        
        return PortalRenderer.getPortalTransformation(this);
    }
    
    
    @Override
    protected void registerData() {
        //do nothing
    }
    
    public boolean canDoOuterFrustumCulling() {
        if (isFuseView()) {
            return false;
        }
        if (specialShape == null) {
            initDefaultCullableRange();
        }
        return cullableXStart != cullableXEnd;
    }
    
    // use canTeleportEntity
    // TODO remove
    @Deprecated
    public boolean isTeleportable() {
        return teleportable;
    }
    
    public static boolean doesPortalBlockEntityView(
        LivingEntity observer, Entity target
    ) {
        observer.world.getProfiler().startSection("portal_block_view");
        
        List<Portal> viewBlockingPortals = McHelper.findEntitiesByBox(
            Portal.class,
            observer.world,
            observer.getBoundingBox().union(target.getBoundingBox()),
            8,
            p -> p.rayTrace(observer.getEyePosition(1), target.getEyePosition(1)) != null
        );
        
        observer.world.getProfiler().endSection();
        
        return !viewBlockingPortals.isEmpty();
    }
}
