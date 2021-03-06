/*
 * Copyright (c) 2018 CA. All rights reserved.
 * This software may be modified and distributed under the terms
 * of the MIT license.  See the LICENSE file for details.
 */

package com.ca.apim.gateway.cagatewayconfig.beans;

import com.ca.apim.gateway.cagatewayconfig.bundle.builder.*;
import com.ca.apim.gateway.cagatewayconfig.config.spec.BundleGeneration;
import com.ca.apim.gateway.cagatewayconfig.config.spec.ConfigurationFile;
import com.ca.apim.gateway.cagatewayconfig.config.spec.EnvironmentType;
import com.ca.apim.gateway.cagatewayconfig.util.IdGenerator;
import com.ca.apim.gateway.cagatewayconfig.util.entity.EntityTypes;
import com.ca.apim.gateway.cagatewayconfig.util.file.DocumentFileUtils;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.lang3.StringUtils;

import javax.inject.Named;
import java.io.File;
import java.util.*;

import static com.ca.apim.gateway.cagatewayconfig.config.spec.ConfigurationFile.FileType.JSON_YAML;
import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;
import static org.apache.commons.lang3.ObjectUtils.firstNonNull;
import static java.util.stream.Collectors.toCollection;

@JsonInclude(NON_NULL)
@Named("ENCAPSULATED_ASSERTION")
@ConfigurationFile(name = "encass", type = JSON_YAML)
@EnvironmentType("ENCAPSULATED_ASSERTION")
@BundleGeneration
public class Encass extends GatewayEntity implements AnnotableEntity {

    private String policy;
    private Set<EncassArgument> arguments;
    private Set<EncassResult> results;
    private Map<String, Object> properties = new HashMap<>();
    @JsonDeserialize(using = AnnotationDeserializer.class)
    private Set<Annotation> annotations = new HashSet<>();
    @JsonIgnore
    private String guid;
    @JsonIgnore
    private String policyId;
    @JsonIgnore
    private String path;
    @JsonIgnore
    private AnnotatedEntity<? extends GatewayEntity> annotatedEntity;

    /* Set to true if any Parent (Policy or Encass) in hierarchy is annotated with @shared */
    @JsonIgnore
    private boolean parentEntityShared;
    @JsonIgnore
    private String uniqueEntityName;

    public Encass() { }

    public Encass(Encass otherEncass) {
        this.guid = firstNonNull(otherEncass.guid, this.guid);
        this.setName(firstNonNull(otherEncass.getName(), this.getName()));
        this.setId(firstNonNull(otherEncass.getId(), this.getId()));
        this.properties.putAll(firstNonNull(otherEncass.properties, Collections.emptyMap()));
        this.policy = firstNonNull(otherEncass.policy, this.policy);
        this.path = firstNonNull(otherEncass.path, this.path);
        this.arguments = firstNonNull(firstNonNull(otherEncass.arguments, Collections.emptySet()));
        this.results = firstNonNull(firstNonNull(otherEncass.results, Collections.emptySet()));
        this.policyId = firstNonNull(otherEncass.policyId, this.policyId);
        this.annotations.addAll(firstNonNull(otherEncass.annotations, Collections.emptySet()));
    }

    public Set<EncassArgument> getArguments() {
        return arguments;
    }

    public void setArguments(Set<EncassArgument> arguments) {
        this.arguments = arguments;
    }

    public Set<EncassResult> getResults() {
        return results;
    }

    public void setResults(Set<EncassResult> results) {
        this.results = results;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, Object> properties) {
        this.properties = properties;
    }

    @Override
    public Set<Annotation> getAnnotations() {
        return annotations;
    }

    public void setAnnotations(Set<Annotation> annotations) {
        this.annotations = annotations;
    }

    public String getGuid() {
        return guid;
    }

    public void setGuid(String guid) {
        this.guid = guid;
    }

    public String getPolicy() {
        return policy;
    }

