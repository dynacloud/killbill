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

package com.ning.billing.beatrix.integration;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;

import org.joda.time.DateTimeZone;
import org.joda.time.LocalDate;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import com.ning.billing.ErrorCode;
import com.ning.billing.account.api.Account;
import com.ning.billing.api.TestApiListener.NextEvent;
import com.ning.billing.beatrix.util.InvoiceChecker.ExpectedInvoiceItemCheck;
import com.ning.billing.beatrix.util.PaymentChecker.ExpectedPaymentCheck;
import com.ning.billing.catalog.api.BillingActionPolicy;
import com.ning.billing.catalog.api.BillingPeriod;
import com.ning.billing.catalog.api.Currency;
import com.ning.billing.catalog.api.PriceListSet;
import com.ning.billing.catalog.api.ProductCategory;
import com.ning.billing.entitlement.api.DefaultEntitlement;
import com.ning.billing.entitlement.api.Entitlement.EntitlementActionPolicy;
import com.ning.billing.invoice.api.Invoice;
import com.ning.billing.invoice.api.InvoiceApiException;
import com.ning.billing.invoice.api.InvoiceItemType;
import com.ning.billing.invoice.generator.DefaultInvoiceGeneratorWithSwitchRepairLogic;
import com.ning.billing.invoice.generator.DefaultInvoiceGeneratorWithSwitchRepairLogic.REPAIR_INVOICE_LOGIC;
import com.ning.billing.payment.api.Payment;
import com.ning.billing.payment.api.PaymentStatus;

import com.google.common.collect.ImmutableList;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

public class TestIntegrationInvoiceWithRepairLogic extends TestIntegrationBase {

    @Inject
    private DefaultInvoiceGeneratorWithSwitchRepairLogic invoiceGenerator;

    @AfterMethod(groups = "slow")
    public void afterMethod() throws Exception {
        super.afterMethod();
        // Make sure to reset to default invoice logic so subsequent test will pass
        invoiceGenerator.setDefaultRepairLogic(REPAIR_INVOICE_LOGIC.PARTIAL_REPAIR);
    }

