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

package com.ning.billing.payment.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.billing.ErrorCode;
import com.ning.billing.ObjectType;
import com.ning.billing.account.api.Account;
import com.ning.billing.account.api.AccountApiException;
import com.ning.billing.account.api.AccountInternalApi;
import com.ning.billing.bus.api.PersistentBus;
import com.ning.billing.callcontext.InternalCallContext;
import com.ning.billing.callcontext.InternalTenantContext;
import com.ning.billing.clock.Clock;
import com.ning.billing.commons.locker.GlobalLocker;
import com.ning.billing.events.BusInternalEvent;
import com.ning.billing.events.PaymentErrorInternalEvent;
import com.ning.billing.invoice.api.Invoice;
import com.ning.billing.invoice.api.InvoiceApiException;
import com.ning.billing.invoice.api.InvoiceInternalApi;
import com.ning.billing.osgi.api.OSGIServiceRegistration;
import com.ning.billing.payment.api.DefaultPayment;
import com.ning.billing.payment.api.DefaultPaymentErrorEvent;
import com.ning.billing.payment.api.DefaultPaymentInfoEvent;
import com.ning.billing.payment.api.DefaultPaymentPluginErrorEvent;
import com.ning.billing.payment.api.Payment;
import com.ning.billing.payment.api.PaymentApiException;
import com.ning.billing.payment.api.PaymentStatus;
import com.ning.billing.payment.dao.PaymentAttemptModelDao;
import com.ning.billing.payment.dao.PaymentDao;
import com.ning.billing.payment.dao.PaymentModelDao;
import com.ning.billing.payment.dao.RefundModelDao;
import com.ning.billing.payment.dispatcher.PluginDispatcher;
import com.ning.billing.payment.plugin.api.PaymentInfoPlugin;
import com.ning.billing.payment.plugin.api.PaymentPluginApi;
import com.ning.billing.payment.plugin.api.PaymentPluginApiException;
import com.ning.billing.payment.plugin.api.PaymentPluginStatus;
import com.ning.billing.payment.retry.AutoPayRetryService.AutoPayRetryServiceScheduler;
import com.ning.billing.payment.retry.FailedPaymentRetryService.FailedPaymentRetryServiceScheduler;
import com.ning.billing.payment.retry.PluginFailureRetryService.PluginFailureRetryServiceScheduler;
import com.ning.billing.tag.TagInternalApi;
import com.ning.billing.util.callcontext.TenantContext;
import com.ning.billing.util.config.PaymentConfig;
import com.ning.billing.util.dao.NonEntityDao;
import com.ning.billing.util.entity.Pagination;
import com.ning.billing.util.entity.dao.DefaultPaginationHelper.EntityPaginationBuilder;
import com.ning.billing.util.entity.dao.DefaultPaginationHelper.SourcePaginationBuilder;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.inject.name.Named;

import static com.ning.billing.payment.glue.PaymentModule.PLUGIN_EXECUTOR_NAMED;
import static com.ning.billing.util.entity.dao.DefaultPaginationHelper.getEntityPagination;
import static com.ning.billing.util.entity.dao.DefaultPaginationHelper.getEntityPaginationFromPlugins;

public class PaymentProcessor extends ProcessorBase {

    private static final UUID MISSING_PAYMENT_METHOD_ID = UUID.fromString("99999999-dead-beef-babe-999999999999");

    private final PaymentMethodProcessor paymentMethodProcessor;
    private final FailedPaymentRetryServiceScheduler failedPaymentRetryService;
    private final PluginFailureRetryServiceScheduler pluginFailureRetryService;
    private final AutoPayRetryServiceScheduler autoPayoffRetryService;

    private final Clock clock;

    private final PaymentConfig paymentConfig;

