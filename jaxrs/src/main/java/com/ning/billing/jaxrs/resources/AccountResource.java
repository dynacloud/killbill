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

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;

import com.ning.billing.ErrorCode;
import com.ning.billing.ObjectType;
import com.ning.billing.account.api.Account;
import com.ning.billing.account.api.AccountApiException;
import com.ning.billing.account.api.AccountData;
import com.ning.billing.account.api.AccountEmail;
import com.ning.billing.account.api.AccountUserApi;
import com.ning.billing.account.api.MutableAccountData;
import com.ning.billing.clock.Clock;
import com.ning.billing.entitlement.api.SubscriptionApi;
import com.ning.billing.entitlement.api.SubscriptionApiException;
import com.ning.billing.entitlement.api.SubscriptionBundle;
import com.ning.billing.invoice.api.Invoice;
import com.ning.billing.invoice.api.InvoicePayment;
import com.ning.billing.invoice.api.InvoicePaymentApi;
import com.ning.billing.invoice.api.InvoiceUserApi;
import com.ning.billing.jaxrs.json.AccountEmailJson;
import com.ning.billing.jaxrs.json.AccountJson;
import com.ning.billing.jaxrs.json.AccountTimelineJson;
import com.ning.billing.jaxrs.json.BundleJson;
import com.ning.billing.jaxrs.json.ChargebackJson;
import com.ning.billing.jaxrs.json.CustomFieldJson;
import com.ning.billing.jaxrs.json.InvoiceEmailJson;
import com.ning.billing.jaxrs.json.InvoiceJson;
import com.ning.billing.jaxrs.json.OverdueStateJson;
import com.ning.billing.jaxrs.json.PaymentJson;
import com.ning.billing.jaxrs.json.PaymentMethodJson;
import com.ning.billing.jaxrs.json.RefundJson;
import com.ning.billing.jaxrs.util.Context;
import com.ning.billing.jaxrs.util.JaxrsUriBuilder;
import com.ning.billing.overdue.OverdueApiException;
import com.ning.billing.overdue.OverdueState;
import com.ning.billing.overdue.OverdueUserApi;
import com.ning.billing.overdue.config.api.OverdueException;
import com.ning.billing.payment.api.Payment;
import com.ning.billing.payment.api.PaymentApi;
import com.ning.billing.payment.api.PaymentApiException;
import com.ning.billing.payment.api.PaymentMethod;
import com.ning.billing.payment.api.Refund;
import com.ning.billing.util.api.AuditLevel;
import com.ning.billing.util.api.AuditUserApi;
import com.ning.billing.util.api.CustomFieldApiException;
import com.ning.billing.util.api.CustomFieldUserApi;
import com.ning.billing.util.api.TagApiException;
import com.ning.billing.util.api.TagDefinitionApiException;
import com.ning.billing.util.api.TagUserApi;
import com.ning.billing.util.audit.AccountAuditLogs;
import com.ning.billing.util.callcontext.CallContext;
import com.ning.billing.util.callcontext.TenantContext;
import com.ning.billing.util.entity.Pagination;
import com.ning.billing.util.tag.ControlTagType;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

@Singleton
@Path(JaxrsResource.ACCOUNTS_PATH)
public class AccountResource extends JaxRsResourceBase {

    private static final String ID_PARAM_NAME = "accountId";

    private final SubscriptionApi subscriptionApi;
    private final InvoiceUserApi invoiceApi;
    private final InvoicePaymentApi invoicePaymentApi;
    private final PaymentApi paymentApi;
    private final OverdueUserApi overdueApi;

    @Inject
    public AccountResource(final JaxrsUriBuilder uriBuilder,
                           final AccountUserApi accountApi,
                           final InvoiceUserApi invoiceApi,
                           final InvoicePaymentApi invoicePaymentApi,
                           final PaymentApi paymentApi,
                           final TagUserApi tagUserApi,
                           final AuditUserApi auditUserApi,
                           final CustomFieldUserApi customFieldUserApi,
                           final SubscriptionApi subscriptionApi,
                           final OverdueUserApi overdueApi,
                           final Clock clock,
                           final Context context) {
        super(uriBuilder, tagUserApi, customFieldUserApi, auditUserApi, accountApi, clock, context);
        this.subscriptionApi = subscriptionApi;
        this.invoiceApi = invoiceApi;
        this.invoicePaymentApi = invoicePaymentApi;
        this.paymentApi = paymentApi;
        this.overdueApi = overdueApi;
    }

    @GET
    @Path("/{accountId:" + UUID_PATTERN + "}")
    @Produces(APPLICATION_JSON)
    public Response getAccount(@PathParam("accountId") final String accountId,
                               @QueryParam(QUERY_ACCOUNT_WITH_BALANCE) @DefaultValue("false") final Boolean accountWithBalance,
                               @QueryParam(QUERY_ACCOUNT_WITH_BALANCE_AND_CBA) @DefaultValue("false") final Boolean accountWithBalanceAndCBA,
                               @QueryParam(QUERY_AUDIT) @DefaultValue("NONE") final AuditMode auditMode,
                               @javax.ws.rs.core.Context final HttpServletRequest request) throws AccountApiException {
        final TenantContext tenantContext = context.createContext(request);
        final Account account = accountUserApi.getAccountById(UUID.fromString(accountId), tenantContext);
        final AccountAuditLogs accountAuditLogs = auditUserApi.getAccountAuditLogs(account.getId(), auditMode.getLevel(), tenantContext);
        final AccountJson accountJson = getAccount(account, accountWithBalance, accountWithBalanceAndCBA, accountAuditLogs, tenantContext);
        return Response.status(Status.OK).entity(accountJson).build();
    }

