/*
 * Copyright 2010-2012 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ning.billing.util.tag.dao;

import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import org.skife.jdbi.v2.IDBI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.billing.BillingExceptionBase;
import com.ning.billing.ErrorCode;
import com.ning.billing.ObjectType;
import com.ning.billing.bus.api.PersistentBus;
import com.ning.billing.callcontext.InternalCallContext;
import com.ning.billing.callcontext.InternalTenantContext;
import com.ning.billing.clock.Clock;
import com.ning.billing.events.TagInternalEvent;
import com.ning.billing.util.api.TagApiException;
import com.ning.billing.util.audit.ChangeType;
import com.ning.billing.util.cache.CacheControllerDispatcher;
import com.ning.billing.util.dao.NonEntityDao;
import com.ning.billing.util.entity.Pagination;
import com.ning.billing.util.entity.dao.DefaultPaginationSqlDaoHelper.PaginationIteratorBuilder;
import com.ning.billing.util.entity.dao.EntityDaoBase;
import com.ning.billing.util.entity.dao.EntitySqlDao;
import com.ning.billing.util.entity.dao.EntitySqlDaoTransactionWrapper;
import com.ning.billing.util.entity.dao.EntitySqlDaoTransactionalJdbiWrapper;
import com.ning.billing.util.entity.dao.EntitySqlDaoWrapperFactory;
import com.ning.billing.util.tag.ControlTagType;
import com.ning.billing.util.tag.Tag;
import com.ning.billing.util.tag.api.user.TagEventBuilder;

import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.inject.Inject;

public class DefaultTagDao extends EntityDaoBase<TagModelDao, Tag, TagApiException> implements TagDao {

    private static final Logger log = LoggerFactory.getLogger(DefaultTagDao.class);

    private final TagEventBuilder tagEventBuilder;
    private final PersistentBus bus;

    @Inject
    public DefaultTagDao(final IDBI dbi, final TagEventBuilder tagEventBuilder, final PersistentBus bus, final Clock clock,
                         final CacheControllerDispatcher controllerDispatcher, final NonEntityDao nonEntityDao) {
        super(new EntitySqlDaoTransactionalJdbiWrapper(dbi, clock, controllerDispatcher, nonEntityDao), TagSqlDao.class);
        this.tagEventBuilder = tagEventBuilder;
        this.bus = bus;
    }

    @Override
    public List<TagModelDao> getTagsForObject(final UUID objectId, final ObjectType objectType, final boolean includedDeleted, final InternalTenantContext internalTenantContext) {
        return transactionalSqlDao.execute(new EntitySqlDaoTransactionWrapper<List<TagModelDao>>() {
            @Override
            public List<TagModelDao> inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws Exception {
                final TagSqlDao tagSqlDao = entitySqlDaoWrapperFactory.become(TagSqlDao.class);
                if (includedDeleted) {
                    return tagSqlDao.getTagsForObjectIncludedDeleted(objectId, objectType, internalTenantContext);
                } else {
                    return tagSqlDao.getTagsForObject(objectId, objectType, internalTenantContext);
                }
            }
        });
    }

    @Override
    public List<TagModelDao> getTagsForAccountType(final UUID accountId, final ObjectType objectType, final boolean includedDeleted, final InternalTenantContext internalTenantContext) {
        final List<TagModelDao> allTags = getTagsForAccount(includedDeleted, internalTenantContext);
        return ImmutableList.<TagModelDao>copyOf(Collections2.filter(allTags, new Predicate<TagModelDao>() {
            @Override
            public boolean apply(final TagModelDao input) {
                return input.getObjectType() == objectType;
            }
        }));
    }

    @Override
    public List<TagModelDao> getTagsForAccount(final boolean includedDeleted, final InternalTenantContext internalTenantContext) {
        return transactionalSqlDao.execute(new EntitySqlDaoTransactionWrapper<List<TagModelDao>>() {
            @Override
            public List<TagModelDao> inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws Exception {
                final TagSqlDao tagSqlDao = entitySqlDaoWrapperFactory.become(TagSqlDao.class);
                if (includedDeleted) {
                    return tagSqlDao.getByAccountRecordIdIncludedDeleted(internalTenantContext);
                } else {
                    return tagSqlDao.getByAccountRecordId(internalTenantContext);
                }
            }
        });
    }

    @Override
    protected void postBusEventFromTransaction(final TagModelDao tag, final TagModelDao savedTag, final ChangeType changeType,
                                               final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory, final InternalCallContext context)
            throws BillingExceptionBase {

        final TagInternalEvent tagEvent;
        final TagDefinitionModelDao tagDefinition = getTagDefinitionFromTransaction(tag.getTagDefinitionId(), entitySqlDaoWrapperFactory, context);
        final boolean isControlTag = ControlTagType.getTypeFromId(tagDefinition.getId()) != null;
        switch (changeType) {
            case INSERT:
                tagEvent = (isControlTag) ?
                           tagEventBuilder.newControlTagCreationEvent(tag.getId(), tag.getObjectId(), tag.getObjectType(), tagDefinition,
                                                                      context.getAccountRecordId(), context.getTenantRecordId(), context.getUserToken()) :
                           tagEventBuilder.newUserTagCreationEvent(tag.getId(), tag.getObjectId(), tag.getObjectType(), tagDefinition,
                                                                   context.getAccountRecordId(), context.getTenantRecordId(), context.getUserToken());
                break;
            case DELETE:
                tagEvent = (isControlTag) ?
                           tagEventBuilder.newControlTagDeletionEvent(tag.getId(), tag.getObjectId(), tag.getObjectType(), tagDefinition,
                                                                      context.getAccountRecordId(), context.getTenantRecordId(), context.getUserToken()) :
                           tagEventBuilder.newUserTagDeletionEvent(tag.getId(), tag.getObjectId(), tag.getObjectType(), tagDefinition,
                                                                   context.getAccountRecordId(), context.getTenantRecordId(), context.getUserToken());
                break;
            default:
                return;
        }

        try {
            bus.postFromTransaction(tagEvent, entitySqlDaoWrapperFactory.getSqlDao());
        } catch (PersistentBus.EventBusException e) {
            log.warn("Failed to post tag event for tag " + tag.getId().toString(), e);
        }
    }

    @Override
    protected boolean checkEntityAlreadyExists(final EntitySqlDao<TagModelDao, Tag> transactional, final TagModelDao entity, final InternalCallContext context) {
        return Iterables.find(transactional.getByAccountRecordId(context),
                              new Predicate<TagModelDao>() {
                                  @Override
                                  public boolean apply(final TagModelDao existingTag) {
                                      return entity.equals(existingTag) || entity.isSame(existingTag);
                                  }
                              },
                              null) != null;
    }

    @Override
    protected TagApiException generateAlreadyExistsException(final TagModelDao entity, final InternalCallContext context) {
        // Print the tag details, not the id here, as we throw this exception when checking if a tag already exists
        // by using the isSame(TagModelDao) method (see above)
        return new TagApiException(ErrorCode.TAG_ALREADY_EXISTS, entity.toString());
    }

    private TagDefinitionModelDao getTagDefinitionFromTransaction(final UUID tagDefinitionId, final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory, final InternalTenantContext context) throws TagApiException {
        TagDefinitionModelDao tagDefintion = null;
        for (final ControlTagType t : ControlTagType.values()) {
            if (t.getId().equals(tagDefinitionId)) {
                tagDefintion = new TagDefinitionModelDao(t);
                break;
            }
        }
        if (tagDefintion == null) {
            final TagDefinitionSqlDao transTagDefintionSqlDao = entitySqlDaoWrapperFactory.become(TagDefinitionSqlDao.class);
            tagDefintion = transTagDefintionSqlDao.getById(tagDefinitionId.toString(), context);
        }

        if (tagDefintion == null) {
            throw new TagApiException(ErrorCode.TAG_DEFINITION_DOES_NOT_EXIST, tagDefinitionId);
        }
        return tagDefintion;
    }

    @Override
    public void create(final TagModelDao entity, final InternalCallContext context) throws TagApiException {
        transactionalSqlDao.execute(TagApiException.class, getCreateEntitySqlDaoTransactionWrapper(entity, context));
    }

    @Override
    public void deleteTag(final UUID objectId, final ObjectType objectType, final UUID tagDefinitionId, final InternalCallContext context) throws TagApiException {

        transactionalSqlDao.execute(new EntitySqlDaoTransactionWrapper<Void>() {

            @Override
            public Void inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws Exception {

                final TagDefinitionModelDao tagDefinition = getTagDefinitionFromTransaction(tagDefinitionId, entitySqlDaoWrapperFactory, context);
                final TagSqlDao transactional = entitySqlDaoWrapperFactory.become(TagSqlDao.class);
                final List<TagModelDao> tags = transactional.getTagsForObject(objectId, objectType, context);
                TagModelDao tag = null;
                for (final TagModelDao cur : tags) {
                    if (cur.getTagDefinitionId().equals(tagDefinitionId)) {
                        tag = cur;
                        break;
                    }
                }
                if (tag == null) {
                    throw new TagApiException(ErrorCode.TAG_DOES_NOT_EXIST, tagDefinition.getName());
                }
                // Delete the tag
                transactional.markTagAsDeleted(tag.getId().toString(), context);

                postBusEventFromTransaction(tag, tag, ChangeType.DELETE, entitySqlDaoWrapperFactory, context);
                return null;
            }
        });

    }

    @Override
    public Pagination<TagModelDao> searchTags(final String searchKey, final Long offset, final Long limit, final InternalTenantContext context) {
        return paginationHelper.getPagination(TagSqlDao.class,
                                              new PaginationIteratorBuilder<TagModelDao, Tag, TagSqlDao>() {
                                                  @Override
                                                  public Iterator<TagModelDao> build(final TagSqlDao tagSqlDao, final Long limit) {
                                                      return tagSqlDao.searchTags(searchKey, String.format("%%%s%%", searchKey), offset, limit, context);
                                                  }
                                              },
                                              offset,
                                              limit,
                                              context);
    }
}
