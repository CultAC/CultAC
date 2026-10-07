package ac.cult.blocksim.generator;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Audits source declarations, including overloads, inherited defaults and helper surfaces. */
public final class SourceScanner {
    private static final Set<String> METHODS = Set.of(
        "getStateForPlacement", "canSurvive", "updateShape", "updateIndirectNeighbourShapes", "canBeReplaced",
        "clip", "clipWithInteractionOverride", "traverseBlocks", "getDirection", "clipPoint", "getApproximateNearest",
        "clipIncludingBorder", "getHitResultOnViewVector", "getHitResult", "calculateHitResult",
        "calculateOutputSignal", "refreshOutputState", "shouldTurnOn", "getInputSignal", "getAnalogOutputSignal", "hasAnalogOutputSignal",
        "getRedstoneSignal", "getRedstoneSignalFromContainer", "getRedstoneSignalFromBlockEntity", "getComparatorOutput",
        "getProjectile", "getSupportedHeldProjectiles", "getAllSupportedProjectiles", "getHeldProjectile",
        "isValidBonemealTarget", "growCrop", "growWaterPlant", "nextDamageWillBreak",
        "getPlayerPOVHitResult", "calculateViewVector", "getHeight", "getOwnHeight", "getFlow", "affectsFlow", "isSolidFace", "isSame", "getFluidContext", "mayUseItemAt",
        "useItemOn", "useWithoutItem", "setPlacedBy", "playerWillDestroy", "destroy", "neighborChanged",
        "getSignal", "getDirectSignal", "isSignalSource", "getCollisionShape", "getShape", "getBlockSupportShape",
        "getInteractionShape", "getMaxHorizontalOffset", "getMaxVerticalOffset", "getOffset",
        "useOn", "use", "place", "placeBlock", "getPlacementState", "updatePlacementContext", "updateBlockStateFromTag",
        "canPlace", "mustSurvive", "canDestroyBlock", "getDestroyProgress", "getDestroySpeed", "getMiningSpeed", "isCorrectForDrops",
        "hasCorrectToolForDrops", "isCorrectToolForDrops", "hasDigSpeed", "getDigSpeedAmplification",
        "setBlock", "setBlockState", "retainKnownServerState", "updateKnownServerState", "endPredictionsUpTo",
        "startPredicting", "updateOrDestroy", "updateFromNeighbourShapes", "neighborShapeChanged", "updateNeighbourShapes",
        "shapeUpdate", "addAndRun", "runUpdates", "runNext", "executeShapeUpdate", "updateNeighborsAt",
        "updateNeighborsAtExceptFromFacing", "performUseItemOn", "useItem", "startDestroyBlock", "continueDestroyBlock",
        "destroyBlock", "stopDestroyBlock", "sameDestroyTarget", "getDestroyStage", "attack", "interact", "transformBlock", "playerHasBlockingItemUseIntent", "getOptionalState", "blockActionRestricted", "getNearestLookingDirections",
        "getClickedPos", "getClickedFace", "getClickLocation", "isInside", "canReplace", "isReplacingClickedOnBlock",
        "placeLiquid", "canPlaceLiquid", "pickupBlock", "emptyContents", "createLegacyBlock", "isFaceSturdy",
        "getFluidState", "hasNeighborSignal", "getBestNeighborSignal", "getDirectSignalTo", "ownSignal",
        "offsetType", "getSeed", "create", "findBits", "joinIsNotEmpty", "move", "getFaceShape", "calculateFace", "isSupporting",
        "join", "joinUnoptimized", "createIndexMerger", "optimize", "isCubeLikeAlong", "findIndex", "forAllBoxes",
        "isFaceFull", "isShapeFullBlock", "getSignalForState", "getOutputSignal", "getConnectedDirection",
        "getConnectionState", "getMissingConnections", "getConnectingSide", "canSurviveOn", "isCross", "isDot",
        "shouldConnectTo", "shouldRedstoneWireConnectTo", "isInValidBounds", "isValid",
        "mayPlaceOn", "isHanging", "canAttachTo", "hasFace", "isFaceSupported", "hasAnyFace",
        "orderedByNearest", "makeDirectionArray", "fromYRot", "getHorizontalDirection", "isSecondaryUseActive",
        "getRotation", "getNearestLookingDirection", "getNearestLookingVerticalDirection", "replacingClickedOnBlock", "at",
        "sin", "cos", "isEmpty", "getCount", "setCount", "shrink", "consume", "isSourceOfType", "getComponents",
        "getHinge", "getClockWise", "getCounterClockWise", "removeFace",
        "getState", "canSurviveOnBlock", "canBurn", "getIgniteOdds", "isValidFireLocation", "getStateWithAge", "isSnowySetting",
        "isExceptionForConnection", "isWall", "connectsToDirection", "attachsTo", "connectsTo", "isSameFence",
        "isConnected", "isCovered", "topUpdate", "sideUpdate", "shouldRaisePost", "updateSides", "makeWallState",
        "getStairsShape", "canTakeShape", "isStairs", "chestCanConnectTo", "candidatePartnerFacing", "getChestType",
        "withPropertiesOf", "getLeastOxidizedChestOfConnectedBlocks", "unwaxBlock", "isWaxed", "setInstrument",
        "getStateWithConnections", "canSupportAtFace", "getUpdatedState", "hasFaces", "countFaces", "isAcceptableNeighbour",
        "shouldSolidify", "touchesLiquid", "canSolidify", "getNeighbourDirection", "canStayAtPosition", "validBlockState", "isSourceIfFluid", "isSource", "isSmokeSource",
        "canSupportCenter", "canAttach", "isProperHit", "getHeadBlock", "getBodyBlock",
        "updateHeadAfterConvertedFromBody", "updateBodyAfterConvertedFromHead", "getMaxAgeState", "isMaxAge",
        "isSpeleothemWithDirection", "isValidSpeleothemPlacement", "calculateSpeleothemThickness", "calculateTipDirection",
        "getOptionalDistanceAt", "getDistanceAt", "updateDistance", "copyWaterloggedFrom", "canSupportRigidBlock", "toggle", "press",
        "hasAnyVacantFace", "isValidStateForPlacement", "isBottom", "getDistance", "hasRequiredLogs", "updateState", "shouldMaintainFarmland",
        "fromDegreesWithTurns", "fromDegrees", "fromDirection", "convertToDirection", "newBlockEntity", "getControlInputSignal", "getAlternateSignal", "isLocked",
        "getProgressAabb", "getProgressDeltaAabb", "calculateState", "onRemoved", "canEat", "eat", "needsFood", "saturationByModifier", "shouldChangedStateKeepBlockEntity",
        "getCooldownGroup", "getCooldownPercent", "isOnCooldown", "getUseDuration", "applyAfterUseComponentSideEffects",
        "convertIntoRemainder", "canPlaceOnBlockInAdventureMode", "canBreakBlockInAdventureMode", "handleExtraItemsCreatedOnUse", "compareNbt", "getEmptySuccessItem",
        "startConsuming", "canConsume", "consumeTicks", "onConsume", "startUsingItem", "getPrototype", "getComponentsPatch",
        "swapWithEquipmentSlot", "canBeEquippedBy", "tryInsertIntoJukebox",
        "applyComponentsFromItemStack", "applyComponents", "applyImplicitComponents", "saveAdditional", "saveWithoutMetadata", "copyInto", "copyOne", "addToTag", "updateBlockEntityComponents",
        "getMaxStackSize", "isDamageableItem", "getDamageValue", "isDamaged", "isStackable", "isSameItemSameComponents", "grow", "copyWithCount", "copyAndClear", "createFilledResult", "drop");
    private static final Pattern TAG = Pattern.compile("(?:BlockTags|ItemTags|FluidTags|EnchantmentTags)\\.[A-Z][A-Z0-9_]*");