    @GET
    @Path("/" + PAGINATION)
    @Produces(APPLICATION_JSON)
    public Response getAccounts(@QueryParam(QUERY_SEARCH_OFFSET) @DefaultValue("0") final Long offset,
                                @QueryParam(QUERY_SEARCH_LIMIT) @DefaultValue("100") final Long limit,
                                @QueryParam(QUERY_ACCOUNT_WITH_BALANCE) @DefaultValue("false") final Boolean accountWithBalance,
                                @QueryParam(QUERY_ACCOUNT_WITH_BALANCE_AND_CBA) @DefaultValue("false") final Boolean accountWithBalanceAndCBA,
                                @QueryParam(QUERY_AUDIT) @DefaultValue("NONE") final AuditMode auditMode,
                                @javax.ws.rs.core.Context final HttpServletRequest request) throws AccountApiException {
        final TenantContext tenantContext = context.createContext(request);
        final Pagination<Account> accounts = accountUserApi.getAccounts(offset, limit, tenantContext);
        final URI nextPageUri = uriBuilder.nextPage(AccountResource.class, "getAccounts", accounts.getNextOffset(), limit, ImmutableMap.<String, String>of(QUERY_ACCOUNT_WITH_BALANCE, accountWithBalance.toString(),
                                                                                                                                                           QUERY_ACCOUNT_WITH_BALANCE_AND_CBA, accountWithBalanceAndCBA.toString(),
                                                                                                                                                           QUERY_AUDIT, auditMode.getLevel().toString()));
        return buildStreamingPaginationResponse(accounts,
                                                new Function<Account, AccountJson>() {
                                                    @Override
                                                    public AccountJson apply(final Account account) {
                                                        final AccountAuditLogs accountAuditLogs = auditUserApi.getAccountAuditLogs(account.getId(), auditMode.getLevel(), tenantContext);
                                                        return getAccount(account, accountWithBalance, accountWithBalanceAndCBA, accountAuditLogs, tenantContext);
                                                    }
                                                },
                                                nextPageUri);
    }

    @GET
    @Path("/" + SEARCH + "/{searchKey:" + ANYTHING_PATTERN + "}")
    @Produces(APPLICATION_JSON)
    public Response searchAccounts(@PathParam("searchKey") final String searchKey,
                                   @QueryParam(QUERY_SEARCH_OFFSET) @DefaultValue("0") final Long offset,
                                   @QueryParam(QUERY_SEARCH_LIMIT) @DefaultValue("100") final Long limit,
                                   @QueryParam(QUERY_ACCOUNT_WITH_BALANCE) @DefaultValue("false") final Boolean accountWithBalance,
                                   @QueryParam(QUERY_ACCOUNT_WITH_BALANCE_AND_CBA) @DefaultValue("false") final Boolean accountWithBalanceAndCBA,
                                   @QueryParam(QUERY_AUDIT) @DefaultValue("NONE") final AuditMode auditMode,
                                   @javax.ws.rs.core.Context final HttpServletRequest request) throws AccountApiException {
        final TenantContext tenantContext = context.createContext(request);
        final Pagination<Account> accounts = accountUserApi.searchAccounts(searchKey, offset, limit, tenantContext);
        final URI nextPageUri = uriBuilder.nextPage(AccountResource.class, "searchAccounts", accounts.getNextOffset(), limit, ImmutableMap.<String, String>of("searchKey", searchKey,
                                                                                                                                                              QUERY_ACCOUNT_WITH_BALANCE, accountWithBalance.toString(),
                                                                                                                                                              QUERY_ACCOUNT_WITH_BALANCE_AND_CBA, accountWithBalanceAndCBA.toString(),
                                                                                                                                                              QUERY_AUDIT, auditMode.getLevel().toString()));
        return buildStreamingPaginationResponse(accounts,
                                                new Function<Account, AccountJson>() {
                                                    @Override
                                                    public AccountJson apply(final Account account) {
                                                        final AccountAuditLogs accountAuditLogs = auditUserApi.getAccountAuditLogs(account.getId(), auditMode.getLevel(), tenantContext);
                                                        return getAccount(account, accountWithBalance, accountWithBalanceAndCBA, accountAuditLogs, tenantContext);
                                                    }
                                                },
                                                nextPageUri);
    }

