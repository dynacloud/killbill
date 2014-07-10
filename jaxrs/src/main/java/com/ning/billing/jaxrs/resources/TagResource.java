/*
 * Copyright 2010-2014 Ning, Inc.
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

package com.ning.billing.jaxrs.resources;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;

import com.ning.billing.ObjectType;
import com.ning.billing.account.api.AccountUserApi;
import com.ning.billing.clock.Clock;
import com.ning.billing.jaxrs.json.TagJson;
import com.ning.billing.jaxrs.util.Context;
import com.ning.billing.jaxrs.util.JaxrsUriBuilder;
import com.ning.billing.util.api.AuditUserApi;
import com.ning.billing.util.api.CustomFieldUserApi;
import com.ning.billing.util.api.TagApiException;
import com.ning.billing.util.api.TagUserApi;
import com.ning.billing.util.audit.AuditLog;
import com.ning.billing.util.callcontext.TenantContext;
import com.ning.billing.util.entity.Pagination;
import com.ning.billing.util.tag.Tag;
import com.ning.billing.util.tag.TagDefinition;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

@Singleton
@Path(JaxrsResource.TAGS_PATH)
public class TagResource extends JaxRsResourceBase {

    @Inject
    public TagResource(final JaxrsUriBuilder uriBuilder,
                       final TagUserApi tagUserApi,
                       final CustomFieldUserApi customFieldUserApi,
                       final AuditUserApi auditUserApi,
                       final AccountUserApi accountUserApi,
                       final Clock clock,
                       final Context context) {
        super(uriBuilder, tagUserApi, customFieldUserApi, auditUserApi, accountUserApi, clock, context);
    }

    @GET
    @Path("/" + PAGINATION)
    @Produces(APPLICATION_JSON)
    public Response getTags(@QueryParam(QUERY_SEARCH_OFFSET) @DefaultValue("0") final Long offset,
                            @QueryParam(QUERY_SEARCH_LIMIT) @DefaultValue("100") final Long limit,
                            @QueryParam(QUERY_AUDIT) @DefaultValue("NONE") final AuditMode auditMode,
                            @javax.ws.rs.core.Context final HttpServletRequest request) throws TagApiException {
        final TenantContext tenantContext = context.createContext(request);
        final Pagination<Tag> tags = tagUserApi.getTags(offset, limit, tenantContext);
        final URI nextPageUri = uriBuilder.nextPage(TagResource.class, "getTags", tags.getNextOffset(), limit, ImmutableMap.<String, String>of(QUERY_AUDIT, auditMode.getLevel().toString()));

        final Map<UUID, TagDefinition> tagDefinitionsCache = new HashMap<UUID, TagDefinition>();
        for (final TagDefinition tagDefinition : tagUserApi.getTagDefinitions(tenantContext)) {
            tagDefinitionsCache.put(tagDefinition.getId(), tagDefinition);
        }

        return buildStreamingPaginationResponse(tags,
                                                new Function<Tag, TagJson>() {
                                                    @Override
                                                    public TagJson apply(final Tag tag) {
                                                        final TagDefinition tagDefinition = tagDefinitionsCache.get(tag.getTagDefinitionId());

                                                        // TODO Really slow - we should instead try to figure out the account id
                                                        final List<AuditLog> auditLogs = auditUserApi.getAuditLogs(tag.getId(), ObjectType.TAG, auditMode.getLevel(), tenantContext);
                                                        return new TagJson(tag, tagDefinition, auditLogs);
                                                    }
                                                },
                                                nextPageUri);
    }

    @GET
    @Path("/" + SEARCH + "/{searchKey:" + ANYTHING_PATTERN + "}")
    @Produces(APPLICATION_JSON)
    public Response searchTags(@PathParam("searchKey") final String searchKey,
                               @QueryParam(QUERY_SEARCH_OFFSET) @DefaultValue("0") final Long offset,
                               @QueryParam(QUERY_SEARCH_LIMIT) @DefaultValue("100") final Long limit,
                               @QueryParam(QUERY_AUDIT) @DefaultValue("NONE") final AuditMode auditMode,
                               @javax.ws.rs.core.Context final HttpServletRequest request) throws TagApiException {
        final TenantContext tenantContext = context.createContext(request);
        final Pagination<Tag> tags = tagUserApi.searchTags(searchKey, offset, limit, tenantContext);
        final URI nextPageUri = uriBuilder.nextPage(TagResource.class, "searchTags", tags.getNextOffset(), limit, ImmutableMap.<String, String>of("searchKey", searchKey,
                                                                                                                                                  QUERY_AUDIT, auditMode.getLevel().toString()));
        final Map<UUID, TagDefinition> tagDefinitionsCache = new HashMap<UUID, TagDefinition>();
        for (final TagDefinition tagDefinition : tagUserApi.getTagDefinitions(tenantContext)) {
            tagDefinitionsCache.put(tagDefinition.getId(), tagDefinition);
        }
        return buildStreamingPaginationResponse(tags,
                                                new Function<Tag, TagJson>() {
                                                    @Override
                                                    public TagJson apply(final Tag tag) {
                                                        final TagDefinition tagDefinition = tagDefinitionsCache.get(tag.getTagDefinitionId());

                                                        // TODO Really slow - we should instead try to figure out the account id
                                                        final List<AuditLog> auditLogs = auditUserApi.getAuditLogs(tag.getId(), ObjectType.TAG, auditMode.getLevel(), tenantContext);
                                                        return new TagJson(tag, tagDefinition, auditLogs);
                                                    }
                                                },
                                                nextPageUri);
    }
}
