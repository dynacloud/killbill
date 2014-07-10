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

package com.ning.billing.overdue.applicator;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import javax.inject.Named;

import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.joda.time.Period;
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
import com.ning.billing.catalog.api.BillingActionPolicy;
import com.ning.billing.catalog.api.ProductCategory;
import com.ning.billing.clock.Clock;
import com.ning.billing.entitlement.api.BlockingApiException;
import com.ning.billing.entitlement.api.BlockingStateType;
import com.ning.billing.entitlement.api.Entitlement;
import com.ning.billing.entitlement.api.EntitlementApi;
import com.ning.billing.entitlement.api.EntitlementApiException;
import com.ning.billing.events.OverdueChangeInternalEvent;
import com.ning.billing.junction.BlockingInternalApi;
import com.ning.billing.junction.DefaultBlockingState;
import com.ning.billing.overdue.OverdueApiException;
import com.ning.billing.overdue.OverdueCancellationPolicy;
import com.ning.billing.overdue.OverdueService;
import com.ning.billing.overdue.OverdueState;
import com.ning.billing.overdue.config.api.BillingState;
import com.ning.billing.overdue.config.api.OverdueException;
import com.ning.billing.overdue.config.api.OverdueStateSet;
import com.ning.billing.overdue.glue.DefaultOverdueModule;
import com.ning.billing.overdue.notification.OverdueCheckNotificationKey;
import com.ning.billing.overdue.notification.OverdueCheckNotifier;
import com.ning.billing.overdue.notification.OverduePoster;
import com.ning.billing.tag.TagInternalApi;
import com.ning.billing.util.api.TagApiException;
import com.ning.billing.util.dao.NonEntityDao;
import com.ning.billing.util.email.DefaultEmailSender;
import com.ning.billing.util.email.EmailApiException;
import com.ning.billing.util.email.EmailConfig;
import com.ning.billing.util.email.EmailSender;
import com.ning.billing.util.tag.ControlTagType;
import com.ning.billing.util.tag.Tag;

import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import com.samskivert.mustache.MustacheException;

public class OverdueStateApplicator {

    private static final Logger log = LoggerFactory.getLogger(OverdueStateApplicator.class);

    private final BlockingInternalApi blockingApi;
    private final Clock clock;
    private final OverduePoster checkPoster;
    private final PersistentBus bus;
    private final AccountInternalApi accountApi;
    private final EntitlementApi entitlementApi;
    private final OverdueEmailGenerator overdueEmailGenerator;
    private final TagInternalApi tagApi;
    private final EmailSender emailSender;
    private final NonEntityDao nonEntityDao;

    @Inject
    public OverdueStateApplicator(final BlockingInternalApi accessApi,
                                  final AccountInternalApi accountApi,
                                  final EntitlementApi entitlementApi,
                                  final Clock clock,
                                  @Named(DefaultOverdueModule.OVERDUE_NOTIFIER_CHECK_NAMED) final OverduePoster checkPoster,
                                  final OverdueEmailGenerator overdueEmailGenerator,
                                  final EmailConfig config,
                                  final PersistentBus bus,
                                  final NonEntityDao nonEntityDao,
                                  final TagInternalApi tagApi) {

        this.blockingApi = accessApi;
        this.accountApi = accountApi;
        this.entitlementApi = entitlementApi;
        this.clock = clock;
        this.checkPoster = checkPoster;
        this.overdueEmailGenerator = overdueEmailGenerator;
        this.tagApi = tagApi;
        this.nonEntityDao = nonEntityDao;
        this.emailSender = new DefaultEmailSender(config);
        this.bus = bus;
    }