    @GET
    @Path("/{accountId:" + UUID_PATTERN + "}/" + BUNDLES)
    @Produces(APPLICATION_JSON)
    public Response getAccountBundles(@PathParam("accountId") final String accountId,
                                      @QueryParam(QUERY_EXTERNAL_KEY) final String externalKey,
                                      @javax.ws.rs.core.Context final HttpServletRequest request) throws AccountApiException, SubscriptionApiException {
        final TenantContext tenantContext = context.createContext(request);

        final UUID uuid = UUID.fromString(accountId);
        accountUserApi.getAccountById(uuid, tenantContext);

        final List<SubscriptionBundle> bundles = (externalKey != null) ?
                                                 subscriptionApi.getSubscriptionBundlesForAccountIdAndExternalKey(uuid, externalKey, tenantContext) :
                                                 subscriptionApi.getSubscriptionBundlesForAccountId(uuid, tenantContext);

        final Collection<BundleJson> result = Collections2.transform(bundles, new Function<SubscriptionBundle, BundleJson>() {
            @Override
            public BundleJson apply(final SubscriptionBundle input) {
                return new BundleJson(input, null);
            }
        });
        return Response.status(Status.OK).entity(result).build();
    }

    @GET
    @Produces(APPLICATION_JSON)
    public Response getAccountByKey(@QueryParam(QUERY_EXTERNAL_KEY) final String externalKey,
                                    @QueryParam(QUERY_ACCOUNT_WITH_BALANCE) @DefaultValue("false") final Boolean accountWithBalance,
                                    @QueryParam(QUERY_ACCOUNT_WITH_BALANCE_AND_CBA) @DefaultValue("false") final Boolean accountWithBalanceAndCBA,
                                    @QueryParam(QUERY_AUDIT) @DefaultValue("NONE") final AuditMode auditMode,
                                    @javax.ws.rs.core.Context final HttpServletRequest request) throws AccountApiException {
        final TenantContext tenantContext = context.createContext(request);
        final Account account = accountUserApi.getAccountByKey(externalKey, tenantContext);
        final AccountAuditLogs accountAuditLogs = auditUserApi.getAccountAuditLogs(account.getId(), auditMode.getLevel(), tenantContext);
        final AccountJson accountJson = getAccount(account, accountWithBalance, accountWithBalanceAndCBA, accountAuditLogs, tenantContext);
        return Response.status(Status.OK).entity(accountJson).build();
    }

    private AccountJson getAccount(final Account account, final Boolean accountWithBalance, final Boolean accountWithBalanceAndCBA,
                                   final AccountAuditLogs auditLogs, final TenantContext tenantContext) {
        if (accountWithBalanceAndCBA) {
            final BigDecimal accountBalance = invoiceApi.getAccountBalance(account.getId(), tenantContext);
            final BigDecimal accountCBA = invoiceApi.getAccountCBA(account.getId(), tenantContext);
            return new AccountJson(account, accountBalance, accountCBA, auditLogs);
        } else if (accountWithBalance) {
            final BigDecimal accountBalance = invoiceApi.getAccountBalance(account.getId(), tenantContext);
            return new AccountJson(account, accountBalance, null, auditLogs);
        } else {
            return new AccountJson(account, null, null, auditLogs);
        }
    }

    @POST
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    public Response createAccount(final AccountJson json,
                                  @HeaderParam(HDR_CREATED_BY) final String createdBy,
                                  @HeaderParam(HDR_REASON) final String reason,
                                  @HeaderParam(HDR_COMMENT) final String comment,
                                  @javax.ws.rs.core.Context final HttpServletRequest request,
                                  @javax.ws.rs.core.Context final UriInfo uriInfo) throws AccountApiException {
        final AccountData data = json.toAccountData();
        final Account account = accountUserApi.createAccount(data, context.createContext(createdBy, reason, comment, request));
        return uriBuilder.buildResponse(uriInfo, AccountResource.class, "getAccount", account.getId());
    }

    @PUT
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    @Path("/{accountId:" + UUID_PATTERN + "}")
    public Response updateAccount(final AccountJson json,
                                  @PathParam("accountId") final String accountId,
                                  @HeaderParam(HDR_CREATED_BY) final String createdBy,
                                  @HeaderParam(HDR_REASON) final String reason,
                                  @HeaderParam(HDR_COMMENT) final String comment,
                                  @javax.ws.rs.core.Context final HttpServletRequest request) throws AccountApiException {
        final AccountData data = json.toAccountData();
        final UUID uuid = UUID.fromString(accountId);
        accountUserApi.updateAccount(uuid, data, context.createContext(createdBy, reason, comment, request));
        return getAccount(accountId, false, false, new AuditMode(AuditLevel.NONE.toString()), request);
    }

    // Not supported
    @DELETE
    @Path("/{accountId:" + UUID_PATTERN + "}")
    @Produces(APPLICATION_JSON)
    public Response cancelAccount(@PathParam("accountId") final String accountId,
                                  @javax.ws.rs.core.Context final HttpServletRequest request) {
        /*
        try {
            accountUserApi.cancelAccount(accountId);
            return Response.status(Status.NO_CONTENT).build();
        } catch (AccountApiException e) {
            log.info(String.format("Failed to cancel account %s", accountId), e);
            return Response.status(Status.BAD_REQUEST).build();
        }
       */
        return Response.status(Status.INTERNAL_SERVER_ERROR).build();
    }