    public void setPolicy(String policy) {
        this.policy = policy;
    }

    public String getPolicyId() {
        return policyId;
    }

    public void setPolicyId(String policyId) {
        this.policyId = policyId;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public boolean isParentEntityShared() {
        return parentEntityShared;
    }

    public void setParentEntityShared(boolean parentEntityShared) {
        this.parentEntityShared = parentEntityShared;
    }

    public String getUniqueEntityName() {
        return uniqueEntityName;
    }

    public void setUniqueEntityName(String uniqueEntityName) {
        this.uniqueEntityName = uniqueEntityName;
    }

    @Override
    public void postLoad(String entityKey, Bundle bundle, File rootFolder, IdGenerator idGenerator) {
        setGuid(idGenerator.generateGuid());
        setId(idGenerator.generate());
        setName(entityKey);
    }

    @Override
    public void preWrite(File configFolder, DocumentFileUtils documentFileUtils) {
        sortArgumentsAndResults();
    }

    @JsonIgnore
    @Override
    public Metadata getMetadata() {
        return new Metadata() {
            @Override
            public String getType() {
                return EntityTypes.ENCAPSULATED_ASSERTION_TYPE;
            }

            @Override
            public String getName() {
                if (StringUtils.isNotBlank(getUniqueEntityName())) {
                    return getUniqueEntityName();
                }
                return Encass.this.getName();
            }

            @Override
            public String getId() {
                if (StringUtils.isNotBlank(getUniqueEntityName()) && isBundle()) {
                    return IdGenerator.generate(getUniqueEntityName());
                }
                AnnotatedEntity annotatedEntity = getAnnotatedEntity();
                if (annotatedEntity != null && StringUtils.isNotBlank(annotatedEntity.getId())) {
                    return annotatedEntity.getId();
                }
                return Encass.this.getId();
            }

            @Override
            public String getGuid() {
                if (StringUtils.isNotBlank(getUniqueEntityName()) && isBundle()) {
                    return IdGenerator.generateGuid(getUniqueEntityName());
                }
                AnnotatedEntity annotatedEntity = getAnnotatedEntity();
                if (annotatedEntity != null && StringUtils.isNotBlank(annotatedEntity.getGuid())) {
                    return annotatedEntity.getGuid();
                }
                return Encass.this.getGuid();
            }

            public Set<EncassArgument> getArguments() {
                return Encass.this.getArguments();
            }

            public Set<EncassResult> getResults() {
                return Encass.this.getResults();
            }
        };
    }

    @VisibleForTesting
    public void sortArgumentsAndResults() {
        setArguments(getArguments().stream().collect(toCollection(() -> new TreeSet<>(Comparator.comparing(EncassArgument::getName)))));
        setResults(getResults().stream().collect(toCollection(() -> new TreeSet<>(Comparator.comparing(EncassResult::getName)))));
    }

    @Override
    public AnnotatedEntity getAnnotatedEntity() {
        if (annotatedEntity == null && annotations != null) {
            annotatedEntity = createAnnotatedEntity();
            if (StringUtils.isBlank(annotatedEntity.getDescription())) {
                Map<String, Object> props = getProperties();
                if (props != null) {
                    annotatedEntity.setDescription(props.getOrDefault("description", "").toString());
                }
            }
            annotatedEntity.setPolicyName(getPolicy());
            annotatedEntity.setEntityName(getName());
        }
        return annotatedEntity;
    }

    @VisibleForTesting
    public void setAnnotatedEntity(AnnotatedEntity<Encass> annotatedEntity) {
        this.annotatedEntity = annotatedEntity;
    }

    @Override
    public String getEntityType() {
        return EntityTypes.ENCAPSULATED_ASSERTION_TYPE;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Encass)) {
            return false;
        }
        Encass encass = (Encass) o;
        return Objects.equals(getName(), encass.getName());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getName());
    }
}
