/*
 * Copyright (c) 2018 CA. All rights reserved.
 * This software may be modified and distributed under the terms
 * of the MIT license.  See the LICENSE file for details.
 */

package com.ca.apim.gateway.cagatewayconfig.bundle.builder;

import com.ca.apim.gateway.cagatewayconfig.ProjectInfo;
import com.ca.apim.gateway.cagatewayconfig.beans.*;
import com.ca.apim.gateway.cagatewayconfig.bundle.builder.EntityBuilder.BundleType;
import com.ca.apim.gateway.cagatewayconfig.util.entity.EntityTypes;
import com.ca.apim.gateway.cagatewayconfig.util.gateway.MappingActions;
import com.ca.apim.gateway.cagatewayconfig.util.paths.PathUtils;
import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.ca.apim.gateway.cagatewayconfig.bundle.builder.BuilderConstants.*;
import static com.ca.apim.gateway.cagatewayconfig.bundle.builder.EntityBuilder.BundleType.*;
import static com.ca.apim.gateway.cagatewayconfig.util.entity.EntityTypes.FOLDER_TYPE;
import static com.ca.apim.gateway.cagatewayconfig.util.file.DocumentFileUtils.DELETE_BUNDLE_EXTENSION;
import static com.ca.apim.gateway.cagatewayconfig.util.file.DocumentFileUtils.INSTALL_BUNDLE_EXTENSION;
import static java.util.Collections.unmodifiableSet;

@Singleton
public class BundleEntityBuilder {
    private static final Logger LOGGER = Logger.getLogger(BundleEntityBuilder.class.getName());
    private final Set<EntityBuilder> entityBuilders;
    private final BundleDocumentBuilder bundleDocumentBuilder;
    private final BundleMetadataBuilder bundleMetadataBuilder;
    private final PrivateKeyImportContextBuilder privateKeyImportContextBuilder;
    private final EntityTypeRegistry entityTypeRegistry;

    @Inject
    BundleEntityBuilder(final Set<EntityBuilder> entityBuilders, final BundleDocumentBuilder bundleDocumentBuilder,
                        final BundleMetadataBuilder bundleMetadataBuilder,
                        final EntityTypeRegistry entityTypeRegistry, PrivateKeyImportContextBuilder privateKeyImportContextBuilder) {
        // treeset is needed here to sort the builders in the proper order to get a correct bundle build
        // Ordering is necessary for the bundle, for the gateway to load it properly.
        this.entityBuilders = unmodifiableSet(new TreeSet<>(entityBuilders));
        this.bundleDocumentBuilder = bundleDocumentBuilder;
        this.bundleMetadataBuilder = bundleMetadataBuilder;
        this.entityTypeRegistry = entityTypeRegistry;
        this.privateKeyImportContextBuilder = privateKeyImportContextBuilder;
    }

    public Map<String, BundleArtifacts> build(Bundle bundle, BundleType bundleType,
                                              Document document, ProjectInfo projectInfo) {
        return build(bundle, bundleType, document, projectInfo, false);
    }

