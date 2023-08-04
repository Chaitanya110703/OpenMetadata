/*
 *  Copyright 2021 Collate
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.openmetadata.service.jdbi3;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.entity.classification.Classification;
import org.openmetadata.schema.entity.classification.Tag;
import org.openmetadata.schema.entity.policies.Policy;
import org.openmetadata.schema.entity.policies.accessControl.Rule;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.ProviderType;
import org.openmetadata.schema.type.Relationship;
import org.openmetadata.schema.type.TagLabel.TagSource;
import org.openmetadata.service.Entity;
import org.openmetadata.service.exception.CatalogExceptionMessage;
import org.openmetadata.service.resources.tags.TagResource;
import org.openmetadata.service.util.EntityUtil.Fields;
import org.openmetadata.service.util.FullyQualifiedName;
import org.openmetadata.service.util.JsonUtils;
import org.openmetadata.service.util.RestUtil;

import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonPatch;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;
import static org.openmetadata.schema.type.Include.ALL;
import static org.openmetadata.schema.type.Include.NON_DELETED;
import static org.openmetadata.service.Entity.FIELD_DOMAIN;
import static org.openmetadata.service.Entity.TAG;
import static org.openmetadata.service.util.EntityUtil.entityReferenceMatch;
import static org.openmetadata.service.util.EntityUtil.getId;

@Slf4j
public class TagRepository extends EntityRepository<Tag> {
  public TagRepository(CollectionDAO dao) {
    super(TagResource.TAG_COLLECTION_PATH, Entity.TAG, Tag.class, dao.tagDAO(), dao, "", "");
  }

  @Override
  public void prepare(Tag entity) throws IOException {
    // Validate parent term
    EntityReference parentTerm = Entity.getEntityReference(entity.getParent(), NON_DELETED);
    entity.setParent(parentTerm);

    // Validate Classification
    EntityReference classification = Entity.getEntityReference(entity.getClassification(), NON_DELETED);
    entity.setClassification(classification);
  }

  @Override
  public void storeEntity(Tag tag, boolean update) throws IOException {
    EntityReference classification = tag.getClassification();
    EntityReference parent = tag.getParent();

    // Parent and Classification are not stored as part of JSON. Build it on the fly based on relationships
    tag.withClassification(null).withParent(null);
    store(tag, update);
    tag.withClassification(classification).withParent(parent);
  }

  @Override
  public void restorePatchAttributes(Tag original, Tag updated) {
    updated.setChildren(original.getChildren());
  }

  @Override
  public void storeRelationships(Tag entity) {
    addClassificationRelationship(entity);
    addParentRelationship(entity);
  }

  @Override
  public Tag setInheritedFields(Tag tag, Fields fields) throws IOException {
    if (fields.contains(FIELD_DOMAIN) && nullOrEmpty(tag.getDomain())) {
      if (tag.getParent() != null) {
        Tag parentTerm = get(null, tag.getParent().getId(), getFields("domain"));
        tag.setDomain(parentTerm.getDomain());
      } else {
        Classification classification = Entity.getEntity(tag.getClassification(), "domain", ALL);
        tag.setDomain(classification.getDomain());
      }
    }
    return tag;
  }

  @Override
  public void setFullyQualifiedName(Tag tag) {
    if (tag.getParent() == null) {
      tag.setFullyQualifiedName(FullyQualifiedName.build(tag.getClassification().getName(), tag.getName()));
    } else {
      tag.setFullyQualifiedName(FullyQualifiedName.add(tag.getParent().getFullyQualifiedName(), tag.getName()));
    }
  }

  @Override
  public EntityRepository<Tag>.EntityUpdater getUpdater(Tag original, Tag updated, Operation operation) {
    return new TagUpdater(original, updated, operation);
  }

  @Override
  protected void postDelete(Tag entity) {
    // Cleanup all the tag labels using this tag
    daoCollection.tagUsageDAO().deleteTagLabels(TagSource.CLASSIFICATION.ordinal(), entity.getFullyQualifiedName());
  }

  @Override
  public Tag setFields(Tag tag, Fields fields) throws IOException {
    tag.withClassification(getClassification(tag)).withParent(getParent(tag));
    tag.setChildren(fields.contains("children") ? getChildren(tag) : null);
    return tag.withUsageCount(fields.contains("usageCount") ? getUsageCount(tag) : null);
  }

  @Override
  public RestUtil.PatchResponse<Tag> patch(UriInfo uriInfo, UUID id, String user, JsonPatch patch) throws IOException {
    // Get all the fields in the original entity that can be updated during PATCH operation
    Tag original = setFieldsInternal(dao.findEntityById(id), patchFields);
    setInheritedFields(original, patchFields);
    JsonArray jsonArray = patch.toJsonArray();
    // Apply JSON patch to the original entity to get the updated entity
    Tag updated = JsonUtils.applyPatch(original, patch, Tag.class);
    updated.setUpdatedBy(user);
    updated.setUpdatedAt(System.currentTimeMillis());

    prepareInternal(updated);
    populateOwner(updated.getOwner());
    restorePatchAttributes(original, updated);

    // Update the attributes and relationships of an entity
    EntityUpdater entityUpdater = getUpdater(original, updated, Operation.PATCH);
    entityUpdater.update();
    jsonArray.forEach(
        entry -> {
          JsonObject jsonObject = entry.asJsonObject();
          if (jsonObject.getString("path").equals("/name")) {
            List<String> policyData = daoCollection.policyDAO().listPoliciesWithMatchTagCondition();
            for (String json : policyData) {
              Policy policy;
              try {
                policy = JsonUtils.readValue(json, Policy.class);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
              List<Rule> rules = policy.getRules();
              for (Rule rule : rules) {
                if (rule.getCondition() != null) {
                  List<String> tags = new ArrayList<>();
                  Pattern pattern = Pattern.compile("'([^']+)'");
                  Matcher matcher = pattern.matcher(rule.getCondition());
                  while (matcher.find()) {
                    String tagValue = matcher.group(1);
                    tags.add(tagValue);
                  }
                  if (tags.contains(original.getFullyQualifiedName())) {
                    rule.setCondition(
                        rule.getCondition().replace(original.getFullyQualifiedName(), updated.getFullyQualifiedName()));
                    policy.setRules(rules);
                    PolicyRepository policyRepository = new PolicyRepository(daoCollection);
                    try {
                      policyRepository.createOrUpdateInternal(uriInfo, policy);
                    } catch (IOException e) {
                      throw new RuntimeException(e);
                    }
                  }
                }
              }
            }
          }
        });
    String change = entityUpdater.fieldsChanged() ? RestUtil.ENTITY_UPDATED : RestUtil.ENTITY_NO_CHANGE;
    return new RestUtil.PatchResponse<>(Response.Status.OK, withHref(uriInfo, updated), change);
  }

  private Integer getUsageCount(Tag tag) {
    return daoCollection.tagUsageDAO().getTagCount(TagSource.CLASSIFICATION.ordinal(), tag.getFullyQualifiedName());
  }

  private List<EntityReference> getChildren(Tag entity) throws IOException {
    return findTo(entity.getId(), TAG, Relationship.CONTAINS, TAG);
  }

  private EntityReference getParent(Tag tag) throws IOException {
    return getFromEntityRef(tag.getId(), Relationship.CONTAINS, TAG, false);
  }

  private EntityReference getClassification(Tag tag) throws IOException {
    return getFromEntityRef(tag.getId(), Relationship.CONTAINS, Entity.CLASSIFICATION, true);
  }

  private void addClassificationRelationship(Tag term) {
    addRelationship(term.getClassification().getId(), term.getId(), Entity.CLASSIFICATION, TAG, Relationship.CONTAINS);
  }

  private void addParentRelationship(Tag term) {
    if (term.getParent() != null) {
      addRelationship(term.getParent().getId(), term.getId(), TAG, TAG, Relationship.CONTAINS);
    }
  }

  public class TagUpdater extends EntityUpdater {
    public TagUpdater(Tag original, Tag updated, Operation operation) {
      super(original, updated, operation);
    }

    @Override
    public void entitySpecificUpdate() throws IOException {
      recordChange("mutuallyExclusive", original.getMutuallyExclusive(), updated.getMutuallyExclusive());
      recordChange("disabled,", original.getDisabled(), updated.getDisabled());
      updateName(original, updated);
      updateParent(original, updated);
    }

    public void updateName(Tag original, Tag updated) throws IOException {
      if (!original.getName().equals(updated.getName())) {
        if (ProviderType.SYSTEM.equals(original.getProvider())) {
          throw new IllegalArgumentException(
              CatalogExceptionMessage.systemEntityRenameNotAllowed(original.getName(), entityType));
        }
        // Category name changed - update tag names starting from classification and all the children tags
        LOG.info("Tag name changed from {} to {}", original.getName(), updated.getName());
        daoCollection.tagDAO().updateFqn(original.getFullyQualifiedName(), updated.getFullyQualifiedName());
        daoCollection
            .tagUsageDAO()
            .rename(
                TagSource.CLASSIFICATION.ordinal(), original.getFullyQualifiedName(), updated.getFullyQualifiedName());
        recordChange("name", original.getName(), updated.getName());
      }

      // Populate response fields
      getChildren(updated);
    }

    private void updateParent(Tag original, Tag updated) throws JsonProcessingException {
      // Can't change parent and Classification both at the same time
      UUID oldParentId = getId(original.getParent());
      UUID newParentId = getId(updated.getParent());
      boolean parentChanged = !Objects.equals(oldParentId, newParentId);

      UUID oldCategoryId = getId(original.getClassification());
      UUID newCategoryId = getId(updated.getClassification());
      boolean classificationChanged = !Objects.equals(oldCategoryId, newCategoryId);

      daoCollection.tagDAO().updateFqn(original.getFullyQualifiedName(), updated.getFullyQualifiedName());
      daoCollection
          .tagUsageDAO()
          .rename(
              TagSource.CLASSIFICATION.ordinal(), original.getFullyQualifiedName(), updated.getFullyQualifiedName());
      if (classificationChanged) {
        updateClassificationRelationship(original, updated);
        recordChange(
            "Classification", original.getClassification(), updated.getClassification(), true, entityReferenceMatch);
      }
      if (parentChanged) {
        updateParentRelationship(original, updated);
        recordChange("parent", original.getParent(), updated.getParent(), true, entityReferenceMatch);
      }
    }

    private void updateClassificationRelationship(Tag orig, Tag updated) {
      deleteClassificationRelationship(orig);
      addClassificationRelationship(updated);
    }

    private void deleteClassificationRelationship(Tag term) {
      deleteRelationship(
          term.getClassification().getId(), Entity.CLASSIFICATION, term.getId(), TAG, Relationship.CONTAINS);
    }

    private void updateParentRelationship(Tag orig, Tag updated) {
      deleteParentRelationship(orig);
      addParentRelationship(updated);
    }

    private void deleteParentRelationship(Tag term) {
      if (term.getParent() != null) {
        deleteRelationship(term.getParent().getId(), TAG, term.getId(), TAG, Relationship.CONTAINS);
      }
    }
  }
}