    public void apply(final OverdueStateSet overdueStateSet, final BillingState billingState,
                      final Account account, final OverdueState previousOverdueState,
                      final OverdueState nextOverdueState, final InternalCallContext context) throws OverdueException {
        try {

            if (isAccountTaggedWith_OVERDUE_ENFORCEMENT_OFF(context)) {
                log.debug("OverdueStateApplicator:apply returns because account (recordId = " + context.getAccountRecordId() + ") is set with OVERDUE_ENFORCEMENT_OFF ");
                return;
            }

            log.debug("OverdueStateApplicator:apply <enter> : time = " + clock.getUTCNow() + ", previousState = " + previousOverdueState.getName() + ", nextState = " + nextOverdueState);

            final OverdueState firstOverdueState = overdueStateSet.getFirstState();
            final boolean conditionForNextNotfication = !nextOverdueState.isClearState() ||
                                                        // We did not reach the first state yet but we have an unpaid invoice
                                                        (firstOverdueState != null && billingState != null && billingState.getDateOfEarliestUnpaidInvoice() != null);

            if (conditionForNextNotfication) {
                final Period reevaluationInterval = nextOverdueState.isClearState() ? overdueStateSet.getInitialReevaluationInterval() : nextOverdueState.getReevaluationInterval();
                // If there is no configuration in the config, we assume this is because the overdue conditions are not time based and so there is nothing to retry
                if (reevaluationInterval == null) {
                    log.debug("OverdueStateApplicator <notificationQ> : Missing InitialReevaluationInterval from config, NOT inserting notification for account " + account.getId());

                } else {
                    log.debug("OverdueStateApplicator <notificationQ> : inserting notification for account " + account.getId() + ", time = " + clock.getUTCNow().plus(reevaluationInterval));
                    createFutureNotification(account, clock.getUTCNow().plus(reevaluationInterval), context);
                }

            } else if (nextOverdueState.isClearState()) {
                clearFutureNotification(account, context);
            }

            if (previousOverdueState.getName().equals(nextOverdueState.getName())) {
                return;
            }

            storeNewState(account, nextOverdueState, context);

            cancelSubscriptionsIfRequired(account, nextOverdueState, context);

            sendEmailIfRequired(billingState, account, nextOverdueState, context);

            avoid_extra_credit_by_toggling_AUTO_INVOICE_OFF(account, previousOverdueState, nextOverdueState, context);

        } catch (OverdueApiException e) {
            if (e.getCode() != ErrorCode.OVERDUE_NO_REEVALUATION_INTERVAL.getCode()) {
                throw new OverdueException(e);
            }
        }
        try {
            bus.post(createOverdueEvent(account, previousOverdueState.getName(), nextOverdueState.getName(), isBlockBillingTransition(previousOverdueState, nextOverdueState),
                                        isUnblockBillingTransition(previousOverdueState, nextOverdueState), context));
        } catch (Exception e) {
            log.error("Error posting overdue change event to bus", e);
        }
    }

    private void avoid_extra_credit_by_toggling_AUTO_INVOICE_OFF(final Account account, final OverdueState previousOverdueState,
                                                                 final OverdueState nextOverdueState, final InternalCallContext context) throws OverdueApiException {
        if (isBlockBillingTransition(previousOverdueState, nextOverdueState)) {
            set_AUTO_INVOICE_OFF_on_blockedBilling(account.getId(), context);
        } else if (isUnblockBillingTransition(previousOverdueState, nextOverdueState)) {
            remove_AUTO_INVOICE_OFF_on_clear(account.getId(), context);
        }
    }

    public void clear(final Account account, final OverdueState previousOverdueState, final OverdueState clearState, final InternalCallContext context) throws OverdueException {

        log.debug("OverdueStateApplicator:clear : time = " + clock.getUTCNow() + ", previousState = " + previousOverdueState.getName());

        storeNewState(account, clearState, context);

        clearFutureNotification(account, context);

        try {
            avoid_extra_credit_by_toggling_AUTO_INVOICE_OFF(account, previousOverdueState, clearState, context);
        } catch (OverdueApiException e) {
            throw new OverdueException(e);
        }

        try {
            bus.post(createOverdueEvent(account, previousOverdueState.getName(), clearState.getName(), isBlockBillingTransition(previousOverdueState, clearState),
                                        isUnblockBillingTransition(previousOverdueState, clearState), context));
        } catch (Exception e) {
            log.error("Error posting overdue change event to bus", e);
        }
    }

    private OverdueChangeInternalEvent createOverdueEvent(final Account overdueable, final String previousOverdueStateName, final String nextOverdueStateName,
                                                          final boolean isBlockedBilling, final boolean isUnblockedBilling, final InternalCallContext context) throws BlockingApiException {
        return new DefaultOverdueChangeEvent(overdueable.getId(), previousOverdueStateName, nextOverdueStateName, isBlockedBilling, isUnblockedBilling,
                                             context.getAccountRecordId(), context.getTenantRecordId(), context.getUserToken());
    }