    public static void main(String[] args) throws Exception {
        if ((args.length != 2 && args.length != 3) || args[0].isBlank()) throw new IllegalArgumentException("Source root and manifest output required");
        Path root = Path.of(args[0]), output = Path.of(args[1]);
        List<Entry> entries = scan(root);
        Files.createDirectories(output.toAbsolutePath().getParent());
        List<String> rows = new ArrayList<>();
        rows.add("class\tmethod\tsignature\tbodyHash\tdisposition\treference\ttags");
        for (Entry entry : entries) rows.add(entry.tsv());
        if (args.length == 3 && args[2].equals("--verify")) {
            if (!Files.readAllLines(output, StandardCharsets.UTF_8).equals(rows)) throw new IOException("Stale source manifest: " + output);
            System.out.println("Verified " + entries.size() + " current source hashes");
            return;
        }
        Files.write(output, rows, StandardCharsets.UTF_8);
        System.out.println("Audited " + entries.size() + " method declarations (unmapped until reviewed)");
    }

    public static List<Entry> scan(Path root) throws IOException {
        JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE));
        List<Entry> entries = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (!inScope(relative)) continue;
                var result = parser.parse(file);
                if (!result.isSuccessful()) throw new IOException("Source parse failed for " + file + ": " + result.getProblems());
                var unit = result.getResult().orElseThrow();
                for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
                    if (relative.equals("world/entity/Entity.java") && !Set.of("calculateViewVector", "applyComponentsFromItemStack", "applyImplicitComponents", "applyImplicitComponent", "applyImplicitComponentIfPresent").contains(method.getNameAsString())) continue;
                    if (relative.equals("world/entity/EntityType.java") && !Set.of("getSpawnAABB", "canSpawn", "getDimensions", "updateInterval").contains(method.getNameAsString())) continue;
                    if (relative.equals("world/entity/decoration/Cushion.java") && !Set.of("canBePlacedAt", "wouldSurviveAt", "hasAnchorBelow", "isAnchorBuried", "isCoveredBySuffocatingBlocks").contains(method.getNameAsString())) continue;
                    boolean hangingSource = Set.of("world/entity/decoration/HangingEntity.java", "world/entity/decoration/ItemFrame.java", "world/entity/decoration/painting/Painting.java").contains(relative);
                    if (hangingSource && !Set.of("calculateBoundingBox", "createBoundingBox", "recalculateBoundingBox", "survives", "isSupportingBlock", "calculateSupportBox", "canCoexist", "hasLevelCollision", "getPopBox", "create", "offsetForPaintingSize", "applyImplicitComponents", "applyImplicitComponent").contains(method.getNameAsString())) continue;
                    boolean entityPlacementHelper = relative.equals("world/entity/decoration/Cushion.java")
                        || hangingSource
                        || relative.equals("world/entity/Entity.java") && method.getNameAsString().startsWith("apply")
                        || relative.equals("world/entity/EntityType.java")
                        || relative.equals("world/item/BoatItem.java") && method.getNameAsString().equals("getBoat")
                        || Set.of("world/item/HangingEntityItem.java", "world/item/ItemFrameItem.java").contains(relative) && method.getNameAsString().equals("mayPlace")
                        || relative.equals("world/item/CushionItem.java") && method.getNameAsString().equals("recalculateContextForSpecialCollisionShapes")
                        || relative.equals("world/level/BlockCollisions.java") && Set.of("computeNext", "getChunk").contains(method.getNameAsString());
                    boolean cooldownHelper = relative.equals("world/item/ItemCooldowns.java")
                        && Set.of("tick", "addCooldown", "removeCooldown").contains(method.getNameAsString());
                    boolean clockHelper = relative.equals("client/ClientClockManager.java")
                        && Set.of("tick", "handleUpdates", "getInstance", "totalTicks", "partialTick", "rate", "isPaused").contains(method.getNameAsString());
                    boolean shulkerAnimation = relative.equals("world/level/block/entity/ShulkerBoxBlockEntity.java")
                        && Set.of("tick", "updateAnimation", "triggerEvent").contains(method.getNameAsString());
                    boolean jukeboxPlayback = relative.equals("world/item/JukeboxSongPlayer.java")
                        && Set.of("tick", "stop", "setSongWithoutPlaying").contains(method.getNameAsString())
                        || relative.equals("world/item/JukeboxSong.java") && Set.of("lengthInTicks", "hasFinished").contains(method.getNameAsString())
                        || relative.equals("world/level/block/entity/JukeboxBlockEntity.java") && method.getNameAsString().equals("loadAdditional");
                    boolean ignitionHelper = relative.equals("world/level/block/BaseFireBlock.java")
                        && Set.of("getState", "canBePlacedAt", "isPortal", "inPortalDimension").contains(method.getNameAsString())
                        || relative.equals("world/level/portal/PortalShape.java") && Set.of("findEmptyPortalShape", "findPortalShape", "findAnyShape",
                            "calculateBottomLeft", "calculateWidth", "getDistanceUntilEdgeAboveFrame", "calculateHeight", "hasTopFrame", "getDistanceUntilTop", "isEmpty", "isValid").contains(method.getNameAsString())
                        || relative.equals("world/item/HoneycombItem.java") && method.getNameAsString().equals("getWaxed")
                        || Set.of("world/level/block/CandleBlock.java", "world/level/block/CandleCakeBlock.java", "world/level/block/CampfireBlock.java").contains(relative)
                            && method.getNameAsString().equals("canLight");
                    boolean cauldronHelper = relative.startsWith("core/cauldron/")
                        && Set.of("bootStrap", "get", "fillBucket", "emptyBucket", "fillWaterInteraction", "fillLavaInteraction", "fillPowderSnowInteraction",
                            "shulkerBoxInteraction", "bannerInteraction", "dyedItemIteration", "isUnderWater").contains(method.getNameAsString())
                        || relative.equals("world/item/alchemy/PotionContents.java") && Set.of("is", "isPotionWithoutCustomEffects").contains(method.getNameAsString());
                    boolean recipeHelper = relative.equals("client/multiplayer/ClientRecipeContainer.java") && method.getNameAsString().equals("propertySet")
                        || relative.equals("client/multiplayer/ClientPacketListener.java") && method.getNameAsString().equals("handleUpdateRecipes")
                        || relative.equals("world/item/crafting/RecipePropertySet.java") && method.getNameAsString().equals("test");
                    boolean slotHelper = relative.equals("world/level/block/SelectableSlotContainer.java")
                        && Set.of("getHitSlot", "getRelativeHitCoordinatesForBlockFace", "getSection").contains(method.getNameAsString());
                    boolean signHelper = relative.equals("world/level/block/entity/SignBlockEntity.java")
                        && Set.of("getSlotPlayerIsFacing", "canExecuteClickCommands").contains(method.getNameAsString())
                        || relative.equals("world/level/block/entity/SignText.java") && method.getNameAsString().equals("hasAnyClickCommands")
                        || Set.of("world/level/block/CeilingHangingSignBlock.java", "world/level/block/WallHangingSignBlock.java").contains(relative)
                            && Set.of("shouldTryToChainAnotherHangingSign", "isHittingEditableSide", "getYRotationDegrees").contains(method.getNameAsString())
                        || relative.equals("world/level/block/SignBlock.java") && method.getNameAsString().equals("getSignHitboxCenterPosition")
                        || relative.equals("util/Mth.java") && Set.of("atan2", "fastInvSqrt", "degreesDifference", "degreesDifferenceAbs", "wrapDegrees").contains(method.getNameAsString());
                    boolean stackHelper = relative.equals("world/item/ItemStack.java")
                        && Set.of("validateStrict", "validateComponents", "validateContainedItemSizes", "getItem", "typeHolder").contains(method.getNameAsString());
                    boolean bundleHelper = relative.equals("world/item/component/BundleContents.java")
                        && Set.of("computeContentWeight", "getWeight").contains(method.getNameAsString());
                    boolean predicateHelper = (relative.equals("world/item/AdventureModePredicate.java") && Set.of("test", "areSameBlocks").contains(method.getNameAsString()))
                        || (relative.startsWith("advancements/predicates/") && Set.of("matches", "matchesState", "matchesBlockEntityData", "match").contains(method.getNameAsString()))
                        || (relative.equals("world/level/block/state/properties/IntegerProperty.java") && method.getNameAsString().equals("getValue"));
                    boolean foodHelper = relative.equals("world/food/FoodData.java") && method.getNameAsString().equals("add");
                    boolean enchantmentPresence = relative.equals("world/item/enchantment/EnchantmentHelper.java")
                        && (method.getNameAsString().equals("has") || method.getNameAsString().equals("runIterationOnItem") && method.getParameters().size() == 2);
                    boolean componentHelper = relative.equals("core/component/PatchedDataComponentMap.java")
                        && Set.of("set", "remove", "fromPatch", "isPatchSanitized", "applyPatch", "asPatch", "copy").contains(method.getNameAsString());
                    boolean transformHelper = relative.startsWith("world/level/levelgen/blockpredicates/") && method.getNameAsString().equals("test");
                    boolean predicateWorldHelper = relative.equals("world/level/biome/BiomeManager.java")
                            && Set.of("getBiome", "getFiddledDistance", "getFiddle").contains(method.getNameAsString())
                        || relative.equals("util/LinearCongruentialGenerator.java") && method.getNameAsString().equals("next")
                        || relative.equals("world/level/chunk/ChunkAccess.java") && method.getNameAsString().equals("getNoiseBiome")
                        || relative.equals("world/level/LevelReader.java") && Set.of("getBiome", "getNoiseBiome").contains(method.getNameAsString())
                        || relative.equals("client/multiplayer/ClientLevel.java") && method.getNameAsString().equals("getUncachedNoiseBiome")
                        || relative.equals("world/level/levelgen/VerticalAnchor.java") && method.getNameAsString().equals("resolveY")
                        || relative.equals("world/level/levelgen/WorldGenerationContext.java")
                            && Set.of("of", "getMinGenY", "getGenDepth", "seaLevel").contains(method.getNameAsString());
                    boolean inventoryHelper = relative.equals("world/entity/player/Inventory.java")
                        && Set.of("getItem", "setItem", "hasRemainingSpaceForItem", "getFreeSlot", "getSlotWithRemainingSpace", "addResource", "add", "contains").contains(method.getNameAsString());
                    if ((!METHODS.contains(method.getNameAsString()) && !entityPlacementHelper && !clockHelper && !cooldownHelper && !stackHelper && !bundleHelper && !predicateHelper && !foodHelper && !enchantmentPresence && !componentHelper && !inventoryHelper && !transformHelper && !predicateWorldHelper && !shulkerAnimation && !jukeboxPlayback && !ignitionHelper && !cauldronHelper && !slotHelper && !signHelper && !recipeHelper) || method.getBody().isEmpty()) continue;
                    List<String> owners = new ArrayList<>();
                    var node = method.getParentNode();
                    while (node.isPresent()) {
                        if (node.get() instanceof TypeDeclaration<?> type) owners.add(0, type.getNameAsString());
                        node = node.get().getParentNode();
                    }
                    String owner = unit.getPackageDeclaration().orElseThrow().getNameAsString() + "." + String.join("$", owners);
                    String signature = String.join(",", method.getParameters().stream().map(p -> p.getType().toString() + (p.isVarArgs() ? "..." : "")).toList());
                    String body = canonicalBody(method);
                    var matcher = TAG.matcher(body);
                    Set<String> tags = new TreeSet<>();
                    while (matcher.find()) tags.add(matcher.group());
                    entries.add(new Entry(owner, method.getNameAsString(), signature, hash(body),
                        relative + ":" + method.getBegin().orElseThrow().line, String.join(",", tags)));
                }
            }
        }
        return List.copyOf(entries);
    }

    private static boolean inScope(String path) {
        return path.startsWith("world/level/block/") || path.startsWith("world/item/")
            || path.startsWith("world/level/redstone/") || path.startsWith("world/level/material/")
            || (path.startsWith("world/level/") && path.substring("world/level/".length()).indexOf('/') < 0)
            || path.equals("world/level/chunk/LevelChunk.java")
            || path.equals("world/level/chunk/ChunkAccess.java") || path.equals("world/level/biome/BiomeManager.java")
            || path.equals("world/level/levelgen/VerticalAnchor.java") || path.equals("world/level/levelgen/WorldGenerationContext.java")
            || path.equals("world/level/portal/PortalShape.java")
            || path.equals("world/entity/projectile/ProjectileUtil.java")
            || path.equals("world/entity/EntityType.java") || path.equals("world/entity/decoration/Cushion.java")
            || path.equals("world/entity/decoration/HangingEntity.java") || path.equals("world/entity/decoration/ItemFrame.java") || path.equals("world/entity/decoration/painting/Painting.java")
            || path.startsWith("core/cauldron/")
            || path.startsWith("world/phys/shapes/") || path.equals("world/phys/AABB.java") || path.equals("world/entity/Entity.java")
            || path.equals("util/Mth.java") || path.equals("util/SegmentedAnglePrecision.java")
            || path.equals("util/LinearCongruentialGenerator.java")
            || path.startsWith("client/multiplayer/") || path.equals("client/ClientClockManager.java") || path.equals("core/Direction.java") || path.equals("world/entity/monster/Shulker.java")
            || path.equals("world/entity/player/Player.java") || path.equals("world/entity/player/Inventory.java") || path.equals("world/Container.java") || path.equals("world/entity/LivingEntity.java") || path.startsWith("world/food/")
            || path.equals("nbt/NbtUtils.java") || path.equals("core/component/PatchedDataComponentMap.java") || path.equals("world/LockCode.java") || path.equals("advancements/predicates/BlockPredicate.java")
            || path.equals("advancements/predicates/StatePropertiesPredicate.java") || path.equals("client/player/LocalPlayer.java")
            || path.equals("world/effect/MobEffectUtil.java") || path.equals("core/component/BlockTransformer.java")
            || path.startsWith("world/level/levelgen/blockpredicates/") || path.startsWith("world/level/levelgen/feature/stateproviders/");
    }
    static String canonicalBody(MethodDeclaration method) {
        var body = method.getBody().orElseThrow().clone();
        body.getAllContainedComments().forEach(c -> c.remove());
        body.removeComment();
        return body.toString();
    }
    static String hash(String body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    public record Entry(String owner, String method, String signature, String bodyHash, String reference, String tags) {
        public String tsv() { return String.join("\t", owner, method, signature, bodyHash, "unmapped", reference, tags); }
    }
}