    @GET
    @Path("/{accountId:" + UUID_PATTERN + "}/" + TIMELINE)
    @Produces(APPLICATION_JSON)
    public Response getAccountTimeline(@PathParam("accountId") final String accountIdString,
                                       @QueryParam(QUERY_AUDIT) @DefaultValue("NONE") final AuditMode auditMode,
                                       @javax.ws.rs.core.Context final HttpServletRequest request) throws AccountApiException, PaymentApiException, SubscriptionApiException {
        final TenantContext tenantContext = context.createContext(request);

        final UUID accountId = UUID.fromString(accountIdString);
        final Account account = accountUserApi.getAccountById(accountId, tenantContext);

        // Get the invoices
        final List<Invoice> invoices = invoiceApi.getInvoicesByAccount(account.getId(), tenantContext);

        // Get the payments
        final List<Payment> payments = paymentApi.getAccountPayments(accountId, tenantContext);

        // Get the refunds
        final List<Refund> refunds = paymentApi.getAccountRefunds(account, tenantContext);
        final Multimap<UUID, Refund> refundsByPayment = ArrayListMultimap.<UUID, Refund>create();
        for (final Refund refund : refunds) {
            refundsByPayment.put(refund.getPaymentId(), refund);
        }

        // Get the chargebacks
        final List<InvoicePayment> chargebacks = invoicePaymentApi.getChargebacksByAccountId(accountId, tenantContext);
        final Multimap<UUID, InvoicePayment> chargebacksByPayment = ArrayListMultimap.<UUID, InvoicePayment>create();
        for (final InvoicePayment chargeback : chargebacks) {
            chargebacksByPayment.put(chargeback.getPaymentId(), chargeback);
        }

        // Get the bundles
        final List<SubscriptionBundle> bundles = subscriptionApi.getSubscriptionBundlesForAccountId(account.getId(), tenantContext);

        // Get all audit logs
        final AccountAuditLogs accountAuditLogs = auditUserApi.getAccountAuditLogs(accountId, auditMode.getLevel(), tenantContext);

        final AccountTimelineJson json = new AccountTimelineJson(account, invoices, payments, bundles,
                                                                 refundsByPayment, chargebacksByPayment,
                                                                 accountAuditLogs);
        return Response.status(Status.OK).entity(json).build();
    }

    /*
    * ************************** EMAIL NOTIFICATIONS FOR INVOICES ********************************
    */

    @GET
    @Path("/{accountId:" + UUID_PATTERN + "}/" + EMAIL_NOTIFICATIONS)
    @Produces(APPLICATION_JSON)
    public Response getEmailNotificationsForAccount(@PathParam("accountId") final String accountId,
                                                    @javax.ws.rs.core.Context final HttpServletRequest request) throws AccountApiException {
        final Account account = accountUserApi.getAccountById(UUID.fromString(accountId), context.createContext(request));
        final InvoiceEmailJson invoiceEmailJson = new InvoiceEmailJson(accountId, account.isNotifiedForInvoices());

        return Response.status(Status.OK).entity(invoiceEmailJson).build();
    }

    @PUT
    @Path("/{accountId:" + UUID_PATTERN + "}/" + EMAIL_NOTIFICATIONS)
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    public Response getEmailNotificationsForAccount(final InvoiceEmailJson json,
                                                    @PathParam("accountId") final String accountIdString,
                                                    @HeaderParam(HDR_CREATED_BY) final String createdBy,
                                                    @HeaderParam(HDR_REASON) final String reason,
                                                    @HeaderParam(HDR_COMMENT) final String comment,
                                                    @javax.ws.rs.core.Context final HttpServletRequest request) throws AccountApiException {
        final CallContext callContext = context.createContext(createdBy, reason, comment, request);

        final UUID accountId = UUID.fromString(accountIdString);
        final Account account = accountUserApi.getAccountById(accountId, callContext);

        final MutableAccountData mutableAccountData = account.toMutableAccountData();
        mutableAccountData.setIsNotifiedForInvoices(json.isNotifiedForInvoices());
        accountUserApi.updateAccount(accountId, mutableAccountData, callContext);

        return Response.status(Status.OK).build();
    }

    /*
     * ************************** INVOICE CBA REBALANCING ********************************
     */
    @POST
    @Path("/{accountId:" + UUID_PATTERN + "}/" + CBA_REBALANCING)
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    public Response rebalanceExistingCBAOnAccount(@PathParam("accountId") final String accountIdString,
                                                  @HeaderParam(HDR_CREATED_BY) final String createdBy,
                                                  @HeaderParam(HDR_REASON) final String reason,
                                                  @HeaderParam(HDR_COMMENT) final String comment,
                                                  @javax.ws.rs.core.Context final HttpServletRequest request) throws AccountApiException {

        final CallContext callContext = context.createContext(createdBy, reason, comment, request);
        final UUID accountId = UUID.fromString(accountIdString);

        invoiceApi.consumeExstingCBAOnAccountWithUnpaidInvoices(accountId, callContext);
        return Response.status(Status.OK).build();
    }


        /*
     * ************************** INVOICES ********************************
     */