    protected void storeNewState(final Account blockable, final OverdueState nextOverdueState, final InternalCallContext context) throws OverdueException {
        try {
            blockingApi.setBlockingState(new DefaultBlockingState(blockable.getId(),
                                                                  BlockingStateType.ACCOUNT,
                                                                  nextOverdueState.getName(),
                                                                  OverdueService.OVERDUE_SERVICE_NAME,
                                                                  blockChanges(nextOverdueState),
                                                                  blockEntitlement(nextOverdueState),
                                                                  blockBilling(nextOverdueState),
                                                                  clock.getUTCNow()),
                                         context);
        } catch (Exception e) {
            throw new OverdueException(e, ErrorCode.OVERDUE_CAT_ERROR_ENCOUNTERED, blockable.getId(), blockable.getClass().getName());
        }
    }

    private void set_AUTO_INVOICE_OFF_on_blockedBilling(final UUID accountId, final InternalCallContext context) throws OverdueApiException {
        try {
            tagApi.addTag(accountId, ObjectType.ACCOUNT, ControlTagType.AUTO_INVOICING_OFF.getId(), context);
        } catch (TagApiException e) {
            throw new OverdueApiException(e);
        }
    }

    private void remove_AUTO_INVOICE_OFF_on_clear(final UUID accountId, final InternalCallContext context) throws OverdueApiException {
        try {
            tagApi.removeTag(accountId, ObjectType.ACCOUNT, ControlTagType.AUTO_INVOICING_OFF.getId(), context);
        } catch (TagApiException e) {
            if (e.getCode() != ErrorCode.TAG_DOES_NOT_EXIST.getCode()) {
                throw new OverdueApiException(e);
            }
        }
    }

    private boolean isBlockBillingTransition(final OverdueState prevOverdueState, final OverdueState nextOverdueState) {
        return !blockBilling(prevOverdueState) && blockBilling(nextOverdueState);
    }

    private boolean isUnblockBillingTransition(final OverdueState prevOverdueState, final OverdueState nextOverdueState) {
        return blockBilling(prevOverdueState) && !blockBilling(nextOverdueState);
    }

    private boolean blockChanges(final OverdueState nextOverdueState) {
        return nextOverdueState.blockChanges();
    }

    private boolean blockBilling(final OverdueState nextOverdueState) {
        return nextOverdueState.disableEntitlementAndChangesBlocked();
    }

    private boolean blockEntitlement(final OverdueState nextOverdueState) {
        return nextOverdueState.disableEntitlementAndChangesBlocked();
    }

    protected void createFutureNotification(final Account account, final DateTime timeOfNextCheck, final InternalCallContext context) {
        final OverdueCheckNotificationKey notificationKey = new OverdueCheckNotificationKey(account.getId());
        checkPoster.insertOverdueNotification(account.getId(), timeOfNextCheck, OverdueCheckNotifier.OVERDUE_CHECK_NOTIFIER_QUEUE, notificationKey, context);
    }

    protected void clearFutureNotification(final Account account, final InternalCallContext context) {
        // Need to clear the override table here too (when we add it)
        checkPoster.clearOverdueCheckNotifications(account.getId(), OverdueCheckNotifier.OVERDUE_CHECK_NOTIFIER_QUEUE, OverdueCheckNotificationKey.class, context);
    }

    private void cancelSubscriptionsIfRequired(final Account account, final OverdueState nextOverdueState, final InternalCallContext context) throws OverdueException {
        if (nextOverdueState.getSubscriptionCancellationPolicy() == OverdueCancellationPolicy.NONE) {
            return;
        }
        try {
            final BillingActionPolicy actionPolicy;
            switch (nextOverdueState.getSubscriptionCancellationPolicy()) {
                case END_OF_TERM:
                    actionPolicy = BillingActionPolicy.END_OF_TERM;
                    break;
                case IMMEDIATE:
                    actionPolicy = BillingActionPolicy.IMMEDIATE;
                    break;
                default:
                    throw new IllegalStateException("Unexpected OverdueCancellationPolicy " + nextOverdueState.getSubscriptionCancellationPolicy());
            }
            final List<Entitlement> toBeCancelled = new LinkedList<Entitlement>();
            computeEntitlementsToCancel(account, toBeCancelled, context);

            final UUID tenantId = nonEntityDao.retrieveIdFromObject(context.getTenantRecordId(), ObjectType.TENANT);
            for (final Entitlement cur : toBeCancelled) {
                try {
                    cur.cancelEntitlementWithDateOverrideBillingPolicy(new LocalDate(clock.getUTCNow(), account.getTimeZone()), actionPolicy, context.toCallContext(tenantId));
                } catch (EntitlementApiException e) {
                    // If subscription has already been cancelled, there is nothing to do so we can ignore
                    if (e.getCode() != ErrorCode.SUB_CANCEL_BAD_STATE.getCode()) {
                        throw new OverdueException(e);
                    }
                }
            }
        } catch (EntitlementApiException e) {
            throw new OverdueException(e);
        }
    }

