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

package com.ning.billing.jaxrs;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.ning.billing.client.model.Account;
import com.ning.billing.client.model.AccountTimeline;
import com.ning.billing.client.model.AuditLog;
import com.ning.billing.client.model.Chargeback;
import com.ning.billing.client.model.Credit;
import com.ning.billing.client.model.EventSubscription;
import com.ning.billing.client.model.Invoice;
import com.ning.billing.client.model.Payment;
import com.ning.billing.client.model.Refund;
import com.ning.billing.util.api.AuditLevel;
import com.ning.billing.util.audit.ChangeType;

public class TestAccountTimeline extends TestJaxrsBase {

    private static final String PAYMENT_REQUEST_PROCESSOR = "PaymentRequestProcessor";
    private static final String TRANSITION = "SubscriptionBaseTransition";

    @Test(groups = "slow", description = "Can retrieve the timeline without audits")
    public void testAccountTimeline() throws Exception {
        clock.setTime(new DateTime(2012, 4, 25, 0, 3, 42, 0));

        final Account accountJson = createAccountWithPMBundleAndSubscriptionAndWaitForFirstInvoice();

        final AccountTimeline timeline = killBillClient.getAccountTimeline(accountJson.getAccountId());
        Assert.assertEquals(timeline.getPayments().size(), 1);
        Assert.assertEquals(timeline.getInvoices().size(), 2);
        Assert.assertEquals(timeline.getBundles().size(), 1);
        Assert.assertEquals(timeline.getBundles().get(0).getSubscriptions().size(), 1);
        Assert.assertEquals(timeline.getBundles().get(0).getSubscriptions().get(0).getEvents().size(), 3);
        final List<EventSubscription> events = timeline.getBundles().get(0).getSubscriptions().get(0).getEvents();
        Assert.assertEquals(events.get(0).getEffectiveDate(), new LocalDate(2012, 4, 25));
        Assert.assertEquals(events.get(0).getEventType(), "START_ENTITLEMENT");
        Assert.assertEquals(events.get(1).getEffectiveDate(), new LocalDate(2012, 4, 25));
        Assert.assertEquals(events.get(1).getEventType(), "START_BILLING");
        Assert.assertEquals(events.get(2).getEffectiveDate(), new LocalDate(2012, 5, 25));
        Assert.assertEquals(events.get(2).getEventType(), "PHASE");
    }

    @Test(groups = "slow", description = "Can retrieve the timeline with audits")
    public void testAccountTimelineWithAudits() throws Exception {
        final DateTime startTime = clock.getUTCNow();
        final Account accountJson = createAccountWithPMBundleAndSubscriptionAndWaitForFirstInvoice();
        final DateTime endTime = clock.getUTCNow();

        // Add credit
        final Invoice invoice = killBillClient.getInvoicesForAccount(accountJson.getAccountId()).get(1);
        final BigDecimal creditAmount = BigDecimal.ONE;
        final Credit credit = new Credit();
        credit.setAccountId(accountJson.getAccountId());
        credit.setInvoiceId(invoice.getInvoiceId());
        credit.setCreditAmount(creditAmount);
        killBillClient.createCredit(credit, createdBy, reason, comment);

        // Add refund
        final Payment postedPayment = killBillClient.getPaymentsForAccount(accountJson.getAccountId()).get(0);
        final BigDecimal refundAmount = BigDecimal.ONE;
        final Refund refund = new Refund();
        refund.setPaymentId(postedPayment.getPaymentId());
        refund.setAmount(refundAmount);
        killBillClient.createRefund(refund, createdBy, reason, comment);

        // Add chargeback
        final BigDecimal chargebackAmount = BigDecimal.ONE;
        final Chargeback chargeback = new Chargeback();
        chargeback.setPaymentId(postedPayment.getPaymentId());
        chargeback.setAmount(chargebackAmount);
        killBillClient.createChargeBack(chargeback, createdBy, reason, comment);

        // Verify payments
        verifyPayments(accountJson.getAccountId(), startTime, endTime, refundAmount, chargebackAmount);

        // Verify invoices
        verifyInvoices(accountJson.getAccountId(), startTime, endTime);

        // Verify credits
        verifyCredits(accountJson.getAccountId(), startTime, endTime, creditAmount);

        // Verify bundles
        verifyBundles(accountJson.getAccountId(), startTime, endTime);
    }