    public Map<String, BundleArtifacts> build(Bundle bundle, BundleType bundleType,
                                              Document document, ProjectInfo projectInfo, boolean generateMetadata) {
        if (Bundle.isEnvironmentEntityUniqueNamingDisabled()) {
            LOGGER.log(Level.WARNING, "Environment entity unique-naming is disabled");
        }

        Map<String, BundleArtifacts> artifacts = buildAnnotatedEntities(bundleType, bundle, document, projectInfo);
        if (artifacts.isEmpty()) {
            List<Entity> entities = new ArrayList<>();
            entityBuilders.forEach(builder -> entities.addAll(builder.build(bundle, bundleType, document)));
            final Element fullBundle = bundleDocumentBuilder.build(document, entities);
            BundleMetadata bundleMetadata = null;
            Element deleteBundleElement = null;

            final String bundleNamePrefix = StringUtils.isBlank(projectInfo.getVersion()) ? projectInfo.getName() :
                    projectInfo.getName() + "-" + projectInfo.getVersion();
            String bundleFileName = "";
            String deleteBundleFileName = "";
            if (bundleType == DEPLOYMENT) {
                // Create bundle metadata for un-annotated entities
                bundleMetadata = bundleMetadataBuilder.build(null, bundle, entities, projectInfo);

                // Create DELETE bundle - ALWAYS skip environment entities
                deleteBundleElement = createDeleteBundle(document, entities, bundle, null, projectInfo);

                // Generate bundle filenames

                bundleFileName = generateBundleFileName(false, bundleNamePrefix);
                deleteBundleFileName = generateBundleFileName(true, bundleNamePrefix);
            } else if (bundleType == ENVIRONMENT) {
                // Create bundle metadata for environment entities
                if (generateMetadata) {
                    bundleMetadata = bundleMetadataBuilder.buildEnvironmentMetadata(entities, projectInfo);
                }

                // Create DELETE Environment bundle
                deleteBundleElement = createDeleteEnvBundle(document, entities);
            }
            BundleArtifacts bundleArtifacts = new BundleArtifacts(fullBundle, deleteBundleElement, bundleMetadata,
                    bundleFileName, deleteBundleFileName);
            addPrivateKeyContexts(bundle, projectInfo, bundleArtifacts, document);
            artifacts.put(bundleNamePrefix, bundleArtifacts);
        }
        return artifacts;
    }

    private Map<String, BundleArtifacts> buildAnnotatedEntities(BundleType bundleType, Bundle bundle,
                                                                Document document, ProjectInfo projectInfo) {
        final Map<String, BundleArtifacts> annotatedElements = new LinkedHashMap<>();
        if (EntityBuilderHelper.ignoreAnnotations()) {
            return annotatedElements;
        }
        Map<String, EntityUtils.GatewayEntityInfo> entityTypeMap = entityTypeRegistry.getEntityTypeMap();
        // Filter the bundle to export only annotated entities
        entityTypeMap.values().stream().filter(EntityUtils.GatewayEntityInfo::isBundleGenerationSupported).forEach(entityInfo ->
                bundle.getEntities(entityInfo.getEntityClass()).values().stream()
                        .filter(entity -> entity instanceof AnnotableEntity && ((AnnotableEntity) entity).isBundle())
                        .forEach(gatewayEntity -> {
                            AnnotatedEntity<GatewayEntity> annotatedEntity;
                            if (gatewayEntity instanceof Encass) { // encass bundle - make copy and get AnnotatedEntity
                                annotatedEntity = new Encass((Encass) gatewayEntity).getAnnotatedEntity();
                            } else { // Service bundle - no need for copy
                                annotatedEntity = ((AnnotableEntity) gatewayEntity).getAnnotatedEntity();
                            }
                            List<Entity> entities = new ArrayList<>();
                            AnnotatedBundle annotatedBundle = new AnnotatedBundle(bundle, annotatedEntity, projectInfo);
                            Map bundleEntities = annotatedBundle.getEntities(annotatedEntity.getEntity().getClass());
                            bundleEntities.put(annotatedEntity.getEntityName(), annotatedEntity.getEntity());
                            loadPolicyDependenciesByPolicyName(annotatedEntity.getPolicyName(), annotatedBundle,
                                    bundle, false, false);
                            entityBuilders.forEach(builder -> entities.addAll(builder.build(annotatedBundle, bundleType, document)));

                            // Create deployment bundle
                            final Element bundleElement = bundleDocumentBuilder.build(document, entities);

                            String bundleFilename = "";
                            String deleteBundleFilename = "";

                            Element deleteBundleElement = null;
                            if (EntityBuilder.BundleType.DEPLOYMENT.equals(bundleType)) {
                                // Create DELETE bundle - ALWAYS skip environment entities for DEPLOYMENT bundle
                                deleteBundleElement = createDeleteBundle(document, entities, bundle,
                                        annotatedEntity, projectInfo);

                                // Generate bundle filenames
                                bundleFilename = generateBundleFileName(false, annotatedBundle.getBundleName());
                                deleteBundleFilename = generateBundleFileName(true, annotatedBundle.getBundleName());
                            }

                            // Create bundle metadata
                            BundleMetadata bundleMetadata = null;
                            if (bundleType == DEPLOYMENT) {
                                bundleMetadata = bundleMetadataBuilder.build(annotatedBundle, bundle, entities,
                                        projectInfo);
                            }

                            BundleArtifacts artifacts = new BundleArtifacts(bundleElement, deleteBundleElement,
                                    bundleMetadata, bundleFilename, deleteBundleFilename);
                            addPrivateKeyContexts(annotatedBundle, projectInfo, artifacts, document);
                            annotatedElements.put(annotatedBundle.getBundleName(), artifacts);

                        })
        );

        return annotatedElements;
    }