    private void computeEntitlementsToCancel(final Account account, final List<Entitlement> result, final InternalTenantContext context) throws EntitlementApiException {
        final UUID tenantId = nonEntityDao.retrieveIdFromObject(context.getTenantRecordId(), ObjectType.TENANT);
        final List<Entitlement> allEntitlementsForAccountId = entitlementApi.getAllEntitlementsForAccountId(account.getId(), context.toTenantContext(tenantId));
        // Entitlement is smart enough and will cancel the associated add-ons. See also discussion in https://github.com/killbill/killbill/issues/94
        final Collection<Entitlement> allEntitlementsButAddonsForAccountId = Collections2.<Entitlement>filter(allEntitlementsForAccountId,
                                                                                                              new Predicate<Entitlement>() {
                                                                                                                  @Override
                                                                                                                  public boolean apply(final Entitlement entitlement) {
                                                                                                                      return !ProductCategory.ADD_ON.equals(entitlement.getLastActiveProductCategory());
                                                                                                                  }
                                                                                                              });
        result.addAll(allEntitlementsButAddonsForAccountId);
    }

    private void sendEmailIfRequired(final BillingState billingState, final Account account,
                                     final OverdueState nextOverdueState, final InternalTenantContext context) {
        // Note: we don't want to fail the full refresh call because sending the email failed.
        // That's the reason why we catch all exceptions here.
        // The alternative would be to: throw new OverdueApiException(e, ErrorCode.EMAIL_SENDING_FAILED);

        // If sending is not configured, skip
        if (nextOverdueState.getEnterStateEmailNotification() == null) {
            return;
        }

        final List<String> to = ImmutableList.<String>of(account.getEmail());
        // TODO - should we look at the account CC: list?
        final List<String> cc = ImmutableList.<String>of();
        final String subject = nextOverdueState.getEnterStateEmailNotification().getSubject();

        try {
            // Generate and send the email
            final String emailBody = overdueEmailGenerator.generateEmail(account, billingState, account, nextOverdueState);
            if (nextOverdueState.getEnterStateEmailNotification().isHTML()) {
                emailSender.sendHTMLEmail(to, cc, subject, emailBody);
            } else {
                emailSender.sendPlainTextEmail(to, cc, subject, emailBody);
            }
        } catch (IOException e) {
            log.warn(String.format("Unable to generate or send overdue notification email for account %s and overdueable %s", account.getId(), account.getId()), e);
        } catch (EmailApiException e) {
            log.warn(String.format("Unable to send overdue notification email for account %s and overdueable %s", account.getId(), account.getId()), e);
        } catch (MustacheException e) {
            log.warn(String.format("Unable to generate overdue notification email for account %s and overdueable %s", account.getId(), account.getId()), e);
        }
    }

    //
    // Uses callcontext information to retrieve account matching the Overduable object and check whether we should do any overdue processing
    //
    private boolean isAccountTaggedWith_OVERDUE_ENFORCEMENT_OFF(final InternalCallContext context) throws OverdueException {

        try {
            final UUID accountId = accountApi.getByRecordId(context.getAccountRecordId(), context);

            final List<Tag> accountTags = tagApi.getTags(accountId, ObjectType.ACCOUNT, context);
            for (Tag cur : accountTags) {
                if (cur.getTagDefinitionId().equals(ControlTagType.OVERDUE_ENFORCEMENT_OFF.getId())) {
                    return true;
                }
            }
            return false;
        } catch (AccountApiException e) {
            throw new OverdueException(e);
        }
    }
}
