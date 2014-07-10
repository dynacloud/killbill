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

import java.net.URI;
import java.util.List;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;

import com.ning.billing.ObjectType;
import com.ning.billing.account.api.AccountUserApi;
import com.ning.billing.clock.Clock;
import com.ning.billing.jaxrs.json.TenantJson;
import com.ning.billing.jaxrs.json.TenantKeyJson;
import com.ning.billing.jaxrs.util.Context;
import com.ning.billing.jaxrs.util.JaxrsUriBuilder;
import com.ning.billing.tenant.api.Tenant;
import com.ning.billing.tenant.api.TenantApiException;
import com.ning.billing.tenant.api.TenantData;
import com.ning.billing.tenant.api.TenantKV.TenantKey;
import com.ning.billing.tenant.api.TenantUserApi;
import com.ning.billing.util.api.AuditUserApi;
import com.ning.billing.util.api.CustomFieldUserApi;
import com.ning.billing.util.api.TagUserApi;
import com.ning.billing.util.callcontext.CallContext;
import com.ning.billing.util.callcontext.TenantContext;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

@Singleton
@Path(JaxrsResource.TENANTS_PATH)
public class TenantResource extends JaxRsResourceBase {

    private final TenantUserApi tenantApi;

    @Inject
    public TenantResource(final TenantUserApi tenantApi,
                          final JaxrsUriBuilder uriBuilder,
                          final TagUserApi tagUserApi,
                          final CustomFieldUserApi customFieldUserApi,
                          final AuditUserApi auditUserApi,
                          final AccountUserApi accountUserApi,
                          final Clock clock,
                          final Context context) {
        super(uriBuilder, tagUserApi, customFieldUserApi, auditUserApi, accountUserApi, clock, context);
        this.tenantApi = tenantApi;
    }

    @GET
    @Path("/{tenantId:" + UUID_PATTERN + "}")
    @Produces(APPLICATION_JSON)
    public Response getTenant(@PathParam("tenantId") final String tenantId) throws TenantApiException {
        final Tenant tenant = tenantApi.getTenantById(UUID.fromString(tenantId));
        return Response.status(Status.OK).entity(new TenantJson(tenant)).build();
    }

    @GET
    @Produces(APPLICATION_JSON)
    public Response getTenantByApiKey(@QueryParam(QUERY_API_KEY) final String externalKey) throws TenantApiException {
        final Tenant tenant = tenantApi.getTenantByApiKey(externalKey);
        return Response.status(Status.OK).entity(new TenantJson(tenant)).build();
    }

    @POST
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    public Response createTenant(final TenantJson json,
                                 @HeaderParam(HDR_CREATED_BY) final String createdBy,
                                 @HeaderParam(HDR_REASON) final String reason,
                                 @HeaderParam(HDR_COMMENT) final String comment,
                                 @javax.ws.rs.core.Context final HttpServletRequest request,
                                 @javax.ws.rs.core.Context final UriInfo uriInfo) throws TenantApiException {
        final TenantData data = json.toTenantData();
        final Tenant tenant = tenantApi.createTenant(data, context.createContext(createdBy, reason, comment, request));
        return uriBuilder.buildResponse(uriInfo, TenantResource.class, "getTenant", tenant.getId());
    }

    @POST
    @Path("/" + REGISTER_NOTIFICATION_CALLBACK)
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    public Response registerPushNotificationCallback(@PathParam("tenantId") final String tenantId,
                                                     @QueryParam(QUERY_NOTIFICATION_CALLBACK) final String notificationCallback,
                                                     @HeaderParam(HDR_CREATED_BY) final String createdBy,
                                                     @HeaderParam(HDR_REASON) final String reason,
                                                     @HeaderParam(HDR_COMMENT) final String comment,
                                                     @javax.ws.rs.core.Context final HttpServletRequest request) throws TenantApiException {
        final CallContext callContext = context.createContext(createdBy, reason, comment, request);
        tenantApi.addTenantKeyValue(TenantKey.PUSH_NOTIFICATION_CB.toString(), notificationCallback, callContext);
        final URI uri = UriBuilder.fromResource(TenantResource.class).path(TenantResource.class, "getPushNotificationCallbacks").build();
        return Response.created(uri).build();
    }

    @GET
    @Path("/" + REGISTER_NOTIFICATION_CALLBACK)
    @Produces(APPLICATION_JSON)
    public Response getPushNotificationCallbacks(@javax.ws.rs.core.Context final HttpServletRequest request) throws TenantApiException {

        final TenantContext tenatContext = context.createContext(request);
        final List<String> values = tenantApi.getTenantValueForKey(TenantKey.PUSH_NOTIFICATION_CB.toString(), tenatContext);
        final TenantKeyJson result = new TenantKeyJson(TenantKey.PUSH_NOTIFICATION_CB.toString(), values);
        return Response.status(Status.OK).entity(result).build();
    }

    @DELETE
    @Path("/REGISTER_NOTIFICATION_CALLBACK")
    public Response deletePushNotificationCallbacks(@PathParam("tenantId") final String tenantId,
                                                    @HeaderParam(HDR_CREATED_BY) final String createdBy,
                                                    @HeaderParam(HDR_REASON) final String reason,
                                                    @HeaderParam(HDR_COMMENT) final String comment,
                                                    @javax.ws.rs.core.Context final HttpServletRequest request) throws TenantApiException {
        final CallContext callContext = context.createContext(createdBy, reason, comment, request);
        tenantApi.deleteTenantKey(TenantKey.PUSH_NOTIFICATION_CB.toString(), callContext);
        return Response.status(Status.OK).build();
    }

    @Override
    protected ObjectType getObjectType() {
        return ObjectType.TENANT;
    }
}