    /**
     * Creates the DELETE bundle element.
     *
     * @param document        Document
     * @param entities        Entities packaged in the deployment bundle
     * @param bundle          Bundle containing all the Gateway entities
     * @param annotatedEntity Annotated Bundle for which bundle is being created.
     * @return Delete bundle Element for the Annotated Bundle
     */
    private Element createDeleteBundle(final Document document, List<Entity> entities, final Bundle bundle,
                                       final AnnotatedEntity<GatewayEntity> annotatedEntity, ProjectInfo projectInfo) {
        List<Entity> deleteBundleEntities = copyFilteredEntitiesForDeleteBundle(entities, FILTER_NON_ENV_ENTITIES);

        // If @redeployable annotation is added, we can blindly include all the dependencies in the DELETE bundle.
        // Else, we have to include only non-shared entities
        if (annotatedEntity != null && !annotatedEntity.isRedeployable()) {
            // Include only non-shared entities
            AnnotatedBundle annotatedBundle = new AnnotatedBundle(bundle, annotatedEntity, projectInfo);
            Map bundleEntities = annotatedBundle.getEntities(annotatedEntity.getEntity().getClass());
            bundleEntities.put(annotatedEntity.getEntityName(), annotatedEntity.getEntity());
            loadPolicyDependenciesByPolicyName(annotatedEntity.getPolicyName(), annotatedBundle, bundle, true, false);

            Iterator<Entity> it = deleteBundleEntities.iterator();
            while (it.hasNext()) {
                final Entity entity = it.next();
                final Class<? extends GatewayEntity> entityClass = entityTypeRegistry.getEntityClass(entity.getType());
                Map entityMap = annotatedBundle.getEntities(entityClass);
                if (!entityMap.containsKey(entity.getOriginalName())) {
                    it.remove();
                }
            }
        }

        deleteBundleEntities.forEach(e -> e.setMappingAction(MappingActions.DELETE)); // Set Mapping Action to DELETE
        return bundleDocumentBuilder.build(document, deleteBundleEntities);
    }

    /**
     * Creates the DELETE environment bundle element.
     *
     * @param document Document
     * @param entities Entities packaged in the deployment bundle
     * @return Delete bundle Element for the Annotated Bundle
     */
    private Element createDeleteEnvBundle(final Document document, List<Entity> entities) {
        List<Entity> filteredEntities = copyFilteredEntitiesForDeleteBundle(entities,
                FILTER_ENV_ENTITIES.and(FILTER_OUT_DEFAULT_LISTEN_PORTS).and(FILTER_OUT_PRIVATE_KEYS));
        filteredEntities.forEach(e -> e.setMappingAction(MappingActions.DELETE));
        return bundleDocumentBuilder.build(document, filteredEntities);
    }