    private final PluginDispatcher<Payment> paymentPluginDispatcher;
    private final PluginDispatcher<Void> voidPluginDispatcher;

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessor.class);

    @Inject
    public PaymentProcessor(final OSGIServiceRegistration<PaymentPluginApi> pluginRegistry,
                            final PaymentMethodProcessor paymentMethodProcessor,
                            final AccountInternalApi accountUserApi,
                            final InvoiceInternalApi invoiceApi,
                            final TagInternalApi tagUserApi,
                            final FailedPaymentRetryServiceScheduler failedPaymentRetryService,
                            final PluginFailureRetryServiceScheduler pluginFailureRetryService,
                            final AutoPayRetryServiceScheduler autoPayoffRetryService,
                            final PaymentDao paymentDao,
                            final NonEntityDao nonEntityDao,
                            final PersistentBus eventBus,
                            final Clock clock,
                            final GlobalLocker locker,
                            final PaymentConfig paymentConfig,
                            @Named(PLUGIN_EXECUTOR_NAMED) final ExecutorService executor) {
        super(pluginRegistry, accountUserApi, eventBus, paymentDao, nonEntityDao, tagUserApi, locker, executor, invoiceApi);
        this.paymentMethodProcessor = paymentMethodProcessor;
        this.failedPaymentRetryService = failedPaymentRetryService;
        this.pluginFailureRetryService = pluginFailureRetryService;
        this.autoPayoffRetryService = autoPayoffRetryService;
        this.clock = clock;
        this.paymentConfig = paymentConfig;
        final long paymentPluginTimeoutSec = TimeUnit.SECONDS.convert(paymentConfig.getPaymentPluginTimeout().getPeriod(), paymentConfig.getPaymentPluginTimeout().getUnit());
        this.paymentPluginDispatcher = new PluginDispatcher<Payment>(paymentPluginTimeoutSec, executor);
        this.voidPluginDispatcher = new PluginDispatcher<Void>(paymentPluginTimeoutSec, executor);
    }

    public Payment getPayment(final UUID paymentId, final boolean withPluginInfo, final InternalTenantContext context) throws PaymentApiException {
        final PaymentModelDao model = paymentDao.getPayment(paymentId, context);
        if (model == null) {
            return null;
        }
        final PaymentPluginApi plugin = withPluginInfo ? getPaymentProviderPlugin(model.getPaymentMethodId(), context) : null;
        PaymentInfoPlugin pluginInfo = null;
        if (plugin != null) {
            try {
                pluginInfo = plugin.getPaymentInfo(model.getAccountId(), paymentId, buildTenantContext(context));
            } catch (PaymentPluginApiException e) {
                throw new PaymentApiException(ErrorCode.PAYMENT_PLUGIN_GET_PAYMENT_INFO, paymentId, e.toString());
            }
        }
        return fromPaymentModelDao(model, pluginInfo, context);
    }

    public Pagination<Payment> getPayments(final Long offset, final Long limit, final TenantContext tenantContext, final InternalTenantContext internalTenantContext) {
        return getEntityPaginationFromPlugins(getAvailablePlugins(),
                                              offset,
                                              limit,
                                              new EntityPaginationBuilder<Payment, PaymentApiException>() {
                                                  @Override
                                                  public Pagination<Payment> build(final Long offset, final Long limit, final String pluginName) throws PaymentApiException {
                                                      return getPayments(offset, limit, pluginName, tenantContext, internalTenantContext);
                                                  }
                                              });
    }

    public Pagination<Payment> getPayments(final Long offset, final Long limit, final String pluginName, final TenantContext tenantContext, final InternalTenantContext internalTenantContext) throws PaymentApiException {
        final PaymentPluginApi pluginApi = getPaymentPluginApi(pluginName);

        return getEntityPagination(limit,
                                   new SourcePaginationBuilder<PaymentModelDao, PaymentApiException>() {
                                       @Override
                                       public Pagination<PaymentModelDao> build() {
                                           // Find all payments for all accounts
                                           return paymentDao.getPayments(pluginName, offset, limit, internalTenantContext);
                                       }
                                   },
                                   new Function<PaymentModelDao, Payment>() {
                                       @Override
                                       public Payment apply(final PaymentModelDao paymentModelDao) {
                                           PaymentInfoPlugin pluginInfo = null;
                                           try {
                                               pluginInfo = pluginApi.getPaymentInfo(paymentModelDao.getAccountId(), paymentModelDao.getId(), tenantContext);
                                           } catch (final PaymentPluginApiException e) {
                                               log.warn("Unable to find payment id " + paymentModelDao.getId() + " in plugin " + pluginName);
                                               // We still want to return a payment object, even though the plugin details are missing
                                           }

                                           return fromPaymentModelDao(paymentModelDao, pluginInfo, internalTenantContext);
                                       }
                                   }
                                  );
    }

    public Pagination<Payment> searchPayments(final String searchKey, final Long offset, final Long limit, final InternalTenantContext internalTenantContext) {
        return getEntityPaginationFromPlugins(getAvailablePlugins(),
                                              offset,
                                              limit,
                                              new EntityPaginationBuilder<Payment, PaymentApiException>() {
                                                  @Override
                                                  public Pagination<Payment> build(final Long offset, final Long limit, final String pluginName) throws PaymentApiException {
                                                      return searchPayments(searchKey, offset, limit, pluginName, internalTenantContext);
                                                  }
                                              });
    }

    public Pagination<Payment> searchPayments(final String searchKey, final Long offset, final Long limit, final String pluginName, final InternalTenantContext internalTenantContext) throws PaymentApiException {
        final PaymentPluginApi pluginApi = getPaymentPluginApi(pluginName);

        return getEntityPagination(limit,
                                   new SourcePaginationBuilder<PaymentInfoPlugin, PaymentApiException>() {
                                       @Override
                                       public Pagination<PaymentInfoPlugin> build() throws PaymentApiException {
                                           try {
                                               return pluginApi.searchPayments(searchKey, offset, limit, buildTenantContext(internalTenantContext));
                                           } catch (final PaymentPluginApiException e) {
                                               throw new PaymentApiException(e, ErrorCode.PAYMENT_PLUGIN_SEARCH_PAYMENTS, pluginName, searchKey);
                                           }
                                       }
                                   },
                                   new Function<PaymentInfoPlugin, Payment>() {
                                       @Override
                                       public Payment apply(final PaymentInfoPlugin paymentInfoPlugin) {
                                           if (paymentInfoPlugin.getKbPaymentId() == null) {
                                               // Garbage from the plugin?
                                               log.debug("Plugin {} returned a payment without a kbPaymentId for searchKey {}", pluginName, searchKey);
                                               return null;
                                           }

                                           final PaymentModelDao model = paymentDao.getPayment(paymentInfoPlugin.getKbPaymentId(), internalTenantContext);
                                           if (model == null) {
                                               log.warn("Unable to find payment id " + paymentInfoPlugin.getKbPaymentId() + " present in plugin " + pluginName);
                                               return null;
                                           }

                                           return fromPaymentModelDao(model, paymentInfoPlugin, internalTenantContext);
                                       }
                                   }
                                  );
    }

    public List<Payment> getInvoicePayments(final UUID invoiceId, final InternalTenantContext context) {
        return getPayments(paymentDao.getPaymentsForInvoice(invoiceId, context), context);
    }

    public List<Payment> getAccountPayments(final UUID accountId, final InternalTenantContext context) {
        return getPayments(paymentDao.getPaymentsForAccount(accountId, context), context);
    }

    private List<Payment> getPayments(final List<PaymentModelDao> payments, final InternalTenantContext context) {
        if (payments == null) {
            return Collections.emptyList();
        }
        final List<Payment> result = new LinkedList<Payment>();
        for (final PaymentModelDao cur : payments) {
            final Payment entry = fromPaymentModelDao(cur, null, context);
            result.add(entry);
        }
        return result;
    }

    private Payment fromPaymentModelDao(final PaymentModelDao input, @Nullable final PaymentInfoPlugin pluginInfo, final InternalTenantContext context) {
        final List<PaymentAttemptModelDao> attempts = paymentDao.getAttemptsForPayment(input.getId(), context);
        final List<RefundModelDao> refunds = paymentDao.getRefundsForPayment(input.getId(), context);
        final Payment payment = new DefaultPayment(input, pluginInfo, attempts, refunds);
        return payment;
    }

    public void process_AUTO_PAY_OFF_removal(final Account account, final InternalCallContext context) throws PaymentApiException {

        try {
            voidPluginDispatcher.dispatchWithAccountLock(new CallableWithAccountLock<Void>(locker,
                                                                                           account.getExternalKey(),
                                                                                           new WithAccountLockCallback<Void>() {

                                                                                               @Override
                                                                                               public Void doOperation() throws PaymentApiException {

                                                                                                   final List<PaymentModelDao> payments = paymentDao.getPaymentsForAccount(account.getId(), context);
                                                                                                   final Collection<PaymentModelDao> paymentsToBeCompleted = Collections2.filter(payments, new Predicate<PaymentModelDao>() {
                                                                                                       @Override
                                                                                                       public boolean apply(final PaymentModelDao in) {
                                                                                                           // Payments left in AUTO_PAY_OFF or for which we did not retry enough
                                                                                                           return (in.getPaymentStatus() == PaymentStatus.AUTO_PAY_OFF ||
                                                                                                                   in.getPaymentStatus() == PaymentStatus.PAYMENT_FAILURE ||
                                                                                                                   in.getPaymentStatus() == PaymentStatus.PLUGIN_FAILURE ||
                                                                                                                   in.getPaymentStatus() == PaymentStatus.UNKNOWN);
                                                                                                       }
                                                                                                   });
                                                                                                   // Insert one retry event for each payment left in AUTO_PAY_OFF
                                                                                                   for (PaymentModelDao cur : paymentsToBeCompleted) {
                                                                                                       switch (cur.getPaymentStatus()) {
                                                                                                           case AUTO_PAY_OFF:
                                                                                                               autoPayoffRetryService.scheduleRetry(cur.getId(), clock.getUTCNow());
                                                                                                               break;
                                                                                                           case PAYMENT_FAILURE:
                                                                                                               scheduleRetryOnPaymentFailure(cur.getId(), context);
                                                                                                               break;
                                                                                                           case PLUGIN_FAILURE:
                                                                                                           case UNKNOWN:
                                                                                                               scheduleRetryOnPluginFailure(cur.getId(), context);
                                                                                                               break;
                                                                                                           default:
                                                                                                               // Impossible...
                                                                                                               throw new RuntimeException("Unexpected case " + cur.getPaymentStatus());
                                                                                                       }

                                                                                                   }
                                                                                                   return null;
                                                                                               }
                                                                                           }));
        } catch (TimeoutException e) {
            throw new PaymentApiException(ErrorCode.UNEXPECTED_ERROR, "Unexpected timeout for payment creation (AUTO_PAY_OFF)");
        }
    }

    public Payment createPayment(final Account account, final UUID invoiceId, @Nullable final BigDecimal inputAmount,
                                 final InternalCallContext context, final boolean isInstantPayment, final boolean isExternalPayment)
            throws PaymentApiException {
        // If this is an external payment, retrieve the external payment method first.
        // We need to do this without the lock, because getExternalPaymentProviderPlugin will acquire the lock
        // if it needs to create an external payment method (for the first external payment for that account).
        // We don't want to retrieve any other payment method here, because we need to validate the invoice amount first
        // (to avoid throwing an exception if there is nothing to pay).
        final PaymentPluginApi externalPaymentPlugin;
        if (isExternalPayment) {
            externalPaymentPlugin = paymentMethodProcessor.getExternalPaymentProviderPlugin(account, context);
        } else {
            externalPaymentPlugin = null;
        }

        try {
            return paymentPluginDispatcher.dispatchWithAccountLock(new CallableWithAccountLock<Payment>(locker,
                                                                                                        account.getExternalKey(),
                                                                                                        new WithAccountLockCallback<Payment>() {

                                                                                                            @Override
                                                                                                            public Payment doOperation() throws PaymentApiException {

                                                                                                                try {
                                                                                                                    // First, rebalance CBA and retrieve the latest version of the invoice
                                                                                                                    final Invoice invoice = rebalanceAndGetInvoice(account.getId(), invoiceId, context);
                                                                                                                    if (invoice == null || invoice.isMigrationInvoice()) {
                                                                                                                        log.error("Received invoice for payment that is a migration invoice - don't know how to handle those yet: {}", invoice);
                                                                                                                        return null;
                                                                                                                    }

                                                                                                                    // Second, validate the payment amount. We want to bail as early as possible if e.g. the balance is zero
                                                                                                                    final BigDecimal requestedAmount = getAndValidatePaymentAmount(invoice, inputAmount, isInstantPayment);

                                                                                                                    // Third, retrieve the payment method and associated plugin
                                                                                                                    final PaymentPluginApi plugin;
                                                                                                                    final UUID paymentMethodId;
                                                                                                                    try {
                                                                                                                        // Use the special external payment plugin to handle external payments
                                                                                                                        if (isExternalPayment) {
                                                                                                                            plugin = externalPaymentPlugin;
                                                                                                                            paymentMethodId = paymentMethodProcessor.getExternalPaymentMethod(account, context).getId();
                                                                                                                        } else {
                                                                                                                            plugin = getPaymentProviderPlugin(account, context);
                                                                                                                            paymentMethodId = account.getPaymentMethodId();
                                                                                                                        }
                                                                                                                    } catch (PaymentApiException e) {

                                                                                                                        // Insert a payment entry with one attempt in a terminal state to keep a record of the failure
                                                                                                                        processNewPaymentForMissingDefaultPaymentMethodWithAccountLocked(account, invoice, requestedAmount, context);

                                                                                                                        // This event will be caught by overdue to refresh the overdue state, if needed.
                                                                                                                        // Note that at this point, we don't know the exact invoice balance (see getAndValidatePaymentAmount() below).
                                                                                                                        // This means that events will be posted for null and zero dollar invoices (e.g. trials).
                                                                                                                        final PaymentErrorInternalEvent event = new DefaultPaymentErrorEvent(account.getId(), invoiceId, null,
                                                                                                                                                                                             ErrorCode.PAYMENT_NO_DEFAULT_PAYMENT_METHOD.toString(),
                                                                                                                                                                                             context.getAccountRecordId(), context.getTenantRecordId(),
                                                                                                                                                                                             context.getUserToken());
                                                                                                                        postPaymentEvent(event, account.getId(), context);
                                                                                                                        throw e;
                                                                                                                    }

                                                                                                                    final boolean isAccountAutoPayOff = isAccountAutoPayOff(account.getId(), context);
                                                                                                                    setUnsaneAccount_AUTO_PAY_OFFWithAccountLock(account.getId(), paymentMethodId, isAccountAutoPayOff, context, isInstantPayment);

                                                                                                                    if (!isInstantPayment && isAccountAutoPayOff) {
                                                                                                                        return processNewPaymentForAutoPayOffWithAccountLocked(paymentMethodId, account, invoice, requestedAmount, context);
                                                                                                                    } else {
                                                                                                                        return processNewPaymentWithAccountLocked(paymentMethodId, plugin, account, invoice, requestedAmount, isInstantPayment, context);
                                                                                                                    }
                                                                                                                } catch (InvoiceApiException e) {
                                                                                                                    throw new PaymentApiException(e);
                                                                                                                }
                                                                                                            }
                                                                                                        }));
        } catch (TimeoutException e) {
            if (isInstantPayment) {
                throw new PaymentApiException(ErrorCode.PAYMENT_PLUGIN_TIMEOUT, account.getId(), invoiceId);
            } else {
                log.warn(String.format("Payment from Account %s, Invoice %s timedout", account.getId(), invoiceId));
                // If we don't crash, plugin thread will complete (and set the correct status)
                // If we crash before plugin thread completes, we may end up with a UNKNOWN Payment
                // We would like to return an error so the Bus can retry but we are limited by Guava bug
                // swallowing exception
                return null;
            }
        } catch (RuntimeException e) {
            log.error("Failure when dispatching payment for invoice " + invoiceId, e);
            if (isInstantPayment) {
                throw new PaymentApiException(ErrorCode.PAYMENT_INTERNAL_ERROR, invoiceId);
            } else {
                return null;
            }
        }
    }

    public void notifyPendingPaymentOfStateChanged(final Account account, final UUID paymentId, final boolean isSuccess, final InternalCallContext context)
            throws PaymentApiException {

        new WithAccountLock<Void>().processAccountWithLock(locker, account.getExternalKey(), new WithAccountLockCallback<Void>() {

            @Override
            public Void doOperation() throws PaymentApiException {
                final PaymentModelDao payment = paymentDao.getPayment(paymentId, context);
                if (payment == null) {
                    throw new PaymentApiException(ErrorCode.PAYMENT_NO_SUCH_PAYMENT, paymentId);
                }
                if (payment.getPaymentStatus() != PaymentStatus.PENDING) {
                    throw new PaymentApiException(ErrorCode.PAYMENT_NOT_PENDING, paymentId);
                }

                final List<PaymentAttemptModelDao> attempts = paymentDao.getAttemptsForPayment(paymentId, context);
                final PaymentAttemptModelDao lastAttempt = attempts.get(attempts.size() - 1);
                final PaymentStatus newPaymentStatus = isSuccess ? PaymentStatus.SUCCESS : PaymentStatus.PAYMENT_FAILURE_ABORTED;
                paymentDao.updatePaymentAndAttemptOnCompletion(paymentId, newPaymentStatus, payment.getProcessedAmount(), payment.getProcessedCurrency(), lastAttempt.getId(), null, null, context);
                return null;
            }
        });
    }

    private void setUnsaneAccount_AUTO_PAY_OFFWithAccountLock(final UUID accountId, final UUID paymentMethodId, final boolean isAccountAutoPayOff,
                                                              final InternalCallContext context, final boolean isInstantPayment)
            throws PaymentApiException {

        final PaymentModelDao lastPaymentForPaymentMethod = paymentDao.getLastPaymentForPaymentMethod(accountId, paymentMethodId, context);
        final boolean isLastPaymentForPaymentMethodBad = lastPaymentForPaymentMethod != null &&
                                                         (lastPaymentForPaymentMethod.getPaymentStatus() == PaymentStatus.PLUGIN_FAILURE_ABORTED ||
                                                          lastPaymentForPaymentMethod.getPaymentStatus() == PaymentStatus.UNKNOWN);

        if (isLastPaymentForPaymentMethodBad &&
            !isInstantPayment &&
            !isAccountAutoPayOff) {
            log.warn(String.format("Setting account %s into AUTO_PAY_OFF because of bad payment %s", accountId, lastPaymentForPaymentMethod.getId()));
            setAccountAutoPayOff(accountId, context);
        }
    }

    private BigDecimal getAndValidatePaymentAmount(final Invoice invoice, @Nullable final BigDecimal inputAmount, final boolean isInstantPayment)
            throws PaymentApiException {

        if (invoice.getBalance().compareTo(BigDecimal.ZERO) <= 0) {
            throw new PaymentApiException(ErrorCode.PAYMENT_NULL_INVOICE, invoice.getId());
        }
        if (isInstantPayment &&
            inputAmount != null &&
            invoice.getBalance().compareTo(inputAmount) < 0) {
            throw new PaymentApiException(ErrorCode.PAYMENT_AMOUNT_DENIED,
                                          invoice.getId(), inputAmount.floatValue(), invoice.getBalance().floatValue());
        }
        final BigDecimal result = inputAmount != null ? inputAmount : invoice.getBalance();
        return result.setScale(2, RoundingMode.HALF_UP);
    }

    public void retryAutoPayOff(final UUID paymentId, final InternalCallContext context) {
        retryFailedPaymentInternal(paymentId, context, PaymentStatus.AUTO_PAY_OFF);
    }

    public void retryPluginFailure(final UUID paymentId, final InternalCallContext context) {

        retryFailedPaymentInternal(paymentId, context, PaymentStatus.PLUGIN_FAILURE);
    }

    public void retryFailedPayment(final UUID paymentId, final InternalCallContext context) {
        log.info("Retrying failed payment " + paymentId + " time = " + clock.getUTCNow());
        retryFailedPaymentInternal(paymentId, context, PaymentStatus.PAYMENT_FAILURE);
    }

    public void retryPaymentFromApi(final UUID paymentId, final InternalCallContext context) {
        log.info("Retrying payment " + paymentId + " time = " + clock.getUTCNow());
        retryFailedPaymentInternal(paymentId, context, PaymentStatus.UNKNOWN,
                                   PaymentStatus.AUTO_PAY_OFF,
                                   PaymentStatus.PAYMENT_FAILURE,
                                   PaymentStatus.PLUGIN_FAILURE);
    }

    private void retryFailedPaymentInternal(final UUID paymentId, final InternalCallContext context, final PaymentStatus... expectedPaymentStates) {

        try {

            final PaymentModelDao payment = paymentDao.getPayment(paymentId, context);
            if (payment == null) {
                log.error("Invalid retry for non existent paymentId {}", paymentId);
                return;
            }

            if (isAccountAutoPayOff(payment.getAccountId(), context)) {
                log.info(String.format("Skip retry payment %s in state %s because AUTO_PAY_OFF", payment.getId(), payment.getPaymentStatus()));
                return;
            }

            final Account account = accountInternalApi.getAccountById(payment.getAccountId(), context);
            final PaymentPluginApi plugin = getPaymentProviderPlugin(account, context);

            voidPluginDispatcher.dispatchWithAccountLock(new CallableWithAccountLock<Void>(locker,
                                                                                           account.getExternalKey(),
                                                                                           new WithAccountLockCallback<Void>() {

                                                                                               @Override
                                                                                               public Void doOperation() throws PaymentApiException {
                                                                                                   try {
                                                                                                       // Fetch again with account lock this time
                                                                                                       final PaymentModelDao payment = paymentDao.getPayment(paymentId, context);
                                                                                                       boolean foundExpectedState = false;
                                                                                                       for (final PaymentStatus cur : expectedPaymentStates) {
                                                                                                           if (payment.getPaymentStatus() == cur) {
                                                                                                               foundExpectedState = true;
                                                                                                               break;
                                                                                                           }
                                                                                                       }
                                                                                                       if (!foundExpectedState) {
                                                                                                           log.info("Aborted retry for payment {} because it is {} state", paymentId, payment.getPaymentStatus());
                                                                                                           return null;
                                                                                                       }

                                                                                                       final Invoice invoice = rebalanceAndGetInvoice(payment.getAccountId(), payment.getInvoiceId(), context);
                                                                                                       if (invoice == null || invoice.isMigrationInvoice()) {
                                                                                                           return null;
                                                                                                       }
                                                                                                       if (invoice.getBalance().compareTo(BigDecimal.ZERO) <= 0) {
                                                                                                           log.info("Aborted retry for payment {} because invoice has been paid", paymentId);
                                                                                                           setTerminalStateOnRetryWithAccountLocked(account, invoice, payment, invoice.getBalance(), "Paid invoice", context);
                                                                                                           return null;
                                                                                                       }
                                                                                                       processRetryPaymentWithAccountLocked(plugin, account, invoice, payment, invoice.getBalance(), context);
                                                                                                       return null;
                                                                                                   } catch (InvoiceApiException e) {
                                                                                                       throw new PaymentApiException(e);
                                                                                                   }
                                                                                               }
                                                                                           }));
        } catch (AccountApiException e) {
            log.error(String.format("Failed to retry payment for paymentId %s", paymentId), e);
        } catch (PaymentApiException e) {
            log.info(String.format("Failed to retry payment for paymentId %s", paymentId), e);
        } catch (TimeoutException e) {
            log.warn(String.format("Retry for payment %s timedout", paymentId));
            // STEPH we should throw some exception so NotificationQ does not clear status and retries us
        }
    }

    private Payment processNewPaymentForAutoPayOffWithAccountLocked(final UUID paymentMethodId, final Account account, final Invoice invoice,
                                                                    final BigDecimal requestedAmount, final InternalCallContext context)
            throws PaymentApiException {
        final PaymentStatus paymentStatus = PaymentStatus.AUTO_PAY_OFF;

        final PaymentModelDao paymentInfo = new PaymentModelDao(account.getId(), invoice.getId(), paymentMethodId, requestedAmount, invoice.getCurrency(), clock.getUTCNow(), paymentStatus);
        final PaymentAttemptModelDao attempt = new PaymentAttemptModelDao(account.getId(), invoice.getId(), paymentInfo.getId(), paymentMethodId, paymentStatus, clock.getUTCNow(),
                                                                          requestedAmount, invoice.getCurrency());

        paymentDao.insertPaymentWithFirstAttempt(paymentInfo, attempt, context);
        return fromPaymentModelDao(paymentInfo, null, context);
    }

    private Payment processNewPaymentForMissingDefaultPaymentMethodWithAccountLocked(final Account account, final Invoice invoice,
                                                                                     final BigDecimal requestedAmount, final InternalCallContext context)
            throws PaymentApiException {
        final PaymentStatus paymentStatus = PaymentStatus.PAYMENT_FAILURE_ABORTED;

        final PaymentModelDao paymentInfo = new PaymentModelDao(account.getId(), invoice.getId(), MISSING_PAYMENT_METHOD_ID, requestedAmount, invoice.getCurrency(), clock.getUTCNow(), paymentStatus);
        final PaymentAttemptModelDao attempt = new PaymentAttemptModelDao(account.getId(), invoice.getId(), paymentInfo.getId(), MISSING_PAYMENT_METHOD_ID, paymentStatus, clock.getUTCNow(),
                                                                          requestedAmount, invoice.getCurrency());

        paymentDao.insertPaymentWithFirstAttempt(paymentInfo, attempt, context);
        return fromPaymentModelDao(paymentInfo, null, context);
    }

    private Payment processNewPaymentWithAccountLocked(final UUID paymentMethodId, final PaymentPluginApi plugin, final Account account, final Invoice invoice,
                                                       final BigDecimal requestedAmount, final boolean isInstantPayment, final InternalCallContext context) throws PaymentApiException {
        final PaymentModelDao payment = new PaymentModelDao(account.getId(), invoice.getId(), paymentMethodId, requestedAmount.setScale(2, RoundingMode.HALF_UP), invoice.getCurrency(), clock.getUTCNow());
        final PaymentAttemptModelDao attempt = new PaymentAttemptModelDao(account.getId(), invoice.getId(), payment.getId(), paymentMethodId, clock.getUTCNow(),
                                                                          requestedAmount, invoice.getCurrency());

        final PaymentModelDao savedPayment = paymentDao.insertPaymentWithFirstAttempt(payment, attempt, context);
        return processPaymentWithAccountLocked(plugin, account, invoice, savedPayment, attempt, isInstantPayment, context);
    }

    private Payment setTerminalStateOnRetryWithAccountLocked(final Account account, final Invoice invoice, final PaymentModelDao payment, final BigDecimal requestedAmount, final String terminalStateReason, final InternalCallContext context) {

        final PaymentStatus paymentStatus;
        switch (payment.getPaymentStatus()) {
            case PAYMENT_FAILURE:
                paymentStatus = PaymentStatus.PAYMENT_FAILURE_ABORTED;
                break;

            case PLUGIN_FAILURE:
            case UNKNOWN:
                paymentStatus = PaymentStatus.PLUGIN_FAILURE_ABORTED;
                break;

            case AUTO_PAY_OFF:
            default:
                throw new IllegalStateException("Unexpected payment status for retry " + payment.getPaymentStatus());
        }
        final PaymentAttemptModelDao attempt = new PaymentAttemptModelDao(account.getId(), invoice.getId(), payment.getId(), account.getPaymentMethodId(), clock.getUTCNow(),
                                                                          requestedAmount, invoice.getCurrency());
        paymentDao.updatePaymentWithNewAttempt(payment.getId(), attempt, context);
        paymentDao.updatePaymentAndAttemptOnCompletion(payment.getId(), paymentStatus, requestedAmount, account.getCurrency(), attempt.getId(), null, terminalStateReason, context);

        final List<PaymentAttemptModelDao> allAttempts = paymentDao.getAttemptsForPayment(payment.getId(), context);
        return new DefaultPayment(payment, null, allAttempts, Collections.<RefundModelDao>emptyList());

    }

    private Payment processRetryPaymentWithAccountLocked(final PaymentPluginApi plugin, final Account account, final Invoice invoice, final PaymentModelDao payment,
                                                         final BigDecimal requestedAmount, final InternalCallContext context) throws PaymentApiException {
        final PaymentAttemptModelDao attempt = new PaymentAttemptModelDao(account.getId(), invoice.getId(), payment.getId(), account.getPaymentMethodId(), clock.getUTCNow(),
                                                                          requestedAmount, invoice.getCurrency());
        paymentDao.updatePaymentWithNewAttempt(payment.getId(), attempt, context);
        return processPaymentWithAccountLocked(plugin, account, invoice, payment, attempt, false, context);
    }

    private Payment processPaymentWithAccountLocked(final PaymentPluginApi plugin, final Account account, final Invoice invoice,
                                                    final PaymentModelDao paymentInput, final PaymentAttemptModelDao attemptInput, final boolean isInstantPayment, final InternalCallContext context)
            throws PaymentApiException {
        final UUID tenantId = nonEntityDao.retrieveIdFromObject(context.getTenantRecordId(), ObjectType.TENANT);

        List<PaymentAttemptModelDao> allAttempts = null;
        if (paymentConfig.isPaymentOff()) {
            paymentDao.updatePaymentAndAttemptOnCompletion(paymentInput.getId(), PaymentStatus.PAYMENT_SYSTEM_OFF,
                                                           attemptInput.getRequestedAmount(), account.getCurrency(), attemptInput.getId(), null, null, context);
            allAttempts = paymentDao.getAttemptsForPayment(paymentInput.getId(), context);
            return new DefaultPayment(paymentInput, null, allAttempts, Collections.<RefundModelDao>emptyList());
        }

        PaymentModelDao payment = null;
        BusInternalEvent event = null;
        PaymentStatus paymentStatus;
        final PaymentInfoPlugin paymentPluginInfo;
        try {
            try {
                paymentPluginInfo = plugin.processPayment(account.getId(), paymentInput.getId(), attemptInput.getPaymentMethodId(),
                                                          attemptInput.getRequestedAmount(), account.getCurrency(), context.toCallContext(tenantId));
            } catch (RuntimeException e) {
                // Handle case of plugin RuntimeException to be handled the same as a Plugin failure (PaymentPluginApiException)
                final String formatError = String.format("Plugin threw RuntimeException for payment %s", paymentInput.getId());
                throw new PaymentPluginApiException(formatError, e);
            }
            switch (paymentPluginInfo.getStatus()) {
                case PROCESSED:
                case PENDING:

                    // Update Payment/PaymentAttempt status
                    paymentStatus = paymentPluginInfo.getStatus() == PaymentPluginStatus.PROCESSED ? PaymentStatus.SUCCESS : PaymentStatus.PENDING;

                    // In case of success we are using the amount/currency as returned by the plugin-- if plugin decides to mess with amount and currency, we track it there
                    paymentDao.updatePaymentAndAttemptOnCompletion(paymentInput.getId(), paymentStatus, paymentPluginInfo.getAmount(), paymentPluginInfo.getCurrency(),
                                                                   attemptInput.getId(), paymentPluginInfo.getGatewayErrorCode(), null, context);

                    // Fetch latest objects
                    allAttempts = paymentDao.getAttemptsForPayment(paymentInput.getId(), context);

                    payment = paymentDao.getPayment(paymentInput.getId(), context);
                    // NOTE that we are not using the amount/currency as returned by the plugin but the requested amount/currency; if plugin decide to change the currency
                    // at the time of payment, we want to stay consistent with the currency on the account
                    invoiceApi.notifyOfPayment(invoice.getId(),
                                               payment.getAmount(),
                                               payment.getCurrency(),
                                               paymentPluginInfo.getCurrency(),
                                               payment.getId(),
                                               payment.getEffectiveDate(),
                                               context);

                    // Create Bus event
                    event = new DefaultPaymentInfoEvent(account.getId(),
                                                        invoice.getId(),
                                                        payment.getId(),
                                                        payment.getAmount(),
                                                        payment.getPaymentNumber(),
                                                        paymentStatus,
                                                        payment.getEffectiveDate(),
                                                        context.getAccountRecordId(),
                                                        context.getTenantRecordId(),
                                                        context.getUserToken());
                    break;

                case ERROR:
                    allAttempts = paymentDao.getAttemptsForPayment(paymentInput.getId(), context);
                    // Schedule if non instant payment and max attempt for retry not reached yet
                    if (!isInstantPayment) {
                        paymentStatus = scheduleRetryOnPaymentFailure(paymentInput.getId(), context);
                    } else {
                        paymentStatus = PaymentStatus.PAYMENT_FAILURE_ABORTED;
                    }

                    paymentDao.updatePaymentAndAttemptOnCompletion(paymentInput.getId(), paymentStatus, attemptInput.getRequestedAmount(), account.getCurrency(),
                                                                   attemptInput.getId(), paymentPluginInfo.getGatewayErrorCode(), paymentPluginInfo.getGatewayError(), context);

                    log.info(String.format("Could not process payment for account %s, invoice %s, error = %s",
                                           account.getId(), invoice.getId(), paymentPluginInfo.getGatewayError()));

                    event = new DefaultPaymentErrorEvent(account.getId(), invoice.getId(), paymentInput.getId(), paymentPluginInfo.getGatewayError(),
                                                         context.getAccountRecordId(), context.getTenantRecordId(), context.getUserToken()
                    );
                    throw new PaymentApiException(ErrorCode.PAYMENT_CREATE_PAYMENT, account.getId(), paymentPluginInfo.getGatewayError());

                default:
                    final String formatError = String.format("Plugin return status %s for payment %s", paymentPluginInfo.getStatus(), paymentInput.getId());
                    // This caught right below as a retryable Plugin failure
                    throw new PaymentPluginApiException("", formatError);
            }

        } catch (PaymentPluginApiException e) {
            //
            // An exception occurred, we are left in an unknown state, we need to schedule a retry
            //
            paymentStatus = isInstantPayment ? PaymentStatus.PAYMENT_FAILURE_ABORTED : scheduleRetryOnPluginFailure(paymentInput.getId(), context);
            // STEPH message might need truncation to fit??

            paymentDao.updatePaymentAndAttemptOnCompletion(paymentInput.getId(), paymentStatus, attemptInput.getRequestedAmount(), account.getCurrency(),
                                                           attemptInput.getId(), null, e.getMessage(), context);

            event = new DefaultPaymentPluginErrorEvent(account.getId(), invoice.getId(), paymentInput.getId(), e.getMessage(),
                                                       context.getAccountRecordId(), context.getTenantRecordId(), context.getUserToken()
            );
            throw new PaymentApiException(ErrorCode.PAYMENT_CREATE_PAYMENT, account.getId(), e.toString());

        } catch (InvoiceApiException e) {
            throw new PaymentApiException(ErrorCode.INVOICE_NOT_FOUND, invoice.getId(), e.toString());
        } finally {
            if (event != null) {
                postPaymentEvent(event, account.getId(), context);
            }
        }

        return new DefaultPayment(payment, paymentPluginInfo, allAttempts, Collections.<RefundModelDao>emptyList());
    }

    private PaymentStatus scheduleRetryOnPluginFailure(final UUID paymentId, final InternalTenantContext context) {
        final List<PaymentAttemptModelDao> allAttempts = paymentDao.getAttemptsForPayment(paymentId, context);
        final int retryAttempt = getNumberAttemptsInState(allAttempts, PaymentStatus.UNKNOWN, PaymentStatus.PLUGIN_FAILURE);
        final boolean isScheduledForRetry = pluginFailureRetryService.scheduleRetry(paymentId, retryAttempt);
        return isScheduledForRetry ? PaymentStatus.PLUGIN_FAILURE : PaymentStatus.PLUGIN_FAILURE_ABORTED;
    }

    private PaymentStatus scheduleRetryOnPaymentFailure(final UUID paymentId, final InternalTenantContext context) {
        final List<PaymentAttemptModelDao> allAttempts = paymentDao.getAttemptsForPayment(paymentId, context);
        final int retryAttempt = getNumberAttemptsInState(allAttempts,
                                                          PaymentStatus.UNKNOWN, PaymentStatus.PAYMENT_FAILURE);

        final boolean isScheduledForRetry = failedPaymentRetryService.scheduleRetry(paymentId, retryAttempt);

        log.debug("scheduleRetryOnPaymentFailure id = " + paymentId + ", retryAttempt = " + retryAttempt + ", retry :" + isScheduledForRetry);

        return isScheduledForRetry ? PaymentStatus.PAYMENT_FAILURE : PaymentStatus.PAYMENT_FAILURE_ABORTED;
    }

    private int getNumberAttemptsInState(final List<PaymentAttemptModelDao> allAttempts, final PaymentStatus... statuses) {
        if (allAttempts == null || allAttempts.size() == 0) {
            return 0;
        }
        return Collections2.filter(allAttempts, new Predicate<PaymentAttemptModelDao>() {
            @Override
            public boolean apply(final PaymentAttemptModelDao input) {
                for (final PaymentStatus cur : statuses) {
                    if (input.getProcessingStatus() == cur) {
                        return true;
                    }
                }
                return false;
            }
        }).size();
    }
}