    private void verifyPayments(final UUID accountId, final DateTime startTime, final DateTime endTime,
                                final BigDecimal refundAmount, final BigDecimal chargebackAmount) throws Exception {
        for (final AuditLevel auditLevel : AuditLevel.values()) {
            final AccountTimeline timeline = killBillClient.getAccountTimeline(accountId, auditLevel);

            // Verify payments
            Assert.assertEquals(timeline.getPayments().size(), 1);
            final Payment paymentJson = timeline.getPayments().get(0);

            // Verify refunds
            Assert.assertEquals(paymentJson.getRefunds().size(), 1);
            final Refund refundJson = paymentJson.getRefunds().get(0);
            Assert.assertEquals(refundJson.getPaymentId(), paymentJson.getPaymentId());
            Assert.assertEquals(refundJson.getAmount().compareTo(refundAmount), 0);

            // Verify chargebacks
            Assert.assertEquals(paymentJson.getChargebacks().size(), 1);
            final Chargeback chargebackJson = paymentJson.getChargebacks().get(0);
            Assert.assertEquals(chargebackJson.getPaymentId(), paymentJson.getPaymentId());
            Assert.assertEquals(chargebackJson.getAmount().compareTo(chargebackAmount), 0);

            // Verify audits
            final List<AuditLog> paymentAuditLogs = paymentJson.getAuditLogs();
            final List<AuditLog> refundAuditLogs = refundJson.getAuditLogs();
            final List<AuditLog> chargebackAuditLogs = chargebackJson.getAuditLogs();
            if (AuditLevel.NONE.equals(auditLevel)) {
                // Audits for payments
                Assert.assertEquals(paymentAuditLogs.size(), 0);

                // Audits for refunds
                Assert.assertEquals(refundAuditLogs.size(), 0);

                // Audits for chargebacks
                Assert.assertEquals(chargebackAuditLogs.size(), 0);
            } else if (AuditLevel.MINIMAL.equals(auditLevel)) {
                // Audits for payments
                Assert.assertEquals(paymentAuditLogs.size(), 1);
                verifyAuditLog(paymentAuditLogs.get(0), ChangeType.INSERT, null, null, PAYMENT_REQUEST_PROCESSOR, startTime, endTime);

                // Audits for refunds
                Assert.assertEquals(refundAuditLogs.size(), 1);
                verifyAuditLog(refundAuditLogs.get(0), ChangeType.INSERT, reason, comment, createdBy, startTime, endTime);

                // Audits for chargebacks
                Assert.assertEquals(chargebackAuditLogs.size(), 1);
                verifyAuditLog(chargebackAuditLogs.get(0), ChangeType.INSERT, reason, comment, createdBy, startTime, endTime);
            } else {
                // Audits for payments
                Assert.assertEquals(paymentAuditLogs.size(), 2);
                verifyAuditLog(paymentAuditLogs.get(0), ChangeType.INSERT, null, null, PAYMENT_REQUEST_PROCESSOR, startTime, endTime);
                verifyAuditLog(paymentAuditLogs.get(1), ChangeType.UPDATE, null, null, PAYMENT_REQUEST_PROCESSOR, startTime, endTime);

                // Audits for refunds
                Assert.assertEquals(refundAuditLogs.size(), 3);
                verifyAuditLog(refundAuditLogs.get(0), ChangeType.INSERT, reason, comment, createdBy, startTime, endTime);
                verifyAuditLog(refundAuditLogs.get(1), ChangeType.UPDATE, reason, comment, createdBy, startTime, endTime);
                verifyAuditLog(refundAuditLogs.get(2), ChangeType.UPDATE, reason, comment, createdBy, startTime, endTime);

                // Audits for chargebacks
                Assert.assertEquals(chargebackAuditLogs.size(), 1);
                verifyAuditLog(chargebackAuditLogs.get(0), ChangeType.INSERT, reason, comment, createdBy, startTime, endTime);
            }
        }
    }