    /**
     * Copies all the filtered entities in the reverse order to a new {@link List}. The entries in the DELETE bundle
     * must be in the reverse order of deployment bundle.
     * All the Folders are skipped from the DELETE bundle list and added only matching entities.
     *
     * @param entities      Entities in the deployment bundle
     * @param entityFilter  Predicate for entity inclusion
     * @return Filtered list of entities in the reverse order
     */
    private List<Entity> copyFilteredEntitiesForDeleteBundle(List<Entity> entities, Predicate<Entity> entityFilter) {
        List<Entity> deleteBundleEntities = new ArrayList<>();
        for (int i = entities.size() - 1; i >= 0; i--) { // Copy in reverse order
            final Entity entity = entities.get(i);
            // Add matching entities and skip Folders from the DELETE bundle
            if (entityFilter.test(entity) && !FOLDER_TYPE.equals(entity.getType())) {
                deleteBundleEntities.add(entity);
            }
        }
        return deleteBundleEntities;
    }

    /**
     * Loads all the gateway entities used in the policy including the environment or global dependencies.
     *
     * @param policyNameWithPath Name of the policy for which gateway dependencies needs to be found.
     * @param annotatedBundle    Annotated Bundle for which bundle is being created.
     * @param rawBundle          Bundle containing all the entities of the gateway.
     * @param excludeShared      Exclude loading Shared entities as the dependencies of the policy
     * @param isParentShared  TRUE if any Parent (Policy or Encass) in the hierarchy was is annotated with @shared
     */
    private void loadPolicyDependenciesByPolicyName(String policyNameWithPath, AnnotatedBundle annotatedBundle,
                                                    Bundle rawBundle, boolean excludeShared, boolean isParentShared) {
        final Policy policy = findPolicyByNameOrPath(policyNameWithPath, rawBundle);
        loadPolicyDependencies(policy, annotatedBundle, rawBundle, excludeShared, isParentShared);
    }

    /**
     * Loads the Policy and its dependencies
     *
     * @param policy          Policy for which gateway dependencies needs to be loaded.
     * @param annotatedBundle Annotated Bundle for which bundle is being created.
     * @param rawBundle       Bundle containing all the entities of the gateway.
     * @param excludeShared   Exclude loading Shared entities as the dependencies of the policy
     * @param isParentShared  TRUE if any Parent (Policy or Encass) in the hierarchy was is annotated with @shared
     */
    private void loadPolicyDependencies(Policy policy, AnnotatedBundle annotatedBundle, Bundle rawBundle,
                                        boolean excludeShared, boolean isParentShared) {
        if (policy == null || excludeGatewayEntity(Policy.class, policy, annotatedBundle, excludeShared)) {
            return;
        }

        Policy policyCopy = new Policy(policy);
        loadFolderDependencies(annotatedBundle, policyCopy);

        isParentShared = isParentShared || policyCopy.isShared();
        policyCopy.setParentEntityShared(isParentShared);

        Map<String, Policy> annotatedPolicyMap = annotatedBundle.getEntities(Policy.class);
        annotatedPolicyMap.put(policyCopy.getPath(), policyCopy);

        Set<Dependency> dependencies = policyCopy.getUsedEntities();
        if (dependencies != null) {
            for (Dependency dependency : dependencies) {
                switch (dependency.getType()) {
                    case EntityTypes.POLICY_TYPE:
                        Policy dependentPolicy = findPolicyByNameOrPath(dependency.getName(), rawBundle);
                        loadPolicyDependencies(dependentPolicy, annotatedBundle, rawBundle, excludeShared, isParentShared);
                        break;
                    case EntityTypes.ENCAPSULATED_ASSERTION_TYPE:
                        Encass encass = rawBundle.getEncasses().get(dependency.getName());
                        loadEncassDependencies(encass, annotatedBundle, rawBundle, excludeShared, isParentShared);
                        break;
                    default:
                        loadGatewayEntity(dependency, annotatedBundle, rawBundle);
                }
            }
        }
    }

