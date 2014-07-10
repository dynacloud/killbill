/*
 * Copyright 2010-2011 Ning, Inc.
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

import org.skife.jdbi.v2.sqlobject.Bind;
import org.skife.jdbi.v2.sqlobject.BindBean;
import org.skife.jdbi.v2.sqlobject.SqlQuery;
import org.skife.jdbi.v2.sqlobject.SqlUpdate;
import org.skife.jdbi.v2.sqlobject.customizers.FetchSize;

import com.ning.billing.ObjectType;
import com.ning.billing.callcontext.InternalCallContext;
import com.ning.billing.callcontext.InternalTenantContext;
import com.ning.billing.util.audit.ChangeType;
import com.ning.billing.util.entity.dao.Audited;
import com.ning.billing.util.entity.dao.EntitySqlDao;
import com.ning.billing.util.entity.dao.EntitySqlDaoStringTemplate;
import com.ning.billing.util.tag.Tag;

@EntitySqlDaoStringTemplate
public interface TagSqlDao extends EntitySqlDao<TagModelDao, Tag> {

    @SqlUpdate
    @Audited(ChangeType.DELETE)
    void markTagAsDeleted(@Bind("id") String tagId,
                          @BindBean InternalCallContext context);

    @SqlQuery
    List<TagModelDao> getTagsForObject(@Bind("objectId") UUID objectId,
                                       @Bind("objectType") ObjectType objectType,
                                       @BindBean InternalTenantContext internalTenantContext);

    @SqlQuery
    List<TagModelDao> getTagsForObjectIncludedDeleted(@Bind("objectId") UUID objectId,
                                                      @Bind("objectType") ObjectType objectType,
                                                      @BindBean InternalTenantContext internalTenantContext);

    @SqlQuery
    // Magic value to force MySQL to stream from the database
    // See http://dev.mysql.com/doc/refman/5.0/en/connector-j-reference-implementation-notes.html (ResultSet)
    @FetchSize(Integer.MIN_VALUE)
    public Iterator<TagModelDao> searchTags(@Bind("searchKey") final String searchKey,
                                            @Bind("likeSearchKey") final String likeSearchKey,
                                            @Bind("offset") final Long offset,
                                            @Bind("rowCount") final Long rowCount,
                                            @BindBean final InternalTenantContext context);
}