    @GET
    @Path("/{accountId:" + UUID_PATTERN + "}/" + INVOICES)
    @Produces(APPLICATION_JSON)
    public Response getInvoices(@PathParam("accountId") final String accountIdString,
                                @QueryParam(QUERY_INVOICE_WITH_ITEMS) @DefaultValue("false") final boolean withItems,
                                @QueryParam(QUERY_UNPAID_INVOICES_ONLY) @DefaultValue("false") final boolean unpaidInvoicesOnly,
                                @QueryParam(QUERY_AUDIT) @DefaultValue("NONE") final AuditMode auditMode,
                                @javax.ws.rs.core.Context final HttpServletRequest request) throws AccountApiException {
        final TenantContext tenantContext = context.createContext(request);

        // Verify the account exists
        final UUID accountId = UUID.fromString(accountIdString);
        accountUserApi.getAccountById(accountId, tenantContext);

        final List<Invoice> invoices = unpaidInvoicesOnly ?
                                       new ArrayList<Invoice>(invoiceApi.getUnpaidInvoicesByAccountId(accountId, null, tenantContext)) :
                                       invoiceApi.getInvoicesByAccount(accountId, tenantContext);

        final AccountAuditLogs accountAuditLogs = auditUserApi.getAccountAuditLogs(accountId, auditMode.getLevel(), tenantContext);

        final List<InvoiceJson> result = new LinkedList<InvoiceJson>();
        for (final Invoice invoice : invoices) {
            result.add(new InvoiceJson(invoice, withItems, accountAuditLogs));
        }

        return Response.status(Status.OK).entity(result).build();
    }

    /*
     * ************************** PAYMENTS ********************************
     */

    @GET
    @Path("/{accountId:" + UUID_PATTERN + "}/" + PAYMENTS)
    @Produces(APPLICATION_JSON)
    public Response getPayments(@PathParam("accountId") final String accountId,
                                @javax.ws.rs.core.Context final HttpServletRequest request) throws PaymentApiException {
        final List<Payment> payments = paymentApi.getAccountPayments(UUID.fromString(accountId), context.createContext(request));
        final List<PaymentJson> result = new ArrayList<PaymentJson>(payments.size());
        for (final Payment payment : payments) {
            result.add(new PaymentJson(payment, null));
        }
        return Response.status(Status.OK).entity(result).build();
    }