    private void verifyInvoices(final UUID accountId, final DateTime startTime, final DateTime endTime) throws Exception {
        for (final AuditLevel auditLevel : AuditLevel.values()) {
            final AccountTimeline timeline = killBillClient.getAccountTimeline(accountId, auditLevel);

            // Verify invoices
            Assert.assertEquals(timeline.getInvoices().size(), 2);

            // Verify audits
            final List<AuditLog> firstInvoiceAuditLogs = timeline.getInvoices().get(0).getAuditLogs();
            final List<AuditLog> secondInvoiceAuditLogs = timeline.getInvoices().get(1).getAuditLogs();
            if (AuditLevel.NONE.equals(auditLevel)) {
                Assert.assertEquals(firstInvoiceAuditLogs.size(), 0);
                Assert.assertEquals(secondInvoiceAuditLogs.size(), 0);
            } else {
                Assert.assertEquals(firstInvoiceAuditLogs.size(), 1);
                verifyAuditLog(firstInvoiceAuditLogs.get(0), ChangeType.INSERT, null, null, TRANSITION, startTime, endTime);
                Assert.assertEquals(secondInvoiceAuditLogs.size(), 1);
                verifyAuditLog(secondInvoiceAuditLogs.get(0), ChangeType.INSERT, null, null, TRANSITION, startTime, endTime);
            }
        }
    }

    private void verifyCredits(final UUID accountId, final DateTime startTime, final DateTime endTime, final BigDecimal creditAmount) throws Exception {
        for (final AuditLevel auditLevel : AuditLevel.values()) {
            final AccountTimeline timeline = killBillClient.getAccountTimeline(accountId, auditLevel);

            // Verify credits
            final List<Credit> credits = timeline.getInvoices().get(1).getCredits();
            Assert.assertEquals(credits.size(), 1);
            Assert.assertEquals(credits.get(0).getCreditAmount().compareTo(creditAmount.negate()), 0);

            // Verify audits
            final List<AuditLog> creditAuditLogs = credits.get(0).getAuditLogs();
            if (AuditLevel.NONE.equals(auditLevel)) {
                Assert.assertEquals(creditAuditLogs.size(), 0);
            } else {
                Assert.assertEquals(creditAuditLogs.size(), 1);
                verifyAuditLog(creditAuditLogs.get(0), ChangeType.INSERT, reason, comment, createdBy, startTime, endTime);
            }
        }
    }

