package com.ldtteam.vault.api.permission;

import com.ldtteam.vault.api.grouping.IGroup;
import com.ldtteam.vault.api.grouping.VaultGroup;
import com.ldtteam.vault.api.location.Location;
import com.ldtteam.vault.api.region.IRegion;
import com.ldtteam.vault.api.region.VaultServerRegion;
import com.ldtteam.vault.api.region.VaultWorldRegion;
import com.ldtteam.vault.api.utils.LogUtils;
import com.ldtteam.vault.api.utils.NBTUtils;
import com.mojang.authlib.GameProfile;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.server.permission.DefaultPermissionLevel;
import net.minecraftforge.server.permission.context.ContextKeys;
import net.minecraftforge.server.permission.context.IContext;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ------------ Class not Documented ------------
 */
public class VaultPermissionHandler implements IVaultPermissionHandler
{
    //NBTConst
    private static final String CONST_NBT_DIMID = "dim";
    private static final String CONST_NBT_DIMDATA = "data";

    private static final Logger logger = LogUtils.constructLoggerForClass(VaultPermissionHandler.class);

    private final VaultServerRegion defaults = new VaultServerRegion();

    private final Map<Integer, VaultWorldRegion> worldRegionMap = new HashMap<>();

    public VaultPermissionHandler()
    {
        setupDefaultRootRegion();
    }

    private void setupDefaultRootRegion() {
        final VaultGroup rootGroup = new VaultGroup();
        final VaultPermissionNode allPermissionNode = new VaultPermissionNode();
        rootGroup.setData(allPermissionNode);

        final VaultGroup opGroup = new VaultGroup("op", "Vanilla minecraft default group that holds the operators", "OP", DefaultPermissionLevel.OP);
        final VaultPermissionNode opPermissionNode = new VaultPermissionNode();
        opGroup.setData(opPermissionNode);
        opGroup.setParent(rootGroup);

        final VaultGroup allGroup = new VaultGroup("all", "Vanilla minecraft default group that holds the administrators", "", DefaultPermissionLevel.ALL);
        final VaultPermissionNode allPersmissionNode = new VaultPermissionNode();
        allGroup.setData(allPermissionNode);
        allGroup.setParent(opGroup);

        defaults.setData(rootGroup);
    }

    @Override
    public void registerNode(final String node, final DefaultPermissionLevel level, final String desc)
    {
        logger.info("Registering new permission node: " + node + " with default level: " + level + " and description: " + desc);
        final String[] nodeElements = node.split("\\.");

        registerNodeToDefaults(nodeElements, level, desc);
    }

    private void registerNodeToDefaults(@NotNull final String[] nodeElements, final DefaultPermissionLevel level, final String desc)
    {
        VaultGroup defaultRootGroup = defaults.getData();
        VaultGroup matchingDefaultLevelGroup = defaultRootGroup.getDeepestChild(c -> c.getVanillaPermissionLevel() != null && c.getVanillaPermissionLevel().equals(level));

        VaultPermissionNode currentWorkingNode = matchingDefaultLevelGroup.getData();

        //Find the deepest node along the elements tree.
        int index = 0;
        for (int i = 0;index < nodeElements.length; index++)
        {
            String key = nodeElements[index];
            if (currentWorkingNode == null)
                break;

            currentWorkingNode = currentWorkingNode.getChildren().stream().filter(c -> c.getKey().equalsIgnoreCase(key)).findFirst().orElse(null);
        }

        if (currentWorkingNode != null)
        {
            if (currentWorkingNode.getDesc().isEmpty() && currentWorkingNode.getType().equals(PermissionType.DONOTCARE))
            {
                //We encountered a previously added autoconstructed node.
                //Lets update its state.
                logger.info("Updating state of previously auto created node: " + currentWorkingNode.getName());
                currentWorkingNode.setType(PermissionType.ACCEPTING);
            }

            logger.warn("Can not override already existing permission node on this default level!");
            return;
        }

        for (int i = 0; index < nodeElements.length; index++)
        {
            String key = nodeElements[index];

            final VaultPermissionNode newNode = new VaultPermissionNode(key, desc, (index == nodeElements.length - 1) ? PermissionType.ACCEPTING : PermissionType.DONOTCARE);
            newNode.setParent(currentWorkingNode);

            currentWorkingNode = newNode;
        }
    }