    @Test(groups = "slow")
    public void testSimplePartialRepairWithItemAdjustment() throws Exception {
        // We take april as it has 30 days (easier to play with BCD)
        final LocalDate today = new LocalDate(2012, 4, 1);
        final Account account = createAccountWithNonOsgiPaymentMethod(getAccountData(1));

        // Set clock to the initial start date - we implicitly assume here that the account timezone is UTC
        clock.setDay(today);

        final String productName = "Shotgun";
        final BillingPeriod term = BillingPeriod.MONTHLY;
        final String pricelistName = PriceListSet.DEFAULT_PRICELIST_NAME;

        //
        // CREATE SUBSCRIPTION AND EXPECT BOTH EVENTS: NextEvent.CREATE NextEvent.INVOICE
        //

        final DefaultEntitlement bpEntitlement = createBaseEntitlementAndCheckForCompletion(account.getId(), "externalKey", productName, ProductCategory.BASE, term, NextEvent.CREATE, NextEvent.INVOICE);
        assertNotNull(bpEntitlement);

        List<Invoice> invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), callContext);
        assertEquals(invoices.size(), 1);
        ImmutableList<ExpectedInvoiceItemCheck> toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), null, InvoiceItemType.FIXED, BigDecimal.ZERO));
        invoiceChecker.checkInvoice(invoices.get(0).getId(), callContext, toBeChecked);

        //
        // Check we get the first invoice at the phase event
        //
        busHandler.pushExpectedEvents(NextEvent.PHASE, NextEvent.INVOICE, NextEvent.PAYMENT);
        // Move the clock to 2012-05-02
        clock.addDays(31);
        assertListenerStatus();

        invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), callContext);
        assertEquals(invoices.size(), 2);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), null, InvoiceItemType.FIXED, BigDecimal.ZERO));
        invoiceChecker.checkInvoice(invoices.get(0).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("249.95")));
        invoiceChecker.checkInvoice(invoices.get(1).getId(), callContext, toBeChecked);

        //
        // Adjust the recurring item
        //
        busHandler.pushExpectedEvents(NextEvent.INVOICE_ADJUSTMENT);
        invoiceUserApi.insertInvoiceItemAdjustment(account.getId(), invoices.get(1).getId(), invoices.get(1).getInvoiceItems().get(0).getId(), clock.getUTCToday(),
                                                   BigDecimal.TEN, account.getCurrency(), callContext);
        assertListenerStatus();

        invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), callContext);
        assertEquals(invoices.size(), 2);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), null, InvoiceItemType.FIXED, BigDecimal.ZERO));
        invoiceChecker.checkInvoice(invoices.get(0).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("249.95")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 2), new LocalDate(2012, 5, 2), InvoiceItemType.ITEM_ADJ, new BigDecimal("-10")),
                // TODO PIERRE The cba start_date/end_date are created using the callcontext
                new ExpectedInvoiceItemCheck(callContext.getCreatedDate().toLocalDate(), callContext.getCreatedDate().toLocalDate(), InvoiceItemType.CBA_ADJ, new BigDecimal("10")));
        invoiceChecker.checkInvoice(invoices.get(1).getId(), callContext, toBeChecked);

        //
        // Force a plan change
        //
        changeEntitlementAndCheckForCompletion(bpEntitlement, "Blowdart", term, BillingActionPolicy.IMMEDIATE, NextEvent.CHANGE, NextEvent.INVOICE, NextEvent.INVOICE_ADJUSTMENT);

        invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), callContext);
        assertEquals(invoices.size(), 3);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), null, InvoiceItemType.FIXED, BigDecimal.ZERO));
        invoiceChecker.checkInvoice(invoices.get(0).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("249.95")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 2), new LocalDate(2012, 5, 2), InvoiceItemType.ITEM_ADJ, new BigDecimal("-10")),
                // TODO PIERRE The cba start_date/end_date are created using the callcontext
                new ExpectedInvoiceItemCheck(callContext.getCreatedDate().toLocalDate(), callContext.getCreatedDate().toLocalDate(), InvoiceItemType.CBA_ADJ, new BigDecimal("10")),
                // You can check here that 239.95 - 249.95/31 = 231.88
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 2), new LocalDate(2012, 6, 1), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-231.88")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 2), new LocalDate(2012, 5, 2), InvoiceItemType.CBA_ADJ, new BigDecimal("231.88")));
        invoiceChecker.checkInvoice(invoices.get(1).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 2), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("9.63")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 2), new LocalDate(2012, 5, 2), InvoiceItemType.CBA_ADJ, new BigDecimal("-9.63")));
        invoiceChecker.checkInvoice(invoices.get(2).getId(), callContext, toBeChecked);
    }

    @Test(groups = "slow")
    public void testMultiplePartialRepairs() throws Exception {
        // We take april as it has 30 days (easier to play with BCD)
        final LocalDate today = new LocalDate(2012, 4, 1);
        final Account account = createAccountWithNonOsgiPaymentMethod(getAccountData(1));

        // Set clock to the initial start date - we implicitly assume here that the account timezone is UTC
        clock.setDay(today);

        final String productName = "Shotgun";
        final BillingPeriod term = BillingPeriod.MONTHLY;
        final String pricelistName = PriceListSet.DEFAULT_PRICELIST_NAME;

        //
        // CREATE SUBSCRIPTION AND EXPECT BOTH EVENTS: NextEvent.CREATE NextEvent.INVOICE
        //
        final DefaultEntitlement bpEntitlement = createBaseEntitlementAndCheckForCompletion(account.getId(), "externalKey", productName, ProductCategory.BASE, term, NextEvent.CREATE, NextEvent.INVOICE);
        assertNotNull(bpEntitlement);

        List<Invoice> invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), callContext);
        assertEquals(invoices.size(), 1);
        ImmutableList<ExpectedInvoiceItemCheck> toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), null, InvoiceItemType.FIXED, BigDecimal.ZERO));
        invoiceChecker.checkInvoice(invoices.get(0).getId(), callContext, toBeChecked);

        // Move the clock to 2012-04-04
        clock.addDays(3);
        assertListenerStatus();

        //
        // Change plan in trial - there is no repair
        //
        changeEntitlementAndCheckForCompletion(bpEntitlement, "Assault-Rifle", term, BillingActionPolicy.IMMEDIATE, NextEvent.CHANGE, NextEvent.INVOICE);

        invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), callContext);
        assertEquals(invoices.size(), 2);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), null, InvoiceItemType.FIXED, BigDecimal.ZERO));
        invoiceChecker.checkInvoice(invoices.get(0).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 4), null, InvoiceItemType.FIXED, BigDecimal.ZERO));
        invoiceChecker.checkInvoice(invoices.get(1).getId(), callContext, toBeChecked);

        //
        // Check we get the first invoice at the phase event
        //
        busHandler.pushExpectedEvents(NextEvent.PHASE, NextEvent.INVOICE, NextEvent.PAYMENT);
        // Move the clock to 2012-05-02
        clock.addDays(28);
        assertListenerStatus();

        invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), callContext);
        assertEquals(invoices.size(), 3);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), null, InvoiceItemType.FIXED, BigDecimal.ZERO));
        invoiceChecker.checkInvoice(invoices.get(0).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 4), null, InvoiceItemType.FIXED, BigDecimal.ZERO));
        invoiceChecker.checkInvoice(invoices.get(1).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("599.95")));
        invoiceChecker.checkInvoice(invoices.get(2).getId(), callContext, toBeChecked);

        //
        // Force another plan change
        //
        // Move the clock to 2012-05-07
        clock.addDays(5);
        changeEntitlementAndCheckForCompletion(bpEntitlement, "Blowdart", term, BillingActionPolicy.IMMEDIATE, NextEvent.CHANGE, NextEvent.INVOICE, NextEvent.INVOICE_ADJUSTMENT);

        invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), callContext);
        assertEquals(invoices.size(), 4);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), null, InvoiceItemType.FIXED, BigDecimal.ZERO));
        invoiceChecker.checkInvoice(invoices.get(0).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 4), null, InvoiceItemType.FIXED, BigDecimal.ZERO));
        invoiceChecker.checkInvoice(invoices.get(1).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("599.95")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 7), new LocalDate(2012, 6, 1), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-483.86")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 7), new LocalDate(2012, 5, 7), InvoiceItemType.CBA_ADJ, new BigDecimal("483.86")));
        invoiceChecker.checkInvoice(invoices.get(2).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 7), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("8.02")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 7), new LocalDate(2012, 5, 7), InvoiceItemType.CBA_ADJ, new BigDecimal("-8.02")));
        invoiceChecker.checkInvoice(invoices.get(3).getId(), callContext, toBeChecked);

        //
        // Force another plan change
        //
        // Move the clock to 2012-05-08
        clock.addDays(1);
        changeEntitlementAndCheckForCompletion(bpEntitlement, "Pistol", term, BillingActionPolicy.IMMEDIATE, NextEvent.CHANGE, NextEvent.INVOICE, NextEvent.INVOICE_ADJUSTMENT);

        invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), callContext);
        assertEquals(invoices.size(), 5);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), null, InvoiceItemType.FIXED, BigDecimal.ZERO));
        invoiceChecker.checkInvoice(invoices.get(0).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 4), null, InvoiceItemType.FIXED, BigDecimal.ZERO));
        invoiceChecker.checkInvoice(invoices.get(1).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("599.95")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 7), new LocalDate(2012, 6, 1), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-483.86")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 7), new LocalDate(2012, 5, 7), InvoiceItemType.CBA_ADJ, new BigDecimal("483.86")));
        invoiceChecker.checkInvoice(invoices.get(2).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 7), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("8.02")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 7), new LocalDate(2012, 5, 7), InvoiceItemType.CBA_ADJ, new BigDecimal("-8.02")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 8), new LocalDate(2012, 6, 1), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-7.69")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 8), new LocalDate(2012, 5, 8), InvoiceItemType.CBA_ADJ, new BigDecimal("7.69")));
        invoiceChecker.checkInvoice(invoices.get(3).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 8), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("23.19")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 8), new LocalDate(2012, 5, 8), InvoiceItemType.CBA_ADJ, new BigDecimal("-23.19")));
        invoiceChecker.checkInvoice(invoices.get(4).getId(), callContext, toBeChecked);

        //
        // Verify the next bcd will happen as expected
        //
        busHandler.pushExpectedEvents(NextEvent.INVOICE);
        // Move the clock to 2012-06-08
        clock.addMonths(1);
        assertListenerStatus();

        invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), callContext);
        assertEquals(invoices.size(), 6);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), null, InvoiceItemType.FIXED, BigDecimal.ZERO));
        invoiceChecker.checkInvoice(invoices.get(0).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 4), null, InvoiceItemType.FIXED, BigDecimal.ZERO));
        invoiceChecker.checkInvoice(invoices.get(1).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("599.95")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 7), new LocalDate(2012, 6, 1), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-483.86")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 7), new LocalDate(2012, 5, 7), InvoiceItemType.CBA_ADJ, new BigDecimal("483.86")));
        invoiceChecker.checkInvoice(invoices.get(2).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 7), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("8.02")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 7), new LocalDate(2012, 5, 7), InvoiceItemType.CBA_ADJ, new BigDecimal("-8.02")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 8), new LocalDate(2012, 6, 1), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-7.69")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 8), new LocalDate(2012, 5, 8), InvoiceItemType.CBA_ADJ, new BigDecimal("7.69")));
        invoiceChecker.checkInvoice(invoices.get(3).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 8), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("23.19")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 8), new LocalDate(2012, 5, 8), InvoiceItemType.CBA_ADJ, new BigDecimal("-23.19")));
        invoiceChecker.checkInvoice(invoices.get(4).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 1), new LocalDate(2012, 7, 1), InvoiceItemType.RECURRING, new BigDecimal("29.95")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 8), new LocalDate(2012, 6, 8), InvoiceItemType.CBA_ADJ, new BigDecimal("-29.95")));
        invoiceChecker.checkInvoice(invoices.get(5).getId(), callContext, toBeChecked);

        //
        // Verify the next month as well
        //
        busHandler.pushExpectedEvents(NextEvent.INVOICE);
        // Move the clock to 2012-07-08
        clock.addMonths(1);
        assertListenerStatus();

        invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), callContext);
        assertEquals(invoices.size(), 7);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), null, InvoiceItemType.FIXED, BigDecimal.ZERO));
        invoiceChecker.checkInvoice(invoices.get(0).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 4), null, InvoiceItemType.FIXED, BigDecimal.ZERO));
        invoiceChecker.checkInvoice(invoices.get(1).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("599.95")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 7), new LocalDate(2012, 6, 1), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-483.86")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 7), new LocalDate(2012, 5, 7), InvoiceItemType.CBA_ADJ, new BigDecimal("483.86")));
        invoiceChecker.checkInvoice(invoices.get(2).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 7), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("8.02")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 7), new LocalDate(2012, 5, 7), InvoiceItemType.CBA_ADJ, new BigDecimal("-8.02")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 8), new LocalDate(2012, 6, 1), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-7.69")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 8), new LocalDate(2012, 5, 8), InvoiceItemType.CBA_ADJ, new BigDecimal("7.69")));
        invoiceChecker.checkInvoice(invoices.get(3).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 8), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("23.19")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 8), new LocalDate(2012, 5, 8), InvoiceItemType.CBA_ADJ, new BigDecimal("-23.19")));
        invoiceChecker.checkInvoice(invoices.get(4).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 1), new LocalDate(2012, 7, 1), InvoiceItemType.RECURRING, new BigDecimal("29.95")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 8), new LocalDate(2012, 6, 8), InvoiceItemType.CBA_ADJ, new BigDecimal("-29.95")));
        invoiceChecker.checkInvoice(invoices.get(5).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 1), new LocalDate(2012, 8, 1), InvoiceItemType.RECURRING, new BigDecimal("29.95")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 8), new LocalDate(2012, 7, 8), InvoiceItemType.CBA_ADJ, new BigDecimal("-29.95")));
        invoiceChecker.checkInvoice(invoices.get(6).getId(), callContext, toBeChecked);
    }

    @Test(groups = "slow")
    public void testPartialRepairWithCompleteRefund() throws Exception {

        // We take april as it has 30 days (easier to play with BCD)
        final LocalDate today = new LocalDate(2012, 4, 1);
        final Account account = createAccountWithNonOsgiPaymentMethod(getAccountData(1));

        // Set clock to the initial start date - we implicitly assume here that the account timezone is UTC
        clock.setDeltaFromReality(today.toDateTimeAtCurrentTime(DateTimeZone.UTC).getMillis() - clock.getUTCNow().getMillis());

        final String productName = "Shotgun";
        final BillingPeriod term = BillingPeriod.ANNUAL;
        final String planSetName = PriceListSet.DEFAULT_PRICELIST_NAME;

        //
        // CREATE SUBSCRIPTION AND EXPECT BOTH EVENTS: NextEvent.CREATE NextEvent.INVOICE
        //
        DefaultEntitlement bpEntitlement = createBaseEntitlementAndCheckForCompletion(account.getId(), "externalKey", productName, ProductCategory.BASE, term, NextEvent.CREATE, NextEvent.INVOICE);
        assertNotNull(bpEntitlement);

        assertEquals(invoiceUserApi.getInvoicesByAccount(account.getId(), callContext).size(), 1);

        assertEquals(bpEntitlement.getSubscriptionBase().getCurrentPlan().getBillingPeriod(), BillingPeriod.ANNUAL);

        // Move out of trials for interesting invoices adjustments
        busHandler.pushExpectedEvents(NextEvent.PHASE, NextEvent.INVOICE, NextEvent.PAYMENT);
        clock.addDays(30);
        assertListenerStatus();

        List<Invoice> invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), callContext);
        assertEquals(invoices.size(), 2);
        ImmutableList<ExpectedInvoiceItemCheck> toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2013, 5, 1), InvoiceItemType.RECURRING, new BigDecimal("2399.95")));
        invoiceChecker.checkInvoice(invoices.get(1).getId(), callContext, toBeChecked);

        //
        // FORCE AN IMMEDIATE CHANGE OF THE BILLING PERIOD
        //
        bpEntitlement = changeEntitlementAndCheckForCompletion(bpEntitlement, productName, BillingPeriod.MONTHLY, BillingActionPolicy.IMMEDIATE, NextEvent.CHANGE, NextEvent.INVOICE, NextEvent.INVOICE_ADJUSTMENT);

        assertEquals(bpEntitlement.getSubscriptionBase().getCurrentPlan().getBillingPeriod(), BillingPeriod.MONTHLY);

        invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), callContext);
        assertEquals(invoices.size(), 3);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2013, 5, 1), InvoiceItemType.RECURRING, new BigDecimal("2399.95")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2013, 5, 1), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-2399.95")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 5, 1), InvoiceItemType.CBA_ADJ, new BigDecimal("2399.95")));
        invoiceChecker.checkInvoice(invoices.get(1).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("249.95")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 5, 1), InvoiceItemType.CBA_ADJ, new BigDecimal("-249.95")));
        invoiceChecker.checkInvoice(invoices.get(2).getId(), callContext, toBeChecked);

        busHandler.pushExpectedEvents(NextEvent.INVOICE);
        clock.addMonths(1);
        assertListenerStatus();

        invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), callContext);
        assertEquals(invoices.size(), 4);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2013, 5, 1), InvoiceItemType.RECURRING, new BigDecimal("2399.95")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2013, 5, 1), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-2399.95")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 5, 1), InvoiceItemType.CBA_ADJ, new BigDecimal("2399.95")));
        invoiceChecker.checkInvoice(invoices.get(1).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("249.95")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 5, 1), InvoiceItemType.CBA_ADJ, new BigDecimal("-249.95")));
        invoiceChecker.checkInvoice(invoices.get(2).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 1), new LocalDate(2012, 7, 1), InvoiceItemType.RECURRING, new BigDecimal("249.95")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 1), new LocalDate(2012, 6, 1), InvoiceItemType.CBA_ADJ, new BigDecimal("-249.95")));
        invoiceChecker.checkInvoice(invoices.get(3).getId(), callContext, toBeChecked);

        busHandler.pushExpectedEvents(NextEvent.INVOICE);
        clock.addMonths(1);
        assertListenerStatus();

        invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), callContext);
        assertEquals(invoices.size(), 5);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2013, 5, 1), InvoiceItemType.RECURRING, new BigDecimal("2399.95")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2013, 5, 1), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-2399.95")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 5, 1), InvoiceItemType.CBA_ADJ, new BigDecimal("2399.95")));
        invoiceChecker.checkInvoice(invoices.get(1).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("249.95")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 5, 1), InvoiceItemType.CBA_ADJ, new BigDecimal("-249.95")));
        invoiceChecker.checkInvoice(invoices.get(2).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 1), new LocalDate(2012, 7, 1), InvoiceItemType.RECURRING, new BigDecimal("249.95")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 1), new LocalDate(2012, 6, 1), InvoiceItemType.CBA_ADJ, new BigDecimal("-249.95")));
        invoiceChecker.checkInvoice(invoices.get(3).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 1), new LocalDate(2012, 8, 1), InvoiceItemType.RECURRING, new BigDecimal("249.95")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 1), new LocalDate(2012, 7, 1), InvoiceItemType.CBA_ADJ, new BigDecimal("-249.95")));
        invoiceChecker.checkInvoice(invoices.get(4).getId(), callContext, toBeChecked);
    }

    @Test(groups = "slow")
    public void testInvoiceLogicWithFullRepairFollowedByPartialRepair() throws Exception {

        // START TEST WITH OLD FULL_REPAIR LOGIC
        invoiceGenerator.setDefaultRepairLogic(REPAIR_INVOICE_LOGIC.FULL_REPAIR);

        final LocalDate today = new LocalDate(2012, 4, 1);
        final Account account = createAccountWithNonOsgiPaymentMethod(getAccountData(1));

        // Set clock to the initial start date - we implicitly assume here that the account timezone is UTC
        clock.setDeltaFromReality(today.toDateTimeAtCurrentTime(DateTimeZone.UTC).getMillis() - clock.getUTCNow().getMillis());

        final String productName = "Shotgun";
        final BillingPeriod term = BillingPeriod.ANNUAL;
        final String planSetName = PriceListSet.DEFAULT_PRICELIST_NAME;

        //
        // CREATE SUBSCRIPTION AND EXPECT BOTH EVENTS: NextEvent.CREATE NextEvent.INVOICE
        //
        DefaultEntitlement bpEntitlement = createBaseEntitlementAndCheckForCompletion(account.getId(), "externalKey", productName, ProductCategory.BASE, term, NextEvent.CREATE, NextEvent.INVOICE);
        assertNotNull(bpEntitlement);

        assertEquals(invoiceUserApi.getInvoicesByAccount(account.getId(), callContext).size(), 1);

        assertEquals(bpEntitlement.getSubscriptionBase().getCurrentPlan().getBillingPeriod(), BillingPeriod.ANNUAL);

        // Move out of trials for interesting invoices adjustments
        busHandler.pushExpectedEvents(NextEvent.PHASE, NextEvent.INVOICE, NextEvent.PAYMENT);
        clock.addDays(40);
        assertListenerStatus();

        List<Invoice> invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), callContext);
        assertEquals(invoices.size(), 2);
        ImmutableList<ExpectedInvoiceItemCheck> toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2013, 5, 1), InvoiceItemType.RECURRING, new BigDecimal("2399.95")));
        invoiceChecker.checkInvoice(invoices.get(1).getId(), callContext, toBeChecked);

        //
        // FORCE AN IMMEDIATE CHANGE OF THE BILLING PERIOD
        //
        changeEntitlementAndCheckForCompletion(bpEntitlement, productName, BillingPeriod.MONTHLY, BillingActionPolicy.IMMEDIATE, NextEvent.CHANGE, NextEvent.INVOICE, NextEvent.INVOICE_ADJUSTMENT);

        assertListenerStatus();

        invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), callContext);
        assertEquals(invoices.size(), 3);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2013, 5, 1), InvoiceItemType.RECURRING, new BigDecimal("2399.95")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2013, 5, 1), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-2399.95")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 11), new LocalDate(2012, 5, 11), InvoiceItemType.CBA_ADJ, new BigDecimal("2399.95")));
        invoiceChecker.checkInvoice(invoices.get(1).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 5, 11), InvoiceItemType.RECURRING, new BigDecimal("65.76")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 11), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("169.32")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 11), new LocalDate(2012, 5, 11), InvoiceItemType.CBA_ADJ, new BigDecimal("-235.08")));
        invoiceChecker.checkInvoice(invoices.get(2).getId(), callContext, toBeChecked);

        // NOW SWITCH BACK TO PARTIAL REPAIR LOGIC AND GENERATE NEXT 2 INVOICES
        invoiceGenerator.setDefaultRepairLogic(REPAIR_INVOICE_LOGIC.PARTIAL_REPAIR);

        busHandler.pushExpectedEvents(NextEvent.INVOICE);
        clock.addMonths(1);
        assertListenerStatus();

        invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), callContext);
        assertEquals(invoices.size(), 4);

        // RECHECK PREVIOUS INVOICE DID NOT CHANGE
        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2013, 5, 1), InvoiceItemType.RECURRING, new BigDecimal("2399.95")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2013, 5, 1), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-2399.95")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 11), new LocalDate(2012, 5, 11), InvoiceItemType.CBA_ADJ, new BigDecimal("2399.95")));
        invoiceChecker.checkInvoice(invoices.get(1).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 5, 11), InvoiceItemType.RECURRING, new BigDecimal("65.76")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 11), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("169.32")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 11), new LocalDate(2012, 5, 11), InvoiceItemType.CBA_ADJ, new BigDecimal("-235.08")));
        invoiceChecker.checkInvoice(invoices.get(2).getId(), callContext, toBeChecked);

        // AND THEN CHECK NEW INVOICE
        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 1), new LocalDate(2012, 7, 1), InvoiceItemType.RECURRING, new BigDecimal("249.95")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 11), new LocalDate(2012, 6, 11), InvoiceItemType.CBA_ADJ, new BigDecimal("-249.95")));
        invoiceChecker.checkInvoice(invoices.get(3).getId(), callContext, toBeChecked);

        busHandler.pushExpectedEvents(NextEvent.INVOICE);
        clock.addMonths(1);
        assertListenerStatus();

        invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), callContext);
        assertEquals(invoices.size(), 5);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 1), new LocalDate(2012, 8, 1), InvoiceItemType.RECURRING, new BigDecimal("249.95")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 11), new LocalDate(2012, 7, 11), InvoiceItemType.CBA_ADJ, new BigDecimal("-249.95")));
        invoiceChecker.checkInvoice(invoices.get(4).getId(), callContext, toBeChecked);
    }

    @Test(groups = "slow")
    public void testInvoiceLogicWithFullRepairFollowedByPartialRepairWithItemAdjustment() throws Exception {

        // START TEST WITH OLD FULL_REPAIR LOGIC
        invoiceGenerator.setDefaultRepairLogic(REPAIR_INVOICE_LOGIC.FULL_REPAIR);

        final LocalDate today = new LocalDate(2012, 4, 1);
        final Account account = createAccountWithNonOsgiPaymentMethod(getAccountData(1));

        // Set clock to the initial start date - we implicitly assume here that the account timezone is UTC
        clock.setDeltaFromReality(today.toDateTimeAtCurrentTime(DateTimeZone.UTC).getMillis() - clock.getUTCNow().getMillis());

        final String productName = "Shotgun";
        final BillingPeriod term = BillingPeriod.ANNUAL;
        final String planSetName = PriceListSet.DEFAULT_PRICELIST_NAME;

        //
        // CREATE SUBSCRIPTION AND EXPECT BOTH EVENTS: NextEvent.CREATE NextEvent.INVOICE
        //
        DefaultEntitlement bpEntitlement = createBaseEntitlementAndCheckForCompletion(account.getId(), "externalKey", productName, ProductCategory.BASE, term, NextEvent.CREATE, NextEvent.INVOICE);
        assertNotNull(bpEntitlement);
        assertEquals(invoiceUserApi.getInvoicesByAccount(account.getId(), callContext).size(), 1);

        assertEquals(bpEntitlement.getSubscriptionBase().getCurrentPlan().getBillingPeriod(), BillingPeriod.ANNUAL);

        // Move out of trials for interesting invoices adjustments
        busHandler.pushExpectedEvents(NextEvent.PHASE, NextEvent.INVOICE, NextEvent.PAYMENT);
        clock.addDays(40);
        assertListenerStatus();

        List<Invoice> invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), callContext);
        assertEquals(invoices.size(), 2);
        ImmutableList<ExpectedInvoiceItemCheck> toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2013, 5, 1), InvoiceItemType.RECURRING, new BigDecimal("2399.95")));
        invoiceChecker.checkInvoice(invoices.get(1).getId(), callContext, toBeChecked);

        //
        // ITEM ADJUSTMENT PRIOR TO DOING THE REPAIR
        //
        final Invoice invoice1 = invoices.get(1);
        final List<Payment> payments = paymentApi.getAccountPayments(account.getId(), callContext);
        final ExpectedPaymentCheck expectedPaymentCheck = new ExpectedPaymentCheck(clock.getUTCNow().toLocalDate(), new BigDecimal("2399.95"), PaymentStatus.SUCCESS, invoice1.getId(), Currency.USD);
        final Payment payment1 = payments.get(0);

        final Map<UUID, BigDecimal> iias = new HashMap<UUID, BigDecimal>();
        iias.put(invoice1.getInvoiceItems().get(0).getId(), new BigDecimal("10.00"));
        busHandler.pushExpectedEvents(NextEvent.INVOICE_ADJUSTMENT);
        paymentApi.createRefundWithItemsAdjustments(account, payment1.getId(), iias, callContext);
        assertListenerStatus();

        invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), callContext);
        assertEquals(invoices.size(), 2);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2013, 5, 1), InvoiceItemType.RECURRING, new BigDecimal("2399.95")),
                // TODO SETPH the  ITEM_ADJ seems to be created with the callcontext getCreatedDate()
                new ExpectedInvoiceItemCheck(callContext.getCreatedDate().toLocalDate(), callContext.getCreatedDate().toLocalDate(), InvoiceItemType.ITEM_ADJ, new BigDecimal("-10.00")));
        invoiceChecker.checkInvoice(invoices.get(1).getId(), callContext, toBeChecked);

        //
        // FORCE AN IMMEDIATE CHANGE OF THE BILLING PERIOD
        //
        changeEntitlementAndCheckForCompletion(bpEntitlement, productName, BillingPeriod.MONTHLY, BillingActionPolicy.IMMEDIATE, NextEvent.CHANGE, NextEvent.INVOICE, NextEvent.INVOICE_ADJUSTMENT);

        invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), callContext);
        assertEquals(invoices.size(), 3);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2013, 5, 1), InvoiceItemType.RECURRING, new BigDecimal("2399.95")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2013, 5, 1), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-2389.95")),
                new ExpectedInvoiceItemCheck(callContext.getCreatedDate().toLocalDate(), callContext.getCreatedDate().toLocalDate(), InvoiceItemType.ITEM_ADJ, new BigDecimal("-10.00")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 11), new LocalDate(2012, 5, 11), InvoiceItemType.CBA_ADJ, new BigDecimal("2389.95")));
        invoiceChecker.checkInvoice(invoices.get(1).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 5, 11), InvoiceItemType.RECURRING, new BigDecimal("65.76")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 11), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("169.32")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 11), new LocalDate(2012, 5, 11), InvoiceItemType.CBA_ADJ, new BigDecimal("-235.08")));
        invoiceChecker.checkInvoice(invoices.get(2).getId(), callContext, toBeChecked);

        // NOW SWITCH BACK TO PARTIAL REPAIR LOGIC AND GENERATE NEXT 2 INVOICES
        invoiceGenerator.setDefaultRepairLogic(REPAIR_INVOICE_LOGIC.PARTIAL_REPAIR);

        busHandler.pushExpectedEvents(NextEvent.INVOICE);
        clock.addMonths(1);
        assertListenerStatus();

        invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), callContext);
        assertEquals(invoices.size(), 4);

        // RECHECK PREVIOUS INVOICE DID NOT CHANGE
        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2013, 5, 1), InvoiceItemType.RECURRING, new BigDecimal("2399.95")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2013, 5, 1), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-2389.95")),
                new ExpectedInvoiceItemCheck(callContext.getCreatedDate().toLocalDate(), callContext.getCreatedDate().toLocalDate(), InvoiceItemType.ITEM_ADJ, new BigDecimal("-10.00")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 11), new LocalDate(2012, 5, 11), InvoiceItemType.CBA_ADJ, new BigDecimal("2389.95")));
        invoiceChecker.checkInvoice(invoices.get(1).getId(), callContext, toBeChecked);

        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 5, 11), InvoiceItemType.RECURRING, new BigDecimal("65.76")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 11), new LocalDate(2012, 6, 1), InvoiceItemType.RECURRING, new BigDecimal("169.32")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 11), new LocalDate(2012, 5, 11), InvoiceItemType.CBA_ADJ, new BigDecimal("-235.08")));
        invoiceChecker.checkInvoice(invoices.get(2).getId(), callContext, toBeChecked);

        // AND THEN CHECK NEW INVOICE
        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 1), new LocalDate(2012, 7, 1), InvoiceItemType.RECURRING, new BigDecimal("249.95")),
                new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 11), new LocalDate(2012, 6, 11), InvoiceItemType.CBA_ADJ, new BigDecimal("-249.95")));
        invoiceChecker.checkInvoice(invoices.get(3).getId(), callContext, toBeChecked);
    }

    @Test(groups = "slow")
    public void testRepairWithFullItemAdjustment() throws Exception {

        invoiceGenerator.setDefaultRepairLogic(REPAIR_INVOICE_LOGIC.PARTIAL_REPAIR);

        final LocalDate today = new LocalDate(2013, 7, 19);
        final Account account = createAccountWithNonOsgiPaymentMethod(getAccountData(1));

        // Set clock to the initial start date - we implicitly assume here that the account timezone is UTC
        clock.setDeltaFromReality(today.toDateTimeAtCurrentTime(DateTimeZone.UTC).getMillis() - clock.getUTCNow().getMillis());

        final String productName = "Shotgun";
        final BillingPeriod term = BillingPeriod.ANNUAL;
        final String planSetName = PriceListSet.DEFAULT_PRICELIST_NAME;

        //
        // CREATE SUBSCRIPTION AND EXPECT BOTH EVENTS: NextEvent.CREATE NextEvent.INVOICE
        //
        DefaultEntitlement bpEntitlement = createBaseEntitlementAndCheckForCompletion(account.getId(), "externalKey", productName, ProductCategory.BASE, term, NextEvent.CREATE, NextEvent.INVOICE);
        assertNotNull(bpEntitlement);
        assertEquals(invoiceUserApi.getInvoicesByAccount(account.getId(), callContext).size(), 1);

        assertEquals(bpEntitlement.getSubscriptionBase().getCurrentPlan().getBillingPeriod(), BillingPeriod.ANNUAL);

        // Move out of trials for interesting invoices adjustments
        busHandler.pushExpectedEvents(NextEvent.PHASE, NextEvent.INVOICE, NextEvent.PAYMENT);
        clock.addDays(30);
        assertListenerStatus();

        List<Invoice> invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), callContext);
        assertEquals(invoices.size(), 2);
        ImmutableList<ExpectedInvoiceItemCheck> toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2013, 8, 18), new LocalDate(2014, 8, 18), InvoiceItemType.RECURRING, new BigDecimal("2399.95")));
        invoiceChecker.checkInvoice(invoices.get(1).getId(), callContext, toBeChecked);


        // Move clock to 2013-09-17
        clock.addDays(30);
        busHandler.pushExpectedEvents(NextEvent.BLOCK, NextEvent.CANCEL, NextEvent.INVOICE_ADJUSTMENT);
        bpEntitlement.cancelEntitlementWithPolicyOverrideBillingPolicy(EntitlementActionPolicy.IMMEDIATE, BillingActionPolicy.IMMEDIATE, callContext);
        assertListenerStatus();

        invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), callContext);
        assertEquals(invoices.size(), 2);
        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2013, 8, 18), new LocalDate(2014, 8, 18), InvoiceItemType.RECURRING, new BigDecimal("2399.95")),
                new ExpectedInvoiceItemCheck(new LocalDate(2013, 9, 17), new LocalDate(2014, 8, 18), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-2202.67")),
                new ExpectedInvoiceItemCheck(new LocalDate(2013, 9, 17), new LocalDate(2013, 9, 17), InvoiceItemType.CBA_ADJ, new BigDecimal("2202.67")));
        invoiceChecker.checkInvoice(invoices.get(1).getId(), callContext, toBeChecked);



        //
        // ITEM ADJUSTMENT PRIOR TO DOING THE REPAIR
        //
        final Invoice invoice1 = invoices.get(1);
        final List<Payment> payments = paymentApi.getAccountPayments(account.getId(), callContext);
        final ExpectedPaymentCheck expectedPaymentCheck = new ExpectedPaymentCheck(clock.getUTCNow().toLocalDate(), new BigDecimal("2399.95"), PaymentStatus.SUCCESS, invoice1.getId(), Currency.USD);
        final Payment payment1 = payments.get(0);

        final Map<UUID, BigDecimal> iias = new HashMap<UUID, BigDecimal>();
        iias.put(invoice1.getInvoiceItems().get(0).getId(), new BigDecimal("197.28"));
        busHandler.pushExpectedEvents(NextEvent.INVOICE_ADJUSTMENT);
        paymentApi.createRefundWithItemsAdjustments(account, payment1.getId(), iias, callContext);
        assertListenerStatus();

        try {
            invoiceUserApi.triggerInvoiceGeneration(account.getId(), new LocalDate(clock.getUTCToday()), false, callContext);
            Assert.fail("Should not gnenerated an new invoice");
        } catch (InvoiceApiException e) {
            Assert.assertEquals(e.getCode(), ErrorCode.INVOICE_NOTHING_TO_DO.getCode());
        }
    }

    //
    // This is the exact same test as testRepairWithFullItemAdjustment except we now only do a partial item adjustment.
    // The invoice code will NOT reinvoice for the remaining part as the item was both repaired and adjusted and so
    // it does not have enough info to understand what should be re-invoiced.
    //
    // Note that there is no real use case for those scenarii in real life
    //
    @Test(groups = "slow")
    public void testRepairWithPartialItemAdjustment() throws Exception {

        invoiceGenerator.setDefaultRepairLogic(REPAIR_INVOICE_LOGIC.PARTIAL_REPAIR);

        final LocalDate today = new LocalDate(2013, 7, 19);
        final Account account = createAccountWithNonOsgiPaymentMethod(getAccountData(1));

        // Set clock to the initial start date - we implicitly assume here that the account timezone is UTC
        clock.setDeltaFromReality(today.toDateTimeAtCurrentTime(DateTimeZone.UTC).getMillis() - clock.getUTCNow().getMillis());

        final String productName = "Shotgun";
        final BillingPeriod term = BillingPeriod.ANNUAL;
        final String planSetName = PriceListSet.DEFAULT_PRICELIST_NAME;

        //
        // CREATE SUBSCRIPTION AND EXPECT BOTH EVENTS: NextEvent.CREATE NextEvent.INVOICE
        //
        DefaultEntitlement bpEntitlement = createBaseEntitlementAndCheckForCompletion(account.getId(), "externalKey", productName, ProductCategory.BASE, term, NextEvent.CREATE, NextEvent.INVOICE);
        assertNotNull(bpEntitlement);
        assertEquals(invoiceUserApi.getInvoicesByAccount(account.getId(), callContext).size(), 1);

        assertEquals(bpEntitlement.getSubscriptionBase().getCurrentPlan().getBillingPeriod(), BillingPeriod.ANNUAL);

        // Move out of trials for interesting invoices adjustments
        busHandler.pushExpectedEvents(NextEvent.PHASE, NextEvent.INVOICE, NextEvent.PAYMENT);
        clock.addDays(30);
        assertListenerStatus();

        List<Invoice> invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), callContext);
        assertEquals(invoices.size(), 2);
        ImmutableList<ExpectedInvoiceItemCheck> toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2013, 8, 18), new LocalDate(2014, 8, 18), InvoiceItemType.RECURRING, new BigDecimal("2399.95")));
        invoiceChecker.checkInvoice(invoices.get(1).getId(), callContext, toBeChecked);


        // Move clock to 2013-09-17
        clock.addDays(30);
        busHandler.pushExpectedEvents(NextEvent.BLOCK, NextEvent.CANCEL, NextEvent.INVOICE_ADJUSTMENT);
        bpEntitlement.cancelEntitlementWithPolicyOverrideBillingPolicy(EntitlementActionPolicy.IMMEDIATE, BillingActionPolicy.IMMEDIATE, callContext);
        assertListenerStatus();

        invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), callContext);
        assertEquals(invoices.size(), 2);
        toBeChecked = ImmutableList.<ExpectedInvoiceItemCheck>of(
                new ExpectedInvoiceItemCheck(new LocalDate(2013, 8, 18), new LocalDate(2014, 8, 18), InvoiceItemType.RECURRING, new BigDecimal("2399.95")),
                new ExpectedInvoiceItemCheck(new LocalDate(2013, 9, 17), new LocalDate(2014, 8, 18), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-2202.67")),
                new ExpectedInvoiceItemCheck(new LocalDate(2013, 9, 17), new LocalDate(2013, 9, 17), InvoiceItemType.CBA_ADJ, new BigDecimal("2202.67")));
        invoiceChecker.checkInvoice(invoices.get(1).getId(), callContext, toBeChecked);



        //
        // ITEM ADJUSTMENT PRIOR TO DOING THE REPAIR
        //
        final Invoice invoice1 = invoices.get(1);
        final List<Payment> payments = paymentApi.getAccountPayments(account.getId(), callContext);
        final ExpectedPaymentCheck expectedPaymentCheck = new ExpectedPaymentCheck(clock.getUTCNow().toLocalDate(), new BigDecimal("2399.95"), PaymentStatus.SUCCESS, invoice1.getId(), Currency.USD);
        final Payment payment1 = payments.get(0);

        final Map<UUID, BigDecimal> iias = new HashMap<UUID, BigDecimal>();
        iias.put(invoice1.getInvoiceItems().get(0).getId(), new BigDecimal("100.00"));
        busHandler.pushExpectedEvents(NextEvent.INVOICE_ADJUSTMENT);
        paymentApi.createRefundWithItemsAdjustments(account, payment1.getId(), iias, callContext);
        assertListenerStatus();

        try {
            invoiceUserApi.triggerInvoiceGeneration(account.getId(), new LocalDate(clock.getUTCToday()), false, callContext);
            Assert.fail("Should not gnenerated an new invoice");
        } catch (InvoiceApiException e) {
            Assert.assertEquals(e.getCode(), ErrorCode.INVOICE_NOTHING_TO_DO.getCode());
        }
    }

}