    private void verifyBundles(final UUID accountId, final DateTime startTime, final DateTime endTime) throws Exception {
        for (final AuditLevel auditLevel : AuditLevel.values()) {
            final AccountTimeline timeline = killBillClient.getAccountTimeline(accountId, auditLevel);

            // Verify bundles
            Assert.assertEquals(timeline.getBundles().size(), 1);
            Assert.assertEquals(timeline.getBundles().get(0).getSubscriptions().size(), 1);
            Assert.assertEquals(timeline.getBundles().get(0).getSubscriptions().get(0).getEvents().size(), 3);

            // Verify audits
            final List<AuditLog> bundleAuditLogs = timeline.getBundles().get(0).getAuditLogs();
            final List<AuditLog> subscriptionAuditLogs = timeline.getBundles().get(0).getSubscriptions().get(0).getAuditLogs();
            final List<AuditLog> subscriptionEvent1AuditLogs = timeline.getBundles().get(0).getSubscriptions().get(0).getEvents().get(0).getAuditLogs();
            final List<AuditLog> subscriptionEvent2AuditLogs = timeline.getBundles().get(0).getSubscriptions().get(0).getEvents().get(1).getAuditLogs();
            if (AuditLevel.NONE.equals(auditLevel)) {
                // Audits for bundles
                Assert.assertEquals(bundleAuditLogs.size(), 0);

                // Audits for subscriptions
                Assert.assertEquals(subscriptionAuditLogs.size(), 0);

                // Audit for subscription events
                Assert.assertEquals(subscriptionEvent1AuditLogs.size(), 0);
                Assert.assertEquals(subscriptionEvent2AuditLogs.size(), 0);
            } else if (AuditLevel.MINIMAL.equals(auditLevel)) {
                // Audits for bundles
                Assert.assertEquals(bundleAuditLogs.size(), 1);
                verifyAuditLog(bundleAuditLogs.get(0), ChangeType.INSERT, reason, comment, createdBy, startTime, endTime);

                // Audits for subscriptions
                Assert.assertEquals(subscriptionAuditLogs.size(), 1);
                verifyAuditLog(subscriptionAuditLogs.get(0), ChangeType.INSERT, reason, comment, createdBy, startTime, endTime);

                // Audit for subscription events
                Assert.assertEquals(subscriptionEvent1AuditLogs.size(), 1);
                verifyAuditLog(subscriptionEvent1AuditLogs.get(0), ChangeType.INSERT, reason, comment, createdBy, startTime, endTime);
                Assert.assertEquals(subscriptionEvent2AuditLogs.size(), 1);
                verifyAuditLog(subscriptionEvent2AuditLogs.get(0), ChangeType.INSERT, reason, comment, createdBy, startTime, endTime);
            } else {
                // Audits for bundles
                Assert.assertEquals(bundleAuditLogs.size(), 3);
                verifyAuditLog(bundleAuditLogs.get(0), ChangeType.INSERT, reason, comment, createdBy, startTime, endTime);
                verifyAuditLog(bundleAuditLogs.get(1), ChangeType.UPDATE, null, null, TRANSITION, startTime, endTime);
                verifyAuditLog(bundleAuditLogs.get(2), ChangeType.UPDATE, null, null, TRANSITION, startTime, endTime);

                // Audits for subscriptions
                Assert.assertEquals(subscriptionAuditLogs.size(), 3);
                verifyAuditLog(subscriptionAuditLogs.get(0), ChangeType.INSERT, reason, comment, createdBy, startTime, endTime);
                verifyAuditLog(subscriptionAuditLogs.get(1), ChangeType.UPDATE, null, null, TRANSITION, startTime, endTime);
                verifyAuditLog(subscriptionAuditLogs.get(2), ChangeType.UPDATE, null, null, TRANSITION, startTime, endTime);

                // Audit for subscription events
                Assert.assertEquals(subscriptionEvent1AuditLogs.size(), 1);
                verifyAuditLog(subscriptionEvent1AuditLogs.get(0), ChangeType.INSERT, reason, comment, createdBy, startTime, endTime);
                Assert.assertEquals(subscriptionEvent2AuditLogs.size(), 1);
                verifyAuditLog(subscriptionEvent2AuditLogs.get(0), ChangeType.INSERT, reason, comment, createdBy, startTime, endTime);
            }
        }
    }

    private void verifyAuditLog(final AuditLog auditLogJson, final ChangeType changeType, @Nullable final String reasonCode,
                                @Nullable final String comments, @Nullable final String changedBy,
                                final DateTime startTime, final DateTime endTime) {
        Assert.assertEquals(auditLogJson.getChangeType(), changeType.toString());
        Assert.assertFalse(auditLogJson.getChangeDate().isBefore(startTime));
        // Flaky
        //Assert.assertFalse(auditLogJson.getChangeDate().isAfter(endTime));
        Assert.assertEquals(auditLogJson.getReasonCode(), reasonCode);
        Assert.assertEquals(auditLogJson.getComments(), comments);
        Assert.assertEquals(auditLogJson.getChangedBy(), changedBy);
    }
}