    @Override
    public Collection<String> getRegisteredNodes()
    {
        return defaults.getData().getData().stream().map(t -> t.getName()).collect(Collectors.toSet());
    }

    @Override
    public boolean hasPermission(@Nullable final GameProfile profile, final String node, @Nullable final IContext context)
    {
        if (context == null)
            return checkForPermissionInSpecificRegionAnonymously(defaults, node) != PermissionType.BLOCKING;

        IRegion<?, VaultGroup, VaultPermissionNode> region = context.getWorld() != null && worldRegionMap.containsKey(context.getWorld().provider.getDimension()) ? worldRegionMap.get(context.getWorld().provider.getDimension()) : defaults;

        if (context.getPlayer() != null || context.has(ContextKeys.POS))
        {
            BlockPos target = context.has(ContextKeys.POS) ? context.get(ContextKeys.POS) : context.getPlayer().getPosition();
            region = region.getSmallestChild(new Location(context.getWorld() != null ? context.getWorld().provider.getDimension() : -1, target));
        }

        if (context.getPlayer() != null)
        {
            return checkForPermissionInSpecificRegionWithPlayer(region, node, context.getPlayer().getUniqueID()) != PermissionType.BLOCKING;
        }
        else if (profile != null)
        {
            return checkForPermissionInSpecificRegionWithPlayer(region, node, profile.getId()) != PermissionType.BLOCKING;
        }
        else
        {
            return checkForPermissionInSpecificRegionAnonymously(region, node) != PermissionType.BLOCKING;
        }
    }

    public PermissionType checkForPermissionInSpecificRegionWithPlayer(final IRegion<?, VaultGroup, VaultPermissionNode> region, final String name, final UUID player)
    {
        VaultGroup group = region.getData().getDeepestChild(g -> g.getPlayers().contains(player) && checkForPermissionInGroup(g, name) != PermissionType.DONOTCARE);
        if (group == null && !region.isRoot())
            return checkForPermissionInSpecificRegionWithPlayer(region.getParent(), name, player);

        if (group == null)
        {
            return checkForPermissionInSpecificRegionAnonymously(region, name);
        }

        return checkForPermissionInGroup(group, name);
    }

    public PermissionType checkForPermissionInSpecificRegionAnonymously(final IRegion<?, VaultGroup, VaultPermissionNode> region, final String name)
    {
        VaultGroup group = region.getData().getDeepestChild(g -> checkForPermissionInGroup(g, name) != PermissionType.DONOTCARE);
        if (group == null && !region.isRoot())
        {
            return checkForPermissionInSpecificRegionAnonymously(region.getParent(), name);
        }

        if (group == null)
        {
            return PermissionType.DONOTCARE;
        }

        return checkForPermissionInGroup(group, name);
    }

    public PermissionType checkForPermissionInGroup(final VaultGroup group, final String name)
    {
        VaultPermissionNode node = group.getData().getDeepestChild(n -> n.getName().equalsIgnoreCase("*." + name));
        if (node == null && !group.isRoot())
        {
            return checkForPermissionInGroup(group.getParent(), name);
        }

        if (node == null)
        {
            return PermissionType.DONOTCARE;
        }

        return node.getType();
    }

    @Override
    public String getNodeDescription(final String node)
    {
        VaultPermissionNode rootNode = defaults.getData().getData();

        final VaultPermissionNode target = rootNode.getDeepestChild(r -> r.getName().equals(node));
        if (target == null)
        {
            return "UNKNOWN";
        }

        return target.getDesc();
    }

    @Override
    public NBTTagList serializeNBT()
    {
        return worldRegionMap.keySet().stream().map(dimId -> {
            NBTTagCompound compound = new NBTTagCompound();
            compound.setInteger(CONST_NBT_DIMID, dimId);
            compound.setTag(CONST_NBT_DIMDATA, worldRegionMap.get(dimId).serializeNBT());
            return compound;
        }).collect(NBTUtils.toNBTTagList());
    }

    @Override
    public void deserializeNBT(final NBTTagList nbt)
    {
        worldRegionMap.clear();

        NBTUtils.streamCompound(nbt).forEach(tag -> {
            Integer dimId = tag.getInteger(CONST_NBT_DIMID);

            VaultWorldRegion worldData = new VaultWorldRegion(dimId);
            worldData.deserializeNBT(tag.getCompoundTag(CONST_NBT_DIMDATA));

            worldRegionMap.put(dimId, worldData);
        });
    }
}