    /**
     * Loads the Encass and its dependencies
     *
     * @param encass          Encass policy for which gateway dependencies needs to be loaded.
     * @param annotatedBundle Annotated Bundle for which bundle is being created.
     * @param rawBundle       Bundle containing all the entities of the gateway.
     * @param excludeShared   Exclude loading Shared entities as the dependencies of the policy
     * @param isParentShared  TRUE if any Parent (Policy or Encass) in the hierarchy was is annotated with @shared
     */
    private void loadEncassDependencies(Encass encass, AnnotatedBundle annotatedBundle, Bundle rawBundle,
                                        boolean excludeShared, boolean isParentShared) {
        if (encass != null && !excludeGatewayEntity(Encass.class, encass, annotatedBundle, excludeShared)) {
            Encass encassCopy = new Encass(encass);
            isParentShared = isParentShared || encass.isShared();
            encassCopy.setParentEntityShared(isParentShared);

            annotatedBundle.getEncasses().put(encass.getName(), encassCopy);
            loadPolicyDependenciesByPolicyName(encassCopy.getPolicy(), annotatedBundle, rawBundle, excludeShared, isParentShared);
        }
    }

    /**
     * Loads the Folders.
     *
     * @param annotatedBundle Annotated Bundle for which bundle is being created.
     * @param policyEntity    Policy for which folder dependencies needs to be loaded.
     */
    private void loadFolderDependencies(AnnotatedBundle annotatedBundle, GatewayEntity policyEntity) {
        if (policyEntity instanceof Folderable) {
            Folder folder = ((Folderable) policyEntity).getParentFolder();
            Map<String, Folder> folderMap = annotatedBundle.getEntities(Folder.class);
            while (folder != null) {
                folderMap.putIfAbsent(folder.getPath(), folder);
                folder = folder.getParentFolder();
            }
        }
    }

    /**
     * Loads the Gateway entities other than Policy and Encass.
     *
     * @param dependency      Dependency to be loaded
     * @param annotatedBundle Annotated Bundle for which bundle is being created.
     * @param rawBundle       Bundle containing all the entities of the gateway.
     */
    private void loadGatewayEntity(Dependency dependency, AnnotatedBundle annotatedBundle, Bundle rawBundle) {
        Class<? extends GatewayEntity> entityClass = entityTypeRegistry.getEntityClass(dependency.getType());
        if(entityClass != null){
            Map<String, ? extends GatewayEntity> allEntitiesOfType = rawBundle.getEntities(entityClass);
            Optional<? extends Map.Entry<String, ? extends GatewayEntity>> optionalGatewayEntity =
                    allEntitiesOfType.entrySet().stream()
                            .filter(e -> {
                                GatewayEntity gatewayEntity = e.getValue();
                                if (gatewayEntity.getName() != null) {
                                    return dependency.getName().equals(gatewayEntity.getName());
                                } else {
                                    return dependency.getName().equals(PathUtils.extractName(e.getKey()));
                                }
                            }).findFirst();
            Map entityMap = annotatedBundle.getEntities(entityClass);
            optionalGatewayEntity.ifPresent(e -> entityMap.put(e.getKey(), e.getValue()));
        } else {
            //if entity type is not present, add corresponding unsupported entity
            Map<String, UnsupportedGatewayEntity> unsupportedEntities = rawBundle.getUnsupportedEntities();
            Optional<? extends Map.Entry<String, UnsupportedGatewayEntity>> optionalGatewayEntity =
                    unsupportedEntities.entrySet().stream()
                            .filter(e -> {
                                UnsupportedGatewayEntity gatewayEntity = e.getValue();
                                return dependency.getName().equals(PathUtils.extractName(e.getKey())) && dependency.getType().equals(gatewayEntity.getType());
                            }).findFirst();
            Map entityMap = annotatedBundle.getUnsupportedEntities();
            optionalGatewayEntity.ifPresent(e -> entityMap.put(e.getValue().getMappingValue(), e.getValue()));
        }
    }

    /**
     * Return TRUE is the Gateway entity needs to be excluded from being loaded.
     *
     * @param entityType      Type of entity class
     * @param gatewayEntity   Gateway entity to be checked
     * @param annotatedBundle Annotated Bundle for which bundle is being created.
     * @param excludeShared   Exclude loading Shared entities as the dependency
     * @return TRUE if the Gateway entity needs to be excluded
     */
    private boolean excludeGatewayEntity(Class<? extends GatewayEntity> entityType, GatewayEntity gatewayEntity,
                                         AnnotatedBundle annotatedBundle, boolean excludeShared) {
        return annotatedBundle.getEntities(entityType).containsKey(gatewayEntity.getName())
                || excludeSharedOrPolicyEntity(gatewayEntity, annotatedBundle, excludeShared);
    }

