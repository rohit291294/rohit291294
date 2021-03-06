package com.ca.apim.gateway.cagatewayconfig.bundle.loader;

import com.ca.apim.gateway.cagatewayconfig.beans.Bundle;
import com.ca.apim.gateway.cagatewayconfig.beans.Folder;
import com.ca.apim.gateway.cagatewayconfig.beans.Folderable;
import com.ca.apim.gateway.cagatewayconfig.util.paths.PathUtils;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ServiceAndPolicyLoaderUtil {

    public static final String HANDLE_DUPLICATE_NAMES_PROPERTY = "com.ca.apim.export.handleDuplicateNames";
    public static final String HANDLE_DUPLICATE_NAMES_PROPERTY_DEFAULT = "true";
    public static final String MIGRATE_PORTAL_INTEGRATIONS_ASSERTIONS_PROPERTY = "com.ca.apim.export.migratePortalIntegrationAssertions";
    public static final String MIGRATE_PORTAL_INTEGRATIONS_ASSERTIONS_PROPERTY_DEFAULT = "false";

    public static boolean handleDuplicateNames() {
        return Boolean.parseBoolean(System.getProperty(HANDLE_DUPLICATE_NAMES_PROPERTY,
                HANDLE_DUPLICATE_NAMES_PROPERTY_DEFAULT));
    }

    public static boolean migratePortalIntegrationsAssertions() {
        return Boolean.parseBoolean(System.getProperty(MIGRATE_PORTAL_INTEGRATIONS_ASSERTIONS_PROPERTY,
                MIGRATE_PORTAL_INTEGRATIONS_ASSERTIONS_PROPERTY_DEFAULT));
    }

    /**
     * Get path with file name
     *
     * @param parentFolder parent folder for file name to appended on to for path
     * @param fileName name used to create a path with parent folder
     * @return a path ending with name
     */
    public static String getPath(Folder parentFolder, String fileName) {
        return PathUtils.unixPath(Paths.get(parentFolder.getPath()).resolve(fileName));
    }

    /**
     * Extract folder object from bundle
     *
     * @param bundle bundle contaning the folder
     * @param folderId id of folder to be extracted from bundle
     * @return folder object from bundle
     */
    public static Folder getFolder(Bundle bundle, String folderId) {
        List<Folder> folderList = bundle.getFolders().values().stream().filter(f -> folderId.equals(f.getId())).collect(Collectors.toList());
        if (folderList.isEmpty()) {
            throw new BundleLoadException("Invalid dependency bundle. Could not find folder with id: " + folderId);
        } else if (folderList.size() > 1) {
            throw new BundleLoadException("Invalid dependency bundle. Found multiple folders with id: " + folderId);
        }
        return folderList.get(0);
    }


    /**
     * Append a value to end of name of the service/policy in a path if the name is found in bundle.
     * Service/Policy Id must be different and must exist in the same folder.
     *
     * @param bundleEntity bundle containing original service/policy
     * @param entity duplicate service with same path and service/policy name
     * @return a path with a numerical value appended to the end to differentiate from original service/policy
     */
    public static String handleDuplicatePathName(Map<String, ? extends Folderable> bundleEntity, Folderable entity) {
        int duplicateCounter = 2;
        String basePath = entity.getPath();
        String clonePath = basePath;

        if (handleDuplicateNames()) {
            while (bundleEntity.containsKey(clonePath)) {
                Folderable service = bundleEntity.get(clonePath);
                if (!service.getId().equals(entity.getId())
                        && (service.getParentFolderId().equals(entity.getParentFolderId())) ) {
                    clonePath = basePath + " (" + duplicateCounter + ")";
                    duplicateCounter++;
                } else {
                    break;
                }
            }
            return clonePath;
        } else {
            throw new BundleLoadException("Duplicate name found while exporting string with invalid characters: " + basePath);
        }

    }

    private ServiceAndPolicyLoaderUtil(){}
}