    @POST
    @Produces(APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    @Path("/{accountId:" + UUID_PATTERN + "}/" + PAYMENTS)
    public Response payAllInvoices(@PathParam("accountId") final String accountId,
                                   @QueryParam(QUERY_PAYMENT_EXTERNAL) @DefaultValue("false") final Boolean externalPayment,
                                   @HeaderParam(HDR_CREATED_BY) final String createdBy,
                                   @HeaderParam(HDR_REASON) final String reason,
                                   @HeaderParam(HDR_COMMENT) final String comment,
                                   @javax.ws.rs.core.Context final HttpServletRequest request) throws AccountApiException, PaymentApiException {
        final CallContext callContext = context.createContext(createdBy, reason, comment, request);

        final Account account = accountUserApi.getAccountById(UUID.fromString(accountId), callContext);
        final Collection<Invoice> unpaidInvoices = invoiceApi.getUnpaidInvoicesByAccountId(account.getId(), clock.getUTCToday(), callContext);
        for (final Invoice invoice : unpaidInvoices) {
            if (externalPayment) {
                paymentApi.createExternalPayment(account, invoice.getId(), invoice.getBalance(), callContext);
            } else {
                paymentApi.createPayment(account, invoice.getId(), invoice.getBalance(), callContext);
            }
        }
        return Response.status(Status.OK).build();
    }

    @POST
    @Path("/{accountId:" + UUID_PATTERN + "}/" + PAYMENT_METHODS)
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    public Response createPaymentMethod(final PaymentMethodJson json,
                                        @PathParam("accountId") final String accountId,
                                        @QueryParam(QUERY_PAYMENT_METHOD_IS_DEFAULT) @DefaultValue("false") final Boolean isDefault,
                                        @QueryParam(QUERY_PAY_ALL_UNPAID_INVOICES) @DefaultValue("false") final Boolean payAllUnpaidInvoices,
                                        @HeaderParam(HDR_CREATED_BY) final String createdBy,
                                        @HeaderParam(HDR_REASON) final String reason,
                                        @HeaderParam(HDR_COMMENT) final String comment,
                                        @javax.ws.rs.core.Context final UriInfo uriInfo,
                                        @javax.ws.rs.core.Context final HttpServletRequest request) throws AccountApiException, PaymentApiException {
        final CallContext callContext = context.createContext(createdBy, reason, comment, request);

        final PaymentMethod data = json.toPaymentMethod(accountId);
        final Account account = accountUserApi.getAccountById(data.getAccountId(), callContext);

        final boolean hasDefaultPaymentMethod = account.getPaymentMethodId() != null || isDefault;
        final Collection<Invoice> unpaidInvoices = payAllUnpaidInvoices ? invoiceApi.getUnpaidInvoicesByAccountId(account.getId(), clock.getUTCToday(), callContext) :
                                                   Collections.<Invoice>emptyList();
        if (payAllUnpaidInvoices && unpaidInvoices.size() > 0 && !hasDefaultPaymentMethod) {
            return Response.status(Status.BAD_REQUEST).build();
        }

        final UUID paymentMethodId = paymentApi.addPaymentMethod(data.getPluginName(), account, isDefault, data.getPluginDetail(), callContext);
        if (payAllUnpaidInvoices && unpaidInvoices.size() > 0) {
            for (final Invoice invoice : unpaidInvoices) {
                paymentApi.createPayment(account, invoice.getId(), invoice.getBalance(), callContext);
            }
        }
        return uriBuilder.buildResponse(PaymentMethodResource.class, "getPaymentMethod", paymentMethodId, uriInfo.getBaseUri().toString());
    }

    @GET
    @Path("/{accountId:" + UUID_PATTERN + "}/" + PAYMENT_METHODS)
    @Produces(APPLICATION_JSON)
    public Response getPaymentMethods(@PathParam("accountId") final String accountId,
                                      @QueryParam(QUERY_PAYMENT_METHOD_PLUGIN_INFO) @DefaultValue("false") final Boolean withPluginInfo,
                                      @QueryParam(QUERY_AUDIT) @DefaultValue("NONE") final AuditMode auditMode,
                                      @javax.ws.rs.core.Context final HttpServletRequest request) throws AccountApiException, PaymentApiException {
        final TenantContext tenantContext = context.createContext(request);

        final Account account = accountUserApi.getAccountById(UUID.fromString(accountId), tenantContext);
        final List<PaymentMethod> methods = paymentApi.getPaymentMethods(account, withPluginInfo, tenantContext);
        final AccountAuditLogs accountAuditLogs = auditUserApi.getAccountAuditLogs(account.getId(), auditMode.getLevel(), tenantContext);
        final List<PaymentMethodJson> json = new ArrayList<PaymentMethodJson>(Collections2.transform(methods, new Function<PaymentMethod, PaymentMethodJson>() {
            @Override
            public PaymentMethodJson apply(final PaymentMethod input) {
                return PaymentMethodJson.toPaymentMethodJson(account, input, accountAuditLogs);
            }
        }));

        return Response.status(Status.OK).entity(json).build();
    }

    @PUT
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    @Path("/{accountId:" + UUID_PATTERN + "}/" + PAYMENT_METHODS + "/{paymentMethodId:" + UUID_PATTERN + "}/" + PAYMENT_METHODS_DEFAULT_PATH_POSTFIX)
    public Response setDefaultPaymentMethod(@PathParam("accountId") final String accountId,
                                            @PathParam("paymentMethodId") final String paymentMethodId,
                                            @QueryParam(QUERY_PAY_ALL_UNPAID_INVOICES) @DefaultValue("false") final Boolean payAllUnpaidInvoices,
                                            @HeaderParam(HDR_CREATED_BY) final String createdBy,
                                            @HeaderParam(HDR_REASON) final String reason,
                                            @HeaderParam(HDR_COMMENT) final String comment,
                                            @javax.ws.rs.core.Context final HttpServletRequest request) throws AccountApiException, PaymentApiException {
        final CallContext callContext = context.createContext(createdBy, reason, comment, request);

        final Account account = accountUserApi.getAccountById(UUID.fromString(accountId), callContext);
        paymentApi.setDefaultPaymentMethod(account, UUID.fromString(paymentMethodId), callContext);

        if (payAllUnpaidInvoices) {
            final Collection<Invoice> unpaidInvoices = invoiceApi.getUnpaidInvoicesByAccountId(account.getId(), clock.getUTCToday(), callContext);
            for (final Invoice invoice : unpaidInvoices) {
                paymentApi.createPayment(account, invoice.getId(), invoice.getBalance(), callContext);
            }
        }
        return Response.status(Status.OK).build();
    }

    /*
     * ************************** CHARGEBACKS ********************************
     */
    @GET
    @Path("/{accountId:" + UUID_PATTERN + "}/" + CHARGEBACKS)
    @Produces(APPLICATION_JSON)
    public Response getChargebacksForAccount(@PathParam("accountId") final String accountIdStr,
                                             @javax.ws.rs.core.Context final HttpServletRequest request) {
        final UUID accountId = UUID.fromString(accountIdStr);
        final List<InvoicePayment> chargebacks = invoicePaymentApi.getChargebacksByAccountId(accountId, context.createContext(request));
        final List<ChargebackJson> chargebacksJson = new ArrayList<ChargebackJson>();
        for (final InvoicePayment chargeback : chargebacks) {
            chargebacksJson.add(new ChargebackJson(accountId, chargeback));
        }
        return Response.status(Response.Status.OK).entity(chargebacksJson).build();
    }

    /*
     * ************************** REFUNDS ********************************
     */
    @GET
    @Path("/{accountId:" + UUID_PATTERN + "}/" + REFUNDS)
    @Produces(APPLICATION_JSON)
    public Response getRefunds(@PathParam("accountId") final String accountId,
                               @javax.ws.rs.core.Context final HttpServletRequest request) throws AccountApiException, PaymentApiException {
        final TenantContext tenantContext = context.createContext(request);

        final Account account = accountUserApi.getAccountById(UUID.fromString(accountId), tenantContext);
        final List<Refund> refunds = paymentApi.getAccountRefunds(account, tenantContext);
        final List<RefundJson> result = new ArrayList<RefundJson>(Collections2.transform(refunds, new Function<Refund, RefundJson>() {
            @Override
            public RefundJson apply(Refund input) {
                // TODO Return adjusted items and audits
                return new RefundJson(input, null, null);
            }
        }));

        return Response.status(Status.OK).entity(result).build();
    }

    /*
     * ************************** OVERDUE ********************************
     */
    @GET
    @Path("/{accountId:" + UUID_PATTERN + "}/" + OVERDUE)
    @Produces(APPLICATION_JSON)
    public Response getOverdueAccount(@PathParam("accountId") final String accountId,
                                      @javax.ws.rs.core.Context final HttpServletRequest request) throws AccountApiException, OverdueException, OverdueApiException {
        final TenantContext tenantContext = context.createContext(request);

        final Account account = accountUserApi.getAccountById(UUID.fromString(accountId), tenantContext);
        final OverdueState overdueState = overdueApi.getOverdueStateFor(account, tenantContext);

        return Response.status(Status.OK).entity(new OverdueStateJson(overdueState)).build();
    }

    /*
     * *************************      CUSTOM FIELDS     *****************************
     */

    @GET
    @Path("/{accountId:" + UUID_PATTERN + "}/" + CUSTOM_FIELDS)
    @Produces(APPLICATION_JSON)
    public Response getCustomFields(@PathParam(ID_PARAM_NAME) final String id,
                                    @QueryParam(QUERY_AUDIT) @DefaultValue("NONE") final AuditMode auditMode,
                                    @javax.ws.rs.core.Context final HttpServletRequest request) {
        return super.getCustomFields(UUID.fromString(id), auditMode, context.createContext(request));
    }

    @POST
    @Path("/{accountId:" + UUID_PATTERN + "}/" + CUSTOM_FIELDS)
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    public Response createCustomFields(@PathParam(ID_PARAM_NAME) final String id,
                                       final List<CustomFieldJson> customFields,
                                       @HeaderParam(HDR_CREATED_BY) final String createdBy,
                                       @HeaderParam(HDR_REASON) final String reason,
                                       @HeaderParam(HDR_COMMENT) final String comment,
                                       @javax.ws.rs.core.Context final HttpServletRequest request,
                                       @javax.ws.rs.core.Context final UriInfo uriInfo) throws CustomFieldApiException {
        return super.createCustomFields(UUID.fromString(id), customFields,
                                        context.createContext(createdBy, reason, comment, request), uriInfo);
    }

    @DELETE
    @Path("/{accountId:" + UUID_PATTERN + "}/" + CUSTOM_FIELDS)
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    public Response deleteCustomFields(@PathParam(ID_PARAM_NAME) final String id,
                                       @QueryParam(QUERY_CUSTOM_FIELDS) final String customFieldList,
                                       @HeaderParam(HDR_CREATED_BY) final String createdBy,
                                       @HeaderParam(HDR_REASON) final String reason,
                                       @HeaderParam(HDR_COMMENT) final String comment,
                                       @javax.ws.rs.core.Context final HttpServletRequest request) throws CustomFieldApiException {
        return super.deleteCustomFields(UUID.fromString(id), customFieldList,
                                        context.createContext(createdBy, reason, comment, request));
    }

    /*
     * *************************     TAGS     *****************************
     */

    @GET
    @Path("/{accountId:" + UUID_PATTERN + "}/" + TAGS)
    @Produces(APPLICATION_JSON)
    public Response getTags(@PathParam(ID_PARAM_NAME) final String accountIdString,
                            @QueryParam(QUERY_AUDIT) @DefaultValue("NONE") final AuditMode auditMode,
                            @QueryParam(QUERY_TAGS_INCLUDED_DELETED) @DefaultValue("false") final Boolean includedDeleted,
                            @javax.ws.rs.core.Context final HttpServletRequest request) throws TagDefinitionApiException {
        final UUID accountId = UUID.fromString(accountIdString);
        return super.getTags(accountId, accountId, auditMode, includedDeleted, context.createContext(request));
    }

    @POST
    @Path("/{accountId:" + UUID_PATTERN + "}/" + TAGS)
    @Produces(APPLICATION_JSON)
    public Response createTags(@PathParam(ID_PARAM_NAME) final String id,
                               @QueryParam(QUERY_TAGS) final String tagList,
                               @HeaderParam(HDR_CREATED_BY) final String createdBy,
                               @HeaderParam(HDR_REASON) final String reason,
                               @HeaderParam(HDR_COMMENT) final String comment,
                               @javax.ws.rs.core.Context final UriInfo uriInfo,
                               @javax.ws.rs.core.Context final HttpServletRequest request) throws TagApiException {
        return super.createTags(UUID.fromString(id), tagList, uriInfo,
                                context.createContext(createdBy, reason, comment, request));
    }

    @DELETE
    @Path("/{accountId:" + UUID_PATTERN + "}/" + TAGS)
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    public Response deleteTags(@PathParam(ID_PARAM_NAME) final String id,
                               @QueryParam(QUERY_TAGS) final String tagList,
                               @HeaderParam(HDR_CREATED_BY) final String createdBy,
                               @HeaderParam(HDR_REASON) final String reason,
                               @HeaderParam(HDR_COMMENT) final String comment,
                               @javax.ws.rs.core.Context final HttpServletRequest request) throws TagApiException, AccountApiException {
        final CallContext callContext = context.createContext(createdBy, reason, comment, request);

        // Look if there is an AUTO_PAY_OFF for that account and check if the account has a default paymentMethod
        // If not we can't remove the AUTO_PAY_OFF tag
        final Collection<UUID> tagDefinitionUUIDs = getTagDefinitionUUIDs(tagList);
        boolean isTagAutoPayOff = false;
        for (final UUID cur : tagDefinitionUUIDs) {
            if (cur.equals(ControlTagType.AUTO_PAY_OFF.getId())) {
                isTagAutoPayOff = true;
                break;
            }
        }
        final UUID accountId = UUID.fromString(id);
        if (isTagAutoPayOff) {
            final Account account = accountUserApi.getAccountById(accountId, callContext);
            if (account.getPaymentMethodId() == null) {
                throw new TagApiException(ErrorCode.TAG_CANNOT_BE_REMOVED, ControlTagType.AUTO_PAY_OFF, " the account does not have a default payment method");
            }
        }

        return super.deleteTags(UUID.fromString(id), tagList, callContext);
    }

    /*
     * *************************     EMAILS     *****************************
     */

    @GET
    @Path("/{accountId:" + UUID_PATTERN + "}/" + EMAILS)
    @Produces(APPLICATION_JSON)
    public Response getEmails(@PathParam(ID_PARAM_NAME) final String id,
                              @javax.ws.rs.core.Context final HttpServletRequest request) {
        final UUID accountId = UUID.fromString(id);
        final List<AccountEmail> emails = accountUserApi.getEmails(accountId, context.createContext(request));

        final List<AccountEmailJson> emailsJson = new ArrayList<AccountEmailJson>();
        for (final AccountEmail email : emails) {
            emailsJson.add(new AccountEmailJson(email.getAccountId().toString(), email.getEmail()));
        }
        return Response.status(Status.OK).entity(emailsJson).build();
    }

    @POST
    @Path("/{accountId:" + UUID_PATTERN + "}/" + EMAILS)
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    public Response addEmail(final AccountEmailJson json,
                             @PathParam(ID_PARAM_NAME) final String id,
                             @HeaderParam(HDR_CREATED_BY) final String createdBy,
                             @HeaderParam(HDR_REASON) final String reason,
                             @HeaderParam(HDR_COMMENT) final String comment,
                             @javax.ws.rs.core.Context final HttpServletRequest request,
                             @javax.ws.rs.core.Context final UriInfo uriInfo) throws AccountApiException {
        final CallContext callContext = context.createContext(createdBy, reason, comment, request);

        final UUID accountId = UUID.fromString(id);

        // Make sure the account exist or we will confuse the history and auditing code
        accountUserApi.getAccountById(accountId, callContext);

        // Make sure the email doesn't exist
        final AccountEmail existingEmail = Iterables.<AccountEmail>tryFind(accountUserApi.getEmails(accountId, callContext),
                                                                           new Predicate<AccountEmail>() {
                                                                               @Override
                                                                               public boolean apply(final AccountEmail input) {
                                                                                   return input.getEmail().equals(json.getEmail());
                                                                               }
                                                                           })
                                                    .orNull();
        if (existingEmail == null) {
            accountUserApi.addEmail(accountId, json.toAccountEmail(UUID.randomUUID()), callContext);
        }

        return uriBuilder.buildResponse(uriInfo, AccountResource.class, "getEmails", json.getAccountId());
    }

    @DELETE
    @Path("/{accountId:" + UUID_PATTERN + "}/" + EMAILS + "/{email}")
    @Produces(APPLICATION_JSON)
    public Response removeEmail(@PathParam(ID_PARAM_NAME) final String id,
                                @PathParam("email") final String email,
                                @HeaderParam(HDR_CREATED_BY) final String createdBy,
                                @HeaderParam(HDR_REASON) final String reason,
                                @HeaderParam(HDR_COMMENT) final String comment,
                                @javax.ws.rs.core.Context final HttpServletRequest request) {
        final UUID accountId = UUID.fromString(id);

        final List<AccountEmail> emails = accountUserApi.getEmails(accountId, context.createContext(request));
        for (AccountEmail cur : emails) {
            if (cur.getEmail().equals(email)) {
                final AccountEmailJson accountEmailJson = new AccountEmailJson(accountId.toString(), email);
                final AccountEmail accountEmail = accountEmailJson.toAccountEmail(cur.getId());
                accountUserApi.removeEmail(accountId, accountEmail, context.createContext(createdBy, reason, comment, request));
            }
        }
        return Response.status(Status.OK).build();
    }

    @Override
    protected ObjectType getObjectType() {
        return ObjectType.ACCOUNT;
    }
}
