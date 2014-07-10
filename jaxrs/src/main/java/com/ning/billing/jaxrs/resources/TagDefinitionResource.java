/*
 * Copyright 2010-2013 Ning, Inc.
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

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;

import com.ning.billing.ObjectType;
import com.ning.billing.account.api.AccountUserApi;
import com.ning.billing.clock.Clock;
import com.ning.billing.jaxrs.json.TagDefinitionJson;
import com.ning.billing.jaxrs.util.Context;
import com.ning.billing.jaxrs.util.JaxrsUriBuilder;
import com.ning.billing.util.api.AuditUserApi;
import com.ning.billing.util.api.CustomFieldUserApi;
import com.ning.billing.util.api.TagDefinitionApiException;
import com.ning.billing.util.api.TagUserApi;
import com.ning.billing.util.audit.AuditLog;
import com.ning.billing.util.callcontext.TenantContext;
import com.ning.billing.util.tag.TagDefinition;

import com.google.common.base.Preconditions;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

@Singleton
@Path(JaxrsResource.TAG_DEFINITIONS_PATH)
public class TagDefinitionResource extends JaxRsResourceBase {

    @Inject
    public TagDefinitionResource(final JaxrsUriBuilder uriBuilder,
                                 final TagUserApi tagUserApi,
                                 final CustomFieldUserApi customFieldUserApi,
                                 final AuditUserApi auditUserApi,
                                 final AccountUserApi accountUserApi,
                                 final Clock clock,
                                 final Context context) {
        super(uriBuilder, tagUserApi, customFieldUserApi, auditUserApi, accountUserApi, clock, context);
    }

    @GET
    @Produces(APPLICATION_JSON)
    public Response getTagDefinitions(@javax.ws.rs.core.Context final HttpServletRequest request,
                                      @QueryParam(QUERY_AUDIT) @DefaultValue("NONE") final AuditMode auditMode) {
        final TenantContext tenantContext = context.createContext(request);
        final List<TagDefinition> tagDefinitions = tagUserApi.getTagDefinitions(tenantContext);

        final Collection<TagDefinitionJson> result = new LinkedList<TagDefinitionJson>();
        for (final TagDefinition tagDefinition : tagDefinitions) {
            final List<AuditLog> auditLogs = auditUserApi.getAuditLogs(tagDefinition.getId(), ObjectType.TAG_DEFINITION, auditMode.getLevel(), tenantContext);
            result.add(new TagDefinitionJson(tagDefinition, auditLogs));
        }

        return Response.status(Status.OK).entity(result).build();
    }

    @GET
    @Path("/{tagDefinitionId:" + UUID_PATTERN + "}")
    @Produces(APPLICATION_JSON)
    public Response getTagDefinition(@PathParam("tagDefinitionId") final String tagDefId,
                                     @QueryParam(QUERY_AUDIT) @DefaultValue("NONE") final AuditMode auditMode,
                                     @javax.ws.rs.core.Context final HttpServletRequest request) throws TagDefinitionApiException {
        final TenantContext tenantContext = context.createContext(request);
        final TagDefinition tagDefinition = tagUserApi.getTagDefinition(UUID.fromString(tagDefId), tenantContext);
        final List<AuditLog> auditLogs = auditUserApi.getAuditLogs(tagDefinition.getId(), ObjectType.TAG_DEFINITION, auditMode.getLevel(), tenantContext);
        final TagDefinitionJson json = new TagDefinitionJson(tagDefinition, auditLogs);
        return Response.status(Status.OK).entity(json).build();
    }

    @POST
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    public Response createTagDefinition(final TagDefinitionJson json,
                                        @HeaderParam(HDR_CREATED_BY) final String createdBy,
                                        @HeaderParam(HDR_REASON) final String reason,
                                        @HeaderParam(HDR_COMMENT) final String comment,
                                        @javax.ws.rs.core.Context final HttpServletRequest request,
                                        @javax.ws.rs.core.Context final UriInfo uriInfo) throws TagDefinitionApiException {
        // Checked as the database layer as well, but bail early and return 400 instead of 500
        Preconditions.checkNotNull(json.getName(), String.format("TagDefinition name needs to be set"));
        Preconditions.checkNotNull(json.getDescription(), String.format("TagDefinition description needs to be set"));

        final TagDefinition createdTagDef = tagUserApi.createTagDefinition(json.getName(), json.getDescription(), context.createContext(createdBy, reason, comment, request));
        return uriBuilder.buildResponse(uriInfo, TagDefinitionResource.class, "getTagDefinition", createdTagDef.getId());
    }

    @DELETE
    @Path("/{tagDefinitionId:" + UUID_PATTERN + "}")
    @Produces(APPLICATION_JSON)
    public Response deleteTagDefinition(@PathParam("tagDefinitionId") final String tagDefId,
                                        @HeaderParam(HDR_CREATED_BY) final String createdBy,
                                        @HeaderParam(HDR_REASON) final String reason,
                                        @HeaderParam(HDR_COMMENT) final String comment,
                                        @javax.ws.rs.core.Context final HttpServletRequest request) throws TagDefinitionApiException {
        tagUserApi.deleteTagDefinition(UUID.fromString(tagDefId), context.createContext(createdBy, reason, comment, request));
        return Response.status(Status.NO_CONTENT).build();
    }

    @Override
    protected ObjectType getObjectType() {
        return ObjectType.TAG_DEFINITION;
    }
}