    /**
     * Returns TRUE if the Gateway entity is annotated as @shared and the shared entity needs to excluded or the
     * gateway entity is a policy entity and the annotated bundle already contains that policy.
     *
     * @param gatewayEntity   Gateway entity to be checked
     * @param annotatedBundle Annotated Bundle for which bundle is being created.
     * @param excludeShared   Exclude loading Shared entities as the dependency
     * @return TRUE if the Gateway entity is @shared and needs to be excluded or entity is Policy and annotated
     * bundle already contains the policy
     */
    private boolean excludeSharedOrPolicyEntity(GatewayEntity gatewayEntity, AnnotatedBundle annotatedBundle,
                                                  boolean excludeShared) {
        if (gatewayEntity instanceof AnnotableEntity && ((AnnotableEntity) gatewayEntity).isShared() && excludeShared) {
            return true;
        }
        // Special case for policy because policies are stored by Path in the entities map and
        // GatewayEntity.getName() only gives Policy name.
        return gatewayEntity instanceof Policy && findPolicyByNameOrPath(gatewayEntity.getName(), annotatedBundle) != null;
    }

    /**
     * Finds policy in the Raw bundle by just Policy name or Policy path.
     *
     * @param policyNameOrPath Policy name or path
     * @param rawBundle        Bundle containing all the entities of the gateway.
     * @return Found Policy is exists, returns NULL if not found
     */
    private Policy findPolicyByNameOrPath(String policyNameOrPath, Bundle rawBundle) {
        for (String policyKey : rawBundle.getPolicies().keySet()) {
            if (StringUtils.equalsAny(policyNameOrPath, PathUtils.extractName(policyKey), policyKey)) {
                return rawBundle.getPolicies().get(policyKey);
            }
        }
        return null;
    }

    /**
     * Generates the filename for the install bundle and delete bundle files/
     *
     * @param isDeleteBundle is delete bundle
     * @param bundleName compiled prefix for filename from project details or from annotations
     * @return filename for the bundle
     */
    private String generateBundleFileName(boolean isDeleteBundle, String bundleName) {
        String filenameSuffix = isDeleteBundle ? DELETE_BUNDLE_EXTENSION : INSTALL_BUNDLE_EXTENSION;
        return bundleName + filenameSuffix;
    }

    public void addPrivateKeyContexts(Bundle bundle, ProjectInfo projectInfo, BundleArtifacts bundleArtifacts,
                                      Document document) {
        if (!bundle.getPrivateKeys().isEmpty()) {
            bundle.getPrivateKeys().forEach((alias, privateKey) -> {
                if (privateKey.getPrivateKeyFile() == null) {
                    privateKey.setPrivateKeyFile(bundle.getPrivateKeyFiles().get(privateKey.getAlias()));
                }
                if (privateKey.getPrivateKeyFile() != null) {
                    Element element = privateKeyImportContextBuilder.build(privateKey, document);
                    String filename = generatePrivateKeyFileName(privateKey, projectInfo);
                    bundleArtifacts.addPrivateKeyContext(element, filename);
                }
            });
        }
    }

    private String generatePrivateKeyFileName(PrivateKey privateKey, ProjectInfo projectInfo) {
        StringBuilder filename = new StringBuilder(projectInfo.getName()).append("-").append(privateKey.getAlias());
        if (StringUtils.isNotBlank(projectInfo.getVersion())) {
            filename.append("-").append(projectInfo.getVersion());
            if (StringUtils.isNotBlank(projectInfo.getConfigName())) {
                filename.append("-").append(projectInfo.getConfigName());
            }
        }
        return filename.append(".privatekey").toString();
    }

    @VisibleForTesting
    public Set<EntityBuilder> getEntityBuilders() {
        return entityBuilders;
    }
}
